# MER-001 门店读侧切片：范围核验（SCOPE-VERIFY）

日期：2026-09-22。角色B（门店读侧后端）。worktree：`wt-mer-stores`，分支 `codex/mer001-store-read-20260922`，基于 develop `52a1c45`（PR65，SVC-001 服务域读切片已合入）。本文件是第一阶段（范围核验+CCR草案+测试计划）产物，**本轮不写任何实现代码、不改任何权威文档**；CCR 草案见 [store-read-proposal.md](../../../ccr/CCR-W2-API-001/store-read-proposal.md)（DRAFT v0.1，未获批不得实现）。

## 1. 承接登记一致性核验

| 登记处 | 内容 | 核验结果 |
|---|---|---|
| WORK_STATE.md NEXT_PHASE | "门店读侧（MER-001 承接）" | 一致 |
| CCR-W2-API-001 主索引第13行（商家/门店行） | 2026-09-22 登记：C端门店两条读路由由 MER-001 后续门店读侧切片承接（SVC-001提案§7） | 一致 |
| SVC-001 服务域提案 §7 / 决定回执 D2 | 门店两条路由（`/c/stores`、`/c/stores/{storeId}`）由 MER-001 承接，"含城市/列表页面字段，届时另走其域提案" | 一致；本文即该域提案的前置核验 |
| HTTP10 §3.3（10号 v0.4 行280-283） | 四条 C 端路由已列；门店两条只有路由名，无字段/分页/筛选定义 | 一致，缺口属实，由本切片 CCR 补齐 |
| OpenAPI 11号 | `/c/stores/{storeId}/services`、`/c/services/{serviceId}` 已有 `ACCEPTED_CONTRACT_NOT_IMPLEMENTED` 形态；`/c/stores`、`/c/stores/{storeId}` 缺登记 | 一致，本切片获批后收录 |
| 错误码 12号 §12 | `STORE_NOT_FOUND` **已存在**（"门店不存在"），`SERVICE_NOT_FOUND` 亦存在 | 无需新增错误码 |

结论：承接链完整，无范围冲突；本切片范围 = 两条 C 端门店读路由的契约草案（+必要的内部查询增补草案），不含服务写入、排期、订单、支付。

## 2.（B1）字段集裁剪：逐字段"本轮纳入/本轮延后"

权威事实源盘点（仅以下三组，不虚构数据源）：

- **27号 §4 `MerchantStoreDTO`**：merchantId、storeId、merchantName(1~128)、storeName(1~128)、address(1~255)、longitude/latitude（可空，≤7位小数，[-180,180]/[-90,90]）、phoneMasked（可空）、merchantStatus、storeStatus、version；
- **SVC-D5 第四查询**（已实现，`MerchantDisplayEligibilityApi`）：merchantEnabled、storeEnabled、acceptsNewOrders 三个布尔事实，错误两分（NOT_FOUND vs DEPENDENCY_UNAVAILABLE）；
- **成都城市目录**：boot 侧 `MerchantApplicationCityCatalog`（`OpenCityReader` 端口，配置驱动，空配置=503 失败关闭，开放城市服务端控制）+ **29号存储 `merchant_profile_compat.city_code`**（APPROVE 建档同事务写入 `source_kind='APPLICATION'` 行；存量 LEGACY 行须受控回填，"没有来源时不得从名称、地址或手机号猜测"）。

C端PRD §5.1.13（商家服务页）/ §5.1.14（商家详情页）逐字段裁定：

### 2.1 列表卡片（§5.1.13）

