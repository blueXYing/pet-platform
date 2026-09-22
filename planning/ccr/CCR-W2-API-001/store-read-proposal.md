# CCR-W2-API-001 门店读侧契约提案（v0.2，已按人工裁决修订）

> **状态：v0.2 —— 2026-09-22 用户分项批准并给出修订要求，本版按修订后范围实现。** 决定回执见 [store-read-decisions.md](store-read-decisions.md)。
> 规范版本：0.2，日期：2026-09-22。提出方：角色B（MER-001 门店读侧切片）。基线：develop `52a1c45`；worktree `wt-mer-stores`，分支 `codex/mer001-store-read-20260922`。
> 关联：CCR-W2-API-001 索引"商家/门店"行 2026-09-22 登记；SVC-001 提案 §7 承接表；SVC-D1～D5（已批）；角色A 服务写入方提案（另一 worktree，只读参考，其 SVCW-D4 SERVICE_COVER 管线增补由本切片实现）。前置核验：[SCOPE-VERIFY.md](../../issues/wave-2/MER-001-store-read/SCOPE-VERIFY.md)。

## 修订记录

| 版本 | 变更 |
|---|---|
| 0.1（2026-09-22） | 初稿：STR-D1～D8；两条 C 端门店读路由草案；pet-merchant-api 第五内部查询草案；测试计划另行见 TEST-PLAN |
| 0.2（2026-09-22） | 按用户裁决修订：D1/D2/D4/D5/D6/D7 按推荐批准（D2/D7 延后仅界定本切片范围，不代表完整门店页面验收完成）；**D3 增补存量完整性程序（SQL 检查+回归反例+告警与修复 runbook），整页 503 不作为无处置遗留风险**；**D8 改为按 PRD 支持匿名浏览并统一四条浏览路由（核对结论：无更高优先级冲突批准）**；电话本轮保持掩码、不宣称拨号功能，号码边界列入剩余待裁决；新增"决定回执"与"剩余需要决定的具体问题"两节；TEST-PLAN 同步匿名与完整性用例 |

## 决定回执（要点摘录，全文见 store-read-decisions.md）

用户于 2026-09-22 对 v0.1 分项批复：

1. STR-D1、D2、D4、D5、D6、D7 按推荐推进；**字段裁剪和筛选搜索延后仅代表本切片范围，不代表完整门店页面验收完成**。
2. STR-D8 修订：先核对是否存在覆盖 PRD"所有用户浏览、登录后预约"的更高优先级批准记录；若无，按 PRD 支持匿名浏览并统一核对门店和服务四条浏览路由（`/c/stores`、`/c/stores/{storeId}`、`/c/stores/{storeId}/services`、`/c/services/{serviceId}` 全部支持匿名 GET），相关契约及访问控制同步补齐；若发现冲突批准则暂停该子项并汇报证据。
3. STR-D3 修订：城市目录方案认可；本轮接受资料缺失时整页 503，但须明确异常检查范围，补齐存量完整性检查、告警和修复方案（SQL 完整性检查+回归测试覆盖该反例+运维告警与修复 runbook 文档），不作为无处置的遗留风险。
4. 电话：本轮保持掩码，不宣称拨号功能完成；"公开营业电话与私人联系号码的边界"在剩余待裁决问题清单中单独列出。
5. 据此修订草案，仅列出剩余需要决定的具体问题（v0.2）。

## 人工 CTO 一页阅读指南

**用通俗话说：小程序"门店列表"和"门店详情"两个页面还只有接口名字、没有内容定义。门店档案的权威字段早就批过（店名/地址/坐标/掩码电话），"能不能展示"的判断也有已批接口；但现有查门店的接口只有商家本人能查，消费者查不了，也没有"按城市翻页列出可展示门店"的查询，所以需要一个消费者视角的新内部查询。PRD 页面里的评分、月售、距离、收藏、评价、相册、促销、服务人员这些花哨字段，要么对应的功能压根没做（评价、收藏、订单统计），要么已被位置裁决取消（距离），本轮一律不做、不编数据。v0.1 列了八个决定，你已分项批复：六个按推荐做，D3 加了完整性运维程序，D8 按核对结论改为四条浏览路由统一支持匿名 GET。**

