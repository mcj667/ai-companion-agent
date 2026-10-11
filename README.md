# AI 情感陪伴 Agent

一个 **Spring Boot 3 + Spring AI** 的 AI Agent 项目：聊天式情感咨询。支持多轮记忆、流式输出、结构化报告、RAG 知识库问答、工具调用（联网搜索 / 网页抓取），以及通过 **MCP** 接入外部进程提供的工具。

**技术栈**：Spring Boot 3.5 · Java 21 · Spring AI 1.1 · DeepSeek（对话）· 阿里百炼（向量化）· Redis · Vue 3 · Vite

**当前状态**：后端 10 个接口 · 前端页面可用 · 36 个测试通过

<!-- 截图放这里：把图丢进 docs/images/ 再取消下面两行注释
![主界面](docs/images/main.png)
![流式输出](docs/images/stream.gif)
-->

---

## 功能

| 模式 | 干什么 | 用到的能力 |
|---|---|---|
| 普通对话 | 一问一答 | 多轮记忆（Redis） |
| 流式对话 | 逐字蹦出来 | SSE |
| 知识库问答 | 先查知识库再回答 | RAG（向量检索） |
| 联网搜索 | **模型自己决定**要不要上网查 | 工具调用（`@Tool`） |
| MCP 工具 | 工具来自**外部进程**，项目里没有这些方法的代码 | MCP（高德地图，12 个工具） |
| 结构化报告 | 直接返回 JSON 建议 | Structured Output |

---

## 架构一览

```
┌────────────────┐
│   Vue 3 前端    │
└───────┬────────┘
        │  /api（Vite 代理，开发时无跨域）
┌───────▼──────────────────────────────────────┐
│                Spring Boot 3                 │
│      ChatClient + Advisor 链                 │
│   （对话记忆 · 日志 · RAG 检索，按请求挂）      │
└──┬────────┬────────┬──────────┬──────────────┘
   │        │        │          │
DeepSeek   百炼     Redis    工具调用 / MCP
 对话     向量化   记忆      ├─ 本地 @Tool：联网搜索、抓网页
 模型     模型     会话名单   └─ MCP 子进程：高德地图 12 个工具
                  模式记录        （这些方法的代码不在本项目里）
```

---

## 几个「踩过才知道」的设计

> 每条只写结论，展开见 [设计说明](docs/设计说明.md)。

**1. MCP 的代价是启动期强耦合。**
本地 `@Tool` 是编译期的事，失败了只影响那一个工具；而 MCP 的工具是应用**启动时**从一个外部进程问回来的——
所以它挂了断的不是「MCP 这个模式」，而是**整个 Spring 容器起不来**。本项目把包装到本地让 `npx` 不依赖网络，
并留了 `initialized: false` 做应急开关。

**2. 工具接得对 ≠ 模型会用。**
MCP 通了、12 个工具确实发给了模型，但模型一次都不调——因为系统提示词里写着「只讨论情感话题」，
把「查天气」当成了其他话题挡回去。**系统提示词和工具是两套独立注入的指令，它们会打架**，而提示词的约束力更强。
排查路径：先用假的 `ChatModel` 截住请求、确认工具发出去了 → 再翻提示词有没有和它冲突。

**3. 一轮工具调用 = 至少两次模型调用。**
模型**不执行任何代码**，它只会「说」一句「我要调 `webSearch`」，真正发 HTTP 请求的是本地的 Java 方法，
拿到结果再塞回去让模型写最终答案。这就是开了工具会更慢更贵的原因；后端日志里那两条 `AI Response` 是直接证据。

**4. 缺 key 时的行为是分级设计的，不是一刀切。**
`DEEPSEEK_API_KEY` 缺了直接**启动失败**（对话没了应用就没意义，早失败早发现）；
向量化和联网搜索的 key 缺了只让对应功能**降级**，不影响其他模式。同理，向量化失败被 try/catch 包住，RAG 挂掉不拖垮对话。

---

## 快速开始

**前置**：JDK 21 · Redis（`localhost:6379`）· Node 18+（只跑前端时需要）

### 1. 配置 API Key（走环境变量，不写进代码）

```bash
DEEPSEEK_API_KEY=sk-xxxx     # 必填，对话用
DASHSCOPE_API_KEY=sk-xxxx    # 可缺省，向量化用（缺了 RAG 降级成普通对话）
BOCHA_API_KEY=sk-xxxx        # 可缺省，联网搜索用（缺了该模式不可用）
```

