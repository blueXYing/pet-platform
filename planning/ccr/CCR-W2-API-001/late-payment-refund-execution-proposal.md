# 迟到支付自动退款执行：契约审查与实施边界

状态：**APPROVED / IMPLEMENTED_INTERNAL**，基于 `develop` 74f480f（PR88/89 已合并）。2026-09-28 用户对本稿明确回复“批准 A/B 推荐技术方案”。批准范围是下述内部核验/原子建单及发送/验签持久协议；不授权真实拉卡拉交易、生产迁移、生产开关或新 PR 合并。候选描述保留作为审批来源，实际字段/状态以 [42号实施契约](../../../docs/04-api/42-Late-Payment-Refund-Contract-v0.1.md) 为准。三十分钟自动接单为后续独立切片。

## 已确定，不再请求产品裁决

| 规则 | 证据与实施含义 |
|---|---|
| 订单因付款超时关闭后才确认真实成功 | [SSOT §22](../../../docs/00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md)及[07 §26](../../../docs/04-api/07-内部API-Contract-v0.6.md)：保持 `CANCELED/PAYMENT_TIMEOUT`，不恢复预约/券、不发布正常 `OrderPaidEvent`、不进入商家 30 分钟接单。退款金额是该笔**渠道实付金额**，不能用订单应付金额、优惠券金额或通知声称金额代替已验签事实。 |
| 一张业务退款单 | SSOT §22、07 §9.5、[SQL06](../../../docs/03-database/06-核心数据库Schema-v0.1.sql)：`payment_order.order_id` 与 `refund_order.order_id` 均唯一。在 V1 一单一支付且仅一笔已确认渠道成功事实的前提下，迟到成功对应一张 `FULL/LATE_PAYMENT_TIMEOUT` 业务退款单；渠道查询、提交重试与多条 `refund_transaction` 不另建退款单。这里的“原金额”指此支付的真实 `channelPaidAmount`，非订单标价。同单多笔不同真实扣款是现有唯一键无法自动表达的遗留异常，冻结自动处理并登记对账，不纳入本次两项契约审批。 |
| 既有事件链 | [08 §3/§17](../../../docs/05-events/08-Integration-Event-Catalog-v0.6.md)、[09 §29](../../../docs/06-scheduler/09-Scheduler-Retry-Compensation-v0.5.md)：PAYMENT 唯一成功事实/Outbox → ORDER 关闭态投影与 `LatePaymentSucceededAfterTimeoutEvent.v1` → REFUND 幂等建单 → 原路提交、查单、最终事件。确定性业务键为 `EVENT:LATE_PAYMENT_AUTO_REFUND:{paymentId}:{orderId}`。`UNKNOWN` 保持退款中并查原号；自动重试耗尽形成 `LATE_PAYMENT_AUTO_REFUND_FAILED` 对账问题。 |
| 公共幂等与事务 | [23 §5/§7](../../../docs/04-api/23-公共接口与幂等契约补充-v0.1.md)：写命令携带稳定 `requestId`，Provider HTTP 不在本地事务内；事件消费日志与消费方业务变化同事务。任务 lease 丢失不撤销已发生的业务副作用。 |

## 审批前仓库事实（保留历史）

