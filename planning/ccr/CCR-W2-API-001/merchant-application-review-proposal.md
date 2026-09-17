# 商家入驻申请与真实审核闭环 CCR 候选

状态：`PRODUCT_DECISIONS_ACCEPTED / CONTRACT_SYNC_PENDING / NOT_IMPLEMENTED`

2026-09-17最新：用户明确回复“四项建议确认”，§1.1四项业务规则已获批并同步SSOT §27及[PRD26](../../../docs/01-prd/26-商家申请审核人工裁决补充-v1.0.md)。以下“待确认/未获批”对这四项的描述仅保留提案语境，后续不重复请求确认；其余具体接口/存储/事件仍按本CCR细化同步，不以本回执宣称全部能力已实现。PR52尚未获合并授权。
基线：`5ee8f750f6f5c5f26f344fc82d1538ad3dd8359d`（PR51 已合入）
Owner：MER-001（申请、材料版本、审核业务事实）＋ AUTH/ADM（运营会话、动作与数据范围）＋私有资产/OCR 适配 Owner（仅提供证据，不决定业务状态）。

本文只为现有 MER-001 补齐可评审的申请审核 Contract，不修改权威 docs，不批准新产品规则。电子协议换版、下线存量语义、OWNER 使用真实 USER 操作者均已批准，不在本 CCR 重问。外部支付签约 Provider 已被 SSOT §26/PRD25 覆盖；本文的 Provider 仅可能是尚未选定的 OCR/私有对象存储技术适配，不得参与“已签约”判定。

## 1. 原始依据与冲突收敛

直接核对原始 Word，而非仅引用历史摘要：

- C 端 PRD §5.1.23、§5.4.3：个人中心发起；草稿和断点续填；商家名称、联系人、11位手机号、类型、城市、定位、营业执照、身份证正反面等；提交后只读；驳回后修改重提；同一营业执照或同一身份证不得重复；营业执照/身份证 OCR 与有效期校验；历史审核留痕。
- 商家端 PRD §5.7、§6.6：商家/门店资料、草稿→审核中→通过/驳回、审核中只读、驳回修改重提、同一主体拦截、敏感证件脱敏。
- 运营端 PRD §6.2.1、§7.1：审核列表/详情、单人领取和释放、通过/驳回/补正、意见与内部备注、医院类行业资质、OCR 失败允许人工录入比对、敏感原件受控查看、每个变化可追溯实际审核人和时间。
- 27号商家域契约与 Storage27：正式申请投影固定 `DRAFT/REVIEWING/APPROVED/REJECTED`；至少含 applicationId、ownerUserId、merchantId、version、reviewedAt 和审核审计引用；`APPROVED` 必须来自真实审核动作，禁止从 `merchant.status` 重建。
- 现状：Schema06 无申请表；OpenAPI/HTTP 无申请草稿、提交和运营审核端点；现有 `ApplicationReviewFactsReader` 只是严格失败关闭的模块端口，不能用 fixture 变成生产审核来源。

以下两处原 PRD 表达不完全一致，本文提出技术收敛，仍需本 CCR 批准：

1. 正式申请状态只有四态，但运营端有“补正”动作。建议保留四态：`REQUEST_CORRECTION` 是不可变审核决定类型，结果投影为 `REJECTED`，携带对申请人可见的补正指引；申请人保存新版本后重新提交回 `REVIEWING`。不新增第五个公开申请状态。
2. C 端要求提交时完成主体重复/有效期校验，运营端又允许 OCR 失败后人工录入。建议正常路径在提交时完成机器证据和去重；OCR 不可用/失败时允许进入 `REVIEWING` 并标记 `SUBJECT_VERIFICATION_PENDING`，但在获权审核员写入人工核验事实、完成主体唯一性和有效期校验前，`APPROVE` 必须 409。该例外不能返回 APPROVED，也不能用申请人自报字段生成主体 claim。

### 1.1 已获用户确认的四项决策

以下四项已由用户确认，按“具体推荐”列执行：

