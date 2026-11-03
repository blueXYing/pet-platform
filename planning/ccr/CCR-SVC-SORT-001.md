# CCR-SVC-SORT-001 服务门店列表排序（综合/评分/销量）

- 状态：**APPROVED**（2026-11-03 用户确认；按推荐口径实施：综合/评分/销量三键，距离不做）
- 用户已确认口径（2026-11-03）：
  1. **综合排序**沿用当前 `rating.score DESC, monthlySold DESC, storeId ASC`，不新增加权公式；
  2. 排序只覆盖默认优质商家视图；关键词搜索视图不承载排序；
  3. `preview=1` 隐藏排序条，不伪造预览排序；
  4. **距离最近**隐藏，不做禁用态/假按钮；
  5. 小程序未上线，不引入发布灰度/前端开关；后端与前端同批实施。
- 关联：C 端 PRD 5.1.13；Figma `690:6370`（服务-全部服务-分类）；
  74 号契约 §3.4/§10.5/§11；CCR-SVC-CARD-001 §2.2/§6；
  [CCR-SVC-CAT-001](CCR-SVC-CAT-001.md)；70 号商家统计契约、56 号评价契约
- 用户请求（2026-11-03）：按 Figma `690:6370` 的排序条实现
  **综合排序 / 评分最高 / 销量优先** 三个排序；**距离最近不做**。

## 1. 背景与问题

Figma `690:6370` 在分类门店列表上方有四个排序 pill：
综合排序、距离最近、评分最高、销量优先。

当前实现与契约：

- `74 §3.4` 的 `/c/stores/featured` 只有固定排序
  `rating.score DESC NULLS LAST, monthlySold DESC NULLS LAST, storeId ASC`；
- `74 §10.5` 与 `CCR-SVC-CARD-001 §2.2` 明确“**不做优质商家排序控件**”；
- `74 §10.1` 明确距离不展示（V1 无定位能力，`distance` 不进入响应）；
- 分类门店页默认视图已复用 `/c/stores/featured` 的优质商家卡，但没有排序参数，
  因此图上的排序条无法真实生效。

本 CCR 只补齐 **综合/评分/销量** 三个可真实执行的排序，不引入距离、不引入
运营竞价/加权综合分等未确认规则。

## 2. 范围

### 2.1 本期做

1. `GET /api/v1/c/stores/featured` 增加可选 `sort` 查询参数：
   `COMPREHENSIVE`（默认）/ `RATING` / `SALES`。
2. 三个排序的稳定全序与 NULL 处理（见 §3.2）。
3. 分类门店页默认视图渲染三个排序 pill；切换时从第 1 页重载，
   保留 `cityCode`/`categoryCode`；分页沿用当前 `sort`。
4. 后端 composer/controller、契约、设计登记、测试同步更新。
5. 兼容：省略 `sort` 时保持现状排序，旧客户端不受影响。

### 2.2 本期不做

- **距离最近**：V1 无定位/距离字段，不实现、不返回、不在 UI 中伪造可点按钮。
- `/c/stores` 门店目录与 `/c/services` 服务搜索的排序参数；本切片只覆盖
  `/c/stores/featured` 的默认优质商家视图。
- 加权综合分公式（如评分 60% + 月售 40%）；无产品确认前不引入。
- 用户排序偏好持久化、运营干预排序、竞价/推荐位、A/B 实验。
- 服务项目（每店 2 个）的排序变化；仍按 `sold_count DESC, service_id ASC`。

## 3. 契约补丁（74 §3.4）

### 3.1 查询参数

`GET /api/v1/c/stores/featured?sort=&cityCode=&categoryCode=&page=&pageSize=`

| 参数 | 类型 | 约束 | 默认 | 说明 |
|---|---|---|---|---|
| sort | String | enum，区分大小写 | `COMPREHENSIVE` | 排序键 |

- 允许值：`COMPREHENSIVE` / `RATING` / `SALES`。
- 未传 = `COMPREHENSIVE`，与现有排序完全一致。
- `sort` 为空串、未知值、大小写不匹配 → 400 `COMMON_INVALID_ARGUMENT`。
- 未知查询参数继续 400（沿用现有严格传输）。
- 响应字段、资格、`cityCode`/`categoryCode` 过滤、`Cache-Control: no-store`
  均不变；响应不新增 `sort` 回显字段。

### 3.2 排序定义

