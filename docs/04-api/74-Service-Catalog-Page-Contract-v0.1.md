# 74-服务目录页契约 v0.1

状态：**ACCEPTED_CONTRACT / 未实现**（2026-10-30 用户确认）。最终以实现 PR 的 CI 为准。
本契约定义接口与字段；实现状态未实现，Schema/迁移见 CCR-SVC-CAT-001/CCR-SVC-CARD-001。

来源：C 端 PRD 5.1.13/5.1.14/5.1.27；运营端 PRD 6.2.4/6.11.2；商家端 PRD 5.5；
SSOT §24；Figma `97:7825`（热门服务）、`110:480`（服务 tab）；
[CCR-SVC-CAT-001](../../planning/ccr/CCR-SVC-CAT-001.md)、
[CCR-SVC-CARD-001](../../planning/ccr/CCR-SVC-CARD-001.md)、
[71 号营销活动中心契约 v0.3](71-Marketing-Center-Contract-v0.1.md)、
70 号商家统计契约、56 号评价契约。

版本记录：

| 版本 | 日期 | 变更 |
|---|---|---|
| v0.1 | 2026-10-30 | 初版：服务分类字典、分类筛选、优质商家聚合读、营销投影增量 |

## 1. 范围与开关

本契约覆盖：

1. 服务分类字典的 C 端读、运营端读写、分类精确筛选；
2. 服务 tab「优质商家」聚合读卡片；
3. 服务 tab / 热门服务页所需的营销投影增量（商家钜惠图片、首页内容位热门服务落地）。

开关（沿用服务域既有装配，不新增独立开关）：

| 开关 | 作用 |
|---|---|
| `pet.service.query.enabled` | C 端分类/服务读侧；关闭时路由不注册 |
| `pet.service.command.enabled` | 运营端分类字典写侧与既有服务审核 |
| review/order 域既有装配开关 | 评分/销量事实面；未装配时按 §6 降级 |

C 端读匿名可用；携带非法 Bearer 仍 401；运营端使用 ADMIN_WEB Bearer + 动作码。

## 2. 分类字典对象

### 2.1 `service_category` 字段

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| categoryId | String | Snowflake 十进制 | 既有主键 |
| categoryCode | String | 2..32，`^[A-Z][A-Z0-9_]{1,31}$`，唯一 | 稳定契约标识，创建后不可改 |
| categoryName | String | 1..20 码点，唯一 | C 端展示名 |
| parentCode | String/null | 必须指向已存在分类 | V1 预留，平铺展示 |
| iconUrl | String/null | HTTPS ≤512；本地 E2E 可显式 allow-http；由运营通过既有后台素材上传后填写 | 分类图标 |
| description | String/null | ≤200 码点 | 热门服务卡片文案 |
| sortNo | int | 0..9999 | C 端排序第一键 |
| status | enum | `ENABLED` / `DISABLED` | 停用后 C 端不展示、商家不可新选 |
| version | int | ≥0 | 乐观锁 |
| createdAt/updatedAt | ISO-8601 | UTC 毫秒 | 事实字段 |

初始分类与编号：

| categoryName | categoryCode | sortNo |
|---|---|---|
| 宠物美容 | `PET_GROOMING` | 10 |
| 遛狗陪护 | `DOG_WALKING` | 20 |
| 宠物寄养 | `PET_BOARDING` | 30 |
| 宠物训练 | `PET_TRAINING` | 40 |
| 上门喂养 | `HOME_FEEDING` | 50 |
| 兽医助理 | `VET_ASSISTANT` | 60 |
| 小宠寄养 | `SMALL_PET_BOARDING` | 70 |
| 异宠上门喂养 | `EXOTIC_HOME_FEEDING` | 80 |
| 异宠健康检查 | `EXOTIC_HEALTH_CHECK` | 90 |
| 绿植养护 | `PLANT_CARE` | 100 |
| 植物代养 | `PLANT_BOARDING` | 110 |

DDL 见 [CCR-SVC-CAT-001 §3](../../planning/ccr/CCR-SVC-CAT-001.md)。

## 3. C 端接口

### 3.1 分类列表

`GET /api/v1/c/service-categories?cityCode=`

