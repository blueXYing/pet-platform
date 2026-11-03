# 71 号营销活动中心契约补丁草案：热门服务投放、首页落地与商家钜惠图片

状态：**MERGED / 已合入 71 v0.3（2026-10-30）**。本文件保留为变更来源与实现核对记录；
[71 号营销活动中心契约 v0.3](71-Marketing-Center-Contract-v0.1.md) 为权威正文。
合入位置：§2.1/§2.3/§3.1/§3.4/§4.1/§4.2/§5.1/§6/§8/§10。

关联：

- [CCR-SVC-CAT-001](../../planning/ccr/CCR-SVC-CAT-001.md) 服务分类字典与分类筛选；
- [CCR-SVC-CARD-001](../../planning/ccr/CCR-SVC-CARD-001.md) 服务页商家卡聚合读；
- [74 号服务目录页契约](74-Service-Catalog-Page-Contract-v0.1.md)；
- C 端 PRD 5.1.13/5.1.14；运营端 PRD 6.11.5；
- Figma `97:7825`（热门服务）、`110:480`（服务 tab）。

## 1. 补丁总览

| 71 号章节 | 变更 | 类型 |
|---|---|---|
| §2.3 投放位 `marketing_placement` | `positionCode` 增加 `HOT_SERVICE_TOP`；全平台最多 1 条启用 | 枚举/规则 |
| §3.1 C 端活动位 | `positionCode` 允许 `HOT_SERVICE_TOP`；该位置最多返回 1 条 | 接口 |
| §3.4 商家钜惠横排 | 列表项增加 `imageUrl/imageUrlExpiresAt` | 字段 |
| §4.2 运营端投放位 | `HOT_SERVICE_TOP` 全平台最多 1 条启用；超限 409 | 规则 |
| §10 首页内容位 | `targetType` 增加 `HOT_SERVICE`；该类型允许 `targetValue=null` | 枚举/DDL |
| §2.1/§4.1/§5.1 活动分类 | 澄清 `serviceCategoryCodes` 使用服务字典 `categoryCode` 并校验 | 校验 |
| §6 错误码 | 新增 `SERVICE_CATEGORY_INVALID` 400；复用 `PLACEMENT_LIMIT_EXCEEDED` 409 | 错误码 |
| §3.2/§3.3/§3.5/§3.6/§8 | 不变 | — |

不新增表、不新增字段列；只改枚举/CHECK 与读投影。活动、投放位、首页内容位的写权限、
requestId 幂等、审计、乐观锁规则不变。

合并方式：按上表逐节替换/追加，未列出的章节保持 71 号原文不变。

## 2. 投放位新增 `HOT_SERVICE_TOP`

### 2.1 §2.3 对象字段变更

`positionCode` 枚举：

```
HOME_BANNER / HOME_ACTIVITY / STORE_LIST_TOP / HOT_SERVICE_TOP
```

新增规则：

1. `HOT_SERVICE_TOP` 用于热门服务二级页顶部 banner；该位置**全平台最多 1 条 `ENABLED`**
   （不分城市，不做全局桶/城市桶叠加）。
2. `cityCode` 只决定这条唯一 banner 的展示范围：`cityCode=null` 表示全部开放城市；
   具体 `cityCode` 表示仅该城市可见。因为全平台只有 1 条，其他城市在该投放生效期间无 banner。
3. C 端请求带 `cityCode` 时：若该唯一投放位 `cityCode` 为空或与请求一致，返回该条；否则返回 0 条。
   请求不带 `cityCode` 时：返回该唯一投放位（若已启用且在窗口内）。该位置最多返回 1 条。
4. `HOT_SERVICE_TOP` 的其余字段、状态机、窗口校验、排序键
   （`sortWeight ASC, placementId ASC`）与既有投放位一致。
5. `templateCode` 可空；`HOT_SERVICE_TOP` 使用前端固定 banner 版式，不新增模板代码。
6. 启用/编辑时沿用既有校验：活动 `SCHEDULED/ONLINE`、窗口相交、落地页有效、城市开放。

### 2.2 §3.1 C 端活动位变更

`GET /api/v1/c/marketing/placements?positionCode=HOT_SERVICE_TOP&cityCode=`

