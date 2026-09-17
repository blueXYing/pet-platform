# 商家申请审核存储说明 v0.1

状态：`PRODUCT_RULES_ACCEPTED / DDL_REVIEW_ARTIFACT / NOT_IMPLEMENTED`。本说明与 [29 号 SQL](29-Merchant-Application-Schema-v0.1.sql) 同步，是 MER-001 申请、材料版本、主体核验和人工审核事实的存储审阅定义。四项产品决定已批准，具体兼容增量技术契约在本PR一起审阅。SQL只用于隔离MySQL验证，不是默认Flyway，不表示生产库已安装或业务已实现。

本切片不改写 [Schema06](06-核心数据库Schema-v0.1.sql) 的 `merchant`、`merchant_store`、`merchant_staff`，不选择 OCR 厂商，不实现业务代码、HTTP、Outbox 或站内消息消费者。所有新表由 merchant-biz 使用与现有商家表相同的 DataSource 和事务写入。

## 1. 表与列摘要

| 表 | 事实与关键列 | 关键唯一键/索引 |
|---|---|---|
| `merchant_subject_lookup_policy` | 单一 `policy_slot=1`、持久 `key_version`、固定 `algorithm=HMAC-SHA-256`。不保存 HMAC 密钥。 | `PRIMARY(policy_slot)`、`UNIQUE(key_version)`、`UNIQUE(policy_slot,key_version)` |
| `merchant_application` | `id`、`application_no`、`owner_user_id`、预分配 `reserved_merchant_id`、四态 `status`、当前/提交 `revision` 指针、当前 `review_task` 指针、`current_decision_id/current_decision_type`、`review_audit_id`、主体核验状态、`version`、提交/审核/审计时间。 | owner 和 reserved merchant 各唯一；状态、owner、时间索引；APPROVED 投影 CHECK 和末端复合 FK |
| `merchant_application_revision` | 不可变申请快照：`merchant_name`、受保护的 `contact_phone_protected/email_protected`、`merchant_type_code`、`city_code`、地址/经纬度/简介、`canonical_sha256`、创建人/时间。草稿允许字段为空。 | `UNIQUE(application_id,revision_no)`、`UNIQUE(application_id,id)`；按申请和创建时间索引 |
| `merchant_application_material` | 不可覆盖的材料事实：`material_type`、不透明 `private_asset_id`、资产 `sha256`、MIME、字节数、上传人/时间。没有 position、public URL、证件号或原始 OCR JSON。 | 同申请同资产唯一；同申请材料 ID/hash/type 唯一；申请/类型、资产 hash 索引 |
| `merchant_application_revision_material` | `application_id/revision_id/material_id` 冻结材料集合，`material_type + position` 属于 revision 槽位而非 material。 | 组合主键；同 revision 的 type/position 唯一；材料反向索引 |
| `merchant_credential_evidence` | 绑定当前 `application_id/revision_id/material_id/material_type`；`material_sha256`、`evidence_source(OCR/MANUAL)`、`evidence_status`、`credential_type`、`subject_name_protected`、`identifier_protected`、32 字节 HMAC `identifier_lookup_digest`、policy/version、`validity_kind(UNKNOWN/DATED/LONG_TERM)`、日期/证据依据、provider-neutral extractor 版本、私有原始证据引用、人工核验人/原因/时间。 | revision/material/type/hash 复合 FK；材料状态索引、credential/digest 索引；敏感值只在受保护二进制列或 HMAC 摘要中 |
| `merchant_subject_claim` | `claim_type(CREDIT_CODE/IDENTITY_NUMBER)`、HMAC `lookup_digest`、policy/version、历史 evidence 引用、`ACTIVE/RELEASED`、占有/释放时间；`active_lookup` 为按类型和摘要生成的唯一键。 | `UNIQUE(active_lookup)` 防止跨申请 ACTIVE 重复；同申请同类型最多一个 ACTIVE；按申请/状态索引 |
| `merchant_application_review_task` | 每次提交/重提的 `submitted_revision_id`、`submission_no`、`AVAILABLE/CLAIMED/CLOSED`、当前 claimant、claim/close 时间、乐观 `version`。 | `UNIQUE(application_id,submitted_revision_id)` 保证重提新 task；claim/status/时间索引 |
| `merchant_application_review_decision` | append-only 的 `decision_type(APPROVE/REJECT/REQUEST_CORRECTION)`、意见、内部备注、审核员、审核时刻、`authz_version/scope_version`、request/trace；APPROVE 必须携带本 submitted revision 的 credit/identity 两个 VERIFIED evidence proof（各自带 digest/key version）及同申请 ACTIVE claim proof 指针。 | 每 task 只能有一个决定；按申请、提交 revision、时间和审核员索引；复合 FK 保证决策属于同申请/同 revision/task，APPROVE proof 不能引用旧 revision 或 FAILED evidence |
| `merchant_application_audit` | `actor_type/actor_id`、`action_code`、from/to 状态、revision、request/trace、发生时间、可选 `decision_id`。 | 申请/时间、动作/时间索引；`DECISION` audit 必须有 decision 且引用同申请 |
| `merchant_profile_compat` | 不改 SQL06 的兼容投影：`merchant_id`、来源 `application_id/source_revision_id`、`merchant_type_code/city_code`、`source_kind(LEGACY/APPLICATION)`、版本/时间。 | merchant 主键；申请唯一；城市/类型索引；来源复合 FK |