| 决策 | 原文冲突 | 具体推荐 | 选择影响 |
|---|---|---|---|
| 门店照片是否阻断提交 | C 端同时写“建议上传（强制），至少1张”；运营字段表明确必填1～6张 | **推荐必填1～6张**，格式JPG/PNG/JPEG、单张≤10MB，按运营字段表和“强制/至少1张”执行 | 若改为可选，submit材料守卫、审核风险标识和验收用例都要改；不能前端可空、后端暗中必填 |
| OCR 失败能否提交 | C 端把证件有效期/OCR校验放在提交拦截；运营异常规则明确OCR失败允许人工录入比对并留痕 | **推荐仅OCR不可用/识别失败时允许提交到 REVIEWING+SUBJECT_VERIFICATION_PENDING**；清晰原件仍必需，已知过期仍拦截；人工核验完成主体、编号、有效期和去重前禁止APPROVE | 若不批准例外，OCR故障会完全阻断申请；若批准，必须交付人工核验权限、证据、claim和风险队列，不能把自报编号当已核验 |
| 补正如何落四态 | 运营有“补正”决定，27号正式申请只有四态 | **推荐决定类型REQUEST_CORRECTION，申请投影REJECTED**，意见10～500字，申请人修改新revision后重提 | 若新增公开第五态，将改变27号DTO、资格策略、筛选和前端状态机，需另行Contract变更 |
| 审核通过时 merchant 初值 | Schema06有APPLYING/ACTIVE，但审核和签约已是独立事实；现有consent已收窄为只接受ACTIVE首次签署 | **推荐APPROVE建档时写ACTIVE**，新单/工作台仍由APPROVED+SIGNED共同拦截 | 这是申请APPROVE的落库映射，不重问已批准电子签约规则；若选择APPLYING，必须另批状态迁移及并发语义，当前consent会503且不得私自激活 |

四项产品门禁已解除，后续按已批选择同步接口、存储及事件；未交付的真实依赖不得用fixture解除，也不因规则获批而标记功能完成。

## 2. 已批准规则与本 CCR 技术候选

| 类别 | 已批准/原 PRD 规则 | 本 CCR 候选（批准前不可实现为生产事实） |
|---|---|---|
| 状态 | DRAFT/REVIEWING/APPROVED/REJECTED；审核中只读，驳回可修改重提 | 补正决定映射 REJECTED；领取、OCR、主体核验使用内部技术状态，不扩公开四态 |
| 主体唯一 | 同一营业执照或同一身份证重复申请拦截；V1 一个主体一家门店 | 两类独立 ACTIVE claim，任一摘要冲突即阻断；旧驳回申请必须原单重提 |
| 材料 | 营业执照、身份证正反面必需；门店照片原文同时出现“建议/强制”，运营字段表明确1～6张；医院类行业许可证必需 | 推荐门店照片1～6张，但这是§1.1待确认产品冲突；其余商家类型不凭空增加行业证照 |
| OCR/有效期 | 清晰、有效；过期不得通过；OCR 失败可人工录入比对并留痕 | provider-neutral OCR evidence；人工 evidence 必须关联原材料 hash、审核员、原因和时间；无 VERIFIED evidence 不可批准 |
| 审核 | 单人领取，其他人不可重复处理；通过/驳回/补正；无内部双人审批 | claim 只防并发处理，不是第二审批；当前 claimant 才能决定；运营获权单人完成 |
| 权限 | `merchant.application.read/decide` 目前仅 AUTH 映射中的 PROPOSED_ONLY；AdminSessionQueryApi 已能解析真实会话和权限快照 | 本 CCR 同步后才可将两码注册 CONTRACT_READY；敏感原件另需 `merchant.identity.reveal` 和用途审计 |
| 产物 | 审核通过进入电子协议签署，审核不自动签约 | APPROVE 同事务创建真实 merchant/store 初始档案并写审核引用；`provider_merchant_no` 保持 null；签约仍由27号同意记录完成 |

## 3. 最小候选状态闭环

```text
无申请
  └─ createDraft → DRAFT（预分配 applicationId / reservedMerchantId；尚不生成申请编号）
DRAFT
  ├─ saveDraft → DRAFT（新增不可变 revision，替换当前草稿指针）
  └─ submit → REVIEWING（冻结 submittedRevision，建立审核任务和主体 claim）
REVIEWING
  ├─ claim / release → 状态不变，仅变更审核任务占有
  ├─ APPROVE → APPROVED（同事务写 decision + merchant/store + audit）
  ├─ REJECT → REJECTED（原因必填）
  └─ REQUEST_CORRECTION → REJECTED（补正指引必填）
REJECTED
  ├─ saveDraft → REJECTED（新增 revision，可编辑）
  └─ resubmit → REVIEWING（冻结新 revision，保留历史 decision）
APPROVED
  └─ 只读，进入27号电子协议首次签署；本闭环无撤回、取消或改写 APPROVED
```

该状态/审核切片不实现“已通过后资料变更审核”、撤回/取消申请、同主体多门店、OCR 自动供应商选择、资料到期后的经营处罚。它们不能借本表状态扩展实现。只有再交付§10的站内审核结果通知后，才能称为满足原PRD的最小完整闭环。

