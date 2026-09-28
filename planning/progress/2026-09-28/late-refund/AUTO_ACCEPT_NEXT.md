# 三十分钟自动接单：迟到退款之后的最小后续切片核对

基线：`74f480f`。本文件只梳理后续实施边界，不实现、启用自动接单，也不把迟到支付退款事件误认为正常付款接单。遵循 `AGENTS.md`、`WORK_EXECUTION_PROTOCOL.md` 的 SSOT 优先、Contract 缺失走 CCR、P0 测试和默认关闭边界。

## 已定业务事实与当前交付状态

- SSOT §1、§3：真实正常支付后进入待确认；商家 30 分钟未处理则系统接单并进入待服务。商家先接单或拒单时自动任务失效；改期成功后重新待确认并重新开始 30 分钟。支付超时关闭后的迟到支付始终不恢复、不进入接单（`docs/00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md:12,29,78-125,1054-1086`）。
- 首轮期限起点**已定为渠道事实 `paidAt`**，不是回调收到时间、ORDER 消费时间或任务提交时间。`confirmDeadline = paidAt + 30min`（`docs/04-api/07-内部API-Contract-v0.6.md:1328-1348`；`docs/05-events/08-Integration-Event-Catalog-v0.6.md:67-85`；`docs/04-api/40-Payment-Foundation-Contract-v0.1.md:49-51`）。若事件消费已经晚于期限，提交到过去的 `execute_at` 后应尽快执行，但仍逐项核当前事实。
- 当前正常 PaymentSucceeded 消费在同一主库事务内核 PAYMENT 权威事实、写 ORDER `PENDING_CONFIRM/PAID` 与 `confirm_deadline`、确认 SCH 预约、保存 `NORMAL` 支付结果、发布 `OrderPaidEvent.v1`；迟到分支只保留关闭并发 `LatePaymentSucceededAfterTimeoutEvent.v1`，没有正常 OrderPaid（`backend/pet-order-biz/src/main/java/com/petplatform/order/biz/apiimpl/OrderPaymentResultApiImpl.java:143-220`；`.../OrderPaymentStore.java:25-99`）。40 号明确：写截止值和事件**不代表**自动接单已交付（`docs/04-api/40-Payment-Foundation-Contract-v0.1.md:49-51`）。现有 ORDER 代码搜索不到 `ORDER_AUTO_CONFIRM` 提交/Handler/确认写入；`pet_order.confirm_mode` 与 `confirmed_at` 尚无对应写入。
- MySQL 核心草案已有 `pet_order.reschedule_count`、`confirm_mode`、`confirm_deadline`、`confirmed_at`、`refund_order_id`、索引，以及 `refund_order.order_id` 唯一；`async_task` 有唯一 `task_key`、`execute_at`、payload、租约和状态（`docs/03-database/06-核心数据库Schema-v0.1.sql:227-271,417-440`；`docs/03-database/13-Async-Infra-Schema-v0.1.sql:5-43`）。`confirmRound` 可按首轮 0、改期后 1 与 `reschedule_count` 对应，但尚无单独 `confirm_round` 字段。

## 后续最小可实现范围（内部、默认关闭）

