# 已批准售后目录与确定冲突补充：独立 QA

日期：2026-10-01。Owner：独立 QA 子代理。状态：QA_COMPLETE。本报告只属于用户已批准的 `CCR-AFS-PAGE-OPTIONS-001`、`CCR-AFS-CONFLICT-001` 增量；旧页面切片验收见 `QA-HANDOFF.md`。本代理不修改实现、不提交、不 push；根协调负责集成与 PR。最终37个唯一后端验收用例通过，其中14个真实HTTP、23个真实域数据库回归；来源为下述三轮执行，不声称单次37项全绿。脱敏机器摘要见 `ccr-http-summary.json`。

## 审查范围与验证边界

- 目录：本人 MINIAPP 会话、AFS HTTP 默认关闭、严格无 query/body、四字段 envelope；代码和名称来自创建校验使用的同一完整配置；缺失/不一致失败关闭，无原始代码冒充名称，无 fixture 目录进入生产。
- 确定冲突：仅已确认 caseVersion/finalSetVersion 不匹配且事务未提交业务副作用时返回新的稳定 409 码；通用幂等争锁忙保留 `COMMON_CONFLICT`；权限复验失败不误归类为版本冲突；成功 UUID 原回执重放仍先于当前版本比较。
- C/M/A：收到确定冲突后退休对应原 UUID，重新读取权威详情/资格与 P4 历史终局，清理旧人工确认；读失败保持只读；下一次人工提交新 UUID。未知/忙/401/403/429 仍保留原 payload+UUID，仅显式重试，不自动发送。
- 并发：真实 HTTP 对相同版本的竞争写，只一个成功，loser 不新增证据/决定/transition/audit/outbox，不改状态与订单指针；成功请求同 UUID 回放不增加上述副作用。

## 初步审查

已读取本树 `AGENTS.md`、两个 CCR、Contract51、已有配置/HTTP/controller/domain 的实现与验收。初始树为 `382c65f`，两个 CCR 当时仍标 PROPOSED/NOT_IMPLEMENTED；已向根提示随正式冻结更新用户批准状态。已向后端 Owner 提示新确定冲突码的事务边界、`change` 中版本比较与动作当前授权的先后，以及 P4 缺必填证明与证明真正过期的区分。

最终实现审查：`commandAuthority` 在版本比较前验证当前动作，并在确定拒绝前再次复验；真正撤权返回401/403，授权版本变化保持 `COMMON_CONFLICT`。`SUCCEEDED` 原命令重放先于目录、内容审核和当前版本比较。创建与查询共用完整、不可变、按代码排序的 `ReasonPolicy` 配置，未添加任何生产默认选项或名称。

审查反馈已修正：目录首尾空白从 Java `strip` 与 JS `trim` 的不同定义收紧为统一 ECMAScript 集合，双方拒绝孤立 surrogate，允许64个有效码点的 paired emoji；目录 GET 拒绝原始空 query 及所有 body；并发败方增加状态日志、工单/ORDER版本与指针检查；冻结门店用例保持 USER ACTIVE，以验证域写授权优先于 CAS；Error12 原“后两项”改为明确命名耐久隔离错误。源码审查未发现出款裁决按钮、生产默认代码/名称或以QA目录回退。

小程序退休证明从实际失败请求内部获取完整 `Command` 快照，以 error 对象为 WeakMap 键，同时绑定用户/端/商家/店/目标/action slot；退休再次比较 path、method、body 和 UUID。迟到旧拒绝不能退休相同 payload 的新 UUID。存储先成功写入新 journal 再删除内存旧命令；存储失败保持旧命令，C捕获后继续锁定，M生产repository捕获后返回false。C/M确定冲突刷新期间关闭新写门禁、清旧详情/图片；读取失败不会沿旧版本继续处理。A重新读取全部历史终局、清除旧人工确认，再由人工提交新 UUID。

## 验证记录

独立 QA 小程序源码测试：`shared/tests/aftersale-api.test.ts`、`consumer/tests/aftersale-model.test.ts`、`merchant/tests/aftersale-controller.test.ts`，62 tests /62 pass /0 failure /0 skip，首次通过。使用本树实际模块及注入 transport；它们覆盖严格目录、身份变更迟到响应、当前选项/资格门禁、未决原 create 不随目录改变、确定与模糊冲突 UUID、迟到退休和存储失败。它们不是页面连接真实 HTTP 的闭环。