- 鉴权：匿名/可选 Bearer；`cityCode` 可选，`[a-z][a-z0-9_-]{0,31}`；未知参数 400。
- 只返回启用 + 有可用商家的分类；按 `sortNo ASC, categoryId ASC`。
- 可用商家：C 端可见门店且至少有一个 `ACTIVE` 服务；带 `cityCode` 时门店城市匹配。
- data：

```json
{
  "items": [
    {
      "categoryCode": "PET_GROOMING",
      "categoryName": "宠物美容",
      "parentCode": null,
      "iconUrl": "https://cdn.example/service-category/grooming.png",
      "description": "专业美容师为您的爱宠提供洗澡、剪毛、造型等全方位护理服务",
      "sortNo": 10,
      "storeCount": 12,
      "serviceCount": 37
    }
  ]
}
```

字段约束：

| 字段 | 类型 | 约束 |
|---|---|---|
| categoryCode | String | 字典编号 |
| categoryName | String | ≤20 码点 |
| parentCode | String/null | V1 通常为 null |
| iconUrl | String/null | HTTPS ≤512；可为 null，前端用占位图 |
| description | String/null | ≤200 码点 |
| sortNo | int | 0..9999 |
| storeCount | int | ≥0；该分类下 C 端可见且至少一个 ACTIVE 服务的门店数 |
| serviceCount | int | ≥0；该分类下 C 端可见的 ACTIVE 服务数 |

- `Cache-Control: no-store`。
- 400：`cityCode` 非法；503：读取失败（前端整块隐藏）。

### 3.2 门店列表增加分类筛选

`GET /api/v1/c/stores?city=&keyword=&categoryCode=&promoted=&page=&pageSize=`

- 在既有 HTTP10 §3.3.2 门店目录上新增可选 `categoryCode`。
- `categoryCode` 精确匹配；返回“至少有一个该分类 ACTIVE 服务”的 C 端可见门店。
- 与 `city/keyword/promoted` 按 AND 组合；响应字段不变（仍为既有 9 字段 + promotion）。
- 未知/停用 `categoryCode` → 400 `INVALID_ARGUMENT`；合法但无供给 → 200 + 空分页。
- 既有 `keyword` 仍匹配 `storeName/merchantName`；服务名搜索仍走 §3.3。

### 3.3 服务搜索增加分类筛选

`GET /api/v1/c/services?keyword=&categoryCode=&page=&pageSize=`

- 由“`keyword` 必填”改为“`keyword` 与 `categoryCode` 至少一个必填；同时存在时 AND”。
- `categoryCode` 精确匹配服务分类；只返回 C 端可见的 ACTIVE 服务。
- 响应字段不变（既有服务分页投影）。
- 未知/停用 `categoryCode` → 400；无供给 → 200 + 空分页。
- 仅传 `categoryCode` 时，按 `serviceId ASC` 稳定分页；实现需保证排序稳定。

### 3.4 优质商家聚合卡片

`GET /api/v1/c/stores/featured?cityCode=&categoryCode=&page=&pageSize=`

- 鉴权：匿名/可选 Bearer。
- `page` 1..10000，默认 1；`pageSize` 1..20，默认 10；未知参数 400。
- `cityCode` 可选；`categoryCode` 可选。本接口使用 `cityCode`（与营销读一致）；
  既有 `/c/stores` 的 `city` 参数保持兼容，不在本接口复用 `city` 名称。
- 资格：C 端可见门店 + 至少一个 `ACTIVE` 服务；带 `categoryCode` 时该分类下至少一个 `ACTIVE` 服务。
- 排序（已确认 2026-10-30）：`rating.score DESC NULLS LAST, monthlySold DESC NULLS LAST, storeId ASC`。
- data：

