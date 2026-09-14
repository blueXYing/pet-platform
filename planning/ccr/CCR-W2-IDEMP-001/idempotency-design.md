# 持久化幂等技术提案 v0.1

当前阶段：**CONTRACT_SYNCED_IN_PR / S1_REVIEW_READY / S2_PENDING_DESIGN**。本提案已接受部分已同步[23号权威补充](../../../docs/04-api/23-公共接口与幂等契约补充-v0.1.md)，本阶段新PR尚未合并；下文仍是原提案/批准时快照。S1仅纯接口/转换交付，DB幂等/旧key迁移/物理适配等原未定项未实现；详见[s1-handoff.md](s1-handoff.md)，不关闭CCR或完整Issue。

状态：**PROPOSAL_ACCEPTED / PENDING_CONTRACT_SYNC**。隶属[CCR-W2-IDEMP-001](../CCR-W2-IDEMP-001.md)。2026-09-14人工“接受两项建议”，严格对应`a9856c14fd596304611496cf794c935ec4d1243e`版本；批准范围和门禁见主文当前回执。本文件无可执行DDL，不改变权威Contract。

历史状态：PROPOSED / PENDING_REVIEW。以下原0.1版技术正文保持不变，“建议待批/待批解释”等为原审批前措辞；两项建议已接受，等待Contract同步。具体DDL/迁移和原文未定实现设计仍需后续审查；不表示组件已实现、测试通过或CCR RESOLVED/Issue DONE，不授权PR10合并或后续任务。来源缩写见[证据表](review-evidence.md)。

## 1. 持久化选型

| 方案 | 取舍与依据 | 推荐程度 |
|---|---|---|
| A 业务表唯一键+模块结果适配器 | I07 §21允许。不强制统一表；每个命令需证明主体隔离、不可变参数、第一次成功结果、失败绑定、同事务；D06现有字段不足以普遍成立，当前资源不能冒充历史首次结果 | 满足全部条件时允许替代 |
| B 独立DB记录+原业务约束 | I07允许幂等表；统一生命周期和比较规则。需要迁移、容量监控及持久化Owner；记录必须与被守护业务同库同事务管理器 | **公共默认推荐** |
| C Redis锁/结果缓存+DB事实 | 可优化热点，但Redis清空/失效后DB仍保证唯一、摘要和结果恢复；Redis-only不满足I07 | 仅A/B优化 |
| D 已提交RUNNING+租约再提交业务 | 会新增旧租约接管、fencing和业务已提交/记录未完成缺口；长操作可先本地受理再用现有durable task | 不作为同步命令默认方案 |

common只提供纯契约/规范化；持久化不放api，不跨biz访问Repository。实际适配模块及文件Owner由根Work在实施前登记，当前无新增模块授权。未来拆服务各事实Owner自守本地幂等，不使用中央跨库回执事务（I07 §1/20）。

## 2. Key、可信主体与摘要

### 2.1 唯一元组（建议）

`(commandNamespace, actorType, actorId, authorityScope, requestId)`：全部非空、明确字节语义，固定域值代替NULL。

- commandNamespace是服务端稳定业务动词，如order.create，不从URL或客户端自由字段取。普通DTO升级不换namespace；语义变化需原Owner迁移评审，不能通过换版本穿透旧key。
- actorType/actorId来自当前可信身份；authorityScope仅承载已授权的业务边界（例如merchantId/storeId）。C可用固定消费者域、Admin固定平台域。业务目标storeId仍入参数。不能从客户端operatorId/source/门店header自证身份。
- 不向CommandContext静默添加tenantId/workspaceId/command；五字段保持。作用域是适配器私有执行元数据，不是身份体系。真实认证映射等CCR-ACR-001/PERM-001；匿名登录/注册无可信主体时由AUTH原Owner定义专用域，此提案不造默认用户或SYSTEM后门。
- SYSTEM用受信适配器登记的稳定逻辑身份，不能用每次重启变化的worker实例号。回调先验签；事件使用eventId+consumerName；任务按S09 §37含generation的确定性requestId，attempt变化不能换key。
- H10终端写header保持UUID；I07内部确定性串不强制UUID。建议公共requestId最多512 UTF-8字节、无控制字符、区分大小写、不trim；这是新增待批限制。超过旧表64字符须显式映射/迁移，不能静默截断。

### 2.2 canonical-v1（建议）

