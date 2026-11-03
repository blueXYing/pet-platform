# CCR-SVC-CARD-001 服务页商家卡聚合读与营销投影

- 状态：APPROVED（2026-10-30 用户确认；实施按任务清单推进）
- 关联：C 端 PRD 5.1.13（商家服务页）/5.1.14（商家详情）/5.1.27（评价）；
  70 号商家统计契约、56 号评价申诉契约、71 号营销契约；
  Figma `110:480`（服务 tab）、`97:7825`（热门服务）；
  [CCR-SVC-CAT-001](CCR-SVC-CAT-001.md)、
  [71 号热门服务补丁草案](../../docs/04-api/71-Marketing-Center-Contract-v0.1-hot-service-patch-draft.md)、
  [74 号服务目录页契约](../../docs/04-api/74-Service-Catalog-Page-Contract-v0.1.md)
- 用户已确认口径（2026-10-30）：
  1. 服务 tab 全量对齐 `110:480`（分类 + 商家钜惠 + 优质商家）；
  2. 热门服务页分类由服务字典自动生成，不做运营精选；
  3. **距离本期不做**（V1 无定位能力）；
  4. **资质标签不做**（“持证上岗/5年经验”等无数据标签不展示，只展示真实分类标签）；
  5. 评分全量、月售自然月、服务已售历史；
  6. **部分退款不计入**月售/服务已售；月售按 `paid_at` 归属；自然月固定 Asia/Shanghai；
  7. 门店头像使用**签名 URL**；
  8. 热门服务页顶部 banner **新增 `HOT_SERVICE_TOP` 投放位**；全平台最多 1 条启用。

## 1. 背景与问题

`110:480` 的“优质商家”卡片包含：商家头像/名称/分类标签/评分/月售/距离/资质标签/简介/
2 个服务项目（名称/价格/已售）/新客促销。当前契约与实现只有很小的子集：

- `StoreView` 只有 9 个冻结字段（`frontend-miniapp/src/consumer/store/model.ts:13-22`），
  没有头像、简介、评分、月售、距离、标签。
- `ServiceItemView` 没有已售数量（`frontend-miniapp/src/consumer/service/model.ts:14-19`）。
- 服务 tab 目前只有 12 个硬编码分类 + 商家钜惠横排 + “全部门店目录”入口卡
  （`frontend-miniapp/src/consumer/pages/services/index.tsx`），没有优质商家列表。
- 商家列表页 `stores.tsx:18-21` 已登记：评分/月售/距离/热门服务/促销条无契约事实，
  分类筛选 STR-D7 未接线。
- 评价域已有 `ReviewQueryApi.getStoreScoreSummary`（70 号统计事实读，按窗口聚合），
  但它是内部 OWNER 口径；C 端公开评分需要新的只读事实面。
- 订单域已有商家统计 `MerchantStatsQueryApi`（70 号），但同样是商家鉴权口径；
  C 端公开月售/服务已售需要新的只读事实面。

本 CCR 定义这些聚合读与卡片字段；不新增订单/评价/营销写路径。

## 2. 范围

### 2.1 本期做

1. 新增 C 端“优质商家”聚合读接口，返回商家卡 + 2 个服务项目 + 评分/月售/服务已售/
   头像/简介/真实分类标签/促销。
2. 新增评价域公开只读事实面（门店评分聚合）与订单域公开只读事实面（门店月售/服务已售）。
3. 服务 tab 用该接口渲染“优质商家”列表；热门服务页用分类接口渲染分类卡。
4. 商家钜惠横排补门店图片字段（契约 71 增量）。
5. 首页热门服务内容位增加 `HOT_SERVICE` 落地类型，指向热门服务二级页（契约 71/CCR-MKT-003 增量）。
6. 热门服务页顶部新增 `HOT_SERVICE_TOP` 投放位（契约 71 增量），banner 由运营配置，
   全平台最多 1 条启用。
7. 设计偏差登记：距离不展示、资质标签不做、图稿首页式头部不照搬。

### 2.2 本期不做

- 距离计算/定位授权；`distance` 字段不进入 V1 响应（未来加定位时再做增量）。
- “持证上岗/5年经验”等资质标签；不新增商家标签维护/审核。
- 优质商家的排序控件（推荐/距离/评分/销量/价格）；V1 只按固定默认排序。
- 商家自选热门/竞价/结算；热门分类仍由字典 + 供给自动生成。
- 评价内容列表/评分算法调整；只读聚合，不改变 56 号评价规则。
- 服务项目“热门”运营标记；按历史已售取前 2。

## 3. 卡片字段与数据来源

完整字段、示例与错误码见
[74 号服务目录页契约](../../docs/04-api/74-Service-Catalog-Page-Contract-v0.1.md)。

