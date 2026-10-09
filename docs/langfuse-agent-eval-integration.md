# Langfuse Agent Eval 接入设计

> 状态：Phase 1 设计方案  
> 范围：只设计 Langfuse 接入、Tracing、Dataset / Experiment、Score 与现有 Agent Eval 的关系；不包含业务代码实现。  
> 当前项目：Spring Boot 4.0.8、Spring AI 2.0.1、Java 17。

## 1. 目标

当前 Agent 已经是多步工具调用：

```text
User
 ↓
LLM
 ↓
Prometheus Tool
 ↓
LLM
 ↓
Loki Tool
 ↓
LLM
 ↓
RAG Tool
 ↓
LLM
 ↓
Final Answer
```

只看最终答案已经很难判断失败发生在哪一层。

Langfuse 第一阶段的目标不是替换现有 Agent Runtime，而是回答：

1. 一次 Agent Run 到底发生了什么？
2. 这次 Run 做得好不好？

因此 Langfuse 定位为：

```text
Tracing / Observability
+
Dataset / Experiment
+
Score Store / Compare UI
```

而不是 Agent Execution Engine。

## 2. 核心原则

### 2.1 Langfuse 只做观察者

现有执行链继续保持：

```text
AgentExecutionService
 ↓
ChatClient
 ↓
ToolCallingAdvisor
 ↓
ToolCallingManager
 ↓
ToolCallback
 ↓
LLM / Prometheus / Loki / RAG
```

Langfuse 通过 OpenTelemetry / Micrometer Observation 观察这条链路。

不让 Langfuse：

- 决定下一步 Tool。
- 执行 Tool。
- 修改 AgentRunContext。
- 接管会话状态。
- 取代 Turn Commit。
- 取代预算控制。

### 2.2 评估逻辑不全部交给 Langfuse

确定性 Agent 指标继续由项目自己的 Evaluator 计算：

```text
ToolCallF1
Tool Argument Accuracy
Tool Failure Rate
Tool Call Count
Run Success Rate
Evidence Recall
```

Langfuse负责保存这些 Score、关联 Trace、展示 Case、聚合 Experiment、比较版本，以及承载人工标注或 LLM-as-a-Judge。

### 2.3 先 Trace，后 Experiment

```text
Phase 1A  完整 Trace 可见
Phase 1B  Agent Gold Dataset + Experiment
Phase 1C  Deterministic Score + LLM Judge
Phase 1D  CI / Regression Gate
```

先确保“看得见”，再做“打分”。

## 3. 当前项目已经具备的基础

### 3.1 ObservationRegistry 已进入 Agent 主链

`AgentExecutionService` 当前已经注入：

```java
ObservationRegistry observationRegistry
```

并传给：

```text
DefaultToolCallingManager.builder()
    .observationRegistry(observationRegistry)

ChatClient.builder(
    agent.chatModel(),
    observationRegistry,
    ...
)
```

因此不需要为了 Langfuse 重写 Agent 执行器。

### 3.2 Spring AI 已经具备 Observation 能力

当前 `pom.xml` 已包含：

```text
spring-boot-starter-actuator
spring-ai-autoconfigure-model-chat-observation
```

Spring AI 2.0.1 本身可以对 ChatClient、ChatModel、Advisor、Tool Calling 等产生 Micrometer Observation。

### 3.3 工具包装器已经有 Agent 诊断数据

当前 `EvidenceCollectingToolCallback` 已经掌握：

```text
toolName
normalizedArgs
elapsedMs
success / empty / error
evidenceId
resultChars
```

这部分以后可以作为 Agent-specific Trace / Score 数据源。

### 3.4 当前 Agent Eval 的缺口

现有 `RagasDatasetExportIT` 虽然运行完整 Agent，但当前 collector 只采集 `queryInternalDocs` 的调用和 chunk。

因此目前无法完整评价：

```text
queryActiveAlerts
queryServiceHealth
queryResourceUsage
queryJvmHealth
checkMonitoringTarget
queryLogs
```

Langfuse 接入应解决“完整 Agent Trace”问题，而不是只观察 RAG。

## 4. 总体架构

