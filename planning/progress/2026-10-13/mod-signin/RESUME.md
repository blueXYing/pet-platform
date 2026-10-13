# RESUME.md — mod-signin 切片进度（签到+拉新+积分量后台配置+页面+测试+PR）

分支 `codex/mod-signin-20261013`（基 develop 6263f17）。任务：32号裁决第二批①。
若再遇 1310 限额错误：更新本文件后立即停止。

## 接力补记（2026-10-14 第二棒）

- 订单链核查结论：**保留**（纯只读加法 `SELECT MIN(paid_at)`，52 行，biz→api 有 checkReviewEligibility 同接口先例；PR 论证已在本文件 §5）。
- boot 三文件已存在但有小错，已修：AdminPointsTaskConfigController 的 `PointsTaskQueryApiImpl.ConfigQuery` → `PointsTaskQueryApi.ConfigQuery`（嵌套在 api 接口）；PointsTaskService `var[]`→`var`、裸 `Canonical`→`PointsTaskCanonicalParams.Canonical`（6 处）、`configReceipt(binding.receiptJson())`→`receipt(..., PointsTaskService::configReceipt)`、`long balanceAfter = -1` 声明处初始化。
- 四处安全接线完成：CSessionSecurityConfiguration（+pet.points.task.http.enabled 开关+GET status/tasks+POST sign-in/claims/referral-bindings 五条 permitAll）、AdminAuthSecurityConfiguration（+开关+GET/PUT points-task-config）、CBearerSessionFilter.protectedPath（+五条精确路径）、AdminPermissionEvaluator.DEPLOYED_ACTIONS（+points.task.config.read/write；AdminAuthPrimitivesTest 同步 13 码）。
- **编译教训**：`-pl` 必须带 `pet-points-api`（本地仓库是旧 SNAPSHOT，不带会报 PointsTaskTypes 不存在）。标准命令：`mvn -q -o compile -pl pet-points-api,pet-points-biz,pet-admin-biz,pet-order-api,pet-order-biz,pet-boot`。
- 内核笔误已核：合同 §3.3 `milestone:{required,status,progress}` 实现为平铺 `milestoneStatus/milestoneProgress/milestoneRewarded`——OpenAPI 按实现登记（PointsTaskTypes.InviteOverview 形状），58 号文档 §3.3 字段描述待 PR 附注对齐（不改行为）。

## 做到哪（2026-10-14 复活盘点，git status 全对账）

### 已完成（已核对/已编译通过）
1. **侦查完成**：契约链（contract_smoke.py 家族模式 + test_private_asset_contract 计数 operations=143）、积分表（06§9）、幂等先例（通知偏好切片 14号绑定表 admission/execution 双事务）、admin RBAC（AdminPermissionEvaluator.DEPLOYED_ACTIONS + AdminBearerAuthenticationFilter.VIEW + AdminAuthSecurityConfiguration permitAll 开关族）、C 链（CBearerSessionFilter.protectedPath + CSessionSecurityConfiguration）、设计稿三树（签到 690:11773 / 拉新 129:5854 / 领取奖励 129:7677，从 D:/Temp/figma-mcp/user-end-page1.xml 提取）。
2. **文档已写**：
   - `planning/ccr/CCR-POINTS-TASK-001.md`（细则 S1~S3/R1~R4/M1~M2/A1~A3 定稿=最保守口径）
   - `docs/04-api/58-Points-Task-Contract-v0.1.md`（7 操作 HTTP 契约）
   - `docs/03-database/58-Points-Task-Schema-v0.1.sql`（4 新表：points_task_config/points_sign_in/points_referral_binding/points_task_award）
