# CCR-POINTS-TASK-001：签到 + 拉新任务模块（写路径 + 运营配置）契约提案

状态：**PROPOSED（32号裁决第二批①实施切片，2026-10-13）**。裁决依据：`docs/01-prd/32-售后资金出路与未建页面裁决补充-v1.0.md` 第二节第 2/3 条——"签到与拉新的积分发放量必须支持后台配置（运营侧配置项）；其余规则按常规签到/拉新语义执行——涉及产品规则的具体细则（如防重复口径、拉新成立条件）由各切片 CCR 定稿，**需裁决处回禀，不自创**；各页契约缺失走 CCR 流程"。用户 2026-10-13 口径：凡涉及产品细则的选择，**采用最保守的标准做法并在 PR 里逐条披露待用户复核，不自创激进规则**。本 CCR 按该口径定稿细则；全部细则在 PR 披露清单中回禀复核。提出方/唯一 Writer：Backend（mod-signin 切片，分支 `codex/mod-signin-20261013`）。

## 一页结论

develop（6263f17）上签到/拉新无可用的写事实链：`pet-points-api` 仅有只读 `PointsQueryApi`（07 号 §13.1，PR#113 起已实现）；`points_ledger.biz_type` 五枚举中 SIGN_IN/INVITE/TASK 三类无任何产生路径（PTS-001 家族 BLOCKED）；运营后台无积分配置面；C 端三页（签到 690:11773、拉新任务 129:5854、领取奖励 129:7677）无契约无路由。本切片按 32 号裁决交付：签到 + 拉新任务 + 任务里程碑的**赚取写路径**、**运营配置读写面**与 **C 端三页**。积分配账一律走 `points_account` + `points_ledger`（只赚取，无消费）；V1 硬规则不变。

## 事实基础（本次核实，非提案内容）

- Schema 事实（06 号 §9）：`points_account`（user 唯一、balance/version）；`points_ledger`（biz_type SIGN_IN/INVITE/TASK/ORDER_REWARD/REFUND_CLAWBACK、delta 带符号非零、balance_after、`uk_points_request` 全局幂等键、`idx_points_order_type`）。奖励命令 API `PointsRewardApi`（07 号 §13.2）为文档形态（PTS-001/PTS-002 BLOCKED，"订单奖励优先通过事件触发"）。
- 幂等基线：写操作 requestId 幂等（AGENTS）；先例 = 通知偏好切片（14 号 `command_idempotency` 绑定表，admission/execution 双事务，23 号 §5.4 首次成功回执重放、异参 409）。
- 运营配置先例：通知偏好 GET/PUT（版本号 + 全量 PUT + 严格 JSON）；部署开关清单（21 号）登记装配面；admin 面 RBAC 动作码由 `AdminPermissionEvaluator.DEPLOYED_ACTIONS` 登记（先例 review.appeal.read/decide）。
- 事件事实：`PaymentSucceededPayload`/`OrderPaidPayload` **均不携带 userId**，且 order 表属 order 域持久层（ARCH-002 禁跨模块读）；被邀请人"完成支付"事实在积分域不可由事件直接解析。`OrderQueryApi.listMyOrders` 主体固定为会话用户（本人订单可查、他人不可查，防枚举）。
- 设计事实（20 号登记表 §3）：签到 `690:11773`（每日签到卡：连续签到 N 天 + 7 日周条 + "立即签到 · 得 5 积分" + 拉新任务/奖励领取/积分明细三快捷）；拉新任务 `129:5854`（任务面板：邀请好友注册"注册并下单，50 积分/人"进度 2/3、发表社区动态 20 积分 0/1、完成首次下单 100 积分 已完成、每日签到 5 积分/天）；领取奖励 `129:7677`（里程碑面板：连续签到 7 天 +30 未达成、邀请 3 位好友 +100 领取、完善个人资料 +20 领取）。设计含"积分商城/兑换"入口——V1 硬规则禁止消费/兑换，本切片不做（32 号第一节同口径）。
- 用户资料事实：`UserQueryApi.getUser`（07 号 §3.1）返回 nickname；头像/个性签名不在内部 API 投影内。

## 提案（细则按"最保守标准做法"定稿，逐条入 PR 披露清单回禀复核）

### P1 C 端写/读表面（会话主体，登录态，随 `pet.auth.c.enabled` + `pet.points.task.http.enabled` 装配）

