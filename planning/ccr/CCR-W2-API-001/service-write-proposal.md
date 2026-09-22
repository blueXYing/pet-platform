# CCR-W2-API-001 服务写入方契约提案（草案 v0.1）

> **DRAFT — 未提交人工批准，不得据此实现。** 本文件仅为规划草案：其中一切 DTO/路由/DDL/错误码/命令名均为候选，未经批准不得同步任何权威文档（06/07/10/11/12/23/27/31/Event08）、不得派发实现、不得写入 backend/。批准后由明确 Owner 同步权威文档再实施。

状态：DRAFT_PROPOSED（未提交审阅）。规范版本：0.1，日期：2026-09-22。
提出方/唯一编辑者：角色A（服务写入方后端，ADM-001 服务写入切片规划阶段）。关联 Issue：ADM-001（运营商家/服务治理）/ EPIC-18 / ST-ADM-01，依赖 MER-001/SVC-001/AUTH-001；消费者：M-002（工作台服务管理页）、A-002（运营审核页）、C-003（间接受益：有真实数据可展示）。
基线：develop `52a1c45`（PR#65 服务域读切片已合入）；分支 `codex/adm001-service-write-20260922`。
配套核验：[SCOPE-VERIFY.md](../../issues/wave-2/ADM-001-service-write/SCOPE-VERIFY.md)、[TEST-PLAN.md](../../issues/wave-2/ADM-001-service-write/TEST-PLAN.md)。

## 修订记录

| 版本 | 变更 |
|---|---|
| 0.1（2026-09-22） | 初稿，SVCW-D1～D10；仅规划，无任何权威/代码变更 |

## 人工 CTO 一页阅读指南

**用通俗话说：现在数据库里的"服务"只能靠测试脚本手工插入，商家在小程序工作台里没法新增/编辑/上架服务，运营也没法审核——所以"商家发布服务→消费者看到→预约"整条链走不通。这个提案要把"商家管自己的服务"和"运营审核服务"两套写接口的字段、规则、错误先写成书面约定。要点有三个：一是数据库表缺一堆 PRD 要求的字段（划线价、封面、适用宠物类型等）和"待审核/驳回"两个状态，要列清楚怎么补；二是防止商家"下架再上架绕过审核改价格"，重新上架必须再过审；三是运营只能审核和强制下架，不能替商家上下架。以下十条决定建议请逐条勾选。**

| 分工 | 内容 |
|---|---|
| 我负责 | 逐操作字段/校验/错误/鉴权映射、状态机与乐观锁规则、Schema 差距 DDL 草案、权限反例、测试映射与依赖核验 |
| 你负责 | 审阅"建议决定"十项（含两项 Schema 变更与两项新错误码）；权威契约同步与实现派发按流程另批 |
| 不变 | 产品规则原样执行（单次服务/必须预约/金额两位 String/快照不受主数据变化影响/审核 SLA 24 小时），不提前实现排期、库存锁、订单创建、支付、售罄、硬删除 |

---

## 建议决定（需人工批准的十项）

1. **SVCW-D1 状态机：status 单列扩展五值 + append-only 审核决定表；编辑仅限 DRAFT/REJECTED/OFFLINE**。
   `service_item.status` 值域扩为 `DRAFT/REVIEWING/ACTIVE/OFFLINE/REJECTED`（权威 06 SQL 该列无 CHECK，仅注释修订+应用层校验；C 端可见性仍仅 `ACTIVE`，已批读切片四条件合取与索引零变化）。审核历史/驳回原因/重提次数由新表 `service_review_decision` 承载（不引入提交快照/revision 表，防"审核中偷改"改为状态机写保护：**仅 DRAFT/REJECTED/OFFLINE 可编辑**；对 ACTIVE/REVIEWING 编辑返回 409 `SERVICE_STATE_NOT_ALLOWED`）。理由：PRD 状态机无 ACTIVE→REVIEWING 直接转移边，"下架→重新上架→待审"即防绕审改价机制；编辑 ACTIVE 需先下架。
   **备选**：审核态放子表（visible_status 复合计算）——需改动已批读切片可见性判定，不推荐；或"编辑 ACTIVE 自动转待审核"——新增 PRD 未定义转移边，不推荐。
