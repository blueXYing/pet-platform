# M-002 服务页窗口级 E2E 取证（WINDOW-E2E-EVIDENCE）

日期：2026-09-24（本地会话）。分支 `codex/e2e-window-20260923`（基于 develop 639b61a）。
取证人：Codex 窗口级验收（恢复执行轮）。工具：微信开发者工具 CLI（`wechatide -c ZCode`，simulator_screenshot / automation_element_action / automation_evaluate / automation_wx_api / get_simulator_network / debug_clear_cache / simulator_refresh）。

> **2026-09-24 修复轮更新（本节置顶）**：F1/F2 已在本分支修复并通过窗口补全走查（§8、§9）；
> 走查中新发现后端缺陷 **F3**（create 草稿链 NPE/NOT-NULL，§8.3），未修（禁止项），已登记。
> §1–§7 为修复轮之前的原始取证，保留不动；§8 起为修复轮记录。

## 0. 证据分层声明（先读）

| 层 | 定义 | 本轮覆盖 |
|----|------|----------|
| 窗口内操作 | 用户在模拟器窗口内可见、可点击的页面行为，元素断言 + 截图 | C1 匿名矩阵全四步；C2 登录态 shell→工作台→服务管理入口；C3 消息中心→详情→查看服务跳转 |
| HTTP 种子 + 窗口只读 | 种子链/写链经真实 HTTP（M002IntegrationServer 18081）完成，窗口侧仅读取呈现 | 商家入驻→签署→建店→服务上架种子链；服务草稿→封面→提交审核写链；运营审核决议 |
| 真机 | 手机实机预览 | **未做**（本轮无真机；不声称） |

测试域缝隙（如实披露，均为测试域动作，不进产品代码）：

1. **会话注入**：消费者登录态通过 `wx.setStorageSync('http://127.0.0.1:18081:pet.c.session.v1', grant)` 注入种子链的登录凭证（键格式来自 `src/shared/consumer-runtime.ts` 的 `${C_API_ORIGIN}:${SESSION_KEY}`，grant 结构来自 `src/shared/consumer-api.ts`）。凭证本身是真实 HTTP `POST /api/v1/c/auth/wechat-login`（FixedWechatProvider 缝隙 `ok:<openid>`/`phone:<phone>`）签发的，窗口内经 `GET /api/v1/c/auth/session` 服务端校验通过后才呈现登录态。
2. **HTTP 层写链**：服务"新建草稿→提交审核"因发现 F1/F2（见 §3）无法在窗口 UI 内完成，改为等价真实 HTTP 调用（同一仓储六路由契约形状）完成，窗口侧以消息中心消费其结果。
3. **showModal 双通道取证**：预约登录门先以 `automation_wx_api mock` 记录弹窗参数（行为断言），再恢复真实 API 点击截取真实弹窗画面（视觉取证）。
4. **模拟器刷新重置**：真实弹窗截图后无法以选择器关闭系统弹窗，用 `simulator_refresh` 重置页面（测试域动作）。
5. **服务器重启与二次播种**：见 §5 环境事实。

## 1. 构建与环境

- 构建命令（真实模式）：`NODE_ENV=development PET_C_API_ORIGIN=http://127.0.0.1:18081 PET_ALLOW_LOCAL_HTTP=true PET_MERCHANT_APPLICATION_ENABLED=true PET_PRIVATE_MATERIAL_UPLOAD_ENABLED=true npm run build:weapp`。
  - 注意：**缺 `PET_MERCHANT_APPLICATION_ENABLED=true` 时商家准入客户端失败关闭**（`src/merchant/admission-runtime.ts` 直接 `ADMISSION_NOT_CONNECTED`），本轮曾因此重构建一次；此为本地验收操作失误后修正，非产品缺陷。
