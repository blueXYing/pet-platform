# M-002/C-003 实施轮交接：服务管理页 + 门店列表/门店契约绑定（HANDOFF）

日期：2026-09-22。分支 `codex/m002-service-pages-20260922`（worktree `wt-m-svc-pages`，基于本分支第一阶段 4 commits：e84c410/4e52b04/8dd2f5b/42e27b8）。
设计源裁决与裁剪依据：用户 2026-09-22 裁决（见 ../C-003-design-inputs/INVENTORY.md §4/§6）；导航依据：本目录 [NAVIGATION-BASIS.md](NAVIGATION-BASIS.md)。**不 push 不建 PR**——与后端 Mock 契约联动，统一联调后再 PR。

## 交付清单

### C1 商家导航依据（文档）

- `NAVIGATION-BASIS.md`：商家端 V1 无 TabBar（PRD 无导航章节、E1-E9 证据链）；服务管理挂接 = 工作台（MS-3）ALLOWED 态入口 → 列表/编辑页 → navigateBack 返回；LIMITED 不给入口；原稿 TabBar 差异登记（N1-N3）。结论：无需暂停项——既有批准导航已覆盖挂接，缺口仅为"工作台内二级导航层级"PRD 未细化，沿用工作台头部先例。

### C2 M-002 服务管理页（设计源 10:5255/11:5768 + V1 裁剪 + 补齐）

- **契约 Mock 层** `src/merchant/services/model.ts`：严格解码器（exact-key、ID/金额两位 String、五状态、REJECTED⇒驳回决定 10-500 意见、划线价≥售价[整数分比较]、ALL 互斥）；`missingSubmitFields`/`draftInputProblems`（提交必填=名称/分类/履约/价格/时长/封面/适用宠物类型；草稿宽松）；`PreviewServiceManageRepository` 严格匹配契约语义：状态机守卫（409 `SERVICE_STATE_NOT_ALLOWED`：编辑 ACTIVE/REVIEWING、offline 非 ACTIVE、不可经营场景全部写命令）、expectedVersion CAS（409 `COMMON_CONFLICT`）、提交缺必填（400 `SERVICE_REVIEW_REASON_REQUIRED`，注：v0.1 草案该码为运营驳回缺意见码，本 Mock 按用户 2026-09-22 批准口径用于提交校验，**A 侧定稿时核对**）、slot 级 requestId 幂等（同参重放回执不重复加版本、异参 `PENDING_WRITE_CHANGED`）、503 事实故障失败关闭、快照值拷贝。`coverUrl`（可空 String）按指示 Mock 并标注"待 A 侧契约定稿核对"。
- **真实仓储** `src/merchant/services/repository.ts`：六路由接线（GET/POST `/api/v1/merchant/services`、GET/PUT `/{serviceId}`、POST `/{id}/online|offline`、GET `/merchant/service-categories`），写命令经 `ConsumerApi.write` 落 X-Request-Id 幂等 journal；online/offline 以 query 携带目标 merchantId/storeId（共享层新增 `RequestSpec.query`）。**契约未冻结、后端未交付：真实模式失败关闭，不冒充联调。**
- **共享层**（C-End 唯一 Writer 名义，本轮由角色C执行）：`consumer-api.ts` 路径白名单加服务管理路由族 + 类目读豁免目标匹配；merchant 坐标门禁扩展 query 目标；`request.ts`/`http-adapter.ts` 支持 `query`；新增 `anonymousRequest`（见 C3）。
- **页面**（merchant 分包新增，`merchant/pages/services/`，402 画布 `--msvc-unit`）：
  - `index` 列表（10:5255 一比一布局：#FFF6E5 头区/计数行/368×88 r17 白卡描边 #C0ECFF/价格 #FF7A00/47×26 开关）+ 五状态标签 + 驳回可见；开关语义 ON=提交审核 OFF=下架（二次确认）；点击卡片进编辑/详情；分页"加载更多"；状态（loading/entry[非商家坐标]/load-error/empty）。
  - `edit` 新增/编辑（11:5768 表单语言：#F9FAFB r11.6 输入、#5BAAE8 选中 pill、主按钮）：字段=服务名称(2-50)/价格/划线价/分类(数据驱动11类)/适用宠物类型(猫狗异宠全部,多选互斥)/履约方式(两值)/时长/封面(Mock可选图,真实显式不接通)/核销标志(默认是)/服务说明(0-1000)+补齐人员要求(0-200)/售后说明(0-500)/备注(0-500)；REJECTED 显驳回原因横幅；ACTIVE/REVIEWING 只读+下架引导；动作=保存草稿(POST/PUT)/提交审核(存+online→REVIEWING)/下架。
  - 工作台入口：`merchant/pages/workspace` ALLOWED 卡片新增"服务管理"按钮（LIMITED/DENIED 不展示）。
  - 切图：`assets/` = 商家端节点级切图 10:5302（返回箭头）/10:5333（加号）/11:5674（相机），manifest 登记，源自 C-003-design-inputs（FETCH-LOG 第 9-11 行补抓，设计版本复核无变化）。