2. **SVCW-D2 商家端沿用 HTTP10 §4.10 既有六路由；`online` 语义＝提交审核/重新提交（→REVIEWING），`offline`＝ACTIVE→OFFLINE；"上架"仅由运营审核通过产生**。
   不新增 `/submit-review` 路由、不改权威已列路由名；`POST .../{serviceId}/online` 的成功语义是"进入待审核"（DRAFT/REJECTED/OFFLINE→REVIEWING），响应 data.status=REVIEWING。商家动作从不直接产生 ACTIVE（PRD 商家端 §6.3/运营端 §7.2/§5.2 衔接点"必须保留强制审核环节"）。
   **备选**：新增显式 `POST .../{serviceId}/submit-review` 并废弃 online 命名——偏离权威已列路由，需改 10 号 §4.10，仅在强烈倾向语义清晰时选择。
3. **SVCW-D3 Schema 变更（两项，均待批准后由 Owner 执行）**：
   (a) `ALTER TABLE service_item`：status 注释扩五值；新增列 `list_price DECIMAL(18,2) NULL`（划线价，CHECK ≥ price）、`cover_asset_id BIGINT NULL`（封面素材，提交审核时应用层必填）、`applicable_pet_types VARCHAR(64) NULL`（逗号分隔 DOG/CAT/EXOTIC/ALL，ALL 与其他互斥应用层校验）、`staff_requirement VARCHAR(200) NULL`、`verification_required TINYINT(1) NOT NULL DEFAULT 1`、`aftersale_note VARCHAR(500) NULL`、`remark VARCHAR(500) NULL`、`submitted_at DATETIME(3) NULL`（SLA 起算）。不加"服务形态/是否需要预约"列（V1 固定值，SSOT 封板）。
   (b) 新表 `service_review_decision`（append-only：APPROVE/REJECT、opinion 10-500（REJECT 必填，CHECK 约束仿 29 号 `chk_mer_decision_opinion`）、submission_no、operator/authz_version/scope_version/request_id/trace_id）与 `service_governance_action`（FORCE_OFFLINE 审计：reason 10-500、operator、authz/scope、request_id）。
   新权威文档建议 `docs/03-database/33-Service-Write-Schema-v0.1.sql`（03-database 现编号至 31，全库至 32）；Flyway 版本号实现阶段另派（migration/README 要求实现前整合）。`service_category` 运营 CRUD **不纳入**本切片，仅随切片提供最小只读路由 `GET /api/v1/merchant/service-categories`（ENABLED、sort_no 排序，商家表单事实源）。
   **备选**：决定表与治理动作表合并为一张 `service_admin_action`（少一张表，语义混装）；或类目读路由延后（前端暂无事实源，不推荐）。
4. **SVCW-D4 封面图链路：落 `cover_asset_id` 列，上传/扫描复用既有私有资产管线（新增 SERVICE_COVER 材料类型），公开展示链路延后**。
   提交审核时必填 cover_asset_id（PRD 必填+内容安全审核）；上传走既有 `POST /api/v1/c/private-assets` 扩展（31 号契约增补，需 MER 域 Owner 评审）；C 端封面展示字段不在已批读契约（ServiceDetailView 无 cover），后续 C 端读切片扩展时另走增补。**若复用被否**：备选一，coverUrl 字符串（无内容安全锚点、无审计，不推荐）；备选二，封面整链延后且提交审核暂不强制封面（**与 PRD 必填冲突，须产品明示放宽**）。
