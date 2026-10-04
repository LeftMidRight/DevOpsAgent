"""Ragas 离线评估器：读取 Java 导出的 JSONL，计算语义/生成指标。

只负责评估，不调用 SuperBizAgent；retrieved_contexts 必须是
Java 侧导出的 Agent 实际上下文，禁止在本脚本里二次检索。

用法：
    python evaluate.py --input input/ragas-eval-dataset.jsonl \
        --output output/ragas-eval-result.json --report ../../docs/ragas-eval-report.md

设计要点（见 docs/plan.md）：
- 按 answerable / no-result 分组汇总，no-result 不与 Context Recall 混平均。
- 单 case 失败只记 NaN，不中断全批，也不当 0 分混入平均值。
- Judge / embedding 配置全环境变量化，与业务生成模型解耦。
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import pandas as pd
from openai import AsyncOpenAI
from ragas import EvaluationDataset, evaluate
from ragas.embeddings import OpenAIEmbeddings
from ragas.llms import llm_factory
from ragas.metrics.collections import (
    AnswerRelevancy,
    ContextPrecision,
    ContextRecall,
    Faithfulness,
)
from ragas.run_config import RunConfig

from config import load_embedding_config, load_judge_config, redacted

EVAL_DIR = Path(__file__).resolve().parent
DEFAULT_OUTPUT = EVAL_DIR / "output" / "ragas-eval-result.json"
DEFAULT_REPORT = EVAL_DIR.parent.parent / "docs" / "ragas-eval-report.md"

JUDGE_TEMPERATURE = 0.0
METRIC_NAMES = ["faithfulness", "answer_relevancy", "context_precision", "context_recall"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Ragas eval over Java-exported dataset")
    parser.add_argument("--input", required=True, type=Path, help="Java 导出 JSONL")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="case 级 JSON 结果")
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT, help="Markdown 报告路径")
    parser.add_argument(
        "--ids",
        default=None,
        help="逗号分隔的 case id 子集（smoke 用），默认全部",
    )
    parser.add_argument("--timeout", type=int, default=300, help="单 case 超时（秒）")
    parser.add_argument("--max-retries", type=int, default=5, help="Judge 调用最大重试")
    return parser.parse_args()


def load_cases(path: Path) -> list[dict]:
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
            missing = [k for k in ("id", "user_input", "response", "retrieved_contexts") if k not in record]
            if missing:
                raise SystemExit(f"第 {line_no} 行缺少字段: {missing}")
            cases.append(record)
    return cases


def build_samples(cases: list[dict]) -> list:
    from ragas import SingleTurnSample

    samples = []
    for case in cases:
        samples.append(
            SingleTurnSample(
                user_input=case["user_input"],
                retrieved_contexts=list(case["retrieved_contexts"]),
                response=case["response"],
                reference=case.get("reference"),
            )
        )
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


def run_group(cases: list[dict], metrics: list, run_config: RunConfig) -> pd.DataFrame:
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


def main() -> int:
    args = parse_args()
    cases = load_cases(args.input)
    if args.ids:
        wanted = {i.strip() for i in args.ids.split(",") if i.strip()}
        cases = [c for c in cases if c["id"] in wanted]
    if not cases:
        raise SystemExit("没有可评估的 case")

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

    answerable = [c for c in cases if not c.get("no_result")]
    no_result = [c for c in cases if c.get("no_result")]

    # answerable：四个指标全算（Context Precision/Recall 依赖 reference）
    answerable_metrics = [faithfulness, answer_relevancy, context_precision, context_recall]
    # no-result：只看回答是否立足上下文、是否回应问题；context 语义指标与拒答型 reference 混算会失真
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

    # 合并 case 级结果
    case_rows = []
    for group, df in frames.items():
        metrics = answerable_metrics if group == "answerable" else no_result_metrics
        metric_names = [m.name for m in metrics]
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

    def metric_names_for(group: str) -> list[str]:
        if group == "answerable":
            return [m.name for m in answerable_metrics]
        return [m.name for m in no_result_metrics]

    summary = {}
    for group, df in frames.items():
        summary[group] = summarize(df, metric_names_for(group))
    all_metrics = sorted({name for names in (metric_names_for(g) for g in frames) for name in names})
    all_df = pd.concat([df[["case_id"] + [n for n in metric_names_for(g) if n in df.columns]]
                        for g, df in frames.items()], ignore_index=True) if frames else pd.DataFrame()
    summary["all"] = summarize(all_df, all_metrics)

    failed_cases = [row["id"] for row in case_rows if row["status"] == "failed"]

    output = {
        "run": {
            "judge": redacted(judge),
            "embedding": redacted(embedding),
            "dataset": str(args.input),
            "dataset_case_count": len(cases),
            "answerable_count": len(answerable),
            "no_result_count": len(no_result),
            "failed_count": len(failed_cases),
            "judge_temperature": JUDGE_TEMPERATURE,
            "usage": totals,
        },
        "summary": summary,
        "cases": case_rows,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")

    write_report(args.report, output)

    print(f"[ragas-eval] cases={len(case_rows)} failed={len(failed_cases)}")
    print(f"[ragas-eval] json:  {args.output}")
    print(f"[ragas-eval] report: {args.report}")
    if failed_cases:
        print(f"[ragas-eval] failed cases: {failed_cases}")
    return 0


def write_report(path: Path, result: dict) -> None:
    run = result["run"]
    summary = result["summary"]
    cases = result["cases"]

    lines = [
        "# Ragas Eval Report",
        "",
        "## Run Metadata",
        "",
        f"- Judge model: `{run['judge'].get('model')}` (provider: {run['judge'].get('provider')})",
        f"- Evaluator embedding: `{run['embedding'].get('model')}`",
        f"- Judge temperature: {run['judge_temperature']}",
        f"- Dataset: `{run['dataset']}`",
        f"- Case count: {run['dataset_case_count']} "
        f"(answerable={run['answerable_count']}, no-result={run['no_result_count']})",
        f"- Failed cases: {run['failed_count']}",
        f"- Token usage: {run['usage']}",
        "",
        "## Summary",
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
    for metric in METRIC_NAMES:
        ranked = [c for c in cases if c.get(metric) is not None]
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
                lines.append(f"- `{case['id']}` ({case['group']}): {case['user_input'][:80]}")
    lines.append("")

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main())
