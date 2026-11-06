# 支付中心阶段 1 实施报告（CCR-PAY-CENTER-001 §12 第 1 阶段）

- 日期：2026-11-04；worktree `/mnt/d/wt-fix-20261104`，分支 `codex/fix-20261104`，基线 `origin/develop 49e8c28b`。
- 范围：PLAN 的 WP-A（渠道协议）、WP-B（存储与配置）、WP-C（支付中心契约与 DEMO 闭环）。
- 第 1 轮审查结论 NEEDS_CHANGES（1 个阻断 B1、6 条建议 S1～S6），本版是重做，逐条回应见 §8。
- 未执行 git commit / push / 切换分支 / 破坏性命令。未把 `/tmp/lkl` 或 `planning/local/lakala/` 中的任何参数、私钥、SM4 密钥写入受跟踪文件。
- 本机没有 MySQL 和 Docker。所有需要数据库的测试都只写好、编译通过，**未在本机运行**（见 §5）。

## 1. 结论

| 项 | 状态 |
|---|---|
| 收款补齐（settle_type 上送、log_no/log_date 解析落库、平台唯一商户） | WP-A/WP-B 完成，离线测试通过 |
| 业务无关支付中心契约 `PaymentCenterApi` / `PaymentCenterFactsApi` 及实现 | WP-C 完成，离线测试通过；落库与并发行为只有未运行的 MySQL 测试覆盖 |
| DEMO"支付 → 通知/查单 → 确认"闭环 | 代码完成，默认关闭、无 HTTP；闭环由 `PaymentCenterMySqlTest` 覆盖，**未运行** |
| ORDER 现有支付路径 | WP-C 未修改任何 ORDER 文件；WP-B 的 ORDER 改动只有 settle_type 写入/上送与 log 字段落库 |
| 架构门禁 | `check-persistence-style.py`、`check-module-deps.py`、`check-display-status.py`、`ArchitectureRulesTest` 均通过 |

## 2. 人工待裁决项的处理（PLAN §9）

PLAN §9 的问题在实施时都还没有答复。WP-C 按 PLAN 推荐的缺省方案实施，均可回退：

| 问题 | 本次采用 | 回退方式 |
|---|---|---|
| Q1 log 字段名 | 暂定 `log_no`/`log_date`，可选解析（WP-A） | 改 `LakalaProtocol` 两个常量 |
| Q2 ORDER 默认 settle_type | yml 缺省 **`0`**（审查 B1 后由 PLAN §4.3 的 `1` 改回；与 Java 记录缺省一致）。SIT 联调在 env 显式设 `1` | Q2 若裁决接受 `1`，改 yml 缺省并在 21 号清单写明理由 |
| Q3 迁移形式 | 不建 Flyway，只有 docs SQL75 / SQL75-Demo | — |
| Q4 payer 身份来源 | **方案 A**：`PaymentIdentityApi` 增加无门店重载（改 USER 两个文件） | 删除重载；接口默认实现已是 503 |
| Q5 支付回调 HTTP | 不开放；`PaymentCallbackRouter` 只作进程内入口 | — |
| Q6 SIT 触发 DEMO | 只提供 `DemoPaymentService`，**未写** opt-in 手工联调测试（见 §6 风险 7） | — |
| Q7 新事件 | 新增 `PaymentCenterSucceededEvent.v1`，已登记 08 号事件目录并标"待审批" | — |
| Q8 `order_id` 可空 | 按 SQL75（WP-B 已加入测试夹具） | — |
| Q9 DEMO 上限/期限 | 0.01 元 / 10 分钟；上限可配 `pet.payment.center.demo.max-amount` | — |
| Q10 out_org_code | 沿用 `LAKALA_OUT_ORG_CODE`，空值时 dispatch 启动失败（现有行为） | — |
| Q11 LAKALA_ENV | 模板注释改为 `SIT 或 PRODUCTION`（WP-B）；yml 不再缺省 `SIT`（审查 S2），空值 dispatch 启动失败 | — |

## 3. 全部改动文件

### 3.1 WP-A 渠道协议层（实施记录见 impl_channel 报告）

| 文件 | 改动 |
|---|---|
| `backend/pet-payment-biz/.../infrastructure/provider/LakalaProtocol.java` | `PreorderInput` 末位加 `settleType`（保留 11/12 参构造）；`settleType="1"` 时在 `notify_url` 后上送 `"settle_type":"1"`，null/"0" 不上送（报文字节不变），其他值抛 `ProtocolException`；`NotificationReceipt`/`QueryResult` 末尾加 `logNo`/`logDate`（保留 8 参构造）；新增私有 `collectTrace`，格式不符或只有一半时两者都为 null，不影响支付判定 |
| `backend/pet-payment-biz/.../application/PaymentReceiptVerifier.java` | `VerifiedNotice` 加 `logNo`/`logDate`，保留 8 参构造 |
| `backend/pet-payment-biz/src/test/.../provider/LakalaCollectFieldsTest.java`（新） | 11 个离线用例 |