| 卡片元素 | 数据来源 | V1 口径 | 现状 |
|---|---|---|---|
| storeId/storeName/merchantId/merchantName/cityCode/address | store 域 | 既有 C 端可见门店字段 | 已有 |
| avatarUrl/avatarUrlExpiresAt | `merchant_store_profile.avatar_asset_id` | 复用私有素材签名 URL；无头像返回 null | C 投影没有 |
| intro | `merchant_store_profile.intro` | 0..500 码点；无则 null | C 投影没有 |
| categoryCode/categoryName | service 域 | 该店已售最高服务的分类；无已售则取该店 ACTIVE 服务按字典排序第一个 | 新增 |
| tags | service 域 | 只返回真实分类名，V1 最多 1 个；不做资质标签 | 新增 |
| rating | review 域 | `score_included=1` 全量评价 `composite_score` 均值，一位小数；评价数 <5 → score=null；聚合不可用 → available=false | 内部有窗口聚合，C 无 |
| monthlySold | order 域 | 当前自然月有效订单数；不可用 → null | 仅商家统计，C 无 |
| services | service 域 + order 域 | 该店 ACTIVE 服务按历史已售降序取前 2；每项含名称/分类/售价/划线价/时长/履约方式/已售 | 新增 |
| promotion | `merchant_store_profile.promo_*` + marketing 活动 | 有真实商家促销/当前活动时返回；`新客` 字样只在活动文案确实存在时展示 | 门店详情已有单店投影，卡片无批量投影 |
| 距离 | — | **V1 不返回**；未来加定位时再增字段 | 不做 |
| 资质标签 | — | **V1 不返回**；只展示真实分类标签 | 不做 |

### 3.1 统计口径（写入契约时固定）

- **评分**：全量 `review.score_included = 1` 的 `composite_score` 均值，0.0..5.0 一位小数；
  `reviewCount < 5`（PRD 5.1.14“有效评价不足 5 条”）→ `score=null`，前端显示“暂无评分”。
- **月售**：当前自然月（Asia/Shanghai）内“有效订单”按门店计数。
- **服务已售**：历史全部“有效订单”按 `service_id` 计数（PRD 5.1.13 不区分时间）。
- **有效订单（已确认，2026-10-30）**：
  `paid_at IS NOT NULL` 且未取消、`refunded_amount = 0`；**部分退款与全额退款均不计入**；
  **不要求已完成/已核销，已支付即可**。
- **月售归属时间**：固定用 `paid_at`；自然月固定 Asia/Shanghai（UTC+8），存储 UTC。
- **评分时间窗**：全量；实现按门店聚合，不逐条返回评价内容。
- **服务前 2 选择**（已确认 2026-10-30）：`sold_count DESC, service_id ASC`；
  无已售时按 `service_id ASC`。
- **卡片排序**（已确认 2026-10-30）：
  `rating.score DESC NULLS LAST, monthlySold DESC NULLS LAST, storeId ASC`。

### 3.2 资格与过滤

- 门店必须 C 端可见（审核通过、未冻结、当前可营业），且至少有一个 `ACTIVE` 服务；
- `cityCode` 匹配；`categoryCode` 匹配时，门店必须至少有一个该分类的 `ACTIVE` 服务；
- 无符合门店 → 200 + 空列表，前端展示空态；
- 不返回手机号、用户 ID、订单 ID 等敏感/可枚举字段。

## 4. 聚合实现与架构边界

- 新增 C 读接口由 **boot 适配层组合**：store-api（可见门店）、service-api（分类/服务）、
  review-api（评分事实）、order-api（销量事实）、marketing-api（促销投影）。
- 遵守 AGENTS：`biz` 不直接依赖另一个 `biz`；不跨模块访问 Repository/Mapper/DO/Entity；
  不在本模块 Mapper XML 之外写 SQL；不在 Java 内联 SQL。
- **批量查询，禁止 N+1**：
  - 一页 10~20 家门店，按 storeIds 批量取门店、评分、月售、促销；
  - 按 storeIds 批量取 ACTIVE 服务（窗口函数每家取前 2，SQL 在 service 模块 Mapper XML）；
  - 按 serviceIds 批量取历史已售计数；
  - 评分按 storeIds 批量取全量聚合。
- 新内部只读事实面（建议）：
  - review-api：`ReviewPublicFactsApi.getStoreRatingSummary(storeIds, window)`；
  - order-api：`ServiceCatalogSalesFactsApi.storeMonthlySold(storeIds, month)` /
    `serviceSold(serviceIds)`。
  - 两端口只返回计数/均值，不返回明细，不开枚举面。
- 读取失败降级：
  - 评分聚合不可用 → `rating.available=false`，卡片仍渲染；
  - 订单聚合不可用 → `monthlySold=null`、`services[].soldCount=null`，卡片仍渲染；
  - 整体门店/服务读取失败 → 503，前端整块失败关闭/隐藏；
  - 不用假数据填充。

