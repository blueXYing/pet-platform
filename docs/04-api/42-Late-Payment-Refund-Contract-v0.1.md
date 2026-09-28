# 迟到支付退款执行 v0.1

2026-09-28，用户明确批准 [CCR A/B](../../planning/ccr/CCR-W2-API-001/late-payment-refund-execution-proposal.md)。补充 07/08/09/23/40/41，使用 SQL42、43、44。适用范围仅 `FULL/LATE_PAYMENT_TIMEOUT`；不实现其他退款来源、部分退款、公开退款 HTTP 或真实渠道启用。

## 业务单与事实

`LateRefundService` 只消费严格的 `LatePaymentSucceededAfterTimeoutEvent.v1`。ORDER 的 `OrderLatePaymentFactsApi.locateStore` 只给门店提示；在同店 guard、同 DataSource 的 READ_COMMITTED 事务里，`requireLatePayment` 再核对 CANCELED/PAYMENT_TIMEOUT、LATE 支付投影及 SCH EXPIRED。PAYMENT 的原 `requireSucceeded` 核对已验签实付、渠道交易号、币种、原成功 eventId；事件载荷不能单独授权退款。

退款金额固定为渠道真实实付金额，一单一 `refund_order`，一原支付一 `refund_execution`。绑定表保存原 order/payment/paymentNo/store/merchant/user、成功与迟到事件、实付金额/时间、退款号、固定 requestId 和 Created eventId。身份、金额与 `bindingVersion=0` 不可修改；进度 version 可递增。历史退款缺执行绑定时失败关闭，不推断为从未发送。

`EVENT:LATE_PAYMENT_AUTO_REFUND:{paymentId}:{orderId}` 绑定建单。业务单、执行绑定、消费日志、`RefundOrderCreatedEvent.v1` 和首发任务同库原子提交。任务 `task_key=REFUND_SUBMIT:{refundId}:0`，执行命令 `requestId=TASK:REFUND_SUBMIT:{refundId}:0`；任务 payload 仅 refundOrderId/storeId/bindingVersion。原事件重放须核原不可变事实，不生成第二退款号。自己的退款成功后，旧迟到事件重放无需再次要求 PAYMENT 仍为 PAID。

REFUND `RefundExecutionFactsApi.requireForChannel` 在当前 guard/事务内返回不可变业务授权，包含 Created eventId/时间；PAYMENT 不能使用调用方声称的金额/商户。`requireSucceeded` 返回已持久退款成功及唯一成功 eventId，供 ORDER 投影复核。所有 ID 使用 String、金额 BigDecimal、UTC 持久时间。

## 唯一发送与查询

PAYMENT 内部 `PaymentRefundApi.submitRefund/queryRefund` 接受可信 SYSTEM、固定请求键、refundId/refundNo/paymentId/storeId 和原 bindingVersion。首次发送前重新校验 ORDER、PAYMENT 与 REFUND 权威事实，将 `payment_refund_dispatch=MAY_HAVE_SENT`、原退款号、支付双流水/商户/金额/请求摘要及最早查询时间同事务提交，出事务才发一次请求。提交确认丢失、HTTP/验签异常、进程崩溃或 lease 丢失之后只查原退款号，不再次 submit。已观察到原付款退款/撤销的首次发送进入核验，不用旧 SUCCESS 重新退款。

SQL43 Owner 为 PAYMENT；已验签回执保留 SUBMIT/QUERY 来源、响应摘要、金额、原交易及退款流水。`PaymentRefundResultFactsApi.requireVerified` 仅返回当前持久且完整匹配的 SUCCESS；协调 DTO 的 VERIFIED_SUCCESS 只是提示，不能授权业务完成。官方 FAIL 的终局保证未确认，本轮仍查单或核验，不允许自动重新退款。

本轮查询统一采用保守的至少 30 秒间隔，并用主库 UTC 时间验证；官方明确规定的是**退款超时后至少 30 秒再查**，未把所有处理中结果的间隔写死。任务重试在主库最早查询时点和 30秒/1分/2分/5分/15分/30分/60分渐增间隔中取较晚者。提交任务只有在最终业务结果或唯一 `REFUND_CHANNEL_QUERY:{refundId}:0` 查询任务已经持久化后才完成。查询任务首次 executeAt 固定保存，首发任务重放不改变它；查询命令键为 `TASK:REFUND_CHANNEL_QUERY:{refundId}:0`。

## 成功、投影与对账

REFUND 再次读取 PAYMENT 已验签持久结果，核原退款号/支付/币种/全额/成功时间后，同事务写 SUCCESS、退款流水、唯一成功 eventId 和 `RefundSucceededEvent.v1`。未知保持退款中；错误金额、来源冲突或不能验证成功均不能发成功事件。渠道请求号保留原 refundNo，渠道返回退款号单独保存在 refund_order。

ORDER Created/Success 消费核 REFUND 当前权威事实及原 eventId/时间，消费日志、`order_late_refund_result` 和 `pet_order.refund_order_id/refunded_amount`、审计同事务。SUCCESS 先于 Created 也不得倒退；原 CANCELED/PAYMENT_TIMEOUT 与 SCH EXPIRED 保持。此为内部退款进度投影，未交付公开订单展示 HTTP 或通知/券/积分消费者。

任务重试耗尽或取消，由 REFUND 对自己的未完成单循环扫描，通过 task-core 的只读状态接口识别 DEAD/CANCELED，写唯一 `LATE_PAYMENT_AUTO_REFUND_FAILED` 对账项并在提交后产生日志告警；不换退款单，不恢复订单，不把未知改成已退款。对账扫描默认随退款 worker 关闭，启用时每 60 秒运行，每批 100 条并保存进程内游标，重启可从头幂等扫描。已验签成功随后到达会解决该项。管理端对账页面、外部告警路由及人工修复入口不在本切片内。

## 渠道与运行边界

官方旧聚合扫码退款 [1073](https://o.lakala.com/open/document/1073) 指向统一退款 [1826](https://o.lakala.com/open/document/1826)，查询 [1827](https://o.lakala.com/open/document/1827)，公共信封 [1828](https://o.lakala.com/open/document/1828)，签名 [1004](https://o.lakala.com/open/document/1004)。固定 V3 HTTPS 地址、原字节 RSA 签验、无重定向、64KiB 上限、完整响应超时，禁止应用层和 JDK 隐式提交重试。`code=000000` 仅通讯成功，真实 SUCCESS 还须金额、原支付双号、退款流水、实退金额和渠道时间匹配。查询响应未含 merchant_no，依赖签名与服务端商户请求及原交易双号一致性；正式联调必须再核商户归属。

`pet.refund.late.enabled` 与 `pet.payment.foundation.enabled` 均打开才装配；worker 另需 `pet.refund.late.worker.enabled`，均默认关闭。缺正式凭证、商户配置或明确渠道时区失败关闭。不开放退款回调 HTTP，本轮通过签名同步应答和主动查询闭环；正式原路退款、同号重提幂等和完整渠道验收仍待商户参数与联调。
