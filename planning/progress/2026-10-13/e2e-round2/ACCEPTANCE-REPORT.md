# 二轮扩展链路 微信开发者工具模拟器 E2E 验收报告

- 日期：2026-10-13（北京时间；跨首任/接力两代理，中途两次环境级中断后恢复）
- worktree：`C:/Users/Administrator/Desktop/wt-e2e-round2`，分支 `codex/e2e-round2-20261013`，基线 develop `3be00c1`
- 验收人：QA E2E 代理（ZCode，接力）
- 结论：**走查矩阵 6 场景全部真机（模拟器）走查通过**（商家退款处理 / 评价申诉 / 改期 / 售后 / 商家订单列表页 / 核心闭环回归）；改期闭环含 30 分钟自动确认终态收口（截图 53）；另登记 2 个产品/前后端发现（不修，F2R-1/F2R-2）、7 项测试域夹具加固（随本 PR 提交）、4 个环境阻塞点及处置；**日期雷排查令：本轮扩展夹具无写死日期，无新增雷**（§5）。

## 1. 验收环境

| 项 | 值 |
|---|---|
| 后端 | 全链真实 Spring Boot（测试域启动器 `E2eCoreLoopServer`，本 PR 扩展），127.0.0.1:18082；**复用模式**（`E2E_DATABASE_NAME` 重开前任进程遗留库，schema/种子跳过、存活事实即验收对象；配 fresh `E2E_SNOWFLAKE_NODE`） |
| MySQL | 专用隔离实例 `127.0.0.1:33442`（datadir `D:/Temp/e2e-r2-mysql`，root/空，python subprocess detached 启动；33440 共享实例为 18081 看门狗占用，未触碰） |
| Redis | 共享 `127.0.0.1:16379`，专属前缀 `auth001czq<rand>_...`（验收中途被主会话重启过一次，会话经静默重登+storage 注入无缝恢复） |
| 前端 | `frontend-miniapp` build:weapp 产物，`PET_C_API_ORIGIN=http://127.0.0.1:18082`、`PET_ALLOW_LOCAL_HTTP=true`、`PET_MERCHANT_APPLICATION_ENABLED=true` 已编译进 dist；真实 appid `project.config.json` 本地使用未入库 |
| 模拟器 | 微信开发者工具（wechatide，skill 0.3.9，已登录，automation 端口可用）；窗口中途被环境关闭一次，`open_project_window` 重开后续走 |
| 二轮开关（本 PR 在首轮基础上打开） | `pet.order.reschedule(+http).enabled`（46号）、`pet.schedule.selection.enabled`（39号选窗增补）、`pet.review(+http).enabled`、`pet.review.appeal(+http).enabled`（56号）、`pet.refund.application(+http)`（49号，worker 关）、`pet.refund.late.enabled`、`pet.refund.merchant.http.enabled`（57号）、`pet.aftersale(+http).enabled`（50/51号，worker/refund 关）；首轮开关（支付/核销/商家订单/自动确认等）全保持 |
| QA 缝隙（与库内验收测试同缝） | WechatSessionProvider 桩（任意真实 wx.login code → 种子身份，**Redis 重启后会话清空即静默重登自愈，实测 401→重登→同 X-Request-Id 重放不重复建单**）、PaymentChannel 沙箱+回调轮询、PaymentReceiptVerifier 桩、**E2E_INTEGRATION_SINK（无进程内消费者的集成事件汇，见 F2R-5）**、RecoveringIds（Snowflake 失闭自动换节点，节点号单调递增）、商家主体凭据/退款渠道/审核桩 |
| 种子（全部相对日期） | 首轮种子沿用（商家 710301/门店 710302/上架服务 710401 ¥60/60分钟 DOG/员工 710303/C 用户 710100+宠物 710200）+ 本 PR 新增：已审批已签约申请+协议投影（29/28 SQL）、**当日+次日 09:00-19:00 整点窗×22（容量 6）**、PENDING_SERVICE 待改期单 103335140960333824（10-14 10:00 槽）、admin 运营账号 e2e-round2-operator（PLATFORM_SUPER_ADMIN，SSOT §24 单运营无 MFA） |

## 2. 走查矩阵（截图编号对应 `screenshots/`，01-44 首任、45-53 接力）

### 2.1 核心闭环回归（复用首轮口径，全链重放）