先认证并做传输格式/大小基础校验；可信作用域内查已绑定key并选其canonical/schema版本，再做该版本静态校验与有效业务参数树；新key使用当前获批schema。不能先用最新DTO拒绝本应可兼容的旧参数。拒绝未知字段/重复JSON键。路径和查询中影响写入的资源ID、动作、理由/用途、目标和期望业务版本都入摘要，不能只hash body。排除requestId/traceId/Authorization/网络重试次数/纯技术source；可信主体在key中。技术source不能改变业务状态。

1. 对象键按Unicode码点排序，UTF-8无BOM、紧凑JSON、无尾换行；字符串用JSON标准转义，非ASCII原样UTF-8，不用可选斜线转义。拒绝孤立surrogate。数组保留顺序，只有schema明定无序集合才能排序。
2. 业务字符串原样，不trim、折叠大小写或Unicode归一化。null与缺字段不同，只有schema已定默认值才先补默认。
3. ID用正Long范围十进制String，不经JS Number；建议拒绝前导零、加号、指数。金额按公共草图校验转两位定点String，不舍入。时间依字段已定语义转UTC毫秒；无时区拒绝，不能为摘要擅自截断亚毫秒，偏移有业务意义时单列字段。
4. boolean为JSON布尔；其他数值按命令schema，不做通用浮点转换。非法输入不生成绑定。
5. 保存canonicalVersion、SHA-256算法标识及规范字节摘要；保存受保护的规范参数字节用于等值复核（或经批准等价加密存储），不凭hash相同就认定相同。加密随机IV不参与业务摘要。仅身份Authorization/传输层签名排除；verificationCode/couponCode等敏感业务输入仍须用受保护的语义等值材料参与比较，换业务码就是异参，不能因秘密而忽略。业务敏感材料最小化、加密、受限访问。
6. 已有绑定用其旧canonicalVersion解释重试，保留旧规范化器；不能重算覆盖。新版字段无法表达旧语义时拒绝兼容失败、告警，不以新版本另建key执行。版本不是key的一部分。

建议规范参数/回执各最多64KiB，超限静态拒绝；各命令schema和敏感白名单必须由原API Owner固定。没有获批业务DTO的命令不能由通用例子创造公开API。

## 3. 推荐 B 的并发与原子事务

**分开参数绑定与业务成功。RESERVED不是RUNNING，不是租约。**

1. 认证、当前动作权/数据范围初检、静态规范化；未授权者不能看到绑定是否存在、摘要、冲突细节或旧回执。查询权限失败关闭。
2. Admission短独立事务按唯一元组插入RESERVED与规范参数；已有则比较已提交绑定。只固定有效参数，不能hold资源、取业务guard或调用Provider。唯一冲突不是成功。应向权威主库读事实，不用滞后副本决定不存在。
3. Execution事务锁该记录，当前读重新检查状态/参数。RESERVED再核当前授权和业务资格，通过后本模块业务事实、成功最小回执和必要task/outbox意图同commit；回执序列化/写入失败也回滚业务。
4. SUCCEEDED只重新核当前身份、动作权及结果资源数据范围，然后重放首次成功业务回执；不再次执行业务，不重复检查已经消耗的“只能创建一次/改期一次”等执行资格。最新展示态另走查询（由order计算），历史回执不是最新资源。
5. 并发同参在唯一键/行锁上有界等待，建议总2秒配置预算；释放后用新事务/当前读确认已提交胜者，避免MySQL旧快照。预算耗尽建议`409 COMMON_CONFLICT`，`data:null`、提示用原requestId稍后重试；这是新映射待批，无新错误码/轮询端点。不能仅凭409自动重试：`IDEMPOTENCY_KEY_CONFLICT`必须停止并处理参数冲突。
6. 已可见绑定的异参立即冲突，不必等业务成功。先到者失败也不能由后者覆盖原摘要。DB不可用返回既有503依赖不可用，不放行写入。

Admission必须在无活动业务外层事务时完成，不能已做其他业务写入后用REQUIRES_NEW假装首次受理。Execution参与单个明确顶层本地事务，禁止REQUIRES_NEW提前提交成功；最终外层commit确认后才发成功响应。死锁/锁超时/连接失败须整笔回滚，不能catch后提交局部写。锁是执行排他权，RESERVED年龄不代表可以删除；同参下一次取得行锁即可重新尝试。

I07 §20/S09 §35不允许依靠跨模块大事务；执行闭包不得包含Provider HTTP或假设其他模块API写可整体回滚。跨模块流程可固化本模块可靠受理事实，但只有原命令Contract允许受理回执时才可作为首次成功；同步依赖外部结果的命令须先按§7补完整映射。必要后置事件/任务在原Owner事务持久化，各消费者分别幂等。Outbox字段实现仍待CCR-W0-001。