所有 Snowflake ID 在数据库中为正 `BIGINT`。HTTP/JSON 仍序列化成十进制 String。`DATETIME(3)` 统一保存 UTC。SQL 中对字符串的二进制比较和字节长度检查使用 ascii/binary collation 或 `OCTET_LENGTH`，避免大小写/尾空格被隐式折叠。

## 2. APPROVED 的数据库闭环

`status='APPROVED'` 的 CHECK 要求 `submitted_revision_id`、`current_revision_id`、`current_review_task_id`、`current_decision_id`、`current_decision_type='APPROVE'`、`review_audit_id`、`reviewed_at`、`current_credit_claim_id`、`current_identity_claim_id` 和 `subject_verification_status='VERIFIED'` 全部具备，并要求当前 revision 等于 submitted revision。这一步防止只更新状态列或手填 `VERIFIED` 就伪造审核通过；两个 claim 指针还由复合 FK 锁定到同申请的 ACTIVE、对应类型且关联 VERIFIED evidence 的 claim。task 是否为 CLOSED 由服务锁内守卫和读取 join 检查，不能把可变 task status 镜像到 application 再做非延迟外键。

脚本末尾才把申请的循环当前指针接回审核表。复合外键进一步保证：

- `(application_id,current_decision_id,current_decision_type,submitted_revision_id)` 必须命中同申请、同提交 revision 且 `decision_type='APPROVE'` 的真实 decision；把 `REJECT` 或其他申请的 decision 填入会被数据库拒绝。
- `(application_id,submitted_revision_id)` 必须命中真实 revision；task、decision 也分别以同申请复合外键引用该 revision。
- `(application_id,review_audit_id,current_decision_id)` 必须命中同申请、挂有该 decision 的 audit；`DECISION` audit 没有 decision_id 会被 CHECK 拒绝。
- `current_credit_claim_id/current_identity_claim_id` 分别必须命中同申请的 `CREDIT_CODE/IDENTITY_NUMBER` ACTIVE claim；claim 自身只保留主体占用和历史证据引用，允许相同 digest 的重提沿用旧 ACTIVE claim。
- APPROVE decision 的两个 evidence proof 复合 FK 必须命中本 submitted revision 的 VERIFIED evidence，claim proof FK 必须命中同申请的 ACTIVE claim。服务层还必须逐项比较 evidence proof 与 claim proof 的 `claim_type/digest/policy/version`，并在首写前/读取 join 中确认一致；数据库不能用不同列之间的等值 CHECK 代替这次锁内 join。
- task 的 `(application_id,submitted_revision_id)` 唯一键和 decision 的 `task_id` 唯一键不允许为同一轮重建或追加第二个正式决定；decision 前必须由服务确认 task 为 CLAIMED，决定提交时事务内将其关闭。