| PRD 字段 | 裁定 | 理由（事实源核验） |
|---|---|---|
| 商家/门店名称 | **本轮纳入** | `merchant_name`/`store_name`，MerchantStoreDTO 已有 |
| 门店地址 | **本轮纳入** | `address`，MerchantStoreDTO 已有 |
| 经纬度 | **本轮纳入（可空）** | `longitude/latitude` 已有（SSOT §28 仍要求入驻保存坐标；用于地图标注展示，不做距离计算） |
| 城市 | **本轮纳入**（query 筛选 + 响应 cityCode） | `merchant_profile_compat.city_code` + 开放城市目录；成都开放经 2026-09-20 人工确认 |
| 电话（掩码） | **本轮纳入（phoneMasked，可空）** | MerchantStoreDTO 仅授权 phoneMasked；完整号码无已批暴露契约（见 §2.3 待裁决） |
| 距离 | **本轮延后（事实上取消展示）** | SSOT §28：不以地址/坐标做地理匹配/距离阈值/围栏，取消腾讯 WebService Key 与外部地理编码依赖；"计算当前用户地址相差距离"无权威事实源，不得用坐标自行算距离冒充已批能力 |
| 按距离/评分/销量/价格排序 | **本轮延后** | 同上无距离；评分/销量统计无事实源（见下）；排序退化为稳定 ID 序（见提案 STR-D4） |
| 评分 | **本轮延后** | 评价域未实现；PRD §5.1.27 评分算法依赖"有效完成订单的评价"，无评价/订单统计事实源 |
| 月售单量 | **本轮延后** | 订单统计未实现，无事实源 |
| 商家头像/门店图 | **本轮延后** | `merchant`/`merchant_store` 无 avatar/logo/相册列（Schema06 核验）；申请门店照片是私有审核材料（S8），不是公开相册，不得挪用 |
| 主营服务标签 | **本轮延后** | 无商家维护标签字段；可由服务类目推导但服务写入方未交付、店铺-类目聚合契约未批（与分类筛选同批，见 STR-D7） |
| 热门服务2项/已售数量/价格 | **本轮延后** | "热门/已售"无排序与统计事实源；C 端可经已冻结的 `/c/stores/{storeId}/services` 取服务列表，不在门店路由内聚合 |
| 搜索关键词（店名/服务名） | **本轮建议延后** | "按相关性排序"无定义、无排序事实源；跨店名/服务名双域匹配需新契约（STR-D7） |
| 定位筛选 | **本轮延后** | 依赖被 §28 取消的地理能力，仅保留城市筛选 |

### 2.2 详情页（§5.1.14）

| PRD 字段 | 裁定 | 理由 |
|---|---|---|
| 店铺名/地址/经纬度/城市 | **本轮纳入** | 同 2.1 |
| 联系电话 | **本轮纳入 phoneMasked** | 同 2.1（完整号码待裁决） |
| 服务项目列表 + 预约入口 | **本轮不重复实现** | 已冻结路由 `/c/stores/{storeId}/services`（SVC-D2）承接；详情路由与其配合关系见提案 STR-D5 |
| 简介 | **本轮延后** | `merchant_store` 无简介列；申请 revision 内的简介是不可变审核证据快照，不是商家可维护的门店简介事实，不得当作公开简介暴露 |
| 营业时间/公告 | **本轮延后** | Schema 无列、无维护写入方 |
| 相册 | **本轮延后** | 无公开相册资产契约（CCR-OSS-001 是运营素材，私有材料是审核证据） |
| 促销文案/活动角标 | **本轮延后** | 无字段、无写入方、无契约 |
| 评价摘要/评价列表 | **本轮延后** | 评价域未实现 |
| 收藏按钮/收藏状态 | **本轮延后** | 收藏无表、无契约 |
| 服务人员列表（在岗） | **本轮延后** | `getStaff` 是所有者视角（27号 §4，`selectOwnedStaff` 带 owner 前提），C 端展示需新契约；"资质与擅长项"Schema 无字段（`staff_service_capability` 归 SCH 域） |
| 第三方核销标识 | **本轮延后** | 依赖美团/大众点评核销域，未实现 |

### 2.3 需人工裁决的字段级问题

1. **门店联系电话是否暴露完整号码**：既有授权事实只有 phoneMasked（27号 §3 "联系方式默认掩码"）。消费者联系门店通常需要完整电话，但完整暴露是新的披露决定。推荐：本轮仅 phoneMasked，完整号码列入待裁决（影响：C-003 详情页可用性）。
2. **PRD"所有用户浏览" vs 现行 C 端会话过滤语义**：见 §4（B5）。

## 3. 既有实现核验（B4 依据）

