# Spring AI 2.0.1 引入记录

## 已完成的顺序

1. 修复 SSE 增量输出，逐次记录模型 token 用量，增加 Actuator / Prometheus。
2. 升级 Spring Boot 4.0.8、Spring AI 2.0.1；项目 JSON 处理迁移到 Jackson 3；聊天调用迁移到新的 OpenAI SDK。
3. 用 `ChatClient` + `ToolCallingAdvisor` 接管同步及流式工具循环；`RunModelAdvisor` 放在工具循环内部，在每次模型调用前后执行原有预算、轨迹和输出处理。

PostgreSQL 会话持久化、摘要及上下文组装、会话锁、工具证据包装、MinIO 文档存储和现有混合检索保持原有流程。

## 配置及运行行为

| 配置 | 默认值 | 行为 |
| --- | --- | --- |
| `agent.budget.max-model-calls` | 12 | 每次模型调用计数，耗尽后交付预算终止说明 |
| `agent.budget.max-tool-calls` | 20 | 一轮所有工具调用合计上限 |
| `agent.budget.max-calls-per-tool` | 6 | 一轮同一工具上限，改变参数仍计数 |
| `agent.budget.max-duration-seconds` | 240 | 超时或取消时拒绝提交迟到结果 |
| `volcengine.chat.base-url` | 火山方舟 API 根路径 | SDK 自动追加 `/chat/completions`，已删除 `completions-path` |

工具超限使用 `RETURN_ERROR_RESPONSE`，跳过超限调用并将错误返回给模型；模型可以利用已有信息回答。工具预算按当前轮对话历史计算，请求之间不共享计数。

流式内容收到后立即发出 SSE；推理字段从 SDK 的累计内容转换为增量。最终会话答案仍取最后一个模型轮次，工具调用前的中间说明不拼入持久化答案。

`ToolCallingAdvisor` 的响应用量包含工具循环各轮累计值。轨迹通过内部 `RunModelAdvisor` 记录每轮原始用量，再在结束事件求和，避免累计值被重复相加。模型 token 指标通过 Spring AI 的 observation 自动配置导出。

## 验证

```powershell
mvn clean verify
```

回归覆盖：

- 本地 HTTP 模拟豆包，验证 API 路径、`thinking` 参数、同步与流式推理内容、流末尾 usage 和 token 指标。
- 同步与流式工具循环，批次及跨模型轮次的调用上限、请求间计数隔离。
- 每轮 token 用量、整轮总用量及工具结果轨迹；模型次数预算、流式取消和迟到模型结果。
- Boot 4 随机端口启动、静态首页、知识库 JSON 接口、Prometheus 导出。
- 现有会话、上下文、检索、文档及证据校验测试。

启动测试模拟数据库和对象存储。真实豆包、PostgreSQL / pgvector、MinIO 和 MCP 联调尚未执行；真实检索评估仍需要配置这些服务并显式运行 `HybridRetrievalEvalIT`。

结构化运维输出、真实 MCP 接入和大量工具时的工具搜索属于后续条件项，需在相应业务入口或服务启用后引入。

## 官方参考

- [升级说明](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)
- [Advisors API](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- [工具调用](https://docs.spring.io/spring-ai/reference/api/tools.html)
