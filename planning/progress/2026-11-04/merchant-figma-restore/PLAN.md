# 商家端小程序 Figma 原型比对还原方案（2026-11-04）

> 输入：Figma《宠物小程序商家端》`Usvn3d6UCVCAlDxou5KAK8`，用户选中 89 个顶层节点。
> 基线：`docs/02-architecture/20-前端技术基线-v0.7.md` §4、`docs/07-testing/21-前端与小程序验收补充-v0.1.md` VIS-001～004、`docs/08-engineering/20-设计源登记表-figma-map.md` §4.10。
> 当前代码：develop `49e8c28b`（worktree `/mnt/d/wt-dev-20261104`）。

## 1. 结论先行

1. **89 个选中节点 = 84 个页面 frame + 5 个悬浮组件/Toast**（`10:2080/2081` 退款成功 Toast、`23:18696/18697` 接单成功 Toast、`149:926` 底部 tab 栏组）。真正需要按页面处理的是 84 帧。
2. **当前不存在“完全没做”的 V1 内页面**：2026-10-19 审计列出的 5 个真缺口（M 端店铺信息、客户列表/详情、两稿数据统计）已在后续 PR 落地（`shop-info`/`customers`/`stats` 路由已存在）。因此这次不是“补页面”，而是**视觉还原 + 已登记偏差回补**。
3. **差距大的根因不是样式没调，而是此前按契约裁决主动裁掉了 Figma 的整块信息架构**：工作台今日营收/KPI/待办预约/营业状态/底部五 tab、评价聚合区、订单/退款页的客户信息等。其中一部分现在已有新契约/数据支撑（营业状态、统计、订单列表、客户、店铺资料），可以回补；另一部分仍被硬规则或已批裁决挡住，不能靠改 CSS 解决。
4. 84 帧按现行口径重新分类：**A 可还原 23 帧、B 语义冲突 40 帧、C2 V1 范围外 21 帧**。A 类是主战场；B 类只能借“视觉外壳”，不能照搬交互/字段；C2 类不实现。

## 2. 84 帧分类与处置

| 页面族 | 代表 frame | 现有路由 | 分类 | 还原策略 |
|---|---|---|---|---|
| 首页/工作台主稿 + 7 变体 | `95:1899`、`141:1568`…`143:4920` | `merchant/pages/workspace` | A | **重点重排**：店招/营业状态/铃铛（店铺信息契约已提供）、今日营收/环比（70号统计 TODAY）、KPI 计数、待办预约（10号 §4.1 订单列表）、8 格快捷入口；底部 tab 待裁决 |
| 首页-休息中 | `141:754` | workspace | B→可回补 | 营业状态字段已落地（27号 §6.4 扩展），可按稿显示“休息中”态 |
| 首页-消息 | `141:1297` | `merchant/pages/messages` | A | 已有独立消息页；工作台铃铛入口可接 |
| 首页-待办预约/待处理/进行中 14 帧 | `141:3423`、`142:3695`、`5:4614`、`9:273` 等 | `order-list`/`order-confirm` | B | 只借列表卡/状态胶囊视觉；不引入商家取消、客户手机号、短码、接单即“已开始服务”等冲突语义 |
| 商品管理 + 添加商品 | `10:5255`、`11:5768` | `services/index`、`services/edit` | A | 登记表 §4.10 复核为 already match；仅需按最新稿复核资产/字阶 |
| 排期管理 | `12:6214` | `schedule/*` 四页 | B | 周模板+60 分钟槽模型与 53号分钟级契约冲突，**不做一比一**；保持家族语言 |
| 员工管理 + 变体 | `12:6591`、`12:7842` | `members/index` | A | 登记表复核 zero-change；按稿复核头像/状态点/标签胶囊 |
| 员工管理-核销记录 | `12:7426` | `staff-verify` | B | 第三方平台聚合+4 位短码冲突 47/48号；不还原 |
| 营销工具 13 帧 | `12:7982` 族 | `marketing/index` | C2 | M 端 PRD 无营销模块；现页为 Contract-71 平台活动报名，**仅 `12:7982` 可做视觉外壳适配**，其余 12 帧不做 |
| 今日营收-数据统计 / 数据统计 | `5:3759`、`143:5192` | `stats/index` | A | 70号读侧已落地；按稿还原概览卡/Top5/趋势/利用率 |
| 客户管理 / 客户详情 | `23:12738`、`23:13478` | `customers/index`、`customers/detail` | A | 69号读侧已落地；按稿还原列表/详情，隐私字段按契约掩码 |
| 店铺评价 4 帧 | `23:14160`、`68:2106`、`68:3178`、`68:4048` | `reviews/index` | A | 已有家族语言；聚合大卡/直方图/筛选 chips 无 56号读侧字段，继续登记偏差 |
| 退款单 11 帧 | `9:779`、`9:891`、`10:2010`、`40:1061` 等 | `refund/index`、`refund/detail` | B | 只借卡面/头带/按钮形态；不引入客户信息、申诉入口、服务流程时间线 |
| 消息通知 3 帧 | `23:19357`、`23:20009`、`23:20084` | `messages/index` | A/B | 主稿 A；两个跳转态由订单/退款页承载，不做独立帧 |
| 订单 tab 族 14 帧 | `23:14926`、`23:18010`、`40:356` 等 | `order-list`、`order-confirm` | B | 只借列表/详情视觉外壳（C 端已有先例）；商家拒单五类 reasonCode、无商家取消、无客户手机号等契约语义不变 |
| 核销 2 帧 | `23:18830`、`23:19210` | `staff-verify` | B | 第三方平台聚合/手机号查询冲突 47/48号；不还原 |
| 我的族 9 帧 | `25:20291`、`25:20684`、资金 4、设置 3 | `shop-info` / 无 | A/C2 | 店铺信息 A 可还原；「我的」hub、资金、安全/通知/语言设为 C2 不做 |
| 底部 tab 组 | `149:926` | 无 | 待裁决 | M 端现为工作台导航、无底部 tab；引入属产品/路由变更，需裁决 |
| Toast 2 组 | `10:2080/2081`、`23:18696/18697` | 退款/接单回执 | 状态 | 可按稿做轻提示；文案按契约（接单后是“待服务”，不能照抄“已开始服务”） |

## 3. 还原方法（按 VIS-001～004）