### 3.2 WP-B 存储、配置与 ORDER 接线（实施记录见 impl_storage 报告）

| 文件 | 改动 |
|---|---|
| `.../persistence/PaymentFoundationStore.java` | `Row` 末尾加 `bizType,bizNo,settleType,logNo,logDate` |
| `.../resources/mapper/PaymentFoundationMapper.xml` | 列清单末尾加 5 列；`insertPayment` 写 `settle_type`；`markPaid` 写 log；新增 `fillCollectTrace`（只在 PAID 且两列为空时补写一次） |
| `.../persistence/mapper/PaymentFoundationMapper.java` | 新增 `fillCollectTrace` |
| `.../application/PaymentMerchantBindings.java` | 新增 `default requirePlatform()`，默认 503 |
| `.../apiimpl/PaymentPreparationApiImpl.java` | 新增带 `settleType` 的构造（只接受 0/1），旧构造等价 "0" |
| `.../application/PaymentDispatchService.java` | 预下单只按支付行的 settle_type 上送 |
| `.../application/PaymentNotificationService.java` | 查单结果带 log 字段；PAID 时写 log；已 PAID 的一致 SUCCESS 回执补写一次 |
| `backend/pet-boot/.../config/PaymentFoundationConfiguration.java` | `LakalaSettings` 加 `platform`、`settleType`；平台模式与 stores 互斥、半填启动失败；验签 lambda 传 log 字段 |
| `backend/pet-boot/src/main/resources/application.yml` | 首次映射 `pet.payment.*`（全部 `${VAR:默认}`，开关缺省 false） |
| `ops/payment-center.env.example`（未跟踪，需一起入库） | `LAKALA_ENV` 注释、`LAKALA_SETTLE_TYPE`、`LAKALA_OUT_ORG_CODE` |
| `docs/08-engineering/21-部署开关组合清单-v1.0.md` | settle-type / platform.* 行及三条启动失败 |
| 测试夹具 6 个：`BookingCreateAcceptanceTest`、`SelectionExpiryAcceptanceTest`、`OrderQueryHttpTest`、`AdminFacesFixRegressionHttpTest`、`E2eCoreLoopServer`、`PaymentRefundServiceMySqlTest` | schema 清单末尾追加 SQL75 |
| `pet-boot/.../config/PaymentFoundationConfigurationTest.java`（新）、`PaymentApplicationYamlDefaultsTest.java`（新） | 16 + 5 个离线用例（重做时 +1：空 `environment` 绑定为 null 且渠道 bean 失败关闭；yml 缺省断言改为 `settle-type=0`、`environment` 无缺省） |
| `backend/pet-boot/src/main/resources/application.yml`、`ops/payment-center.env.example`（重做） | `settle-type` 缺省 `0`；`environment: ${LAKALA_ENV:}`；env 模板 `LAKALA_SETTLE_TYPE=0` |
| `pet-payment-biz/.../application/PaymentCollectFieldsMySqlTest.java`（新） | 2 个离线用例 + 11 个 MySQL 用例（未运行） |

### 3.3 WP-C 支付中心契约与 DEMO 闭环（本次）

**API（pet-payment-api / pet-event-api / pet-user-api）**

| 文件 | 内容 |
|---|---|
| `backend/pet-payment-api/.../command/PaymentCenterApi.java`（新） | `create` / `query`，签名同 75 号 §4.1 |
| `backend/pet-payment-api/.../dto/PaymentCenterTypes.java`（新） | `CreateCenterPaymentCommand`、`CenterPayment`（toString 脱敏）、`QueryCenterPayment`、`CenterPaymentView`、`CenterPaymentSuccessFact` |
| `backend/pet-payment-api/.../query/PaymentCenterFactsApi.java`（新） | `requireSucceeded(bizType, bizNo, context)` |
| `backend/pet-event-api/.../PaymentCenterSucceededPayload.java`（新） | 75 号 §6 payload |
| `backend/pet-user-api/.../query/PaymentIdentityApi.java` | 新增无门店重载 `requireCurrentPaymentIdentity(userId, appId, context)`，接口默认实现 503 |

**实现（pet-payment-biz，不依赖任何 *-biz）**