## 4. HTTP 候选

所有 ID 为正 Long 十进制 String；写接口使用完整 UUID `X-Request-Id`、MINIAPP/ADMIN_WEB Bearer、未知字段拒绝；时间遵循23号。下列路径均是候选，未同步10/11号前不得暴露。

### 4.1 C 端本人申请

| 方法/路径 | 请求 | 成功 data / 状态 |
|---|---|---|
| GET `/api/v1/c/merchant-applications/current` | 无；userId仅取当前会话 | `MerchantApplicationDetail`；无申请404 |
| POST `/api/v1/c/merchant-applications` | 空对象或首个 `DraftRevisionInput` | 201 DRAFT；服务端生成 applicationId/reservedMerchantId，applicationNo为空 |
| PUT `/api/v1/c/merchant-applications/{applicationId}/draft` | `{expectedVersion,draft:DraftRevisionInput}`，完整替换草稿 | 200 DRAFT；仅本人且状态 DRAFT/REJECTED |
| POST `/api/v1/c/merchant-applications/{applicationId}/submit` | `{expectedVersion,revisionId}` | 200 REVIEWING；DRAFT 首交或 REJECTED 重提 |

`DraftRevisionInput` 候选字段：`merchantName,contactName,contactPhone,email?,merchantTypeCode,cityCode,address,longitude,latitude,introduction?,storePhotoAssetIds[],businessLicenseAssetId,idCardFrontAssetId,idCardBackAssetId,industryLicenseAssetId?`。草稿允许字段缺失；submit 统一验证必填、格式、材料数量和资产归属，不把草稿保存成功等同可提交。提交词法直接取运营原字段表：merchantName 2～50字、contactName 2～20字中文、contactPhone 11位手机号、introduction 0～500字、cityCode 必须指向当时已开通城市；merchantTypeCode 必须映射且只映射“宠物生活馆/宠物医院/宠物美容院/宠物寄养中心/宠物训练机构/其他”六个已批字典项。address 与经纬度在申请提交时均必填并做地图合理性校验；这里不套用正式门店 DTO 可空坐标的兼容读取规则。APPROVE 还必须确认 merchantName 与已验证营业执照主体名称一致，不能只验证名称非空。

`MerchantApplicationDetail` 至少含 applicationId/applicationNo/reservedMerchantId/status/version/currentRevision（敏感编号不回显明文）、submittedAt?/reviewedAt?、latestDecision 的申请人可见 `decisionType/opinion/decidedAt`、`subjectVerificationStatus`。不返回内部备注、审核员登录账号、原始 OCR 包或长期原件 URL。

### 4.2 运营审核

| 方法/路径 | 请求 | 动作与结果 |
|---|---|---|
| GET `/api/v1/admin/merchant-applications` | page/pageSize、status?、merchantTypeCode?、cityCode?、submittedFrom/To?、keyword? | `merchant.application.read`；先 scope 过滤再 total/page；关键词精确敏感查找另受控，不默认开放证件号明文搜索 |
| GET `/api/v1/admin/merchant-applications/{applicationId}` | 默认脱敏；`reveal=true`时另带purpose | read；reveal 还需 `merchant.identity.reveal`，短期水印读取并逐次审计 |
| POST `/api/v1/admin/merchant-applications/{applicationId}/claim` | `{expectedTaskVersion}` | `merchant.application.decide`；200 CLAIMED；已有他人 claim 返回409 |
| POST `/api/v1/admin/merchant-applications/{applicationId}/release` | `{expectedTaskVersion}` | 当前 claimant 释放；200 AVAILABLE；管理员强制释放/超时 lease 没有产品来源，另行治理，不在本候选暗加 |
| POST `/api/v1/admin/merchant-applications/{applicationId}/manual-verification` | `{submissionRevisionId,expectedVersion,evidenceItems,reason,confirmed:true}` | decide + identity.reveal；为 OCR 失败材料写人工核验证据和主体 claim，不改变公开状态 |
| POST `/api/v1/admin/merchant-applications/{applicationId}/decision` | `{decisionType,submissionRevisionId,expectedVersion,expectedTaskVersion,opinion?,internalNote?,confirmed:true}` | decide；APPROVE/REJECT/REQUEST_CORRECTION，200 最终投影 |

驳回/补正的 opinion 必填10～500字符；通过是否强制意见按原字段表为非必填，本候选不新增强制。internalNote 可空、最多500字符且永不返回申请人。`confirmed=true` 是同人动作确认，不是 MFA 或第二审核人。