| 分工 | 内容 |
|---|---|
| 我负责 | 逐路由字段/校验/错误/鉴权映射、第五内部查询契约、可见性与失败关闭规则、D3 完整性程序、D8 匿名核对与统一改造、SERVICE_COVER 管线增补（用户裁决派生）、示例与反例、Schema/Event/OpenAPI 影响与依赖核验 |
| 你负责（已批复） | STR-D1～D8 按 v0.2 修订后执行；剩余待裁决仅"公开营业电话边界"等清单项（见文末） |
| 不变 | 产品硬规则与 SSOT §28 位置裁决原样执行；不提前实现排期/订单/支付；不实现无事实源字段 |

## 已批准决定（v0.2 修订后）

1. **STR-D1 门店可见性 = 三条件合取、同事务判定、失败关闭**（已批）。C 端门店可见当且仅当 `merchantEnabled ∧ storeEnabled ∧ acceptsNewOrders`（即 27号 §4 的 `merchant.status==ACTIVE` ∧ `store.status==ACTIVE` ∧ 审核APPROVED+签约SIGNED；后三者复用 `MerchantOrderEligibilityPolicy` 同一资格策略）。与 SVC-D1b 服务可见性同构，但**不含"服务 ACTIVE"条件**——门店本身可见性与它是否有在售服务是两件事（有店无服务仍可见，空服务列表由已冻结路由返回空页）。可见 = 基本资格合格，不代表有空位、可下单。错误两分沿用 SVC-D1 细化：**确认不存在或无资格 → 列表隐藏/详情 404；事实源故障、读取失败或状态未知 → 503 `COMMON_DEPENDENCY_UNAVAILABLE`；两者不得混同**。
2. **STR-D2 字段集裁剪**（已批）。本轮门店投影字段 = `storeId、merchantId、storeName、merchantName、address、longitude?、latitude?、phoneMasked?、cityCode`（九字段）。`merchantStatus/storeStatus/version` 不出现在 C 端响应（可见性已蕴含资格合格，避免恒真字段——沿用服务域 0.2 补正撤下 bookability 的同一理由）。评分/月售/距离/排序权重/头像/相册/简介/公告/营业时间/促销/收藏/评价/服务人员全部延后，理由与逐字段清单见 SCOPE-VERIFY §2（无事实源或 SSOT §28 取消），不虚构数据源、不返回占位假值。**电话本轮仅返回 phoneMasked 掩码投影，不宣称"可拨号/联系"功能完成；完整号码边界见剩余待裁决。**字段裁剪与筛选搜索延后仅界定本切片范围，不代表 C-003 完整门店页面验收完成。
3. **STR-D3 列表 query 参数与城市语义**（已批，v0.2 增补完整性程序）。`GET /c/stores` 仅接受 `city?`、`page?`、`pageSize?`（未知参数 400，`COMMON_INVALID_ARGUMENT`）。
   - `city` 语义：**城市范围由服务端控制**——取值必须属于 boot 侧开放城市目录（`MerchantApplicationCityCatalog`，现仅成都），目录在 boot 适配层校验：不在目录 → 400（城市字典是闭合集合，传未开放城市是参数错误）；**省略 `city` = 隐式限定为当前全部开放城市集合**（今天等价于成都），避免把"chengdu"硬编码进契约，未来增开城市不需改此路由；
   - 逐店城市事实来自 `merchant_profile_compat.city_code`（29号已批存储，APPROVE 同事务写入）；**通过资格合取但缺 compat 行的商家视为城市事实损坏 → 整页 503**（该商家按已批流程必有该行；沿用 ServiceQueryService "store service merchant binding is inconsistent" 的完整性反例先例），不得静默隐藏或归入任何城市。compat 行存在但 `city_code` 词法损坏（非 `[a-z][a-z0-9_-]{0,31}`）同样按完整性反例 503；
   - 开放城市但暂无可见门店 → 200 正常空页（`items:[], total:0`），与故障 503 严格区分（失败关闭，见 STR-D1）。
   - biz 不依赖 boot：merchant-biz 第五查询以 `cityCodes` 集合入参承接城市范围，不自行判断何为"开放"。
   - **存量完整性程序（v0.2 增补，用户裁决要求，不作为无处置遗留风险）**，检查范围 = "通过资格合取但缺 compat 行（或 compat city_code 词法损坏）的商家"：
     a. **SQL 完整性检查**（随实现交付，可作迁移后手工巡检脚本，见 [INTEGRITY-RUNBOOK.md](../../issues/wave-2/MER-001-store-read/INTEGRITY-RUNBOOK.md) 附 SQL）：列出 `merchant.status='ACTIVE' ∧ merchant_store.status='ACTIVE'` 但 `merchant_profile_compat` 缺行或 city_code 非法的商家；
     b. **回归测试覆盖该反例**：`CStoreControllerHttpTest` SQL 播种删除 compat 行 → 断言整页 503 且详情 503，补齐行后恢复 200（"恢复后可见"用例，见 TEST-PLAN N8/N9）；
     c. **运维告警与修复 runbook**：[INTEGRITY-RUNBOOK.md](../../issues/wave-2/MER-001-store-read/INTEGRITY-RUNBOOK.md)——检查范围、告警建议（对 503 `COMMON_DEPENDENCY_UNAVAILABLE` 且 message 含 compat 关键字的出现率/整页失败做监控与人工复核）、修复步骤（补行来源=申请审批链留存的 revision 事实，LEGACY 行须受控回填、不得猜测；幂等重放注意事项）。