数据库无法从 CHECK 中读取运营会话、检查意见含义、确认材料主体名称，或判断一次写入是否由当前 claimant 发起。因此服务层必须在同一个 merchant 事务、固定锁序 `idempotency → application → review_task → claims/evidence` 下再次核对：当前任务为 CLAIMED 且 claimant 是操作者；任务的 submitted revision 未变；意见/证据与该 revision 相符；两个必需主体 claim 均 ACTIVE；每个材料为私有资产当前 owner/hash/status；有效期按服务端业务日期仍有效；主体名称与已核验营业执照一致；最后一次授权检查仍允许 `merchant.application.decide`。服务层写 decision、merchant ACTIVE、merchant_store ACTIVE、profile 兼容投影、application 指针、task CLOSED、audit、Outbox 和幂等回执必须同一事务提交。

`review_audit_id` 必须指向这次决定产生的本地 `DECISION` audit，而不是仅随便指向历史 audit；当前 decision/task/revision/actor/scope/request 的一致性由服务层在锁内检查。撤权与 MER 本地事务之间没有跨域事务，不能对外宣称零窗口：最终权限检查失败就回滚，检查之后的外部撤权由既有两轮版本复核契约承接。

## 3. 提交、补正和重提

草稿可以创建空 revision；草稿保存只插入新 revision，并移动 `current_revision_id`。`REVIEWING` 期间禁止修改已提交 revision。提交时服务层检查材料集合：营业执照一份、身份证正反各一份、门店照片 1～6 张；宠物医院再要求行业许可证。SQL 将照片位置和所有材料槽约束在 `revision_material`，允许补正版本使用同一个不可变 material 槽位；同时约束单材料不超过 10 MiB、图片 MIME 和当前 revision 的材料归属，但“至少一张”、资产归属、医院类型所需材料和 OCR 失败分支仍由服务层完成。

`REQUEST_CORRECTION` 仍写 `merchant_application.status=REJECTED`，opinion 为 10～500 字。申请人修改时产生新的 revision，重新提交创建新的 review task；旧 task、旧 decision、旧材料和旧 audit 均保留，`UNIQUE(application_id,submitted_revision_id)` 防止把同一提交轮次重新包装成新 task。新 revision 的 claim 变更必须先锁 application 和旧 claim，再原子取得新 claim；失败时旧 claim 不释放。

申请只有四个公开状态 `DRAFT/REVIEWING/APPROVED/REJECTED`。claim/人工核验是内部事实，不新增第五个公开状态。`APPROVED` 后申请和已提交 revision 只读；本切片没有通过后改资料、撤回、取消或多门店扩展。

## 4. 敏感材料、证据与主体去重

`merchant_credential_evidence.identifier_protected` 只允许服务层写入经密钥管理服务保护的密文封装；`identifier_lookup_digest` 是专用 HMAC-SHA-256 的 32 字节摘要。任何证件号都不得进入普通字符串列、`receipt_json`、audit、decision opinion/internal note、错误消息或普通日志。`raw_evidence_asset_id` 只能指向私有对象，不能以 SQL15 `public_url` 替代。SQL 不会保存 OCR 原始 JSON。evidence 必须属于当前 revision 的材料快照，且 `credential_type` 与 material type 一致；身份证有效期和主体核验由服务层要求来自当前 revision 的身份证背面 evidence，不能拿旧 revision 或正面单独核验冒充完整证据。APPROVE decision 的 proof 外键把本轮证据锁死，历史 decision/evidence 不会被改写。

