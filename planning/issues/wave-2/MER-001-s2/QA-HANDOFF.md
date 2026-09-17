# MER-001 S2 QA handoff

状态：测试契约已收到，等待后端类名/提交稳定后落地测试；本文件是测试计划与环境交接，不是 PASS 报告。

## 范围与证据边界

- 本轮只验证 `MerchantQueryApi` 的 `getStore(StoreIdQuery)`、`getStaff(MerchantStaffQuery)` 和 `checkOrderEligibility(MerchantOrderEligibilityQuery)`。
- 后端当前授权切片仅接受可信 `QueryContext` 的 `USER`，且 `operatorId` 必须是目标 merchant 的真实 `owner_user_id`；不把请求中的目标 ID、staffId、source 或手机号当授权凭据，不推断成员/子账号权限。
- 所有数据库断言使用真实 MySQL 8 和每次随机创建的 `mer001_*` 数据库。fixture 只执行 Schema06（以及实现实际需要且已批准的既有 SQL），不触碰既有库、不读取秘密凭据、不使用 H2、Mock DAO 或内存结果替代 MySQL。
- Schema06 没有批准的申请/审核/协议事实表；不得插入假 `APPROVED`、`SIGNED`、默认 `ACTIVE` 证据来声称真实联调。资格事实使用后端注入的 `EligibilityFactsReader` 作为严格标注的领域层替身，默认 DataSource 构造应因事实不可用而 503；替身只证明策略分支，不证明申请/签约持久化或 HTTP。
- 不测试尚未实现的 HTTP 控制器、员工写入/停用、协议同意、下线事件/Outbox、订单存量履约或服务预约；这些在交接中明确为未覆盖。

## 计划用例

### W2-MER-001：商家、门店、人员读取和归属

1. 使用 ID `9007199254740993` 及同前缀的门店/人员 ID 写入 Schema06，读取 DTO 时保持十进制 String 精度，内部 SQL 绑定为 Long。
2. owner 读取本人 active store/staff，断言 merchant/store/staff 归属一致、返回独立 DTO；phone 只返回掩码（空 phone 保持 null），不泄露完整联系方式或登录成员信息。
3. 目标不存在、其他 owner、跨 merchant 的 store、跨 store 的 staff、伪造 staffId 均统一 `COMMON_NOT_FOUND`，`data` 不可带旧结果；不能通过请求中的 merchantId/storeId 改变实际归属。
4. 非 USER、operatorId 为空/格式非法、QueryContext 缺失、ID 为零/负数/前导零/非数字，分别按实现契约断言 `COMMON_FORBIDDEN`、`COMMON_UNAUTHORIZED` 或 `COMMON_INVALID_ARGUMENT`。
5. merchant/store/staff 状态和枚举映射覆盖 ACTIVE/OFFLINE/FROZEN、employment ACTIVE/INACTIVE、serviceEnabled true/false；未知枚举应 `COMMON_DEPENDENCY_UNAVAILABLE`，不猜测允许。
6. 读取结果边界覆盖名称/地址合法值、经纬度空值和 7 位小数；超长/非法持久化事实的响应应拒绝或依契约报依赖错误，不截断脱敏字段。

### W2-MER-002：新单资格的事实组合和失败关闭

