# 用户设置枢纽与账号注销 HTTP 契约 v0.1（66 号）

状态：IMPLEMENTED_INTERNAL_DEFAULT_OFF，最终以实现 PR 的 CI 为准。v0.1 2026-10-19；**v0.2 2026-10-19 注销冷静期**（用户裁决"继续扩展CCR"：请求落库进冷静期——默认 7 天可配、期间账号恒 ACTIVE 可登录可撤销、到期 durable 任务执行 CANCELED 化；v0.1 同步注销口径废止）。文件名沿用 v0.1 不改，x-contract 钉不变。
来源：C 端 PRD `docs/01-prd/02-PRD-C端用户-V1.0-最终基线.docx` §5.1.29（账号与隐私设置页，含注销前置校验规则）；[10 号](10-HTTP-API-Contract-v0.4.md) §3.1/§3.2 既有账号面；[23 号](23-公共接口与幂等契约补充-v0.1.md) §5 幂等五元组；[59 号](59-User-Address-Contract-v0.1.md)（地址子页既有）、[21 号部署开关清单]（装配开关登记）；SSOT §16.4（通知偏好）、§21（未决项清单）。设计稿：登记表 §3 帧帮助/反馈/设置族 `131:18372`（设置）——见 §6 设计偏差登记。
注销最小实现口径按 [CCR-USR-DELETION-001](../../planning/ccr/CCR-USR-DELETION-001.md) 冻结；人工/在线客服承载形态按 [CCR-CS-FEEDBACK-001](../../planning/ccr/CCR-CS-FEEDBACK-001.md) 冻结（帮助与客服页归 67 号 slices 文档）。

本契约为 C 端"设置枢纽"族（PRD 5.1.29）后端面的唯一契约文档（66 号预分配位）。**设置页大部分子项复用既有已批面**（通知偏好=10 号 §3.15 notification-preferences；收货地址=59 号；手机号脱敏展示=既有 `GET /api/v1/c/profile`；退出登录=既有 `POST /api/v1/c/auth/logout`；清除缓存/关于/协议入口为纯前端本地行为），本契约只新增**注销前置校验读侧**与**账号注销写路径**两条路由，不重做任何既有子面。

## 1. 范围与产品边界

1. **注销前置校验**（PRD 5.1.29 业务规则"账号注销前置校验"）：存在待支付 / 待确认 / 待服务 / 退款待确认 / 退款中订单或处理中售后工单时拦截注销；名下商家存在未完成订单或售后工单时同样拦截。读侧逐项给出阻塞明细，前端在注销确认页渲染。
2. **账号注销写路径**（PRD 5.1.29 字段表"账号注销 按钮/入口"；**v0.2 冷静期**）：注销=写路径——requestId 幂等 + 审计落库 + 冷静期请求单状态机。前置校验任一阻塞即 409；通过后请求落 `user_deletion_request` **PENDING** 行（`effective_at = now + 冷静期天数`，同事务提交 durable 到期任务），**账号保持 ACTIVE**（登录/使用不受限）。冷静期天数=部署配置 `pet.usersettings.deletion.cooldown-days`（默认 7，有界 1..30，越界装配失败）。到期由 durable 任务 `ACCOUNT_DELETION_EXEC`（SQL13，availableAt=effective_at）在行锁事务内重跑前置校验后执行 `user_account.status`→`CANCELED`（SQL06 §1 既有词表）+请求行 ACCEPTED；到期出现阻塞或账号 FROZEN 则自动作废请求（CANCELED+blockers 快照），不产生部分注销。CANCELED 后：既有会话下一次请求即失效（user-biz 既有 resolveSession 语义）、微信登录链路拒绝（既有 finishIdentityProof CANCELED 拒绝语义）——零新代码。
3. **冷静期状态读与撤销**（v0.2 新增）：`GET /c/account/deletion-status`（NONE/PENDING+三事实，"申请中可撤销"提示数据源——PR 口径落点：设置枢纽注销入口徽标+注销页横幅）；`POST /c/account/deletion-cancel`（CAS PENDING→CANCELED；账号从未离开 ACTIVE，无账号状态迁移；撤销后可重新发起）。