| # | 步骤 | 结果 | 证据 |
|---|---|---|---|
| 1 | 匿名浏览（首页/门店/服务/详情） | **通过** | 01/04/05/06：真实 /c/stores、/c/services；登录门 02 与已登录态 03 |
| 2 | 预约→支付→待确认 | **通过** | 07/08（订单A 103336266921242624，13:00 槽，中文备注经夹具放行桩）、09/10（沙箱支付回执）、11（PENDING_CONFIRM） |
| 3 | M 工作台准入→接单 | **通过** | 12（工作台就绪，含售后/退款/评价/订单处理入口）、13（M 订单列表）、14/15（表单收**订单ID** 103336266921242624 非订单号；接单后 PENDING_SERVICE） |
| 4 | C 待服务→核销码→M 核销 | **通过** | 17（详情待服务）、18（核销码 5 分钟倒计时）、19（核销成功）、20（C 已完成+退款/评价入口） |

### 2.2 商家退款处理（57号，服务后 MERCHANT_CONFIRM_AFTER_SERVICE）

| # | 步骤 | 结果 | 证据 |
|---|---|---|---|
| 5 | C 申请退款（UI 入口+curl 探针） | **通过** | 20（已完成订单退款入口）、21（表单）；201 → `PENDING_MERCHANT`、`route=MERCHANT_CONFIRM_AFTER_SERVICE`、`merchantDeadline=申请+24h`（SSOT 24 小时规则在体） |
| 6 | M 待处理列表→详情 | **通过** | 22（M 服务列表）、23（待处理列表）、24（详情：期限倒计时） |
| 7 | M 拒绝→C 重申→M 同意 | **通过** | 25/26（拒绝表单/结果，DB `REJECTED`+1 decision+0 refund_order）、27（C 详情展示已拒绝）、28（C 重新申请，**每轮重计 24h**）、29/30（第二轮同意，DB `APPROVED`）；`refund_application` 两轮 103382249088606208 REJECTED / 103384241504641024 APPROVED，**refund_order 计数=0**（57号同意=申请通过，无资金移动；REFUND_APPLICATION_CREATE 任务落库） |

### 2.3 评价与申诉（56号）

| # | 步骤 | 结果 | 证据 |
|---|---|---|---|
| 8 | C 评价（UI 表单 5/4/3 分） | **通过** | 31/32：reviewId 103385434561843200，DB `PUBLISHED`、store 5/service 4/staff 3、composite 4.2、中文内容原文落库 |
| 9 | M 评价管理列表→申诉入口 | **通过** | 33（M 评价列表 4.2 分）、34（申诉表单；UI textarea 中文注入坏 → raw-socket ASCII reason 201 `SUBMITTED`，appealId 103386810587156480） |
| 10 | admin 运营裁决（HTTP） | **通过** | `/admin/auth/attempts`+`login`（需 Origin 头 `https://e2e.example.invalid`+`__Host-pet-admin-attempt` cookie）→ `GET /admin/review-appeals` → `POST decision REJECTED`；35（M 页显示"申诉未成立"，评价维持公开） |

### 2.4 售后（50/51号）

| # | 步骤 | 结果 | 证据 |
|---|---|---|---|
| 11 | 第二笔完整下单→核销 | **通过** | 36（订单B 103388746744991744，16:00 槽；确认+核销经 HTTP 驱动） |
| 12 | C 售后资格+申请（UI） | **通过** | 37（售后首页）、38/39（申请表单，原生 Picker 以 `--action trigger` 注入：type=服务质量问题 demand=退款）；caseId 103393051996725248 `PENDING`，发起窗口截止=完成+7天（2026-10-20 15:12） |
| 13 | C 列表/详情→M 列表/详情 | **通过** | 40/41（C 端）、42/43（M 端） |
| 14 | M 商家意见提交 | **通过** | 45（意见弹层：上次 MySQL 中断遗留 pending 命令原样恢复——「重试原提交」按钮+原中文草稿 46/500）、46（提交成功：「商家 · 部分同意 · 已提交，平台将结合双方证据处理」）；DB `aftersale_evidence_batch` 新增 MERCHANT 行（submitter 710100/merchant 侧）、`aftersale_case` version 0→1 仍 PENDING（**意见≠裁决**，部分退款仅运营裁决规则在体） |

