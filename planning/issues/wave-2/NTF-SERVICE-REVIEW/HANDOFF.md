# NTF-SERVICE-REVIEW 交接：服务审核结果站内通知消费侧

日期：2026-09-22。角色：E（通知消费侧）。分支 `codex/ntf-service-review-20260922`（基于 origin/develop `52a1c45`，独立 worktree `wt-ntf-svc-review`）。**未 push、未建 PR。**

依据：用户 2026-09-22 裁决——审核结果站内通知按 SSOT 保留（01-SSOT:46"订单、退款、核销、售后、审核必须保留站内消息"），拆分并行：角色A（服务写入方，`wt-adm001-write`，分支 `codex/adm001-service-write-20260922`）负责 ServiceReviewedEvent 及事务内 Outbox；本切片负责可靠消费、商家收件箱与权限隔离。先例＝MER-001 S5/S7 + NTF-001/C-006（PR64，develop `bb6bb5c` 已合入）：MerchantApplicationReviewedEvent.v1 → notification 域消费 → 站内落库 → C 端消息中心（商家主账号以 C 端 USER 身份收件）。

## 1. 实现范围（本分支交付）

### 后端（pet-notification-biz，未触碰 pet-service-*/pet-merchant-*/pet-boot）

| 文件 | 作用 |
|---|---|
| `event/ServiceReviewedConsumer.java` | ServiceReviewedEvent.v1 严格消费者：envelope 校验（eventVersion=1、aggregateType=SERVICE、aggregateId=serviceId）、payload 严格字段集校验（8 字段全量比对，未知/重复/尾随字段拒绝且不带原始敏感内容）、Snowflake ID String 词法、submissionNo 为 JSON 整数 1..4294967295、serviceName 2..50 码点且无控制字符、decisionType∈{APPROVE,REJECT}、opinion 规则对齐既有（REJECT 必填 10..500 非空白；APPROVE 可空、≤500）、decidedAt 毫秒 OffsetDateTime；consumerName=`notification.service-reviewed.v1` |
| `event/ServiceReviewReceiverResolver.java` | 收件人解析函数接口：merchantId → owner_user_id（≤0 视为未解析，消费失败留待重试，**不烧幂等claim**） |
| `infrastructure/persistence/ServiceReviewNotificationStore.java` | 通知域自有事务（REQUIRES_NEW）：consume_guard claim 与落库同事务提交/回滚，UTC 存储 |
| `infrastructure/persistence/mapper/NotificationServiceReviewMapper.java` + `resources/mapper/NotificationServiceReviewMapper.xml` | 落库列：receiver_type=USER、receiver_id=解析出的主账号、category=SYSTEM、message_type=`SERVICE_REVIEWED`、biz_type=`SERVICE`、biz_id=serviceId、mandatory_inbox=1 |

内容模板（沿用既有"标题+正文、消息体无 URL、意见全文（≤500 校验上限）"口径）：
- 标题：`服务审核结果`
- APPROVE：`服务《{serviceName}》：审核通过，已上架`
- REJECT：`服务《{serviceName}》：审核未通过。{opinion}`

### 前端（frontend-miniapp，消息中心既有页面扩展）

- `src/consumer/notifications/messages.ts`：`routeForNotification` 白名单新增 `SERVICE_REVIEWED`+`SERVICE`（bizId 非空）→ `/merchant/pages/services/index`；新增 `jumpLabelFor`（`查看服务`/`查看入驻申请`）。
- `src/consumer/pages/messages/index.tsx`：跳转按钮文案改为 `jumpLabelFor`，其余交互不动。
- 跳转路由依据：**角色C 的 `planning/issues/wave-2/M-002-service-pages/NAVIGATION-BASIS.md` §3 已定稿**（在 C 的 worktree `wt-m-svc-pages`，分支 `codex/m-svc-pages`），其 §3 明确"消息中心 routeForNotification 白名单可扩展，新增 SERVICE_REVIEWED/SERVICE 类型 → 服务管理列表页；商家收件箱与通知下发本身归角色E"。故本分支直接采用 C 的最终路由，未用工作区占位路径；`messages.ts` 内留 TODO 注明页面与 app.config 注册随 C 分支合并生效。