首版只有一个持久 policy slot（slot 1）。evidence 和 claim 都以 `(lookup_policy_slot,lookup_key_version)` 外键绑定同一个 policy 版本；policy 缺失、不匹配或 secret service 不可用时服务层必须返回依赖失败并禁止提交/批准。首版不支持在线轮换：不得仅修改 `key_version` 或用新 HMAC 重新算一份摘要来绕过去重；存在 ACTIVE claims 时任何配置版本不一致都必须 fail-closed。未来若批准轮换，必须另行设计双算、全量回填、每个有效版本原子占用及回滚，不能由本表自行推断。

`merchant_subject_claim.active_lookup` 是由 `claim_type + lookup_digest` 生成的 stored key，并且不包含 key version；唯一键因此在固定 policy 下跨申请阻止同一主体重复。`RELEASED` 行的 generated key 为 NULL，保留历史但不继续占用 ACTIVE 名额；同一申请重提遇到相同摘要时服务层沿用原 ACTIVE claim，不改写其历史占用语义，另以本轮 evidence proof 完成核验。APPROVE proof 和 application 当前 claim 指针的 digest/type/keyVersion 一致性是服务首写前和读取 join 必查项。对其他申请的冲突只返回通用 409，不泄露对方信息。`UNKNOWN` validity 不能为 VERIFIED；`DATED` 必须有起止日期，`LONG_TERM` 必须有起始日期且 `valid_to` 为空，并保留来自证据的 protected validity basis。

SQL 没有把身份证或统一社会信用代码限制成“仅 18 位”，也没有把证件明文格式当成新产品规则。归一、法定校验、旧格式兼容和主体字段映射必须另有已批准 Contract；本表只规定密文/HMAC 的安全边界以及 claim 类型。

OCR 证据为 provider-neutral：`extractor_name/version` 仅记录实际适配器事实，不在 DDL 选择厂商。OCR 不可用/失败可以产生 `SUBJECT_VERIFICATION_PENDING`，但 `APPROVE` 前必须由获权人工核验写入证据、完成主体/编号/有效期/去重并由当前 task claimant 执行；manual verify 与最终 approve 可以是同一个获权运营人，不是内部双人审批，也不引入 MFA。

## 5. merchant 兼容映射与 ACTIVE/SIGNED 边界

Schema06 的 `merchant` 仍保留既有 `owner_user_id`、`merchant_name`、`status`、`provider_merchant_no` 等列，不向核心 SQL06 偷加 `merchant_type_code` 或 `city_code`。`merchant_profile_compat` 提供清晰的兼容投影：

- 存量商家可由后续受控回填建立 `source_kind='LEGACY'` 行，type/city 直接来自已确认的存量来源；没有来源时不得从名称、地址或手机号猜测。
- 新申请 APPROVE 时，在同一业务事务插入 `source_kind='APPLICATION'` 行，`merchant_id` 必须等于 application 的 `reserved_merchant_id`，`source_revision_id` 必须是 APPROVED 的 submitted revision，type/city 原样来自该不可变 revision。该表不是把数据塞进 JSON，也不是让 profile 反过来证明审核成功。
- 兼容投影不能迁移到另一个 merchant，也不能覆盖历史申请来源。需要变更正式商家档案时，另行提交 Schema/Contract 变更。

APPROVE 建档时服务层创建预分配 `reserved_merchant_id` 对应的 `merchant`，状态写 `ACTIVE`，并创建一个 ACTIVE `merchant_store`；`provider_merchant_no` 保持 NULL。`ACTIVE` 只代表档案正常、未冻结/未下线，绝不代替申请 APPROVED 或协议 SIGNED。批准事务不写 `merchant_agreement_acceptance`，不自动签约；新单资格仍须同时检查 APPROVED、SIGNED、merchant/store 状态和当前账号权限。

## 6. 运行顺序、外键和回滚边界

运行前提是 SQL06 的 `merchant` 已存在；该脚本没有默认插入 policy，也没有生产初始化数据。可执行的建表顺序如下：