```text
                         SuperBizAgent
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               ▼
          ChatModel      Tool Calling      Final Answer
                              │
             ┌────────────────┼────────────────┐
             ▼                ▼                ▼
        Prometheus          Loki              RAG
             │                │                │
             └────────────────┼────────────────┘
                              │
                      Micrometer Observation
                              │
                              ▼
                        OpenTelemetry
                              │
                              │ OTLP
                              ▼
                          Langfuse
                              │
           ┌──────────────────┼──────────────────┐
           ▼                  ▼                  ▼
         Traces            Experiments          Scores
           │                  │                  │
           └──────────────────┼──────────────────┘
                              ▼
                    Agent Eval / Regression
```

推荐链路：

```text
Spring AI
→ Micrometer Observation
→ OpenTelemetry
→ OTLP
→ Langfuse
```

而不是专门再写一套 Langfuse Agent Runtime。

官方参考：

- Langfuse Spring AI integration: https://langfuse.com/integrations/frameworks/spring-ai
- Spring AI observability: https://docs.spring.io/spring-ai/reference/observability/
- Langfuse experiments via OpenTelemetry: https://langfuse.com/docs/evaluation/experiments/experiments-via-opentelemetry

## 5. 第一阶段 Trace 模型

一个用户请求对应一个 Agent Run Trace。

理想 Trace：

```text
Trace: agent.run
│
├─ ChatClient / Model Call #1
│    └─ model decides queryActiveAlerts
│
├─ Tool: queryActiveAlerts
│    ├─ args
│    ├─ duration
│    ├─ status
│    └─ evidence_ids
│
├─ ChatClient / Model Call #2
│    └─ model decides queryResourceUsage
│
├─ Tool: queryResourceUsage
│    ├─ args
│    ├─ duration
│    ├─ status
│    └─ evidence_ids
│
├─ ChatClient / Model Call #3
│    └─ model decides queryLogs
│
├─ Tool: queryLogs
│    ├─ args
│    ├─ duration
│    ├─ status
│    └─ evidence_ids
│
└─ Final Answer
```

目标是让一个 Trace 能回答：

```text
模型调用了几次？
选了哪些工具？
调用顺序是什么？
每个 Tool 参数是什么？
Tool 成功还是失败？
Tool 花了多久？
获得了哪些 evidence？
最终答案是什么？
总耗时多少？
```

## 6. Trace 关联字段

当前项目已有：

```text
conversationId
requestId
runId
```

建议：

```text
runId
→ 一次 Agent Run 的稳定关联 ID

conversationId
→ Session / Conversation 关联

requestId
→ 用户本轮请求关联
```

建议 Trace / span metadata 至少包含：

```text
agent.run_id
agent.request_id
agent.conversation_id
agent.model
agent.streaming
agent.environment
agent.app_version
```

其中 `app_version` 建议使用 git commit / release version，方便 Experiment 对比。

Secret、API Key、数据库凭据不得进入 Trace attribute。

## 7. Spring AI 自动观测与自定义观测的边界

### 7.1 优先复用 Spring AI 自动 Span

Spring AI 2.0.1 已经能观察：

```text
ChatClient
Advisor
ChatModel
Tool Call
```

Tool observation 名称为：

```text
spring.ai.tool
```

它能够携带 Tool name、definition metadata、duration 和 tracing context。

因此不要重复创建另一套完全相同的 Tool span。

### 7.2 自定义 Agent Root Span

建议在：

```text
AgentExecutionService.execute(...)
```

外围建立 Agent Run 级 Observation / Span：

```text
agent.run
```

它负责承载：

```text
runId
conversationId
requestId
final status
modelCalls
toolCalls
duration
termination reason
```

Spring AI 内部 Chat / Tool spans 成为它的 children。

### 7.3 EvidenceCollectingToolCallback 的职责

继续负责：

```text
预算控制
失败 streak
结果裁剪
evidence_id
AgentRunContext 统计
```

不建议为了 Langfuse 删除这层。

未来只补充少量安全的 Observation attribute / event：

```text
agent.tool.status
agent.tool.evidence_ids
agent.tool.result_chars
agent.tool.source
```

## 8. Prompt、Completion、Tool Args/Result 是否上传

这是接入 Langfuse 时必须显式决定的。

Spring AI 默认不会导出完整 prompt / completion / tool args / tool result，因为这些内容可能敏感。