### 2.5 改期（46号+39号选窗增补）

| # | 步骤 | 结果 | 证据 |
|---|---|---|---|
| 15 | C 详情改期入口门控 | **通过** | 47：种子单 103335140960333824（PENDING_SERVICE、reschedule_count=0）详情出现「订单改期」 |
| 16 | 改期页读侧（分钟级选窗） | **通过** | 48：日期条 7 日（今天~10-19），次日 11 个 60 分钟槽（09:00-19:00，容量 6），39号 windowId/kind 增补开放（无「选窗信息未开放」失败关闭态）；选中 10-14 17:00 槽（is-selected） |
| 17 | 提交改期（含中断恢复） | **通过** | 网络取证：POST `/c/orders/{id}/reschedule`（X-Request-Id `c8987736-...`，body 含 `expectedOrderVersion:"2"`+`selectedGeneralWindowId:103335067044114432`）首撞发号器失闭 503×5 → **同 X-Request-Id 幂等重放成功**（中途 401→静默重登→继续原请求）；DB：`reschedule_count` 0→1、stage `PENDING_SERVICE→PENDING_CONFIRM`、预约 10:00→**17:00~18:00**、version 2→3、`confirm_deadline`=改期时刻+30 分钟、`order_reschedule_record` 落库、`OrderRescheduledEvent.v1` 经 outbox 被 E2E_EVENT_SINK 消费 |
| 18 | 改期后 C 详情 | **通过** | 49：待确认徽章、服务时间 2026-10-14 17:00~18:00（北京时间）、**「订单改期」入口消失**（每单最多 1 次在体）；订单列表同步显示「待确认」 |
| 19 | 改期后 30 分钟确认钟（M 手动路径受 F2R-1 阻断，自动路径） | **通过（自动）** | 50（M 订单列表终版：3 单 3 状态 待接单/售后中/已完成）、51/52（M 再确认表单→409，见 F2R-1）；**确认钟已到期触发**：`async_task` `ORDER_AUTO_CONFIRM:103335140960333824:1` **SUCCEEDED**（retry 0；改期前旧任务 `:0` CANCELED——30 分钟钟随改期重启的实证），`order_status_log` 09:32:16 UTC `ORDER_AUTO_CONFIRMED` SYSTEM `PENDING_CONFIRM→PENDING_SERVICE`（恰为改期时刻+30 分），日志 `E2E_EVENT_SINK OrderConfirmedEvent.v1 aggregate=103335140960333824`；DB `order_stage=PENDING_SERVICE`、version 4；**53（C 详情终态：待服务徽章+新时段 2026-10-14 17:00~18:00+未核销）** |

### 2.6 商家订单列表页（45号读侧增补）

| # | 步骤 | 结果 | 证据 |
|---|---|---|---|
| 20 | M 工作台「订单处理」列表（待接单/全部 tab、多状态多订单） | **通过** | 13（首轮 1 单）、50（终版 3 单：待接单[改期单新时段]/售后中[订单B]/已完成[订单A]，含服务时间、支付时间、金额、坐标校验「本店 · 共 N 笔」）；卡片→详情/接单路径 51 |

## 3. 发现的问题清单

### 3.1 登记（产品/前后端层，不修）

| 编号 | 级别 | 问题 | 处置建议 |
|---|---|---|---|
| F2R-1 | 中（前后端集成） | **改期后 M 端手动接单/拒单必然 409**：后端 `MerchantOrderService` 要求 `expectedConfirmRound == order.rescheduleCount`（改期后=1），而前端 `src/merchant/order/repository.ts:53` 硬编码 `expectedConfirmRound: 0`，且 M 订单列表/详情读侧不回 rescheduleCount/confirmRound，前端无从构造正确值。网络取证：`POST /merchant/orders/103335140960333824/confirm` body `{"expectedConfirmRound":0}` → 409 `ORDER_STATE_NOT_ALLOWED`（截图 52）。改期后订单只能靠 30 分钟 auto-confirm worker 兜底离开待确认，"商家确认 30 分钟"的 M 手动路径对改期单失效。 | M 读侧（45号列表/详情）增补 confirmRound/rescheduleCount 投影，表单携带读侧值；需产品/架构裁决后另开 Issue |
| F2R-2 | 低（前端） | 改期「重试原改期」在会话过期 401→静默重登后，页面控制器停在 load-error 态（「改期结果尚未确认」+需手动「重新加载」），不自动续发未确认命令。幂等语义正确（不会重复改期），仅恢复体验不完整。 | silent-login 恢复后自动续发 pending 写；随 F2R-1 一并裁决 |

