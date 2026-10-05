# 普通退款申请与真实来源契约 v0.1

2026-09-30；R1/R2 已获用户明确“批准”。来源：SSOT §6/§7/§38、PRD30、[批准提案](../../planning/ccr/CCR-W2-API-001/refund-application-proposal.md)。本契约承接其全部约束，实施仅为默认关闭 INTERNAL 切片；不开放 HTTP、不运行生产迁移、不合并 PR。

2026-10-05 用户裁决（PR #103 阻塞项①—⑤）：新增服务前自动全额退款专门来源 `PRESTART_AUTO`（不再复用 `MERCHANT_TIMEOUT_AUTO`）、补 REF-001 实施章节、明确服务前 merchantDeadline 语义、Created 事件按真实终态发布 AUTO_APPROVED；服务前不留拒绝口子。SSOT §39 已同步修订。

## 范围与归属

REFUND 拥有普通申请、独立幂等准入、不可变决定与退款执行；ORDER 拥有资格、正常付款来源、当前申请投影、CREATE_REFUND 能力及本域提交证明；PAYMENT 拥有原渠道提交/查询；SCHEDULE 拥有成功后的预约释放。模块只消费公共 API，不跨读表。

包括已到服务开始的未核销订单及已核销订单全额普通退款。商家只同意或拒绝；不增加普通退款七天期限。服务前 REF-001、AFS 创建/裁决、PARTIAL、STAFF、HTTP/小程序和通知实际送达不属本批。

## 命令与事实

- `RefundApplicationCommandApi.apply(Apply)`：当前真实 ACTIVE USER 会话、orderId、服务端配置的 reasonCode、可选 reasonText。金额由真实正常付款得出。reasonCode ≤64；说明 ≤500 码点；没有生产默认原因字典。
- `decide(Decide)`：当前真实 OWNER 会话、applicationId、String expectedApplicationVersion、APPROVE/REJECT。拒绝 reasonText 必填且非空白、≤500 码点；不要求未封板的拒绝 reasonCode。真实归属和当前权限在共享门店 guard 下重验，包含幂等重放。OFFLINE 可处理存量订单，FROZEN 新决定失败关闭。
- 首回执固定为 orderId/applicationId/applicationStatus/applicationVersion/merchantDeadline/decidedAt/decisionId；ID 和版本 String，时间 UTC 毫秒。批准回执不表示退款成功，旧回执不随后续状态变更。
- `RefundApplicationTimeoutApi.handle(Timeout)`：受信任任务上下文、applicationId/storeId/expectedMerchantDeadline，提前调用返回 Retry 至真实期限；到期 PENDING 才自动批准。`createApproved(Create)` 只恢复指定申请/决定，不能外部自报 SYSTEM 建单。
- `OrderRefundApplicationApi` 提供 locate/requireEligible/bindApplication/recordDecision/acquireCreate/commitCreated 及本域证明复核。写方法强制同 DataSource 可写 READ_COMMITTED 事务与共享 guard。
- `RefundApplicationApprovalFactsApi` 提供持久 application/decision/approval/created 事实；`OrderRefundApplicationFactsApi` 提供正常付款及已提交的普通退款来源证明。裸状态、事件或客户端 DTO 不构成资金授权。

所有新写命令遵守23号五元组 namespace/operatorType/operatorId/scope/requestId：apply scope=ORDER:id，decide scope=REFUND_APPLICATION:id。独立 Admission 持久加密规范参数，失败保留绑定，同 key 异参冲突；业务事务锁 Admission→store guard→ORDER→REFUND。首回执加密，敏感原文不进入日志/事件；审核 Provider 必须真实配置，无永远通过的生产实现。

## 状态、时间及竞态

每轮 createdAt 使用数据库 UTC 毫秒，merchantDeadline=createdAt+24h。拒绝后新申请必须新行、新ID、新期限；同单仅一笔活动申请（PENDING/APPROVED/AUTO_APPROVED），已批准待建单只能恢复原来源。任何来源/状态 refund_order 已存在即禁止新申请和后续核销。旧决定重放/旧超时任务不改变新轮。

商家新决定只在 now<deadline 可提交；now>=deadline 返回 `REFUND_MERCHANT_DEADLINE_PASSED`。同意/超时竞争只能有一个不可变决定。三阶段分别原子提交：

