# CCR-W2-ADMISSION-001：商家工作台成员关系与准入线上契约冻结

状态：**APPROVED_FOR_SLICE（2026-09-22 用户授权"开始切片A/C"）**。本 CCR 只冻结 HTTP10《商家工作台选择与准入》章节既有批准语义的线上传输细节，不新增、不修改任何产品规则；与内部契约 27 号 §5（复用 HTTP10、不新增第二套会话）一致。

Owner：AUTH-001（会话侧）/ MER-001（商家事实侧）。实施切片：M-002 切片C。

## 1. 范围

纳入：

- `GET /api/v1/c/auth/merchant-memberships`（本人可选范围门店列表）
- `GET /api/v1/merchant/auth/admission`（每次进入重查的准入事实）

不纳入（维持现状，不因本 CCR 变更）：

- `CurrentSession.merchantEntry` 提案：维持 HTTP10 提案级，待切片D前端需要时另行最小补充；
- STAFF 成员绑定与 staffId 语义（HTTP10 已单列持久化缺口；字段位保留，V1 不产出 STAFF 行）；
- 商家域新单资格内部 API（`checkOrderEligibility`，契约 27 号 §4）与各业务命令逐命令鉴权；
- 冻结写动作裁决（OPEN_DECISIONS 维持未决，LIMITED 态只读提示不扩展写动作）。

## 2. GET /api/v1/c/auth/merchant-memberships

- 鉴权：MINIAPP Bearer（C 端会话），仅本人归属范围；启用条件 `pet.auth.c.enabled=true` 且 `pet.merchant.application.enabled=true`（任一关闭即端点不装配，失败关闭）。
- 查询参数：`page`（可选，默认 1，1..10000）、`pageSize`（可选，默认 20，1..50）；非整数或越界 → 400 `COMMON_INVALID_ARGUMENT`；不允许其他参数。
- data：

```json
{ "items": [ { "merchantId": "...", "merchantName": "1..128", "storeId": "...", "storeName": "1..128", "membershipKind": "OWNER" } ],
  "page": 1, "pageSize": 20, "total": 1 }
```

- 排序固定 `merchantId`、`storeId` 数值 Long 升序；total 为按本人归属过滤后的总数；越界页返回空 items、total 不变。
- 无门店的入驻申请不进入列表（HTTP10 原文）；`staffId` 字段仅 STAFF 行由服务端返回，V1 OWNER 行不产出。
- 错误：401 未登录/失效。纯自范围端点无 403/404。

## 3. GET /api/v1/merchant/auth/admission

- 鉴权：MINIAPP Bearer；参数 `merchantId`、`storeId` 必填 String；携带 `staffId`/`workspace` 等其他参数或 ID 格式非法 → 400。
- 启用条件同上（merchant 模块装配）。
- 无归属（非本人商家/门店不属于商家）：404 `COMMON_NOT_FOUND`（防枚举，与协议端点口径一致）。
- 事实来源不可读或互相矛盾（申请事实缺失、协议接受事实损坏、状态未知枚举）：503 `COMMON_DEPENDENCY_UNAVAILABLE` 且 data=null，失败关闭；不得映射为 DENIED。
- data（Admission）字段与来源：

| 字段 | 类型/取值 | 来源 |
|---|---|---|
| merchantId / storeId | String | 回显已验证归属目标 |
| membershipKind | `OWNER`（V1） | 归属关系 |
| admission | `ALLOWED`/`LIMITED`/`DENIED` | §4 矩阵 |
| checkedAt | 偏移毫秒时间 | 服务端时钟；非可复用通行证 |
| authzVersion | 16 位小写 hex | SHA-256(`membershipKind|merchantStatus|storeStatus|applicationStatus|signingStatus|staffEnabled|storeVersion`) 前 16 字符；客户端只做失效识别 |
| facts.application | `{status}` 或 null | 审核事实读者；V1 OWNER 路径恒非空（缺失即 503），null 位保留给未来权威确认无申请的场景 |
| facts.signing | `{status:NOT_SIGNED/SIGNED}` | 协议接受事实；`SIGNING/FAILED/UNKNOWN` 为 HTTP10 值域保留，V1 OWNER 路径不产出 |
| facts.storeStatus | `ACTIVE/OFFLINE/FROZEN` | 门店权威状态 |
| facts.merchantStatus | `APPLYING/ACTIVE/OFFLINE/FROZEN/CANCELED` | 商家权威状态 |
| facts.staffEnabled | null | OWNER 固定 null（不适用），null 绝不表示已启用 |
| allowedActions | String[] 唯一排序 | §4 提示码；非逐命令授权清单 |
| reasonCodes | String[] | §4；ALLOWED 时空数组，不使用 NONE |
| nextSteps | `{type}` 数组（必填，可空） | §4 固定 type，客户端映射白名单路由 |

