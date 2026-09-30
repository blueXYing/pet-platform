# 普通退款与售后接续：代码审查及测试映射

日期：2026-09-30。只读代码审查基线：`origin/develop` / `eb5d16ffff5a2db2799545b4f5fd490e1431f99c`（PR95 合并后）。下文路径均为仓库相对路径，行号按该提交核对。本文件是准备材料，未执行新业务实现、Schema 迁移或测试；不得据此将 REF-002/REF-003/REF-004、AFS 或 QA-004 标为 DONE。

## 1. 建议的第一实施批与批准状态

建议第一批为 **REF-002 + REF-003 的普通全额退款来源增量 + REF-004**：真实用户服务开始后退款申请、真实 OWNER 同意/拒绝、24 小时 durable 超时、普通全额退款的持久来源证明、渠道执行及成功后的 ORDER/SCHEDULE 投影。内部内核及 worker 默认关闭；第一批不接 AFS 创建/运营裁决、员工账号授权、公开 HTTP、小程序或生产开关。REF-001 服务前自动退款不混入本批；普通退款和来源能力以本次 CCR 正式冻结后的边界实施。

用户已明确批准的新增产品规则：**商家拒绝后允许再次申请；每次新申请重新计算 24 小时；同一订单同时仅一笔待处理申请；一旦创建 refund_order，禁止再申请。** 该批准不被下文“技术候选”状态撤销；需由主提案同步 SSOT/正式 Contract。其余新增接口名、字段、索引、错误回执和任务协议均为技术候选，需随 CCR 审阅后执行。

建议技术映射：每次拒绝后的新意图创建新 `applicationId`，保留旧申请及决定的不可变历史；同 requestId 同参数返回原申请首次回执，不生成新轮、不延长时限；同 key 异参冲突。已有待处理申请时，另一个 key 不得创建第二笔或重置 deadline，可返回冲突并提供本人有权读取的现有 applicationId，具体错误码/回执待 CCR 冻结。旧申请的超时任务只绑定旧 applicationId，不能改写新申请或其截止时间。

若只交付“申请 + 拒绝来源”，可以作为更小的准备切片，但不能将完整 REF-002 标为已完成；建议本批包含批准/超时真实全额退款闭环，以形成可独立验收的后端能力。

## 2. 已有事实与可复用代码

