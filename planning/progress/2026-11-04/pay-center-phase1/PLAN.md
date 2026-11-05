# 支付中心阶段 1 实施方案（CCR-PAY-CENTER-001 §12 第 1 阶段）

- 日期：2026-11-04；worktree `/mnt/d/wt-fix-20261104`，分支 `codex/fix-20261104`，基线 `origin/develop 49e8c28b`。
- 依据：40 号裁决补充（APPROVED）、CCR-PAY-CENTER-001（APPROVED）、40/41/42 号契约、22 号 MyBatis 裁决、21 号部署开关清单、09 号调度、08 号事件目录。
- 本阶段新契约：[75 号契约](../../../../docs/04-api/75-Payment-Center-Contract-v0.1.md)；Schema 草案：
  [SQL75](../../../../docs/03-database/75-Payment-Center-Schema-v0.1.sql)、[SQL75-Demo](../../../../docs/03-database/75-Payment-Center-Demo-Schema-v0.1.sql)。
- 本文件只做方案；未改任何 Java/SQL 迁移/YAML。§9 列出需要人工裁决的问题，实施前请先答复。

## 0. 本机基线验证（2026-11-04 实测）

| 命令 | 结果 |
|---|---|
| `python3 backend/tools/check-persistence-style.py` | `PASS: production SQL uses MyBatis Mapper XML`，exit 0 |
| `python3 backend/tools/check-module-deps.py` | `ARCH-001 scanned 43 reactor modules, 18 biz POMs` / `PASS: no biz -> biz dependencies`，exit 0 |
| `mvn -o -q -pl pet-payment-biz -am test -Dtest='Lakala*Test,RefundFundingEvidenceChecksTest,WechatOriginalRouteRefundFundingAuthorityTest' -Dsurefire.failIfNoSpecifiedTests=false`（JDK21） | exit 0；LakalaHttpClientQaTest 9、LakalaProtocolTest 6、LakalaRefundHttpClientTest 3、LakalaRefundProtocolTest 3、RefundFundingEvidenceChecksTest 5、WechatOriginalRouteRefundFundingAuthorityTest 4，全部 0 失败 |
| `mvn -o -q -pl pet-payment-biz -am test -Dtest='!*MySql*' ...` | exit 1：`pet-task-core` 的 `JdbcAsyncTaskSubmitterTest` 需要 MySQL（CannotGetJdbcConnection）。该模块测试类名不含 MySql，排除模式无效。**后续工作包请改用上一行的显式测试名清单方式** |

所有 `*MySqlTest` 及 pet-boot 的 `*AcceptanceTest`（使用 `BookingCreateAcceptanceTest.Database`）需要 MySQL，本机未运行。

## 1. 现状事实

### 1.1 预下单构包、通知/查单解析

| 事实 | 位置 |
|---|---|
| 预下单 req_data 构包，字段：merchant_no/term_no/out_trade_no/account_type/trans_type/total_amount/timeout_express/location_info/subject/notify_url/acc_busi_fields；**无 settle_type** | `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/infrastructure/provider/LakalaProtocol.java:117-137`（`preparePreorder`） |
| `PreorderInput` 记录及 11 参兼容构造 | `LakalaProtocol.java:97-109` |
| 查单构包（含 `trade_req_date`） | `LakalaProtocol.java:139-147` |
| 1112 通知验签与解析（三行签名、merchant_no/out_trade_no/trade_status/total_amount/payer_amount/trade_no/trade_time/account_type） | `LakalaProtocol.java:202-224`（`verifyNotification`） |
| 1111 查单验签与解析（同族字段，`trade_state`） | `LakalaProtocol.java:227-243`（`verifyQueryResponse`） |
| 回执对象 `NotificationReceipt` / `QueryResult` | `LakalaProtocol.java:70-80` |
| HTTP 发送：预下单/查单/关单，路径白名单 | `LakalaHttpClient.java:110-123`、`129-145`、`185-188` |
| 渠道接口与已验签包装 `VerifiedPreorder`/`VerifiedQuery` | `application/PaymentChannel.java:13-24` |
| 通知回执对象 `PaymentReceiptVerifier.VerifiedNotice`（8 字段） | `application/PaymentReceiptVerifier.java:7-11` |
| boot 中通知验签 lambda：`NotificationReceipt → VerifiedNotice` 映射 | `backend/pet-boot/src/main/java/com/petplatform/boot/config/PaymentFoundationConfiguration.java:47-62`（映射在 58-60） |
| 查单结果 `QueryResult → VerifiedNotice` 映射 | `application/PaymentNotificationService.java:64-67`（`receiveQuery` 54-73） |
| 成功写入：同店 guard → 锁行 → 回执去重 → `markPaid` + `PaymentSucceededEvent.v1` 同事务 | `PaymentNotificationService.java:75-124`（markPaid 111-113，事件 114-115） |
| `validRow` 要求 storeId/merchantId/userId > 0 | `PaymentNotificationService.java:140-145` |
| 派发：`MAY_HAVE_SENT` 栅栏 → 事务外 `submitPreorder` → 记录参数 | `application/PaymentDispatchService.java:114-162`；预校验构包 227-232；`markMayHaveSent` 233-235；预下单调用 136-140 |
| 查单只在恢复与到期协调时触发，**没有定时查单任务** | `PaymentDispatchService.java:242-253`（`queryForRecovery`）、`315-346`（`reconcileForExpiry`） |
| 支付回调 HTTP 未注册：`PaymentNotificationService.receive` 在 main 代码中无调用方 | `PaymentFoundationConfiguration.java:26` 注释；`grep receive(` 无 boot 调用 |
| C 端发起支付路由 `POST /api/v1/c/orders/{orderId}/payments` → `PaymentInitiationApi.create` | `backend/pet-boot/src/main/java/com/petplatform/boot/adapter/web/c/COrderCreateController.java:143-165` |

### 1.2 `log_no` / `log_date` 字段名

| 资料 | 结论 |
|---|---|
| `/tmp/lkl/pdf/1293.txt:17-20,81-84` | 分账请求 `log_no`（String 14，"拉卡拉对账单流水号，posp流水号，查清结算用"）、`log_date`（String 8，"posp日期，yyyyMMdd"），M 必填 |
| `/tmp/lkl/pdf/2396.txt:14-17,23-26`；`1296.txt:51-54` | 同名同义 |
| SDK `V3LabsRelationRefundRequest.java:74-78` | 退款请求有 `origin_log_no`（"对账单中的交易流水"），说明 labs 交易存在 log_no 概念 |
| SDK `V3LabsQueryTradequeryRequest.java` | 只有请求字段（merchant_no/term_no/out_trade_no/trade_no），**SDK 不含任何响应类** |
| 1111/1112 文档 | 本地 `planning/local/lakala/` 与 `/tmp/lkl` 均无；`https://o.lakala.com/open/document/1111` 需滑块验证，无法读取 |

