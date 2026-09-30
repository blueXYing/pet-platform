# AFS 工作流独立 QA / 安全验收矩阵

日期：2026-09-30。角色：独立 QA / 安全验收。阶段：契约与测试准备，**全部 AFS 功能用例 NOT_EXECUTED**。

工作树：`C:/Users/Administrator/.codex/worktrees/refund-aftersale/宠物平台V1.0`；分支计划：`codex/aftersale-workflow-20260930`。基线由主执行者指定为 PR96 合并提交 `ff983596cad899c7b4e1ce9c8278228787ca7ac6`；CI `36668953557` 的实际报告由主执行者单独核对。本文件不将基线 CI、旧准备文档或源码中的断言当成本批运行结果。

## 1. 权威来源与适用边界

- [AGENTS](../../../../AGENTS.md) 与 [WORK_EXECUTION_PROTOCOL](../../../../WORK_EXECUTION_PROTOCOL.md)：不扩 Scope，Contract 缺失走 CCR；测试通过才可完成。
- [SSOT](../../../../docs/00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md) §5–8：首次终局裁决、两类七天资格、未履约售后与核销/退款互斥、一单一次退款；§9–11 为退款成功下游规则；§24/25 为单运营及取消 MFA；§37–39 为真实核销、普通退款来源及区别。
- [运营权限补充](../../../../docs/01-prd/22-运营权限人工裁决补充-v1.0.md)、[取消 MFA 补充](../../../../docs/01-prd/24-取消MFA人工裁决补充-v1.0.md)、[普通退款重复申请补充](../../../../docs/01-prd/30-普通退款重复申请人工裁决补充-v1.0.md)。普通申请重试/重开规则不得自动套用于 AFS。
- [Contract48](../../../../docs/04-api/48-Verification-Completion-Contract-v0.1.md)、[Storage48](../../../../docs/03-database/48-Verification-Completion-Storage-v0.1.md)：真实 OWNER 核销、同源事务和不可变证明、当前未履约售后原子失效。
- [Contract49](../../../../docs/04-api/49-Refund-Application-Contract-v0.1.md)、[Storage49](../../../../docs/03-database/49-Refund-Application-Storage-v0.1.md)：真实普通申请/拒绝、三阶段事务、原付款来源、退款原号恢复。
- [Scheduler09](../../../../docs/06-scheduler/09-Scheduler-Retry-Compensation-v0.5.md) §33已明确售后申请截止包含等号；查询和创建实时计算，不为资格关窗新建任务。
- [公共幂等契约23](../../../../docs/04-api/23-公共接口与幂等契约补充-v0.1.md)、[私有素材契约31](../../../../docs/04-api/31-Private-Asset-Contract-v0.1.md)。31 当前只有商家材料/服务封面用途，现有私有读取授权也是商家审核语义，**不能直接视为已经授权 AFS 证据上传/引用/读取**。

此矩阵是验收约束，不替代正式 AFS Contract/Schema/Event/Scheduler，也不自行冻结接口名、状态枚举、错误码或新增权限码。主执行者把已批准范围映射到正式契约后，再将下列场景绑定具体测试。待定项仅阻断依赖该项的实现与验收；已明确的不变量可继续准备。

## 2. 待裁决参数与绝不推导的边界

