# Hutool 适配评审例子 v0.2

状态：**SDK_SELECTED / ADAPTER_PROPOSED**。对应[当前设计](s2-id-provider-design.md)。本页替代d5acc3a自研算法例子作为PR13当前候选；旧版本仍从Git读取。以下都是规范推演，不是适配器/实际SDK冻结/数据库恢复测试。

## H01 显式构造参数与S1接口

node=71 → dataCenterId=2、workerId=7；node=1023 → 31/31。恒等式(dataCenterId<<17)|(workerId<<12)=node<<12，因此与原10位node兼容。原epoch相对毫秒、12位sequence仍由SDK拼接，适配器不重写。

构造显式epoch=2026-01-01T00:00:00Z、useSystemClock=false/timeOffset=0/randomSequenceLimit=0。反例：默认epoch2010、默认node/IP取模、容忍回拨2000ms、每次调用new SDK均不合格。根任务8线程单实例100000 ID实验只为原SDK有限证据，不是本适配器并发验收。

业务只收SnowflakeIdGenerator返回的合法Long，再由PublicIdCodec转String。库的Snowflake对象、Future、SDK异常类型不进HTTP/API/TS。node/时间位不成为门店/权限/订单状态依据。

三端超时/结果未知时不得本地造业务ID、伪造成功或自动换requestId重投，完整边界见设计§8；SDK选型不改变原意图的重试与当前授权要求。

## H02 正常候选与后验拒绝

逻辑示例：node17已确认grant=[100000,104999]，SDK内部候选t=100123、seq8、id=419946369032。调用100ms内返回，实际OS UTC前后采样合法且与t相容、token当前、未超时/关闭、id>lastPublishedId → 唯一成功仲裁并发布。数字为位布局算术样本，不是本轮执行SDK生成。

重复或小于lastPublished的候选、错node、0/负数、decoded t<L或t>U、实际OS UTC超41位/回拨异常 → 不发布并FAIL_CLOSED；不能修补位、自增候选、反射改sequence或在失败后new SDK。

SDK内部genTime可能跨过U：外部调用前看到104999不等于内部仍看到104999；若候选105000，不允许先交业务再补U，不事后扩窗追认。保持“已发布范围不重叠”，不声称SDK连内部临时值也不会生成窗外ID。

## H03 一秒成功/失败仲裁与迟到结果

同一token在999ms候选就绪与1000ms截止附近竞争：原子仲裁只确定一个结果。超时先胜则provider关闭、操作FAILED；1001ms候选、迟到DB续租ACK都不能复活或走旁路回调。成功先胜则保留同一成功结果供caller观察，不能另一路同时报该操作超时。

终态路径不得等SDK monitor或可能由暂停线程持有的发布mutex。仅说“短锁”不证明预算；实现期必须有明确非阻塞CAS/等价仲裁及竞态测试。所有等待从入口共用1秒，不是排队1秒后再等SDK1秒。

未获得串行通道、尚无SDK/不明授权操作的另一个caller，预算内未入场即返回本次不可用，不能往后台塞任务；它的竞争失败不必杀掉仍正常执行的当前操作。SDK已入场后的超时则触发整provider终态。两种分支需单测区分。

线程也可在最终时间检查和成功仲裁/返回之间暂停。若终态先胜则晚候选必丢；若成功先胜，在途返回可迟到，沿用不可调度暂停的原边界，不承诺任意真实时点后绝无旧返回。

## H04 SDK等待不返回与资源恢复

源码场景：lastTimestamp等于当前系统毫秒、sequence将绕0，tilNextMillis持续观察相同毫秒。该private循环无deadline/interrupt判断，所以Future.get超时或cancel(true)之后SDK可能仍占CPU。

适配器在预算内将已入场操作/整个provider终态失败，停止接收SDK任务及后续续租；只可能留下一个SDK工作线程，不能每个重试新造线程/SDK。外部调用失败不等于底层停止，指标应明确SDK线程未退出、provider不可用。

恢复：宿主确认旧JVM实际退出后，冷启动新JVM、唯一SDK实例、从主库H之后取得新grant。lease过期/Future.isCancelled/线程interrupt/连接断开都不是退出证据；确认能力未部署则保持not-ready。不能在原JVM重建通道来掩盖一秒资源退出差距。