- **在 IDEA 里跑**：`Run → Edit Configurations… → Environment variables`
- **在命令行里跑**：`setx DEEPSEEK_API_KEY "sk-xxxx"`（只对**之后新开**的终端生效）

去哪儿拿：DeepSeek <https://platform.deepseek.com/> · 百炼 <https://bailian.console.aliyun.com/> · 博查 <https://open.bochaai.com/>

> 三个 key 缺失时的行为**是刻意做成不一样的**：`DEEPSEEK_API_KEY` 缺失会让应用**启动失败**（对话是核心功能，早失败早发现）；另外两个缺失只让对应功能降级，不影响其他模式。

> ⚠️ **`DASHSCOPE_API_KEY` 的默认值 `not-configured` 不能改成留空** —— DashScope 的自动配置校验 key 是 fail-fast 的，
> 留空会让整个 Spring 上下文起不来，连对话功能一起陪葬。`not-configured` 是代码里认的"没配"标记，会走优雅降级那条路。
>
> 另外搜索地址默认走 `api.bochaai.com`；连不上时（比如被 VPN 劫持 DNS）在同级加一行
> `endpoint: https://api.bocha.cn/v1/web-search` 即可，不用改代码。

### 2. 启动

```bash
./mvnw spring-boot:run                        # 后端 → http://localhost:8123/api
cd frontend && npm install && npm run dev     # 前端 → http://localhost:5173
```

- 接口文档（Knife4j）：<http://localhost:8123/api/doc.html>
- 健康检查：<http://localhost:8123/api/health>

> 首次启动会读 `src/main/resources/document/` 里的知识库 → 调百炼向量化 → 存到 `data/` 下的 JSON 文件。
> 之后启动直接读文件，不再重复调接口（省钱，启动也快）。想重新生成，删掉那个文件再启动即可。

---

## 接口一览

`context-path` 是 `/api`，完整路径要加前缀，例如 `POST /api/love/chat`。对话类接口都接收 `message` 和 `chatId` 两个参数。

| 方法 | 路径 | 返回 |
|---|---|---|
| POST | `/love/chat` | 纯文本 |
| POST | `/love/chat/stream` | **SSE 流式**，逐字返回 |
| POST | `/love/rag` | 纯文本，回答前先查知识库 |
| POST | `/love/search` | 纯文本，模型自己决定要不要联网搜 / 抓网页 |
| POST | `/love/mcp` | 纯文本，工具来自 MCP 服务进程 |
| POST | `/love/report` | `LoveReport` JSON（title + suggestions） |
| GET | `/love/sessions` | 会话列表，最近聊过的排前面 |
| GET | `/love/history` | 某个会话的消息气泡 |
| DELETE | `/love/sessions` | 删除会话 |
| GET | `/health` | 固定字符串 `ok` |

> 用 curl 测流式接口记得加 `-N` 关掉缓冲，否则看不到逐字效果：
> `curl.exe -N -X POST "http://localhost:8123/api/love/chat/stream?message=你好&chatId=test1"`
> （中文参数在 PowerShell 里可能被 Tomcat 拒绝，建议直接用前端页面测。）

---

## 项目结构

```
src/main/java/com/example/aiagent/
├── app/LoveApp.java         ★ 组装 ChatClient + 六个对话方法
├── controller/              10 个接口
├── advisor/                 自定义日志 Advisor
├── memory/                  ★ 对话记忆（Redis + Kryo）、会话名单、模式记录
├── service/                 把 Redis 里的数据翻译成前端要的形状
├── rag/                     ★ 向量库 + markdown 知识库加载
├── tool/                    ★ 工具调用：联网搜索、网页抓取
└── demo/invoke/             学习用的示例代码

src/main/resources/
├── document/                知识库（3 个 md）
└── mcp-servers.json         MCP 服务清单（这里配了高德地图）

src/test/java/               36 个测试
frontend/                    Vue 3 + Vite
```

---

## 更多文档

- **[设计说明](docs/设计说明.md)** — Advisor 链、对话记忆、会话历史、RAG、工具调用、MCP 分别是什么、为什么这么设计，以及 Redis 的 key 规划
- **[已知限制与排查](docs/已知限制与排查.md)** — 14 条已知缺口（含安全相关的）+ 常见问题排查 + 待办
- **[更新日志](docs/更新日志.md)**

---

## 说明

个人项目。代码注释偏重解释**设计取舍**（为什么这么写），而不是逐行翻译（这行在干嘛），希望能直接照着讲。