4. **STR-D4 排序与分页**（已批）。排序固定为 `merchantId 数值升序, storeId 数值升序`（对齐 27号 §3 通用列表规则与 `selectOwnedStores` 既有先例；按 ID 数值序、非字符串词典序）。明示不支持"按距离/评分/销量/价格排序"——对应事实源不存在（距离被 SSOT §28 取消，评分/销量依赖未建域），接口不提供 `sort` 参数，不假装支持。分页形态对齐已冻结服务列表路由：`page` 1..10000 默认 1、`pageSize` 1..50 默认 20，信封 `items/page/pageSize/total`，多页间不承诺快照游标。
5. **STR-D5 详情 404 语义与已冻结服务路由的配合**（已批）。`GET /c/stores/{storeId}`：确认不存在、或三条件任一不满足 → **404 `STORE_NOT_FOUND`，不区分原因（防探测）**；事实源故障/未知 → 503。`STORE_NOT_FOUND` 已在 12号 §12 注册，无需新增错误码，仅实现层在 `CServiceExceptionHandler` STATUS 表补 `STORE_NOT_FOUND→404` 映射。
   **披露（已由角色F在另一分支修复，合并顺序见 §6）**：已合入的服务详情 404 实际返回 `COMMON_NOT_FOUND`（提案文本写 `SERVICE_NOT_FOUND`）。角色F 已修复服务路由回补 `SERVICE_NOT_FOUND`（分支 049a9c6）；本切片 404 语义沿用 F 修复后的结果，门店用 `STORE_NOT_FOUND`（语义精确、注册表已有），两者不互为前提。
   **配合关系（与已冻结 `/c/stores/{storeId}/services`）**：该服务列表路由对不存在/不可见门店返回 200 空列表（已批防探测语义：不暴露门店状态细节），门店详情路由对同一门店返回 404。两者不矛盾：不同资源各自的已批语义；C-003 页面应以详情路由为门店可访问性判据（PRD §5.1.14 异常边界"下线/冻结/删除 → 不可访问提示"），服务列表空页仅在该店内渲染"暂无服务"。列表项点进详情在两次调用间状态变化时按 404 处理。
