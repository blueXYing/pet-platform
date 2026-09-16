# 整合基线与发号器核查（2026-09-16）

本轮从重新fetch的origin/develop `b91a0146b7d768f38ab7288cb0c3bd56788b2e55`创建独立worktree和`codex/id-timezone-integration-closeout-20260916`分支。主目录的project.config.json改动保留；未读取真实凭据，未运行本地放行配置，未启动其他开发会话或子代理。生产代码没有已确认需修改的缺陷，因此本轮交付是回归保护和事实纠正，不宣称已修复尚未复现的故障。

## GitHub当前事实与历史回执

| 事项 | 核验结果 |
|---|---|
| PR35 | 已合并，merge e7d1c7ad3af32d5ed22cbbd8dcbc14a5804f81e8 |
| [PR36](https://github.com/blueXYing/pet-platform/pull/36) | 已合并，head 16aafa4cbef84461eaf6500d6f8240c1a34b10ef；merge b91a014；mergedAt 2026-09-16T07:48:17Z |
| PR36合并前检查 | [run 35069725733](https://github.com/blueXYing/pet-platform/actions/runs/35069725733)，pull_request，六项成功；不是develop合并后的检查 |
| develop合并后检查 | [run 35070404547](https://github.com/blueXYing/pet-platform/actions/runs/35070404547)，push，head b91a014，六项成功，backend结束于07:51:59Z |
| PR36代码事实 | pet-boot资源明确排除application-local.yml；example中的cache-prefix尾冒号已有YAML引号。此次读的是已跟踪源码/模板，未读取填写后的凭据文件 |
| PR36历史冒烟 | PR评论记录真实wx.login/jscode2session、手机号绑定、会话查询、带会话GET /pets、登出及登出后401成功。本轮未重新执行；历史使用未提交的宿主放行及JDBC URL UTC配置，不能视为正式生产启用证明 |
| C端页面 | profile-edit仍由preview参数启用预览仓库，宠物页仍为预览及设计样例；未形成真实页面保存、上传、会话失效恢复等业务闭环 |

六项为backend、frontend-inventory、repository-policy、contract-smoke、web-build、miniapp-weapp-build。CI不等真机、真实微信/短信重测或全产品验收。

## 时区调查：未支持PR36推测，保留已有正确实现

实际调用链是AdminAuthConfiguration将同一个DataSource传给JdbcSnowflakeNodeStore，后者创建使用该DataSource的JdbcTemplate和DataSourceTransactionManager。acquire的观察事务提交后，在**锁外**验证旧宿主退出，再开新的锁定事务；renew用单个新事务。每个事务均为REQUIRES_NEW、READ_COMMITTED、1秒事务超时。

`inTransaction`里的SET SESSION time_zone、read、锁后新语句SELECT NOW(3)、CAS写入共用Spring绑定的连接。不同事务允许使用不同物理连接，但每次都重新设置UTC。时间读取使用LocalDateTime并显式按UTC转Instant，lease写入也用UTC LocalDateTime。不是在事务之外SET后再从池借另一条连接查询。

新增PooledSnowflakeMySqlTest使用真实HikariCP三连接池：每次借出前把会话改为+08:00或-05:30；通过占用观察事务归还的连接，迫使加锁事务借另一条物理连接；记录真实CONNECTION_ID并断言两次不同，acquire两次checkout、每次renew仅一次checkout。矩阵覆盖JVM UTC/Asia-Shanghai、驱动UTC/Asia-Shanghai，明确关闭forceConnectionTimeZoneToSession。每组验证UTC采样、租约落库、H/fence、12次续租，以及另一独立审计初始节点的真实SDK发号与自动续租。

未修改生产源码时四组通过，没有8小时偏移或持续WARMING。另做负向对照：临时仅移除事务内UTC SET，四组全部因`Database and OS UTC sample mismatch`失败，随后恢复原文件并全量重跑。该负向变异未提交；证明回归可检测UTC保护丢失，也说明真正采样偏移会被当前代码拒绝，不能直接推导为无限WARMING。

WARMING的实际触发条件是OS相对时间尚未到已确认grant.start；未来高水位可以导致等待，但本轮没有历史故障时的节点行、调用栈和时间样本，不能据此倒推PR36根因。禁止手动降低H或盲重置节点来“修好”。

## 续租超时调查：区分两条1秒保护

Spring transaction.setTimeout(1)约束事务语句；SingleFlightLane的1秒单调时钟预算独立仲裁发布。`OPERATION_TIMEOUT`来自SingleFlightLane，不是Spring异常名称；若数据库/事务异常先返回，lane可以记录ACTION_FAILED。仅凭PR36的OPERATION_TIMEOUT分类不能认定是事务配置错误。

新增受控故障测试：初次真实发号后，自动续租执行真实MySQL commit，再由test-only连接代理扣住ACK直至发布预算过期。实测OPERATION_TIMEOUT、provider终态关闭、最后已发布ID/本地grant不变；数据库提交的lease及H保留，释放迟到ACK后仍不能发号或发布新grant。这证明提交未知/迟到时保护有效；**不是**复现Docker自发卡顿，也不证明线程/数据库已被停止。

本轮正常池化48次直接续租及四组自动续租成功，未复现历史偶发Docker停顿。没有提高事务/发布超时，没有重试复活、替换SDK线程、换节点、重置H或放松宿主证明。后续若重现需采集同一时间窗的事务/SQL耗时与异常链、池活动/等待数、MySQL锁等待、提交ACK时间、JVM GC/safepoint与宿主调度停顿，以及脱敏的node/fence/grant和UTC/nano样本；避免采集连接密码和认证令牌。生产Linux尚未做长时间压力与恢复演练。

## 验证记录

本次实测：Windows、JBR Java21.0.10、Maven3.9.12，新建隔离Docker MySQL8.4.11与Redis7.4（RDB/AOF关闭）。只使用测试随机库，不操作PR36的c-smoke服务或其数据。发号器定向回归时MySQL全局为+08:00；全后端回归使用UTC全局以匹配现有CI，新增回归仍会逐连接注入非UTC会话。没有跳过测试或用H2代替MySQL。

首轮Docker全后端clean verify失败：AdminAuthFailureRecoveryTest的lostAttemptCreationAckCannotReplayAnonymousSecretsOrCreateAgain、readResultRechecksAfterCacheReadWhenLogoutOrNewLoginCommits两项在AuthTestDatabase.close执行DROP DATABASE时发生5秒SocketTimeoutException；其他已运行模块通过，boot/architecture因reactor前序失败未执行。该证据是测试清理DDL延迟，不是已定位的续租事务故障。保留失败记录，随后新建独立原生MySQL8.4.9（127.0.0.1:33447、独立新datadir，默认持久化设置），保持相同源码/断言/超时与Redis配置重跑完整clean verify。未接管或修改已有MySQL服务，未增加超时或降低刷盘保护。

原生首轮中前40个reactor项目通过（含认证全部数据库回归），pet-boot的AdminAuthHttpTest/CAuthHttpTest两组启动因已有进程占用8080失败，ArchUnit当轮未执行。测试的properties(server.port=0)是低优先级默认值，被application.yml覆盖。没有结束原进程或修改boot源码；后续完整重跑仅给测试子进程设置SERVER_PORT=0，HTTP测试仍按实际local.server.port调用，所有业务断言保留。此环境隔离不涉及发号安全放行。

详细最终计数、逐测试套件结果、日志/测试源码SHA256及物理连接见[机器可读证据](id-closeout-evidence.json)。最终完整重跑于2026-09-16T16:21:51+08:00通过：42个reactor项目、260项JUnit，零失败/错误/跳过，包含22项ArchUnit。本轮PR的六项CI结果以PR当前head检查为准，不能拿b91a014的历史绿灯代替。

| 验证 | 结果/范围 |
|---|---|
| 未修改生产代码的首轮时区矩阵 | 4通过，0失败/错误/跳过 |
| pet-common + pet-id-core模块回归 | common79、id-core41，0失败/错误/跳过；包括既有MySQL行锁/高水位/ACK丢失与真实独立JVM退出恢复 |
| UTC SET移除负向对照 | 4个预期错误，均为UTC样本不一致；Maven exit 1；变异已撤销 |
| 架构源检查 | 模块依赖、展示状态检查通过；13个Python架构负例通过 |
| 契约离线检查 | 52操作/37写、630引用/118 String ID；82个Python测试通过；不表示live HTTP或业务E2E |
| 首轮Docker全后端clean verify | 失败：认证测试清理DROP DATABASE的2个5秒读超时；boot/ArchUnit当轮未执行，不记为通过 |
| 原生首轮全后端clean verify | 认证数据库回归通过；2个HTTP测试类因8080占用启动失败，ArchUnit未执行；后续用测试进程随机端口重跑 |
| 最终原生MySQL + SERVER_PORT=0完整clean verify | 42个项目、260项JUnit（含id-core41、ArchUnit22），0失败/错误/跳过；不是仅重跑失败测试 |
| 本轮PR CI | 查看本轮PR当前head的六项检查及测试artifact，与上述develop合并后CI分开记录 |
| 微信真实登录/手机号、真机与页面保存/上传 | 本轮未执行；仅有PR36历史冒烟回执 |

## 边界与下一阶段

生产实现、Schema/API/Event/Scheduler、1秒预算和所有安全约束不变。本轮只加test-scope Hikari依赖和测试，不新增JDBC持久层实现，不与MyBatis裁决冲突，不需要借此次调查提前迁移pet-id-core或全模块。

可以确认下一阶段的已批接口接入范围并进入有限的C端接入开发；**尚不具备无临时放行的完整真实业务验收条件**：可信PreviousJvmExitVerifier仍缺、节点初始化/高水位持久与恢复证明/迁移及生产启用未完成。已存在Admin可选装配，不应继续沿用旧交接“完全无boot Bean”的历史表述。

C端页面真实会话/资料保存/宠物增改删/上传与幂等重试仍需专门阶段验收；设计中的芯片号、疫苗驱虫记录、头像等未批字段沿用既有缺口登记，不能自行扩展契约，出现新缺口走CCR。此PR不启动该阶段，也不将AUTH-001、PLAT-002、CCR-ACR-001或其他相关CCR标记完成，不启动商家、交易或全量MyBatis迁移，不自行合并。
