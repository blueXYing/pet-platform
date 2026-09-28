# 支付派发与到期协调独立审查

状态：2026-09-28，增量只读复审 wt-payment-coordination HEAD 11fa822（含 75d9c99 的未知提交恢复与 c407b6d 的终局证明收紧）；并对照 ORDER 到期入口、PAYMENT 最终证明、通知写入器、渠道适配器及 SQL41。除本记录外未改生产文件。本记录不代表生产启用批准。

## 当前代码结论

未发现新的当前范围释放竞态。首次预下单前在同店事务中持久化原 paymentNo、原请求时间及查单日期、MAY_HAVE_SENT；提交后才联网。网络异常或提交结果未知不会重发原预下单。预下单结果入库提交回执丢失时，只重读原状态并尝试恢复参数或原号查询，不再次发预下单。到期先核原预约和 deadline，再在短事务内 fence；原预下单仍可能在途或响应未知时保留占用。查询的验签结果由 PAYMENT 通知写入器形成收据及成功事实；关单 ACK 只作收据，不直接释放。最终关闭要求预下单签验摘要、已持久的关单 ACK、本次关单后签验的 CLOSE 查询及其匹配收据、明确开启的渠道终局能力。c407b6d 又要求 PAYMENT 为 CLOSED/OBSERVED、无已付字段或不利收据，并核对关单收据存在；ORDER 最终事务还会重读支付及收据并核对来源域分离摘要、金额和交易号。并发 SUCCESS 在同店锁下与这些复核串行，不能被旧 CLOSE 收据掩盖。

复审中报告的三处问题已经修复：PARAMETERS_READY 重放先排除 PAYMENT 已付事实；终局确认使用本次 post-close CLOSE 查询摘要，避免旧 CLOSE 收据与新非 CLOSE 结果混用；支付通知先到时仍保存已签验的预下单响应摘要。参数本地有效期现取原 preorder_req_time + timeout_express、持久 may_have_sent_at + timeout_express 和 ORDER deadline 三者中最早的时刻；过期或不可解密参数只走原号查询，不能再次派发。

渠道适配器在发包前拒绝活动事务，并在装配前核对 JVM 启动参数 jdk.httpclient.disableRetryConnect=true，同时拒绝启用全方法重试。11fa822 增加明确 timeout_express 入包映射和参数字符串脱敏测试。传输层 QA 604a594 已通过 9 项离线及真实 loopback JDK 路径测试。

主线程最终补记：独立 QA 提交 1433706 的协调层 12 项真实 MySQL 测试全部通过，已包含真实 commit 成功后抛 SQLException 模拟 ACK 丢失，验证恢复原参数且渠道预下单仍只有一次；旧基础 10 项通过。主线程协议/传输/配置/Java 架构及契约检查通过，详细分项见 IMPLEMENTATION.md。代码审阅者未独立复跑上述测试；远程全量结果以最终 PR head 的 CI 为准。

## 接入门禁与保守结果

当前装配为 opt-in。内部 MySQL 协调及 ORDER/SCH 释放链路已有离线验收；正式启用仍需部署迁移验证和运营配置所依据的渠道“关闭后终局不再支付”能力证据。该能力未证实时 terminalCloseCapability 保持关闭；未知提交、未知关单结果或旧派发仍可能在途时保持 HOLD，不从 lease 到期、查不到、关单 ACK 或本地参数过期推断可释放。迟到 SUCCESS 必须继续走 PAYMENT 事实及原路退款规则。

依据：[40 号支付基础契约](../../../../docs/04-api/40-Payment-Foundation-Contract-v0.1.md)、[23 号公共幂等契约](../../../../docs/04-api/23-公共接口与幂等契约补充-v0.1.md)、[已批准协调方案](../../../../planning/ccr/CCR-W2-API-001/payment-dispatch-expiry-proposal.md)。