错误映射沿用27号：400字段非法；401会话失效；403缺动作；404不存在或 scope 外；409版本/状态/claim/主体重复/材料未核验冲突；同 key 异参为 `IDEMPOTENCY_KEY_CONFLICT`；AUTH、私有资产、OCR证据或存储事实不可用为503。主体冲突若属于其他用户，只返回通用409，不泄露对方申请号、姓名或证件片段；当前本人重复则可引导 GET current。

## 5. 内部 API 接入与精确缺口

### 5.1 可直接使用

- `AdminSessionQueryApi.resolveSession(accessToken)`：在可信 Web adapter 解析 ADMIN_WEB 会话；使用 `AdminSessionPrincipal`、`AdminPermissionSnapshot.actionCodes/dataScope/authzVersion`，原始 token 不进入 MER Context、日志或数据库。
- `AdminDataScope`：ALL/CITY/MERCHANT/NONE 的结构和排序可复用；申请资源的 CITY 取提交 revision 的 `cityCode`，MERCHANT 取预分配的 `reservedMerchantId`，不能取请求筛选值。
- `CommandContext`：USER 命令映射真实 owner userId，运营决定映射 PLATFORM_OPERATOR/真实 operatorId；requestId、traceId 和 source 不授予权限。
- 当前 `ApplicationReviewFactsReader.read(merchantId)`：保留为 merchant-biz 内部端口；真实实现只能查询下述 MER 表并加入调用者同一 DataSource 事务。

### 5.2 必须在本 CCR 冻结的新接口/字段

建议在 `pet-merchant-api` 增加：

```java
interface MerchantApplicationQueryApi {
  MerchantApplicationDetail getCurrent(CurrentMerchantApplicationQuery q);
  Page<MerchantApplicationSummary> listForReview(MerchantApplicationReviewListQuery q);
  MerchantApplicationReviewDetail getForReview(MerchantApplicationReviewQuery q);
  MerchantApplicationScopeFact getScope(MerchantApplicationScopeQuery q);
  MerchantApplicationEligibilityFact getEligibility(MerchantApplicationEligibilityQuery q);
}

interface MerchantApplicationCommandApi {
  MerchantApplicationResult createDraft(CreateMerchantApplicationCommand c);
  MerchantApplicationResult saveDraft(SaveMerchantApplicationDraftCommand c);
  MerchantApplicationResult submit(SubmitMerchantApplicationCommand c);
  ReviewTaskResult claim(ClaimMerchantApplicationCommand c);
  ReviewTaskResult release(ReleaseMerchantApplicationCommand c);
  MerchantApplicationResult recordManualVerification(VerifyMerchantSubjectCommand c);
  MerchantApplicationResult decide(DecideMerchantApplicationCommand c);
}
```

`MerchantApplicationScopeFact` 精确字段：applicationId、reservedMerchantId、cityCode、ownerUserId、submittedRevisionId、scopeVersion。`MerchantApplicationEligibilityFact` 精确字段：applicationId、merchantId、status、version、reviewDecisionId?、reviewedAt?；只有 APPROVED 时 decisionId/reviewedAt 必填且必须可连接真实 decision。

现有 Admin API 只能返回入口权限快照，缺少业务锁后的最终检查。建议在 `pet-admin-api` 增加候选：

```java
interface AdminAuthorizationQueryApi {
  AdminActionDecision check(AdminActionCheckQuery query);
}

record AdminActionCheckQuery(
  String sessionId, long sessionGeneration, String operatorId,
  String actionCode, MerchantApplicationScopeFact resource,
  String purpose, CheckPhase phase
) {}

record AdminActionDecision(
  boolean allowed, OffsetDateTime checkedAt,
  String authzVersion, String reasonCode
) {}
```

该接口由 AUTH/ADM Owner 实现强一致当前读；MER 在锁住幂等绑定、application/task 后、首写前调用 EXECUTE 检查，成功回执重放或敏感原件读取用 READ_RESULT。若不批准 merchant-biz→admin-api 依赖，应由 boot/admin-biz 提供等价的受信任执行回调；不得仅把入口 `PermissionSnapshot` 塞进 CommandContext 后长期信任。

当前完全缺失私有材料接口。SQL15 `asset_registry.public_url` 面向公开资产，不适合营业执照/身份证。最小候选需要私有资产 Owner 提供 `PrivateAssetRef`（assetId、ownerUserId、sha256、mediaType、bytes、status）当前读和短期水印 read-grant；MER 只保存 opaque assetId/hash，不保存 public URL。上传、病毒/图片解码检查、授权下载与生命周期必须另有 Contract；没有该接口不得把原件放进公开 registry 或普通日志。

