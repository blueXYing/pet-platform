# CCR-USR-DELETION-001 — 账号注销最小实现登记与待裁扩展项（66 号 / 用户裁决 2026-10-19 "全做"）

状态：已批最小实现落地 + 扩展项待裁；2026-10-19。
依据：用户裁决（2026-10-19 收尾批次）"设置枢纽页……含注销=写路径：requestId 幂等+审计+durable 处理；
注销后数据处置口径 PRD 有则从之，无则走 CCR 登记最小实现：账号置注销态+登录拒绝+数据保留策略按 SSOT"；
资料优先级（SSOT > 最终 PRD > 技术基线 > Schema/API）：**SSOT 全文无"注销"条款（0 命中，数据保留策略无口径）**，
C 端 PRD §5.1.29 为唯一来源（注销前置校验规则 + 字段表"账号注销 按钮/入口：通过订单、售后、商家责任和冷静期校验，
进入影响确认和身份验证"）。
关联：`docs/04-api/66-User-Settings-Deletion-Contract-v0.1.md`、
`docs/03-database/66-User-Settings-Deletion-Schema-v0.1.sql`、HTTP10 §3.17（本 PR 同步）、
`docs/08-engineering/21-部署开关组合清单-v1.0.md`（`pet.usersettings.http.enabled` 行）。

## 1. 已落地（最小实现，用户裁决授权）

- **注销前置校验读侧** `GET /api/v1/c/account/deletion-precheck`：PRD 5.1.29 业务规则原文四类阻塞
  （本人未完结订单五 displayStatus、本人处理中售后、名下 OWNER 商家门店未完结订单、名下门店处理中售后）
  逐项投影；事实端口 `OrderDeletionFactsApi`（order-api 新登记）/`AfterSaleDeletionFactsApi`
  （aftersale-api 新登记，开放案例词表=域既有 active 集合）/ merchant 域既有 `listMemberships`
  （OWNER-only）；fail-closed 503。
- **注销写路径** `POST /api/v1/c/account/deletion`：终结性 X-Request-Id（23 号 §5，namespace
  `account.deletion`，scope USER_SELF，user 模块本地 `command_idempotency` 绑定）；同事务
  （a）前置校验行锁重跑、（b）`user_deletion_request` 追加审计行（append-only，含 acknowledged、
  阻塞快照位）、（c）`user_account.status`→`CANCELED`（SQL06 §1 既有词表，乐观锁）。
- **注销即登录拒绝/会话失效**：零新代码——user 域既有 `resolveSession`（CANCELED 立即 401）与
  `finishIdentityProof`（CANCELED 登录 403）既有语义直接覆盖；V1 volatile Redis 会话自然过期，
  不新增会话清理动作（安全语义由状态检查保证）。
- **审计=durable 事实行**：`user_deletion_request` 为 append-only 审计事实（谁、何时、以何 requestId、
  确认了什么），无独立审计 sink 新增。
- **开关**：`pet.usersettings.http.enabled` 默认 **false**，硬前置 `pet.auth.c.enabled`（21 号清单登记）。
- **无事件**：注销不发布 Outbox 事件——V1 无跨域消费方（订单/售后域按自身状态机流转，无需"用户已注销"
  推送）；无 AsyncTask（同步注销，见 §2 第 1 项）。

## 2. 待裁扩展项（以下一律未实现，路由/字段/状态均不存在，待用户逐项裁决）

1. **冷静期 / "申请中"中间态**：PRD 字段表按钮态含"申请中"+ 业务规则提"冷静期校验"，但无时长、
   无期间账号状态、无撤销规则口径；V1 为**同步注销**（请求成功即 CANCELED）。若引入冷静期需要
   注销单状态机 + 定时 AsyncTask + 冷静期撤销路由，另行立项。
2. **注销后数据处置**：SSOT 无口径。V1 账号置 CANCELED + **全部业务数据保留现状**（订单/售后/评价/
   宠物档案/地址/积分/券等零删除零匿名化）。匿名化/删除策略（及《个人信息保护法》最小保留期）需
   用户裁决后另立 Schema/事件切片。
3. **注销撤销 / 账号申诉**：PRD 字段表提"账号申诉 按钮/入口（封禁、限制或注销异议时可用，进入售后与
   投诉中心）"；无状态机、无运营面；V1 不做。CANCELED 为终态（重新登录被拒且无自助恢复路径——
   客服人工兜底不在 V1 工程内）。
4. **"名下商家"成员资格口径**：本最小实现取 `listMemberships` 的 `membershipKind=OWNER` 行；STAFF
   （受雇员工）成员不构成"名下商家"不阻塞；商家申请审核中（无门店）不阻塞。若需扩大到"申请中商家
   拦截"或"员工身份拦截"，需 merchant 域裁决。
5. **`REJECTED`/`CANCELED_PENDING` 等请求单状态**：Schema 词表预留、V1 唯一终值 `ACCEPTED`；随 §2.1
   冷静期裁决决定去留。
6. **注销的微信侧资产处置**（订阅消息授权关系、微信开放数据）：无口径，未处理。

## 3. 边界披露

- FROZEN 账号不可注销（403 USER_FROZEN，与全平台写路径一致）。
- 重复注销：CANCELED 后会话已死，路由不可达；历史 binding 的重放仅对持本人有效会话的调用方返回原回执。
- 并发双注销：幂等行锁 + 账号乐观锁保证恰好一行审计与一次状态翻转。
- order 侧阻塞集不含 AFTERSALE displayStatus 分支（单一归属 aftersale 域事实，避免双计数）——66 号 §3 披露。
