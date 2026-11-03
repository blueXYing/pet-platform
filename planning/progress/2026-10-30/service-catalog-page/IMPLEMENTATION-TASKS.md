# 服务目录页实施任务清单

状态：**APPROVED / 实施中**（2026-10-30；C-01 契约合入、C-02 74 定稿完成；代码任务未开工）。

来源：

- [CCR-SVC-CAT-001](../../../ccr/CCR-SVC-CAT-001.md) 服务分类字典与分类筛选
- [CCR-SVC-CARD-001](../../../ccr/CCR-SVC-CARD-001.md) 服务页商家卡聚合读
- [74 号服务目录页契约](../../../../docs/04-api/74-Service-Catalog-Page-Contract-v0.1.md)
- [71 号热门服务补丁草案](../../../../docs/04-api/71-Marketing-Center-Contract-v0.1-hot-service-patch-draft.md)
- Figma `97:7825`（热门服务）、`110:480`（服务 tab）

范围：服务分类字典、热门服务二级页、服务 tab 优质商家、商家列表分类筛选、营销投放位/内容位/
商家钜惠图片、运营后台分类字典。距离和资质标签不在本期。

---

## 0. 前置门禁

| ID | 任务 | 依赖 | 交付/验收 |
|---|---|---|---|
| G-01 | 确认两份 CCR、74 契约、71 补丁 v0.3 | 用户 | 文档状态从 DRAFT 改为已批准；无剩余产品待确认 |
| G-02 | 确定分支/worktree 策略 | G-01 + #204 状态 | 推荐：新建 `D:\wt-svc-catalog`，分支 `codex/svc-catalog-20261030`；#204 未合并时 base=`origin/codex/marketing-center-20261030` 并在 PR 注明 stacked on #204；#204 合并后 base 改回 `develop` |
| G-03 | 设计/文案确认 | 用户/设计 | 11 个分类图标、热门页 banner 兜底、服务 tab 图标映射；图标 URL/路径入 manifest |
| G-04 | 开关与部署顺序确认 | G-01 | 沿用 `pet.service.query/command.enabled`、营销开关；新增 `pet.marketing.campaign.category-validation.enabled` 默认 false |
| G-05 | 生产存量活动分类码只读盘点 | 生产 DB 访问 | 跑 71 补丁 §5.4 的只读 SQL；产出需清理活动清单；运营确认责任人与时间 |

## 1. 契约与文档

| ID | 任务 | 依赖 | 交付/验收 |
|---|---|---|---|
| C-01 | 合并 71 补丁为 71 号 **v0.3** | G-01 | §2.3/§3.1/§3.4/§4.2/§10 更新；§6 错误码；版本记录 |
| C-02 | 74 号契约定稿 | G-01、C-01 | 去掉 draft 状态；字段/示例/错误码与实现一致；同步 OpenAPI/HTTP 契约 |
| C-03 | 数据库 Schema 文档 | G-01 | 新增 `docs/03-database/70-Service-Category-Dictionary-Schema-v0.1.sql`；营销 DDL 增量登记；迁移号 service `V29`、marketing `V36`（最终以迁移目录为准） |
| C-04 | 设计登记表更新 | G-03 | `20-设计源登记表-figma-map.md` 更新 `97:7825`/`110:480`/`stores`、`SubpageHeader` 偏差、距离/资质标签偏差、素材 manifest |
| C-05 | 错误码/动作码登记 | C-01 | `SERVICE_CATEGORY_INVALID`、`service.category.read/manage`；`PLACEMENT_LIMIT_EXCEEDED` 复用说明；同步权限测试预期列表 |

## 2. 后端：服务分类字典