```json
{
  "items": [
    {
      "storeId": "710302",
      "merchantId": "710301",
      "storeName": "萌宠之家宠物店",
      "merchantName": "萌宠之家",
      "cityCode": "310100",
      "address": "上海市浦东新区世纪大道 100 号",
      "avatarUrl": "https://cdn.example/store/710302/avatar.jpg",
      "avatarUrlExpiresAt": "2026-11-30T12:00:00Z",
      "intro": "一站式宠物生活馆，提供专业美容、家庭寄养、进口零食与玩具。",
      "categoryCode": "PET_GROOMING",
      "categoryName": "宠物美容",
      "rating": { "score": "4.9", "count": 328, "available": true },
      "monthlySold": 328,
      "tags": ["宠物美容"],
      "services": [
        {
          "serviceId": "710401",
          "serviceName": "专业美容·小型犬",
          "categoryCode": "PET_GROOMING",
          "categoryName": "宠物美容",
          "salePrice": "80.00",
          "listPrice": "100.00",
          "durationMinutes": 60,
          "fulfillmentType": "IN_STORE",
          "soldCount": 328
        },
        {
          "serviceId": "710402",
          "serviceName": "洗护SPA套餐",
          "categoryCode": "PET_GROOMING",
          "categoryName": "宠物美容",
          "salePrice": "128.00",
          "listPrice": null,
          "durationMinutes": 90,
          "fulfillmentType": "IN_STORE",
          "soldCount": 156
        }
      ],
      "promotion": {
        "source": "MERCHANT_PROMOTION",
        "offerTag": "5折",
        "offerTitle": "全场美容套餐5折起",
        "offerCopy": "专业美容师持证上岗",
        "campaignId": null
      }
    }
  ],
  "page": 1,
  "pageSize": 10,
  "total": 23
}
```

字段约束：

| 字段 | 类型 | 约束 |
|---|---|---|
| storeId/merchantId | String | Snowflake 十进制 |
| storeName/merchantName | String | ≤128 |
| cityCode | String | 开放城市编号 |
| address | String | ≤255 |
| avatarUrl | String/null | ≤512；私有素材签名 URL（已确认）；无头像返回 null |
| avatarUrlExpiresAt | ISO-8601/null | 与 avatarUrl 同时为 null 或同时有值 |
| intro | String/null | ≤500 码点 |
| categoryCode/categoryName | String | 主分类；真实字典值 |
| rating | object | 见 §6.1 |
| monthlySold | int/null | ≥0；null=聚合不可用 |
| tags | String[] | 0..3；V1 最多 1 个真实分类名；不含资质标签 |
| services | array | 0..2；按历史已售降序、serviceId 升序 |
| promotion | object/null | 见 §6.3 |
| distance | — | **V1 不返回** |

服务项目字段：

| 字段 | 类型 | 约束 |
|---|---|---|
| serviceId | String | Snowflake 十进制 |
| serviceName | String | ≤50 |
| categoryCode/categoryName | String | 服务自身分类 |
| salePrice | String | `DECIMAL(18,2)` 字符串，两位小数 |
| listPrice | String/null | 同上；null=未设置 |
| durationMinutes | int | 1..10080 |
| fulfillmentType | enum | `IN_STORE` / `PICKUP_DELIVERY` |
| soldCount | int/null | ≥0；null=聚合不可用 |

- 整体读取失败 → 503；单类事实不可用按 §6 降级，不整页失败。
- `Cache-Control: no-store`。
- 不返回手机号、用户 ID、订单 ID、经纬度。

### 3.5 商家钜惠投影增量（契约 71 §3.4）

`GET /api/v1/c/marketing/merchant-deals?cityCode=&limit=`

在既有 `StoreCard` 上增加可选字段：

| 字段 | 类型 | 约束 |
|---|---|---|
| imageUrl | String/null | ≤512；门店头像/照片优先，活动封面兜底 |
| imageUrlExpiresAt | ISO-8601/null | 与 imageUrl 同时为 null 或同时有值 |

其余字段、排序、去重规则不变；无图时前端用设计系统占位，不虚构商家。

### 3.6 首页内容位热门服务落地（契约 71 §10 v0.3 更新 / CCR-MKT-003）

`marketing_home_block.target_type` 白名单增加 `HOT_SERVICE`：

- `targetType=HOT_SERVICE` 时点击进入热门服务二级页；
- `targetValue` 可为 null（或不参与跳转），DDL 需同步放宽
  `target_value IS NOT NULL` 的 CHECK，允许该类型为空；
- 其余内容位字段、模板、排序、窗口不变。

### 3.7 热门服务页顶部投放位 `HOT_SERVICE_TOP`（契约 71 增量）

在契约 71 的投放位枚举新增 `HOT_SERVICE_TOP`，用于热门服务二级页顶部 banner。

- 运营端复用既有投放位 CRUD：`POST/PUT /api/v1/admin/marketing/placements`，
  `positionCode=HOT_SERVICE_TOP`；字段沿用
  `campaignId/cityCode/titleOverride/subtitleOverride/imageUrlOverride/templateCode?/
  sortWeight/startAt/endAt/status`。
