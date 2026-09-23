# NTF-SERVICE-REVIEW 交接：服务审核结果站内通知消费侧

日期：2026-09-22。角色：E（通知消费侧）。分支 `codex/ntf-service-review-20260922`（基于 origin/develop `52a1c45`，独立 worktree `wt-ntf-svc-review`）。**2026-09-22 二次适配**：角色A PR#68 已在 Event08 定稿九字段载荷（新增 `ownerUserId`），本分支消费侧已按九字段适配并推送建 PR；**本 PR 依赖 PR#68（事件生产方）先合并**。

依据：用户 2026-09-22 裁决——审核结果站内通知按 SSOT 保留（01-SSOT:46"订单、退款、核销、售后、审核必须保留站内消息"），拆分并行：角色A（服务写入方，PR#68 `codex/adm001-service-write-20260922`）负责 ServiceReviewedEvent 及事务内 Outbox；本切片负责可靠消费、商家收件箱与权限隔离。先例＝MER-001 S5/S7 + NTF-001/C-006（PR64，develop `bb6bb5c` 已合入）：MerchantApplicationReviewedEvent.v1 → notification 域消费 → 站内落库 → C 端消息中心（商家主账号以 C 端 USER 身份收件）。

## 1. 实现范围（本分支交付）

### 后端（pet-notification-biz，未触碰 pet-service-*/pet-merchant-*/pet-boot）

| 文件 | 作用 |
|---|---|
| `event/ServiceReviewedConsumer.java` | ServiceReviewedEvent.v1 严格消费者（**九字段＝Event08 定稿，与 PR#68 对齐**）：envelope 校验（eventVersion=1、aggregateType=SERVICE、aggregateId=serviceId）、payload 严格字段集校验（**9 字段** serviceId/serviceName/merchantId/storeId/submissionNo/decisionType/opinion?/decidedAt/**ownerUserId** 全量比对，未知/重复/尾随字段拒绝且不带原始敏感内容）、Snowflake ID String 词法（含 ownerUserId）、submissionNo 为 JSON 整数 1..4294967295、serviceName 2..50 码点且无控制字符、decisionType∈{APPROVE,REJECT}、opinion 规则对齐既有（REJECT 必填 10..500 非空白；APPROVE 可空、≤500）、decidedAt 毫秒 OffsetDateTime；**收件人＝事件自带的 ownerUserId（消费者自包含，不读 merchant 表，ARCH-002）**；consumerName=`notification.service-reviewed.v1` |
| ~~`event/ServiceReviewReceiverResolver.java`~~ | **已删除**（2026-09-22 九字段适配）：Event08 定稿后事件自带 ownerUserId，原 merchantId→owner 的 resolver 注入方案（8 字段时期的防御设计）不再需要，测试不再播种 merchant 表 |
| `infrastructure/persistence/ServiceReviewNotificationStore.java` | 通知域自有事务（REQUIRES_NEW）：consume_guard claim 与落库同事务提交/回滚，UTC 存储 |
| `infrastructure/persistence/mapper/NotificationServiceReviewMapper.java` + `resources/mapper/NotificationServiceReviewMapper.xml` | 落库列：receiver_type=USER、receiver_id=**事件 ownerUserId**、category=SYSTEM、message_type=`SERVICE_REVIEWED`、biz_type=`SERVICE`、biz_id=serviceId、mandatory_inbox=1 |

内容模板（沿用既有"标题+正文、消息体无 URL、意见全文（≤500 校验上限）"口径）：
- 标题：`服务审核结果`
- APPROVE：`服务《{serviceName}》：审核通过，已上架`
- REJECT：`服务《{serviceName}》：审核未通过。{opinion}`

### 前端（frontend-miniapp，消息中心既有页面扩展）

- `src/consumer/notifications/messages.ts`：`routeForNotification` 白名单新增 `SERVICE_REVIEWED`+`SERVICE`（bizId 非空）→ `/merchant/pages/services/index`；新增 `jumpLabelFor`（`查看服务`/`查看入驻申请`）。
- `src/consumer/pages/messages/index.tsx`：跳转按钮文案改为 `jumpLabelFor`，其余交互不动。
- 跳转路由依据：**角色C 的 `planning/issues/wave-2/M-002-service-pages/NAVIGATION-BASIS.md` §3 已定稿**（在 C 的 worktree `wt-m-svc-pages`，分支 `codex/m-svc-pages`），其 §3 明确"消息中心 routeForNotification 白名单可扩展，新增 SERVICE_REVIEWED/SERVICE 类型 → 服务管理列表页；商家收件箱与通知下发本身归角色E"。故本分支直接采用 C 的最终路由，未用工作区占位路径；`messages.ts` 内留 TODO 注明页面与 app.config 注册随 C 分支合并生效。