6. **STR-D6 pet-merchant-api 第五内部查询（对 27号 §4 的显式增补）**（已批）。现有三查询均为所有者视角（`getStore` 走 `selectOwnedStore` owner 前件，消费者不可借用），第四查询只返回布尔不含档案字段，且无任何 C 端分页能力——组合复用不成立，需第五查询。按 27号风格：

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

   语义约定：**仅供 C 端展示聚合消费，只读门店档案投影，不授予任何商家操作权限**；无所有者前提，`QueryContext` 仅承载链路信息不得冒充主体（同 SVC-D5，匿名调用时主体字段为空）；与 4.1/4.2 同一资格策略（`MerchantOrderEligibilityPolicy`）、同一 repeatable-read 快照（`pageDisplayStores` 在 merchant-biz 自有只读事务内完成 join `merchant`/`merchant_store`/`merchant_profile_compat` 与资格事实判定，**整页一次资格判定**——一次快照内按页内出现的去重 merchant/store 对逐对求值策略，同一商家多店共享一次事实读取，不逐行重复读取；`getDisplayStore` 单店判定）。错误语义同 STR-D1 两分。
   **分页实现口径（如实披露）**：`acceptsNewOrders` 依赖审核/签约事实（经 `MerchantEligibilityFactsReader`，含协议文档校验业务逻辑，不能下沉为纯 SQL join），故列表在 SQL 过滤 `ACTIVE/ACTIVE + cityCodes` 的候选集上于同一快照内完成资格判定后过滤分页，`total` 只计可见门店（诚实 total，不把不可见门店计入）。候选集规模=开放城市内 ACTIVE 商家门店数（V1 单城市开放，规模有界）；未来多城市放量时如需改为两段式 SQL 分页须另行走小额 CCR，本切片不预设。
   实现归 pet-merchant-api/merchant-biz（MER 域文件，唯一 Writer 规则独立 commit 披露，SVC-D5 先例）；boot 适配层组合本查询与（未来的）服务域查询，biz 不依赖 biz。
7. **STR-D7 服务分类筛选与关键词搜索本轮延后**（已批延后）。
   - 分类筛选（"该店存在 ACTIVE 服务命中类目"）：语义明确但跨域过滤分页无诚实实现——service_item 在服务域，merchant-biz 不可跨模块查询；若服务域先按类目分页再由 merchant 域过滤可见性，`total` 会把不可见门店计入（假分页）；反之亦然。诚实做法需要服务域新增"按类目返回门店ID全集"查询 + merchant 域按集合过滤分页（两域各加契约，V1 规模可行但无界），且与角色A（服务写入方）在 pet-service-* 同文件族并行改动，冲突面上升。本轮延后：路由不接受 `categoryId`（未知参数 400），待服务写入方合入后另行走小额 CCR（届时 ADM 写入方在场，数据与写入路径可验证）。备选（若人工要求本轮纳入）：pet-service-api 增 `listStoreIdsWithActiveService(categoryId, context)`（无分页、DISTINCT store_id 数值升序），boot 将集合传给 `pageDisplayStores` 的增补参数 `storeIds?`（merchant 域 SQL IN 过滤后分页计数，total 诚实）；该备选同时扩大两域契约与冲突面，需在批准时明确选择。
   - 关键词搜索（店名/服务名）：PRD 要求"按相关性排序"，无相关性/权重事实源与定义；跨店名（merchant 域）+服务名（service 域）双域匹配同样需要新契约。本轮延后并登记 C-003 后续需求，不提供 `keyword` 参数。
   - 两项延后均登记于 CCR 索引商家/门店行与 READY_QUEUE，不挂空、不隐式扩大；**延后仅代表本切片范围，不代表完整门店页面验收完成（用户裁决原文措辞）**。