3. **pet-points-api**（新，编译通过）：PointsTaskCommandApi（signIn/bindReferral/claimReward/updateConfig）、PointsTaskQueryApi（signInStatus/tasks/config）、PointsTaskTypes（DTO 全家）。
4. **pet-points-biz**（新，编译通过）：PointsTaskService（内核全部规则）、PointsTaskStore（事务壳+幂等绑定，仿通知偏好店）、PointsTaskMapper+.xml（全部 SQL）、5 实体、InviteCodeCodec（P+13位base36+1校验位，可逆）、PointsTaskFacts（跨域事实端口）、PointsTaskCanonicalParams（canonical-v1 镜像）、两个 apiimpl；pom 加 jackson-databind。
5. **pet-order-api/biz 增补**（编译通过，已评估保留）：OrderQueryApi.firstPaidAt(UserOrderFactQuery)（07号§7.1 增补）+ Impl + Service + Mapper XML 一条 MIN(paid_at) 聚合。**理由**（PR 已论证）：拉新"注册并下单"条件需要被邀请人首付事实；PaymentSucceeded/OrderPaid 载荷无 userId、事件路径必须读 order 持久层（ARCH-002 禁）；listMyOrders 主体锁死会话用户不能查被邀请人；biz→api 是既定模式（verification/review 先例）。只读、加法、52 行。
6. **环境**：两个 npm ci 完成；MySQL 33440 / Redis 16379 连通；JAVA_HOME 就绪。

### 设计决定（防遗忘）
- 7 个新操作：cGetSignInStatus/cSignIn/cListPointsTasks/cClaimPointsReward/cBindReferralCode（C 面）+ adminGetPointsTaskConfig/adminUpdatePointsTaskConfig（admin 面）。计数 143→150。
- 开关：pet.points.task.enabled（内核，硬依赖 Snowflake bean）+ pet.points.task.http.enabled（HTTP；C 控制器另需 auth.c，admin 控制器另需 auth.admin；http 开而内核关=启动失败 guard）。
- RBAC 新码：points.task.config.read / points.task.config.write（登记进 AdminPermissionEvaluator.DEPLOYED_ACTIONS）。
- 日边界/月周期=Asia/Shanghai（合同文档里 R4 写了"每自然月（UTC）"——**待统一改成 UTC+8**，与日界一致）。
- 积分 ledger 写法：biz_type SIGN_IN/INVITE/TASK；ledger request_id=命令 requestId（单行）或 requestId|bindingId（多人头）；余额=upsert 原子加+selectBalance。
- 签到冲突=409 COMMON_CONFLICT；绑定违规=400/409；无可领取=409。
- ConfigView 缺省 5/30/50/3/100/100/20。

## 接力补记（2026-10-14 第三棒盘点）

- 订单链二核查（第三棒）：**保留**。diff 复核确认纯只读加法（api 接口+record / Impl 委派 / Service 一段 / Mapper 接口一行 / XML 一条 `SELECT MIN(paid_at)`，共 52 行，无既有语句改动），`checkReviewEligibility` biz→api 先例成立，注释已引 07号§7.1+CCR。PR 论证沿用 §5。
- **远端核查**：origin/develop 仍在 6263f17（#154），**#155 未并入**——本分支计数 143→150 维持正确；若 PR 前 #155 先合（它也+7），rebase 时把两处计数断言（operations / pointsTaskOperations 若重名）解到 157。
- 实际进度远超第二棒 RESUME：boot 装配、C/admin 控制器、PointsTaskHttpTest（C+admin 两面已写完）、PointsTaskMySqlTest、OpenAPI 723 行、契约 10/12/20/21/07、e2e 双文件、miniapp 三页+model/repository+mine 接通**全部已在工作树**。
- R4 时区：CCR §R4 仍写"每自然月（UTC）"，58 号 §90 已是 UTC+8——**待核实现**（biz 内核月界取 Asia/Shanghai 即改 CCR 一行）。
- 第三棒发现的缺口：①`TempDebugAdvice.java` 调试残留待删（GlobalApiExceptionHandler/AdminAuthExceptionHandler 在，删除后需测试复验）；②app.config.ts **未注册** points-tasks 分包（页面进不了包）；③`src/consumer/tests/points-tasks.test.ts` 缺；④**frontend-admin 全部未动**（api 客户端/页面/路由/导航/夹具）；⑤全部测试未复验。