- [PaymentNotificationService](../../../backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/application/PaymentNotificationService.java) 对已验签通知/查询统一写 `payment_channel_receipt`、PAID、实付金额、渠道流水、唯一成功事件与 Outbox；退款/撤销先到会置 `RECONCILIATION_REQUIRED`，不应再从迟到事件盲目退款。[PaymentSuccessFactsApiImpl](../../../backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/apiimpl/PaymentSuccessFactsApiImpl.java) 要求 PAID、OBSERVED 和匹配的 SUCCESS 回执。
- [OrderPaymentResultApiImpl](../../../backend/pet-order-biz/src/main/java/com/petplatform/order/biz/apiimpl/OrderPaymentResultApiImpl.java) 在同店 guard/事务内验证 PAYMENT 权威成功事实及 `PAYMENT_TIMEOUT` 关闭证据，写 `order_payment_result` 的 `LATE` 投影并发迟到事件。`order_payment_result` 对 order/payment/source event 各有唯一键（[SQL40](../../../docs/03-database/40-Payment-Foundation-Schema-v0.1.sql)）。[PaymentFoundationAcceptanceTest](../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/PaymentFoundationAcceptanceTest.java) 当前明确断言迟到事件后 `refund_order` 仍为 0；因此现有测试证明的是**退款意图**，不是退款执行。
- `pet-refund-api`、`pet-refund-biz` 目前仅包占位；没有 `RefundCommandApi`、迟到事件消费者、退款持久化或拉卡拉退款/查单实现。[07 §8～9](../../../docs/04-api/07-内部API-Contract-v0.6.md) 的 `PaymentRefundApi` 与 `RefundCommandApi` 仍是文档接口。[SQL06](../../../docs/03-database/06-核心数据库Schema-v0.1.sql) 的 `refund_order.source_type` 注释尚未列 `LATE_PAYMENT_TIMEOUT`，实际字段长度允许该值，但这不是运行实现。
- [JdbcOutboxConsumeGuard](../../../backend/pet-event-core/src/main/java/com/petplatform/event/core/JdbcOutboxConsumeGuard.java) 可把消费日志并入同 DataSource 事务；[JdbcAsyncTaskSubmitter](../../../backend/pet-task-core/src/main/java/com/petplatform/task/core/JdbcAsyncTaskSubmitter.java) 要求同 DataSource 当前事务。[OutboxDispatcher](../../../backend/pet-event-core/src/main/java/com/petplatform/event/core/OutboxDispatcher.java) 对没有注册消费者的事件保持等待；必须显式注册退款消费者和任务处理器，不能只创建类或仅凭任务 lease 认为渠道副作用恰一次。现有支付和 Outbox 运行组件默认关闭。

## 本轮最小实施建议

1. **只接收可信迟到事实。** REFUND 消费者严格验证事件 type/version、ORDER aggregate/orderId、Snowflake 字符串 ID、正数两位人民币实付及时间；再通过公共 API 核对当前 ORDER `CANCELED/PAYMENT_TIMEOUT`、`order_payment_result=LATE`、原 `paymentId/paymentNo/sourceEventId/channelPaidAmount`，以及 PAYMENT 的已验签 SUCCESS 与 `OBSERVED`。事件载荷仅是索引，不单独授权资金动作。当前 ORDER 公共 API 尚无 `LATE` 投影 getter；见下文契约缺口。任何绑定冲突、旧退款单来源或金额不符、支付 `RECONCILIATION_REQUIRED` 均失败关闭并进入对账，不覆盖或补造事实。
2. **原子建单与排任务。** REFUND Owner 在一笔 READ_COMMITTED 本地事务内先取得同店 guard，重读/锁定第 1 步的当前权威事实，再锁定/检查本单退款事实；按固定 requestId 写 `FULL`、`LATE_PAYMENT_TIMEOUT`、`refund_amount=渠道实付`、`refund_ratio=1.000000`、原支付关联与 `CREATED`，同事务写消费日志、`RefundOrderCreatedEvent.v1` Outbox 和唯一提交任务。事务失败整体回滚；提交 ACK 丢失时按 `(order_id)` 唯一键、固定 requestId 和不可变原支付/金额恢复原单，不换 refundNo。退款单创建是永久禁止后续核销的边界，不能把仅收到迟到事件当成已建单。若系统已有其他业务退款单，不覆盖、不创第二张，转对账。
3. **提交与查询分离。** REFUND 提交任务调用 PAYMENT `PaymentRefundApi`；PAYMENT 在短事务内重验 REFUND 公共业务退款事实和原 PAYMENT 成功事实，并持久化原退款号、原渠道支付流水、原金额及“可能已发送”界线，然后事务外调用渠道。网络超时、签名异常、进程崩溃或 ACK 丢失均保持 `UNKNOWN/PROCESSING`；下次只用原 `refundNo`/渠道请求号查原单。仅在经验证的 Provider 同号幂等能力允许时，才原号重提；不因 `FAILED` 文本、查询暂未找到、任务 lease 到期或重试次数耗尽自动造新号或重提。PAYMENT 对回调/查单先验签、核商户/原交易/原退款号/金额/币种/最终状态并持久化回执；REFUND 再从 PAYMENT 公共已验签事实 getter 读取，不信任裸返回 DTO。最终 `SUCCESS` 只发一次 `RefundSucceededEvent.v1`；未知持续查询并保留“退款中”。
4. **异常恢复。** 退款单、Outbox 与初始任务必须同提交；任务成功与本地状态 ACK 丢失时，重试先读取当前事实并查询原号。DEAD/长期 UNKNOWN 要有持久对账项与告警，恢复后仍沿原退款单。原订单和预约始终保持关闭/已释放；迟到退款成功后券消费者 NOOP、无原积分流水则积分消费者 NOOP（09 §29.1～29.3）。

