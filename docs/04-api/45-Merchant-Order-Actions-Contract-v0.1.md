> 2026-09-29: [Contract 46](46-Order-Reschedule-Contract-v0.1.md) extends expectedConfirmRound and immutable decisions to 0/1. Use paidAt+30min for round 0, rescheduledAt+30min for round 1; request binding and refund origin include the selected round. No production enablement.

# 商家首轮确认、拒单及退款契约 v0.1

2026-09-29 用户批准[CCR D1/D2/D3](../../planning/ccr/CCR-W2-API-001/merchant-order-actions-proposal.md)。该回执§2～6的字段、事务、权限、退款来源及持久化定义在本版本冻结；首切片主账号、round0，开关默认关闭，不合并或生产启用。员工权限和完整ORD-001没有因此完成。

## 截止与幂等

首轮截止=渠道paidAt+30min。取得同店guard并读取当前事实后，以DB UTC毫秒时间判定，严格小于截止才接受新的商家决定；等于/超过即409 ORDER_CONFIRM_DEADLINE_PASSED，自动任务仍用原截止。覆盖PRD网络先到的歧义；截止前点击但排队越界亦拒绝。已经确认则普通拒单409 ORDER_STATE_NOT_ALLOWED。

UUID X-Request-Id沿23号。ORDER独立占号事务提交后，业务短事务依次锁幂等行、共同门店guard、当前权限与订单/付款/预约/退款事实。失败保留绑定；成功回执与业务同提交。成功回放返回第一次回执并重验当前权限，不重新执行或受截止约束。

## HTTP与来源

沿既有POST /api/v1/merchant/orders/{orderId}/confirm和/reject。expectedConfirmRound仅整数0。confirm可省略internalNote，存在须非null字符串、0～200码点；省略和空串幂等不同。reject必填reasonCode、5～200码点且非全空白的reasonText。分类为SCHEDULE_CONFLICT/STAFF_UNAVAILABLE/PET_NOT_MATCHED/TEMPORARY_CLOSURE/OTHER，分别对应PRD五类。未知/重复字段拒绝；文本不修剪改写，敏感词审核依赖缺失不放行。

成功回执orderId/decisionId/confirmRound/action/orderStageAtCommit/decidedAt/refundOrderId，ID为String，confirm退款ID为null。内部备注不进入C端或事件，拒单原因经授权读侧查询。主账号来自真实会话，执行/回放再验会话与当前owner关系；OFFLINE已有履约允许，FROZEN未决写入保持未接通。

confirm产生MERCHANT确认和既有七字段OrderConfirmedEvent.v1。reject同事务取消订单、记录不可变决定、创建全额退款及执行绑定/任务、设置ORDER退款指针、写日志/Outbox和回执。cancel_reason=MERCHANT_REJECT_ORDER，预约暂保持CONFIRMED。退款显示优先于关闭状态；退款SUCCESS验证后，ORDER进度及SCH RELEASED投影原子提交。

MERCHANT_REJECT_ORDER独立来源证明由ORDER公共事实API提供；不能用迟到事件伪造。RefundExecutionFact追加sourceType/sourceEventId，旧构造器仍代表原LATE来源；迟到旧事件载荷保持兼容。退款Created/Success已有source/refundSource字段区分来源，普通来源通过不可变execution读取sourceEventId，不在旧事件中新增破坏严格解码的字段。所有消费者先验证载荷及来源，再验证本Owner所需权威事实；已知其他来源安全不消费，未知来源失败。

## Schema与装配

DDL见[45号Schema](../03-database/45-Merchant-Order-Actions-Schema-v0.1.sql)。两个ORDER表保存二进制唯一幂等键、加密输入/回执和加密决定文本。REFUND新增来源字段并回填历史；兼容阶段仅允许可证明旧迟到行的两个新字段同时NULL，普通来源必须明确完整；不能用NULL来源绕过校验。生产迁移和收紧约束独立安排，本次只隔离库验证。

所有SQL归属本Owner MyBatis XML，无biz→biz。pet.order.merchant.enabled控制命令、来源事实与成功投影，pet.order.merchant.http.enabled控制HTTP，pet.order.merchant.worker.enabled控制普通来源退款Worker和恢复扫描，三者默认false。HTTP要求主开关、退款Worker、payment.foundation及auto-confirm生产/Worker齐备；缺少必要依赖拒绝装配。没有默认敏感词放行Provider。AES-256-GCM保护配置pet.order.merchant.protection-key须显式提供Base64的32字节密钥，或提供可信Protection实现；密钥轮换和历史解密需在生产启用前安排，不使用测试密钥。

普通来源任务使用MERCHANT_REFUND_SUBMIT、MERCHANT_REFUND_CHANNEL_QUERY；原迟到来源仍使用REFUND_SUBMIT、REFUND_CHANNEL_QUERY，Worker不能越类型领取。共用PAYMENT原refundNo、MAY_HAVE_SENT/UNKNOWN查询流程和渠道请求幂等键；仅任务类型/键按来源隔离。启用普通退款不默认开启迟到消费者/Worker。实际验收及遗留依赖在实现记录逐项列出，不以契约通过代替业务测试。

内部公共入口为MerchantOrderCommandApi.decide(Command)，action仅CONFIRM/REJECT（对应两条HTTP路由），不新增第三个公开动作。MerchantOrderAuthorityApi.requireOwner只验证当前真实USER主账号；OrderMerchantRejectFactsApi.requireRejected提供不可变正常付款/拒单/退款绑定。MerchantRejectRefundApi.create不接受金额，在调用方同一guard事务内重验并按实付全额建单。ReservationRefundReleaseApi.release在同一事务内验证REFUND最终成功及原预约归属，CONFIRMED→RELEASED并记唯一审计，重复调用验证既有释放证明。