1. **正常付款生产任务。** 只在通过 PAYMENT 权威核对并成功写 ORDER 正常付款的同一事务里，提交 `ORDER_AUTO_CONFIRM:{orderId}:0`，`execute_at = confirmDeadline`，payload 固定 `expectedConfirmDeadline` 与 `expectedConfirmRound=0`。与 `OrderPaidEvent.v1` 一起提交；回滚时订单、任务、事件一起回滚。迟到支付分支绝不创建任务（`docs/06-scheduler/09-Scheduler-Retry-Compensation-v0.5.md:504-542,1344-1369`；`docs/05-events/08-Integration-Event-Catalog-v0.6.md:410-438`）。现有 `JdbcAsyncTaskSubmitter.enqueueAt` 可在同一 DataSource 事务提交，重复键内容一致才重放（`backend/pet-task-core/src/main/java/com/petplatform/task/core/JdbcAsyncTaskSubmitter.java:12-101`）。
2. **系统任务执行。** Handler 用固定 `TASK:ORDER_AUTO_CONFIRM:{orderId}:{round}` requestId，解析并严格核 task type/key/bizId、round、毫秒期限；不能从 payload 直接确认。ORDER 在短事务按一致锁顺序核 `PENDING_CONFIRM`、`PAID`、当前轮次与期限完全等于任务预期且 `confirm_deadline <= DB now`，并核当前退款/取消/核销及预约权威事实；可推进时 CAS 到 `PENDING_SERVICE`，写 `confirm_mode=AUTO`、`confirmed_at=DB now`、状态日志和一次 `OrderConfirmedEvent.v1`。旧轮次、商家先处理、订单取消/退款等是 NOOP；事实未知或依赖不可用是可重试失败，不放行、不手改数据库（`docs/06-scheduler/09-Scheduler-Retry-Compensation-v0.5.md:526-562,1248-1265,1411-1430`）。现有 task worker 按类型 claim、租约恢复和重试，但业务幂等仍靠 ORDER（`backend/pet-task-core/src/main/java/com/petplatform/task/core/AsyncTaskWorker.java:16-24,91-145`）。
3. **竞态与代际。** 商家确认/拒单与任务同一 ORDER 当前状态 CAS：先提交者胜，另一方 NOOP 或 409；改期成功的当前轮次变 1、期限为改期成功时刻 +30 分钟，旧任务需取消，并且即使已被 worker 拿到也必须在 Handler 核 `expectedConfirmRound` 与 `expectedConfirmDeadline` 后 NOOP；新任务键 `ORDER_AUTO_CONFIRM:{orderId}:1`（`docs/06-scheduler/09-Scheduler-Retry-Compensation-v0.5.md:543-589`）。**当前仓库尚无改期/商家确认拒单实现；首轮内部切片不能宣称已完成这些业务入口的联动**。任务核心目前只有 claim 后 `Cancelled` 完成，没有取消尚未 claim 的任务公共方法（`backend/pet-task-core/src/main/java/com/petplatform/task/core/mapper/AsyncTaskMapper.java:1-52`），改期切片须补上明确取消契约和实现；旧任务再核代际仍是最后防线。
4. **默认关闭及历史待确认。** 建议独立 `pet.order.auto-confirm.enabled=false` 控制生产任务/内部命令装配，`pet.order.auto-confirm.worker.enabled=false` 单独控制 worker，且不开放公共 HTTP、不触发真实渠道。现有付款基础、到期任务、Outbox 均为显式 opt-in（`backend/pet-boot/src/main/java/com/petplatform/boot/config/PaymentFoundationConfiguration.java:24-29`；`.../BookingExpiryConfiguration.java:26-66`；`.../EventOutboxConfiguration.java:22-29`）。如果生产任务也默认关闭，后来启用时已有 `PENDING_CONFIRM` 可能没有任务；启用前须按权威当前事实幂等补建/扫描，并覆盖停机后 `confirm_deadline <= now`，否则无法满足恢复规则（`docs/06-scheduler/09-Scheduler-Retry-Compensation-v0.5.md:1248-1265`）。启用与历史数据处置是后续显式决定，不随代码合并自动执行。

## 必须先经 CCR 冻结的契约缺口

