# 三轮新能力走查矩阵 微信开发者工具模拟器 E2E 验收报告（QA-005 Round 3）

- 日期：2026-10-16（北京时间）
- worktree：`C:/Users/Administrator/Desktop/wt-e2e3`，分支 `codex/e2e-round3-20261016`，基线 develop `82a2439c`（含 #165 百科 / #168 券全链 / #170 积分订单奖励）
- 验收人：QA E2E 第三轮代理（ZCode）
- 结论（滚动更新，见 §2/§3）：**矩阵 3 组 13 项**——截至本稿：券冻结链 4 项通过、券超时释放进行中（被环境雪花风暴阻塞，任务退避中）、券已用/返还被新发现 **F3R-3（阻断级）** 阻断；百科 5 项全部通过（附 **F3R-1** 缺陷定界）；积分领取 3 项通过（附 **F3R-4** 缺陷定界）、扣回链进行中（同一风暴）。共登记 4 个新发现（F3R-1/2/3/4），均**只定界取证、未改生产代码**。

## 1. 验收环境

| 项 | 值 |
|---|---|
| 后端 | 全链真实 Spring Boot（测试域启动器 `E2eCoreLoopServer`，本 PR 扩展第三轮开关），127.0.0.1:18081 |
| MySQL | 共享实例 `127.0.0.1:33440`（root/空，主会话就绪，纪律：不启动/安装任何容器服务） |
| Redis | 共享 `127.0.0.1:16379` |
| 隔离库 | `auth001czq154d2_e2e_core_loop_db39f8043dd549dca07384713f9ce093`（全新建库+种子；首轮走查库 `auth001czq3930a…` 在环境杀进程后按二轮 RESUME 配方复用重启时，被启动器重试 catch 的 DROP 语义清库——已按复用契约外的方式重建，此坑登记 §5 E-5） |
| 第三轮开关（本 PR 在二轮基础上新增打开） | `pet.coupon.lifecycle.enabled`（61号券生命周期）、`pet.encyclopedia.query.enabled`（62号百科读侧）、`pet.points.task.enabled`+`pet.points.task.http.enabled`（58/60号积分内核+C面）、`pet.points.refund-clawback.enabled`（PTS-002 扣回）、`pet.refund.application.worker.enabled`（退款执行链贯通）、`pet.refund.pre-service-auto-refund.enabled`（服务前自动全额裁决）、`pet.order.expiry.worker.enabled`（支付超时关单→券释放）；schema 追加 `60-Points-Task-Schema`、`62-Baike-Content-Schema`（62 自带 5 分类+25 品种已发布种子） |
| 种子（本轮新增） | 券模板 716001「E2E立减10元券」ACTIVE，rule_json 四键 `{"amountOff":10.00,"thresholdAmount":50.00,"scopeSummary":"全部门店通用","typeLabel":"满减券"}`；券实例 716011/716012/716013 AVAILABLE（用户 710100，30 天有效）；百科/积分种子由 62 schema SQL 与内置缺省配置（首单 100 分）自带的 |
| 二轮种子沿用 | 商家 710301/门店 710302/服务 710401 ¥60/60 分钟/员工 710303/C 用户 710100+宠物 710200（柯基「豆豆」）；boot 后种子链自动产生 1 笔 PAID+PENDING_SERVICE 种子单（见 §2.3 绑定目标） |
| 前端 | `frontend-miniapp` build:weapp，`PET_C_API_ORIGIN=http://127.0.0.1:18081`、`PET_ALLOW_LOCAL_HTTP=true` 编译进 dist；真实 appid `project.config.json`（urlCheck=false）本地使用未入库 |
| 模拟器 | 微信开发者工具（wechatide，ZCode 客户端）；换包后 `debug_clear_cache` cleanFileCache/cleanStorageCache/cleanCompileCache/cleanProjectFileListCache 全清；截图落仓库 evidence 目录 |
| 会话配比 | 任意 wx.login 码映射种子身份（WechatSessionProvider 桩）；环境杀服务器进程后 Redis 前缀随库名更换 → 模拟器旧 grant 全失效，采用「HTTP wechat-login → wx.setStorageSync 注入 `http://127.0.0.1:18081:pet.c.session.v1` → simulator_refresh 冷启动」配方恢复（round-2 E-4 的冷启动变体） |
| 环境扰动 | 共享 MySQL 33440 遭主会话重度负载，Snowflake 单飞行 1s 预算频繁失闭（server-console3.log 记录 30+ 次换节点自愈），全部异步写任务（退款执行/超时关单/自动确认）在 RETRY_WAIT 退避重试——二轮 E-2 同源，本轮影响被放大（见 §5 E-6） |