**结论：1111 查单响应与 1112 通知中承载对账流水号/日期的字段名待联调核实。** 方案以 `log_no`/`log_date` 作为暂定名，集中为常量，按"可选字段、缺失为 NULL、不阻断支付成功"实现，SIT 拿到原始报文后核对（§9 Q1）。

### 1.3 商户绑定

- 接口：`application/PaymentMerchantBindings.java:4-5`，`Binding require(String merchantId,String storeId)`，`Binding(merchantNo,termNo,subAppId)`。
- 配置：`PaymentFoundationConfiguration.java:31-35`，`pet.payment.lakala.stores.{storeId}` → `MerchantSettings(merchantId,merchantNo,termNo,subAppId)`；Bean 在 39-46 按 storeId 取并校验 merchantId，缺失 503。
- 唯一生产调用方：`apiimpl/PaymentPreparationApiImpl.java:63`，结果写入 `insertPayment`（66）。退款使用支付行上的 merchant_no（`PaymentRefundService.java:294,332`），不再查绑定。
- 测试中以 lambda 实现该接口：`PaymentFoundationAcceptanceTest.java:470,771`、`PaymentCoordinationAcceptanceTest.java:396`、`E2eCoreLoopServer.java:296`。
- `LakalaSettings` 三参构造被测试直接调用：`PaymentDispatchConfigurationTest.java:38`、`LateRefundConfigurationTest.java:87`、`MerchantOrderConfigurationTest.java:44`；`LateRefundConfiguration.java:148` 注入该 Bean。

### 1.4 迁移与 Schema 现状

- **payment 表不在任何 Flyway 迁移里。** `backend/pet-boot/src/main/resources/db/migration/` 只有 README；`payment_order` 仅定义于 `docs/03-database/06-核心数据库Schema-v0.1.sql:385-401`，扩展于 `40-Payment-Foundation-Schema-v0.1.sql:3-17`、`41-Payment-Dispatch-Schema-v0.1.sql`、42/43/44 号草案，由 MySQL 测试直接执行（如 `BookingCreateAcceptanceTest.java:866-885` 的 `SCHEMA` 清单、`PaymentRefundServiceMySqlTest.java:386-394`）。
- 现有 Flyway 目录都是"按域、开关开启、隔离库前缀、独立 history 表"模式（如 `MarketingConfiguration.java:134-156`、`AdminTradeDispositionConfiguration.java:150-172`）。版本号现状：admin-auth V26、service V27-V30、schedule V29、staff-member V30-V31、admin-trade V32、marketing V33-V36、community V1（独立计数）。**当前最大 V36。**
- docs 编号：04-api 最大 74，03-database 最大 72；本阶段新增均用 75（与契约号对齐，沿用 40/41/42 的对齐惯例）。
- `PaymentFoundationStore.Row`（`PaymentFoundationStore.java:37-41`）由 MyBatis 按构造参数**顺序**自动映射（`PaymentMybatis.java:22` 只开了 mapUnderscoreToCamelCase，未开按名映射）；`paymentColumns` 在 `PaymentFoundationMapper.xml:8`。新增列必须**同时追加在列清单末尾和记录末尾**。

### 1.5 AsyncTask 与事件

- 生产者：`backend/pet-task-core/.../JdbcAsyncTaskSubmitter.java:22-43`（`enqueue`/`enqueueAt`），47-51 强制调用方事务与同一 DataSource。`pet-payment-biz/pom.xml:53` 已依赖 pet-task-core；biz 直接使用 submitter 的先例：`OrderExpiryApiImpl.java:26`、`WechatDeliveryTaskProducer.java:30`。
- 消费注册与 worker 在 boot：`LateRefundConfiguration.java:195-250`（`TaskRegistration` + `AsyncTaskWorker.create` + `TaskRetryDelays`）。
- 09 号 §6（`docs/06-scheduler/09-Scheduler-Retry-Compensation-v0.5.md:208`）已规定 `PAYMENT_CHANNEL_QUERY:{paymentId}`，§17（680-）规定始终用原 paymentNo 查单。
- 事件：`pet-event-api` 的 `IntegrationEvent`/`IntegrationEventPublisher`/`IntegrationEventConsumer`；`PaymentSucceededEvent.v1` 唯一消费者 `OrderPaymentResultApiImpl.java:54`。`OutboxDispatcher.java:125-131` 对无消费者事件只释放不丢弃。

### 1.6 配置现状

- `backend/pet-boot/src/main/resources/application.yml`（128 行）**没有任何 `pet.payment.*` 或 `LAKALA_*` 映射**；支付开关全靠缺省 false。
- `PaymentDispatchConfiguration.DispatchSettings`（`PaymentDispatchConfiguration.java:38-43`）字段：environment(`SIT`/`PRODUCTION`)、appId、merchantSerial、merchantPrivateKeyPath、platformSerial、platformCertificatePath、parameterKeyPath、outOrgCode、subject、requestIp、notifyUrl、terminalCloseCapability。
- `ops/payment-center.env.example` 写的是 `LAKALA_ENV=SIT 或 PROD`，与枚举 `PRODUCTION` 不一致；模板中没有 `out_org_code` 对应变量（SDK `V3CommRequest` 也不发送 out_org_code，现有协议必填）。见 §9 Q10/Q11。
- USER 支付身份接口要求持有门店 guard：`pet-user-biz/.../PaymentIdentityApiImpl.java:31-40`（`guard.requireHeld(storeId)`），支付中心行没有门店。见 §9 Q4。

## 2. 总体切分与顺序

| 包 | 执行者 | 内容 | 依赖 |
|---|---|---|---|
| WP-A | Sonnet | 渠道协议层：settle_type 构包、log_no/log_date 解析、回执对象扩展 | 无 |
| WP-B | Sonnet | 存储、配置与 ORDER 路径接线：Row/Mapper XML、平台商户、settle_type 持久化与上送、log 字段落库、application.yml、测试 schema 清单 | 编译依赖 WP-A 的公开签名（§3.1） |
| WP-C | Opus | 支付中心 API、回执写入、查单任务、DEMO 闭环 | WP-A、WP-B 合入后开始 |

WP-A 与 WP-B 写入文件集合不相交（§5）。B 可与 A 并行编码，按 §3.1 签名对接，A 先合入后 B 编译。

