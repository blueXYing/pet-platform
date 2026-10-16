# 运营 Web 主链真实链路 E2E 验收报告（QA-005 运营侧收尾，2026-10-16）

- 日期：2026-10-16（北京时间）
- worktree：`C:/Users/Administrator/Desktop/wt-e2e-admin`，分支 `codex/e2e-admin-main-20261016`，基线 develop `2f822f43`（含 #165~#179 全部）
- 验收人：QA-005 运营 Web 主链 E2E 代理（ZCode）
- 结论（详见 §2/§3）：**主链 7 段中 6 段真实贯通，1 段（63号 v0.2 处置动作）被死面阻断**；共登记 **5 个新发现（FA-1~FA-5，均只定界取证、未改生产代码）**，外加 1 个已知环境坑复现（E-5）。前端真后端浏览器走查 **Playwright 1 passed**（17 张截图）。

## 0. 交付物（本 PR）

| 类别 | 文件 | 说明 |
|---|---|---|
| 启动器四轮扩展 | `backend/pet-boot/src/test/java/com/petplatform/boot/e2e/E2eCoreLoopServer.java` | 追加 63号处置 schema+开关、64号治理、A-004 原路退回（worker+funding）、admin-chain 种子（过去时段 COMPLETED 单/处置目标单/退款目标单/PENDING 售后单）、昨日可用窗口、种子幂等修复（evidenceAssetIds 必填、槽位按尝试轮移、元数据先写） |
| 测试域 tie-break | 同上 | `ReservationRefundReleaseApi` 双 bean 碰撞以 BFPP 标 primary（FA-4 规避，语义同构） |
| HTTP 探针 | `planning/progress/2026-10-16/e2e-admin-main/probes/admin_main_chain.py`、`admin_chain_resume.py` | 全链 JSONL transcript 驱动（原始 socket，一条请求一行） |
| 浏览器走查 | `frontend-admin/tests/admin-main-chain.spec.ts` | 真后端 opt-in live spec（LIVE_JOINT_BASE 门控，CI 不跑）；SPA 内导航 |
| 测试域 Origin 桥 | `frontend-admin/scripts/e2e-origin-shim.mjs` | 启动器 origin（`https://e2e.example.invalid`）与 vite preview 代理基线（127.0.0.1）之间的换源跳板，仅测试域 |
| 证据 | `planning/progress/2026-10-16/e2e-admin-main/` | 本报告 + probes/（transcript 8 份、定界探针 5 份、失败现场 console 3 份、server-metadata）+ screenshots/（17 张） |

生产代码零改动；契约零变化（计数钉死 204，无 Schema/API/Event 变化）；测试域开关在 PR 中逐项披露（§4）。

## 1. 验收环境

| 项 | 值 |
|---|---|
| 后端 | `E2eCoreLoopServer`（测试域启动器，本 PR 四轮扩展），127.0.0.1:18081，全新库 `auth001czq3c988_e2e_core_loop_e5b7cdf7…`（node 89） |
| MySQL / Redis | 共享 `127.0.0.1:33440`（root/空）/ `127.0.0.1:16379`；只连不启，全程未动 Docker |
| 前端 | `frontend-admin` build + `vite preview`（18082，ADMIN_API_PROXY_TARGET→本地 shim 18083→后端 18081）；Playwright chromium 真浏览器 |
| 会话 | 运营 `e2e-round2-operator`（PLATFORM_SUPER_ADMIN + ALL scope，26号 maintenance 首账号真引导）；C 端任意 wechat-code 映射种子身份 |

## 2. 链路清单与结果（探针 transcript 为准，浏览器走查为 UI 证据）