## 2. 走查矩阵与证据（截图编号对应 `screenshots/`，探针原文在 `probes/`）

### 2.1 券全链（#168，CPN-001/002）

| # | 矩阵项 | 结果 | 证据 |
|---|---|---|---|
| A1 | 种子造券（模板四键 rule_json + 实例） | **通过** | 券列表 3 张 AVAILABLE（旧库 02 截图；新库截图 24 链路内同构）：`GET /c/coupons` 200 `total=3`，四键投影 `amountOff "10.00" / thresholdAmount "50.00" / scopeSummary "全部门店通用" / typeLabel "满减券"`、`validTo 2026-11-15`；券详情页 03（¥10.00 满 50 可用）。DB：`coupon_template 716001 ACTIVE` |
| A2 | 下单选券折后价展示 | **部分通过（前端选券入口被 F3R-2 阻断，折后价以服务端证据在体）** | BookingSheet 选券区 5/5 次打开（2 个宿主页）不渲染（F3R-2，§3.1）。服务端折后价证据：带 `couponInstanceId` 创建 201 → `payAmount "50.00"`（60-10），订单列表/详情 ¥50.00（18/19 截图，旧库；新库同构 24/25）；`booking_order.discount_amount=10.00` |
| A3 | 券冻结（并发第二单同券被拒） | **通过** | 并行双发同券 716012（不同时段）：胜者 201（orderId 104416072845004800，¥50.00），负者 **409 `COUPON_NOT_AVAILABLE`「优惠券不可用」**（CPN-004 行级竞争真语义；旧库首轮并行负者因同时撞门店 guard 得 503 fail-closed，串行复测 409 同券拒绝）；DB：`coupon_instance 716012 FROZEN order_id=胜者`、`coupon_ledger FREEZE AVAILABLE→FROZEN request_id=CPN:FREEZE:<orderId>`、`freeze_expire_at=创建+10min`；券列表 AVAILABLE 桶只剩 2 张（17 截图，FROZEN 桶不出——D2 隔离在体） |
| A4 | 支付成功券变已用 | **被 F3R-3 阻断（阻断级新发现）** | 券单 `discountAmount≠0` → `POST /orders/{id}/payments` **409 `COMMON_CONFLICT` "order is not eligible for payment intent"**（`OrderPaymentFactsApiImpl.requirePayableForPreparation` 硬性要求 `discountAmount==0`，40/41 切片早于 61 号未放宽）；读侧 `canPay` 不看 discount → 详情页窗口内展示「去支付」按钮（19 截图）读写不一致。券单在结构上永远无法支付 → USED/消耗不可达。详见 §3.1 F3R-3 |
| A5 | 支付超时关单券释放回可用 | **进行中（环境阻塞）** | 两张券单（104416072845004800/104416148527026176，各冻 716012/716011）均未支付过窗（paymentExpireAt 03:16/03:22 UTC 已过）；`RESERVATION_HOLD_EXPIRE` ×2 任务 READY→RETRY_WAIT 退避（retry 5/20，HANDLER_EXCEPTION=雪花失闭），到期执行即关单释放（39 号 close 释放路径已由 `pet.order.expiry.worker.enabled=true` 打开）。终态证据待任务落地后补录（§2.4 滚动） |
| A6 | 退款成功券返还 | **被 F3R-3 阻断** | 券单无法支付 → 无 PAID 券单可退 → `RefundSucceededCouponConsumer` 的 FULL→AVAILABLE 返还路径在 C 端矩阵内不可达（消费语义仅能由 DB/单测背书）。如实登记为未覆盖 |