1. 申请、ORDER引用、超时任务、Created Outbox、首回执。
2. 决定、ORDER投影、Decided Outbox、首回执；批准另带 CREATE 任务，拒绝没有退款单。
3. SYSTEM 重读已批准来源，获得本次当前 ORDER 版本的 CREATE_REFUND token，提交退款单、执行绑定、ORDER引用/提交证明、RefundOrderCreated Outbox、渠道任务。跨 Owner 提交前相互复核，失败全回滚。

PENDING 或批准后尚未建退款单时可核销；核销先完成仍允许普通全额退款。保留 verifiedAt/核销历史，不用批准时 ORDER 版本阻挡合法核销后的恢复。已合法提交的决定不因原用户会话过期或撤权撤销。AFS 未履约失效是另一来源规则，不套用普通退款。

## 来源与执行兼容

只新增 `MERCHANT_APPROVED`（APPROVED/OWNER）及 `MERCHANT_TIMEOUT_AUTO`（AUTO_APPROVED/SYSTEM）两类商家路径来源；2026-10-05 用户裁决后，服务前自动退款（下节 REF-001）另新增第三类来源 `PRESTART_AUTO`（AUTO_APPROVED/SYSTEM，命名沿用 SQL06 `refund_order.source_type` 既有预留值），不复用 `MERCHANT_TIMEOUT_AUTO`。三类来源同样 `sourceBizId=applicationId`、`sourceDecisionId=decisionId`，绑定原正常付款 paymentId/paymentNo/paymentSuccessEventId/channelTradeNo/paidAmount/paidAt 及 order/user/merchant/store/reservation。FULL=真实本金。

普通执行的 late_event_id/source_event_id 都为 NULL；created_event_id 仍是真实退款创建事件。旧 LATE_PAYMENT_TIMEOUT/MERCHANT_REJECT_ORDER 构造器与事件语义保留。PAYMENT 首次发送前独立复核 REFUND 批准事实与 ORDER 提交证明；MAY_HAVE_SENT 先于网络调用，UNKNOWN/重启只查原 refundNo，不重新创建或出款。查询恢复不要求付款状态仍为 PAID。

RefundOrderCreatedEvent.v1/RefundSucceededEvent.v1 字段保持不变；旧消费者识别已知其他来源后跳过，未知来源失败关闭。普通成功投影核验最终渠道事实，消费claim、ORDER金额、SCHEDULE释放同事务；最终成功前不释放，重复成功只释放一次。

## 新事件、任务与恢复

`RefundApplicationCreatedEvent.v1` 精确字段：applicationId/orderId/userId/merchantId/storeId/applicationStatus/merchantDeadline/createdAt。applicationStatus 普通路径固定 PENDING_MERCHANT；2026-10-05 用户裁决：服务前即时批准（REF-001 开启，下节）按提交时真实终态发布 AUTO_APPROVED，字段集与 v1 不变，通知消费者后续交付按此对齐。

`RefundApplicationDecidedEvent.v1` 精确字段：applicationId/decisionId/orderId/userId/merchantId/storeId/applicationStatus/decidedAt。

上述事件由 REFUND 在相应业务事务唯一生产，ID String、时间 UTC 毫秒，不含说明或手机号，仅表达通知意图。消费者及送达后续验收。

任务归 REFUND：`REFUND_MERCHANT_TIMEOUT:{applicationId}`、`REFUND_APPLICATION_CREATE:{applicationId}`、`APPLICATION_REFUND_SUBMIT:{refundId}:0`、`APPLICATION_REFUND_CHANNEL_QUERY:{refundId}:0`。源任务类型、业务ID、版本、载荷、key严格匹配；渠道worker只允许三类普通来源（`MERCHANT_APPROVED`、`MERCHANT_TIMEOUT_AUTO`、`PRESTART_AUTO`，均映射 PAYMENT 既有 `TASK:REFUND_SUBMIT:{refundId}:0` / `TASK:REFUND_CHANNEL_QUERY:{refundId}:0`）。

