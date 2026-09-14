# S2 生产 Snowflake / Clock 具体设计 v0.1

状态：**PROPOSED / PENDING_REVIEW**。属于CCR-W2-IDEMP-001 / PLAT-002的新增实现机制决定，不是原提案自动已批。唯一Writer Backend Core；Transaction/QA独立只读审时序，三端只核ID/业务边界。本阶段仅planning文档，不改原已接受正文、权威docs、源码、DDL或环境。

初始输入develop `0cc7d0151cdae3500185957d2a35088d0b31e37f`；根批准PR12合并后，本设计分支已快进到交付基线`bcb269c2adc9405e669747d9b3bedfae2bf5ccbd`。已接受方案a9856c14、批准回执3c7b529、S1交付7c484371不变。只读PLAT-004工作区44dc固定提交`c641794119063706d3ab468a90898d3694ed26d8`的task-core/HANDOFF/README及注入点，已合并组件不等于本S2生产实现。来源/审阅证据见[s2-review-evidence.md](s2-review-evidence.md)，人工入口见[s2-review-guide.md](s2-review-guide.md)。

## 1. 保持不变与待批准范围

保持正Long Snowflake：符号位0、相对毫秒41位、worker10位、序号12位，epoch=`2026-01-01T00:00:00Z`；API/JSON/TS ID String；Clock使用java.time.Clock，业务Zone初始Asia/Shanghai；nextId总等待预算1秒。不能凭ID解码代替业务时间、顺序、状态或权限；不能用ID代替requestId/task_key。

本次新增推荐：MySQL持久高水位、worker授权/续租、只增不回收时间区间、本地许可检查与故障恢复、具体参数、技术表与实现模块归属、装配门禁。原通用业务幂等表/旧request_id迁移另案继续待设计；不借发号器表一并审批。

## 2. 选型与证明边界

| 实际可行方案 | 取舍 | 结论 |
|---|---|---|
| A 每worker在MySQL预留时间范围，本地发号 | 每次扩窗持久commit，平时本地序列；需不可回退H与单实例内存，允许废弃空洞和接管等待 | **推荐**，利用已有DB，不新增网络服务 |
| B 每一个ID都在MySQL锁行分配timestamp/sequence并commit后返回 | 同Snowflake布局可行，状态集中证明直接；每ID一次持久事务，吞吐/延迟/DB不可用敏感且挤占1秒预算 | 可行替代，不选默认；即便如此DB回滚丢历史仍不安全 |
| C 静态worker + 本地持久WAL/磁盘高水位 | 单机可行；容器重建、共享磁盘、磁盘丢失/快照/双启动隔离约束更多 | 不选默认，不把“静态worker+进程lastTimestamp”当充分方案 |
| Redis锁/租约或随机ID降级 | 仅锁没有不可复用区间，暂停/重启可重用；随机/自增替换已接受Snowflake | 不作为本次方案 |

**唯一性前提**：同一ID域只有一个权威MySQL写入谱系，所有已确认的H更新永久保留；无双主/丢提交切主/回滚H；每个已授权区间只交给一个不可克隆的JVM状态机。发生备份回滚、谱系不明、进程/VM内存克隆时停发，按§8恢复，不声称算法能抵抗丢失其唯一持久事实。

“独占”是DB中唯一当前owner可续租/扩窗。不能保证失去租约的旧进程在任意真实时刻绝无返回；本地检查与返回之间仍可能暂停。本文保证在上述前提下不重复，**不保证即时远程撤销、全局ID顺序或已返回号无空洞**。

## 3. 逻辑持久记录与Owner（非DDL）

推荐一张技术表，暂名`雪花worker状态 / snowflake_worker_state`。以配置的workerId作主键，无需先调用Snowflake生成该行主键，避免启动循环。一个ID域最多1024行，不按业务模块/订单表另开同worker命名空间；测试域与生产不得混库/事后合并产生相同业务ID的数据。