| 参数 | 已明确的基础 | 待确认内容及参数化用例 |
|---|---|---|
| D1 工单粒度 | 首次裁决终局；核销使当前未履约工单失效后允许服务完成后的新问题 | 同单同一问题识别、多个不同问题是否可并行、活动工单上限；为每个获批组合设置创建/并发/旧指针用例，不能从现有单指针直接推导产品结论。 |
| D2 撤回/重开 | 核销自动失效是既定行为；无复审 | 用户能否主动撤回、撤回后是否可新建及期限、非退款终局后新问题是否可申请；状态表存在 WITHDRAWN 不代表入口已获批。不得恢复失效旧工单或以重开绕过终局。 |
| D3 普通退款并存 | 普通 PENDING/已批准未建单可核销；一旦存在 refund_order 禁止后续核销和新普通申请 | AFS 与普通待处理/已批准申请的并存、创建/裁决优先级、旧拒绝被后续普通轮次覆盖的资格；分别参数化创建、决定、建单三阶段竞争。 |
| D4 七天精确边界（已有正式契约，不待裁决） | VERIFIED 从真实 verifiedAt 起；UNVERIFIED_POST_START 从 appointmentStart 起且须真实商家拒绝 | Scheduler09 §33明确包含截止：UTC毫秒 `t0 <= now <= t0+7×24h`。t0、截止前1ms、恰好截止允许，截止后1ms拒绝新申请；锁内读取当前时间实时计算。补证超时是独立机制，其任务在now>=supplementDeadline触发，不套用此申请资格比较符。 |
| D5 证据与补证 | 商家可提供说明/证据/意见，运营终裁；证据必须保护归属和当前权限 | AFS 专用 purpose、支持类型/数量/大小、用户与商家互见范围、运营读权限/用途原因、补证轮次与进入/退出状态；复用31的底层能力不直接继承其领取人/版本语义。 |
| D6 非退款与部分退款落地 | FULL/PARTIAL/驳回/重新服务/其他非退款是允许的裁决种类；部分退款后业务结束且不能再 AFS | 非退款终局对 ORDER 投影、重新服务是否仅记录处置而不新建预约、部分退款后预约释放及评价/积分/券下游本批交付边界；不自行新增免费订单、改期次数、重复核销或第二笔退款。 |

D1–D3 由主执行者向用户询问；D4遵循正式Scheduler09 §33包含端点；此前半开技术建议已撤回，不另设产品问题。其余精确字段/安全协议优先通过现有上位来源和 CCR 澄清，涉及新增产品规则再请求裁决。矩阵状态 `BLOCKED_DECISION` 只用于尚未裁定的预期，不表示所有 AFS 工作停滞。

## 3. 真实来源主链路与验收证据

主链 A：真实 C 会话 → 正常订单/真实成功付款 → 商家确认 → 到预约开始 → 真实普通申请 → 当前真实 OWNER 拒绝（含原因）→ 本人真实私有证据资产 → 真实 AFS 创建 → 当前 ADMIN_WEB 会话受理/要求补证 → 本人或真实 OWNER 按授权补证 → 运营首次终局决定。

主链 B：真实 C/OWNER 会话 → 正常订单/付款/确认 → 真实核销凭证 → 真实核销完成 → 本人证据 → VERIFIED 类 AFS → 受理/补证/终裁。已核销售后不额外强加普通退款先被拒绝的条件，除非后续上位裁决明确改变。

主链 C：主链 A 在退款单创建前真实核销 → 原未履约工单 INVALIDATED → 原工单不能退款 → 以服务完成后新问题和真实 verifiedAt 创建新工单 → 原核销首回执仍可合法重放且不改新工单。

每条链保存脱敏关联：用户/运营会话来源、订单/付款/普通申请及决定/核销/资产/工单/终局决定/退款/任务/事件 ID、版本、受控时间、首回执摘要、实际数据库不变量和渠道调用计数。不得打印 bearer、密码、密钥、证据原文/对象 key 或读授权 token。协议替身必须在报告中写明（支付渠道、微信上游、对象存储、扫描器等），不得声称生产支付/OSS/真机已经验收。

## 4. 验收矩阵

状态约定：所有行初始 `NOT_EXECUTED`；带 D1–D3/D5/D6 且确未封板的条件分支为 `BLOCKED_DECISION`（D4已由正式契约明确，不阻断），裁决后转 `NOT_EXECUTED`。执行时逐行登记测试方法、提交 SHA、命令/报告、结果与风险；现有源码仅是可复用证明设计。

### 4.1 资格、生命周期与终局

