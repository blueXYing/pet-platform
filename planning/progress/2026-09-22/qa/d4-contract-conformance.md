# D4 契约符合性抽检：PR#65 服务读切片（真实证据）

日期：2026-09-22。执行者：角色D（QA/集成）。worktree `C:/Users/Administrator/Desktop/wt-qa-svc-stores`，分支 `codex/qa-svc-stores-20260922`（HEAD=52a1c45=origin/develop，即 PR#65 合入点）。抽检对象：`GET /api/v1/c/stores/{storeId}/services`、`GET /api/v1/c/services/{serviceId}`。

## 1. 环境独占权检查（按指令先查后动）

检查命令与结果（2026-09-22 16:40 前后）：

- `env -u DOCKER_HOST docker ps -a`：在跑容器仅 `ms1-redis`（redis:7-alpine，127.0.0.1:16383→6379，Up 23h，Args=`--save --appendonly no`）与 `ms1-clamav`（13310，Up 23h）；其余 12 个容器均 Exited（5～12 天前残留）。
- `netstat -ano | grep LISTENING`：`127.0.0.1:33452`（PID 21488）、`127.0.0.1:16383`（docker backend）、另有无主 `0.0.0.0:3306/33060`（PID 5092 本机另一 MySQL，未使用）。
- `Win32_Process` 查证：PID 21488 = `"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqld.exe" --port=33452 --datadir=D:/Temp/mer001-s4-mysql-0a9e8e2fe9c7438f86a54072656f6795`（MER-001 S4 会话遗留）。
- MySQL 连通：`mysql -h127.0.0.1 -P33452 -uroot --skip-password -e "SELECT VERSION()"` → **8.4.9**；存在 6 个历史 `auth001c_http_*` 遗留库（此前中断运行残留，非本轮产生，不动）。

**结论：MySQL 33452 与 Redis 16383 均被既有进程占用（约 23～24 小时前的 MS-1/MER-001 会话遗留）。按任务指令"如被占用，记录并跳过启动改为运行模块测试"：本轮未启动任何新进程/容器，也未停止任何非本人启动的进程。**

## 2. 模块测试执行（真实 MySQL/Redis + 真实 HTTP）

命令（Git Bash，worktree 根）：

