# C-003 交接：商家详情页·服务项目列表/服务卡片 + 服务详情页（冻结契约切片）

日期：2026-09-22。分支 `codex/m002-service-pages-20260922`（worktree `wt-m-svc-pages`，基于 origin/develop `52a1c45`）。主任务 C-003（阶段切片，不关闭 Issue）。

## 交付

- **设计输入（角色C集中抓取，共享）**：`../C-003-design-inputs/`（INVENTORY/ FETCH-LOG/ assets 11 切图+11 截图/ nodes 结构摘要）。设计版本与 20 号登记表一致（用户端 `2401180846436413285`、商家端 `2401168563353004923`，2026-09-22 在线核对无更新）。
- **领域模块** `src/consumer/service/model.ts`：冻结契约（CCR-W2-API-001 SVC-D1..D5 / 后端 `CServiceController` 投影）的视图类型与严格解码器（9 字段列表项 / 10 字段详情 / 分页信封，exact-key、ID String、`salePrice` 两位小数 String、`IN_STORE|PICKUP_DELIVERY`）；`formatSalePrice`（传输两位小数 → 设计整数价显示）；`designSamples`（店铺头/门店信息/促销/评价/已售N —— 无契约字段，仅视觉）；`fixtureServices`×3（设计样例：专业美容套餐 80.00 / 家庭寄养·天 60.00 / 洗护SPA 128.00）；`PreviewServiceRepository`（**契约 Mock**：不可见门店→空页不区分、未知服务→404 `SERVICE_NOT_FOUND`、事实故障→503 `COMMON_DEPENDENCY_UNAVAILABLE` 失败关闭、快照值拷贝、分页边界钳制）。
- **真实仓储**：`RealServiceRepository`（`consumer/api/repositories.ts` + `page-repository.ts` 工厂）——`GET /api/v1/c/stores/{storeId}/services?page&pageSize`、`GET /api/v1/c/services/{serviceId}`，路径已落在共享客户端白名单内（无需改 `consumer-api.ts`）。
- **页面**（新分包 `consumer/pages/store-services`，402 逻辑画布、`--svc-unit` 运行时单位、`navigationStyle: custom`）：
  - `index` 商家详情页（节点 `690:6660` 一比一）：底图/导航/店铺信息卡/门店信息卡/促销条/用户评价/底部操作栏按设计原文与切图；**团购套餐（服务项目列表）绑契约数据**（Mock 或真实，`preview=1` 切换）；服务卡＝名称/描述/已售（样例）/价格（契约）/预约按钮（显式 not-wired 提示，预约属后续切片）；点卡片进服务详情。
  - `service-detail` 服务详情页（节点 `690:2025`/`690:4205`，与 690:6660 同稿）：同一版式，绑 `GET /c/services/{serviceId}`，团购套餐区渲染**当前服务一张卡**（描述用契约 `description` 字段）；404→"服务不存在或已下架"（SVC-D1b 不可区分语义）。
- **注册**：`app.config.ts` 分包行、`consumerPageSections` 增 `storeServices/serviceDetail → services`、shell 预览与真实入口按钮、`package-check.cjs` 分包清单与产物断言同步。
- **M-002 服务管理页 PLAN**：`../M-002-service-pages/PLAN.md`（含"商品管理 frame=服务项目管理设计源"裁决建议）。

## 状态与加载语义

- phase：loading / ready / load-error（503 或网络，失败关闭+重试）/ expired（会话或工作区失效，真实模式引导去登录）/ invalid（路由参数非雪花 ID）/ missing（仅详情页，404）。
- 旧响应经 `scope.run` + sequence + revision 守卫丢弃（沿用 C-002 模式）；preview 与真实仓储随工作区 revision 重建。

## 测试证据（2026-09-22 本 worktree）

- `npm test`：**150/150 通过**（新增 `consumer/tests/service.test.ts` 12 项：解码器字段集/ID/金额/枚举/分页边界；列表无 description、详情含 description；Mock 404/503 两分不混同、空页不可区分、分页、快照值拷贝、旧响应丢弃；真实仓储路由/查询参数/信封解码/404/503/非法 ID；designSamples 键位契约缺口守护）。
- `npx tsc --noEmit` 通过；`npm run build:weapp` 通过（仅 webpack 资产体积建议告警，先例同样存在）；`npm run check:package` 通过（store-services 分包 1,205,066B < 2MiB 内部预算；总数 3,891,991B < 20MiB）。

## 未实现与如实披露

- **门店列表（服务tab `110:480`/`690:6370`）、门店信息摘要、评价、促销**：依赖 `/c/stores`（角色B草案待批）与评价契约，未绑定字段——占位规划见 [STORE-PLACEHOLDER.md](STORE-PLACEHOLDER.md)；页面该区域为设计样例文案（`designSamples`，先例标注）。
- **预约动作**（卡片/底部"立即预约"、"拨打电话"）：显式 not-wired 提示；预约页（`238:2356` 等 4 状态）属后续切片（依赖 availability/下单 C-004）。
- **VIS 未通过声明**：未做真机/模拟器截图叠图；设计稿 TabBar（首页/订单/核销/消息/我的）与已交付 V1 共享导航（首页/服务/宠友圈/消息/我的）及 PRD §4 四 Tab 约定不一致，已按既有共享导航实施并登记为待人工裁决差异（INVENTORY §3 D1）；服务详情页"单卡 vs 设计三卡"为数据驱动差异，同待 VIS-003 复核。
- 真实模式未联调（后端 `pet.service.query.enabled` 默认关闭、无 SQL 播种服务数据）；Mock 不冒充真实联调。
- 发现并上报：SVC-001 交接声称"10 号 §3.3.1 已同步"，但 `docs/04-api/10-HTTP-API-Contract-v0.4.md` 实际无该节（提交 `50f7ff4` 未含该文件）；OpenAPI11/API07 均引用之。本轮以 CCR 提案 v0.3 + 决定回执 + 后端实现为权威形状，未改权威文档。
