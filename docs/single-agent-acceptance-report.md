# 单 Agent 改造验收报告

日期：2026-09-16  
验收依据：`docs/single-agent-refactoring-plan.md`  
验收对象：当前工作区代码，包含未提交与未跟踪文件。  
结论：**尚未全部实现，当前不通过最终验收。** 主入口已切换为单 Agent，但存在启动装配失败、流式答案提取错误、运行终止不完整和证据校验缺口。

本次没有修改业务代码。新增了本报告与 `tmp/single-agent-acceptance/` 下的独立复现程序及输出。

## 1. 实际执行的验证

| 验证 | 结果 | 范围 |
|---|---|---|
| `mvn -o test` | BUILD SUCCESS；35 个测试，0 失败、0 错误、0 跳过 | 当前默认 Surefire 测试集 |
| `node --check src/main/resources/static/app.js` | 通过 | JavaScript 语法，不包含浏览器交互验证 |
| 独立 Spring 装配验证 | 失败 | 注册实际 `EvidenceParser`、`DiagnosisService`，未使用外部服务 |
| 脚本模型驱动真实 ReactAgent 与执行服务 | 复现流式最终答案错误 | 模型和数据库服务使用 Mock，Agent、工具循环及 `AgentExecutionService` 使用真实实现 |
| 同一执行服务的截止时间验证 | 超时后仍成功提交 | 总预算 1 秒，模型响应延迟 1.2 秒 |
| 证据与报告解析边界验证 | 复现多项错误接受 | 调用实际证据分类器、报告解析器和诊断校验器 |

复现文件：

- `tmp/single-agent-acceptance/AcceptanceProbe.java`
- `tmp/single-agent-acceptance/probe-output.txt`

独立探针是诊断程序，输出问题现象，不以进程退出码表示验收通过。

本次未启动完整外部服务环境，未调用真实 DashScope、Prometheus、腾讯云 MCP，也未执行 PostgreSQL 集成验证。`HybridRetrievalEvalIT` 没有进入上述 35 个默认测试；其存在不代表本次已执行。

## 2. 按计划逐项判断

| 计划项 | 状态 | 判断依据 |
|---|---|---|
| 三个业务接口委托统一执行入口 | 已实现代码接入 | `/chat`、`/chat_stream`、`/ai_ops` 使用 `AgentExecutionService` |
| 主路径只有一个业务 ReactAgent | 已实现代码接入 | `UnifiedAgentFactory` 构建同一个命名 Agent，通过策略切换 CHAT / OPS |
| 显式模型配置 | 已实现 | 主路径使用 `agent.model` |
| 运维快捷接口无请求体兼容 | 已实现代码接入 | `@RequestBody(required=false)`、默认任务、固定 OPS 模式 |
| 运维与聊天共用会话 | 已实现代码接入，缺完整回归 | 前端发送当前 ID；执行服务调用相同会话与上下文服务 |
| 任务模式写入消息元数据 | 已实现代码接入 | 用户和 assistant 消息 metadata 写入 mode |
| 本地日志工具仅在 Mock 时注册 | 已实现 | `QueryLogsTools` 增加条件装配 |
| MCP 不可用时正常启动 / 工具模式互斥 | 未完成验收 | 只有空 Provider 兜底；真实连接仍默认启用并使用占位地址，缺启动和切换测试 |
| 单请求模型 / 工具调用次数限制 | 基础实现存在 | Hook 和工具包装器维护请求级计数 |
| 总耗时、断线取消、迟到结果隔离 | 未完成 | 截止时间是调用前检查；没有完整取消链路，已复现超时仍提交 |
| 本轮工具消息与历史共同控制总 token | 未实现 | 仅入场历史预算和单个工具结果字符截断 |
| 重复失败的参数标准化 | 部分实现 | 参数只去首尾空白并截取前 300 字符，未规范化 JSON |
| 流式最终结果独立于中间说明 | 未完成 | 实际 ReAct 探针回退到所有增量拼接 |
| 实际工具证据采集 | 基础实现存在 | 本地与 MCP ToolCallback 统一包装并产生 ev-ID |
| 证据有效性与未知归属校验 | 未完成 | 空结果 / 错误可被判成功，未知归属可放行 |
| 结构化报告解析及一次格式修正 | 部分实现 | 有解析和修正，但缺必需字段 / 语义形状校验 |
| 两种模式统一轨迹 | 已接入代码，缺端到端验证 | 使用统一 runId、任务模式、传输方式和采集 Hook |
| 前端共享 SSE 解析、过程与终态分离 | 未完成 | 两份按行解析逻辑；CHAT 仍累计所有正文，meta 仅打印 |
| 真实执行路径的可重复自动化测试 | 未完成 | 仓库新增测试主要是模式、解析、控制器 Mock；缺执行服务与取消等测试 |
| 遗留代码与文档清理 | 未完成 | 旧服务仍为 Spring Bean；README 仍介绍多 Agent |

