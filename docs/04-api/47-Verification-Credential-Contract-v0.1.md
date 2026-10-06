# 47 — 真实核销码生命周期与改期失效

2026-09-30 接续：真实 OWNER 适配、成功核销和最小售后失效已获 K1/K2 批准，见 [48号契约](48-Verification-Completion-Contract-v0.1.md)。下文“无适配器/成功核销后续”等为 47 交付时点边界，由 48 对应部分接续；码生命周期和风险规则不变。

**2026-10-06 原号修订（v0.2）**：§4 保留的 GET/POST `/api/v1/c/orders/{orderId}/verification-code` 已按本契约形状落地实现（默认关闭，`pet.verification.credential.http.enabled`，详见 §4 修订段）；码生命周期、风险与加密规则不变。

状态：APPROVED。用户于2026-09-29明确“批准 V1、V2”；[批准方案](../../planning/ccr/CCR-W2-API-001/verification-credential-proposal.md)§2～7的协议、字段、事务及验收范围在此冻结。SSOT §36覆盖原PRD第三/第四次失败及按单码锁的歧义。SQL见[47号Schema](../03-database/47-Verification-Credential-Schema-v0.1.sql)。

## 生命周期与接口

内部`VerificationCredentialApi`提供issue/read/check。issue的Issue为context/orderId/expectedCredentialVersion/refreshKind，kind仅INITIAL/AUTO/MANUAL。读为orderId+可信USER QueryContext。check为context/orderId/storeId/verificationCode，调用方须经必需AttemptAuthority重验当前商家权限，不能自行声明USER/MERCHANT_STAFF来授权。

资格由ORDER `OrderVerificationCredentialFactsApi`在共享店guard下判断：正常PAID、PENDING_SERVICE、UNVERIFIED、确认来源/预约绑定完整、无任何来源refund_order；仅售后/退款申请不阻断，服务开始后仍可使用。未知来源、依赖故障、密钥不可用失败关闭。

read是只读，不创建state、不延长有效期；NONE/ACTIVE/EXPIRED/INVALIDATED/LOCKED，仅ACTIVE返回码。首次生成、到期自动刷新和手动刷新都是幂等写入；version为VER当前版本十进制String，初始0。INITIAL仅无当前码，AUTO仅当前码已过期，MANUAL滚动60秒最多5次成功提交。版本冲突不替换当前码，第6次手动刷新COMMON_RATE_LIMITED。DB now >= expiresAt即过期。

凭证是160bit随机源产生的32位字母数字码，绑定订单、商家、门店、预约、当前服务时间与确认轮次；每次签发5分钟，刷新成功立即废旧，失败旧码仍保留原截止。AES-256-GCM保护码/幂等输入/回执，独立HMAC-SHA256查找摘要；密钥标识和密文信封支持旧密钥读取，缺密钥失败。代码不记录明文码，敏感DTO的toString脱敏；未来HTTP必须Cache-Control:no-store且禁止请求体日志。

issue首回执orderId/credentialId/credentialVersion/code/issuedAt/expiresAt/refreshAfter，ID/version均String，refreshAfter=expiresAt。读视图orderId/credentialVersion/status/code?/expiresAt?/refreshAfter?/lockedUntil?。check首结果orderId/attemptId/resultCode/checkedAt，VALID仅表示该时刻通过凭证预检，不完成订单，不是可用于以后绕过最终guard的授权凭据。

消费者写scope=CONSUMER；核验scope=STORE:storeId，namespace分别verification.issue/verification.check，actor按可信context，五元组二进制唯一。独立Admission保存受保护规范参数与版本/hash；失败保留绑定；执行重放前复核当前身份/归属权限。同key返回不可变首结果，原码过期/失效不会被重放复活或延长；后续真正VERIFY必须在其最终事务重新核验当前码和ORDER资格。check的业务失败结果持久化，不因返回无效码结果回滚风险计数。

## 风险与事务

滚动5分钟，第3个独立无效/过期码尝试触发15分钟锁。锁属于订单凭证系列；刷新、改期、设备切换和重启均不清零。锁内读取不回显码、禁止签发；锁内重试不累计错误、不续锁。15分钟届满不复活过期码。无权/跨店/参数错误/依赖故障不计码错误。

