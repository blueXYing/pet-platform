# 商家申请审核契约补充 v0.1（已批准，分阶段实现）

状态：APPROVED / HTTP_IMPLEMENTED_DEFAULT_OFF_REQUIRES_PROVIDERS。用户在PR53技术契约包说明后明确“那么请你实施”，随后批准S7多线接通。申请/协议HTTP已实施，默认关闭且真实外部依赖不可缺失。下文历史“候选/未装配”以§14当前交付说明覆盖；接口存在不代表私有材料或生产部署已完成。

产品依据为SSOT §27、PRD26；存储依据为[SQL29](../03-database/29-Merchant-Application-Schema-v0.1.sql)及其存储说明。现有07/10/11/12与Event08只作兼容增量；审批、私人材料/授权、通知实现仍按验收交接，字段定义不代替真实Provider事实。

## 本轮收敛说明（覆盖下文原提案中同项建议）

1. 四项决定已批准：照片1～6张、OCR异常待人工、补正REJECTED重提、APPROVE创建ACTIVE且另需SIGNED。材料/字典等既有要求保持。
2. admin-api使用本域通用`AdminResourceScope(resourceType,resourceId,merchantId,cityCode,scopeVersion)`，不import merchant-api DTO。对申请资源，merchantId为服务端reservedMerchantId，cityCode来自已提交不可变revision；客户端字段不可冒充授权来源。
3. 所有人工核验和决定均要求当前CLAIMED任务的实际领取人。单人可核验并决定，没有第二审核人；每个任务写请求都带expectedTaskVersion。
4. 运营列表/详情只查看已提交申请；DRAFT无运营可见性。REJECTED后申请人未重提的新草稿不暴露给运营，运营读取原submittedRevision。敏感原件不通过GET reveal参数开放，私有查看须独立授权、用途、审计与短时读取协议，未就绪时失败关闭。
5. 主体唯一占用与本轮核验证据分离：同digest可沿用ACTIVE claim，但APPROVE必须引用本submittedRevision的两类VERIFIED证据，并核对claim类型/digest/key版本，不能旧材料核验代替新材料核验。证据必须关联整个不可变revision材料集合，身份证正反两面不可混用版本。
6. 初期HMAC使用持久锁定的单一key policy；运行配置不符或密钥不可用则503，不启用第二套digest绕过去重。在线轮换尚未交付，不把文字“双算/回填”当作已实现。证件类型/历史号码的可信规范化由证据适配契约定义，不在此擅自仅支持18位或选择OCR厂商。
7. 草稿是全量替换：缺失/null字段都表示清空，storePhotoAssetIds缺失表示空数组；这是明确默认值，规范化在摘要前完成。保存允许不完整，提交仍必须完整验证。HTTP中证件原文仅在受权人工核验的writeOnly入参出现；原文不进入普通日志、JSON回执或事件。
8. 有效期用UNKNOWN/DATED/LONG_TERM明确表达；VERIFIED不可UNKNOWN，DATED要求有效起止及顺序，LONG_TERM须原件明确证明，null截止日期不能自动当作长期。按服务端业务日复核，过期拒绝，临期只提示。
9. 商家六类代码固定映射PET_LIFE_STORE/宠物生活馆、PET_HOSPITAL/宠物医院、PET_GROOMING/宠物美容院、PET_BOARDING/宠物寄养中心、PET_TRAINING/宠物训练机构、OTHER/其他；不采用旧视觉稿的园艺/异宠工作室。cityCode仍为有效已开通城市字典key，不由自由文本或虚构6位行政代码代替。
10. DB精确列、组合FK和可保证边界以SQL29/Storage29为准，覆盖下文逻辑字段速记。SQL29不是默认Flyway/生产迁移；跨行状态/权限/当前日期与证据真实性必须由实现和读取join复核。

## 3. 申请状态闭环

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

## 4. HTTP 同步范围

所有 ID 为正 Long 十进制 String；写接口使用完整 UUID `X-Request-Id`、MINIAPP/ADMIN_WEB Bearer、未知字段拒绝；时间遵循23号。下列路径同步OpenAPI，统一标记CONTRACT_SYNC_CANDIDATE_NOT_IMPLEMENTED；业务实现与真实鉴权验收之前不得暴露。

### 4.1 C 端本人申请

