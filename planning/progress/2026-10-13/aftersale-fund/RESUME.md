# 售后资金闭环切片（32号裁决）进度

分支 `codex/aftersale-fund-20261013`（基 develop 6263f17，含32号裁决文档）。工作树 `C:/Users/Administrator/Desktop/wt-aftersale-fund`。

## 已完成（待最终验证）

1. **生产资金资格适配** `backend/pet-payment-biz/.../application/WechatOriginalRouteRefundFundingAuthority.java`：实现 `RefundFundingEligibilityFactsApi`，原路退回语义——调用方事务内读 PAYMENT 权威成功事实全字段比对，一致即 ALLOWED/UNSETTLED/授权金额=请求金额；evidenceId=`WECHAT_ORIGINAL_ROUTE:<hash>`；FIRST_SEND 只扩展决定阶段同源证据；证据窗口 24h；DB 时钟（PaymentFoundationStore.now）。
2. **装配** `LateRefundConfiguration` 新增 `wechatOriginalRouteRefundFunding` @Bean（`pet.aftersale.refund.enabled=true` 条件）。
3. **控制器** `AfterSaleController.decide` 删除 503 fail-closed 分支；退款型要求 refundAmount 非空、非退款型要求 null；金额界由内核（FULL=实付、PARTIAL 0<金额<实付，`AFTERSALE_AMOUNT_INVALID` 400）。
4. **契约**：51号契约 §1/§2/§3/§6 修订；Error12 503 行修订；OpenAPI decideAftersale/AfterSaleDecisionRequest/AfterSaleReceipt.refundOrderId 注记（x-public-refund-enabled: true + switch 扩展）；e2e/contract_smoke.py decideAftersale pin 更新；新 CCR `planning/ccr/CCR-AFS-FUNDING-001.md`。
5. **admin web**：`AfterSaleDetailPage` 五类终局 + 退款金额输入（两位小数客户端预检）+ 原路退回执行状态提示 + 回执退款单号；`api/aftersales.ts` DecisionType/checkIntent 放行退款型。playwright aftersale.spec.ts 39/39 通过（含新增 PARTIAL 金额路径、金额非字符串拒绝）。
6. **测试**：`WechatOriginalRouteRefundFundingAuthorityTest`（payment-biz，通过）；`AfterSaleHttpAcceptanceTest` 新增 refundDecisionsAdjudicateOriginalRouteRefundOrdersUnderTheRuling32Switch（refund.enabled=true 真实 provider 全链）+ 既有 503 用例改为"开关关闭→内核 503，绑定+2"。

## 关键决策记录

- 内核 decide/退款建单/任务族链路本就完整（31号 A1-A3 已交付），本切片缺口=生产 RefundFundingEligibilityFactsApi + HTTP fail-closed + 契约/页面。
- 证据 24h 窗口为技术护栏（覆盖任务重试节奏），非产品规则；CCR 已披露。
- FULL 必须提交与实付一致的金额（内核强校验），admin 页文案已说明。

## 进行中（第2棒 2026-10-13 16:15 接力）

前次后台验收双双失败，根因已诊断（非代码问题）：

1. **boot 3 个 ERROR（ZipException: invalid LOC header）= 本地仓库竞态**：前次将 pet-boot 测试与 pet-payment-biz 构建并发跑，15:33:30 一个 install 重写 `D:/maven_repository/com/petplatform/pet-user-api/0.1.0-SNAPSHOT/...jar` 时，boot 测试 JVM 正在读取同一 jar。现磁盘上 42 个 petplatform jar 全部通过 zipfile 校验——损坏是瞬态的，jar 现在完好。教训：**共享 D:/maven_repository 的 Maven 命令必须串行**。
2. **payment-biz 19 ERROR（IllegalArgument: dedicated local MySQL URL required）= 缺环境变量**：`PaymentRefundServiceMySqlTest` 要求 `PAYMENT_REFUND_MYSQL_URL`（必须匹配 `jdbc:mysql://(127.0.0.1|localhost):\d+/`，结尾斜杠无库名）+ `PAYMENT_REFUND_MYSQL_USER=root` + 空 `PAYMENT_REFUND_MYSQL_PASSWORD`。前次未设。
3. **关键缺口**：repo 的 `pet-payment-biz-0.1.0-SNAPSHOT.jar` 是 2026-10-10 旧版，**不含新类 WechatOriginalRouteRefundFundingAuthority**；boot 用 `-pl pet-boot` 单模块跑，运行时从 repo 解析 payment-biz，新测试（refund.enabled=true 建 funding bean）会 NoClassDefFound。必须先 `mvn install -pl pet-payment-biz -DskipTests` 再跑 boot。
4. 17:00 前状态：新测试本身已绿（WechatOriginalRouteRefundFundingAuthorityTest 4/4、RefundFundingEvidenceChecksTest 5/5）；boot 侧 AfterSaleEvidenceHttpTest 8/8、Options 4/4、EvidenceAcceptance 3/3；MySQL 33440 / Redis 16379 端口通。
5. 注意：tasklist 里的 java.exe PID 18108 属于别的项目（D:\xinzitong-delivery），不是我们的残留，勿动。

