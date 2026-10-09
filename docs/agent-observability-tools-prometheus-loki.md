# Agent Observability Tools 设计：Prometheus + Loki

> 状态：Phase 1 方案  
> 范围：只定义 Agent 的观测工具与接入边界，不包含业务代码实现。  
> 目标：用 Prometheus 作为 Metrics/Alerts 数据源，用 Loki 作为 Logs 数据源，并让 Agent 只感知稳定的业务语义 Tool，而不是直接操作 PromQL / LogQL。

## 1. 设计目标

这一版工具设计解决三个问题：

1. Agent 能获取被观测服务的实时运行证据。
2. Tool 接口不与具体查询语言、日志 Topic 或机器路径强绑定。
3. Tool Calling 可以被稳定评估：是否选对工具、参数是否正确、证据是否充分。

第一阶段保持全部工具只读，不提供 restart、rollback、scale、kill、clear cache 等写操作。

## 2. 总体架构

```text
                  被观测服务
                     │
          ┌──────────┴──────────┐
          │                     │
      Metrics                  Logs
          │                     │
 /actuator/prometheus      stdout / app.log
          │                     │
          ▼                     ▼
     Prometheus             Grafana Alloy
          │                     │
          │                     ▼
          │                    Loki
          │                     │
          └──────────┬──────────┘
                     ▼
               SuperBizAgent
                     │
       ┌─────────────┴─────────────────────┐
       │                                   │
       ▼                                   ▼
 Prometheus Tools                      queryLogs
       │                                   │
       ├─ queryActiveAlerts                │
       ├─ queryServiceHealth               │
       ├─ queryResourceUsage               │
       ├─ queryJvmHealth                   │
       └─ checkMonitoringTarget            │
       │                                   │
       └──────────────┬────────────────────┘
                      ▼
               Runtime Evidence
                      │
                      ▼
                Final Diagnosis
```

Prometheus 与 Loki 是观测后端；Agent 不直接连接业务进程读取内部状态。

Prometheus Agent Tool 的拆分原则是：

> 不按 Prometheus HTTP Endpoint 拆 Tool，也不按单个 Metric 拆 Tool，而按高频运维诊断意图拆 Tool。

这样既避免一个万能 `queryMetrics` 过于抽象，也避免 `queryCpu/queryGc/queryP99/...` 造成工具数量膨胀。

## 3. 第一版 Tool Catalog

第一版 Agent 暴露 8 个业务 Tool：

| Tool | 数据源 | 作用 |
|---|---|---|
| `getCurrentDateTime` | 本地 | 获取当前时间、辅助确定查询时间范围 |
| `queryInternalDocs` | RAG | 查询内部 Runbook、流程、最佳实践 |
| `queryActiveAlerts` | Prometheus | 查询当前 firing / active alerts |
| `queryServiceHealth` | Prometheus | 查询请求量、错误率、P95/P99 等服务健康信号 |
| `queryResourceUsage` | Prometheus | 查询 CPU、内存等资源使用情况 |
| `queryJvmHealth` | Prometheus | 查询 Heap、Old Gen、GC、线程等 JVM 状态 |
| `checkMonitoringTarget` | Prometheus | 检查 Prometheus scrape target 是否健康 |
| `queryLogs` | Loki | 查询应用日志、异常日志和诊断日志 |

未来设计中不再要求 Agent 调用 `getAvailableLogTopics`。日志 Topic、CLS TopicId、腾讯云 region 都不应成为 Agent 的核心业务概念。

## 4. 工具与证据职责

```text
queryActiveAlerts
→ “现在正在发生什么异常？”

queryServiceHealth
→ “服务流量、错误率、延迟是否恶化？”

queryResourceUsage
→ “CPU / Memory 是否存在资源压力？”

queryJvmHealth
→ “Heap / Old Gen / GC / Threads 是否异常？”

checkMonitoringTarget
→ “Prometheus 是否真的采集到了这个服务？”

queryLogs
→ “异常期间具体发生了什么？”

queryInternalDocs
→ “公司建议怎么处理这类问题？”

getCurrentDateTime
→ “当前时间是什么？相对时间范围如何解析？”
```

实时事实和知识库必须分开：

- Prometheus / Loki：Runtime Evidence。
- RAG：Knowledge / Runbook。
- RAG 内容不能替代当前运行状态。
- 当前告警或日志也不能替代规范、处理流程和知识说明。

## 5. Prometheus 接入

