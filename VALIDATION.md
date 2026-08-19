# Validation

本源码包基于上一版 `itemnest-optimized-source-2026-08-19.zip` 直接修改。

已执行：
- Java 21 定向 `javac`：InventoryRepository / HistoryRepository / LifecycleRepository / AttachmentService / AiService / ItemNestController 通过
- DatabaseInitializer Java 21 定向 `javac` 通过
- App.vue `<script setup>` TypeScript `tsc --noEmit` 检查通过
- Vue 模板 HTML 结构严格解析通过
- package.json / manifest / seed-data JSON 解析通过
- pom.xml XML 解析通过
- application.yml / ci.yml / pnpm-workspace.yaml YAML 解析通过
- CSS 花括号平衡检查通过
- shell 脚本 `bash -n` 通过
- v0.7 SQLite 模拟迁移：旧箱子自动 owner_id=1（我），物品保留
- SQLite FTS5 建表/索引/检索模拟通过

当前执行环境没有可用的 Maven/pnpm 外网依赖下载能力，因此未声称完成真实的 `pnpm install && pnpm build` 和完整 Spring Boot Maven 构建。

建议你本机最终执行：

```bat
cd frontend
pnpm install
pnpm build
cd ..\backend
mvnw.cmd clean package
```
