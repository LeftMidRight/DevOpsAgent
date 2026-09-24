# 问答 Agent 上下文管理

## 目标

模型上下文只由后端组装。浏览器 `localStorage` 仅用于界面展示，不参与模型推理，也不是会话事实来源。

后端采用“完整消息日志 + 滚动摘要 + 最近原始消息”的分层结构：

1. `conversation_messages` 保存每轮用户消息和最终回答，提供可恢复、可审计的原始记录。
2. `conversations.summary` 保存已经压缩的较早对话状态，`summary_until_seq` 标记摘要覆盖边界。
3. 每次调用只把摘要和预算内的最近原始消息传给 Agent。

## 一轮对话的生命周期

1. 按会话 ID 串行化请求；锁等待纳入本轮截止时间。
2. 先写入状态为 `PENDING` 的用户消息，并立即通过 SSE `meta` 返回 `sessionId` / `runId`。
3. 根据本轮问题的实际 token 估算决定是否压缩较早历史。
4. 以标准 `UserMessage` / `AssistantMessage` 列表调用统一 `ReactAgent`（CHAT 与 OPS 同一 Agent，仅任务策略不同）。
5. 成功时在一个事务中提交用户消息并追加 assistant **最终**回答；超时、取消或异常把用户消息标为 `FAILED`。
6. 预算耗尽（模型次数 / 本轮消息 token）时交付部分结果或确定性未完成说明，不把迟到结果改判为成功。

`FAILED` 消息不会进入后续模型上下文，因此失败请求不会污染记忆。工具调用前的中间说明不会拼入最终持久化答案。

CHAT 与 OPS 可在同一会话切换与追问。涉及“当前状态”的运维追问应重新取证，不能把旧告警当成实时数据。

## 上下文预算

总预算按以下部分隔离：

- 模型输出预留；
- 系统提示和工具定义预留；
- 滚动摘要预算；
- 最近原始消息预算。

压缩判断会同时计算历史消息和本轮 `PENDING` 问题。摘要切点只落在完整问答轮次之间；如果预算只能容纳回答而容纳不下对应问题，则整轮进入摘要，避免出现孤立的 assistant 消息。

本轮工具调用与结果消息也计入运行中的上下文预算：`BudgetEnforcementHook` 在每次模型调用前估算当前消息列表 token，超出 `max-context-tokens` 减去输出与系统/工具预留后的额度即停止继续取证。

摘要生成失败不会阻断主请求，系统会退化为有界的最近消息窗口。

## 安全边界

- 摘要使用固定结构，要求仅提取事实、约束、决定、未解决问题和失败尝试。
- 摘要被标记为应用生成的参考数据，不作为用户指令。
- 系统提示明确规定工具结果和检索内容不能覆盖系统指令。

## Agent 运行轨迹

运行轨迹和对话记忆是两个不同的数据平面：

- PostgreSQL 对话记忆负责下一轮模型推理；
- JSONL 运行轨迹负责审计、排错、评估和回放。

轨迹按会话写入 `trajectory.base-dir`。文件名使用会话 ID 的 SHA-256，防止路径穿越和非法文件名；每行是一个独立 JSON 事件，只追加、不改写。事件信封包含：

```json
{
  "schemaVersion": 1,
  "eventId": "...",
  "runId": "...",
  "conversationId": "...",
  "requestId": "...",
  "sequence": 4,
  "timestamp": "...",
  "type": "tool.result",
  "agent": "intelligent_assistant",
  "payload": {}
}
```

当前事件类型包括：

- `run.started`、`run.completed`、`run.failed`；
- `message.user`、`message.assistant`；
- `tool.result`；
- `context.assembled`、`model.usage`。

`message.assistant` 中保留框架暴露的正文、metadata 和结构化 tool calls；`tool.result` 保留 tool call ID、工具名和结果。系统不会主动开启或记录模型隐藏思维链。工具参数、工具结果和 metadata 会限制长度，并按敏感字段名脱敏。

完整历史上下文不会在每次运行中重复写入；一条运行轨迹只记录当前 user 消息以及本轮新增的 assistant/tool 事件。轨迹也不会直接回灌下一轮上下文，后续若要从轨迹学习，应经过结果验证、过程评价和跨轨迹归纳，再形成可检索的经验知识。

清空聊天只清除 PostgreSQL 中用于推理的会话记忆，不改写 append-only 审计文件。生产环境需要根据隐私政策配置独立的轨迹保留和删除机制。

## 当前范围

当前 JSONL 写入器支持单应用实例内的并发保护。跨多个应用实例部署时，需要同时把 JVM 会话串行器替换为数据库 advisory lock 或分布式锁，并将本地 JSONL 换成具备原子追加语义的共享日志或对象存储入口。