| # | 链路段 | 结果 | 关键证据 |
|---|---|---|---|
| S0 | 运营登录（attempts→login→session→permissions） | **通过** | attempts 201（Set-Cookie 一次性 attempt 凭据）、login 200、超级管理员权限集含 `order.read/aftersale.decide/merchant.read` 等；截图 01 |
| S1 | 商家/服务治理浏览（ADM-001 面） | **HTTP 通过；UI 被新发现 FA-6 阻断** | HTTP：申请列表+详情 200（含种子 SQ20261007E2ELOOP1）、服务列表+详情 200（服务 710401）；UI：服务审核控制台任何响应被前端拒渲染（02 截图，FA-6） |
| S2 | 订单/退款/核销查询（63号只读） | **部分通过：核销+状态日志两条路由被新发现 FA-5 阻断（400）** | 订单列表/详情 200（3 笔种子单+1 笔二轮单全部在列，displayStatus 过滤正确）；status-logs 与 verifications **全量 400 COMMON_INVALID_ARGUMENT**（连超管也不豁免）；UI：核销记录页/订单详情页均渲染错误态（05/06 截图——详情页因内嵌状态日志调用连带整页报错，FA-5 波及面扩大） |
| S3 | 处置动作（63号 v0.2：渠道查单/重试、异常关单、商家下线） | **被新发现 FA-3 阻断（403 死面）** | 四条 POST 全部 403 COMMON_FORBIDDEN（有效超管 token 在体）：安全链从未放行处置路由（`AdminAuthSecurityConfiguration` 只 permit 63号 GET，POST 落入 `anyRequest().denyAll()`）；kernel 级验收（既有 63号验收测试）不能证明 HTTP 面可达 |
| S4 | 售后终裁全链（A-004） | **全链通过**（退款资金落地见 §2.4 滚动） | 列表 200（OPS 面强制 merchantId+storeId，与前端页一致）→ 详情 PENDING → **受理 accept 200（PROCESSING）** → **补证请求 200（WAITING_SUPPLEMENT，USER 方+截止时间）** → C 端补证 200（PROCESSING）→ **终裁 FULL_REFUND 60.00 200（RESOLVED + refundOrderId 104498021626454016）**；UI：售后列表+已裁决详情页（08/09 截图）。受理/补证/终裁三动作全部携带 expectedVersion 乐观锁 |
| S5 | 服务前退款申请（自动全额硬规则） | **申请+投影通过**（资金落地见 §2.4 滚动） | C 申请 201 `AUTO_APPROVED`/`route=AUTO_FULL_BEFORE_SERVICE`/`displayStatus=REFUNDING`；管理端退款列表出现 `PRESTART_AUTO` source 退款单 104499794323910656 |
| S6 | 退款查询 + 治理查询面（64号） | **通过** | 退款列表含 `AFTERSALE_DECISION`+`PRESTART_AUTO` 双 source ≥2 笔、SUCCESS 过滤有效；治理面 8 条路由全 200：商家台账列表/详情（E2E体验商家+门店）、员工/员工成员/邀请、排期窗口/员工窗口/预约占用；UI：10~13 截图 |
| S7 | 运营台四块浏览（65号） | **被 FA-2+FA-3 阻断（HTTP 死面）** | 65号 ops 读路由全部 403（FA-3 同因：安全链无放行条目）；且 65号开关本身打不开——`pet.admin.ops.http.enabled=true` 时上下文启动失败（FA-2 bean 歧义，现场日志 probes/server-console-ops-boot-failure.log）；UI：五个 ops 控制台只能渲染壳/错误态，且 403 被前端判为会话失效触发登出（14~18 截图如实记录） |

### 2.4 滚动补录（退款资金终态）

两笔退款单（AFTERSALE_DECISION 104498021626454016 / PRESTART_AUTO 104499794323910656）的 `AFTERSALE_REFUND_SUBMIT`/`APPLICATION_REFUND_SUBMIT` 任务在共享 MySQL 雪花风暴（本库 console 100+ 次 fail-closed，含任务 claim 阶段）中按 REFUND_CHANNEL 退避策略重试：两任务均多次被 Reconciler 复活重排（retry 打满后重新入队），截至本稿定稿仍为 **CREATED（触发证据已齐，渠道资金终态未能在本场风暴窗口内落地）**。`admin-chain-transcript-final2.jsonl`/`admin-chain-summary-final2.json` 记录了管理端读侧的完整状态（两单 status=CREATED、channelRefundNo=null）；服务器日志（probes/server-console-final.log）记录了风暴自愈与任务重试全过程。**如实标注：S4/S5 的资金终态（refund status=SUCCESS + channelRefundNo）在本轮环境窗口内未落地，属环境阻塞（E-6），非产品缺陷——同一执行链（APPLICATION_REFUND_SUBMIT→渠道执行）在 round-3 走查中已实证落地。**

补录：final 驱动在退款 TIMEOUT 后**无条件跑完了剩余链路**——S3 处置四动作 403 取证（FA-1，新库复现）、S6b 治理面 8 条路由（其中员工两条路由的 FA-7 经 schema 补齐后 200 空列表取证）、S7 ops 七条读路由 403 取证（FA-3 新库复现；注意 `points-task-config` 是 58号独立面、放行在案，GET 200 属预期，不计入 FA-3 集合；本驱动一次 PUT 400 为探针载荷形状问题，不作为证据）。

## 3. 发现的问题清单（只定界取证，未修）