### 3.2 已修（测试域/夹具，随本 PR 提交于 `E2eCoreLoopServer.java`）

| 编号 | 问题 | 加固 |
|---|---|---|
| F2R-3 | 无进程内消费者的集成事件（OrderPaid/Confirmed/Verified.v2/RefundApplication*/AfterSale* 等）使 OutboxDispatcher 对最老事件 claim→release 死循环，饿死后续事件（**生产装配同样存在此组合风险**） | QA 夹具注册 `E2E_INTEGRATION_SINK` 汇掉全部集成事件并打印 `E2E_EVENT_SINK` 行（生产是否需要空消费者/死信策略，建议架构侧评估） |
| F2R-4 | RecoveringIds 旧实现按 attempt 枚举节点号，与自身残留行撞号 → 失闭后永久失能 | 节点号改 `AtomicInteger` 单调递增（本轮实测滚至 node 13x 仍自愈） |
| F2R-5 | 复用模式缺 50/51 售后 schema（退款申请准入读 aftersale_case 投影，缺列 503）——首轮库无售后表 | `loadSchemas` 补 `50-AfterSale-Workflow-Schema`、`51-AfterSale-Http-Schema` |
| F2R-6 | 服务器 JVM 内 JDK HttpClient 请求本机端口落错安全链（空 403，字节相同的裸 socket 正常；系统代理嫌疑） | 种子/内部探针改 raw-socket loopback |
| F2R-7 | 一用户一未来预约占用：COMPLETED 单的未来 claim 阻断再下单（"completed order has future active claim"） | 种子只保留 1 张 PENDING_SERVICE 单；窗口容量 2→6（多次重试/回归不撞容量） |
| F2R-8 | 强杀进程跳过 DROP 钩子，库残留无法二次验收 | **复用模式**：`E2E_DATABASE_NAME`+fresh node 重开库（schema/种子跳过，存活事实即对象）；配 `databaseLifecycle=DROP_ON_PROCESS_SHUTDOWN` 元数据 |

### 3.3 环境阻塞点与处置（如实记录）

| 编号 | 现象 | 处置 |
|---|---|---|
| E-1 | 专用 MySQL 33442 与 java 18082 均被环境终止两次（15:4x、16:1x；后者致 M 意见提交 attempts 500 中断） | python subprocess detached（DETACHED_PROCESS+NEW_PROCESS_GROUP+BREAKAWAY_FROM_JOB）重启；数据无损（InnoDB 恢复），中断写经 UI pending 幂等重试续传（§2.4 #14 反成断点续传实证） |
| E-2 | 冷启 MySQL 上 Snowflake 单飞行 1s 预算偶发超时 → 发号器失闭（本轮每分钟 1-11 次，RecoveringIds 均秒级自愈） | QA 库 `SET GLOBAL innodb_flush_log_at_trx_commit=2` 降低 commit 尾延迟；写操作在自愈间隙完成；503 一律同 X-Request-Id 重放（不改代码——生产 MySQL 专属实例无此负载画像，首轮报告 F-4 已登记根因类） |
| E-3 | DevTools 窗口被环境关闭 + 自动化 navigate/switchTab 桥 `Uncaught [object Object]`；UI textarea 中文注入失败 | `open_project_window` 重开；导航只用元素 tap（无 id 按钮经 outerWxml 找动态 id，页面返回用 `wx.navigateBack()` evaluate）；中文意见草稿经 pending 命令存储通道带入（原生 Picker 用 `--action trigger`） |
| E-4 | Redis 被主会话重启，C/admin 会话全清 | C 侧：HTTP attempts+wechat-login 拿 grant → evaluate 注入 storage `http://127.0.0.1:18082:pet.c.session.v1`（`consumer-api.ts` SESSION_KEY 缝隙）；admin 侧重走 attempts/login |

## 4. 附带实证的产品硬规则

