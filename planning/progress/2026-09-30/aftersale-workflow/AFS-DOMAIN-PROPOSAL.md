# AFS-001 / AFS-002 领域实施提案（待审阅）

日期：2026-09-30。核对基线：PR96 合并提交 `ff983596cad899c7b4e1ce9c8278228787ca7ac6`。本文是具体候选方案，不是产品批准、契约批准、代码完成或验收记录。只覆盖后端内部售后工作流，不扩前端、STAFF、REF-001、生产迁移或生产启用。

## 1. 来源与裁决状态

已读取本工作树 `AGENTS.md`、`WORK_EXECUTION_PROTOCOL.md`，按 SSOT > 最终 PRD > 技术基线 > Schema/API/Event/Scheduler > Test 执行。主要来源：

- `docs/00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md` §5–8、§24–25、§37–39。
- `docs/04-api/48-Verification-Completion-Contract-v0.1.md`、`49-Refund-Application-Contract-v0.1.md`。
- `docs/04-api/07-内部API-Contract-v0.6.md` §11、`10-HTTP-API-Contract-v0.4.md` §3.12–3.13、4.12、5.3–5.4 及运营权限清单。
- `docs/03-database/06-核心数据库Schema-v0.1.sql` 498–554 行、`docs/05-events/08-Integration-Event-Catalog-v0.6.md` §9–11。
- `planning/progress/2026-09-30/refund-aftersale/SOURCES-AND-GAPS.md`：继承其差异提示，已重新核对上述权威原件及现码，不以准备稿替代证据。
- 最终 PRD 原件：`docs/01-prd/02-PRD-C端用户-V1.0-最终基线.docx`（下称 C）、`03-PRD-商家端-V1.0-最终基线.docx`（M）、`04-PRD-运营端-V1.0-最终基线.docx`（O）。本次只读 ZIP/XML，p[N] 为 `word/document.xml` 中所有 `w:p` 的一基编号，含表格和空段落，不是页码。

已定且不重复裁决：普通退款拒绝后可重申请；售后原 7 天窗口（包含到期等号，Scheduler09 §33，见§3.1）；核销后新问题不要求先被商家拒绝；仅运营可部分退款；首次裁决终局；旧工单不可重开；无 MFA、无内部双人审批。未知产品项集中为以下四项，P1～P3 已询问，P4 已由 root 补问；均未获答复，等待 root 转发用户回执，下面按候选 A 展开可审阅设计，不将候选当批准：

| 项目 | 候选 A（root 已询问） | 备选影响 / 未批准时边界 |
|---|---|---|
| P1 活动粒度 | 一单一活动工单；同单其他问题在当前工单追加，不另开活动工单 | 若允许不同问题并行，必须定义问题身份，并重做 ORDER 单指针、唯一索引、核销批量失效与证明；描述哈希、类型相同均不能证明是同一问题。旧 SQL 单 active 不是产品授权 |
| P2 撤回与再次提出 | PENDING / PROCESSING / WAITING_SUPPLEMENT 在终局前允许本人撤回；仍满足原资格和原 7 天期限可新建新号 | 不恢复旧工单、不刷新 7 天。已裁决的新问题与换号复审需区分；核销后新问题已有来源依据。未获回执不开放撤回写入 |
| P3 普通退款并存 | 普通申请 PENDING_MERCHANT / APPROVED / AUTO_APPROVED 与活动 AFS 双向互斥 | 若选并存，须定义同意/超时与 AFS 裁决谁取得唯一退款、败方工单如何结束；只靠 refund_order 唯一键不能表达产品结果。未获回执不静默改 REFUND |
| P4 终局后新问题 | 原窗口内提交新问题说明，运营受理前核对历史；重复问题关闭并引用原结论，真正新问题才受理 | 备选为同履约阶段非退款终局后不再受理，核销后依新阶段资格；两者均未获答复，不自动采用，详见§3.2 |

## 2. 现有实现与精确缺口

以下路径均相对本工作树根目录。

