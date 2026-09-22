# CCR-W2-API-001 门店读侧契约提案（DRAFT v0.1）

> **状态：DRAFT v0.1 —— 未获批不得实现。** 本文件仅为草案，未获人工 Contract Owner 批准前，不得据此修改任何权威文档（07/10/11/12/27号）、不得写实现代码、不得建PR。决策点编号 STR-D1～STR-D8，每条附推荐方案。
> 规范版本：0.1（草案），日期：2026-09-22。提出方：角色B（MER-001 门店读侧切片）。基线：develop `52a1c45`；worktree `wt-mer-stores`，分支 `codex/mer001-store-read-20260922`。
> 关联：CCR-W2-API-001 索引"商家/门店"行 2026-09-22 登记；SVC-001 提案 §7 承接表；SVC-D1～D5（已批，尤其错误两分细化）。前置核验：[SCOPE-VERIFY.md](../../issues/wave-2/MER-001-store-read/SCOPE-VERIFY.md)。

## 修订记录

| 版本 | 变更 |
|---|---|
| 0.1（2026-09-22） | 初稿：STR-D1～D8；两条 C 端门店读路由草案；pet-merchant-api 第五内部查询草案；测试计划另行见 TEST-PLAN |

## 人工 CTO 一页阅读指南

**用通俗话说：小程序"门店列表"和"门店详情"两个页面还只有接口名字、没有内容定义。门店档案的权威字段早就批过（店名/地址/坐标/掩码电话），"能不能展示"的判断也有已批接口；但现有查门店的接口只有商家本人能查，消费者查不了，也没有"按城市翻页列出可展示门店"的查询，所以需要一个消费者视角的新内部查询。PRD 页面里的评分、月售、距离、收藏、评价、相册、促销、服务人员这些花哨字段，要么对应的功能压根没做（评价、收藏、订单统计），要么已被位置裁决取消（距离），本轮一律不做、不编数据。这个草案把能做的字段和规则写清楚，把做不了的和要你拍板的列成八个决定。**

| 分工 | 内容 |
|---|---|
| 我负责 | 逐路由字段/校验/错误/鉴权映射、第五内部查询契约、可见性与失败关闭规则、示例与反例、Schema/Event/OpenAPI 影响与依赖核验 |
| 你负责 | 审核 STR-D1～D8 八项建议决定（D6 是对 27号已批三+一查询的显式增补；D7 含延后登记） |
| 不变 | 产品硬规则与 SSOT §28 位置裁决原样执行；不提前实现排期/订单/支付；不实现无事实源字段 |

## 建议决定（需人工批准的八项）

1. **STR-D1 门店可见性 = 三条件合取、同事务判定、失败关闭**（推荐：批准）。C 端门店可见当且仅当 `merchantEnabled ∧ storeEnabled ∧ acceptsNewOrders`（即 27号 §4 的 `merchant.status==ACTIVE` ∧ `store.status==ACTIVE` ∧ 审核APPROVED+签约SIGNED；后三者复用 `MerchantOrderEligibilityPolicy` 同一资格策略）。与 SVC-D1b 服务可见性同构，但**不含"服务 ACTIVE"条件**——门店本身可见性与它是否有在售服务是两件事（有店无服务仍可见，空服务列表由已冻结路由返回空页）。可见 = 基本资格合格，不代表有空位、可下单。错误两分沿用 SVC-D1 细化（决定回执第6条）：**确认不存在或无资格 → 列表隐藏/详情 404；事实源故障、读取失败或状态未知 → 503 `COMMON_DEPENDENCY_UNAVAILABLE`；两者不得混同**。
2. **STR-D2 字段集裁剪**（推荐：批准）。本轮门店投影字段 = `storeId、merchantId、storeName、merchantName、address、longitude?、latitude?、phoneMasked?、cityCode`（九字段）。`merchantStatus/storeStatus/version` **不出现在 C 端响应**（可见性已蕴含资格合格，避免恒真字段——沿用服务域 0.2 补正撤下 bookability 的同一理由）。评分/月售/距离/排序权重/头像/相册/简介/公告/营业时间/促销/收藏/评价/服务人员全部延后，理由与逐字段清单见 SCOPE-VERIFY §2（无事实源或 SSOT §28 取消），**不虚构数据源、不返回占位假值**。
3. **STR-D3 列表 query 参数与城市语义**（推荐：批准）。`GET /c/stores` 仅接受 `city?`、`page?`、`pageSize?`（未知参数 400，`COMMON_INVALID_ARGUMENT`）。
   - `city` 语义：**城市范围由服务端控制**——取值必须属于 boot 侧开放城市目录（`MerchantApplicationCityCatalog`，现仅成都），目录在 boot 适配层校验：不在目录 → 400（城市字典是闭合集合，传未开放城市是参数错误）；**省略 `city` = 隐式限定为当前全部开放城市集合**（今天等价于成都），避免把"chengdu"硬编码进契约，未来增开城市不需改此路由；
   - 逐店城市事实来自 `merchant_profile_compat.city_code`（29号已批存储，APPROVE 同事务写入）；**通过资格合取但缺 compat 行的商家视为城市事实损坏 → 整页 503**（该商家按已批流程必有该行；沿用 ServiceQueryService "store service merchant binding is inconsistent" 的完整性反例先例），不得静默隐藏或归入任何城市；
   - 开放城市但暂无可见门店 → 200 正常空页（`items:[], total:0`），与故障 503 严格区分（失败关闭，见 STR-D1）。
   - biz 不依赖 boot：merchant-biz 第五查询以 `cityCodes` 集合入参承接城市范围，不自行判断何为"开放"。
