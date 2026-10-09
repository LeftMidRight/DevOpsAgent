# 上下文管理重构方案 — 第一阶段

> 状态：第一阶段已实现，验收项已勾选。
>
> 本阶段只确定 **运行时会话存储、持久化边界和核心数据结构**。
>
> **本阶段不讨论上下文裁剪、摘要压缩、长期记忆、向量化 Memory 等策略。**

---

## 1. 背景

当前项目的会话上下文主要依赖 PostgreSQL：

```text
请求进入
↓
根据 conversationId 查询 PostgreSQL
↓
读取 ConversationState
↓
读取 conversation_messages
↓
ContextAssembler 重新组装上下文
↓
调用模型
```

这种设计存在两个问题：

1. PostgreSQL 同时承担了“持久化历史”和“运行时上下文来源”两个职责。
2. 每一轮请求都需要重新从数据库读取会话消息，再转换成 Spring AI `Message`。

本次重构希望明确分离：

```text
Runtime Conversation State
和
Durable Conversation History
```

---

# 2. 第一阶段目标

确定以下设计原则：

1. **JVM 内存是活跃会话上下文的主要运行时存储。**
2. **PostgreSQL 只负责持久化已完成的会话历史。**
3. 正常活跃会话不在每一轮请求中重新从 PostgreSQL 构造上下文。
4. PostgreSQL 主要在：
  - 会话恢复；
  - 应用重启后首次访问；
  - JVM 会话缓存 miss；
  - UI 查询历史记录；
   等场景读取。
5. 会话持久化采用 **Turn Commit**。
6. 一次 Agent Run 的临时状态不进入 Conversation State。
7. 上下文裁剪和压缩策略放到第二阶段设计。

---

# 3. 总体架构

目标架构：

```text
                    ChatClient
                        │
                        ▼
               Conversation Memory
                        │
                        ▼
             JVM Runtime State Store
                        │
             sessionId -> state
                        │
             ┌──────────┴──────────┐
             │                     │
             ▼                     ▼
       Model Context          PostgreSQL
                             Durable History
                                  ↑
                                  │
                         restart / cache miss
                                  │
                             lazy hydrate
```

正常运行路径：

```text
用户请求
↓
根据 conversationId 取得 JVM RuntimeConversationState
↓
直接构建当前模型上下文
↓
执行 Agent
↓
成功
↓
Turn Commit
├─ 更新 JVM RuntimeConversationState
└─ 持久化 PostgreSQL
```

恢复路径：

```text
应用重启 / JVM cache miss
↓
根据 conversationId 查询 PostgreSQL
↓
恢复已持久化 Conversation History
↓
hydrate RuntimeConversationState
↓
后续请求继续以内存为主
```

---

# 4. Conversation、Turn、Run 三个概念

第一阶段必须明确区分这三个生命周期。

## 4.1 Conversation

表示一个跨多轮用户交互的会话。

例如：

```text
conversationId = session_123

Turn 1
User
Assistant

Turn 2
User
Assistant

Turn 3
User
Assistant
```

Conversation 可能持续数分钟、数小时甚至多次应用启动。

---



## 4.2 Turn

表示一次完整的：

```text
User Message
+
Final Assistant Message
```

例如：

```text
User:
payment-service 为什么 CPU 高？

Assistant:
根据监控和日志...
```

第一阶段采用：

```text
Turn Commit
```

即只有完整成功的 Turn 才进入正式 Conversation History。

---



## 4.3 Agent Run

表示完成当前 Turn 时的一次 Agent 执行。

例如：

```text
User
↓
Model Call
↓
Tool Call
↓
Tool Result
↓
Model Call
↓
Tool Call
↓
Tool Result
↓
Final Answer
```

Agent Run 是临时执行状态。

现有：

```text
AgentRunContext
```

继续负责：

- runId
- model call budget
- tool call budget
- deadline
- cancellation
- 本轮 evidence / execution state

Agent Run 结束后，该状态即可销毁。

---



# 5. RuntimeConversationState

之前讨论中的 `ActiveConversationContext` 改名为：

```text
RuntimeConversationState
```

这个名字更准确。

它表示：

> 某个 Conversation 当前在 JVM 中的活跃、可用于下一轮模型调用的会话状态。

它不是一次 Agent Run。

生命周期：

```text
RuntimeConversationState
    │
    ├─ Turn 1
    ├─ Turn 2
    ├─ Turn 3
    └─ Turn 4

只要 conversation 仍是活跃状态，
该 state 就可以持续存在 JVM 中。
```

---



# 6. RuntimeConversationState 第一版结构

第一阶段先保持最小化。

建议：