```bash
cd backend
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
env AUTH_MYSQL_URL="jdbc:mysql://127.0.0.1:33452/" AUTH_REDIS_HOST=127.0.0.1 AUTH_REDIS_PORT=16383 \
  mvn -pl pet-boot -am test -Dtest=ServiceQueryHttpTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

结果（完整日志 `d4-servicequeryhttptest-run.log`，surefire 摘要 `d4-servicequeryhttptest-surefire.txt`）：

```text
[INFO] Running com.petplatform.boot.auth.ServiceQueryHttpTest   (ForkedBooter Java 21.0.11, profile "local")
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 15.32 s
[INFO] BUILD SUCCESS  (Total time: 28.530 s)
```

测试为真实端到端：随机隔离库 `auth001c_http_<uuid>`（25/06/14/26/28/29 schema）→ 真实申请→审批→签署链 → SQL 播种 service_item → 随机端口起真实 Spring Boot → 真实 HTTP 调用两路由。等价 curl 场景与断言映射（行号为 `ServiceQueryHttpTest.java`）：

| 等价 curl 场景 | 断言 | 行 |
|---|---|---|
| `GET /c/stores/{id}/services?page=1&pageSize=20`（正例） | 200；items 仅 ACTIVE；`salePrice="128.00"`（String 两位）；serviceId=String；列表项无 description；page/pageSize/total 回显 | L333-345 |
| `GET /c/services/{id}`（正例） | 200；description/categoryName 投影 | L348-351 |
| 改价后再查（快照语义） | 新值 158.00；此前 API 副本仍 128.00/旧名（W2-SVC-002） | L354-367 |
| `GET /c/services/{offlineId}`、`/{draftId}`、`/{未知id}` | 一律 404（不区分原因） | L370-373 |
| `GET /c/stores/{不存在id}/services` | 200 空页（不暴露门店状态） | L375-378 |
| 商家 OFFLINE 后两路由 | 列表空页 + 详情 404；内部 reasonCodes=MERCHANT_DISABLED | L381-390 |
| 门店 FROZEN 后两路由 | 详情 404 + 列表空页 | L394-398 |
| 商家状态损坏 `CORRUPTED` | **详情 503 且列表 503**（与 404 严格区分） | L401-404 |
| 恢复 ACTIVE 后 | 详情重新 200 | L408 |
| 反例：`not-a-number`、`page=0`、`pageSize=51`、无 Authorization、未知 query `extra=1` | 400/400/400/**401**/400 | L411-416 |
| SVC-D5：无关消费者调所有者视角 checkOrderEligibility | ApiException NOT_FOUND | L421-431 |

信封契约：`ApiResponse{code,message,data,traceId}`，成功 code=`SUCCESS`，≥400 时 `data=null`（L546-549 断言）；`Cache-Control: no-store` 由 `@ModelAttribute` 与异常处理器双路设置（`CServiceController.java` L44-47、`CServiceExceptionHandler.java` L72）。

## 3. 对照冻结契约（OpenAPI11 两操作）逐项核对

| 契约点（OpenAPI11 L5986-6153 / 07号 §5.1.1 / 提案 v0.3） | 实现 | 判定 |
|---|---|---|
| 路由与 audience（MINIAPP，bearerAuth） | `CServiceController` 两路由，默认关 `pet.service.query.enabled`，CBearerSessionFilter 登记 | 符合 |
| storeId/serviceId path 参数 string | String + DecimalPublicIdCodec 正整数词法 | 符合 |
| page min1/max10000/def1；pageSize min1/max50/def20 | `parse(page,1,10_000)` / `parse(pageSize,20,50)` | 符合 |
| 404 "Not found, not visible or ineligible (indistinguishable)" | OFFLINE/DRAFT/未知/商家门店停用统一 404 | 符合 |
| 503 "Facts source failure or unknown status; fail closed" | CORRUPTED/非法存储值→503 | 符合 |
| 401 "C session required" | 无 Bearer 401 | 符合 |
| 400 "Invalid path or pagination arguments" | 非法 ID/越界分页/未知参数 400 | 符合 |
| 列表排序 created_at DESC,id DESC；仅 ACTIVE | `ServiceReadMapper.xml` ORDER BY+status='ACTIVE' | 符合 |
| C 端响应不携带 bookability | 视图无该字段；reasonCodes 仅内部 checkBookable | 符合 |
| 快照值拷贝、金额两位 String | toPlainString（DECIMAL(18,2)→scale 2）；改价副本不变 | 符合 |
| 详情 404 错误码 `SERVICE_NOT_FOUND`（提案 §3 + 决定回执第 1 条明文） | 实现抛 `COMMON_NOT_FOUND`（`ServiceQueryService.java` L171-173） | **漂移②（见 §4）** |

## 4. 契约漂移发现（附证据）

1. **HTTP10 §3.3.1 未落盘（权威同步缺口）**
   - 证据：`git log bb6bb5c..52a1c45 -- docs/04-api/10-HTTP-API-Contract-v0.4.md` 为空（PR#65 未改该文件）；`grep -c "3\.3\.1" docs/04-api/10-HTTP-API-Contract-v0.4.md` = 0；该文件无 SERVICE_NOT_FOUND、无两路由响应示例。
   - 但决定回执（`service-domain-decisions.md` "权威同步（随本切片 PR）"）、提交信息 `50f7ff4`（"HTTP10 3.3.1 route contract…"）、HANDOFF 均声称已同步；且 OpenAPI11 L6018/L6095 与 07号 L294 交叉引用"HTTP contract 10 section 3.3.1"——引用目标不存在。
2. **详情 404 错误 code 与已批文案不一致**
   - 已批：提案 v0.3 §3 表格"404 `SERVICE_NOT_FOUND`（…一律同响应不区分原因）"+ 回执第 1 条同文。
   - 实现：`CommonApiCodes.NOT_FOUND`（字面 `COMMON_NOT_FOUND`）；07号 §5.1.1 同步文本却写泛化"不可见一律 NOT_FOUND"。测试仅断言 HTTP 404 未断言 body code（`ServiceQueryHttpTest` L370 等），故未被 CI 发现。
   - 影响：12 号注册表中 `SERVICE_NOT_FOUND`（§12）在两路由上从未被发出。需 Contract Owner 裁决：改实现发 SERVICE_NOT_FOUND，或修订提案/07号并补落 10号 §3.3.1。
3. （观察项，非漂移）OpenAPI11 两操作 200 响应引用 `MerErrorEnvelope`（含 `success` 布尔的商家域信封）作为占位，而实际 C 端信封为 `{code,message,data,traceId}` 无 success 字段；与 AUTH 路由用 AuthErrorEnvelope 的占位惯例一致，但域选型建议在补 §3.3.1 时一并明确 200 投影 schema（ServiceDetailView/StoreServiceItemView 未入 OpenAPI）。

## 5. 进程与容器记账

- 本轮启动：无（MySQL/Redis 复用既有进程；测试为 surefire fork 短生命周期 JVM，随 mvn 退出自动结束，16:46:07 结束）。
- 本轮停止：无（未动 ms1-redis、mysqld 33452、ms1-clamav 及任何非本人进程）。
- 残留核验：`information_schema.tables` 按 create_time 核对，现存 7 个 `auth001c_http_*` 遗留库全部创建于 09-20～09-21（此前中断运行残留）；本轮运行（09-22 16:45-16:46）自建的随机库已被 fixture close() 正常 DROP，**本人运行零残留**。遗留库非本人产生，不清理。
- 未起本地长跑服务器（MS1LocalServer 先例的 Hikari/上传开关/硬杀要求因此未触发）：环境被占用按指令跳过，L3 留待后续（见 ACCEPTANCE-CHECKLIST §6）。
