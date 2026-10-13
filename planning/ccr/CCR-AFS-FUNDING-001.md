# CCR-AFS-FUNDING-001 — 售后退款裁决公开执行（32号裁决落定 OD-W0-001）

状态：APPROVED_BY_RULING / IMPLEMENTED_DEFAULT_OFF。2026-10-13。Owner：AFS + REFUND Contract Owner。产品授权来自 [32号裁决](../../docs/01-prd/32-售后资金出路与未建页面裁决补充-v1.0.md)（裁决人：产品负责人，2026-10-13 已合入 develop）；本 CCR 只登记由此产生的技术契约增量，不新增产品规则。

## 背景与授权边界

Contract51 v0.1 §3 将 FULL_REFUND/PARTIAL_REFUND 固定为"已知但关闭"：合法请求固定 503，绝不调用内部 decide/REFUND；31号裁决同时声明缺 OD-W0-001 资金权威基线时退款型终裁 fail-closed。32号裁决落定 OD-W0-001：V1 售后裁决退款（全额/部分）一律走**微信支付原路退回**，不做冻结/分账/提现/保证金；并明确"admin 售后 decisions 的 FULL_REFUND/PARTIAL_REFUND 分支解除 fail-closed，退款执行按原路退回语义接入既有退款渠道执行链（submit / channel-query 任务族）"。执行约束不变：requestId 幂等、渠道回执确认、错误码登记；DisplayOrderStatus 仍由 order 统一计算。

## 已冻结的技术决定（依裁决实施，无需二次产品批准）

1. **HTTP 公开面**：`POST /admin/aftersales/{afterSaleId}/decisions` 五类 decisionType 全部可执行；控制器不再自带 503 短路。`pet.aftersale.refund.enabled`（默认 false）继续作为退款资金权威开关：关闭时 FULL/PARTIAL 进入内核后被资金资格适配拒绝，仍为 503 COMMON_DEPENDENCY_UNAVAILABLE，且不产生决定/退款单/ORDER 投影。REJECT/RESERVICE/OTHER 的 refundAmount=null 约束不变。
2. **refundAmount 语义（内核既有，公开面首次可观察）**：金额为精确两位小数字符串；FULL_REFUND 必须等于订单真实实付金额，PARTIAL_REFUND 必须大于 0 且严格小于实付；违例返回 400 `AFTERSALE_AMOUNT_INVALID`（Error12 既有码，无新码）。实付金额以 PAYMENT 权威成功事实为准，不以请求方自报为准。
3. **响应投影**：成功回执沿用 Contract51 固定十字段 Receipt；退款型决定回执的 `refundOrderId` 由 null 变为已创建退款单 ID，`status` 为工单终局 RESOLVED。退款执行（渠道提交/查询）由既有 AFTERSALE_REFUND_SUBMIT / AFTERSALE_REFUND_CHANNEL_QUERY durable 任务族异步完成，不在决定事务内做任何网络 I/O；决定事务内原子完成：终局决定 + ORDER 绑定 + refund_order 创建（source_type=AFTERSALE_DECISION）+ 首个提交任务入队。一笔订单最多一次退款、部分退款后不得再发起新售后（资格面 REFUND_ORDER_ALREADY_EXISTS）由既有内核保证，本批不改变。
4. **生产资金资格适配（原路退回）**：`WechatOriginalRouteRefundFundingAuthority`（pet-payment-biz）实现既有 `RefundFundingEligibilityFactsApi`：仅在调用方事务内读取 PAYMENT 权威成功事实并与 FundingCheck 全字段比对（支付号/成功事件/渠道交易号/实付金额/币种/主体），全部一致即 ALLOWED、UNSETTLED（V1 无分账/结算生命周期）、授权金额=请求金额；任何不一致为 503 COMMON_DEPENDENCY_UNAVAILABLE。证据 evidenceId=`WECHAT_ORIGINAL_ROUTE:<check-hash>`，FIRST_SEND 只扩展决定阶段提交的同源证据；证据有效窗口 24 小时（覆盖任务族重试节奏的技术护栏，非产品规则），窗口内每次首次提交前仍以当前 DB 时间复验。装配在 LateRefundConfiguration，`pet.aftersale.refund.enabled=true` 时生效；未开启时无该 Bean，AfterSaleWorkflowConfiguration 维持 fail-closed（启动校验不变）。
5. **无变化项**：无 Schema/迁移、无新事件、无新错误码、无新路由；DisplayOrderStatus 投影、券/积分/评价消费者、C/M 端 HTTP 面均不在本批。运营权限（aftersale.decide）、一次终裁、重复裁决 409 `AFTERSALE_DECISION_FINAL` 均不变。

## 验收口径

HTTP 验收（真实会话）：FULL/PARTIAL 成功路径（回执 refundOrderId、refund_order/AFTERSALE_REFUND_SUBMIT 任务、无渠道调用）、金额边界（FULL≠实付、PARTIAL≥实付、0.00 均 400 AFTERSALE_AMOUNT_INVALID）、同 UUID 重放 200 同回执/换参 409、终局后再次裁决 409、开关关闭 503 且无资金副作用、退款单存在后资格面 eligible=false(REFUND_ORDER_ALREADY_EXISTS)；生产资格适配单测（绑定、金额界、支付事实漂移拒绝、FIRST_SEND 同源扩展、24h 窗口）。既有内核资金链测试（AfterSaleMoneyAcceptanceTest/SourceIntegrity）保持通过。
