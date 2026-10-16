# 60-Points-Task-Contract-v0.1（签到 + 拉新任务 + 运营配置；§7 增补订单奖励/退款扣回）

裁决链：32号裁决第二节（2026-10-13）+ CCR-POINTS-TASK-001（细则定稿，PR 披露回禀）+ CCR-POINTS-ORDER-001（2026-10-15 增补，PTS-001/PTS-002，见 §7）。本契约为该切片的权威 HTTP 表面；与 10 号总契约并存，10 号 §3.15.4/§5.9 摘要指向本文件。

## 1. 范围与硬边界

- 积分只赚取/扣回，不消费（AGENTS 硬规则）：本契约只有赚取与扣回路径（SIGN_IN/INVITE/TASK/ORDER_REWARD/REFUND_CLAWBACK 五流水类型），无兑换/抵扣/转赠/消费。
- 一切配账走 `points_ledger` + `points_account`（06 号 §9）；PTS-002 退款扣回已交付（§7，事件消费面，无新 HTTP 路由）。
- 写操作 requestId 幂等（23 号 + 14 号绑定表）；读操作无 X-Request-Id；事件消费幂等走 outbox consume-log（08 号 §15/§21）+ `uk_points_request` 台账双保险。
- 主体恒为会话用户（C 面）/RBAC 动作码持有者（admin 面）；退款扣回主体来自本模块台账（事件载荷无 userId，07 号 §7.1 增补注记）。

## 2. 装配开关（21 号清单 §1.11）

| 开关 | 默认 | 位置 | 依赖 |
|---|---|---|---|
| `pet.points.task.enabled` | false | `PointsTaskConfiguration`（内核） | [硬] Snowflake bean（PLAT-002） |
| `pet.points.task.http.enabled` | false | `CPointsTaskController`（另需 auth.c）、`AdminPointsTaskConfigController`（另需 auth.admin） | [硬] points.task.enabled（guard 启动失败） |
| `pet.points.refund-clawback.enabled` | false | `PointsRefundClawbackConfiguration` → `POINTS_REFUND_CLAWBACK` 消费者（§7） | [硬] Snowflake bean（PLAT-002）+ `pet.outbox.enabled`（JdbcOutboxConsumeGuard）；关则 RefundSucceededEvent 待命不派发（同券 #168 装配形） |

## 3. C 端表面（MINIAPP / USER / bearerAuth / Cache-Control: no-store）

### 3.1 GET /api/v1/c/points/sign-in/status — operationId `cGetSignInStatus`

query 全禁止。200 `SignInStatusEnvelope`：

```json
{ "code":"SUCCESS","message":"ok","data":{
  "signedToday":false,"streak":0,"dailyPoints":"5",
  "week":[{"date":"2026-10-07","signed":true}, ...近7个自然日...] },
  "traceId":"..." }
```

### 3.2 POST /api/v1/c/points/sign-in — operationId `cSignIn`

体：空 JSON 对象（`{}`，严格解析；query 全禁止）。规则 S1~S3：Asia/Shanghai 自然日一次、固定面额、biz_type=SIGN_IN。回执（200，首交与同 requestId 重放同形）：

```json
{ "data":{ "signedDate":"2026-10-13","points":"5","streakAfter":1,
           "ledgerId":"9400...","balanceAfter":"1285" } }
```

当日已签且换新 requestId → **409 COMMON_CONFLICT**（"每日仅可签到一次"）。错误面：400/401/409/500/503（`PointsTaskErrorEnvelope`）。

### 3.3 GET /api/v1/c/points/tasks — operationId `cListPointsTasks`

query 全禁止。一读供任务面板（129:5854）与奖励面板（129:7677）两页。200 `PointsTasksEnvelope`（字段全集见 11 号 OpenAPI `PointsTasksData`）：

- `inviteCode`：本人邀请码（R1：`P` + base36(userId) 定长 + 1 校验位）。
- `signIn`：`{signedToday, streak, dailyPoints, weekCount:7}`。
- `invite`：`{perInviteePoints, monthlyRewardCap, rewardedThisMonth, claimableCount, milestone:{required:3, status, progress}}`。
- `tasks[]`：`INVITE_PER_HEAD / FIRST_ORDER / SIGN_IN / COMMUNITY_POST` 四行，status ∈ `CLAIMABLE|CLAIMED|NOT_ACHIEVED|NOT_OPEN|DONE_TODAY`（SIGN_IN 行用 DONE_TODAY/NOT_ACHIEVED 表当日状态；COMMUNITY_POST 恒 NOT_OPEN——V1 无社区积分规则，页面文案"暂未开放"）。
- `rewards[]`：`SIGN_IN_STREAK_7 / INVITE_MILESTONE_3 / PROFILE_COMPLETE` 三行，status ∈ `NOT_ACHIEVED|CLAIMABLE|CLAIMED`，带 `progress`（如 `2/3`、`5/7`）。

