# 迟到支付退款内部执行交付

基线：develop `74f480f4f25a4194e5f02d8bec7158b2b333e6bc`；分支 `codex/late-refund-20260928`。用户已明确批准 CCR A/B；多角色 GPT-6 Sol / xhigh 完成渠道协议、PAYMENT、ORDER、独立 QA 和集成审查。范围不含真实退款、生产迁移、生产启用和 PR 合并。

## 实现

- REFUND 在门店事务锁内核验 ORDER 迟到付款和 PAYMENT 真实付款事实，按渠道实际支付金额建立全额原路退款单、不可变原支付绑定、消费记录、Outbox 和 durable task，同成同败。
- PAYMENT 在网络请求前提交 MAY_HAVE_SENT 留痕；同一退款重放和未知结果只查原退款号。仅验签且持久化的匹配回执可以推动业务退款成功；成功流水、唯一事件与退款状态同事务提交。
- ORDER 消费退款创建/成功事件并核权威事实，乱序与重放不倒退；订单仍 CANCELED/PAYMENT_TIMEOUT，预约仍 EXPIRED。
- 查询至少等待 30 秒并逐步退避；重试耗尽或任务取消写入幂等对账问题及 ERROR 日志。UNKNOWN 不擅自改成失败，也不自动重新发起退款。
- 正式接口、SQL42/43/44、任务及事件约束见 [42号契约](../../../../docs/04-api/42-Late-Payment-Refund-Contract-v0.1.md)。

## 验证

- 最终集成代码 `64c26ef`：Java21 Maven 指定回归共 **50 项，失败/错误/跳过均为 0**。其中真实隔离 MySQL 端到端 16、PAYMENT MySQL 2、退款协议/传输 6、装配开关 4、Java 架构 22。
- 独立 ORDER MySQL 回归 18 项通过；渠道模块新旧拉卡拉测试共 21 项通过。上述为分角色证据，不与最终50项重复累加。
- 本地最终日志：`late-refund-final-local.log`（工作区之外的交付机器 Desktop）；完整仓库验证以 PR CI 保留的 Surefire 报告为准。
- MySQL 使用任务专用 127.0.0.1:33471 和随机测试库；协议使用临时密钥、本地 HTTP 测试服务器或可信替身。未连接真实退款渠道。

## 开关与运行边界

`pet.refund.late.enabled` 和 `pet.payment.foundation.enabled` 均显式开启才装配；worker 另需 `pet.refund.late.worker.enabled`。默认关闭。开启时复用支付渠道 appId、商户/终端、PKCS8 私钥、平台证书、证书序列号、请求 IP、通知地址及明确时区；缺配置应启动失败。传输使用已有 JVM 禁止自动重发的启动约束。当前无正式拉卡拉参数，不启用真实环境。

出现 UNKNOWN/DEAD/CANCELED 时，按持久化原退款号核对渠道，保留所有绑定、dispatch 和回执。回滚先停 worker 与新发送，保留事实；不可删除留痕后重发，不可把 MAY_HAVE_SENT 人工重置为未发起。

## 未交付范围

没有真实 SIT/生产联调、退款回调 HTTP、公开订单/退款展示、券积分通知消费者或对账管理 UI/外部告警路由。成功来源限签名同步回执或查询。日志与对账记录不等于已接入外部告警。

三十分钟自动接单仅完成 [后续范围核对](AUTO_ACCEPT_NEXT.md)，尚未实现；其内部命令、事件和退款并发契约仍需单独冻结。