OCR 只定义 merchant-biz infrastructure port，例如 `CredentialExtractionPort.extract(privateAssetId, sha256)`，输出 provider-neutral 证据；供应商、凭据、回调和置信度阈值未选，不得在业务 Contract 写死。无 Provider 时只允许原 PRD 的获权人工核验路径。

## 6. 存储候选（MER 自有，同一 DataSource）

以下均是候选 DDL 语义，未批准前不创建迁移。

### 6.1 merchant_application

`id BIGINT PK`、`application_no VARCHAR(22) ASCII BIN NULL UNIQUE`、`owner_user_id BIGINT UNIQUE`、`reserved_merchant_id BIGINT UNIQUE`、`status VARCHAR(16)`、`current_revision_id BIGINT`、`submitted_revision_id BIGINT NULL`、`current_review_task_id BIGINT NULL`、`current_decision_id BIGINT NULL`、`review_audit_id BIGINT NULL`、`subject_verification_status VARCHAR(32)`、`version BIGINT`、`submitted_at/reviewed_at DATETIME(3) NULL`、created/updated。

- status CHECK 仅 DRAFT/REVIEWING/APPROVED/REJECTED；版本非负。APPROVED 时 currentDecisionId、reviewAuditId、reviewedAt 和 submittedRevisionId 全部非空，且 decision 必须是同 application/submittedRevision 的 APPROVE；其他状态不得借残留指针伪装批准事实。
- applicationNo 是 PRD 的 `SQ+YYYYMMDD+8位随机码` 人读编号，按原流程在首次 submit 成功时同事务生成，草稿期为空；重提沿用原编号。实体主键仍为 Snowflake；生成碰撞以唯一键重试，不拿 applicationNo 当发号器。
- owner 唯一与 Schema06 `merchant.owner_user_id` 的 V1 单商家边界一致；撤回/注销后能否重开没有规则，本候选不删除或复用旧 application。
- reservedMerchantId 在 createDraft 时预分配，但 merchant/store 行只在 APPROVE 事务创建，避免不完整草稿污染正式主表。

### 6.2 merchant_application_revision

`id`、applicationId、revisionNo、merchantName、contactName、contactPhoneProtected、email?、merchantTypeCode、cityCode、address、longitude/latitude、introduction?、canonicalSha256、createdByUserId、createdAt；UNIQUE(applicationId,revisionNo)。每次 save 插入不可变 revision，application 只移动 currentRevisionId；submit 固定 submittedRevisionId，审核期间不修改该 revision。

建议初次审批成功映射：merchant.id=reservedMerchantId、ownerUserId、merchantName、status=ACTIVE、providerMerchantNo=null；建立一个 merchant_store，名称取 submitted merchantName，地址/坐标取 submitted location，status=ACTIVE。这里 ACTIVE 只表示实体未冻结/未下线，27号资格仍同时要求 APPROVED+SIGNED，不能把 ACTIVE 当审核或签约事实。联系人手机号是否直接成为公开门店 phone 原 PRD未明确；推荐初始复制但只经脱敏 DTO 输出，此映射必须在 CCR 审阅后才能实施。

该 ACTIVE 初值是本申请 CCR 对 APPROVE 建档的候选映射，不是重新询问或改变已经批准的电子协议规则。当前 agreement consent 已收窄为仅允许 ACTIVE 首次签署；APPLYING→ACTIVE 没有获批迁移语义并按503能力待补处理。因此若 Contract Owner 改选 APPROVE 创建 APPLYING，必须另行冻结激活触发点、CAS和冻结/下线并发后再同步协议实现，申请侧不得自行激活。

Schema06 的 merchant/merchant_store 还没有 merchantTypeCode 或 cityCode 列。最小闭环中这两个获批字段必须继续由不可变 submitted revision/application 投影提供，不能在 APPROVE 时丢弃；若后续商家列表、推荐或运营台账要把它们作为当前正式档案查询，须在本 CCR 的 Schema 同步中明确新增正式 profile 列/表及其与申请快照的初始映射，不能临时塞 JSON 或从名称、地址反推。

### 6.3 material / revision_material / credential_evidence