- 每次换构建产物后执行 `debug_clear_cache --action cleanCompileCache` + `simulator_refresh`（DevTools 编译缓存经验），存储键跨刷新保留。
- 后端：`M002IntegrationServer`（pet-boot test 源，JDK21，MySQL 127.0.0.1:33452 专用库 + Redis 127.0.0.1:16383），`pet.service.review.notifications-enabled=true` 服务器启动参数已默认开启（源码第 216 行附近），无需环境变量补开。
- 种子：`D:/Temp/m002-ci/seed.mjs`（恢复轮重写版）完整真实链：登录→入驻申请→提交→运营认领+人证+APPROVE→签署→会员→类目→服务草稿→online→运营 APPROVE→匿名四读验证→收件箱轮询 SERVICE_REVIEWED。链2（`seed2.mjs`，变化主体证件号避免与链1主体占位冲突）为本轮走查主链：

| 标识 | 值 |
|------|----|
| userId | 96422039895756800（登录态 138****5713） |
| merchantId / storeId | 96422040029974529 / 96422040575234048 |
| 种子服务（APPROVE→ACTIVE） | 96422040873029632《窗口验收·基础洗护》¥128 |
| 走查写链服务（草稿→REVIEWING→REJECT） | 96423284563857408《窗口验收·次日洗护加购》¥88 |

## 2. C1 消费者匿名矩阵（STR-D8：所有用户浏览；登录只门控动作）

前置：清空 `pet.c.session.v1` 等会话存储（纯匿名），真实模式（无 `preview=1`）。
截图目录：`window-shots/`；网络证据：`window-shots/c1-network-evidence.txt`。

| 步骤 | 元素断言（automation_element_action 实测文本） | 截图 | 网络取证（Authorization 全部缺失） |
|------|------|------|------|
| 门店列表 | `#sdir-store-96422040575234048` 文本="窗景宠物生活馆窗景宠物生活馆锦江区Window路1号"（九字段投影，phoneMasked=null 不渲染） | c1-01 | GET /api/v1/c/stores?page=1&pageSize=20 → 200，两条链门店均在目录 |
| 门店详情（任务A） | 页头 `.svc-store-name`="窗景宠物生活馆…"；`#svc-row-96422040873029632`="窗口验收·基础洗护¥128" | c1-02 | GET /api/v1/c/stores/96422040575234048 → 200；GET .../services → 200 |
| 服务详情（任务A） | `#svcd-row-96422040873029632`="窗口验收·基础洗护窗口级验收种子服务：含洗护、吹干、基础梳理¥128"（名称/说明/价格全契约绑定） | c1-03 | GET /api/v1/c/services/96422040873029632 → 200，响应含 cover 投影（coverAssetId 590000000000000201 + 每次读取重签的 coverUrl，T+1h 过期） |
| 立即预约（动作门控） | mock 记录：`{"title":"请先登录","content":"立即预约需要先登录，是否前往登录？","confirmText":"去登录","cancelText":"暂不"}`；真实弹窗截图视觉核验文字一致 | c1-04 | 无新增网络（纯客户端门） |

**任务A结论（详情页匿名浏览修正，本 PR 代码）**：门店详情页与服务详情页在无登录态下可完整浏览（此前这两页 `workspace !== 'consumer'` 即判过期）；`立即预约/拨打电话`动作保留登录引导（弹窗四参数如上）；登录/登出（revision 变化）不再使详情页过期，仅按新上下文重读公共目录。`detailReadAllowed`/`detailActionGate` 纯函数由 `src/consumer/tests/browse-gate.test.ts` 三组用例钉住。

边界如实记录：服务详情页的封面目前是**契约数据层事实**（HTTP 响应含 cover 三字段，解码器消费），设计稿封面视觉位尚未绑定图片元素；种子 coverUrl 指向 `cover.example.invalid`（CI 固定签名桩，本就不可显示）。

## 3. C2 商家侧走查（登录注入缝隙，见 §0.1）