1. **逐帧取证**：`get_design_context` 取代码/截图/资产；`get_screenshot` 取 402 宽参考图；原始 SVG/图片按节点下载，不得用近似素材。
2. **测量基准**：统一以 402 设计宽为基准，`unit = windowWidth / 402`；头带 86、卡片描边 `#c0ecff`、主蓝 `#5baae8`、浅蓝底 `#f0fbff`、橙 `#ff7a00` 等既有 token 继续沿用。
3. **实现**：优先抽 `src/merchant/components` 共用壳（头带/返回键/卡片/胶囊/按钮/空态），页面只写差异；不改动既有 Button id、aria、挂载条件、路由和请求语义。
4. **验收**：微信开发者工具固定窗口+像素比截图，与 Figma 402 截图叠图；逐项核对尺寸/间距/颜色/字阶/圆角/阴影/裁剪；差异写入登记表偏差，不以“相似度”代替。
5. **每次提交**：只做一个页面族，附截图、叠图、差异清单；Function/Contract 零变化。

## 4. 分批建议

- **P0 工作台首页**（`95:1899`）：店招/营业状态/铃铛、今日营收+环比、KPI 四卡、待办预约、8 格入口、toast 态；底部 tab 若裁决不做则登记偏差。
- **P0 订单/退款视觉外壳**（`23:14926`、`9:779` 族）：卡面、状态胶囊、筛选 tab、按钮形态；契约冲突字段不引入。
- **P1 已实现页视觉复核**：`services`/`services/edit`、`members`、`reviews`、`messages`、`shop-info`、`customers`、`stats`、`marketing`（仅 hub 壳）。
- **P2 家族语言页**：`schedule`、`staff-*`、`aftersale`、`order-confirm`、`refund`（在各自裁决策略内做壳/描边/头带对齐）。
- **不做**：营销工具 12 子页、我的 hub、资金 4 页、设置 3 页、第三方聚合核销、周模板排期。

## 5. 需要产品裁决的点

1. **底部五 tab 是否引入**：Figma 84 帧几乎都带“首页/订单/核销/消息/我的”；现架构是工作台导航。引入需新增 M 端 tabBar + 「我的」页，属导航/路由变更。
2. **工作台数据区口径**：今日营收用 70号 TODAY 统计；KPI 计数可用订单/退款列表 total 组合；待办预约卡缺少客户姓名/服务名字段，是否接受“订单号+时间+金额”的契约内形态。
3. **8 格入口映射**：Figma 为商品管理/排期管理/员工管理/核销记录/营销工具/数据统计/客户管理/店铺评价；现多出订单处理/退款处理/售后管理/店铺信息。是严格 8 格+“更多”，还是保留 12 格。
4. **严格一比一 vs 契约内最大化**：严格一比一需要为 B/C2 帧补契约/改规则（CCR），周期与风险显著更高。

## 6. 风险

- B 类 40 帧照搬会直接违反 45/47/48/49/53/57号契约与 AGENTS.md 硬规则；只能借壳。
- 工作台营收/KPI 依赖多接口聚合，需处理加载失败、口径与 30 分钟确认窗口的一致性。
- 底部 tab 会改变现有工作台 handoff/返回栈，需回归全部 M 端入口与准入态。
- 评价聚合、客户身份、订单服务名等字段仍无读侧契约，补稿前只能登记偏差。

## 7. 本批已落地（2026-11-04）

用户裁决：契约内最大化还原；引入底部五 tab；四个批次全做。

### 已完成

1. **工作台首页 `95:1899`**
   - 头带 86 `#fff6e5`：店招首字占位、店名、账号类型、营业中/休息中胶囊（27号 §6.4）、铃铛→消息。
   - 营收票券：原稿 `paper-grid` / `hero-ticket` / `hero-panel` / `tape` 切图；今日营收 + 较昨日（70号 TODAY）。
   - 四 KPI：待确认/待服务（10号 §4.1 列表 total）、今日订单（70号）、退款（57号待处理 total）。
   - 待办预约：PENDING_CONFIRM 横滑卡，拒绝/接单进入 `order-confirm`。
   - 8 格入口严格按原稿：商品管理/排期管理/员工管理/核销记录/营销工具/数据统计/客户管理/店铺评价。
   - 准入态、受限态、STAFF 态与既有 handoff/Button id 保留。
2. **底部五 tab**（`src/merchant/components/tab-bar`）
   - 原稿 61 高 `#f0fbff` + `#c0ecff` 顶线、中间 53x53 凸起核销键；五等分图标/标签。
   - 切换用 `navigateTo`，目标页在栈内时 `navigateBack`；工作台离开前 `handoffToChild`，失败 `handoffCancelled`。
3. **「我的」tab**（`merchant/pages/mine`，用户批准的新最小枢纽）
   - 店铺卡 + 店铺管理/经营处理/更多；仅聚合既有入口；资金/安全/通知设置不引入。
4. **内部视觉验收夹具**
   - `?preview=1` 使用 `fixtureDeps('allowed')` + `workbench/preview.ts` 样本数据；不接真实接口、不写业务数据。
5. **测试**
   - 新增 `tests/workbench-dashboard.test.ts` 5 例；商家端 195 测试全过；`tsc --noEmit` 通过；`build:weapp` 成功。
   - 微信开发者工具 iPhone 12/13 Pro（390 宽）实拍，证据见 `evidence/`。

### 已登记偏差（本批）

- 原稿「★ 5 评分」无读侧字段 → 显示账号类型（商家主账号/员工账号）。
- 原稿客户姓名/头像/服务名无读侧字段 → 待办卡显示「订单 + 订单号后 6 位」、金额、预约时间；按钮仍进入契约处理页。
- 门店头像 `avatarAssetId` 无展示 URL → 店招首字占位。
- 底部 tab 首次进入的五个根页已接入；子页（订单处理、退款、评价、服务等）保留返回导航，下一批按页接入。
- 原稿纸纹/票券使用原稿切图；390 真机宽度下内容高度比 898 设计帧略长，需滚动查看完整 8 格。

### 下一批

1. 订单列表/详情、退款列表/详情：借 Figma 卡面与筛选 tab 视觉外壳，不引入客户手机号/商家取消/申诉/服务流程时间线。
2. 评价/消息/服务/员工：逐页截图叠图，修字阶/间距/资产。
3. 店铺/客户/统计/营销：按各自 frame 复核；营销只做 hub 外壳。