4. **STR-D4 排序与分页**（推荐：批准）。**排序固定为 `merchantId 数值升序, storeId 数值升序`**（对齐 27号 §3 通用列表规则与 `selectOwnedStores` 既有先例；按 ID 数值序、非字符串词典序）。**明示不支持**"按距离/评分/销量/价格排序"——对应事实源不存在（距离被 SSOT §28 取消，评分/销量依赖未建域），接口不提供 `sort` 参数，不假装支持。分页形态对齐已冻结服务列表路由：`page` 1..10000 默认 1、`pageSize` 1..50 默认 20，信封 `items/page/pageSize/total`，多页间不承诺快照游标。
5. **STR-D5 详情 404 语义与已冻结服务路由的配合**（推荐：批准）。`GET /c/stores/{storeId}`：确认不存在、或三条件任一不满足 → **404 `STORE_NOT_FOUND`，不区分原因（防探测）**；事实源故障/未知 → 503。`STORE_NOT_FOUND` 已在 12号 §12 注册，**无需新增错误码**，仅需实现层在 `CServiceExceptionHandler` STATUS 表补 `STORE_NOT_FOUND→404` 映射。
   **披露（待人工对齐，不阻塞本提案）**：已合入的服务详情 404 实际返回 `COMMON_NOT_FOUND`（提案文本写的是 `SERVICE_NOT_FOUND`，见 SCOPE-VERIFY §6.2）。本提案推荐门店用 `STORE_NOT_FOUND`（语义精确、注册表已有）；服务路由是否回补 `SERVICE_NOT_FOUND` 由人工另行决定，两者不互为前提。
   **配合关系（与已冻结 `/c/stores/{storeId}/services`）**：该服务列表路由对不存在/不可见门店返回 **200 空列表**（已批防探测语义：不暴露门店状态细节，"门店存在但无服务"与"门店不可见"不可区分），门店详情路由对同一门店返回 **404**。两者不矛盾：不同资源各自的已批语义；C-003 页面应以详情路由为门店可访问性判据（PRD §5.1.14 异常边界"下线/冻结/删除 → 不可访问提示"），服务列表空页仅在该店内渲染"暂无服务"。列表项点进详情在两次调用间状态变化时按 404 处理。