| 步骤 | 结果 | 证据 |
|------|------|------|
| 注入会话→shell | `#c-session-state`="已登录 · 138****5713"；GET /api/v1/c/auth/session → 200 | c2-01 + c2c3-network-evidence.txt 首条 |
| 商家工作台 | `.workbench-body`="工作台已就绪…merchant.service.manage…当前工作区：merchant · 96422040029974529"；memberships OWNER + admission ALLOWED 均真实 200 | c2-02 |
| 工作台→服务管理 | 页面可达但呈**entry 态**："请从商家工作台进入服务管理。"（发现 F1，见下） | c2-03 |
| 新建草稿→提交审核 | 窗口 UI 内不可达（F1+F2 叠加），以 HTTP 层等价完成：POST 建草稿(cover 省略)→201 DRAFT v0；PUT 补 coverAssetId→200 v1；POST online→200 **REVIEWING** v2 | §0.2 缝隙；c2c3-network-evidence.txt 之外的 HTTP 日志见本表 |

### F1（新发现，集成缺口）：真实模式工作台→服务管理导航死锁

- 现象：工作台 ALLOWED 卡片点「服务管理」（navigateTo）后，服务管理列表页恒为 entry 态（"请从商家工作台进入服务管理。"）。
- 根因：`src/merchant/pages/workspace/index.tsx` 的 `useDidHide(() => controller.leave())` 在页面隐藏（含 navigateTo 离开）时把 `consumerApi.scope` 重置为 consumer 坐标（`MerchantWorkspace.leave()`，MINI-003 语义：每次进入工作台都重新准入）；而 `merchant/pages/services/index.tsx` 的 `useDidShow` 门禁要求 scope 处于 merchant 坐标。导航离开动作本身清掉了目标页所需的坐标，且返回工作台会重新准入再次离开又被清——真实模式下列表/编辑页**经由 UI 永远不可达**。preview 模式走 fixture scope 不受影响，故 M-002 切片（PR#75）Mock 验收未暴露。
- 影响面：`merchant/pages/services/index`、`services/edit` 真实模式列表/编辑/草稿/提交全链；C3 通知跳转「查看服务」落到同一 entry 态（跳转本身成立，见 §4）。
- 修复方向（不在本 PR 越权处理，登记待裁决）：leave() 对"工作台自身发起的子页导航"保留坐标（如以导航意图标记 ownedRevision 让渡），或服务管理页自行重放准入。涉 MINI-003/M-002 两片契约语义，须 C 端所有者裁决。

### F2（新发现，前后端形状不匹配）：真实草稿保存必 400

- 现象：`POST /api/v1/merchant/services` 带 `coverAssetId: ""` → 400 COMMON_INVALID_ARGUMENT；省略该字段 → 201 DRAFT。
- 根因：`src/merchant/services/repository.ts` 的 `toBody` 对空封面固定发送 `coverAssetId: input.coverAssetId ?? ''`（Mock 层接受空串），真实 A 侧按 ID 词法拒绝空串。叠加编辑页真实模式 `pickCover` 显式"SERVICE_COVER 管线未接通"（HANDOFF 既定披露），窗口内既存不了无封面草稿、也选不了真实封面——服务写链在窗口 UI 内三重阻断。
- 修复方向：toBody 对空 coverAssetId 省略字段（与 staffRequirement 等文本可选字段同策略），或 A 侧接受空串语义。属 C/A 两侧契约核对项。

## 4. C3 运营审核→通知→商家消息中心（全链真实）