| 领域/能力 | 已核对路径和行号 | 当前事实与复用边界 |
|---|---|---|
| 共享门店保护 | `backend/pet-schedule-api/src/main/java/com/petplatform/schedule/api/protection/ScheduleCapacityGuardApi.java:6`；`backend/pet-schedule-biz/src/main/java/com/petplatform/schedule/biz/apiimpl/ScheduleCapacityGuardApiImpl.java:36`、`:84` | 已有 acquire / requireHeld。普通申请、批准建单、超时、核销共用同实例、同 DataSource、可写 READ_COMMITTED；不能靠不同锁互相“看过状态”代替串行化。 |
| 所有退款来源的存在事实 | `backend/pet-refund-biz/src/main/java/com/petplatform/refund/biz/apiimpl/RefundOrderFactsApiImpl.java:16`；`backend/pet-refund-biz/src/main/resources/mapper/RefundExecutionMapper.xml:4` | 查询 `refund_order` 全来源、全状态。FAILED 或缺 execution 的退款行也永久阻断核销；读失败不能伪装 NONE。 |
| 正常付款持久事实 | `backend/pet-order-biz/src/main/java/com/petplatform/order/biz/infrastructure/persistence/OrderPaymentStore.java:40` | `lockResult` 保存 ORDER 已消费的付款事实。新普通退款资格由 ORDER 公共 API 提供；REFUND 不得跨读该 Store/Mapper/表。 |
| 真实主账号 | `backend/pet-merchant-biz/src/main/java/com/petplatform/merchant/biz/apiimpl/MerchantOrderAuthorityApiImpl.java:18` | `requireOwner` 校验 USER 主账号与商家/门店，不能用服务人员档案或手机号代替登录授权。 |
| 当前会话装配 | `backend/pet-boot/src/main/java/com/petplatform/boot/config/MerchantOrderConfiguration.java:47` | 真实 MINIAPP 会话与 ACTIVE 用户重验可复用。新执行和历史回放均需当前会话/权限；SYSTEM 已受理任务不能冒充原用户会话。 |
| 写命令幂等 | `backend/pet-order-biz/src/main/java/com/petplatform/order/biz/application/MerchantOrderService.java:54`、`:120`、`:148` | 已有独立 Admission、保护后的规范参数/首回执、幂等执行事务、回放重鉴权。可复用模式，不能 REFUND 直接依赖 order-biz。 |
| 退款执行事实和渠道核心 | `backend/pet-refund-biz/src/main/java/com/petplatform/refund/biz/application/LateRefundService.java:137`、`:145`；`backend/pet-refund-biz/src/main/java/com/petplatform/refund/biz/application/RefundExecutionService.java:38` | 已有 execution/success fact、最终凭证、异步任务、死任务核验。类名虽含 Late，现兼容迟到付款及商家拒单两种来源。 |
| 防重复发送 | `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/application/PaymentRefundService.java:101`、`:204`、`:258` | 首发前持久化 MAY_HAVE_SENT，网络 I/O 在事务外；发送结果未知/重启后仅查原退款号。不能为新来源另做简单重试 submit。 |
| 核销提交能力 | `backend/pet-order-biz/src/main/java/com/petplatform/order/biz/apiimpl/OrderVerificationCommitApiImpl.java:28`、`:41` | VERIFY token 仅绑定当前事务的核销提交，不提供 CREATE_REFUND。新退款协议不能把现有 token 当通用业务授权。 |
| 最小售后失效 | `backend/pet-aftersale-biz/src/main/java/com/petplatform/aftersale/biz/apiimpl/AfterSaleVerificationApiImpl.java:20`、`:38` | 仅实现当前未履约售后的核销原子失效与证明；没有真实申请资格/创建/运营裁决入口。 |

`refund_application` 仅有基础 DDL（`docs/03-database/06-核心数据库Schema-v0.1.sql:394`），backend 没有申请、商家退款处理、24h 任务实现。已有 `current_refund_application_id` 多用于其他命令的未知事实/拒绝门禁，不能作为 REF-002 已实现的证据。

## 3. 新来源必经的代码硬门禁

| 位置 | 现状 | 本批必需增量 |
|---|---|---|
| `backend/pet-refund-biz/src/main/java/com/petplatform/refund/biz/application/LateRefundService.java:185`、`:207`、`:212` | `verifyRow`、`requestKey`、`origin` 仅接受 LATE_PAYMENT_TIMEOUT / MERCHANT_REJECT_ORDER。 | 新普通退款不可变申请/决定来源证明及来源专用键；保留旧来源保护。不得仅扩大字符串白名单。 |
| `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/application/PaymentRefundService.java:224` | 首次发送只支持 ORDER 迟到付款/拒单来源 API。 | 接新普通退款来源公共事实，与 REFUND/PAYMENT 全量绑定比对后才允许首发；已持久发送后的查询仍依原 dispatch。 |
| `backend/pet-refund-biz/src/main/resources/mapper/RefundExecutionMapper.xml:100` | `scanOpen` 仅扫描两个既有来源。 | 新来源进入 durable 恢复/异常扫描，避免 worker 耗尽后永久遗失。 |
| `backend/pet-refund-biz/src/main/java/com/petplatform/refund/biz/application/RefundExecutionService.java:154`、`:176`、`:179` | 死任务恢复、任务名、issueCode 仅覆盖既有来源。 | 新来源的提交/查询/异常分类；任务类型、payload、来源和业务绑定必须一致。 |
| `backend/pet-order-biz/src/main/java/com/petplatform/order/biz/apiimpl/OrderLateRefundProjectionConsumer.java:83` | 只跳过 MERCHANT_REJECT_ORDER，其他非迟到来源抛错。 | 已知新来源的路由处理与对应消费；不能令共享事件一直失败重试。未知来源仍不能被当作普通成功事实。 |
| `backend/pet-order-biz/src/main/java/com/petplatform/order/biz/apiimpl/OrderMerchantRefundProjectionConsumer.java:44` | 只跳过迟到付款，其他非拒单来源抛错。 | 同步来源路由；新增普通退款的 ORDER 投影与去重证明。 |
| `backend/pet-schedule-biz/src/main/java/com/petplatform/schedule/biz/apiimpl/ReservationRefundReleaseApiImpl.java:23`、`:28` | 只允许 MERCHANT_REJECT_ORDER 最终全额成功释放预约。 | 接受经新来源证明的最终全额退款；仍核对原订单/预约/店铺，保持唯一释放审计和失败回滚。 |
| `docs/03-database/45-Merchant-Order-Actions-Schema-v0.1.sql:56` | `chk_refund_source` 只支持两种来源。 | 新迁移扩展约束及一致性预检，不偷改历史已执行 migration。 |
| `backend/pet-boot/src/main/java/com/petplatform/boot/config/LateRefundConfiguration.java:188` | 注册/解码固定提交查询任务协议。 | 普通退款任务注册与准确来源约束；新增配置依赖验证、默认关闭、缺依赖失败。 |

