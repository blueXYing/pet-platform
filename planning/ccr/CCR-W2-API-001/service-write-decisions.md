# ADM-001 服务写入方决定回执（2026-09-22）

来源：用户在当前任务中对 [服务写入方提案 v0.1](service-write-proposal.md) 的原则批准与修订要求（原文要点逐条照录，不得改写语义）：

> 1. 同意五状态、编辑限制、提交审核后由运营批准上架、轻量审核及相关权限和错误码设计。Schema 按唯一 Writer 流程同步与迁移。
> 2. 撤回"读切片零变化"的实现结论——保持仅 ACTIVE 对消费者可见，同时兼容 REVIEWING/REJECTED，补齐读取与资格查询回归测试。
> 3. 封面保持必填；复用资产管线须明确素材归属、运营审阅和消费者展示授权；消费者封面展示作为本轮关联任务（C端读契约增补cover展示字段），不仅交付上传。SERVICE_COVER 上传类型的 31号私有资产管线增补由角色B在其worktree实现（MER域唯一Writer），角色A只负责 service 侧（cover_asset_id 存储、提交审核必填校验、C端读响应的封面展示字段与签名URL授权）——不改 pet-merchant-* 文件；契约文档中标注该分工。
> 4. 审核结果站内通知按 SSOT 保留：服务侧负责审核事件（ServiceReviewedEvent）及事务内 Outbox；通知侧（可靠消费、商家收件箱、权限隔离）由角色E在另一worktree/PR承接。契约先同步（Event08+事件载荷）。通知未接通不得将完整审核流程标为完成——PR描述必须如实标注该边界。
> 5. 本轮暂不做售罄和硬删除；"不做事件/Outbox"不再适用于审核通知（即：审核APPROVE/REJECT要发事件+Outbox；强制下架是否通知列剩余问题）。
> 6. 更新任务拆分和共享文件 Writer；其他已明确部分继续并行。

## 已确认

1. **SVCW-D1**：五状态 DRAFT/REVIEWING/ACTIVE/OFFLINE/REJECTED；编辑仅限 DRAFT/REJECTED/OFFLINE；append-only 决定表；C 端可见性仍仅 ACTIVE。
2. **SVCW-D2**：商家 `online`＝提交审核/重新提交（→REVIEWING）、`offline`＝ACTIVE→OFFLINE；上架仅由运营审核通过产生；沿用既有六路由名。
3. **SVCW-D3**：Schema 两项变更（service_item 列扩展+注释五值、两新表），新文档编号 33，唯一 Writer＝角色A（06 号注释 + 33 号 + Flyway 迁移）。
4. **SVCW-D4（v0.2 修订）**：封面必填不放宽；消费者封面展示纳入本轮（C 端读契约 cover 字段 + 签名 URL 授权仅 ACTIVE 可见时返回）；SERVICE_COVER 上传管线＝角色B（31 号增补），角色A不改 pet-merchant-* 文件。
5. **SVCW-D5**：商家事实门禁；不可经营 409 SERVICE_STATE_NOT_ALLOWED（实现经已批所有者准入事实查询，见提案 v0.2 §SVCW-D5 实现说明）。
6. **SVCW-D6**：轻量审核（无领取任务表），CAS+expectedVersion 拦并发；批量通过延后。
7. **SVCW-D7**：运营四路由 + 动作码 service.review.read/decide/forceOffline（DEPLOYED_ACTIONS 代登记，AUTH Owner 复核）。
8. **SVCW-D8**：新错误码 SERVICE_STATE_NOT_ALLOWED（409）、SERVICE_REVIEW_REASON_REQUIRED（400）。
9. **SVCW-D9（v0.2 修订）**：售罄/硬删除本轮不做；"不做事件/Outbox"不再适用于审核通知。
10. **SVCW-D10（v0.2 修订）**：审核 APPROVE/REJECT 决定同事务写 ServiceReviewedEvent.v1 到 Outbox；通知消费侧＝角色E；强制下架是否通知＝剩余问题。

## Event08 载荷定稿（角色E按此消费，中途不变卦；2026-09-22 与角色E对齐后增补 ownerUserId）

`ServiceReviewedEvent.v1`，eventVersion=1，aggregateType=SERVICE，aggregateId=serviceId；payload（9 字段）：`serviceId、serviceName、merchantId、storeId、submissionNo（JSON 整数，非字符串）、decisionType(APPROVE|REJECT)、opinion?（REJECT 必填 10-500，APPROVE 可空）、decidedAt、ownerUserId`。ownerUserId 为商家主账号收件人（服务侧创建时落 service_item.owner_user_id，33号增补列；消费者自包含、无需读 merchant 表）。意见不得粘贴敏感原文；envelope 沿用标准 IntegrationEvent。消费侧 Bean 装配（ServiceReviewedConsumer + `pet.service.review.notifications-enabled` 默认关闭）预留于 pet-boot EventOutboxConfiguration，随角色E分支合入后接线。

## 共享文件 Writer 分工登记（裁决第 6 条）

| 文件 | Writer |
|---|---|
| 06 号注释 + 新 33 号 + Flyway 迁移 | 角色A |
| Event08 ServiceReviewedEvent.v1 | 角色A |
| 10 号 §4.10 细化 + §5 新增 + 11 号新操作 + 12 号新错误码 | 角色A |
| 10 号 §3.3 门店小节 | 角色B |
| 31 号 SERVICE_COVER 增补 | 角色B |
| pet-boot 三共享登记文件（CBearerSessionFilter/CSessionSecurityConfiguration/CServiceExceptionHandler） | 角色A 与 角色B 行级追加（区域不重叠，合并顺序见 SCOPE-VERIFY §11） |
| pet-admin-biz AdminPermissionEvaluator.DEPLOYED_ACTIONS | 角色A 代登记三个动作码（PR 披露） |

## 保留边界（PR 如实标注）

- 通知消费侧（角色E）未接通前，完整审核流程不标完成。
- 售罄、硬删除、批量通过、运营类目 CRUD 延后；排期/订单/支付不提前。
- SERVICE_COVER 真实上传链在角色B 合入前以端口桩+SQL 夹具验证；B 合入后补真实链路回归。
