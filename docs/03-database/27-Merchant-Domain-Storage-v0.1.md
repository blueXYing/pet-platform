# 商家域存储映射 v0.1

状态：ACCEPTED_STORAGE_DESIGN / DDL_NOT_DELIVERED。2026-09-17用户确认技术契约后从PR50提案同步。与[商家域接口契约](../04-api/27-Merchant-Domain-Contract-v0.1.md)配套；实际建表脚本、索引/约束验证、初始化与生产迁移尚未交付，不添加默认Flyway、不声明表已存在。下文明确留待其他Owner交接的依赖不因设计批准自动完成。

## 1. 现有实体

| 表 | 保持的事实 | 禁止推断 |
|---|---|---|
| merchant | id、owner_user_id唯一、merchant_name、status、version、审计时间 | ACTIVE不代表有真实审核记录/已签协议；provider_merchant_no不能代替电子协议 |
| merchant_store | id、merchant_id、名称地址坐标电话、status、version | 有门店不能代表当前用户属于门店；营业/排期不是status的别名 |
| merchant_staff | id、merchant_id、store_id、姓名电话、employment_status、service_enabled、version | phone不能绑定user；service_enabled不是子账号启停；staff人数不是可约容量 |

写入前锁定本域目标并核对商家/门店归属；接口更新禁止迁移staff到另一个merchant/store。实体被停用仍保留历史引用；软删除契约未同步前不实现DELETE。

## 2. 最小新增关系（表名/类型均为已批准）

### merchant_agreement_version

- id BIGINT主键；agreement_version VARCHAR(64) ASCII二进制唯一键。
- content TEXT（非空UTF-8纯文本，统一上限65535字节），content_sha256 CHAR(64) ASCII二进制。
- published_at DATETIME(3)、published_by_operator_id BIGINT；发布后整条业务内容不可改。
- published_by由真实运营权限得出，不允许客户端自报。内容变更必须创建新版本；不使用update把历史文本换掉。

### merchant_agreement_current

- agreement_key VARCHAR(32)主键，V1固定MERCHANT；agreement_version_id BIGINT、version BIGINT、updated_at DATETIME(3)。
- 发布指针和新版本在同库原子发布，指向已发布有效版本。首次同意锁/读取当前指针，与发布并发时按同一锁顺序判定当前版本；不接受“刚查完版本就被覆盖”的中间事实。
- 发布功能属于原运营内容维护范围的后续契约；生产没有发布内容时依赖失败，不能内置假协议完成签约。

### merchant_agreement_acceptance

- id BIGINT主键；merchant_id BIGINT、agreement_version_id BIGINT、accepted_by_user_id BIGINT、accepted_at DATETIME(3)。
- UNIQUE(merchant_id, agreement_version_id)，保存版本引用与签署时hash（CHAR(64)，与不可变版本内容一致），请求审计trace与requestId原值按幂等表保留。
- 当前已批准不强制重签，因此商家已有真实有效同意记录就保留签约效力；不因发布指针更新退回未签。
- 一个商家首次同意串行锁merchant行；只有执行时实际owner可写；SIGNED由真实同意记录派生，不另放可随意置true的缓存列。没有记录且查询成功才是NOT_SIGNED。
- 不新增SIGNING/FAILED过程记录，不接入外部签约Provider。

### merchant_member 与 merchant_member_store_grant

- member：id、merchant_id、user_id、status(ENABLED/DISABLED/REVOKED)、version、created_at/updated_at；UNIQUE(merchant_id,user_id)。OWNER仍来源merchant.owner_user_id，不重复插入一条假staff记录。
- grant：id、member_id、store_id、可空的实际staff_id、status、version、审计时间；UNIQUE(member_id,store_id)。引用目标必须同商家；staff_id若存在还必须属于该store。
- 动作授予另以(member_id,store_id,action_code)唯一关联，实际动作列表由AUTH/权限契约同步。没有授予行不隐含全权限；未知角色名不升级权限。
- 创建成员需要“该真实用户同意绑定/身份核实”的完整已批流程，目前尚缺。已批准只定义关系存储，不开放通过任意userId/手机号添加的HTTP端点；不存在真实关系时不可生成生产授权。
- 撤权修改、grant/成员版本递增及审计在本域同事务；普通读和成功重放重新查真实关系。撤权与在途写入的锁序/一致性须在AUTH交接中冻结，不能用缓存TTL代替。

## 3. 申请/审核事实与后续字段缺口

现有Schema06没有申请表。已批准资格投影至少需applicationId、ownerUserId、merchantId、status(DRAFT/REVIEWING/APPROVED/REJECTED)、version、reviewedAt及审核审计引用；APPROVED必须追溯实际审核动作。S2新单资格不可从merchant.status重建该投影。

完整申请需承接PRD主体去重、审核中只读、驳回重提、城市、定位、证件/有效期、私有资产等，不能仅建一张status表称入驻完成。主体唯一键来源、敏感材料存储与OCR属于CCR-W2-API-001商家/治理联合交接；未冻结不得用商家名或手机号代替同一主体。

档案岗位/头像/资质/擅长项、staff软删除状态、店铺营业时间/简介/相册/公告等现有列缺口按原MER/M/ADM Issue补齐；不隐式塞进无Schema的JSON包。员工离职状态变更与订单改派依赖见主提案，S2不实现危险半成品写入。

## 4. 幂等与审计物理所有权

不从merchant-biz访问user-biz的CommandIdempotencyStore。已批准采用merchant自有 `merchant_command_idempotency` 表，沿用已批23号语义和SQL14字段结构/边界，实际DDL在实现交接时补齐并验证，不改共享公共五字段Context。

审计记录包含actorType/actorId、动作、merchantId/storeId、targetId、requestId、traceId、发生时间和状态/版本变化；与业务同事务。不写令牌、明文证件或完整敏感协议请求到普通日志。新key已签同一版本不能伪造新的acceptedAt；命令审计与业务同意记录区分。

所有新表ID来自既有Snowflake接口，HTTP为String。索引/唯一约束必须由真实MySQL回归证明；无正式迁移编号、无默认启用、不导入生产数据。生产ID高水位/可信宿主恢复、迁移计划与回滚兼容性仍归PLAT-002后续阶段。