若只在 REFUND 增加申请表写入，PAYMENT 首发、ORDER 成功投影和 SCHEDULE 释放都会拒绝普通来源。必须把这条完整依赖链纳入首批 AllowedModules；不能借增加第三个 source 枚举声称打通。

## 4. 产品硬规则与资格分支

权威依据包括 SSOT §4.3、§6、§7；Internal07 §7.5、§9；HTTP10 §3.9；Scheduler09 §15（`:597`）和 §16（`:647`）。重复申请采用本轮已批准补充。

| 当前状态/来源 | 第一批必须实现或保持的行为 |
|---|---|
| 到达服务开始时间、未核销且无退款单 | 可以创建 PENDING_MERCHANT；截止时间从本次创建的锁内 DB 时间起算 24h。待处理申请本身不消耗核销码、不阻止核销、不释放预约。 |
| 已核销完成、无退款单 | 普通全额退款申请仍合法；不能仅因 VERIFIED 一刀切拒绝。新接口的完整资格须由 ORDER 核实。 |
| 申请等待期间核销先成功 | 普通申请仍可由商家同意，或到 24h 自动批准全额退款。不得将旧未履约售后失效规则套到普通退款申请。 |
| 商家拒绝、无退款单 | 保存真实操作者与买家可读拒绝说明；恢复与真实履约阶段一致的展示语义（未核销待服务，已核销已完成），允许用户以新意图再次申请。旧申请不改回 PENDING。 |
| 已有一笔待处理申请 | 不同 key 不产生第二笔申请、不延长 deadline；相同 key 同参返回原始回执。并发申请必须在 DB/guard 下收敛。 |
| 新轮申请 | 新 applicationId、新创建时间及 24h；保留上一轮拒绝/任务/结果历史。旧任务只能检查旧 applicationId。 |
| refund_order 任何来源/状态已存在 | 禁止新的申请、禁止后续核销；UNKNOWN/FAILED 仍是已建退款边界，不可绕过建立第二退款单。 |
| 商家同意 / 超时建退款 | 仅 FULL，金额来自经验证实际付款；不提供商家部分退款或自由 source/金额入口。 |
| 退款申请/建单/UNKNOWN | 预约继续占用；不能提早释放。 |
| 渠道最终全额成功 | 以 REFUND 最终成功证明更新 ORDER，并通过 SCHEDULE API 释放预约，同提交去重；保留核销时间/原履约历史，不能伪造重新完成。 |
| 旧未履约 AFS 在核销后失效 | 该旧 AFS 不能再裁决退款；完整 AFS 创建/裁决在后续批验证，本批不能宣称已实现。 |

`backend/pet-order-biz/src/main/java/com/petplatform/order/biz/apiimpl/OrderVerificationCredentialFactsApiImpl.java:26` 的 `requireEligible` 是核销资格，`:32` 明确拒绝 VERIFIED；普通退款不得拿它替代退款资格。其真实付款/预约一致性检查可以在 ORDER 模块内部抽取复用，但新的已核销退款分支必须独立允许并核实完成证据。