### 3.4 POST /api/v1/c/points/claims — operationId `cClaimPointsReward`

体（严格）：`{"claimType":"INVITE_PER_HEAD|FIRST_ORDER|SIGN_IN_STREAK_7|INVITE_MILESTONE_3|PROFILE_COMPLETE"}`。回执（200，首交与重放同形）：

```json
{ "data":{ "claimType":"INVITE_PER_HEAD","pointsGranted":"100",
           "ledgerIds":["9400...","9400..."],"balanceAfter":"1385" } }
```

无可领取（含已领过的一次性里程碑）→ **409 COMMON_CONFLICT**。多人头一次领取生成多行 ledger（每被邀请人一行，biz_type=INVITE，biz_id=绑定行 id）。错误面：400/401/409/500/503。

### 3.5 POST /api/v1/c/points/referral-bindings — operationId `cBindReferralCode`

体（严格）：`{"inviteCode":"PxxxxxxxxX"}`（1~32 字符 `[A-Za-z0-9]`）。规则 R1~R4。回执（200）：

```json
{ "data":{ "bindingId":"9400...","createdAt":"2026-10-13T09:00:00.000Z" } }
```

无效码/用户不存在/自邀 → 400；已有绑定（终局）→ 409；被邀请人已有订单史 → 409（"不符合新用户条件"）。错误面：400/401/409/500/503。

## 4. 运营配置表面（ADMIN_WEB / OPS / bearerAuth）

### 4.1 GET /api/v1/admin/points-task-config — operationId `adminGetPointsTaskConfig`

RBAC：`points.task.config.read`。无行读内置缺省：`signInDailyPoints=5, signInStreakBonusPoints=30, invitePerInviteePoints=50, inviteMonthlyRewardCap=3, inviteMilestoneBonusPoints=100, firstOrderPoints=100, profileCompletePoints=20`；`version="0"`、`updatedAt=null`。

### 4.2 PUT /api/v1/admin/points-task-config — operationId `adminUpdatePointsTaskConfig`

RBAC：`points.task.config.write`。全量 PUT（七字段全部必填整数；严格 JSON：未知字段/显式 null/重复键/尾随 token 均 400）。范围：`signInDailyPoints`[1,1000]、`signInStreakBonusPoints`[0,1000]、`invitePerInviteePoints`[0,1000]、`inviteMonthlyRewardCap`[0,100]、`inviteMilestoneBonusPoints`[0,1000]、`firstOrderPoints`[0,1000]、`profileCompletePoints`[0,1000]。同 requestId 同参重放首成回执、异参 409 IDEMPOTENCY_KEY_CONFLICT；配置即时生效（新领取按新值；既有流水不回改）。错误面：400/401/403/409/500/503。

## 5. 错误码（12 号登记内复用，不新增全局码）

`PointsTaskErrorEnvelope`：`COMMON_INVALID_ARGUMENT / COMMON_UNAUTHORIZED / COMMON_FORBIDDEN / COMMON_CONFLICT / IDEMPOTENCY_KEY_CONFLICT / COMMON_DEPENDENCY_UNAVAILABLE / COMMON_INTERNAL_ERROR`。业务原因经 message 中文呈现，客户端不得解析中文分流。

## 6. 细则速查（全文见 CCR-POINTS-TASK-001"细则定稿"，PR 披露回禀）

S1 每日一次（UTC+8 自然日）；S2 固定面额无连签递增；S3 连签7天一次性里程碑（当前连签口径）；R1 确定性邀请码；R2 绑定=终身一次且被邀请人须零订单史；R3 人头=绑定后完成首笔支付（firstPaidAt>绑定时刻）；R4 月上限（0=关）；M1 里程碑一次性（INVITE_3 按已领取人头数、PROFILE 按昵称非空、FIRST_ORDER 按 firstPaidAt）；M2 社区动态 NOT_OPEN；A1 配账纪律；A2 除签到外全部显式领取、无事件自动发放（§7 首单奖励保留显式领取，领取即绑单）；A3 服务端时间口径。

## 7. 增补（2026-10-15）：订单奖励与退款扣回（PTS-001/PTS-002，CCR-POINTS-ORDER-001）

无新 HTTP 路由、无新错误码、无 OpenAPI/10号/07号变化（11号 operations 计数不变）。HTTP 表面只有 §3.4 既有 `cClaimPointsReward(FIRST_ORDER)` 一条路径，回执形状不变。

### 7.1 赚取（PTS-001，订单奖励绑定）