| 真实接口 / 代码 | 已有能力 | 本次所需增量（候选，必须 CCR） |
|---|---|---|
| `backend/pet-aftersale-api/src/main/java/com/petplatform/aftersale/api/command/AfterSaleVerificationApi.java`；`pet-aftersale-biz/.../apiimpl/AfterSaleVerificationApiImpl.java`；`.../resources/mapper/AfterSaleVerificationMapper.xml` | 核销事务中仅失效当前 UNVERIFIED_POST_START 活动工单；独占自身表；没有创建、受理、补证、终裁 | 增加真正命令、持久首回执、证据批次、补充请求、终局决定和 ORDER 同事务投影；不得把测试插行当创建 |
| `backend/pet-refund-api/src/main/java/com/petplatform/refund/api/query/RefundApplicationApprovalFactsApi.java`；`pet-refund-biz/.../application/RefundApplicationService.java` | `requireApplication/requireDecision` 可验证已知申请和真实拒绝决定，含关系、版本、身份及事件；requireApproved 只认可两普通来源 | 缺按订单发现拒绝历史和当前活动申请事实。拟加 RefundAftersaleFactsApi，REFUND 自己查询和验证；AFS 不直接 Mapper 查退款表，不接收客户端拒绝标志 |
| `backend/pet-refund-api/src/main/java/com/petplatform/refund/api/query/RefundOrderFactsApi.java` | 同事务、共享门店 guard 下按订单读所有来源所有状态的真实退款单，异常不等于 NONE | 可复用存在性检查；AFS 裁决来源及 PARTIAL 建单仍需新增公共命令与不可变来源校验 |
| `backend/pet-order-api/src/main/java/com/petplatform/order/api/command/OrderRefundApplicationApi.java` | 普通退款事实及普通申请专用 CREATE_REFUND token | 不含 verifiedAt；有普通资格语义；不能借其 requireEligible 充当 AFS 资格。需 AFS 专用 ORDER 事实/投影/提交能力，保留 ORDER 独占展示态计算 |
| `backend/pet-admin-api/.../query/AdminSessionQueryApi.java`、`AdminAuthorizationQueryApi.java`、`.../dto/AdminActionCheckQuery.java` | 真实会话解析、动作及资源范围的当前授权；EXECUTE 加入当前事务，READ_RESULT 独立读取 | `backend/pet-admin-biz/.../domain/service/AdminPermissionEvaluator.java` 的 DEPLOYED_ACTIONS 只有入驻/服务6项，没有 aftersale.*；`.../application/AdminAuthorizationService.java` 的 checkCollection 只支持 merchant.application.read。需 AUTH Owner 注册售后已批准动作和集合读取支持；不能假称现接口已准许售后 |
| `backend/pet-merchant-api/.../query/MerchantOrderAuthorityApi.java` | 共享 guard 下验证真实 OWNER 与订单商家/店；OFFLINE 存量边界 | 可经 boot 适配用于售后 OWNER 同店校验；不是 STAFF。冻结补证写仍是 B-FROZEN-WRITE 资料缺口，保持未交付/失败关闭，不称产品已禁止 |
| `backend/pet-thirdparty-api/src/main/java/com/petplatform/thirdparty/api/PrivateAssetApi.java`、`.../dto/PrivateAssetTypes.java` | owned asset 解析、扫描状态、对象版本/hash，授予/消费时重查、无裸 URL | 实现 `pet-thirdparty-biz/.../apiimpl/PrivateAssetApiImpl.java` 用途只 MERCHANT_APPLICATION_MATERIAL / SERVICE_COVER。读 DTO 是 applicationId/revisionId，`pet-boot/.../config/PrivateAssetReadAuthorizationAdapter.java` 强绑定 MerchantApplicationQueryApi、入驻裁决/身份解密权限。必须新增 AFS 用途与资源绑定/授权协议，不能把 caseId 冒充 applicationId |
| `backend/pet-boot/src/main/java/com/petplatform/boot/config/RefundApplicationConfiguration.java` | 真实 MINIAPP bearer → UserAuthService 当前 ACTIVE USER；OWNER authority 适配；缺真实审核/字典等失败关闭 | AFS 通过自己的 port + boot 装配同类可信身份，不依赖 refund-biz；ADMIN 通过 AdminSessionQueryApi，不信 CommandContext 中客户端自报身份 |

特别兼容风险（已核对调用路径）：`backend/pet-verification-biz/src/main/java/com/petplatform/verification/biz/application/VerificationCompletionService.java:25` 的成功重放直接比对本域保存的首回执与自身 `receipt()`，并不调用 ORDER/AFS `requireCommitted`；因此不能声称新 AFS 必然破坏当前核销成功重放。已经证明的耦合有三处：`AfterSaleVerificationApiImpl.requireCommitted()` 要求订单没有任何 active 工单并以当前工单状态/version 匹配历史证明；`backend/pet-order-biz/src/main/java/com/petplatform/order/biz/apiimpl/OrderVerificationCommitApiImpl.java:56` 的公开历史证明 API 将当前 AFS 指针/状态与核销时记录比较；同目录 `OrderRefundApplicationApiImpl.java:219` 的普通退款资格 `normal()` 也执行此比较。后续合法 VERIFIED AFS 改变当前指针/状态会破坏这些历史证明读取及普通退款资格，不能只修 AFS 单域读取。本切片必须区分“核销提交当时的完整性验证”和“后来校验不可变历史证明”：提交当时仍要求无遗留未履约活动工单；之后以核销ID、来源、发生时间与不可变提交证据证明历史，并允许新 VERIFIED 工单存在及结束，当前 AFS/普通退款互斥另外按已批准规则校验。不能改写旧证明、抹掉旧工单或放宽提交当时一致性；保留旧核销成功重放回归验收，但不将它写成已证实的现有缺陷。

## 3. AFS-001 真实资格、创建和状态机

### 3.1 资格读取与创建重验

查询给出原因明确的资格快照，不授予稍后创建的能力。create 必须重新验证。沿正式 `docs/06-scheduler/09-Scheduler-Retry-Compensation-v0.5.md` §33 第1304–1305行的 `now <= verifiedAt + 7d` / `now <= appointmentStart + 7d`，统一为数据库 UTC 毫秒的闭区间 `[t0, t0 + 7*24h]`：`t0 <= now <= deadline`，起点和到期等号允许，到期后1ms拒绝新申请。7天按连续168小时计算，不按自然日零点截断；这是沿用既有正式契约，不另提出半开边界改变：

