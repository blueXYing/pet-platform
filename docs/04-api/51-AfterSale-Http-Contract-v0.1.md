# AfterSale HTTP Contract v0.1

状态：APPROVED / IMPLEMENTATION_IN_PROGRESS，2026-09-30；不表示测试已经通过或生产已开放。授权为用户在“先冻结售后HTTP契约、实现C本人/商家OWNER/获权运营非出款HTTP，再接页面”之后回复“那么请你开始”。[CCR](../../planning/ccr/CCR-W2-API-001/aftersale-http-proposal.md) 记录技术范围。产品规则沿 [SSOT §40](../00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md)、[PRD31](../01-prd/31-售后流程人工裁决补充-v1.0.md) 和 [Contract50](50-AfterSale-Workflow-Contract-v0.1.md)。

## 1. 本批边界与身份

本批为三端非出款 HTTP、按当前权限限定门店的分页和私有证据接口；无页面、STAFF、跨店运营聚合、生产迁移/开关授权。FULL_REFUND/PARTIAL_REFUND 的内部 A2 能力不改，但公开 HTTP 始终关闭；真实资金 Provider 不阻塞非出款流程开发。

C 路由严格 USER 本人；merchant 严格当前 OWNER，不能仅因同一个 USER 是买家而放过商家路由；admin 严格 ADMIN_WEB 当前会话、aftersale.read/handle/decide 与当前 MER 资源范围。RouteParty=USER/MERCHANT/OPS 由路由构建，域事务和成功重放再次验证。没有客户端 context/actor/session/party 业务授权字段。

FROZEN 当前用户、商家/店按已批准只读语义可查看获权历史/待处理工单及图片；申请、上传、补证、意见和撤回仍需 ACTIVE 及原写权限。资格 GET 的 eligible 仅表示订单规则资格，不等于账号具有写权限。MER.requireOwnerRead 验真实 OWNER 关联且允许 ACTIVE/OFFLINE/FROZEN；requireOwner 写规则不变。新单、详情、证据及回执均不因此开放给 STAFF。

## 2. 路由清单

以下均以 `/api/v1` 为前缀。表中 `{afterSaleId}` 即实现内部 `caseId` 路径变量，公共含义相同。

| Method | Path | 当前权限/用途 |
|---|---|---|
| GET | /c/aftersale-options | 当前 MINIAPP 用户读取同一申请校验目录 |
| GET | /c/orders/{orderId}/aftersale-eligibility | 本人订单资格 |
| POST | /c/orders/{orderId}/aftersales | 本人创建 |
| GET | /c/aftersales | 本人分页 |
| GET | /merchant/aftersales | OWNER 指定店分页 |
| GET | /admin/aftersales | aftersale.read 指定店分页 |
| GET | /{c,merchant,admin}/aftersales/{afterSaleId} | 明确端别获权卷宗 |
| POST | /{c,merchant}/aftersales/{afterSaleId}/evidence | 本人/OWNER 追加证据 |
| POST | /c/aftersales/{afterSaleId}/withdraw | 本人终局前撤回 |
| POST | /merchant/aftersales/{afterSaleId}/opinion | OWNER 意见，非终裁 |
| POST | /admin/aftersales/{afterSaleId}/accept | aftersale.handle 受理 |
| POST | /admin/aftersales/{afterSaleId}/supplement-requests | aftersale.handle 要求指定方补证 |
| POST | /admin/aftersales/{afterSaleId}/close-duplicate | aftersale.handle 引用同单真实旧非退款终局关闭重复问题 |
| POST | /admin/aftersales/{afterSaleId}/decisions | aftersale.decide，公开只执行三类非退款决定 |
| POST | /c/aftersale-evidence-assets | C/M 共用本人图片预上传 |
| POST | /{c,merchant,admin}/aftersales/{afterSaleId}/evidence-batches/{batchId}/assets/{assetId}/read-grants | 按当前端别签发只读 grant |
| GET | /{c,merchant,admin}/aftersale-evidence-read-grants/{token} | 当前同端同会话单次消费 |

旧草案 `/admin/aftersales/{afterSaleId}/decision` 单数未实现，本批正式路径为 `/decisions`，不新增旧路径别名。不存在售后复审、商家终裁、公开退款执行或后台补写到账接口。