| ID / 优先级 | 前置与操作 | 独立验收标准 |
|---|---|---|
| AFS-01 / P0 | 主链 A，真实普通拒绝后创建 | 工单来源绑定正确订单/用户/商家/门店及真实拒绝决定；订单当前工单与状态一致，历史拒绝不改；创建不产生 refund_order，不释放预约、不禁核销。 |
| AFS-02 / P0 | 主链 B，真实核销后创建 | 来源是不可变核销成功事实和 verifiedAt；不能凭订单裸 COMPLETED、客户端时间或伪核销行获得资格；核销前后源阶段不串用。 |
| AFS-03 / P0 | 两类 t0 边界按 D4；商家晚于预约开始才拒绝 | 未核销用 appointmentStart，拒绝/补证/受理不重置七天；已核销用 verifiedAt，不能沿用早先预约窗口。固定UTC毫秒：t0、deadline−1ms、deadline允许，deadline+1ms拒绝；到期前锁等待到期后按锁内当前时间判断。 |
| AFS-04 / P0 | 未到预约、无拒绝、PENDING普通申请、错误买家、跨店/商家、取消/迟到付款订单 | 未满足真实资格的来源拒绝；普通申请并存按 D3；迟到付款不得恢复履约或借AFS重新出款。拒绝请求不制造工单/指针/事件。 |
| AFS-05 / P0 | 创建→受理→待补充→补证→终裁，重复/逆序命令及旧版本 | 每步仅允许正式状态转换；补证保存归属、原证据与历史，不能覆写此前证据；旧版本/已终局命令不修改当前版本、决定或金额；轮次/角色按 D5。技术CCR候选：只有锁内now<supplementDeadline的指定方可满足当前轮；deadline等号/后1ms均不可满足，即使timeout worker尚未运行。timeout在now>=deadline回PROCESSING；后续一般追加只遵循当前状态，不把过期round改成及时完成。测试−1ms/等号/+1ms及任务先后两序，当前仍NOT_EXECUTED。 |
| AFS-06 / P0 | 获权单运营作首次决定；其他运营/本人再决定或换key重开 | 一份终局决定；无需第二人审批或MFA。商家意见不能变成决定；无复审。D1/D2批准的新问题与旧问题复审分开断言，不能用新key绕过。 |
| AFS-07 / P0 | 主链 C，新 VERIFIED 工单后重放旧创建/受理/决定及原核销 | 旧未履约工单保留原证据且不可退款；新工单合法关联新问题，窗口从核销起；旧首回执不覆盖新指针，不因合法后续工单误判历史核销证明损坏。 |
| AFS-08 / P0 | 真实 FULL、PARTIAL；金额0/负数/超本金/超精度/等于全额/缺失 | FULL取真实正常本金；PARTIAL由有权限运营终裁且金额关系依正式合同严格验证（部分金额必须低于本金）；金额BigDecimal/DECIMAL精确，无客户端自报付款。等于本金不静默冒充PARTIAL。 |
| AFS-09 / P0 | 真实驳回、重新服务、其他非退款终局 | 无退款单、渠道出款任务或金额扣减；终局、原因、操作者、历史与ORDER投影一致。依D6核验后续履约，不自行复制订单/恢复码/增加核销次数。 |
| AFS-10 / P0 | PARTIAL最终渠道成功后再次退款或售后；核销/未核销两组 | 一单一次退款硬约束；不得新AFS。已核销仍保留评价资格及原verifiedAt起30天、不计星级；未核销不可评价。未交付评价消费者时标明合同核对/集成待验，不能宣称端到端完成。 |

### 4.2 会话、RBAC、证据与隐私