## 5. 各域 API、存储与配置影响候选

以下仅为实现设计输入，具体命名及字段须在正式 CCR 固化。

| Owner | API/行为候选 | 存储/其他影响 |
|---|---|---|
| REFUND | 普通申请、OWNER 同意/拒绝、SYSTEM 到期执行；查询申请/决定不可变事实；为已批准申请创建全额业务退款，不接外部任意 source/金额。 | 申请 version/CAS、当前待处理唯一约束、每轮 applicationId、决定历史、作用域命令 Admission/首回执、execution 真实 sourceBizId/来源事件及建单关联。 |
| ORDER | 退款申请资格/当前付款及履约事实；同 guard 下登记申请指针/状态；接受真实决定证明绑定业务退款；给渠道/恢复提供可重读的不可变普通退款来源证明。 | ORDER 自有申请/退款投影、状态日志与提交证明；保留 VERIFIED/verifiedAt 与原始付款快照，不能跨域直接写申请表。 |
| PAYMENT | 复用 PaymentRefundApi；首次发送加入已批准普通来源的权威检查，原 dispatch 查询恢复语义不变。 | 既有 dispatch/receipt 原则上复用；若增加来源审计列或校验版本须披露 migration，不能把整条 refactored 路径藏在 REFUND Scope 中。 |
| SCHEDULE | 扩展 ReservationRefundReleaseApi 的可信全额成功来源。 | 既有预约状态及唯一释放日志复用；只在最终成功后释放，核销前后均保留历史。 |
| MERCHANT / USER | 当前 OWNER、门店归属、有效 MINIAPP 会话事实，撤权后重放仍检查。 | 不增加员工绑定，不把 staff profile 当登录主体；复用公共事实与 boot 会话适配。 |
| BOOT / TASK / EVENT | 默认关闭配置、业务依赖验证、REFUND_MERCHANT_TIMEOUT durable handler、普通来源提交/查询注册、既有消费者来源路由适配。 | 任务绑定具体 applicationId/expectedVersion/deadline，不靠扫描线程内存定时；恢复不依赖原用户仍在线。来源事件如仅扩充已定义枚举也须明确消费者兼容，新增 payload 则按事件版本约束处理。 |
| VERIFY / AFTERSALE | 首批主要为真实普通申请/退款与核销并发回归；不新增 AFS 创建/裁决接口。 | 不能通过 SQL 种 AFS/退款假装完成售后闭环。后续单列原 AFS 失效后新售后与核销历史回放的兼容审查。 |

Schema06 `refund_application` 的全局 `uk_refund_application_request(request_id)`（`:412`）不符合公共幂等五元组。需正式迁移/映射为命令作用域，不因两个不同主体使用相同 UUID 错误冲突；历史已存在 key 必须有明确迁移检查，不删除数据过关。

`refund_order.uk_refund_order_once(order_id)`（Schema06 `:438`）继续保留。它约束一单一次业务退款，不等同于“退款申请一生只能一次”；不可把申请表新增 `UNIQUE(order_id)` 破坏本轮已批准重申请。数据库防双 pending 可通过独立当前申请唯一映射或可空生成列等方式设计，具体方案待 CCR。

原因字典仍是开放项：`docs/04-api/10-HTTP-API-Contract-v0.4.md:637` 明确 reasonCode 分类未封板。可提出配置来源或候选字典，但不能把示例 USER_REQUEST 或现有“商家拒单”原因枚举当作已批准退款字典。拒绝文字长度、保护/审查及展示协议亦应同步。

当前 ORDER 代码未找到通用 DisplayOrderStatus calculator，仅 `OrderCreationTypes` 带展示结果字段；本批如内部回执要返回展示状态，也须由 ORDER 计算。公开 HTTP 响应和完整展示查询留后续，避免 REFUND 或前端自拼状态。

## 6. 事务、锁与恢复候选