上述第 2 步的本地建单、消费去重、任务入库可沿既有 07/08/09 规则开发并在隔离 MySQL/离线 Provider fixture 验证；没有正式拉卡拉商户参数时，不做真实提交、真实查询或对外成功承诺。三十分钟自动接单属于正常 `OrderPaidEvent` 链路的独立后续切片，不能由迟到事件产生。

## 两项重大 Contract 补充：已批准的候选记录

以下保留送审时的 API/Schema/执行边界推荐值；用户已按 `WORK_EXECUTION_PROTOCOL.md` §4 批准 A/B，最终 Java 签名及字段见42号实施契约。不重新审批 SSOT §22 的退款产品规则。既有 `LatePaymentSucceededAfterTimeoutEvent.v1` 与 `RefundSucceededEvent.v1` 沿用 v1，不借此改变公开 HTTP。

### 补充 A：ORDER 权威迟到事实 + REFUND 业务执行事实

**ORDER 只读 API。** 增加 `OrderLatePaymentFactsApi.locateStore(orderId, QueryContext)`，仅给未加锁的门店定位提示，不赋予退款资格；`requireLatePayment(orderId, paymentId, storeId, QueryContext)` 必须在调用方同 DataSource、已持同店 guard 的短事务内锁当前 `pet_order` 与 `order_payment_result`，返回只读 `LatePaymentFact(orderId,storeId,paymentId,paymentSuccessEventId,channelTradeNo,channelPaidAmount,channelPaidAt,orderStage,cancelReason,paymentStatus,resultType)`。只有 `CANCELED/PAYMENT_TIMEOUT/PAID/LATE` 且关闭日志与结果行一致才返回；缺失、冲突、已撤销/退款冲突或历史孤立行均拒绝。`paymentNo` 不在 ORDER 投影中，仍由 PAYMENT 的现有 `PaymentSuccessFactsApi.requireSucceeded` 返回并核对。`sourceEventId` 在 ORDER 投影中指原 `PaymentSucceededEvent.v1`，迟到事件自己的 eventId 另外保存，不能混用。

**REFUND 公共 API 与业务表。** `RefundCommandApi.createRefund` 沿 07 §9.2/§9.4 语义实现，迟到事件消费者使用确定性 `CommandContext.requestId=EVENT:LATE_PAYMENT_AUTO_REFUND:{paymentId}:{orderId}`。新增 `RefundExecutionFactsApi.requireForChannel(refundOrderId,refundNo,paymentId,storeId,QueryContext)`，由 REFUND Owner 在同店 guard/同 DataSource 当前事务内锁定并返回业务退款单及不可变执行绑定；供 PAYMENT 在任何首次退款发送前校验，不接受任意 SYSTEM DTO 自报金额。另提供 `requireSucceeded(refundOrderId,orderId,storeId,QueryContext)` 给 ORDER 的 `RefundSucceededEvent.v1` 消费者核对 REFUND 当前最终事实。所有跨模块 ID 为十进制 String、金额为 BigDecimal、时间为明确时区时间；QueryContext 只作调用背景，不是资金授权。

`refund_order` 保留 SQL06 的 `UNIQUE(order_id)`、Snowflake `refundNo`、`FULL/LATE_PAYMENT_TIMEOUT`、`refund_ratio=1.000000`、状态和核销阻断边界。新增 REFUND Owner `refund_execution`，以 `refund_order_id` 为主键，至少不可变保存 `order_id`、`payment_id`（唯一）、`refund_no`、原 `payment_success_event_id`、迟到事件 `late_event_id`、原 `channel_trade_no`、`channel_paid_amount`、`refund_amount`、`currency=CNY`、确定性 `request_id` 与创建时间；`refund_amount == channel_paid_amount > 0` 是持久约束，任何重放逐字段核对，不可更新为新的付款/金额/来源。可变进度只保存版本、最终结果关联及对账状态，不能污染上述原始绑定；历史行无绑定时失败关闭，不猜测回填。