| ID / 优先级 | 操作 | 独立验收标准 |
|---|---|---|
| AFS-11 / P0 | 真登录后的本人提交；伪operatorId、过期/退出/冻结用户、他人requestId重放 | 身份来自当前会话并核验真实资源归属；所有命令及首回执读取重放均重验，失败不泄露他人结果。常量allow/fake SessionCache测试不能替代这条主验收。 |
| AFS-12 / P0 | 真实运营登录，分别撤销动作、角色、CITY/MERCHANT范围、会话和generation后重放 | 受理/补证要求/终裁/查询/证据读取按当前动作和服务端资源范围校验；旧permission snapshot、旧首回执无授权效力；SUPER_ADMIN仍不能绕过资金和终局硬规则。 |
| AFS-13 / P0 | 外层旧RR快照后并发撤权；入口通过后、锁等待期间撤权 | 最终执行和结果读取观察当前权限；定义与撤权事务的串行化点，不能仅入口查一次。真实提交前撤权导致拒绝，已提交合法资金承诺的系统恢复不能依赖原会话长期有效。 |
| AFS-14 / P0 | OWNER提供意见/证据；跨商家OWNER、员工档案、手机号代身份、OWNER变更 | 当前真实OWNER及门店归属重验；STAFF未授权则不可使用；商家不能运营终裁。OFFLINE不统一阻断存量；FROZEN保持现有失败关闭，不自行开放。 |
| AFS-15 / P0 | 本人真实上传的READY私有资产引用；他人、错purpose、未就绪、隔离、摘要/版本错 | AFS消费thirdparty公共API重核owner/purpose/不可变版本/状态，不能只信assetId或客户端URL；错证据整次业务写失败，不留下部分绑定；证据用途须先完成D5契约。 |
| AFS-16 / P0 | 本订单多角色、同买家其他工单、同商家不同订单、其他租户查详情/读证据 | 按批准可见矩阵逐格断言；归属相同不等于跨工单随意访问。集合列表也过滤资源，空列表仍需当前读权限；未授权错误不能泄露证据存在性。 |
| AFS-17 / P0 | 证据授权签发/消费间撤权、会话代际变化、工单/证据版本变化、token复制/过期重用 | AFS正式协议约束下重新核验当前权与绑定；不返回源对象URL/key/原件；若复用单次授权则只能消费一次、失败不复活令牌、重放不延长有效期，审计区分STARTED/失败/成功。 |
| AFS-18 / P1 | 敏感说明/证据名/原因进入参数、日志、事件、异常、查询 | 存储/幂等回执保护符合合同；日志/Outbox不含敏感原文和token；读取有用途/原因/审计及必要脱敏，响应禁止缓存。审核/扫描依赖未知或超时不能默认允许。 |

### 4.3 幂等、原子性、竞争与恢复