**不在本切片（CCR-USR-DELETION-001 登记，一行不做）**：~~注销冷静期/"申请中"中间态与延迟 AsyncTask 清理~~【v0.2 已放开，见上】；注销后数据删除/匿名化处置（无口径，账号置注销态+数据保留现状，冷静期内数据零改动）；~~注销撤销~~【v0.2 已放开冷静期内撤销】与"账号申诉"入口（PRD 字段表提及，V1 无状态机与运营面——已注销账号的异议仍走客服人工兜底）；密码体系与"修改密码"子页（V1 登录为微信授权，无密码面——设计帧 `131:19091` 为审计 D 类帧，登记表偏差小节登记）；支付设置子页（PRD 5.1.29 明示不提供）；黑名单（PRD 明示 V1 仅预留不开放）；宠物档案授权撤回面（PRD 5.1.29 提及，无授权记录读侧契约，属宠物档案域切片，另行立项）。

## 2. 鉴权、开关与边界

- 装配开关 `pet.usersettings.http.enabled`（默认 **false**），且硬前置 `pet.auth.c.enabled=true`（双条件装配，缺一不装配；21 号清单同步登记）。两条路由均为真实 MINIAPP Bearer 会话（`CBearerSessionFilter`），无匿名路由。
- `Cache-Control: no-store` 全路由；信封 `{code,message,data,traceId}`；未知 query 参数整单 400（precheck 无任何 query 参数）；ID 全部 Snowflake 十进制 String；时间 ISO-8601 UTC 毫秒。
- 写路由（POST）终结性 `X-Request-Id`（23 号 §5 五元组，namespace `account.deletion`，scope USER_SELF）：幂等绑定在 user 模块本地 `command_idempotency`（14 号共享物理表）完成，同 ID 异参重放 409 `IDEMPOTENCY_KEY_CONFLICT`，同 ID 同参重放返回首次成功回执（200）；状态冲突 409 `DELETION_BLOCKED`。
- 展示态零推导（ARCH-005）：控制器只透传内核投影事实；手机号只以脱敏形态出域（既有 mask 口径），注销路由不回显任何明文身份字段。
- biz 不依赖 biz：user-biz 通过 **order 域 / aftersale 域在各自 `-api` 模块新登记的叶子事实端口**（`OrderDeletionFactsApi` / `AfterSaleDeletionFactsApi`，纯事实读、无会话主体、无授权语义，沿 `OrderFirstPaidFactsApi` 先例）与 **merchant 域既有 `MerchantAdmissionQueryApi.listMemberships`**（OWNER 成员资格）组合出前置校验；实现 bean 由 pet-boot 装配层注入。SQL 只进各模块自有 Mapper XML（order/aftersale/user/customer-service 各自命名空间），无 JdbcTemplate/注解 SQL/内联 SQL，过 `backend/tools/check-persistence-style.py`。
- 无事件 by design：注销不发布任何 Outbox 事件（V1 无跨域消费方——订单/售后域按既有状态自行流转，无任何域需要"用户已注销"的事实推送；后续若引入消费方，走 Event 目录增补 + 本契约 vNext）。**v0.2 增一个 durable AsyncTask**：`ACCOUNT_DELETION_EXEC`（SQL13 `async_task`，uk `ACCOUNT_DELETION_EXEC:{request行id}`，ownerModule=USER，retryPolicy 同名 max 8，`enqueueAt`=effective_at，提交与请求落库同事务——请求成功而任务缺失不可能发生）；到期执行为行锁幂等内核（非 PENDING→任务 Cancelled 不重试；技术异常按策略重试，自动作废/已终局不消耗重试）；执行器装配开关 `pet.usersettings.deletion.worker.enabled`（默认 false，独立 opt-in——生产/执行/恢复各自决策，SQL13 家族先例）。PR 披露。

## 3. 注销前置校验（读侧）

| 方法/路径 | 请求 | 成功 data | 关键错误 |
|---|---|---|---|
| GET `/api/v1/c/account/deletion-precheck` | 无 query、无 body | `{blocked, blockers: [{code, label, detail}]}` | 400/401/503 |