6. **STR-D6 pet-merchant-api 第五内部查询（对 27号 §4 的显式增补）**（推荐：批准）。现有三查询均为所有者视角（`getStore` 走 `selectOwnedStore` owner 前件，消费者不可借用），第四查询只返回布尔不含档案字段，且无任何 C 端分页能力——**组合复用不成立，需第五查询**。按 27号风格草案：

   ```java
   public interface MerchantStoreDisplayApi {
       MerchantStoreDisplayPageDTO pageDisplayStores(MerchantStoreDisplayPageQuery query);
       MerchantStoreDisplayDTO getDisplayStore(MerchantStoreDisplayQuery query);
   }

   public record MerchantStoreDisplayPageQuery(
       java.util.List<String> cityCodes, int page, int pageSize, QueryContext context) {}

   public record MerchantStoreDisplayQuery(String storeId, QueryContext context) {}

   public record MerchantStoreDisplayDTO(
       String merchantId, String storeId, String merchantName, String storeName,
       String address, String longitude, String latitude,   // 可空，≤7位小数
       String phoneMasked,                                   // 可空
       String cityCode) {}

   public record MerchantStoreDisplayPageDTO(
       java.util.List<MerchantStoreDisplayDTO> items, int page, int pageSize, long total) {}
   ```

   语义约定：**仅供 C 端展示聚合消费，只读门店档案投影，不授予任何商家操作权限**；无所有者前提，`QueryContext` 仅承载链路信息不得冒充主体（同 SVC-D5）；与 4.1/4.2 同一资格策略、同一 repeatable-read 快照（`pageDisplayStores` 在 merchant-biz 自有读事务内完成 join `merchant`/`merchant_store`/`merchant_profile_compat` 与资格事实判定，**整页一次资格判定**——每行共享同一商家/门店对集合的资格策略求值，避免逐行重复读取；`getDisplayStore` 单店判定）。错误语义同 STR-D1 两分。实现归 pet-merchant-api/merchant-biz（MER 域文件，唯一 Writer 规则独立 commit 披露，SVC-D5 先例）；boot 适配层组合本查询与（未来的）服务域查询，biz 不依赖 biz。
7. **STR-D7 服务分类筛选与关键词搜索本轮延后**（推荐：批准延后；备选=本轮纳入，见下）。
   - 分类筛选（"该店存在 ACTIVE 服务命中类目"）：语义明确但**跨域过滤分页无诚实实现**——service_item 在服务域，merchant-biz 不可跨模块查询；若服务域先按类目分页再由 merchant 域过滤可见性，`total` 会把不可见门店计入（假分页）；反之亦然。诚实做法需要服务域新增"按类目返回门店ID全集"查询 + merchant 域按集合过滤分页（两域各加契约，V1 规模可行但无界），且与角色A（服务写入方）在 pet-service-* 同文件族并行改动，冲突面上升。**推荐本轮延后**：路由不接受 `categoryId`（未知参数 400），待服务写入方合入后另行走小额 CCR（届时 ADM 写入方在场，数据与写入路径可验证）。备选（若人工要求本轮纳入）：pet-service-api 增 `listStoreIdsWithActiveService(categoryId, context)`（无分页、DISTINCT store_id 数值升序），boot 将集合传给 `pageDisplayStores` 的增补参数 `storeIds?`（merchant 域 SQL IN 过滤后分页计数，total 诚实）；该备选同时扩大两域契约与冲突面，需在批准时明确选择。
   - 关键词搜索（店名/服务名）：PRD 要求"按相关性排序"，无相关性/权重事实源与定义；跨店名（merchant 域）+服务名（service 域）双域匹配同样需要新契约。**推荐本轮延后**并登记 C-003 后续需求，不提供 `keyword` 参数。
   - 两项延后均登记于 CCR 索引商家/门店行与 READY_QUEUE，不挂空、不隐式扩大。
8. **STR-D8 会话语义沿用 C 端登录态**（推荐：批准沿用；差异登记待裁决）。现状：全部已上线 C 端业务路由（含已冻结两条服务读路由）经 `CBearerSessionFilter` 强制 MINIAPP Bearer，未登录 401 `COMMON_UNAUTHORIZED`；`/c/stores`、`/c/stores/{storeId}` 当前未登记（落入 denyAll 403），本切片将其纳入同一模式（permitAll + protectedPath 两条登记）。PRD §5.1.13/14 写"所有用户浏览"——**与现行过滤器语义不一致，属产品规则差异，不由本切片自行变更**。推荐沿用登录态（与已批服务域一致、防探测面不扩大）；若人工裁决要求匿名可浏览，需另行批准匿名访问安全评审（防爬/防枚举/限流）后再改，本提案不为匿名预留半开实现。

## 1. 范围与来源（依赖核验结论）

