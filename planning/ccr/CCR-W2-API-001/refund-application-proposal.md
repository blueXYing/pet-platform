# 普通退款申请、商家处理与真实退款来源接续方案

日期：2026-09-30。基线：PR95 合并提交 `eb5d16ffff5a2db2799545b4f5fd490e1431f99c`。

状态：**APPROVED — 2026-09-30 用户明确回复“批准”，批准本文件 R1/R2 的正式契约同步、默认关闭实现、隔离测试及 PR。** 不包括合并、生产迁移或启用。下文“候选/待审阅/未执行”保留提案形成时的原文，实施状态以 [Contract49](../../../docs/04-api/49-Refund-Application-Contract-v0.1.md) 和 WORK_STATE 为准；已批准业务与技术范围不再重复索取确认。

## 1. 本次审阅的具体内容

首批交付一个默认关闭的内部闭环：**用户服务开始后申请全额退款 → 商家主账号同意/拒绝，或24小时自动同意 → 有真实申请/决定来源的业务退款单 → 原渠道执行 → ORDER退款事实与预约释放。**

| 决定 | 推荐方案与影响 |
|---|---|
| R1：申请、决定与24小时恢复 | REFUND拥有真实申请及不可变决定；ORDER拥有当前申请引用/状态和资格。沿已批准重复申请规则，独立幂等准入、真实身份、每轮独立期限、历史保留。批准决定先持久化并投递建退款任务，退款单创建独立短事务可恢复，不把“已同意”谎报为“已退款”。 |
| R2：普通全额退款来源贯通 | 新增有不可变申请决定证明的 `MERCHANT_APPROVED` / `MERCHANT_TIMEOUT_AUTO` 来源，贯通 REFUND、ORDER、PAYMENT、SCHEDULE 的校验、任务、成功事件消费；复用原路退款与UNKNOWN查单机制。不会通过开放source枚举就允许任意建单。 |

两项范围包括相应正式契约/Schema/任务/事件兼容说明、实现、隔离MySQL与并发故障测试、默认关闭PR；不包括PR合并、生产迁移/启用。技术增量涉及当前仅两种退款来源的共享存储约束及多模块API，按 [WORK_EXECUTION_PROTOCOL §4](../../../WORK_EXECUTION_PROTOCOL.md) 的“Contract重大变更”及 [.github/CODEOWNERS](../../../.github/CODEOWNERS) 由人工技术契约审核人审阅。

不包含：服务开始前自动退款 REF-001、AFS 创建/证据/运营裁决、PARTIAL、员工账号、手动核销、公开HTTP/小程序、通知/评价消费者。AFS关联的完整CREATE_REFUND与QA-004仍后续交付。首批完成不能关闭整个 REF-003/VER-002/AFS-001/QA-004。

## 2. 已核实基线与来源