### 5.1 被观测服务

Spring Boot 服务通过 Micrometer / Actuator 暴露 Prometheus 指标，例如：

```text
/actuator/prometheus
```

Prometheus 负责 scrape，被观测服务不需要与 Agent 同机部署。

推荐至少统一以下 Label：

```text
service
environment
instance
```

如果未来运行在 Kubernetes，再扩展：

```text
cluster
namespace
pod
```

Tool 层优先使用 `service` 定位业务，不让模型记忆 IP、端口或机器路径。

### 5.2 queryActiveAlerts

职责：回答“当前有没有告警、哪些实例正在告警”。

概念签名：

```java
queryActiveAlerts(
    String service
)
```

规则：

- `service == null`：查询所有当前告警。
- 指定 service：只返回该服务相关告警。
- 底层主要使用 Prometheus `/api/v1/alerts`。
- 不把 Prometheus 原始大 JSON 直接交给模型。

建议返回：

```json
{
  "status": "success",
  "alerts": [
    {
      "alertName": "HighCPUUsage",
      "service": "payment-service",
      "instance": "10.0.0.12:8080",
      "severity": "warning",
      "state": "firing",
      "activeSince": "2026-10-07T15:40:00+08:00",
      "description": "CPU usage > 80%"
    }
  ]
}
```

### 5.3 queryServiceHealth

职责：回答“服务整体健康吗”，一次返回在线服务最常见的 RED 信号。

概念签名：

```java
queryServiceHealth(
    String service,
    String lookback,
    String instance
)
```

第一版返回：

```text
REQUEST_RATE
ERROR_RATE
LATENCY_P95
LATENCY_P99
```

建议结果：

```json
{
  "service": "payment-service",
  "range": "30m",
  "requestRate": {
    "current": 120.5,
    "trend": "stable"
  },
  "errorRate": {
    "current": 0.083,
    "trend": "rising"
  },
  "latencyP95Ms": 1800,
  "latencyP99Ms": 4200
}
```

### 5.4 queryResourceUsage

职责：回答“CPU / Memory 是否存在资源压力”。

概念签名：

```java
queryResourceUsage(
    String service,
    String lookback,
    String instance
)
```

第一版优先覆盖：

```text
PROCESS_CPU
PROCESS_MEMORY
```

如果后续接入 Node Exporter 或容器指标，再扩展 Host / Container CPU、Memory、Disk、Network，而不是一开始全部塞进 Tool。

建议返回当前值、平均值、峰值、趋势和异常实例。

### 5.5 queryJvmHealth

职责：Java 服务专项健康检查。

概念签名：

```java
queryJvmHealth(
    String service,
    String lookback,
    String instance
)
```

第一版重点信号：

```text
JVM_HEAP_USAGE
JVM_OLD_GEN_USAGE
GC_RATE
GC_PAUSE
THREAD_COUNT
```

建议返回：

```json
{
  "service": "payment-service",
  "range": "30m",
  "heapUsage": 0.86,
  "oldGenUsage": 0.91,
  "gcRate": 7.2,
  "gcPauseP99Ms": 850,
  "threads": 214,
  "trend": {
    "oldGen": "rising",
    "gcRate": "rising"
  }
}
```

### 5.6 checkMonitoringTarget

职责：判断“没有数据”究竟是业务状态，还是 Prometheus 根本没有正常 scrape。

概念签名：

```java
checkMonitoringTarget(
    String service,
    String instance
)
```

底层主要使用 Prometheus `/api/v1/targets`。

建议返回：

```json
{
  "service": "order-service",
  "targetHealth": "down",
  "lastError": "connection refused",
  "lastScrape": "2026-10-07T16:00:00+08:00",
  "scrapeUrl": "http://10.0.0.13:8080/actuator/prometheus"
}
```

### 5.7 PromQL 仍然属于内部实现

第一阶段不向 LLM 暴露：

```text
executePromql(promql)
```

也不再向 Agent 暴露通用：

```text
queryMetrics(metric=...)
```

而是由各诊断 Tool 在内部调用统一查询层：

```text
LLM
 ↓
queryServiceHealth / queryResourceUsage / queryJvmHealth
 ↓
PrometheusQueryService
 ↓
MetricQueryRegistry
 ↓
PromQL Template
 ↓
PrometheusClient
 ↓
/api/v1/query 或 /api/v1/query_range
```

内部建议职责：