| 文件 | 内容 |
|---|---|
| `.../biz/center/PaymentCenterService.java`（新） | `PaymentCenterApi` 实现。T1 requestId 绑定（`payment_center_request`，同键异参 `IDEMPOTENCY_KEY_CONFLICT`）→ T2 `(biz_type,biz_no)` 行锁取或建支付行（平台商户、order/store/merchant 为 NULL；并发插入撞 `uk_payment_biz` 时重读一次）→ T3 派发栅栏：锁支付行与派发行，读 USER 身份，预校验构包，`MAY_HAVE_SENT` 与 `PAYCENTER_CHANNEL_QUERY:{paymentId}` 任务**同事务**登记 → 事务外预下单 → 失败置 UNKNOWN 返回 503、不重发 → T4 验签结果 AES-GCM 加密保存后返回参数。SQLState 08 只用原绑定重读一次；1205/1213 → `COMMON_CONFLICT` |
| `.../biz/center/PaymentCenterReceiptWriter.java`（新） | 支付中心行的通知（`receiveNotification`，公开）与查单（`receiveQuery`，包内）共用写入器：锁支付行 → 校验是支付中心行、平台商户号、CNY、`LAKALA_WECHAT` → 回执摘要去重 → SUCCESS 置 PAID（含 log）并同事务发布 `PaymentCenterSucceededEvent.v1`；FAIL→FAILED；CLOSE→CLOSED；其他→PAYING；退款/撤销类→`RECONCILIATION_REQUIRED`；已 PAID 的一致回执补写 log 一次。复用 `PaymentFoundationMapper` 的状态迁移语句 |
| `.../biz/center/PaymentCenterQueryCoordinator.java`（新） | 一次查单尝试：只用原 paymentNo，事务外调用渠道，结果交给写入器；终态 DONE，非终态 PENDING，超过 expireAt+5min EXHAUSTED |
| `.../biz/center/PaymentCenterFactsApiImpl.java`（新） | 要求调用方在同一 DataSource 事务内；锁行并核对 PAID/OBSERVED/成功回执，否则 503 |
| `.../biz/center/PaymentCallbackRouter.java`（新） | 按行类型把原始通知分给支付中心写入器或现有 ORDER `PaymentNotificationService.receive`；不注册 HTTP |
| `.../biz/center/PaymentCenterBizPolicy.java`（新） | bizType → 金额上限 + 最长期限；阶段 1 只在 DEMO 开关开启时注册 DEMO |
| `.../biz/center/CenterParameterCipher.java`（新） | AES-GCM，AAD `PAYMENT_CENTER_PARAMETERS_V1:{id}:{no}`；身份 HMAC 用独立派生域。与 ORDER 私有实现重复约 40 行，这是有意的（阶段 1 不改 ORDER 文件） |
| `.../biz/center/demo/DemoPaymentService.java`（新） | DEMO 下单：`payment.demo.start/USER/{op}/{requestId}` 幂等，`expireAt = created_at + 10min`，调用 `PaymentCenterApi.create` |
| `.../biz/center/demo/DemoPaymentConfirmConsumer.java`（新） | 消费者 `payment-demo-confirm`；严格解码 payload；在消费日志事务内调用 `requireSucceeded` 后把 DEMO 单 CAS 为 PAID 或 AMOUNT_MISMATCH；非 DEMO 类型只记录消费 |
| `.../biz/task/PaymentCenterQueryTask.java`（新） | 任务键/payload/退避（5s/15s/30s/1m/2m/5m）/结果映射/严格 decode/`TaskRegistration` 工厂 |
| `.../infrastructure/persistence/PaymentCenterStore.java`、`PaymentDemoStore.java`（新） | `CenterRow`（owner 列为可空包装类型）、`Route`、`RequestBinding`、`DemoRow` |
| `.../persistence/mapper/PaymentCenterMapper.java`、`PaymentDemoMapper.java`（新） | Mapper 接口，无注解 SQL |
| `backend/pet-payment-biz/src/main/resources/mapper/PaymentCenterMapper.xml`、`PaymentDemoMapper.xml`（新） | 全部 SQL；不含任何 `UPDATE payment_order` |

**USER 实现**

| 文件 | 内容 |
|---|---|
| `backend/pet-user-biz/.../apiimpl/PaymentIdentityApiImpl.java` | 覆盖无门店重载：同样的账号/身份校验，改为要求调用方事务；校验主体抽成私有 `lockedIdentity`，门店版行为不变 |

**boot 装配**

| 文件 | 内容 |
|---|---|
| `backend/pet-boot/.../config/PaymentCenterConfiguration.java`（新） | guard（`InitializingBean`）+ 嵌套 `Runtime`（需 center && foundation && dispatch）。Bean：`PaymentCenterApi`、`PaymentCenterFactsApi`、`PaymentCenterReceiptWriter`、`PaymentCallbackRouter`；worker 开关下的 `PaymentCenterQueryCoordinator` + `AsyncTaskWorker`；DEMO 开关下的 `DemoPaymentService` + `DemoPaymentConfirmConsumer`。无 Controller |
| `backend/pet-boot/src/main/resources/application.yml` | 追加 `pet.payment.center.{enabled,worker.enabled,demo.enabled,demo.max-amount}` |
| `ops/payment-center.env.example` | 追加 `PAYMENT_CENTER_ENABLED/WORKER_ENABLED/DEMO_ENABLED=false`、`PAYMENT_CENTER_DEMO_MAX_AMOUNT=0.01` |