5. **SVCW-D5 商家事实通道与写命令门禁：`MerchantMembershipQueryApi.getFacts`（27 号 §5 已批）单查询取归属+四态事实；商家/门店不可经营时写命令 409**。
   service-biz 仅依赖 pet-merchant-api（读切片 `ServiceQueryApiImpl` 注入 `MerchantDisplayEligibilityApi` 范式）；写命令校验序：membershipKind=OWNER 且 membershipEnabled（否则无关系 404 防枚举/非 OWNER 403）→ 事实可读否则 503（失败关闭）→ merchant=ACTIVE ∧ store=ACTIVE ∧ application=APPROVED ∧ signing=SIGNED 才可经营，否则 **409 `SERVICE_STATE_NOT_ALLOWED`**（事实明确但当前状态不允许经营写）。getFacts 已返回 membershipKind/membershipEnabled/applicationStatus/signingStatus/merchantStatus/storeStatus/authzVersion，单次同快照足够。
   **备选**：`checkOrderEligibility`（三布尔）+另补 OWNER 判定——两查询拼装且该接口语义是"新单资格"不是"管理资格"，不推荐。409 备选 403（状态非权限，倾向 409）。
6. **SVCW-D6 审核任务模型：轻量（无领取任务表），并发双审由 status CAS + expectedVersion 拦截；批量通过延后**。
   V1 单运营（AGENTS 已批运营权限补充：单运营、无内部双人审批），申请审核的 AVAILABLE/CLAIMED/CLOSED 领取模型为多人队列设计，服务审核不需要。运营决定命令带 expectedVersion（service_item.version），事务内 `UPDATE ... SET status=? WHERE id=? AND status='REVIEWING' AND version=?`。"批量通过低风险服务"（PRD §6.2.4）延后（需批量幂等与风险判定规则，另行提案）。
   **备选**：完整复刻 `merchant_application_review_task` 领取模型（为未来多运营预留；当前增加表与流程成本）。
7. **SVCW-D7 运营端新路由族与动作码：`/api/v1/admin/services` 四条；动作码 `service.review.read` / `service.review.decide` / `service.forceOffline`（登记归 AUTH/RBAC Owner）**。
   `GET /api/v1/admin/services`（审核列表：status∈{REVIEWING,ACTIVE,OFFLINE,REJECTED} 筛选、分类、商家、提交时间、SLA 剩余）、`GET /api/v1/admin/services/{serviceId}`（含历史驳回记录与重提次数）、`POST /api/v1/admin/services/{serviceId}/decision`（APPROVE→ACTIVE；REJECT→REJECTED，opinion 10-500 必填）、`POST /api/v1/admin/services/{serviceId}/force-offline`（ACTIVE→OFFLINE，reason 10-500 必填，记录治理动作）。会话与鉴权先例＝`MerchantApplicationAdminController`（真实 AdminSessionView + `admin(req, actionCode)` + AdminAuthorizationReference + adminCommand 携 X-Request-Id）。HTTP10 §5 现无服务审核路由，属本提案补缺。
   **备选**：路由挂 `/api/v1/admin/merchant-services`（与 merchant-applications 命名对齐）——语义上审核对象是服务不是商家，推荐 `/admin/services`；动作码命名备选 `admin.service.review.decide` 前缀风格，随 RBAC 登记统一定。
8. **SVCW-D8 错误码：新增 `SERVICE_STATE_NOT_ALLOWED`（409）与 `SERVICE_REVIEW_REASON_REQUIRED`（400）**。
   命名对齐既有 `ORDER_STATE_NOT_ALLOWED`（12 号 §3）与 `REFUND_MERCHANT_REASON_REQUIRED`（12 号 §4）。其余全部复用：COMMON_INVALID_ARGUMENT/UNAUTHORIZED/FORBIDDEN/NOT_FOUND/CONFLICT/DEPENDENCY_UNAVAILABLE、IDEMPOTENCY_KEY_CONFLICT、SERVICE_NOT_FOUND（12 号:10-27、163-169）；27 号式映射（12 号:223）。
   **备选**：不新增，状态违规统一 COMMON_CONFLICT、原因缺失统一 COMMON_INVALID_ARGUMENT——省注册但丢失域语义与前端可判别性。