| 缺口 | 为什么现有契约不足 / 冻结项 |
|---|---|
| ORDER 自动确认命令 | 07 号仅有 `autoConfirmOrder(AutoConfirmOrderCommand)` 方法名，没有字段、expected round/deadline、幂等结果/冲突语义，也没有可调用 Java DTO/实现（`docs/04-api/07-内部API-Contract-v0.6.md:569-610`）。须定义 SYSTEM 入口与状态 CAS、状态日志、DB 时间及失败关闭语义；不把任务 DTO 当作权威命令。 |
| 退款与自动接单同单并发 | 服务前退款自动全额；`refund_order` 创建后不可继续履约/核销。07 号既有 `CREATE_REFUND` guard 是与 `VERIFY` 的互斥，不含 `AUTO_CONFIRM`（`docs/04-api/07-内部API-Contract-v0.6.md:627-703,938-949,1405-1425`）。必须冻结退款申请中/退款单创建中/已创建时能否自动接单，以及退款创建、商家拒单、自动接单对同一 ORDER 的锁顺序和提交点。仅在 Handler 查询一次退款快照有 TOCTOU 风险；不得让已创建退款单后仍推进到待服务。迟到退款对应关闭订单和无正常任务，作为负例覆盖。 |
| PAYMENT/SCH 权威复核 | 付款消费曾核 PAYMENT 与预约，但任务可能晚执行；须冻结任务时是否调用 `PaymentSuccessFactsApi`/ORDER 正常支付结果和 SCH `ReservationConfirmApi.assertConfirmed` 等公共 API，以及出现 PAYMENT `RECONCILIATION_REQUIRED`、预约失效/事实不可得时的处理。不可跨模块读 Repository/表，也不能以 `OrderPaidEvent` payload 或 `payment_status=PAID` 单字段替代当前权威核对（`backend/pet-order-biz/src/main/java/com/petplatform/order/biz/apiimpl/OrderPaymentResultApiImpl.java:149-188`；`backend/pet-schedule-api/src/main/java/com/petplatform/schedule/api/command/ReservationConfirmApi.java:1-12`；`docs/04-api/40-Payment-Foundation-Contract-v0.1.md:35-51`）。 |
| 确认事件、字段名称和旧任务取消 | 08 号仅列 `OrderConfirmedEvent.v1` 生产者/消费者，未给严格 payload schema（`docs/05-events/08-Integration-Event-Catalog-v0.6.md:28-47`）。09 号写 `confirmation_type=AUTO`，测试也用此名，而 06 Schema 实际列为 `confirm_mode`（`docs/06-scheduler/09-Scheduler-Retry-Compensation-v0.5.md:534-542`；`docs/07-testing/14-全链路测试矩阵-v0.1.md:68-74`；`docs/03-database/06-核心数据库Schema-v0.1.sql:248-254`）。须统一正式 DB/事件契约；改期旧任务从 READY/RETRY/RUNNING 取消的 API/事务排序尚不存在。 |
| 初次启用的遗留行 | 当前支付基础已可产生无自动接单任务的 `PENDING_CONFIRM` 行。需冻结回填范围、何时扫描、按什么正常支付/退款/预约事实放行、和任务唯一键冲突的处理；不能把仅新付款产任务称为三十分钟自动接单完整交付。 |

上述是技术与跨模块契约缺口，不要求改动 SSOT 产品规则。CCR 审定后，优先做首轮正常付款任务 + SYSTEM Handler + MySQL 并发验收；商家确认/拒单和改期联动由其各自写入切片接入，直至联动完成前保持对外默认关闭。

## 验收与证据边界

- 用现有本机隔离 MySQL 夹具扩展：`PaymentFoundationAcceptanceTest.Fixture` 复用 `BookingCreateAcceptanceTest.Database`，后者依次加载 06/13/38/39/40/41 SQL 且只接受本机 DB URL（`backend/pet-boot/src/test/java/com/petplatform/boot/booking/PaymentFoundationAcceptanceTest.java:584-647`；`.../BookingCreateAcceptanceTest.java:799-852`）。现有付款测试只验证 `paidAt/confirm_deadline` 精确时间，尚未验证任务/自动确认（`PaymentFoundationAcceptanceTest.java:430-453`）。
- P0/P1 必测：正常付款原子任务唯一与 `execute_at`；迟到支付零任务；提前执行不确认；到期成功、重复/租约重领只一次状态日志和事件；商家确认/拒单先后、退款创建先后、改期旧轮次已 claim、数据库时钟/时区、停机恢复及缺失权威事实失败关闭。对应 `PAY-012`、`CONF-001`～`006`、并发 `CON-006`（`docs/07-testing/14-全链路测试矩阵-v0.1.md:68-74`；`docs/07-testing/15-并发故障测试矩阵-v0.1.md:17`）。
- 本次只读核对，未运行测试；没有业务代码改动、数据库迁移、worker 启用或真实渠道调用。
