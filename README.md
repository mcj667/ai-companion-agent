# AI 情感陪伴 Agent

![Java](https://img.shields.io/badge/Java-21-blue?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?logo=springboot&logoColor=white)
![Spring AI](https://img.shields.io/badge/Spring_AI-1.1-6DB33F)
![Vue](https://img.shields.io/badge/Vue-3-4FC08D?logo=vuedotjs&logoColor=white)

一个基于 **Spring Boot 3 + Spring AI** 的对话式 AI Agent，面向情感咨询场景。

项目围绕一个目标展开：**把 Spring AI 的主要能力完整落到一个可运行的应用里**——多轮对话记忆、流式输出、结构化输出、RAG 知识库问答、Function Calling 工具调用，以及通过 **MCP** 接入外部进程提供的工具。

**技术栈**：Spring Boot 3.5 · Java 21 · Spring AI 1.1 · DeepSeek（对话）· 阿里百炼（向量化）· Redis · Vue 3 · Vite

**项目状态**：后端 10 个接口 · Vue 3 前端 · 36 个测试通过

<!-- 截图：把图放进 docs/images/ 后取消下面两行注释
![主界面](docs/images/main.png)
![流式输出](docs/images/stream.gif)
-->

---

## 功能

| 模式 | 说明 | 使用的能力 |
|---|---|---|
| 普通对话 | 单轮问答 | 多轮记忆（Redis） |
| 流式对话 | 逐字返回 | SSE |
| 知识库问答 | 检索知识库后回答 | RAG（向量检索） |
| 联网搜索 | 模型自主决定是否联网检索、抓取网页 | Function Calling（`@Tool`） |
| MCP 工具 | 工具由外部 MCP Server 提供 | MCP（高德地图，12 个工具） |
| 结构化报告 | 返回结构化 JSON 建议 | Structured Output |

---

## 架构

```
┌──────────────────┐
│    Vue 3 前端     │
└─────────┬────────┘
          │  /api（Vite 代理）
┌─────────▼──────────────────────────────────────────┐
│                   Spring Boot 3                    │
│              ChatClient + Advisor 链               │
│            （对话记忆 · 日志 · RAG 检索）             │
└──┬─────────┬─────────┬───────────┬─────────────────┘
   │         │         │           │
DeepSeek    百炼      Redis     工具调用
 对话模型  向量化    对话记忆   ├─ 本地 @Tool：联网搜索、网页抓取
                    会话名单   └─ MCP Server（独立进程）：高德地图 12 个工具
                    模式记录        （工具实现不在本项目中）
```

---

## 核心设计

### 1. MCP 接入的代价：启动期强耦合

本地 `@Tool` 方法在编译期就已确定，调用失败只影响单个工具；而 MCP 工具是应用**启动时**从外部进程拉取的——
因此 MCP Server 不可用会导致**整个 Spring 容器启动失败**，而不是单个模式不可用。
应对方式是两条：把 MCP Server 的依赖预先安装到本地，使其不再依赖启动时的网络；并保留 `initialized: false` 作为应急开关。

### 2. 保证工具真的会被调用

工具注册成功不等于模型会调用它。系统提示词与工具定义是**两套独立注入的指令**，当提示词的约束范围
（如"只讨论情感类话题"）与工具的适用范围冲突时，模型会优先服从提示词。
做法是在提示词中为工具明确划定适用范围，并通过 mock `ChatModel` 捕获实际下发的请求来验证工具是否真的存在——
把"提示词与工具冲突"和"工具根本没发出去"这两类完全不同的故障分开排查。

### 3. Function Calling 的执行模型

模型本身不执行任何代码，它只输出"要调用哪个工具、参数是什么"；实际执行的是本地的 Java 方法，
返回结果作为一条工具消息回填给模型，再由模型生成最终回答。
因此**一轮工具调用至少包含两次模型调用**，这也是开启工具后延迟与成本上升的原因。

### 4. 配置缺失时的分级降级

核心链路的 key 缺失时快速失败（`DEEPSEEK_API_KEY` 未配置会让应用直接启动失败）；
增强能力的 key 缺失时优雅降级（向量化 / 联网搜索不可用，其余模式不受影响）。
同理，向量化的运行时异常被 try/catch 兜住，RAG 不可用不会影响对话功能。

---

## 快速开始

**环境要求**：JDK 21 · Redis（`localhost:6379`）· Node 18+（仅前端需要）

### 1. 配置 API Key（通过环境变量，不写入代码）

```bash
DEEPSEEK_API_KEY=sk-xxxx     # 必填，对话模型
DASHSCOPE_API_KEY=sk-xxxx    # 可缺省，向量化（缺省时 RAG 降级为普通对话）
BOCHA_API_KEY=sk-xxxx        # 可缺省，联网搜索（缺省时该模式不可用）
```

- **IDEA**：`Run → Edit Configurations… → Environment variables`
- **命令行**：`setx DEEPSEEK_API_KEY "sk-xxxx"`（对之后新开的终端生效）

Key 申请地址：DeepSeek <https://platform.deepseek.com/> · 百炼 <https://bailian.console.aliyun.com/> · 博查 <https://open.bochaai.com/>

> ⚠️ `DASHSCOPE_API_KEY` 的默认值 `not-configured` 不能改为空值——DashScope 的自动配置会校验 key，
> 空值会导致整个 Spring 上下文无法启动。该字符串是代码中约定的"未配置"标记，会走优雅降级路径。

### 2. 启动

```bash
./mvnw spring-boot:run                        # 后端 → http://localhost:8123/api
cd frontend && npm install && npm run dev     # 前端 → http://localhost:5173
```

- 接口文档（Knife4j）：<http://localhost:8123/api/doc.html>
- 健康检查：<http://localhost:8123/api/health>

> 首次启动会读取 `src/main/resources/document/` 下的知识库并调用百炼向量化，结果落盘到 `data/` 下的 JSON 文件；
> 之后启动直接读文件，不会重复调用接口。需要重新生成时，删除该文件再启动即可。

---

## 接口一览

`context-path` 为 `/api`，完整路径需加前缀，例如 `POST /api/love/chat`。对话类接口均接收 `message` 与 `chatId` 两个参数。

| 方法 | 路径 | 返回 |
|---|---|---|
| POST | `/love/chat` | 纯文本 |
| POST | `/love/chat/stream` | **SSE 流式**，逐字返回 |
| POST | `/love/rag` | 纯文本，回答前先检索知识库 |
| POST | `/love/search` | 纯文本，模型自主决定是否联网检索 / 抓取网页 |
| POST | `/love/mcp` | 纯文本，工具来自 MCP Server |
| POST | `/love/report` | `LoveReport` JSON（title + suggestions） |
| GET | `/love/sessions` | 会话列表，按最近活跃排序 |
| GET | `/love/history` | 指定会话的消息列表 |
| DELETE | `/love/sessions` | 删除会话 |
| GET | `/health` | 固定字符串 `ok` |

> 使用 curl 测试流式接口需加 `-N` 关闭缓冲，否则看不到逐字效果：
> `curl.exe -N -X POST "http://localhost:8123/api/love/chat/stream?message=你好&chatId=test1"`
> （中文参数在 PowerShell 下可能被 Tomcat 拒绝，建议直接通过前端页面测试。）

---

## 项目结构

```
src/main/java/com/aicompanion/agent/
├── app/LoveApp.java         组装 ChatClient + 六个对话方法
├── controller/              10 个接口
├── advisor/                 自定义日志 Advisor
├── memory/                  对话记忆（Redis + Kryo）、会话名单、模式记录
├── service/                 Redis 数据到前端视图的转换
├── rag/                     向量库 + markdown 知识库加载
├── tool/                    Function Calling：联网搜索、网页抓取
└── demo/invoke/             Spring AI 各类 API 的调用示例

src/main/resources/
├── document/                知识库（3 个 markdown 文件）
└── mcp-servers.json         MCP Server 清单（高德地图）

src/test/java/               36 个测试
frontend/                    Vue 3 + Vite
```

---

## 更多文档

- **[设计说明](docs/设计说明.md)** — Advisor 链、对话记忆与会话历史、RAG、Function Calling、MCP 的实现细节与设计取舍，以及 Redis 的 key 规划
- **[已知限制与排查](docs/已知限制与排查.md)** — 已知限制清单、常见问题排查、待办
- **[更新日志](docs/更新日志.md)**

---

## 说明

个人项目，用于完整实践 Spring AI 的各项能力。代码注释侧重解释**设计取舍**（为什么这么写），而非逐行翻译（这行在干嘛）。更详细的排查过程与设计讨论见 [`docs/`](docs/)。