1. 创建 HMAC policy、application。
2. 创建 revision、material、revision_material、credential evidence、subject claim。
3. 创建 review task、review decision、application audit、profile compatibility。
4. 所有表已存在后执行脚本末尾的 `ALTER TABLE merchant_application`，一次补上 current revision/task/decision/audit 的循环复合外键。

这样不会在 `CREATE TABLE merchant_application` 时引用尚未创建的 revision/task/decision/audit，也不会省略循环指针。默认外键删除行为为RESTRICT；申请、材料、证据、claim、task、decision、audit均为留痕事实，不提供DELETE业务命令。测试fixture只整体删除自己成功创建的随机隔离库。若在隔离环境逐表清理，须先解除脚本末尾application回指的循环FK，再按profile → audit → decision → task → claim → evidence → revision_material → material → revision → application → policy顺序处理；不能在循环引用仍存在时机械倒序DROP。生产禁止照搬测试清理，迁移/回滚由PLAT-002另行设计并保留历史事实。

## 7. 接口与实现难点

- C 端只允许当前 USER 访问自己的 application；运营列表先按真实 `AdminDataScope` 过滤再分页。请求中的 merchantId/cityCode 不能当作授权证明。`merchant.application.read` 和 `merchant.application.decide` 需要真实 action/scope；原件读取另外需要 `merchant.identity.reveal`、purpose 和短期水印私有 read-grant。
- 当前 claimant 是审核并发锁，不是第二审批人。claim/release/人工核验/decision 必须锁同一 task；manual verify 明确要求当前 claimant，之后可以由该人继续 decide。服务端最终检查权限和版本，不能长期信任入口 PermissionSnapshot。
- 私有资产 Owner 必须提供当前 `assetId/owner/hash/mediaType/bytes/status`；MER 只保存 opaque assetId 和 hash。没有该端口时不能把 SQL15 的 public URL 当证件材料，也不能以 OCR fixture 或申请人自报编号生成 VERIFIED claim。
- `ApplicationReviewFactsReader` 只能从这些 MER 表按 merchant DataSource 一致性快照读取。无 application、APPROVED 缺任一决策链、decision/revision/audit 断链或未知枚举都应依赖失败；不能从 `merchant.status=ACTIVE`、`provider_merchant_no` 或空值推断有审核资格。
- 每个写操作继续使用 merchant 自有幂等表和稳定命令 namespace：`merchant.application.create-draft/save-draft/submit/claim/release/manual-verify/decide`。成功重放先重新检查当前会话、动作和 scope，再返回原 receipt；同 key 异参 409；requestId、traceId 和 source 不授予权限。
- 审核结果站内通知、可靠 Outbox、AUTH/ADM 会话、私有资产和 OCR 适配均不由本 SQL 交付。没有这些真实依赖时，状态链只能标阶段交付，不能声称 MER-001 完整 DoD。

## 8. DDL 能保障什么、服务层必须保障什么

DDL 能保障实体引用存在、申请与 revision/task/decision/audit 的同申请关系、APPROVED 指针的非空和 APPROVE 类型、两类 ACTIVE VERIFIED claim、APPROVE 的本轮 evidence proof、decision audit 的绑定、不可复用的提交轮次、主体摘要的 ACTIVE 唯一、材料大小/类型/位置、日期/坐标/版本等结构约束，以及敏感字段不落明文列。task 的 AVAILABLE/CLAIMED/CLOSED 状态本身有 CHECK，但 application 指针不镜像可变 task status。

DDL 不能保障可变 task 状态与 application 当前指针同时更新、跨表业务内容相等（例如 proof digest 与 ACTIVE claim digest 的等值、营业执照主体名称与 revision 名称）、身份证有效期确实来自背面、资产服务的 owner/status、提交时材料齐套、服务端业务日期下证件有效、当前会话动作/数据范围、锁顺序、HMAC secret 可用、日志脱敏、merchant/store 同事务建档、Outbox 可靠投递或通知消费幂等。这些必须在服务层同一事务、真实依赖和后续集成测试中证明；不能以一张 status 表或一条 fixture 记录替代。
