# 物栈 ItemNest v0.8

ItemNest 是一个本地优先的家庭物品管理工具。v0.8 在原有 Spring Boot + Vue 3 + SQLite 基础上，把数据模型从单人库存升级为 **家 → 家庭成员 → 箱子 → 物品**，并加入操作历史、撤销、生命周期、可选附件、本地 FTS5 检索、AI Scope、批量操作和快速录入。

## v0.8 核心模型

```text
家
├─ 我 (id=1)
│  └─ 箱子
│     └─ 物品
├─ 爸 (id=2)
│  └─ 箱子
│     └─ 物品
└─ 妈 (id=3)
   └─ 箱子
      └─ 物品

物品
├─ 生命周期（可选）
└─ 图片 / 附件（可选）

所有数据库写操作
└─ operation_history
```

**升级旧数据库时，现有全部箱子和物品自动归到“我”(id=1)。** 当前版本不加入具体房间层级，也不加入扫码/NFC。

## 新增功能

### 家庭成员
默认固定三个成员：我、爸、妈。箱子属于某个成员，箱内物品自动继承箱子的归属。

### 操作历史与撤销
新增/修改/移动/删除物品、新增/修改/删除箱子、批量操作、AI 操作和生命周期修改都会进入历史记录。打开箱子后点击 **操作记录** 即可查看。撤销使用 before/after 快照；如果该实体之后还有更新记录，会拒绝直接撤销旧记录，避免覆盖新状态。

### 生命周期
统一处理：
- `EXPIRY`：保质期 / 有效期
- `WARRANTY`：保修
- `REPLACE`：建议更换
- `CHECK`：定期检查

可用于电池、调料、水果、药品、耗材、电子设备保修等。支持提前 N 天提醒，并在 Dashboard 显示 7 天、30 天和已到期数量。

### 图片与附件
物品可选上传图片或普通附件，单文件默认上限 20MB，保存到 `data/attachments/`。**附件不是必填项。**

### AI 图片边界
当前大语言模型链路只接收文本字段：名称、数量、家庭成员、箱子、状态、备注、标签和生命周期。

不会发送：
- 图片文件
- 图片 Base64
- 本地文件路径
- PDF/附件正文
- 其他附件内容

以后更换视觉模型时再单独增加视觉 Context Provider。

### 本地检索 / RAG
v0.8 使用 **SQLite FTS5** 作为本地 Retrieval 层，不引入向量数据库：

```text
用户问题
  ↓
家庭成员 / 生命周期 Scope
  ↓
SQLite FTS5
  ↓
Top-K 文本候选（最多 24 条）
  ↓
LLM
```

这属于 Retrieval-Augmented Generation 的轻量实现。当前库存规模下，它比单独维护向量数据库更简单、更快、更可解释。

### AI Scope
AI 会话顶部可多选：
- 我
- 爸
- 妈
- 仅生命周期物品

过滤在数据库检索阶段完成，不只是 Prompt 文字约束。AI 执行新增、修改、移动、删除和生命周期操作时也会检查所选成员 Scope。

### 批量操作
可对当前成员的多件物品批量：
- 移动箱子
- 修改状态
- 修改标签

批量修改只生成一条主历史记录，避免历史列表被大量同类操作刷屏。

### 快速录入
选择一个箱子后可连续输入多行物品，一次提交。适合整理箱子时连续登记。

### Dashboard
首页显示家庭总量、成员统计、生命周期提醒和最近操作。

## 暂不实现

- 房间 / 柜子 / 抽屉层级
- 二维码 / NFC
- 分类树 / 动态属性
- 向量数据库
- 图片或附件喂给大语言模型
- 手机端专项 UI
- 语音输入

## 语音输入后续方案

建议保持现有 Agent 不变，在最前面增加本地 ASR：

```text
麦克风
  ↓
MediaRecorder
  ↓
faster-whisper / whisper.cpp
  ↓
文本
  ↓
现有 ItemNest AI Agent
  ↓
FTS5 / 数据库工具
  ↓
确认卡片
  ↓
执行 + operation_history
```

因此未来加语音时不需要重写数据库或 AI Scope。

## 开发端口

- 生产版 Spring Boot：`http://127.0.0.1:8765`
- Vite 开发服务器：`http://127.0.0.1:15473`

需要局域网访问时显式设置：

```text
ITEMNEST_BIND_ADDRESS=0.0.0.0
```

当前没有账号登录，不要把 8765 暴露到公网。

## 构建

Windows：

```bat
build.bat
start.bat
```

手动构建：

```bat
cd frontend
pnpm install
pnpm build

cd ..\backend
mvnw.cmd clean package
```

生成：

```text
backend/target/itemnest-0.8.0.jar
```

## 数据目录

```text
data/
├─ inventory.db
├─ ai_settings.json
└─ attachments/
```

SQLite 仍是唯一权威数据源。