8. **STR-D8 会话语义 = 四条浏览路由统一匿名可 GET（v0.2 修订，按核对结论执行）**。
   **核对结论：未发现覆盖/推翻 PRD"所有用户浏览、登录后预约"的更高优先级批准记录。**核对范围与证据见 [store-read-decisions.md](store-read-decisions.md) §D8：SSOT 全文无匿名/未登录/浏览会话规则（§1 仅列登录方式）；C端最终 PRD §5.1.13/§5.1.14 明文"所有用户浏览；已登录用户可进入预约"；技术基线（AGENTS.md/02-architecture）无浏览会话规则；OPEN_DECISIONS.md 无此项；全部已批 CCR 决定回执（SVC-D1～D5、ACR-001、AUTH-001、运营权限/MFA/签约/位置等裁决）中无任何一项把"浏览需登录"作为产品规则批准——已合入服务两路由的"C 端登录态、未登录 401"是当时对既有过滤器登记机制的**描述性对齐**（提案原文注明"既有三处登记"），不在 SVC 被批准的编号决定之内，级别亦低于最终 PRD。据用户裁决"若无冲突→按 PRD 支持匿名浏览并统一四条路由"，执行：
   - **四条浏览路由 `/c/stores`、`/c/stores/{storeId}`、`/c/stores/{storeId}/services`、`/c/services/{serviceId}` 的 GET 一律匿名可访问**：无 Authorization 头 → 匿名放行（不设置会话主体属性）；携带 Bearer → 校验，**无效/过期 token 仍 401 `COMMON_UNAUTHORIZED`**（匿名放行不掩盖坏凭证）；有效 token → 正常解析会话主体。可选登录不改变可见性：门店/服务可见性与登录与否无关（三条件合取/四条件合取均无用户主体条件）；
   - 写操作与其他 C 端路由（pets/profile/notifications/private-assets/merchant-applications 等）行为不变：非 GET 方法与未列入清单的路径维持既有强制 Bearer/denyAll；
   - 实现收敛在路径清单机制上（`CBearerSessionFilter` 增"GET 可选会话路径"清单 + `CSessionSecurityConfiguration` 对应 GET permitAll 登记），不重写过滤器、不改变其余路径语义；
   - **契约同步**：10号 §3.3 四条浏览路由标注匿名 GET 语义；11号 `cListStoreServices`/`cGetService`（既有两操作）与新增 `cListStores`/`cGetStore` 的 security 注记改为"匿名或 Bearer 可选"（OpenAPI `security: [{}, {bearerAuth: []}]` 表达），401 描述改为"carried bearer invalid or expired"；
   - 既有测试同步：`ServiceQueryHttpTest` 原"无 token 401"断言按新契约改为"无 token 200"（本裁决授权的契约变更，PR 披露）；"无效 token 401"断言保留。

## 1. 范围与来源（依赖核验结论）

| 来源 | 核验结果 |
|---|---|
| HTTP10 §3.3 | 门店两路由仅列名，无字段/分页/筛选/错误定义（本提案补齐，含匿名语义说明）。10号现无 §3.3.1（服务小节欠账），由角色F 在另一分支补写；本切片 10号改动收敛在门店小节与 §3.3 总述，避免与 F 冲突 |
| 27号 §4 | MerchantStoreDTO 字段与三+一查询已批；getStore/checkOrderEligibility 为所有者视角；`acceptsNewOrders` 含 APPROVED+SIGNED 语义 |
| 07号 v0.6 §4.2 | SVC-D5 展示资格查询已批已实现（错误两分原文）；第五查询形状随本切片同步 07号 §4.3 |
| Schema06/29号 | `merchant_store` 无城市/简介/相册/营业时间列；城市事实在 `merchant_profile_compat.city_code`（已批存储，APPROVE 同事务写入，PK=merchant_id 单行） |
| SSOT §28 | 取消地理匹配/距离阈值/围栏与地图 Key；地址坐标仍保存（展示用途可暴露坐标，不做距离） |
| 错误码 12号 §12 | `STORE_NOT_FOUND` 已存在，无需新增 |
| OpenAPI 11号 | 门店两操作缺登记；新增以 `ACCEPTED_CONTRACT_NOT_IMPLEMENTED` 起步（MER-001/SVC-001 先例），实现合并后保持该状态直至 E2E 具备 |
| 既有实现 | pet-merchant 读侧仅 owner 前件查询；pet-boot C 过滤器三处登记模式（Filter/Security/Advice）可直接复用 |
| 31号私有资产契约 | SERVICE_COVER 材料类型增补由本切片实现（用户裁决派生分工，独立 commit 披露；详见 §4 与角色A提案 SVCW-D4） |

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