## 8. 第二批已落地（2026-11-04）

### 订单列表（23:14926 外壳）

- 店招头带 86 `#fff6e5`：店名/首字头像/商家主账号/营业中|休息中胶囊/铃铛（27号 §6.4）。
- 奶油底筛选 tab：待接单/全部/待服务/已完成/退款；前四项走 10号 §4.1 `displayStatus` 只读过滤，
  退款 tab 跳 57号退款处理页；默认待接单，preview 下默认全部（对齐原稿）。
- 白卡 `#c0ecff` 描边 r15.4、金额 `#ff7a00` 右置、状态胶囊、待接单双操作胶囊；
  客户姓名/手机号/服务名/商家取消按钮不渲染（读侧无字段/45号冲突）。
- 底部五 tab 接入，订单 active；`?preview=1` 使用 `previewOrderPage` 样本。

### 订单处理/核销/消息/退款

- `order-confirm`、`staff-verify`、`messages`、`refund/index`、`refund/detail` 全部接入底部五 tab，
  分别 active 订单/核销/消息/订单；页面底部留 96u spacer 或 padding-bottom，避免固定 tab 遮挡。
- 退款列表卡按 Figma 9:779 外壳：头像「退」+「退款申请」+ 橙色金额 + 订单号/时间 + 状态胶囊 + 处理按钮；
  退款详情首卡按 9:891 外壳：状态胶囊 + 订单号 + 退款金额橙色行；买家信息/申请正文/图片/服务时间线/
  申诉按钮不渲染（57号读侧无字段，V1 无商家申诉）。
- `?preview=1` 退款样本：1 笔 PENDING_MERCHANT（268.00），详情可直接进入拒绝/同意面板。

### 验证

- `tsc --noEmit` 通过；商家端 195 测试全过；`build:weapp` 成功。
- 微信开发者工具实拍：订单列表、退款列表/详情、消息、核销；证据见 `evidence/impl-orders.png`、
  `impl-refund-list.png`、`impl-refund-detail.png`、`impl-messages.png`、`impl-verify.png`。

### 本批偏差

- 订单列表 tab 用「待接单/全部/待服务/已完成/退款」替代原稿「全部/待服务/已完成/已取消/退款」：
  保留 30 分钟确认入口，已取消过滤未上 tab（读侧仍可用 displayStatus）。
- 订单卡无客户姓名/头像/服务名，以订单号/预约时间窗/金额呈现。
- 退款列表标题为「退款处理」（契约语义），非原稿「售后单」；售后与退款在 SSOT 是两条链路。
- 退款详情无买家身份/电话/申请正文/图片/时间线/申诉入口（57号读侧与 V1 规则冲突）。
- 消息/核销/订单处理/退款子页保留返回键；底部 tab 已接入，子页返回栈不变。

## 9. 第三/四批已落地（2026-11-04）

### 评价/消息/服务/员工

- `reviews`、`services/index`、`services/edit`、`members` 全部接入底部五 tab（active 首页）；
  `messages` 已在前批接入（active 消息）。
- 评价页申诉半屏打开时隐藏底部 tab，避免模态层被固定 tab 穿透。
- 服务管理 `?preview=1` 实拍：店招头/服务卡/状态标签/价格/开关与 Figma 10:5255 家族一致；
  标题按契约读「服务管理」，非原稿「商品管理」。
- 成员/评价页无后端时显示错误态，tab 与 86 头带布局正常；两页视觉度量沿登记表 §4.10
  复核结论（零改动）。

### 店铺/客户/统计/营销

- `shop-info`（active 我的）、`customers/index`、`customers/detail`、`stats/index`、
  `stats/service-orders`（active 首页）、`marketing`（active 首页）接入底部五 tab。
- 营销页头部从 rpx 96 高改为家族 86 + statusBarHeight，返回键换原稿箭头；
  修复直开无会话时 `consumerApi.scope.capture()` 抛 `NO_CONTEXT` 导致白屏（改用 `scope.current` 守卫）。
- 统计页新增 `?preview=1` 聚合夹具（70号指标/Top5/趋势/利用率），实拍看板可读。
- 店铺/客户页无后端时显示错误态；tab 与头带布局正常。营销页直开显示「请从商家工作台进入」。

### 验证与证据

- `tsc --noEmit` 通过；商家端 195 测试全过；`build:weapp` 成功。
- 证据：`evidence/impl-services.png`、`impl-members.png`、`impl-reviews.png`、`impl-shop-info.png`、
  `impl-customers.png`、`impl-stats.png`、`impl-marketing.png`；原稿 `figma-services-10-5255.png`、
  `figma-reviews-23-14160.png`、`figma-members-12-6591.png`。

### 仍未做/限制

- 店铺/客户/评价/统计的完整真实数据视觉验收需要后端或进一步 preview 夹具；当前只验证了
  头带/卡片壳/tab 布局，未声称逐像素通过。
- 排期四页、核销记录、员工工作台/邀请、售后两页仍按登记表 B 类/无原稿的家族语言实现，
  不在一比一还原范围。
- 营销工具 12 子页、我的资金/安全/通知设置、第三方聚合核销仍为 C2 不实现。

## 10. Preview 夹具补齐与视觉复核（2026-11-04）

### 新增夹具

- `members?preview=1`：3 名成员（启用/停用）+ 1 个待确认邀请，覆盖 54号成员卡。
- `reviews?preview=1`：3 条评价（未申诉/申诉中/低分），覆盖 56号评分/维度/正文/申诉标签。
- `customers?preview=1`：2 位客户（脱敏手机号、消费/订单/客单价、最近到店、常用宠物）；
  `customers/detail?userId=9601&preview=1`：客户详情 + 宠物快照 + 商家备注。
- `shop-info?preview=1`：27号 §6.4 店铺资料表单（店名/地址/电话/营业状态/头像/照片/促销）。
- 所有夹具先注入商户坐标，再使用本地 deps；不接真实接口、不写业务数据。

### 视觉复核结果

- 成员页：成员/邀请 tab、头像首字、核销员标签、启用/停用状态与 Figma 12:6591 家族一致。
- 评价页：星级、三维度、正文、申诉状态/按钮与 23:14160 卡壳一致；买家身份/头像/图片/聚合
  大卡仍不在 56号读侧，不渲染。
