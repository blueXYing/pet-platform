# S2 Hutool 5.8.47 最小生产适配设计 v0.2

**当前状态：SDK_SELECTED / ADAPTER_ACCEPTED / PENDING_CONTRACT_SYNC。** 2026-09-14人工已接受提交 **e81c6bcaba360a192001167823c30f2afa24a92a** 的新增适配方案及指南初始参数，见[主文回执](../CCR-W2-IDEMP-001.md)。本次只记录批准，以下技术正文/参数不变；原ADAPTER_PROPOSED/待审措辞保留为历史。

权威Contract同步、具体DDL/部署/实现验收均未完成；实际宿主退出确认能力与DB恢复前提仍需落实，通用业务幂等表/旧key迁移仍未定，CCR非RESOLVED、PLAT002/004非DONE。PR13合并未授权；当前分支已集成643f05cd新基线，原正文bcb269c2指批准对象当时的分析基线。

状态：**SDK_SELECTED / ADAPTER_PROPOSED**。人工已选cn.hutool:hutool-core:5.8.47；协调/发布闸门/超时恢复是新增适配建议，尚待审。旧d5acc3a未获批准、仅Git历史，不继续自写生成算法。本阶段仅修订对应CCR五份Markdown，不改权威docs/S1/pom/SQL/环境。

基线develop bcb269c2adc9405e669747d9b3bedfae2bf5ccbd；原9dd6及PR13分支。根制品/比较报告只读；[来源证据](s2-review-evidence.md)、[指南](s2-review-guide.md)、[例子](s2-examples.md)。原布局/epoch/String/金额/Context不重裁。

## 1. 精确SDK配置与已核源码

5.8.47完整构造器参数固定：Date(epoch2026), workerId=node & 31, dataCenterId=node >>> 5, useSystemClock=false, timeOffset=0, randomSequenceLimit=0。node显式分配0～1023，5+5拼接等于原10bit node，不改1/41/10/12。禁止默认构造器/epoch=null、IdUtil默认缓存节点、IP/MAC取模；关闭默认2000ms回拨容忍及随机起始序号。依赖只用hutool-core。

同版源码事实：nextId在SDK实例上同步，sequence/lastTimestamp在内存；genTime与tilNextMillis私有；后者时间不变时循环、无deadline/interrupt检查。false取System.currentTimeMillis，不接受外部java.time.Clock。SDK是Serializable，但序列化恢复/克隆会复制生成状态，不是持久重启安全方案。SDK拼位没有本项目完整的epoch/41位域保护。

根已用Java21.0.11同实例8线程十万ID验证正Long唯一、epoch/node解码，反射注入未来lastTimestamp在timeOffset=0时报错，并实际发现同node两个全新实例可重复。这里只引用其有限证据，没有重跑、改OS时钟或安装到项目。

## 2. 删除哪些旧自研，仍保留什么

| 旧设计组成 | 当前处理与理由 |
|---|---|
| 自写位拼接、序列递增、4096等待循环 | **删除**，全部交原版Hutool；不fork/抄源码/反射修改其私有状态 |
| 本地lastSequence/作为生成状态的lastTimestamp | **删除**；适配器不分配序号或修补候选ID |
| 同JVM回拨追平后手动恢复旧序列 | **删除**；SDK异常/不明结果失败关闭，恢复走新JVM+新grant |
| 预调用检查能控制SDK取时/等待的假设 | **删除**；SDK时钟私有，改为返回后的候选发布检查 |
| 持久H、node owner/fence/grant | 仍必要：SDK没有跨进程/冷重启协调；不说明这一点就照搬旧方案不成立 |
| lastPublishedId及许可/操作状态 | 仅输出安全守卫，不是另一套算法；防回退/重复/窗外候选交业务 |
| 超时执行模型 | 新增唯一SDK通道与非阻塞终态仲裁，不声称Future取消会终止SDK |
| pet-id-core | 仍仅建议单体薄适配模块：容纳SDK依赖及协调，common保持纯接口、boot只装配；不增加独立平台服务 |
| 通用业务幂等物理表/旧key迁移 | 不纳入本次，不能因选SDK附带批准 |