门店详情 `StoreDetailView`：与列表项同字段集（本切片内详情无增量字段；相册/简介/人员等延后项见 SCOPE-VERIFY §2.2）。**电话为掩码投影，不构成"可拨号"能力；前端不得据此宣称一键联系功能完成。**

校验（违规 400 `COMMON_INVALID_ARGUMENT`，details 指明字段；未知 query 参数一律 400——`CServiceController.rejectUnknownParameters` 先例）：

- `storeId`：雪花 ID 十进制 String（公共 ID Codec），路径非法即 400；
- `city`：ASCII 小写词法 `[a-z][a-z0-9_-]{0,31}`（城市目录条目模式）且必须属于开放城市目录，否则 400；
- `page` 1..10000、`pageSize` 1..50（十进制数字串，与服务列表路由解析一致）；
- `longitude/latitude`：可空；非空时 ≤7 位小数且范围 [-180,180]/[-90,90]，存储值非法按读侧失败关闭 503（27号 `coordinate` 校验先例）；
- 不接受 `sort`/`keyword`/`categoryId`（STR-D4/D7）。

## 3. 操作契约

会话（v0.2，STR-D8）：四条浏览路由 GET **匿名可访问，可选 Bearer**——无 token 匿名放行、无效/过期 token 401、有效 token 正常；其余 C 端路由行为不变。实现登记：`CBearerSessionFilter`（GET 可选会话清单）、`CSessionSecurityConfiguration`（GET permitAll，受 `pet.store.query.enabled`/既有开关门控）、`CServiceExceptionHandler`（assignableTypes 增 CStoreController；STATUS 增 `STORE_NOT_FOUND→404`）。读操作无 requestId 幂等要求（23号）。

| 操作 | 成功 | 关键错误 |
|---|---|---|
| GET `/api/v1/c/stores?city&page&pageSize` | 200 分页 `StoreListItemView[]`，仅返回 STR-D1 可见门店，`merchantId,storeId` 数值升序 | 400 参数/未知参数/未开放城市；401 携带的 Bearer 无效；503 事实源故障（含 compat 缺行完整性反例） |
| GET `/api/v1/c/stores/{storeId}` | 200 `StoreDetailView`（可见即资格合格） | 404 `STORE_NOT_FOUND`（不存在/OFFLINE/FROZEN/未签/审核未过——一律同响应不区分原因）；400；401 携带的 Bearer 无效；503 |
| GET `/api/v1/c/stores/{storeId}/services`（既有，匿名语义统一） | 200 分页（SVC 已批语义不变） | 401 仅当携带的 Bearer 无效；400/404 族不变 |
| GET `/api/v1/c/services/{serviceId}`（既有，匿名语义统一） | 200 `ServiceDetailView`（SVC 已批语义不变） | 同上 |
| 内部 `MerchantStoreDisplayApi.pageDisplayStores` | 可见门店页（含 total；城市集合过滤；整页一次资格判定） | 参数 400；确认无匹配城市门店=正常空页（非错误）；事实源异常 503 |
| 内部 `MerchantStoreDisplayApi.getDisplayStore` | 单店投影（可见时） | 不存在/不可见 NOT_FOUND（调用方 404）；事实源异常 DEPENDENCY_UNAVAILABLE（调用方 503）；不得混同 |