```text
RuntimeConversationState
────────────────────────

conversationId

messages
    当前已经提交的 conversation messages

turnRevision
    当前已经成功提交到第几个 Turn

persistedRevision
    PostgreSQL 已持久化到哪个 Turn

lastAccessAt
    最后访问时间
```

概念 Java 结构：

```java
final class RuntimeConversationState {

    private final String conversationId;

    private final Deque<Message> messages;

    private long turnRevision;

    private long persistedRevision;

    private Instant lastAccessAt;
}
```

这里的 `Message` 优先直接采用：

```java
org.springframework.ai.chat.messages.Message
```

包括：

```text
UserMessage
AssistantMessage
```

避免 Runtime Context 再维护一套：

```text
role = user
role = assistant
```

然后每次调用模型前再做二次转换。

---



# 7. RuntimeConversationState 第一版只保存什么

第一版只保存：

```text
UserMessage
Final AssistantMessage
```

例如：

```text
UserMessage #1
AssistantMessage #1

UserMessage #2
AssistantMessage #2

UserMessage #3
AssistantMessage #3
```

第一版明确不保存：

- tool call
- tool result
- 中间 assistant message
- reasoning
- Prometheus 原始返回
- Logs 原始返回
- RAG chunk
- 模型 usage
- budget 状态
- cancellation 状态

这些属于：

```text
Agent Run
```

而不是 Conversation Runtime State。

---



# 8. 为什么不直接复用当前 ConversationMessage

当前：

```java
record ConversationMessage(
    UUID id,
    String conversationId,
    UUID requestId,
    long sequence,
    String role,
    String content,
    String status,
    int tokenCount,
    Instant createdAt
)
```

这个结构明显带有：

- 数据库主键
- requestId
- sequence
- status
- tokenCount
- createdAt

因此它本质上属于：

```text
Persistence Model
```

而不是：

```text
Runtime Context Model
```

第一阶段应明确：

```text
Persistence Model
≠
Runtime Model
```

数据库记录和模型运行时 Message 可以有不同的数据结构。

---



# 9. Spring AI ChatMemory 的角色

Spring AI 的 `ChatMemory` 适合作为运行时 Conversation Memory 的框架抽象。

可以考虑：

```text
MessageChatMemoryAdvisor
        ↓
SuperBizChatMemory
        ↓
RuntimeConversationStore
        ↓
RuntimeConversationState
```

职责：

```text
MessageChatMemoryAdvisor
────────────────────────
负责：
在模型调用时取得 Conversation Memory
并注入 ChatClient


SuperBizChatMemory
────────────────────────
负责：
按 conversationId 访问运行时会话状态


RuntimeConversationStore
────────────────────────
负责：
在 JVM 中保存活跃 conversation state
```

第一版不直接采用：

```text
JdbcChatMemoryRepository
```

因为本次设计目标就是：

> PostgreSQL 不处在每次模型调用的上下文热路径中。

---



# 10. RuntimeConversationStore

建议给 JVM 会话存储单独定义一个接口。

概念：

```java
interface RuntimeConversationStore {

    Optional<RuntimeConversationState> get(String conversationId);

    void put(RuntimeConversationState state);

    void remove(String conversationId);

    boolean contains(String conversationId);
}
```

第一版实现：

```text
JVM Memory
```

底层可以先：

```text
ConcurrentHashMap
```

但正式实现建议使用：

```text
Bounded Cache
```

支持：

- maximum size
- expire after access
- eviction

避免活跃过的 conversation 永久占用 JVM 内存。

后续可考虑 Caffeine。

---



# 11. PostgreSQL 的新职责

PostgreSQL 不再承担：

```text
每次 Model Call 的 Context Source
```

而是：

```text
Durable Conversation History
```

主要用于：

1. 应用重启后恢复 Conversation。
2. JVM RuntimeConversationStore cache miss。
3. 用户查看历史会话。
4. 用户查看历史消息。
5. 后续重新生成 Context Snapshot。
6. 审计成功完成的 Conversation Turn。

---



# 12. conversation_messages 的定位

现有：

```text
conversation_messages
```

第一阶段继续保留。

但职责重新定义：

> 保存已经成功提交的 Chat History。

数据库中的内容主要应该是：

```text
Turn 1:
User
Final Assistant

Turn 2:
User
Final Assistant

Turn 3:
User
Final Assistant
```

而不是：

```text
模型运行时当前 Context
```

因此：

```text
conversation_messages
≠
ChatMemory
```

更准确地说：

```text
conversation_messages
= Durable Chat History
```

---



# 13. Turn Commit

第一阶段确定采用：

```text
Turn Commit
```

一个完整 Turn：

