# Work State

PROJECT: 宠物平台 V1.0
STATE_VERSION: 6.5
UPDATED_AT: 2026-09-17

CURRENT_PHASE: W3_INTEGRATION_REVIEW
CURRENT_STATUS: MER001_CONTRACT_REVIEW_REQUIRED
NEXT_PHASE: MER-001 契约审阅、权威同步与领域实施
NEXT_PHASE_APPROVED: NO

## 当前结论

2026-09-17 MER-001产品确认：用户已明确“不新增强制重签”“下线后旧订单继续履约”，已登记[决定回执](planning/ccr/CCR-W2-API-001/merchant-product-decisions.md)，覆盖下方关于这两项仍待确认的阶段描述。冻结写动作仍未决；技术契约尚未明确批准，PR50保持草稿且未获合并授权。SSOT/PRD文字同步尚未执行，不重复索取已确认的产品决定。

2026-09-17最新推进：用户已授权开始MER-001，从远端develop `609153b`（PR49）创建独立分支 `codex/mer001-contract-20260917`，唯一Writer为当前任务。已形成[商家域契约审阅包](planning/ccr/CCR-W2-API-001/merchant-domain-proposal.md)：三查询/员工接口、成员与服务人员分离、电子协议、存储映射、错误/幂等和22项未来验收设计。状态REVIEW_REQUIRED，尚未修改权威Contract或业务代码。需确认协议换版政策，以及SSOT §26“下线仅可查看”与§15存量履约/售后继续的冲突；OD-W0-002外部签约阻塞不恢复。当前NEXT_PHASE_APPROVED:NO指尚未批准的新Contract同步/实施，不否定本次契约阶段的启动授权。主目录用户project.config.json保持原样。

Wave1 工程壳已完成；Wave2 已按逐阶段授权实施完毕（PR6～#44 全部合入）。2026-09-17 W3 整合验收已执行：核验基线 **develop `37350d0`**（PR45 合并后，CI 六项成功，无在途 PR）；后端全量 261 项 JUnit（34 套件，含 22 项 ArchUnit）0 失败/跳过，逐模块计数与各 PR 合入证据一致；MyBatis 迁移组合事实（10 个 mapper XML 入构建产物、classpath*: 装配、生产主代码仅剩 boot Flyway 校验一处 java.sql）与 22 号裁决回执相符；生产门禁（pet.auth.c/admin、pet.outbox、task 装配、Snowflake rejecting、默认 Flyway 目录）全部确认默认关闭。验收详情见[W3 整合验收报告](planning/progress/2026-09-17/W3_INTEGRATION_ACCEPTANCE.md)（分支 `codex/w3-integration-acceptance-20260917` 的 PR 待人工审阅合并）。

小程序 PR38 链路模拟器联调（W3 新证据）：真实凭据下真实 wx.login→code2session→账号/身份落库链路验证通过（证据类型 A）；后端固定 code 替身下登录会话/恢复/失效、昵称保存、宠物读改写全链路、底栏导航、后端不可用错误态、登出全部 UI→HTTP→DB 验证通过（证据类型 C）。模拟器无法唤起 getPhoneNumber 原生授权，真机验收继续挂账；替身证据不表述为真实微信链路通过。

PLAT-005 DoD 按原 AC 与 PR21 证据逐项核对：全部满足，**可关闭**（见验收报告 §5）。验收代码零改动；主目录用户本地 `frontend-miniapp/project.config.json` 保留未动。