用阻塞调用替身可测试控制层不积压/迟到丢弃，但不等于真实Hutool时钟冻结实验。本轮没有这样的实现测试或宿主进程操作。

## H05 MySQL ACK未知、重启与接管

旧grant=[100000,104999]；扩窗D=104100拟newU=109099并续lease到114100。ACK未知→provider终止，旧窗也停用。DB可能后来commit；停续租不撤销已有在途事务，晚ACK必须丢弃且H不得回退。

若实际commit，新JVM在旧进程退出、锁/lease条件满足后读H109099；例如D114101，新L114101/U119100。若回滚H仍104999，D110001时新L110001/U115000。不能只读到“owner相同”就恢复旧SDK/序号；不能从副本猜失败。

旧SDK迟到返回只能是内部候选：旧token已FAILED，不进入业务。如果旧操作此前已成功仲裁而返回迟到，其t≤旧H，新进程发布t>旧H，仍不重复。fence只阻止旧授权续留，不声称杀死旧内存；新SDK必须遵循新区间，根已证明直接同node new实例可重复。

## H06 真实OS时钟与SDK解码掩码

SDK genTime=false分支使用System.currentTimeMillis；注入Clock.fixed只控制外部/业务测试，不能冻结它。实际OS UTC调用前后必须处于epoch起41位毫秒域内，并与候选时间相容，无观测回拨/非法跳变才可发布。

反例：OS跳过2^42毫秒后，SDK左移截断及getter的41位掩码可能让解码看似回到合法t；所以“正Long+decoded t在窗内”不是时间有效性充分检查。还需独立OS取时验证与id>lastPublished。有限采样不能证明未观测到的瞬时钟跳变不存在，不把采样守卫当全世界时钟正确；发布唯一性仍以不交叠grant及严格单调输出为核心。

timeOffset=0检测回拨会抛异常，按本包关闭provider；不恢复其lastTimestamp/sequence。冷启动新grant仍等待实际UTC合法追上，单次不超1秒。epoch/node0候选0直接拒绝，不复用旧自研“跳过序号再拼位”的分支。

## H07 H丢失与热克隆仍会破坏安全

A仍有[1000,5999]，错误恢复MySQL H=1099且owner空；D=1500，B获得[1500,6499]。未隔离的A/B同node都可能发布1500/seq0 → 重复。Hutool替算法不能修复该协调事实丢失；必须单写/不丢确认H、恢复前隔离并验证可信上界。业务表max(id)不包含未用授权，不能代替H。

将SDK与适配器/lastPublished一起热克隆，两副本拥有同grant和发布状态，仍可能各自通过自己的单调性检查。Serializable不能作恢复机制；禁止暴露/序列化恢复SDK、活内存克隆，合法恢复是旧JVM确认退出后新状态+新grant。

## H08 预算算术与当前范围

沿用逻辑参数W5000、R1000、T10000、renew2000、G1000、S250、maxAhead10000毫秒（协调初值待审）。单次B1000已接受，不调整。若L未来4秒，单次1秒失败/WARMING而非提前生成；同一待用grant不反复抬H。U-D>10000的申请不修改H。

mono使用同JVM差值；m0=Long.MAX_VALUE-500000000，500000001ns后now=Long.MIN_VALUE，有符号差值仍500000001，小于B1000000000。不能比较易溢出的绝对截止，间隔≥2^63ns/时基异常拒绝。此处仅整数算术，不是SDK性能或调度保证。

## H09 后续验证清单及不可冒称

可复用S1接口/String/Money/Context测试和PLAT004原DB lease组件证据，仅其原范围。新增适配必须另测：参数与SDK实例封装、合法候选/拒绝候选、发布仲裁、唯一执行通道无积压/不补线程、迟到SDK/DBACK丢弃、真实JVM退出后恢复、主库H/授权故障与克隆禁用守卫。

私有时间不可注入和不可取消等待须制定适用的隔离验证方法；反射lastTimestamp注入只是SDK有限分支实验，不能冒充真实OS回拨/重启灾难。当前本页全部是规范样例，新增适配/生产唯一性/MySQL灾难/底层截止测试均NOT_EXECUTED；CI不改变此事实。
