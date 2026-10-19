# E2E 收尾复检报告（E2E_BIND 补丁入库 + 模拟器复检，2026-10-19）

- 日期：2026-10-19（北京时间）
- worktree：`C:/Users/Administrator/Desktop/wt-e2e-final`，分支 `codex/e2e-bind-and-recheck-20261019`，基线 develop `61f3ac82`（含 #165/#168/#170/#179/#180/#181/#182/#183/#184/#185 全部）
- 执行人：E2E 收尾代理（ZCode）
- 结论速览：
  - **任务 A（E2E_BIND）完成**：双启动验证通过（默认 127.0.0.1 行为不变 / `E2E_BIND=0.0.0.0` 手机可达），代码为 test sources 单文件 3 处改动，commit `cc97c60f`；
  - **任务 B（模拟器复检）矩阵 3 组 11 项：9 项通过、2 项如实标注**（A5 触发链全过、资金终态未落地；A6 券返还依赖 A5 渠道执行——E-6 风暴全夜持续，三个库窗口均未落地，async_task 行持久存在由 worker 自动补完；详见 §2.4）；
  - FA 修复后运营面 UI 级复验**全绿**（新增 opt-in live spec `admin-fa-fixes-recheck.spec.ts`，Playwright 1 passed），并新发现 **FL-1/FL-2 两个确定性前端缺陷（只定界不修）**；
  - 生产代码零改动（E2E_BIND 在 test sources）；契约零变化。

## 0. 交付物（本 PR）

| 类别 | 文件 | 说明 |
|---|---|---|
| E2E_BIND | `backend/pet-boot/src/test/java/com/petplatform/boot/e2e/E2eCoreLoopServer.java` | `--server.address` 改为 `E2E_BIND` env 可覆盖（默认 127.0.0.1 行为不变）；READY 日志带 `bind=<address>`；真机验收前置 |
| 复验 spec | `frontend-admin/tests/admin-fa-fixes-recheck.spec.ts` | 断言 #183/#185 修复后的行为（旧 admin-main-chain.spec 断言的是修复前错误态，对修复后后端必红）；LIVE_JOINT_BASE opt-in，CI 不跑 |
| 证据 | `planning/progress/2026-10-19/e2e-final-recheck/` | 本报告 + screenshots/（admin 13 张 + miniapp 13 张）+ probes/（探针脚本、网络取证、载荷）+ server-logs/（服务端 console ×6、metadata、Playwright/轮询日志；.gitignore 的 logs/ 与 *.log 规则曾静默丢弃，已 force-add） |

不提交物（本地使用）：`frontend-miniapp/project.config.json`（真实 appid + urlCheck=false，从主工作区拷贝后本地改 urlCheck，git 状态保持未提交）；`backend/pet-boot/cp.txt`（构建产物）。

## 1. 验收环境

| 项 | 值 |
|---|---|
| 后端 | `E2eCoreLoopServer`（含 E2E_BIND），`E2E_BIND=0.0.0.0`，127.0.0.1:18081（netstat：`0.0.0.0:18081 LISTENING`，手机可达）；全套开关随启动器 + application.yml 默认（65号 ops 面现已默认开） |
| MySQL / Redis | 共享 `127.0.0.1:33440`（root/空）/ `127.0.0.1:16379`，只连不启，未动 Docker |
| 隔离库 | `auth001czq7ddf7_e2e_core_loop_bf0b7902554d443897f5b14235862ccf`（node 1020 首建；环境杀进程后按复用缝 `E2E_DATABASE_NAME` + 全新节点 777 重开，事实保留） |
| 小程序 | `frontend-miniapp`：npm ci → `NODE_ENV=development PET_C_API_ORIGIN=http://127.0.0.1:18081 PET_ALLOW_LOCAL_HTTP=true npm run build:weapp`；真实 appid `wx1a64646b1d75306e` project.config.json（urlCheck=false，未入库）；换包后 debug_clear_cache 六类全清 |
| 会话 | C 端 HTTP wechat-login → `wx.setStorageSync('http://127.0.0.1:18081:pet.c.session.v1', grant)` → simulator_refresh（round-2 E-4 冷启动配方；access token 5 分钟 TTL，走查中多次重注入）；运营 `e2e-round2-operator`（PLATFORM_SUPER_ADMIN + ALL） |
| 模拟器 | 微信开发者工具（wechatide CLI v0.3.9，ZCode 客户端），fullMode 项目窗口 |
| 运营 Web | `frontend-admin` npm ci + vite build；`e2e-origin-shim.mjs`（18083→18081，Origin 桥）+ `vite preview`（18082，ADMIN_API_PROXY_TARGET/PROXY_ORIGIN）；Playwright chromium |
| 环境扰动 | 共享 MySQL 雪花风暴全程持续（本库 console 记录 60+ 次 `fail-closed: OPERATION_TIMEOUT` 换节点自愈）；期间环境存储锁杀死过后端进程一次（E-5 复用缝重启恢复） |