| 逻辑字段 | 约束 |
|---|---|
| workerId | 0～1023、主键唯一、部署显式配置；不自动抢另一个worker或从门店/机器IP推导 |
| formatIdentity | 固定epoch+1/41/10/12签名；不匹配即拒绝，不以新format行绕过旧H |
| reservedThrough H | 相对epoch毫秒，单调只增、不回收；初始未用可为-1但须完成存量证明；范围-1～2^41-1 |
| grantStart L / grantThrough U | 当前incarnation已确认授权的连续边界，L≤U≤H；同owner扩窗只增加U，H=最新授权上界；不代表已实际发到U |
| ownerIncarnation | 每次进程启动/重新取得许可都新标识，只用于诊断/CAS；不能凭相同owner字符串恢复旧序列；标识可UUID，非业务ID降级 |
| fence | 每次新授权严格+1，旧fence无法续租/扩窗；不得回绕；不编码入Snowflake，不能单独解决本地重用 |
| leaseUntil / updatedAt | DB UTC时间；租约只控制新授权/续租，不决定已预留范围可被回收 |
| enabled / initializedEvidenceRef | 缺记录/未审初始化/禁用即拒绝；初始化依据和变更审计可追踪 |

H、grant边界、owner、fence、lease在同一短InnoDB事务原子改变，worker主键行锁串行。物理列型/索引/权限账号/备份配置/可执行迁移脚本需后续设计评审与实测，本阶段没有DDL。续留类更新须同时检查owner+fence+未过期；只比owner不合格。新grant按§5.1的无owner/已过期路径，不要求旧owner仍有效。行不存在时运行进程不能自建低H。

建议后续新建**同一单体内技术实现模块pet-id-core**承载MySQL授权适配、生产生成状态机和配置类型，依赖pet-common的既有SnowflakeIdGenerator接口；不新增独立服务/新Issue。pet-common保持纯接口/S1转换，pet-boot仅装配。新增模块/root POM注册、必要JDBC依赖、迁移文件与boot配置是本次提案的后续影响，现阶段均不改；根Work按文件登记Owner后才实施。通用业务幂等持久化Owner不因此被指派到该模块。

## 4. 推荐参数和时钟分工

| 名称 | 值 | 状态/说明 |
|---|---:|---|
| 单次nextId总预算B | 1000ms | 沿用已接受1秒；包括连接、锁、commit ACK、追时间和序号等待 |
| 窗口W | 5000ms | 新建议，启动预留5秒物理时间范围 |
| 扩窗触发R | 剩余≤1000ms | 一次最多补到DB当前时间后约W，不批量预取多个窗 |
| DB租约T | 10000ms | 新建议，独立于Task的60秒租约 |
| 续租周期P | 2000ms | 新建议；每个provider最多一个DB授权操作在途 |
| 本地安全余量G | 1000ms | 新建议，许可截止从请求发出前mono算T-G=9秒 |
| 时钟采样容差S | 250ms | 新建议，应用UTC包围DB采样的合理容差；超差停发 |
| 最大超前A | 10000ms | 新建议，任何新U必须满足U-D≤A，防重复启动烧向远未来 |

应用UTC墙钟Wnow取Clock.systemUTC（未来以资格校验后的注入实例装配），编码时间t直接来自真实采样相对epoch毫秒，**不能t=max(now,L/lastTimestamp)造未来时间**。业务Zone只用于显示/业务解释，不参与ID位运算。数据库UTC D只决定授权/窗口/lease，不能借它替代PLAT004的DB lease时钟；Task继续由自己的DB NOW(3)决定claim/重试/complete，注入Clock只给Handler context。

等待预算、续租本地截止及跳变观测用进程内monotonic elapsed（Java nanoTime差值），不把其绝对值跨JVM持久化。DB往返取应用UTC前后样本、mono起止；若D不在[UTCbefore-S, UTCafter+S]、往返超过B或检测墙钟/elapsed明显不一致，则许可不启用/停发。每次调用检查墙钟回拨及明显跳变，不能只有后台检查。

文中的m0+(T-G)只表示逻辑到期点，未来Java实现以`elapsed = nanoNow - m0`与T-G比较，不能直接比较可能溢出的绝对nanoTime加法；调用预算同理。使用同一JVM有符号long差值，采样间隔须小于2^63纳秒；负差/超出可解释采样范围失败，不能因计数符号跨界误放行。正常正负边界环绕例子见E11。所有相对毫秒和窗口加法在41位边界内作checked计算。

