# A-002 入驻审核页面切片 · 派发记录

- 日期：2026-09-20
- 基线：develop `c04df90`（PR57 合并后）
- 分支/工作区：`feat/a002-review-page-20260920` @ `../wt-A-002-review-page`
- 授权来源：用户在 2026-09-20 会话中明确指示"现在你开始推进 A-002"，范围为该会话与 PR58 更新的 Ready Queue 一致确认的切片：**真实运营登录与商家申请审核业务页接入**（"运营审核页面、手机签约和通知跳转联调"中的运营页面部分；手机端联合验收不在本轮）。
- 唯一 Writer：本任务。本轮写入 `frontend-admin/src/**`（新增业务文件）、`frontend-admin/tests/**`、`frontend-admin/src/request.ts`、`frontend-admin/src/main.tsx`、`frontend-admin/vite.config.ts` 与 `frontend-admin/proxy-guard.ts`（受控代理判定，以上 A-001 工程壳共享文件/根文件，本轮登记借用，仅做登记范围内的最小改动）、`frontend-admin/package.json`（test 前置 build）、`planning/issues/wave-2/A-002-review-page/**`。
- 不写入：`WORK_STATE.md`、Ready/Blocked Queue（归属根 Work 调度任务，PR58 在途）；后端任何模块；权威 SSOT/PRD/Schema/API 文档；小程序与商家端目录。

## 范围

1. 生产入口从占位页切换为真实应用：运营登录（attempt/requirements/captcha/login/session/permissions/logout，契约见 10 号与 AdminAuthController 实现）、会话失效拒绝与撤权隔离。
2. 商家申请审核列表页与详情页，消费 PR54/56/57 已交付并已批准的接口（30 号申请契约 + 31 号私有材料契约）：list/get/claim/release/manual-verification/decision/read-grants。
3. 契约 Mock（Playwright `page.route`）驱动的前端验收测试；真实后端联调、手机签约与通知跳转不在本轮完成，保持挂账。

## 边界

- 不改变产品规则：单运营、无内部双人审批、无额外 MFA；已确认四项规则（26 号补充）只做界面呈现，不在前端重算服务端状态。
- Mock 数据由已批准接口契约约束（W2-FE-001），不把内部工程 fixture 升级为业务 DTO；生产构建不得包含 fixture 标记（check-boundaries 保持通过）。
- 发现的契约缝隙只在 [CONTRACT.md](CONTRACT.md) 披露并提交 Contract Owner 评审输入，不自行修改权威契约。