**偏离任务书的一处说明：** 任务书把"settle_type 由配置开关控制"放在 WP-A。若 WP-A 修改 `PaymentDispatchService` 读配置，而 WP-B 要让同一服务改读持久化行值，两包会写同一文件。方案把 settle_type 的唯一事实来源定为"prepare 时按配置写入支付行、派发时原样上送行值"（防止 prepare 与 dispatch 之间改配置导致库内记录与实际上送不一致），因此配置与接线放在 WP-B，WP-A 只提供协议能力。

## 3. WP-A 渠道层（Sonnet）

### 3.1 必须实现的公开签名（WP-B/C 依赖，不得改名）

```java
// LakalaProtocol
public record PreorderInput(String outOrgCode, LocalDateTime requestTime, String merchantNo, String termNo,
        String outTradeNo, BigDecimal amount, String subject, String subAppId, String userId,
        String requestIp, String notifyUrl, Integer timeoutExpressMinutes, String settleType) {
    // 保留现有 11 参构造；新增 12 参构造（现 canonical 签名），二者 settleType=null
}
public record NotificationReceipt(String merchantNo, String outTradeNo, String channelTradeNo,
        TradeState tradeStatus, long totalAmountCents, Long payerAmountCents,
        LocalDateTime channelTradeTime, String accountType, String logNo, LocalDate logDate) {
    // 保留现有 8 参构造，logNo/logDate=null
}
public record QueryResult(String merchantNo, String outTradeNo, String channelTradeNo,
        TradeState tradeState, long totalAmountCents, Long payerAmountCents,
        LocalDateTime channelTradeTime, String accountType, String logNo, LocalDate logDate) {
    // 保留现有 8 参构造
}
// PaymentReceiptVerifier
record VerifiedNotice(String merchantNo, String paymentNo, String channelTradeNo, String status,
        BigDecimal totalAmount, BigDecimal paidAmount, LocalDateTime channelTradeTime,
        String accountType, String logNo, LocalDate logDate) {
    // 保留现有 8 参构造
}
```

保留旧构造是为了不改动 pet-boot 中直接构造这些记录的测试（`PaymentCoordinationAcceptanceTest.java:627`、`PayTimeoutCallbackConcurrencyMySqlTest.java:480`、`E2eCoreLoopServer.java:701,723`、`PaymentFoundationAcceptanceTest.java:778`）。

### 3.2 改动清单

| 文件 | 方法/位置 | 改动 |
|---|---|---|
| `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/infrastructure/provider/LakalaProtocol.java` | `PreorderInput`（97-109） | 增加末位 `settleType`；保留 11/12 参构造；`toString` 仍 redacted |
| 同上 | `preparePreorder`（117-137） | `settleType` 为 null 或 `"0"`：不写字段（保持现有报文字节）；`"1"`：在 `notify_url` 之后、`acc_busi_fields` 之前写 `"settle_type":"1"`；其他值 `invalid()` |
| 同上 | 新增常量 `LOG_NO_FIELD="log_no"`、`LOG_DATE_FIELD="log_date"`，Javadoc 注明"1111/1112 字段名待 SIT 原始报文核实" | — |
| 同上 | 新增私有 `collectTrace(JsonNode data)` → `Trace(String logNo, LocalDate logDate)` | 两字段均缺失/null/空串 → (null,null)；`log_no` 必须是文本且匹配 `[0-9A-Za-z]{1,32}`；`log_date` 必须是文本且 `[0-9]{8}` 并以 `BASIC_ISO_DATE`+STRICT 解析；任一不满足 → (null,null)。**不抛异常**，不影响支付成功判定 |
| 同上 | `verifyNotification`（202-224）、`verifyQueryResponse`（227-243） | 返回值追加 `collectTrace` 结果；其余校验不变 |
| 同上 | `NotificationReceipt`/`QueryResult`（70-80） | 追加 `logNo`、`logDate`，保留旧构造 |
| `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/application/PaymentReceiptVerifier.java` | `VerifiedNotice`（9-11） | 追加 `logNo`、`logDate`，保留旧构造 |
| `LakalaHttpClient.java` | — | **不改**。`QueryResult` 经 `lookup` 原样上传；若实现中发现必须改，先停下报告 |

不改：`PaymentChannel`（记录类型透传）、`PaymentDispatchService`、`PaymentNotificationService`、boot 配置（属 WP-B）。

### 3.3 单元测试（新文件，无 MySQL）

`backend/pet-payment-biz/src/test/java/com/petplatform/payment/biz/infrastructure/provider/LakalaCollectFieldsTest.java`（同包，可用 `LakalaHttpClient` 包级 Exchange 构造；RSA 测试密钥内存生成，参照 `LakalaProtocolTest.java:30-34`，不读 `/tmp/lkl` 或 `planning/local` 任何密钥）：

1. `preorderOmitsSettleTypeForNullAndZero`：null、"0" 时 req_data 无 `settle_type`，且原始字节与旧 11/12 参构造产物逐字节相同。
2. `preorderSendsSettleTypeOneAtTopLevel`：`"1"` 时 `req_data.settle_type=="1"`，不在 `acc_busi_fields` 内。
3. `preorderRejectsUnknownSettleType`：`"2"`、`""`、`" 1"` → `ProtocolException`。
4. `notificationParsesLogNoAndLogDate`：签名通知含 `log_no`/`log_date` → receipt 带值，`LocalDate` 正确。
5. `notificationWithoutLogFieldsStillSucceeds`：缺失两字段 → SUCCESS 照常，值为 null。
6. `malformedLogFieldsBecomeNullNotFailure`：`log_no` 超 32 位/含符号/非文本、`log_date` 为 `20241332`/`2024-10-10`/数字类型 → 支付结果不变，两字段 null。
7. `logFieldsArePaired`：只有 `log_no` 或只有 `log_date` → 两者 null。
8. `queryResponseParsesLogFields`：已签名查单响应 → `QueryResult` 带值；FAIL/DEAL 状态同样解析。
9. `httpClientLookupCarriesLogFields`：经 `LakalaHttpClient.lookup`（假 Exchange）返回的 `VerifiedQuery.result()` 带值。
10. `legacyConstructorsDefaultToNull`：旧构造的 `PreorderInput`/`NotificationReceipt`/`QueryResult`/`VerifiedNotice` 新字段为 null。

验证命令（JDK21，离线）：