1. 用户/OWNER 命令先验证参数与当前身份，独立事务持久绑定幂等规范参数；执行事务锁命令行，再取得共享门店 guard，重验当前授权及 ORDER、REFUND 原始事实。等待 guard 后重新取得 DB 时间，不用进入接口时的旧时间作截止判断。
2. 申请事务同提交申请、当前待处理约束、ORDER 申请指针/投影、超时任务、必要状态日志/Outbox、首回执。失败保留 Admission，不留下缺任务的待处理申请或孤立 ORDER 指针。
3. 沿主提案的两阶段候选：OWNER 同意/系统超时先同事务提交不可变决定、ORDER 决定投影、决定Outbox、唯一建单恢复任务与不可变首回执；随后SYSTEM建单事务重验来源，同提交refund_order、refund_execution、ORDER退款指针/证明、RefundOrderCreatedEvent和首发任务。金额全额取原付款事实。拒绝事务同提交拒绝决定、结束当前待处理、ORDER 投影、决定Outbox和首回执；无退款单、无渠道任务。
4. 普通退款与 VERIFY 必须使用同一个门店 guard；退款建单先提交后 VERIFY 拒绝。VERIFY 先提交时普通退款来源仍允许继续，不能滥用当前 VERIFY 的资格条件或持久 token。
5. Scheduler09 的 AUTO_APPROVED 后宕机未建退款状态必须可恢复。主提案选择真实批准API先持久化，建单worker独立恢复，不能靠SQL伪造半成品验收。普通APPROVED同样恢复；批准后核销改变ORDER版本仍允许按当前版本取得新的CREATE_REFUND能力，不能把批准时版本当永久栅栏。提前timeout重排至持久deadline，到期PENDING的缺失/DEAD/错误CANCELED任务按task-core协议恢复；不可拿当前新轮申请顶替旧来源。
6. `PaymentRefundService` 的持久发送意图与出事务网络调用顺序不变；发送可能成功后，无论数据库 ACK 丢失、网络超时、worker lease 丢失或重启，都只查原退款号。原 PAYMENT 已观察 REFUND/REVOKED 时不能强制要求 PAID 使合法旧结果无法恢复，也不能用旧 SUCCESS 重新首发。
7. 最终退款成功由 REFUND 持有验签结果证明；ORDER 成功投影、消费去重和 SCHEDULE 释放同事务，任一点失败全部回滚。新来源要明确既有消费者跳过/路由策略；不支持的未知来源不能当已验证来源执行。
8. 新增生产 SQL 只能放 Owner 模块 Mapper XML，经 MyBatis 执行；不得跨 biz 依赖或跨域访问 Repository/Mapper/DO/Entity；guard 内参与者不得自行 REQUIRES_NEW 提交。

## 7. 最小必要验收矩阵

下列用例为 **NOT_EXECUTED**；应通过真实普通退款申请/决定 API 建立业务来源。用于时间推进/故障注入/损坏负例的 SQL 可用，但不能用 INSERT 申请/决定/退款替代正向业务闭环。

