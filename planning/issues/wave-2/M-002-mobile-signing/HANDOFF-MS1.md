# MS-1 交接：手机协议签署页＋申请页签约入口

日期：2026-09-21。分支 `feat/m002-ms1-signing-page-20260921`（基于 develop `66a28ec`）。主任务 M-002；方案见同目录 [PLAN.md](PLAN.md) §6 裁决 (b)。

## 交付范围

- 新增签约页 `consumer/pages/merchant-application/signing`：读协议（版本/正文/SHA-256）→ 勾选 → 同意并签署 → 已签回显；含加载失败重试、409 冲突重读重确认、未决命令恢复、无权限（403/404）、会话失效（401）、无效商家标识与交互预览态。
- 申请页 APPROVED 行从"签约入口暂未接通"接通为"去签署协议"入口（携带 reservedMerchantId，服务端仍做归属校验）。
- `app.config.ts` 登记 `signing` 页；`package-check.cjs` 期望清单同步。

## 文件与归属登记

| 文件 | 变更 | 归属 |
|---|---|---|
| `src/consumer/merchant-application/signing.ts` | 新增：SigningController 状态机、signingMessage、不可用依赖桩 | M-002 |
| `src/consumer/merchant-application/signing-runtime.ts` | 新增：真实/预览运行时装配 | M-002 |
| `src/consumer/pages/merchant-application/signing.tsx/.config.ts/.scss` | 新增：页面与样式 | M-002 |
| `src/consumer/pages/merchant-application/view.tsx`、`index.tsx` | APPROVED 行入口 | M-002 |
| `src/shared/consumer-api.ts` | 新增 `retireRejectedCommand`（仅按服务端已定论 409 精确载荷退休日志命令；未知结果不可通过此口清除） | **共享文件，C-End 唯一Writer 名义登记的本切片变更** |
| `src/shared/merchant-repositories.ts` | 新增 `ConsentIntent` 导出类型与 `retireConsent`（载荷精确匹配才退休） | 同上 |
| `src/shared/tests/merchant-http-integration.ts` | 扩展：已签读取断言 acceptedVersion/acceptedAt + 客户端防重签守卫 | 同上 |
| `src/app.config.ts`、`package-check.cjs` | 页面登记 | C-End 名义 |

## 关键语义（验收对应）

- 未决意图只能由命令结果清除：恢复态仅 `retryConsent`（同 requestId 重放）可解除；GET 快照 SIGNED 不清除未决（有专项测试）。
- 409 CONFLICT：服务端已定论拒绝该精确载荷 → `retireConsent` 按 path/method/data 精确匹配退休 → 冲突态 → 重读新版本 → 勾选清空重新确认。退休失败（载荷已变）回落恢复态。
- 503/网络未知结果：命令保留日志，进入恢复态，禁止重新发起。
- 会话隔离：沿用 ConsumerApi 会话级 journal；页面 enter 时切换 workspace 至 merchant 候选坐标，leave 仅在本页持有当前 revision 时恢复 consumer 坐标，不覆盖更新页面的坐标。
- 已签：回显原版本+时间，无签署入口；客户端 EXPLICIT_AGREEMENT_REQUIRED 守卫 + 服务端幂等回放双层防重签。
- 换账号：scope 替换触发控制器重置为 switching 态，需重新进入。

## 测试证据（分层）

1. **注入单测**（本机通过）：`src/consumer/tests/merchant-signing.test.ts` 9 项——工作区切换与读取、勾选门槛与回执签署、未知结果恢复重放（同载荷同 requestId 语义）、409 退休后新版本重签、已签快照不清除未决、错误映射（403/401/503/无效ID）、leave 归属守卫、预览失败关闭、消息文案。全套 `npm test`：135 通过 0 失败。
2. **工程门**（本机通过）：`npm run typecheck`、`npm run build:weapp`（signing 页 wxml/wxss/js/json 产物生成）、`npm run check:package`（分包登记与包体预算，exit 0）。
3. **真实 Boot 联调**：扩展后的 `merchant-http-integration.ts` 由 `MerchantApplicationLifecycleHttpTest`（真实 MySQL/Redis + 真实授权链路）在 CI 执行；本机无 JDK 21（仅 17/8/11，后端 enforcer 强制 21），未本地复跑，**待 PR CI 证据回填**。
4. **模拟器/真机/VIS**：未执行。本页无 Figma 原稿（设计登记表 §4），按 PLAN §6 裁决 (b) 沿用申请页（132:862 交付版）页面语言与 design tokens 实现，**不声称一比一还原，VIS 不适用**；补稿后按 21 号验收补充对齐。

## 边界与不包含

- 未实现工作台准入（切片C/D）、通知跳转（切片E）；未改任何后端代码；未新增接口。
- `retireRejectedCommand` 仅在收到服务端 409 定论后使用；不改变 application 写路径的 409 语义。
- 不以本切片关闭 M-002/MER-001。

## 遗留

- PR CI 六项结果回填后本交接才视为完成 DoD。
- 用户本地未提交修改 `frontend-miniapp/project.config.json` 与未跟踪 `.zcodeignore`、`docs/08-engineering/20-设计源登记表-figma-map.md` 不在本分支提交范围。