- `merchant_application_material`：id、applicationId、materialType、privateAssetId、sha256、mediaType、bytes、position、uploadedByUserId、createdAt；不可覆盖，同 asset/hash 才可复用。
- `merchant_application_revision_material`：revisionId、materialId 组合主键，冻结每个 revision 的材料集合。
- `merchant_credential_evidence`：id、materialId、materialSha256、evidenceSource(OCR/MANUAL)、evidenceStatus(SUCCEEDED/FAILED/VERIFIED)、credentialType、subjectNameProtected、identifierProtected、identifierLookupDigest、validFrom/validTo、extractorName/version?、verifiedByOperatorId?、verificationReason?、observedAt。原始 OCR 包只存私有引用，不进普通 JSON 日志。

submit 验证 JPG/PNG/JPEG、单张≤10MB、门店照片1～6、执照1、身份证正反各1；医院类再需行业许可证。APPROVE 使用服务端业务日期重新校验证据有效期，不能只信提交时结果；“临期”只作风险提示，原 PRD未授权因临期自动驳回。

### 6.4 merchant_subject_claim

字段：id、claimType(CREDIT_CODE/IDENTITY_NUMBER)、lookupDigest BINARY(32)、lookupKeyVersion、applicationId、evidenceId、status(ACTIVE/RELEASED)、claimedAt、releasedAt?，并以 generated `active_lookup` 对 `(claimType,lookupDigest)` 建唯一键。明文编号只存在受保护 evidence，不进入 claim、审计或错误。

- 同一申请提交新 revision 时，先锁 application 和旧 claims；新 evidence 全部验证后，相同摘要沿用原 claim、变化摘要先原子取得新 claim，再 RELEASE 不再对应的旧 claim，失败则旧 claim 不动。不能对同一 application 的相同 ACTIVE claim 再插一行并把自身误判为重复主体。
- REJECTED 保留 ACTIVE claims，强制原申请重提；APPROVED 永久保留并关联 merchant。不存在产品批准的取消/释放规则，不自动清理。
- OCR 失败可暂时 PENDING，但 APPROVE 前必须由人工 evidence 建立 CREDIT_CODE 和 IDENTITY_NUMBER 两个 ACTIVE claim。任一唯一冲突均禁止批准。
- 推荐 V1 归一：统一社会信用代码使用人工/OCR确认后的18位大写 ASCII 并做法定校验；居民身份证使用18位大写、末位数字/X并做校验。具体归一与旧15位证件支持属于 Contract 字段规则，必须在批准时明确，不能上线后静默改变。lookup 使用专用 HMAC-SHA-256；换 key 前须双算/回填并保持跨版本唯一，不能因轮换产生不同 digest 绕过去重。

### 6.5 review_task / review_decision / application_audit

- `merchant_application_review_task`：id、applicationId、submittedRevisionId、submissionNo、status(AVAILABLE/CLAIMED/CLOSED)、claimedByOperatorId?、claimedAt?、closedAt?、version、updatedAt；UNIQUE(applicationId,submittedRevisionId)。每次首次提交/重提创建新的 task 并更新 application.currentReviewTaskId，不复用或改写已关闭任务；claim 使用行锁/CAS，decision 必须由当前 task claimant 执行。
- `merchant_application_review_decision`：id、applicationId、submittedRevisionId、taskId、decisionType(APPROVE/REJECT/REQUEST_CORRECTION)、opinion?、internalNote?、decidedByOperatorId、decidedAt、authzVersion、scopeVersion、requestId、traceId；append-only。APPROVED application.currentDecisionId/reviewAuditRef 必须指向 APPROVE decision。
- `merchant_application_audit`：id、applicationId、revisionId?、actorType/actorId、actionCode、fromStatus/toStatus、requestId、traceId、occurredAt、decisionId?；记录 draft save/submit/claim/release/manual verify/decision。决定事务写出的审核 audit id 回填 application.reviewAuditId；业务 decision/audit 与 application 状态在 MER 同一事务；admin 自身安全审计可以另记，但不能替代本地权威审核引用。
- 复用 merchant 自有 `merchant_command_idempotency` 设计，稳定 namespace：`merchant.application.create-draft/save-draft/submit/claim/release/manual-verify/decide`。规范参数包含目标、revision、decision、意见/备注、expectedVersion/taskVersion；敏感证件号只以受保护规范字节/HMAC参与等值，不写明文 receipt。

## 7. 权限、scope 与敏感读取

