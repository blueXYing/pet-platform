# 公共 ID / Clock / 金额 / Context 与 PLAT-004 交接 v0.1

当前阶段：**CONTRACT_SYNCED_IN_PR / S1_REVIEW_READY / S2_PENDING_DESIGN**。已批准方案及PR10已合入develop，本阶段实际源码/测试、23号权威补充及PLAT-004条件交接见[s1-handoff.md](s1-handoff.md)。下文保留原草图/批准时历史状态；“尚无源文件”“PR10未授权”等不描述当前S1。生产worker/高水位/数据库幂等与未定设计仍未完成，CCR不RESOLVED，Issue不DONE，新阶段PR未合并。

状态：**PROPOSAL_ACCEPTED / PENDING_CONTRACT_SYNC**；[所属CCR](../CCR-W2-IDEMP-001.md)。2026-09-14人工“接受两项建议”，已接受`a9856c14fd596304611496cf794c935ec4d1243e`版公共ID/Snowflake、Clock、金额及数据库幂等方案和失败/旧回执澄清，未新增技术决定。下面接口只在Markdown中表达，不是Java源文件，不是实现或装配。

历史状态：PROPOSED / PENDING_REVIEW。以下原0.1版正文不变，“待批”等保留当时措辞，当前两项方案接受状态以主文回执为准。权威Contract尚未同步，具体worker独占/高水位等原未定设计、DDL/迁移仍待审，组件/真实DB验证未完成；CCR不RESOLVED、Issue不DONE，PR10合并与后续任务未授权。

## 1. 已定约定与现状

| 能力 | 已定约定及来源 | 实际基线 |
|---|---|---|
| ID | T05 §3.1：主键/订单号/退款号Snowflake BIGINT，Java Long，API/JSON/TS String；I07 §1/2同样适用内部API | common无ID提供器；前端工程样本仅透传String |
| 时间 | T05 §3.2：MySQL DATETIME(3)，Instant/OffsetDateTime，ISO-8601，业务时区统一配置；I07 §2.3 API OffsetDateTime | task-core Context已有OffsetDateTime now，无Clock提供器 |
| 金额 | T05 §3.3：DECIMAL(18,2)、BigDecimal，比例DECIMAL(10,6)，禁止float/double资金计算；H10 §2.7 JSON定点String | 现有前端样本128.00，无公共金额解析/精度校验器 |
| Context | I07 §2.1：requestId必填、traceId追踪、operatorId String、source仅技术来源；OperatorType四种 | pet-common现有record与文档五字段一致，未做真实认证/幂等校验 |

技术基线没有指定Snowflake epoch、位分配、worker分配/回拨策略、业务时区具体值或公共Clock签名。**不能把以下建议标为现有要求。** 比例/积分取整不等同金额舍入；SSOT积分扣回四舍五入是特定业务规则，不能扩为公共Money四舍五入。

## 2. 固定接口草图（待批）

```java
// 仅 Markdown 契约提案，无源文件或可运行实现。
interface SnowflakeIdGenerator {
    long nextId();  // 正Long；时钟/worker不安全时失败，不降级随机数或本地计数
}
interface PublicIdCodec {
    String toApi(long id);
    long fromApi(String id);
}
interface MoneyCodec {
    BigDecimal parse(String fixedDecimal);
    String format(BigDecimal value);
}
// 时间直接注入 java.time.Clock；业务时区另注入配置的 ZoneId。
// Context 沿用已存在的五字段 record，不新增租户/工作区/权限字段。
```

建议位置为pet-common纯接口/转换约定；生产实现/配置装配由根Work在实现阶段按文件登记，pet-boot只装配。Long仅用于模块内部存储/生成，任何公开/跨模块DTO ID仍String。Codec遇非法ID/金额建议映射既有COMMON_INVALID_ARGUMENT；提供器安全性失败内部code到HTTP映射由原Owner登记，不伪造成功ID。

## 3. Snowflake 运行提案与未完成事项

推荐经典正64位布局：1符号位固定0、41位相对毫秒、10位worker、12位同毫秒序号；建议epoch `2026-01-01T00:00:00Z`，一经投入不得更改。同worker同毫秒最多4096个，序号耗尽等待物理时钟下一毫秒（有界预算），不能溢出或虚构未来时间；越界/早于epoch失败。不能从ID反推业务预约时间或作权限凭证。

worker建议由部署配置分配稳定0～1023身份并验证唯一。**仅静态配置不能证明多进程/重启唯一性**：实现前必须固定独占持有与重启高水位方案，证明旧进程被隔离、重启不复用已发区间。推荐每worker持久化预留时间高水位/独占凭据并在重启等待已预留上界后再发；存储介质、锁持有机制、隔离/fencing与预留窗口属实现设计待审，不在此假设已有服务或加表。