- 校验：活动必须 `SCHEDULED/ONLINE`、窗口相交、落地页有效；**全平台最多 1 条启用**
  （已确认 2026-10-30；`cityCode` 只决定这条 banner 的展示范围；如需轮播，另开契约增量并在此登记）。
- C 端：`GET /api/v1/c/marketing/placements?positionCode=HOT_SERVICE_TOP&cityCode=`
  返回 `items`；热门服务页取 `sortWeight ASC, placementId ASC` 第一条；
  无启用投放位则隐藏 banner。
- 展示字段：`title/subtitle/tagName/coverUrl` 与 `jumpType/jumpParams`；
  点击按既有投放位跳转规则（活动/门店/服务/分类/白名单路径）。
- `templateCode` 可空；`HOT_SERVICE_TOP` 使用固定 banner 版式，不新增模板代码。
- 失败：503 时整块隐藏；不阻塞分类卡。
- 契约 71 v0.3 §2.3/§3.1/§4.2 已同步；详见
  [71 号营销活动中心契约 v0.3](71-Marketing-Center-Contract-v0.1.md) §2.3/§3.1/§4.2。

## 4. 运营端接口

统一 ADMIN_WEB Bearer、`Cache-Control: no-store`、信封、分页、`X-Request-Id` 幂等、
`expectedVersion` 乐观锁；未知 query 参数 400；资源不存在 404；状态/版本冲突 409；
依赖不可用 503。

| 方法/路径 | 动作码 | 请求 | 成功 data |
|---|---|---|---|
| GET `/api/v1/admin/service-categories` | `service.category.read` | `status?/keyword?/page/pageSize` | 分页分类（含 categoryId/version/status/createdAt/updatedAt） |
| POST `/api/v1/admin/service-categories` | `service.category.manage` | `categoryCode/categoryName/parentCode?/iconUrl?/description?/sortNo` + `X-Request-Id` | `{categoryId,categoryCode,status,version}` |
| PUT `/api/v1/admin/service-categories/{categoryCode}` | `service.category.manage` | `categoryName?/parentCode?/iconUrl?/description?/sortNo?/expectedVersion` + `X-Request-Id` | `{categoryId,categoryCode,status,version}` |
| POST `/api/v1/admin/service-categories/{categoryCode}/enable` | `service.category.manage` | `{expectedVersion}` + `X-Request-Id` | `{categoryCode,status,version}` |
| POST `/api/v1/admin/service-categories/{categoryCode}/disable` | `service.category.manage` | `{expectedVersion}` + `X-Request-Id` | `{categoryCode,status,version}` |

规则：

- `categoryCode` 创建后不可修改；改名/图标/描述/排序/启停均 `version+1`。
- 分类图标采用方案 A：`iconUrl` 由运营在既有后台素材上传后填写 HTTPS URL；
  本接口不接收 multipart 文件，不新增分类图标上传管线。
- 停用不删除；停用后商家不可新选，C 端不展示；历史服务/订单保留分类文本。
- 写操作同 requestId 同参重放返回首回执；异参 409；审计 append-only
  （`service_category_audit`）。
- V1 单运营，无双人复核（SSOT §24）；动作码需加入 ADMIN_WEB 权限注册表。
- 当分类下有服务/历史引用时，删除不提供；只允许停用。

## 5. 字段与示例

### 5.1 运营端分类对象

```json
{
  "categoryId": "910001",
  "categoryCode": "PET_GROOMING",
  "categoryName": "宠物美容",
  "parentCode": null,
  "iconUrl": "https://cdn.example/service-category/grooming.png",
  "description": "专业美容师为您的爱宠提供洗澡、剪毛、造型等全方位护理服务",
  "sortNo": 10,
  "status": "ENABLED",
  "version": 1,
  "createdAt": "2026-10-30T02:00:00Z",
  "updatedAt": "2026-10-30T02:00:00Z"
}
```

### 5.2 创建请求

```json
{
  "categoryCode": "PET_GROOMING",
  "categoryName": "宠物美容",
  "parentCode": null,
  "iconUrl": "https://cdn.example/service-category/grooming.png",
  "description": "专业美容师为您的爱宠提供洗澡、剪毛、造型等全方位护理服务",
  "sortNo": 10
}
```

### 5.3 更新请求