| ID | 任务 | 依赖 | 交付/验收 |
|---|---|---|---|
| B-SVC-01 | `service_category` 迁移与审计表 | C-03 | 加 `category_code/parent_id/icon_url/description`；回填 11 个 code；NOT NULL+UNIQUE；`service_category_audit`；空库/存量库迁移通过 |
| B-SVC-02 | 运营端分类 CRUD | B-SVC-01、C-05 | API/DTO/命令、biz、Mapper XML、审计、requestId 幂等、version CAS、`service.category.read/manage`、boot 控制器；错误码 400/404/409/503 |
| B-SVC-03 | C 端分类读接口 | B-SVC-01、C-02 | `GET /c/service-categories`：启用 + 有可用商家 + 城市匹配；按 `sort_no,id`；返回 `storeCount/serviceCount`；匿名、no-store、503 隐藏 |
| B-SVC-04 | 分类精确筛选 | B-SVC-03 | `/c/services?categoryCode=`、`/c/stores?categoryCode=`；keyword 与 categoryCode 组合；未知/停用 400、无供给 200 空页；分页稳定 |
| B-SVC-05 | 商家端分类投影 | B-SVC-01 | `/merchant/service-categories` 增加 `categoryCode/iconUrl/description`，保留 `categoryId`；商家编辑页不受影响 |
| B-SVC-06 | 服务分类测试 | B-SVC-02～05 | 单测/MySQL 集成/架构测试；迁移回填；分类可用性分组查询 EXPLAIN；`check-persistence-style.py` 通过 |

## 3. 后端：评价/订单聚合事实

| ID | 任务 | 依赖 | 交付/验收 |
|---|---|---|---|
| B-STAT-01 | 评价公开评分事实面 | C-02 | `ReviewPublicFactsApi` 批量门店评分：`score_included=1` 全量 `composite_score` 均值、一位小数；`<5` 条返回 score=null；只返回 count/avg；不返回评价内容 |
| B-STAT-02 | 订单公开销量事实面 | C-02 | `ServiceCatalogSalesFactsApi` 批量：门店当月已支付（`paid_at`）有效订单数；服务历史有效订单数；有效=未取消、`refunded_amount=0`（部分/全额退款不计）；自然月 Asia/Shanghai；批量接口、索引与 EXPLAIN 通过 |

## 4. 后端：优质商家聚合读

| ID | 任务 | 依赖 | 交付/验收 |
|---|---|---|---|
| B-CARD-01 | `GET /c/stores/featured` 聚合读 | B-SVC-03/04、B-STAT-01/02 | DTO/分页/排序（评分↓、月售↓、storeId↑）；资格=C 可见 + ≥1 ACTIVE 服务 + 城市/分类；每家店按已售取前 2 个服务（窗口函数一次取）；boot 组合各域 API，不跨模块 SQL；禁止 N+1 |
| B-CARD-02 | 头像/简介/促销投影 | B-CARD-01 | 门店头像签名 URL + 过期时间；`intro`；商家促销/当前活动；无图/无简介返回 null；不返回 PII |
| B-CARD-03 | 降级与契约测试 | B-CARD-01/02 | 评分不可用 `available=false`；销量不可用 `monthlySold/soldCount=null`；整体失败 503；分页/排序/空态/评分 <5/2 个服务/无 PII 测试 |

## 5. 后端：营销补丁

| ID | 任务 | 依赖 | 交付/验收 |
|---|---|---|---|
| B-MKT-01 | `HOT_SERVICE_TOP` 投放位 | C-01 | 迁移 `V36`：position CHECK 加枚举；全平台最多 1 条 ENABLED；启用第二条 409 `PLACEMENT_LIMIT_EXCEEDED`；C 端 placements 返回 0/1；城市范围过滤 |
| B-MKT-02 | 首页内容位 `HOT_SERVICE` | C-01 | target CHECK 加 `HOT_SERVICE`，允许 `targetValue=null`；C 端 home-blocks 返回；前台路由到热门服务页 |
| B-MKT-03 | 商家钜惠图片字段 | C-01 | `StoreCard` 增加 `imageUrl/imageUrlExpiresAt`；门店头像/首张照片优先、活动封面兜底；签名 URL；无图 null |
| B-MKT-04 | 活动分类字典校验 | C-05、B-SVC-02 查询接口 | 开关 `pet.marketing.campaign.category-validation.enabled` 默认 false；开启后创建/编辑校验字典 code；未知/停用/词法非法 400 `SERVICE_CATEGORY_INVALID`；空数组=全部 |
| B-MKT-05 | 营销回归测试 | B-MKT-01～04 | 既有 `HOME_BANNER` 最多 3 条、`HOME_ACTIVITY`/`STORE_LIST_TOP`、内容位、活动发布/报名流程不回归；requestId/审计/版本冲突保持 |

## 6. 小程序 C 端