| ID / 优先级 | 操作 | 独立验收标准 |
|---|---|---|
| AFS-19 / P0 | 创建/受理/补证/决定同key同参、异参；同UUID不同actor/scope/namespace | 同参返回原始首回执（ID/时间/版本）；异参冲突，包括证据集合、决定类型、金额、原因和expectedVersion。规范化按合同绑定，不因金额字符串/资产顺序漏洞接受不同意图；不同主体不能全局误冲突。 |
| AFS-20 / P0 | 业务失败后同key改参；各命令提交成功但客户端ACK丢失，重启恢复 | 独立Admission保留规范参数，业务失败后仍禁止换参；ACK丢失实际发生在commit之后，恢复原首回执，不重复工单/证据/决定/事件/退款。不能用提交前异常冒充ACK丢失。 |
| AFS-21 / P0 | 每持久点注入失败，随后同参恢复 | 按第5节逐事务断言完整回滚和Admission例外；不只断言HTTP错误。必须核对双方指针、证据、版本、任务、Outbox、日志、钱与预约，无半成品。 |
| AFS-22 / P0 | 相同/不同key并发创建；受理与补证；两种终局决定并发 | 同key同回执；不同key工单数量遵循D1。不同决定只有一个终局赢家，输家无金额/事件；旧version不覆盖。竞争用barrier和明确提交顺序，不以随机sleep证明。 |
| AFS-23 / P0 | 真实未履约AFS：核销先提交，再退款决定/建单 | 原工单立即失效且保留历史；旧工单不得产生退款、出款任务或渠道调用。合法新VERIFIED工单与旧来源隔离。 |
| AFS-24 / P0 | 真实AFS裁决并实际建refund_order先提交，再核销 | 无论渠道未调用/UNKNOWN/FAILED，已有退款单立即阻止核销；不能等退款成功才封锁。一个订单无第二退款；码不因AFS申请本身先失效。 |
| AFS-25 / P0 | 退款决定已提交但建单暂停时核销；创建与核销并发；三方决定/建单/核销竞争 | 正式协议必须在退款单创建前重核当前未履约来源；核销赢家使旧AFS无出款可能，退款建单赢家阻核销。若决定与建单合一按同事务验；若拆分必须验持久恢复窗口，不能仅在决定时查一次。 |
| AFS-26 / P0 | 普通批准/超时建单与AFS创建/终裁/建单竞争 | 遵循D3并保持最多一笔refund_order；普通来源核销后仍可合法全额退款，不能误套AFS失效；失败方不留可另行出款的孤立来源。 |
| AFS-27 / P0 | 无guard、错误DataSource、只读/错隔离级别、伪/跨事务/旧token、孤立单域提交 | 同源可写RC与事务内能力绑定必须有效；ORDER/AFS/REFUND缺对方持久证明不能提交；查询故障不是“无工单/无退款”；禁止跨biz/跨Mapper自取授权。 |
| AFS-28 / P0 | 来源/订单/买家/商家/门店/付款/核销/拒绝/决定/金额/任务载荷任一被篡改 | 裸状态、事件、DTO或伪SYSTEM不能产生资金授权；首发前独立重核各域持久来源。证明损坏拒绝出款并保留可定位脱敏问题，不能自动修成合法。 |
| AFS-29 / P0 | 已合法决定后进程崩溃、缺任务、DEAD/CANCELED/SUCCEEDED任务待恢复 | 按真实原来源恢复原工单/决定/退款号；精确校验任务key/type/bizId/version/payload；保留尝试历史，fence递增，不偷RUNNING租约、不重置活任务。恢复无需原会话在线。 |
| AFS-30 / P0 | 批扫描首条坏来源/冲突任务，后续合法来源；修复后重扫；数据库整体故障 | 逐行隔离坏来源并记录OPEN/RESOLVED脱敏问题，后续合法项继续；不变资金状态。数据库不可用向上失败，不能报告扫描成功或将其当单条坏数据。 |
| AFS-31 / P0 | 渠道已接受后网络/提交ACK/lease丢失、UNKNOWN、worker重启 | MAY_HAVE_SENT先持久再网络；只查询原refundNo，submit计数不增加；查原单不要求原付款仍PAID；金额/渠道交易号/验签异常不确认成功。 |
| AFS-32 / P0 | FULL/PARTIAL真实成功；重复/乱序成功事件；最终投影故障 | 金额严格核验；成功投影、去重claim及批准的预约处理同事务；创建/UNKNOWN不提前释放；重复成功只处理一次，ORDER保留真实核销历史且统一计算DisplayOrderStatus。 |

### 4.4 回归与交付门禁

| ID / 优先级 | 验收范围 | 独立验收标准 |
|---|---|---|
| AFS-33 / P0 | 普通申请/拒绝重申请/24h超时/核销后同意、普通渠道恢复；真实AFS后续改变当前指针 | 保留Contract49真实来源、不可变首回执及新轮期限；不能新增普通7天期限，旧任务不覆新轮。合法新AFS不应令此前已提交的普通退款承诺/历史核销回执失去验证能力；历史事实验证不能错误要求当前AFS指针仍等于历史快照，也不能为兼容而放松失效AFS禁止出款。并存可达分支依D3。 |
| AFS-34 / P0 | 迟到支付、商家拒单及PAYMENT来源保护 | 迟到订单始终关闭，以渠道实际金额全额原路退款、不恢复预约；拒单来源仍有真实决定；新增AFS来源不放宽旧校验。 |
| AFS-35 / P0 | 核销凭证/改期/成功核销及旧AFS最小失效组件 | 旧核销首回执、风险计数、凭证版本、核销真实身份不变；原seed用例保留组件覆盖但由AFS-01/23/25补真实闭环。 |
| AFS-36 / P0 | 迁移/默认关闭/缺依赖/架构/契约 | 未知历史工单/决定不能猜测映射或删历史过关；仅隔离QA迁移。新运行开关默认关闭，缺真实权限/证据/审核/加密/来源适配失败关闭；生产SQL本模块MyBatis XML，ID String，金额精确，无biz→biz。 |
| AFS-37 / P1 | 事件路由、通知/评价/优惠券/积分接口及前端范围 | 已知其他来源正确路由或跳过、未知失败关闭；强制站内消息意图不可被外部推送开关取消。FULL券规则/PARTIAL不返券、奖励积分比例四舍五入扣回的消费者若未交付必须列风险，不以Outbox等同送达/结算。HTTP/前端/真机未在切片内则明确未验。 |