| 方法/路径 | 请求 | 成功 data / 状态 |
|---|---|---|
| GET `/api/v1/c/merchant-applications/current` | 无；userId仅取当前会话 | `MerchantApplicationDetail`；无申请404 |
| POST `/api/v1/c/merchant-applications` | 空对象或首个 `DraftRevisionInput` | 201 DRAFT；服务端生成 applicationId/reservedMerchantId，applicationNo为空 |
| PUT `/api/v1/c/merchant-applications/{applicationId}/draft` | `{expectedVersion,draft:DraftRevisionInput}`，完整替换草稿 | 200 DRAFT；仅本人且状态 DRAFT/REJECTED |
| POST `/api/v1/c/merchant-applications/{applicationId}/submit` | `{expectedVersion,revisionId}` | 200 REVIEWING；DRAFT 首交或 REJECTED 重提 |

`DraftRevisionInput` 字段：`merchantName,contactName,contactPhone,email?,merchantTypeCode,cityCode,address,longitude,latitude,introduction?,storePhotoAssetIds[],businessLicenseAssetId,idCardFrontAssetId,idCardBackAssetId,industryLicenseAssetId?`。草稿允许字段缺失；submit 统一验证必填、格式、材料数量和资产归属，不把草稿保存成功等同可提交。提交词法直接取运营原字段表：merchantName 2～50字、contactName 2～20字中文、contactPhone 11位手机号、introduction 0～500字、cityCode 必须指向当时已开通城市；merchantTypeCode 必须映射且只映射“宠物生活馆/宠物医院/宠物美容院/宠物寄养中心/宠物训练机构/其他”六个已批字典项。address 与经纬度在申请提交时均必填并做地图合理性校验；这里不套用正式门店 DTO 可空坐标的兼容读取规则。APPROVE 还必须确认 merchantName 与已验证营业执照主体名称一致，不能只验证名称非空。

`MerchantApplicationDetail` 至少含 applicationId/applicationNo/reservedMerchantId/status/version/currentRevision（敏感编号不回显明文）、submittedAt?/reviewedAt?、latestDecision 的申请人可见 `decisionType/opinion/decidedAt`、`subjectVerificationStatus`。不返回内部备注、审核员登录账号、原始 OCR 包或长期原件 URL。

### 4.2 运营审核

| 方法/路径 | 请求 | 动作与结果 |
|---|---|---|
| GET `/api/v1/admin/merchant-applications` | page/pageSize、status?、merchantTypeCode?、cityCode?、submittedFrom/To?、keyword? | `merchant.application.read`；先 scope 过滤再 total/page；关键词精确敏感查找另受控，不默认开放证件号明文搜索 |
| GET `/api/v1/admin/merchant-applications/{applicationId}` | 仅脱敏；本阶段不接受reveal参数，敏感原件走独立受控读取契约 | read；不得在GET URL中放敏感用途或返回原件URL/证件原文 |
| POST `/api/v1/admin/merchant-applications/{applicationId}/claim` | `{expectedTaskVersion}` | `merchant.application.decide`；200 CLAIMED；已有他人 claim 返回409 |
| POST `/api/v1/admin/merchant-applications/{applicationId}/release` | `{expectedTaskVersion}` | 当前 claimant 释放；200 AVAILABLE；管理员强制释放/超时 lease 没有产品来源，另行治理，不在本候选暗加 |
| POST `/api/v1/admin/merchant-applications/{applicationId}/manual-verification` | `{submissionRevisionId,expectedVersion,expectedTaskVersion,evidenceItems,reason,confirmed:true}` | decide + identity.reveal + 当前CLAIMED任务领取人；为 OCR 失败材料写人工核验证据和主体 claim，不改变公开状态 |
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
  String actionCode, AdminResourceScope resource,
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

建议初次审批成功映射：merchant.id=reservedMerchantId、ownerUserId、merchantName、status=ACTIVE、providerMerchantNo=null；建立一个 merchant_store，名称取 submitted merchantName，地址/坐标取 submitted location，status=ACTIVE。这里 ACTIVE 只表示实体未冻结/未下线，27号资格仍同时要求 APPROVED+SIGNED，不能把 ACTIVE 当审核或签约事实。联系人手机号不自动变成公开门店电话；初始merchant_store.phone保持NULL，避免未经明确业务授权改变私人联系方式用途，后续店铺资料按独立权限维护。