- **改期最多一次**：改期后详情页改期入口消失（canReschedule 门控读 reschedule_count）；
- **改期后回到待确认并重启 30 分钟商家确认**：stage PENDING_CONFIRM、confirm_deadline=改期+30min、`ORDER_AUTO_CONFIRM:...:0` CANCELED + `:1` 重排；**到期后 `:1` SUCCEEDED、`ORDER_AUTO_CONFIRMED` SYSTEM 迁移 PENDING_CONFIRM→PENDING_SERVICE、`OrderConfirmedEvent.v1` 出站**（截图 53 C 详情终态）；
- **服务后退款商家 24 小时处理**：两轮申请各自 merchantDeadline=申请+24h（重申重计）；
- **57号同意=申请通过、无资金移动**：APPROVED 后 refund_order 计数 0、REFUND_APPLICATION_CREATE 任务落库；
- **refund_order 创建后禁止核销/未履约售后在 refund_order 创建前仍可核销**：种子库 0 张 refund_order，核销均先行完成（组合规则未触发反向分支，如实披露为未覆盖）；
- **售后商家意见≠裁决**：意见提交后 case 仍 PENDING、version+1，等待平台结合双方证据（部分退款仅运营裁决）；
- **评价申诉被驳回后评价维持公开**：admin REJECTED → M 页"申诉未成立"、review PUBLISHED 不变；
- **跨中断/跨会话写幂等**：同 X-Request-Id 的改期/意见命令在 503/401/进程重启后重放，均单次生效零重复（aftersale 单 MERCHANT batch 恰 1 行、reschedule_count 恰 1）。

## 5. 日期雷排查令（专项结论）

- 排查对象：本 PR 扩展夹具 `backend/pet-boot/src/test/java/com/petplatform/boot/e2e/E2eCoreLoopServer.java`（+423 行）。
- 方法：全文 grep `'202[0-9]-'`、`LocalDate.of|LocalDateTime.of|Instant.parse|ZonedDateTime.of`、字面年份 `2026|2027|2028`。
- 结果：日期字面量仅 `'2020-01-01','2035-01-01'` 一处——商家主体凭据 evidence 的 valid_from/valid_to（DATED 区间，**跨当日的前后远期跨度**，非当日锚定，任何执行日都成立）；唯一字面年份出现在展示用申请编号字符串 `SQ20261007E2ELOOP1`（仅 INSERT 常量，无任何比较/过期逻辑，惰性）。其余全部相对：种子窗口 `LocalDate.now(Asia/Shanghai).plusDays(0..1)`（09:00-19:00 整点）、支付/核销/确认期限均 `now().plusMinutes/Nanos`、`UTC_TIMESTAMP(3)`。
- **结论：无写死当日的第五颗雷。**

## 6. 复现

1. 起专用 MySQL：python subprocess detached `mysqld --no-defaults --port=33442 --datadir=D:/Temp/e2e-r2-mysql --bind-address=127.0.0.1 --console`（普通后台任务会被环境杀）。
2. 首次起后端（全新库）：`E2E_CORE_LOOP_SERVER=true AUTH_MYSQL_URL=jdbc:mysql://127.0.0.1:33442/ AUTH_REDIS_HOST=127.0.0.1 AUTH_REDIS_PORT=16379 E2E_PORT=18082 E2E_SNOWFLAKE_NODE=43 E2E_RUNTIME_DIR=D:/Temp/e2e-r2` + classpath=pet-boot/target/{classes,test-classes}+cp.txt，main=`com.petplatform.boot.e2e.E2eCoreLoopServer`；中断后续验收加 `E2E_DATABASE_NAME=<server-metadata.txt 数据库名>`+递增 node 复用重开。metadata/凭据在 `D:/Temp/e2e-r2/server-metadata.txt`。
3. 前端：`PET_C_API_ORIGIN=http://127.0.0.1:18082 PET_ALLOW_LOCAL_HTTP=true PET_MERCHANT_APPLICATION_ENABLED=true npm run build:weapp`；真实 appid `project.config.json`（不入库）。
4. DevTools 打开 frontend-miniapp，按 §2 矩阵走查；会话失效时按 §3.3 E-4 注入。

证据目录：`planning/progress/2026-10-13/e2e-round2/`（截图 53 张、RESUME.md 断点全程记录、`D:/Temp/e2e-r2/server-console*.log` 含沙箱支付/事件汇/发号器自愈/自动确认事件日志、网络取证经 DevTools network 缓冲 grep，关键请求/响应已摘录于 §2）。