| 来源 | 核验结果 |
|---|---|
| HTTP10 §3.3 | 门店两路由仅列名，无字段/分页/筛选/错误定义（本提案补齐）。**注：10号现无 §3.3.1**，服务路由详细形态实际在 11号两操作与 SVC 提案（SCOPE-VERIFY §6.1）；获批后权威同步时建议一并补写 10号 §3.3.1（服务）与门店小节 |
| 27号 §4 | MerchantStoreDTO 字段与三+一查询已批；getStore/checkOrderEligibility 为所有者视角；`acceptsNewOrders` 含 APPROVED+SIGNED 语义 |
| 07号 v0.6 §4.2 | SVC-D5 展示资格查询已批已实现（错误两分原文） |
| Schema06/29号 | `merchant_store` 无城市/简介/相册/营业时间列；城市事实在 `merchant_profile_compat.city_code`（已批存储，APPROVE 同事务写入） |
| SSOT §28 | 取消地理匹配/距离阈值/围栏与地图 Key；地址坐标仍保存（展示用途可暴露坐标，不做距离） |
| 错误码 12号 §12 | `STORE_NOT_FOUND` 已存在，无需新增 |
| OpenAPI 11号 | 门店两操作缺登记；获批后以 `ACCEPTED_CONTRACT_NOT_IMPLEMENTED` 起步（MER-001/SVC-001 先例），实现合并后保持该状态直至 E2E 具备 |
| 既有实现 | pet-merchant 读侧仅 owner 前件查询；pet-boot C 过滤器三处登记模式（Filter/Security/Advice）可直接复用 |

## 2. DTO 与字段校验

HTTP 投影（成功 200 `ApiResponse<...>`，信封与既有 C 端一致，`Cache-Control: no-store`）：

门店列表项 `StoreListItemView`（= STR-D2 九字段）：

```json
{
  "storeId": "957002", "merchantId": "957001",
  "storeName": "锦江区萌宠之家", "merchantName": "萌宠之家（成都）有限公司",
  "address": "四川省成都市锦江区春熙路XX号",
  "longitude": "104.0812345", "latitude": "30.6571234",
  "phoneMasked": "138****5678", "cityCode": "chengdu"
}
```

门店详情 `StoreDetailView`：与列表项同字段集（本切片内详情无增量字段；相册/简介/人员等延后项见 SCOPE-VERIFY §2.2）。

校验（违规 400 `COMMON_INVALID_ARGUMENT`，details 指明字段；未知 query 参数一律 400——`CServiceController.rejectUnknownParameters` 先例）：

- `storeId`：雪花 ID 十进制 String（公共 ID Codec），路径非法即 400；
- `city`：ASCII 小写词法 `[a-z][a-z0-9_-]{0,31}`（城市目录条目模式）且必须属于开放城市目录，否则 400；
- `page` 1..10000、`pageSize` 1..50（十进制数字串，与服务列表路由解析一致）；
- `longitude/latitude`：可空；非空时 ≤7 位小数且范围 [-180,180]/[-90,90]，存储值非法按读侧失败关闭 503（27号 `coordinate` 校验先例）；
- 不接受 `sort`/`keyword`/`categoryId`（STR-D4/D7）。

## 3. 操作契约

会话：C 端登录态（STR-D8；三处登记：`CBearerSessionFilter.protectedPath`、`CSessionSecurityConfiguration` permitAll、`CServiceExceptionHandler` assignableTypes）。未登录 401。读操作无 requestId 幂等要求（23号）。

| 操作 | 成功 | 关键错误 |
|---|---|---|
| GET `/api/v1/c/stores?city&page&pageSize` | 200 分页 `StoreListItemView[]`，仅返回 STR-D1 可见门店，`merchantId,storeId` 数值升序 | 400 参数/未知参数/未开放城市；401 未登录；503 事实源故障（含 compat 缺行完整性反例） |
| GET `/api/v1/c/stores/{storeId}` | 200 `StoreDetailView`（可见即资格合格） | 404 `STORE_NOT_FOUND`（不存在/OFFLINE/FROZEN/未签/审核未过——一律同响应不区分原因）；400；401；503 |
| 内部 `MerchantStoreDisplayApi.pageDisplayStores` | 可见门店页（含 total；城市集合过滤；整页一次资格判定） | 参数 400；确认无匹配城市门店=正常空页（非错误）；事实源异常 503 |
| 内部 `MerchantStoreDisplayApi.getDisplayStore` | 单店投影（可见时） | 不存在/不可见 NOT_FOUND（调用方 404）；事实源异常 DEPENDENCY_UNAVAILABLE（调用方 503）；不得混同 |

