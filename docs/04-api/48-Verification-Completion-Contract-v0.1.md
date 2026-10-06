# 48 — 核销完成与最小售后失效

状态：APPROVED，2026-09-30，用户批准 M94/K1/K2；**K1 于 2026-10-06 原号修订（v0.2）**，依据 [52 号绑定 CCR §5 用户裁决](../../planning/ccr/CCR-W2-API-001/staff-identity-binding-proposal.md)（D2/D4/D5 及「核销记录必须可追溯到店员」），SSOT §37、商家 PRD §5.6/§5.8。**K2 于 2026-10-06 原号修订（v0.3）：交付对外 HTTP 面（HTTP10 §4.7 路由），见 K2 修订段。**本契约覆盖 47 中“无真实主账号适配/无核销完成”的阶段限制；不改码期限、风险阈值或退款规则。

## K1 身份（v0.2 修订）

只接受有效 MINIAPP 会话的 ACTIVE USER，会话 operatorId 必须为该会话真实 userId；每次执行、beforeCommit 复核及重放均重新解析身份，旧回执不授予权限。核销命令在 VER 事务内按固定鉴权链「成员动作门 → 核销」解析唯一操作身份，两路径互斥（52 号存储禁止伪造 OWNER 成员行）：

- **OWNER 路径（不变）**：MER 在共享门店 guard 下重验 merchant.owner_user_id 与门店归属（ACTIVE/OFFLINE 存量履约，FROZEN 失败关闭）。operatorType=USER、membershipKind=OWNER、operatorStaffId=null、落库 operator_id=真实 userId。
- **STAFF 路径（本修订新增，开关 `pet.verification.staff-identity.enabled`，默认 false，开启前置要求 52 号内核 `pet.merchant.staff-identity.enabled=true`）**：OWNER 校验未通过时，由 52 号 `requireStaffAction` 在 guard 事务内对 member/grant/动作行 `FOR UPDATE` 锁定并重验当前事实——动作仅 `merchant.order.verify`（D2：V1 店员仅核销一个动作，订单处理权不授予）；FROZEN/OFFLINE 失败关闭、无存量例外（D5）；未确认邀请（无 ENABLED 成员/grant）、DISABLED/REVOKED、无 grant、动作未授予、申请未批、协议未签均拒绝（D4：锁内重验失败即整体回滚，在途事务先提交者胜）。门通过后在同一锁保护下读 grant 的展示 staffId；grant 无 staffId 时拒绝——核销记录必须可追溯到店员，禁止无店员身份的共享核销（PRD §5.6，核销权限绑定员工账号；核销人所属门店须与订单门店一致，PRD §5.8，由 attemptLocation 既有门店一致性校验保证）。operatorType=MERCHANT_STAFF、membershipKind=STAFF、operatorStaffId=该 staffId、落库 operator_id=operatorStaffId（SQL48 CHECK 固定）；真实登录用户恒以 verification_credential_command（actor_type='USER'、actor_id=真实 userId）与五元组 requestId 留痕，风险计数仍记真实会话用户。

**落库与审计口径**：verification_attempt / verification_record 写入上述身份四元组（operator_type、operator_id、membership_kind、operator_staff_id）；order_verification_commit.operator_id 恒为真实会话 USER id（命令真实来源）；OrderVerifiedEvent.v2 的 operatorType/operatorId/membershipKind/operatorStaffId 与 VER 落库四元组一致。重放要求当前解析身份与持久身份一致，不一致即失败关闭；撤权后提交与撤权后成功回执重放均拒绝（STA-05 以真实 MySQL 竞争测试证明）。不把服务人员档案、roleName、phone、service_enabled 或客户端声明身份当作授权来源。

## K2 命令与首回执

内部 VerificationCompletionApi.verify(Command)：context/orderId/storeId/verificationCode/expectedCredentialVersion/confirmed。confirmed 必须 true，方式固定 SCAN；无手动订单号兜底。码语法沿用 47 的 1～128 位大写字母数字，版本非负十进制 String。成功回执 orderId/attemptId/resultCode=VERIFIED/verificationId/verifiedAt/orderVersion。无效码、过期码、风险锁为已提交业务结果，其后三字段 null；风险计数与 47 共用系列。

namespace=verification.complete，二进制五元组(namespace,operatorType,operatorId,STORE:storeId,requestId)唯一。独立 Admission 持久绑定加密参数；执行回滚保留绑定。成功重放返回首回执的原始时间与版本，经当前权限及本域持久证据校验；另一个 key 对已核销订单返回 VERIFICATION_ALREADY_DONE。~~没有 HTTP Controller，不接受客户端声明的身份。~~（v0.3 修订：HTTP 面已交付，身份仍不接受客户端声明，见下段。）

### K2 HTTP 面（v0.3 修订，2026-10-06 核销 HTTP 切片）

`POST /api/v1/merchant/orders/{orderId}/verification`（HTTP10 §4.7 既有路由；`MerchantOrderVerificationController`，随 `pet.verification.completion.http.enabled` 装配、默认关闭，开启要求 completion 内核）：