- `blockers[].code` 词表（冻结，前端按 code 渲染文案兜底）：
  - `USER_ORDERS_UNSETTLED`：本人存在待支付 / 待确认 / 待服务 / 退款待确认 / 退款中订单（order 域事实：displayStatus ∈ {PENDING_PAYMENT, PENDING_CONFIRM, PENDING_SERVICE, REFUND_PENDING_CONFIRM, REFUNDING}，真值表由 order 域自有固定 XML 分支投影，与既有 `OrderQueryMapper.displayFilter` 同源纪律；order 域是唯一展示态计算者，本面不重推导）。
  - `USER_AFTERSALES_OPEN`：本人存在处理中售后工单（aftersale 域事实：`aftersale_case.active_flag=1 AND status ∈ {PENDING, PROCESSING, WAITING_SUPPLEMENT}`）。
  - `OWNER_STORE_ORDERS_UNSETTLED`：名下商家（`MerchantAdmissionQueryApi.listMemberships` 中 `membershipKind=OWNER` 的门店）存在未完成订单（同一 order 域阻塞真值表，按 store 维度）。
  - `OWNER_STORE_AFTERSALES_OPEN`：名下商家门店存在处理中售后工单（同一 aftersale 域事实，按 store 维度）。
- `label` 为服务端中文短句、`detail` 为人读明细（如含数量口径的说明）；`blocked = blockers 非空`。顺序固定：上表词表顺序。**成员资格枚举失败/事实端口不可用 → 503 fail-closed（COMMON_DEPENDENCY_UNAVAILABLE），绝不降级为"可注销"**。
- 注：order 域 displayStatus=AFTERSALE（活跃售后）与 `USER_AFTERSALES_OPEN` 语义重叠，阻塞判定单一归属 aftersale 域（`USER_AFTERSALES_OPEN`），order 侧阻塞集不含 AFTERSALE 分支——披露，避免双计数。
- 名下商家成员资格仅取 `OWNER`；`STAFF`（员工）成员不构成 PRD"名下商家"语义，不阻塞——披露。

## 4. 账号注销（写路径，v0.2 冷静期）

| 方法/路径 | 请求 | 成功 data | 关键错误 |
|---|---|---|---|
| POST `/api/v1/c/account/deletion` | 终结性 X-Request-Id；严格 JSON `{acknowledged: true}`（必填布尔真，未知字段/显式 null/假值/尾随 token 均 400） | `{userId, status: "PENDING", requestedAt, effectiveAt, cooldownDays}`（首执 201，同参重放 200 同回执） | 400/401/403(USER_FROZEN)/409(DELETION_BLOCKED)/409(已有 PENDING 申请，COMMON_CONFLICT)/409(IDEMPOTENCY_KEY_CONFLICT)/503 |
| GET `/api/v1/c/account/deletion-status` | 无 query 无 body | `{status:"NONE"}` 或 `{status:"PENDING", requestedAt, effectiveAt, cooldownDays}` | 401 |
| POST `/api/v1/c/account/deletion-cancel` | 终结性 X-Request-Id（namespace `account.deletion.cancel`）；无 body | `{userId, status: "CANCELED", canceledAt}`（首执 201，同参重放 200） | 400/401/409(无 PENDING 申请，COMMON_CONFLICT)/503 |

语义与硬规则（v0.2）：

- **前置校验在行锁事务内重跑**（§3 同一内核组合，任一阻塞 409 `DELETION_BLOCKED`；全局信封 data=null，message 汇总首个阻塞短句——阻塞明细由前端先行调用 §3 precheck 渲染，收到 409 后重取 precheck 刷新）。
- 通过后同事务落两笔持久事实（**账号状态不再即时翻转**）：
  1. `user_deletion_request` 追加一行 status=`PENDING`（66 号 Schema v0.2 状态机行：`PENDING →（撤销 CAS）CANCELED /（到期任务）ACCEPTED`，含 cooldown_days 快照、effective_at、撤销列 cancel_request_id/canceled_at、执行列 executed_at；到期自动作废亦落 CANCELED+blockers 快照）。
  2. durable 任务 `ACCOUNT_DELETION_EXEC` 入队（`enqueueAt`=effective_at）。