```bash
export JAVA_HOME=$HOME/.local/opt/jdk-21 PATH=$HOME/.local/opt/jdk-21/bin:$PATH
cd backend && mvn -o -q -pl pet-payment-biz -am test \
  -Dtest='Lakala*Test,RefundFundingEvidenceChecksTest,WechatOriginalRouteRefundFundingAuthorityTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -q -pl pet-boot -am test-compile   # 确认 pet-boot 测试仍能编译（旧构造保留）
cd .. && python3 backend/tools/check-persistence-style.py && python3 backend/tools/check-module-deps.py
```

## 4. WP-B 存储与配置（Sonnet）

### 4.1 迁移目录决定

**决定：阶段 1 不新增 Flyway 文件。** Schema 变化写在 `docs/03-database/75-Payment-Center-Schema-v0.1.sql`（已由本方案写好），与 40/41/42/43 号支付草案同一约定；测试夹具按清单执行。理由：

1. payment 表从未进入 Flyway（§1.4）。一个只含 `ALTER payment_order` 的 Flyway 文件在新库上没有基表，必然失败；如果在独立 history 表下执行，也不能保证 06/40/41 已执行。
2. 现有 Flyway 目录都是"域独占表 + 隔离库前缀"模式；阶段 1 没有域独占新表（`payment_center_request` 仍属 payment 草案）。
3. CCR §12 不授权生产迁移，生产迁移整合由 `db/migration/README.md` 所述的统一整合工作负责。

`settlement-migration` 目录留给阶段 2（结算单等全新表）创建，届时版本号按当时最大值顺延（当前最大 V36，即预计 V37）。若人工裁决要求阶段 1 就建 Flyway 文件，见 §9 Q3。

### 4.2 改动清单

| 文件 | 改动 |
|---|---|
| `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/infrastructure/persistence/PaymentFoundationStore.java` | `Row` 末尾追加 `String bizType, String bizNo, String settleType, String logNo, LocalDate logDate`（顺序必须与 XML 列清单末尾一致） |
| `backend/pet-payment-biz/src/main/resources/mapper/PaymentFoundationMapper.xml` | `paymentColumns`（8）末尾追加 `biz_type,biz_no,settle_type,log_no,log_date`；`insertPayment`（33-38）增加 `settle_type=#{settleType}`（ORDER 路径 biz_type/biz_no 不写，保持 NULL）；`markPaid`（62-66）增加 `log_no=#{logNo},log_date=#{logDate}`；新增 `fillCollectTrace`：`UPDATE payment_order SET log_no=#{logNo},log_date=#{logDate},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{paymentId} AND status='PAID' AND log_no IS NULL AND log_date IS NULL` |
| `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/infrastructure/persistence/mapper/PaymentFoundationMapper.java` | 新增 `int fillCollectTrace(Map<String,Object> p);` |
| `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/application/PaymentMerchantBindings.java` | 保持函数式接口与 `require` 不变；新增 `default Binding requirePlatform()`，默认抛 503（失败关闭）。WP-C 使用 |
| `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/apiimpl/PaymentPreparationApiImpl.java` | 新增带 `String settleType` 的构造（校验仅 `"0"`/`"1"`），旧 6 参构造等价于 `settleType="0"`（不改变现有测试语义）；`insertPayment`（66）传 `"settleType"` |
| `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/application/PaymentDispatchService.java` | 两处构造 `PreorderInput`（136-140 用 `Plan`，228-231 预校验）传入行值：`"1".equals(payment.settleType()) ? "1" : null`；`Plan` 记录增加 `settleType` 字段。不读配置 |
| `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/application/PaymentNotificationService.java` | `receiveQuery`（64-67）把 `result.logNo()/logDate()` 传入 `VerifiedNotice` 新构造；`persist`：`markPaid` 参数增加 `"logNo"`,`"logDate"`；已 PAID 且 SUCCESS 回执通过 88-91 一致性检查后（108 行返回前），若 `notice.logNo()!=null` 调用 `fillCollectTrace`。其余逻辑不变 |
| `backend/pet-boot/src/main/java/com/petplatform/boot/config/PaymentFoundationConfiguration.java` | `LakalaSettings` 增加 `PlatformMerchant platform`（merchantNo/termNo/subAppId）与 `String settleType`；保留现 3 参构造（platform=null，settleType="0"）。`paymentMerchantBindings`：`stores` 非空且 `platform` 已配置 → 启动失败（歧义）；仅 `platform` → `require(m,s)` 校验两个 ID 非空后返回平台绑定，`requirePlatform()` 返回同一绑定；仅 `stores` → 原逻辑，`requirePlatform()` 失败关闭；都未配置 → 原失败关闭。验签 lambda（58-60）改用 `VerifiedNotice` 10 参构造传 `logNo/logDate`。`paymentPreparationApi` 传 `settings.settleType()`（值域外启动失败） |
| `backend/pet-boot/src/main/resources/application.yml` | 新增 `pet.payment` 段（见 §4.3），全部 `${VAR:默认}`，开关默认 false |
| `ops/payment-center.env.example` | `LAKALA_ENV` 注释改为 `SIT 或 PRODUCTION`；增加 `LAKALA_OUT_ORG_CODE=`（空，注明待拉卡拉确认）、`LAKALA_SETTLE_TYPE=1`；不写任何真实值 |
| `docs/08-engineering/21-部署开关组合清单-v1.0.md` | §1.6 增加 `pet.payment.lakala.settle-type`、`pet.payment.lakala.platform.*` 行与歧义启动失败说明；§3 失败表增加对应报错 |

**对 `PaymentMerchantBindings` 调用方的影响：** 接口仍是函数式接口、`require` 签名不变，所有测试 lambda 无需修改。生产唯一调用方 `PaymentPreparationApiImpl` 不改调用代码。平台模式下所有门店收款到同一 `merchant_no`；门店/商户归属仍由 ORDER 事实决定并写入支付行，退款继续用支付行上的 merchant_no。

### 4.3 application.yml 映射（只做阶段 1 需要的变量）