1. 注入明确的 application/signing reader facts，覆盖 merchant ACTIVE + store ACTIVE + APPROVED + SIGNED，断言五字段 DTO 仅在此组合返回 `acceptsNewOrders=true`，merchantEnabled/storeEnabled 与状态独立计算。
2. merchant OFFLINE/FROZEN/CANCELED、store OFFLINE/FROZEN、application DRAFT/REVIEWING/REJECTED、signing NOT_SIGNED，逐项断言可判定的 `acceptsNewOrders=false`，不把 false 扩展成存量履约/售后拒绝。
3. 缺 application、缺 signing、未知状态/来源异常、reader 抛依赖异常，断言 `COMMON_DEPENDENCY_UNAVAILABLE`，禁止默认 true，也禁止把事实未知伪装为正常 false。
4. merchant 与 store 不归属、owner 越权、非法大 ID，先做资源/参数校验并断言对应 400/403/404，不调用事实 reader 泄露跨范围信息。
5. 构造默认 `MerchantQueryApiImpl(DataSource)`，确认未具备批准事实表时保持 503 失败关闭；这项不创建申请/协议表，也不构成真实资格联调证据。
6. 若后端暴露同一事务 reader 可观察接口，使用并发更新或两次查询检查 merchant/store/事实读取来自同一连接/一致性边界；无法验证时记录为未覆盖，不用替身宣称读一致性通过。

## 环境交接

建议根线程/CI 注入以下非秘密变量，变量只指向本机隔离 MySQL 8：

- `MER001_MYSQL_URL`：`jdbc:mysql://127.0.0.1:33450/`（根线程已启动独占 MySQL 8.4 隔离实例）；fixture 自己追加随机库名和 UTC 连接参数。
- `MER001_MYSQL_USER`：`root`。
- `MER001_MYSQL_PASSWORD`：空值；不写入仓库、日志或测试输出。

fixture 建库前校验 URL 不含 database/query 参数，建库成功后只执行 `CREATE DATABASE mer001_<uuid>`；`close()` 仅删除本次 fixture 自己成功创建的同名库。MySQL 版本不是 8.x、连接不在 loopback 或建库失败时测试失败/不可执行，不能降级 H2。

## 交付与报告

测试文件只允许放在 `backend/pet-merchant-api/src/test/**` 和 `backend/pet-merchant-biz/src/test/**`；本交接文件是唯一允许的 planning 写入。后端正在写生产文件时不做全量构建；待其报告类名、构造器和提交稳定后先编译 merchant-api/biz 定向测试，再做真实 MySQL 回归。最终报告必须列准确测试数、每项失败及修复过程、真实 MySQL/替身层级、W2-MER-001/002 覆盖和 W2-MER-003/HTTP/跨域未覆盖范围。

## 已执行的定向回归

2026-09-17 使用 Java 21.0.11、真实 MySQL 8.4.9（`127.0.0.1:33450`，每例随机 `mer001_*` 库）执行：

```text
mvn -pl pet-merchant-api,pet-merchant-biz -am
  -Dtest=MerchantQueryApiMySqlTest,MerchantDtoSerializationTest
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`MerchantQueryApiMySqlTest` 9/9、`MerchantDtoSerializationTest` 1/1，合计 10 tests，Failures=0、Errors=0、Skipped=0，Maven BUILD SUCCESS。首次 PowerShell 未将 `-Dsurefire...` 整体传给 Maven，误报 lifecycle phase；为参数加引号后重新执行通过，未修改生产代码或测试断言降级。

真实 MySQL 覆盖了同商家跨店 staff 错配、owner/merchant 越权、Long.MAX/溢出/换行 ID、TinyInt `service_enabled` 异常、父状态/枚举异常、phone 掩码、默认缺失资格事实 503、明确申请/签约四态及同 DataSource repeatable-read 连接快照。`MerchantEligibilityFactsReader` 是明确标注的策略端口替身；申请/签约真实来源、HTTP、写入、停用、协议、下线 Outbox、订单/服务跨域仍未覆盖。

随后根修复了资格事实来源异常的统一边界：reader 抛出 `ApiException`（包括 400/403/404）或普通运行时异常，都转换为固定 `COMMON_DEPENDENCY_UNAVAILABLE`，不泄露 source code/message；本域资源越权 404 保持原语义。最终定向回归再次 9/9 + 1/1 全绿，并补验 `INACTIVE + service_enabled=1`、非空员工手机号必须为 11 位 ASCII 手机、`service_enabled=2/-1` 以及 reader 异常脱敏。