REFUND 消费迟到事件时，先查 ORDER 门店提示；在一笔 READ_COMMITTED 本地事务内按固定顺序取得同店 guard → ORDER `requireLatePayment` → PAYMENT `requireSucceeded` → REFUND 退款行/绑定。对比 ORDER 投影、PAYMENT 已验签金额/流水/付款号/原成功 eventId 与迟到事件载荷；PAYMENT `RECONCILIATION_REQUIRED`、`REFUND/REVOKED/PART_REFUND` 回执先到，均冻结并转对账，不从迟到事件推断应二次退款。同事务提交 `refund_order`、`refund_execution`、`integration_event_consume_log`、`RefundOrderCreatedEvent.v1` Outbox、`TASK:REFUND_SUBMIT:{refundOrderId}:0` durable task；任一失败整体回滚。并发新事件、同 event 重放、ACK 丢失按唯一键与固定 requestId 读取原结果，绝不重造 refundNo。任务 payload 只放业务 ID/原 generation，不放密钥或原始报文。

### 补充 B：PAYMENT 退款渠道所有权、持久发送界线与验签结果

**接口语义。** 落实 07 §8.2 已有 `PaymentRefundApi.submitRefund(ChannelRefundSubmitCommand)` / `queryRefund(ChannelRefundQuery)` 抽象。建议两命令仅允许可信 SYSTEM，携带固定 `requestId`、`refundOrderId`、`refundNo`、`paymentId` 与期望原绑定版本；提交命令不把调用方传入的金额、商户号、原渠道交易号当权威值，PAYMENT 从自己的原支付事实和 REFUND `requireForChannel` 读取并逐项比较。返回 `PENDING_QUERY/SUCCESS_OBSERVED/RECONCILIATION_REQUIRED` 等协调状态只提示后续动作，不是退款成功证明。新增 `PaymentRefundResultFactsApi.requireVerified(refundOrderId,refundNo,paymentId,storeId,QueryContext)`，在同店 guard/同 DataSource 事务内读取 PAYMENT 自己验签并持久化的退款回执，返回原绑定、渠道退款号、渠道最终状态、金额、回执来源/摘要与结果时间。REFUND 仅据此推进 `refund_order`，不能信任 Provider 裸 DTO 或异步任务自报成功。

**PAYMENT 持久表。** 新增 PAYMENT Owner `payment_refund_dispatch`：`refund_order_id` 主键，`payment_id/refund_no` 唯一，原支付渠道流水、商户绑定、币种、原实付及本次全额退款金额、稳定 `channel_request_no`、请求摘要、首次发送时点、发送代际、状态、version、创建/更新时间。上述身份/金额/原请求字段首次绑定后不可变。状态最小集合：`PREPARED`（本地验证完、确定未发送）、`MAY_HAVE_SENT`（发送资格已持久化，可能在途）、`QUERY_PENDING`（未知/ACK/回调缺失，仅查原号）、`VERIFIED_SUCCESS`、`VERIFIED_TERMINAL_FAILURE`（仅经已核实终局语义）、`RECONCILIATION_REQUIRED`。新建或历史缺事实不能从 null、任务 lease、HTTP 异常推断 `PREPARED`。`payment_refund_receipt` 至少存 `refund_order_id`、`SUBMIT/QUERY/CALLBACK` 来源、已验签响应 SHA-256、原支付/退款号、渠道退款号、渠道状态/金额/时间与收到时间；按 `(refund_order_id,receipt_sha256)` 去重，对渠道退款号建立符合渠道空值语义的唯一约束。只存最小脱敏事实，不存原始签名包、账户或密钥。

**网络边界。** 提交前 PAYMENT 按同一顺序取得店 guard → ORDER 当前迟到事实（若接口需要）→ 自己原 PAYMENT 已验签成功事实 → REFUND `requireForChannel` → 自己的 `payment_refund_dispatch` 锁/CAS，核对业务退款金额等于渠道原实付且原订单仍为迟到支付；只有无既有派发事实的 `PREPARED` 可在短事务中原子推进 `MAY_HAVE_SENT` 并提交。**提交成功后才出事务发送一次**；提交 ACK 未知视为可能已发送。之后任何超时、崩溃、退款请求 ACK、签名失败、`UNKNOWN`、查询暂未找到或任务 lease 丢失都只能用同一 `refundNo/channel_request_no` 查原单，不能直接再提交。已验签查询/回调由 PAYMENT 写 receipt 和派发状态；`REFUND/REVOKED/PART_REFUND` 原支付回执先到时保留 `RECONCILIATION_REQUIRED`，禁止把晚到旧 SUCCESS 当可退款新款。只有官方确认原号重提幂等能力后才可单独评审开放原号重提；本次默认不实现该路径。Provider HTTP 不持有任何数据库事务或店 guard。

