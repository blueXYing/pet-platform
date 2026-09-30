# K1/K2 实施与验收

批准：用户回复“批准”，包含 M94 合并及 K1/K2 方案/计划。PR94 merge `eb083cb`，合并后 CI36654304138 六项成功。新 PR 不合并，所有运行开关默认关闭。

已实现：真实 OWNER 会话/MER 归属适配；verification.complete 五元组加密 Admission；共享码/风险内核；同 DataSource、可写 RC 与共享门店 guard 下的 VERIFY 事务令牌；成功记录/凭证消耗、ORDER 完成与唯一 v2 Outbox、最小 AFS 失效及各域提交证明。SQL48/Contract48/SSOT §37 已同步。

## 已观察的验证

- K1 RED：真实 Owner 适配装配测试因缺 AttemptAuthority 失败；接通真实组合后 6/6 通过。
- K2 RED：成功核销测试明确因 Completion not implemented 断言失败；实现后首轮 8 项数据库验收和 6 项配置测试通过。
- 扩展验收：15 项真实 MySQL 核销测试 + 8 项配置测试，0 失败/错误/跳过。涵盖 12 个持久化点故障、同请求并发、提交成功后丢响应、风险计数共享、当前售后丢失/反向孤儿/归属错配/未知状态/错误来源、历史工单保留、真假事务令牌、真实身份解析与撤权、自动确认及改期接送。
- Python 架构源代码测试 18/18；契约回归 118/118。模块依赖、MyBatis XML、DisplayOrderStatus 归属检查通过。
- 三项迁移/不同请求竞争/事务边界通过；ArchUnit 22/22。另补非法门店ID测试，观察到误报 DEPENDENCY 的 RED，改为 INVALID_ARGUMENT 后 GREEN。合计覆盖 19 个不同核销验收方法、8 个配置测试、22 个 ArchUnit。全量远端 CI（含既有码生命周期及退款回归）尚待执行；不以旧 PR 报告代替本批结果。

## 覆盖边界

数据库夹具是真实隔离 MySQL（本机 3314），不是生产迁移。AFS 工单由测试直接准备，验证的是既有工单的当前事实/失效内核，不是尚未实现的售后申请/裁决端到端。

身份专项使用真实 UserAuthService、账户表、MER 归属和生产 bean 工厂；仅易失缓存是明确测试替身，不伪装真实 Redis 登录。既有 CAuthHttpTest 在全量 CI 使用真实 Redis/HTTP；微信上游仍为固定凭据测试适配。退款行拒绝专项只证明当前事实保护，完整 QA-004 竞态需真实售后裁决 CREATE_REFUND 来源后补；已有迟到支付/商家拒单退款由回归覆盖。

历史核销行无可信身份映射即阻断迁移。尚未交付员工登录授权、手动订单号兜底、HTTP/小程序和 v2 通知/评价消费者；完整 VER-001/VER-002/AFS-001/QA-004 不标 DONE。