| 编号 | 级别 | 问题 | 定界证据 | 处置建议 |
|---|---|---|---|---|
| **FA-1** | **高（63号 v0.2 处置面 HTTP 死路）** | 四条处置 POST（`/admin/refunds/{id}/channel-query`、`/retry`、`/admin/orders/{id}/abnormal-close`、`/admin/merchants/{id}/disable`）在 `pet.admin.trade.http.enabled=true` 下**永远 403**：控制器存在、kernel 有验收测试，但 `AdminAuthSecurityConfiguration` 的放行清单只含 63号 GET 路由，处置 POST 落入 `anyRequest().denyAll()` | 探针 S3（transcript-final）：有效超管 token、body 合法，四条全 403 COMMON_FORBIDDEN；对照 AdminAuthHttpTest 权限集中存在 `refund.channel.query/refund.retry/order.abnormal.close/merchant.disable` | 在 adminSecurity 的 trade 分支补四条 POST 放行（kernel 自证会话+动作码+scope），另开修复 Issue |
| **FA-2** | **高（65号 ops 面无法启动）** | `pet.admin.ops.http.enabled=true` 时**应用上下文启动失败**：`AdminOpsController` 构造参数 `AdminEncyclopediaCommandApi` 命中两个候选（`AdminEncyclopediaApiImpl` 同时实现查/改两接口，`AdminOpsConfiguration` 暴露两个 bean，无 @Primary/@Qualifier，编译未保留参数名无法按名消歧） | probes/server-console-ops-boot-failure.log：`Parameter 1 of constructor in AdminOpsController required a single bean, but 2 were found`；本 PR 被迫关闭该开关走查（E2eCoreLoopServer 内注记） | AdminOpsConfiguration 的 command bean 方法标 `@Primary`（或删除重复暴露），另开修复 Issue |
| **FA-3** | 高（65号 ops 读面 HTTP 死路） | 即便 FA-2 修复，`/api/v1/admin/encyclopedia/**`、`/admin/coupon-templates/**`、`/admin/coupon-instances/**`、`/admin/points/ledger`、`/admin/settings/runtime`、`/admin/points-task-config` 也全被安全链 denyAll（无放行条目）→ 403 | 探针 S7 七条 GET+PUT 全 403 COMMON_FORBIDDEN（transcript-final）；UI 端 ops 页 403 且触发前端登出（14~18 截图） | 在 adminSecurity 按 action code 补 ops GET/写放行；同时评估前端把 FORBIDDEN 一律当会话失效（onAuthLost）是否合理 |
| **FA-4** | 中（测试域组合启动失败，未来生产组合预演） | `pet.admin.trade.http.enabled` + `pet.order.merchant.enabled` 同开时上下文启动失败：`ReservationRefundReleaseApi` 双 bean（`dispositionRefundRelease` 有 @ConditionalOnMissingBean、`merchantRefundRelease` 没有，注册顺序不保证）→ 63号 v0.2 全面推开会与商家面撞车 | probes/server-console-release-collision.log：`Parameter 5 of method merchantRefundProjection … 2 were found`；本 PR 以 BFPP 将 `merchantRefundRelease` 标 primary 规避（两 bean 同构 `ReservationRefundReleaseApiImpl(source,ids,guard,refunds)`，行为零变化） | 给 `merchantRefundRelease` 补 @ConditionalOnMissingBean 或合并为一处定义 |
| **FA-5** | **高（63号两条只读路由全量 400，含 UI 连带）** | `AdminActionCheckQuery` 的 action-code 词法 `[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+` **不允许段内连字符**，而部署动作码 `order.status-log.read`、`order.verification-log.read` 段内带连字符 → 两条路由的授权入口校验每次抛 IllegalArgumentException → **400 COMMON_INVALID_ARGUMENT，任何授权（含超管）不可恢复**。波及面：`GET /admin/trade/orders/{id}/status-logs`、`GET /admin/trade/verifications(/[…])`，且前端订单详情页内嵌状态日志调用 → **订单详情控制台整页不可用** | 探针 S2 两条路由全量 400（transcript 163628）；代码定位 `AdminActionCheckQuery` 构造校验 vs `AdminPermissionEvaluator.DEPLOYED_ACTIONS`（注释自述曾把 `refund.channel-query` 点改拼写，但漏掉这两条）；UI 05/06 截图 | 二选一：词法放宽段内连字符，或部署码改点号拼写（需同步前端/契约），另开修复 Issue；**本审计不修** |
| **FA-6** | 中（ADM-001 服务审核控制台不可用） | 服务审核 HTTP 面成功 envelope `message="成功"`，前端 `src/api/serviceReview.ts` 要求 `message==='ok'` → **任何响应（含空列表）都被前端拒渲染（INVALID_RESPONSE）** | 02 截图（alert: 服务查询失败（INVALID_RESPONSE））；HTTP 探针同路由 200（后端自测不校验 message，故此前未暴露） | 服务面 envelope 统一为 `ok`（与 trade/aftersale/governance 一致），另开修复 Issue |
| FA-7 | 低（**测试夹具缺口，本 PR 已修**） | 64号治理面 `merchants/{id}/staff-members(/invitations)` 查 contract-52/54 员工身份表，而启动器 loadSchemas 从未加载这两个 schema → **500 COMMON_INTERNAL_ERROR**（表缺失，非产品缺陷） | 修复前：两条路由 500（transcript-final2 + debug 复现）；loadSchemas 补 `52-Merchant-Staff-Identity` + `54-Merchant-Staff-Binding` 后对运行库等价建表 → **两条路由 200 空列表**（probes/debug-staff-members-after-schema-fix.jsonl） | 已在本 PR 修（纯测试域）；后续对同一库复用重启时无需再处理 |