**测试（新）**

| 文件 | 类型 |
|---|---|
| `pet-payment-biz/src/test/.../center/PaymentCenterBizPolicyTest.java` | 离线，5 |
| `pet-payment-biz/src/test/.../center/CenterParameterCipherTest.java` | 离线，6 |
| `pet-payment-biz/src/test/.../center/PaymentCenterGuardsTest.java` | 离线，8 |
| `pet-payment-biz/src/test/.../center/PaymentCenterMapperAlignmentTest.java` | 离线，3 |
| `pet-payment-biz/src/test/.../center/demo/DemoPaymentConfirmConsumerTest.java` | 离线，4 |
| `pet-payment-biz/src/test/.../task/PaymentCenterQueryTaskTest.java` | 离线，5 |
| `pet-payment-biz/src/test/.../center/PaymentCenterMySqlTest.java` | MySQL，19，**未运行** |
| `pet-user-biz/src/test/.../PaymentIdentityStorelessGuardTest.java` | 离线，2 |
| `pet-user-biz/src/test/.../PaymentIdentityApiMySqlTest.java`（追加 1 个方法） | MySQL，**未运行** |
| `pet-boot/src/test/.../config/PaymentCenterConfigurationTest.java` | 离线（ApplicationContextRunner + mock），9 |

**文档**

| 文件 | 改动 |
|---|---|
| `docs/04-api/75-Payment-Center-Contract-v0.1.md` | 追加 §10"实现补充"（9 条，见 §4.1），不改上文规则 |
| `docs/05-events/08-Integration-Event-Catalog-v0.6.md` | §2 表加一行；末尾新增 `PaymentCenterSucceededEvent.v1` 小节，标"待审批" |
| `docs/08-engineering/21-部署开关组合清单-v1.0.md` | §1.6 加三行 center 开关；§2.1 加七条启动失败；变更记录一条 |
| `planning/progress/2026-11-04/pay-center-phase1/IMPLEMENTATION.md`（本文件） | — |

## 4. 契约 / Schema / 事件 / 开关变化

### 4.1 契约

- 新增 75 号契约中的 `PaymentCenterApi`、`PaymentCenterFactsApi` 及 DTO，签名与 §4 一致。
- **超出 75 号原文、已写入其 §10 的实现细节**：USER 无门店身份重载（Q4-A）；业务对象已 PAID/FAILED/CLOSED 时 `create` 返回 `COMMON_CONFLICT`；`replayed` 语义；查询任务结果码；DEMO 非 SIT 一律启动失败（比原文严格）；DEMO 截止时间由 `created_at` 推导；AAD/HMAC 域；回调路由规则；payload 序列化格式。
- `PaymentIdentityApi` 新增方法属于 USER 跨模块接口变化，随 75 号一并待审核。
- **错误码**：没有新增 API 错误码，没有修改 12 号登记。使用的都是既有码：`IDEMPOTENCY_KEY_CONFLICT`、`COMMON_CONFLICT`、`COMMON_INVALID_ARGUMENT`、`COMMON_FORBIDDEN`、`COMMON_NOT_FOUND`、`COMMON_DEPENDENCY_UNAVAILABLE`。`PAYMENT_QUERY_EXHAUSTED` 等只是 `async_task` 内部结果码，与现有 `REFUND_QUERY_PENDING` 同类，不进 12 号登记。

### 4.2 Schema

- WP-C 没有修改 SQL75 / SQL75-Demo（由 PLAN 阶段写好）。没有新增 Flyway 文件。
- WP-C 依赖的既有列：`async_task.submitted_execute_at`（SQL39，`JdbcAsyncTaskSubmitter.enqueueAt` 本来就需要）。`PaymentCenterMySqlTest` 只单独执行了 SQL39 中这一条 ALTER，因为 SQL39 其余语句改的是本夹具不建的排期表。

### 4.3 事件

- 新增 `PaymentCenterSucceededEvent.v1`（aggregateType=PAYMENT），已登记 08 号目录，**待审批**。不复用 `PaymentSucceededEvent.v1`。
- 新增消费者 `payment-demo-confirm`（仅 DEMO 开关开启时装配）。

### 4.4 开关（全部缺省 false，不注册任何 HTTP）

| 开关 | 硬依赖 / 启动失败 |
|---|---|
| `pet.payment.center.enabled` | foundation + dispatch；平台商户；可读的 32 字节参数密钥 + 渠道时区 |
| `pet.payment.center.worker.enabled` | center.enabled |
| `pet.payment.center.demo.enabled` / `demo.max-amount`（0.01） | center.enabled；outbox；`pet.payment.dispatch.environment=SIT`；金额 >0 且 ≤2 位小数 |

## 5. 实际运行的命令与结果