这些超时/偏差检查是保守失效机制，不是假定所有机器时钟永远准确；唯一性还依赖永久区间互斥，即使暂停/DB时钟跳变导致lease判断失真也不能重分旧范围。机器暂停无法调度期间不承诺1秒内回包，恢复后超过预算拒绝新分配。

## 5. 取得许可、续租、扩窗与未知提交

### 5.1 新启动或接管

初态NOT_READY，无可用旧区间。先连接权威主库，**取得worker行锁后，在新语句中取DB UTC D并检查lease**；不能用等锁前的NOW（PLAT004已有对应故障证据）。仅enabled/format/初始化有效且旧lease已过期（leaseUntil≤D，或已受控清空owner）才能取得新授权；与续租要求leaseUntil>D互补。

锁下读取旧H，候选`L=max(H+1,D)`，`U=L+W-1`；检查整数精度、41位范围、U-D≤A、fence不溢出。任何检查不通过不修改H。原子写新incarnation/fence+1/L/U/H=U及leaseUntil=D+T，commit明确成功后才安装本地许可。新fence从不采用前一进程的lastTimestamp/sequence；所有t≤旧H永久弃用，包括未发出的槽位。

DB请求前记录m0；明确ACK回来仍须检查此次预算、UTC采样、许可截止`m0+(T-G)`、关停状态，才能使用。ACK太晚即弃本次授权，不使用其范围；H仍保留。如本地UTC<L，处于WARMING而非ACTIVE，只等待真实时间（单次最多B）或返回不可用；未到L不能连续再申请新窗。同一个进程最多一个待用grant，启动重试不每次盲抬H。

### 5.2 当前owner续租/扩窗

本地单飞串行DB授权操作。**每次续租/扩窗/安全复核都重新校验enabled、formatIdentity、初始化证据和锁下H/U/L与本地已确认许可一致**；禁用、格式不符、异常记录或不明状态即ABANDONED，不因已持owner绕过。取得行锁后新语句采样D，CAS校验owner+fence+leaseUntil>D；过期即使owner字符串相同也不能续旧许可。纯续租不变H/L/U，只延长DBlease，本地截止仍从本次请求前mono采样保守计算。禁用变更在下一次许可核验被观测后关闭，不能承诺DB改enabled的瞬间撤销所有已经线性化/尚未观测的本地调用。

当已确认U与真实UTC差≤R时可附带扩窗：`newU=max(U+1,D+W-1)`；保留当前L，新增授予的只可能是`[旧H+1,newU]`且要求旧H=本许可U。检查newU-D≤A和41位边界，原子推进H/U后commit。ACK前不能使用newU；正常扩窗不重置sequence或lastTimestamp。若D/本地钟跳远超当前窗口，先通过时钟安全复核再扩窗，不临时伪造timestamp；无法在B内完成则失败。

本地发号、许可发布、状态失效/close使用同一线性化状态机；DB操作在途时发号许可关闭（或等待，仍计入B），避免发号和关闭状态交错。不能建两个provider对象各用同一grant且sequence独立；Spring以单实例装配、构造/配置防重复，启动双进程也要各自走新授权流程。

### 5.3 失败和COMMIT ACK未知（推荐保守分支）

任何DB授权操作超时/连接失效/ACK未知/fence冲突，使当前incarnation进入终止的ABANDONED：**停用全部本地grant，包括此前已确认部分**。不从“查到同owner/同H”猜成功，也不恢复旧grant序号。迟到ACK不能重新启用已终止状态；每次操作有本地递增编号，用于丢弃旧回调，不能用网络回包覆盖新incarnation。

恢复必须重新从主库锁行读取最终H，等待旧事务释放锁与lease到期（或受控安全关闭），取得更高fence和全新不重叠grant并收到明确commit ACK；无论未知事务实际提交还是回滚，后续都以**锁下已确认H**为准。未确定主库事实不得启动，副本读或客户端超时都不是回滚证明。这样无需把不确定旧授权恢复出来，代价是更长停发和空洞。

已明确失败也按同样保守策略终止而不继续剩余旧窗，降低状态组合。客户端预算超时不代表DB事务已经取消，它仍可能迟到commit并消耗区间；本地先终止许可并丢弃迟到回包，新授权仍等待该锁释放后查权威H。接管频繁时A上界阻止无限超前；失败不自动切worker、换算法或本地延长H。停机可先在本地线性化close，再条件清空DB owner/缩短lease但**永不减少H**；DB不可达则等lease，不强行复用。关闭前已线性化生成的ID仍可能迟到返回，见§6。