零协调的静态node+单例只适合受控单JVM实验，不满足原生产保证。SDK独立子进程可加强资源隔离，但增加IPC/进程治理成本，本次不选默认；给SDK增加Clock/deadline需维护受控分支，不是直接采用原版5.8.47。本方案如实保留无法由SDK消除的代价。

## 3. 最小MySQL协调（待审，非DDL）

建议一张node状态表（暂名snowflake_worker_state）：nodeId主键、immutable formatIdentity、enabled/初始化依据、ownerIncarnation、单增fence、leaseUntil、grantStart L/grantThrough U、永久reservedThrough H及审计时间。node作主键无需发号自举；同ID域不能各模块独立复用同node。H/fence禁止回退/删除/回绕。

冷启动全新incarnation，不恢复旧SDK对象。先锁单一权威MySQL node行，再新语句取DB UTC相对epoch毫秒D；enabled/format/初始化有效且无owner或leaseUntil≤D才可新授权。读取旧H，L=max(H+1,D)，U=L+5000-1，校验41位、fence和U-D≤10000，原子写新owner/fence+1/L/U/H=U/leaseUntil=D+10000。明确commit ACK后才安装本地许可；时间未到L则等待或按单次预算失败，不把L合成为SDK当前时间。

续租/扩窗每次重新核enabled/format/初始化、owner+fence、leaseUntil>D和H/U/L与本地已确认值一致。纯续租不改H；剩余窗≤1000ms时newU=max(U+1,D+5000-1)，在范围与超前上限内原子推进H/U，ACK前不能启用新上界。一个待用grant不反复再分配多个未来窗；本地许可截止从请求前mono算9秒，不从迟到ACK时刻续满10秒。

H定义为已授权“可向业务发布”的时间上界，不是SDK最近生成时间。SDK可内部计算不合格候选，但未过发布闸门不得交业务；不能把未经确认范围内的内部值当成功。旧incarnation全部可发布范围≤旧H，新grant严格在其后。

DB失败/ACK未知也使本incarnation终态失败：整旧窗停用，H不减少；迟到DB ACK不刷新本地许可/不复活。请求超时不证明服务器事务取消，它仍可晚commit H/lease。新进程须等旧锁/租约并在主库锁下读最终H取得新区间，不能查到同owner就恢复旧序列。真实旧JVM终止是本适配恢复的附加门禁，不由DBlease证明。

## 4. 一秒预算与独占SDK执行通道

原版私有tilNextMillis可能无界spin。调用前限流/看一眼时钟不能证明避开：外部检查与内部genTime之间仍可变化；不依赖继承private方法、Thread.interrupt或Future.cancel终止它。

每JVM一个provider、一个私有SDK实例、一个专用SDK执行线程；最多一个SDK调用在途，无SDK待发队列，无自动补建通道。不能直接用“单线程Executor”这个名称就认为满足：实现期须验证无积压、无自动替换卡死实例/线程的具体机制。

每次nextId从入口共用1000ms预算，覆盖竞争通道、必要DB操作、SDK等待、输出验证。未取得串行资格且尚未启动任何SDK/不明DB操作的调用，预算耗尽只返回本次不可用，不越权提交任务；**SDK已启动/授权结果不明后的超时则终止整个provider**。不按每步骤各等1秒，也不为每个重试新建线程。

终态/成功发布须由**不依赖SDK monitor或可被暂停线程持有互斥锁的原子状态仲裁**决定。推荐把生命周期、incarnation/许可版、当前操作token/结果、lastPublishedId纳入明确CAS状态转换或经证明等价机制。绝不能持发布锁调用SDK/等Future/访问DB；所谓“短锁”也不自动给超时路径1秒上界。

可能阻塞的DB连接/授权和SDK调用放在同一有界隔离执行路径，由独立、不做阻塞I/O的调用/计时控制端观察剩余预算并完成终态仲裁；不能把DB同步阻塞留在超时控制线程上却声称总预算覆盖。后台续租也采用同一单飞路径和独立截止观察，无新待发队列；繁忙时不堆积后台工作，下一发号须先满足有效许可。具体执行器/计时器关闭与不补线程行为须在实现期实测，不仅依赖API名称。

流程：