后端 Owner 的离线 Java21 unit/config：41 tests，0 failures/errors/skipped（Catalog3、Boundary8、Options MockMvc4、Configuration26）。QA最终重新独立读取对应四份 Surefire XML、数量及SHA256，与更新后的 `ccr-unit-summary.json` 全部匹配。首次扩展空 query MockMvc断言失败，是测试框架未保留空 queryString；修复为显式设置 servlet queryString 后通过。真实空 query 另由本批真实 HTTP 用例覆盖，MockMvc不冒充该证据。

Root/实现 Owner 的最终整包结果：miniapp252 pass、typecheck/weapp build/package pass；A66 pass、2个既有真实环境 opt-in skip；新 A 页面测试证明 caseVersion 和 finalSetVersion 拒绝后旧确认清空、全部新历史重读、下一次人工写使用新 UUID，及迟到旧409不能删除新journal；boundaries pass。A测试为实际App/源码UI加拦截HTTP响应，未连接真实后端。文档129 tests、smoke114 operations/69 writes/24AFS；架构脚本18 tests及3个source gates通过。这些不称为QA独立运行；本次独立测试限上一段及以下真实后端验收。

JAVA_HOME仅设为本进程的现有 `C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot`（21.0.11）；Maven3.9.12 `-o`，本批验收串行运行，无并发Maven。缓存mysql:8.4与redis:7.4-alpine，`--pull never`；Docker使用现有npipe engine，独立命名容器只映射127.0.0.1随机端口：MySQL49456、Redis49485。未下载/安装服务、未改已有密码/配置、未使用用户已有MySQL或其它项目Redis16389。

首轮5 suites于14:43:43完成，Maven BUILD FAILURE（19:54），失败只来自新suite。已有7项真实 HTTP及23项内部真实数据库回归全部通过、无skip；修改范围包括新的目录校验和动作授权入口，因此这些结果属于本增量的相关回归。仅Spring Boot+真实HTTP三套才称HTTP，另外两套直接调用真实域API/事务。逐份XML验证如下：

| Suite | tests / failures / errors / skipped | XML SHA256 |
|---|---|---|
| AfterSaleEvidenceHttpAcceptanceTest | 3 / 0 / 0 / 0 | `3d1902dfd6edd527bd4ad2b61ae0ba3db83ef2c4c6af1ca45e1a108410753f70` |
| AfterSaleHttpAcceptanceTest | 4 / 0 / 0 / 0 | `d1ca1fdfc026eb743b3a574b6317f04814fab763a93e87f35ce42cb14065b819` |
| AfterSaleSourceIntegrityAcceptanceTest | 2 / 0 / 0 / 0 | `c811349b7065f83268326eaa7d426807a452b52365e9bea3313cf1c602bf594c` |
| AfterSaleWorkflowAcceptanceTest | 21 / 0 / 0 / 0 | `536c06d8cfa86eaada8a61a180a7fda53382cbae1d27f30746b6c088fbf15609` |
| AfterSaleOptionsConflictHttpAcceptanceTest 首轮 | 6 / 3 / 1 / 0 | `2b4d56d120bd4cce1562a71808cd9dc5a92e1031ff3c13d8a8657d1a60a78762` |

首轮新增真实 HTTP suite 为6项，3 failures、1 error、0 skips；不能以41项 unit通过代替这一失败证据。两个目录请求被遗漏的 C Spring Security enabled GET白名单拒绝，通用403追踪字段导致 header/envelope不同，尚未进入目录 controller；实际生产缺陷为 `CSessionSecurityConfiguration` 缺新路由，修复仅在原AFS HTTP开启条件下加入新GET路由。关闭 HTTP入口真实返回403且controller未注册，首次测试错误期待404；修正按已有denyAll关闭语义，不开放关闭入口。权限竞争测试在AFS持有ADMIN授权revision行锁期间等待另一个RBAC写事务，造成测试自等待超时；修复fixture等待顺序，未改变生产锁或放宽HTTP timeout。