1. `GET /api/v1/c/points/sign-in/status`——签到状态视图：`signedToday`(bool)、`streak`(int≥0)、`dailyPoints`(String，当前配置)、`week`（近 7 个自然日 `[{date:"YYYY-MM-DD", signed:bool}]`，真实日历不虚构）。只读、query 全禁止。
2. `POST /api/v1/c/points/sign-in`（X-Request-Id）——今日签到。规则（细则 S1~S3，见下）：每日一次、固定面额、立即入账（biz_type=SIGN_IN）。回执 `{signedDate, points, streakAfter, ledgerId, balanceAfter}`；同 requestId 同参重放首成回执；**当日再次以新 requestId 签到 = 409 COMMON_CONFLICT**。
3. `GET /api/v1/c/points/tasks`——任务中心 + 奖励视图（一读两页共用）：`inviteCode`(本人邀请码)、`signIn`（状态摘要）、`invite`（perInviteePoints、monthlyRewardCap、rewardedThisMonth、claimableCount、milestone 状态）、`tasks[]`（taskCode/title/description/points/status/progress——INVITE_PER_HEAD、FIRST_ORDER、SIGN_IN、COMMUNITY_POST 四行；COMMUNITY_POST 恒为 NOT_OPEN，不做任何社区积分）、`rewards[]`（SIGN_IN_STREAK_7、INVITE_MILESTONE_3、PROFILE_COMPLETE 三里程碑：points/status[NOT_ACHIEVED|CLAIMABLE|CLAIMED]/progress）。query 全禁止。
4. `POST /api/v1/c/points/claims`（X-Request-Id，体 `{claimType}` 五枚举之一）——奖励领取（全部赚取动作除签到外统一走本面）：INVITE_PER_HEAD（一次性领取当前可领且不超月上限的全部人头）、FIRST_ORDER、SIGN_IN_STREAK_7、INVITE_MILESTONE_3、PROFILE_COMPLETE。回执 `{claimType, pointsGranted, ledgerIds[], balanceAfter}`；无可领取 = 409 COMMON_CONFLICT。
5. `POST /api/v1/c/points/referral-bindings`（X-Request-Id，体 `{inviteCode}`）——被邀请人绑定邀请码（细则 R1~R4）。回执 `{bindingId, inviterBound: true, createdAt}`。

### P2 运营配置表面（admin 面，随 `pet.points.task.http.enabled` + `pet.auth.admin.enabled` 装配）

6. `GET /api/v1/admin/points-task-config`——配置视图（七项发放量参数 + version + updatedAt；无行读内置缺省：5/30/50/3/100/100/20）。
7. `PUT /api/v1/admin/points-task-config`（X-Request-Id）——全量 PUT（通知偏好先例：严格 JSON、未知字段/显式 null 均 400、同 requestId 同参重放、异参 409）。范围：`signInDailyPoints`[1,1000]、`signInStreakBonusPoints`[0,1000]、`invitePerInviteePoints`[0,1000]、`inviteMonthlyRewardCap`[0,100]、`inviteMilestoneBonusPoints`[0,1000]、`firstOrderPoints`[0,1000]、`profileCompletePoints`[0,1000]。RBAC：`points.task.config.read` / `points.task.config.write` 两动作码（登记进 DEPLOYED_ACTIONS；单运营编辑获权直接发布，24 号裁决，无双人审批）。

### 细则定稿（S=签到 / R=拉新 / M=里程碑 / A=防刷，全部入 PR 披露清单）

- **S1 每日一次**：每用户每自然日一次；自然日按 **Asia/Shanghai (UTC+8)** 计算（面向国内用户的行业标准；库内连接仍 UTC，日期在服务端计算后落 DATE 列）。`uk (user_id, sign_date)` 唯一键兜底并发。
- **S2 固定面额、不做连续递增**：签到无连签加成系数（设计稿"连续签到奖励更多"仅以下述 S3 一次性里程碑实现），避免自创连续奖励曲线。`连续签到 N 天` = 自今日（或昨日，今日未签时）回溯的连续已签天数，断签即清零重计；**不补签**。
- **S3 连续 7 天一次性里程碑**：`当前`连续签到 ≥7 天且未领取过 → SIGN_IN_STREAK_7 可领取（+signInStreakBonusPoints）；领取一次后终局（CLAIMED）。断签后当前连签 <7 则回到不可领（保守：不按历史最高连签追溯）。
- **R1 邀请码**：由本人 userId 确定性生成（`P` + base36(id) 定长 + 1 位校验字符，可逆可校验、不可枚举他人收益），不入库；无效码/用户不存在 = 400。
- **R2 绑定条件（拉新成立条件之一）**：被邀请人绑定时**从未在平台下过任何订单**（以 order 域事实 `firstPaidAt(invitee) == null` 判定，见 P3）；每被邀请人**终身仅可绑定一次**（首绑即终局，不可换绑）；不可自邀（自己码 400）。不满足 = 409 COMMON_CONFLICT。
- **R3 人头奖励成立条件（拉新成立条件之二，即设计"注册并下单"）**：被邀请人**绑定之后完成首笔成功支付**（`firstPaidAt(invitee) != null && firstPaidAt > binding.createdAt`）。绑定前已支付过的用户因 R2 已被排除；绑定后退款不追回已成立标记（V1 无扣回 INVITE 的规则依据，保守不扣，披露）。
- **R4 月上限**：每自然月（Asia/Shanghai UTC+8，与 S1 日界同口径）每邀请人最多领取 `inviteMonthlyRewardCap` 个人头奖励；超出部分留待次月仍可领（绑定不失效）。0 = 关闭人头奖励（里程碑独立计量）。
- **M1 里程碑均为一次性（终身一次）**：INVITE_MILESTONE_3 以**已领取人头奖励数 ≥3** 计量（不含待领取）；PROFILE_COMPLETE 以 `UserQueryApi.getUser(invitee=本人).nickname` 非空白判定（内部 API 无头像/签名字段，条件收窄为昵称，页面文案同步收窄，披露）；FIRST_ORDER 以本人 `firstPaidAt != null` 判定。
- **M2 社区动态不开放**：129:5854 的"发表社区动态 20 积分"行按 NOT_OPEN 展示（无社区积分规则裁决），不产生任何积分，页面明示暂未开放。
- **A1 配额账本纪律**：一切赚取只写 `points_ledger`（biz_id 指向签到行/绑定行/奖励行；order_id 空）+ `points_account` 余额原子累加（version+1）；幂等键：签到/绑定/领取命令走 14 号绑定表；ledger 行 request_id = 命令 requestId（单行）或 `requestId|行id` 拼接（多人头一次领取），均 ≤64 字符且全局唯一。
- **A2 无自动发放**：除签到（点击即得）外一切奖励均需用户显式领取（claim）；内核不消费任何事件、不后台派发（被邀请人支付事实在领取时实时查询 order 域事实，见 P3）。
- **A3 时间口径**：日/月边界、周条均为服务端计算；时间输出 ISO 毫秒（10 号 §2.6）。