## 2. 走查矩阵与证据

### 2.1 任务 B-1：券终态补录（F3R-3 修复后的首条 C 端 UI 全链）

| # | 矩阵项 | 结果 | 证据 |
|---|---|---|---|
| A1 | 下单选券（预约弹层内选 716011） | **通过** | 弹层选券区 4 chip 渲染（截图 03/04）；选中后底部按钮变「确认预约 · ¥50.00」（截图 04）；`POST /c/orders` 201 带 `couponInstanceId:"716011"`（probes/net-order-create.txt） |
| A2 | 券冻结 | **通过** | DB：`coupon_instance 716011 FROZEN order_id=105492144382377984`、`coupon_ledger FREEZE AVAILABLE→FROZEN request_id=CPN:FREEZE:…`；C 端 AVAILABLE 桶 3→2 |
| A3 | 支付成功券变已用（C 端桶 + DB 双证） | **通过** | UI：订单详情 ¥50.00「去支付」可达（截图 05，F3R-3 修复后折后单可付）→ 确认支付页（截图 06）→ 支付单 105494226833342464 已发起 → 沙箱自动回调；支付后券页 AVAILABLE 桶剩 2 张（截图 07）。DB：`pet_order PENDING_CONFIRM/PAID 60.00/10.00/50.00`、`payment_order PAID`、`coupon_instance 716011 USED`、`coupon_ledger CONSUME FROZEN→USED request_id=CPN:CONSUME:…` |
| A4 | 服务前退款自动全额 | **观察（口径正确）** | 退款申请经 UI 提交（截图 08/09，申请编号 105500611154321408）：提交时预约 11:00 已开始（环境杀进程重启耗尽了服务前窗口），平台按窗口口径正确路由「服务后商家 24 小时处理」——**窗口判定行为正确**；服务前自动全额路径此前两轮已两度实证（round-3 §2.4、round-4 S5） |
| A5 | 退款成功 | **触发链全过，资金终态未在环境窗口内落地（E-6）** | 商家批准 HTTP 200（`APPROVED`，decisionId 105501326832619520；买家即商家主的 fixture 等价驱动，披露）→ `refund_order 105501328820719616 MERCHANT_APPROVED FULL 50.00 CREATED`、`APPLICATION_REFUND_SUBMIT` 任务在 RETRY_WAIT（E-6 风暴退避，重试 6/8，Reconciler 兜底复活） |
| A6 | 退款成功券返还 AVAILABLE | **未落地（依赖 A5 渠道执行，E-6；§2.4）** | 依赖 A5 渠道执行 → `RefundSucceededEvent.v1` → 券返还消费者；消费语义已有 DB/单测背书，C 端终态证据待任务落地后补录 |

### 2.2 任务 B-2：两个界面竞态复测（F3R-2/F3R-1 修复后）

| # | 矩阵项 | 结果 | 证据 |
|---|---|---|---|
| B1 | 预约弹层开 5 次，选券区必现渲染 | **通过 5/5** | 宿主 1（门店页行内预约）×4：`.bks-coupon-chip` selectorQuery 实测 4 chip（不使用优惠 + 716011/716012/716013），`GET /c/coupons` 200 3 items（probes/net-coupons-open1.txt）；宿主 2（booking/create 直连）×1：4 chip + 分钟级时段渲染（截图 01/02） |
| B2 | 百科页新开 3 次，首载不死锁 | **通过 3/3** | 3 次独立新开页面实例，品种列表首载即渲染（截图 10/11/12；贵宾犬～博美犬全列），无骨架屏死锁（F3R-1 修复前 3/3 必现死锁） |