1. 当前本人 ACTIVE USER 会话；订单 owner 与会话一致，归属由 ORDER 读取，不能从 body 传 userId/merchantId/storeId 来证明。
2. 任何来源实际 refund_order 已存在则不得新建；既有退款中/全退/部分退不得新建，未知事实/损坏证明失败关闭。
3. VERIFIED：ORDER 的真实 verifiedAt 存在，`verifiedAt <= now <= verifiedAt + 7*24h`。不要求任何普通退款申请或拒绝；不额外加 appointmentStart 门槛。
4. UNVERIFIED_POST_START：`appointmentStart <= now <= appointmentStart + 7*24h`，且 REFUND 提供同单、同用户、同商家/店的真实商家 REJECTED 不可变决定。拒绝日期不重置窗口。按订单读取“存在有效历史拒绝”并独立读取当前活动申请，不能只看订单最新申请状态；若 P3 A 获批，新申请等待/已批期间暂不可新建 AFS。
5. P1/P2/P3/P4 产品回执决定活动工单、重提及普通退款活动检查。已裁决的旧问题不能靠新 requestId 绕过终局；不能用描述文本哈希自动判断是否新问题。
6. sourceStage、资格起算时点、资格到期、ORDER 版本、拒绝 applicationId/decisionId（仅未核销分支）随创建保存为不可变来源快照，后续核销/新申请不覆盖历史。

候选 REFUND 新接口（不存在，待 CCR）：`RefundAftersaleFactsApi.readForOrder(orderId, storeId, QueryContext, DataSource)`，返回 `orderId/storeId/userId/merchantId/checkedAt/latestRejected{applicationId,decisionId,decidedAt,actorId,applicationVersion}/activeApplication{applicationId,status,version,decisionId}/refunds{refundId,status}`。无记录必须显式空；失败不得返回空；最新拒绝按 decidedAt、decisionId 固定排序，并经现有 requireDecision 级别的完整证明检查。只向内部可信调用者暴露最小事实，不含支付渠道号或拒绝自由文本。锁必须保留至 create 提交，使互斥对称成立。

候选 ORDER 新接口（不存在，待 CCR，替代早期 OrderAfterSaleApi 命名）：`OrderAfterSaleFactsApi.locate / requireCurrentEligible / current`、`OrderAfterSaleCommitApi.bindCreated / projectTransition / acquireRefund / requirePending / commitCreated / requireCreated`，以及用于不可变退款来源的 `OrderAfterSaleRefundFactsApi`。facts 包含真实订单关系、appointmentStart、verificationStatus/verifiedAt、当前 AFS/普通申请引用、真实实付/付款证明和用于 ADMIN 范围的 cityCode/scopeVersion。锁内读取，展示状态只能由 ORDER 决定。不能将市级权限的 cityCode 从客户端或旧商家自由文本补出。方法逐字段语义随总案 CCR 冻结，名称收敛不表示接口已存在。

### 3.2 同问题终局与新问题识别（P4，已补问、未获答复、不可实施）

已定的是首次裁决终局、旧工单不可重开及核销后新问题的既有资格；现有资料没有机器可验证的 problemKey。需要区别三类：撤回后再次提出（未发生正式终裁）；核销使未履约工单失效后发生服务完成新问题；已有非退款终局后声称又发生新问题。具体受理边界按 P4 回执冻结，不把推荐当默认规则，不延长任何新工单的原分支时效。

推荐产品候选 A：在原窗口内由仍具资格的本人提交新问题说明，生成受 P1 活动约束的 PENDING 新工单。运营受理前核对真实历史：重复问题关闭并引用原终局结论，真正新问题才受理。该核对是同一有权运营的受理范围判断，不是重审旧结论，不需要第二审批人或 MFA；不会生成新的 REJECT 决定。用户改变分类、描述、requestId 不自动成为新问题，系统不宣称能够自动证明问题同一性。

备选产品 B：同履约阶段已有非退款终局后不再受理新的售后；之后核销完成时依新的已核销阶段资格处理。该选择会限制同阶段新问题，属于须用户明确选择的产品分支，不能拿来作缺少人工判断时的默认兜底。上一版“运营预先发单次建单资格”已撤出本轮候选，不新增额外准入流程。

A 的具体协议候选与[总案§3](../../../ccr/CCR-W2-API-001/aftersale-workflow-proposal.md)统一：存在既往非退款终局时 `Create` 必带受保护且审核的 `newProblemStatement`。服务端记录此前真实终局工单集合及说明摘要，不信客户端省略历史；`Accept` 必带非空 `newProblemAssessment` 并绑定核对时的终局集合版本。受理核对证据包含真实运营ID、时间、关联历史集合/版本、理由摘要及授权版本，不创造机器推断的 problemId 作为准入事实。

重复问题使用独立 `CloseDuplicate(context, caseId, expectedVersion, priorFinalCaseId, reason)`：仅 PENDING → CLOSED，active_flag=0，原因码 `DUPLICATE_FINAL_PROBLEM`。服务端重验当前 `aftersale.handle` 权限及资源范围；`priorFinalCaseId` 必须是真实同单、存在正式非退款决定的旧终局工单，不接受 WITHDRAWN/INVALIDATED 或仅因重复关闭而没有正式决定的 CLOSED 工单冒充终局。命令同事务写关闭关联、ORDER投影、日志、进度Outbox与固定首回执，幂等参数包含该引用和原因；不建立新的 decision、REJECT 裁决或 refund_order，不改旧结论。它不是任意状态的通用关单入口。真正新问题才以 `Accept` 进入 PROCESSING，之后依原五类裁决规则处理。

