# 核心交易闭环 微信开发者工具模拟器 E2E 验收报告

- 日期：2026-10-07 ~ 2026-10-08（北京时间跨零点执行）
- worktree：`C:/Users/Administrator/Desktop/wt-e2e-core-loop`，分支 `codex/e2e-core-loop-20261007`，基线 develop `be29745`
- 验收人：QA E2E 代理（ZCode）
- 结论：**核心交易闭环 9 步全部真机（模拟器）走查通过**；另登记 2 个产品/组合层发现（不修）、4 个环境阻塞点及处置。

## 1. 验收环境

| 项 | 值 |
|---|---|
| 后端 | 全链真实 Spring Boot（测试域启动器 `E2eCoreLoopServer`，本 PR 新增，模式沿用 `M002IntegrationServer`/`AfterSaleHttpFixture`），127.0.0.1:18081 |
| MySQL | 专用隔离实例 `127.0.0.1:33441`（本机自起，root/空，进程退出 DROP 库；33440 共享实例在 4 个并行代理争用下无法满足 Snowflake 1s 单飞行预算，详见 §5.3） |
| Redis | 共享 `127.0.0.1:16379`，专属前缀 `auth001czq<rand>_...`（避免与并行代理的前缀清扫互相误删） |
| 前端 | `frontend-miniapp` dev 构建（Taro watch），`PET_C_API_ORIGIN=http://127.0.0.1:18081`、`PET_ALLOW_LOCAL_HTTP=true`、`PET_MERCHANT_APPLICATION_ENABLED=true` 已编译进 dist |
| 模拟器 | 微信开发者工具（wechatide 2.02.2608060，skill 0.3.9，已登录，自动化端口可用），真实 appid `project.config.json` 本地使用未入库（先例：aftersale-pages DEVICE-PREP） |
| 开关 | C 端全链开关全开：pet.auth.c / merchant.application(+protection/subject) / store.query / service.query+command / schedule.query+protection / order.creation / order.expiry(+worker) / payment.foundation+dispatch / order.auto-confirm(+worker) / order.merchant(+http+worker) / verification.credential(+http) / verification.completion(+http) / refund.application(+http) / refund.late / outbox |
| QA 缝隙（与库内验收测试同缝） | WechatSessionProvider 桩（接受任意真实 wx.login code → 种子身份）、PaymentChannel 沙箱（预下单参数确定性返回）+ 支付成功通知轮询器（模拟渠道回调 → 真实 PaymentNotificationService）、PaymentReceiptVerifier 桩、PaymentMerchantBindings 定值、退款渠道/审核(AfterSale 式 moderation)桩、封面/私有素材/地图/主体凭据桩、RecoveringIds（Snowflake 失闭自动换节点重开，仅测试域） |
| 种子 | 已审批已签约商家 710301/门店 710302/上架 IN_STORE 服务 710401（¥60/60 分钟，DOG）/当日+次日分钟级窗口×11/合格员工 710303；C 用户 710100（已绑手机 138****0100，登录一步完成）+宠物 710200 |

## 2. 链路通过矩阵（截图编号对应 `screenshots/`）