9. **SVCW-D9 本轮显式不做：售罄态、硬删除、事件/Outbox**。
   售罄：无库存/可售次数事实源（V1 容量=min(配置,可用人员) 属排期域动态事实），运营端 §7.2 亦无此态，实现需排期联动（明令不提前）；列产品未决（若 V1 保留售罄概念需先裁决库存事实源）。硬删除：订单域无"该 serviceId 是否被引用"内部查询，不得跨模块读 order Repository（AGENTS），对照 27 号 §6.1 员工 disable IMPLEMENTATION_BLOCKED 先例——即使有查询也需与订单创建建立并发协调，"先查无单再删"不安全；列未决。事件：Event08 无 SERVICE 聚合事件，新增须另批（见 SVCW-D10）。
10. **SVCW-D10 审核结果通知：本轮不实现，登记张力待裁决**。
    SSOT §一"订单、退款、核销、售后、**审核**必须保留站内消息"（01-SSOT:46）若覆盖服务审核，则需 MERCHANT 收件箱（CCR-W2-NOTIFICATION-001 明确商家端消息路径未实现）+ 仿 `MerchantApplicationReviewedEvent.v1` 的服务事件 + Outbox 接入，均不在本切片。**备选**（若裁决要求本切片同步通知）：增补 ServiceReviewedEvent + notification 域消费者，工作量与跨域文件显著增加，需重新排期。

---

## 1. 范围与来源（依赖核验结论）

| 来源 | 核验结果 |
|---|---|
| PRD 商家端 §5.5/§5.6/§6.3；运营端 §3.3/§5.2/§6.2.4/§6.1/§7.2 | 归属与状态机证实（详见 SCOPE-VERIFY A1）；运营不代商家上下架；服务审核 SLA 24 小时 |
| Schema 06（:129-146） | service_item 缺 REVIEWING/REJECTED 及 PRD 八字段（A2.3）；service_category 字典已存在 |
| 内部 API07 §5.1/§5.1.1（:221-297） | 读契约已批四方法；**无任何写命令**，本提案首次补 `ServiceCommandApi`（07 号 §5 增补待批） |
| HTTP10 §4.10（:990-999） | 商家六路由已列、无响应/字段/错误定义（本提案补）；§5 无运营服务审核路由（本提案补四条） |
| 23 号 §3～§7 / 14 号 | 写命令 requestId 幂等全规则已批；command_idempotency 表已备；命令名+operatorType+operatorId+目标 ID 入规范参数（27 号 §7 先例） |
| 27 号 §3/§4/§5/§6/§7 | expectedVersion、错误映射、getFacts/checkOrderEligibility、merchant 前缀会话、写事务执行序先例均可沿用 |
| 12 号 | COMMON 族+SERVICE_NOT_FOUND 既有；拟新增两码（SVCW-D8） |
| Event08 | 无 SERVICE 事件（SVCW-D9/D10） |
| 读切片（PR#65） | CServiceController/ServiceQueryApiImpl/ServiceQueryConfiguration/CBearerSessionFilter 装配先例；可见性=ACTIVE 合取不得破坏 |
| SVC-001 HANDOFF:28 / CCR 索引:16 | 本切片承接登记：ADM-001"服务操作"，M-002 消费 |

## 2. 内部命令与 DTO（pet-service-api，形状获批后同步 07 号）

```java
public interface ServiceCommandApi {
    ServiceItemResult createDraft(CreateServiceItemCommand command);          // merchant.service.create
    ServiceItemResult update(UpdateServiceItemCommand command);               // merchant.service.update
    ServiceItemResult submitForReview(SubmitServiceItemCommand command);      // merchant.service.submit
    ServiceItemResult takeOffline(TakeServiceOfflineCommand command);         // merchant.service.offline
    ServiceItemResult decideReview(DecideServiceReviewCommand command);       // admin.service.review.decide
    ServiceItemResult forceOffline(ForceOfflineServiceCommand command);       // admin.service.forceOffline
}
```

命令形状（对齐 MerchantApplicationCommandApi/CommandContext 五字段）：