P4 两个选项及上述字段/命令均未获答复、不可实施；只能留作一次审阅的候选。A 获批后在同一次 CCR 冻结条件必填、历史集合版本及 CloseDuplicate 回执；B 获批则不实现 A 的候选入口，按获批阶段规则阻断。描述哈希仅用于完整性/幂等参数绑定，类型仅用于分类，禁止自动依据哈希、相似度或类型相等判定同一/不同问题。最低验收覆盖同分类不同事实、不同分类同事实、漏报历史、跨单或非正式终局引用、重复关闭不产新决定、旧未履约失效后的新问题、撤回重提和到期时点。

### 3.3 状态映射和命令矩阵

活动状态固定 PENDING / PROCESSING / WAITING_SUPPLEMENT，active_flag=1；终态 RESOLVED / INVALIDATED / WITHDRAWN / CLOSED，active_flag=0。不存在 status=ACTIVE。旧 SQL 与 PRD 的“已处理/已关闭”映射须在 CCR 明示：建议正式裁决落 RESOLVED，返回“已处理/终局”，无独立再次关闭动作；不自动做 RESOLVED→CLOSED 的第二次业务写。按总案统一采用 RESOLVED；C p2911–2922 的“已关闭”作为读侧文案映射披露，不另用 CLOSED 保存正式裁决。CLOSED 的新增写入仅为 P4 A 获批后的 PENDING 重复问题关闭，不是复审入口。

| 命令 / 触发 | 起态 → 终态 | 条件与副作用 |
|---|---|---|
| create | 无 → PENDING | 全量资格、本人、证据通过后创建；P1 A 时唯一 active；同事务 ORDER 引用、日志、Created Outbox、首回执 |
| accept | PENDING → PROCESSING | 当前运营 aftersale.handle，写真实受理人/acceptedAt；若 P4 A 获批，存在终局历史时须先核对新问题并绑定历史集合版本；不是双人审批；现 Internal07 缺具体方法，需要补齐 |
| requestSupplement | PROCESSING → WAITING_SUPPLEMENT | 运营给对象 USER 或 MERCHANT、说明及未来截止时间，创建唯一活动 supplementRequestId；不让商家意见自行驱动状态 |
| submitEvidence/submitOpinion | PENDING / PROCESSING 保持；WAITING_SUPPLEMENT → PROCESSING | 来自本人/本店 OWNER 的审核通过内容，追加不可变批次；WAITING 时满足该轮必须对应当前请求、来自指定补充方且 now<deadline；等号及以后拒绝该轮提交，即使任务未执行。无关一方补充不擅自满足对方请求。C p2899–2902 明确补证后回处理中 |
| supplementTimeout | WAITING_SUPPLEMENT → PROCESSING | 数据库 now >= deadline 且 requestId/请求版本仍匹配；只标该轮超时，运营按现材料裁决，不自动退款/驳回。与补证同锁竞争；C p2903–2906 已明确 |
| withdraw | PENDING / PROCESSING / WAITING_SUPPLEMENT → WITHDRAWN | 仅 P2 获批后本人可执行；与终裁、核销同 guard 串行；终态不可撤回；保留原证据与审计，不重开、不重置资格时间 |
| closeDuplicate | PENDING → CLOSED | 仅 P4 A 获批后可用；真实同单 priorFinalCaseId 引用正式终局，原因 DUPLICATE_FINAL_PROBLEM；不新建 REJECT/decision/refund，详见§3.2 |
| verify | 活动 UNVERIFIED_POST_START → INVALIDATED | 已有 Contract48，核销与失效同事务；VERIFIED 工单不被旧核销失效 |
| decide | PROCESSING → RESOLVED（候选技术映射） | 五类裁决且只第一次；WAITING 先补证/超时回 PROCESSING，不能绕过当前待补充轮次；退款型与实际建单同事务候选详见下节 |

补充截止由运营设置（C p2189、M p1191、O p2362），不能发明统一24h补证时限。首次响应24h是 SLA 提醒，不是自动退款规则。O p2351“分派后计时”与 C p2894“受理开始计时”存在口径差异；本最小切片不伪造分派/SLA已完成，单运营受理与分派同刻可作为单运营实施映射提交 CCR，独立自动分派/升级后续验收。

补证期限技术候选（待 CCR）：当前轮次提交只在数据库 `now < deadline` 时可满足该轮；到期等号及之后，即使 timeout 任务尚未运行，也拒绝将提交计为该轮按时补证。timeout 在 `now >= deadline` 令 WAITING_SUPPLEMENT → PROCESSING；旧 round 不再可满足，旧任务也不能结束新 round。非指定一方追加证据不能触发该轮完成；超时回 PROCESSING 后，一般新证据仍按 PROCESSING 规则追加，保留实际收到时间，不虚记成期限内完成。

