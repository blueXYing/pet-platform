# 支付协调内部运行说明

依赖 PR88 的 SQL40；SQL41 只在新建隔离测试库执行。正式迁移、启用、PR 合并和渠道交易未获授权。

## 开关与参数

- 既有真实 ID、ORDER 创建、排期保护、到期与 Outbox 基础必须已配置。
- `pet.payment.foundation.enabled` 和 `pet.payment.dispatch.enabled` 均为 true 才装配新服务；缺省不装配。公开小程序支付/通知 HTTP 均未开放。
- 沿用 `pet.payment.lakala.stores` 的服务端商户绑定与显式 `channel-time-zone`，时区必须由渠道核实。
- 新 `pet.payment.dispatch` 配置要求：`environment` 明确 SIT 或 PRODUCTION、`app-id`、`merchant-serial`、`merchant-private-key-path`（RSA-2048 PKCS8 PEM）、`platform-serial`、`platform-certificate-path`（X.509 平台验签证书）、`parameter-key-path`（文件内为 32 字节 AES 密钥的 Base64）、`out-org-code`、`subject`、`request-ip`、`notify-url`。
- 启动 JVM 必须提供 `-Djdk.httpclient.disableRetryConnect=true`，并禁止全方法自动重试；运行中设置同名系统属性不能解除启动门禁。该前提仍不等于渠道恰好执行一次。
- `terminal-close-capability` 默认 false。在真实渠道最终关闭语义核实前保持 false；测试替身显式 true 只用于验证内部流程。

配置文件不得提交商户私钥或 AES 密钥。不得直接覆盖 AES 密钥进行轮换：现有参数解密和身份等值核验依赖原密钥，需另行设计版本兼容与迁移。短期参数不能因无法解密而重新预下单。

## 内部调用与恢复

`PaymentInitiationApi.create` 只接受后端构造的可信 USER context 与订单号。先持久绑定 requestId，再创建原付款意图、持久发送界线、出事务请求渠道。成功返回有效微信参数；付款状态未知、参数过期/丢失或请求结果不确定时查原号并失败关闭，不返回假参数，不新造付款单。

ORDER 原到期任务可以调用已装配的支付协调器。先核对原预约/订单/期限，再出事务原号查单、关单与复查，最终同事务重读 PAYMENT 安全证据后关闭 ORDER/SCH。协调返回 READY 类提示也不能绕过最终当前读证明。

FENCED_UNSENT 是本系统从未派发且已永久阻断该号发起的证明。MAY_HAVE_SENT、UNKNOWN 和 CLOSE_MAY_HAVE_SENT 不因时间或租约到期自动回到未发送。CLOSE_ACKED 不代表最终关闭。历史付款没有派发记录时不自动补造事实。

需人工核验时记录付款编号、订单编号、原交易请求日期、派发状态及已验签回执摘要；不要复制 openid、支付参数、签名头、密钥或完整渠道包。任务进入 DEAD 后沿既有任务对账流程处理，不自动重置 generation 或改 requestId。

PAYMENT 查询/通知成功只投递一次成功事件，ORDER 消费完成才改变订单展示状态。迟到支付仍保留 CANCELED/EXPIRED；本切片只衔接既有迟到退款意图，退款执行及商家三十分钟自动接单仍待后续交付。

## 验证边界

测试使用本机临时 HTTP 服务、临时 RSA/AES 密钥和随机 MySQL schema。网络层覆盖响应正文卡住、超大响应、签名篡改/重复头及重定向；业务验收覆盖持久状态、当前权限、并发与故障恢复。

没有向拉卡拉 SIT 或生产发送交易。正式商户、小程序关联、通知可达性、渠道时间和实际营销实付金额仍需后续真实联调。消费端支付可用性不能从这些内部测试直接推定。