```java
public record CreateServiceItemCommand(
    String merchantId, String storeId,
    String serviceName, String categoryId, FulfillmentType fulfillmentType,
    java.math.BigDecimal price, java.math.BigDecimal listPrice,          // MoneyCodec 词法，两位小数
    Integer durationMinutes, String coverAssetId,
    java.util.List<String> applicablePetTypes,                           // DOG/CAT/EXOTIC/ALL
    String staffRequirement, Boolean verificationRequired,
    String description, String aftersaleNote, String remark,
    CommandContext context) {}

public record UpdateServiceItemCommand(/* 同上业务字段 */ String serviceId, long expectedVersion, CommandContext context) {}

public record SubmitServiceItemCommand(String serviceId, long expectedVersion, CommandContext context) {}

public record TakeServiceOfflineCommand(String serviceId, long expectedVersion, CommandContext context) {}

public record DecideServiceReviewCommand(
    String serviceId, long expectedVersion,
    String decisionType,                // APPROVE / REJECT
    String opinion,                     // REJECT 必填 10-500；APPROVE 可空 ≤500
    AdminAuthorizationReference authorization, CommandContext context) {}

public record ForceOfflineServiceCommand(
    String serviceId, long expectedVersion,
    String reason,                      // 必填 10-500
    AdminAuthorizationReference authorization, CommandContext context) {}

public record ServiceItemResult(
    String serviceId, String merchantId, String storeId, String status,
    long version, java.time.OffsetDateTime updatedAt) {}
```

查询增补（审核列表/详情，运营端消费，`ServiceQueryApi` 或独立 `ServiceReviewQueryApi`——**建议独立**，保持已批四查询不动）：`ServiceReviewQueryApi.listForReview/getForReview`，返回含 serviceName/merchant/store 归属、分类、履约方式、售价/划线价、状态、submissionNo、submittedAt（+SLA 剩余计算）、历史驳回记录（opinion/decidedAt）、重提次数。另增商家工作台列表/详情只读（M-002 消费：本店全状态分页，非 C 端可见性过滤）——同一 ReviewQueryApi 家族或 `ServiceManagementQueryApi`，命名随批准定。

校验（违规 400 COMMON_INVALID_ARGUMENT，details 指明字段）：
- ID 全部雪花 String（23 号 §1）；`expectedVersion` 非负 Long String（27 号 §3）。
- serviceName 2-50 字（PRD）；categoryId 必须命中 ENABLED 类目（服务端字典校验，不信客户端枚举）；fulfillmentType ∈ {IN_STORE, PICKUP_DELIVERY}；price > 0.00 且 MoneyCodec 词法（输入可无小数或 1-2 位，输出两位）；listPrice 可空、≥ price；durationMinutes 分钟 > 0；coverAssetId 提交审核时必填（SVCW-D4）；applicablePetTypes ⊆ {DOG,CAT,EXOTIC} 或 =[ALL]；staffRequirement ≤200；description ≤1000；aftersaleNote/remark ≤500；opinion/reason 10-500（按命令必填性）。
- DRAFT 保存可缺必填（草稿宽松）；**submitForReview 前置校验**：必填齐全（名称/分类/履约方式/售价/封面/适用宠物类型/门店——运营端 §5.2 第二步清单）。

## 3. 状态机与命令门禁（实现规则）

```
DRAFT ──create──▸ DRAFT（可重复编辑）
DRAFT/REJECTED/OFFLINE ──submitForReview（必填齐全）──▸ REVIEWING（submitted_at=now，submission_no+1）
REVIEWING ──adminApprove──▸ ACTIVE
REVIEWING ──adminReject（opinion 必填）──▸ REJECTED
ACTIVE ──takeOffline（商家）──▸ OFFLINE
ACTIVE ──forceOffline（运营，reason 必填）──▸ OFFLINE
```