```yaml
pet:
  payment:
    foundation:
      enabled: ${PAYMENT_FOUNDATION_ENABLED:false}
    dispatch:
      enabled: ${PAYMENT_DISPATCH_ENABLED:false}
      environment: ${LAKALA_ENV:SIT}
      app-id: ${LAKALA_APP_ID:}
      merchant-serial: ${LAKALA_MERCHANT_SERIAL_NO:}
      merchant-private-key-path: ${LAKALA_MERCHANT_PRIVATE_KEY_PATH:}
      platform-serial: ${LAKALA_PLATFORM_CERT_SERIAL:}
      platform-certificate-path: ${LAKALA_PLATFORM_CERT_PATH:}
      parameter-key-path: ${PAYMENT_PARAM_AES_KEY_PATH:}
      out-org-code: ${LAKALA_OUT_ORG_CODE:}
      subject: ${LAKALA_SUBJECT:}
      request-ip: ${LAKALA_REQUEST_IP:}
      notify-url: ${LAKALA_PAY_NOTIFY_URL:}
    lakala:
      notification-certificate-path: ${LAKALA_NOTIFY_CERT_PATH:}
      channel-time-zone: ${LAKALA_CHANNEL_TIMEZONE:}
      settle-type: ${LAKALA_SETTLE_TYPE:1}
      platform:
        merchant-no: ${LAKALA_MERCHANT_NO:}
        term-no: ${LAKALA_TERM_NO:}
        sub-app-id: ${WECHAT_MINI_APP_ID:}
```

注意：`platform.*` 三项为空串时须视为"未配置"（不能因空串进入平台模式）；`environment` 空值或 `PROD` 在 dispatch 开启时启动失败（现有行为）。不映射：SM4、退款/分账/提现/接收方回调地址、`LAKALA_ORG_CODE`、`LAKALA_SPLIT_LOWEST_RATIO`、`SETTLEMENT_RECEIVER_AES_KEY_PATH`（阶段 2 起）。`pet.refund.*` 的退款凭证映射不在本阶段。

### 4.4 测试 schema 清单（必须改，否则所有使用 PaymentFoundationMapper 的 MySQL 测试会因未知列失败）

在以下夹具的 schema 列表末尾追加 `"75-Payment-Center-Schema-v0.1.sql"`：

- `backend/pet-boot/src/test/java/com/petplatform/boot/booking/BookingCreateAcceptanceTest.java`（`Database.SCHEMA`，866-885；被 pet-boot 多数 Acceptance 测试共用）
- `backend/pet-boot/src/test/java/com/petplatform/boot/booking/SelectionExpiryAcceptanceTest.java`（约 353）
- `backend/pet-boot/src/test/java/com/petplatform/boot/adapter/web/c/OrderQueryHttpTest.java`（约 452）
- `backend/pet-boot/src/test/java/com/petplatform/boot/auth/AdminFacesFixRegressionHttpTest.java`（约 431）
- `backend/pet-boot/src/test/java/com/petplatform/boot/e2e/E2eCoreLoopServer.java`（约 1375）
- `backend/pet-payment-biz/src/test/java/com/petplatform/payment/biz/application/PaymentRefundServiceMySqlTest.java`（389-391）

order-biz 的 `MerchantCustomerStatsMySqlTest`、`OrderCreationMySqlTest` 不使用 payment Mapper，不改。实施时先 `grep -rn "40-Payment-Foundation-Schema" --include=*.java backend` 复核清单。

### 4.5 WP-B 测试

无 MySQL（本机可运行）：

1. 新建 `backend/pet-boot/src/test/java/com/petplatform/boot/config/PaymentFoundationConfigurationTest.java`（`ApplicationContextRunner`，mock DataSource）：平台模式 `require` 与 `requirePlatform` 返回同一绑定；`stores`+`platform` 同时配置启动失败；只配 `stores` 时 `requirePlatform` 抛 503；`platform.*` 为空串视为未配置；`settle-type` 为 `2` 启动失败；三参 `LakalaSettings` 构造仍可用。
2. 新建 `backend/pet-boot/src/test/java/com/petplatform/boot/config/PaymentApplicationYamlDefaultsTest.java`：用 `YamlPropertySourceLoader` 读 `application.yml`，断言 foundation/dispatch 缺省 false、`settle-type` 缺省 `1`、所有凭证项缺省空串、文件中不含任何疑似密钥字面量。
3. 回归：`PaymentDispatchConfigurationTest`、`LateRefundConfigurationTest`、`MerchantOrderConfigurationTest` 不改且通过。

需要 MySQL（编写，本机未运行）：

4. 新建 `backend/pet-payment-biz/src/test/java/com/petplatform/payment/biz/application/PaymentCollectFieldsMySqlTest.java`（schema 06/13/40/41/43/75）：
   prepare 写入 settle_type=1/0；历史行（直接插入，不含新列）读出新列为 NULL；带 log 字段的通知 → PAID 行有 log_no/log_date；
   先无 log 通知后带 log 查单 → 补写一次；已有值后不同值 → 不覆盖；PAID 后 FAIL/DEAL 回执不写 log；`ck_payment_log_pair` 拒绝只写一列。
5. pet-boot `PaymentFoundationAcceptanceTest`/`PaymentCoordinationAcceptanceTest` 原样回归（schema 清单已追加）。

本机可运行命令：

```bash
cd backend && mvn -o -q -pl pet-boot -am test \
  -Dtest='PaymentFoundationConfigurationTest,PaymentApplicationYamlDefaultsTest,PaymentDispatchConfigurationTest,LateRefundConfigurationTest,MerchantOrderConfigurationTest,Lakala*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -q -pl pet-boot -am test-compile
cd .. && python3 backend/tools/check-persistence-style.py && python3 backend/tools/check-module-deps.py
```

## 5. WP-A 与 WP-B 写入文件清单（不相交）

| WP-A | WP-B |
|---|---|
| `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/infrastructure/provider/LakalaProtocol.java` | `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/infrastructure/persistence/PaymentFoundationStore.java` |
| `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/application/PaymentReceiptVerifier.java` | `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/infrastructure/persistence/mapper/PaymentFoundationMapper.java` |
| `backend/pet-payment-biz/src/test/java/com/petplatform/payment/biz/infrastructure/provider/LakalaCollectFieldsTest.java`（新） | `backend/pet-payment-biz/src/main/resources/mapper/PaymentFoundationMapper.xml` |
| | `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/application/PaymentMerchantBindings.java` |
| | `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/apiimpl/PaymentPreparationApiImpl.java` |
| | `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/application/PaymentDispatchService.java` |
| | `backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/application/PaymentNotificationService.java` |
| | `backend/pet-payment-biz/src/test/java/com/petplatform/payment/biz/application/PaymentCollectFieldsMySqlTest.java`（新） |
| | `backend/pet-payment-biz/src/test/java/com/petplatform/payment/biz/application/PaymentRefundServiceMySqlTest.java` |
| | `backend/pet-boot/src/main/java/com/petplatform/boot/config/PaymentFoundationConfiguration.java` |
| | `backend/pet-boot/src/main/resources/application.yml` |
| | `backend/pet-boot/src/test/java/com/petplatform/boot/config/PaymentFoundationConfigurationTest.java`（新） |
| | `backend/pet-boot/src/test/java/com/petplatform/boot/config/PaymentApplicationYamlDefaultsTest.java`（新） |
| | `backend/pet-boot/src/test/java/com/petplatform/boot/booking/BookingCreateAcceptanceTest.java` |
| | `backend/pet-boot/src/test/java/com/petplatform/boot/booking/SelectionExpiryAcceptanceTest.java` |
| | `backend/pet-boot/src/test/java/com/petplatform/boot/adapter/web/c/OrderQueryHttpTest.java` |
| | `backend/pet-boot/src/test/java/com/petplatform/boot/auth/AdminFacesFixRegressionHttpTest.java` |
| | `backend/pet-boot/src/test/java/com/petplatform/boot/e2e/E2eCoreLoopServer.java` |
| | `ops/payment-center.env.example` |
| | `docs/08-engineering/21-部署开关组合清单-v1.0.md` |