补证次数未给上限，不自行加“一次补证”。一轮只指定一个补充方，另一方可另轮请求；若需要同轮双方同时补充，应另明确 round/required-participants，不能任一上传就误判双方完成。PRD 图片“最多6张”按一次申请/一次补证批次候选绑定，历史批次不覆盖；总量是否整单最多6张在 CCR 明示其解释，不扩为无上限上传开放入口。

## 4. AFS-002 首次终局与真实退款来源

| decisionType（公共） | 金额 | 原子结果 |
|---|---|---|
| FULL_REFUND | 必須等于当前权威原实付；客户端确认金额必须比对，不能信 requestedAmount | 不可变决定 + 真实 FULL refund_order + ORDER 引用/禁止核销证明 + 渠道任务 + Outbox + 首回执 |
| PARTIAL_REFUND | `0 < amount < paidAmount`，两位小数，不超过实付 | 仅当前授权运营的 AFS 来源可建 PARTIAL；同上；渠道成功才 ORDER 进入部分退款终态 |
| REJECT | 必须为空 | 终局决定与原因、ORDER 去掉活动售后投影；按当前核销事实恢复已完成/待服务，不改历史完成事实 |
| RESERVICE | 必须为空 | 记录重新服务处理结论及双方可见说明，不自动重开订单/核销凭证、不生成第二笔预约、不增加一次改期额度 |
| OTHER | 必须为空 | 仅非退款处理结论，不通过自由文本触发支付/积分消费/异常资金调整 |

Internal07/HTTP10 公共枚举是 OTHER，旧 SQL 注释是 OTHER_NON_REFUND；建议公共及新持久枚举统一 OTHER，并对既有存储显式兼容映射，禁止无披露改值。FULL/PARTIAL 是 REFUND 金额类型，与上述决定枚举区分。

退款型决策建议一笔本地短事务完成“正式决定 + 实际建单”。来源现只支持普通 MERCHANT_APPROVED / MERCHANT_TIMEOUT_AUTO，不能复用或伪造普通申请。新增候选 `AfterSaleRefundFactsApi.requirePendingDecision / requireCreated`（替代早期 AfterSaleDecisionFactsApi）提供当前事务内待绑定决定、已创建退款绑定、原付款及授权版本/原工单阶段；REFUND 新建单入口只接受该真实来源。AFS→REFUND→ORDER 公共 API 调用，均同 DataSource 可写 READ_COMMITTED 和共享 store guard；不调用渠道网络。REFUND 在该事务写唯一退款单、执行绑定和 durable submit 任务；PAYMENT 发起前独立校验 AFS 决定与 ORDER 提交证据。

原因：普通退款 §39 的“批准后等待建单，核销仍合法且不推翻普通批准”不能套在未履约售后。AFS 若先写终局退款决定再异步建单，会留下核销使工单失效而不可执行的终局结论。候选原子建单将边界明确为：核销先提交 → 旧工单 INVALIDATED，退款裁决不提交；AFS 建单先提交 → 以后核销禁止。若业务 Owner 坚持两阶段，必须另有尚非终局的决定准备状态及核销取消语义，属于额外重大协议，不能暗加。

不因已到售后窗口末日就强制结案；7天限制发起，不是运营裁决完成截止。已受理工单可能在7天后裁决。O p2365 要求退款裁决判断是否已分账：真实未分账证明/资金路由必须由资金 Owner 公共接口提供；候选统一为 `RefundFundingEligibilityFactsApi.requireForDecision / requireForFirstSend` 两阶段资格事实，不另造 AFS 资格事实源；任何 AFS 组合 port 只能调用该权威接口。未知不可当未分账。已分账追回（回退/下期冲抵/冻结）未在本次现码核实，不得把 AFS 内部建单宣称追回已交付。CCR 须明确切片先限定已有真实可退资金路径并默认关闭，资金事实/追回另项接续；禁止添加测试常量“全部未分账”生产适配。

RESERVICE 已是允许的裁决种类；其执行承诺的自动履约协议未给出，首切片可记录人工处理结论而不能声称已完成再次服务。不得为了实现它扩出新订单/重复核销规则。REJECT 的核销码恢复必须由既有 ORDER/VER 权威入口判断；原工单没有生成退款单且码未消耗时不可私自改凭证状态；C p2922 提及重新生成码，具体接既有码获取/轮换能力而非 AFS 跨表重发。

## 5. 幂等、事务、并发与事件

所有写命令遵守23号二进制五元组 `(namespace,operatorType,operatorId,scope,requestId)`：`aftersale.create` scope ORDER:id；`aftersale.accept/supplement.request/evidence.submit/opinion.submit/withdraw/duplicate.close/decide` scope AFTERSALE:id；补证超时仅 trusted task 确定性 requestId，不允许用户自报 SYSTEM。

独立 Admission 先持久绑定加密规范参数；业务失败仍保留参数绑定；同 key 同参返回首回执，同 key 异参冲突。首回执包含 commandId/orderId/afterSaleId/status/version/occurredAt/evidenceBatchId?/supplementRequestId?/decisionId?/refundOrderId?，时间/版本固定，不随工单后续状态改变。重放须复验当前会话与资源授权、校验历史命令/决定证据；不得因为后续工单状态改变就无法重放旧首回执。不同 key 重复创建按已批 P1 返回业务冲突，不能将别人/另一次创建首回执当本次成功。