失败关闭细则：城市目录自身不可用（boot 配置为空）→ 按已批 `MerchantApplicationCityCatalog` 空配置即 DEPENDENCY_UNAVAILABLE（503）处理，不返回未过滤全量；任何事实读取异常 → 整页 503，**不得降级为空页或部分页**；"空城市"= 目录可读、城市开放、确证无可见门店 → 200 空页。

## 4. Schema / Event / OpenAPI 影响

- **Schema：无变化**（`merchant`/`merchant_store`/`merchant_profile_compat` 既有列满足；不建新表；无迁移；31号 SERVICE_COVER 为既有私有资产表 purpose 取值增补，无 DDL）。
- **Event：无**（纯读切片，无 outbox 事件）。
- **内部契约：pet-merchant-api 增补第五查询**（STR-D6，对 27号 §4 的显式增补，经本 CCR 批准；实现按唯一 Writer 规则独立 commit 并在 PR 披露——SVC-D5 先例）。
- **OpenAPI 11号：增补**两操作 `cListStores`/`cGetStore`（`ACCEPTED_CONTRACT_NOT_IMPLEMENTED` 起步；tags Store-Catalog、x-audience MINIAPP、**匿名或可选 bearerAuth**、no-store、404 "not found, not visible or ineligible (indistinguishable)"、503 fail closed）；既有 `cListStoreServices`/`cGetService` security 注记同步改为匿名可选（STR-D8）。
- **31号：增补 SERVICE_COVER 材料类型**（用户裁决派生，MER 域唯一 Writer）：`POST /api/v1/c/private-assets` 的 `purpose` 取值在 `MERCHANT_APPLICATION_MATERIAL` 之外新增 `SERVICE_COVER`（商家主账号范围上传、仅图片、内容扫描/不可变事实/幂等恢复链路全部沿用既有管线）；素材归属=上传商家（owner 绑定上传会话用户，resolveOwned 按 owner+purpose 隔离）；运营审阅=服务审核详情可见封面（消费方为角色A 的审核详情路由，经私有资产 read-grant 机制）；消费者展示授权=由服务域读侧签名 URL 机制处理（角色A 负责，不在本切片）。独立 commit 并在 PR 披露。
- **权威同步（实现 PR 内，独立 commit）**：27号 §4 增第五查询行；07号 §4.3 形状；10号 §3.3 门店小节+四条浏览路由匿名语义说明（收敛在门店小节，不触碰 F 正在补写的 §3.3.1 服务小节）；11号两新操作+两既有操作注记；12号无变化。
- **boot 配置**：新增装配开关 `pet.store.query.enabled`（默认关闭，对齐 `pet.service.query.enabled` 先例）。

## 5. 测试映射（详见 [TEST-PLAN.md](../../issues/wave-2/MER-001-store-read/TEST-PLAN.md)）

| 验收 | 本提案对应 |
|---|---|
| W2-STR-001～006（TEST-PLAN，v0.2 增匿名与完整性用例） | 可见性合取与不可见隐藏、城市/分页/参数、详情 404 防探测、503/404 不混同与恢复后可见、空城市、**四路由匿名语义（无 token 200/无效 token 401/有效 token 200 且可选主体不改变可见性）**、compat 缺行整页 503 与补齐恢复、SERVICE_COVER 上传归属反例 |
| ARCH001~005 | 不跨模块 Repository；biz 不依赖 biz；boot 适配层组合 |
| W2-MER-001 | 本切片是其 C 端读侧补充，不解除其余 owner 侧验收 |

## 6. 承接登记、风险与合并顺序

