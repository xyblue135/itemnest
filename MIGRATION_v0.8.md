# v0.8 数据迁移说明

首次启动 v0.8 时会自动执行增量迁移，不会要求清空 `data/inventory.db`。

- 创建 `household_members`：1=我、2=爸、3=妈
- 给 `containers` 增加 `owner_id`，所有旧箱子默认 `owner_id=1`
- 物品通过所在箱子继承成员归属，不在 `items` 重复保存 owner
- 创建 `operation_history`
- 创建 `item_lifecycle`
- 创建 `attachments`
- 创建 / 重建 `items_fts` FTS5 索引

建议第一次运行 v0.8 前手动复制一份 `data/inventory.db`。这不是 v0.8 的自动备份模块，只是版本升级前的人工保险措施。

## 附件
附件文件保存到 `data/attachments/`，SQLite 只保存元数据。删除物品时附件数据库记录会被级联删除，但文件本体暂时保留，以支持“撤销删除”恢复附件元数据；后续可以再增加孤立附件清理工具。

## AI
图片和附件不进入当前 AI Context。AI 只接收经过 Scope + FTS5 过滤后的文本字段和生命周期信息。