第二轮只重跑新增suite，于14:51:56完成（Maven 3:02），7 tests /1 failure /0 error /0 skip，XML SHA256 `10410e468a17f7b68c4a84988e74d226ab58b7005f0fb715cfbc28b5096aaccb`。6个其它用例通过：关闭入口403/noBean；缺label查询及创建503零admission；同版本竞争败方确定409且无业务副作用、成功原UUID重放、当前门店冻结403优先于CAS；真实RBAC竞争及撤权后原UUID403；同AFS事务revision故障整体回滚；P4缺证明400、过期finalSet409零副作用、原UUID不能换hash及新UUID成功。唯一失败是已认证目录用例的尾随 `?` 期望400却收到200；Java HttpClient发送前将空query归一化，未向服务端发送契约要测试的输入。该失败使同一case后续body/create/FROZEN/logout断言当时未执行，未提前计为通过。

第三轮仅重跑上述失败方法 `authenticatedCatalogIsSortedStrictAndUsesExactlyTheNewCreateCodes`，将 `?`/`?&&` 改用有界Socket发送完整原始HTTP/1.1 request-target；未改生产规则、未放宽400断言、未改变其余6项。14:57:48完成，Maven BUILD SUCCESS（37.614秒），1 test /0 failure /0 error /0 skip，XML SHA256 `b5248093207c8e492ccf37b547470c6bc9b6045a86fdc32aa8c7834e233c0f8a`。两种原始query均400；该case的目录排序、真实会话、所有body拒绝、查询/创建同目录、未知代码拒绝、FROZEN可读禁写及logout后401全部执行通过。最终按首轮旧30、第二轮成功6、第三轮成功1聚合37个唯一case，不覆盖或隐藏历史失败。

权限竞争证据明确区分：真实独立RBAC事务首先申请锁定授权revision行，被AFS持有行锁阻塞，AFS200提交后才完成撤权；原成功UUID再请求403且零额外副作用。同AFS bound DataSource内SQL人为revision+1为标注的 `FAULT_INJECTION`，验证COMMON冲突整体回滚，包括revision自身回滚；它不称为外部RBAC已提交的并发撤权。通用争锁忙保留COMMON的前端行为由源码/注入transport测试验证，本批不把此故障注入称为真实幂等争锁。

三轮源边界：均以原HEAD `382c65fa610d73ff7bd2a00cac53dbbcb7cf7dea` 上的已批准CCR未提交增量运行；首轮后生产只补新目录条件式安全白名单，其余生产域授权/目录/旧路由不变。第二轮后仅改失败目录方法的原始HTTP传输probe，其余6个case未改；旧30项的生产域/旧路由在后两轮未变，保留首轮结果，无冗余重跑。

临时容器精确身份：mysql `765f093d719440a85dd75facd2c3af264e72886870f4bc46049f7f04aa133e32`；redis `c836f9e29efc1d313185eed808fc6dfa0e13b29bd8f7ccf1b3d292684926cc5b`。最后fixture已清至仅4个MySQL系统库、Redis DBSIZE0；核对ID后仅移除本批两个容器/其匿名卷，49456/49485无监听，其它项目Redis16389继续运行。三轮原始XML/日志及 `mini-targeted.log` 仅在 `.cache/aftersale-ccr-qa/` 保留，不作为仓库业务文件提交；正式摘要无token、请求body、授权证明或私有资产内容。

## 未执行验收

本报告未声称新页面连接真实后端完成三端闭环、物理微信真机或 VIS 通过。外部微信登录、审核/扫描/OSS通过fixture provider隔离，使用真实本机HTTP、MySQL、Redis、事务和认证会话；非生产外部服务联调。本批非出款边界无资金产物，但不代表真实支付/退款渠道验收。生产选项内容与环境启用未由本次技术契约批准自动获得；后续CI/PR合并由根协调记录，本报告不以旧PR CI代替新增量CI。

本轮批准增量的独立审查已完成，剩余可复现实现/验收缺陷为0；上述未执行环境边界继续保留。最终summary已校验30+7数量、7个新case各自成功轮次/哈希来源、无重复case及无敏感字段模式；本树 `git diff --check` 通过。