## 3. 公共输入与回执

所有 POST 要求唯一 `X-Request-Id` 完整 UUID 原值，遵 API23，不 trim、不改大小写。Authorization 来自唯一 Bearer；成功重放仍查当前身份和原动作权限。JSON 严格解析：对象、字段类型、唯一键及单个文档；拒绝未知字段、重复字段、隐式字符串/数字转换、附加文档及未知/重复 query。主 JSON body 最大 32768 字节；不接受 ctx/actor/orderId 等额外主体字段。路径 ID 为正十进制 String（无前导零，最大 Long.MAX_VALUE），版本为非负十进制 String；不得用 JSON number 传 ID/版本。

金额为精确两位 String，形式 `(0|[1-9][0-9]{0,15})\.[0-9]{2}`，拒绝 JSON number、科学计数和额外小数。optional nullable 字段缺省等于 null，必填字段不可省略/null；evidenceAssetIds 必填唯一 ID 数组，0..6，不将 null 吞成空数组。时间输出全部 `YYYY-MM-DDTHH:mm:ss.SSSZ`；补证 deadline 输入同格式、真实有效时间且未来，不接受其他偏移或亚毫秒。

| DTO | 必填字段 | 可省略/null 字段 |
|---|---|---|
| Create | typeCode(1..64),demandCode(1..64),description(10..500),evidenceAssetIds | requestedAmount,newProblemStatement(10..500) |
| Evidence | expectedVersion,evidenceAssetIds | supplementRequestId,text(10..500)；文字/图片至少一项 |
| Opinion | expectedVersion,opinionCode,explanation(10..500),evidenceAssetIds | supplementRequestId |
| Withdraw | expectedVersion | 无 |
| Accept | expectedVersion | newProblemAssessment(1..500),expectedFinalSetVersion(64位小写hex) |
| SupplementRequest | expectedVersion,targetParty(USER/MERCHANT),reason(1..500),deadline | 无 |
| CloseDuplicate | expectedVersion,priorFinalCaseId,reason(1..500) | 无 |
| Decision | expectedVersion,decisionType,reason(1..500) | refundAmount |

字符串不可空白。问题/诉求必须由真实配置目录验证；意见限定 AGREE/PARTLY_AGREE/DISAGREE/NEED_USER_SUPPLEMENT。P4 原规则不变：已有非退款终局时必须提交新问题说明，Accept 必须有当前终局集合版本与运营评估；重复问题只 PENDING→CLOSED，引用真实同单旧终局，无新 decision。HTTP 不以描述 hash 自动判断新问题。

公开 Decision 的 REJECT/RESERVICE/OTHER 要求 refundAmount 为 null，走真实决定事务。FULL_REFUND/PARTIAL_REFUND 仅作为已知但关闭的类型：当前真实会话及 decide 动作权限通过、字段格式与正金额合法、OPS 资源读取获权后，固定 503 COMMON_DEPENDENCY_UNAVAILABLE，绝不调用内部 decide/REFUND；内部退款开关或 Provider 不改变本限制。格式错误仍 400，缺会话/权限仍 401/403。

JSON envelope 固定四项 `code/message/data/traceId`，不加 success。成功 code=SUCCESS/message=ok；失败 data=null，保留安全错误码及 traceId，不暴露内部异常/SQL。创建真实首次提交 201，持久成功重放 200，依据 CreationResult，不根据当前工单状态/时点猜测。其余主 JSON 成功 200。

Receipt 的 data 固定 commandId/orderId/afterSaleId/status/version/occurredAt/evidenceBatchId/supplementRequestId/decisionId/refundOrderId；后四项按动作可 null，不省略。同 key 同参返回原业务回执，不重做审核；同 key 异参或换端 409。明确 HTTP evidence 的 routeParty 写入既有 namespace/scope 的 canonical 参数，旧内部格式不变。