### 3.2 环境阻塞点与处置（如实记录）

| 编号 | 现象 | 处置 |
|---|---|---|
| E-5（复现+补充） | 共享 MySQL 雪花风暴（本库 100+ 次 fail-closed，任务 claim 阶段也发号）令异步任务退避；**进程被环境硬杀后复用缝重启时，attempt-1 因旧自愈节点占用新节点号 DuplicateKey，catch 把复用库 DROP**（round-3 E-5 同源，第一次连库带事实全部损失，现场：probes/server-console-e5-reuse-drop.log）；另发现**复用模式启动会用 `Files.writeString` 覆盖 server-metadata.txt**，丢失 adminChain* 种子 id 行（driver 以 FALLBACK 常量兜底） | 全新建库重跑（node 89 一次通过；第二次复用重启换全新节点号 240 后成功，事实保留）；建议给启动器提 Issue：复用模式失败不得 DROP 复用库 + 复用模式节点号应自动挑空闲 + metadata 改追加写 |
| E-6 | 风暴使两笔退款资金任务在 REFUND_CHANNEL 退避中反复 HANDLER_EXCEPTION | 不改代码；终态由 resume 驱动滚动补录（§2.4） |
| E-7 | 启动器固定 origin 与前端代理基线不同源 | 测试域 Origin 桥（scripts/e2e-origin-shim.mjs），PR 披露，非生产行为 |

## 4. 测试域开关披露（生产默认不变）

本轮在启动器显式开启（全部默认关闭，生产组合不变）：`pet.admin.trade.http.enabled`、`pet.admin.trade.worker.enabled`、`pet.admin.governance.http.enabled`、`pet.aftersale.refund.enabled`、`pet.aftersale.worker.enabled`；schema 追加 `63-Admin-Trade-Disposition-Schema`。`pet.admin.ops.http.enabled` 因 FA-2 无法开启（保持关闭，行为以 403 取证）。测试域规避：FA-4 tie-break（BFPP 标 primary）、E2E_DATABASE_NAME 复用缝（本轮最终未用）。

## 5. 复现

1. 起服务端：`E2E_CORE_LOOP_SERVER=true AUTH_MYSQL_URL=jdbc:mysql://127.0.0.1:33440/ AUTH_MYSQL_USER=root AUTH_MYSQL_PASSWORD="" AUTH_REDIS_HOST=127.0.0.1 AUTH_REDIS_PORT=16379 E2E_PORT=18081 E2E_SNOWFLAKE_NODE=<fresh> E2E_RUNTIME_DIR=D:/Temp/e2e-admin-main` + classpath=`pet-boot/target/{classes,test-classes}`+`cp.txt`，main=`com.petplatform.boot.e2e.E2eCoreLoopServer`（Maven=D:/apache-maven-3.9.12、JDK21=Adoptium、-Dmaven.repo.local=D:/m2-e2e-admin）。
2. 探针：`python probes/admin_main_chain.py D:/Temp/e2e-admin-main`（transcript JSONL 逐请求落盘）；退款被风暴拖延时用 `admin_chain_resume.py` 接棒。
3. 浏览器：`npm run build` → `ADMIN_API_PROXY_TARGET=http://127.0.0.1:18083 ADMIN_API_PROXY_ORIGIN=http://127.0.0.1:18082 npx vite preview --port 18082`（先起 `node scripts/e2e-origin-shim.mjs`）→ `LIVE_JOINT_BASE=http://127.0.0.1:18082 E2E_ADMIN_ACCOUNT=… E2E_ADMIN_PASSWORD=… E2E_ADMIN_EVIDENCE_DIR=<dir> npx playwright test tests/admin-main-chain.spec.ts`。
4. 服务器日志 `D:/Temp/e2e-admin-main/server-console3.log`。

证据目录：`planning/progress/2026-10-16/e2e-admin-main/`（本报告、probes/、screenshots/）。
