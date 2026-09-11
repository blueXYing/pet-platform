# PLAT-001 验证并修复 Maven 后端骨架可构建

Owner Role: Backend Core
Role File: `.ai/roles/backend-core.md`
Epic: EPIC-01
Story: ST-PLAT-01
Priority: P0
Wave: 1
Status: READY
Dependencies: GOV-001

## Allowed Modules
`backend/**`

## Source
- 最新 SSOT
- 后端技术基线 v0.6 + 前端技术基线 v0.7（20号文档）
- AI研发流水线规范
- 对应 PRD/Contract（实现前读取）

## Acceptance Criteria
1. 仅完成本 Issue 定义的范围。
2. 不修改已封板产品规则。
3. 不新增 biz→biz 依赖。
4. 相关工程/测试可运行。
5. 输出变更、测试结果、风险。
6. 若需要修改保护 Contract，提交 CCR。

## Required Tests
`ARCH-001~ARCH-003`

## Definition of Done
- Acceptance Criteria 全部满足；
- CI/相关测试通过；
- 无未披露 Contract/Schema/Event 变化；
- PR 可供 Review。