### 2.3 任务 B-3：FA 修复后运营面走查（#183 后首次 UI 级复验，spec 1 passed）

| # | 矩阵项 | 结果 | 证据（截图号对应 screenshots/admin/） |
|---|---|---|---|
| C1 | 服务审核控制台渲染（FA-6，"ok"口径） | **通过** | r02：列表渲染、0 alert（修复前任何响应被 `message!=='ok'` 拒渲染） |
| C2 | 订单详情页 200（FA-5 连字码） | **通过** | r04：详情渲染 + 履约层可见 + 0 alert（修复前 `order.status-log.read` 词法 400 连带整页报错）；权限集含 `order.status-log.read`/`order.verification-log.read`（server-logs/server-metadata-final.txt） |
| C3 | 核销记录页 200（FA-5） | **通过** | r06：表格渲染、0 alert（修复前全量 400 COMMON_INVALID_ARGUMENT） |
| C4 | 处置按钮可达（FA-1，不再 403） | **通过（含环境注记）** | 异常关单 UI 真点（首轮）：POST 过安全链入内核——订单 105485164972326912 → **CANCELED** + 新建 `refund_order 105505504552431616 ADMIN_ABNORMAL_CLOSE FULL 60.00`（服务端 console `RefundOrderCreatedEvent.v1` 在案）；信封因 E-6 风暴在事后发号步骤 500（`COMMON_INTERNAL_ERROR`，trace a0a98ab0…）——**不是 403**，修复语义成立；复跑轮 r05 截图显示已关单后的按钮停用态与提示文案。商家下线按钮同页渲染可达（未点击，避免破坏走查商家）。渠道查单/重试按钮因两张退款单均非 PROCESSING 态未点击（按钮状态门控正确） |
| C5 | 运营台四块页面正常（FA-2/FA-3，ops 默认开） | **通过 5/5** | r09~r13：百科分类/品种库/券模板/积分台账/系统设置五块全部渲染数据、0 alert、全程同一会话（apiStatus 断言全部 ops GET = 200；修复前 403 死面 + 打不开开关） |
| C6 | 403 不再误登出 | **间接通过** | 五块 ops 浏览全程会话保持（修复前首遇 ops 403 即登出）；FORBIDDEN→权限提示的 13 页改造在 #183 自带回归覆盖；本 walk 为超管 ALL scope 无自然 403 面，如实标注为间接验证 |

### 2.4 滚动补录（E-6 风暴下的终态处置，如实记录）

- **A5/A6 终态在本轮三个环境窗口内均未落地；触发链已全部取证在案，async_task 行持久存在，环境恢复后由 worker 自动补完**（与 round-3 §2.4、round-4 §2.4 同口径）：
  - 首库 `auth001czq7ddf7…`：商家批准路径退款单 `105501328820719616 MERCHANT_APPROVED FULL 50.00 CREATED`，`APPLICATION_REFUND_SUBMIT` 退避至 7/8 后进程被环境击杀；
  - 二库 `auth001czq6f71b…`（E-5 复用自毁后重建）：服务前自动全额路径重跑 201 `AUTO_APPROVED/AUTO_FULL_BEFORE_SERVICE`，退款单 `105526710706622464 CREATED`，退避 6/8 后再次被环境击杀；
  - 末库 `auth001czq437b…`（本节记录主体）：完整重跑触发链全部落地——折后单 `105542738543280128 PENDING_SERVICE 60/10/50`、券 716011 `FREEZE→CONSUME USED`（C 端桶出桶）、`refund_order 105542801067769856 PRESTART_AUTO FULL 50.00 CREATED`（**服务前自动全额路由的退款单本体首次取证**）、`APPLICATION_REFUND_SUBMIT RETRY_WAIT 4/8 HANDLER_EXCEPTION`（probes/final-db-state.txt + probes/coupon-chain-rerun.txt 全transcript）；
  - 佐证说明：同一执行链（APPLICATION_REFUND_SUBMIT→渠道执行→`RefundSucceededEvent.v1`→券返还/积分扣回消费者）在 round-3 final-chain 走查中已实证落地；券返还消费语义另有 DB/单测背书。
