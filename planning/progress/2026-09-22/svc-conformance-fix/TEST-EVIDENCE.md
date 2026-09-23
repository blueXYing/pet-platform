# SVC 契约符合性修复（角色F）测试证据（2026-09-22）

worktree `C:/Users/Administrator/Desktop/wt-svc-fix`，分支 `codex/svc-conformance-fix-20260922`（基于 origin/develop 52a1c45）。实现提交 `049a9c6`（错误码修复+测试补强）；文档提交见本分支第二个 commit（10号 §3.3.1 补写、11号 404 码对齐、HANDOFF 勘误）。

## 环境（全程未启动/停止任何非本人进程）

- MySQL `127.0.0.1:33452`（既有 mysqld，PID 21488）与 Redis `127.0.0.1:16383`（容器 ms1-redis）复用，未重启。
- JDK `C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot`；Git Bash；Maven 本地仓 `D:\maven_repository`。
- 跑测试前后 `tasklist` 检查：仅既有 MS1LocalServer（PID 18780）与无关项目 jar（PID 12460），无并发 mvn/测试进程；本轮所有 surefire JVM 随 mvn 退出结束，零残留（测试库为随机隔离库，fixture close() 正常 DROP）。

## 1. 定向测试：ServiceQueryHttpTest（真实 MySQL/Redis + 真实 HTTP）

命令：

```bash
cd backend
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
env AUTH_MYSQL_URL="jdbc:mysql://127.0.0.1:33452/" AUTH_REDIS_HOST=127.0.0.1 AUTH_REDIS_PORT=16383 \
  mvn -pl pet-boot -am test -Dtest=ServiceQueryHttpTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

结果（2026-09-22 17:32，surefire 摘要见 `servicequeryhttptest-surefire.txt`）：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 22.28 s
BUILD SUCCESS（Reactor 全模块 SUCCESS，Total time 39.050 s）
```

断言覆盖（本轮补强后，状态码与响应 body `code` 双断言）：

| 场景 | 断言 |
|---|---|
| 不存在 ID / 服务 OFFLINE / DRAFT | 404 + `SERVICE_NOT_FOUND` |
| 商家 OFFLINE（列表隐藏 + 详情） | 列表 200 空页；详情 404 + `SERVICE_NOT_FOUND` |
| 门店 FROZEN（列表隐藏 + 详情） | 列表 200 空页；详情 404 + `SERVICE_NOT_FOUND` |
| 商家状态损坏 CORRUPTED（事实源故障，两路由） | 503 + `COMMON_DEPENDENCY_UNAVAILABLE` |
| 正例（可见列表/详情/改价快照副本/400/401/空门店空页/D5 反例） | 维持原断言不变 |

该测试在修复前实现（COMMON_NOT_FOUND）下会因 `assertHidden` 的 body code 断言失败——这正是 PR#65 测试未拦截漂移的缺口，本轮已补上。

## 2. 文档冒烟：e2e/contract_smoke.py

`python e2e/contract_smoke.py` → `PASS_OFFLINE_DOCUMENT_SMOKE`，`serviceCatalogOperations: 2`（11号两操作结构未破坏；07号 §5.1.1 与 11号对 10号 §3.3.1 的引用随本补写消解）。

## 3. 大范围回归：`mvn -pl pet-boot -am test`（全 Reactor 42 模块）

如实记录过程（中间失败均为本 worktree 环境前置条件，非代码问题）：

- 第1次：失败于 pet-id-core —— 其 MySQL 夹具默认端口 33442 无监听（需 `PLAT002_ID_MYSQL_URL`）；
- 第2次（补 PLAT002/PLAT003/OSSTEST/PLAT004/USR001）：失败于 pet-merchant-biz —— `MER001_MYSQL_URL` 默认 33450 无监听；
- 第3次（补 MER001，先尝试 `mvn -rf :pet-merchant-biz` 续跑）：编译失败——本地仓 pet-merchant-api 快照过期（缺 SVC-D5 API），`-rf` 续跑不重编上游，遂放弃续跑、改整跑；
- 第4次（全量、env 齐备）：上游 41 模块全 SUCCESS；pet-boot 2 个用例失败——`CFrontendHttpTest`/`MerchantApplicationLifecycleHttpTest` 要求 frontend-miniapp 已 `npm ci`（本新 worktree 未装 node_modules）；
- 第5次（`npm ci` 安装 frontend-miniapp 依赖后全量重跑，完整日志 `pet-boot-full-regression.log`）：**BUILD SUCCESS**，全 Reactor 42 模块 SUCCESS（Total time 07:57 min，2026-09-22 18:05:59）；pet-boot `Tests run: 67, Failures: 0, Errors: 0, Skipped: 0`，其中 `ServiceQueryHttpTest` 1/1 通过（5.614 s，含本轮补强的 body code 断言）。

## 4. 备注

- `PRIVATE_ASSET_MYSQL_URL` 未显式设置：其代码回退到 `OSSTEST_MYSQL_URL`（已设 33452）。
- 各模块测试夹具默认连接历史上不同端口（33440/33442/33450），本轮统一指向在跑实例 33452；夹具均按随机库名隔离，未动既有库。