1. HTTP：`POST /api/v1/admin/services/96423284563857408/decision`（REJECT + 意见"窗口走查驳回样例：说明需补充服务时长与上门范围描述"，AdminToken 来自 server-metadata.txt，Origin 白名单）→ 200 REJECTED v3。
2. 窗口（登录态商家主账号）：消息中心列表出现**三条真实通知**（REJECT SERVICE_REVIEWED《次日洗护加购》01:52:19 / APPROVE SERVICE_REVIEWED《基础洗护》01:46:46 / MERCHANT_APPLICATION_REVIEWED 入驻通过 01:46:45）→ 截图 c3-01。
3. 点开首条：详情卡展示标题/正文/时间，打开即标记已读（`已读于 2026-09-24T01:53:20.852Z`；网络 `POST /api/v1/c/notifications/{id}/read` → 200）→ 截图 c3-02，按钮「查看服务」在列。
4. 点「查看服务」：路由跳转断言成立——`currentPage.route = /merchant/pages/services/index` → 截图 c3-03（目标页呈 entry 态为 F1 的直接后果，跳转行为本身符合白名单预期）。

通知开关链路结论：`pet.service.review.notifications-enabled` 已在服务器启动参数开启；outbox 异步投递实测 ~秒级（决议 01:52:19 → 收件箱可读 01:52:54 之前）；SERVICE_REVIEWED 的 REJECT/APPROVE 双态文案均经真实链路核验。

## 5. 环境事实与处置记录（如实）

- 会话开始时 M002IntegrationServer（PID 17384）**读路径正常、写路径全 500**（/api/v1/c/auth/attempts 等）：诊断为运行实例僵死（Redis 曾重启致缓存键丢失、Snowflake 单飞续租停滞；17:28 曾有一次失败的重启尝试——cmd 行长超限截断 classpath 致 NoClassDefFoundError——未影响当时存活的旧进程）。
- 处置：以 java @argfile（完整 215 项 classpath）重启集成服务器（新 PID 36012、新随机库 auth001cm002ci_ae409…）；旧库 auth001cm002ci_73405… 因强杀未走 shutdown hook 遗留在 MySQL 33452（仅 CI 数据，未触碰 wt-sch001 工作树与共享数据）。
- 重启后重播种子：链1 主体证件号占位导致链2 报"verified subject is already occupied"，改用随机主体（seed2.mjs）后全链 PASS（含通知）。
- 服务器 health 端点报 DOWN：源于该测试服务器某个组件尝试连接默认 6379 端口 Redis（本机 Redis 在 16383），不影响 18081 全部业务 API（本轮所有业务断言均为 200/预期码）；已如实记录，不修改后端代码（禁止项）。
- 走查结束后 18081 服务器保留运行（PID 36012）。

## 6. 走查矩阵汇总

| # | 场景 | 结果 |
|---|------|------|
| C1-1 | 匿名·门店列表真实模式见种子门店 | PASS |
| C1-2 | 匿名·门店详情（任务A） | PASS |
| C1-3 | 匿名·服务详情含 cover 契约投影（任务A；视觉位未绑定为既有披露） | PASS（附边界） |
| C1-4 | 匿名·立即预约→登录引导弹窗 | PASS |
| C2-1 | 会话注入→服务端校验→已登录 shell | PASS（缝隙披露） |
| C2-2 | 商家工作台 ALLOWED + merchant.service.manage | PASS |
| C2-3 | 工作台→服务管理列表 | **BLOCKED（F1，新发现）** |
| C2-4 | 新建草稿→提交审核 | **窗口 BLOCKED（F1+F2）；HTTP 层等价链 PASS（REVIEWING 达成）** |
| C3-1 | 运营 REJECT 决议 | PASS（HTTP） |
| C3-2 | 商家消息中心 SERVICE_REVIEWED（未读→已读） | PASS |
| C3-3 | 「查看服务」跳转 /merchant/pages/services/index | PASS（目标页 entry 态=F1） |
| — | 真机 | 未做 |

## 7. 本轮证据文件清单

- `window-shots/c1-01…c1-04`（匿名矩阵四步）
- `window-shots/c2-01…c2-03`（登录 shell / 工作台 / 服务管理 entry 态=F1 取证）
- `window-shots/c3-01…c3-03`（消息列表 / 通知详情+查看服务 / 跳转目标）
- `window-shots/c1-network-evidence.txt`（匿名四路由请求/响应，无 Authorization）
- `window-shots/c2c3-network-evidence.txt`（会话/会员/准入/通知读+已读标记）