```text
PrometheusTools
PrometheusQueryService
PrometheusClient
MetricQueryRegistry
PrometheusProperties
```

其中：

- `PrometheusTools`：对 Agent 暴露 5 个 Prometheus 诊断 Tool，并负责参数校验与结果封装。
- `PrometheusQueryService`：组合某个诊断意图需要的多个 PromQL 查询。
- `MetricQueryRegistry`：维护底层指标到 PromQL 模板的映射。
- `PrometheusClient`：HTTP 调用、超时、状态码和 JSON 解析。
- `PrometheusProperties`：base-url、timeout 等配置。

### 5.8 Tool Result 统一原则

Prometheus Tool Result 应该：

- 提供 current / average / max / trend 等摘要。
- 必要时提供少量代表性 samples。
- 能按 instance 展示异常实例。
- 区分 `no_data`、`target_down`、`query_error` 和真实的“指标为 0”。
- 不把大量 raw time-series samples 直接塞进模型上下文。

## 6. Loki 接入

### 6.1 日志链路

推荐：

```text
Spring Boot
 ↓
stdout / structured app.log
 ↓
Grafana Alloy
 ↓
Loki
 ↓
Loki HTTP API
 ↓
SuperBizAgent.queryLogs
```

第一阶段可以运行单体 Loki；Agent 不关心 Loki 是单体还是分布式部署。

### 6.2 日志格式

推荐应用优先输出结构化 JSON：

```json
{
  "timestamp": "2026-10-07T16:00:31+08:00",
  "level": "ERROR",
  "service": "payment-service",
  "logger": "PaymentService",
  "message": "Redis connection timeout",
  "traceId": "abc123"
}
```

推荐 Loki 低基数标签：

```text
service
environment
level
instance
```

未来 Kubernetes 环境可加入：

```text
cluster
namespace
pod
```

`traceId`、`requestId`、`userId` 等高基数字段默认不要作为 Loki label；保留在日志内容或 structured metadata 中。

## 7. queryLogs

未来 `queryLogs` 不再暴露以下 CLS 专属参数：

```text
region
logTopic
CLS TopicId
```

建议业务语义签名：

```java
queryLogs(
    String service,
    String lookback,
    String level,
    String keyword,
    String instance,
    Integer limit
)
```

参数规则：

- `service`：必填，目标业务服务。
- `lookback`：默认 30m，设置最大允许时间窗口。
- `level`：可选，如 ERROR / WARN。
- `keyword`：可选，关键词或受控搜索条件。
- `instance`：可选，用于缩小单实例。
- `limit`：默认 20，建议最大 100。

Tool 内部再负责安全地生成 LogQL：

```text
queryLogs(
  service="payment-service",
  lookback="30m",
  level="ERROR",
  keyword="Full GC"
)

        ↓

{service="payment-service", level="ERROR"}
|= "Full GC"
```

第一阶段不直接暴露：

```text
executeLogql(logql)
```

### 7.1 queryLogs 返回结构

建议：

```json
{
  "service": "payment-service",
  "range": "30m",
  "total": 7,
  "logs": [
    {
      "timestamp": "16:01:03",
      "level": "WARN",
      "instance": "payment-01",
      "message": "Full GC pause 1.82s, Old Gen 91%"
    },
    {
      "timestamp": "16:03:17",
      "level": "WARN",
      "instance": "payment-01",
      "message": "Full GC pause 2.01s, Old Gen 93%"
    }
  ]
}
```

必须限制日志数量和单条长度，避免一次 Tool Call 将数万行日志灌入模型上下文。

### 7.2 内部职责

建议代码边界：

```text
LokiTools
LokiClient
LokiProperties
```

- `LokiTools`：Agent Tool 输入校验、业务参数归一化、结果裁剪。
- `LokiClient`：Loki HTTP API / query_range 调用与解析。
- `LokiProperties`：base-url、timeout、default-limit、max-limit。

如果未来 Loki 被替换为 CLS、Elastic、OpenSearch，Agent 层应该尽量继续保留 `queryLogs` 语义。

## 8. Spring AI 接入方式

当前项目已经具备本地 Tool 主干：

```text
@Tool Java Method
      ↓
MethodToolCallbackProvider
      ↓
ToolCallback[]
      ↓
UnifiedAgentFactory
      ↓
OpenAiChatOptions.toolCallbacks(...)
      ↓
LLM
```

因此 Prometheus 和 Loki 第一版都建议作为本地 `@Tool` 接入，不需要额外搭 MCP Server。