- `positionCode` 白名单增加 `HOT_SERVICE_TOP`。
- 过滤规则仍为：投放位 `ENABLED`、当前在投放窗口、活动 `ONLINE` 且在活动窗口、城市匹配；
  落地页失效不返回并告警。
- `HOT_SERVICE_TOP` 全平台最多 1 条启用；按 §2.1 的城市范围过滤后返回 **0 或 1 条**；
  其余位置仍按原规则返回。
- 响应字段不变：`placementId/positionCode/campaignId/title/subtitle/tagCode/tagName/coverUrl/
  templateCode/startAt/endAt/jumpType/jumpParams`。
- 失败：400 参数非法；503 读取失败（热门服务页隐藏 banner，不阻塞分类卡）。

### 2.3 §4.2 运营端投放位变更

- 创建/编辑/启用 `HOT_SERVICE_TOP` 时，校验**全平台**现有 `ENABLED` 条数；
  已有 1 条时再启用第二条 → 409 `PLACEMENT_LIMIT_EXCEEDED`。
- 计数校验与状态变更在同一事务内完成；并发启用时，后到者按 409 收口
  （实现可用 `SELECT ... FOR UPDATE` 或等价条件更新，SQL 仍写在本模块 Mapper XML）。
- 编辑不改变 `cityCode` 桶时，允许更新自身；改变桶时按新桶校验。
- 其余校验（活动状态、窗口、落地页、城市开放）不变。
- 复用既有 `marketing.placement.manage` 动作码；不新增权限。

### 2.4 DDL 变更

```sql
ALTER TABLE marketing_placement
    DROP CHECK chk_marketing_placement_position;

ALTER TABLE marketing_placement
    ADD CONSTRAINT chk_marketing_placement_position CHECK (
        position_code IN ('HOME_BANNER','HOME_ACTIVITY','STORE_LIST_TOP','HOT_SERVICE_TOP')
    );
```

`position_code` 列宽 `VARCHAR(24)` 足够容纳 `HOT_SERVICE_TOP`（15 字符）。
全平台唯一性由应用层在事务内校验（不建议加唯一索引：窗口/状态会变化，且软停用保留历史行）。

## 3. §3.4 商家钜惠增加图片字段

`GET /api/v1/c/marketing/merchant-deals?cityCode=&limit=10`

列表项增加可选字段：

| 字段 | 类型 | 约束 |
|---|---|---|
| imageUrl | String/null | HTTPS ≤512；门店头像/首张门店照片优先，活动封面兜底；无图 null |
| imageUrlExpiresAt | ISO-8601/null | `imageUrl` 为私有素材签名 URL 时必填；公开 CDN/活动素材 URL 可为 null |

规则：

- 来源优先级：
  1. `source=CAMPAIGN`：活动 `coverUrl` → 门店头像/首张照片；
  2. `source=MERCHANT_PROMOTION`：门店头像 → 首张门店照片。
- 门店头像/照片来自 `merchant_store_profile.avatar_asset_id/photo_asset_ids`，按既有私有素材
  签名 URL 管线返回；签名过期时间写入 `imageUrlExpiresAt`。
- 无图时返回 `null`，前端使用设计系统占位图，不虚构商家图片。
- 其余字段、来源合并、同店去重、排序、`limit`、失败语义不变。
- 不新增表/列；复用 `merchant_store_profile` 与活动/投放位既有字段。

## 4. §10 首页内容位增加 `HOT_SERVICE` 落地类型

### 4.1 对象/枚举变更

`marketing_home_block.target_type` 白名单增加 `HOT_SERVICE`：

```
SERVICE_DIRECTORY | SERVICE_CATEGORY | STORE | SERVICE | CAMPAIGN |
COMMUNITY | MINIAPP_PATH | NONE | HOT_SERVICE
```

规则：

- `targetType=HOT_SERVICE` 表示点击进入热门服务二级页；
- 该类型允许 `targetValue=null`（不参与跳转）；其他类型仍要求 `targetValue` 非空；
- `positionCode`、`templateCode` 不变：首页内容位仍是 `HOME_SERVICE` /
  `HOT_SERVICE_FEATURE`，它只作为首页入口卡；