- 客户页：补头像首字 + 浅蓝三指标盒，列表/详情更接近 23:12738/23:13478；手机号按契约脱敏
  （原稿为明文），健康字段按 CCR 口径不外显。
- 店铺信息页：资质只读卡 + 店铺事实表单 + 营业状态/头像/照片/促销字段，沿 25:20684 家族语言。
- 服务管理页：8 条 preview 服务，卡面/状态/价格/开关对齐 10:5255。

### 证据

`evidence/impl-members2.png`、`impl-reviews2.png`、`impl-customers2.png`、
`impl-customer-detail.png`、`impl-shop-info2.png`；原稿 `figma-customers-23-12738.png`、
`figma-customer-detail-23-13478.png`、`figma-shop-info-25-20684.png`。

### 验证

- `tsc --noEmit` 通过；完整小程序测试 658 个全过；`build:weapp` 成功。
- 仍不声称逐像素通过：客户头像为占位、评价缺买家身份/媒体、店铺资质不可编辑均按契约登记。

## 11. 数据统计 5:3759 视觉复核（2026-11-04）

- 2×2 指标卡（营业额/订单量/新增客户/客单价 + 环比）、白卡 `#c0ecff` 描边与 Figma 一致。
- 区间 tab 按 70号契约保留「今日/近7天/近30天/自定义」；Figma 为「本周/本月/本年」，
  属聚合口径差异，未改契约语义。
- 营收趋势柱状图、热门服务 Top5 保留；Top5 行补原稿的圆形排名 + 橙色进度条
  （宽度按该服务订单量 / Top1 订单量）。
- 「履约与质量」卡（核销率/拒单率/退款率/复购率/评价均分）为 70号扩展指标，Figma 无此卡，
  保留契约字段。
- 证据：`evidence/figma-stats-5-3759.png`、`impl-stats2.png`。
- 完整 658 测试、`tsc --noEmit`、`build:weapp` 通过。

## 12. 排期/员工/售后子页底部 tab（2026-11-04）

- 排期四页共用 `ScheduleShell`：接入底部五 tab（active 首页），页面 `padding-bottom`
  预留 61u + 安全区；同时把「返回」文字换成家族箭头并修掉全局 button 重置压掉边距的问题。
- 员工工作台、员工邀请确认：接入底部五 tab（active 核销），ScrollView 尾部留 96u spacer。
- 售后列表/详情共用 `MerchantAfterSaleShell`：接入底部五 tab（active 订单）；
  `mas-page` 预留底部 61u + 安全区，详情固定操作栏落在 tab 之上。
- 售后补证/意见半屏与图片预览的 z-index 抬到 40，避免固定 tab 穿透模态层；
  退款拒绝半屏同样抬到 40。
- 至此 M 端 25 个路由全部接入底部五 tab（工作台/订单/核销/消息/我的 五类 active）。
- 证据：`impl-schedule.png`、`impl-staff-workbench.png`、`impl-staff-invitation.png`、
  `impl-aftersale.png`。
- 完整 658 测试、`tsc --noEmit`、`build:weapp` 通过。

## 13. 工作台 95:1899 逐块复核（2026-11-04）

- 头部评分：接入 70号 `StatsOverview.reviewAvg`，显示「★ 4.8 评分」（无数据时「★ -- 评分」）；
  移除原「商家主账号」文案，接近原稿「★ 5 评分」结构。
- 纸张纹理确认：`paper-grid.jpg` 即原稿 `huaban-5913185224 1`（竖条+圆点），
  非网格；Figma 画布上的网格是透明底预览，不是设计素材。
- 当前仍无法在真实页显示完整首页的原因（非前端渲染缺陷）：
  1. 未登录：`consumerApi.scope.current` 为空，工作台直接进入「请先登录后进入工作台」分支；
  2. 当前 dist 是默认构建：`PET_MERCHANT_APPLICATION_ENABLED` 未开启、`PET_C_API_ORIGIN` 为空，
     即使登录，商家准入客户端也会失败关闭；
  3. 本机 18082 只是 Python 文件服务器，Java 后端未启动（Redis 6379 在，MySQL/18081 未见）。
- 真实数据启动配方（沿用既有文档）：
  `NODE_ENV=development PET_C_API_ORIGIN=http://127.0.0.1:18081 PET_ALLOW_LOCAL_HTTP=true PET_MERCHANT_APPLICATION_ENABLED=true PET_PRIVATE_MATERIAL_UPLOAD_ENABLED=true npm run build:weapp`
  并先启动 MySQL/Redis + `mvn -pl pet-boot -am spring-boot:run`，再用真实测试账号完成 C 端登录。
- 视觉验收仍可用 `merchant/pages/workspace/index?preview=1`，不依赖后端。
- 仍属契约缺口的 Figma 视觉块：门店头像照片（只有 `avatarAssetId`，无展示 URL）、
  待办卡的客户姓名/服务名（10号 §4.1 商家订单读侧无字段）。需要 CCR/后端扩展才能真实显示。
- 证据：`evidence/impl-workspace-rating.png`。

## 14. 本地真实数据闭环（2026-11-04，无 Docker）

- 本地链路：Windows MySQL 8.4（WSL 经 13306 转发）+ 本机 Redis 6379 +
  独立易失 auth Redis 16379 + `E2eCoreLoopServer`（Spring Boot 测试域启动器，18081，
  mock 微信/OSS/支付）。使用该分支自行建库、灌 schema 与种子，再由 C 端静默登录进入。
- 前端真实构建（已验证静默登录 `wx.login → /c/auth/attempts → /c/auth/wechat-login` 成功）：
  `NODE_ENV=development PET_C_API_ORIGIN=http://127.0.0.1:18081 PET_ALLOW_LOCAL_HTTP=true PET_MERCHANT_APPLICATION_ENABLED=true PET_PRIVATE_MATERIAL_UPLOAD_ENABLED=true npm run build:weapp`
- 工作台实测（`merchant/pages/workspace/index`）：E2E体验门店、今日营收 480.00、
  待确认 2、待服务 5、今日订单 8、退款 0；待办卡显示订单号/金额/时间/倒计时/拒绝-接单。