1. 获串行资格，原子检查当前许可/预算/UTC，登记PENDING操作token及已确认grant快照。授权DB操作和SDK在途不并行修改许可；都不得阻塞终态仲裁。
2. 专用通道运行原版SDK.nextId得到内部候选或异常；只把内部候选送回控制层，SDK Future/实例不交业务。
3. 调用方/超时控制与候选验证竞争该token的最终结果。通过§5校验后一次仲裁记SUCCEEDED及lastPublishedId；否则FAILED并在需要时将provider置FAIL_CLOSED。结果不可从FAILED再改SUCCEEDED，迟到完成不能触发旁路业务回调。
4. 超时先获胜：对外失败、停止接收SDK任务/后续续租，所有旧候选/旧ACK丢弃。发布先获胜：该操作只交同一已仲裁结果，不再另外报告它超时。操作结果须保留到调用方可观察，不能新token覆盖旧结果造成双结论。
5. SDK异常、已启动调用超时、错node/非法/重复/窗外候选、close/失权/不明提交使provider终态失败。停止续租不否认已有DB在途commit；本地不接受其迟到ACK。

**Future取消仅清理请求，不是底层停止证据。** 超时后最多保留一个不可响应SDK线程，provider保持不可用并报警；禁止同JVM重建SDK/线程/恢复grant。宿主必须确认旧JVM真正退出，才能冷启动新进程+新grant；现在没有实现或假定已部署此退出/恢复能力。不能把lease过期、连接断开、Future.isCancelled或interrupt当死亡证明。

原1秒是对外nextId操作/发布预算。不可调度暂停期间无法承诺现实1秒回包；恢复核预算和线性化边界沿用原约定。当前不承诺所有底层计算资源也在1秒内停止；若要求该额外语义，原版SDK不能直接满足，须另审隔离进程/受控SDK修改或明确资源时限条款，不能默默降级。该失效/恢复模型本身仍待人工评审与并发实测。

## 5. 返回后候选检查与安全证明

调用前Clock检查不能决定SDK内部再次取时，所以候选必须返回后全部校验：

- token仍属于当前incarnation、未超时/关闭/失权，DB授权明确，mono调用预算和本地许可仍有效。
- 正Long；SDK公开getWorkerId/getDataCenterId解码匹配显式5+5；getGenerateDateTime解码的相对t在已确认[L,U]。
- 独立校验调用前后实际OS UTC相对epoch处于合法41位范围，与候选编码时间相容、无已观测回拨/明显跳变；不能只用解码掩码检查，因为SDK移位溢出可能被getter掩码隐藏。注入业务Clock不是SDK真实时钟，不可拿fixed Clock伪造这些采样。
- id严格大于本incarnation的lastPublishedId（初次无值）；此字段只拒绝候选，不产生序列、不修正位、不改SDK状态。候选失败不能先交业务再异步扩窗/补H，也不能事后授予区间追认。
- 最后成功仲裁一次更新lastPublishedId及操作结果。返回SDK异常不暴露Hutool类型为业务DTO，后续HTTP映射由原Owner按既有错误处理。

采样不等式明确为：OSbefore≤candidateAbsoluteMillis≤OSafter；OSbefore、OSafter均位于[epoch, epoch+2^41-1]且OSafter≥OSbefore；墙钟历时与同次mono历时毫秒差的绝对值≤S。DB授权另取OSbeforeDB/OSafterDB，要求Dabsolute∈[OSbeforeDB-S, OSafterDB+S]且授权往返未超剩余预算。S=250ms是DB/时钟跳变容差，不用于放宽SDK候选到调用采样区间之外。原始OS采样与SDK实际同源，不以业务fixed Clock冒充；对完全未被采样观测的瞬时钟故障不作检测完备性承诺，区间/严格发布单调性仍独立保护输出不重用。

仍有不可消除的调度边界：暂停可在最终UTC/deadline检查与成功仲裁/返回之间。超时终态先仲裁则候选必丢；成功先仲裁的在途返回可能迟到，不能声称任意墙钟时刻以后绝无旧返回。不得锁住超时路径来“保证”发布。输出来自此前SDK真实采样，但并不等于业务发生/返回时刻。

在单写持久H不丢不回退、不可克隆状态前提下：不同node位不同；同node不同incarnation可发布时间范围不交叠；同incarnation唯一SDK加单一发布仲裁确保已发布id严格递增。由此不向业务重复返回ID。库内部未发布候选不构成业务成功，也不占据“已返回ID”的契约含义。

