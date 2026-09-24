# M-002 服务页窗口级 E2E 取证（WINDOW-E2E-EVIDENCE）

日期：2026-09-24（本地会话）。分支 `codex/e2e-window-20260923`（基于 develop 639b61a）。
取证人：Codex 窗口级验收（恢复执行轮）。工具：微信开发者工具 CLI（`wechatide -c ZCode`，simulator_screenshot / automation_element_action / automation_evaluate / automation_wx_api / get_simulator_network / debug_clear_cache / simulator_refresh）。

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
