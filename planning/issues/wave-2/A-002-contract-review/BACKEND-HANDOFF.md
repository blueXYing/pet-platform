# A-002 材料引用后端交接

授权：用户于2026-09-21明确批准PR60的材料引用CCR方案。唯一Writer为当前根任务，分支codex/a002-contract-review-20260921；前端由ZCODE维护。本轮不修改PR59、不合并、不部署。

## 给前端的接入信息

GET `/api/v1/admin/merchant-applications/{applicationId}` 的 `data.submittedRevision.materialReferences` 为必需数组，每项只有：

```json
{"materialId":"9001001","assetId":"8001001","materialSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","materialType":"ID_CARD_BACK","position":1}
```

上例仅为格式示例，不是真实材料引用。材料编号、资产编号及版本仍为String，不转Number；position为整数。按assetId与现有图片槽位对应；人工核验传materialId和登记materialSha256，禁止自行计算替代。引用来自本次submittedRevision，后续草稿不混入；顺序固定为materialType字典序、position、materialId数值序。

身份证需查看正反面，提交一条绑定ID_CARD_BACK的IDENTITY_NUMBER证据；其他主体证据按营业执照和医院许可证既有规则提交。缺字段、未知枚举、非法摘要/ID或版本映射不一致时保持提交禁用，不把错误当空数组。依旧需要当前会话、动作、资源范围、任务领取人及请求幂等；元数据不等于材料已查看，也不授权图片读取。

## 后端变化与兼容性

- 新增内部不可变MaterialReference列表，仅运营详情公开映射。复用SQL29材料关联，不增加表、DDL、迁移、HTTP路径或事件。
- 在同一事务快照中读取申请提交指针、任务、材料及提交内容，核对任务版本引用和必需材料集合；损坏引用/缺材料失败关闭。
- 运营列表、C端详情、写入回执不增加材料引用字段。严格校验响应的运营客户端需随本次OpenAPI变更更新；当前PR59尚未接通，不能认为后端合入即前端可用。
- 审核提交仍保留后端锁内materialId/hash比对、身份/权限和版本检查；不放宽核验业务规则或生产开关。

## 验证口径

验证结果及准确计数见[validation.json](validation.json)。真实MySQL测试覆盖编号与资产编号不同、摘要权威来源、稳定顺序、不可变列表、权限/范围拒绝、损坏材料集合、补正草稿与已提交版本隔离。真实TCP生命周期测试改为从HTTP详情取引用，拒绝资产ID/错误摘要替代后，用正确引用完成核验和审核；该测试使用受控外部身份/材料适配器，不能当作浏览器HTTPS、真实原件水印或手机端验收。

本地全量clean verify通过403项（零失败/跳过）。最终读取锁及并发用例收敛后，对商家运行/安全/投影、HTTP生命周期及架构重新定向验证，结果全部通过；未将收敛前全量结果冒充最终源码的全量复跑。离线契约115项通过，源架构检查与文档差异检查通过。读取会持有本申请行锁直至投影完成，并发用例验证期间其他连接不能修改该申请版本；高并发吞吐不属于本轮验收结论。

首次新增损坏摘要测试被数据库CHECK约束提前阻止，已改为验证数据库拒绝及读侧缺失关联失败关闭；首次本地TCP复跑因新worktree缺tsx未运行完整链路，安装锁定依赖后重跑。未降低测试断言或生产门禁。

## 剩余范围

ZCODE需接入上述投影、恢复人工核验提交并覆盖正反例；代理来源、未决恢复与真实浏览器到后端联调仍单独推进。PR59/60保持独立审阅；本次授权不包含合并、生产迁移或发布。材料保留期限、完整MER-001其余AC仍沿用既有台账。