### 测试

后端 `pet-notification-biz/src/test/.../ServiceReviewedConsumerMySqlTest.java`（真实 MySQL 8.4.9 @127.0.0.1:33461 **本轮自建隔离实例**，权威 Schema 06 建库；**九字段适配后不再播种 merchant 表**——收件人全部来自事件载荷，测试本身证明消费者不读 merchant 表），8 例：
1. APPROVE/REJECT 各落一条 mandatory 通知（含全部投影列断言，receiver=载荷 ownerUserId 1001），重复投递不重复落库（consume_log 唯一）；
2. 落库失败回滚 consume claim，恢复重试成功（RENAME TABLE 模拟）；
3. 并发重复投递恰好一条（两线程）；
4. 严格校验拒绝族（未知字段/REQUEST_CORRECTION/submissionNo 字符串/超长名称/REJECT 缺意见/意见超500/数字类型ID/aggregateId 不匹配/尾随文档/重复字段），全部在 claim 之前失败且异常消息不含载荷敏感内容；
5. **缺/坏 ownerUserId 严格拒绝不 claim**（九字段反例：载荷 remove ownerUserId、JSON 数字 1001、"0" 三种形态均 IllegalArgumentException 且 notification/consume_log 均为 0；同一 eventId 修正后重投成功且 receiver=载荷值 1099，证明严格失败在 claim 前、可重试、收件人取自字段）；
6. 真实 Outbox 全链：发布事务回滚则 outbox 无痕；提交后 OutboxDispatcher 派发 → 通知落库 + consume_log + outbox=PUBLISHED；
7. 读取/已读隔离（复用既有 NotificationInboxService）：收件主账号 1001 列表可见（messageType/bizType/bizId/title/content/readAt 全断言）；另一用户 1002 列表空、详情 404 `COMMON_NOT_FOUND`、标记已读 404；本人标记已读结果幂等（同时间戳重放）；
8. decidedAt 非 UTC（+08:00）经 Shanghai 会话连接仍落 UTC DATETIME(3)。

前端：`src/consumer/tests/messages.test.ts` 白名单/文案用例（SERVICE_REVIEWED → services 页 + `查看服务`；未登记类型无跳转、无文案）——本轮无改动，仅回归。

## 2. 测试结果（2026-09-22 九字段适配轮，本机）

- 后端：`mvn -pl pet-notification-biz test`（JAVA_HOME=Adoptium 21.0.11，PLAT003_MYSQL_URL=jdbc:mysql://127.0.0.1:33461/，env -u DOCKER_HOST）——**14/14 通过**（既有 MerchantApplicationReviewedConsumerMySqlTest 6 例回归 + 本切片 8 例）。
- 架构：`mvn -pl pet-architecture-test test` **22/22 通过**（删除 resolver 后 ARCH 规则不受影响）。
- 前端：`npm test` **139/139 通过**（九字段适配无前端改动，回归确认）。
- 环境：33452 共享实例数据目录保留待恢复、**未触碰**；按 A2/MER-001 先例自建隔离 MySQL 8.4.9（`--no-defaults --initialize-insecure`，独立 datadir `D:/Temp/ntf-svc9f-mysql-20260922`，仅监听 127.0.0.1:33461），测试结束 mysqladmin shutdown 并删除 datadir 与日志，端口已释放；测试库由 fixture 随机建、结束自动 DROP。并发避让：wmic 检查无 pet-boot/在途测试 JVM 后才启动。前端 node_modules 沿用与 `wt-ntf-messages` 的目录联接（package-lock 一致，不提交）。
- **未运行**：pet-boot 的 NotificationInboxHttpTest（在 pet-boot 测试域，本角色禁改 pet-boot；读侧逻辑复用其已验证代码，本轮改动不触及）。

## 3. 与角色A的对齐结果（已闭环：PR#68 Event08 九字段定稿）

