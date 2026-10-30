# CCR-SVC-CAT-001 服务分类字典与分类精确筛选

- 状态：APPROVED（2026-10-30 用户确认；实施按任务清单推进）
- 关联：C 端 PRD 5.1.13；运营端 PRD 6.2.4/6.11.2；商家端 PRD 5.5；SSOT §24；
  Figma `97:7825`（热门服务）、`110:480`（服务 tab）；
  [71 号营销契约](../../docs/04-api/71-Marketing-Center-Contract-v0.1.md)、
  [71 号热门服务补丁草案](../../docs/04-api/71-Marketing-Center-Contract-v0.1-hot-service-patch-draft.md)、
  [CCR-MKT-003](CCR-MKT-003.md)、
  [74 号服务目录页契约](../../docs/04-api/74-Service-Catalog-Page-Contract-v0.1.md)
- 用户已确认口径（2026-10-30）：
  1. 热门服务卡片的最小单元是**服务分类**；
  2. 热门分类由**服务字典自动生成**（启用 + 有可用商家 + 城市匹配，按字典排序），不做运营精选；
  3. 服务 tab 全量对齐 `110:480`；
  4. 距离不做；资质标签不做；
  5. 分类图标采用**方案 A**：复用既有后台素材上传，分类表单填写 HTTPS URL；
     不新增分类图标上传管线/接口。

## 1. 背景与问题

PRD 已明确服务分类是平台统一字典，但当前实现仍是前端硬编码 + 模糊关键词：

- `service_category` 只有 `id/category_name/status/sort_no`
  （`docs/03-database/06-核心数据库Schema-v0.1.sql:146-154`），没有稳定分类编号、图标、描述、父级。
- C 端没有分类字典读接口；服务 tab 的 12 个分类来自 `frontend-miniapp/src/consumer/pages/services/index.tsx`
  的硬编码数组，商家列表页的 11 个分类来自
  `frontend-miniapp/src/consumer/store/model.ts:196` 的 `directoryCategories`。
- 分类点击传的是 `keyword=分类名`（`stores.tsx:147-153`、`home-sections.tsx:29-32`），
  门店列表的 keyword 只匹配 `storeName/merchantName`（`store/repository.ts:40-45`），
  **并不会按服务分类过滤商家**。
- 运营端没有服务分类字典维护入口；营销活动 `serviceCategoryCodes` 只做词法校验
  （`MarketingAdminService.java:1024-1026`），不与服务字典校验。
- 结果：分类停用/无供给时仍展示；分类与商家服务、活动、热门页无法可靠关联。

本 CCR 只补齐 PRD 已明确的字典与精确筛选，不新增产品规则。

## 2. 范围

### 2.1 本期做

1. `service_category` 扩字段：`category_code`、`parent_id`、`icon_url`、`description`。
2. 运营端分类字典 CRUD + 启停 + 审计 + requestId 幂等。
3. C 端分类读接口：只返回启用且有可用商家的分类，按 `sort_no, id` 排序。
4. 商家列表 / 服务列表支持 `categoryCode` 精确筛选，替代分类名 keyword。
5. 营销活动 `serviceCategoryCodes` 接入分类字典校验（新建/编辑时；存量数据先清理）。
6. 商家端 `/merchant/service-categories` 投影补 `categoryCode/iconUrl`，兼容既有 `categoryId`。

### 2.2 本期不做

- 分类父子层级管理界面；`parent_id` 仅预留，V1 仍按平铺分类展示。
- 分类图标上传管线；采用方案 A，复用既有后台素材上传，分类表单填写 HTTPS URL；
  不新增 multipart 上传接口。
- 券适用范围字段；券模板仍按 `serviceCategoryCodes` 引用分类编号，由券域维护。
- 热门分类精选、竞价、排序算法；按用户裁决由字典自动生成。
- 商家自建分类、分类改名历史快照回刷。

## 3. 数据模型（DDL 草案）

### 3.1 `service_category` 扩字段

```sql
ALTER TABLE service_category
    ADD COLUMN category_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER id,
    ADD COLUMN parent_id     BIGINT NULL AFTER category_code,
    ADD COLUMN icon_url      VARCHAR(512) NULL AFTER sort_no,
    ADD COLUMN description   VARCHAR(200) NULL AFTER icon_url;

-- 按下表回填 11 个既有分类。
UPDATE service_category SET category_code = 'PET_GROOMING'         WHERE category_name = '宠物美容';
UPDATE service_category SET category_code = 'DOG_WALKING'          WHERE category_name = '遛狗陪护';
UPDATE service_category SET category_code = 'PET_BOARDING'         WHERE category_name = '宠物寄养';
UPDATE service_category SET category_code = 'PET_TRAINING'         WHERE category_name = '宠物训练';
UPDATE service_category SET category_code = 'HOME_FEEDING'         WHERE category_name = '上门喂养';
UPDATE service_category SET category_code = 'VET_ASSISTANT'        WHERE category_name = '兽医助理';
UPDATE service_category SET category_code = 'SMALL_PET_BOARDING'   WHERE category_name = '小宠寄养';
UPDATE service_category SET category_code = 'EXOTIC_HOME_FEEDING'  WHERE category_name = '异宠上门喂养';
UPDATE service_category SET category_code = 'EXOTIC_HEALTH_CHECK'  WHERE category_name = '异宠健康检查';
UPDATE service_category SET category_code = 'PLANT_CARE'           WHERE category_name = '绿植养护';
UPDATE service_category SET category_code = 'PLANT_BOARDING'       WHERE category_name = '植物代养';

ALTER TABLE service_category
    MODIFY category_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD UNIQUE KEY uk_service_category_code (category_code),
    ADD KEY idx_service_category_parent (parent_id);
```