## 5. 营销投影增量

1. `GET /api/v1/c/marketing/merchant-deals` 的 `StoreCard` 增加：
   `imageUrl`、`imageUrlExpiresAt`（门店头像/照片优先，活动封面兜底；无图 null）。
   契约 71 §3.4 更新。
2. `GET /api/v1/c/marketing/home-blocks` 的 `targetType` 白名单增加 `HOT_SERVICE`：
   首页热门服务卡点击进入热门服务二级页。
   契约 71 §10（v0.3 更新）/CCR-MKT-003 更新。
3. 热门服务页顶部 banner 使用新增 `HOT_SERVICE_TOP` 投放位：
   - 契约 71 §2.3 投放位 `positionCode` 白名单增加 `HOT_SERVICE_TOP`；
   - C 端复用 `GET /api/v1/c/marketing/placements?positionCode=HOT_SERVICE_TOP&cityCode=`，
     取 `sortWeight ASC, placementId ASC` 第一条；无启用投放位则隐藏 banner；
   - 展示投放位的 `title/subtitle/tagName/coverUrl` 与 `jumpType/jumpParams`；
   - 运营复用既有投放位 CRUD，不新增后台接口；
   - **全平台最多 1 条启用**（已确认 2026-10-30，与 Figma 单 banner 一致）；
     `cityCode` 只决定这条 banner 的展示范围；如需轮播，另开契约增量。
   - 契约 71 §2.3/§3.1/§4.2 同步更新；详见
     [71 号热门服务补丁草案](../../docs/04-api/71-Marketing-Center-Contract-v0.1-hot-service-patch-draft.md) §2。

## 6. 不做/设计偏差

1. 距离不展示：`110:480` 的“1.2km”与 PRD 距离项登记为 V1 偏差；V1 只有手动城市选择。
2. 资质标签不做：只显示真实分类名；“持证上岗/5年经验”等不展示。
3. 图稿 `97:7825` 的首页式头部 + 底部 tab 栏不照搬；二级页统一 `SubpageHeader`。
4. 不做排序控件、商家自选热门、竞价、评价内容展示。
5. 不改变 `DisplayOrderStatus`、订单/退款/核销/售后状态机。

## 7. 验收要点

- 服务 tab 的优质商家卡显示真实门店/服务/评分/月售/已售；无评分时显示“暂无评分”；
  不出现距离和资质标签。
- 每家店最多 2 个服务项目，按历史已售降序；点击服务项目进服务详情，点击门店进商家详情。
- `categoryCode` 过滤后只返回该分类有 ACTIVE 服务的门店；无供给显示空态。
- 停用分类/无供给分类在分类接口中不返回；分类点击后商家列表确实刷新。
- 商家钜惠横排有真实图片；无图时用设计系统占位，不虚构商家。
- 月售/服务已售不含部分退款与全额退款；月售按 `paid_at` 归属自然月（Asia/Shanghai）。
- 热门服务页分类由字典自动生成，banner 来自 `HOT_SERVICE_TOP` 投放位；无启用投放位隐藏；
  “全部 >”切到服务 tab。
- 首页热门服务卡落热门服务页，不再直接落 `stores`。
- 性能：一页 10~20 家门店不出现按门店/服务的 N+1 查询；评分/销量聚合命中门店前导索引。
- 无 PII 泄漏；C 端读匿名可用，非法 Bearer 仍 401。

## 8. 风险与待确认

1. **有效订单聚合实现需与订单域核验**：口径已确认（`paid_at` 非空、未取消、
   `refunded_amount = 0`；部分/全额退款不计入），但订单状态字段、退款金额回写时机与索引
   需在实现时复核，避免月售/已售跳变。
2. **全量评分聚合成本**：评价量增长后需命中 `review(store_id, score_included, created_at)` 索引；
   必要时做读模型/短 TTL 缓存，但不改变 `<5 条` 口径。
3. **门店头像签名 URL（已确认）**：C 端返回签名 URL + 过期时间；需确认签名窗口/刷新策略；
   无头像时前端用占位图，不阻塞卡片。
4. **评价内核/订单内核未装配**：评分/销量字段按 `available=false`/null 降级，页面不失败；
   不能用 0 冒充“无数据”。
5. **批量读接口**：service/review/order/marketing 都需要批量方法；若暂时只有单店接口，
   必须先在各自 API 增加批量只读方法，不能在 boot 里循环调用。
6. **“新客”文案**：只有活动/商家促销真实提供时才展示；不得把普通促销统一标成“新客”。
7. **`HOT_SERVICE_TOP` 启用上限**：已确认全平台最多 1 条启用（与 Figma 单 banner 一致）；
   `cityCode` 只决定展示范围；后续如需轮播/多条排序，另开契约增量。