- 为打通读侧修的后端装配缺口（工作树未提交）：
  1. `CSessionSecurityConfiguration`：27 §6.4 / 69 / 70 的商家店招、统计、客户路由
     按各自 `*.http.enabled` 开关放行；默认仍关闭。
  2. `CBearerSessionFilter`：上述路由加入 MINIAPP Bearer 保护路径，服务层 store guard
     才能拿到会话主体；否则表现 401。
  3. `MerchantStoreProfileApiImpl`：申请状态 facts 的二次读取放入只读事务，
     修复严格 reader 的 503「application facts require the caller's merchant transaction」。
  4. `E2eCoreLoopServer`：夹具加载 69-Merchant-Customer-Note schema。
  5. 工作台 `enter()`：会话尚未恢复时先等单飞 `consumerSession.launch()`，消除启动竞态。
- 底部 tab handoff（2026-11-04 修）：tab 改为 `redirectTo` 单栈切换；tab bar 在切换前写
  3 秒一次性交接标记，工作台 `onHide` 消费后走 `handoffToTab()`，其 `dispose()` 对 tab
  交接豁免坐标重置。验证 `首页 → 订单 → 我的 → 首页` 四跳后订单/我的/工作台都读真实数据，
  不再出现「请从商家工作台进入」或「工作区已切换」。
- 8 宫格几何：`.mwb-grid-card` top margin 6→11（design unit），渲染 top 645.55px
  （390 宽、statusBar 47）与 Figma 617 design 坐标换算值 645.58px 对齐。
- 证据：`evidence/impl-workspace-real.png`。

## 15. 订单/退款页真实数据复核（2026-11-04）

### 真实数据准备

- 订单：沿用本地 C 端真实下单→沙箱支付→商家接单→核销链路，新增一笔已完成订单
  `111404301120794624`（昨天 12:00 时段）；另建两笔待接单订单（明天 15:00/16:00）。
- 退款：在已完成订单上以 C 端真实 `POST /c/orders/{id}/refund-applications` 创建
  `PENDING_MERCHANT` / `MERCHANT_CONFIRM_AFTER_SERVICE` 申请
  `111404315901521920`，金额 60.00，期限 24 小时；未用前端 mock/preview。
- 备注：已过服务单不能再走服务前自动全额，必须真实服务后申请；本次按 49号窗口验证。

### 订单列表（23:14926）

- 修复会话竞态：`useDidShow` 先 `await consumerSession.launch()`，再读门店资料、评分和列表；
  解决新 tab 首次打开时 header 回落“商家订单/商家主账号”、列表 401/失败。
- 头部：门店名取 27号 store-profile；评分取 70号 `reviewAvg`（无评价显示 `★ -- 评分`）；
  状态胶囊、铃铛保留。
- 卡面：订单号显示后 8 位；预约时间压成“今天/明天 14:00”；金额右置；状态改为纯色文本；
  删除支付时间；待接单的拒绝/接单放进状态行；卡片固定视口宽度并收紧溢出，消除金额被裁切。
- tab：新增四个状态的计数徽标（列表 `total`）；退款计数取 57号待处理 `total`；
  退款 tab 补 `id=mol-tab-refund`。tab 语义仍按 10号，保留“待接单”，未复制 Figma 的
  “待服务仍可接单” B 类冲突。
- 证据：`evidence/impl-orders-real.png`。

### 退款列表/详情（9:779 / 9:891）

- 修复会话竞态：`useMerchantRefund` 的 `useDidShow` 先等 `consumerSession.launch()`。
- 列表卡：订单号后 8 位、今天/明天时间、状态纯色文本；删除顶部“共 N 笔”和卡片底部的
  申请编号/期限长文案（期限保留在详情）；金额/处理按钮/底部 tab 保留。
- 详情：申请编号/订单号只显示尾号，申请时间压成今天/明天；移除独立“刷新详情”按钮
  （did-show 与操作回执已刷新）；拒绝/同意全额操作区保留。
- 证据：`evidence/impl-refund-list-real.png`、`evidence/impl-refund-detail-real.png`。
- 契约缺口（不渲染）：57号 Summary/Detail 不含客户姓名、服务名、申请说明、凭证图片、
  订单服务流程；Figma 的客户信息/退款申请/时间线块需 CCR 扩展，当前仅呈现申请编号、
  原因编码、金额、状态和期限。

### 验证

- `tsc --noEmit`、`build:weapp` 通过；前端 658 测试全过。
- 订单/退款均从本地 18081 真实接口读取；待接单两笔、退款一笔为真实数据库行。
- 未执行同意/拒绝退款决定，保留该申请给后续人工验收（避免消耗唯一退款夹具）。

## 16. 服务页/成员页真实数据复核（2026-11-05）

### 服务管理（10:5255）

- 真实数据：本地服务表已有 1 个 ACTIVE 服务（E2E 到店精细洗护，¥60，on）；页面从
  `/api/v1/merchant/services` 真实读取，未用 preview。
- 修复：`useDidShow` 先等 `consumerSession.launch()`；卡片/计数行固定为视口减两侧 17u，
  解决“+ 添加服务”和右侧开关被裁切；删除非 preview 的“真实接口（后端交付前失败关闭）”
  开发态尾注（列表和编辑页均改为仅 preview 显示）。
- 对齐：头带/返回/标题、共 N 个服务 + 添加服务、88 高白卡/#c0ecff 描边/17 圆角、
  价格橙色、右侧开关与 10:5255 一致；契约状态标签（已上架等）保留，原稿无此标签。
- 证据：`evidence/impl-services-real.png`。

### 成员管理（12:6591）

- 打通真实读侧：启用 `pet.merchant.staff-member.enabled`，并在 `CSessionSecurityConfiguration`
  放行 `/merchant/staff-members/**` 与 `/c/staff/invitations/**`；`CBearerSessionFilter`
  增加对应 Bearer 保护路径；前端 `ConsumerApi` 增加 staff-member 路径白名单与坐标校验。
- 修复：成员页 `useDidShow` 先等会话；把“邀请成员”从被微信胶囊遮挡的 header 移到
  “共 N 名员工”右侧（原稿的计数/添加行），并修掉 ScrollView 内容宽度导致的右侧裁切；
  非 preview 不再显示“真实接口（contract 54…）”尾注；返回工作台改 `reLaunch`，避免 Taro
  复用已 dispose 的工作台 React 树导致占位页。
