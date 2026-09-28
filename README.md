# DevOpsAgent

> 基于 Spring Boot + AI Agent 的智能问答与运维系统

## 📖 项目简介

企业级智能业务代理系统，包含两大核心模块：

### 1. RAG 智能问答
集成 PostgreSQL + pgvector 向量数据库和阿里云 DashScope，提供基于检索增强生成的智能问答能力，支持多轮对话和流式输出。

### 2. AIOps 智能运维
基于统一业务 Agent 的自动化运维：同一套工具与会话完成告警分析、日志查询、智能诊断和报告生成。问答（CHAT）与运维（OPS）共享执行入口，不再运行 Supervisor / Planner / Executor 多 Agent。

## 🚀 核心特性

- ✅ **RAG 问答**: 向量检索 + 多轮对话 + 流式输出
- ✅ **AIOps 运维**: 单 Agent 诊断 + 证据校验 + 服务端报告渲染
- ✅ **工具集成**: 文档检索、告警查询、日志分析、时间工具
- ✅ **会话管理**: PostgreSQL 持久化、滚动摘要、token 预算与失败隔离
- ✅ **运行轨迹**: append-only JSONL，记录模型消息、工具调用与工具结果
- ✅ **Web 界面**: 提供测试界面和 RESTful API


## 🛠️ 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.0 | 应用框架 |
| Spring AI | - | AI Agent 框架 |
| DashScope | 2.17.0 | 阿里云 AI 服务 |
| PostgreSQL + pgvector | 16 | 向量数据库（混合检索） |
| MinIO | - | 对象存储（文档原文） |

## 📦 核心模块

```
DevOpsAgent/
├── src/main/java/org/example/
│   ├── controller/
│   │   └── ChatController.java        # 统一接口控制器 ⭐
│   ├── agent/
│   │   ├── AgentExecutionService.java # 单 Agent 执行入口
│   │   ├── UnifiedAgentFactory.java   # 模型、工具与 Hook 装配
│   │   └── tool/                      # Agent 工具集
│   ├── context/                        # 上下文预算、组装与滚动摘要
│   ├── conversation/                   # 会话持久化、事务与并发控制
│   ├── diagnosis/                      # 证据校验
│   ├── trajectory/                     # Agent JSONL 运行轨迹
│   └── config/                        # 配置类
├── src/main/resources/
│   ├── static/                        # Web 界面
│   └── application.yml                # 应用配置
└── aiops-docs/                        # 运维文档库
```


## 📡 核心接口

### 1. 智能问答接口

**流式对话（推荐）**
```bash
POST /api/chat_stream
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```
支持 SSE 流式输出、自动工具调用、多轮对话。

**普通对话**
```bash
POST /api/chat
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```
一次性返回完整结果，支持工具调用和多轮对话。

模型上下文由后端数据库中的消息日志、滚动摘要和最近消息统一组装；浏览器本地历史只负责界面展示。设计细节见 [上下文管理设计](docs/context-management-design.md)。

### 2. AIOps 智能运维接口

```bash
POST /api/ai_ops
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "重点检查 payment-service 最近一小时的异常"
}
```
固定 OPS 模式，走与问答相同的单 Agent 执行入口（SSE）。可省略请求体；未指定 `Id` 时创建新会话。

问答接口可增加可选 `"Mode": "OPS"`，缺省为 `CHAT`。两种模式共用会话、工具与运行轨迹。

### 3. 会话管理

- `POST /api/chat/clear` - 清空会话历史
- `GET /api/chat/session/{sessionId}` - 获取会话信息

### 4. 文件管理

文档处理分为两个阶段：**上传**（文件存入 MinIO 对象存储并登记文档元数据）与**分块**（从对象存储读取原文，分片、向量化后写入向量库）。

- `POST /api/upload` - 上传文件（存入 MinIO）并自动触发分块
- `POST /api/knowledge-base/documents/{id}/chunk` - 重新分块（用于失败重试）
- `GET /api/knowledge-base/documents` - 文档列表（含分块状态）
- `DELETE /api/knowledge-base/documents?id={id}` - 删除文档（分片 + 文档行 + 对象存储原文）
- `GET /db/health` - 数据库健康检查


## ⚙️ 核心配置

### application.yml

```yaml
server:
  port: 9900

# PostgreSQL 数据源
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/superbiz
    username: superbiz
    password: superbiz
    driver-class-name: org.postgresql.Driver
  ai:
    dashscope:
      api-key: "${DASHSCOPE_API_KEY}" # 环境变量

# RAG 配置
rag:
  top-k: 3
  model: "qwen3-max"

# 文档原文对象存储
minio:
  endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
  access-key: ${MINIO_ACCESS_KEY:superbiz}
  secret-key: ${MINIO_SECRET_KEY:superbiz}
  bucket: ${MINIO_BUCKET:superbiz-documents}

# 文档分片
document:
  chunk:
    max-size: 800
    overlap: 100
```

### 环境变量

```bash
export DASHSCOPE_API_KEY=your-api-key  # 或 VOLCENGINE_API_KEY（主链路模型）
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=superbiz
export MINIO_SECRET_KEY=superbiz
```


## 🚀 快速开始

### 1. 环境准备

```bash
# 设置 API Key
export DASHSCOPE_API_KEY=your-api-key
```

### 2. 启动应用

方法一： 手动启动
```bash
# 1. 启动 PostgreSQL + pgvector + MinIO
docker compose -f vector-database.yml up -d

# 2. 启动服务
mvn spring-boot:run
```

方法二：一键启动
```bash
make init  # 会自动启动 PostgreSQL 与 MinIO，并上传运维文档（存入 MinIO 后自动分块）
```


### 3. 使用示例

**Web 界面**
```
http://localhost:9900
```

MinIO 控制台：`http://localhost:9001`（superbiz / superbiz）

**命令行**
```bash
# 上传文档（存入 MinIO 并自动分块）
curl -X POST http://localhost:9900/api/upload \
  -F "file=@document.txt"

# 查看文档列表（含分块状态）
curl http://localhost:9900/api/knowledge-base/documents

# 重新分块（失败重试）
curl -X POST http://localhost:9900/api/knowledge-base/documents/{id}/chunk

# 删除文档
curl -X DELETE "http://localhost:9900/api/knowledge-base/documents?id={id}"

# 智能问答
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test","Question":"什么是向量数据库？"}'

# 健康检查
curl http://localhost:9900/db/health
```

> 上传以文档 ID 为逻辑主键，同名文件不会互相覆盖，重复上传会产生新文档。


**版本**: v1.0.0  
**作者**: chief  
**许可证**: MIT
