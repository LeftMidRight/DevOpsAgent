"""Ragas 离线评估器。

--mode pure-rag（默认）：
    读取 PureRagDatasetExportIT 导出的 JSONL。
    指标：Context Precision / Context Recall / Faithfulness /
    Answer Relevancy / Factual Correctness。
    no-result 不计算 Context Precision/Recall，改用拒答 rubric。

--mode agent：
    读取 RagasDatasetExportIT 导出的完整 Agent JSONL。
    只保留原有四指标，不与 Pure RAG 分数混用。

失败 case（error 或空 response，或 Judge 全 NaN）不计入平均值。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

import pandas as pd
from openai import AsyncOpenAI
from ragas import EvaluationDataset, SingleTurnSample, evaluate
from ragas.embeddings import OpenAIEmbeddings
from ragas.llms import llm_factory
from ragas.metrics.collections import (
    AnswerRelevancy,
    ContextPrecision,
    ContextRecall,
    DomainSpecificRubrics,
    Faithfulness,
    FactualCorrectness,
)
from ragas.run_config import RunConfig

from config import load_embedding_config, load_judge_config, redacted

EVAL_DIR = Path(__file__).resolve().parent
JUDGE_TEMPERATURE = 0.0

ABSTENTION_RUBRIC = {
    "score1_description": "编造了知识库中不存在的具体规程或事实，或假装找到了相关资料。",
    "score2_description": "没有明确拒答，回答含糊，或大部分内容没有依据。",
    "score3_description": "提到资料不足，但仍补充了上下文不支持的具体做法。",
    "score4_description": "明确说明知识库没有足够信息，只有轻微无关发挥。",
    "score5_description": "明确说明无法从知识库回答，且没有编造任何具体规程或事实。",
}

# 0-1 指标。拒答 rubric 是 1-5，只出现在 no-result 分组，不并进 all。
ZERO_ONE_METRICS = [
    "faithfulness",
    "answer_relevancy",
    "context_precision",
    "context_recall",
    "factual_correctness",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Ragas eval over a Java-exported dataset")
    parser.add_argument("--mode", choices=["pure-rag", "agent"], default="pure-rag")
    parser.add_argument("--input", type=Path, default=None, help="Java 导出 JSONL")
    parser.add_argument("--output", type=Path, default=None, help="case 级 JSON 结果")
    parser.add_argument("--report", type=Path, default=None, help="Markdown 报告路径")
    parser.add_argument("--ids", default=None, help="逗号分隔的 case id 子集")
    parser.add_argument("--timeout", type=int, default=300, help="单 case 超时（秒）")
    parser.add_argument("--max-retries", type=int, default=5, help="Judge 调用最大重试")
    args = parser.parse_args()
    if args.mode == "pure-rag":
        args.input = args.input or (EVAL_DIR / "input" / "pure-rag-eval-dataset.jsonl")
        args.output = args.output or (EVAL_DIR / "output" / "pure-rag-eval-result.json")
        args.report = args.report or (EVAL_DIR.parent.parent / "docs" / "pure-rag-eval-report.md")
    else:
        args.input = args.input or (EVAL_DIR / "input" / "ragas-eval-dataset.jsonl")
        args.output = args.output or (EVAL_DIR / "output" / "ragas-eval-result.json")
        args.report = args.report or (EVAL_DIR.parent.parent / "docs" / "ragas-eval-report.md")
    return args


def load_cases(path: Path, mode: str) -> list[dict]:
    required = ["id", "user_input", "response", "retrieved_contexts"]
    if mode == "pure-rag":
        required += ["reference", "reference_contexts", "reference_context_ids"]
    cases = []
    with path.open(encoding="utf-8") as fh:
        for line_no, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as e:
                raise SystemExit(f"第 {line_no} 行不是合法 JSON: {e}")
            missing = [k for k in required if k not in record]
            if missing:
                raise SystemExit(f"第 {line_no} 行缺少字段: {missing}")
            cases.append(record)
    return cases


def build_samples(cases: list[dict]) -> list[SingleTurnSample]:
    fields = getattr(SingleTurnSample, "model_fields", {})
    samples = []
    for case in cases:
        payload = {
            "user_input": case["user_input"],
            "retrieved_contexts": list(case.get("retrieved_contexts") or []),
            "response": case.get("response") or "",
            "reference": case.get("reference"),
        }
        if "reference_contexts" in fields and case.get("reference_contexts") is not None:
            payload["reference_contexts"] = list(case["reference_contexts"])
        samples.append(SingleTurnSample(**payload))
    return samples


def summarize(df: pd.DataFrame, metrics: list[str]) -> dict:
    summary = {}
    for metric in metrics:
        values = df[metric].dropna() if metric in df.columns else pd.Series(dtype=float)
        if values.empty:
            summary[metric] = None
        else:
            summary[metric] = {
                "mean": round(float(values.mean()), 4),
                "median": round(float(values.median()), 4),
                "p90": round(float(values.quantile(0.9)), 4),
                "n": int(values.count()),
            }
    return summary


def run_group(cases: list[dict], metrics: list, run_config: RunConfig):
    dataset = EvaluationDataset(samples=build_samples(cases))
    result = evaluate(
        dataset,
        metrics=metrics,
        run_config=run_config,
        raise_exceptions=False,
        show_progress=True,
    )
    df = result.to_pandas()
    df.insert(0, "case_id", [case["id"] for case in cases])
    return df, result


def is_run_failure(case: dict) -> bool:
    if case.get("error"):
        return True
    return not str(case.get("response") or "").strip()


def main() -> int:
    args = parse_args()
    cases = load_cases(args.input, args.mode)
    if args.ids:
        wanted = {i.strip() for i in args.ids.split(",") if i.strip()}
        cases = [c for c in cases if c["id"] in wanted]
    if not cases:
        raise SystemExit("没有可评估的 case")

    runnable = [c for c in cases if not is_run_failure(c)]
    blocked = [c for c in cases if is_run_failure(c)]

    judge = load_judge_config()
    embedding = load_embedding_config()
    judge_llm = llm_factory(
        judge.model,
        provider="openai",
        client=AsyncOpenAI(api_key=judge.api_key, base_url=judge.base_url, timeout=120.0),
        temperature=JUDGE_TEMPERATURE,
    )
    evaluator_embedding = OpenAIEmbeddings(
        client=AsyncOpenAI(api_key=embedding.api_key, base_url=embedding.base_url, timeout=120.0),
        model=embedding.model,
    )
    run_config = RunConfig(timeout=args.timeout, max_retries=args.max_retries, max_wait=60)

    faithfulness = Faithfulness(llm=judge_llm)
    answer_relevancy = AnswerRelevancy(llm=judge_llm, embeddings=evaluator_embedding)
    context_precision = ContextPrecision(llm=judge_llm)
    context_recall = ContextRecall(llm=judge_llm)
    factual_correctness = FactualCorrectness(llm=judge_llm) if args.mode == "pure-rag" else None
    abstention = None
    if args.mode == "pure-rag":
        abstention = DomainSpecificRubrics(
            llm=judge_llm,
            with_reference=True,
            rubrics=ABSTENTION_RUBRIC,
        )
        try:
            abstention.name = "abstention"
        except Exception:
            pass

    answerable = [c for c in runnable if not c.get("no_result")]
    no_result = [c for c in runnable if c.get("no_result")]

    if args.mode == "pure-rag":
        answerable_metrics = [
            faithfulness,
            answer_relevancy,
            context_precision,
            context_recall,
            factual_correctness,
        ]
        no_result_metrics = [faithfulness, answer_relevancy, abstention]
    else:
        answerable_metrics = [faithfulness, answer_relevancy, context_precision, context_recall]
        no_result_metrics = [faithfulness, answer_relevancy]

    frames = {}
    totals = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0, "cost": None}

    def add_totals(result) -> None:
        usage = result.total_tokens() if callable(getattr(result, "total_tokens", None)) else {}
        if isinstance(usage, dict):
            for key in ("prompt_tokens", "completion_tokens", "total_tokens"):
                totals[key] += int(usage.get(key) or 0)
        try:
            cost = result.total_cost()
            if cost is not None:
                totals["cost"] = (totals["cost"] or 0.0) + float(cost)
        except Exception:
            pass

    if answerable:
        df, result = run_group(answerable, answerable_metrics, run_config)
        frames["answerable"] = df
        add_totals(result)
    if no_result:
        df, result = run_group(no_result, no_result_metrics, run_config)
        frames["no-result"] = df
        add_totals(result)

    def names_of(metrics: list) -> list[str]:
        return [m.name for m in metrics]

    case_rows = []
    for case in blocked:
        case_rows.append({
            "id": case["id"],
            "group": "no-result" if case.get("no_result") else "answerable",
            "user_input": case.get("user_input", ""),
            "status": "failed",
            "error": case.get("error") or "empty response",
        })

    for group, df in frames.items():
        metric_names = names_of(answerable_metrics if group == "answerable" else no_result_metrics)
        for _, row in df.iterrows():
            scores = {}
            failed = True
            for name in metric_names:
                value = row.get(name)
                if value is None or (isinstance(value, float) and math.isnan(value)):
                    scores[name] = None
                else:
                    scores[name] = round(float(value), 4)
                    failed = False
            case_rows.append({
                "id": row["case_id"],
                "group": group,
                "user_input": row.get("user_input", ""),
                **scores,
                "status": "failed" if failed else "ok",
            })

    summary = {}
    for group, df in frames.items():
        summary[group] = summarize(
            df, names_of(answerable_metrics if group == "answerable" else no_result_metrics)
        )

    shared = [name for name in ZERO_ONE_METRICS if any(name in df.columns for df in frames.values())]
    pieces = []
    for group, df in frames.items():
        columns = ["case_id"] + [name for name in shared if name in df.columns]
        pieces.append(df[columns])
    all_df = pd.concat(pieces, ignore_index=True) if pieces else pd.DataFrame()
    summary["all"] = summarize(all_df, shared)

    failed_cases = [row["id"] for row in case_rows if row["status"] == "failed"]
    dataset_hash = hashlib.sha256(args.input.read_bytes()).hexdigest()
    output = {
        "run": {
            "mode": args.mode,
            "judge": redacted(judge),
            "embedding": redacted(embedding),
            "dataset": str(args.input),
            "dataset_sha256": dataset_hash,
            "dataset_case_count": len(cases),
            "answerable_count": sum(1 for c in cases if not c.get("no_result")),
            "no_result_count": sum(1 for c in cases if c.get("no_result")),
            "blocked_count": len(blocked),
            "failed_count": len(failed_cases),
            "judge_temperature": JUDGE_TEMPERATURE,
            "usage": totals,
            "notes": {
                "abstention": "1-5 rubric, no-result only, excluded from the all average",
                "failed_cases": "excluded from metric means",
            },
        },
        "summary": summary,
        "cases": case_rows,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    write_report(args.report, output)

    print(f"[ragas-eval] mode={args.mode} cases={len(case_rows)} failed={len(failed_cases)}")
    print(f"[ragas-eval] json:  {args.output}")
    print(f"[ragas-eval] report: {args.report}")
    if failed_cases:
        print(f"[ragas-eval] failed cases: {failed_cases}")
    return 0


def write_report(path: Path, result: dict) -> None:
    run = result["run"]
    summary = result["summary"]
    cases = result["cases"]
    title = "Pure RAG Eval Report" if run["mode"] == "pure-rag" else "Agent Eval Report"
    lines = [
        f"# {title}",
        "",
        "本报告只评价一种系统。Pure RAG 与 Agent Eval 的分数不能互相比较或合并。",
        "",
        "## Run Metadata",
        "",
        f"- Mode: `{run['mode']}`",
        f"- Judge model: `{run['judge'].get('model')}` (provider: {run['judge'].get('provider')})",
        f"- Evaluator embedding: `{run['embedding'].get('model')}`",
        f"- Judge temperature: {run['judge_temperature']}",
        f"- Dataset: `{run['dataset']}`",
        f"- Dataset sha256: `{run['dataset_sha256']}`",
        f"- Case count: {run['dataset_case_count']} "
        f"(answerable={run['answerable_count']}, no-result={run['no_result_count']})",
        f"- Run failures excluded before judging: {run['blocked_count']}",
        f"- Failed cases: {run['failed_count']}",
        f"- Token usage: {run['usage']}",
        "",
        "## Summary",
        "",
        "no-result 不计算 Context Precision / Context Recall。",
        "`abstention` 为 1-5 分，不进入 all 平均。其余指标为 0-1，失败 case 不进入平均。",
        "",
        "| Group | Metric | Mean | Median | P90 | N |",
        "|---|---|---|---|---|---|",
    ]
    for group in ("answerable", "no-result", "all"):
        if group not in summary:
            continue
        for metric, stats in summary[group].items():
            if stats is None:
                lines.append(f"| {group} | {metric} | n/a | n/a | n/a | 0 |")
            else:
                lines.append(
                    f"| {group} | {metric} | {stats['mean']:.4f} | {stats['median']:.4f} "
                    f"| {stats['p90']:.4f} | {stats['n']} |"
                )

    lines += ["", "## Worst Cases", ""]
    metric_names = []
    for group in ("answerable", "no-result"):
        for metric in summary.get(group, {}):
            if metric not in metric_names:
                metric_names.append(metric)
    for metric in metric_names:
        ranked = [c for c in cases if isinstance(c.get(metric), (int, float))]
        ranked.sort(key=lambda c: c[metric])
        bottom = ranked[:10]
        if not bottom:
            continue
        lines.append(f"### Bottom 10 by {metric}")
        lines.append("")
        lines.append("| id | group | score | user_input |")
        lines.append("|---|---|---|---|")
        for case in bottom:
            question = str(case["user_input"]).replace("|", "\\|")[:60]
            lines.append(f"| {case['id']} | {case['group']} | {case[metric]:.4f} | {question} |")
        lines.append("")

    lines += ["## Errors", ""]
    if run["failed_count"] == 0:
        lines.append("No failed cases.")
    else:
        for case in cases:
            if case["status"] == "failed":
                detail = case.get("error") or "judge returned no score"
                lines.append(f"- `{case['id']}` ({case['group']}): {detail}")
    lines.append("")

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main())