- 真实数据：通过真实 merchant 邀请 API 创建 1 条 INVITED 邀请；为展示成员卡片，向本地
  fixture DB 插入 1 个 CONFIRMED 邀请 + merchant_member/grant/action（用户 710101，
  138****0101，核销员，ENABLED）。C 端邀请列表/确认路由已打通并验证：同一 owner 确认
  返回 409“owner cannot be bound as staff member”，符合产品规则；非 owner 确认因夹具
  只有一个登录身份未走通。
- 对齐：头带/返回/标题、共 N 名员工 + 邀请成员、头像首字/姓名/角色标签/脱敏手机/
  状态点/停用按钮与 12:6591 结构一致；原稿的星级评分、店长/前台/美容师多角色、
  “在线”文案不在 54号读侧，保留契约字段（核销员/启用中）。
- 证据：`evidence/impl-members-real2.png`、`evidence/impl-members-invitations.png`。

### 工作台复用修复

- 工作台 `enter()` 改为始终先 `consumerSession.launch()`；子页/底部 tab 交接统一走
  `handoffToMerchantPage()`（dispose 豁免坐标重置）；新增 `MerchantWorkspace.reopen()`，
  `useDidShow` 时重新挂回 scope 订阅并 `dashboard.show()`；服务/成员页返回工作台改
  `reLaunch`，确保重新挂载而不是复用已 dispose 的占位页。
- 验证：服务页返回后工作台显示当日真实数据（今日营收/订单/退款），成员页可正常返回。

### 验证

- `tsc --noEmit`、`build:weapp` 通过；前端 658 测试全过；后端
  `CBearerSessionFilterOrderPathsTest` 通过。
- 服务 1 条、成员 1 条、邀请 2 条均为本地真实数据库行；成员卡片为夹具补种（见上）。

## 17. 排期/员工/售后页真实数据复核（2026-11-05）

本轮重启本地 E2E 时发现上一轮复用的数据库只剩空 schema（0 张表），后端从启动起
全部 DB 调用 BadSqlGrammar（后台 task/outbox 轮询刷屏，C 登录 500）。改为不带
`E2E_DATABASE_NAME` 重新建库+灌 schema+种子后恢复正常；启动脚本已留注释说明复用空库的风险。

### 排期工作台/时段/排班/能力（12:6214，B 类）

- 真实数据：`schedule_availability_window` 33 条（昨天/今天/明天 09:00-20:00）、
  `staff_availability_window` 1 条（员工 710303）、能力集合版本 1 + 1 项授权。
- 修复：
  1. 四个排期页 `useDidShow` 先等 `consumerSession.launch()`（深链/复用页先恢复会话）；
     排期首页返回工作台改 `reLaunch`。
  2. ScrollView 宽高：`.sch-body` 作为 native `scroll-view` 没有命中 `view` 的
     `box-sizing` 重置，水平 padding 叠加在 flex 拉伸宽度上（390 视口实测 422.97），
     计数行/卡片/提示文字整体右溢出。`.sch-page` 重置加入 `scroll-view`，
     `.sch-body` 设 `width:100%; box-sizing:border-box`；同类问题一并修
     `.msw-body`、`.msi-body`。
  3. 夹具缺口：`E2eCoreLoopServer` 未加载 53-Schedule-Write-Schema，能力读侧因
     `staff_capability_set` 不存在返回 503；补加载该 schema，并为种子员工写入
     version=1 的 SCHC-2 头（version 0 + 明细会被内核判为 disagree）。
- 对齐：三入口（服务时段管理/员工排班/员工能力）、三态摘要、类型/状态服务端筛选 chips、
  时段卡（服务名/类型/时间/容量/版本/状态）与既有 M 端家族语言一致；原稿的周模板/
  固定 60 分钟槽与 53号分钟级契约冲突，按登记表 B 类不做一比一。
- 证据：`evidence/impl-schedule-real.png`、`impl-schedule-windows-real.png`、
  `impl-schedule-staff-real.png`、`impl-schedule-capabilities-real.png`。

### 员工工作台/邀请确认（无原稿，B 类）

- 修复：
  1. 两页 `useDidShow` 先等会话（员工侧 52/54 读侧按登录手机号匹配，深链必须先恢复）。
  2. 按钮选择器优先级：`.msi-page button` / `.msw-page button` 重置（0,1,1）压过
     `.msi-primary` / `.msi-query-submit` / `.msw-secondary` / `.msw-entry-button`
     等裸类（0,1,0），确认绑定按钮渲染成白底白字、次按钮丢边框。统一改成
     `.msi-page .msi-*` / `.msw-page .msw-*` 复合选择器。
- 真实数据：owner 账号进入员工工作台显示未绑定视图（符合“owner 不是员工”的产品规则），
  “我的邀请记录”显示为当前登录手机号补种的 INVITED 邀请（E2E体验门店/订单核销/去确认）；
  邀请详情显示门店/商家/被邀姓名/权限/待确认。未执行确认：owner 确认会被服务端 409 拒绝，
  属产品规则，保留邀请给人工验收。
- 对齐：头带/标题/返回、邀请卡（门店/商家·姓名·邀请日期/权限/去确认）、未绑定核销入口与
  54号读侧字段一致；原稿无员工工作台帧，按登记表缺稿处理。
- 证据：`evidence/impl-staff-workbench-real.png`、`impl-staff-invitation-real.png`。

### 售后工单/详情（40:1061 / 40:1345 / 40:1500）

- 修复：`useMerchantAfterSale` 的 `useDidShow` 先等 `consumerSession.launch()`，
  深链/复用页不再落到“登录已失效式”失败态。
- 真实数据：E2E 管理链种子创建 1 笔 PENDING 售后（工单 111740192217460736，订单
  111740156763009024，SERVICE_QUALITY，诉求 ¥60.00）；列表 1 笔、详情（问题类型/诉求/
  发起窗口截止/申请说明/双方证据批次）与商家意见面板均从真实接口渲染。
- 契约缺口（不渲染）：51号列表/详情无客户姓名、服务名、宠物信息；原稿的客户/订单
  信息块需 CCR 扩展，当前显示工单号/订单号/金额/类型/状态。
- 证据：`evidence/impl-aftersale-list-real.png`、`impl-aftersale-detail-real.png`、
  `impl-aftersale-sheet-real.png`。

### 验证

- `tsc --noEmit`、`build:weapp` 通过；前端 658 测试全过；后端
  `CBearerSessionFilterOrderPathsTest` 通过；`backend/tools/check-persistence-style.py` PASS。