该ACTIVE初值已由用户确认，见SSOT §27；不再次询问，也不改变电子协议规则。当前 agreement consent 已收窄为仅允许 ACTIVE 首次签署；APPLYING→ACTIVE 没有获批迁移语义并按503能力待补处理。因此若 Contract Owner 改选 APPROVE 创建 APPLYING，必须另行冻结激活触发点、CAS和冻结/下线并发后再同步协议实现，申请侧不得自行激活。

Schema06 的 merchant/merchant_store 还没有 merchantTypeCode 或 cityCode 列。最小闭环中这两个获批字段必须继续由不可变 submitted revision/application 投影提供，不能在 APPROVE 时丢弃；若后续商家列表、推荐或运营台账要把它们作为当前正式档案查询，须在本 CCR 的 Schema 同步中明确新增正式 profile 列/表及其与申请快照的初始映射，不能临时塞 JSON 或从名称、地址反推。

### 6.3 material / revision_material / credential_evidence

- `merchant_application_material`：id、applicationId、materialType、privateAssetId、sha256、mediaType、bytes、uploadedByUserId、createdAt；不可覆盖，不承载跨版本固定槽位。
- `merchant_application_revision_material`：同申请的revisionId/materialId/type/hash关系，position只属于该revision；按revision/type/position唯一，允许新revision同槽位使用新材料，也允许合法重排，旧版本不变。
- `merchant_credential_evidence`：绑定applicationId、revisionId、materialId/type/hash和不可变材料集合；包含来源/状态、credentialType、加密subjectName/identifier、HMAC摘要及policy版本、validityKind、有效期、长期依据、核验人/原因/时间。VERIFIED不得UNKNOWN，DATED要合法起止，LONG_TERM须原件明确证明。原始OCR包只存私有引用，不进普通JSON日志；核验身份证必须同revision正反面，不跨版本拼证据。

submit 验证 JPG/PNG/JPEG、单张≤10MB、门店照片1～6、执照1、身份证正反各1；医院类再需行业许可证。APPROVE 使用服务端业务日期重新校验证据有效期，不能只信提交时结果；“临期”只作风险提示，原 PRD未授权因临期自动驳回。

### 6.4 merchant_subject_claim

字段：id、claimType(CREDIT_CODE/IDENTITY_NUMBER)、lookupDigest BINARY(32)、lookupKeyVersion、applicationId、evidenceId、status(ACTIVE/RELEASED)、claimedAt、releasedAt?，并以 generated `active_lookup` 对 `(claimType,lookupDigest)` 建唯一键。明文编号只存在受保护 evidence，不进入 claim、审计或错误。

- 同一申请提交新 revision 时，先锁 application 和旧 claims；新 evidence 全部验证后，相同摘要沿用原 claim、变化摘要先原子取得新 claim，再 RELEASE 不再对应的旧 claim，失败则旧 claim 不动。不能对同一 application 的相同 ACTIVE claim 再插一行并把自身误判为重复主体。
- REJECTED 保留 ACTIVE claims，强制原申请重提；APPROVED 永久保留并关联 merchant。不存在产品批准的取消/释放规则，不自动清理。
- OCR 失败可暂时 PENDING，但 APPROVE 前必须由人工 evidence 建立 CREDIT_CODE 和 IDENTITY_NUMBER 两个 ACTIVE claim。任一唯一冲突均禁止批准。
- 证件规范化采用受信证据适配器的固定scheme版本，不能由客户端提供digest，也不在本切片擅自仅支持18位或排除历史证件。lookup采用HMAC-SHA-256及单一持久policy；配置版本不符/密钥不可用503。SQL29首期不支持在线密钥轮换；有占用时不可直接改key产生新digest。将来轮换须另交全量回填/双查和跨版本唯一证明，不把尚未实现的“双算”写成已有能力。
- 主体占用与本轮核验分离：相同digest沿用ACTIVE claim，其旧取证引用可以保留。APPROVE decision另绑定本submittedRevision的两类VERIFIED evidence；服务锁内及资格读取join必须比对其type/digest/policy与两个ACTIVE claims一致，不能以R1 claim证明R2材料已核验。

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
aggregateType: MERCHANT_APPLICATION
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
- 证件规范化由可信证据适配契约交接；门店照片必填和补正REJECTED已批准，不重复询问。联系人信息不自动作为公开门店电话。
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

## 13. S5 实施映射与未装配边界

