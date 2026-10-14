# 60-Points-Task-Contract-v0.1（签到 + 拉新任务 + 运营配置）

裁决链：32号裁决第二节（2026-10-13）+ CCR-POINTS-TASK-001（细则定稿，PR 披露回禀）。本契约为该切片的权威 HTTP 表面；与 10 号总契约并存，10 号 §3.15.4/§5.9 摘要指向本文件。

## 1. 范围与硬边界

- 积分只赚取/扣回，不消费（AGENTS 硬规则）：本契约只有赚取路径（SIGN_IN/INVITE/TASK 三流水类型），无兑换/抵扣/转赠/消费。
- 一切配账走 `points_ledger` + `points_account`（06 号 §9）；本切片无 REFUND_CLAWBACK 写路径（退款扣回仍属 PTS-002 家族，不在本切片）。
- 写操作 requestId 幂等（23 号 + 14 号绑定表）；读操作无 X-Request-Id。
- 主体恒为会话用户（C 面）/RBAC 动作码持有者（admin 面）；无跨用户坐标。

## 2. 装配开关（21 号清单 §1.11）

| 开关 | 默认 | 位置 | 依赖 |
|---|---|---|---|
| `pet.points.task.enabled` | false | `PointsTaskConfiguration`（内核） | [硬] Snowflake bean（PLAT-002） |
| `pet.points.task.http.enabled` | false | `CPointsTaskController`（另需 auth.c）、`AdminPointsTaskConfigController`（另需 auth.admin） | [硬] points.task.enabled（guard 启动失败） |

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

S1 每日一次（UTC+8 自然日）；S2 固定面额无连签递增；S3 连签7天一次性里程碑（当前连签口径）；R1 确定性邀请码；R2 绑定=终身一次且被邀请人须零订单史；R3 人头=绑定后完成首笔支付（firstPaidAt>绑定时刻）；R4 月上限（0=关）；M1 里程碑一次性（INVITE_3 按已领取人头数、PROFILE 按昵称非空、FIRST_ORDER 按 firstPaidAt）；M2 社区动态 NOT_OPEN；A1 配账纪律；A2 除签到外全部显式领取、无事件自动发放；A3 服务端时间口径。