约束：

| 字段 | 类型 | 约束 |
|---|---|---|
| category_code | String | 2..32，`^[A-Z][A-Z0-9_]{1,31}$`，全局唯一，**创建后不可改** |
| category_name | String | 1..20 码点（PRD 6.11.2），唯一（既有约束保留） |
| parent_id | BIGINT/null | 预留；V1 不建父子 UI，不参与 C 端展示 |
| icon_url | String/null | HTTPS ≤512；本地 E2E 可显式 allow-http |
| description | String/null | ≤200 码点；热门服务卡片文案 |
| sort_no | int | 0..9999；C 端排序第一键 |
| status | enum | `ENABLED` / `DISABLED` |

### 3.2 分类审计表

```sql
CREATE TABLE service_category_audit (
    id             BIGINT NOT NULL,
    category_id    BIGINT NOT NULL,
    category_code  VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_type    VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status    VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_status      VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    operator_id    BIGINT NOT NULL,
    request_id     VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace_id       VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    detail_json    JSON NULL,
    created_at     DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_service_category_audit_request (request_id),
    KEY idx_service_category_audit_row (category_id, created_at, id),
    CONSTRAINT chk_service_category_audit_action CHECK (
        action_type IN ('CREATE','UPDATE','ENABLE','DISABLE')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='服务分类字典写动作 append-only 审计';
```

说明：`detail_json` 只记录字段名/前后值摘要，不记录凭据；`request_id` 唯一保证幂等。

## 4. 分类可用性规则

C 端分类列表只返回同时满足以下条件的分类：

1. `service_category.status = 'ENABLED'`；
2. 该分类下至少有一个 **C 端可见门店**，且该门店至少有一个 `service_item.status = 'ACTIVE'` 的服务；
3. 若请求带 `cityCode`，门店 `city_code` 必须匹配；不带则统计全部开放城市；
4. 停用分类下即使有历史服务/订单也不返回；历史服务与订单快照保留原分类文本。

“C 端可见门店”沿用既有门店可见性三条件合取（审核通过、未冻结、当前可营业），
不在本 CCR 内重新定义。

排序：`sort_no ASC, id ASC`。无供给的分类不返回；整页无分类时前端展示空态，不伪造数据。

## 5. 接口草案

完整字段、示例与错误码见
[74 号服务目录页契约](../../docs/04-api/74-Service-Catalog-Page-Contract-v0.1.md)。

### 5.1 C 端

| 方法/路径 | 鉴权 | 参数 | 返回 |
|---|---|---|---|
| GET `/api/v1/c/service-categories` | 匿名/可选 Bearer | `cityCode?`（`[a-z][a-z0-9_-]{0,31}`） | `{items:[{categoryCode,categoryName,parentCode,iconUrl,description,sortNo,storeCount,serviceCount}]}` |
| GET `/api/v1/c/stores` | 匿名/可选 Bearer | 新增 `categoryCode?`；与 `city/keyword/promoted/page/pageSize` 组合 | 既有门店分页，响应字段不变 |
| GET `/api/v1/c/services` | 匿名/可选 Bearer | 新增 `categoryCode?`；`keyword` 与 `categoryCode` 至少一个 | 既有服务分页，响应字段不变 |

筛选语义：

- `categoryCode` 精确匹配服务分类，不再用分类名 keyword；
- 门店列表返回“至少有一个该分类 ACTIVE 服务”的 C 端可见门店；
- 服务列表返回该分类的 ACTIVE 且可见服务；
- `keyword` 与 `categoryCode` 同时存在时按 AND 组合；
- 非法/未知/停用 `categoryCode` → 400；合法但无供给 → 200 + 空分页。

### 5.2 运营端

| 方法/路径 | 动作码 | 请求 | 返回 |
|---|---|---|---|
| GET `/api/v1/admin/service-categories` | `service.category.read` | `status?/keyword?/page/pageSize` | 分页分类（含 version/status/createdAt/updatedAt） |
| POST `/api/v1/admin/service-categories` | `service.category.manage` | `categoryCode/categoryName/parentCode?/iconUrl?/description?/sortNo` + `X-Request-Id` | `{categoryId,categoryCode,status,version}` |
| PUT `/api/v1/admin/service-categories/{categoryCode}` | `service.category.manage` | `categoryName?/parentCode?/iconUrl?/description?/sortNo?/expectedVersion` + `X-Request-Id` | `{categoryId,categoryCode,status,version}` |
| POST `/api/v1/admin/service-categories/{categoryCode}/enable` | `service.category.manage` | `{expectedVersion}` + `X-Request-Id` | `{categoryCode,status,version}` |
| POST `/api/v1/admin/service-categories/{categoryCode}/disable` | `service.category.manage` | `{expectedVersion}` + `X-Request-Id` | `{categoryCode,status,version}` |

