# W3 整合验收报告（PR38～#45 连续合入后的 develop 整体验收）

> 2026-09-17。本报告为 W3 整合验收登记：后端全量验证、MyBatis 迁移组合事实抽查、
> PR38 C 端小程序模拟器联调、PLAT-005 DoD 核对与生产门禁巡检。只做验收与文档登记，
> 不含新业务开发，未执行任何生产启用/迁移。分支 `codex/w3-integration-acceptance-20260917`，
> 独立工作树 `C:/Users/Administrator/Desktop/wt-w3-acceptance`（主目录未动）。

## 1. 基线与 GitHub 核查

| 项 | 结果 |
|---|---|
| origin/develop | `37350d0815c66d56e7aa34ebf727bc3154bfffa4`（PR45 合并提交），与预期一致，未前移 |
| PR38～#45 | 全部 MERGED（38=15e1f64、39=ee4fd05、40=d8b623c、41=b6f6088、42=809a443、43=ba54ef5、44=19b15d0、45=37350d0） |
| develop 头部 CI | [run 35173601495](https://github.com/blueXYing/pet-platform/actions/runs/35173601495) 六项 success（backend、frontend-inventory、repository-policy、contract-smoke、web-build、miniapp-weapp-build） |
| 在途 PR / 新增提交 | 无（open PR 列表为空） |
| 主目录 | 仅用户本地 `frontend-miniapp/project.config.json` 修改（保留，未覆盖未提交） |

## 2. 后端整体验收

命令与 CI 同口径，全部在独立工作树执行：

- `python backend/tools/check-module-deps.py` → **PASS**（41 reactor 模块、17 个 biz POM，无 biz→biz 依赖）
- `python -m unittest discover -s backend/tools -p 'test_*.py'` → **13 项 OK**（架构负例）
- `python backend/tools/check-display-status.py` → **PASS**（无重复 DisplayOrderStatus 派生）
- `mvn -B -f backend/pom.xml clean verify` → **BUILD SUCCESS**（分两轮完成，见下）

环境：Java Temurin 21.0.11（JAVA_HOME 显式指向）、MySQL 8.4.9 @127.0.0.1:3306（root；
测试库随机名只建删）、Redis 7.4 @16379（容器 `plat006-auth-redis`，已核 `save=""`/`appendonly no`）、
`SERVER_PORT=0`（本机 8080 被占用）、CI 全套 MySQL env（PLAT004/PLAT003/USR001/OSSTEST/PLAT002_ID/AUTH_*）。

### 首轮唯一失败与解释（环境性，非代码缺陷）

首轮 `clean verify` 在 pet-boot `CFrontendHttpTest` 失败：「Run npm ci in frontend-miniapp
before the integration suite」。CI（ci.yml L74-76）在 mvn verify 之前执行
`working-directory: frontend-miniapp → npm ci` 准备前端客户端；本地首轮未执行该步骤。
补跑 `npm ci` 后 `mvn verify -rf :pet-boot` 全绿。断言与测试代码零改动。

### 逐模块测试计数（surefire XML 权威汇总）

| 模块 | tests | failures/errors/skipped | 套件 |
|---|---|---|---|
| pet-common | 79 | 0/0/0 | 5 |
| pet-thirdparty-biz | 4 | 0/0/0 | 2 |
| pet-user-biz | 24 | 0/0/0 | 3 |
| pet-event-core | 10 | 0/0/0 | 3 |
| pet-task-core | 22 | 0/0/0 | 2 |
| pet-id-core | 41 | 0/0/0 | 7 |
| pet-admin-biz | 34 | 0/0/0 | 3 |
| pet-boot | 25 | 0/0/0 | 7 |
| pet-architecture-test | 22 | 0/0/0 | 2 |
| **合计** | **261** | **0/0/0** | **34** |

与各 PR 合入证据对比：总数 261/34 套件与 PR38 报告一致；PLAT-006 六模块计数
（thirdparty 4、user 24、event 10、task 22、id 41、admin 34）与迁移报告 §8 逐模块数字
完全一致；ArchUnit 22 项持续通过。**无差异**。

## 3. MyBatis 迁移组合事实抽查（PLAT-006 #39～#44 合入后）

| 抽查项 | 结果 |
|---|---|
| mapper XML 源码位置 | 10 个 `src/main/resources/mapper/*.xml`：thirdparty 1、user 4、event 2、task 1、id 1、admin 1 |
| 构建产物 | clean verify 后 10 个 XML 全部位于各模块 `target/classes/mapper/` |
| 装配方式 | 6 处 `SqlSessionFactoryBean.setMapperLocations(classpath*:mapper/*.xml)`（Admin/Event/Id/Task/AssetRegistry/User MyBatis 装配类） |
| 生产主代码手写 JDBC | `grep JdbcTemplate|java.sql.` 于 `backend/*/src/main` 仅 1 处：pet-boot `AdminAuthConfiguration.java:103` Flyway 迁移目标校验（已审计的装配基础设施，非表持久化） |

结论：与 22 号裁决 §5 完成回执声明一致。勘误登记：迁移报告 §8 正文写“8 个 XML”，
按其自身逐模块列举合计为 10（1+4+2+1+1+1）；实体清单与仓库事实一致，仅计数笔误。

## 4. 小程序联调验收（PR38 链路，模拟器）

### 4.1 本地联调环境（全部隔离、不入库）

- 后端：未提交的本地 harness（`W3LocalHarness.java`，仅存于工作树，不在本次 PR 内）以
  JUnit 夹具同款方式启动真实 Boot 栈：隔离随机库（Schema 06+14+25 + 处女节点 19）、
  易失 Redis 16379（独立 `auth001c-w3-*` 前缀）、Snowflake 经 PLAT-002 真实组件
  （测试范围验证器；见 §4.4 环境事实）、HTTPS(8443, 自签 localhost 证书) 满足前端
  `HTTPS_ORIGIN_REQUIRED` 约束。真实凭据经 git-ignored `application-local.yml`
  （自主目录字节复制，从未读取/打印/提交内容；仅 grep 过公开 app-id 与前端一致）。
- 前端：工作树 `PET_C_API_ORIGIN=https://localhost:8443 npm run build:weapp`；
  工作树 `project.config.json` 本地改为真实 appid + `urlCheck:false`（均不提交）。
- 微信开发者工具 2.02.2608060（D:\soft，已登录 Morii文瀚），经 wechatide CLI 自动化。
- PR36 的凭据打包排除（pom exclude `application-local.yml`）按设计生效：凭据只能经
  `--spring.config.additional-location` 运行时注入，本次验证该路径可用。

### 4.2 证据类型声明（不混淆）

- **A 真实微信身份链路**：模拟器真实 `wx.login` code → 后端真实 `WechatMiniApiProvider`
  → api.weixin.qq.com code2session（真实凭据）→ 账号/身份落库。**已验证**。
- **B 手机号授权组件（getPhoneNumber 原生手势）**：模拟器中自动化 tap/trigger 与
  OS 级真实点击均无法唤起原生授权（见 §4.4-3）。**未验证，真机仍挂账**（与 PR38 一致）。
- **C 替身链路（后端固定 code 双身 Provider，同 CAuthHttpTest 替身）**：登录→会话→
  昵称→宠物 CRUD→导航→错误态→登出的 **UI→HTTP→DB 全链路**。**已验证**。
- C 类证据不得表述为“真实微信链路验收通过”；A 类证据不等于 B（手机号组件）通过。

### 4.3 逐项结果

| # | 验收项 | 结果 | 证据（类型） |
|---|---|---|---|
| 1 | 登录会话建立 | PASS | A：真实 code 登录至 VERIFY_PHONE，user_account 93911382155669504 + user_auth_identity(WECHAT_MINI, app_id=wx1a64646b1d75306e, 真实 openid) 落库（截图 02；库已清理）。C：替身 code 完整 SessionGrant，`已登录 · 138****1111`（截图 04） |
| 2 | 会话恢复 | PASS | C：`#c-session-query` → GET /auth/session 200，状态保持已登录；Redis session key 存在 |
| 3 | 会话过期/失效行为 | PASS | C：旧 token（服务重启后）查询 → 「登录或授权已失效，请重新登录」，客户端清理回未登录；登出后 Redis session 键删除 |
| 4 | 昵称保存（资料页） | PASS | C：`#profile-nickname` 输入→保存→DB `user_account.nickname='W3联调昵称'`（截图 05） |
| 5 | 宠物列表读取 | PASS | C：空列表态（截图 06）与 1 只态（截图 08）均正确；重进页面从服务器读取 |
| 6 | 宠物添加 | PASS | C：表单（action sheet 选 DOG、日期 Picker、性别、体重两位小数）→ POST → DB user_pet 全字段正确（Doubo/DOG/Corgi/2023-05-01/MALE/12.50/ACTIVE，截图 07） |
| 7 | 宠物详情 | PASS | C：列表卡进详情页路由与展示（截图 09） |
| 8 | 宠物编辑 | PASS | C：改名保存 → PUT → DB name=DouboV2、version 0→1（乐观锁递增） |
| 9 | 宠物删除 | PASS | C：长按+确认 → DELETE → DB status=DISABLED（软删除），列表回 0 只 |
| 10 | 底栏导航 | PASS | C：pet 页 5 个 tab（home/services/community/messages/mine）齐全；点击未接入项显示「“服务”页面尚未接入本次预览」，不误导航 |
| 11 | 后端不可用错误态 | PASS | C：杀停后端→重进列表→load-error 态 + 「重新加载」按钮（截图 10）；后端恢复→重新加载成功（截图 11） |
| 12 | 登出 | PASS | C：`#c-logout` → 「已退出登录」、状态未登录、Redis session 清除（截图 12） |

所有截图存于 [w3-miniapp-evidence/](w3-miniapp-evidence/)；关键 DB 行已在上表登记
（联调库为随机隔离库，验收后已 DROP；Redis w3 前缀键已清空）。无真实密钥与真实用户
敏感数据入库（openid/手机号仅在联调库短暂存在并随库销毁；本报告只留掩码形式）。

### 4.4 联调过程事实登记（非虚报）

1. **Snowflake 长时运行续租超时（环境事实，非新缺陷）**：本地 harness 两次观察到
   `HutoolSnowflakeIdProvider` renew 触发 `OPERATION_TIMEOUT` fail-closed（11:00:33、
   11:17:00，启动后 10 秒内/类加载竞争期）。这是组件已登记语义（1 秒预算、fail-closed、
   lane 关闭后实例不自愈，生产由宿主重启承接——PLAT-002 HANDOFF/收尾报告已记录）。
   id-core 41 项测试（含续租回归）全绿不受影响。harness 采用“本节点租约过期后重建
   provider 实例”的测试范围自愈代理维持长会话，**不构成产品代码改动**。此事实再次
   提示 PLAT-002 生产宿主退出证明/恢复流程是启用前置。
2. **CI 与本地差异**：CI 无 pet-boot 环境性失败（npm ci 步骤在 CI 内置）；本地补齐后等价。
3. **DevTools getPhoneNumber 限制**：模拟器中该原生组件无法被自动化（automator tap、
   trigger、真实 OS 点击均无效，无授权弹窗）。PR36 历史真实手机号授权回执不因此复现；
   真机验收保留挂账。
4. **automator tap 失效差异**：部分按钮 automator `tap` 动作无效，须 `trigger --type tap`
   （PR38 测试脚本同样如此处理）；`wx_api mock` 在 `simulator_open_page` 重编译后失效，
   需重设。均为工具层事实。
5. **控制台 GBK 乱码**：本机终端显示中文参数乱码为编码显示问题；DB 内值验证正确。

## 5. PLAT-005 DoD 核对（按原 Issue AC 与 PR21 证据）

| AC/DoD 项 | 核对结果 |
|---|---|
| AC1 响应包裹/错误码/头行为与 HTTP10、Error12 一致；AdminAuth 行为不变 | 满足：ApiEnvelopeTest/PublicContractChecksTest 于 CI 真跑；本次联调实测 401 `COMMON_UNAUTHORIZED` 包裹 + data:null + traceId |
| AC2 每请求 traceId 入 MDC、X-Trace-Id 回显、请求后无泄漏 | 满足：TraceContextFilterTest 绿；联调响应头实测 `X-Trace-Id: c3db532e-…` |
| AC3 未知异常 500 COMMON_INTERNAL_ERROR、日志仅异常类名 | 满足：GlobalApiHandlerTest 绿；联调期 ID 组件故障时服务端日志仅 `Unhandled C-end exception: java.lang.IllegalStateException`（类名，无业务载荷），客户端收到 500 包裹 |
| AC4 MockMvc 独立覆盖、无新外部依赖 | 满足：TraceContextFilterTest/GlobalApiHandlerTest 均无 DB/Redis |
| Required Tests（TraceContextFilterTest、GlobalApiHandlerTest、pet-common 包裹单测） | 全部存在且绿（pet-boot 25 项含两组；pet-common 79 项含 ApiEnvelopeTest） |
| 21 号补充获批合入 | 满足：`docs/02-architecture/21-可观测与日志基线补充-v0.1.md` 已在 develop |
| CI 六 job 通过 + 人工合并 | 满足：PR21 已于 2026-09-15 人工合并；当前基线 CI 六项成功 |
| ARCH001~005 持续通过 | 满足：本次 W3 全量验证同口径通过 |
| 运维采集/告警（Loki/ELK） | 不属本 Issue（原 Issue 明示留运维阶段），不计缺口 |

**结论：PLAT-005 可关闭**（Catalog Status 可由 IN_PROGRESS 登记为完成；缺口清单：无）。

## 6. 生产门禁巡检（只确认、不启用）

| 门禁 | 巡检结果 |
|---|---|
| C 端装配 `pet.auth.c.enabled` | 默认 `${AUTH_C_ENABLED:false}` 关闭；真实 Provider 缺凭据拒绝启动（本次凭据经运行时注入，未入库） |
| 运营装配 `pet.auth.admin.enabled` | 无任何配置默认 true；admin 迁移另需 `maintenance=true` 且仅允许隔离 `auth001_*` 目标库 |
| Outbox 装配 `pet.outbox.enabled` | 默认关闭，且双重门禁 `@ConditionalOnBean(SnowflakeIdGenerator)`；空消费者注册保持 dispatcher 空转 |
| AsyncTask 生产装配 | pet-boot main 无任何 worker 装配（与“生产装配仍缺”一致） |
| Snowflake 生产装配 | 仅 Admin 配置内可选装配；`PreviousJvmExitVerifier::rejecting` 缺省拒绝；可信宿主退出证明仍未提供 |
| 默认 Flyway | `classpath:db/migration` 仅 README.md；`db/admin-auth-migration/V26` 在独立 location，仅 admin 迁移显式开启时执行 |
| MyBatis 迁移对门禁的影响 | 无：六模块仅持久层实现替换，未新增任何 enabled 配置/迁移/装配 |

## 7. 缺陷与差异登记

| # | 级别 | 内容 | 处置 |
|---|---|---|---|
| 1 | 环境性 | 本地首轮 pet-boot 失败（缺 npm ci 步骤） | 已补齐复跑全绿；CI 内置该步骤，无代码改动 |
| 2 | 文档勘误 | PLAT006 迁移报告 §8“8 个 XML”应为 10（按其自身列举 1+4+2+1+1+1） | 本报告登记；不改历史报告原文 |
| 3 | 环境事实 | Snowflake 续租 1s 预算在本机长时运行两次 fail-closed（语义已登记，实例不自愈） | 登记；强化 PLAT-002 生产宿主恢复流程前置必要性；不修改组件 |
| 4 | 工具限制 | 模拟器无法自动化 getPhoneNumber 原生授权 | 真机验收继续挂账（C-002/AUTH-001 边界不变） |
| 5 | 工具差异 | automator tap 需 trigger 代替；open_page 后 mock 失效需重设 | 联调方法登记（本报告 §4.4-4） |

无“本次必须修”的产品代码缺陷；**验收代码零改动**。

## 8. 遗留风险与建议下一步

1. 真机验收（物理设备键盘/授权/getPhoneNumber/跨设备字形）仍未执行——PR38/C-002 挂账不变。
2. 正式环境最小联调前置（PR38 报告所列）：可信退出证明测试宿主、受审迁移、独立易失会话、
   已授权 HTTPS 域名——均未具备。
3. MER（OD-W0-002 签约裁决）仍是 MER-001/SVC-001 启动前置，建议用户尽早裁决。
4. PLAT-002 生产启用路径（宿主退出证明、节点/高水位恢复、生产迁移）建议单列阶段任务。
5. W3 基线号：**37350d0**；建议后续 PR 均以本基线为起点。