## 3. 阻塞与关键缺陷

### F1 — P1：DiagnosisService 无法通过当前 Spring 构造器装配

位置：`src/main/java/org/example/diagnosis/DiagnosisService.java:18`。

类有两个公开构造器，但没有无参构造器，也没有明确标注注入构造器。以标准注解上下文注册 `EvidenceParser` 和该服务后，实际报错：

```text
BeanCreationException: Error creating bean with name 'diagnosisService'
No default constructor found
NoSuchMethodException: org.example.diagnosis.DiagnosisService.<init>()
```

新的 `OpsReportService` 依赖此 Bean；主应用扫描到该服务时，当前装配定义会构成启动阻塞。此问题在此前工作区中也已存在，并非断言由本次重构引入，但当前验收必须解决。

建议：明确唯一注入构造器，或显式配置 Bean，保留其他构造器仅用于测试；增加实际 Spring 装配测试，而不只在测试中手工 `new`。

### F2 — P1：流式最终答案提取在实际框架路径中失效

位置：`src/main/java/org/example/agent/AgentExecutionService.java:152`、`:167`。

实现只尝试从 `AGENT_MODEL_FINISHED` 类型的 `StreamingOutput` 取完整回答，取不到就使用整个 `displayBuffer`。

独立探针以脚本模型驱动真实 ReactAgent：第一轮输出 `CHECKING;` 并调用工具，第二轮输出 `FINAL`。实际执行结果为：

```text
streaming_model_rounds=2
streaming_persisted_answer=CHECKING;FINAL
streaming_displayed_answer=CHECKING;FINAL
```

执行日志同时出现“未从框架终态提取到完整回答，使用增量拼接作为最终答案”。应保存的最终答案是 `FINAL`；当前工具调用前的说明仍被提交到会话中。

即使只修复后端，CHAT 前端依旧累计全部 `content`，控制器也没有向 CHAT 发送权威最终答案替换事件，因此界面与数据库一致性仍需一起处理。

建议：从当前依赖实际返回的终态消息列表取最终 assistant 消息；缺失时明确失败，不使用跨轮累积文本兜底。为前端设计最终答案替换机制，并将本次实际 ReAct 复现纳入自动化测试。

### F3 — P1：超时与断线取消没有接入运行终止

位置：`src/main/java/org/example/controller/ChatController.java:244`；`src/main/java/org/example/agent/AgentExecutionService.java:74`、`:110`、`:161`。

当前没有注册 emitter 的超时、错误和完成回调来取消运行；`sendSafe` 捕获 IOException 后仅记录日志。执行服务调用无截止时间的 `blockLast()`，同一会话的信号量等待也没有超时。

`AgentRunContext` 在获取会话锁之后才创建，截止时间不包含等待。模型和工具只在调用前检查预算，返回后以及提交前没有再次拒绝迟到结果。

已复现：配置最大耗时 1 秒，模型延迟 1.2 秒返回，实际总耗时约 1247 ms，`LATE_FINAL` 仍被作为成功答案提交。

影响：客户端已离开或连接超时后任务仍可能继续消耗模型 / 工具资源，持有会话锁，并把用户以为已终止的任务写入记忆。

建议：建立请求级取消句柄和一次性终态，截止时间覆盖锁等待及整个执行；取消订阅和后续调度，在模型 / 工具返回后及提交前检查终态，并配置实际客户端超时。

### F4 — P1：失败、空结果和未知归属仍可成为“有效证据”

位置：`src/main/java/org/example/agent/RunEvidenceAnalyzer.java:29`；`src/main/java/org/example/diagnosis/Evidence.java:35`。

实际边界验证：

| 输入 | 当前判定 | 期望处理 |
|---|---|---|
| `[]` | SUCCESS | EMPTY |
| `{broken` | SUCCESS | JSON 工具协议解析失败，不当作有效日志 / 告警 |
| `{"isError":true,"content":[{"type":"text","text":"query failed"}]}` | SUCCESS | 对该错误协议识别为 ERROR |
| 证据和结论均缺服务 / 告警归属 | SUPPORTED | 未知归属不能因两边都空就通过归属校验 |