| 事项 | 承接 | 登记位置 |
|---|---|---|
| 服务分类筛选（categoryId） | 服务写入方（ADM-001）合入后的后续小额 CCR（或按 STR-D7 备选另行批准） | CCR 索引商家/门店行 + READY_QUEUE |
| 关键词搜索（相关性排序） | 同上，待排序/相关性事实源定义 | 同上 |
| 门店简介/公告/营业时间/相册/头像/收藏/评价/促销/人员展示 | 各自域后续（无事实源或无写入方） | SCOPE-VERIFY §2 延后清单 |
| SERVICE_COVER 消费者展示签名 URL | 角色A（服务域读侧） | 本提案 §4 + 角色A 提案 SVCW-D4 |
| 强制下架（force-offline）通知归属 | 角色A 提案 SVCW-D10 已登记张力，供其参考 | 剩余待决定清单（见下） |

风险与如实披露：

- 门店可见 ≠ 门店有可预约服务：列表/详情可能展示"空店"（无 ACTIVE 服务）。这是三条件合取的直接后果，与已批"店内服务空页"语义衔接；若产品要求"无在售服务门店不展示"，那是给可见性加第五条件（服务存在性，跨域），需人工另行决定，本提案不默认加入（列入剩余待决定）。
- `merchant_profile_compat` 缺行/城市码损坏 → 503 的完整性反例会让单条脏数据影响整页可用性（失败关闭的代价）；**已按 D3 修订配套完整性程序**（SQL 巡检+回归反例+告警与修复 runbook），运维按 runbook 监控处置，不作为无处置遗留风险。
- 与角色A 在 pet-boot 三个共享登记文件有追加行级交叠（`CSessionSecurityConfiguration`/`CBearerSessionFilter`/`CServiceExceptionHandler`），双方登记行带切片注释，先后合并均可、后合者 rebase 机械合并（SCOPE-VERIFY §5）。
- 与角色F（404 码修复+§3.3.1 补写）交叠：`CServiceExceptionHandler` STATUS 表与 10号 §3.3 区域。**建议合并顺序：F 修复先并 → 本切片 rebase**（404 语义沿用 F 修复后结果）；A 的 PR 与之并行。
- 匿名 GET 扩大未认证读取面（防爬/防枚举）：四条路由均无用户私有数据（可见性合取已过滤不可见资源，404 不可区分），分页参数有界（pageSize≤50）；V1 无独立限流中间件，如需全局匿名限流另行裁决（列入剩余待决定）。
- 无门店写入方场景下（门店只能经申请审批建档，该链路已存在），测试可用真实申请→审批→签署链路造可见门店，SQL 播种仅用于异常状态反例——比 SVC-D4 依赖面小，如实区分。

## 7. 剩余需要决定的具体问题（v0.2，仅列待决项）

1. **公开营业电话与私人联系号码的边界**（用户裁决指定单列）：本轮 C 端仅 phoneMasked（既有授权事实只有掩码投影，27号 §3"联系方式默认掩码"）；消费者联系门店通常需要完整电话，完整暴露是新的披露决定（影响 C-003 详情页可用性与拨号功能）。待人工裁决：是否开放完整号码、经何种脱敏/限流策略。
2. **强制下架通知归属（供角色A 参考）**：SSOT §一"订单、退款、核销、售后、审核必须保留站内消息"若覆盖服务审核/强制下架，需 MERCHANT 收件箱+服务事件+Outbox，均不在角色A 切片；归属与时机待裁决（角色A 提案 SVCW-D10 已登记同一张力）。
3. **"无在售服务门店是否展示"**：现按三条件合取（有店无服务仍可见）；若产品要求加"存在 ACTIVE 服务"第五条件（跨域事实），需另批。
4. **匿名浏览的公共限流策略**：四路由匿名 GET 已按 PRD 开放；V1 无独立限流中间件，是否对匿名请求加平台级限流（防爬）待运维/产品裁决。
5. **SERVICE_COVER 消费者展示签名 URL 契约细节**：消费者展示授权机制由角色A 的服务域读侧实现，其契约形状（有效期、签名范围、缓存策略）在其提案内决定，本切片仅提供管线与归属语义。