### 测试

后端 `pet-notification-biz/src/test/.../ServiceReviewedConsumerMySqlTest.java`（真实 MySQL 8.4.9 @127.0.0.1:33452，权威 Schema 06 建库，merchant 表播种真实主账号归属），8 例：
1. APPROVE/REJECT 各落一条 mandatory 通知（含全部投影列断言），重复投递不重复落库（consume_log 唯一）；
2. 落库失败回滚 consume claim，恢复重试成功（RENAME TABLE 模拟）；
3. 并发重复投递恰好一条（两线程）；
4. 严格校验拒绝族（未知字段/REQUEST_CORRECTION/submissionNo 字符串/超长名称/REJECT 缺意见/意见超500/数字类型ID/aggregateId 不匹配/尾随文档/重复字段），全部在 claim 之前失败且异常消息不含载荷敏感内容；
5. merchant 不可解析 → 失败且不 claim，可解析后重试成功（收件人=新解析主账号）；
6. 真实 Outbox 全链：发布事务回滚则 outbox 无痕；提交后 OutboxDispatcher 派发 → 通知落库 + consume_log + outbox=PUBLISHED；
7. 读取/已读隔离（复用既有 NotificationInboxService）：主账号 1001 列表可见（messageType/bizType/bizId/title/content/readAt 全断言）；另一商家主账号 1002 列表空、详情 404 `COMMON_NOT_FOUND`、标记已读 404；本人标记已读结果幂等（同时间戳重放）；
8. decidedAt 非 UTC（+08:00）经 Shanghai 会话连接仍落 UTC DATETIME(3)。

前端：`src/consumer/tests/messages.test.ts` 新增白名单/文案用例（SERVICE_REVIEWED → services 页 + `查看服务`；未登记类型无跳转、无文案）。

## 2. 测试结果（2026-09-22，本机）

- 后端：`mvn -pl pet-notification-biz test`（JAVA_HOME=Adoptium 21.0.11，PLAT003_MYSQL_URL=jdbc:mysql://127.0.0.1:33452/，env -u DOCKER_HOST）——**14/14 通过**（既有 MerchantApplicationReviewedConsumerMySqlTest 6 例回归 + 新增 8 例）。
- 架构：`mvn -pl pet-architecture-test test` 全 reactor install 后 **22/22 通过**（首次失败为 pet-id-core 未编译的环境序问题，与本次改动无关）。
- 前端：`npm test` **139/139 通过**（新增 1 例）；`npm run typecheck`、`npm run build:weapp`、`npm run check:package` 均通过（check:package 需先 build 产生 dist）。
- 环境协调：MySQL 33452（本机 mysqld 8.4.9）与 Redis 16383 为既有共享进程，未改动未重启；测试建独立随机库并在结束时 DROP。本 worktree 无 node_modules，以目录联接复用 `wt-ntf-messages/frontend-miniapp/node_modules`（package-lock 逐字节一致）；联接不提交（node_modules 本就被忽略）。
- **未运行**：pet-boot 的 NotificationInboxHttpTest（在 pet-boot 测试域，本角色禁改 pet-boot；其覆盖的 C 端会话/Redis/HTTP 层不在本切片改动面内，通知读侧逻辑复用其已验证代码）。

## 3. 与角色A的对齐点（协调者转A，A是Event08唯一Writer）

按约定按给定载荷形状开发：`serviceId/serviceName/merchantId/storeId/submissionNo/decisionType(APPROVE|REJECT)/opinion(可空)/decidedAt`。实现中发现三点需A在 Event08 定稿时确认（**本分支未改任何权威文档**）：