| 核验项 | 结论 |
|---|---|
| `MerchantQueryApi.getStore` | **所有者视角**：`MerchantQueryService.getStore` 经 `ownerUserId(context)` 提取 USER 主体并走 `selectOwnedStore(storeId, ownerUserId)`（owner 前件过滤）。消费者调用必然查不到，**不得借用**（与 SVC-D5 立项前 `checkOrderEligibility` 同一情形） |
| `checkDisplayEligibility`（第四查询） | 已实现于 pet-merchant-api/biz（`MerchantDisplayEligibilityService`：joining template 加入调用方事务、同一资格策略、无 owner 前件、NOT_FOUND/DEPENDENCY_UNAVAILABLE 两分）；但**只返回三个布尔，不含门店档案字段** |
| C 端门店分页查询 | **不存在**：`MerchantReadMapper` 仅有 `selectOwnedStore/selectOwnedStores/countOwnedStores`（均 owner 前件）+ `selectDisplayEligibilityBase`（单店对）；无城市过滤、无可见性整页查询 |
| 城市事实源 | 门店本身无城市列（Schema06 `merchant_store` 核验）；唯一城市事实在 `merchant_profile_compat.city_code`（29号已批存储，merchant-biz 自有 Mapper，APPROVE 同事务写入） |
| pet-service 读切片 | `storePage`（`ServiceQueryService.storePage`）示范了"整页一次资格判定"（同店同对一次 D5 调用）与"不可见=空页"先例；服务域既有查询（快照/资格/店内分页）**均无按类目返回门店集合的能力** |
| 结论 | 两条 C 端门店路由**必须新增第五内部查询**（展示视角的门店分页+单店投影），复用现有三/四查询组合不成立（组合无档案权限、无城市、无分页）；草案见提案 STR-D6 |

## 4.（B5）会话与安全现状核验

现状（代码事实，非猜测）：

- `CBearerSessionFilter.protectedPath`：现有受保护 C 端路径含 `/api/v1/c/pets*`、`/c/profile`、`/c/auth/session`、`/c/merchant-application-cities`、`/c/private-assets`、`/c/merchant-applications*`、`/merchant/agreement*`、`/c/auth/merchant-memberships`、`/merchant/auth/admission`、`/c/notifications*`、`/c/stores/{id}/services`（正则）、`/c/services/*`。**`/c/stores` 与 `/c/stores/{storeId}` 未登记**。
- `CSessionSecurityConfiguration`：显式 permitAll 之外的 `/api/v1/c/**` 一律 `denyAll()`。因此**当前 `GET /api/v1/c/stores` 与 `GET /api/v1/c/stores/{storeId}` 落入 403 COMMON_FORBIDDEN**（C 鉴权启用时）；C 切片未启用时落入全局拒绝。
- 语义：Spring Security 层 permitAll + 自定义 Filter 强制 MINIAPP Bearer（401 `COMMON_UNAUTHORIZED`）。SVC-D1~D5 已批契约沿用"C 端登录态，未登录 401"（服务域提案 §3），实现一致。
- PRD §5.1.13/§5.1.14 写"所有用户浏览；已登录用户可进入预约"。**现状与已批先例都是要求登录态**；"未登录可浏览"是产品规则变更（涉及匿名防爬/防探测面扩大），不由本切片自行决定。建议：本轮沿用登录态（401），差异列入待人工裁决（提案 STR-D8）。
- 消费者可见性失败关闭：事实源故障/读取失败/状态未知 → 整页 503 `COMMON_DEPENDENCY_UNAVAILABLE`，**不得把不可见伪装成正常空页返回**；空页 200 仅当事实可读且确证无可见门店（如开放城市无店）。对齐 SVC-D1 错误两分细化（决定回执第6条）。

## 5.（B6）文件归属与冲突协调

本切片获批后的预期改动清单（本轮不执行）：

| 模块 | 新增 | 修改 |
|---|---|---|
| pet-merchant-api | `query/MerchantStoreDisplayApi.java`、`query/MerchantStoreDisplayPageQuery.java`、`query/MerchantStoreDisplayQuery.java`、`dto/MerchantStoreDisplayDTO.java`、`dto/MerchantStoreDisplayPageDTO.java` | 无 |
| pet-merchant-biz | `application/MerchantStoreDisplayService.java`、`apiimpl/MerchantStoreDisplayApiImpl.java` | `MerchantReadMapper.java`/`.xml`（新增展示分页/单店 select，join `merchant_profile_compat`；复用既有资格策略与 `MerchantEligibilityFactsReader`） |
| pet-boot | `adapter/web/c/CStoreController.java`、`config/StoreQueryConfiguration.java`（默认关闭开关装配） | `config/CBearerSessionFilter.java`（protectedPath 两条）、`config/CSessionSecurityConfiguration.java`（permitAll 两条）、`adapter/web/c/CServiceExceptionHandler.java`（assignableTypes 增 CStoreController；STATUS 增 `STORE_NOT_FOUND→404`） |
| 测试 | pet-boot `StoreQueryHttpTest`（ServiceQueryHttpTest 同款：真实 MySQL/Redis + 申请→审批→签署链 + SQL 播种） | — |

与角色A（服务写入方，另一 worktree，ADM-001 范围）的交叠与合并顺序：