- 请求体严格 JSON `{verificationCode}`（1~128 位大写字母数字），`X-Request-Id` 终端 UUID，五元组幂等同上；未知字段/显式null/重复键 400。无 query 参数、无手动订单号兜底、`confirmed` 恒由服务端置 true、方式恒 SCAN。
- **merchantId/storeId 服务端解析**：由订单经 `OrderVerificationCredentialFactsApi.locate` 路由到门店 guard；客户端不声明门店或店员（沿 HTTP10 §4.7）。操作身份按 K1 v0.2 链在 guard 事务内解析（OWNER 先行；`pet.verification.staff-identity.enabled` 与 52 号内核双开时 STAFF 路径可用）。
- **expectedCredentialVersion 服务端解析（本修订新增内部只读 API `VerificationCredentialApi.currentVersion(orderId)`，SYSTEM 作用域、无 guard、不证明资格或授权）**：扫码语义下商家设备不掌握版本号，适配层在命令组装时读取当前 VER 系列 version 传入；命令事务内仍按本契约原样复核（刷新竞态导致不一致时 409 `COMMON_CONFLICT`，商家重扫即可），码哈希校验独立保证只认当前活码。
- 回执：200 + `orderId/attemptId/resultCode/verificationId/verifiedAt/orderVersion`；`resultCode=VERIFIED` 携带后三字段，无效码/过期码/风险锁为已提交业务结果（200、后三字段 null）。错误：400 参数、401 未登录、403 无权/门拒绝/依赖失败关闭、404 K1 防枚举（未确认/撤权等按不存在处理）、409 `VERIFICATION_BLOCKED_BY_REFUND`/`VERIFICATION_ALREADY_DONE`/`VERIFICATION_NOT_ALLOWED`/`COMMON_CONFLICT`/`IDEMPOTENCY_KEY_CONFLICT`、503 依赖不可用；一律 `Cache-Control: no-store`（12号 §7 状态映射同步增补）。

## 同事务提交协议

顶层 VER 事务为同 DataSource、可写 READ_COMMITTED，命令行锁→共享门店 guard→ORDER 原始资格/VERIFY token→退款当前事实→VER 当前码/版本/风险→AFS 当前工单。公共 check 不得嵌套；仅复用 VER 包内校验内核。

OrderVerificationCommitApi.acquire 创建仅当前事务有效的随机 VERIFY token，绑定 commandId、可信 context、ORDER 当前版本与完整事实；不提供 CREATE_REFUND。负面码结果 release token 后提交风险尝试。markVerified 必须使用相同 token 和持久化 VER 成功证据、AFS 证据，并携带 VER 解析出的操作身份四元组（ORDER 不自行推导、不读 VER 表）；更新 COMPLETED/VERIFIED、verified_at/completed_at、version+1、状态日志及唯一 OrderVerifiedEvent.v2。ORDER 自有提交记录保存版本、核销/凭证/尝试/命令/事件关联，其 operator_id 恒为真实会话 USER id。

AfterSaleVerificationApi.invalidateCurrent 从 ORDER token 取得真实来源，只访问自身工单。核对订单、用户、商家、门店、source_stage、active_flag 和指针。无指针且无 active 行返回明确 NONE 证据；指针缺行、反向孤儿、身份不一致或未知状态均失败。当前 active 的 UNVERIFIED_POST_START 且 PENDING/PROCESSING/WAITING_SUPPLEMENT 才失效，version+1、active=0、INVALIDATED、invalidatedAt=verifiedAt，保留证据和历史指针。已结束历史工单不改写；不能以未履约订单为来源失效 VERIFIED 工单。AFS 记录不可变核销证明，ORDER 同步 aftersale_status。

VER 写唯一成功记录、真实操作人尝试、凭证 VERIFIED 消耗；ORDER/AFS/VER 提交前相互复核同一次核销的持久证据。孤立完成、孤立失效、错误 DataSource、只读事务、旧 token/跨事务 token 均不能提交。任一持久化、日志、Outbox 或首回执失败整体回滚。

## 事件与迁移

OrderVerifiedEvent.v1 不变。v2 由 ORDER 唯一生产，aggregate=ORDER/orderId，payload 严格为 orderId/verificationId/merchantId/storeId/operatorType/operatorId/membershipKind/operatorStaffId/verifiedAt；所有 ID 为 String，staffId 可空，时间 UTC 毫秒，无码/手机号/昵称。v0.2 起身份四元组镜像 VER 落库值：OWNER 为 USER/真实 userId/OWNER/null，STAFF 为 MERCHANT_STAFF/staffId/STAFF/staffId（真实登录用户经 command 关联追溯，见 K1）；旧消费者按“operatorType=USER 且 membershipKind=OWNER”过滤即可保持原语义。通知与评价消费者后续交付。

SQL48 扩展原成功/尝试表的真实身份和 command_id，移除全局 request_id 唯一键、保留 order_id 成功唯一键；新增 ORDER/AFS 提交证据与 ORDER 售后状态投影。表结构不变（CHECK 已含 STAFF 形状），仅写入语义按 K1 v0.2 开放。现无可核实员工绑定历史的来源，因此迁移遇到任何旧核销行即拒绝，必须先另行提供并审阅真实映射，绝不猜测 OWNER 或抹除历史。仅隔离 QA 执行迁移。

pet.verification.completion.enabled=false；开启需要 credential 内核及其真实依赖/密钥。`pet.verification.staff-identity.enabled` 默认 false，且仅在 `pet.merchant.staff-identity.enabled=true` 时可开启，否则启动失败。~~不开 HTTP~~（v0.3：`pet.verification.completion.http.enabled` 已交付、默认关闭，开启要求 completion 内核；47号 §4 的 C 端凭证路由同批交付）、~~生产开关~~或 worker 仍不开；STAFF 路径依赖 52 号绑定切片（#107）产出的真实 member/grant/动作数据，测试种子仅作反例与门禁证据，不声称绑定交付。~~员工端核销页面与绑定/授予写命令（52 号 STAFF-B，#107）未交付~~（绑定/授予写命令已由 54号/#107 交付；员工端核销页面归 M 端，后端通道本批就绪）；完整售后创建/裁决/CREATE_REFUND、员工授权矩阵与端到端 VER-002/QA-004 均未交付。