- 事实口径：首笔成功支付 = `OrderQueryApi.firstPaidAt`（07号 §7.1 增补，MIN(paid_at)，payment_status='PAID'，含迟到支付标记为 PAID 的订单）；首笔支付订单标识自 2026-10-16 起改读 order 域自有 O(1) 事实端口 `OrderFirstPaidFactsApi.firstPaidOrder`（07号 §7.1.1 增补：最早 paid_at、最小规范 id 决胜，单条索引行读；CCR-POINTS-ORDER-001 收口，替代原 PR#170 boot 侧 `listMyOrders` 全量分页解析——其 5000 单防御上限属分页实现而非事实，随实现移除，任何订单量下不再 fail-closed 503；同一付费集与决胜口径，行为等价由测试保证）。订单模块外的适配只搬运事实（ARCH-005），事件驱动自动发放仍结构性不可达（ARCH-002：事件载荷无 userId、事件路径禁读订单持久层——A2 显式领取保留，领取即绑单）。
- 领取 `cClaimPointsReward(FIRST_ORDER)`：仍要求 firstPaidAt 非空；成立则一次发放 `firstOrderPoints`（运营配置，即时读），台账 biz_type=`ORDER_REWARD`、order_id=首笔支付订单、biz_id=award 行 id；`points_task_award` 终局行不变（uk user+code，每用户一次 ⇒ 每订单至多一次）。首付事实端口不可用时 fail-closed 503。
- 回执与幂等：同 §3.4（23 号绑定表同 requestId 重放首成回执、异参 409）。

### 7.2 扣回（PTS-002，事件消费）

- 消费者 `POINTS_REFUND_CLAWBACK`（pet-points-biz/event，开关 §2 行 3），消费 `RefundSucceededEvent.v1`（08 号目录 points 已登记），事务内 outbox claim + ORDER_REWARD 行 `FOR UPDATE` 串行化同订单并发退款。
- 判定矩阵（08 号目录 §8 points + SSOT §10.1）：
  - 本订单无 ORDER_REWARD 台账 → 权威 NOOP（从未发分：含迟到支付来源未领取场景，09 号 §29.2；非首单订单退款亦 NOOP）；
  - FULL → 扣回该订单剩余全部未扣奖励；
  - PARTIAL → 每次按 `round_half_up(本单累计发放 × refundAmount / originalPaidAmount)` 记账，单次以剩余未扣额封顶，多次部分退款累计不超过本单发放额；
  - 未知 refundType / 金额越界（负数、scale>2、PARTIAL 超原付）→ fail-closed 503，派发重试至搁置（同券消费者）。
- 台账：biz_type=`REFUND_CLAWBACK`、order_id=订单、biz_id=refundOrderId、delta 为负、balance_after 扣后读回；request_id=`PTS:CLAWBACK:{refundOrderId}`（uk_points_request 二次防重）。余额数学上有界不为负，读回为负即分歧 fail-closed 503。
- 只动 ORDER_REWARD/REFUND_CLAWBACK 台账；签到/拉新/里程碑等其他来源积分不触碰。

### 7.3 CCR-POINTS-ORDER-001 登记（要点）

P1 发放面额=运营配置 firstOrderPoints，领取时即时读快照入账；P2 赚取触发=显式领取（A2 保留）+首笔支付订单绑定，事件驱动自动发放因 OrderPaid/PaymentSucceeded 载荷无 userId 且订单模块本切片禁改而不可达，登记为 V1 明确取舍；P3 扣回时点=RefundSucceededEvent.v1 消费事务内；P4 迟到支付来源不特判——按台账有无奖励流水自然 NOOP/扣回（09 号 §29.2 同源）；P5 取整=HALF_UP（四舍五入，SSOT §10.1）；P6 多次退款：每次部分退款按发放额×当次比例记账、以剩余未扣额封顶，累计不超过本单发放额（退款比例合计 100% 时奖励全额扣回）；P7 扣回后不改变 points_task_award 终局状态（任务面板仍显 CLAIMED，净额由流水呈现）。

### 7.4 收口（2026-10-16）：首笔支付订单标识 O(1) 事实查询

CCR-POINTS-ORDER-001 遗留的实现缺口（boot 适配器经 `listMyOrders` 全量分页解析首单，O(全量订单)，防御上限 5000 单 fail-closed）由 order 域自有只读事实端口 `OrderFirstPaidFactsApi.firstPaidOrder`（07号 §7.1.1）收口：单条索引行读 O(1)，同一付费集与"最早 paid_at、最小规范 id 决胜"口径，boot 适配器只搬运事实（ARCH-005）；5000 单上限属旧分页实现，随实现移除。07号 §7.1.1 为本事实面的契约登记；完整裁决记录见 `planning/ccr/CCR-POINTS-ORDER-001.md`（状态：RESOLVED）。
