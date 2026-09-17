# MER-001 S3 协议同意链路 QA handoff

状态：定向真实MySQL测试18/18通过，主代码与测试已释放给根任务整合。下文保留测试设计，实际执行结果见末尾；全后端/远端CI以主交接和对应提交结果为准。

## 范围与证据边界

- 本轮只验 merchant 模块的协议版本读取、主账号首次同意、版本不可覆盖及 merchant 自有 requestId 幂等/同意事实持久化。
- fixture 每次只创建随机 `mer001_*` 数据库，执行 Schema06 与根线程提供的已批准 `28-Merchant-Agreement-Schema-v0.1.sql`。协议版本、当前发布指针、merchant 本行、必要门店和幂等表由测试直接 seed；没有生产 publish HTTP/API，也不把 seed 记录当作运营发布功能证据。
- 申请审核事实没有冻结真实物理表。`ApplicationReviewFactsReader`（名称以 backend 最终签名为准）只能作为严格标注的领域端口替身，返回 APPROVED/非 APPROVED 或抛出依赖异常；不建申请表、不伪造真实审核审计，不用 merchant.status 推导 APPROVED。
- 测试使用真实 MySQL 事务、唯一约束和行锁；Hash 从 fixture 的明确非生产 UTF-8 纯文本按 SHA-256 计算。不得使用 H2、Mock DAO、内存 acceptance 或预先伪造 signed 缓存替代数据库事实。
- HTTP 控制器、真实 Bearer 会话/AUTH成员绑定、运营 publish、真实申请审核、外部 Provider、生产迁移/部署不在本轮声称通过；没有对应 backend 入口的用例记录为未覆盖。

## Fixture 约束

1. merchant 使用 owner ID `9007199254740993`，另 seed 非 owner 和 STAFF 场景所需用户/关系；HTTP/JSON ID 断言保持十进制 String，Java/SQL 使用 Long。
2. 协议文本使用非生产纯文本（例如包含中文和换行的 UTF-8 内容），校验数据库保存字节数、原始 SHA-256 小写 64 位十六进制、版本 ASCII 词法和同版本内容不可覆盖。
3. 当前指针只从已 seed 的版本中选择；通过 SQL 更新测试当前发布指针以模拟运营发布，不测试 publish 命令本身。每个测试结束只删除本测试成功创建的随机库。
4. 服务端时间使用 backend 可注入 Clock（如有），或以数据库 `DATETIME(3)` 读取实际 acceptedAt；不依赖客户端时间，不将 acceptedAt 作为输入。

## 计划用例

### 读取、身份与事实依赖

- owner 读取未签商家的当前发布版本，返回版本、原始 content、匹配 hash、`NOT_SIGNED`，无 acceptance 审计账号泄露；同库 acceptance 不存在时不能默认为 SIGNED。
- 非 owner、STAFF、禁用/撤销关系分别拒绝 GET 和 consent；请求 body 的 merchantId、operatorId、staffId、source 不能自授予 owner 权限。精确错误码按 backend 契约断言（HTTP 层缺失时以内部 403/404 边界报告）。
- 主账号在审核 APPROVED、尚未签署且工作台 admission 仍非 ALLOWED 的事实下可以 consent，证明签署不依赖“先取得 ALLOWED”而不会形成死锁。
- 申请事实 reader 缺失、抛出异常、返回未知/损坏状态或数据库连接不可用时，读取/同意必须 503 失败关闭；不返回正常 `NOT_SIGNED`，不默认允许，不泄露 source exception/message。

### 版本、内容与输入校验

- agreementVersion 覆盖合法边界、空值、首字符非法、空格/换行、Unicode、超过 64 字节/字符、未知版本；当前发布版本不匹配、未发布版本、审核未通过分别按批准契约拒绝，且不写 acceptance。
- contentSha256 必须是当前版本原始 UTF-8 文本的匹配小写 64 位 hex；大写、长度不符、非 hex、hash 与内容不符、请求 version/hash 组合不符均拒绝并保持数据库不变。
- `accepted=true` 是唯一成功值；false、null、未知字段或缺字段拒绝。成功请求不接受客户端 acceptedAt、operatorId、publishedBy 或任何审计账号覆盖。
- 同一 agreementVersion 的内容/hash/published metadata 不可 UPDATE 覆盖；新版必须新增版本和当前指针，旧 acceptance 引用仍可读。

### 同意、换版与读取语义

- 首次 consent 在真实 owner、APPROVED、当前发布版本匹配时写入一条不可变 acceptance、服务端时间和 operator userId，结果 `SIGNED`；不创建第二条过程状态。
- 已签商家发布新版后，旧 acceptance 仍保持原版本和原 acceptedAt；GET 返回已同意版本及原始文本/Hash，不因 current 指针换版退回 NOT_SIGNED 或强制重签。
- 已签商家用新 requestId 对新版或其他版本再次 consent 按 V1 不新增重签规则返回冲突，旧 acceptance/时间不变；首次签署旧版本而 current 已切到新版返回 409，不能凭旧页面完成签署。
- 事实为非 APPROVED、merchant 不存在/越权、current 指针缺失/损坏、版本 source 不可用时均保持失败关闭；不以 merchant ACTIVE、provider_merchant_no 或空 acceptance 猜测签约成功。

### 幂等、并发、事务和 ACK 丢失