扫描覆盖已到期 PENDING 和已批准无退款。TASK core 最小兼容新增 `JdbcAsyncTaskRecoverer`：本域事实复核后，在调用者同源可写 RC 事务复用 enqueue 的完整不可变参数检查，仅恢复 DEAD/CANCELED/SUCCEEDED，递增 fencing version、保留 attempt 历史；READY/RETRY_WAIT/RUNNING 不动，不盗取租约。原方案“原则上不改框架”经检查无此公共能力，因此增加此必要公开恢复入口，不跨域修改任务表。

申请恢复逐行隔离损坏证明或不匹配的任务元数据，独立保存脱敏 APPLICATION_PROOF_INVALID / APPLICATION_TASK_CONFLICT，继续后续申请；修复后重新核验并RESOLVED。渠道异常扫描同样隔离损坏来源并记录既有表的 REFUND_SOURCE_PROOF_INVALID，不因此变更资金状态；数据库整体故障仍失败，不将其吞成扫描成功。

## 服务前自动全额退款（REF-001，2026-10-05 用户裁决补入）

SSOT §4.1 封板规则的实施章节（原“不属本批”限制由用户裁决解除，机制仍在本契约范围内，无独立新契约）：

- **触发条件**：开关开启时，买家对真实正常付款订单发起申请，服务端数据库 UTC 时间 `now<预约开始时间` 且订单未核销（verificationStatus≠VERIFIED）。触发点是买家申请本身，不是定时扫描，也不是取消订单。
- **全额=实付**：申请与退款单金额一律等于真实正常付款实付金额（与 FULL=真实本金一致），refundType=FULL；原路复用既有拉卡拉渠道退款执行（`APPLICATION_REFUND_SUBMIT`/`APPLICATION_REFUND_CHANNEL_QUERY` 及 PAYMENT 既有 TASK 映射），无新增渠道能力。
- **决定与来源**：同一申请事务内记录不可变 SYSTEM 决定（decidedAt=createdAt，DB CHECK 强制 SYSTEM/无操作者/无理由密文），绑定专用准入 namespace `refund.application.pre-service-auto`，不入队 `REFUND_MERCHANT_TIMEOUT`；退款单来源为 `PRESTART_AUTO`，与超时来源 `MERCHANT_TIMEOUT_AUTO`（decidedAt≥merchantDeadline=createdAt+24h）按决定绑定命名空间与 decidedAt 数学区分，退款单上可判定。
- **merchantDeadline 语义**：服务前即时批准路径决定与申请同事务（decidedAt=createdAt），merchantDeadline=createdAt+24h=decidedAt+24h，即自决定时间起 24 小时，与 SQL49 CHECK `merchant_deadline=created_at+INTERVAL 24 HOUR` 完全一致；该字段在此路径无商家处理含义。
- **互斥规则**：引用既有硬规则——refund_order 创建成功后立即禁止后续核销与新的普通申请；核销先完成不否决已批准的服务前全额退款；任何来源/状态 refund_order 已存在即禁止新申请；AFS 未履约失效规则来源独立；槽位只在渠道退款最终成功后释放（SSOT §20/§39/§40）。
- **开关**：`pet.refund.pre-service-auto-refund.enabled`，默认 false；关闭时维持既有桩错误码 `REFUND_BEFORE_SERVICE_NOT_IMPLEMENTED`。
- **拒绝路径**：2026-10-05 用户裁决——服务前不留拒绝口子，系统自动全额、不可拒绝（DB CHECK 与应用校验均不允许 REJECTED）；未来如需“服务前可拒绝”属新规则，须另行裁决。
- **事件**：申请 Created 事件按提交时真实终态 applicationStatus=AUTO_APPROVED 发布；Decided 事件照常由决定事务发布；建单/执行/成功事件与普通路径同构，仅 source 字段为 `PRESTART_AUTO`。

## 交付与限制

SQL49/Storage49 披露全部存储增量；未知历史申请阻断迁移，不伪造决定/身份或清空数据。运行默认关闭，缺真实身份、审核、原因配置、加密或来源适配失败关闭。全部新增 SQL 位于本模块 MyBatis XML。

验收须实际执行提案§7及测试映射 RF01～RF19，以隔离 MySQL 和真实申请/决定 API 验证幂等、期限、核销竞态、故障回滚、恢复、渠道来源和释放；旧基线通过不替代本批验证。完整 Issue/AFS/员工/公开流程不提前标完成。
