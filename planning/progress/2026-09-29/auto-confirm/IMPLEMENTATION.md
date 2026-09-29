# A+B 首轮自动接单内部实现与验收

日期：2026-09-29。分支：codex/auto-confirm-20260928；PR：[91](https://github.com/blueXYing/pet-platform/pull/91)，目标 develop。用户已批准 A 推荐方案与 B 剩余实现/测试/提交 PR，明确不合并、不生产启用。

## 已实现

- A：SYSTEM 首轮确认、同门店 guard 下 ORDER/PAYMENT/SCH/REFUND 当前事实核验、CAS 更新 PENDING_SERVICE/AUTO/confirmed_at、成功证明及 OrderConfirmedEvent.v1 Outbox 同事务；重复与提交 ACK 丢失恢复。
- REFUND Owner 提供全部来源退款单存在性查询；不依赖迟到退款执行绑定，FAILED/UNKNOWN 仍阻断。仅存在申请/售后引用时可重试并持久登记 APPLICATION_FACTS_UNAVAILABLE。
- B：沿用正常付款原子任务生产，接入严格任务绑定解码、Worker、缺失任务原截止补建和异常终态恢复。READY/RETRY_WAIT/RUNNING 不重置；DEAD/CANCELED/SUCCEEDED 保留原任务及 attempts，到期且资格仍成立才执行同一确认事务。
- 每60秒有界 ORDER 游标扫描，每单独立事务；worker-only 只登记终态异常，repair=true 才补建/恢复。只读 inspection 继续独立。
- 诊断复用 ORDER order_status_log，无新 DDL。契约07/08/09/14/15与新44号同步；confirm_mode 使用既有Schema字段。

## 本地验证

Java 21 / Maven / 私有 MySQL 8.4（127.0.0.1:23391，测试随机数据库，已签名离线回执，无渠道网络）。

| 验证 | 实际结果 |
|---|---|
| AutoConfirmExecutionAcceptanceTest 首次完整复跑 | 20 tests，0 failures/errors/skipped；196.1秒 |
| OrderAutoConfirmTaskConfigurationTest | 5 tests，0 failures/errors/skipped |
| 成功证明严格校验收紧及新增 Worker 崩溃/租约重领后定向复跑 | 3 tests，0 failures/errors/skipped（2项重跑+1项新增） |
| AutoConfirmTaskPreparationAcceptanceTest | 9 tests，0 failures/errors/skipped |
| PaymentFoundationAcceptanceTest | 10 tests，0 failures/errors/skipped |
| 架构源门禁测试 | 18 tests，全部通过 |
| 离线契约回归 | 115 tests，全部通过 |
| contract_smoke | PASS_OFFLINE_DOCUMENT_SMOKE；90 operations、57 writesWithRequestId |
| 模块依赖 / DisplayOrderStatus / MyBatis SQL / git diff --check | 全部通过 |

最终新增执行套件有21个测试方法；上述四组Java套件共45个不同测试，不把3项定向重跑重复计数。首轮有两项并发夹具使用默认事务隔离，被 guard 拒绝；已修正为 READ_COMMITTED，20项完整复跑通过。最新提交完整 CI 及其后端计数以 PR91 检查页和 PR 描述为准，旧627/614基线数字不用于本轮验收。

本地日志位于本机临时目录 .codex/tmp/auto-confirm-20260928：a-test.log（包含首次失败与B/支付通过）、a-test-final.log（20+5通过）、a-proof-test.log（最后3项通过）。CI 会上传 backend-test-reports，包含最终全部测试，而不是依赖本地选择器报告。

## 最低验收映射

| CCR | 证据范围 |
|---|---|
| AC-AUTO-01 | B任务唯一/原截止/消费重放、任务写失败全回滚、冲突不可覆盖 |
| AC-AUTO-02 | 迟到支付回归无任务；取消/已推进/旧轮次不确认 |
| AC-AUTO-03 | 原渠道30分钟、未来NOT_DUE重新安排、跨时区等价、B延迟跨午夜回执 |
| AC-AUTO-04 | 并发业务命令一次日志/事件；确认commit ACK丢失重放；真实Worker在业务commit后崩溃、过期租约重领仍只有一次确认 |
| AC-AUTO-05 | 退款先提交/回滚与确认竞争；确认先提交后仍可创建服务前退款夹具；无执行绑定、FAILED/UNKNOWN阻断；孤立ORDER退款指针阻断 |
| AC-AUTO-06 | 当前支付对账/预约失效/付款金额漂移/申请未知/退款查询失败/任务owner伪造/损坏成功证明均拒绝推进 |
| AC-AUTO-07 | 缺任务原截止补建；活跃状态与可变deadline/retry不重置；绑定冲突；三个终态恢复保留任务及attempt；未来终态只登记并重访 |
| AC-AUTO-08 | 当前轮次1使旧0失效；命令round1拒绝；同轮错误deadline冲突 |
| AC-AUTO-09 | 默认无命令/Worker/修复；缺主开关或支付基础拒绝；显式repair只装配命令和扫描；架构及离线契约通过，最终CI见PR |

## 风险与交接

- 所有运行开关默认 false，未生产迁移、未真实交易、未合并。
- 商家确认/拒单、普通退款业务入口、一次改期round1/旧任务取消、公开HTTP/小程序及通知消费者仍未交付。并发测试使用受同guard保护的退款夹具，不冒称普通退款入口完成；CONF-001/003/004/006完整流程未标通过。ORD-002对ORD-001依赖保留。
- 无退款单但存在申请/售后引用时暂缓，需后续Owner权威资格接口；不得把这解释为永久禁止核销。
- 所有后续退款创建者须共享同门店guard直到commit；此协议是当前空退款查询安全性的前提。
- 持久诊断有代码、订单和轮次可查；外部告警路由未配置。数据库整体不可用时诊断写入也会失败，此时不能声称告警已送达。
- 完整CI验证后提交PR供审阅；合并与生产启用继续保持未授权。