### 8.1 Eval 环境

离线 Agent Eval 建议：

```text
prompt:         ON
completion:     ON
tool arguments: ON
tool result:    ON 或 sanitized
sampling:       100%
```

因为没有这些内容，很难分析 Tool Argument Accuracy 与 Evidence Grounding。

Spring AI 2.0.1 相关配置方向：

```yaml
spring:
  ai:
    chat:
      observations:
        log-prompt: true
        log-completion: true
    tools:
      observations:
        include-content: true
```

实现时必须以项目实际 Spring AI 2.0.1 配置绑定结果验证。

### 8.2 Production 环境

生产默认建议：

```text
prompt:         OFF 或脱敏
completion:     OFF 或脱敏
tool arguments: 只保留安全字段
tool result:    不上传 raw logs / raw docs
sampling:       小于 100%
```

特别是：

```text
queryLogs result
queryInternalDocs result
```

可能包含业务日志、用户数据、内部文档内容，不应该无条件完整发送到外部 Observability 平台。

### 8.3 推荐折中

生产 Trace 保存：

```text
toolName
normalized safe args
status
duration
result size
evidence ids
```

而不是：

```text
完整日志正文
完整 RAG chunk
完整 Prompt
```

Eval 环境再通过隔离数据开启完整内容。

## 9. OpenTelemetry → Langfuse

### 9.1 推荐方式

当前 Java 项目优先使用：

```text
Micrometer
→ OpenTelemetry bridge
→ OTLP exporter
→ Langfuse OTLP endpoint
```

Langfuse 官方 Spring AI 集成也是这一方向。

### 9.2 依赖方向

当前项目已经有：

```text
spring-boot-starter-actuator
```

设计上还需要补充 OpenTelemetry tracing/export 能力，候选依赖包括：

```text
micrometer-tracing-bridge-otel
opentelemetry-exporter-otlp
```

Langfuse 官方示例还使用 OpenTelemetry Spring Boot Starter。

但本项目是：

```text
Spring Boot 4.0.8
Spring AI 2.0.1
spring-boot-starter-jdbc
```

因此实现阶段不应直接复制官方旧版本示例中的 dependencyManagement。

必须先用 Maven dependency tree 验证：

```text
Spring Boot 管理的 OTel 版本
Micrometer Tracing 版本
OpenTelemetry instrumentation 版本
```

Langfuse 官方文档也提醒 JDBC + OTel instrumentation 可能出现 instrumentation-api-incubator 版本冲突。

本设计不提前锁定 OTel 具体版本号。

### 9.3 配置方式

推荐把 Langfuse 作为可开关的外部 Trace Backend：

```text
LANGFUSE_ENABLED
OTEL_EXPORTER_OTLP_ENDPOINT
OTEL_EXPORTER_OTLP_HEADERS
```

Langfuse Cloud / Self-hosted 都通过 OTLP 接收。

概念：

```text
OTEL_EXPORTER_OTLP_ENDPOINT=https://<langfuse-host>/api/public/otel
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic <...>,x-langfuse-ingestion-version=4
```

API Key 只通过环境变量 / Secret 管理，不写入 git。

## 10. 第一阶段 Trace 验收

Phase 1A 只要求：

1. 发起一次 Agent 请求。
2. Langfuse 能看到一条 Agent Run Trace。
3. Trace 下能看到 Spring AI Model Call。
4. Trace 下能看到 Tool Call。
5. Tool Span 能看到 tool name。
6. 能看到 Tool duration / error。
7. 能通过 runId 定位本地日志与 Langfuse Trace。
8. Streaming / Non-streaming 都能形成完整 Trace。
9. Agent 执行行为与未接 Langfuse 前一致。

只有这些稳定后，再进入 Dataset / Experiment。

## 11. Agent Eval Dataset 设计

不要直接复用 Pure RAG Dataset 作为 Agent Gold。

Agent Dataset 需要描述：

```text
用户目标
需要哪些工具
工具参数约束
需要获得哪些证据
期望最终答案
```

推荐 Case：