环境：`export JAVA_HOME=$HOME/.local/opt/jdk-21 PATH=$HOME/.local/opt/jdk-21/bin:$PATH`，Maven 离线 `-o`，工作目录 `backend/`（Python 门禁在仓库根目录运行）。WP-A/WP-B 的命令与结果见各自报告，下面只列 WP-C 阶段在 A+B+C 全部改动都在工作树中时运行的命令。

| # | 命令 | 结果 |
|---|---|---|
| 1 | `mvn -o -q -pl pet-payment-biz -am compile` | exit 0 |
| 2 | `mvn -o -q -pl pet-boot -am test-compile` | exit 0 |
| 3 | `mvn -o -pl pet-payment-biz -am test -Dtest='PaymentCenter*Test,CenterParameterCipherTest,DemoPaymentConfirmConsumerTest' -Dsurefire.failIfNoSpecifiedTests=false`（第一次） | 28 个用例 1 个失败：重复键 JSON 通知返回 503 而测试期望 400。修正 `PaymentCenterReceiptWriter.hintNo`，把 JSON 解析错误映射为 `COMMON_INVALID_ARGUMENT` |
| 4 | 同上（加 `PaymentCenterMapperAlignmentTest`，排除 `PaymentCenterMySqlTest`） | BUILD SUCCESS，31 个用例 0 失败：CenterParameterCipherTest 6、DemoPaymentConfirmConsumerTest 4、PaymentCenterBizPolicyTest 5、PaymentCenterGuardsTest 8、PaymentCenterMapperAlignmentTest 3、PaymentCenterQueryTaskTest 5 |
| 5 | `mvn -o -pl pet-user-biz -am test -Dtest='PaymentIdentityStorelessGuardTest' -Dsurefire.failIfNoSpecifiedTests=false` | BUILD SUCCESS，2/0 |
| 6 | `mvn -o -pl pet-boot -am test -Dtest='PaymentCenterConfigurationTest,PaymentApplicationYamlDefaultsTest' -Dsurefire.failIfNoSpecifiedTests=false` | BUILD SUCCESS，PaymentCenterConfigurationTest 9/0，PaymentApplicationYamlDefaultsTest 5/0（yml 新变量与 env 模板一致） |
| 7 | `mvn -o -pl pet-payment-biz -am test -Dtest='Lakala*Test,RefundFundingEvidenceChecksTest,WechatOriginalRouteRefundFundingAuthorityTest,PaymentCollectFieldsMySqlTest#preparationRejectsAnUnknownSettleType+rowRecordAndMapperColumnListStayAlignedAtTheTail,PaymentCenterBizPolicyTest,PaymentCenterGuardsTest,PaymentCenterMapperAlignmentTest,CenterParameterCipherTest,PaymentCenterQueryTaskTest,DemoPaymentConfirmConsumerTest' -Dsurefire.failIfNoSpecifiedTests=false`（A+B+C 回归） | BUILD SUCCESS，74 个用例 0 失败：LakalaCollectFieldsTest 11、LakalaHttpClientQaTest 9、LakalaProtocolTest 6、LakalaRefundHttpClientTest 3、LakalaRefundProtocolTest 3、RefundFundingEvidenceChecksTest 5、WechatOriginalRouteRefundFundingAuthorityTest 4、PaymentCollectFieldsMySqlTest（2 个离线方法）2，加上 #4 的 31 个 |
| 8 | `mvn -o -pl pet-boot -am test -Dtest='PaymentCenterConfigurationTest,PaymentFoundationConfigurationTest,PaymentApplicationYamlDefaultsTest,PaymentDispatchConfigurationTest,LateRefundConfigurationTest,MerchantOrderConfigurationTest,RefundApplicationConfigurationTest,PaymentIdentityStorelessGuardTest' -Dsurefire.failIfNoSpecifiedTests=false`（boot 配置回归） | BUILD SUCCESS，0 失败：PaymentCenterConfigurationTest 9、PaymentFoundationConfigurationTest 15、PaymentApplicationYamlDefaultsTest 5、PaymentDispatchConfigurationTest 3、LateRefundConfigurationTest 4、MerchantOrderConfigurationTest 4、RefundApplicationConfigurationTest 34；上游 pet-user-biz 的 PaymentIdentityStorelessGuardTest 2 |
| 9 | `mvn -o -q test-compile`（整个 reactor） | exit 0。所有模块的测试代码（包括 19 个用例的 `PaymentCenterMySqlTest` 和追加方法后的 `PaymentIdentityApiMySqlTest`）都能编译 |
| 10 | `python3 backend/tools/check-persistence-style.py` | `PASS: production SQL uses MyBatis Mapper XML`，exit 0 |
| 11 | `python3 backend/tools/check-module-deps.py` | `ARCH-001 scanned 43 reactor modules, 18 biz POMs` / `PASS: no biz -> biz dependencies`，exit 0 |
| 12 | `python3 backend/tools/check-display-status.py` | `PASS: no duplicate display derivation patterns in existing sources`，exit 0 |
| 13 | `PATH=/tmp/pybin:$PATH mvn -o -pl pet-architecture-test -am test -Dtest=ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false` | BUILD SUCCESS，7/0。`/tmp/pybin/python` 是仓库外指向 python3 的符号链接，因为该测试调用 `python` 命令而本机只有 `python3`（既有环境依赖） |
| 14 | 对未跟踪与已修改文件 grep `BEGIN (RSA )?PRIVATE KEY`、`822[0-9]{12}`、`OP0000[0-9]{4}`、`SM4_KEY=<值>` | 只命中 `PaymentApplicationYamlDefaultsTest` 中 `doesNotContain("BEGIN PRIVATE KEY")` 断言字面量；没有私钥、SM4 或测试商户号 |