## 5. 逐持久点故障表

正式实现冻结后，以实际写集合补齐每项数据库触发故障或等价精确注入；这里只描述业务持久边界，不预设新表名。

| 事务阶段 | 必须逐点覆盖 | 失败后的独立不变量 |
|---|---|---|
| Admission | 参数保护、唯一准入写、准入commit及commit后ACK | 未提交不允许业务开始；已提交绑定不可换参；重启可定位同意图，不生成新作用域。 |
| 创建 | AFS主体/来源证明、证据关联、ORDER指针/投影/本域证明、状态日志、Outbox、必要任务、首回执 | 无孤工单/孤指针/半证据；资金与预约不变；Admission可保留。 |
| 受理/补证 | 版本CAS、状态/操作历史、追加证据及原始版本、权限审计、ORDER投影、Outbox、首回执 | 原状态和历史完整；不会部分挂载私有资产或误使终局工单可编辑。 |
| 首次终局 | 唯一决定、工单终局/版本、金额/来源证明、ORDER投影、审计/日志、Outbox、必要建单任务、首回执 | 无决定但有退款意图/无意图但已终局等孤立结果；原工单仍可按同意图恢复。非退款决定没有资金任务。 |
| 退款创建 | refund_order、execution绑定、AFS/ORDER来源提交证明及指针、状态日志、Created事件、渠道任务 | 对应事务全回滚；原已提交合法决定按正式恢复规则保留；不消费/泄露CREATE能力，不留半张退款。 |
| 核销失效 | VER成功/尝试/码消耗、AFS失效/证明/日志、ORDER完成/历史/证明/事件、核销首回执 | 核销与当前未履约工单失效同提交；不得单独完成或单独失效；恢复不覆盖后续新工单。 |
| 渠道最终成功投影 | 可信成功记录/事件、消费者claim、ORDER金额/状态、批准范围的SCHEDULE释放 | 各既定事务边界一致；成功前不释放、重复不重扣/重返/重释放；恢复使用原号。 |

每阶段各加一次真实commit后ACK丢失；业务异常后读取独立连接核对durable结果，不能只用同事务缓存或实现返回值当oracle。权限拒绝/业务拒绝与基础设施故障分别验证，不能统一吞成成功或幂等命中。

## 6. 可复用设施与真实程度

| 已核对来源 | 复用方式与限制 |
|---|---|
| [BookingCreateAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/BookingCreateAcceptanceTest.java) 的 Database | 随机 `qa_booking_<UUID>` schema、仅localhost MySQL根URL、UTC、按序装载Schema；作为共同fixture基础，避免各域复制一套库。当前装载含48/49，AFS及ADMIN/私有资产迁移按正式依赖添加一次。 |
| [RefundApplicationAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/RefundApplicationAcceptanceTest.java) 的 F/TimeSource | 真实普通apply/decide/createApproved、数据库时钟、commit后ACK、trigger故障、恢复/坏来源隔离/渠道计数可复用。默认sessions是fixture检查，login辅助直接填SessionCache；需另接真实登录/session resolver才能声称真实用户会话主链。 |
| [VerificationCredentialAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/VerificationCredentialAcceptanceTest.java) 的 T 与 [VerificationCompletionAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/VerificationCompletionAcceptanceTest.java) 的 components | 正常付款/确认/凭证/真实核销与ORDER/AFS证明装配；必须共用AFS fixture同一DataSource/guard。其seed直接插AFS、部分拒绝例插refund，只用于损坏/组件负例，不能正向代替新API。 |
| [CAuthHttpTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/auth/CAuthHttpTest.java)、[AdminAuthHttpTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/auth/AdminAuthHttpTest.java) | loopback HTTP真实登录、Redis和会话，可构成C/ADMIN主会话。上游微信/验证码协议替身须披露；管理员bootstrap仅初始化账户，之后走真实登录而非伪造session行。 |
| [AdminAuthorizationCurrentReadTest](../../../../backend/pet-admin-biz/src/test/java/com/petplatform/admin/biz/auth/AdminAuthorizationCurrentReadTest.java) | 当前角色/动作/范围/session generation及外层RR旧快照撤权证明；新AFS需接真实资源范围，不复用常量merchant/application标识。 |
| [PrivateAssetUploadHttpMySqlTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/auth/PrivateAssetUploadHttpMySqlTest.java) | 真C登录→multipart→私有素材core→SQL31；内存对象/扫描替身仅验证应用协议，不能称真实OSS。必须扩展批准AFS purpose后才能用于AFS资产绑定。 |
| [PrivateAssetAuthorizationMySqlTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/auth/PrivateAssetAuthorizationMySqlTest.java)、[PrivateAssetCoreMySqlTest](../../../../backend/pet-thirdparty-biz/src/test/java/com/petplatform/thirdparty/biz/PrivateAssetCoreMySqlTest.java) | 真实权限锁/消费和单次读取恢复模式；现有授权测试seed的是MER入驻材料，不能证明AFS归属、互见和补证版本语义。 |
| [LateRefundAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/LateRefundAcceptanceTest.java)、[MerchantOrderAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/MerchantOrderAcceptanceTest.java) | 旧迟到付款/拒单来源、渠道未知与恢复/预约释放回归。沿用真实来源API，不通过新source白名单跳过旧校验。 |