建议锁序：Admission → shared store guard → ORDER → REFUND事实 → AFS → ADMIN最终授权 → 证据当前状态/绑定；OSS 上传、内容安全外部调用在主事务前完成，最终事务仍验证扫描通过的同一 hash/version。实际增量必须与 PrivateAsset 的授权回调锁序合审，避免读附件先锁 OSS 再锁 AFS 而业务写先 AFS 再 OSS 的反序。可用预校验短阶段 + 最终同序绑定接口解决，不能只列锁顺序而不验证。

创建/状态变化/决定均同事务写 status_log、脱敏 audit、ORDER 投影和 Outbox；只有退款最终渠道成功后由现有来源对应处理器更新金额/释放预约。售后创建、待补证、撤回、非退款裁决不释放资源。订单维度强并发验证覆盖 create×普通申请、withdraw×decide、evidence×timeout、decide×verify、不同 key 决策争抢及旧任务不覆盖新工单。

事件已有 AfterSaleCreatedEvent.v1 / AfterSaleResolvedEvent.v1 / AfterSaleInvalidatedEvent.v1，只能按原载荷发，不把自由文本/图片/手机号塞入事件。受理、要求补证、提交补证、撤回及截止恢复任务的精确事件/通知意图尚缺，应在同一 CCR 定义最小版本载荷（case/order/actor-role/status/发生时间/补证轮次及截止；ID String）。原 Created 的 afterSaleType 须与批准类型字典一致；Resolved amount 仅退款型有值。AFS 结果事件不授予资金权力，不作为实时核销门禁。首次失效事实由核销同步事务建立；补发通知也要唯一键，不能事件消费二次改变工单。

## 6. 证据保护、业务权限与可见字段

### 6.1 附件链路必须完整

拟新增 THIRD_PARTY 私有用途 `AFTERSALE_EVIDENCE`（候选名称）及 AFS typed resource/read API；不改旧商家材料含义。上传者是当前可信 USER（买家或 OWNER）；授权上传/解析先绑定真实主体和用途，assetId 本身不是授权。

流程：上传暂存 → 病毒/内容安全扫描及图片校验 → READY → create/submitEvidence 最终事务复核 owner、用途、READY、objectSha256/objectVersionRef/factVersion → 绑定新不可变 evidenceBatchId。SCANNING/REJECTED/QUARANTINED/RETIRED、他人素材、服务封面、错用途一律不能入卷；扫描未完成不先建“已完成证据”。外部审核不可在持共享门店锁时发网路调用。失败留暂存资产供原请求恢复，不能产生半批入卷；孤儿素材清理归 THIRD_PARTY 的已有/另立生命周期，撤回不删除已入卷证据。

AFS evidence batch 记录 submitterType、真实 actorId、caseId、补充轮次、提交时间、textCiphertext、审核 policyVersion/hash、不可变 asset refs（assetId/hash/version/mediaType/bytes）。每次补证追加而非覆盖。正文、商家说明、补证原因、终裁原因审核通过再入卷；不把原文放 status_log.remark/异常/Outbox/幂等索引。旧 evidence.file_url 不能沿用为公开 URL。

最小第一切片只支持 PRD 已明确的图片（用户及商家单次最多6张）和文本，不借旧 SQL FILE 枚举开放任意文件。C/M 描述及商家说明 10–500 字；诉求金额 0.00–实付仅作用户诉求、不是出款授权；费用争议金额条件按 PRD字段。运营最终处理结论采用三端可展示的≤500字候选，并保留非空原因；旧 SQL 1000只是容量不代表放宽 UI。

读取须创建短期、绑定 session/generation/actor/caseId/evidenceBatchId/assetId/用途/对象版本的 read grant，发放和消费均当前鉴权、逐次访问审计；不输出永久裸 URL、bucket/key、可重放无主体令牌。读授权不得沿用 merchant.identity.reveal：AFS 的本店/本人/运营处理权限依据不同。下载字节前再次查资产隔离状态、案例关系和当前权限；撤权/错店/令牌转借/批次不匹配拒绝。

### 6.2 可见性有 PRD 来源，不默认全公开

M p1180 明确本店处理人可看问题描述、证据、进度；M p1278–1283明确用户图片只给授权处理人员，C p2166/M p1193/C p2902要求商家说明和举证同步用户及运营；O p2357/2364要求运营查看卷宗并审计。因此候选授权矩阵为：

| 身份 | 读取 | 写入 / 禁止 |
|---|---|---|
| 订单本人 | 本工单、订单最小快照、双方入卷说明/证据、补证期限、处理时间轴和正式结论 | 创建、本人补证、P2允许时撤回；不得自报 sourceStage/终裁/实付 |
| 当前本店 OWNER | 仅真实同店工单；订单号/快照服务名/时间/实付、用户问题类别/诉求/合规申请金额/描述/经授权证据、进度、补充截止、结论 | 审核后的说明、补证、意见（同意诉求/部分同意/有异议/需用户补充）；不能受理、改变状态、终裁或建部分退款；“部分同意”只是意见 |
| 当前授权运营 | 资源范围内完整业务卷宗；证据访问需用途与审计；默认不解密手机号/证件/渠道原始报文 | aftersale.handle 受理/补证要求，aftersale.decide 五类终裁；无 MFA/第二审批人 |
| 超管 | ADMIN 当前授权规则覆盖已部署批准动作，全平台范围 | 不绕过交易/资料缺口，不恢复旧失效工单 |