| ID | 任务 | 依赖 | 交付/验收 |
|---|---|---|---|
| F-C-01 | 模型与解码器 | C-02、C-01 | 分类、优质商家卡、商家钜惠图片、首页内容位 `HOT_SERVICE`、投放位 `HOT_SERVICE_TOP`；严格解码单测 |
| F-C-02 | 服务 tab 全量对齐 `110:480` | F-C-01、B-CARD-01、B-MKT-03 | 头部 logo/城市/搜索/铃铛；11 类图标网格；商家钜惠带图；优质商家列表（评分/月售/2 服务/促销）；无距离/资质标签；空态/失败态 |
| F-C-03 | 热门服务二级页 `97:7825` | F-C-01、B-SVC-03、B-MKT-01/02 | 新路由；`SubpageHeader`；`HOT_SERVICE_TOP` banner；分类卡自动生成；「全部 >」切服务 tab；卡片进 `stores?categoryCode=`；无底部 tab 栏 |
| F-C-04 | 商家列表页改造 | F-C-01、B-SVC-04 | 替换 `sdir-top` 为 `SubpageHeader`；城市/搜索/分类；`categoryCode` 精确筛选；删除硬编码分类数组；保留关键词搜索 |
| F-C-05 | 前端测试与视觉验收 | F-C-02～04 | `npm run typecheck`、单测、`npm run build:weapp`；对照 `97:7825`/`110:480` 截图；二级页头部与既有页面一致；不伪造数据 |

## 7. 运营后台

| ID | 任务 | 依赖 | 交付/验收 |
|---|---|---|---|
| F-A-01 | 服务分类字典页 | B-SVC-02 | 列表/新建/编辑/启停；`iconUrl`（方案 A：粘贴既有后台上传 URL）；描述/排序/版本；requestId、409 错误展示 |
| F-A-02 | 活动分类多选 | B-MKT-04 | 从分类接口取数多选，提交 `categoryCode[]`；`SERVICE_CATEGORY_INVALID` 可读提示；不再手输 code |
| F-A-03 | 投放位/内容位表单 | B-MKT-01/02 | 投放位增加 `HOT_SERVICE_TOP`；最多 1 条错误提示；首页内容位增加 `HOT_SERVICE`，targetValue 可空 |
| F-A-04 | 后台测试 | F-A-01～03 | `npm run typecheck`；构建；权限/错误/版本冲突手测 |

## 8. 设计与素材

| ID | 任务 | 依赖 | 交付/验收 |
|---|---|---|---|
| D-01 | 11 个分类图标 | G-03 | 从 `110:480` 导出/切图；命名与 `categoryCode` 对应；上传既有后台素材或给出 HTTPS URL；无图标占位 |
| D-02 | 热门页/服务 tab 视觉素材 | G-03 | banner 兜底图、分类卡图标、服务 tab 图标；manifest 登记；不新增本地假数据 |

## 9. QA 与验收

| ID | 任务 | 依赖 | 交付/验收 |
|---|---|---|---|
| Q-01 | 契约 smoke | C-01/C-02 | 74 新接口/新字段/错误码；71 投放位/内容位/商家钜惠；OpenAPI 与严格解码一致 |
| Q-02 | 后端集成 | B-SVC、B-STAT、B-CARD、B-MKT | MySQL E2E：分类 CRUD/审计/幂等、C 分类、筛选、优质商家、评分/月售/已售、投放位、内容位、活动分类校验 |
| Q-03 | 前端测试 | F-C、F-A | typecheck/单测/build:weapp；后台 typecheck/build；关键组件/解码器测试 |
| Q-04 | E2E 与回归/性能 | Q-01～03、D-01 | 灌入分类/门店/服务/活动/投放位/内容位；验证服务 tab、热门页、商家列表、首页入口；EXPLAIN/无 N+1；既有营销/门店/服务/订单流程回归；无 PII |

## 10. 上线与数据

| ID | 任务 | 依赖 | 交付/验收 |
|---|---|---|---|
| R-01 | 开关与部署顺序 | G-04、Q-02 | 服务 query/command、营销开关；分类校验开关默认 false；部署顺序：DB→后端→运营后台→小程序 |
| R-02 | 迁移/回填 | B-SVC-01、B-MKT-01/02 | 生产 opt-in 迁移；分类 code 回填；营销 CHECK 变更；回滚脚本/演练 |
| R-03 | 存量活动清理 | G-05、B-MKT-04 | 运营按只读报表修正；清理完成后开启 `category-validation`；记录清理清单 |
| R-04 | 发布与回滚 | R-01～03 | 灰度/验证清单；失败回滚到旧枚举/旧开关；无未披露 Contract/Schema 变化 |