---

## 8. F1/F2 修复轮（2026-09-24 修复执行）

### 8.1 F1 修复：工作台子页导航坐标让渡

- 根因复核（与 §3 判断一致）：`MerchantWorkspace.leave()` 在 `useDidHide`（navigateTo 子页时也触发）无条件把 scope 重置回 consumer 坐标，而服务管理列表/编辑页门禁读同一 scope 的 merchant 坐标——工作台自己的导航动作清掉了目标页所需坐标。
- 修法（`src/merchant/workspace.ts` + `src/merchant/pages/workspace/index.tsx`，最小行为修正，不改门禁语义、不改 MINI-003 每次进入工作台重新准入的 enter() 语义）：
  1. 新增 `handoffToChild()`：工作台在 navigateTo 自己的管理子页（服务管理）前标记"让渡"——随后 on-hide 的 `leave()` 不清坐标（坐标交给目标页使用）；
  2. `handoffCancelled()`：navigateTo 失败未隐藏本页时收回所有权，之后的 leave()（返回 shell/账号切换）仍正常重置；
  3. `dispose()` 回收"仍然有效的让渡"：整栈销毁（reLaunch 离开工作台子树）时若让渡坐标未被更新的所有者接管，则重置回 consumer——避免商家坐标残留影响消费者个人页（pet-archive/profile-edit 等在 `workspace !== 'consumer'` 时判过期）；
  4. 页面侧 `openServices()`：tap「服务管理」→ `handoffToChild()` → `navigateTo`，失败 `handoffCancelled()`。
- 与既有契约的一致性说明：选择"让渡"而非"列表页自行重放准入"，因 admission/membership 契约（CCR-W2-ADMISSION-001）规定准入由工作台入口持有且每次进入重查；子页仅消费坐标并各自携带 merchantId/storeId 由后端做归属校验。C3 白名单跳转（`routeForNotification`）注释明确"jump never bypasses the workbench gate"——无工作台会话时目标页 entry 态即设计行为（本轮 §9 C3-3' 实测一致）。
- 测试锁定：`src/merchant/tests/workspace.test.ts` 新增 5 组用例（让渡存活性 / 失败收回 / dispose 回收仍有效让渡 / dispose 不动更新的所有者 / 返回后重放准入再离开重置）。

### 8.2 F2 修复：草稿线状按 A 侧可空契约省略未选字段

- 契约核实（读后端请求模型，未改后端代码）：`MerchantServiceController.fields()` 对所有可选字段用 omitted/null = "not provided"（草稿宽松），但 `optionalId/optionalEnum/amount/optionalInt/optionalText`（blank）对 `''`/`0` 一律 400。原 `toBody` 把未选的 `categoryId/fulfillmentType/price/durationMinutes/coverAssetId` 固定序列化为 `''`/`0`（空名也发 `''`）——不止封面，整个宽松草稿保存链在真实后端必 400。
- 修法（`src/merchant/services/repository.ts` toBody）：未选字段全部省略（与既有 listPrice/staffRequirement 等可选文本字段同策略）；`applicablePetTypes`（可空数组）与 `verificationRequired` 照发。
- 提交审核本地校验（既有，本轮走查验证生效）：`missingSubmitFields` 含"封面图"，编辑页提示「提交审核前需补齐：封面图。」且不发出任何请求；`serviceManageMessage` 对 400 的文案含封面项。草稿宽松校验补齐一条 A 侧镜像（`model.ts` draftInputProblems：销售价格 0.00 → "销售价格需大于0"，与后端 prepare() 对草稿也拒绝非正价格一致）。
- 测试锁定：`service-manage.test.ts` 松散草稿（仅名称/完全空）create 线状断言省略字段 + 完整草稿携带封面锚点 + 0 价格校验镜像。

### 8.3 F3（本轮新发现，后端缺陷，未修——禁改后端代码）