建议在测试层共享一个小型AfsFixture，组合上述公共测试设施，避免复制整个旧测试类；只抽取实际重复的准备与oracle，不为测试重构生产业务。预约主链通过可控时钟走到合法时间，已有ready(false)直接改预约时间的辅助不能掩盖跨域来源绑定差异。seed仅允许初始化静态账号/基础资源、时间/故障控制和明确标识的损坏负例；AFS、普通拒绝、核销、资金来源、资产READY正例必须由真实命令产生。

## 7. 执行顺序、资源隔离与报告

1. 先解决D1–D6所涉正式合同缺口，做来源→矩阵→具体测试映射；只读检查默认关闭、权限适配、Schema和跨域边界。文档链接/UTF-8/LF可独立检查，不等于业务通过。
2. 由主执行者统一调度**串行 Maven**；同一工作树不并发构建/改写target、不并发装载同一schema。并发业务测试只在一个受控测试进程内部按barrier运行。所有测试用随机schema及Redis前缀，连接localhost隔离环境；清理仅本测试创建的名字，不删共享数据。
3. 先跑真会话+真拒绝/核销+真私有资产的两条正向主链，再测资格/终局/证据/RBAC。主链断裂时不得用seed绕过后继续宣称闭环。
4. 运行所有资金、核销、幂等、逐持久点回滚、ACK、竞争和恢复P0；对两个确定顺序及一次同时竞争均留证据。用可控DB时间与协调锁，禁止等待实际7天/24小时或靠概率重复碰撞。
5. 串行运行受影响普通/迟到/拒单退款、核销/改期、ADMIN/OSS回归，以及项目既定架构/持久层/契约检查。低影响文档/命名调整无需机械新增同构测试；仅当新增行为、故障或覆盖缺口有独立风险时加测试。
6. 报告记录提交SHA、实际命令、JUnit XML与tests/failures/errors/skipped、矩阵逐行对应和未测范围；skipped不算通过，CI绿色不能代替核对目标类确实执行。凭证/URL/原文不进入报告。

独立审查重点：从SSOT不变量构造反例，检查真正赢家及数据库结果；不用实现同一个谓词重新计算expected值，不仅mock verify调用，不仅assert返回200，不用单线程替代竞争，不用seed替代来源。默认关闭的INTERNAL切片验收不能写成公开HTTP/小程序/真机/生产通知已完成。

## 8. 本文检查记录

本轮只读取基线资料并编写本矩阵；未修改正式契约或业务代码，未运行Maven、数据库或功能验收，未提交Git。文档静态检查已执行：UTF-8有效、无BOM、LF换行；25个本地链接全部存在；AFS-01至AFS-37共37行且编号唯一。此结果仅为文档检查通过；所有AFS功能状态仍为NOT_EXECUTED，任何后续功能PASS必须附实际执行证据。