- 热门服务页内的分类**不由内容位配置**，由服务字典自动生成（见 CCR-SVC-CAT-001）；
- 内容位的标题/文案/图片/排序/窗口/赞助字段规则不变；仍是运营手动配置。

### 4.2 C 端行为

`GET /api/v1/c/marketing/home-blocks?cityCode=` 响应字段不变；前端按 `targetType=HOT_SERVICE`
路由到热门服务二级页。内容位空/失败/城市不匹配时整块隐藏，不阻塞首页。

### 4.3 DDL 变更

```sql
ALTER TABLE marketing_home_block
    DROP CHECK chk_marketing_home_block_target;

ALTER TABLE marketing_home_block
    ADD CONSTRAINT chk_marketing_home_block_target CHECK (
        target_type IN ('SERVICE_DIRECTORY','SERVICE_CATEGORY','STORE','SERVICE','CAMPAIGN',
                        'COMMUNITY','MINIAPP_PATH','NONE','HOT_SERVICE')
        AND (target_type IN ('NONE','HOT_SERVICE') OR target_value IS NOT NULL)
    );
```

不改 `marketing_home_block` 其他列；不新增 home block position/template。

## 5. 联动：`serviceCategoryCodes` 使用服务字典校验

> 本节的字段本身已在 71 号 §2.1 声明“必须是服务字典启用分类”；当前实现只做词法校验，
> 本补丁把校验落点和错误码写清楚，依赖 CCR-SVC-CAT-001 的 C/内部分类读接口。

### 5.1 字段约束澄清（§2.1）

| 字段 | 类型 | 约束 |
|---|---|---|
| serviceCategoryCodes | String[] | 空数组=全部；否则每项 `^[A-Z][A-Z0-9_]{1,31}$`，且必须存在于服务分类字典并处于 `ENABLED` |

- 分类编号使用服务字典的 `categoryCode`，不使用分类名、内部 `categoryId` 或任意字符串。
- 停用分类不允许新选；已存在活动保留历史 code，编辑时按当前字典重新校验。

### 5.2 创建/编辑校验（§4.1）

- `POST/PUT /api/v1/admin/marketing/campaigns`：通过 service-api 只读查询校验
  `serviceCategoryCodes`；未知/停用 code → 400 `SERVICE_CATEGORY_INVALID`
  （`field=serviceCategoryCodes`，message 指出具体 code）。
- marketing 不直连 `service_category` 表；不在 marketing Mapper 里跨模块 SQL。
- 运营后台活动表单建议改为从分类接口取数的多选，避免手输 code；接口仍接受数组。

### 5.3 商家报名匹配（§5.1）

- 匹配口径不变：活动 `serviceCategoryCodes` 为空，或与本店任一 ACTIVE 服务分类 code 相交；
  无服务分类不阻止报名（运营审核时判断）。
- 仅把“分类”明确为字典 `categoryCode`，不改变既有报名/审核状态机。

### 5.4 存量迁移

- 空数组的存量活动不受影响（全部）。
- 非空存量先核对：`serviceCategoryCodes` 与字典 code 一致才可保留；不一致的由运营修正后
  再开启严格校验。严格校验开启前，编辑这类活动可能返回 `SERVICE_CATEGORY_INVALID`。
- 建议把严格校验放在开关 `pet.marketing.campaign.category-validation.enabled`（默认 false）后：
  关闭时保持现有词法校验，存量清理完成后再开启。

## 6. §6 错误码增量

| 错误码 | HTTP | 场景 |
|---|---|---|
| `SERVICE_CATEGORY_INVALID` | 400 | 活动创建/编辑时 `serviceCategoryCodes` 含未知/停用/词法非法 code |
| `PLACEMENT_LIMIT_EXCEEDED` | 409 | 复用既有码：`HOT_SERVICE_TOP` 全平台已有启用投放位时再启用第二条 |

`PLACEMENT_LIMIT_EXCEEDED` 的 message 建议包含 `positionCode`、现有启用投放位的
`placementId/cityCode`（null 显示 `GLOBAL`），便于运营定位。新增错误码需同步 12 号错误码登记表；
其余错误码不变。