未来建议结构：

```text
agent/tool/
├── DateTimeTools
├── InternalDocsTools
├── PrometheusTools
└── LokiTools

observability/
├── prometheus/
│   ├── PrometheusQueryService
│   ├── PrometheusClient
│   ├── MetricQueryRegistry
│   └── PrometheusProperties
│
└── loki/
    ├── LokiClient
    └── LokiProperties
```

`PrometheusTools` 对 Agent 暴露 5 个诊断意图 Tool；底层查询复用同一套 `PrometheusQueryService / PrometheusClient`。

Tool 类保持薄：不把 HTTP、JSON 解析、PromQL / LogQL 拼装全部写进 `@Tool` 方法。

## 9. Agent Tool Calling 生命周期

Agent 不使用 hard-coded：

```java
if (question.contains("CPU")) {
    queryResourceUsage(...);
}
```

而是把 Tool Definition 提供给模型，让模型根据工具语义选择诊断动作。

例如模型看到：

```text
Tool: queryResourceUsage
Description:
Inspect CPU and memory pressure of a service.

Tool: queryJvmHealth
Description:
Inspect JVM heap, old generation, GC and thread health.

Tool: queryServiceHealth
Description:
Inspect request rate, error rate and latency health.
```

用户：

```text
payment-service CPU 为什么变高？
```

一种合理执行链：

```text
User
 ↓
LLM
 ↓
queryActiveAlerts(payment-service)
 ↓
ToolCallingAdvisor
 ↓
PrometheusTools
 ↓
Prometheus
 ↓
Tool Result
 ↓
LLM
 ↓
queryResourceUsage(payment-service, 30m)
 ↓
Tool Result
 ↓
LLM
 ↓
queryJvmHealth(payment-service, 30m)
 ↓
Tool Result
 ↓
LLM
 ↓
queryLogs(payment-service, 30m, WARN, "Full GC")
 ↓
Tool Result
 ↓
LLM
 ↓
Final Answer
```

这条路径不是唯一 Gold Path。例如没有 JVM 异常时，Agent 不应该为了固定流程强行调用 `queryJvmHealth`。

模型只决定：

- 是否调用工具。
- 调哪个诊断工具。
- 填哪些业务参数。
- 根据工具结果是否继续查。

真正执行 Tool 的仍然是 Spring AI Tool Calling 机制。

## 10. 一次典型故障诊断

问题：

```text
payment-service 为什么这么慢？
```

预期 Agent 可以：

```text
1. queryActiveAlerts(service=payment-service)
   → SlowResponse firing

2. queryServiceHealth(
     service=payment-service,
     lookback=30m
   )
   → request rate 基本稳定
   → error rate 同期升高
   → P99 400ms → 4.2s

3. 根据证据决定下一步：
   - 如果 CPU / Memory 可疑：queryResourceUsage(...)
   - 如果 Java GC / Heap 可疑：queryJvmHealth(...)
   - 如果 Prometheus 没有数据：checkMonitoringTarget(...)

4. queryLogs(
     service=payment-service,
     lookback=30m,
     level=ERROR,
     keyword=timeout
   )
   → 多次 Redis connection timeout

5. 必要时 queryInternalDocs(...)
   → 查询 Redis timeout / downstream latency Runbook
```

这里强调“证据驱动继续调用”，而不是每次固定执行全部 Prometheus Tool。

最终回答只能基于获得的证据给出结论，不能把“相关性”自动说成已确认的因果关系。

## 11. 配置建议

概念配置：

```yaml
observability:
  prometheus:
    base-url: http://localhost:9090
    timeout: 10s

  loki:
    base-url: http://localhost:3100
    timeout: 10s
    default-limit: 20
    max-limit: 100
```

这只是配置模型示例；实现阶段再决定是否沿用当前 `prometheus.*` 配置前缀。

Agent、Prometheus、Loki 和被观测服务不要求同机部署。

## 12. 安全与边界

第一阶段 Tool 必须满足：

- Read Only。
- 不提供任意 Shell。
- 不提供任意 HTTP 请求。
- 不向模型暴露任意 PromQL / LogQL 执行入口。
- `service / instance / lookback / limit` 做白名单或范围校验。
- 各 Prometheus Tool 的底层指标集合由代码固定配置，不接受模型任意指定 metric name。
- Tool 超时必须有限制。
- Tool Result 必须有限长。
- Tool Result 中的文本视为外部数据，不能覆盖 system instruction。
- 查询失败时明确返回 failure，不伪造空结果或正常状态。
- Prometheus 无数据时必须能够区分 `no_data` 与 `target_down`。