商家读取不包含用户手机号明文、身份证件、宠物未授权健康档案、运营内部敏感备注、渠道流水或他店工单；这些不是上述商家工单展示字段。既有脱敏/专门用途解密规则不因售后取消。列表不返回附件 grant，详细访问再授权；任何查询和旧成功回执都不能绕过当前权限。

拟注册 ADMIN 动作沿已有运营清单：aftersale.read（详情/列表）、aftersale.handle（受理/补充请求/处理）、aftersale.decide（终裁）。不引入新审批权限；分派不在最小实现时不提前注册 assign。集合入口需扩 checkCollection 的真实 action 支持，实际行按当前 ALL/CITY/MERCHANT 范围查询并检查，不以伪造资源 id 通过授权。Afs resourceType/merchantId/cityCode/scopeVersion 从锁定的 ORDER/AFS 事实组装。

## 7. 可实施的 DTO 草案（全部为候选增量）

公共 ID、version 为十进制 String；时间 UTC 毫秒；内部金额 BigDecimal，HTTP后续采用两位小数String。CommandContext 由可信边界组装，不是 HTTP body 身份。下列命令可采用统一 AfterSaleCommandApi，禁止误报已存在：

- `Create(context, orderId, typeCode, demandCode, description, requestedAmount?, evidenceAssetIds[])`。最多6个唯一asset；无 userId/sourceStage/paidAmount/verifiedAt 参数；若 P4 A 获批，存在既往非退款终局时补条件必填 newProblemStatement。类型语义见 C p2160（质量/未履约/费用/宠物安全/其他），诉求语义见p2162；代码值由已批准配置映射，不把 HTTP SERVICE_DISPUTE 样例当完整产品字典。
- `Accept(context, afterSaleId, expectedVersion)`，若 P4 A 获批则补条件必填 newProblemAssessment 与终局集合版本；`CloseDuplicate(context, caseId, expectedVersion, priorFinalCaseId, reason)` 仅在该候选获批后纳入，详见§3.2；`RequestSupplement(context, afterSaleId, expectedVersion, targetParty, reason, deadline)`。
- `SubmitEvidence(context, afterSaleId, expectedVersion, supplementRequestId?, text?, evidenceAssetIds[])`，文本/图片至少一项有效；`SubmitMerchantOpinion(context, afterSaleId, expectedVersion, opinionCode, explanation, evidenceAssetIds[])`，不接受 merchantId 当权限。
- `Withdraw(context, afterSaleId, expectedVersion)`，仅 P2 批准后存在可执行入口。
- `Decide(context, afterSaleId, expectedVersion, decisionType, refundAmount?, reason)`；金额严格矩阵，禁止系统来源/已退款号由客户端指定。
- `GetCase(context, afterSaleId)` / `CheckEligibility(context, orderId)`：返回端别裁剪的 CaseView；资格包括 eligible/sourceStage?/deadline?/blockingReason/activeCaseId?，不返支付渠道事实。
- `HandleSupplementTimeout(trustedTaskContext, afterSaleId, supplementRequestId, expectedDeadline)`：提前调用 RetryAt，已提交/旧轮次 NOOP；now>=deadline 才结束当前轮，当前轮补证要求now<deadline；真实损坏来源失败并隔离；任务身份从持久 TaskFacts 验证，不接受自报 SYSTEM。
- 候选 `AfterSaleCaseFactsApi.requireCurrent(orderId, storeId, expectedCurrentCaseId, context, DataSource)`：提供 AFS 本域 NONE/活动/历史叶子事实及 caseId/status/sourceStage/activeFlag/version，核验本域正反关联、预期当前指针和孤儿记录；没有工单必须返回明确 NONE，损坏事实失败关闭。供 ORDER.current 通过公共 API 核对，禁止 ORDER 跨查 AFS 表；该叶子方法不回调 ORDER.current 或 AFS.checkEligibility，避免递归。与退款来源的 AfterSaleRefundFactsApi 分开；名称及参数只是统一候选，待正式 CCR 批准。
- 候选 `AfterSaleRefundFactsApi.requirePendingDecision / requireCreated`：含 case/order/user/merchant/store、sourceStage、决定id/type/amount/reasonHash/decidedAt/commandId、证据批次hash集合、当前事务证明和正常付款来源引用。保留授权发生时 authzVersion 和 scopeVersion；渠道恢复验证已提交授权事实，不因原运营离职撤权撤销已经合法创建的退款。
- 候选私有附件 API：`resolveOwnedAftersaleAssets(actor, assetIds, scope)`、`bindEvidenceBatch(...)`、`issueAftersaleReadGrant(...)` / `consumeAftersaleReadGrant(...)`。可在 THIRD_PARTY 新类型接口实现或通用化现 API；CCR 必须明确新 resourceType，禁止挪用 applicationId/revisionId。AFS 提供只读 `proveEvidenceAccess` 给 boot 组合授权，不跨读 AFS 表。