公共key不代替业务约束：不同requestId同一订单仍受业务唯一键、CAS与order_operation_guard。核销成功与退款创建互斥、核销使当前未履约售后失效、一单最多一张退款单均按SSOT，不得误写成已核销后永远不能退款。

## 4. 失败、重试与结果重放

I07 §21原文“返回第一次成功结果”；H10 §2.3原文“返回第一次处理结果”。下表是**待批解释**，尤其业务拒绝后重试，不是当前已冻结语义。

| 情况 | 推荐语义 |
|---|---|
| 未认证/静态非法 | 不建立绑定、不缓存错误，避免占他人key；修正后重新验证 |
| 已绑定、业务拒绝/技术失败 | 回滚业务但留RESERVED+原参数；不固定失败回执。同参重试重新检查，可再次拒绝，也可因业务事实已合法而成功；异参须新意图新requestId |
| 成功后HTTP丢失 | 数据库SUCCEEDED与业务同commit，原key同参取首次成功结果 |
| Admission或Execution commit ACK未知 | 新连接查权威主库原key；成功就重放，RESERVED获锁才执行，DB不可达503；不能认定失败、删记录或更换key重做 |
| 同key异参 | 已绑定即409 IDEMPOTENCY_KEY_CONFLICT，即使首次业务失败；不覆盖绑定 |
| 撤权/会话失效 | 401或403/既有防枚举404，不含旧data；成功记录不删除，不以旧成功恢复权限 |
| 结果版本变化 | 保留resultVersion解码器；不以新DTO重执行业务；无法兼容时503依赖不可用并告警，须解决兼容才可完成重放验收 |

H10 §10已有首次创建201、更新/幂等重复200；第一次业务结果稳定，不要求字节级HTTP原包。当前traceId重新返回；中文message可变，三端按code判断。稳定回执只保存必要业务事实/结果ID，不保存当前actions作为未来授权。

## 5. 保留、敏感结果与权限

建议V1**不自动过期删除绑定与成功最小回执**，RESERVED也不能到期变成新key。不凭空设24h/7d TTL。可归档但查重仍读归档，容量告警；T05 §3.6禁止物理删除的交易事实仍保留。此新增技术保留方案待批，不等于所有敏感原文无限留存。

每次重放必须核当前身份、动作授权及结果资源范围，按当前脱敏策略呈现。不能缓存token、完整渠道payload/密钥、临时导出URL/临时访问能力。敏感查看用途按已定权限规则处理，不新增第二人审批。前端切账号/店必须丢弃旧展示/重试队列，但客户端隔离不是后端权限证明。

敏感载荷确需清理时先证明最小回执可兼容重放；保留不可重用的key墓碑/最小成功事实。不可合法重放则拒绝读取，版本或归档设施不可用503并告警，绝不重做业务。不能先删后把长期503当作完成重放验收。墓碑/载荷清除都需后续保留策略审核，没有当前自动清理授权。

权限初检及回包前复核都要执行。并发撤权如何封闭授权快照竞态由AUTH最终契约规定，当前不能宣称微秒级撤权保证已实现。幂等层不签发令牌、不创建身份关系、不按角色名称放行；真实AUTH/安全测试仍BLOCKED。

## 6. 逻辑数据/约束（仅提案）

| 字段组 | 不变量/约束 |
|---|---|
| recordId | Snowflake正Long，公开传输String；不默认公开API |
| commandNamespace/actorType/actorId/authorityScope/requestId | 非空稳定元组唯一，二进制比较，不能受D06默认大小写/重音折叠影响 |
| keyDigest+完整元组 | 可摘要定位，但必须完整元组碰撞复核，碰撞拒绝/告警不得覆盖；长复合索引物理布局受MySQL索引限制，待迁移设计 |
| canonicalVersion/digestAlgorithm/parameterDigest/protectedCanonicalBytes | 绑定后不可变，保留解码/加密版本，hash相同仍等值复核 |
| state | RESERVED/SUCCEEDED，仅执行成功事务能转SUCCEEDED，不可退回；不持久化伪RUNNING |
| resultVersion/resultCode/protectedResult/resultResourceRefs | 成功必有可解码最小业务回执；RESERVED无成功结果；结果引用服务当前数据权限，不暴露Entity |
| boundAt/succeededAt/updatedAt | 毫秒时间；succeededAt仅成功有值，不充当业务截止时间 |
| retentionClass/archivedAt/payloadErasedAt | 描述已批准策略，不授权定时删除；归档/墓碑仍查重 |
| originTraceId | 仅审计关联，重放响应用当前traceId |