## 4. 判定矩阵（OWNER V1；逐行短路顺序即判定顺序）

| # | 已核事实（合取前提：归属有效） | admission | reasonCodes | allowedActions | nextSteps |
|---|---|---|---|---|---|
| 1 | application ∈ {DRAFT, REVIEWING, REJECTED} | DENIED | `APPLICATION_DRAFT`/`APPLICATION_PENDING`/`APPLICATION_REJECTED` | [] | [VIEW_APPLICATION] |
| 2 | application 缺失或未知 | 503 | — | — | — |
| 3 | application=APPROVED ∧ signing=NOT_SIGNED | DENIED | [SIGNING_REQUIRED] | [] | [COMPLETE_SIGNING] |
| 4 | signing 事实损坏 | 503 | — | — | — |
| 5 | APPROVED ∧ SIGNED ∧ merchant=ACTIVE ∧ store=ACTIVE | ALLOWED | [] | [merchant.aftersale.read, merchant.order.read, merchant.penalty.read, merchant.schedule.manage, merchant.service.manage, merchant.staff.manage] | [] |
| 6 | 成员有效 ∧ (merchant=OFFLINE ∨ store=OFFLINE)（其余同 #5 前提） | LIMITED | [MERCHANT_OFFLINE] / [STORE_OFFLINE]（各自成因，可并存） | [merchant.aftersale.read, merchant.aftersale.respond, merchant.order.fulfill, merchant.order.read, merchant.penalty.appeal, merchant.penalty.read, merchant.refund.handle] | [VIEW_AFTERSALES, VIEW_EXISTING_ORDERS] |
| 7 | 成员有效 ∧ (merchant=FROZEN ∨ store=FROZEN)（其余同 #5 前提） | LIMITED | [MERCHANT_FROZEN] / [STORE_FROZEN]（可并存，可与 OFFLINE 并存按实际） | [merchant.aftersale.read, merchant.order.read, merchant.penalty.appeal, merchant.penalty.read] | [APPEAL, VIEW_AFTERSALES, VIEW_EXISTING_ORDERS] |
| 8 | merchant=APPLYING/CANCELED 或未知枚举 | 503（来源不完整，不按枚举优先级猜） | — | — | — |

- 动作码取自 HTTP10 §3 建议提示组（"拟议，非授权清单"）：本 CCR 仅冻结其作为 `allowedActions` 展示值的组合，执行仍逐命令鉴权；`fulfill/handle/respond` 仅出现在 OFFLINE-LIMITED（存量履约/售后语义），FROZEN-LIMITED 不含写提示组（B-FROZEN-WRITE 未决）。
- 判定顺序：归属 → 成员有效 → 事实可读（否则 503）→ 申请 → 签约 → 状态组合；不以新单资格提前返回整体禁入。
- 门店选择规则（客户端消费约束）：memberships 仅一间可访问门店时自动选中并查准入；多门店必须用户显式选择，禁止默认第一间；零门店不进列表。

## 5. 通用

- 两端点均纯查询：`Cache-Control: no-store`，无 requestId 要求（写幂等不适用）。
- 响应信封沿用全局 SUCCESS 包装；HTTP/JSON ID 一律 String（Snowflake BIGINT）。
- 前端复用约束：`/api/v1/merchant/*` 路径沿用现有客户端工作区坐标门禁（admission 在选店切换 merchant 坐标后调用；memberships 在 consumer 坐标调用）。

## 6. 验收对应

- 测试类 `MerchantAdmissionHttpTest`（真实 MySQL/Redis + HttpFixture + 固定 code 微信替身）：真实登录→申请→审批 APPROVE→协议签署后验证 #5 ALLOWED；SQL 变更门店/商家状态验证 #6/#7；跨用户 404；memberships 排序/分页/自范围；503 失败关闭（损坏申请事实）。
- 对应验收编号：W2-AUTH（准入查询）、W2-MER-001/003、25 号补充 D3 四态校验、HTTP10 §工作台选择与准入。