两列无交集。`docs/03-database/75-*.sql` 与 `docs/04-api/75-*.md` 已由本方案写好，A、B 只读；如需改动先回报。

## 6. WP-C 支付中心契约与 DEMO 闭环（Opus）

对外契约见 [75 号](../../../../docs/04-api/75-Payment-Center-Contract-v0.1.md)。以下是实现方案与理由。

### 6.1 API（`pet-payment-api`）

- `command/PaymentCenterApi`（create/query）、`dto/PaymentCenterTypes`（命令与视图记录）、`query/PaymentCenterFactsApi`（`requireSucceeded`）。签名见契约 §4。
- 命令含 `bizType`、`bizNo`、`amount`、`expireAt`；payer 只来自 `CommandContext` 的 USER `operatorId`；requestId 来自 context。命令里没有收款方字段，金额由进程内业务 Owner 从自身事实给出。
- 事件 payload `PaymentCenterSucceededPayload` 放 `pet-event-api`（与 `PaymentSucceededPayload` 同处）。

### 6.2 与 ORDER 路径的关系

本阶段 ORDER 路径不迁移、不改调用：`PaymentDispatchService`、`PaymentNotificationService`、`PaymentPreparationApiImpl` 中 ORDER 逻辑在 WP-C 中不修改。
支付中心行（`order_id IS NULL AND biz_type IS NOT NULL`）进入 ORDER 读路径时，`Row.orderId` 为原始 long，MyBatis 构造映射会失败 → 现有 catch 转 503，即失败关闭。WP-C 通过新的回调路由器（§6.5）把支付中心行引到自己的写入器，不依赖这一失败路径做路由。

### 6.3 业务类型策略与 DEMO 边界

- `PaymentCenterBizPolicy`（biz 内部）：`bizType → (maxAmount, maxDeadline)`。阶段 1 只注册 `DEMO`，且仅当 `pet.payment.center.demo.enabled=true`；未注册类型 `INVALID_ARGUMENT`。
- DEMO：代码在 `com.petplatform.payment.biz.center.demo`；表 `payment_demo_order`（SQL75-Demo，仅测试库）；不注册任何 HTTP；DEMO 开启且 `pet.payment.dispatch.environment=PRODUCTION` 时启动失败；金额上限默认 0.01 元，期限 10 分钟（§9 Q9）。
- SIT 触发方式待裁决（§9 Q6）。默认方案：opt-in 的手工联调测试 `PaymentCenterDemoSitLiveTest`（需显式环境变量才运行，CI 不跑），调用 `DemoPaymentService.start`，把微信参数打印为脱敏摘要供测试人员在小程序调起。

### 6.4 create 流程与同事务登记查询任务

1. 校验：非外层事务；USER；requestId；bizType 策略；bizNo 格式；金额；期限（契约 §4.1）。
2. 事务 T1：`payment_center_request` 插入（ON DUPLICATE KEY no-op）→ `FOR UPDATE` 读回 → 绑定参数不同则 `IDEMPOTENCY_KEY_CONFLICT`。
3. 事务 T2：`SELECT … FROM payment_order WHERE biz_type=? AND biz_no=? FOR UPDATE`。已存在：核对 user/amount/expire，绑定请求，进入 T3；不存在：`requirePlatform()` 取平台商户 → 插入 `payment_order`（order/store/merchant 为 NULL，biz_type/biz_no/settle_type）与 `payment_dispatch(PREPARED)` → 绑定请求。
4. 事务 T3（派发栅栏）：锁支付行与派发行。`PARAMETERS_READY` 且有效 → 解密重放；`MAY_HAVE_SENT`/`UNKNOWN` → 事务外查一次，503；`PREPARED` → 读 USER 支付身份（§9 Q4）→ 计算 `timeout_express`（1～10 整分钟）→ 预校验构包 → `markMayHaveSent` → **同事务** `JdbcAsyncTaskSubmitter.enqueueAt("PAYMENT_CHANNEL_QUERY:{paymentId}", "PAYMENT", "PAYMENT_CHANNEL_QUERY", "PAYMENT", paymentId, null, {"paymentId":"…"}, 60, "PAYMENT_CHANNEL_QUERY", now+5s)`。
5. 事务外 `PaymentChannel.submitPreorder`。未知 → 短事务置 UNKNOWN，503。
6. 事务 T4：记录已验签参数，AES-GCM 加密，AAD `PAYMENT_CENTER_PARAMETERS_V1:{id}:{no}`（与 ORDER 的 `PAYMENT_PARAMETERS_V1` 区分）。返回参数。

锁的选择：支付中心行没有门店，不使用 `ScheduleCapacityGuardApi`；以 `payment_order` 行 `FOR UPDATE` 作为串行化点，通知/查单写入器同样先锁该行。

**为什么新增查询任务而不是复用现有 dispatch/query：** 现有 `PaymentDispatchService` 没有任何定时查单（§1.1，查单只在恢复和到期协调中触发），且与 ORDER 事实、门店 guard、到期协调强绑定，复用需要改 ORDER 路径，违反本阶段"ORDER 不变"。新增的只是任务登记与处理器；渠道调用复用 `PaymentChannel.lookup`，派发状态复用 `payment_dispatch` 与其 Mapper 语句，回执复用 `payment_channel_receipt`，任务键沿用 09 号既有的 `PAYMENT_CHANNEL_QUERY:{paymentId}`。

参数加解密与 ORDER 的私有实现重复约 40 行。阶段 1 不抽取公共类，以免改动 ORDER 文件；阶段 8 迁移时合并（列入遗留）。

### 6.5 回执写入、通知路由与查询处理器