2026-09-17 合并回执：经用户指示，[PR46](https://github.com/blueXYing/pet-platform/pull/46)（W3 验收文档，合并提交 426ed3b）与 [PR47](https://github.com/blueXYing/pet-platform/pull/47)（OD-W0-002 商家入驻签约裁决：电子协议+勾选同意，合并提交 fd1fca3，含与 #46 的相邻行冲突本地解决）已先后合入 develop；两合并头 CI 六项均 success，**当前基线 `fd1fca3`**。OD-W0-002 RESOLVED，MER-001/M-002/A-002 的签约阻塞解除，MER-001 可派发（契约冻结按 CCR，协议版本管理子项随 MER-001 契约确认）。

尚未发布 main 或生产环境；PLAT-002 生产启用、数据库迁移与 PLAT-002 门禁不因 W3 验收解除。

## 已合入的阶段

| 范围 | 已交付 | 尚未完成或未验收 |
|---|---|---|
| Wave1 / PR6～9 | 七个工程壳、收尾与Wave2规划 | 后续业务验收不由工程壳代替 |
| PLAT-002 / PR10、11、13、16 | 公共约定、S1接口、Hutool+MySQL节点协调组件 | 生产宿主退出证明、节点/高水位恢复、迁移及启用；完整公共幂等范围 |
| PLAT-004 / PR12 | Durable AsyncTask Worker/Lease组件 | producer、DEAD对账/告警、业务Handler、生产装配 |
| AUTH-001 / PR14、15、17、31、33、36、38 | 规范及取消MFA同步、运营登录、C端会话/HTTP链路、真实微信Provider；PR38页面接入已合入；PR36历史回执+W3模拟器真实 wx.login→code2session→账号落库链路（类型A）与替身全链路（类型C）见[W3验收](planning/progress/2026-09-17/W3_INTEGRATION_ACCEPTANCE.md) | 真机 getPhoneNumber/键盘/授权、SMS/密码/刷新等未实现范围、商家准入及生产配置；历史冒烟与W3联调均用测试范围宿主，不等生产准入 |
| PLAT-003 / PR18～20 | Outbox契约同步、事务发布/消费保护/分发恢复 | 业务生产者/消费者、默认关闭装配的生产启用、迁移及运维闭环 |
| PLAT-005 / PR21 | Trace/MDC、响应包裹、全局异常基座；W3 按原 AC/DoD 核对全部满足，**可关闭**（[W3验收 §5](planning/progress/2026-09-17/W3_INTEGRATION_ACCEPTANCE.md)） | 无缺口；运维采集按原 Issue 留运维阶段 |
| USR-001 / PR22～24，PR31接入HTTP | 用户/宠物契约、归属/快照/软删除/幂等服务；带C会话的HTTP链路；W3 已在模拟器完成昵称/宠物读改写 UI→HTTP→DB 联调（替身后端） | 生产ID/迁移启用，完整真实业务E2E与真机 |
| C-002 / PR25、26、34 | 编辑资料代表页；宠物列表/详情/添加表单；共用底栏及分包检查；PR38 后页面已接真实接口（W3 模拟器联调通过） | 独立编辑页设计未提供；性别/签名/上传/更多页面、完整VIS与真机验收 |
| CCR-OSS-001 / PR27、28、30、32 | 资产注册表、同步工具、私有桶量化过期签名URL | 前端完整消费/缓存刷新、后台运营上传全流程和生产运维验收；凭据不入库 |
| PLAT-006 / PR29、39～44 | 统一MyBatis迁移：六模块全部转 MyBatis(XML mapper)，SQL/事务语义原样，回执已登记 | 生产数据库迁移、生产启用与 PLAT-002 门禁仍待后续授权 |

PR6～34证据及逐PR合并提交见[历史同步报告](planning/progress/2026-09-16/PROGRESS_SYNC.md)；PR35/36当前事实与发号器核查见[整合收尾报告](planning/progress/2026-09-16/ID_INTEGRATION_CLOSEOUT.md)。商家、服务、排期、交易、治理页面等未因上述合并自动完成。

## 你现在看哪里

1. 当前进度看本页；操作和阅读顺序看[开发流程指南](05-开发流程与文档阅读指南.md)。
2. 可继续的阶段及前置检查见[Ready Queue](planning/READY_QUEUE_WAVE_2.md)；未解决项见[Blocked Queue](planning/BLOCKED_QUEUE.md)。
3. 用户/商家Figma来源已具备，原始素材只存本地；见[设计来源与保管规则](planning/DESIGN_SOURCES.md)。不重复索取已有链接或把原始资料整包上传。

当前候选已接入微信登录/手机号授权、会话查询/受保护请求/登出、资料查询及昵称保存、宠物列表/详情/新增/编辑/删除。详见[C端接入交接](planning/progress/2026-09-16/C_REAL_API_INTEGRATION.md)。性别/签名、芯片/医疗记录及头像上传继续沿用CCR；完整真实业务验收仍受可信退出证明、节点/高水位恢复与迁移启用门禁约束。本轮没有重跑真实微信或使用PR36临时放行；MyBatis全量迁移、商家、交易及生产发布不在范围。

## 验证与边界

- W3 基线 37350d0 合并后 [CI 35173601495](https://github.com/blueXYing/pet-platform/actions/runs/35173601495) 六项成功；W3 本地全量验证（261 JUnit/架构/契约同口径）见[验收报告](planning/progress/2026-09-17/W3_INTEGRATION_ACCEPTANCE.md)。
- PR36的池化连接时区推测未在当前基线复现：SET、查询和写入已处于同一个事务绑定连接。受控续租ACK延迟触发OPERATION_TIMEOUT并保持fail-closed；W3 本地长时运行再次观察到该语义触发（启动类加载竞争期两次），实例不自愈、生产由宿主重启承接，1秒预算不变。
- CI通过不等于真实微信端到端、物理设备键盘/授权、全产品VIS、资金/支付退款闭环或生产启用通过。W3 模拟器联调的 A/C 两类证据边界见验收报告 §4.2；真机与正式环境链路仍挂账。
- PR25 原视觉接受只绑定当时页面与证据；PR34 后续底栏改造、标题修正和差异见[C端最新交接](planning/issues/wave-2/C-002-pet-page/HANDOFF.md)，不扩写为全部C端验收完成。
- OD-W0-001资金、OD-W0-002签约、人工客服承载等未决项继续有效；迟到支付、退款/核销互斥、单运营及取消额外MFA规则不变。

## 历史与本轮范围

旧远端状态见[历史快照](planning/history/WORK_STATE_BEFORE_20260916_SYNC.md)，未推送的本地阶段记录见[本地历史快照](planning/history/WORK_STATE_LOCAL_THROUGH_20260915.md)。历史中的“尚未批准/待merge”等不得覆盖本页当前事实。

本轮（W3 验收）唯一Writer为整合验收任务，独立分支 `codex/w3-integration-acceptance-20260917`，从 origin/develop 37350d0 创建。只做验收与文档登记：后端全量验证、MyBatis 组合抽查、PR38 模拟器联调（未提交的本地测试 harness，不入库）、PLAT-005 DoD 核对与门禁巡检；无代码改动、无生产启用/迁移、无 Schema/API/Event 变化。主目录用户现有 `frontend-miniapp/project.config.json` 修改保留。本轮PR不自行合并。