```text
UserMessage
+
FinalAssistantMessage
```

成功后作为一个逻辑单元提交。

流程：

```text
User Question
↓
PendingTurn
↓
Agent Run
↓
Final Answer
↓
Turn Commit
├─ RuntimeConversationState
└─ PostgreSQL
```

失败：

```text
User Question
↓
Agent Run
↓
失败
↓
不提交 Conversation Turn
```

---



# 14. PendingTurn

采用 Turn Commit 后，当前请求在成功之前不应该直接成为正式 Conversation History。

因此建议引入：

```text
PendingTurn
```

第一版概念：

```java
record PendingTurn(
    UUID requestId,
    UserMessage userMessage
) {}
```

生命周期：

```text
RuntimeConversationState
    │
    │ begin turn
    ▼
PendingTurn
    │
    ▼
AgentRunContext
    │
    ├─ success
    │     ↓
    │   commit
    │
    └─ failed
          ↓
        discard
```

这样失败请求不会污染之后的 Conversation Context。

---



# 15. Turn Commit 的数据库事务

Turn 成功后，PostgreSQL 应把：

```text
UserMessage
+
FinalAssistantMessage
```

作为同一个事务提交。

例如：

```sql
BEGIN;

INSERT user_message;
INSERT assistant_message;
UPDATE conversation_revision;

COMMIT;
```

目标是不再产生：

```text
只有 User，没有 Assistant
```

的半完成 Conversation Turn。

---



# 16. 对现有 PENDING / FAILED 模型的影响

当前代码：

```text
beginTurn
↓
先把 User 写 PostgreSQL
status = PENDING
↓
Agent Run
↓
成功 → COMMITTED
失败 → FAILED
```

如果采用新的严格 Turn Commit：

```text
Agent Run 成功之前
↓
Conversation DB 不写正式历史
```

那么长期来看：

```text
PENDING
FAILED
```

不再需要承担 Conversation History 状态机。

失败运行只属于运行层。

第一阶段先记录这个方向，不立即修改数据库 schema。

---



# 17. Turn Commit 与响应返回时机

当前暂定候选顺序：

```text
Model Success
↓
PostgreSQL Turn Commit
↓
RuntimeConversationState Commit
↓
Return Response
```

原因：

> 用户一旦收到“成功回答”，就意味着该 Turn 已经 durable。

这样避免：

```text
用户已经收到回答
↓
应用崩溃
↓
数据库没有这一轮
```

后续实现时需要进一步考虑：

- Streaming 下何时视为 Final Answer 已完成。
- DB commit 失败时是否向用户返回失败。
- JVM state commit 与 DB commit 的一致性。
- 重试是否可能重复写入。

---



# 18. Runtime 与 Durable 的一致性

第一阶段暂定：

```text
turnRevision
persistedRevision
```

用于描述：

```text
Runtime 已经提交到哪里
PostgreSQL 已经持久化到哪里
```

正常情况：

```text
turnRevision == persistedRevision
```

如果后续引入异步 persistence，再利用两个 revision 判断 dirty state。

第一阶段如果采用同步 Turn Commit，则通常应保持一致。

---



# 19. 会话恢复

应用启动时不加载全部 Conversation。

采用：

```text
Lazy Hydration
```

流程：

```text
用户请求 session_123
↓
RuntimeConversationStore.get(session_123)
↓
MISS
↓
PostgreSQL
↓
加载该 Conversation 已持久化 History
↓
构建 RuntimeConversationState
↓
放入 JVM RuntimeConversationStore
↓
继续请求
```

之后：

```text
Turn 2
Turn 3
Turn 4
```

都直接从 JVM state 获取。

---



# 20. 为什么不在启动时加载全部会话

不采用：

```text
Spring Boot startup
↓
SELECT all conversations
↓
全部加载 JVM
```

原因：

- 历史 conversation 数量会持续增长。
- 大部分旧会话不会被重新访问。
- 增加启动时间。
- 占用不必要 JVM 内存。

因此：

```text
Active Session
→ JVM

Inactive Session
→ PostgreSQL
```

更合理。

---



# 21. 与开源 Agent 方案的关系

调研后主要可以分成三种模式。

## Spring AI / OpenAI Agents

偏：

```text
conversationId
↓
List<Message>
```

优势：

- 简单
- Message-centric
- 非常适合普通多轮会话

我们采用它的：

```text
Message
ChatMemory
Advisor
```

思想。

---



## LangGraph

偏：

```text
Session / Thread
↓
State Object
├─ messages
├─ custom state
└─ workflow state
```

我们借鉴：

> Runtime Conversation 不必只是裸 List，可以有少量 state metadata。

