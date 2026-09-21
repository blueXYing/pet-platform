# A-002 入驻审核页面切片 · 交接

2026-09-20，基线 develop `c04df90`，分支 `feat/a002-review-page-20260920`。授权与范围见 [DISPATCH.md](DISPATCH.md)，页面/字段契约见 [CONTRACT.md](CONTRACT.md)。

## 当前定性

**审核页面候选和模拟接口测试已完成；人工核验提交因材料引用契约缺口（§4.3，需 CCR）暂不可用；真实后端联调未执行——尚不能认定为可用审核工作台。**

## 评审修复轮（2026-09-21）

用户以真实客户端请求捕获复核提交 `375425f`，指出五项问题；已逐项对照后端源码确认并修复：

1. **登录协议（P1，已修）**：`POST /attempts` 补 `{}` 请求体；requirements/captcha/login 全部携带 `X-Auth-Attempt`（取 attempts 返回的 attemptToken）；logout 补 `{}` 请求体。与 AdminAuthController/HttpModels 实测要求一致。
2. **材料引用（P1，改为待契约）**：撤销“水印读取字节 SHA-256”假设与 assetId 充当 materialId 的提交路径——后端核对 merchant_material 自身编号与登记摘要且动态水印改变字节，替代值必然失败。人工核验提交禁用并明示待 CCR（CONTRACT.md §4.3）；证据录入 UI 保留为候选。
3. **身份证证据（P1，已修）**：正反面均须查看但合并为一条 `IDENTITY_NUMBER`（绑定 ID_CARD_BACK）主体证据，不再提交两条；三类证件各一条，重复类型按后端规则拒绝。
4. **决定门禁（P1，已修）**：核验未完成仅禁用 APPROVE；REJECT/REQUEST_CORRECTION 可正常提交（26 号裁决只限制“通过”）。
5. **重试幂等（P2，已修）**：所有业务写方法接受显式 requestId；结果未知（网络错误/坏响应）时页面保留原 requestId 与参数并提供“重试原操作”复用同一标识；确定性 4xx 不提供复用重试。

## 交付

- 生产入口由占位页切换为真实应用（`src/App.tsx`）：运营登录 → 会话/权限自举（失败关闭）→ 审核列表 `/merchant-applications` → 审核详情 `/merchant-applications/:id`；未登录深链重定向登录页；404 保持。
- 登录页实现完整 attempt→requirements→（CAPTCHA 时 challenges→verify）→login 流；无 MFA、无双人审批入口；账号内存持有 token，刷新重登。
- 列表页：状态/类型/城市/关键词筛选（仅已批准过滤字段）、服务端分页、String ID 原样展示。
- 详情页：提交版本快照（脱敏联系人字段原样）、材料一次性水印读取（理由 10–500 字 + confirmed，读取字节 SHA-256 记录绑定）、任务领取/释放（expectedTaskVersion 乐观锁）、人工核验（四类证件证据行，须先查看对应材料；materialSha256 取水印读取字节摘要）、审核决定（APPROVE/REJECT/REQUEST_CORRECTION，非通过必填意见，confirmed 必勾；核验未完成时 APPROVE 禁用并提示）。
- 共享文件最小改动：`request.ts`（envelope 兼容 + 仅 `/auth/*` 允许 credentials 的选项）、`main.tsx`（生产入口切换）、`style.css`（页面样式）、`package.json`（test 前置 build）、`tests/shell.spec.ts`（生产入口断言更新为真实应用且保持无 fixture 断言）。

## 验证（本地，Windows）

- `npm run typecheck` 通过；`npm run build`（生产）与 `npm run build:fixture` 通过；`npm run check:boundaries` 通过（生产 JS 无 fixture 标记）。
- `npm test`（build+Playwright，dev:4173 / preview:4174）：**13/13 通过**——`tests/review.spec.ts` 7 项契约测试（未登录深链零业务调用、**真实登录协议（空 JSON 体 + X-Auth-Attempt 逐跳断言）**、无权限关闭面板、列表契约渲染与分页、领取→单次水印读取（正反面合并解锁）→**核验提交待契约关闭且零调用**→**未核验直接补正决定**、**结果未知重试复用同一 requestId**、401 失效返回登录、409 版本冲突提示），原 6 项壳测试全部保持通过。
- 未运行：真实后端联调、真机、微信端（不在本轮范围）。模拟接口测试不能替代真实契约联调（本轮评审即证明）。

## 披露与遗留

1. `/auth/*` envelope 无 `success` 字段（PR17 时期实现）与商家/材料接口的统一信封并存；前端按二者其一判定，建议 Contract Owner 后续统一（CONTRACT.md §4.1）。
2. 登录 attempt 流同时依赖 `__Host-pet-admin-attempt` Secure Cookie、`X-Auth-Attempt` 头与 Origin 校验：真实联调需 HTTPS 源且 Origin 与后端配置一致。
3. **人工核验提交待 CCR**：详情投影需补充 merchant_material 的 materialId/materialSha256/materialType（CONTRACT.md §4.3）；补齐前页面禁用提交，不得用替代值。
4. 后续未完成：材料引用 CCR、真实服务器联调（LocalMerchantAcceptanceServer/生产测试环境）、手机端协议签署与通知跳转联合验收、A-002 其余治理页面与整项 AC、运营规范视觉验收。本轮不标 A-002 任何 DONE。

## PR

草稿 PR 待人工审阅合并；未改后端、权威契约、WORK_STATE/队列（PR58 在途），主目录用户配置未动。