分类器对无法解析的结果统一按成功文本处理，未按不同工具的返回协议进行识别；匹配器将结论中的空归属视为通配。

另一个缺口是：一次返回多条告警 / 多个服务的工具调用只生成一个证据 ID，提取整个 JSON 中第一个服务和第一个告警。后续条目可能被误归属，甚至服务与告警来自不同条目。

建议：按工具协议解析有效性，保留时间工具等合法纯文本结果；按实际告警 / 日志条目建立证据归属；对未知归属做明确降级，不能把任意非空文本等同于诊断事实。

### F5 — P2：缺少本轮总上下文预算和诊断预算耗尽兜底

位置：`src/main/java/org/example/agent/BudgetEnforcementHook.java:31`；`src/main/java/org/example/agent/AgentRunContext.java:73`。

当前 Hook 只检查调用次数与时间，不估算持续增加的工具调用 / 结果消息。虽然每个工具结果最多返回约 6000 字符，但最多 20 次调用仍可积累约 120000 字符，超过配置的 24000 token 预算的情况没有被约束。

模型次数耗尽直接抛异常，并由执行服务将整个轮次标为失败，没有计划要求的“预留报告预算 / 交付已有部分诊断或确定性未完成说明”。

建议：在模型调用前检查完整消息预算，成对处理工具消息；为总结预留预算，预算耗尽时交付明确的部分结果或未完成说明。

### F6 — P2：报告解析仅验证 JSON 可反序列化，未验证报告契约

位置：`src/main/java/org/example/agent/report/OpsReportParser.java:28`。

探针确认 `{"foo":1}` 会被接受为有效 `OpsReportDraft`。由于忽略未知字段、缺失列表变为空列表，该输入不会触发格式修正，而会进入普通报告渲染。

建议：验证必要字段和结构；区分合法“无告警 / 无证据报告”与“根本没有报告字段”的 JSON。解析成功不能等价于任务输出契约满足。

## 4. 其他尚未完成的计划项

- **SSE 解析与元信息**：前端聊天和运维各自实现按行解析，跳过空行，没有公共事件解析器；`meta` 只打印到控制台，未更新会话状态。后端在任务完成后才发送 meta，失败的新会话调用方无法通过它取得本轮 ID。
- **证据可追溯信息**：`RunEvidence` 没有 `toolCallId`；证据内容只在本轮内存里保留有限字符，轨迹记录的是已经包装 / 截断的工具结果，不是独立的完整原始证据存储。工具结果截断提示“完整结果见证据记录”与实际保存上限不一致。
- **重复失败参数键**：仅 `strip()` 再截前 300 字符；相同 JSON 的键顺序或内部空白变化可以绕过判重，不同长参数又可能被合并成同一键。
- **真实 MCP 接入**：空 Provider Bean 不能证明启用的 MCP 连接失败时自动配置仍能正常启动。配置仍有占位 SSE 地址；Mock 开关不会自动关闭真实连接。没有据此完成生产接入验收。
- **旧实现清理**：`AiOpsService` 和 `ChatService` 标记 Deprecated，但仍为 `@Service`，旧 Supervisor / 子 Agent 构建代码和提示词仍在；`RagService` 也未清理。主控制器没有调用旧服务，这一点已确认。
- **文档未同步**：README 仍写 Planner-Executor-Replanner，多 Agent 描述尚未更新；上下文文档也没有补齐新模式和终止语义。
- **核心测试缺失**：没有覆盖实际 `AgentExecutionService`、统一工厂装配、工具包装器、截止时间、断线竞态、跨模式会话和总预算的仓库自动化测试。控制器测试将执行服务整体 Mock，因此不能发现上述运行缺陷。

## 5. 分阶段结论

| 阶段 | 结论 |
|---|---|
| 一：单 Agent 主链路 | 架构替换已做，但存在启动装配阻塞，尚不能整体验收通过 |
| 二：会话、流式与运行控制 | 部分完成；最终答案、取消 / 超时、总上下文预算是主要缺口 |
| 三：证据和报告校验 | 有基础链路，但边界可误接受，未达到计划标准 |
| 四：回归与收尾 | 未完成；缺真实执行测试、外部依赖验证记录及旧实现 / 文档清理 |

建议修复顺序：F1 启动装配 → F2 最终答案 → F3 运行终止 → F4 证据有效性 → F5/F6 预算与报告契约 → 前端、配置和文档收尾。修复后重新运行本次复现，并将其转为正式回归测试，再开展真实环境集成验收。
