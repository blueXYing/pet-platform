# 普通退款 R1/R2 实施与验证

日期2026-09-30；[PR96](https://github.com/blueXYing/pet-platform/pull/96)，基线PR95合并 `eb5d16f`。用户先批准拒绝后重新申请规则，再明确回复“批准”同意[R1/R2方案](../../../ccr/CCR-W2-API-001/refund-application-proposal.md)。审批覆盖正式契约、默认关闭内部实现、隔离测试与PR；不包含合并和生产启用。

## 交付行为

真实当前USER会话申请，当前OWNER同意/拒绝或数据库24h期限自动批准。拒绝后保留旧申请/决定/首回执并新建一轮；任意已有退款单禁止再申请。加密五元组Admission保存参数与首回执，失败、并发和提交后响应丢失均按原requestId恢复。

批准决定先提交唯一CREATE任务；SYSTEM随后以真实批准和本次ORDER版本建单。申请/批准未建单时仍可核销，核销先成功仍能普通全额退款；退款单先建则禁止核销。真实普通来源贯通REFUND→ORDER→PAYMENT→SCHEDULE，原渠道UNKNOWN只查原refundNo，最终成功才同事务投影金额、记消费及释放预约，保留核销历史。

已登记两种来源MERCHANT_APPROVED/MERCHANT_TIMEOUT_AUTO、两个申请通知Outbox意图及四类任务。旧两种退款来源和v1成功事件字段保持兼容。TASK core新增精确元数据恢复入口，保留有效租约/attempt历史；申请和渠道扫描逐行记录脱敏坏证明，技术故障保留内部cause并失败，修复后核实收敛。

## 正式变更

[Contract49](../../../../docs/04-api/49-Refund-Application-Contract-v0.1.md)、[Storage49](../../../../docs/03-database/49-Refund-Application-Storage-v0.1.md)、[SQL49](../../../../docs/03-database/49-Refund-Application-Schema-v0.1.sql)、SSOT §39、Internal07、Event08、Scheduler09同步。新增API包括申请/决定/超时建单、REFUND来源事实、ORDER资格与提交证明；SQL49增加申请/决定/Admission/ORDER证明/异常记录，扩展执行来源，移除全局requestId唯一并加活动申请唯一。

所有生产SQL在各自模块MyBatis XML。ArchUnit与负例22项通过。原用户工作目录及未提交文件未动；继续复用附属worktree和原PR96分支。

## 本地验证证据

Java21；独立MySQL8.4.9绑定127.0.0.1:3315，每用例独立随机schema，未使用原3306实例。25个普通退款验收方法分批运行：首批19通过，新增/扩充4通过（其中1个为原成功链路扩充），最后3通过，共25个独立方法。另PAYMENT18、任务恢复及原提交器24、核销历史3、加密2、配置/严格任务/标准组合33通过。测试均无失败/错误/跳过；最终完整回归以PR当前提交的CI和其实际后端报告为准，不能将这些分批数冒充一次全量测试。

| 需求 | 实际验证 |
|---|---|
| RF01～RF06 身份、资格、幂等、重申请 | 真实支付/确认/核销后申请；真实UserAuthService+MySQL账户和OWNER，伪造身份/冻结/退出/撤权；同key并发、异参、拒绝新轮、旧请求旧超时；提交真实ACK丢失恢复 |
| RF07～RF10 期限与恢复 | MySQL连接会话时钟推进，不SQL伪造批准；提前timeout返回Retry、恰好截止排除商家、到期自动批准与并发；缺失/DEAD/CANCELED任务恢复，真实批准后重建service/worker，坏证明/任务隔离及修复 |
| RF11～RF14 核销、原子性、来源 | PENDING和APPROVED阶段真实核销，后续CREATE采用新版本；CREATE先提交阻核销；并发二者共享guard；申请7/决定8/建单7处故障注入；跨事务/伪造token、伪造来源/决定/密文/缺ORDER提交拒绝 |
| RF15～RF17 渠道和最终释放 | 两种来源×已核销/未核销订单；UNKNOWN只查原号；成功前保留占用；成功消费/SCH释放回滚、重复只释放一次；坏渠道来源仅记异常不改资金状态 |
| RF18～RF19 兼容与工程 | PAYMENT旧来源兼容、默认关闭/缺依赖失败、禁止循环引用的完整装配、旧消费者跳过已知其他来源；历史申请迁移阻断不删历史；Python契约118和架构源测试18通过，模块/展示归属/MyBatis检查通过 |

重要边界：渠道是离线测试适配，未连接或触发真实退款；真实会话解析用隔离MySQL和测试volatile缓存，CI既有认证测试另用真实Redis。时间边界通过连接级MySQL时钟控制；预约已开始场景调整的是排期夹具，申请/批准/退款结果均由真实API产生。坏行种子仅作为拒绝与恢复隔离负例，不作为合法出款来源。

## 独立复核与修正

并行Owner与独立交叉审查修正：决定重放错误重跑当前退款资格；核销后版本增加造成旧证明误拒绝；定时任务恢复NULL截止时间误匹配；损坏canonical hash误作用户幂等冲突；跨域技术异常丢cause导致坏行误分类；旧渠道扫描坏证明阻塞后续行。相应恢复/ACK/并发/配置/失联表和真实锁超时负例已纳入验收。

## 上线与后续边界

`pet.refund.application.enabled/worker.enabled/http.enabled`均默认false，http=true始终拒绝启动。本批不提供公开控制器、生产原因字典、生产审核Provider或密钥。上线前需真实配置、部署评审、历史数据迁移方案及公开流程验收；SQL49遇无来源历史申请直接阻断，不能清空或猜填。

REF-001服务前自动退款、真实AFS创建/证据/运营裁决/PARTIAL、STAFF绑定、HTTP/小程序、通知实际送达与完整QA-004仍为后续范围。完整REF-002/REF-003/REF-004/VER-002/AFS-001及整个CCR不提前关闭。PR仅审阅，不自动合并、迁移或启用。
