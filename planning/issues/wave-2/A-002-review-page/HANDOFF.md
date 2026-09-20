# A-002 入驻审核页面切片 · 交接

2026-09-20，基线 develop `c04df90`，分支 `feat/a002-review-page-20260920`。授权与范围见 [DISPATCH.md](DISPATCH.md)，页面/字段契约见 [CONTRACT.md](CONTRACT.md)。

## 交付

- 生产入口由占位页切换为真实应用（`src/App.tsx`）：运营登录 → 会话/权限自举（失败关闭）→ 审核列表 `/merchant-applications` → 审核详情 `/merchant-applications/:id`；未登录深链重定向登录页；404 保持。
- 登录页实现完整 attempt→requirements→（CAPTCHA 时 challenges→verify）→login 流；无 MFA、无双人审批入口；账号内存持有 token，刷新重登。
- 列表页：状态/类型/城市/关键词筛选（仅已批准过滤字段）、服务端分页、String ID 原样展示。
- 详情页：提交版本快照（脱敏联系人字段原样）、材料一次性水印读取（理由 10–500 字 + confirmed，读取字节 SHA-256 记录绑定）、任务领取/释放（expectedTaskVersion 乐观锁）、人工核验（四类证件证据行，须先查看对应材料；materialSha256 取水印读取字节摘要）、审核决定（APPROVE/REJECT/REQUEST_CORRECTION，非通过必填意见，confirmed 必勾；核验未完成时 APPROVE 禁用并提示）。
- 共享文件最小改动：`request.ts`（envelope 兼容 + 仅 `/auth/*` 允许 credentials 的选项）、`main.tsx`（生产入口切换）、`style.css`（页面样式）、`package.json`（test 前置 build）、`tests/shell.spec.ts`（生产入口断言更新为真实应用且保持无 fixture 断言）。

## 验证（本地，Windows）

- `npm run typecheck` 通过；`npm run build`（生产）与 `npm run build:fixture` 通过；`npm run check:boundaries` 通过（生产 JS 无 fixture 标记）。
- `npm test`（build+Playwright，dev:4173 / preview:4174）：**12/12 通过**——新增 6 项契约测试 `tests/review.spec.ts`（未登录深链零业务调用、登录 requestId/无权限关闭面板、列表契约渲染与分页、领取→单次水印读取→人工核验阻断通过→补正决定全链含 X-Request-Id 与 materialSha256=读取字节摘要断言、401 失效返回登录、409 版本冲突提示），原 6 项壳测试全部保持通过。
- 未运行：真实后端联调、真机、微信端（不在本轮范围）。

## 披露与遗留

1. `/auth/*` envelope 无 `success` 字段（PR17 时期实现）与商家/材料接口的统一信封并存；前端按二者其一判定，建议 Contract Owner 后续统一（CONTRACT.md §4.1）。
2. 登录 attempt 依赖 `__Host-pet-admin-attempt` Secure Cookie：真实联调需 HTTPS 源（本地 http 下该流程无法完成，属联调环境条件，非前端缺陷）。
3. `materialSha256` 语义为前端假设（水印读取字节 SHA-256），需真实联调与 30/31 号 Owner 确认；若权威语义不同需详情接口提供摘要（CONTRACT.md §4.3）。
4. 后续未完成：真实服务器联调（LocalMerchantAcceptanceServer/生产测试环境）、手机端协议签署与通知跳转联合验收、A-002 其余治理页面与整项 AC、运营规范视觉验收。本轮不标 A-002 任何 DONE。

## PR

草稿 PR 待人工审阅合并；未改后端、权威契约、WORK_STATE/队列（PR58 在途），主目录用户配置未动。