任意检测到时间回拨建议停止发号并告警，物理时间追上已用/预留安全边界且仍持唯一worker所有权才恢复。不允许自动换worker、退化UUID、忽略回拨或只依赖进程内lastTimestamp。不同worker可并发，ID仅保证唯一，不承诺跨worker全局严格顺序。建议发号等待预算1秒后失败，性能/预算是新工程参数待批，不能让线程永久卡住。

这组布局/epoch是明确推荐，实际provider及多进程抢占/回拨/重启验证仍未完成。具体独占/高水位机制未获审查前不得以公共草图声明生产ID可用；这是工程解除条件，不要求人工CTO另行设计技术方案。

## 4. Clock 与时间边界（建议）

直接采用可注入java.time.Clock，测试用fixed/offset等受控时钟；避免第二套TaskClock。业务时区用统一配置，推荐初始`Asia/Shanghai`，不可把环境所在地当既有业务裁决。内部事件时点以Instant计算，API用带偏移OffsetDateTime。建议DB session/读写适配统一UTC解释DATETIME(3)，业务展示用配置ZoneId；数据库DATETIME自身不携带时区，历史存量需核对再迁移，不能自动按UTC重解释。

持久时点精度到毫秒；公共写入建议拒绝有非零亚毫秒而未定义转换的外部参数，不擅自截断或舍入。业务分钟级预约要求来自SSOT，不能扩大成所有系统时间只有分钟。30分钟确认、24小时退款、改期及120分钟间隔保持原规则，统一Clock不改变边界比较符号。

S09/D13领取示例用DB NOW(3)：PLAT-004生产claim/lease到期比较继续以同一数据库时钟为权威，不混用多个Worker本地时钟决定接管。注入Clock服务业务判断/测试时点；明确记录读取的now并供现有TaskExecutionContext使用，不伪造DB lease一致性。若Worker拟改为统一应用Clock驱动DB比较，须另列S09/D13兼容审查和时钟偏差验证，不由本草图默许。回拨不能导致已提交成功事实退回未执行，计时预算用单调时钟，不把Clock墙钟用于elapsed duration。

## 5. 金额与 Context 校验（建议）

金额输入接受定点String：可选负号、整数部分无冗余前导零；不带小数点，或小数点后1～2位；例如`128`/`128.0`/`128.00`等值并规范为`128.00`。拒绝`128.`、空串、空白、指数、加号、JSON number及超过2位小数（包括128.000），拒绝范围外值。DECIMAL(18,2)绝对值上限`9999999999999999.99`。格式化仅补足两位，需舍入就失败；负数/零是否允许由具体业务schema决定，公共金额不替业务定价/退款资格。建议负零输入拒绝以减少编码歧义。这些词法/规范化规则是新增待批策略。

比例DECIMAL(10,6)、体重DECIMAL(8,2)、评分等有各自业务类型，不经MoneyCodec套同一规则。运算用BigDecimal，未定义业务舍入策略时交原Owner列CCR，不由公共层默默四舍五入。

requestId缺失/空白拒绝；终端UUID格式按H10，内部确定性串按I07/S09。traceId缺失服务端生成并回传（H10 §2.4）；是否必填operatorId/source及SYSTEM稳定标识由真实适配器映射规范固定，不能以客户端传入替代认证。建议非SYSTEM必须解析到已认证operatorType/operatorId，source若存在必须可识别且不用于权限。不得把校验器写成新的登录/角色/门店授权系统。

## 6. 给 PLAT-004 的分阶段交接

| 阶段 | 可获得什么 | 可声明什么 / 不可声明什么 |
|---|---|---|
| S0 本次固定文档提交 | 上述方法草图、Clock方案、约束、规范样例 | 可审查依赖；不是源代码接口或可运行提供器，不启动PLAT-004 |
| S1 另经派发的接口/测试替身交接 | 实际已审接口提交、明确标记的确定性测试替身、逐文件Owner释放 | 根Work解除阶段门禁后，可实现/验证不依赖生产ID的Worker部分；不能生产验收 |
| S2 生产提供器与装配 | Snowflake真实唯一worker/回拨/重启安全机制、Clock/Zone配置、固定实现提交和证据 | 完整Worker必须接入这些真实提供器，并通过真实MySQL多Worker/租约恢复测试；不可自建第二套ID |
| S3 完整Issue验收 | PLAT-004全部AC及TASK/W2-TASK测试，公共依赖实证 | 不以S0/S1代替DoD，也不要求必须等PLAT-002幂等整项DONE才开展已解锁部分 |

现有TaskExecutionContext仍是`taskId/requestId/traceId/OffsetDateTime now`；TaskHandler与Success/Cancelled/Retry/Dead结果不在本次改动。后续生成taskId/taskNo/attemptId用同一公共Snowflake提供器；requestId/task_key是确定性业务去重串，不能拿Snowflake每次生成新值。当前只交接文件与固定PR head，生产装配/任务实现/测试均未授权。