| 编号 | 场景与操作 | 必须断言的结果/证据 |
|---|---|---|
| RF-01 | 真实下单、支付、确认，预约开始后本人申请。 | 一条 PENDING_MERCHANT、正确 ORDER 指针、DB createdAt +24h、唯一 durable timeout；无 refund_order，预约仍占用。 |
| RF-02 | 跨用户申请、失效/停用会话、伪身份；OWNER 跨商家/错店处理与撤权回放。 | 无权请求不产生业务写；成功旧回执不授予当前处理权限；staff 档案/手机号不授予权限。 |
| RF-03 | OWNER 同意、OWNER 拒绝（分别真实申请）。 | 同意阶段返回不可变APPROVED回执并持久决定/建单任务；worker后续全额建单，金额取真实付款；拒绝原因存储和操作者真实，不建退款。 |
| RF-04 | 商家拒绝后用户新 key 再申请。 | 新 applicationId、新 createdAt/deadline；旧拒绝记录/原因/任务不改；同单仅一条待处理。 |
| RF-05 | 已有待处理时同 key 重放/异参；两个不同 key 并发申请。 | 同参同首回执，异参冲突；不同 key 不双建、不延长时限。相同 UUID 在不同已授权主体/作用域不全局误冲突。 |
| RF-06 | 建立新轮后投递旧轮超时/旧决定重放；篡改 applicationId/version/deadline/task key。 | 旧任务不改新申请、旧结果不覆盖当前指针；错绑定失败关闭，原始时限不重置。 |
| RF-07 | 提前执行 timeout、恰好/超过 24h，OWNER 同意/拒绝与 timeout 并发；到期任务缺失/DEAD/CANCELED恢复。 | 提前任务重排不吞掉未来超时；锁内DB时钟裁决，仅一份最终决定/一张退款；拒绝赢家后旧超时无效；now>=deadline商家新处理拒绝。 |
| RF-08 | 通过真实批准API/超时handler提交APPROVED/AUTO_APPROVED后暂停建单worker，再重启/重复处理；其中一组在暂停间完成真实核销。 | 沿原决定补齐一张退款及一个首发任务，原金额/来源稳定；不创建新申请或借新轮延时；核销造成ORDER版本更新不阻断合法普通来源。 |
| RF-09 | PENDING_MERCHANT 期间用真实有效码核销，再 OWNER 同意；另一轮用 timeout。 | 核销成功且申请不被误失效；核销后普通全额退款仍成功，保留原 verifiedAt/履约事实。 |
| RF-10 | 已核销完成后首次普通申请、拒绝后再次申请。 | 满足真实资格时允许；拒绝后维持已完成语义，不倒退待服务；重申请遵循 RF-04。 |
| RF-11 | OWNER 同意/timeout 建退款与真实 VERIFY 同时竞争，控制两个锁获取顺序。 | 建退款先提交时 VERIFY 被阻止；VERIFY 先提交时后续普通退款仍合法。任何状态 refund_order 存在时不得新申请或再核销。 |
| RF-12 | 申请、ORDER 指针/日志、timeout task、决定、refund_order、execution、created Outbox、首回执逐点注入失败。 | 对应业务事务全回滚；独立 Admission 保留同 key 参数绑定；移除故障后同参可继续，异参仍冲突。 |
| RF-13 | 错 DataSource、只读/错隔离事务、缺 guard/过期或伪 token、孤立 ORDER 或 REFUND 提交。 | 不得提交部分状态；事务内交叉证明缺失失败，异常不能伪装为无退款事实。 |
| RF-14 | 篡改/伪造申请决定、source、源事件、金额、订单/店/用户、付款绑定后执行渠道任务。 | 不发生首次渠道发送；可核查的拒绝/异常事实；事件 payload 自身不授予退款权。 |
| RF-15 | 渠道接受后网络超时、发送/业务提交 ACK 丢失、worker lease 丢失及重启。 | 仅一个 MAY_HAVE_SENT 与原退款号；以后只查询，不第二次 submit；恢复成功回执与原编号一致。 |
| RF-16 | 渠道 SUCCESS 错金额/错误交易号/无验签证明，UNKNOWN、重试耗尽/DEAD/CANCELED。 | 不标记业务成功、不释放预约；持久核验问题与恢复入口存在；新来源进入扫描。 |
| RF-17 | 真实已验证 SUCCESS 重复/乱序投递；ORDER 投影、消费去重或 SCHEDULE 释放任一点故障。 | 只有可信成功可释放，三者同提交且可重放；成功前预约占用；原支付/拒单事件重放不复活订单。 |
| RF-18 | 既有迟到支付、商家拒单、核销、改期、任务注册及配置回归。 | 保持旧来源规则；新事件不会使旧消费者无限错误重试；默认关闭，缺依赖无法启用。 |
| RF-19 | 架构/持久层/展示状态/契约检查与迁移负例。 | 无 biz→biz、无跨域 Mapper、无新增 Java SQL；ID String/金额精度合规；全局 requestId 与 source CHECK 的迁移可核查，历史不被删除。 |