实现位于 merchant-api / merchant-biz 的 `MerchantApplicationCommandApi`、`MerchantApplicationQueryApi` 与 MyBatis application mapper。内部 command/result records 用于本域调用，不能直接作为 HTTP DTO；OpenAPI 的 String version、Owner/Admin 两类投影、create 首次201/重放200等仍须专门 HTTP 适配验证。当前不注册这些 HTTP 路由。

运营最终复核由 admin-api 的 `AdminAuthorizationQueryApi` 提供。另增加只读集合入口 `checkCollection(AdminCollectionActionCheckQuery)`，参数为 sessionId、sessionGeneration、operatorId、actionCode、purpose、phase；只支持 `merchant.application.read` 与 `READ_RESULT`。它先检查当前会话/账号/动作，之后逐资源检查真实scope再计算total/page，不使用伪造applicationId证明列表权限。缺实现默认失败关闭。列表分批读取避免一次加载全表，但仍逐行复核；大数据量下的SQL范围下推是公开启用前的性能缺口。

私有材料、已开通城市、地图合理性、受保护字段与证件规范化仍通过显式端口提供，未提供真实默认Provider。字段保护端口的内部解密仅用于已授权事务内完整提交校验，不能据此开放敏感原件GET。幂等规范化保存稳定的保护令牌，不保存证件/联系人明文，也不依赖随机加密nonce形成请求摘要。

`pet.merchant.application.enabled` 默认未开启；显式开启时必须具有真实DataSource、发号器、上述Provider、AdminAuthorizationQueryApi和事务Outbox。此开关仅装配内部领域API、真实审核事实与协议/新单资格组合，不创建HTTP路由、数据库迁移、密钥或模拟数据。通知消费者另需 `pet.outbox.enabled` 与 `pet.merchant.application.notifications-enabled`，默认均不因本轮提交开启。具体运行验证见[S5交接](../../planning/issues/wave-2/MER-001-s5/HANDOFF.md)。

## 14. S7 HTTP与首批城市/证件接通

2026-09-20用户批准按后端依赖/HTTP/前端恢复三线实施，并确认复用本地OSS、前端用微信原生选点、首批只开放成都、身份证件仅接受大陆居民身份证。此决策不自动部署或合并后续PR。

- 原10个申请接口及2个协议接口均已有默认关闭的HTTP适配；身份只能来自真实MINIAPP/ADMIN_WEB会话。未知/重复JSON、数值ID/版本、越权scope拒绝；所有版本转十进制String，时间按毫秒UTC下发。
- 本人详情增加独立可编辑投影，仅在校验当前owner后解密联系人字段，不放入审核详情、幂等回执或事件。运营详情仍脱敏，只读已提交版本。新增审核/版本ID均来自数据库事实，不由HTTP编造。
- 人工核验在内部API和HTTP均强制materialId/materialSha256，锁内匹配本submitted revision。LONG_TERM是当前领取人confirmed=true对所见原件长期有效的明确声明，服务端留存带材料ID/hash的受保护声明依据；不是服务端自动认定原件真实，也不是供应商核验证据。
- 新增只读 `GET /api/v1/c/merchant-application-cities`，MINIAPP Bearer，无请求参数。success envelope data为 `{items:[{cityCode,cityName}]}`；code为1～32位小写ASCII字母开头、其后字母/数字/下划线/连字符，name为1～64字。重复code/name无效。无配置返回503；首次部署配置为 `chengdu / 成都`，不是擅自使用行政区划代码或从地址推测城市。服务端启动配置 `pet.merchant.application.open-cities` 是开放目录，后续扩城市须产品决定。客户端从该目录选择；微信选点只提供GCJ-02坐标和地址，仍不替代后端地图合理性校验。
- 配置值与城市目录必须来自可信部署源，配置缺失不开放任意城市。机密配置只通过外部secret注入。字段保护与证件lookup使用独立密钥，不能暗中轮换固定policy；实现不会生成生产密钥。
- 证件适配按GB11643/GB32100规范化大陆15/18位居民身份证和18位统一社会信用代码；15位转换保留校验同一性。规范化、日期和校验位正确不等于身份真实，原件/人工核验及主体去重仍必需。
- 当前允许测试环境用显式外部替身验证真实HTTP、会话、数据库、AES、Outbox与站内消息，不将替身结果称为真实OSS上传、地图后端校验或商家身份核验验收。私有上传/水印授权读取的新增契约另见CCR-MER-PRIVATE-001，尚未实施。