现有 Error12 的 AFTERSALE_NOT_ELIGIBLE/NOT_FOUND/STATE_NOT_ALLOWED/ALREADY_INVALIDATED/DECISION_FINAL/REFUND_BLOCKED_BY_VERIFICATION/AMOUNT_INVALID 可复用；幂等冲突、活动普通退款、活动工单、补证已过期、证据未就绪/拒绝等若需专码纳入 Registry 同次 CCR。外部查询越权与不存在统一防枚举；内部损坏证明保留脱敏可定位错误。

## 8. 最小切片、模块允许清单与验收承诺

| 切片 | 允许模块/文件类（待 root 分配） | 可审阅成果与验证 |
|---|---|---|
| S0 契约冻结 | Contract Owner 管理 SSOT/PRD补充（仅批准产品回执）、API/SQL/Storage/Event/Error/任务映射及 CCR | 四项回执、真实 DTO/枚举/状态映射、锁序、证据可见性、资金路径及迁移策略；普通 Worker 不直接改保护文件 |
| S1 资格与创建 | aftersale-api/biz；order-api/biz AFS事实与投影；refund-api/biz 按订单拒绝/活动事实和双向互斥；boot 装配 | 真实拒绝来源、已核销无拒绝直接申请、数据库7天边界、并发创建/普通退款、Admission/ORDER/AFS/Outbox全回滚；修复旧核销历史证明读取兼容 |
| S2 受理/证据/撤回 | aftersale-api/biz；thirdparty-api/biz AFS用途及typed grants；admin-api/biz 售后动作/集合；boot 当前身份与授权组合 | OWNER同店/跨店、本人/他人、撤权重放、隔离附件/错用途/扫描状态、不可变批次/内容安全、待补充轮次及超时、撤回×终裁 |
| S3 五类终裁与真实出款来源 | aftersale-api/biz；refund-api/biz AFS FULL/PARTIAL来源；order-api/biz CREATE_REFUND与投影；payment-api/biz 必要来源复核；boot/tasks原恢复装配 | FULL/PARTIAL/REJECT/RESERVICE/OTHER、核销竞争、不可变来源/付款绑定、只一次退款、原号恢复、真实渠道成功释放；资金可退事实依赖需同次披露 |
| S4 集成验收 | 各域本地测试及 pet-boot 隔离MySQL验收、既有架构/持久化检查 | 核销/普通退款既有回归；新核销售后不破坏旧核销重放；事件最小载荷/敏感泄漏检查；重启/任务旧轮次/回滚注入 |

不允许 AFS biz→REFUND/ORDER/ADMIN/THIRD_PARTY biz；只依赖各域 api 或本域 port。生产 SQL 全放本域 MyBatis XML；不跨表直读、不新增内联 JDBC。需要真实适配处可在 boot 组合，测试替身明确只用于测试。接口未配置、字典/审核/资产签名/资金来源不完整时默认开关关闭，不能以返回true的适配替代。

精确验收至少包括：

1. UNVERIFIED 缺拒绝禁止、真实 REJECTED允许、旧拒绝不会重置期限；VERIFIED无需先拒绝；沿 Scheduler09 §33 的包含边界测试 t0、到期前1ms、到期等号均允许（其他资格成立），到期后1ms拒绝新申请；查不到和依赖失败不同。
2. P1/P2/P3/P4 按最终用户回执覆盖两方向并发；withdraw后新ID仍用原资格；正式裁决不可用新key复审；核销后新问题保留新窗口。
3. 同key同参固定首回执、异参冲突、回滚后参数仍绑定、不同key活动冲突；当前撤权/会话失效拒绝重放；后续新工单不损坏公开历史核销证明及普通退款历史事实校验，普通退款活动互斥仍单独执行；旧核销成功首回执重放作为回归验证，不预断现有实现必坏。
4. 真实 ADMIN 动作/数据范围及当前 OWNER店归属；超管仍无法绕交易硬约束；证据读grant在发放与消费之间撤权/隔离均拒绝。
5. 扫描未完成/他人asset/错用途/对象版本变化/审核拒绝不产生证据批次；已入卷不可覆盖；双方可见字段符合PRD，访问有审计且无URL/明文泄漏。
6. 补证与超时竞争唯一迁移；now=deadline时即使任务未运行也拒绝满足该轮，timeout等号执行；旧轮次/非指定方不能完成当前轮，超时后一般新证据保留实际时间；旧补证轮次任务不可影响新轮；超时只回处理中，不自动裁决；撤回/核销/决定后超时 NOOP。
7. 退款型正式决定×真实建单原子；核销赢家使未履约旧工单不可退款；AFS赢家使后核销拒绝；部分金额0/等于实付/超实付/精度拒绝；商家意见不能出款。
8. 所有持久化点失败整体回滚；提交后丢响应按原回执恢复；任务/渠道 UNKNOWN只查原退款号；只有最终成功更新订单金额/释放预约；原迟到支付/拒单/普通退款回归。

本轮只完成阅读与方案文件，不运行 Maven、不修改实现、Schema 或共享契约、不提交 Git。上述测试尚未执行；AFS-001/AFS-002、完整 VER-002/QA-004、私有证据端到端、资金追回、HTTP/小程序、通知实际送达均不得标完成。下一步由 root 收拢产品回执及整体 CCR，再分配批准范围内的实现。