存储层唯一性加适配器状态配对校验，不能只有“先查再写”。本稿不固定表名/列宽/索引DDL，不新增可执行迁移。

## 7. Provider / Event / Scheduler

MySQL与外部服务不能本地原子提交。仅对**原命令Contract本就允许可靠受理回执**的命令，推荐先持久化本模块受理意图+本地回执+必要task/outbox，再由原Provider适配器用稳定渠道业务号调用或查单。此时SUCCEEDED可表示“可靠受理退款”，绝不自动等于渠道退款到账。PROCESSING/UNKNOWN按业务契约返回，不误作500。

H10 §3.6 L393～406的createPayment成功回执包含wechatPayParameters，不能被本通用方案换成“已受理”，也不能无限重放过期支付参数。依赖同步Provider结果的命令，由原Owner提出意图持久化、未知查单、外部结果后的本地成功提交，以及临时参数更新/失效/敏感返回的分阶段映射；**未映射并获批前禁止接入公共成功重放**。本稿不新增202、不修改终端成功响应、不把Provider依赖偷偷移到异步。短期参数无法直接缓存不等于删掉原Contract必需字段。

S09 §18/19：Provider超时不等失败；保持原paymentNo/refundNo查询。仅ChannelCapabilities已明确支持同业务号重放时才原单重提，禁止新号重发/第二张业务退款单。渠道幂等有效期/能力在真实对接前按官方资料验证，本次不选Provider或虚构能力。公共幂等不替代渠道幂等、验签、独立交易流水。

E08 §15 `(eventId,consumerName)`日志与本模块业务同事务；部分消费者成功后不得重做它们。公共命令key不替代eventId；一个事件多个业务命令用各namespace区分。S09 task_key/generation、旧lease/version防覆写仍由PLAT-004负责；任务重试不换requestId，公共记录不接管Worker。

迟到支付保持订单关闭、按渠道真实paidAmount全额退款，不走正常OrderPaid/30分钟确认/重新占资源链路。CCR-W0-002/003及资金基线继续限制对应实现。

## 8. 迁移/API/Event影响与门禁

| 对象 | 现状与拟处理 | 当前限制 |
|---|---|---|
| 公共持久化 | A/B都要补完整映射，B建议新技术记录 | 先批准CCR，再登记模块/文件Owner，审具体索引、容量、加密及迁移 |
| refund_application | D06 L411全局`uk_refund_application_request(request_id)` | refund Owner拟作用域复合唯一/派生key迁移，不改一单一次退款 |
| verification_record / verification_attempt | D06 L474/L488全局request_id唯一 | 分别迁移；失败attempt不能冒充成功回执，保留一次核销/guard |
| coupon_ledger / points_ledger | D06 L606/L637全局request_id唯一 | 原Owner迁移，不换key重复发券/积分 |
| payment_transaction / refund_transaction | D06 L384/L452已有业务ID+requestId+action；request_id 64字符 | 仍需作用域/长度验证；渠道action不等同终端提交 |
| order_status_log / pet_order等 | request_id仅普通索引L320；其他业务唯一只保护业务事实 | 无通用摘要/结果，不宣称原Schema直接完整支持 |
| D13 | task_key、(task_id,attempt_no)已定 | 无本次改动，ID/Clock分阶段交接 |
| I07/H10/OpenAPI/Error12 | 五字段Context不变；需澄清失败、busy、重放权限/外壳/保留/版本 | 保护文件未改，CCR-W2-API-001各域Owner更新契约和Smoke；现有16操作不代表新契约验收 |
| E08/S09 | 无新增事件载荷/任务状态/重试上限 | 本地意图原子性后续验证，Outbox字段仍等CCR-W0-001 |
| 三端 | UUID保留、金额/ID String、按code处理错误、切主体隔离响应、当前权限重放 | 真实AUTH/页面仍按原Issue门禁，不凭工程fixture放行 |

旧全局request_id约束不经迁移会阻塞跨主体相同key，新增B表不能自动解决。技术建议优先由业务Owner显式补作用域列/复合唯一；若用固定长派生key适配64字符，须有带版本/主体/命令的无歧义编码、碰撞检测和原requestId审计映射，不能只截断或把散列当渠道号。本CCR不代替逐模块DDL评审。

实施前盘点真实存量与可恢复原参数/首次结果，不能用当前工程壳推断生产空库。历史无法还原摘要时设置LEGACY保护路径，拒绝盲目重做；逐命令切换，防止新旧算法同时写穿唯一性。回滚也必须识别已写新key，不能回到忽略新幂等事实的旧实现。迁移、回填、灰度、回滚、真实MySQL验证均未执行。