所有排序都必须有稳定全序，最后一键固定 `storeId ASC`，保证跨页不重不漏。

| sort | 排序键 | 说明 |
|---|---|---|
| `COMPREHENSIVE` | `rating.score DESC NULLS LAST, monthlySold DESC NULLS LAST, storeId ASC` | 沿用当前默认顺序，不新增加权公式 |
| `RATING` | `rating.score DESC NULLS LAST, storeId ASC` | 评分优先；月售不参与排序 |
| `SALES` | `monthlySold DESC NULLS LAST, storeId ASC` | 月售优先；评分不参与排序 |

口径说明：

- `rating.score` 为 `score_included=1` 全量评价均值，一位小数；评价数 `<5` 时为
  `null`（展示“暂无评分”），参与排序时按 NULL 处理并置后（`NULLS LAST`）。
- `monthlySold` 为当前自然月（Asia/Shanghai）有效订单数；聚合不可用时为 `null`，
  同样置后。
- `RATING` 与 `COMPREHENSIVE` 的差异仅在次键：前者用 `storeId` 稳定收口，
  后者在评分相同的情况下优先月售更高的门店。
- `SALES` 在月售相同的情况下用 `storeId` 稳定收口；不把评分作为次键，
  避免“销量优先”实际被评分主导。

### 3.3 降级语义

沿用 74 §6 的降级规则，不因排序改变失败边界：

- 评价聚合未装配/读取失败：所有 `rating.score=null`；`RATING` 退化为
  `storeId ASC`，`COMPREHENSIVE`/`SALES` 仍可用月售排序；卡片 `rating.available=false`。
- 订单聚合未装配/读取失败：所有 `monthlySold=null`；`SALES` 退化为
  `storeId ASC`，`COMPREHENSIVE`/`RATING` 仍可用评分排序；卡片 `monthlySold=null`。
- 门店/服务/分类读失败：整页失败关闭（503），不返回部分排序结果。
- 排序不改变“无评分不展示 0 分”“部分/全额退款不计入月售”等既有口径。

### 3.4 示例

```http
GET /api/v1/c/stores/featured?sort=RATING&cityCode=chengdu&categoryCode=PET_GROOMING&page=1&pageSize=10
```

```json
{
  "items": [
    {
      "storeId": "710302",
      "rating": { "score": "4.9", "count": 10, "available": true },
      "monthlySold": 328
    }
  ],
  "page": 1,
  "pageSize": 10,
  "total": 1
}
```

## 4. 后端实现要点

- `CFeaturedStoreController.featured` 增加 `@RequestParam(required = false) String sort`，
  解析为枚举；非法值走统一 `COMMON_INVALID_ARGUMENT`。
- `FeaturedStoreCardComposer.compose` 增加排序参数；`compareCandidates` 按 §3.2
  选择主键/次键，最后一律 `storeId ASC`。
- 排序仍在 composer 的候选集上完成（现有实现即按整集排序后再分页），
  **不新增 SQL/Mapper 排序**，不改变“一次批量读、无 N+1”的约束。
- 现有固定顺序的代码路径保留为 `COMPREHENSIVE` 分支，避免默认行为漂移。
- 测试：
  - `FeaturedStoreCardComposerTest`：三种排序、NULL 置后、同分/同销量按
    `storeId`、评分/销量降级、跨页稳定性。
  - `CFeaturedStoreController` HTTP 验收：默认顺序与现状一致；三种 `sort`；
    非法值 400；未知参数 400；`Cache-Control: no-store`。
  - 现有 `FeaturedStoreHttpTest` 回归：不传 `sort` 时结果不变。
- 本地 E2E fixture（`E2eCoreLoopServer`）补 `27-Merchant-Store-Profile-Schema` /
  `70-Service-Category-Dictionary-Schema` / `72-Service-Category-Seed`，并让 E2E 服务使用
  字典种子 `PET_GROOMING=910001`，避免重启后 featured 读侧因缺列/缺表 503。

## 5. C 端实现要点

- 分类门店页 `consumer/pages/store-services/stores` 默认视图（无关键词、非 preview）
  在页面顶部（替代“门店”标题行）渲染三个 pill：
  - 综合排序（默认选中）
  - 评分最高
  - 销量优先
- 2026-11-03 视觉验收进一步修正：本页按 Figma 不渲染城市选择器/搜索输入；城市沿用
  全局已选城市，关键词仅从路由深链带入；排序条与 `SubpageHeader`（返回+标题）直接相邻。