- **E-5 两次新增实锤（复用缝自毁）**：① 进程被环境杀后复用重启，`E2E_SNOWFLAKE_NODE=1500` 触发 `ck_id_node` 检查约束（节点号有上界，1500 越界），attempt-1 失败 → catch 把复用库 DROP（首库残留终态丢失）；② 复用重启选 960 与自愈梯子撞号 DuplicateKey → 同样 DROP（二库丢失）。两次均为 QA 环境损耗，不影响已落盘证据；此前轮次"复用模式失败不得 DROP 复用库 + 节点号自动挑空闲"的启动器 Issue 建议再次成立且更紧迫。

### 2.5 截图索引

| 文件（screenshots/miniapp/） | 内容 |
|---|---|
| 00-home-baseline.jpg | 模拟器首页基线（tabbar 五格） |
| 01/02-sheet-open5-create-host-slots.jpg | 预约弹层宿主 1/宿主 2 打开态（B1 竞态复测，含分钟级时段） |
| 03/04-coupon-area-visible / coupon-716011-selected-pay50.jpg | 选券区渲染 + 选中 716011 后底部按钮 ¥50.00 |
| 05-order-detail-payable-50.jpg | 折后单详情「去支付 ¥50.00」可达（F3R-3 修复） |
| 06-pay-confirm-page-50.jpg | 确认支付页（LAKALA_WECHAT 沙箱发起） |
| 07-coupons-available-2-after-consume.jpg | 支付后券桶 AVAILABLE 剩 2 张（716011 出桶） |
| 08/09-refund-apply / submitted-post-service-route.jpg | 退款申请表单 + 提交后「服务后商家 24 小时处理」路由（窗口口径正确） |
| 10/11/12-baike-openN-list-rendered.jpg | 百科页 3 次独立新开首载即渲染（F3R-1 修复） |

| 文件（screenshots/admin/） | 内容 |
|---|---|
| r01-login-landing.png | 运营真登录落地（商家申请审核） |
| r02-service-review-renders.png | 服务审核控制台渲染（FA-6 修复） |
| r03/r04-trade-orders / order-detail-timeline-renders.png | 订单总览 + 详情含履约层（FA-5 修复） |
| r05-abnormal-close-outcome.png | 异常关单处置区（复跑轮为已关单停用态；首轮真点 POST 证据见 §2.3 C4） |
| r06-verifications-render.png | 核销记录页渲染（FA-5 修复） |
| r07-refund-list.png | 退款单列表（2 张：ADMIN_ABNORMAL_CLOSE 60.00 + MERCHANT_APPROVED 50.00） |
| r08-refund-detail-fl1-error-state.png | FL-1 定界：详情页 INVALID_RESPONSE 错误态 |
| r09~r13-ops-*.png | 五块 ops 控制台渲染数据、同一会话（FA-2/FA-3 修复 + ops 默认开） |

## 3. 新发现问题清单（只定界取证、未修）

| 编号 | 级别 | 问题 | 定界证据 | 处置建议 |
|---|---|---|---|---|
| **FL-1** | 高（前端确定性缺陷，退款详情面死） | 退款单详情页**永远** INVALID_RESPONSE：`tradeQuery.refundDetail`（src/api/tradeQuery.ts L189-196）先 `exact(13 键)` 再调 `refundSummary`（L170-178）→`exact(11 键)`，同一对象键数不可能同时等于 13 和 11 → 任何 200 详情响应都解码失败 | UI：r08 截图（退款单详情加载失败（INVALID_RESPONSE），两张退款单均复现）；后端载荷契约合法：`GET /admin/trade/refunds/{id}` 200 13 键 envelope（probes/refund-detail-payload.json）；node 复刻解码器：`exact13=true, exact11=false → return false`；bundle 反查（App-BkhsPWUY.js）解码器与源码一致，排除构建过期。波及面：详情页内嵌「渠道处置」（渠道查单/重试）UI 不可达 | 删掉 refundDetail 内层 `refundSummary(v)` 调用或改为非 exact 的键存在性校验；另开 Issue。#181 以来从未被 UI 覆盖（旧 spec 从未点进详情页） |
| **FL-2** | 中（同型缺陷，核销详情面死） | `verificationDetail`（L213-222）同一双重 exact 模式（exact 9 键 + verificationSummary L198 exact 6 键）→ 核销详情页永远 INVALID_RESPONSE | 代码同上文件相邻函数；`TradeVerificationDetailPage` 调用 `getVerification`。核销**列表**页正常（行对象恰 6 键） | 同 FL-1 一并修复 |
| FL-3 | 低（测试域文档失真） | `E2eCoreLoopServer` 内 65号 ops 注释（L570-577）仍称"开关打不开/保持关闭"，#183/#185 后已失真（yml 默认开、启动即装配）；本 PR 不动该注释（超出 E2E_BIND scope） | 对比注释与 application.yml L244-246 | 随下次启动器维护顺手更新 |