失败关闭细则：城市目录自身不可用（boot 配置为空）→ 400 前置校验无法执行的按 503 处理（`MerchantApplicationCityCatalog` 空配置即 DEPENDENCY_UNAVAILABLE 先例）；任何事实读取异常 → 整页 503，**不得降级为空页或部分页**；"空城市"= 目录可读、城市开放、确证无可见门店 → 200 空页。

## 4. Schema / Event / OpenAPI 影响

- **Schema：无变化**（`merchant`/`merchant_store`/`merchant_profile_compat` 既有列满足；不建新表；无迁移）。
- **Event：无**（纯读切片，无 outbox 事件）。
- **内部契约：pet-merchant-api 增补第五查询**（STR-D6，对 27号 §4 的显式增补，经本 CCR 批准；实现按唯一 Writer 规则独立 commit 并在 PR 披露——SVC-D5 先例）。
- **OpenAPI 11号：获批后增补**两操作 `cListStores`/`cGetStore`（`ACCEPTED_CONTRACT_NOT_IMPLEMENTED` 起步；风格对齐既有 Service-Catalog 两操作：tags Store-Catalog、x-audience MINIAPP、bearerAuth、no-store、404 "not found, not visible or ineligible (indistinguishable)"、503 fail closed）。
- **权威同步（获批后、实现 PR 内）**：27号 §4 增第五查询行；07号 v0.6 §4.3（或 §4.2 续）形状；10号 §3.3 门店小节（并建议补写欠账 §3.3.1 服务小节，见 SCOPE-VERIFY §6.1）；11号两操作；12号无变化。
- **boot 配置**：新增装配开关（建议 `pet.store.query.enabled`，默认关闭，对齐 `pet.service.query.enabled` 先例）。

## 5. 测试映射（详见 [TEST-PLAN.md](../../issues/wave-2/MER-001-store-read/TEST-PLAN.md)）

| 验收 | 本提案对应 |
|---|---|
| W2-STR-001～005（本提案新增，见 TEST-PLAN） | 可见性合取与不可见隐藏、城市/分页/参数、详情 404 防探测、503/404 不混同与恢复后可见、空城市与会话语义 |
| ARCH001~005 | 不跨模块 Repository；biz 不依赖 biz；boot 适配层组合 |
| W2-MER-001 | 本切片是其 C 端读侧补充，不解除其余 owner 侧验收 |

## 6. 承接登记与风险

| 事项 | 承接 | 登记位置 |
|---|---|---|
| 服务分类筛选（categoryId） | 服务写入方（ADM-001）合入后的后续小额 CCR（或按 STR-D7 备选本轮纳入） | CCR 索引商家/门店行 + READY_QUEUE |
| 关键词搜索（相关性排序） | 同上，待排序/相关性事实源定义 | 同上 |
| 匿名浏览（PRD"所有用户浏览"） | 待人工裁决；如批准需匿名安全评审 | OPEN_DECISIONS/待裁决清单 |
| 门店简介/公告/营业时间/相册/头像/收藏/评价/促销/人员展示 | 各自域后续（无事实源或无写入方） | SCOPE-VERIFY §2 延后清单 |
| 完整联系电话暴露 | 待人工裁决（现仅 phoneMasked） | 待裁决清单 |

风险与如实披露：

- 门店可见 ≠ 门店有可预约服务：列表/详情可能展示"空店"（无 ACTIVE 服务）。这是三条件合取的直接后果，与已批"店内服务空页"语义衔接；若产品要求"无在售服务门店不展示"，那是给可见性加第五条件（服务存在性，跨域），需人工另行决定，本提案不默认加入。
- `merchant_profile_compat` 缺行 → 503 的完整性反例会让单条脏数据影响整页可用性（失败关闭的代价）；测试需覆盖该反例，运维需监控。
- 与角色A 在 pet-boot 三个共享登记文件有追加行级交叠（SCOPE-VERIFY §5 已列合并顺序建议）。
- 无门店写入方场景下（门店只能经申请审批建档，该链路已存在），测试可用真实申请→审批→签署链路造可见门店，SQL 播种仅用于异常状态反例——比 SVC-D4 依赖面小，如实区分。