### 2.2 百科（#165，契约 62）

| # | 矩阵项 | 结果 | 证据 |
|---|---|---|---|
| B1 | 匿名浏览列表 | **通过** | 04：匿名（请求头无 Authorization）打开「品种百科」；`GET /c/encyclopedia/categories` 200 items=5（狗狗/猫咪/爬宠/鸟类/昆虫）、`GET /c/encyclopedia/breeds?categoryCode=DOG` 200 items=8（贵宾犬…法国斗牛犬）；mine 页「宠物百科」格位入口 14（未登录也可浏览） |
| B2 | 分类切换 | **通过** | 09（CAT tab 选中+柯基关键词空态「没有找到匹配的品种」）、10（清空后 CAT 列表 6 品种：英短/布偶/美短/暹罗/狸花/橘猫）；网络 `categoryCode=CAT` 200 items=6；服务端 `categoryCode=CAT&keyword=柯基` 200 items=0（正交过滤正确） |
| B3 | 关键词搜索 | **通过** | 05：输入「柯基」搜索 → 别名命中「威尔士柯基犬」（aliases 参与匹配）；网络 `keyword=柯基` 200 items=1 |
| B4 | 品种详情 | **通过** | 06：柯基详情（别名/标签/体型签/事实卡 原产地·寿命·体重/适养能力雷达图/描述富文本）；`GET /c/encyclopedia/breeds/711004` 200 |
| B5 | 品种互跳（我的页格位+宠物档案入口） | **通过** | 宠物档案：11（豆豆卡片）→12（详情「品种 柯基」按钮）→13（跳百科带 keyword=柯基，网络 `keyword=柯基` 200 items=1）；相关推荐互跳：柯基详情→related「贵宾犬」→08 贵宾犬详情（`breeds/711001` 200） |
| — | 缺陷定界 | **F3R-1（中）** | 百科列表页每次新页面实例首载必现骨架屏死锁（网络 200 但列表永不渲染，任意分类/关键词变化可恢复），详见 §3.1 |

### 2.3 积分订单奖励（#170，PTS-001/002）

| # | 矩阵项 | 结果 | 证据 |
|---|---|---|---|
| C1 | 首单奖励可领取态 | **通过** | 27：任务页「完成首次下单 100 积分 · 去领取」（种子 PAID 单在体 → CLAIMABLE；内置缺省配置 first_order=100 生效：签到 5/拉新 50/首单 100 同屏可见） |
| C2 | 领取绑定发放 | **通过（领取动作走 HTTP 探针，F3R-4 定界见 §3.1）** | `POST /c/points/claims {"claimType":"FIRST_ORDER"}` 200 → `pointsGranted "100", balanceAfter "100"`（X-Request-Id 412604f0…）；DB：`points_task_award FIRST_ORDER 100`、`points_ledger ORDER_REWARD delta+100 order_id=104415924328894464`（**绑定最早已付订单=种子单**，PTS-001 绑单语义在体）、`points_account balance=100`；UI：28（任务页「已完成」徽章）、29（积分明细页 余额 100 + 「下单奖励 +100 今天 03:30」） |
| C3 | 退款后按比例扣回 | **进行中（环境阻塞）** | 已触发：种子单（PENDING_SERVICE，服务前）UI 申请退款 → 31：**「申请已提交，系统已自动同意全额退款」处理路径=服务前自动全额**（申请编号 104421701395685376）→ `REFUND_APPLICATION_CREATE SUCCEEDED` → `RefundOrderCreatedEvent.v1`（refundOrderId 104421705875202048）→ `APPLICATION_REFUND_SUBMIT` RETRY_WAIT 退避中（雪花风暴）；落地后应产生 `REFUND_CLAWBACK -100`（FULL 退款 → 全额扣回）与余额回落 0，终态证据待补录（§2.4 滚动） |