## 6. 本地每次发号与暂停/回拨

每个provider唯一mutex/等价原子状态，保存已确认[L,U]、incarnation/fence、本地许可截止、lastIssuedTimestamp、lastSequence、maxObservedUtc及状态。每次nextId从方法入口计B，进临界区与真正分配前重新核：未close/ABANDONED、无不确定DB操作、mono预算/许可未过、真实UTC未回拨/明显跳变、t处于已确认[L,U]。

- t>lastIssuedTimestamp：序号从0开始；t=lastIssuedTimestamp：序号递增；t<lastIssuedTimestamp或低于此前maxObservedUtc即CLOCK_UNSAFE，不通过回退lastTimestamp恢复。
- 同毫秒序号0～4095用尽后，只等真实UTC下一毫秒并重做全部检查，或B耗尽失败；不能sequence绕0，也不能timestamp++跳未来。等待释放本地锁但恢复须重新验状态。
- t>U必须先成功持久扩窗；t<L等待/失败，不从L造当前时间。U/H/fence将溢出则永久不可继续自动发号并告警。
- 编码`(t << 22) | (workerId << 12) | sequence`，所有分量先界限检查。epoch瞬间t=0/worker0/seq0结果0，跳过该序号，不能返回0；最大值仍为Long.MAX_VALUE。
- 在临界区最后许可检查后**更新timestamp/sequence即本地序号分配线性化点**。暂停既可在更新后返回前，也可在最后检查与更新之间发生；本地mutex只排除同状态机并发，不把时钟读取与序号更新变成跨进程不可暂停动作。恢复后的在途调用可能才分配/返回此前采样t和旧grant中的ID，不能称“租约过期后绝无旧分配/返回”。该调用持同一本地锁、只消费旧grant内唯一序号，安全仍由区间互斥保证；若新增检查发现失效可丢ID形成空洞，但任何有限检查也不是远程即时撤销证明。

CLOCK_UNSAFE期间停发。若incarnation仍有效，只有真实UTC追上maxObservedUtc和lastIssuedTimestamp、通过新的DB许可复核/续租ACK、仍在已授权窗口且预算可满足才恢复；timestamp/sequence不重置，同毫秒继续旧sequence。若期间租约/本地截止过期、DB不明或进程重启，则ABANDONED，按新grant恢复，不能拿回旧窗。

**暂停恢复的安全重点**：fence拒绝旧进程再续租/扩窗；本地时间/截止复核通常使旧调用失败，但即使旧进程在最后检查后暂停导致迟到返回，旧grant的t≤H_old，新grant的t≥H_old+1，仍不可能相同。不能用“旧实例必然死亡”“Future.cancel已停止”代替这个不变量。

## 7. 不重用证明及反例边界

在§2前提下，持久事务依worker行锁串行形成授权序列。每次新授权L>旧H，每次扩窗只把此前未授权的(旧H,newH]交给同一incarnation；H严格只增，任何旧窗即便未用也不回收。因此不同incarnation同worker的timestamp范围不交叠。

同一incarnation内单状态机使同t的sequence严格递增、t不回退，失败/耗尽不能绕回；重启绝不复用旧grant。不同worker的10位不同。三者结合Snowflake位拼接的一一映射，推出ID不重复。fence不是位的一部分，mono/UTC偏差阈值也不是区间不交叠的替代证明。

这是有前提的设计推导，非机器证明或已运行故障测试。高水位恢复到旧值、两个可写DB谱系同时分相同区间、克隆已初始化内存状态，都能构造重复；这些明确反例不能藏成“无限高可用保证”，详见E09。

## 8. 持久性、迁移、恢复与运维代价

MySQL需单一权威写库、InnoDB提交可靠落盘；建议后续部署校验innodb_flush_log_at_trx_commit=1、启binlog时sync_binlog=1及存储兑现flush。**不在本阶段改这些配置**；本机持久性参数不自动保证任意异步副本零丢失，故障切换必须证明所有已确认H还在。官方来源见证据表。