1. `merchant.application.read`：列表和脱敏详情。`merchant.application.decide`：claim/release/manual verify/decision。角色名称不直接授权；只有当前有效 actionCodes 可用。两码当前仍为 PROPOSED_ONLY，本 CCR 与业务接口同步后方可登记 CONTRACT_READY。
2. 原件/完整证件号读取另需 `merchant.identity.reveal`、purpose、关联 applicationId 和当前 scope；每次签发短期水印读取授权并审计。decide 不自动等于无限明文导出。
3. scope 事实必须由 MER 的已提交 revision 产生：ALL允许；CITY仅 cityCode 命中；MERCHANT仅 reservedMerchantId 命中；NONE拒绝。请求 query/body 中的城市或 merchantId 不作为证明。
4. 列表先 scope 过滤再 count/page；单资源 scope 外统一404。读取依赖失败503，不降级全平台。
5. V1 是单运营决定，无 secondApprover/reviewerId，不新增 MFA。任务领取只解决并发，不是双人审批。

## 8. 事务、并发和幂等顺序

### 8.1 create/save/submit

1. AUTH 解析真实 USER，做静态校验；按23号短事务绑定 requestId。
2. execution 锁 application 幂等记录和 application；save 要求 DRAFT/REJECTED + expectedVersion，插入新 revision 并移动指针。
3. submit 锁 current revision/material/evidence；验证字段、资产 owner/hash/status、材料集合和已知有效期；可核主体时原子取得 claims，OCR 异常则明确 PENDING。
4. 固定 submittedRevisionId，状态转 REVIEWING，review_task 变 AVAILABLE，写 audit/receipt 同事务提交。审核中 save 必须409。

### 8.2 claim/release/decision

1. Web adapter 用 `AdminSessionQueryApi.resolveSession` 做入口检查；MER 读取不可变 submitted scope，AUTH 检查 action/scope。
2. 绑定 requestId 后锁 application→review_task→subject claims（固定锁序）。claim 只允许 AVAILABLE；release 只允许当前 claimant。
3. decision 在锁内确认 REVIEWING、submittedRevision未变化、claim owner、expectedVersion/taskVersion；首写前调用 AdminAuthorizationQueryApi EXECUTE 当前检查，超过预算/版本不稳为503。
4. APPROVE 再核全部材料/evidence/有效期/主体 claims；同事务插入 decision、merchant、merchant_store、application APPROVED、task CLOSED、audit、审核结果事件 Outbox 和幂等 receipt。不得先把 status 改 APPROVED 再异步补 merchant、审计或 Outbox。
5. REJECT/REQUEST_CORRECTION 同事务写 decision、application REJECTED、task CLOSED、audit、审核结果事件 Outbox 和 receipt；不删除 revision、evidence 或历史决定。
6. 同 key 同参重放先核当前会话、动作、scope和结果可读性，再回原 receipt；异参409。不同 requestId 仍受 state/CAS/唯一 claims/merchant owner 唯一键保护。commit ACK未知按原 key 查询，不创建第二决定。

审核权限撤销与 MER 本地写无法形成跨库事务，遵循07号现有两轮版本复核语义：检查先通过的短本地事务可完成；撤权先于最终检查则必须拒绝。不得声称零窗口或跨 biz 持锁。

## 9. 资格与协议接入

- `ApplicationReviewFactsReader` 的真实 adapter 在当前 merchant REPEATABLE_READ 中按 merchantId 连接 merchant_application；无记录、APPROVED缺decision/reviewedAt、decision/revision断链、未知枚举均抛依赖失败，不能返回 NOT_APPROVED 伪装坏库。
- 已明确 DRAFT/REVIEWING/REJECTED 是正常非资格，`checkOrderEligibility.acceptsNewOrders=false`；APPROVED 且审核链完整才把 applicationStatus 交资格策略。
- 27号协议 consent 只能在 APPROVED 后执行；批准申请不写 agreement acceptance、不写 providerMerchantNo、不自动变 SIGNED。
- current session 的 merchantEntry 可从本人 application 投影得到 DRAFT/PENDING/REJECTED/SIGNING_REQUIRED；这不把 admission 当审核命令授权。

## 10. 审核结果站内通知事件候选（完整闭环必需）

SSOT/原PRD要求审核结果进入站内消息，外部微信/订阅消息仅按真实能力补充，不能以 C 端主动查询状态替代。Event08 当前没有该事件，因此下面是待 CCR/Event Owner 批准的新增提案，不直接修改权威事件目录：

```text
eventType: MerchantApplicationReviewedEvent.v1
eventVersion: 1
aggregateType: merchant_application
aggregateId: applicationId
payload:
  applicationId: String
  applicationNo: String
  ownerUserId: String
  reservedMerchantId: String
  submittedRevisionId: String
  reviewDecisionId: String
  decisionType: APPROVE | REJECT | REQUEST_CORRECTION
  applicationStatus: APPROVED | REJECTED
  applicantVisibleOpinion: String?   # REJECT/CORRECTION必填，APPROVE可空
  decidedAt: OffsetDateTime
```