## 11. 推荐顺序与并行

```text
Phase 0  G-01/G-02/G-03/G-04 + D-01/D-02
Phase 1  C-01～C-05 + B-SVC-01
Phase 2  B-SVC-02～06  ┃ B-STAT-01/02  ┃ B-MKT-01～03  ┃ F-A-01
Phase 3  B-CARD-01～03  ┃ B-MKT-04/05  ┃ F-C-01～05  ┃ F-A-02/03
Phase 4  Q-01～04 + R-01～04
```

可并行：

- 服务分类（B-SVC）与评价/订单事实（B-STAT）互不阻塞，可两人并行。
- 营销补丁（B-MKT）在 C-01 合并后即可开工，不依赖 B-CARD。
- 后台分类页（F-A-01）在 B-SVC-02 后即可开工。
- 小程序模型/解码器（F-C-01）可在契约定稿后先做，页面等后端联调。

## 12. 建议 PR 拆分

| PR | 内容 | 依赖 |
|---|---|---|
| PR-1 后端分类 | C-03、B-SVC-01～06、C-05 | G-01/G-02 |
| PR-2 后端聚合读 | B-STAT-01/02、B-CARD-01～03、C-02 | PR-1（分类筛选） |
| PR-3 后端营销补丁 | C-01、B-MKT-01～05 | G-01 |
| PR-4 小程序 | F-C-01～05、D-01/02、C-04 | PR-1～3 |
| PR-5 运营后台 | F-A-01～04 | PR-1、PR-3 |

> 分支：按 G-02。若 #204 未合并，PR 全部 stacked on #204；不要直接往 #204 塞提交。

## 13. 测试矩阵

| 测试 | 覆盖 |
|---|---|
| 单元 | 分类 code/名称/排序校验；可用性规则；折扣/退款口径；排序/前 2 选择；投放位上限；活动分类校验 |
| MySQL 集成 | 迁移回填；分类 CRUD/审计/幂等；C 分类；筛选；优质商家；评分/月售/已售；投放位/内容位；不跨模块 SQL |
| 契约 | 74 字段/错误码；71 增量；严格解码；OpenAPI |
| 前端 | typecheck/单测/build:weapp；后台 typecheck/build |
| E2E | 分类→筛选→门店→服务详情；首页→热门页→分类→门店；服务 tab→优质商家→服务详情；商家钜惠→门店 |
| 性能 | 分类可用性分组查询、优质商家批量查询 EXPLAIN；无 N+1 |
| 安全/权限 | C 匿名读、非法 Bearer 401；后台动作码 403；无 PII |
| 回归 | 既有活动/投放位/内容位/商家报名；门店目录/服务详情/商家服务管理 |

## 14. 风险与未决

1. **分支基线**：#204 未合并时，营销模块/契约在 #204 分支；必须 stacked，不能从 develop 直接切。
2. **生产存量活动分类码**：需运营清理；严格校验开关默认 false，清理完再开。
3. **分类图标来源**：方案 A 依赖既有后台素材入口；运营需知道上传位置；URL 失效前端占位。
4. **门店头像签名 URL**：签名窗口/刷新策略；无头像占位。
5. **聚合性能**：评分/销量/服务批量查询需索引与 EXPLAIN；必要时短 TTL 缓存，但不能改变口径。
6. **统计口径**：已确认“已支付即可、部分/全额退款不计、月售 paid_at、自然月 Asia/Shanghai”，实现时需与订单域核对字段/索引。
7. **设计偏差**：`97:7825` 首页式头部/底部 tab 不照搬；距离、资质标签不做；需在登记表披露。
8. **本地环境**：当前 WSL 无 mysql 客户端，G-05 只读盘点需在有客户端的机器执行。

## 15. Definition of Done

- 两份 CCR/74/71 v0.3 的 Acceptance Criteria 全部满足；
- 相关单测/集成/契约/前端/E2E 通过；架构检查与 `check-persistence-style.py` 通过；
- Contract/Schema/Event/开关变化在 PR 描述披露；
- 设计登记表、错误码/动作码登记同步；
- 迁移 opt-in、分类校验开关默认 false、存量清理完成后再开；
- PR 描述含验证命令/结果、风险与未覆盖项；
- 不把未完成项标为 DONE（尤其生产数据清理、真实 E2E、视觉验收）。