规则：

- `categoryCode` 创建后不可改；改名/改图标/改排序/启停均版本递增。
- 停用不删除数据；停用后商家不可新选，C 端不展示；历史服务/订单保留分类快照文本。
- 写操作 requestId 幂等：同参重放返回首回执，异参 409；审计 append-only。
- 权限：新增动作码 `service.category.read` / `service.category.manage`，纳入 ADMIN_WEB
  动作码注册表；V1 单运营，无双人复核（SSOT §24）。

## 6. 迁移与兼容

1. **回填顺序**：先加可空列 → 按 `category_name` 回填 11 个 code → 校验无 NULL/重复 →
   `MODIFY NOT NULL` + 唯一键。若生产存在 11 类之外的分类，必须人工补 code 后再收紧。
2. **服务数据**：`service_item.category_id` 不变，商户服务归属不需要迁移。
3. **商家端**：`GET /merchant/service-categories` 增加 `categoryCode/iconUrl` 字段；
   既有 `categoryId/categoryName/sortNo` 保留，商家编辑页可继续用 `categoryId`。
4. **营销活动**：`marketing_campaign.service_category_codes` 为空数组的保持“全部”；
   非空存量先跑 SQL 列出 distinct code，与字典比对：
   - 能按名称/既有约定映射的，人工修正；
   - 无法映射的，运营修正后再开启严格校验。
   新建/编辑活动时，`serviceCategoryCodes` 必须是字典中存在的分类（停用分类不允许新选）。
   marketing 侧经 service-api 只读查询校验，不直连 `service_category` 表；运营后台活动表单
   建议改为从分类接口取数的多选，避免手输 code。详见
   [71 号热门服务补丁草案](../../docs/04-api/71-Marketing-Center-Contract-v0.1-hot-service-patch-draft.md) §5。
5. **前端**：删除 `services/index.tsx`、`store/model.ts` 的硬编码分类数组，改为读 C 分类接口；
   分类点击传 `categoryCode`。
6. **设计登记表**：更新 `97:7825`（热门服务页）与 `110:480`（服务 tab）的实现关系，
   并登记“图稿首页式头部/tab 栏不照搬、使用 `SubpageHeader`”的偏差。

## 7. 不做

- 不做分类父子层级运营界面；`parent_id` 仅预留。
- 不做分类图标上传管线；采用方案 A：复用既有后台素材上传，分类表单填写 HTTPS URL。
- 不做券适用范围字段；券模板按分类编号关联由券域负责。
- 不做热门分类精选/竞价/销量排序；分类集合完全由字典 + 商家供给自动生成。
- 不改变订单、支付、退款、核销、售后状态机；不新增订单类型。

## 8. 验收要点

- C 分类接口只返回启用且有可用商家的分类，顺序稳定；停用/无供给分类消失。
- 服务 tab、热门服务页、商家列表三处分类同源，数量/名称/顺序一致。
- 分类点击后商家列表确实按 `categoryCode` 过滤；关键词与分类可组合；未知分类 400。
- 商家端分类下拉只显示启用分类，且带 `categoryCode`。
- 运营端新建/改名/改图标/改排序/启停后，C 端无需发版即可见；停用后 C 端消失。
- 分类图标通过既有后台素材上传后填 HTTPS URL；无图标时前端用占位；不新增分类上传接口。
- 写操作 requestId 重放返回同一回执；审计记录完整；版本冲突 409。
- 现有商家服务编辑、服务审核、门店目录、订单流程不受影响。
- 后端实现时通过 `backend/tools/check-persistence-style.py`；SQL 仅在本模块 Mapper XML。

## 9. 风险与待确认

1. **存量分类 code 回填**：生产若存在 11 类之外的自定义分类，需要人工补 code；未补齐前不能加 `NOT NULL/UNIQUE`。
2. **营销活动存量 code（已确认处理方式）**：当前 `serviceCategoryCodes` 只做词法校验，
   可能存在与字典不一致的 code；严格校验开启前由运营在营销中心逐条修正，
   开发只提供只读报表，不直接改生产数据。严格校验放在
   `pet.marketing.campaign.category-validation.enabled`（默认 false）后，存量清理完成再开启；
   未清理前编辑旧活动会 400 `SERVICE_CATEGORY_INVALID`。
3. **分类图标来源（已确认方案 A，2026-10-30）**：复用既有后台素材上传，分类表单填写
   HTTPS URL；不新增分类图标上传接口。风险：运营需知道素材上传入口；URL 失效/无图标时
   前端用占位图；素材权限/归属沿用既有后台素材规则。
4. **分类可用性查询性能**：11 个分类的可用门店/服务计数需要一次分组查询；实现时需命中
   `service_item(category_id,status)` 与门店可见性索引，避免逐分类 N+1。
5. **动作码注册**：`service.category.read/manage` 需同步加入权限清单与相关测试的预期列表。