## 接力补记（2026-10-14 第三棒·收尾）

第三棒修复与补齐（全部完成）：
- 删 `TempDebugAdvice.java`（调试残留）；GlobalApiExceptionHandler 链路复验通过。
- **补 `AdminBearerAuthenticationFilter` 路径表** `/api/v1/admin/points-task-config`（第二棒漏登记导致 admin GET 401——过滤器不解析会话，控制器拿不到 VIEW）。
- PointsTaskHttpTest INSERT 修复：16 `?` 对 18 列多一值 → 15 `?` + paid_at=UTC_TIMESTAMP(3)。
- CCR R4 月界文案改 UTC+8（内核 `today()` CN 派生 `period()`，实现本就是 UTC+8）。
- app.config.ts 补注册 points-tasks 分包（第二棒漏）；三页 `<Text role>` 改 View 包 Text（TextProps 无 role，typecheck 红）。
- **契约裂缝修复**：控制器 /tasks 内嵌 signIn 是三字段摘要（11 号 PointsTaskSignInSummary，无 week），model.ts 却复用 decodeSignInStatus 强制 week → 真实响应必解码失败。model 增 `SignInSummary`/`decodeSignInSummary`，decodeSignInStatus 加 exact-key（家族惯例）。
- 新增 `src/consumer/tests/points-tasks.test.ts`（解码器+周条+分享文案+repository 五路由幂等槽，7 用例）。
- frontend-admin 三件套：`api/pointsTaskConfig.ts`（GET/PUT 客户端，七字段范围镜像，编辑换新 requestId、重试复用原号）+ `pages/PointsTaskConfigPage.tsx`（读+全量 PUT 表单+版本显示）+ App.tsx（路由/导航/`/` 兜底重定向，gate=points.task.config.read，canWrite=config.write）。FixtureApp 是纯工程壳无需改。

测试证据（全绿）：
- pet-points-biz PointsTaskMySqlTest **10/10**（PTSQT_MYSQL_URL=jdbc:mysql://127.0.0.1:33440/）。
- pet-boot PointsTaskHttpTest **2/2**、CAuthHttpTest **7/7**（曾与 npm 并行跑出 6 败——MySQL 连接被并发负载打断的环境噪声，单独重跑全过）、AdminAuthPrimitivesTest **12/12**。
- e2e 契约烟测 **42 passed**（operations==150 + pointsTaskOperations==7）。
- check-persistence-style **PASS**；pet-architecture-test **31/31**。
- miniapp：typecheck 过 + **541/541** + build:weapp 成功（dist 含 signin/tasks/rewards 三页）。
- frontend-admin：build+check:boundaries **PASS**；playwright 见下。