## 7. 权限、幂等、审计

- 投放位/首页内容位：继续使用 `marketing.placement.read/manage`；不新增动作码。
- 活动分类校验：`marketing.campaign.manage`；不新增动作码。
- 所有写操作继续要求 `X-Request-Id`，同参重放返回首回执、异参 409；键空间不变。
- 投放位/首页内容位继续写既有 append-only 审计；`HOT_SERVICE_TOP` 的启用/停用/编辑
  与既有投放位一致。
- V1 单运营、无双人复核（SSOT §24）。

## 8. 兼容与迁移

1. 既有投放位：`HOME_BANNER/HOME_ACTIVITY/STORE_LIST_TOP` 行为不变；
   新增枚举值不影响存量行。
2. 既有首页内容位：所有 targetType 行为不变；仅新增 `HOT_SERVICE`。
3. `merchant-deals`：新增可选字段，严格解码前端需同步接受；旧前端忽略新字段不受影响。
4. 活动 `serviceCategoryCodes`：空数组不受影响；非空存量按 §5.4 清理。
5. DDL 只在营销迁移目录追加，不改既有表结构；生产按 opt-in 迁移执行。
6. 合并后 71 号契约版本升为 **v0.3**（2026-10-30 已确认；或在 §11 追加补丁记录），并同步：
   - `docs/08-engineering/20-设计源登记表-figma-map.md` 的 `97:7825/110:480` 实现关系；
   - 74 号服务目录页契约草案的投放位/图片字段引用；
   - 前端 `merchant-deals`、首页内容位、热门服务页路由的解码/跳转逻辑。

## 9. 验收要点

- 运营创建并启用 1 条 `HOT_SERVICE_TOP`，热门服务页在 `cityCode` 允许范围内显示该 banner；
  全平台再启用第二条 → 409 `PLACEMENT_LIMIT_EXCEEDED`。
- 该条 `cityCode=null` 时全部开放城市可见；`cityCode=某城市` 时仅该城市可见；
  其他城市无 banner；无启用投放位/窗口外时 banner 隐藏。
- 投放位暂停/过期/活动失效/落地页失效时，C 端立即隐藏 banner，不阻塞分类卡。
- 首页内容位 `targetType=HOT_SERVICE`、`targetValue=null` 可创建/启用；
  点击进入热门服务二级页；其他 targetType 仍要求 targetValue。
- 商家钜惠列表项有真实 `imageUrl`（门店头像/照片或活动封面）；无图返回 null 且前端占位。
- 活动创建/编辑：合法字典 code 通过；未知/停用/词法非法 code 返回 400
  `SERVICE_CATEGORY_INVALID`；空数组=全部。
- 既有 `HOME_BANNER` 最多 3 条、`HOME_ACTIVITY`/`STORE_LIST_TOP` 行为不变。
- 所有写操作 requestId 重放返回同一回执；审计记录完整；版本冲突 409。
- 实现后 `check-persistence-style.py` 通过；SQL 仅在营销模块 Mapper XML。

## 10. 不做

- 不做 `HOT_SERVICE_TOP` 轮播/多条排序；全平台最多 1 条。
- 不新增首页内容位 position/template；热门页分类不再由内容位配置。
- 不做商家自选热门、竞价、扣费、结算；`sponsor_*` 字段仍只预留。
- 不改变活动/投放位/券/订单/退款/核销/售后状态机。
- 不新增商家钜惠专属图片表；复用门店资料与活动封面。

## 11. 合入记录（2026-10-30）

本补丁已合入 71 v0.3；合入时确认：

1. `HOT_SERVICE_TOP` **全平台最多 1 条启用**；`cityCode` 只决定展示范围；
2. 71 号契约版本号 **v0.3**；
3. 存量活动 `serviceCategoryCodes` 清理方式：
   - 运营在营销中心逐条修正；开发只提供只读报表，不直接改生产数据；
   - 严格校验放在 `pet.marketing.campaign.category-validation.enabled`（默认 false）后；
   - 存量清理完成后再开启严格校验。

本文件不再作为权威契约，只保留变更来源；后续以 71 v0.3 正文为准。
