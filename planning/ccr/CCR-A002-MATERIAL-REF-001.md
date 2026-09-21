# CCR-A002-MATERIAL-REF-001：运营审核提交版本材料引用

状态：APPROVED / BACKEND_IMPLEMENTED_LOCAL_VERIFIED。2026-09-21用户在本CCR五字段方案说明后明确回复“批准”，授权同步契约并实施后端。后端投影及契约已交付，准确验证见[后端交接](../issues/wave-2/A-002-contract-review/BACKEND-HANDOFF.md)。当前根任务负责后端/契约，ZCODE负责前端；不授权合并或生产部署。

## 问题与已有依据

API30人工核验要求materialId/materialSha256；API31水印读取按assetId签发。商家域实际比较merchant_application_material.id和sha256，二者不能用private_asset_id与动态水印图片摘要代替。当前运营详情只暴露资产ID，缺少核验引用。

来源：[API30](../../docs/04-api/30-Merchant-Application-Contract-v0.1.md)、[API31](../../docs/04-api/31-Private-Asset-Contract-v0.1.md)、[Storage29](../../docs/03-database/29-Merchant-Application-Storage-v0.1.md)。核验代码MerchantApplicationService.prepareEvidence及执行事务是现有行为依据，不更改主体核验产品规则。

## 最小变更提案

扩展现有GET /api/v1/admin/merchant-applications/{applicationId}的data.submittedRevision，新增必需数组materialReferences，不新增公开路径、不改变已有字段含义。同次响应的submittedRevision.revisionId必须等于顶层submittedRevisionId及task.submittedRevisionId；材料集合只能来自该submitted revision，不可混入补正后的未提交草稿。不存在合法提交版本时沿用既有错误规则，不以空引用掩盖依赖损坏。

每个元素固定为以下五字段：

| 字段 | 类型 | 权威来源和约束 |
|---|---|---|
| materialId | 十进制String | merchant_application_material.id；绝不使用assetId |
| assetId | 十进制String | merchant_application_material.private_asset_id；用于与现有图片入口对应 |
| materialSha256 | 小写64位十六进制String | merchant_application_material.sha256；登记的标准化对象摘要，不是原上传源摘要，也不是水印响应摘要 |
| materialType | String枚举 | STORE_PHOTO/BUSINESS_LICENSE/ID_CARD_FRONT/ID_CARD_BACK/INDUSTRY_LICENSE，来自提交版本材料关联 |
| position | 非负整数 | merchant_application_revision_material.position；按现有存储槽位原样返回，不重新编号 |

数组按materialType字典序、position、materialId数值顺序稳定排序。材料类型/位置、ID、assetId映射必须与提交快照一致；无效关联、未知类型或摘要不合法按依赖不可用失败关闭，不猜默认值。相同资产复用规则保持原契约，不添加新的产品限制。

仅添加材料引用元数据，不返回对象key、OSS URL、原件、证件号、联系人明文、源摘要、扫描原始报告或授权token。元数据不证明操作者已读图片，也不授予任何读取/写入权限。

## 权限与事务

运营详情沿用merchant.application.read及当前数据范围校验，不向C端、列表或公开DTO添加该数组。该方案不把摘要当作秘密凭证；材料图片仍须当前领取人、decide+identity.reveal、用途/原因、一次性授权与审计。核验命令仍在锁内复核当前身份、领取人、申请/任务版本、提交revision、materialId/hash，旧响应不能替代最终鉴权。

Backend Core在merchant-api定义只读引用DTO，通过本域查询服务/Mapper读取同一一致性快照，再由boot映射HTTP。boot不访问商家Repository/Mapper，不跨biz依赖；复用SQL29关系，不新建表、不增加迁移或事件。任何实施中发现的额外字段/权限变化回到本CCR审阅。

## 前端接入说明

1. 收到完整且有效的引用数组后才恢复人工核验提交；接口缺字段、映射冲突、缺材料均失败关闭，不能退回计算水印hash。
2. 营业执照一条CREDIT_CODE证据；身份证正反面都展示/查看，但只以ID_CARD_BACK引用提交一条IDENTITY_NUMBER证据；医院许可证按已批准条件提交一条INDUSTRY_LICENSE证据。STORE_PHOTO不作为主体证件证据。
3. materialId/materialSha256必须原样传递；切换申请、提交版本变化、撤权/登出时清除旧图片及证据状态，释放Object URL。
4. LONG_TERM与DATED均要求validFrom；DATED要求合法validTo，LONG_TERM的validTo为null且明确人工声明，不由前端默认“长期”。沿用当前HTTP/领域校验。
5. 未核验只阻止APPROVE，不阻止符合当前命令权限的REJECT/REQUEST_CORRECTION。

## 实施与验收清单

- 本方案已由用户批准，实施同步API30、OpenAPI11及内部查询契约，登记兼容性和准确字段。
- 后端真实MySQL/HTTP：assetId不同于materialId；watermarked hash不同于登记hash；本次提交与补正草稿分离；跨申请/跨revision伪造拒绝；权限与范围正反例；旧版本冲突；正确引用核验与随后审批成功。
- 身份证前后同时查看但单条主体证据、重复credential拒绝、DATED/LONG_TERM校验，保持原反例。
- 前端覆盖缺字段零提交、严格String ID/摘要校验、原请求幂等、撤权隔离；真实浏览器到真实后端完成核验/补正/审批，不用拦截响应代替集成证据。
- 默认生产开关、正式迁移/ID/密钥门禁不因CCR通过解除。未变更Schema/Event；若实现偏离本条则单独披露。
