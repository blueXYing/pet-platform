# MS-2 交接：工作台准入契约冻结与 OWNER 实现（切片A/C）

日期：2026-09-22。分支 `feat/m002-ms2-admission-20260922`（基于 develop `66a28ec`，独立 worktree）。主任务 M-002；方案见主 worktree M-002-mobile-signing/PLAN.md §5。

## 交付

- **切片A**：[CCR-W2-ADMISSION-001](../../../ccr/CCR-W2-ADMISSION-001.md)——冻结 `GET /api/v1/c/auth/merchant-memberships` 与 `GET /api/v1/merchant/auth/admission` 线上细节（分页/排序、错误映射、判定矩阵含 reasonCodes/allowedActions/nextSteps 值域、authzVersion 构成、失败关闭）。只补技术缺口，规则全部引自 HTTP10 既有批准文本与 27 号契约 §5。
- **切片C**：OWNER 全量实现——
  - `MerchantAdmissionService`：归属优先（非本人 404 防枚举）→ 成员有效 → 事实可读（否则 503 data=null）→ 申请 → 签约 → 状态组合（OFFLINE/FROZEN 并存按实际成因，FROZEN 取更严格动作集）；
  - memberships：本人门店分页列表（merchantId/storeId Long 升序，page 1..10000 / pageSize 1..50）；
  - 新读侧查询 `selectOwnedStores/countOwnedStores`；接线复用 PersistentApplicationReviewFactsReader 与协议接受事实（与 checkOrderEligibility 同源）；
  - boot 三处登记：CBearerSessionFilter protectedPath、CSessionSecurityConfiguration permitAll、CServiceExceptionHandler assignableTypes（缺一即被安全层 403）。

## 测试证据

- `MerchantAdmissionHttpTest`（真实 MySQL/Redis + HttpFixture + 固定 code 微信替身）：**走真实业务流**（登录→申请→审批 APPROVE→协议 consent 201→）后验证全矩阵——DENIED+SIGNING_REQUIRED+COMPLETE_SIGNING（未签）、ALLOWED（五条件合取，含 facts/authzVersion/checkedAt 形状）、STORE_FROZEN 与 MERCHANT_OFFLINE 的 LIMITED（各自 reasonCodes/动作/nextSteps）、authzVersion 随 store version 变化、跨用户 404、参数 400 族、未认证 401、签署事实损坏 503 失败关闭；memberships 排序/双店/他人空列表/分页越界 400。
- 本机全套 pet-boot 65 项通过（含 MerchantApplicationLifecycleHttpTest 与前端集成链路），无回归。
- 注意：审批链 SQL 播种被判定约束矩阵系统性拒绝（state_shape/decision proofs 外键），测试因此全部走真实 HTTP 审批流——这是设计使然，不是绕过。

## 边界与不包含

- STAFF 成员绑定、`CurrentSession.merchantEntry`、冻结写动作裁决、逐命令业务鉴权均不在本切片（CCR §1）。
- 前端（切片D）未动；本切片纯后端+契约。
- 动作码是 HTTP10 建议提示组，非授权清单；ALLOWED 不代表逐命令放行。