- eventId 使用 Snowflake String；traceId 放标准 envelope。payload 不带 internalNote、证件号、手机号、原件/OCR包、审核员账号或长期URL。
- MER 在决定事务内写本域 Outbox；决定回滚则事件也回滚。Outbox 的 eventId、decisionId 唯一关联，成功重放不得再产生新事件。
- notification 消费者按 `(eventId, consumerName)` 幂等，在自己的本地事务同时写 consume_log 和面向 ownerUserId 的真实站内消息；消息展示申请编号、结论、申请人可见意见和进入申请/签约页的受控入口。
- 站内消息持久化失败必须让消费事务回滚并由 Outbox 重试，不允许先记已消费再丢消息。外部微信/订阅消息失败不回滚站内消息，按真实授权与发送能力单独记录/重试。
- DoD 至少证明：三类决定各产生一次事件；同requestId重放和发布重试不产生重复消息；REJECT/CORRECTION意见可见而internalNote永不外泄；用户注销/消息依赖不可用有明确失败与重试记录。

在事件 Contract、MER Outbox 发布和 notification 真实消费未交付前，申请/审核状态链只能标为“阶段交付”，不得宣称最小完整闭环或 MER-001 完整 DoD。

## 11. 最小可落地实施切片与解除证据

### A. 契约与安全基础（必须先完成）

- 同步申请 HTTP/OpenAPI、MerchantApplication API、AdminAuthorizationQueryApi、私有资产引用与敏感读取协议。
- 冻结身份证/信用代码归一、门店照片是否强制、联系人→store.phone 映射、补正→REJECTED 映射。
- 交付 MER DDL/迁移及索引/CHECK/回滚方案；不能只建 application.status 表。

### B. 申请与人工审核状态链（阶段交付）

- 实现 create/save/submit、私有材料版本、人工核验证据、subject claims、claim/release/decision 和真实 admin action/scope。
- 这是不依赖 OCR Provider 的最小闭环：OCR 端口未就绪时必须走获权人工核验，不能 seed VERIFIED 或默认成功。
- APPROVE 同事务创建 merchant/store、decision/audit/outbox；REJECT/CORRECTION 可编辑重提；真实 MySQL 测并发提交、重复主体、双审核员 claim、撤权最终检查、requestId重放和 ACK 丢失。仅做到本阶段仍不能宣称完整闭环。

### C. 审核结果通知

- 批准 `MerchantApplicationReviewedEvent.v1`，实现 MER 同事务 Outbox、可靠发布、notification 幂等消费和真实站内消息；外部提醒按实际授权能力执行。
- B+C 均通过后，才达到原PRD要求的最小完整人工审核闭环。

### D. 资格装配

- 接入真实 ApplicationReviewFactsReader 与协议 acceptance reader，同一 merchant DataSource snapshot；移除“facts not wired”默认503只发生在 B 完整通过后。
- 验证 APPROVED 无 decision、断链或缺材料时仍503；不以 ACTIVE/provider 字段推断。

### E. OCR 自动化（可后续独立）

- 选定 Provider、凭据/回调/查证、置信度和故障恢复后实现；自动证据与人工证据使用同一 claim/approval守卫。E 不阻塞已获批人工闭环，但 OCR 不可用必须显式风险状态。

解除“真实申请审核事实缺失”的证据必须同时包含：原始 PRD字段/状态合同测试；真实 MySQL唯一claim和不可变revision/decision测试；真实 AdminSession/action/scope测试；两个审核员并发只有当前 claimant 能决定；APPROVE 与 merchant/store/audit/outbox 原子；通知消费者真实持久化且重试不重复；资格查询读取真实decision链；敏感原件无public URL/普通日志泄露。只用 fixture 返回 APPROVED、Mock OCR、前端按钮隐藏、C端轮询或单张 status 表均不解除。

## 12. 保留边界

- 私有资产上传/短期读取和 OCR Provider 当前无正式实现，按上述接口单列门禁；不使用 SQL15 public_url 代替。
- 审核结果事件仍须 Event Owner 批准并同步 Event08；在此之前§10只是精确提案。MER 不得借普通日志或直接跨库写消息，C端权威查询也不能替代强制站内通知。
- 审核任务超时自动释放、审核 SLA scheduler、管理员强制转派、已通过资料变更、资质到期处罚均没有本 CCR 足够规则，不进入最小写实现。
- 不因此解除成员绑定、主账号核销 staff 映射、员工停用与订单指派并发或冻结写动作门禁。
