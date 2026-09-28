# BOOKING create 独立 MySQL 验收记录

2026-09-27；分支 `codex/booking-create-qa-20260927`。验收文件：`backend/pet-boot/src/test/java/com/petplatform/boot/booking/BookingCreateAcceptanceTest.java`。以 `fac6b73` 为初始基线，集成冻结的 38 号合同、真实 USER/MER/SERVICE 当前事实、SCH hold/proof、ORDER 创建内核与默认关闭的 Boot 装配，最终被测提交包含 `1c452f8` 共享行锁修复和 `0ebbd59` ACK 未知修复。本文件只报告内部内核，不代表公开 C 下单或完整结算上线。

执行环境：MySQL `8.4.9`，专用本机 `127.0.0.1:33459`，每例创建/销毁随机库；脚本 SQL06/28/29/33/37/38。Java Temurin 21.0.11、Maven 3.9.12；测试连接显式 UTC、业务事务 `READ_COMMITTED`，服务进程默认隔离级别不作通过依据。`BOOKING_MYSQL_URL/USER/PASSWORD` 优先，`AUTH_MYSQL_*` 完整凭据回退；CI 缺凭据会显式失败，不从默认空密码猜测。

运行命令（`backend/`）：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
$env:BOOKING_MYSQL_URL='jdbc:mysql://127.0.0.1:33459/'
$env:BOOKING_MYSQL_USER='root'
$env:BOOKING_MYSQL_PASSWORD=''
mvn -q -pl pet-boot -am '-Dtest=BookingCreateAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

最终 Surefire：`16 tests, 0 failures, 0 errors, 0 skipped`，进程退出码 0，测试耗时 `81.701s`。此前按新增用例定向运行并修复夹具；最终整套在最新已集成代码重跑通过。`git diff --check` 通过。

| 计划项 | 结果 | 实际证据 |
|---|---|---|
| B01 最后容量双用户竞争 | PASS | 两线程两个真实用户/请求同时创建，仅一笔主单、预约、claim、两快照、两域审计与成功绑定；失败者 `SCHEDULE_CAPACITY_EXCEEDED`。 |
| B02 同键同参并发 | PASS | 同一接送命令两线程最终一笔 order、一笔 hold、**PICKUP/RETURN 两条 claim**、各快照和审计一次、首次 SUCCEEDED 回执一次；另一响应重放或忙后用原键重放。 |
| B03 同键异参 | PASS | 首次成功后改返程时间冲突；首次执行失败保留 RESERVED 后改到店结束时间仍 `IDEMPOTENCY_KEY_CONFLICT`，原绑定不被覆盖。 |
| B04 hold 后失败整笔回滚 | PASS | MySQL 触发器在主单、服务快照、宠物快照、输入快照、状态日志、创建审计、成功回执七处分别强制失败；各案预约/claim/主单/快照/审计/成功回执零新增，RESERVED 保留，移除故障后同键成功。 |
| B05 成功重放 | PASS | 原窗已满且服务价格/宠物名改变后，同键仍返回首次 ID、金额和截止时间；旧快照不变，不新增占位。真实 commit 已完成但 ACK 丢失时回查原键重放；主库回查同时不可用时 503，恢复后同键只读出原单。 |
| B06 当前 USER 与归属 | PARTIAL | B 用户带 A 宠物得到 `PET_NOT_FOUND`，A 账号注销状态后旧成功键重放为 `COMMON_FORBIDDEN`；owner 取可信 USER context 与 USER 当前表，ORDER 结果归属复核。公开 HTTP bearer 过期、会话代际和认证适配未开放，`NOT_EXECUTED`。 |
| B07 独立 hold | PASS | 同库 guard RC 事务直接调用 SCH hold，beforeCommit 因缺 ORDER 双向绑定报 503，预约/claim/HOLD 审计均回滚。 |
| B08 旧 PENDING_BIND 孤儿 | PASS | 人工播种已提交活动预约+GENERAL claim、缺 ORDER 主单；真实新建报 503 且不能越过损坏事实，未产生新单。 |
| B09 到店真实快照 | PASS | 90 分钟服务拒绝 60 分钟请求；合法 GENERAL 原窗 ID、实际 claim 起止、双向绑定、128.00 金额、String ID、服务版本/适用宠物/核销标记快照及 USER 宠物快照均由真实 Owner 当前表读取。错误原窗拒绝。 |
| B10 接送双段 | PASS | PICKUP `[09:00,09:35)`、RETURN `[11:30,12:20)` 两条完整原窗 claim，主单展示外包络到 12:20；地址密文可解密且规范参数不含明文，不生成商家最终指派。返程不足120分钟拒绝。 |
| B11 券/支付/到期 | PARTIAL | 主单 `payment_expire_at` 与 hold `lock_expire_at` 均为固定Clock后10分钟；有券以503失败关闭且零业务行。券冻结与补偿、真实支付、自动关闭/释放worker、迟到支付：`NOT_EXECUTED`。 |
| B12 备注/地址保护 | PARTIAL | 缺真实审核器的非空备注503零业务行；接送地址经测试用 AES-GCM/HMAC 保护端口存密文，当前启动配置缺保护器时接送503；到店地址输入拒绝。生产内容审核器与真实保护器接入未验收。 |
| Boot 装配和共享锁反证 | PASS | `ApplicationContextRunner` 默认无创建/hold Bean，仅SCH开关亦无；双开关后 Bean 走真实 SQL 创建。另一连接持有共享协议版本及服务类目 `FOR UPDATE` 时，真实创建在持锁事务结束前完成，证明没有误锁全平台共享行。 |

审核签署资格不是用常量快照冒充：测试播种完整 SQL29 joined 审核链与 SQL28签署版本/接受记录，先由真实 `PersistentApplicationReviewFactsReader`、协议读取器及 `BookingMerchantFactsApiImpl` 当前读取验证，再走创建。该种子是已批准事实的隔离模拟，未执行申请提交、人工审核及签署写流程。测试用固定时钟和显式 SnowflakeIdGenerator 接口替身只控制时间与正 BIGINT 发号，不替代容量、Owner 事实、hold 或持久回执。

遗留门禁：公开 HTTP、真实会话与注销令牌、前端选窗来源、优惠券冻结/补偿、真实备注内容审核、生产地址保护器、支付创建/迟到退款、10分钟超时worker、确认/释放/改期、生产迁移与开关发布均未在本 QA 中交付。不能据本次 16/16 把这些项标成完成。