## 6. 未运行的测试及原因

本机没有 MySQL 和 Docker，以下测试都只编译、**未运行**，不得据此声称通过：

| 测试 | 覆盖内容 | 运行方式 |
|---|---|---|
| `pet-payment-biz/.../center/PaymentCenterMySqlTest`（19 个） | 支付中心行落库（order/store/merchant NULL、settle_type、平台商户）；参数与查询任务同时提交；settle_type=0 不上送；同 requestId 重放不二次预下单；同键异参 `IDEMPOTENCY_KEY_CONFLICT`（金额/单号/期限）；新 requestId 同业务对象重放、金额不同 `COMMON_CONFLICT`；期限窗口外不建行；任务登记失败时 MAY_HAVE_SENT 一并回滚、不发送；预下单未知 → UNKNOWN、只查不重发、用原 paymentNo；查单 SUCCESS → PAID + log + 恰好一个事件（payload 无商户号/openid/log）；通知 + 查单 + 重复通知只一个事件；通知 × 查单并发只一个事件；FAILED 后迟到 SUCCESS 记 PAID 一次；REFUND → 对账、后续 SUCCESS 无事件；PENDING → expireAt+5min 后 EXHAUSTED；路由器分流、ORDER 入口收到支付中心行号 503 且不写库；Facts 需事务且需成功；query 本人/SYSTEM 可见、他人 NOT_FOUND；DEMO 闭环 + 重复投递 NOOP + DEMO 同键异参；DEMO 实付不等 → AMOUNT_MISMATCH | `PAYMENT_CENTER_MYSQL_URL=jdbc:mysql://127.0.0.1:PORT/`（或 `PAYMENT_REFUND_MYSQL_URL`），可选 `_USER`/`_PASSWORD`；schema 06/13/40/41/75/75-Demo + SQL39 的一条 ALTER |
| `pet-user-biz/.../PaymentIdentityApiMySqlTest#storelessVariantRequiresTheCallerTransactionAndTheSameUserChecks` | 无门店重载：事务内取到身份；无事务 503；他人 FORBIDDEN；空 appId INVALID；appId 不匹配 503；FROZEN FORBIDDEN | 既有 `MySqlUserDomainTestDatabase` 夹具 |
| WP-B `PaymentCollectFieldsMySqlTest` 其余 11 个 | 见 WP-B 报告 | 同上 |
| ORDER 回归：pet-boot `PaymentFoundationAcceptanceTest`、`PaymentCoordinationAcceptanceTest`、`PayTimeoutCallbackConcurrencyMySqlTest` 及其他 `*MySqlTest`/`*AcceptanceTest` | ORDER 路径在 SQL75 下不变 | 需要 MySQL |
| SQL75 / SQL75-Demo 在真实 MySQL 8 上执行 | `MODIFY COLUMN order_id`、CHECK 约束、`uk_payment_biz` | 需要 MySQL |
| SIT 联调 | 真实预下单/查单/验签、log 字段名 | 需要 SIT 凭证与环境，Q6 未裁决 |

## 7. 遗留风险