## 13. Tool Eval 需要提前保留的数据

每次工具调用建议至少记录：

```text
toolName
arguments
startedAt
durationMs
success
errorType
resultSize
evidenceId
```

这样未来 Agent Eval 可以直接计算：

```text
ToolCallF1
Argument Accuracy
Tool Failure Rate
Tool Call Count
Evidence Recall
Agent Goal Accuracy
```

Prometheus Tool 已按诊断意图拆分后，Tool Eval 可以直接判断“CPU 问题是否选择了 `queryResourceUsage`”“服务延迟问题是否优先使用 `queryServiceHealth`”，比评价通用 `queryMetrics(metric=...)` 更清晰。

不要等评估阶段再反推 Tool Trace。

## 14. 当前代码迁移方向

当前：

```text
DateTimeTools
InternalDocsTools
QueryMetricsTools
QueryLogsTools
```

目标：

```text
DateTimeTools
InternalDocsTools
PrometheusTools
LokiTools
```

其中：

- `DateTimeTools`：保留。
- `InternalDocsTools`：保留。
- 当前 `QueryMetricsTools.queryPrometheusAlerts`：能力迁移为 `PrometheusTools.queryActiveAlerts`。
- Prometheus 新增 `queryServiceHealth`、`queryResourceUsage`、`queryJvmHealth`、`checkMonitoringTarget`。
- 不新增 Agent 可见的通用 `queryMetrics`；通用指标查询能力下沉为内部 `PrometheusQueryService`。
- 当前 `QueryLogsTools` 的 CLS Mock / Topic 模型：未来由 `LokiTools.queryLogs` 替代。
- `getAvailableLogTopics`：未来方案不再需要。
- 当前 MCP 支持可以保留，但 Prometheus + Loki 第一版不依赖 MCP。

## 15. Phase 1 验收标准

完成实现后应满足：

1. `queryActiveAlerts` 能查询所有当前 Prometheus 告警，并支持按 service 过滤。
2. `queryServiceHealth` 能返回 Request Rate、Error Rate、P95、P99 的当前值和趋势。
3. `queryResourceUsage` 能返回服务 CPU / Memory 的当前值、峰值和趋势。
4. `queryJvmHealth` 能返回 Heap、Old Gen、GC、Threads 的关键状态。
5. `checkMonitoringTarget` 能识别 target up/down、last scrape 和 scrape error。
6. LLM 不需要生成 PromQL，也不能直接指定任意 Prometheus metric name。
7. `queryLogs` 能按 service + time range 查询 Loki 日志。
8. LLM 不需要生成 LogQL。
9. 日志工具不再依赖 CLS Topic/region 语义。
10. Tool Result 有稳定 JSON 结构和长度限制。
11. 所有 Tool 均为只读。
12. Tool Call / args / outcome 可被 Agent Eval 采集。
13. RAG、Metrics、Logs 三类证据职责清晰，不互相替代。
14. 不要求 Agent 与被观测服务同机。

## 16. 第一版最终边界

```text
                        SuperBizAgent
                              │
       ┌──────────────────────┼─────────────────────┐
       │                      │                     │
       ▼                      ▼                     ▼
 Prometheus Tools          queryLogs        queryInternalDocs
       │                      │                     │
       ├─ queryActiveAlerts   │                     │
       ├─ queryServiceHealth  │                     │
       ├─ queryResourceUsage  │                     │
       ├─ queryJvmHealth      │                     │
       └─ checkMonitoringTarget                    │
       │                      │                     │
       ▼                      ▼                     ▼
  Prometheus                Loki                   RAG
       └──────────────┬───────┘                     │
                      ▼                             │
               Runtime Evidence                    │
                      └──────────────┬──────────────┘
                                     ▼
                              Final Diagnosis

                 getCurrentDateTime
                 → 时间辅助工具
```

核心原则：

> Agent Tool 按“运维诊断意图”划分，而不是按 Prometheus API 或单个 Metric 划分。  
> PromQL、LogQL 和底层指标名属于基础设施实现细节，不直接暴露给模型。  
> 第一版先把 Prometheus + Loki 两个观测源接稳，再扩 Kubernetes、Tracing/APM、Deployment Change 等能力。