```json
{
  "id": "cpu-high-001",
  "input": {
    "user_input": "payment-service CPU 为什么这么高？"
  },
  "expected_output": {
    "reference": "CPU 升高与 JVM GC 压力相关，证据包括...",
    "required_tools": [
      "queryResourceUsage",
      "queryJvmHealth"
    ],
    "optional_tools": [
      "queryActiveAlerts",
      "queryLogs",
      "queryInternalDocs"
    ],
    "forbidden_tools": [],
    "reference_evidence": [
      "payment-service CPU 明显升高",
      "Old Gen 使用率较高",
      "GC rate 同期升高"
    ],
    "max_tool_calls": 5
  },
  "metadata": {
    "category": "multi_tool_diagnosis",
    "severity": "P1"
  }
}
```

不要把唯一严格 trajectory 写成 Gold。

例如：

```text
queryActiveAlerts
→ queryResourceUsage
→ queryJvmHealth
→ queryLogs
```

不应默认要求完全一致。

只要 Agent：

- 选择了必要 Tool。
- 参数正确。
- 获得必要 Evidence。
- 完成用户目标。

不同合法路径都应该允许。

## 12. Dataset 场景覆盖

第一版建议至少覆盖：

| Category | 示例 | 主要评价 |
|---|---|---|
| docs-only | “Full GC Runbook 是什么？” | queryInternalDocs |
| alert-only | “现在有哪些告警？” | queryActiveAlerts |
| service-health | “payment-service 最近慢不慢？” | queryServiceHealth |
| resource | “CPU/内存怎么样？” | queryResourceUsage |
| jvm | “是不是 GC 有问题？” | queryJvmHealth |
| monitoring-chain | “为什么查不到指标？” | checkMonitoringTarget |
| logs-only | “最近有哪些 timeout？” | queryLogs |
| multi-tool | “payment-service 为什么变慢？” | Tool + Evidence + Goal |
| no-tool | “你好” | 不应乱调 Tool |
| no-result | 查询不存在服务 | 不幻觉 |
| tool-failure | Prometheus/Loki 模拟失败 | 正确披露失败 |
| prompt-injection | Tool result 中含指令 | 不执行外部数据中的指令 |

第一版 Offline Eval 应使用固定 Mock / Fixture Tool Result。

原因：

```text
真实 Prometheus / Loki
→ 数据不断变化
→ Gold 会失效
→ Experiment 之间不可比较
```

生产 Trace 可以用于 Online Eval，但不能替代 Offline Regression Dataset。

## 13. Experiment 模型

Langfuse Experiment 可以理解成：

```text
同一套 Dataset
×
某一个 Agent 版本
```

例如：

```text
Dataset: aiops-agent-v1

Experiment A
├─ gitCommit = abc123
├─ model = DeepSeek-V4.1-Flash
├─ systemPromptVersion = v3
└─ toolsVersion = v1

Experiment B
├─ gitCommit = def456
├─ model = DeepSeek-V4.1-Flash
├─ systemPromptVersion = v4
└─ toolsVersion = v2
```

比较：

```text
ToolCallF1
Argument Accuracy
Evidence Recall
Goal Accuracy
Faithfulness
Tool Calls
Latency
Failure Rate
```

Experiment metadata 建议固定记录：

```text
git_commit
agent_model
system_prompt_version
tool_catalog_version
dataset_version
evaluator_version
retrieval_config_version
```

这样分数变化才可以回溯到具体系统变化。

## 14. Java Experiment 接入策略

Langfuse 当前 Experiment Runner 主 SDK 是 Python / TypeScript。

本项目是 Java，因此推荐：

```text
Java Agent Eval Runner
       ↓
OpenTelemetry experiment attributes
       ↓
Langfuse
```

即：

- Agent 仍由 Java 测试 / Runner 执行。
- 一条 Dataset Item 对应一个独立 Trace。
- 通过 OTEL baggage / span attributes 标记 experiment 和 item。
- Langfuse 将这些 traces 归组为 Experiment。

Experiment context 需要表达：

```text
langfuse.experiment.id
langfuse.experiment.name
langfuse.experiment.dataset.id
```

每个 Item 需要：

```text
langfuse.experiment.item.id
langfuse.experiment.item.root_observation_id
langfuse.experiment.item.expected_output
```

具体 attribute 以 Langfuse 当前 OpenTelemetry experiment 文档为准：

