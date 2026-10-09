# SuperBizAgent Ragas 评估

Ragas 是独立的离线评估器，不进入 Spring Boot，也不是 Maven 依赖。
Java 负责跑被测系统并导出 JSONL，Python 只打分。

两套评估不能混分数：

```text
HybridRetrievalEvalIT          PureRagDatasetExportIT         RagasDatasetExportIT
确定性检索 Hit/Recall/MRR      一次检索 + 固定生成器            完整 Unified Agent
无 Judge 成本                  eval/ragas/input/               eval/ragas/input/
                               pure-rag-eval-dataset.jsonl     ragas-eval-dataset.jsonl
                                        │                              │
                                        ▼                              ▼
                               evaluate.py --mode pure-rag     evaluate.py --mode agent
                               Context Precision/Recall         原有四指标
                               Faithfulness / Answer Relevancy
                               Factual Correctness
                               no-result 拒答 rubric
```

## 一次性准备

```powershell
python -m venv eval/ragas/.venv
eval/ragas/.venv/Scripts/python -m pip install -r eval/ragas/requirements.txt
```

Judge 与业务生成模型分开配置。复制 `.env.example` 为 `.env`（不提交）：

```powershell
$env:RAGAS_JUDGE_API_KEY="..."
$env:RAGAS_JUDGE_MODEL="..."
$env:RAGAS_JUDGE_BASE_URL="..."
$env:RAGAS_EMBEDDING_API_KEY="..."
$env:RAGAS_EMBEDDING_MODEL="..."
$env:RAGAS_EMBEDDING_BASE_URL="..."
```

导出 Pure RAG / Agent 数据还需要业务模型密钥 `VOLCENGINE_API_KEY`（或 `ARK_API_KEY`），以及本机 PostgreSQL、MinIO。评估使用独立的 `rag_eval` schema 和 `superbiz-rag-eval` bucket。

## Pure RAG

Gold 在 `src/test/resources/retrieval/ragas-dataset.jsonl`：`user_input`、`reference`、`reference_contexts`、`reference_context_ids`。`reference_context_ids` 使用 `文件名#章节`，不使用入库后的 chunk UUID。

```powershell
mvn verify -P rag-eval -Dpure.rag.export=true -Dpure.rag.ids=cpu-001
```

确认单条成功后再去掉 `-Dpure.rag.ids` 跑完全部 48 条。输出：

```text
eval/ragas/input/pure-rag-eval-dataset.jsonl
eval/ragas/input/pure-rag-eval-dataset.meta.json
```

然后：

```powershell
eval/ragas/.venv/Scripts/python eval/ragas/evaluate.py --mode pure-rag
```

默认报告写到 `docs/pure-rag-eval-report.md`。这条链路每条 query 只调用一次 `HybridRetrievalService.search`，生成器不注册工具。

`abstention` 只用于 `no_result=true` 的样本，分数是 1 到 5，不和 Context Recall 一起平均。带 `error` 或空 `response` 的样本不进入任何平均。

## Agent Eval

保留原导出器，评的是完整 Agent，不是 Pure RAG。

```powershell
mvn verify -P rag-eval -Dragas.export=true
eval/ragas/.venv/Scripts/python eval/ragas/evaluate.py --mode agent
```

## 安全

Judge 会把检索片段和回答发给配置的模型服务。API key 只放环境变量或 `eval/ragas/.env`。