新增表属于技术Schema变化，后续须由指定Owner审DDL/迁移、最小更新权限、备份/恢复演练、根POM/模块与boot装配。首次不能假定空库：盘点已有Snowflake生成器、worker映射、历史数据/未落业务表的发号及未用预留；仅查业务表max(id)不是充分上界。未能证明worker从未使用或取得可信旧高水位，禁用该worker，不能初始化H=-1碰碰运气。此次不分配生产worker值。

H/fence记录禁止DELETE/TRUNCATE/回退、不得重建主库后从0启动。灾难恢复若可能丢H，先隔离所有旧生成器和一切授权写入口；核实原持久记录/授权日志等可信上界与DB谱系，再以不低于所有旧授权上界恢复H并按新incarnation取得更高grant。若无法证明最大旧预留上界，保持停发并升级恢复决策，不能用当前UTC或业务max(id)推断安全。**fence更大也不能修复丢失的H。**

禁止复制/热恢复包含已初始化provider内存的VM快照/进程checkpoint，两个副本会复制同grant+sequence；普通DBfence和nanoTime不能检测共用凭据的克隆。合法机器重启须全新JVM状态；若运维必须恢复快照，要在恢复时禁止旧进程执行并销毁原内存状态、验证DB谱系后冷启动新incarnation。本设计不声称支持透明活进程克隆。

容量代价：正常每实例约2秒一次续租、约4秒一次扩窗（可合并）；真实吞吐和DB负担后测，不写QPS保证。废弃的是编码空间中的时间/序列，不是删除业务事实；允许空洞。DB/时钟不安全时返回不可用，需告警worker/fence/窗口剩余/失败原因，不记录敏感业务内容。

## 9. Clock 装配与 PLAT-004 精确交接

只读44dc的`JdbcAsyncTaskRepository(DataSource,SnowflakeIdGenerator)`明确非null provider；`claim(String owner, Duration leaseDuration)`在拿task锁前调用nextId分配attemptId，发号失败不会持有领取事务锁（本次读取c641794）；该流程允许废弃未用attempt ID，不能要求发号回滚复用。`AsyncTaskWorker`显式接收java.time.Clock，任务DB NOW决定lease/重试，Clock只供Handler context。

后续推荐pet-id-core的生产实现实现既有接口；MySQL授权使用自己的基础设施连接/短事务，不能加入调用方业务事务、不能把发号区间分配回滚随业务回滚。仅boot装配受资格检查的Clock.systemUTC、业务Zone配置和单例provider，再注入task-core；缺配置/未初始化表/不可用许可时Worker保持未启用或停止领取，不注册固定ID兜底Bean。common纯codec不因此变HTTP/auth组件。

| 后续Owner | 精确交接内容/门禁 |
|---|---|
| PLAT-002 Backend Core | 获批设计版本→pet-id-core具体文件/配置/表迁移提案/生产ID实现与生命周期；根Work登记root POM、boot及Schema文件唯一Writer后再写 |
| QA | 在原Issue中获独占测试文件后做MySQL两进程/暂停点/ACK未知/重启/恢复/内存复制拒绝等真实测试；当前只有规范例子 |
| PLAT-004原Owner | 固定c641794需求为输入，不在task-core实现第二套ID。与S2固定实现提交集成后验证attemptId、无锁失败、Clock不改DB租约；producer/告警/reconciliation等其他缺口仍在原Issue |
| 三端 | ID全程String；时间/状态/actions/权限读显式业务字段；生成不可用不能造ID/伪造成功/自动换requestId |

批准设计不自动授权或完成这些代码。完整Worker生产DoD须S2真实提供器/boot装配/故障证据和PLAT004自身全部AC，不能以本CCR文档或原Worker测试替代。

## 10. 当前验证与审批出口

本阶段仅结构/来源检查、边界算术和独立只读审阅；[时序例子](s2-examples.md)是评审样例，未实现发号器、未建表/改服务/改环境，未运行生产唯一性或MySQL灾难测试。已有CI只说明文档没有破坏已有基线，不证明S2算法已通过实际运行。

提交一个设计决定包：推荐A及§4参数/§3模块和逻辑表/安全门禁。若批准，下一步由根Work安排权威同步及具体实现/迁移审查；若提出替代选择，在本CCR修订，不新拆Issue。本CCR仍非RESOLVED、完整PLAT002非DONE，通用幂等物理表与业务迁移没有被本包审批。