因此采用：

```text
RuntimeConversationState
```

而不是：

```text
Map<String, List<Message>>
```

---



## Pi Agent

偏：

```text
Durable Session Entries
↓
Projection
↓
Model Context
```

它把：

- message
- compaction
- branch
- model change
- usage
- custom entry

等保存在 session log 中。

当前项目已经移除了 Trajectory，因此第一阶段不复制 Pi 的完整 Event Log 模型。

但保留它的重要思想：

> Durable History 和 Model-visible Context 不是同一个东西。

---



# 22. 当前 Trajectory 已移除后的职责

Trajectory 已经从主链路移除。

现在：

```text
RuntimeConversationState
────────────────────────
跨 Turn 会话状态


AgentRunContext
────────────────────────
单次请求运行状态


PostgreSQL Conversation History
────────────────────────
成功 Turn 的持久化历史
```

不再维护单独：

```text
Trajectory Session
```

当前 `runId` 仍然保留，用于：

- 日志关联
- SSE meta
- observability
- 当前 Run 标识

但不对应持久化 Trajectory。

---



# 23. 第一阶段明确不做

本阶段暂不讨论或实现：

- Summary Compression
- Token Window
- Message Trimming
- Runtime Tool Context Compaction
- Long-term Memory
- Vector Memory
- Semantic Conversation Retrieval
- Memory Importance / Decay
- Pi 风格 CompactionEntry
- Run Resume
- Event Sourcing

这些都等第二阶段 Context Policy 设计。

---



# 24. 第一阶段目标结构

最终希望形成：

```text
                         Conversation
                              │
               ┌──────────────┴──────────────┐
               │                             │
               ▼                             ▼
       RuntimeConversationState       PostgreSQL History
               │                             │
          JVM Memory                    Durable Store
               │                             │
               │                             │
               └──── lazy hydration ─────────┘
               │
               ▼
           ChatMemory
               │
               ▼
             Model


Current Turn
─────────────
PendingTurn
    │
    ▼
AgentRunContext
    │
    ├─ failed → discard
    │
    └─ success
          ↓
       Turn Commit
       /         \
      ▼           ▼
JVM State     PostgreSQL
```

---



# 25. 第一阶段实施时的主要代码方向

后续实施预计涉及：

## 新增

```text
RuntimeConversationState
RuntimeConversationStore
InMemoryRuntimeConversationStore
PendingTurn
SuperBizChatMemory
ConversationHydrator
TurnCommitService / ConversationCommitService
```

具体命名在编码前再确认。

---



## 重构

```text
ConversationService
AgentExecutionService
ConversationRepository
```

---



## 最终逐步退场

```text
ContextAssembler
```

它当前混合承担：

- DB 读取
- Context 构造
- Summary
- Window
- Message 转换

未来会拆成：

```text
ChatMemory
Context Policy
History Repository
```

---



# 26. 第一阶段验收标准

- [x] 活跃 Conversation 正常请求不需要查询 PostgreSQL 读取历史。
- [x] JVM 中按 conversationId 保存 RuntimeConversationState。
- [x] Runtime state 直接使用 Spring AI Message。
- [x] 正式 Conversation History 只保存成功 Turn。
- [x] 一个 Turn 的 User + Final Assistant 以数据库事务提交。
- [x] Agent Run 失败不污染 RuntimeConversationState。
- [x] Agent Run 失败不产生半完成 Conversation History。
- [x] JVM cache miss 可以从 PostgreSQL lazy hydrate。
- [x] 应用重启后可以根据 conversationId 恢复。
- [x] AgentRunContext 和 RuntimeConversationState 生命周期明确分开。
- [x] 不重新引入 Trajectory。
- [x] 本阶段不实现裁剪/压缩。

---



# 27. 第一阶段结论

当前确定的核心原则：

```text
JVM RuntimeConversationState
= 当前模型运行所依赖的会话状态


PostgreSQL
= 已成功 Turn 的持久化 Chat History


PendingTurn
= 当前尚未完成的用户 Turn


AgentRunContext
= 当前 Turn 内的执行状态


Turn Commit
= User + Final Assistant 成功后一起提交
```

因此未来不再采用：

```text
每个请求
↓
从 PostgreSQL 读取全部 Conversation
↓
重新组装模型上下文
```

而是：

```text
正常请求
↓
直接从 JVM RuntimeConversationState 获取


应用重启 / cache miss
↓
PostgreSQL lazy hydrate
↓
恢复 JVM state
```

这是上下文管理重构第一阶段的基础。

第二阶段再在这个结构上设计：

```text
Context Trimming
+
Context Compression
+
Summary State
```