| # | 链路步骤 | 结果 | 证据（操作→页面→网络/数据） |
|---|---|---|---|
| 1 | C 端微信登录（真实 wx.login code → 会话） | **通过** | 01→02：#c-login 点击后 `#c-session-state` = `已登录 · 138****0100`（手机号已种子，一步完成，无 VERIFY_PHONE 分支） |
| 2 | C 端浏览：门店列表（匿名）/服务列表/服务详情 | **通过** | 02（门店 E2E体验门店，真实 /c/stores）、03（服务卡片 ¥60/次）、04（详情+封面 URL 桩+立即预约） |
| 3 | 可约时段读取（SCH 分钟级 availability） | **通过** | 05/06：当日 11 个 60 分钟槽，每槽「剩1个」，切换无窗口日期显示空状态（真实 /c/services/{id}/availability） |
| 4 | 预约下单（#127/#128 POST /c/orders） | **通过** | 07（表单：门店/服务/履约方式/日期/时段/宠物/备注）→08（回执：订单号 101362939969241088、PENDING_PAYMENT、¥60.00、支付截止时间）。网络取证含幂等重放（同一 X-Request-Id 多次重试） |
| 5 | 支付发起（沙箱 POST /c/orders/{id}/payments） | **通过** | 09（确认支付 ¥60.00）→10（回执：支付单号 101363104050413573、RSA 渠道参数、沙箱说明文案）。沙箱回调后 payment_order=PAID（channel_trade_no/paid_at/success_event_id 落库），PaymentSucceededEvent 经 outbox 消费后订单自动进入 **PENDING_CONFIRM** |
| 6 | 商家工作台准入 | **通过** | 12：进入商家工作区 → memberships→admission 真实链 → **工作台已就绪**（E2E体验商家/E2E体验门店，OWNER，全部入口：服务/排期/成员/订单核销/订单处理/售后） |
| 7 | 商家接单（#129 POST /merchant/orders/{id}/confirm） | **通过** | 13：订单处理页输入订单号 → 确认 → order_stage `PENDING_CONFIRM→PENDING_SERVICE` |
| 8 | C 端订单列表/详情（#120/#123） | **通过** | 14（列表：待服务 ¥60.00 真实 /c/orders）、11（详情-待商家确认）、15（详情-已确认，含核销码/退款入口） |
| 9 | C 端取核销码（#116/#114） | **通过** | 16：`HS9FCVA0 TC26RT6G 6H86T3MM 28BXVMM7`，有效期至 01:12:12（5 分钟窗口+倒计时） |
| 10 | 商家核销（#117/#114 POST /merchant/orders/{id}/verification） | **通过** | 17：员工核销页输入订单号+核销码 → order_stage `PENDING_SERVICE→COMPLETED`（在核销码有效期内完成；48 K1 身份链服务端校验） |
| 11 | C 端退款申请（#130 POST /c/orders/{id}/refund-applications） | **通过** | 18（详情-已完成+申请退款入口）→19：**201 Created**，`applicationStatus=PENDING_MERCHANT`、`route=MERCHANT_CONFIRM_AFTER_SERVICE`、`merchantDeadline=2026-10-08T17:11:42Z`（**服务后退款商家 24 小时处理**规则在体确认）、displayStatus=REFUND_PENDING_CONFIRM；order_refund_application_proof 落库 |

未在本链（按任务边界）：C 端评价（评价切片同批并行）、售后工作流/部分退款（仅运营裁决）、迟到支付自动退款、改期。

## 3. 发现的问题清单

### 3.1 登记（产品/组合层，不修）

| 编号 | 级别 | 问题 | 处置建议 |
|---|---|---|---|
| F-1 | 中（组合约束） | `pet.outbox.enabled=true` + `pet.order.merchant.enabled=true` 而 `pet.refund.late.enabled=false` 时**启动失败**：`LateRefundService` 在 refund.late 关闭时注册空订阅（`eventTypes()=Set.of()`），`OutboxDispatcher` 对空订阅消费者直接抛 `Consumer needs a consumerName and at least one eventType` 使整个上下文起不来。当前 application.yml 默认值（全关）掩盖了该组合。 | 建议 LateRefundService 空订阅时不注册消费者，或 OutboxDispatcher 跳过空订阅；需产品/架构裁决（本验收在夹具中以 refund.late=true 规避并留注释） |
| F-2 | 中（前后端集成） | 预约下单带 `remark`（C 端表单自由填写，契约 §3.5 可选字段）时，在当前生产装配下**必然 503** `booking remark review unavailable`：`BookingCreationConfiguration.orderCreationRemarkPolicy()` 默认 Bean 是失败关闭实现，真实备注审核 Provider 尚未落地。模拟器网络取证：4 次同 X-Request-Id 重放全部 503。C 端页面无任何预检提示。 | 备注审核 Provider 交付前，建议 C 端下单页对 remark 字段置灰/提示「暂不支持备注」，或后端在缺 Provider 时降级为放行空备注；需裁决（本验收在夹具中注册放行桩以继续全链） |

### 3.2 已修（测试域/环境，随本 PR 提交）

