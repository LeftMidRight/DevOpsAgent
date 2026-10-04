# SuperBizAgent Ragas 评估

Ragas 是独立的离线语义评估器，**不进入 Spring Boot 生产链路、不是 Maven 依赖**。
Java 侧是被评系统（`RagasDatasetExportIT` 导出真实 Agent 运行数据），本目录是评估器。

## 在整体评估中的位置

```text
HybridRetrievalEvalIT (Java, 确定性)   RagasDatasetExportIT (Java, 导出)
        HitRate@Recall@MRR                     │ 真实 queryInternalDocs contexts
        NoResult P/R                           ▼
                                        input/*.jsonl
                                               │
                                        evaluate.py (本目录)
                                               │
                          Faithfulness / Answer Relevancy
                          Context Precision / Context Recall
                                               │
                              output/*.json + docs/ragas-eval-report.md
```

## 一次性准备

```powershell
python -m venv eval/ragas/.venv
eval/ragas/.venv/Scripts/python -m pip install -r eval/ragas/requirements.txt
```

## 运行步骤

### 1. 生成 Java 侧评估数据（真实 Agent，产生 LLM 成本）

```powershell
mvn verify -P rag-eval -Drag.eval.required=true -Dragas.export=true
```

输出 `eval/ragas/input/ragas-eval-dataset.jsonl`（+ `.meta.json` 运行元数据）。

### 2. 配置 Judge 环境变量

```powershell
$env:RAGAS_JUDGE_API_KEY="..."
$env:RAGAS_JUDGE_MODEL="..."
$env:RAGAS_JUDGE_BASE_URL="..."
$env:RAGAS_EMBEDDING_API_KEY="..."
$env:RAGAS_EMBEDDING_MODEL="..."
$env:RAGAS_EMBEDDING_BASE_URL="..."
```

也可以复制 `.env.example` 为 `.env` 填写（`.env` 不提交）。
完整变量说明见 `.env.example`。

### 3. 运行评估

```powershell
eval/ragas/.venv/Scripts/python eval/ragas/evaluate.py `
  --input eval/ragas/input/ragas-eval-dataset.jsonl `
  --output eval/ragas/output/ragas-eval-result.json `
  --report docs/ragas-eval-report.md
```

## 数据协议

每行 JSONL 一个 case，核心字段：`user_input` / `retrieved_contexts` / `response` / `reference`；
项目自有字段：`id` / `retrieved_context_ids` / `no_result` / `metadata`（排查回归用）。
`retrieved_contexts` 是 **Agent 实际看到**的工具结果，不是 Python 侧二次检索。

## 分组报告

指标按 `answerable` / `no-result` / `all` 三组汇总；no-result case 不与
普通 Context Recall 直接混平均，避免指标失真。失败 case 单列，不计入平均值。

## 版本与可重复性

- `requirements.in`：直接依赖。
- `requirements.txt`：实际验证过的锁定版本（`pip freeze` 全量），升级 Ragas
  需单独跑一轮 eval 确认，不随意漂移。
- 报告记录 Judge model / embedding model / 数据集 case 数 / 每组指标。

## 安全

- Judge API 是数据出站点：确认内部文档内容允许发送给该 provider 再运行。
- API key 只走环境变量 / `.env`，不进入 git，不写死在脚本里。
- JSONL 不得包含密码、连接串、API key。