## 下一步（按序）
1. **boot 装配**（当前断点）：
   - `config/PointsTaskConfiguration.java`：@ConditionalOnProperty pet.points.task.enabled；guard http→enabled；beans：PointsTaskCommandApiImpl/PointsTaskQueryApiImpl（注入 DataSource+ObjectProvider<SnowflakeIdGenerator>+ObjectProvider<Clock>+PointsTaskFacts 适配器）；PointsTaskFacts 适配器=boot 层调 OrderQueryApi.firstPaidAt + UserQueryApi.getUser（ObjectProvider 惰性，缺 bean 时 503 fail-closed）。**注意**：UserQueryApiImpl 无既有 boot bean——适配器直接 new UserQueryApiImpl(dataSource)/引用 OrderQueryApiImpl？不行——跨域。方案：适配器注入 ObjectProvider<OrderQueryApi> 与 DataSource 自建 UserQueryApiImpl？UserQueryApiImpl 是 user-biz 类，boot 可依赖（boot 依赖 biz 合法）。用 ObjectProvider<OrderQueryApi>（pet.auth.c 时由 OrderQueryReadConfiguration 提供）+ 自建 UserQueryApiImpl（轻、只读）。
   - `adapter/web/c/CPointsTaskController.java`：@ConditionalOnProperty(name={"pet.auth.c.enabled","pet.points.task.http.enabled"})；GET sign-in/status、POST sign-in、GET tasks、POST claims、POST referral-bindings；严格 JSON（仿 CNotificationPreferenceController：未知字段/显式 null/重复键/尾随=400，query 全禁）；X-Request-Id 终端 UUID；no-store。
   - `adapter/web/admin/AdminPointsTaskConfigController.java`：@ConditionalOnProperty(name={"pet.auth.admin.enabled","pet.points.task.http.enabled"})；GET/PUT points-task-config；RBAC 仿 AfterSaleHttpSupport.context（AdminBearerAuthenticationFilter.VIEW→AdminSessionView，actionCodes.contains）。
   - CSessionSecurityConfiguration：+@Value pet.points.task.http.enabled + permitAll 五条 C 路由。
   - AdminAuthSecurityConfiguration：+同开关 permitAll GET/PUT /api/v1/admin/points-task-config。
   - CBearerSessionFilter.protectedPath：+五条 C 路径。
   - AdminPermissionEvaluator.DEPLOYED_ACTIONS：+两码。
2. **OpenAPI**（docs/04-api/11-OpenAPI-Core-v0.4.yaml）：7 操作 + PointsTask* schemas + ErrorEnvelope（错误码集合=COMMON_INVALID_ARGUMENT/UNAUTHORIZED/FORBIDDEN/CONFLICT/IDEMPOTENCY_KEY_CONFLICT/DEPENDENCY_UNAVAILABLE/INTERNAL_ERROR）。仿通知偏好小节（12063 行起）。
3. **contract_smoke.py**：POINTS_TASK_OPERATIONS 家族 + check_points_task + 联合集 + 计数字段；test_private_asset_contract.py：operations 143→150、+pointsTaskOperations=7、注释链。
4. **契约 10 号**：§3.15.4（C 五路）+ §5.9（admin 两路）摘要小节（指向 58 号）。**07 号 §7.1**：firstPaidAt 增补登记（实现状态注）。**21 号开关清单**：§1.11 积分任务域两开关 + §2 guard 行。**20 号登记表**：§4.12（三页实现登记：视觉沿用 690:11773/129:5854/129:7677 原稿，功能接 58 号契约；设计稿"兑换/消费"不做；129:7677 标题栏文字"拉新任务"系原稿笔误，实现按"奖励领取"）。**12 号错误码**：确认无需新增（全部复用）。
5. **测试**：
   - pet-points-biz PointsTaskMySqlTest（仿 PointsQueryMySqlTest：自建随机库执行 58+06+14 SQL；env 前缀 PTSQT_MYSQL_URL→AUTH_MYSQL_URL 兜底；SequenceIdStub 造 id；Clock.fixed；PointsTaskFacts.fixed()）。场景：签到当日一次+重放+异 requestId 409+次日+连签+断签清零；7 连签里程碑领取一次；邀请码编解码往返+自邀 400+无效 400+有单 409+重复绑 409；绑定后支付→claim 人头（月上限截断、次月续领）；首次下单/完善资料领取各一次；config 默认读+PUT 越界 400+重放+异参 409+改后新领取按新值。
   - pet-boot PointsTaskHttpTest（仿 NotificationPreferenceHttpTest：CAuthHttpTest.HttpFixture——auth.c+points.task.(http.)enabled，fixture 库跑 58/06/14 SQL；登录两用户；五 C 路由 happy+严格 JSON+401+409 重放）。admin 面测试若 admin 栈太重，至少内核覆盖 config RBAC 码评估（AdminPermissionEvaluatorTest 有先例）；或仿 ReviewAppeal admin HTTP 测试（如存在）。**查**：是否有 admin 面 HTTP 测试先例可抄。
   - e2e：python -m pytest e2e/test_contract_smoke.py e2e/test_private_asset_contract.py（离线文档烟测）。
