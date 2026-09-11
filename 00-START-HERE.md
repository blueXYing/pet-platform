# 宠物平台 V1.0 AI 并发研发启动工程

当前补充：ACR-001 R1～R6已批准并同步，Wave 1仍以WORK_STATE.md的独立批准门禁为准。后端基线v0.6保持，前端执行docs/02-architecture/20-前端技术基线-v0.7.md，测试执行docs/07-testing/21-前端与小程序验收补充-v0.1.md。统一微信小程序包含用户/商家工作区，运营为React网页；旧frontend-c/frontend-merchant仅为迁移说明。

这是已经预搭建的“ChatGPT Work + GitHub + Codex + Git Worktree”研发启动包。

## 你现在不用再和 Work 从零讨论怎么拆项目

本包已经准备好：

- 最终 SSOT、三端 PRD、技术基线、Schema、API、Event、Scheduler、测试矩阵；
- Maven 后端骨架；
- AI 研发组织与 6 个 Role；
- Work 执行协议和当前状态；
- 三端 Capability Map；
- Cross-End 主链路；
- Epic / Story 初始拆解；
- Issue Catalog；
- 第一批 Ready Queue；
- Traceability Matrix；
- CCR、Issue、PR、Code Review 模板；
- Git/Worktree PowerShell 脚本；
- CI 初始模板；
- 人类 CTO 每日操作说明。

## ChatGPT EXE 中的第一步

1. 把整个目录解压到本地，例如 `D:\pet-platform-v1.0`。
2. ChatGPT Desktop → Project `宠物平台 V1.0` → Work。
3. 让 Work 访问这个本地目录。
4. 把 `01-WORK-FIRST-PROMPT.md` 内容作为第一条消息发送。
5. Work 必须读取 `WORK_STATE.md`，不能重新从零设计项目。
6. Work 首先只做 Bootstrap Validation；没有阻断冲突则直接进入第一批 Ready Queue。

## 重要

产品规则优先级：

`SSOT > 三端最终 PRD > 技术基线 > Schema/API/Event/Scheduler > 测试矩阵`

任何 AI 不得自行修改产品规则。

运营权限最新裁决：读取SSOT §24及docs/01-prd/22-运营权限人工裁决补充-v1.0.md，覆盖原运营Word中内部审批/双人复核冲突条款；原Word作为历史基线与该增量补充共同使用。