- 排期 33+1 条、能力集合 1 项、邀请 1 条、售后 1 笔均为本地真实数据库行；
  能力集合头与邀请为夹具补种（见上）。

## 18. 店铺信息/客户/统计页真实数据复核（2026-11-05）

### 店铺信息（25:20684）

- 真实数据：`GET /merchant/store-profile` 返回 E2E体验门店 / chengdu / 营业中；表单从
  真实投影回填（电话按契约不回显明文）。
- 对齐 25:20684：新增顶部身份卡（首字头像 + 店名 + “更换头像”链接；头像仍无展示 URL，
  用登记的店招首字占位），把原表单里的“店铺头像”字段并入身份卡；“保存修改”改为
  通栏主按钮；长标签（促销等级…）不再被 76u 行标签宽度挤成三行。
- 保留契约扩展字段（营业状态/店铺照片/公告/营业时间/促销），原稿的资质证照上传、
  地图定位仍按 CCR 口径只读/不提供；标题沿用产品名“店铺信息”（原稿“门店设置”，登记偏差）。
- 修复会话：`useDidShow` 先等 `consumerSession.launch()`。

### 客户管理/详情（23:12738 / 23:13478）

- 修复真实详情读侧缺陷：`decodeCustomerDetail` 把带 `pets/note` 的详情信封直接交给
  只接受 8 个客户键的严格 `customerRow()`，任何真实详情都判 `INVALID_RESPONSE`；
  改为先重建客户子集再校验（新增回归测试）。详情页现已显示客户统计/宠物/备注。
- 列表卡片改为 `.mcu-page .mcu-card` 复合选择器：`.mcu-page button` 重置曾把按钮卡片的
  白底/描边/圆角/内边距全部剥掉。
- 宠物块按 23:13478 的“头像 + 名字/品种”结构重排（只用 69号 PetView 字段；性别/年龄/
  体重/疫苗/健康按 CCR 隐私口径不显示）；备注仍为可编辑契约扩展。
- 保留当前“昵称/订单尾号”二选一搜索 + 搜索按钮（69号能力），原稿为单一搜索框，登记偏差。
- 修复会话：列表/详情 `useDidShow` 先等会话。

### 数据统计/服务下钻（5:3759 / 143:5192）

- 修复整页失败根因：`isMerchantStatsPath` 白名单缺 `schedule-utilization` 与
  `services/{id}/orders`，客户端在发请求前抛 `INVALID_PATH`，`Promise.all` 使整页进入
  “经营数据读取失败”；补齐两条 GET 路径（新增回归测试）。
- 修复 ScrollView 宽度：`.mst-body` 补 `width:100%; box-sizing:border-box`（同 schedule/
  staff 家族问题），核销率/拒单率/复购率等右侧数值不再被屏幕边缘裁切。
- 修复营收趋势柱高：模板字符串多写了一个 `}`，编译成 `height: 2}px`，所有柱子只剩
  2px min-height；修正后 11-05 的 240.00 显示满高，其余 0 值保持基线。
- 服务下钻：真实 4 笔订单（待服务/售后中）可读；时间从原始 `…Z` 改为北京时间的
  `merchantOrderTimeText`（与订单列表一致）。
- 对齐：2×2 指标卡、履约与质量、热门服务 Top5（排名圆点 + 橙色进度条）、排期利用率、
  营收趋势均为真实 70号数据；区间仍按契约“今日/近7天/近30天/自定义”（原稿本周/本月/本年，
  登记偏差），评价均分为 `--`（本店暂无评价）。

### 验证

- `tsc --noEmit`、`build:weapp` 通过；前端 660 测试全过（新增 2 条回归）；
  后端本轮无改动。
- 店铺资料/客户列表/客户详情/统计概览/Top5/趋势/排期利用率/服务下钻全部来自本地
  18081 真实接口；未执行店铺保存与客户备注保存（保留夹具给人工验收）。
- 证据：`evidence/impl-shop-info-real.png`、`impl-shop-info-real-bottom.png`、
  `impl-customers-real.png`、`impl-customer-detail-real.png`、`impl-stats-real.png`、
  `impl-stats-trend-real.png`、`impl-stats-service-orders-real.png`。

## 19. 评价/消息/服务编辑页真实数据复核（2026-11-06）

### 店铺评价（23:14160 族）

- 夹具：本地 `review`/`review_appeal` 补 3 条真实投影（5.0 无申诉、2.2 申诉处理中、
  4.0 不计分 + 申诉未成立），经真实 `GET /merchant/reviews` 读取。
- 页面：星级/公开展示/日期/三维分/正文/申诉标签与「申诉」按钮全部渲染；申诉半屏
  （1~1000 字、提交申诉/取消、遮罩隐藏底部 tab）正常。未提交申诉。
- 修复会话：`useDidShow` 先等 `consumerSession.launch()`。
- 契约缺口（不渲染）：原稿的评分聚合大卡/星级直方图/总评价·待回复·好评率·申诉中统计/
  筛选 chips/买家身份/图片/有帮助·回复均无 56号读侧字段（登记偏差）。

### 消息通知（23:19357）

- 夹具：本地 `notification` 补 3 条 USER 收件箱消息（订单已确认未读、服务已完成已读、
  平台服务时间调整未读），经真实 `GET /c/notifications` 读取。
- 修复根因：消息页在工作区坐标仍为 merchant 时调用 `/api/v1/c/notifications*`，
  `ConsumerApi.request` 的 consumer-only 守卫在发请求前抛 `WORKSPACE_PATH_MISMATCH`
  （页面只显示“消息读取失败”）。按员工邀请先例增加 `isNotificationInboxPath`
  豁免（收件箱按会话用户定位，不携带商户坐标），列表/详情/已读三条路径生效。
- 修复样式：列表行是 Button，`.mmsg-card` 被 `.mmsg-page button` 重置剥掉白底/圆角/内边距；
  详情页的“查看/返回”按钮同样补复合选择器。
- 修复时间：`createdAt/readAt` 原样显示 ISO+Z；改为北京时间 `merchantOrderTimeText`，
  列表与详情「已读于」一致。打开消息会真实调用已读接口，返回列表未读点消失。
- 标签页（交易通知/系统消息）仍按 CCR §2 合并为单列表（登记偏差）。