每次新锁定与attempt、唯一`VerificationRiskLockedEvent.v1`及首回执同提交。payload为orderId/storeId/triggerAttemptId/lockedAt/lockedUntil/reasonCode，reasonCode=INVALID_CREDENTIAL_THRESHOLD；聚合VERIFICATION/orderId，标准事件envelope，ID String，无码/个人信息。通知消费者/送达未交付。

执行锁序：命令执行行→共享门店guard→Owner事实→VER状态；RC顶层本地事务。issue写废旧、新码、state、刷新记录、首回执一起提交。risk写attempt、锁、Outbox、首结果一起提交。时间使用持guard后的数据库UTC毫秒时间。GET即使读取使用锁保护一致性，也不做业务写入。

真实`VerificationRescheduleFenceApi`加入46号同DataSource可写事务：验证ORDER原始归属/轮次，旧码失效、epoch递增、清current、唯一fence同事务；未发过码也建立持久边界。提交前经ORDER公共事实核对相同rescheduleId/fenceId/时间，孤立fence不提交。ORDER/SCH/TASK或VER任一点失败全部回滚；改期后的码属于新epoch。

所有码校验实时核ORDER资格；退款单创建、取消、核销完成后不依赖异步事件才能拒绝旧码。仅售后不失效。正常改期后的旧码永远不再可用。

## 装配、迁移与限制

`pet.verification.credential.enabled=false`，`.http.enabled=false`（v0.2 起 http 开关落地为真实装配前置：`.http.enabled=true` 要求 credential 内核开启，否则启动失败）。内核开启需要schedule/payment/auto-confirm/merchant基础开关及Owner事实、当前会话、显式CredentialProtection密钥、可信AttemptAuthority。~~未提供默认商家核销身份适配器~~（已被48号 K1 v0.2 的 staff-aware 权威取代：OWNER 恒可用，STAFF 路径随 `pet.verification.staff-identity.enabled` 装配）；QA显式授权替身只用于内核验收，不代表员工权限交付。

默认密钥配置为key-id、encryption-key、lookup-key（后两者Base64的32字节）；可注入带历史key ring的CredentialProtection支持轮换。没有默认开发密钥；生产密钥管理、历史恢复与迁移仍须另行验证。

**v0.2 修订段（2026-10-06，核销 HTTP 切片）**：§4 保留的两条路由已实现（`CVerificationCredentialController`，随 `pet.auth.c.enabled` + `pet.verification.credential.http.enabled` 装配，默认关闭）：

- `GET /api/v1/c/orders/{orderId}/verification-code`：订单本人 MINIAPP 会话只读，返回完整视图 `orderId/credentialVersion/status/code?/expiresAt?/refreshAfter?/lockedUntil`（NONE/ACTIVE/EXPIRED/INVALIDATED/LOCKED，仅 ACTIVE 回显码），不创建 state、不延长有效期；`refund_order` 已创建时 409 `VERIFICATION_BLOCKED_BY_REFUND`。
- `POST /api/v1/c/orders/{orderId}/verification-code`：签发/刷新，请求体严格 JSON `{expectedCredentialVersion, refreshKind}`（INITIAL/AUTO/MANUAL 由客户端明示，服务端按本契约生命周期规则核验：INITIAL 仅无当前码、AUTO 仅当前码已过期、MANUAL 滚动60秒最多5次成功，第6次 429 `COMMON_RATE_LIMITED`）；`X-Request-Id` 终端 UUID，走 verification.issue 五元组幂等（同 key 同参重放返回受保护首回执，异参 409 `IDEMPOTENCY_KEY_CONFLICT`）；首次与重放均 200（回执含明文码，非 REST 资源创建）。成功回执 `orderId/credentialId/credentialVersion/code/issuedAt/expiresAt/refreshAfter`。
- 两条路由一律 `Cache-Control: no-store`，未知字段/显式null/重复键/尾随 token 400（原“未来HTTP拒绝”自本切片生效）；错误码复用 12号（400/401/403/409/429/503，见 OpenAPI11）。

本批仍无商家成功核销的~~公开Controller~~（已由 48号 K2 HTTP 面交付，见 48号 v0.3）、C端页面联调、手动订单号兜底或真实通知送达。原HTTP10的verificationStatus字段由这里完整视图替代，不产生新的ORDER版本读侧。

SQL47新增六张VER表，无生产迁移。停用入口不删除码历史/风控锁/fence；已有改期事实依赖真实VER证据时，不得回退到伪fence或重新接受旧epoch。完整VER-001/VER-002/ORD-003不提前DONE。