- 商家**从不能**把服务置为 ACTIVE（上架仅运营审核通过产生）；OFFLINE/REJECTED 重新上架一律过审（防绕审改价，PRD 两端一致）。
- 每命令前校验（27 号 §7 执行序）：会话→动作/归属（getFacts，SVCW-D5）→状态机合法（否则 409 SVCW-D8）→expectedVersion CAS→写 service_item+决定/治理表+最小回执，同一事务；幂等绑定经 command_idempotency（独立短事务 RESERVED→业务事务复核）。
- 读侧零变化：C 端四条件合取仍 `status='ACTIVE'`（REVIEWING/REJECTED 不可见，与 OFFLINE/DRAFT 同为 404/隐藏）；内部 checkBookable reasonCodes 不变（SERVICE_OFFLINE 涵盖非 ACTIVE）。

## 4. Schema DDL 草案（权威同步待批后执行；Flyway 版本实现阶段另派）

```sql
-- 建议新文档：docs/03-database/33-Service-Write-Schema-v0.1.sql（评审产物，非自动迁移）
ALTER TABLE service_item
    MODIFY COLUMN status VARCHAR(32) NOT NULL
        COMMENT 'DRAFT/REVIEWING/ACTIVE/OFFLINE/REJECTED',
    ADD COLUMN list_price DECIMAL(18,2) NULL
        COMMENT '划线价，可空，须>=price' AFTER price,
    ADD COLUMN cover_asset_id BIGINT NULL
        COMMENT '封面图素材ID（资产注册表引用）' AFTER fulfillment_type,
    ADD COLUMN applicable_pet_types VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '适用宠物类型，逗号分隔 DOG/CAT/EXOTIC/ALL' AFTER cover_asset_id,
    ADD COLUMN staff_requirement VARCHAR(200) NULL
        COMMENT '服务人员要求 0-200 字' AFTER applicable_pet_types,
    ADD COLUMN verification_required TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '是否需要核销，V1 默认需要' AFTER staff_requirement,
    ADD COLUMN aftersale_note VARCHAR(500) NULL COMMENT '售后说明' AFTER verification_required,
    ADD COLUMN remark VARCHAR(500) NULL COMMENT '备注' AFTER aftersale_note,
    ADD COLUMN submitted_at DATETIME(3) NULL
        COMMENT '最近一次提交审核时间（审核SLA 24h 起算）' AFTER remark,
    ADD CONSTRAINT chk_service_item_list_price
        CHECK (list_price IS NULL OR list_price >= price),
    ADD CONSTRAINT chk_service_item_price CHECK (price > 0),
    ADD CONSTRAINT chk_service_item_duration CHECK (duration_minutes > 0);

CREATE TABLE service_review_decision (
    id                     BIGINT NOT NULL,
    service_id             BIGINT NOT NULL,
    submission_no          INT UNSIGNED NOT NULL,
    decision_type          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    opinion                VARCHAR(500) NULL,
    decided_by_operator_id BIGINT NOT NULL,
    decided_at             DATETIME(3) NOT NULL,
    authz_version          VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_version          VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id             VARBINARY(512) NOT NULL,
    trace_id               VARCHAR(128) NULL,
    created_at             DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_svc_decision_request (request_id),
    UNIQUE KEY uk_svc_decision_service_no (service_id, submission_no),
    KEY idx_svc_decision_service_time (service_id, decided_at, id),
    KEY idx_svc_decision_operator (decided_by_operator_id, decided_at),
    CONSTRAINT chk_svc_decision_ids CHECK (id > 0 AND service_id > 0 AND submission_no > 0),
    CONSTRAINT chk_svc_decision_type CHECK (decision_type IN ('APPROVE', 'REJECT')),
    CONSTRAINT chk_svc_decision_opinion CHECK (
        (decision_type = 'APPROVE' AND (opinion IS NULL OR CHAR_LENGTH(opinion) <= 500))
        OR (decision_type = 'REJECT' AND opinion IS NOT NULL
            AND CHAR_LENGTH(opinion) BETWEEN 10 AND 500)),
    CONSTRAINT chk_svc_decision_request CHECK (OCTET_LENGTH(request_id) BETWEEN 1 AND 512),
    CONSTRAINT chk_svc_decision_trace CHECK (trace_id IS NULL OR OCTET_LENGTH(trace_id) <= 128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE service_governance_action (
    id                     BIGINT NOT NULL,
    service_id             BIGINT NOT NULL,
    action_type            VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason                 VARCHAR(500) NOT NULL,
    acted_by_operator_id   BIGINT NOT NULL,
    acted_at               DATETIME(3) NOT NULL,
    authz_version          VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_version          VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id             VARBINARY(512) NOT NULL,
    trace_id               VARCHAR(128) NULL,
    created_at             DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_svc_governance_request (request_id),
    KEY idx_svc_governance_service_time (service_id, acted_at, id),
    CONSTRAINT chk_svc_governance_type CHECK (action_type = 'FORCE_OFFLINE'),
    CONSTRAINT chk_svc_governance_reason CHECK (CHAR_LENGTH(reason) BETWEEN 10 AND 500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

`service_item` 另需 `submission_no INT UNSIGNED NOT NULL DEFAULT 0`（重提计数与决定表对齐）——并入 ALTER 清单（上文为紧凑起见单独列出此处补充）。存量数据兼容：既有行 status∈{DRAFT,ACTIVE,OFFLINE} 均合法，新列均可空/有默认，无回填需求。

## 5. HTTP 操作契约（获批后同步 10 号 §4.10 明细化 + §5 新增）

通用：统一 SUCCESS 信封；ID String；金额两位小数 String；`Cache-Control: no-store`；写请求 X-Request-Id（UUID，缺失/畸形 400）；首次创建 201、更新/命令/幂等重放 200（23 号 §6）；错误映射 SVCW-D8。

### 5.1 商家端（MINIAPP Bearer；目标 merchantId/storeId 必填，本人主账号归属校验）

| 方法/路径 | 请求 | 成功 data | 关键错误 |
|---|---|---|---|
| GET `/api/v1/merchant/services` | query merchantId、storeId、status?、page/pageSize（1..100/默认20） | 本店服务分页（全状态，M-002 工作台） | 400/401/404（无归属防枚举） |
| GET `/api/v1/merchant/service-categories` | 无参数 | ENABLED 类目（id/name/sortNo） | 401 |
| POST `/api/v1/merchant/services` | body §2 业务字段（草稿宽松）；X-Request-Id | 201 `{serviceId,status:DRAFT,version}` | 400/401/404/409（不可经营）/409 异参重放 |
| GET `/api/v1/merchant/services/{serviceId}` | query merchantId、storeId | 详情（含状态/驳回原因/latestDecision） | 404（非本店同响应） |
| PUT `/api/v1/merchant/services/{serviceId}` | body 业务字段 + expectedVersion；X-Request-Id | `{serviceId,status,version}` | 409 SERVICE_STATE_NOT_ALLOWED（ACTIVE/REVIEWING）/409 版本冲突 COMMON_CONFLICT |
| POST `/api/v1/merchant/services/{serviceId}/online` | body expectedVersion；X-Request-Id | `{serviceId,status:REVIEWING,version}`（提交审核/重新提交） | 409（非 DRAFT/REJECTED/OFFLINE 或必填不齐→400） |
| POST `/api/v1/merchant/services/{serviceId}/offline` | body expectedVersion；X-Request-Id | `{serviceId,status:OFFLINE,version}` | 409（非 ACTIVE） |

### 5.2 运营端（真实 admin 会话 + 动作码；`pet.*.enabled` 开关失败关闭）

| 方法/路径 | 动作码 | 请求 | 成功 data | 关键错误 |
|---|---|---|---|---|
| GET `/api/v1/admin/services` | service.review.read | query status?/categoryId?/merchantId?/page/pageSize | 审核分页（含 submittedAt、SLA 剩余、重提次数） | 400/401/403 |
| GET `/api/v1/admin/services/{serviceId}` | service.review.read | — | 详情+历史驳回记录 | 404 |
| POST `/api/v1/admin/services/{serviceId}/decision` | service.review.decide | body decisionType/opinion?/expectedVersion；X-Request-Id | `{serviceId,status,version,decisionId}` | 400 SERVICE_REVIEW_REASON_REQUIRED（REJECT 缺意见）/409（非 REVIEWING） |
| POST `/api/v1/admin/services/{serviceId}/force-offline` | service.forceOffline | body reason/expectedVersion；X-Request-Id | `{serviceId,status:OFFLINE,version,actionId}` | 400（缺/短 reason）/409（非 ACTIVE） |

## 6. Schema / Event / OpenAPI 影响汇总

- **Schema**：06 号 service_item 注释与列扩展 + 新 33 号文档两表（SVCW-D3）——重大变更，需人工批准后由 Owner 执行。
- **Event**：无（SVCW-D9/D10；通知张力登记待裁决）。
- **内部契约**：07 号 §5 增补 ServiceCommandApi 命令族与审核查询（获批后同步）；27 号无改动（getFacts 原样消费）。
- **HTTP**：10 号 §4.10 六路由细化 + `/merchant/service-categories` + §5 新增 admin services 四条（获批后同步）。
- **OpenAPI 11**：新操作以 `ACCEPTED_CONTRACT_NOT_IMPLEMENTED` 起步收录（MER-001/SVC-001 先例），operationId 建议 `merchantListServices/merchantCreateService/merchantGetService/merchantUpdateService/merchantSubmitServiceOnline/merchantTakeServiceOffline/merchantListServiceCategories/adminListServices/adminGetService/adminDecideServiceReview/adminForceOfflineService`。
- **12 号**：拟新增两码（SVCW-D8）。

## 7. 测试映射（详见 TEST-PLAN.md）

| 验收（提案） | 对应 |
|---|---|
| W2-SVCW-001 状态机全路径 | §3 转移表逐边 + 非法转移 409 |
| W2-SVCW-002 幂等重放 | §2/§5：同 requestId 同参重放回执、异参 409、撤权后不泄露旧回执 |
| W2-SVCW-003 权限反例矩阵 | SCOPE-VERIFY A7 P1-P10 |
| W2-SVCW-004 读侧不破坏 | 写入后 C 端四条件可见性回归（复用 ServiceQueryHttpTest 场景） |
| W2-SVCW-005 审核与 SLA | 决定/驳回/重提/历史保留/强制下架审计 |
| ARCH001~005 | biz 不依赖 biz、不跨模块 Repository（getFacts 经 API 模块） |

## 8. 承接登记与风险

| 事项 | 承接 | 登记位置 |
|---|---|---|
| 售罄态（库存事实源） | 未决（SVCW-D9），需产品裁决 | 本提案 + SCOPE-VERIFY §9 Q8 |
| 硬删除 | 未决（订单域引用查询+并发协调，对照 27 号 §6.1 先例） | 同上 Q/A4 |
| 审核结果站内通知/事件/Outbox | 待裁决（SVCW-D10；依赖 MERCHANT 收件箱） | Q4 |
| 批量通过低风险服务 | 后续切片 | SVCW-D6 |
| 运营类目 CRUD | ADM-001 其余范围后续切片 | SVCW-D3 |
| 封面公开展示（C 端读契约增补） | C 端读切片扩展时另走增补 | SVCW-D4 |
| M-002 工作台服务管理页 / A-002 运营审核页 | 前端切片消费本契约 | READY_QUEUE 既有行 |

风险与如实披露：
- 本提案为 DRAFT，未获批前不实现；获批后 Schema/HTTP/OpenAPI/12 号同步属权威变更，需按流程由 Owner 执行。
- 完整"商家发布→审核→C 端可见→预约"E2E 仍依赖排期/订单域，不在本切片验收内（延续 SVC-D4 披露口径）。
- 运营动作码需 AUTH/RBAC Owner 登记后 admin 端点才可鉴权（跨文件依赖）。
- 封面资产链路（SVCW-D4 推荐项）需 MER 域 Owner 评审 31 号契约增补，若被否需回到备选并重审 PRD 必填冲突。