接力计划（串行）：
- [x] 诊断并更新本文件
- [x] `mvn install -pl pet-payment-biz -DskipTests`（16:17 完成，repo jar 含新 funding 类）
- [x] payment-biz 定向测试（带 MySQL env）：28/28 全绿（PaymentRefundServiceMySqlTest 19 + RefundFundingEvidenceChecks 5 + WechatOriginalRouteRefundFundingAuthority 4）
- [x] boot 定向测试第 2 次尝试发现还需 env：`BOOKING_MYSQL_URL/USER/PASSWORD`（或 AUTH_*）+ `AUTH_REDIS_HOST/PORT`（前次 shell 有、我的没有）
- [ ] boot 定向测试最终运行（私有 repo，见下）

## 第2棒新发现（17:25 记录）

1. **新测试一处断言口径错误已修**（AfterSaleHttpAcceptanceTest:148）：三个金额边界尝试中，FULL 127.99 / PARTIAL 128.00 先 admit 后被内核拒（保留绑定），而 PARTIAL 0.00 在内核 `AfterSaleService.decide` 第156行 signum<=0 预检（admit 之前）就被拒——不落 aftersale_command。期望应为 commands+2 而非 +3。产品代码无问题。
2. **共享 D:/maven_repository 有并行 worker 在写**（其他工作树的 mvn install，17:07-17:08 重写了 pet-points-biz/pet-user-api/pet-user-biz jar）：我们的 surefire JVM 读 jar 时撞上重写 → 瞬态 ZipException → LateRefundAcceptanceTest 14 ERROR、RefundApplicationConfigurationTest 10F/6E、RefundApplication*/RefundApply* 全灭（第3次运行 192 tests 24F/54E，全为该原因）。**且并行 worker 的 jar 来自其他分支，会污染我们的测试对象**。
3. **对策：私有本地仓库** `D:/maven_repo_aftersale_fund`（cp 自共享 repo 后已用 zipfile 全量校验 0 坏），再从本工作树全量 `mvn install -DskipTests -Dmaven.repo.local=D:/maven_repo_aftersale_fund` 覆盖所有 petplatform 模块（保证内容=6263f17+本切片）。最终 boot 测试加 `-Dmaven.repo.local=D:/maven_repo_aftersale_fund` 跑，彻底隔离并行写。
4. run3 中非污染部分全绿：Evidence 8+3、Options 4、Money 12、OptionsConflict 7、PrivateEvidence 4、RefundRecovery 2、SourceIntegrity 2、Supplement 4、Workflow 21；AfterSaleHttpAcceptanceTest 仅上述断言口径一处（其余 4/5 通过，含 switch-off 用例）。
5. e2e/contract_smoke.py 为 OpenAPI 静态 pin（decideAftersale x-public-refund-enabled=true 等），不依赖后端进程，改动即覆盖。

## 下一步（沿用）

- 测试全绿后 commit + push 分支 + 开 PR（标题：`feat(aftersale): 售后资金闭环——FULL/PARTIAL 裁决按原路退回执行（32号裁决）`），body 含 AC 对照/测试证据/契约变化（CCR-AFS-FUNDING-001）/遗留风险。
- 不合并、不 push develop/main。