- `PaymentCenterReceiptWriter`：`receiveNotification(headers, body)` 与包内 `receiveQuery(paymentId, VerifiedQuery)`。锁支付行 → 校验是支付中心行、平台商户号、CNY、`LAKALA_WECHAT` → 回执摘要去重 → 按状态推进：SUCCESS → `markPaid`（含 log 字段）+ `PaymentCenterSucceededEvent.v1`（Outbox 同事务）；FAIL → FAILED；CLOSE → CLOSED；DEAL/UNKNOWN 等 → PAYING；退款/撤销类 → `RECONCILIATION_REQUIRED`。已 PAID 的重复成功回执：一致性检查同 ORDER（`PaymentNotificationService.java:88-91`），通过后按 WP-B 的 `fillCollectTrace` 补写。
- `PaymentCallbackRouter.receive(headers, body)`：只从未验签报文取 `out_trade_no` 作提示 → `selectRouteByNo` 读 `(id, biz_type, order_id)` → 支付中心行交 `PaymentCenterReceiptWriter`，其余交现有 `PaymentNotificationService.receive`。阶段 1 不注册 HTTP（§9 Q5），路由器供测试和后续回调控制器使用。
- `PaymentCenterQueryCoordinator.execute(paymentId)` → `Done | Retry(nextAt) | Exhausted`：读行（不锁）→ 终态则 Done → 事务外 `lookup` → `receiveQuery` → 非终态且 `now < expireAt + 5min` 则 Retry；超过则 Exhausted。boot 中 `TaskRegistration` 把 Exhausted 映射为 `TaskExecutionResult.Dead("PAYMENT_QUERY_EXHAUSTED")`，Retry 按 5s/15s/30s/1m/2m/5m 退避（`TaskRetryDelays` 策略名 `PAYMENT_CHANNEL_QUERY`）。处理器遇到 ORDER 行返回 `Cancelled("NOT_PAYMENT_CENTER")`。

### 6.6 状态、幂等、失败处理

见契约 §5。补充：

- DB 提交结果不明（SQLState 08）沿用 `PaymentPreparationApiImpl.java:39-46` 的"一次权威重读"模式。
- 锁等待/死锁（1205/1213）→ `COMMON_CONFLICT`，要求原 requestId 重试。
- 查询任务 DEAD 后不推定失败；阶段 6 补偿扫描负责重新登记或告警。

### 6.7 事件与 DEMO 确认

- `PaymentCenterSucceededEvent.v1`：新增事件类型，登记 08 号事件目录（需审批，§9 Q7）。不复用 v1 的原因：`OrderPaymentResultApiImpl` 是 `PaymentSucceededEvent.v1` 的必要消费者，收到无 orderId 的事件会持续失败重试。
- `DemoPaymentConfirmConsumer implements IntegrationEventConsumer`：consumerName `payment-demo-confirm`，eventTypes `{PaymentCenterSucceededEvent.v1}`；非 DEMO 类型直接记录消费成功；DEMO 类型在消费日志同事务内 `requireSucceeded(DEMO, bizNo)` → `payment_demo_order` CAS `CREATED→PAID`（或 `AMOUNT_MISMATCH`），写 `source_event_id`。

### 6.8 WP-C 文件清单（预计）

pet-payment-api：
- `backend/pet-payment-api/src/main/java/com/petplatform/payment/api/command/PaymentCenterApi.java`（新）
- `backend/pet-payment-api/src/main/java/com/petplatform/payment/api/dto/PaymentCenterTypes.java`（新）
- `backend/pet-payment-api/src/main/java/com/petplatform/payment/api/query/PaymentCenterFactsApi.java`（新）

pet-event-api：
- `backend/pet-event-api/src/main/java/com/petplatform/event/api/PaymentCenterSucceededPayload.java`（新）

pet-payment-biz（main）：
- `.../payment/biz/center/PaymentCenterService.java`（新，implements PaymentCenterApi）
- `.../payment/biz/center/PaymentCenterBizPolicy.java`（新）
- `.../payment/biz/center/PaymentCenterReceiptWriter.java`（新）
- `.../payment/biz/center/PaymentCenterQueryCoordinator.java`（新）
- `.../payment/biz/center/PaymentCenterFactsApiImpl.java`（新）
- `.../payment/biz/center/PaymentCallbackRouter.java`（新）
- `.../payment/biz/center/CenterParameterCipher.java`（新）
- `.../payment/biz/center/demo/DemoPaymentService.java`、`DemoPaymentConfirmConsumer.java`（新）
- `.../payment/biz/infrastructure/persistence/PaymentCenterStore.java`、`PaymentDemoStore.java`（新，同包以使用包级 `PaymentMybatis`）
- `.../payment/biz/infrastructure/persistence/mapper/PaymentCenterMapper.java`、`PaymentDemoMapper.java`（新）
- `backend/pet-payment-biz/src/main/resources/mapper/PaymentCenterMapper.xml`、`PaymentDemoMapper.xml`（新；文件名符合 `classpath*:mapper/Payment*Mapper.xml`）

pet-user-api / pet-user-biz（仅当 §9 Q4 选 A）：
- `backend/pet-user-api/src/main/java/com/petplatform/user/api/query/PaymentIdentityApi.java`（新增无门店方法）
- `backend/pet-user-biz/src/main/java/com/petplatform/user/biz/apiimpl/PaymentIdentityApiImpl.java`

pet-boot：
- `backend/pet-boot/src/main/java/com/petplatform/boot/config/PaymentCenterConfiguration.java`（新：开关、Bean、worker、TaskRegistration、DEMO×PRODUCTION 启动失败）
- `backend/pet-boot/src/main/resources/application.yml`（追加 `pet.payment.center.*`，默认 false）

文档：
- `docs/05-events/08-Integration-Event-Catalog-v0.6.md`（登记新事件）
- `docs/08-engineering/21-部署开关组合清单-v1.0.md`（center 三个开关）
- `docs/04-api/75-Payment-Center-Contract-v0.1.md`（如实现中需要修订，先回报）

### 6.9 WP-C 测试

无 MySQL（本机可运行）：
1. `PaymentCenterBizPolicyTest`：未注册类型、金额 scale/上限、期限窗口、bizNo 格式。
2. `CenterParameterCipherTest`：加解密往返；AAD 与 ORDER 不互通；篡改失败。
3. `PaymentCenterQueryBackoffTest`（boot 的 TaskRegistration 静态工厂）：退避序列、Exhausted→Dead、ORDER 行→Cancelled。
4. `PaymentCenterConfigurationTest`（ApplicationContextRunner）：默认不装配；worker 需 center；DEMO 需 center+outbox；DEMO×PRODUCTION 启动失败；缺平台商户启动失败。
5. 架构：`check-persistence-style.py`、`check-module-deps.py`；新代码无 JdbcTemplate/注解 SQL。

