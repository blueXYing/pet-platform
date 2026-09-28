# 无正式渠道参数的支付基础 v0.1

状态：开发中、内部组件默认关闭。2026-09-28，用户授权在尚无拉卡拉商户参数时继续推进。依据 SSOT、07/08/09、23 号及 36～39 号。此切片不改变产品支付规则，不开放消费者发起支付，不执行生产迁移、渠道扣款或退款。

## 1. 阶段边界

`PaymentPreparationApi.prepare` 是独立内部持久意图，返回 paymentId/paymentNo/订单金额/原截止值，不返回微信支付参数，也不是原 `createPayment` 的成功响应。原同步创建支付仍须实际取得微信支付参数并完成短期参数的幂等/失效方案审查，不能替换为 202 或“受理成功”。没有商户绑定配置时 prepare 失败关闭，不内置官方公共测试商户作为生产默认值。

官方协议适配先实现纯构包、签名、验签与解析，不发送网络请求。入口按真实协议接收原始签名字节；测试用例在内存生成 RSA 测试密钥，不下载、复制或提交官方测试私钥。生产和测试参数仍由部署环境配置。

## 2. 官方依据与限制

- [1004 安全统一接入](https://o.lakala.com/open/document/1004)：请求/普通响应使用 appid、证书序号、时间戳、随机串、原始 body 五行及末尾换行，SHA256withRSA。
- [1009 安全统一通知](https://o.lakala.com/open/document/1009)：异步通知采用独立的三行签名规则，不能按普通响应补空 appid 猜测验签。
- [1075 聚合主扫](https://o.lakala.com/open/document/1075)：微信小程序 WECHAT/71，子 AppID、用户标识及商户/终端字段；金额单位为分。预支付有效期不等于关单。
- [1111 交易查询](https://o.lakala.com/open/document/1111)、[1071 关单](https://o.lakala.com/open/document/1071)、[1112 交易通知](https://o.lakala.com/open/document/1112)：接口调用成功不等于支付成功，重复通知必须去重，未收到通知需查单。
- [1005 测试参数](https://o.lakala.com/open/document/1005)：仅测试联调用，生产参数须另行申请；1075 明确微信测试环境存在商户与 AppID 不匹配限制，不能据此宣称完整微信支付成功。

1112 的 `trade_time` 仅给出十四位格式，未明确时区。本切片按平台业务时区 Asia/Shanghai 解析渠道本地时间，再转 UTC 存储；正式联调前必须向渠道核实。缺失有效实付金额或支付时间时保留未知并拒绝产生成功事件，不用订单原价冒充实付。当前仅实现已批准人民币微信支付范围；渠道营销导致实付与订单应付不同，正常履约须另核实金额映射，不擅自视为足额付款。

## 3. 支付意图与当前事实

内部 prepare 接受 USER 的可信 CommandContext 和 orderId。先检查当前用户，再在独立短事务持久化二进制 requestKey 与原 orderId/userId 绑定；后续资格或配置失败也不允许同键换订单。执行事务为同库 READ_COMMITTED：锁请求绑定 → 同门店 guard → USER 当前资格 → ORDER 公共当前事实 → 服务端商户绑定 → PAYMENT 行。每订单仅一条 payment_order，原十分钟截止不延长，不重开已关闭订单。

商户/门店归属、金额和截止时间来自 ORDER；merchant_no/term_no/sub_appid 来自服务端配置。消费者请求不能指定收款对象、金额、最终支付状态或密钥。成功意图重放重新核对当前用户与订单归属，不获取或重放过期微信参数。

SQL40 新增字段对历史行保持 NULL，不猜测或回填既有商户、币种、支付身份；缺少绑定的历史记录按未知处理。SQL40 的 cancel_reason 由原到期关闭路径写 PAYMENT_TIMEOUT，已核准的状态日志仍保留。

## 4. 已验签结果与事件

原始通知仅先解析 out_trade_no 作本域查找提示；验签前不能推进状态。校验签名、商户、原付款单、钱包类型及订单金额后，PAYMENT 在同 store guard 下锁自己的当前记录，记录最小回执摘要和必要支付事实。原始包、openid、账号和签名密钥不进入事件或日志。

明确 SUCCESS 且有有效实付/支付时间：PAYMENT PAID、唯一渠道流水、实付金额、paidAt、唯一 successEventId 和 `PaymentSucceededEvent.v1` 同事务提交。重复结果与旧事实相同仅重放；变化的渠道流水、金额或时间不能覆盖成功事实。迟来的处理中/失败通知不能将已成功支付降级。其他渠道状态可记录，但不能产生成功事件或成为释放占用的许可。

`PaymentSuccessFactsApi.requireSucceeded` 由 ORDER 在同 DataSource/guard 事务调用，锁 PAYMENT 权威行并验证对应成功回执，返回原 eventId 与绑定。任意 SYSTEM DTO 或伪造事件本身均不是支付成功证据。

## 5. ORDER 消费与 SCH 确认

ORDER 消费 PaymentSucceededEvent 时严格核对 envelope、payload、权威支付事实和 sourceEventId。消费日志与业务变化在同一事务。正常待支付订单的应付金额与权威实付相符后，ORDER 进入 PENDING_CONFIRM/PAID，paidAt 使用渠道事实，confirmDeadline = paidAt + 30 分钟；同时 SCH 将仍完整的 TEMP_LOCKED 预约变为 CONFIRMED，保留原 claim 并写 SYSTEM 审计。已过十分钟但未实际关闭的合法占位不因时钟值而直接失效。

SCH 先读取活跃占位完整性，提交前通过 ORDER 公共 API 验证本事务的支付提交证明。历史 PAID 记录不能使独立 confirm 提交。ORDER 同事务产生 OrderPaidEvent.v1；当前无券路径 couponInstanceId=null。正式三十分钟自动接单、通知和券消费处理器仍是后续切片，不把已写截止值或事件当作这些消费者已交付。

PAYMENT_TIMEOUT 已关闭订单：保持 CANCELED 与 SCH EXPIRED，仅将支付维度记为 PAID、记录渠道真实金额时间，并同事务投递一次 LatePaymentSucceededAfterTimeoutEvent.v1。不得恢复订单/占位、重新冻结券或发正常 OrderPaid。当前仅有退款意图事件，尚未创建并执行真实 refund_order/渠道退款；不能声称自动退款已完成。

订单支付结果表以 orderId/paymentId/sourceEventId 唯一保存结果。重复事件、ACK 丢失后的重放不能再次发布成功或迟到退款意图。旧到期任务遇到已验证的 LATE 结果可 NOOP；没有对应事实的 CANCELED/PAID 仍视为异常。

## 6. 未开放的能力

所有新增运行开关默认 false。发起支付 HTTP、外部回调 HTTP 暂不开放；本轮验收针对内部原始通知入口和真实本地 Outbox 消费。现有到期保护仍拒绝任何已存在 PAYMENT 记录，直到后续完成渠道查单/关单的权威协调，不把 PREPARED/INIT 自动当可释放。此保守阶段限制是尚未上线的实现边界，不是新的产品时限。

完整参数、真实小程序关联、渠道时区核实、实际扣款/查单/关单/退款与新消费者交付之后，才可完成真实支付验收。分账、提现等 OD-W0-001 未决范围不在本切片内。新 PR 合并和生产启用另需用户批准。