- **冷静期不变量**：期间账号恒 ACTIVE（登录/会话/一切既有链路零改动）；数据零删除零改动；至多一个 PENDING（账号行锁串行化，重复提交 409）；撤销仅 CAS 请求行，无账号状态迁移。
- **到期执行**（durable 任务内核）：行锁重读请求行（非 PENDING → ALREADY_RESOLVED，任务 Cancelled）→ 账号 FROZEN 自动作废 → 前置校验重跑阻塞自动作废（blockers_json 快照）→ 否则 `user_account` 乐观 CAS →`CANCELED` + 请求行 →`ACCEPTED`（executed_at）。注销即生效：既有 `resolveSession` 对 CANCELED 立即 401（所有在途会话下一次请求即失效）；既有微信登录 `finishIdentityProof` 对 CANCELED 拒绝（"账号状态不可用"403）——**登录拒绝、会话失效均为 user 域既有语义，零新代码**（volatile Redis 会话自然过期，安全语义由状态检查保证）。
- 幂等：namespace `account.deletion`（注销）/`account.deletion.cancel`（撤销）各自 23 号 §5 binding；**同 requestId 同参重放返回原回执 200**；异参 409。
- FROZEN 账号不可发起注销（403 USER_FROZEN，与全平台写路径一致）；CANCELED 账号不可达本路由（会话已死，401）。
- 不删除/不改写任何业务数据（订单/售后/评价/宠物档案/地址等全保留现状，CCR-USR-DELETION-001 §2）；不注销名下商家（merchant 域无动作）。
- 防并发：同一账号两个并发注销请求 → 命令幂等行锁 + 账号行锁串行化，后者 409（已有 PENDING 申请）；到期任务与撤销并发 → 请求行 CAS 恰有一方成功。

## 5. 错误码（12 号登记）

| Code | 含义 | HTTP |
|---|---|---:|
| DELETION_BLOCKED | 存在注销前置阻塞（订单/售后/名下商家未完结） | 409 |

（其余复用既有码：COMMON_INVALID_ARGUMENT / COMMON_UNAUTHORIZED / USER_FROZEN / IDEMPOTENCY_KEY_CONFLICT / COMMON_DEPENDENCY_UNAVAILABLE。）

## 6. 设计偏差登记（帧 `131:18372`，登记表同步）

| 帧元素 | 处理 | 依据 |
|---|---|---|
| 修改密码行 | 不渲染（D 类帧 `131:19091` 同口径） | V1 微信授权登录无密码体系（审计 D 类，待用户裁决是否删稿） |
| 支付设置行 | 不渲染 | PRD 5.1.29 明示 V1 不提供 |
| 推送通知/消息提醒开关行 | 行改为入口，跳既有通知偏好页（两开关与 PRD 5.1.29 通知设置完全同义） | 10 号 §3.15 既有已批面，不重做 |
| 注销账号入口 | **新增**（帧无此元素） | PRD 5.1.29 字段表明确"账号注销 按钮/入口"，PRD 优先于稿 |
| 清除缓存 12.8MB | 前端本地行为（wx storage 统计；清除保留会话凭据键），无后端路由 | PRD"清除缓存"为端侧能力 |
| 底部 tab（首页/订单/核销/消息/我的） | 不还原 | 原稿 tab 与 C 端已批 tabBar 冲突（登记表既有口径），本页为"我的"入口子页 |
| 关于宠灵工 v1.0.0 | 前端静态展示（版本常量） | PRD"展示版本号" |

## 7. 测试口径

- user 域内核真实 MySQL 回环（隔离库 33440/16379，env 同 CI 名 AUTH_MYSQL_*）：前置校验组合矩阵（四类阻塞逐项 + 无阻塞）、**v0.2 冷静期 happy path**（PENDING 行 + effective_at=created+7d + durable 任务同事务入队且 execute_at=effective_at + 账号保持 ACTIVE）、同参重放 200 / 异参 409、已有 PENDING 409、阻塞 409（零行零任务）、FROZEN 403、**撤销 CAS**（PENDING→CANCELED + status 读回 NONE + 撤销后可重新发起）、**到期执行**（EXECUTED：CANCELED+ACCEPTED+executed_at；重投递 ALREADY_RESOLVED；到期阻塞/FROZEN 自动作废+快照）、任务 spec 绑定钉（matches 真值表）。
- order/aftersale 事实端口真实 MySQL 回环：阻塞 displayStatus 五值逐值命中、非阻塞态不命中、store 维度、空入参。
- boot 装配：开关默认关；开 http 缺 auth.c 启动失败；事实端口 bean 缺失装配失败；cooldown 越界（0/31）装配失败；worker 开关独立 opt-in。
- 前端：设置枢纽（申请中徽标）/注销页（冷静期横幅+撤销+提交回执）/静态协议页 smoke + typecheck + build；package-check 同步。