create（POST）链对"比 DTO 声明更空"的草稿在后端崩溃/拒绝，与 `ServiceWriteTypes`"Draft fields are lenient (nullable)"的声明不符：

1. `ServiceWriteMapper.insertItem` 的 `categoryId/durationMinutes` 参数是**原始类型** `long/int`——null 装箱 NPE → `ServiceWriteStore.run` 兜底 503 COMMON_DEPENDENCY_UNAVAILABLE（server.log：`Cannot invoke "java.lang.Long.longValue()" ... PreparedFields.categoryId() is null`）。`updateItem`（PUT）同位置是 `Long/Integer` 无此问题。
2. `service_item` 表 DDL：`service_name/price/duration_minutes/fulfillment_type/category_id` 均 NOT NULL 且无默认——无履约方式的草稿 INSERT 报 `Column 'fulfillment_type' cannot be null`（DataIntegrityViolation → 同样兜底 503）。
3. 实际可创建的"最松草稿"= 名称+分类+价格+时长+履约方式必填；封面/适用宠物类型/文本可空。本轮窗口走查按此实际形态绕过（仍不选封面，验证 F2）。
- 处置：不改后端（禁止项）；已在本文件与 PR#78 评论登记，建议 A 侧对齐（insertItem 装箱化 + 表默认值/或 prepare() 显式拒绝并 400 而非 503）。另：503 使 ConsumerApi 写日志（requestId 槽位）挂起，同槽位换内容会 PENDING_WRITE_CHANGED——在 F3 修复前，真实模式"先存必败草稿再改内容重存"会卡原槽（重试原载荷仍 503），这是 F3 的连带前端表现，非独立缺陷。

### 8.4 环境处置记录（修复轮，如实）

- 修复轮开始时 18081 旧实例（PID 36012）读路径正常，但 adminToken（仅服务器启动时经验证码 DB 缝隙铸造一次）已过期（约 30 分钟 TTL）→ 种子链 admin 步骤 401。按 §5 既定自举路径重启两次（java @argfile；新 PID 31968 → 12300），每次重启重播 `seed2.mjs` 全链 PASS。两次强杀的旧库（auth001cm002ci_ae409…/92bf4c…）遗留 MySQL 33452（与上轮同类，仅 CI 数据）。
- 走查主链（第二次重启后）：userId 96438271206313984（138****5493）/ merchantId 96438271537664001 / storeId 96438272590434304；种子 ACTIVE 服务 96438273110528000；走查草稿→REVIEWING→APPROVE→ACTIVE 服务 **96438328198516736**《窗口验收·补全走查上门喂养》¥68（种子封面素材 590000000000000201）。F2 草稿验证段（c2-05/06）与 c2-04 使用前一次链（userId 96430343615238144），服务端重启不影响前端行为取证，如实注明。
- 会话 15 分钟 TTL：走查中经 DB 恢复本链 openid（user_auth_identity 表）用 FixedWechatProvider 缝隙为同一 userId 补铸凭证两次，注入方式同 §0.1。
- 测试域缝隙补充（在 §0 基础上）：(a) 表单药丸点选经 `wx.createSelectorQuery` 读取 Taro 运行时元素 id 后按 `#id` tap（automation 伪类/坐标 tap 不可靠）；(b) 503 后清理 ConsumerApi 写日志存储键 `pet.c.pending.v1` 并刷新（应用内存态）再重放（F3 连带，见 8.3）；(c) 含封面的走查草稿经 HTTP 种子创建（同 §0.2 等价链），窗口 UI 完成改价+提交审核。

## 9. 窗口补全走查矩阵（修复轮，2026-09-24）

构建：真实模式（含 `PET_MERCHANT_APPLICATION_ENABLED=true PET_PRIVATE_MATERIAL_UPLOAD_ENABLED=true`），check:package PASS；换构建后 cleanCompileCache + simulator_refresh。单测 171 全绿（含 F1 五组/F2 断言）、tsc 干净、Mock 构建同样全绿。

