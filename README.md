# 物栈 ItemNest v0.7

一个面向个人收纳场景的轻量物品管理 App。v0.7 已从 Python/FastAPI + 原生 JavaScript 迁移为 **Spring Boot + Vue 3**，同时继续使用原来的 SQLite 数据库，因此旧版 `data/inventory.db` 可以直接沿用。

## 当前架构

```text
ItemNest
├─ backend/                    Spring Boot / Java 21
│  ├─ controller/             REST API
│  ├─ repository/             Spring JDBC + SQLite
│  ├─ service/                AI / RabbitMQ
│  └─ resources/
│
├─ frontend/                   Vue 3 + TypeScript + Vite
│  ├─ src/App.vue
│  ├─ src/api.ts
│  └─ src/types.ts
│
└─ data/
   ├─ inventory.db             原 SQLite 数据库，继续兼容
   ├─ ai_settings.json         本机 AI 配置，不提交 Git
   └─ mq_events.jsonl          Worker 消费日志
```

## 保留的功能

- 箱子优先浏览：默认隐藏箱内物品名，点击箱子后才打开清单
- 箱子管理
- 物品增 / 删 / 改 / 查
- 数量、模糊数量描述、状态、标签、备注
- 搜索物品、箱子、标签、备注和状态
- AI 自然语言查询库存
- AI 新增 / 修改 / 移动 / 删除仍需要用户确认后执行
- AI 不可用时自动降级到本地关键词检索
- SQLite 单文件数据库
- RabbitMQ 异步库存事件
- 独立 RabbitMQ Worker
- 手机局域网访问
- PWA

## 环境要求

- JDK 21（当前项目固定使用 Java 21）
- Maven：无需全局安装，项目已自带 Maven Wrapper（首次运行会自动下载 Maven 3.9.11）
- Node.js 20+
- pnpm（也可由 Corepack 提供）

## 最简单运行

Windows 双击：

```text
start.bat
```

首次运行会先调用 `build.bat`：

1. `pnpm install`
2. `pnpm build`
3. `mvn clean package`
4. 将 Vue `dist` 一起打进 Spring Boot JAR
5. 启动 `http://127.0.0.1:8765`

后续如果 JAR 已存在，`start.bat` 会直接启动。

## 开发模式

Windows：

```text
start_dev.bat
```

开发端口：

```text
Vue / Vite:     http://127.0.0.1:15473
Spring Boot:    http://127.0.0.1:8765
```

Vite 已把 `/api` 代理到 Spring Boot，因此前端代码仍直接请求 `/api/...`。

手动启动也可以：

```bash
# backend
cd backend
mvnw.cmd spring-boot:run

# frontend
cd frontend
pnpm install
pnpm dev
```

如果手动从 `backend/` 启动，请保证 `ITEMNEST_DATA_DIR` 指向项目根目录的 `data`。启动脚本会自动处理这个变量。

## 数据库兼容

数据库仍是：

```text
data/inventory.db
```

核心表仍保持：

- `containers`
- `items`
- `migrations`

所以 v0.6 的现有数据库可以直接继续使用，不需要重新导入 14 个箱子和 78 类物品。

如果数据库文件不存在，Spring Boot 会自动创建表，并用 `backend/src/main/resources/seed-data.json` 初始化当前完整清单。

## AI 配置

App → `设置`：

- Base URL
- API Key
- 模型

服务端仍然将设置保存在：

```text
data/ai_settings.json
```

API Key 不会返回给 Vue 前端，只返回 `has_api_key`。

## RabbitMQ

默认：

```text
URL:      amqp://guest:guest@127.0.0.1/
Exchange: itemnest.events
Queue:    itemnest.inventory.events
```

Spring Boot 在数据库写入成功后异步发布 `inventory.#` 事件。RabbitMQ 不可用时只记录连接错误，不影响 SQLite CRUD。

启动 Worker：

```text
start_worker.bat
```

Worker 将事件追加写入：

```text
data/mq_events.jsonl
```

## 生产构建

Windows：

```text
build.bat
```

Linux/macOS：

```bash
./build.sh
```

产物：

```text
backend/target/itemnest-0.7.0.jar
```

Vue 前端会在 Maven 打包阶段从 `frontend/dist` 复制进 JAR 的 `static/`。

## 手机访问

为安全起见，默认只监听本机 `127.0.0.1`。如果确实需要手机从同一局域网访问，在启动前显式开放监听：

CMD：

```bat
set ITEMNEST_BIND_ADDRESS=0.0.0.0
start.bat
```

PowerShell：

```powershell
$env:ITEMNEST_BIND_ADDRESS = "0.0.0.0"
.\start.bat
```

然后手机访问：

```text
http://电脑局域网IP:8765
```

不要把 8765 直接映射到公网；当前版本没有账号登录。开发模式 Vite 使用 `15473`，默认仍只监听本机。

## 说明

v0.7 的目标是完成架构迁移，而不是改变你的库存数据模型。SQLite 仍是唯一权威数据源；Vue 只负责 UI，Spring Boot 负责 API、AI 和 RabbitMQ。

## 网络与可选服务

- 默认仅监听 `127.0.0.1`，避免同一局域网设备直接访问管理接口。
- 如确实需要局域网访问，启动前设置 `ITEMNEST_BIND_ADDRESS=0.0.0.0`。
- RabbitMQ 默认关闭，SQLite 库存功能不依赖 RabbitMQ；需要事件总线/Worker 时设置 `ITEMNEST_RABBITMQ_ENABLED=true`。
- AI 配置优先读取 `ITEMNEST_AI_*` / `OPENAI_*` 环境变量，源码不再包含私人局域网 API 地址。