- **消息中心兼容**：`routeForNotification` 扩展 SERVICE_REVIEWED/SERVICE → `/merchant/pages/services/index`（白名单机制，目标页自校验坐标/准入）；跳转按钮文案按类型（查看服务管理）。**通知下发/商家收件箱归角色E，未冒充完成。**

### C3 C-003 门店页（/c/stores 契约 Mock 先行）

- **契约 Mock 层** `src/consumer/store/model.ts`：九字段解码器（含坐标 ≤7 位小数/±180/±90、cityCode 词法、STR-D4 merchantId,storeId 数值升序校验）；`PreviewStoreRepository`：开放城市目录（现仅成都；未知 city 400、缺省=全部开放城市）、不可见门店列表隐藏且详情 404 不可区分（`STORE_NOT_FOUND`）、503 失败关闭、空城市=200 空页。
- **真实仓储** `src/consumer/store/repository.ts`：经 `ConsumerApi.anonymousRequest`（匿名可浏览，用户裁决口径）GET `/api/v1/c/stores`、`/c/stores/{storeId}`；城市目录注入（页面复用已批 `/c/merchant-application-cities`，STR-D3 同一 boot 目录；401 时页面回退缺省城市不阻断匿名列表）。
- **门店列表页** `store-services/stores`（110:480 裁剪版）：城市按钮（目录驱动 ActionSheet）/全部服务标题/分类宫格（11 类文字卡，点击显式不筛选）/门店卡（九字段，无评分月售距离虚构）/分页/空态/503 重试；卡片点击 → 商家详情页。
- **门店详情契约绑定**：`store-services/index` 升级——先查 `/c/stores/{storeId}`，成功则店名/地址/掩码电话绑契约（评分月售距离标签简介营业时间促销评价仍为已登记设计样例），404→"门店不存在或不可访问"（PRD §5.1.14）+返回门店列表，503→重试。
- **导航打通**：门店列表 → 商家详情页(店内服务列表) → 服务详情 → 预约按钮（既有显式 not-wired 占位，排期/订单不在本轮）。

### 测试与构建（2026-09-22 本 worktree）

- `npm test`：**162/162 通过**（既有 150 项不破坏；新增 12 项：`merchant/tests/service-manage.test.ts` 7 项——解码器形状/不变量、Mock 分页快照隔离、状态机+CAS+幂等重放/异参锁、提交校验/下架、不可经营 409、校验器、真实仓储六路由+journal+坐标门禁；`consumer/tests/store.test.ts` 4 项——九字段/顺序/坐标解码、城市目录分页 400、404 不可区分/503/空页、真实匿名读 query 形状；`messages.test.ts` +1 SERVICE_REVIEWED 白名单跳转）。
- `npx tsc --noEmit` 通过；`npm run build:weapp` 通过（仅既有 webpack 体积建议告警）；`npm run check:package` 通过（merchant 92,177B、store-services 1,237,554B < 2MiB 内部预算；总 3,992,621B < 20MiB；断言已更新至新页面清单）。
- 修复过程中发现并修正：划线价与售价比较从字符串比较改为整数分比较（"9.00">"10.00" 词法序错误）。

## Mock 与真实联调边界声明

- 全部页面 `preview=1` 走契约 Mock（无网络/无会话/无持久化）；真实模式已按当前契约依据接线但**后端两切片（角色A服务写入、角色B门店读）均未交付**，真实请求会失败关闭（404/401/503 呈现），**不宣称真实联调**。
- Mock 形状以两份 v0.2 原则批准草案为准（本 worktree 可见的 v0.1 文本 + 用户裁决要点）；A/B 侧权威契约定稿后，解码器与 Mock 需按定稿核对（重点：`SERVICE_REVIEW_REASON_REQUIRED` 的适用面、商家读 DTO 字段名、coverUrl 命名、online/offline 的目标参数位置）。
- 封面：真实模式依赖 B 侧 SERVICE_COVER 管线，未接通前显式提示、无法选择真实封面；Mock 模式可选本地图（不落契约）。
- 消息中心仅做跳转兼容；SERVICE_REVIEWED 通知的下发与商家收件箱归角色E。

## 未实现与如实披露

- **VIS 未通过声明**：未做真机/模拟器截图叠图；全部视觉差异按 INVENTORY §6（VIS-004 M1-M12/S1-S8）登记，待叠图复核与人工裁决。
- 商家端 TabBar 不实现（NAVIGATION-BASIS N1）；门店列表的评分/月售/距离/热门服务/促销不实现不占位（S1/S2）；分类筛选/关键词/排序延后（STR-D4/D7）。
- 服务管理页的销售数据/预约量展示（PRD §5.5"查看销量预约量"）无契约字段，未实现未登记占位（列表仅状态/价格/名称）。
- 真机走查、模拟器人工验收未执行（等统一联调轮）。