2026-10-01 已批准 [CCR-AFS-CONFLICT-001](../../planning/ccr/CCR-AFS-CONFLICT-001.md)：当前动作权限在比较工单版本前验证；未成功的新命令在业务事务中确认 `expectedVersion` 不等于当前版本、并复验本次当前动作授权后，返回 409 `AFTERSALE_VERSION_CONFLICT`。P4 有真实旧非退款终局时，缺少/空白 assessment、缺少/非法格式的集合 hash 仍为400；合法 hash 与当前历史终局集合不等且本次授权未变化，返回409 `AFTERSALE_FINAL_SET_CONFLICT`。两码仅表示本次命令已明确拒绝，未产生证据/状态日志/业务迁移/决定/ORDER投影/Outbox；独立原参数准入绑定仍保留，同 UUID 不可换参。当前权限失败优先401/403；授权版本复验变化仍 `COMMON_CONFLICT`，请求锁忙也仍 `COMMON_CONFLICT`，均不能当这两种确定拒绝。已提交成功 UUID 重放在版本/目录/内容审核之前返回原回执并重验当前权限。前端收到确定码后刷新卷宗/全部历史，重新人工确认后使用新 UUID；不能用新 UUID 盲重试旧决定。

## 4. 查询与信息范围

申请目录 `GET /api/v1/c/aftersale-options` 不接受任何 query（含空 query）或 body，不要求写入 UUID。只允许真实当前 MINIAPP USER，返回前再次验证同一会话权限版本；FROZEN 当前账号可以读取，创建仍要求 ACTIVE。它随售后 HTTP/workflow 同一默认关闭开关注册，无匿名、商家/运营目录别名或调用方身份字段。

关闭售后 HTTP 时不注册该控制器/映射；当前有效会话的请求在已启用C安全链按既有catch-all deny语义返回403。启用时只放行该GET至原CBearer真实会话过滤器和域读授权，不放行其他方法。

成功 data 精确为 `{typeOptions,demandOptions}`；每组是1..100项的数组，每项精确为 `{code,label}`。code 为 `[A-Z][A-Z0-9_]{0,63}`，每组内唯一，按 ASCII code 升序；两组可各自拥有相同 code。label 为1..64个 Unicode 标量/码点，拒绝孤立 surrogate，不允许首尾空白；首尾空白集合与 ECMAScript `String.trim()` 一致：TAB/LF/VT/FF/CR/SPACE、U+00A0、U+1680、U+2000..U+200A、U+2028/U+2029、U+202F、U+205F、U+3000、U+FEFF。页面使用 label 展示、code 提交，不将展示文案当code。

2026-10-01 用户“批准” [CCR-AFS-PAGE-OPTIONS-001](../../planning/ccr/CCR-AFS-PAGE-OPTIONS-001.md) 后冻结上述技术表面，无新问题/诉求产品字典。读目录及创建校验使用同一完整不可变 ReasonPolicy 配置快照。生产配置仍明确提供 `pet.aftersale.type-codes`/`demand-codes`，另以 `pet.aftersale.type-labels[CODE]`/`demand-labels[CODE]` 提供每个已批准代码的名称；每组 label key 集合必须与 code 集合完全相同。缺少、重复/非法 code、缺/多 label、空白/非法 label、数量超限或来源缺失均不得回成功空列表/半份目录，也不得硬编码回退。已开启且目录适配不完整时，读取及新创建均503 `COMMON_DEPENDENCY_UNAVAILABLE`；现有成功创建重放不重查可变目录。原启动校验遇未提供/非法 code 配置仍阻止启用；默认不开启、不填生产示例代码或名称。隔离验收 `QA_*` 仅为测试配置。

列表参数只允许 page（1..10000，默认1）、pageSize（1..50，默认20）、status、orderId。M/O 另必须 merchantId+storeId；C 禁止这两参数。status 为 PENDING/PROCESSING/WAITING_SUPPLEMENT/RESOLVED/INVALIDATED/WITHDRAWN/CLOSED，未知/空值拒绝。查询条件只能缩小权限范围。除列表外主查询不接受 query 参数。

CasePage 固定 page/pageSize/total/items。CaseSummary 固定 afterSaleId/orderId/merchantId/storeId/status/version/sourceStage/typeCode/demandCode/requestedAmount/createdAt/deadline；不含描述、原因、证据、电话、渠道、付款账户或 DisplayOrderStatus。