1. **建议 payload 增加 `ownerUserId`（商家主账号）**——最重要。既有先例 MerchantApplicationReviewedEvent.v1 直接携带 ownerUserId，消费者自包含；而 ServiceReviewedEvent 只有 merchantId。现核查 pet-merchant-api **不存在** merchantId→owner_user_id 的查询（MerchantQueryApi.getStore 无该字段、MerchantAdmissionQueryApi/getFacts 均为 userId 会话侧输入），且 AGENTS 禁止 notification 读 merchant 表（ARCH-002）。本实现以注入 `ServiceReviewReceiverResolver` 承接：若A在 payload 加 ownerUserId，消费者删掉 resolver 即自包含（一行改动+测试调整）；若维持 8 字段，则需 merchant 域新增内部查询（契约变更，走 CCR）。
2. **aggregateType=SERVICE、aggregateId=serviceId**：本消费者按此强校验（对齐先例 MERCHANT_APPLICATION/applicationId）。若A定稿不同需同步改一处常量。
3. **submissionNo 为 JSON 整数**（非 String；Snowflake 才强制 String），serviceName 2..50、REJECT 意见 10..500、APPROVE 意见可空≤500、decidedAt 毫秒偏移格式——均与A草案 §2/§4 CHECK 约束一致，请A在 payload schema 中同口径固定。

## 4. 运行时装配状态（如实披露：未接通）

**消费者 Bean 尚未注册进 pet-boot 装配**，两级原因：
1. 边界：本角色禁改 pet-service-*/pet-merchant-*/pet-boot。既有通知消费者的注册点是 pet-boot `EventOutboxConfiguration`（MerchantApplicationReviewedConsumer Bean，开关 `pet.merchant.application.notifications-enabled`）。
2. 契约缺口：即使注册，也没有合法的 resolver 实现（见 §3.1）。

待协调者裁决 §3.1 后的装配方式（二选一，均在 pet-boot、约 10 行，建议随A的合并一并落）：
- A 加 ownerUserId：`EventOutboxConfiguration` 增加
  `@Bean @ConditionalOnProperty(prefix="pet.service.review", name="notifications-enabled", havingValue="true") ServiceReviewedConsumer serviceReviewedConsumer(DataSource ds, SnowflakeIdGenerator ids, JdbcOutboxConsumeGuard guard)`，构造 `(new ServiceReviewNotificationStore(ds, guard::tryClaim), ids, eventOwnerIdFromPayload…)`（消费者微调读取 payload ownerUserId）。
- 维持 8 字段：merchant 域先经 CCR 提供内部解析查询，boot 装配以该 API 实现 resolver，再注册上述 Bean。

**因此"发布服务→审核→站内通知→消息中心可见"完整流程未接通、不标完成**；本切片交付的是消费侧全部就绪+测试证据。合入顺序建议：A 的 PR（事件+Outbox+装配，含上述裁决落点）→ 本分支消费侧（或协调者将两分支合并排程）。

## 5. 与角色C的对齐状态

- 跳转目标采用 C 的 NAVIGATION-BASIS §3 定稿路由 `/merchant/pages/services/index`（该文档 §3 同时明示该白名单扩展归角色E）。**页面本体与 app.config 分包注册在 C 的并行分支 `codex/m-svc-pages`**：该分支合入前，消息中心点击"查看服务"会因目标页未注册而跳转失败（失败可见、不会错误导航）；合入后即通。app.config 未由本分支改动（C 是全局配置唯一 Writer，NAVIGATION-BASIS §3 同口径）。
- 冲突面提示：C 分支若也改 `messages.ts`（其文档口径是留给E）会冲突；本分支对 `messages.ts`/`messages/index.tsx`/`messages.test.ts` 的改动是消息中心兼容扩展，建议协调者按"E 先合、C 后合"或让 C rebase。
- CCR-W2-NOTIFICATION-001 §5 的消息类型登记表（`SERVICE_REVIEWED`+`SERVICE` → 服务管理页）属契约增补，本角色不改权威 CCR，**请协调者随合并做登记增补**。

## 6. 遗留与风险

- 完整审核流程（服务写入→审核事件→通知→跳转）未联调：无生产者（A未交付）、无装配（§4）、无目标页（C未合并）——三者齐后再做集成验证与人工走查，届时才可宣称流程完成。
- 运营强制下架（force-offline）是否发同类通知不在本切片（事件形状未含该 decisionType；A的SVCW-D7 是独立路由）；如需覆盖，走 Event08 增补。
- 外部微信订阅消息按裁决不做（站内为准）。
- 商家子账号/STAFF 不收服务审核通知（收件人=主账号 owner_user_id，对齐"商家主账号以C端身份在消息中心看到"先例）。
- WORK_STATE/READY_QUEUE 未改动（协调者统一收口）。