冷重启不恢复SDK对象或lastPublished；只有新grant在旧H之后时清空新实例发布状态才安全。H丢失/双主重叠/SDK及适配器热克隆仍能破坏证明；Serializable不是安全恢复许可。SDK选择不能删除这些前提。

## 6. 回拨、Clock与参数责任

SDK负责其内存lastTimestamp回拨检查，timeOffset=0不允许容忍钳制；SDK异常或外部发现时钟不安全使适配器失败关闭。删除旧方案“同JVM追平后手动恢复序号”分支。新进程取得新区间且实际UTC追上、OS/DB时钟采样合法后才可发布，单次等待不扩大到5秒窗。

保留业务java.time.Clock/Zone原约定，SDK自身用System.currentTimeMillis；不能声称以业务fixed Clock驱动SDK故障测试。Task继续DB NOW决定claim/lease/重试，Clock仅供Handler context。时间位只用于内部候选校验，三端不可推断订单时间/状态/权限。

建议W5000/R1000/DB lease10000/renew2000/local margin1000/skew250/max ahead10000毫秒仍待适配包审批；nextId总预算1000毫秒已接受、保持。mono比较同JVM差值nanoNow-start，采样间隔<2^63ns，异常负差拒绝，不比较易溢出的绝对nano截止。DB先锁后新语句取时；H/U计算checked，41位末端/fence溢出停发。

SDK在epoch瞬间node0可能返回0：丢弃并失败关闭，不自写跳序列。未来若选择重新调用库重试，须另外说明总预算/失效策略，本稿不暗加恢复分支。版本升级必须复核这些private行为，不只改pom版本号。

## 7. 持久与部署约束/具体差距

单一权威MySQL谱系、确认H不丢不回退、显式node、不透明克隆SDK/整个适配器。建议后续核验InnoDB/binlog持久设置与存储兑现flush，并验证切主不丢确认H；本轮不改配置。旧备份恢复/丢提交切主/双主须隔离旧发布者并恢复可信最大授权上界；无法证明则停发，不只查业务表max(id)/当前时间猜上界。

初始化不能假设空库，需盘点历史发号/节点/未落业务表的授权。不能删除H/fence从0重建。滚动发布新进程须新owner/fence/grant；本推荐在当前provider故障恢复时另要求宿主确认旧JVM真实退出。若宿主无此确认能力，就不具备推荐恢复流程，保持not-ready并补部署设计，不以SDK/DB租约代替。

未来pet-id-core只隐藏SDK和必要协调/生命周期适配，common不引Hutool类型，boot只装配；具体模块注册/pom/JDBC/表迁移及宿主退出机制需要原Owner后续落实。本轮无依赖/代码/DDL/环境改动。相比旧方案确实删算法，但不能声称协调和资源治理成本全部消失。

## 8. PLAT-004与验收边界

保持S1接口，已合PR12的JdbcAsyncTaskRepository(DataSource,SnowflakeIdGenerator)在claim(owner,leaseDuration)锁前申请attemptId；适配成功才交合法ID，失败不占task锁，未用ID不回收。TaskRegistration的Clock与DB时钟职责保持；完整Worker仍缺真实S2/装配以及原producer/告警等DoD，不能因选库DONE。

C/M/Admin继续全程ID String、金额/Context与原错误/权限契约；发号超时或结果未知时，不得本地造业务ID、伪造成功、自动换requestId/UUID重投。先依服务端事实和原意图处理，SDK私有时间不决定业务时间/状态/权限；没有新增端上SDK或恢复身份体系。

后续必须验证精确参数、5+5/epoch、候选越界/回退/0/OS时间溢出拒绝、1秒发布仲裁与迟到候选/ACK、无积压/无通道补建、真实JVM终止后新grant、DB ACK未知与冷重启/恢复拒绝。阻塞调用替身只能测试适配控制层，不能证明Hutool真实冻结/不可中断行为的灾难实测；fixed Clock也不控制SDK私有时间。

当前证据仅源码/制品核对、已存在的根实验与有限算术，新增适配尚无实现测试。SDK_SELECTED / ADAPTER_PROPOSED，CCR非RESOLVED/完整Issue非DONE。通用业务幂等物理表、旧业务key迁移、AUTH/渠道业务语义不在本包批准中。