需要 MySQL（编写，本机未运行）——`backend/pet-payment-biz/src/test/java/com/petplatform/payment/biz/center/PaymentCenterMySqlTest.java`，schema 06/13/40/41/43/75/75-Demo + Outbox 表：
6. create 成功：一条 payment_order、PARAMETERS_READY、`async_task` 中恰有 `PAYMENT_CHANNEL_QUERY:{id}` 且与 MAY_HAVE_SENT 同提交（注入在 enqueue 后抛错 → 两者都不存在）。
7. 同 requestId 重放、同键异参冲突、新 requestId 同业务对象重放、金额不同冲突。
8. 预下单未知 → UNKNOWN、不重发；查询任务 SUCCESS → PAID + 一次事件。
9. 通知 × 查单并发只一次事件；重复通知去重；迟到 SUCCESS（已 CLOSED）。
10. 退款类回执 → RECONCILIATION_REQUIRED，后续 SUCCESS 不产生事件。
11. DEMO：事件投递 → `requireSucceeded` → PAID；实付不等 → AMOUNT_MISMATCH；重复投递 NOOP。
12. 路由器：支付中心行与 ORDER 行分别进入各自写入器；ORDER 入口收到支付中心行号 → 503 且不写库。
13. ORDER 回归：pet-boot `PaymentFoundationAcceptanceTest`、`PaymentCoordinationAcceptanceTest`、`PayTimeoutCallbackConcurrencyMySqlTest` 原样通过。

## 7. 风险

| 风险 | 说明 | 处置 |
|---|---|---|
| log 字段名未核实 | 1111/1112 原文缺失；若真实字段名不同，log 列全为 NULL | 解析集中为常量；SIT 第一笔即核对原始报文；阶段 2 分账遇 NULL 失败关闭 |
| settle_type=1 对 ORDER 的资金影响 | 上送后资金进入平台商户待分账，阶段 2 前无分账能力 | §9 Q2；生产启用必须在阶段 2 之后 |
| 平台商户未开通分账（1284） | 未开通时 settle_type=1 预下单是否被拒未知 | SIT 先用 `LAKALA_SETTLE_TYPE=0/1` 各试一次记录结果 |
| SIT 微信限制 | 40 号 §2 引 1005：测试环境商户与 AppID 不匹配，可能拿不到真实 SUCCESS | SIT 验收目标限定为预下单/查单/验签链路；成功路径以签名夹具测试证明 |
| `order_id` 改可空 | 改变 payment_order 原有不变量 | CHECK 约束保证 ORDER 行仍非空；ORDER 读路径遇空失败关闭 |
| 测试 schema 清单遗漏 | 漏加 75 号会让 MySQL 测试报未知列 | §4.4 清单 + 实施时 grep 复核 |
| Row 构造映射顺序 | MyBatis 按列序映射记录 | 列与记录字段同序追加；MySQL 测试覆盖 |
| 参数加解密重复实现 | 两份 AES-GCM 代码 | AAD 域隔离；阶段 8 合并 |
| 无支付回调 HTTP | 阶段 1 只能靠查单确认，时延 5s 起 | §9 Q5 |
| 本机无 MySQL | 所有落库与并发测试本机无法执行 | 报告中标注"未运行"，须在具备 MySQL 的环境补跑 |

## 8. 不做事项

结算单、T+d、分账/回退/撤销、接收方、钱包、提现、对账与补偿扫描（阶段 2～6）；ORDER 迁移到支付中心、ORDER 行回填 biz_type（阶段 8）；
支付中心关单；公开支付中心/回调/DEMO HTTP（待 Q5/Q6）；生产迁移与 Flyway 文件（待 Q3）；引入拉卡拉 SDK；把 `/tmp/lkl` 或 `planning/local/lakala/` 中任何测试参数、私钥、SM4 密钥写入受跟踪文件；
MQ 延迟队列；任何 git commit/push。

## 9. 需要人工裁决的问题

1. **Q1 log 字段名**：1111/1112 的对账流水号/日期字段名本地资料查不到。是否同意按暂定名 `log_no`/`log_date` 实现可选解析，SIT 首笔报文核实后再定？（否则 WP-A 的解析部分需等文档）
2. **Q2 ORDER 是否在阶段 1 上送 settle_type=1**：CCR 要求全部交易带分账标识，但阶段 2 前没有分账能力。默认值按 CCR 设 `1`，是否同意？或阶段 1 默认 `0`，阶段 2 再切 `1`？
3. **Q3 迁移形式**：方案为阶段 1 只写 docs 草案 SQL75（不建 Flyway 文件），settlement-migration 从阶段 2 开始（预计 V37）。是否同意？
4. **Q4 payer 身份来源**：现有 `PaymentIdentityApi` 必须持有门店 guard。选 A：在 pet-user-api/biz 增加无门店重载（推荐，改 USER 模块两个文件）；选 B：DEMO 专用，从部署配置读测试用户 openid（不触碰 USER 模块，仅 SIT 可用）。
5. **Q5 支付回调 HTTP**：阶段 1 是否开放 `/callbacks/lakala/pay`（默认关）？40 号 §6 目前写明"外部回调 HTTP 暂不开放"。不开放则 DEMO 只靠查单确认。
6. **Q6 SIT 如何触发 DEMO 支付**：opt-in 手工联调测试（推荐，无 HTTP），还是临时内部 HTTP（默认关、非生产）？
7. **Q7 新事件**：是否批准新增 `PaymentCenterSucceededEvent.v1` 并登记 08 号事件目录？CCR §3 只列了沿用 `PaymentSucceededEvent.v1`，沿用会导致 ORDER 消费者失败。
8. **Q8 `payment_order.order_id` 改为可空**（含 CHECK 约束），是否接受？替代方案是支付中心另建 `payment_center_order` 表，但与 CCR §4.1 "payment_order 新增 biz_type/biz_no" 不一致。
9. **Q9 DEMO 金额上限与期限**：默认 0.01 元、10 分钟，是否接受？
10. **Q10 out_org_code 取值**：现有协议必填，env 模板无对应变量，SDK 不发送。请向拉卡拉确认取值（是否等于 appid 或机构号）；方案新增 `LAKALA_OUT_ORG_CODE`，未填时 dispatch 启动失败。
11. **Q11 `LAKALA_ENV` 取值**：模板写 `PROD`，代码枚举是 `PRODUCTION`。方案改模板注释，不改代码，是否同意？