1. **落库、锁和并发行为没有在真实数据库上验证过。** 支付中心的核心保证（MAY_HAVE_SENT 与查询任务同事务、只产生一个成功事件、幂等绑定）只有未运行的 `PaymentCenterMySqlTest` 覆盖。合并前必须在有 MySQL 的环境补跑本报告 §6 的全部测试。
2. **MyBatis 构造映射对 NULL 原始类型的失败关闭是推断。** ORDER 读路径（`PaymentFoundationStore.Row` 的 `long orderId/storeId/merchantId`）遇到支付中心行时，预期 MyBatis 构造实例失败 → 503；即使映射成功，`validRow` 的 `storeId>0` 检查也会拒绝。`PaymentCenterMySqlTest#routerSends…` 验证这一点，未运行。
3. **Q2 仍未裁决。** yml 缺省已改为 `0`（不上送 settle_type，资金常规结算）。SIT 联调分账标识时须显式设 `LAKALA_SETTLE_TYPE=1`；阶段 2 前生产不得设 `1`。`settle_type` 在 prepare 时写入行，之后改配置不影响已建行。
4. **Q1：log 字段名待 SIT 核实。** 名称不对时 log 两列全为 NULL，不影响收款；阶段 2 分账遇 NULL 须失败关闭。
5. **新事件与 USER 接口变化待审批。** `PaymentCenterSucceededEvent.v1`（Q7）和 `PaymentIdentityApi` 无门店重载（Q4）都需要技术契约审核。若 Q4 改选 B，需要换掉 boot 中注入的身份来源。
6. **没有支付回调 HTTP（Q5）。** 阶段 1 支付中心只靠查询任务确认，最早 5 秒。查询任务把 FAIL 视为终态后停止，之后的迟到 SUCCESS 只能经通知进入；没有回调 HTTP 时，这种迟到成功要等阶段 6 补偿扫描才会被发现。
7. **SIT 触发方式未实现（Q6）。** PLAN 推荐的 opt-in 手工联调测试 `PaymentCenterDemoSitLiveTest` 没有写：它需要真实凭证、SIT 数据库和 USER 身份数据，本机都没有，且 Q6 未裁决。DEMO 目前只能由进程内代码调用。
8. **查询任务 DEAD 后无人接手。** `PAYMENT_QUERY_EXHAUSTED` / `PAYMENT_RECONCILIATION_REQUIRED` 留给阶段 6 补偿扫描/人工处理，阶段 1 没有告警。
9. **平台商户号变更会让旧支付中心行失败关闭。** 写入器要求行上的 `merchant_no` 等于当前配置的平台商户号。
10. **参数加解密重复实现约 40 行**（`CenterParameterCipher` 与 `PaymentDispatchService` 私有方法），计划在阶段 8 合并。
11a. **部署顺序：先执行 SQL75 再上线代码（审查 S1）。** `PaymentFoundationMapper.xml` 的 `paymentColumns` 含 SQL75 新增 5 列，ORDER 支付的所有读写都依赖它们；未执行 SQL75 时 ORDER 支付查询报未知列。已写入 75 号契约 §8 和 21 号清单变更记录。
11. **`async_task.submitted_execute_at` 依赖 SQL39。** 生产整合迁移时必须包含该列，否则查询任务登记失败，进而导致 create 503（失败关闭，不会发送）。
12. **仓库卫生（非本任务产生）：** 根目录 5 个拉卡拉 PDF 未被 git 忽略，容易误提交，建议移到 `planning/local/lakala/`；`.gitignore` 的修改不是 WP-A/B/C 做的。`ops/payment-center.env.example` 目前未跟踪，`PaymentApplicationYamlDefaultsTest` 会读它，提交时必须一起入库。

13. **非终态回执会把 FAILED/CLOSED 改回 PAYING（审查 S3，未修改）。** 见 §8 S3。

## 8. 第 1 轮审查意见逐条回应

| 编号 | 意见 | 处理 | 证据 |
|---|---|---|---|
| B1（阻断） | ORDER 路径 `settle_type` yml 缺省 `1`，与"生产不得使用 1"矛盾 | **已修复**，采用审查给出的第一种方案：`application.yml` 改为 `${LAKALA_SETTLE_TYPE:0}`；`ops/payment-center.env.example` 改为 `LAKALA_SETTLE_TYPE=0` 并注明 SIT 显式设 1；75 号契约 §8 缺省改为 `0`；21 号清单 1.6 行改为 `0` 并加变更记录；`PaymentApplicationYamlDefaultsTest` 断言改为 `${LAKALA_SETTLE_TYPE:0}`。支付中心 `PaymentCenterConfiguration` 读同一配置，因此支付中心行缺省也是 `0`；DEMO SIT 联调需要分账标识时显式设 1 | §5 #15：`PaymentApplicationYamlDefaultsTest` 5/0 |
| S1 | ORDER 读路径依赖 SQL75 列，需写入部署顺序 | **已采纳**：75 号契约 §8 增"部署顺序"段；21 号清单变更记录注明；本报告 §7 第 11a 条 | 文档改动 |
| S2 | `LAKALA_ENV` 缺省 SIT，生产忘配会静默连 SIT | **已修复**：yml 改为 `${LAKALA_ENV:}`，恢复 PLAN §4.3 注记的现有行为（空值 dispatch 启动失败）。新增 `PaymentFoundationConfigurationTest#emptyDispatchEnvironmentBindsToNullAndChannelFailsClosed`：空串绑定为 null，`paymentChannel` 抛 `IllegalStateException("Payment dispatch credentials or launch configuration unavailable")`。DEMO 门禁本来就要求 `SIT`（空值启动失败），不受影响。env 模板保留 `LAKALA_ENV=SIT` 作填写示例。21 号清单 dispatch 行注明 | §5 #15：`PaymentFoundationConfigurationTest` 16/0 |
| S3 | 非终态回执会把 FAILED/CLOSED 改回 PAYING | **未修改**，理由：与 ORDER `PaymentNotificationService` 现有写法一致；审查已确认资金无风险（`fence` 回到 PAYING 只查不重发）。若只对支付中心加 status 条件，会让 FAILED 行在渠道仍报 DEAL 时停止跟踪，改变查询任务的终止语义，需要和 ORDER 一起在状态机层面决定，不在本阶段收窄范围内。列为遗留风险 §7 第 13 条 | — |
| S4 | `log_no` 接受 1～32 位，文档写 String 14 | **未收紧**，理由：PLAN 指定 32；1111/1112 本地无文档，收紧到 14 有把真实值误判为 NULL 的风险，而放宽只影响长度上限、仍要求字母数字且两字段成对。SIT 首笔报文核实后再收紧（Q1） | — |
| S5 | 新事件、USER 无门店重载、75 号契约待审批 | **已披露**，无代码改动；合并前须技术契约审核（@blueXYing） | §2 Q4/Q7、§7 第 5 条 |
| S6 | 根目录 5 个 PDF 未忽略；env 模板未跟踪 | **未处理文件本身**：PDF 不是本任务产生，移动/删除属于需要负责人确认的操作，未动；env 模板提交时必须一起入库（`PaymentApplicationYamlDefaultsTest` 依赖） | §7 第 12 条 |