第一批不使用上述 RF-11 代替完整 AFS×VERIFY 的 QA-004。后续必须再通过真实 AFS 创建/拒绝来源/运营裁决证明：退款先建阻核销、核销先完成使旧工单失效且旧裁决不可退款、核销后合法新售后仍可处理。

## 8. 测试设施与现有证明的限制

| 复用设施 | 精确路径 | 用途及限制 |
|---|---|---|
| 隔离 MySQL / Schema 装载 | `backend/pet-boot/src/test/java/com/petplatform/boot/booking/BookingCreateAcceptanceTest.java:799` | Database 支持隔离业务验收与 migration；新增普通退款 migration 应纳入装载及反例。 |
| 真实支付/商家链路 Fixture | `backend/pet-boot/src/test/java/com/petplatform/boot/booking/MerchantOrderAcceptanceTest.java:258` | F 提供正常付款、Owner 决定与退款执行基础；不是普通申请实现。 |
| 故障/ACK/渠道来源用例 | `backend/pet-boot/src/test/java/com/petplatform/boot/booking/MerchantOrderAcceptanceTest.java:71`、`:120`、`:143`、`:155`、`:198` | 可沿用事务断点、最终投影、UNKNOWN、来源伪造、数据库确认丢失模式。 |
| 核销凭证 Fixture | `backend/pet-boot/src/test/java/com/petplatform/boot/booking/VerificationCredentialAcceptanceTest.java:172` | T 复用真实付款/确认/改期与有效码。 |
| 核销完成组合 | `backend/pet-boot/src/test/java/com/petplatform/boot/booking/VerificationCompletionAcceptanceTest.java:163` | components 装配 ORDER/VER/AFS 真实提交证明，适合普通退款竞争。 |
| 当前售后种子边界 | `backend/pet-boot/src/test/java/com/petplatform/boot/booking/VerificationCompletionAcceptanceTest.java:174` | seed 直接 INSERT aftersale_case；只能证明最小失效内核，不能证明售后创建/裁决已交付。 |
| 当前退款阻核销边界 | `backend/pet-boot/src/test/java/com/petplatform/boot/booking/VerificationCompletionAcceptanceTest.java:98` | 当前测试直接 INSERT refund_order；本批必须补真实批准/timeout 建退款后竞争，不可据现有测试声称全来源闭环完成。 |
| 迟到付款恢复回归 | `backend/pet-boot/src/test/java/com/petplatform/boot/booking/LateRefundAcceptanceTest.java` | 重跑事件真假来源、UNKNOWN 原号查单、ACK 丢失、Outbox/任务原子性及旧来源 migration 约束。 |
| PAYMENT 实证 | `backend/pet-payment-biz/src/test/java/com/petplatform/payment/biz/application/PaymentRefundServiceMySqlTest.java` | 检查新来源不会放松旧首发来源/金额证明以及持久 dispatch 的恢复语义。 |

下一实施批还需单列兼容风险：`OrderVerificationCommitApiImpl.requireCommitted` 与 `AfterSaleVerificationApiImpl.requireCommitted` 保存/核对核销时旧工单版本/指针。接真实“核销后新工单”时需证明历史核销首回执回放不会被合法新工单误判为损坏；不得为了通过测试删掉历史或放宽旧未履约工单退款限制。

## 9. 实施准入与验收披露

- 先以 CCR 统一新来源/API/Schema/事件及任务协议、原因输入和错误回执；本轮已批准重申请规则直接纳入，不再作为待决定项。
- AllowedModules 至少涵盖 REFUND/ORDER API 与 biz、PAYMENT API/biz、SCHEDULE API/biz、BOOT、受影响 TASK/EVENT 装配、测试与正式 Contract/SQL；MER/USER 限复用公共事实或必要适配，不扩员工系统。
- 所有新开关默认关闭；第一批不开放 HTTP/小程序，不生产迁移、不启动生产 worker。实现 PR 描述须披露真实来源覆盖和 AFS/员工/端到端遗留，不用现有 PR95 测试数替代新验收。
- 本文件仅文档准备，业务测试状态 NOT_EXECUTED；后续通过目标 MySQL 验收、既有受影响回归及架构/持久层检查后，才能报告对应实现切片完成。