### P3 内部契约增补（07 号 §7.1，最小加法）

`OrderQueryApi` 新增只读事实查询（无 PII、无枚举面）：

```java
/** 首笔成功支付时间（无已支付订单返回 null）；积分拉新内核领取时评估用。 */
OffsetDateTime firstPaidAt(UserOrderFactQuery query);   // record UserOrderFactQuery(String userId)
```

pet-order-biz 侧实现为对本人订单表的 `MIN(支付成功时间)` 聚合（只读 MyBatis XML）。不引入任何 order 域写路径、不改既有方法。**不采用事件消费方案**的原因（事实基础第 4 条）：PaymentSucceeded/OrderPaid 载荷无 userId，跨域解析需要 order 持久层读取（ARCH-002 禁止）；claim 时实时查询与"订单奖励优先事件触发"（07 号 §13.2，指 ORDER_REWARD 类按单奖励）不冲突——本切片 INVITE/TASK 类不按单计量。

### P4 装配开关（21 号清单新增"积分任务域"小节）

- `pet.points.task.enabled`（默认 false）：内核装配（签到/绑定/领取/配置存储）。[硬] Snowflake bean（同 auth.c 的 PLAT-002 约束）。
- `pet.points.task.http.enabled`（默认 false）：C 面（另需 auth.c）+ admin 面（另需 auth.admin）HTTP 路由；开启而内核关 = 启动失败（guard，先例 RefundApplicationConfiguration）。

### 验收案例

- 正：签到当日一次成功入账（余额/流水/账户三处一致），同 requestId 重放同回执，异 requestId 当日再签 409；次日可再签、连签累进、断签清零；7 连签后里程碑可领、领后终局；邀请码自邀 400、被邀请人有订单史 409、重复绑定 409；被邀请人绑定后支付 → 邀请人 claim 人头到账（biz_type=INVITE）、月上限截断、次月续领；首次下单/完善资料里程碑领取各一次；运营改面额后新领取按新值、既有流水不变；admin PUT 同 requestId 重放、异参 409、越界值 400；无 points.task.read/write 动作码的运营 403。
- 反：未登录 401；query 开放/未知字段/显式 null/重复键/尾随 token 均 400；COMMUNITY_POST 领取 400；一切消费/兑换/转赠路径不存在；跨用户读面不存在（全部主体=会话用户）。

影响：HTTP 10 号新增 §3.15.4（C 面五路）+ §5.9（admin 两路）并同步 11 号 OpenAPI（7 个新 operationId，契约 58）；07 号 §7.1 增补 firstPaidAt；新表 4 张（58 号 Schema：points_task_config/points_sign_in/points_referral_binding/points_task_award，复用 06 §9 与 14 号既有表，无迁移执行——测试自建随机库）；AdminPermissionEvaluator 登记 2 动作码；contract_smoke 登记 POINTS_TASK_OPERATIONS 家族（总数 143→150）；20 号登记表新增 §4.12（三页实现登记）；前端 frontend-miniapp 新增 points-tasks 分包三页 + 我的页入口接通；frontend-admin 新增配置页。

依据：根 AGENTS.md（硬规则/MyBatis 裁决/幂等）；32 号裁决第二节；07 号 §7.1/§13；06 号 §9；14 号幂等 Schema；23 号公共幂等；21 号开关清单；20 号登记表 §3；SSOT §24（运营权限）；用户 2026-10-13 口径（保守细则 + PR 披露回禀）。