### 服务编辑/新增（11:5768）

- 修复根因：真实 `GET /merchant/service-categories` 返回 70号字典行（含
  `categoryCode/iconUrl/description`），`decodeCategory` 的严格三键校验把整个编辑页
  判为 `INVALID_RESPONSE`（页面显示“操作未确认成功…”且不发详情请求）。改为接受
  文档化扩展键并新增回归断言。
- 修复按钮优先级：`.medit-action` / `.medit-action-secondary` / `.medit-banner-action`
  / `.medit-cover` 被 `.msvc-page button` 重置压掉背景/描边/圆角——“提交审核”白底白字
  不可见，“保存草稿”“下架服务”退化成纯文本、封面框丢失描边。统一改为 `.msvc-page .medit-*`
  复合选择器。
- 真实数据：编辑页读取 ACTIVE 服务 710401（名称/¥60/60 分钟/到店型/需核销/分类/宠物类型/
  说明），按产品规则显示“上架中的服务为只读展示 + 下架服务”；新增页展示完整表单与
  「保存草稿 / 提交审核」双操作。
- 契约缺口：封面只有 `coverAssetId`、无展示 URL，头图显示占位文案（登记偏差）；
  本轮未实际创建/提交服务（需真实封面资产），保留夹具给人工验收。
- 修复会话：编辑页 `useDidShow` 先等 `consumerSession.launch()`。

### 验证

- `tsc --noEmit`、`build:weapp` 通过；前端 660 测试全过（新增评价字典/消息路径断言）；
  后端本轮无改动。
- 证据：`evidence/impl-reviews-real.png`、`impl-reviews-appeal-sheet-real.png`、
  `impl-messages-real.png`、`impl-message-detail-real.png`、`impl-service-edit-real.png`、
  `impl-service-edit-form-real.png`、`impl-service-add-real.png`。

## 20. 订单处理/核销/营销/我的收尾复核（2026-11-06）

### 订单处理（23:14926 族 B 类外壳）

- 真实数据：C 端真实下单+沙箱支付一笔 PENDING_CONFIRM 订单 `112003538795720704`
  （明天 14:00，¥60），工作台待办卡出现「订单 720705 / 4小时后 / 拒绝·接单」。
- 修复：`.moc-action` / `.moc-reason` / `.moc-submit` / `.moc-back-workbench` 被
  `.moc-page button` 重置剥掉背景/描边/圆角（确认接单白底白字不可见、接单/拒单单选退化成
  纯文本）；改复合选择器。`.moc-body` 补 `width:100%; box-sizing:border-box` 并加入
  `scroll-view` 重置；删除真实模式下的开发态尾注。
- 补 `useDidShow` 会话恢复与返回工作台 `reLaunch`（见下）。
- 页面验证：接单态（接单高亮、店内备注、确认接单）/拒单态（五类原因、必填说明、确认拒单）
  均正常；未提交决定，保留该待接单订单给人工验收。

### 订单核销（48 K2 v0.3）

- 真实闭环：C 端为 PENDING_SERVICE 订单 `111740181568122880` 申请核销码，商家端页面
  输入订单号+核销码提交，真实返回核销成功（尝试/核销编号、北京时间 09:56:12、订单版本 3），
  订单转为 COMPLETED，工作台待服务数由 3 降为 2。
- 修复：`.msv-submit` / `.msv-back-workbench` 被按钮重置压掉样式；`.msv-body` 宽度；
  删除真实模式尾注；补会话恢复与返回工作台 `reLaunch`。
- 原稿 23:18830/23:19210 的第三方平台聚合/手机号查询仍按登记表 B 类不还原。

### 营销活动（12:7982 外壳，Contract 71）

- 本地运行态启用营销面：重启 `E2eCoreLoopServer`（kill -9 保留 DB，`E2E_DATABASE_NAME`
  复用）并加 `pet.marketing.enabled/admin/merchant/c.http`、asset base 与
  `pet.coupon.campaign.enabled`；`GET /merchant/marketing/*` 由 404 转为 200。
- 夹具：3 个平台/商家活动（秋季洗护节已通过、冬季寄养招募可报名、双十一洗护套餐草稿）
  + 1 条 APPROVED 报名；随后通过真实 `POST .../enrollments` 为冬季活动提交报名
  （SUBMITTED），页面即时显示「报名状态：已提交」与我的报名「撤回报名」。
- 修复：枚举状态原样显示英文（报名状态：APPROVED / 状态：DRAFT）改为中文映射；活动日期
  改为北京时间日期；tab 补 `mmk-tab-open/mine/campaigns` id；补会话恢复与返回 `reLaunch`。
- 对齐：三 tab（可报名/我的报名/我的活动）、活动卡（标题/副标题/报名期/状态）、报名表单
  （角标/标题/说明）、商家活动草稿表单与操作按钮；原稿其余 12 个营销子页仍为 C2 不做。

### 我的（用户批准的最小枢纽）

- 真实数据：店铺卡（首字/店名/商家主账号/营业中）+ 店铺管理（店铺信息/营销活动/成员管理）
  + 经营处理（订单/退款/售后）+ 更多（评价/客户/统计/员工工作台）+ 返回用户端；
  无新增改动，作为本轮收尾证据留档。原稿的我的 hub/资金/设置帧仍为 C2 不做。

### 返回导航修复（跨页）

- 实测 `navigateBack` 返回工作台会命中 Taro 已 dispose 的 React 树，工作台停留在
  占位（店名回落“商家工作台”、KPI/待办为 —）且不再发请求。订单处理/核销/营销以及
  退款/售后列表壳的返回统一改为 `reLaunch` 工作台（详情→列表保留 `navigateBack`），
  返回后工作台重新准入并显示真实数据。

### 验证

- `tsc --noEmit`、`build:weapp` 通过；前端 660 测试全过。
- 本轮后端仅本地运行态开关变化（营销面），未改后端源码；数据库复用上一轮夹具。
- 证据：`evidence/impl-order-confirm-real.png`、`impl-order-confirm-reject-real.png`、
  `impl-staff-verify-real.png`、`impl-staff-verify-receipt-real.png`、
  `impl-marketing-open-real.png`、`impl-marketing-mine-real.png`、
  `impl-marketing-campaigns-real.png`、`impl-mine-real.png`、`impl-mine-real-bottom.png`。