- **交叠文件（pet-boot，均是小幅追加行）**：`CSessionSecurityConfiguration.java`（双方各加 permitAll 登记行）、`CBearerSessionFilter.java`（双方各加 protectedPath 行）、`CServiceExceptionHandler.java`（若 A 的新 controller 走同一 advice）。本切片在推荐方案（STR-D7 延后分类筛选）下**不改 pet-service-* 任何文件**，与 A 的服务模块写入无交叠；若分类筛选改为本轮纳入，则 B 需在 pet-service-api 增只读查询，与 A 的服务模块改动同文件族冲突面上升（这是推荐延后的原因之一）。
- **建议合并顺序**：两切片对交叠文件都是"追加登记行"式改动，先后合并均可，后合者 rebase 后手工合并（同方法内相邻行冲突，机械解决）。建议：先合门店读侧（范围小、无写入方依赖），A 后合并时重放登记行；或反之，均需在 PR 描述显式披露触碰的共享文件。双方约定各自的登记行带注释标识来源切片，降低误删风险。
- **MER-001 在途 PR**：截至本 worktree（基线 52a1c45），MER-001 各切片（PR50~57、59~64、PR65）均已合入，**无其他在途 MER PR**；本切片是当前唯一 MER 改动流。角色A 状态以协调方信息为准，本 worktree 不可见。

## 6. 与指示冲突/需要如实披露的事实

1. **10号 v0.4 实际不存在 §3.3.1**。SVC-001 交接与 50f7ff4 提交信息均称"HTTP10 3.3.1 路由契约已同步"，07号 v0.6 §5.1.1 也引用"可见性规则见 10 号 §3.3.1"，但 `docs/04-api/10-HTTP-API-Contract-v0.4.md` 中无该节（§3.3 仍只有四行路由列表；50f7ff4 的 diff 未含 10号文件）。已冻结的服务路由详细形态实际落在 11号 OpenAPI 两操作与 CCR 提案 §2/§3。本切片草案以 11号+提案为风格基准，并把"补写 10号 §3.3.1（服务）与新增门店两路由小节"列入获批后权威同步清单，供人工一并处理。
2. **服务详情 404 的错误码：提案与实现不一致**。服务域提案 §3 表格写"404 `SERVICE_NOT_FOUND`"，但已合入实现 `ServiceQueryService.notFound()` 抛 `COMMON_NOT_FOUND`（`CServiceExceptionHandler` STATUS 表无 SERVICE_NOT_FOUND 映射），HTTP 测试仅断言 404 状态码。对消费者两码均可防探测，但家族一致性需要人工选定：门店详情推荐 `STORE_NOT_FOUND`（注册表已有、语义精确、任务指示方向），同时把"服务路由是否回补 SERVICE_NOT_FOUND"列为待裁决（见提案 STR-D5 披露）。
3. **城市事实源的层间位置**：开放城市目录在 boot 配置（`MerchantApplicationCityCatalog`），而逐商家城市事实在 merchant-biz 存储（`merchant_profile_compat`）。biz 不依赖 boot，故"city 参数必须属于开放目录"的校验只能放 boot 适配层，merchant-biz 第五查询以 cityCode 集合入参承接（不自行判断何为开放城市）。该分工已写入提案 STR-D3/STR-D6。
4. PRD 列表页"仅展示审核通过且未冻结的商家"与已批可见性合取（merchantEnabled∧storeEnabled∧acceptsNewOrders，含已签约）不一致处以 SSOT/27号 合取为准——PRD 文本不追加"已签约"门槛但 27号 `acceptsNewOrders` 语义已含 APPROVED+SIGNED，本切片按已批合取执行，不另立规则。

## 7. 结论

- 范围承接链完整，两条路由缺口属实；`STORE_NOT_FOUND` 已注册，无需新增错误码。
- 字段集裁剪：本轮仅 MerchantStoreDTO 投影 + cityCode；评分/月售/距离/收藏/评价/相册/促销/人员/简介/营业时间全部延后（无事实源或已被 SSOT §28 取消），清单见 §2。
- 内部查询：现有三/四查询不足以支撑（无档案权限、无城市、无分页），需按 27号风格增补第五查询（草案 STR-D6）。
- 会话：现状全部 C 端业务路由要求登录态，门店两路由建议沿用并列入待裁决（STR-D8）。
- 三个事实性偏差（10号 §3.3.1 缺失、SERVICE_NOT_FOUND 实现差异、城市事实分层）已如实披露。