```json
{
  "categoryName": "宠物美容",
  "iconUrl": "https://cdn.example/service-category/grooming-v2.png",
  "description": "专业美容师为您的爱宠提供洗澡、剪毛、造型等全方位护理服务",
  "sortNo": 10,
  "expectedVersion": 1
}
```

### 5.4 评分对象

| 场景 | 返回 |
|---|---|
| 正常，评价数 ≥5 | `{"score":"4.9","count":328,"available":true}` |
| 评价数 <5 | `{"score":null,"count":3,"available":true}`（前端显示“暂无评分”） |
| 聚合不可用/评价内核未装配 | `{"score":null,"count":0,"available":false}`（前端隐藏评分） |

### 5.5 促销对象

```json
{
  "source": "MERCHANT_PROMOTION",
  "offerTag": "5折",
  "offerTitle": "全场美容套餐5折起",
  "offerCopy": "专业美容师持证上岗",
  "campaignId": null
}
```

- `source`：`MERCHANT_PROMOTION` / `CAMPAIGN`；同店去重时活动优先（沿用契约 71）。
- `新客` 字样只有真实活动/促销文案存在时才展示；不得由前端合成。

## 6. 统计口径与降级

### 6.1 评分

- 门店 `review.score_included=1` 的 `composite_score` 全量均值，0.0..5.0 一位小数。
- `reviewCount < 5` → `score=null`（PRD 5.1.14）。
- `available=false` 仅表示聚合读取失败/评价内核未装配；不得用 0 分冒充。
- 评分聚合不返回评价内容、用户信息。

### 6.2 月售与服务已售

- **月售**：当前自然月（Asia/Shanghai）内“有效订单”按门店计数。
- **服务已售**：历史全部“有效订单”按 `service_id` 计数（PRD 5.1.13 不区分时间）。
- **有效订单（已确认，2026-10-30）**：`paid_at IS NOT NULL` 且未取消、`refunded_amount = 0`；
  **部分退款与全额退款均不计入**；**不要求已完成/已核销，已支付即可**。
- **月售归属时间**：固定 `paid_at`；自然月固定 Asia/Shanghai（UTC+8），存储 UTC。
- 聚合不可用：`monthlySold=null`、`soldCount=null`；不使用假数据。

### 6.3 促销

- 来源：`merchant_store_profile.promo_level/promo_title/promo_text` 或当前门店参与的
  `ONLINE` 活动。
- 活动来源优先；同店去重；无促销返回 `null`。
- 角标/标题/文案长度沿用契约 71（≤32/≤100/≤100）。

### 6.4 主分类与标签

- 主分类 = 该店已售最高服务的 `categoryName`；无已售时取该店 ACTIVE 服务按字典顺序第一个。
- `tags` 只返回真实分类名，V1 最多 1 个；不返回“持证上岗/5年经验”等无数据标签。
- 无 ACTIVE 服务的门店不进入列表，因此不存在空分类卡。

### 6.5 服务项目

- 取该店 ACTIVE 服务，按 `soldCount DESC, serviceId ASC` 取前 2（已确认 2026-10-30）；
  不足 2 个返回实际数量。
- 价格/划线价/时长/履约方式来自服务快照；已售来自订单聚合。
- 点击服务项目进服务详情；点击门店进商家详情。

## 7. 通用规则

- 信封 `{code,message,data,traceId}`；ID 十进制 String；时间 ISO-8601 UTC 毫秒；
  金额 `DECIMAL(18,2)` 字符串；分页 `page` 1..10000、`pageSize` 1..50（本页卡片接口 1..20）。
- C 端读匿名可浏览；携带非法 Bearer 仍 401；运营端 ADMIN_WEB + 动作码。
- `Cache-Control: no-store`；失败关闭，不伪造数据。
- 未知 query 参数 400；未知/停用分类 400；资源不存在 404；依赖不可用 503。
- 不返回手机号、用户 ID、订单 ID、经纬度等敏感/可枚举字段。

## 8. 错误码