https://langfuse.com/integrations/native/opentelemetry/experiments

重要：

> 每个 dataset item 必须形成独立 trace，不要用一个巨大的父 span 包住整个 Experiment。

## 15. Score 设计

第一版 Score 分两类。

### 15.1 Deterministic Score

项目自己计算：

```text
tool_call_f1
tool_argument_accuracy
tool_failure_rate
run_success
tool_call_count
evidence_recall
```

然后写回 Langfuse Score。

### 15.2 Semantic / Judge Score

可以继续利用现有 Ragas 或 Langfuse LLM-as-a-Judge：

```text
agent_goal_accuracy
faithfulness
answer_correctness
response_relevance
```

原则：

```text
能确定性计算
→ 不用 LLM Judge

需要语义判断
→ LLM Judge
```

Langfuse Score 作为统一展示层。

## 16. ToolCallF1

Gold：

```json
{
  "required_tools": [
    "queryResourceUsage",
    "queryJvmHealth"
  ]
}
```

Actual：

```text
queryResourceUsage
queryJvmHealth
queryLogs
```

计算：

```text
TP = required 且实际调用
FP = 不需要却调用
FN = required 但未调用

precision = TP / (TP + FP)
recall    = TP / (TP + FN)
F1        = 2PR / (P + R)
```

规则：

- `optional_tools` 不作为 FP。
- no-tool Case 若调用任何无关工具应明确扣分。
- 不要求严格工具顺序，除非场景本身有顺序约束。

## 17. Tool Argument Accuracy

不能只评价：

```text
toolName 对不对
```

还要评价：

```text
service
lookback
level
keyword
instance
```

例如：

```json
{
  "tool": "queryLogs",
  "constraints": {
    "service": "payment-service",
    "lookback_max": "1h",
    "level": ["WARN", "ERROR"],
    "keyword_contains_any": ["Full GC", "GC"]
  }
}
```

推荐按字段分别判断，再聚合：

```text
service: 1
lookback: 1
level: 1
keyword: 0
----------------
argument_accuracy = 0.75
```

这样可以区分“选错工具”和“工具选对但参数错误”。

## 18. Evidence Recall

Tool 调对不代表 Agent 获得了正确证据。

Gold：

```text
CPU high
Old Gen high
GC rate high
```

实际 Tool Evidence：

```text
CPU high
Old Gen high
```

则 Evidence Recall 不应为 1。

评价对象应该是：

```text
required evidence facts
vs
all successful Tool evidence
```

这里的 Evidence 包括：

```text
Prometheus
Loki
RAG
```

不能像当前 Agent Ragas exporter 一样只使用 InternalDocs chunks。

## 19. Agent Goal Accuracy

它回答：

> Agent 最后有没有真正完成用户任务？

例如用户问：

```text
payment-service 为什么 CPU 高？
```

Agent 最后只回答：

```text
CPU 当前 92%
```

虽然相关，但没有回答“为什么”。

Goal Accuracy 应依据：

```text
reference
reference_evidence
user intent
```

判断：

- 是否给出诊断结论。
- 是否说明关键证据。
- 是否正确表达不确定性。
- Tool 失败时是否如实披露。

## 20. Faithfulness

Faithfulness 的 context 不应只用 RAG。

应该使用：

```text
all_tool_evidence
=
Prometheus results
+
Loki results
+
Internal Docs chunks
+
其他未来 Tool result
```

最终回答里的 factual claims 应能被这些 Evidence 支持。

## 21. Langfuse Score 映射

建议第一版：

| Score | Type | 来源 |
|---|---|---|
| `run_success` | BOOLEAN | Java |
| `tool_call_f1` | NUMERIC | Java |
| `tool_argument_accuracy` | NUMERIC | Java |
| `evidence_recall` | NUMERIC | Java / Judge |
| `agent_goal_accuracy` | NUMERIC | LLM Judge |
| `faithfulness` | NUMERIC | Ragas / Judge |
| `tool_failure_rate` | NUMERIC | Java |
| `tool_call_count` | NUMERIC | Java |
| `latency_ms` | NUMERIC | Trace / Java |

不要把所有指标合成一个总分后丢失诊断信息。

可以额外计算 `release_pass`，但必须保留原始维度。

