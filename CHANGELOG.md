# v0.7 — Spring Boot + Vue 3 架构迁移

- 后端从 FastAPI/Python 迁移为 Java 21 + Spring Boot
- 前端从原生 HTML/CSS/JavaScript 迁移为 Vue 3 + TypeScript + Vite
- 保留原 `data/inventory.db` 与现有 SQLite 表结构，旧数据无需重录
- 使用 Spring JDBC 访问 SQLite
- AI 配置和 OpenAI-compatible Chat Completions 调用迁移到 Java
- RabbitMQ 发布器和独立 Worker 迁移到 RabbitMQ Java Client
- RabbitMQ 继续作为可降级异步事件层，不影响 SQLite 主流程
- 新增 `build.bat` / `start_dev.bat`，生产构建会把 Vue dist 打进 Spring Boot JAR
- PWA、箱子优先浏览、搜索、AI 确认执行等原有交互继续保留

# Changelog

## v0.5

- 启动脚本改为直接使用系统 Python，不再创建或激活 `.venv`。
- Windows 优先使用 `py -3`，不可用时回退到 `python`。
- Linux/macOS 优先使用 `python3`，不可用时回退到 `python`。
- 启动时先检查运行依赖，仅在缺少依赖时通过系统 Python 的 pip 安装 `requirements.txt`。
- 数据库与前端 UI 均不变，可直接沿用 v0.4 的 `data/inventory.db`。
- FastAPI 应用版本更新到 0.5.0。

## v0.4

- 存储 UI 改为“箱子优先”浏览：默认只展示箱子，不展示物品名称。
- 点击箱子后打开独立的“箱内详情”面板，才显示物品清单。
- 首页全局搜索保留；只有主动输入关键词时才显示匹配的物品。
- 箱子详情中可直接添加、编辑、删除物品。
- 手机端箱子详情采用底部抽屉式布局，更适合单手操作。
- 数据库结构保持不变，继续使用 `data/inventory.db` 的 SQLite 单文件数据库，可直接沿用 v0.3 数据。
- FastAPI 应用版本更新到 0.4.0，PWA 缓存版本同步更新。

## v0.3.0 — 2026-08-18

- 新增 `常用盒子【黑色透明】`，作为高频打开的收纳位置，仅登记箱子，不统计内部物品。
- 新增 `常用盒子【白色透明】`。
- 白色透明常用盒新增：纸、笔、便携标签、粘小飞虫板子。
- AI 库存快照现在包含容器备注；本地降级检索也可以返回只有备注、没有物品记录的箱子。
- 箱子数量由 12 个增加到 14 个，物品类别由 74 类增加到 78 类。
- 增加 v0.2 → v0.3 一次性数据库增量迁移，重复启动不会重复添加。
- 修复空箱子数量汇总错误显示为 1 的问题，现在正确显示 0。

## v0.2.0 — 2026-08-18

- 透明超大鱼缸箱子新增 5 类扩展坞。
- 新增 USB-C → 3.5mm、USB-B → USB-A 两类转换器。
- 新增 USB-A ↔ Micro-B 硬盘线。
- 新增 2 根白色 USB-A ↔ Mini-B 线，以及 1 根黑色低速率 Mini-B 线。
- 纯甄酸奶箱子新增 VGA 视频采集卡，并记录 HDMI/USB 输出、OBS/手机软件采集备注。
- 物品类别由 63 类增加到 74 类。
- 增加 v0.1 数据库的一次性增量迁移，保留旧数据库时自动补齐新增条目且不会重复导入。

## v0.6.0 - RabbitMQ 消息队列

- 新增 RabbitMQ / AMQP 0-9-1 消息队列集成，使用 `aio-pika`。
- SQLite 继续作为唯一权威数据源；RabbitMQ 不参与核心 CRUD 提交，避免消息队列故障影响物品管理。
- 物品/箱子的新增、修改、移动、删除成功后异步发布 `inventory.#` 事件。
- 使用 durable topic exchange、durable queue、persistent message 和 publisher confirms。
- 新增 `/api/mq/status`，设置页可查看 RabbitMQ 连接状态、Exchange、Queue 和最近错误。
- 新增独立 `mq_worker.py` 消费者，将消费事件追加到 `data/mq_events.jsonl`。
- 新增 `start_worker.bat` / `start_worker.sh`，继续使用系统 Python，不创建虚拟环境。
- RabbitMQ 不可用时主应用自动降级，SQLite、搜索、AI 和 CRUD 继续正常工作。