| 错误码 | HTTP | 场景 |
|---|---|---|
| `INVALID_ARGUMENT` | 400 | 参数词法/组合非法；未知/停用分类；`keyword` 与 `categoryCode` 同时缺失 |
| `UNAUTHORIZED` | 401 | C 端携带非法 Bearer；运营端未登录 |
| `FORBIDDEN` | 403 | 运营端动作码缺失 |
| `CATEGORY_NOT_FOUND` | 404 | 运营端按 code 找不到分类 |
| `CATEGORY_CODE_CONFLICT` | 409 | 创建时 code 重复 |
| `CATEGORY_VERSION_CONFLICT` | 409 | 乐观锁版本失配 |
| `DEPENDENCY_UNAVAILABLE` | 503 | 分类/门店/评分/订单读侧不可用 |

## 9. 迁移与兼容

1. `service_category` 先加可空列，回填 11 个 code，再收紧 NOT NULL + UNIQUE。
2. `service_item.category_id` 不变；商家服务归属不迁移。
3. `/merchant/service-categories` 增加 `categoryCode/iconUrl`，保留 `categoryId`。
4. `/c/stores` 响应不变，仅新增可选 `categoryCode` 查询参数；旧前端不受影响。
5. `/c/services` 旧调用仍传 `keyword`；新调用可只传 `categoryCode`。
6. 营销活动 `serviceCategoryCodes` 严格校验前，先清理存量未知 code。
7. 前端删除硬编码分类数组；分类点击改传 `categoryCode`。
8. 设计登记表更新 `97:7825` / `110:480` / `stores` 的关系与偏差。
9. 投放位枚举新增 `HOT_SERVICE_TOP`；`marketing_placement.position_code` 的 CHECK/白名单
   与契约 71 同步更新。
10. `marketing_home_block.target_type` 放宽 `target_value IS NOT NULL` 的 CHECK，允许
    `HOT_SERVICE` 为空。

## 10. 不做/设计偏差

1. 距离不展示（V1 无定位能力）；`distance` 不进入 V1 响应。
2. 资质标签不做；只展示真实分类标签。
3. `97:7825` 的首页式头部 + 底部 tab 栏不照搬；二级页统一 `SubpageHeader`。
4. 不做分类父子层级 UI；不做分类图标上传管线（方案 A：复用既有后台素材上传 + HTTPS URL）。
5. 不做优质商家排序控件、商家自选热门、竞价/结算、评价内容展示。
6. 不改变订单/支付/退款/核销/售后状态机与 `DisplayOrderStatus`。

## 11. 验收要点

- 分类接口只返回启用且有可用商家的分类；顺序稳定；停用/无供给消失。
- 分类点击后门店列表按 `categoryCode` 精确过滤；关键词可组合；未知分类 400。
- 优质商家卡显示真实门店/服务/评分/月售/已售；评分 <5 显示“暂无评分”；
  不出现距离和资质标签；每家店最多 2 个服务项目。
- 商家钜惠横排有真实图片；无图用占位图。
- 月售/服务已售不含部分退款与全额退款；月售按 `paid_at` 归属自然月（Asia/Shanghai）。
- 热门服务页分类自动生成；banner 来自 `HOT_SERVICE_TOP` 投放位；无启用投放位隐藏；
  “全部 >”切服务 tab。
- 首页热门服务卡落热门服务页，不再直接落 `stores`。
- 运营端分类 CRUD/启停幂等、审计、版本冲突正确；商家端分类下拉只显示启用分类。
- 分类图标通过既有后台素材上传后填 HTTPS URL；无图标时前端占位；不新增分类上传接口。
- 一页 10~20 家门店无 N+1 查询；评分/销量聚合命中门店前导索引。
- `check-persistence-style.py` 通过；SQL 仅在本模块 Mapper XML。

## 12. 已确认事项

本契约的待确认项已收口（2026-10-30）：

1. 分类图标采用**方案 A**：复用既有后台素材上传，分类表单填写 HTTPS URL；
   不新增上传管线/接口。
2. `HOT_SERVICE_TOP` **全平台最多 1 条启用**（2026-10-30 确认；与 Figma 单 banner 一致）；
   `cityCode` 只决定这条 banner 的展示范围。后续如需轮播/多条，另开契约增量。
3. 存量活动 `serviceCategoryCodes` 清理：运营在营销中心逐条修正，开发只提供只读报表；
   严格校验由 `pet.marketing.campaign.category-validation.enabled` 控制（默认 false），
   存量清理完成后再开启（清理说明见
   [71 号热门服务补丁草案](71-Marketing-Center-Contract-v0.1-hot-service-patch-draft.md) §5.4/§11，
   该补丁已合入 71 v0.3）。