### 2.4 滚动补录（写报告后落地的终态）

- （待补）

## 3. 发现的问题清单（只定界取证，未修）

### 3.1 本轮新发现

| 编号 | 级别 | 问题 | 定界证据 | 处置建议 |
|---|---|---|---|---|
| **F3R-1** | 中（前端确定性缺陷） | 百科列表页（`consumer/pages/baike/index.tsx`）首载死锁：每次新页面实例打开，分类/标签渲染但品种列表永久卡骨架屏 | `useDidShow(bootstrap)`（93 行）与挂载 `useEffect(load)`（99-102 行）的 `sequence` 竞态：bootstrap 的 `++sequence.current`（78 行）作废在途列表请求的 `current!==sequence.current` 卫语句（65 行）→ `setItems/setPhase('ready')` 永不执行。网络证据：`categories`/`breeds` 均 200（probes/net-baike-anonymous.txt trace a8b8baea/69a56f72）UI 零渲染；3 次独立打开全复现（04/04b/14） | bootstrap 与首载列表请求共用一个 sequence 域导致互相作废；需架构/前端裁决后另开 Issue（形态同 F3R-2 家族） |
| **F3R-2** | 高（前端确定性缺陷，阻断券 UI 链） | 预约弹层（`booking-sheet.tsx`）选券区必现不渲染 → C 端 UI 无法选券下单 | `loadService` 内券列表 fetch 的完成回调卫语句 `current===sequence.current`（137 行）被同函数 `setPhase('ready')` 后同步触发的 `loadSlots` 的 `++sequence.current`（169 行）作废；`GET /c/coupons` 200 3 items（probes 网络取证 trace 27dd20b8/279af97c）但 `.bks-coupon-chip` 0 命中（selectorQuery 实测）；5/5 次打开（store 页弹层+create 页直连）全复现 | 券 fetch 完成回调与 loadSlots 共享 sequence 域；修复需前端裁决另开 Issue。本轮融资链以 `couponInstanceId` 直发 HTTP 等价驱动 |
| **F3R-3** | **阻断级（后端跨契约矛盾，#168 券全链 C 端主链不可达）** | 券订单（`discount_amount≠0`）无法发起支付：`POST /orders/{id}/payments` 409 `COMMON_CONFLICT` "order is not eligible for payment intent" → 「支付成功券变已用」「退款成功券返还」两条 CPN 主链在 C 端结构性不可达 | `pet-order-biz OrderPaymentFactsApiImpl.requirePayableForPreparation`（66 行附近）硬性要求 `discountAmount==0`——40/41 支付切片早于 61 号券生命周期、#168 未放宽；读侧 `OrderActionAvailability.canPay`（50-58 行）不看 discount → 详情页照常渲染「去支付 ¥50.00」（19 截图），点按/HTTP 均 409（trace 7880e837 等）；DB `discount_amount=10.00` 两单实证。**读写门控不一致** | 需产品/架构裁决：放宽 40/41 可付条件至折后单（payAmount>0 且 expire 未到），或明确券单不走该支付路径。**本轮不修** |
| **F3R-4** | 低（前端缺口） | 首单奖励（FIRST_ORDER）在 UI 无领取入口：任务页「去领取」徽章是纯文案（`tasks.tsx` 95-106 行无按钮），奖励页只渲染三奖励（streak/milestone/profile） | rewards 页 id 全集 `ptr-claim-*` 仅三类；tasks 页 `ptt-task-*` 行内无任何 onClick 领取动作；`repository.claimReward` 仅 rewards 页调用 | 任务页任务行按状态渲染领取按钮（POST claims {claimType}），或奖励页并入 FIRST_ORDER 行；随 F3R-2 家族一并裁决 |

### 3.2 环境阻塞点与处置（如实记录）