只分页 workflow_revision=1 的真实工单；SQL 本域权限谓词先于 COUNT/LIMIT，稳定 created_at DESC,id DESC。M/O 在 shared store guard 下验证当前 OWNER 或 ADMIN+MER 真实城市范围，不用工单创建城市快照。C 只不可变 user_id=当前本人。读取前及返回前复验相同权限版本，变化则失败关闭；不全表读后过滤，不跨域表，不逐行查询全部外域。READ_COMMITTED 不承诺并发分页快照；来源证明不一致不返回未证实行。[SQL/Storage51](../03-database/51-AfterSale-Http-Storage-v0.1.md) 只补两项分页索引。

Eligibility 固定 eligible/sourceStage/deadline/blockingReason/activeAfterSaleId，可空值显式 null；真实资格沿 Contract50 闭区间 anchor<=now<=anchor+7*24h，到期+1ms拒绝新申请，已核销新问题无先拒绝前置。本人会话首尾复验。

CaseView 字段由 OpenAPI 的 AfterSaleCaseDetail 完整定义：允许当前参与方查看已入卷 description、新问题说明、当前补证要求、终裁原因、双方不可变证据批次；不含用户电话、内部敏感备注、渠道事实。EvidenceBatch 只 batchId/submitterType/text/opinionCode/submittedAt/assetIds；图片须下节真实私有读取，不提供对象裸 URL。列表和详情均不自行派生 DisplayOrderStatus。

## 5. 私有图片

预上传仅 multipart 的一个非空 file part，无额外参数/part；C/M 共用 C 路由 ACTIVE 当前用户，固定 AFTERSALE_EVIDENCE，用途不得自报。仅 JPEG/PNG，校验图片签名与声明类型，最多10MiB，经真实扫描/规范化后 READY；入卷仍验证 owner/purpose/hash/version 与本店 OWNER。返回 data=assetId/status(READY)/objectSha256/mediaType/bytes；首次201/同参重放200。

签发 body 仅 reason（非空1..500 Java字符），需 UUID；data=readUrl/expiresAt。readUrl 是当前端相对 API 路径，非对象 URL；token 为43位 base64url，五分钟有效、单次消费，绑定真实 case/batch/asset/端别/会话/当前权限及对象版本。端别纳入持久 proof_hash，不能跨端消费。FROZEN 的获权读取允许签发这类只读 POST，不等于开放写操作。

消费 GET 无 body/query；成功为 image/jpeg 或 image/png 二进制（水印处理、最多20MiB），Content-Disposition=attachment; filename="aftersale-evidence"，不是 JSON envelope。签发、消费和网络读取后返回前均复验当前会话/业务权限/资产隔离与版本。失效或已消费410；不允许用任意他人 assetId/裸 URL 读取。

全部主接口和图片响应使用 Cache-Control:no-store, private、Pragma:no-cache、X-Content-Type-Options:nosniff。

## 6. 错误、开关与验收

主状态映射：400 参数/金额；401 会话；403 权限；404 资源；409 资格、互斥、活动工单、CAS、幂等、终态、旧轮/过期补证；422 内容审核；429 限流；503 来源/任务/依赖不可证或公开退款关闭；意外内部错误500安全包装。完整码见 [Error12](12-Error-Code-Registry-v0.5.md)。图片另有409未READY、410grant失效、413体积、415媒体、422安全处理拒绝。

pet.aftersale.enabled/http.enabled/worker.enabled/refund.enabled 全部默认 false。HTTP 要求 workflow=true 且 pet.auth.admin.enabled=true；workflow 继续要求 pet.refund.application.enabled、pet.verification.completion.enabled、pet.private-assets.enabled、pet.auth.c.enabled。worker 独立开关，生产启用前须启动补证任务 worker；隔离测试可关闭自动调度并主动驱动真实 worker。无恒真身份/审核/原因/资金适配。

OpenAPI11 是本批可机读输入/输出表面。验收包括三端真实会话、OWNER/CITY当前范围、冻结只读、严格 JSON/金额/时间/UUID、201/200、同键换端、补证与非退款终局、证据单次消费和旧内部契约回归。具体通过记录由本批 closeout/CI 提供，本契约不冒充已通过。