A 在 PR#68 Event08 定稿（"2026-09-22 与角色E对齐后增补 ownerUserId"）：`ServiceReviewedEvent.v1`，eventVersion=1，aggregateType=SERVICE，aggregateId=serviceId；payload **9 字段**＝`serviceId、serviceName、merchantId、storeId、submissionNo（JSON 整数）、decisionType(APPROVE|REJECT)、opinion?（REJECT 必填 10-500，APPROVE 可空）、decidedAt、ownerUserId`（Snowflake String；服务侧创建时落 service_item.owner_user_id，33号增补列）。生产方 `publishReviewedEvent` 实测同口径（`IDS.toApi` String 化、opinion 可为 null、legacy 无主账号行 fail-closed 不发事件）。本分支此前三项确认点全部闭环：
1. **ownerUserId 已采纳**（原 §3.1 建议）→ 消费者删 resolver 自包含，测试去掉 merchant 播种；
2. aggregateType=SERVICE、aggregateId=serviceId 与 A 定稿一致，无需改；
3. submissionNo JSON 整数、serviceName 2..50、REJECT 意见 10-500、APPROVE 可空≤500、decidedAt 毫秒偏移格式——与 A 定稿及 33号 CHECK 约束一致，无需改。

## 4. 运行时装配状态（如实披露：未接通）

**消费者 Bean 尚未注册进 pet-boot 装配**（本轮维持，如实披露）：
1. 边界：本角色禁改 pet-service-*/pet-merchant-*/pet-boot。既有通知消费者的注册点是 pet-boot `EventOutboxConfiguration`。
2. PR#68 已在 `EventOutboxConfiguration` 预留装配点并注释（"ServiceReviewedConsumer bean registration lands here together with the consumer class from the notification-side PR, gated by pet.service.review.notifications-enabled (default off)"）——但 pet-boot 文件归本角色禁改，接线需协调者/后续切片落。
3. 原"契约缺口（resolver）"已消除：九字段定稿后消费者构造仅 `(ServiceReviewNotificationStore, SnowflakeIdGenerator)`，装配约 8 行：
   `@Bean @ConditionalOnProperty(prefix="pet.service.review", name="notifications-enabled", havingValue="true") ServiceReviewedConsumer serviceReviewedConsumer(DataSource ds, SnowflakeIdGenerator ids, JdbcOutboxConsumeGuard guard)`，构造 `new ServiceReviewedConsumer(new ServiceReviewNotificationStore(ds, guard::tryClaim), ids)`。

**因此"发布服务→审核→站内通知→消息中心可见"完整流程未接通、不标完成**；本切片交付的是消费侧全部就绪+测试证据。合入顺序：**PR#68（事件生产方+装配点预留）先合并 → 本 PR 消费侧合并 → 协调者补 pet-boot 接线（上述 8 行）→ 与 C 页面三方齐备后联调**，届时才可宣称流程完成。

## 5. 与角色C的对齐状态

- 跳转目标采用 C 的 NAVIGATION-BASIS §3 定稿路由 `/merchant/pages/services/index`（该文档 §3 同时明示该白名单扩展归角色E）。**页面本体与 app.config 分包注册在 C 的并行分支 `codex/m-svc-pages`**：该分支合入前，消息中心点击"查看服务"会因目标页未注册而跳转失败（失败可见、不会错误导航）；合入后即通。app.config 未由本分支改动（C 是全局配置唯一 Writer，NAVIGATION-BASIS §3 同口径）。
- 冲突面提示：C 分支若也改 `messages.ts`（其文档口径是留给E）会冲突；本分支对 `messages.ts`/`messages/index.tsx`/`messages.test.ts` 的改动是消息中心兼容扩展，建议协调者按"E 先合、C 后合"或让 C rebase。
- CCR-W2-NOTIFICATION-001 §5 的消息类型登记表（`SERVICE_REVIEWED`+`SERVICE` → 服务管理页）属契约增补，本角色不改权威 CCR，**请协调者随合并做登记增补**。

## 6. 遗留与风险

- 完整审核流程（服务写入→审核事件→通知→跳转）未联调：生产方在 PR#68（未合并）、装配在 pet-boot（§4，协调者补）、目标页在 C 分支（未合并）——三方齐备并联调后再做集成验证与人工走查，届时才可宣称流程完成。本 PR 依赖 #68 先合并（事件生产方在那边）。
- 运营强制下架（force-offline）是否发同类通知不在本切片（事件形状未含该 decisionType；A的SVCW-D7 是独立路由）；如需覆盖，走 Event08 增补。
- 外部微信订阅消息按裁决不做（站内为准）。
- 商家子账号/STAFF 不收服务审核通知（收件人=主账号 owner_user_id，对齐"商家主账号以C端身份在消息中心看到"先例）。
- WORK_STATE/READY_QUEUE 未改动（协调者统一收口）。