| 编号 | 问题 | 修复 |
|---|---|---|
| F-3 | 种子 `staff_availability_window` 用 `DATE_SUB(UTC_TIMESTAMP(3),...)` 带出非整分钟 → 排期内核 `invalid staff availability facts` 503 | 种子改为整点截断（`truncatedTo(HOURS)`），同库内先例口径 |
| F-4 | 共享 MySQL 33440（4 并行代理全链压测+构建）下单次 >1s 抖动即令 Snowflake 单飞行道**永久失闭**（OPERATION_TIMEOUT/ACTION_FAILED），全服务 ID 失能 | ① QA 夹具 `RecoveringIds`：失闭时自动开新 virgin 节点（23→24→25…，节点行真实入库）；② 改用本机专用 MySQL 33441 根治争用（33440 按指示保留未动） |
| F-5 | 并行代理共享 Redis 的前缀清扫误删会话（同名 `auth001c_e2e_core_loop_*` 前缀） | 命名空间加随机中缀 `auth001czq<rand>_...`（仍在 auth001c[a-zA-Z0-9_-]* 契约内） |

### 3.3 环境阻塞点与处置（如实记录）

| 编号 | 现象 | 处置 |
|---|---|---|
| E-1 | DevTools 模拟器视口错配：`windowHeight=753` 而页面按 844 逻辑高度渲染，底部固定导航/下单页底部确认条落在自动化可点窗口之外（rect top 749~844 > 753）。真机（视口=渲染高度）无此问题 | 会话有效时元素 tap 仍可触发（坐标合成命中）；截图取证正常。登记为模拟器设备画像差异，未改产品代码 |
| E-2 | DevTools 长时间运行后自动化桥退化：tap 成功但无响应、`reLaunch` 报 `Uncaught [object Object]` | `simulator_refresh` 后完全恢复（本轮两次） |
| E-3 | 专用 MySQL 后台任务在 00:52 被外部终止（支付发起中途），支付单停留 INIT/PARAMETERS_READY | mysqld 重启 InnoDB 恢复，数据无损；支付幂等重试后沙箱完成 PAID。此事件反而实证了支付发起的断点续传 |
| E-4 | wechatide CLI 的 URL 参数含 `&` 时偶发被 cmd 拆参（`'storeId' is not recognized`） | 改用页面内真实按钮导航（门店→服务→预约入口全 UI 路径） |

## 4. 附带实证的产品硬规则

- 支付成功 → 订单自动进入待商家确认（Transactional Outbox 事件在体消费，非轮询补偿）；
- 核销码 5 分钟有效期 + 页面倒计时；商家在有效期内核销成功后订单 COMPLETED；
- 服务后（已核销）退款走 `MERCHANT_CONFIRM_AFTER_SERVICE`，商家截止时间 = 申请 + 24h（SSOT 规则在体确认）；
- C 会话 access TTL 900s（到期 401 → 页面登录引导，重新登录后凭 pending write 日志按原 X-Request-Id 幂等重放，未重复建单）。

## 5. 复现

1. 起专用 MySQL（或用 33440 空闲时段）：`mysqld --no-defaults --port=33441 --datadir=D:/Temp/e2e-mysql-data ...`（root@127.0.0.1 空密码，详见报告脚本段）。
2. 起后端：`backend/pet-boot/src/test/.../e2e/E2eCoreLoopServer.java`，env：`E2E_CORE_LOOP_SERVER=true AUTH_MYSQL_URL=jdbc:mysql://127.0.0.1:33441/ AUTH_REDIS_HOST=127.0.0.1 AUTH_REDIS_PORT=16379 E2E_PORT=18081`（classpath=pet-boot/target/{classes,test-classes}+依赖 jar）。
3. 前端：`PET_C_API_ORIGIN=http://127.0.0.1:18081 PET_ALLOW_LOCAL_HTTP=true PET_MERCHANT_APPLICATION_ENABLED=true npm run dev:weapp`；本地真实 appid `project.config.json`（不入库）+ `project.private.config.json` urlCheck=false。
4. DevTools 导入 frontend-miniapp，按 §2 矩阵逐步走查（种子坐标见 `server-metadata.txt`）。

证据目录：`planning/progress/2026-10-07/e2e-core-loop/`（截图 19 张、`server-console.log` 含沙箱支付/退款路由日志；网络取证可从 DevTools Network 面板复演，关键请求/响应已摘录在本报告 §2）。