## 第3棒 2026-10-13 22:15 接力

1. **接手即验证**：私有仓库 `D:/maven_repo_aftersale_fund` 41 个 petplatform jar 全部 17:21 从本工作树装好（含 WechatOriginalRouteRefundFundingAuthority，unzip 确认）；MySQL 33440/Redis 16379 通。
2. **离线验证已过**：`python e2e/contract_smoke.py` → PASS_OFFLINE_DOCUMENT_SMOKE（decideAftersale pin 含 x-public-refund-enabled=true）；`python backend/tools/check-persistence-style.py` → PASS（新适配器仅经既有 PaymentFoundationStore→MyBatis 读 DB 时钟，无新增 JdbcTemplate）；`frontend-admin npx playwright test tests/aftersale.spec.ts` → **39/39 通过**。
3. **boot 套件 run4（私有仓库，BOOKING_MYSQL/AUTH_REDIS env 齐）**：除新测试外 14 类全绿——Evidence 3、Money 12、OptionsConflict 7、PrivateEvidence 4、RefundRecovery 2、SourceIntegrity 2、Supplement 4、Workflow 21、LateRefund 17、MerchantRefund 6、RefundApplication 30、RefundApply 4、LateRefundConfiguration 4、RefundApplicationConfiguration 34 = **150/150**。
4. **新测试两处纯夹具问题已修**（产品代码无恙）：
   - 第2个 verifiedOrder() 报 `completed order has future active claim`：继承 foundation 的 booking 链用 systemUTC 时钟，第一单 COMPLETED 后 claim 到 2030-01-01T10:30 仍"未来"。→ 镜像既有 `AfterSaleFixture.secondVerifiedOrder()` 给 `AfterSaleHttpFixture` 增同名方法（扩 710500 窗口至 13:00、at(MAX(reservation end)+1s)、受控时钟重组 booking 链、真实支付通知+确认+核销）；新测试第177行改用它。
   - 编译错：ReservationHoldApiImpl 在 `schedule.biz.apiimpl`（非 order）、`Instant.isBefore` 需 `start.toInstant()`。
   - 时钟跳到 11:00 后构造期签发的三端会话过期（credentials.read 401"验证失败"）→ 跳变后 `loginBuyer();loginOwner();loginAdmin();` 重建（AfterSaleFixture.refreshBuyer 同因）。
5. **run7 复跑：AfterSaleHttpAcceptanceTest 5/5 全绿 BUILD SUCCESS**（含新测试 refundDecisionsAdjudicateOriginalRouteRefundOrdersUnderTheRuling32Switch：PARTIAL 建单/边界400×3/幂等重放200/换参409/终局后409/资格面 REFUND_ORDER_ALREADY_EXISTS/FULL 兄弟单 + 既有开关关闭503用例）。
6. payment-biz 定向复跑见 payment-run2.log：**28/28 全绿 BUILD SUCCESS**（PaymentRefundServiceMySqlTest 19 + RefundFundingEvidenceChecks 5 + WechatOriginalRouteRefundFundingAuthority 4）。

## 交付完成（第3棒 2026-10-13 23:55）

- commit `5ef2a9a`（15 文件，+494/-36），已 push `codex/aftersale-fund-20261013`。
- **PR #160：https://github.com/blueXYing/pet-platform/pull/160**（base develop，未合并——按红线不合并不 push develop/main）。
- 测试证据汇总：payment-biz 28/28；boot 定向 155/155（14 类 150 + Http 5）；admin web playwright 39/39；contract_smoke PASS；check-persistence-style PASS。
- 遗留风险（PR body 已披露）：生产开关默认关闭，启用需渠道凭据+worker；证据 24h 窗口为技术护栏；任务族执行态不在本验收内。

## 坑

- fixture 时间源是 DataSource 包装（`SET timestamp`），payment/aftersale 两域 DB now 一致，provider 用 DB 时钟正确。
- 默认（refund.enabled=false）下 503 用例断言 aftersale_command +2（admit 绑定保留，业务事务回滚）。
- Reply.envelope 私有，已在 fixture Reply 上加 `code()` 访问器。
