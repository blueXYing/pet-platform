# 预约保护基础独立 QA 执行记录

2026-09-27；分支 `codex/reservation-foundation-qa-20260927`；最终执行树含接口/SQL37、MER/ORDER/SCH 实现及 `0f70725` 全店原窗容量修复。本记录是基础 API 验收，不是 SCH-003 hold/create/swap、MER 停用、生产迁移或开关发布验收。

## 环境与命令

- Windows PowerShell；Java Temurin 21.0.11；Maven 3.9.12；本机独立 MySQL 8.4.9，`127.0.0.1:33457`。服务默认 `REPEATABLE-READ`，测试每个业务事务显式设可写 `READ_COMMITTED`；测试另主动提交 RR/只读反例，要求 503。每个用例随机创建并清理自己的 `qa_reservation_*` 数据库，加载 06 Schema + SQL37；多线程分别取得独立物理连接。
- 全部基础测试：在 `backend` 执行 `mvn -q -pl pet-boot -am '-Dtest=ReservationProtectionFoundationAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，**21 tests，0 failures，0 errors，0 skipped**；Surefire 套件运行时间 43.953 秒。没有使用模拟器、生产库、OSS 或共享 Redis。
- 凭据回退改动后，以仅设置 `AUTH_MYSQL_URL=jdbc:mysql://127.0.0.1:33457/`、`AUTH_MYSQL_USER=root`、`AUTH_MYSQL_PASSWORD=''` 且未设置 `PROTECTION_MYSQL_URL`，单独运行 `ReservationProtectionFoundationAcceptanceTest#foundationBeansAreAbsentByDefaultAndPresentOnlyWhenExplicitlyEnabled`，**1 test，0 failures/errors/skips**。配置选择先取整套 `PROTECTION_MYSQL_*`，否则整套 `AUTH_MYSQL_*`，最后本地默认；不会混用 URL 与另一前缀密码。

## 已通过的独立证据

- **共同 guard**：首行双连接竞争，A 回滚后 B 得锁且仅一行；X 锁持有时 Y 获锁；A 持有后 B 同店等待提交且读到新 MER 版本；无事务、只读、RR、错 DataSource、`REQUIRES_NEW` 读、事务结束复用均拒绝。外层捕获错 DS 异常后提交仍被 rollback-only 拦截；嵌套独立事务重拿同店 guard 在 5 秒内拒绝，没有等 MySQL 长锁超时。
- **ORDER 完整性**：有效已指派/未指派/历史已指派全店枚举、按预约和按员工过滤、过滤前总数及 `protectRequired`；两份当前事实单侧缺失、全局孤儿 current assignment、`PENDING_SERVICE+RELEASED` 和 `COMPLETED+UNVERIFIED` 均 503。两个健康门店各有 current assignment 时，X 的 ORDER/assignment 行锁未阻塞 Y 的全店 ORDER 公共查询。
- **SCH 容量**：公开 proof API 验证跨服务同员工不重复使用、非贪心可行解、已固定指派不暗中转给他人、接送双完整段须同人且段间可无班、GENERAL 半开端点、原窗配置容量、与人员闭包时间不相交的原窗超额仍被查出。历史 `COMPLETED/VERIFIED` 且仅过去 claim 的人员即使已 `INACTIVE/false` 仍可保留历史指派；未来有效 claim 则 503。预算通过可控单调 ticker 确定性耗尽，公开 proof 映射 503 且不落订单/预约。
- **独立数学 oracle**：固定种子 150 个 4～5 预约、3 员工小图，以测试内全枚举结果对照公开纯求解器；覆盖不同服务资格、时间相交/相邻、固定人员与传递闭包；无预算超时，全部一致。oracle 不调用实现的排序、剪枝或回溯方法。
- **MER/装配**：员工表真空可证明 `complete=true`，合法 `INACTIVE/false` 保留，未知枚举、坏归属、非法在岗布尔和 `INACTIVE/true` 均 503。默认开关无五个保护 Bean，显式开启则五个公共 Bean 齐备；该测试不启动 Web/Redis。

## 本轮仍未验证

本套没有测试真实 hold/create/swap、窗口和排班/能力减员写路径、MER 停用、跨模块审计/幂等、HTTP/前端、存量回填、生产级规模/索引 EXPLAIN 或故障恢复。方案 G06 多门店升序锁、O06 跨分页完整枚举、F07 指定双段递归桥接等未以专门夹具执行；它们不应从这 21 个通过项推定为通过。开关当前默认关闭。
