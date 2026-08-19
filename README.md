# ItemNest

个人收纳物品管理项目。

## 技术栈

- 后端：Spring Boot + Java 21
- 前端：Vue 3 + TypeScript + Vite
- 数据库：SQLite
- 消息队列：RabbitMQ
- AI：OpenAI Compatible API

## 目录

```text
ItemNest/
├─ backend/          Spring Boot 后端
├─ frontend/         Vue 3 前端
├─ data/             SQLite 与本地配置
├─ build.bat         构建前后端
├─ start.bat         启动正式版
├─ start_dev.bat     开发模式
└─ start_worker.bat  启动 RabbitMQ Worker
```

## 环境

需要安装：

- JDK 21（当前项目固定使用 Java 21）
- Maven：无需全局安装，项目已自带 Maven Wrapper（首次运行会自动下载 Maven 3.9.11）
- Node.js 20+
- pnpm

## 开发运行

双击：

```text
start_dev.bat
```

访问：

```text
http://127.0.0.1:15473
```

后端 API：

```text
http://127.0.0.1:8765
```

## 正式运行

首次构建：

```text
build.bat
```

之后启动：

```text
start.bat
```

访问：

```text
http://127.0.0.1:8765
```

## 数据

本地数据保存在：

```text
data/inventory.db
```

AI 配置保存在：

```text
data/ai_settings.json
```

RabbitMQ Worker 消费日志：

```text
data/mq_events.jsonl
```

## RabbitMQ Worker

需要独立消费者时运行：

```text
start_worker.bat
```