| # | 场景 | 结果 | 证据 |
|---|------|------|------|
| C2-3' | 工作台 tap「服务管理」→ 列表页真实模式**可达可读**（"共 N 个服务"，GET /merchant/services 200 带 Bearer） | **PASS（F1 修复验证）** | c2-04 + c2-network-f2-evidence.txt |
| C2-4'a | 编辑页仅填名称/分类/价格/时长/宠物/履约、**不选封面**：提交审核 → 本地提示「提交审核前需补齐：封面图。」，无任何网络请求 | **PASS（F2 校验验证）** | c2-06 |
| C2-4'b | 同表单保存草稿 → 「草稿已保存。」；线状 POST 体**无 coverAssetId 键**（连同未选字段一并省略）→ 201 DRAFT | **PASS（F2 序列化验证）** | c2-05 + c2-network-f2-evidence.txt（POST 201） |
| C2-4'c | HTTP 种子含封草稿 → 窗口编辑（改价 68.00）→ 提交审核 → PUT 200 + online 200 → 「已提交审核…」REVIEWING（submissionNo=1） | **PASS（写链窗口内达成）** | c2-07 + c2-network-f2-evidence.txt |
| C2-4'd | 无分类/无履约草稿经窗口保存 | **BLOCKED（F3 后端 NPE/NOT NULL → 503；登记未修）** | §8.3 server.log 引文 |
| C3-1' | HTTP 运营 APPROVE 决议（/admin/services/{id}/decision → ACTIVE v3） | PASS（HTTP） | §8.4 |
| C3-2' | 消息中心新通知 SERVICE_REVIEWED《补全走查上门喂养》"审核通过，已上架"；打开即已读 | PASS | c3-04 / c3-05 |
| C3-3' | 「查看服务」跳转 → /merchant/pages/services/index 呈 entry 态+「去商家工作台」——**设计门禁**（跳转不得绕过工作台，routeForNotification 注释）而非 F1 残留；随之 tap「去商家工作台」→ ALLOWED → tap「服务管理」→ 列表可达（F1 再验证）且该服务呈「已上架 ¥68」 | PASS | c3-06 / c3-07 |
| C1-5' | 清会话后匿名：门店列表见新链门店（200 无 Authorization） | PASS | c1-05 + c1-network-anonymous-round2.txt（0 个 Authorization 头） |
| C1-6' | 匿名门店详情 + 门店服务列表含《补全走查上门喂养》¥68（含 cover 投影 590000000000000201 + 签名 URL） | PASS | c1-06 + c1-network-anonymous-round2.txt |
| C1-7' | 匿名服务详情（名称/说明/价格全契约绑定） | PASS | c1-07 |
| — | 真机 | 未做（不声称） | — |

「商家发布→运营审核→消费者看到」窗口内闭环（本轮）：工作台→列表（F1）→含封草稿提交 REVIEWING（窗口 UI）→运营 APPROVE（HTTP）→商家消息中心收 SERVICE_REVIEWED APPROVE 通知并已读（窗口）→跳转回工作台链路见「已上架」（窗口）→匿名 C 端四读到该服务（窗口+网络取证）。

## 10. 修复轮证据文件清单

- `window-shots/c2-04…c2-07`（F1 列表可达 / F2 草稿保存 / 提交封面校验 / REVIEWING 提交）
- `window-shots/c3-04…c3-07`（APPROVE 新通知 / 通知详情已读 / 跳转目标 entry 态=设计门禁 / 列表已上架）
- `window-shots/c1-05…c1-07`（匿名门店列表 / 门店详情含新服务 / 服务详情）
- `window-shots/c2-network-f2-evidence.txt`（F1 列表读 + F2 无封面 POST 201 线状 + PUT/online 200 REVIEWING）
- `window-shots/c1-network-anonymous-round2.txt`（匿名四读，0 Authorization）