## 22. Experiment Pass Policy

不要只看平均分。

建议 Release Gate：

```text
required:
run_success >= 0.98
tool_call_f1 >= baseline - tolerance
tool_argument_accuracy >= baseline - tolerance
faithfulness >= threshold

critical scenarios:
不得从 PASS 回归为 FAIL
```

特别是：

```text
tool failure
prompt injection
no-result
no-tool
```

这些 Case 更适合 Pass / Fail Gate，而不是被平均分掩盖。

## 23. 与现有 Ragas 的关系

保留 Ragas。

职责建议：

```text
HybridRetrievalEvalIT
→ 纯 Retriever

PureRagDatasetExportIT + Ragas
→ Pure RAG

Agent Eval + Langfuse
→ 完整 Agent Trace / Tool / Experiment

Ragas
→ Agent 最终 Answer 的部分语义指标
```

不要让 Langfuse 替代 Retriever Eval。

Langfuse 更适合统一：

```text
Trace
Experiment
Scores
```

## 24. 与 AgentRunContext 的关系

当前 `AgentRunContext` 已有：

```text
runId
modelCalls
toolCalls
failureStreaks
evidences
deadline
termination
```

继续保留。

建议：

```text
AgentRunContext
= execution truth

OpenTelemetry / Langfuse
= observability projection
```

不要反过来从 Langfuse 查询 Trace 来控制当前 Agent Run。

## 25. 与 EvidenceCollectingToolCallback 的关系

建议未来它成为 Tool Trace 的 Agent-specific 数据补充点。

执行前：

```text
toolName
normalizedArgs
source
```

执行后：

```text
status
elapsedMs
evidenceIds
resultChars
```

写入当前 Observation。

不要把完整 raw result 强制写 span。

例如：

```text
queryLogs
resultChars=5800
evidenceIds=log-123,log-124
status=SUCCESS
```

已经足够完成大量 tracing/debug。

Offline Eval 需要完整 Fixture Result 时，可以在隔离 profile 开启 content export。

## 26. 环境划分

建议至少：

```text
dev
eval
prod
```

Langfuse environment / tag 与本地 profile 对齐。

### dev

```text
sampling = 100%
content = 可开启
```

### eval

```text
sampling = 100%
prompt/completion = ON
tool args = ON
tool results = sanitized/full fixture
environment = experiment
```

### prod

```text
sampling < 100%
prompt/completion = OFF 默认
tool raw results = OFF
```

## 27. 配置建议

最终配置不要把 Secret 放入 `application.yml`。

概念：

```yaml
langfuse:
  enabled: ${LANGFUSE_ENABLED:false}
  environment: ${LANGFUSE_ENVIRONMENT:dev}
```

OpenTelemetry：

```text
OTEL_EXPORTER_OTLP_ENDPOINT
OTEL_EXPORTER_OTLP_HEADERS
```

Spring AI Eval Profile：

```yaml
spring:
  ai:
    chat:
      observations:
        log-prompt: true
        log-completion: true
    tools:
      observations:
        include-content: true

management:
  tracing:
    sampling:
      probability: 1.0
```

生产 profile 默认关闭内容型 observation。

注意：配置 key 在实现阶段必须用 Spring AI 2.0.1 实际启动绑定测试验证。

## 28. 建议新增的代码边界

这是未来实现方向，不代表当前立即修改。

```text
observability/
└── langfuse/
    ├── AgentRunObservation
    ├── AgentTraceAttributes
    └── LangfuseProperties

eval/
├── AgentEvalDataset
├── AgentEvalCase
├── AgentEvalRunner
├── AgentEvalResult
└── evaluator/
    ├── ToolCallEvaluator
    ├── ToolArgumentEvaluator
    ├── EvidenceRecallEvaluator
    └── RunSuccessEvaluator
```

不要引入：

```text
LangfuseAgentExecutor
```

因为 Agent 仍由现有 `AgentExecutionService` 执行。

## 29. 推荐实现顺序

### Step 1：OTel Trace 打通

目标：

```text
AgentExecutionService
→ Langfuse Trace
```

验证 Model / Tool span。

### Step 2：Agent Root Trace

增加：

```text
agent.run
runId
conversationId
requestId
status
```

### Step 3：Tool Trace 补充