| 编号 | 现象 | 处置 |
|---|---|---|
| E-5 | 环境随机击杀后台服务器进程（已知存储锁行为）；按二轮 RESUME 配方 `E2E_DATABASE_NAME` 复用重启时，启动器 attempt 循环的 catch 对**复用库同样执行 DROP**（snowflake node 冲突连败 6 次后走查库被清） | 弃用复用模式，全新建库重跑交易链；复用 catch 的 DROP 语义对复用模式是反直觉自毁，建议后续给启动器提 Issue（复用模式跳过 DROP）；旧库证据（17-22 截图/net）仍在，与新库证据分开标注 |
| E-6 | 共享 MySQL 33440 主会话重度负载 → Snowflake 单飞行 1s 预算频繁失闭（30+ 次换节点自愈），全部异步任务（退款执行/超时关单/自动确认）RETROW_WAIT 退避 | 同二轮 E-2 口径：不改代码，等自愈间隙由退避重试落地；本报告 §2.4 滚动补录终态；支付窗口内 UI 重试×6+HTTP×8 未能在窗口内落地属同源 |
| E-7 | 模拟器 DevTools network 缓冲容量有限，支付重试风暴把缓冲灌满，之后的新请求取证缺失 | 关键请求改为服务端 console + DB 行 + curl 落盘（probes/）三元取证 |

## 4. 附带实证的产品硬规则

- **单次服务必须预约+分钟级排期**：预约弹层今日 10 个 60 分钟分钟级槽（16 截图），创建命令五键+appointmentStart/End；
- **服务前退款自动全额**：31 截图「系统已自动同意全额退款·处理路径=服务前自动全额」（PENDING_SERVICE 单，无商家处理环节）；
- **积分只赚取/扣回不消费**：29 截图页脚「积分仅通过赚取获得，退款时按规则扣回；V1 不支持积分消费、兑换或抵现」，全站无消费路由；
- **PTS-001 绑单发放**：points_ledger.ORDER_REWARD 行带 order_id=种子单（扣回可按单定位）；
- **HTTP/JSON ID String + 金额两位小数**：couponId "716011"、amountOff "10.00"、payAmount "50.00" 全程字符串；
- **X-Request-Id 幂等**：领取/创建/退款申请均带 UUID Request-Id（probes 落盘）；支付重试风暴期间同槽换 id 被 409 COMMON_CONFLICT 拒绝（幂等槽防重复语义反向实证）；
- **FROZEN/RISK_FROZEN 永不出 C 端桶**（D2）：冻结后券列表 AVAILABLE 桶即时少一张（17 截图）。

## 5. 复现

1. 起服务端：`E2E_CORE_LOOP_SERVER=true AUTH_MYSQL_URL=jdbc:mysql://127.0.0.1:33440/ AUTH_MYSQL_USER=root AUTH_MYSQL_PASSWORD="" AUTH_REDIS_HOST=127.0.0.1 AUTH_REDIS_PORT=16379 E2E_PORT=18081 E2E_SNOWFLAKE_NODE=<fresh> E2E_RUNTIME_DIR=D:/Temp/e2e-r3` + classpath=pet-boot/target/{classes,test-classes}+cp.txt，main=`com.petplatform.boot.e2e.E2eCoreLoopServer`（Maven/JDK21 用 D:/apache-maven-3.9.12 + Adoptium 21 + -Dmaven.repo.local=D:/m2-e2e3）。
2. 前端：`NODE_ENV=development PET_C_API_ORIGIN=http://127.0.0.1:18081 PET_ALLOW_LOCAL_HTTP=true npm run build:weapp`；真实 appid project.config.json（urlCheck=false，不入库）；换包后 debug_clear_cache 全套。
3. 模拟器走查 + HTTP 探针（token 经 attempts→wechat-login 获取）：券竞争 `probes/coupon-race.py`（python）。
4. 服务器日志 `D:/Temp/e2e-r3/server-console*.log`（沙箱支付/事件汇/发号器自愈/任务退避）。

证据目录：`planning/progress/2026-10-16/e2e-round3/`（截图 31 张、probes/ 网络与登录取证、本报告）。