- 同 actor/scope、同 command、同 requestId、同规范参数重复：返回第一次版本/acceptedAt/receipt，数据库只保留一条 acceptance 和一条最终幂等绑定，不刷新时间。
- 同 requestId 异参（版本、hash、merchantId、operator 或规范命令参数任一变化）返回 `IDEMPOTENCY_KEY_CONFLICT`；不能返回旧敏感回执，不能覆盖第一次绑定。
- requestId 按原始 UTF-8 字节比较：支持公共上限 512 字节（包括规范命令前缀后的 `VARBINARY(1024)` 存储），大小写变化、尾空白变化均视为不同 key；两个大小写不同 UUID 仍因 merchant/version 业务唯一约束只产生一条签署事实，acceptedAt 稳定。
- 首次失败后的 RESERVED/绑定按 23 号规则保留；同 key 同参数只允许按契约重试，异参继续 409；失败不得留下 acceptance、伪造 signed 或覆盖旧绑定。静态参数失败是否在 RESERVED 前返回按 backend 入口标注，不将两层语义混为一谈。
- 两个不同 requestId 并发 consent 同一 merchant/current version，使用真实 MySQL 两连接/CountDownLatch：最多一条 acceptance，acceptedAt 稳定且不被后写刷新；每个结果按契约为同一成功回执或一个明确冲突，不产生第二条同意。
- 注入业务事务中 acceptance 写成功但回执/幂等写失败的故障点，断言业务与 receipt 同事务回滚：无孤立 acceptance、无孤立成功回执、幂等状态可按契约重试。不得用内存结果替代数据库断言。
- 使用只影响测试连接 ACK 的 `CommitAckLossDataSource` 类似替身，让真实 MySQL commit 成功后丢失客户端确认；以原 requestId/主库查询恢复唯一 acceptance 和稳定 acceptedAt，不重复签署、不换 key。该替身只模拟传输确认丢失，commit 事实仍由真实 MySQL 证明。

### 损坏事实与历史保护

- current 指针缺失、指向不存在版本、指向重复/错误 key 或版本 hash 与内容不一致时，GET/consent 报 503/契约依赖错误；不能回退到任意历史版本、不能将损坏事实伪装为 `NOT_SIGNED`。
- 孤儿 acceptance（版本记录缺失）和 acceptance 保存 hash 与版本内容不一致时，读取/同意失败关闭并保留数据库事实用于审计；不删除、修补或重新生成签署时间。

## 环境交接

根线程尚未提供本轮端口/账号，QA 不使用已关闭的 33450 实例。建议沿用专属变量名并由根线程在运行前注入：

- `MER001_MYSQL_URL`：`jdbc:mysql://127.0.0.1:33451/`（根线程已启动本轮独占 MySQL 8 实例）；fixture 自己追加随机库名和 UTC 参数。
- `MER001_MYSQL_USER`：`root`。
- `MER001_MYSQL_PASSWORD`：空值，不写入仓库、日志或异常。

fixture 必须拒绝带 database/query 参数的 URL、拒绝非 loopback、校验 MySQL 8.x；连接或 schema 初始化失败时测试不可执行，不降级 H2。

## 交付报告要求

测试文件仅放 `backend/pet-merchant-api/src/test/**`、`backend/pet-merchant-biz/src/test/**`；本文件是本轮唯一 planning 写入。最终报告列准确测试数、真实 MySQL 版本/随机库规则、失败与修复、事务/ACK 测试替身层级、申请事实/HTTP/Provider/publish/迁移未覆盖范围，并明确不把 S3 基础链路等同完整 MER-001 DONE。

## 定向回归结果

2026-09-17 在根线程分配的独占 MySQL 8 实例执行，环境为 `MER001_MYSQL_URL=jdbc:mysql://127.0.0.1:33451/`、`MER001_MYSQL_USER=root`、空密码，Java 21.0.11。执行命令：

```text
mvn -pl pet-merchant-api,pet-merchant-biz -am
  -Dtest=MerchantAgreementApiMySqlTest
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`MerchantAgreementApiMySqlTest` 共 18 tests，Failures=0、Errors=0、Skipped=0，Maven BUILD SUCCESS（51.600s）。每个用例均创建并清理自己的随机 `mer001_*` 数据库，先执行 Schema06，再执行 SQL28；真实 MySQL 证明协议、同意、唯一约束、幂等绑定、事务回滚和锁并发。

本次实际覆盖：owner/non-owner/STAFF 隔离、未签前不要求 ALLOWED、APPLYING 首次同意 503 且无 acceptance（RESERVED 可保留）、版本/hash/accepted 严格校验、旧协议在新版发布后保持效力、首次旧版 409、同 key 重放/异参冲突、大小写 key 与 512-byte requestId、新 key 同版本稳定回执、失败 RESERVED 绑定、同 key 并发重查 SUCCEEDED 且审核事实只读一次、双 key 并发单 acceptance、current/内容/acceptance 损坏失败关闭、FK 拒绝孤儿 acceptance、reader 异常脱敏、markSucceeded 故障原子回滚、外层事务拒绝、真实 execution commit（第 3 个 commit）ACK 丢失恢复，以及成功重放不再申请新 ID。

首次运行在 testCompile 阶段因测试遗漏 `AtomicInteger` import 失败；补入测试 import 后重新执行，18 项全绿。该修复只改测试文件。`ApplicationReviewFactsReader` 是明确标注的审核事实端口替身；真实申请审核表、运营发布接口、HTTP/Auth/Provider、生产迁移和跨域工作台仍未覆盖。