将已有 `EvidenceCollectingToolCallback` 中的：

```text
status
evidenceId
result size
```

投影进 Trace。

### Step 4：新的 Agent Gold Dataset

不要继续让 `RagasDatasetExportIT` 承担完整 Agent Eval。

创建独立 Agent Dataset。

### Step 5：Deterministic Evaluators

先完成：

```text
RunSuccess
ToolCallF1
ToolArgumentAccuracy
ToolCallCount
ToolFailureRate
```

### Step 6：Experiment → Langfuse

Java Runner 使用 OTEL experiment attributes，将每个 Case 的独立 Trace 归入 Experiment。

### Step 7：Semantic Eval

再增加：

```text
Evidence Recall
Agent Goal Accuracy
Faithfulness
```

### Step 8：Regression Gate

同 Dataset 比较 baseline / candidate。

## 30. Phase 1 不做什么

第一阶段明确不做：

- 不把 System Prompt 迁移到 Langfuse Prompt Management。
- 不让 Langfuse 执行 Tool。
- 不修改 Agent Tool Calling 主循环。
- 不替换现有 Ragas。
- 不把生产完整日志/RAG 文档无条件上传 Langfuse。
- 不使用严格 trajectory exact match 作为主要 Agent 指标。
- 不使用真实动态 Prometheus/Loki 作为唯一 Offline Eval 数据源。
- 不用一个“总分”掩盖各维度退化。

## 31. Phase 1 验收标准

### Tracing

1. 每个 Agent Run 在 Langfuse 有独立 Trace。
2. Model Call 可以看到 duration / model / token metadata（provider 支持时）。
3. Tool Call 能看到 tool name / duration / status。
4. runId 能关联本地运行日志和 Langfuse Trace。
5. Tool error 能在 Trace 中定位。
6. 接入前后 Agent 行为保持一致。

### Dataset / Experiment

7. 有独立的 Agent Gold Dataset。
8. 每个 Dataset Item 对应一个独立 Trace。
9. 同 Dataset 可以跑 baseline 与 candidate Experiment。
10. Experiment metadata 有 git commit / model / tool version / evaluator version。

### Eval

11. ToolCallF1 可确定性计算。
12. Argument Accuracy 可按字段诊断。
13. Tool Failure Rate / Tool Call Count / Run Success 可计算。
14. Evidence Recall 覆盖所有 Tool Evidence，不只 RAG。
15. Goal Accuracy / Faithfulness 可作为 Langfuse Score 展示。
16. Critical Case 回归可以被单独发现。

### Privacy / Safety

17. Secret 不进入 Trace。
18. Production 默认不上传 raw prompt / completion / log result。
19. Eval profile 与 Production trace 策略隔离。

## 32. 最终架构

```text
                         Gold Dataset
                              │
                              ▼
                       AgentEvalRunner
                              │
                              ▼
                      AgentExecutionService
                              │
               ┌──────────────┼──────────────┐
               ▼              ▼              ▼
             Model       Prometheus/Loki     RAG
               │              │              │
               └──────────────┼──────────────┘
                              ▼
                       AgentRunContext
                              │
                        execution truth
                              │
                              ├───────────────┐
                              ▼               ▼
                   Deterministic Eval     OpenTelemetry
                              │               │
                              │               ▼
                              │            Langfuse
                              │               │
                              └──── Scores ───┤
                                              │
                                      ┌───────┴────────┐
                                      ▼                ▼
                                   Trace           Experiment
                                      │                │
                                      └────── Compare ──┘
```

## 33. 结论

本项目接 Langfuse 的推荐定位：

> Langfuse 是 Agent 的可观测与评估平台，不是 Agent Runtime。

第一版优先完成：

```text
Spring AI Observation
→ OpenTelemetry
→ Langfuse Trace
```

然后在现有 Agent Eval 逻辑上增加：

```text
Agent Dataset
→ Java Experiment Runner
→ Deterministic Evaluators
→ Langfuse Scores
→ Experiment Compare
```

最终形成：

```text
运行过程可解释
+
失败位置可定位
+
改模型 / Prompt / Tool 后可回归比较
```

这比只保存最终答案或只跑 Ragas 更适合当前多 Tool AIOps Agent。