### 3.1 环境阻塞点与处置（如实记录）

| 编号 | 现象 | 处置 |
|---|---|---|
| E-5（复现） | 环境存储锁杀死后端进程一次（走查中途 18081 失联）；复用缝 `E2E_DATABASE_NAME` + 全新节点号 777 重开成功，事实保留（此前轮次建议的"复用失败不得 DROP 复用库"仍未落地，本次未触发） | 复用缝重启；会话经 HTTP wechat-login 重注入 |
| E-6（持续） | 共享 MySQL 雪花风暴（60+ 次 fail-closed 自愈）使两张退款单的渠道提交任务反复 HANDLER_EXCEPTION 退避（APPLICATION_REFUND_SUBMIT 6/8、ADMIN_REFUND_SUBMIT 6/8）；同风暴使异常关单信封在内核已提交后 500 | 不改代码；等自愈间隙由退避/Reconciler 落地终态，§2.4 滚动补录 |
| E-7 | DevTools network 缓冲容量有限（round-3 已知），长走查后请求取证缺失 | 关键请求改服务端 console + DB + 独立探针三元取证 |

## 4. 披露（本 PR 变化边界）

- 生产代码零改动；生产 SQL 零改动；契约零变化（E2E_BIND 为 test sources 内的启动参数覆盖，不改任何业务/安全语义，默认行为逐字节不变）；
- 测试域新增：复验 spec（opt-in）、证据目录；小程序 project.config.json 本地改动不入库；
- 等价驱动披露：商家批准退款走 M 面 HTTP（M 工作台在 `PET_MERCHANT_APPLICATION_ENABLED` 未参与构建时准入失败——见 FL-3 关联的构建配方注记；不影响 C 端矩阵断言）；
- 走查期内服務前窗口因 E-5 重启耗尽，退款走了服务后商家批准路径（窗口口径行为正确，A4）。

## 5. 复现

1. 起后端：`E2E_CORE_LOOP_SERVER=true [E2E_BIND=0.0.0.0] AUTH_MYSQL_URL=jdbc:mysql://127.0.0.1:33440/ AUTH_MYSQL_USER=root AUTH_MYSQL_PASSWORD="" AUTH_REDIS_HOST=127.0.0.1 AUTH_REDIS_PORT=16379 E2E_PORT=18081 E2E_SNOWFLAKE_NODE=<fresh> E2E_RUNTIME_DIR=D:/Temp/e2e-final-recheck` + classpath=`pet-boot/target/{classes,test-classes}`+`cp.txt`，main=`com.petplatform.boot.e2e.E2eCoreLoopServer`（Maven=D:/apache-maven-3.9.12、JDK21=Adoptium、`-Dmaven.repo.local=D:/m2-e2e-final`）；READY 行含 `bind=<address>`。
2. 小程序：`NODE_ENV=development PET_C_API_ORIGIN=http://127.0.0.1:18081 PET_ALLOW_LOCAL_HTTP=true npm run build:weapp`；真实 appid project.config.json（urlCheck=false，不入库）；换包 debug_clear_cache 全套。
3. 运营面：`frontend-admin` npm ci → npm run build → `node scripts/e2e-origin-shim.mjs` → `ADMIN_API_PROXY_TARGET=http://127.0.0.1:18083 ADMIN_API_PROXY_ORIGIN=http://127.0.0.1:18082 npx vite preview --host 127.0.0.1 --port 18082` → `LIVE_JOINT_BASE=http://127.0.0.1:18082 E2E_ADMIN_EVIDENCE_DIR=<dir> E2E_DISPOSITION_ORDER_ID=<可关单种子单> npx playwright test tests/admin-fa-fixes-recheck.spec.ts`。

证据目录：`planning/progress/2026-10-19/e2e-final-recheck/`（本报告、screenshots/、probes/、server-logs/）。