- 点击 pill：`sort` 状态更新 → 从第 1 页请求
  `/c/stores/featured?sort=...&cityCode=...&categoryCode=...`；
  城市/分类变化时沿用当前 `sort` 重载；“加载更多”携带同一 `sort`。
- **距离最近不渲染**：不显示不可点/假按钮；Figma 该 pill 登记为 V1 偏差。
- 关键词搜索态（`searching=true`）隐藏排序条：本切片不给 `/c/stores` 与
  `/c/services` 增加排序参数，避免出现“点了排序但结果不受影响”的假交互。
- `preview=1` 设计验收通道：当前分包本地 featured mock 不支持排序；
  建议先隐藏排序条，或另行补 preview 排序数据（见 §8 待确认）。
- 由于 DevTools 分包编译限制，主包服务 tab 与分包 `store-services` 各有一份
  featured 卡/hook；排序参数需要两处同步（见代码注释）。
- 返回页面复用已加载数据：服务 tab 与分类门店页的 `useDidShow` 只在城市变化或上一轮
  未 `ready` 时重读，避免返回时把 phase 重置为 loading 造成分类/优质商家短时空白；
  关键词搜索态返回门店页时同样保留搜索结果。

## 6. 文档与登记变更（确认后执行）

1. `docs/04-api/74-Service-Catalog-Page-Contract-v0.1.md`
   - §3.4 增加 `sort` 参数与 §3.2 排序表；
   - §10.5 从“不做排序控件”改为“综合/评分/销量三键做；距离不做”；
   - §11 验收增加三种排序、NULL 置后、跨页稳定、非法值 400；
   - 版本记录追加 v0.2（2026-11-03）。
2. `planning/ccr/CCR-SVC-CARD-001.md`
   - §2.2 的“排序控件不做”改为“综合/评分/销量三键经 CCR-SVC-SORT-001 增量”；
   - §6 设计偏差保留“距离不做”。
3. `docs/08-engineering/20-设计源登记表-figma-map.md`
   - `690:6370` 行：排序四键 → 三键实现、距离不实现。
4. `planning/progress/2026-10-30/service-catalog-page/IMPLEMENTATION-TASKS.md`
   - F-C-04/F-C-05 增加排序条与三种排序验收。

## 7. 兼容、发布与回退

- **无数据库/Schema/Event/Scheduler 变更**；无数据迁移。
- **无新增后端开关**：沿用 `pet.service.query.enabled`。
- **实施方式**：小程序未上线，后端与前端同批实施；不引入发布灰度/前端构建开关，
  不把旧后端兼容作为约束。
- 回退：小程序停止发送 `sort` 即回到现有默认顺序；后端保留
  `COMPREHENSIVE` 分支，删除参数解析即可完全回退。
- 默认行为不变：`sort` 省略或 `COMPREHENSIVE` 时，与当前线上排序逐项一致。

## 8. 已确认

1. **综合排序口径**：沿用当前 `rating.score DESC, monthlySold DESC, storeId ASC`，
   不新增加权公式；本次只增加“评分最高/销量优先”两个显式排序。
2. **排序范围**：仅默认优质商家视图；关键词搜索视图不承载排序。
3. **preview=1**：隐藏排序条，避免假交互。
4. **距离最近**：隐藏，不做禁用态/假按钮。
5. **实施方式**：小程序未上线，后端与前端同批实施，不做发布灰度。

## 9. 验收要点

- 不传 `sort` / `COMPREHENSIVE`：与现有顺序逐项一致。
- `RATING`：评分降序，评分相同按 `storeId ASC`；`<5` 条评价的门店排在后面。
- `SALES`：月售降序，月售相同按 `storeId ASC`；月售不可用的门店排在后面。
- 三种排序跨页无重复、无遗漏；`total` 不变。
- 非法 `sort`、空串、大小写错误 → 400 `COMMON_INVALID_ARGUMENT`。
- 评分/销量聚合降级时页面仍渲染，排序退化为稳定 `storeId` 顺序，不 503、不伪造 0。
- 小程序默认视图显示三个 pill，默认“综合排序”选中；切换后列表从第 1 页刷新；
  关键词搜索态不显示排序条；不出现“距离最近”。
- `check-persistence-style.py` 通过；无新增 SQL/JdbcTemplate/内联 SQL。
- 相关后端/前端测试通过；无未披露的 Contract/Schema/Event 变化。