- PR95 已合并；合并后 [CI36659585415](https://github.com/blueXYing/pet-platform/actions/runs/36659585415) 六项成功；下载116份Surefire XML核对751项、失败/错误/跳过均0，见[合并证据](../../progress/2026-09-30/refund-aftersale/pr95-merge.json)、[CI](../../progress/2026-09-30/refund-aftersale/pr95-merge-ci.json)和[逐报告摘要](../../progress/2026-09-30/refund-aftersale/pr95-merge-backend-summary.json)。这些是既有基线证据，不是新退款能力验收。
- SSOT §4/§6/§7/§8，Internal07 §7.5/§9，HTTP10 §3.9/§4.4，Scheduler09 §15/§16 已定义普通退款与未履约售后不同；详见[资料及缺口审查](../../progress/2026-09-30/refund-aftersale/SOURCES-AND-GAPS.md)。
- `refund_application` 只有Schema草图，没有真实申请/决定服务。现有退款来源校验、SQL45 CHECK、渠道发送、恢复扫描、ORDER消费和SCH释放只接通迟到支付/商家拒单，见[代码与测试映射](../../progress/2026-09-30/refund-aftersale/CODE-AND-TEST-MAP.md)。
- [48号契约](../../../docs/04-api/48-Verification-Completion-Contract-v0.1.md) 只有VERIFY事务能力和最小AFS失效，不能拿VERIFY token充当创建退款授权。

## 3. 产品裁决回执与不变规则

2026-09-30询问“商家拒绝退款后，用户能否再次申请普通退款”，完整推荐为“允许再次申请，每次重新计24小时，同一订单同时仅一笔待处理，已创建退款单后禁止再申请”。用户明确选择 **“允许再次申请，按上述规则处理（推荐）”**。已同步 SSOT §38 / [PRD30](../../../docs/01-prd/30-普通退款重复申请人工裁决补充-v1.0.md)，此项不再询问。

必须保留：

1. 申请 `PENDING_MERCHANT` 本身不禁止核销；普通申请等待期间核销先完成，商家同意/超时仍可全额退款。只有旧未履约售后失效后，不能再以该旧工单退款。
2. `refund_order` 创建成功即永久禁止后续核销，即使退款随后UNKNOWN/FAILED；一单只允许一张业务退款单。只有渠道最终成功后才释放预约。
3. 普通商家只可同意全额或拒绝。金额来自原订单的真实正常付款事实，不接受客户端金额；不使用商品现价、不伪造迟到付款、不把核销时间当付款时间。
4. 售后7天与普通退款期限不混用。本批不新增普通退款截止天数或重复次数限制。
5. 真实OWNER可处理OFFLINE门店存量订单；FROZEN未决写动作仍失败关闭，不恢复MFA或运营双人审批。

## 4. R1：申请、决定、来源事实

### 4.1 资格与分工

ORDER提供普通退款资格/原正常付款事实和当前申请引用的公共API；REFUND不跨读ORDER表。首批覆盖已到预约开始且未核销的可履约订单，以及已核销完成订单的普通退款申请。开始前路径明确保留REF-001未实现，不能误走24小时流程。失配的订单/预约/支付归属、缺失事实、未知状态均失败关闭。

本人申请复用真实MINIAPP会话的ACTIVE USER，由可信适配层解析userId，且等于订单买家；不能信任调用方声明userId。商家决定复用真实会话与 `MerchantOrderAuthorityApi.requireOwner`，在共享guard下再验当前商家/门店归属。用户和OWNER每次执行与首回执重放都重验当前权限；SYSTEM超时仅通过已注册durable任务入口，不能外部声称SYSTEM。

AFS完整来源未实现的组合不在本批验收声明内；本批不新增或失效AFS工单，也不拿测试seed代替真实售后创建。核销后新售后按既定资格及当前退款事实重验，不能因VERIFIED一刀切拒绝，也不能让已有退款中/全额或部分退款事实的订单绕过原售后限制。

### 4.2 候选内部接口及回执

以下是本CCR候选，尚未升级为正式公共契约或SDK：

| Owner / 接口 | 形状及约束 |
|---|---|
| REFUND `RefundApplicationCommandApi.apply` | 可信会话上下文、orderId、reasonCode、可选reasonText；无金额/source/staffId参数。服务端从ORDER事实定位store及本金。 |
| REFUND `RefundApplicationCommandApi.decide` | 可信会话上下文、applicationId、expectedApplicationVersion、APPROVE或REJECT、拒绝reasonText；无可修改的订单或退款金额。拒绝分类字典仍是后续扩展，本批不新增必填reasonCode。 |
| REFUND `RefundApplicationTimeoutApi.handle` | 仅任务上下文、applicationId、expectedMerchantDeadline及原任务身份；处理目标始终是该申请，不按orderId猜“最新一笔”。 |
| ORDER `OrderRefundApplicationApi` | 定位订单；锁内资格与原付款事实；原子绑定新申请/决定状态；提供普通CREATE_REFUND能力和持久订单侧来源证明。方法均确认同DataSource/共享guard/可写RC事务，禁止传裸DTO绕过证明。 |
| REFUND `RefundApplicationApprovalFactsApi` | 只暴露已提交的真实APPROVED/AUTO_APPROVED不可变决定，包含applicationId、decisionId、order/user/merchant/store、原付款来源、金额和时点；REJECTED/PENDING不能提供建单授权。 |

apply/decide首回执包含orderId、applicationId、applicationStatus、applicationVersion、merchantDeadline、decidedAt（可空）及decisionId（可空）；不冒充完整HTTP `DisplayOrderStatus`。APPROVED/AUTO_APPROVED回执表示批准已持久化、后台建单恢复任务已受理，不表示渠道退款成功。首回执不可变，之后退款进度从独立读侧获取；旧回执重放不切换成新申请/新状态。

ID与版本对外String；时间UTC毫秒；金额内部BigDecimal/存储DECIMAL(18,2)，JSON金额String。申请reasonCode沿既有可配置字典模型：读取服务端配置的有效申请代码，无生产默认字典，未配置时不开放申请入口；不自行把测试用例代码称为产品字典。沿Schema长度申请code≤64、申请说明≤500；商家拒绝只强制reasonText非全空白且≤500码点，不因尚未封板的拒绝分类字典而拦截拒绝，不借用商家拒单分类。敏感词等已要求的审核依赖不得用永远通过的Provider替代；原文加密留存，日志/事件不携带说明或手机号。

### 4.3 多次申请与幂等

- 每轮新申请有独立applicationId/applicationNo/createdAt/merchantDeadline；拒绝的旧行、原因、决定、任务和回执保留，不把旧行改回PENDING。
- 仅在原申请已REJECTED且仍满足当前普通退款资格、没有refund_order时创建新轮。已有PENDING时不同requestId申请返回明确冲突并指向本人可读的当前申请，不新建、不延时；已APPROVED/AUTO_APPROVED待建单时继续恢复原来源，不开新轮。
- 写入使用23号五元组：namespace + operatorType + operatorId + scope + requestId。apply scope=ORDER:orderId；decide scope=REFUND_APPLICATION:applicationId。不同主体相同UUID不冲突；同key异参即冲突。
- REFUND独立Admission事务保存规范参数的加密绑定；失败后保留绑定。业务事务按Admission行→共享门店guard→ORDER当前事实→REFUND申请/决定顺序，同key并发与真实commit后ACK丢失返回首回执，禁止重复建申请/决定。
- 旧申请的决定重放只返回旧结果；旧拒绝、旧超时任务不能覆盖新申请引用、不能延长或自动批准新申请。所有指针修改都带申请ID/版本校验。

### 4.4 24小时、两阶段恢复与事务边界

新申请事务在guard内取数据库UTC毫秒时间：deadline=createdAt+24h；申请、ORDER引用/状态、超时任务与首回执同提交。新一轮只在旧轮已拒绝时计算新期限。任务唯一键沿 `REFUND_MERCHANT_TIMEOUT:{applicationId}`，系统requestId沿 `TASK:REFUND_MERCHANT_TIMEOUT:{applicationId}`。

商家新的处理仅在锁内now<deadline允许，now>=deadline返回既有 `REFUND_MERCHANT_DEADLINE_PASSED`；成功首回执重放不重跑截止资格。超时now<deadline不改变业务，返回Retry/Reschedule至持久deadline，不能以SUCCESS吞掉唯一任务；到期且PENDING才自动批准。拒绝/已处理/旧轮任务幂等结束；任务载荷与持久期限不符按stale或损坏分别处理，不能伪造较早期限。

为保留Scheduler09 §15明确的“批准已提交但建退款前宕机可恢复”语义，候选采用：

1. **申请事务**：真实申请 + ORDER指针/状态 + 24h任务 + 申请Outbox意图 + 首回执，一起提交。
2. **批准/拒绝事务**：在同guard下锁当前申请并复验权限/截止/CAS。拒绝保存不可变原因/审计、ORDER状态、决定Outbox意图和首回执，不建退款。批准保存APPROVED/AUTO_APPROVED、不可变决定/原付款来源、ORDER决定状态、决定Outbox意图、唯一 `REFUND_APPLICATION_CREATE:{applicationId}` 恢复任务和首回执，一起提交。此时尚无refund_order，因此仍可核销；普通退款不受核销先完成否决。
3. **创建退款事务**：SYSTEM任务重读真实批准与ORDER事实；在同guard、同DataSource、可写RC下创建唯一refund_order、执行绑定、ORDER退款指针/证明、RefundOrderCreatedEvent和渠道任务，并在提交前相互校验各Owner持久化证明。任一步失败整体回滚，已批准来源与恢复任务保留供重试。

任务重试/恢复扫描覆盖APPROVED/AUTO_APPROVED无退款的合法来源，也覆盖已到期PENDING但超时任务缺失/DEAD/错误CANCELED的申请；依task-core既有租约/CAS协议恢复，不跨域改任务表、不盗取仍有效租约。扫描由REFUND本域持久事实驱动，确认当前决定/申请后才恢复。无可核实决定/付款证明的历史孤立行不能猜测补齐，须明确异常阻断；兼容迁移先校验。为验证恢复，必须用真实批准API提交后中断建单worker，再重启任务，不靠SQL伪造半成品证明功能。

SYSTEM建单和渠道恢复依已提交的不可变合法决定继续，不依赖原申请人/OWNER会话仍有效；原操作者撤权不得取消已合法受理的退款承诺。建单时重验当前资格与来源绑定，但不要求ORDER版本仍等于批准时版本：批准后核销是合法并发，新的CREATE_REFUND token应绑定本次事务看到的当前版本。

拒绝不得覆盖已批准决定；超时与商家决定最终只提交一条决定。批准和建单阶段的响应/状态分开，退款失败不会把已批准伪装成拒绝，也不会自动新建退款单。

## 5. R2：可信普通来源、渠道与成功投影

### 5.1 只能从不可变批准来源建单

新增普通来源限 `MERCHANT_APPROVED` / `MERCHANT_TIMEOUT_AUTO`，分别要求APPROVED/真实OWNER决定或AUTO_APPROVED/原超时任务决定。`sourceBizId=applicationId`、`sourceDecisionId=decisionId`，与order/payment/user/merchant/store、本金、原渠道号、批准时点持久绑定。FULL金额必须等于真实正常支付金额，拒绝调用方任意金额/来源。

ORDER签发的CREATE_REFUND能力仅对本次事务/命令/申请/决定/订单版本有效；核销已有VERIFY能力保持分离。两种操作用同一个门店guard串行化，但语义是来源敏感：VERIFY发现任何refund_order就拒绝；普通APPROVED/AUTO_APPROVED在VERIFIED后仍能创建退款。暂不接受AFTERSALE_DECISION/PARTIAL/任意运营异常来源。

REFUND决定证明与ORDER来源证明各归本模块；PAYMENT在首次渠道发送前从两方公共API核对，不把事件或客户端参数当资金授权。退款后重试从不可变执行/渠道发送绑定恢复，不能要求当前支付状态仍为PAID而掩盖已发生的退款成功。

### 5.2 存储候选

| 归属 | 增量 |
|---|---|
| REFUND申请 | 增加版本、当前轮控制及不可变决定关联；ORDER引用负责当前轮，REFUND可增加生成列唯一活动order约束作第二防线。REJECTED释放活动约束；APPROVED/AUTO_APPROVED恢复阶段仍占活动约束，避免第二来源。保留历史，不增加refund_application.order_id全局唯一。 |
| REFUND幂等/决定 | 新增独立Admission及决定表，保存二进制五元组唯一键、加密规范参数/首回执、真实USER/OWNER或SYSTEM身份、版本/时点、决定原因及来源证明；移除旧refund_application全局request_id唯一键，由真实作用域替代。 |
| ORDER | 新增本域普通申请/决定/创建退款提交证明；复用已有current_refund_application_id，新增当前尚不存在的refund_application_status存储投影，按当前申请ID/CAS更新，不能让晚到旧轮覆盖新轮。拒绝时保留当前真实核销事实，不按申请时状态把已完成订单退回待服务。 |
| REFUND执行 | 扩展SQL45来源CHECK，新增显式source_biz_id/source_decision_id。普通来源late_event_id/source_event_id均NULL，source_biz_id/source_decision_id必填，created_event_id仍对应真实退款创建Outbox；两种旧来源保留既有事件字段语义。同步扩展RefundExecutionFact与OrderRefundOriginFact及逐来源校验，消除现有List.of非空假设和sourceEventId.equals路径，不能把applicationId/decisionId假装为事件ID；旧构造器语义不变。 |
| 迁移 | 新增独立候选迁移，正式编号在批准后分配；仅隔离QA运行。历史申请/决定不能核实真实身份或存在多个活动申请时阻断，不自动抹除/猜测回填；两种既有退款来源数据原样兼容。 |

所有SQL在本Owner的MyBatis XML；不允许biz→biz、跨域Repository/Mapper/Entity。候选Schema不会放入生产迁移目录或当作已批准DDL。

### 5.3 执行、事件兼容与资源释放

- 扩展现有退款执行事实校验、PAYMENT首次发送授权、REFUND恢复扫描与死信核验，只接通两种新批准来源。通用任意source入参仍不可用。
- 普通申请建单任务、渠道提交/查询任务使用独立已登记类型与确定性key，建议 `REFUND_APPLICATION_CREATE`、`APPLICATION_REFUND_SUBMIT`、`APPLICATION_REFUND_CHANNEL_QUERY`；注册来源组严格限定MERCHANT_APPROVED/MERCHANT_TIMEOUT_AUTO，并逐项比对实际批准状态/决定，不可传expectedSource=null绕过校验。不让旧迟到worker越类型领取，不另建第二套调度器。保留可信worker将业务taskKey映射为现有PAYMENT的 `TASK:REFUND_SUBMIT:{refundId}:0` / `TASK:REFUND_CHANNEL_QUERY:{refundId}:0` 请求键；绑定原任务/退款ID，不接受外部任意键。
- 沿用原refundNo/原渠道订单；MAY_HAVE_SENT先于网络调用持久化，未知结果只能查原退款号。不得重新发送新退款单号或把暂时失败当未发送。只有真实渠道成功证明能驱动成功投影。
- RefundOrderCreatedEvent.v1/RefundSucceededEvent.v1沿现有严格payload，只增加已经在Internal07列出的普通source值；applicationId/decisionId从可信内部事实取得，不偷偷在v1增加字段。所有既有消费者须同步识别“已知其他来源可跳过、未知来源失败”，防止新来源让旧消费者无限失败。
- 为保留强制站内通知的可靠生产事实，候选新增REFUND生产的 `RefundApplicationCreatedEvent.v1`（applicationId/orderId/userId/merchantId/storeId/applicationStatus/merchantDeadline/createdAt）与 `RefundApplicationDecidedEvent.v1`（applicationId/decisionId/orderId/userId/merchantId/storeId/applicationStatus/decidedAt）。只含所列字段，ID String、时间UTC毫秒、无自由文本/手机号；对应申请或决定事务内唯一Outbox，重放不重复生产。事件只是通知意图，不作为退款授权。通知消费者/模板/实际送达单独接续并在公开入口前验收，当前不能宣称通知已送达。
- 新ORDER普通退款消费者验证REFUND最终成功及ORDER本域来源、金额/渠道号/时间/唯一事件；消费claim、ORDER退款进度/金额、SCH预约释放在同一事务提交。重复事件核对既有证明后幂等；失败不先标消费成功。
- SCH释放需通过REFUND公共成功事实验证新普通来源及原order/reservation/store；全额成功才释放，保留预约/claim/释放审计历史。申请或批准阶段不释放，渠道UNKNOWN/FAILED不释放。
- ORDER保留verifiedAt及核销历史，不能靠清除核销标志来让退款通过。DisplayOrderStatus仍由ORDER统一负责；首批内部命令不承诺缺失的完整公共展示聚合。

## 6. 实施分工与依赖

| 次序 | 原Issue / Owner | Allowed Modules与交付 |
|---|---|---|
| 1 | REF-002 / Transaction Backend，Contract Owner | REFUND api/biz、ORDER api/biz、boot；批准后同步申请/决定/幂等/来源契约及Schema，真实申请与OWNER处理。 |
| 2 | REF-004、REF-003已存在普通来源依赖 | REFUND、ORDER、PAYMENT api/biz、SCHEDULE api/biz、boot必要装配；24h及建单恢复，渠道任务与成功投影。TASK/EVENT core仅复用公共API，原则上不改框架。 |
| 3 | QA / 同批协作 | `backend/pet-boot/src/test`、相关模块tests、e2e契约检查；隔离MySQL真实API、真实会话及回滚/并发/恢复证据，既有两种退款/核销回归。 |
| 后续 | AFS-001 → AFS-002 → VER-002 / QA-004 | 真实售后资格/创建/正式裁决来源，接入来源敏感CREATE_REFUND，验收旧未履约售后与核销竞态。 |
| 并行准备 | AUTH-001 / MER-001 | [员工绑定与核销动作授权准备](staff-verification-authority-preparation.md)，不阻塞本批退款，不把档案手机号变成登录授权。 |
| 最后 | C/M/Admin / QA | 正式公开HTTP/读侧、扫码确认/手动兜底、通知评价消费者、小程序和真机E2E。 |

不得把阶段验证当作全部Issue解锁；Catalog旧BLOCKED也不能抹掉已合并子能力。部署/真实支付渠道凭据、生产DDL、历史数据处理、开关启用另行安排。

## 7. 必须通过的验收（当前均未执行）

1. 真实本人会话申请→持久申请+ORDER投影+24h任务；错人/失效会话/未知付款事实拒绝。已核销合法普通退款同样可申请。
2. 真实OWNER同意/拒绝与撤权、跨店负例；拒绝原因留存，拒绝不生成refund_order或渠道任务。
3. 同requestId重试/并发/提交后ACK丢失首回执一致；异参冲突；不同key同时申请仅一笔待处理且期限不延长；不同主体同UUID独立。
4. 拒绝后真实新申请产生新applicationId与新24h期限；旧任务/旧请求回放不能批准、拒绝或覆盖新轮；退款单创建后再申请拒绝。
5. 锁内24h前后边界、提前任务重新调度、任务缺失/DEAD/CANCELED恢复、商家/超时竞争；最后只有一条不可变决定；APPROVED/AUTO_APPROVED提交后暂停/重启建单worker真实恢复且只建一张退款单。
6. PENDING申请不阻止真实核销；先核销后商家同意和自动批准仍能全额退款；另覆盖“批准已提交→暂停CREATE worker→真实核销改变ORDER版本→恢复CREATE仍成功”。先创建refund_order后真实核销拒绝。明确这不是AFS旧工单竞态验收。
7. 申请、决定、ORDER事实、恢复任务、退款单、执行绑定、Outbox、渠道任务及首回执逐持久点注入故障；各短事务效果原子，已提交阶段可恢复，Admission不丢失。
8. 伪造source、错误application/decision/order/payment归属、任意金额或失效跨事务token不能触达渠道；裸APPROVED字段没有证明不能授权退款。
9. 原路退款MAY_HAVE_SENT/UNKNOWN、ACK丢失、重复回调与重启只查原refundNo；新旧来源隔离，无重复出款。
10. 最终成功前预约保持占用；成功事件下ORDER/SCH/消费claim同提交，故障整体回滚，重复事件只记一次释放，保留核销历史。
11. SQL45旧来源迁移兼容；未知历史申请/来源拒绝迁移；未知source不被当作默认普通来源。
12. 默认关闭及装配依赖失败关闭，敏感说明加密/不出日志，无公开任意建退款入口；MyBatis、模块依赖、DisplayOrderStatus归属、ArchUnit和相关契约回归通过。

测试必须走真实创建/决定API；AFS工单seed和渠道测试适配均在报告中标明，不冒充线上支付或AFS端到端。所有新业务测试目前是计划，不借用PR95的751项标PASS。

## 8. 尚待本次审阅的边界

只请求R1/R2所述技术与实施范围审阅；不重问已批准的再次申请规则、24小时、全额金额或核销互斥硬规则。当前已同步的SSOT/PRD只包含用户明确裁决，不把此技术候选写为正式合同。

AFS“同一订单同一问题”与一订单单active工单Schema差异、售后证据协议、员工本人认可的绑定流程等由后续对应CCR解决。本批不自行决定它们，也不因此搁置已授权的基线收尾、来源盘点和验收准备。