6. **前端 miniapp**：
   - 新分包 `consumer/pages/points-tasks`（app.config.ts 注册 subPackages +1 行：pages: ['signin','tasks','rewards']）。
   - `consumer/points-tasks/model.ts`（视图模型+解码器，node 测试用）+ `repository.ts`（RealPointsTaskRepository 走 ConsumerApi.request；路径全部 /^\/api\/v1\/c\// 白名单内不用改 consumer-api.ts；写走 api.write(slot,...) requestId 语义）。
   - 页面 x3：signin（ConsumerPageLayout+周条+立即签到按钮+三快捷，入口拉新/奖励/积分明细）、tasks（面板式：标题+关闭+四任务行+邀请码+复制/分享）、rewards（面板式：三里程碑行+领取按钮）。CSS 仿 mine.css 的 mine-signin* 段与 coupon-points.css；**WXSS 铁律**：button reset（padding/margin/border/border-radius/background/font/line-height 0-1-1 式重置+::after border 0）、选择器不嵌套、**不使用 data-* 属性**、类名前缀区分。色值：主蓝 #5baae8/描边 #c0ecff/浅蓝底 #f0fbff。
   - mine/index.tsx 接通：签到卡（View→Button onClick navigateTo signin 页；连签数接真实 status——失败保持 0 占位语义）、拉新任务/奖励领取两个 quick cell（View→Button navigateTo tasks/rewards）。
   - 单测：src/consumer/tests/points-tasks.test.ts（model 纯函数）；npm run typecheck+test+build:weapp。
7. **admin web**：`src/api/pointsTaskConfig.ts` 客户端 + `src/pages/PointsTaskConfigPage.tsx`（读+全量 PUT 七字段表单+版本显示）+ App.tsx 路由 /points-task-config + 导航（gate=points.task.config.read）+ FixtureApp.tsx 夹具；npm run build + check:boundaries（查 playwright 测试是否需要补——看现有 tests/ 结构再定）。
8. **检查**：backend/tools/check-persistence-style.py；pet-architecture-test 定向；e2e python；汇总证据。
9. **提交+PR**：分支已对；不 push develop/main；不提交 project.config.json（git status 确认无此文件改动）；PR 描述含规则披露清单（S1~S3/R1~R4/M1~M2/A1~A3 全部待用户复核）+ 订单侧改动的论证 + 测试证据 + 遗留风险（事件替代方案、firstPaidAt 无索引扫描性能注记、COMMUNITY_POST 未开放、资料完善条件收窄为昵称）。

## 坑（防再死）
- **禁止全量 reactor**：机器有两验收服务器+其他代理。测试用 -pl 模块定向。
- maven 命令带 JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"。
- 长命令后台+60-90s 轮询。
- MySQL 测试 env：AUTH_MYSQL_URL="jdbc:mysql://127.0.0.1:33440/"（注意尾部斜杠+无库名）AUTH_REDIS_HOST=127.0.0.1 AUTH_REDIS_PORT=16379（boot HTTP 测试用；内核测试 PTSQT 或 AUTH 前缀）。root/空密码。
- contract_smoke 计数断言在 test_private_asset_contract.py::test_current_surface_independent_counts（operations==143→150）。
- 通知偏好控制器是 C 写路由的样板（严格 JSON reader=MerchantJsonReaderFactory.strictReader()）。
- AdminSessionView 包路径：com.petplatform.admin.biz.**（查 AfterSaleHttpSupport import）。
- app.config.ts subPackages 追加一行带注释（家族惯例）。
- frontend-admin 有 FixtureApp + playwright；改动页面后跑 npm run build（含 typecheck）+ 看 tests/ 是否有路由冒烟需要更新。
- 设计稿三树已提取到 D:/Temp/figma-mcp/signin-tree.txt、invite-tree.txt、reward-tree.txt。