### 重做后实际运行的命令（JDK 21，`mvn -o`，2026-11-04）

| # | 命令 | 结果 |
|---|---|---|
| 15 | `cd backend && mvn -o -pl pet-boot -am test -Dtest='PaymentCollectFieldsMySqlTest#preparationRejectsAnUnknownSettleType+rowRecordAndMapperColumnListStayAlignedAtTheTail,PaymentIdentityStorelessGuardTest,PaymentCenterConfigurationTest,PaymentFoundationConfigurationTest,PaymentApplicationYamlDefaultsTest,PaymentDispatchConfigurationTest,LateRefundConfigurationTest,MerchantOrderConfigurationTest,RefundApplicationConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false` | BUILD SUCCESS。user-biz PaymentIdentityStorelessGuardTest 2/0；payment-biz PaymentCollectFieldsMySqlTest（2 个离线方法）2/0；boot 75/0：PaymentDispatchConfigurationTest 3、LateRefundConfigurationTest 4、MerchantOrderConfigurationTest 4、PaymentApplicationYamlDefaultsTest 5、PaymentCenterConfigurationTest 9、PaymentFoundationConfigurationTest 16、RefundApplicationConfigurationTest 34 |
| 16 | `cd backend && mvn -o -pl pet-payment-biz -am test -Dtest='com/petplatform/payment/**/*Test,!*MySql*' -Dsurefire.failIfNoSpecifiedTests=false` | BUILD SUCCESS，72/0 |
| 17 | `PATH=/tmp/pybin:$PATH mvn -o -pl pet-architecture-test -am test -Dtest=ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false` | BUILD SUCCESS，7/0 |
| 18 | `python3 backend/tools/check-persistence-style.py` | `PASS: production SQL uses MyBatis Mapper XML`，rc=0 |
| 19 | `python3 backend/tools/check-module-deps.py` | `ARCH-001 scanned 43 reactor modules, 18 biz POMs` / `PASS: no biz -> biz dependencies`，rc=0 |

重做未运行：所有 `*MySqlTest`、`*AcceptanceTest`、SQL75 实库执行、SIT 联调，原因同 §6（本机无 MySQL/Docker、无 SIT 凭证）。

## 9. 用户裁决（2026-11-05）

| 项 | 裁决 |
|---|---|
| Q2 `settle_type` 缺省 | 同意缺省 0；SIT 分账联调时 env 显式设 1；阶段 2 分账上线时统一切换 |
| SQL75 `payment_order.order_id` 放宽为可空 | 同意 |
| Q5 阶段 1 不开放拉卡拉支付回调 HTTP | 同意，阶段 1 只靠主动查单确认 |
| S-b 查单任务类型 | 同意改名：`PAYMENT_CHANNEL_QUERY` → `PAYCENTER_CHANNEL_QUERY`（原名在 09 号调度文档中已预留给 ORDER 路径）。已修改代码、测试、75 号契约、21 号清单；改后 `pet-payment-biz` 离线单测 74/0，全工程 test-compile rc=0 |
| S-f 技术契约审核（新事件 `PaymentCenterSucceededEvent.v1`、USER `PaymentIdentityApi` 无门店重载、75 号契约） | 2026-11-05 @blueXYing 批准 |
| 支付联调切片（小程序调起 `wx.requestPayment`、订单路径预下单后主动查单） | 暂不做：小程序微信支付尚未开通（用户 2026-11-05） |