**REFUND/ORDER 最终投影。** REFUND 处理查询/通知结果时，在同店 guard + 本地事务中调用 PAYMENT `requireVerified`，再锁自己的业务单与执行绑定。已验签成功且原号/金额/渠道事实完全匹配时只一次写 `refund_order=SUCCESS`、`refund_transaction`、`RefundSucceededEvent.v1` Outbox 与消费/任务结果；`UNKNOWN` 保持处理中并入 `TASK:REFUND_CHANNEL_QUERY:{refundOrderId}:0`，终局失败仅按已核实渠道语义写失败并发既有失败事件。提交任务只能在原单最终结果已提交或查询任务已持久入库后标记完成；若在这一步前崩溃，原提交任务必须可重跑并从 PAYMENT 的 `MAY_HAVE_SENT/QUERY_PENDING` 转原号查询。查询任务用固定 generation/requestId 重试，DEAD 后由持久对账扫描接管。ORDER 消费 `RefundSucceededEvent.v1` 时通过 REFUND `requireSucceeded` 复核当前最终事实，投影 `FULL/已退款`，原 `order_stage=CANCELED`、`cancel_reason=PAYMENT_TIMEOUT` 和 SCH `EXPIRED` 不变；迟到源券消费者 NOOP，无原积分奖励流水则积分消费者 NOOP。退款完成不反向触发正常 `OrderPaidEvent` 或 30 分钟自动接单。

**默认关闭与审批范围。** 新消费者、提交任务、退款 Provider 与查单任务分别显式注册并默认关闭；无正式商户参数、退款协议/签名/时区/终局语义或生产 Snowflake/主库保障时失败关闭。离线 Provider fixture 与隔离 MySQL 可验证上述本地协议，但不构成真实原路退款验收。补充 A/B 的新增公共 API、`refund_execution`、`payment_refund_dispatch/receipt`、精确任务键/状态及结果事实，是本次请求的两项重大 Contract/Schema 补充；实际 DDL、代码、PR 合并、生产迁移和启用仍按各自门禁。正式拉卡拉退款报文、原支付流水映射、同号幂等与终局保证属于外部事实核实，不伪装成已批准或已验收事实。

供本次人工审批的精确范围：**A** 批准 ORDER/REFUND 上述受同店 guard 保护的事实 API、`refund_execution` 不可变绑定，以及退款单/消费日志/Created Outbox/提交任务同库原子性；**B** 批准 PAYMENT 的 `PaymentRefundApi` 内部执行语义、已验签结果 getter、`payment_refund_dispatch/receipt` 和首次发送界线/原号查单状态机。两项均只授权内部离线实现及相应契约/隔离测试；渠道真实提交、生产迁移/开关、新 PR 合并不在本次批准范围。

## 最低验收与披露

- MySQL 真实事务测事件重放、两个 REFUND 消费者并发、与 PAYMENT 原 SUCCESS/退款撤销回执竞争、退款单唯一键冲突、建单/执行绑定/消费日志/Outbox/任务任一步失败整体回滚、提交 ACK 丢失后的原单恢复；断言迟到只建一张原金额退款单，PAYMENT 发送资格仅一次，且始终没有正常 `OrderPaidEvent` 或商家自动接单任务。
- 离线 Provider 测“已发但超时、进程崩溃、回调与查单竞争、查询 UNKNOWN、退款/撤销先于迟到消费、不同金额/流水/商户、坏签名、任务 lease 丢失及 DEAD 对账”。UNKNOWN 只查原退款号，不生成第二张业务退款单或第二次未经证明的渠道提交。
- REFUND 结果使用 PAYMENT 已验签 getter 才能推进；伪造任务返回值、裸 Provider DTO、坏验签回执与晚到旧 SUCCESS 不能形成 `RefundSucceededEvent.v1`。ORDER 重放成功事件仅一次投影 FULL/已退款，仍保持 CANCELED/PAYMENT_TIMEOUT、SCH EXPIRED，券/积分按 09 §29 做 NOOP 或流水核对。
- 运行架构检查：REFUND 仅依赖其他模块 `api`，不访问其 Mapper/Repository/DO/Entity；Provider HTTP 在事务外；新增事件/任务消费者显式注册且默认关闭。相关测试通过以前不宣称自动退款完成。PR 描述应单列实际合约/Schema 变更、渠道未联调、正式参数缺失和生产启用门禁。
