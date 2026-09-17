# Work State

PROJECT: 宠物平台 V1.0
STATE_VERSION: 6.2
UPDATED_AT: 2026-09-16

CURRENT_PHASE: W2_WAVE_IN_PROGRESS
CURRENT_STATUS: STAGE_PRS_MERGED_INTEGRATION_PENDING
NEXT_PHASE: W3_INTEGRATION_REVIEW
NEXT_PHASE_APPROVED: NO

## 当前结论

Wave1 工程壳已经完成；Wave2 已按后续逐阶段授权实施，不能再以“尚未启动Wave2”重新派发已交付任务。2026-09-16 重新查询 GitHub：PR6～37 已合入 develop；本次核验基线为 `3dfb253e642880daecc815976d373f69e6df5e21`，包含 PR35 状态整理、PR36 凭据构建排除与 PR37 发号器回归核查。尚未发布 main 或生产环境。

本次用户已授权“C端登录、资料与宠物真实接口接入”有限切片，已实施候选代码与隔离验证，待本轮PR审阅。未更改产品规则、公共契约或生产发号安全门禁；不将切片交付标为完整Issue DONE。

候选交付：[PR38](https://github.com/blueXYing/pet-platform/pull/38)，未合并。64项前端单元、261项后端JUnit、架构/契约检查及微信构建/包体通过；模拟器真实页面路径5组Provider替身回归、显式预览14组回归及390窗口截图对照已记录。正式真实微信链路与完整VIS/真机验收仍未完成，详见下方接入交接与PR最终head检查。

2026-09-17 PLAT-006 收讫：六模块 MyBatis 迁移 PR #39～#44（含 SQL 全部位于 resources/mapper/*.xml 的审阅修订）经用户授权按序合并，已全部合入 develop（PR38 前置于 #39 合入）；整合组合 clean verify 通过，22号裁决完成回执已登记，PLAT-006 DONE。生产启用、数据库迁移与 PLAT-002 门禁不因此解除；证据见[迁移报告](planning/progress/2026-09-16/PLAT006_MIGRATION_REPORT.md)。

## 已合入的阶段

| 范围 | 已交付 | 尚未完成或未验收 |
|---|---|---|
| Wave1 / PR6～9 | 七个工程壳、收尾与Wave2规划 | 后续业务验收不由工程壳代替 |
| PLAT-002 / PR10、11、13、16 | 公共约定、S1接口、Hutool+MySQL节点协调组件 | 生产宿主退出证明、节点/高水位恢复、迁移及启用；完整公共幂等范围 |
| PLAT-004 / PR12 | Durable AsyncTask Worker/Lease组件 | producer、DEAD对账/告警、业务Handler、生产装配 |
| AUTH-001 / PR14、15、17、31、33、36 | 规范及取消MFA同步、运营登录、C端会话/HTTP链路、真实微信Provider；PR36历史回执记录真实微信登录、手机号绑定、会话查询、带会话宠物查询、登出及登出后401成功 | 页面真实业务闭环、SMS/密码/刷新等未实现范围、商家准入及生产配置；历史冒烟使用了未提交的本地宿主放行，不等生产准入 |
| PLAT-003 / PR18～20 | Outbox契约同步、事务发布/消费保护/分发恢复 | 业务生产者/消费者、默认关闭装配的生产启用、迁移及运维闭环 |
| PLAT-005 / PR21 | Trace/MDC、响应包裹、全局异常基座 | 原Issue整项DoD由Owner核对；不重做已合入基座，运维采集不属于本Issue新增范围 |
| USR-001 / PR22～24，PR31接入HTTP | 用户/宠物契约、归属/快照/软删除/幂等服务；带C会话的HTTP链路 | 生产ID/迁移启用，前端真实业务联调与完整E2E |
| C-002 / PR25、26、34 | 编辑资料代表页；宠物列表/详情/添加表单；四页共用底栏及分包检查 | 当前仍是显式预览数据；独立编辑页设计未提供；登录/上传/真实保存/更多页面及真机验收 |
| CCR-OSS-001 / PR27、28、30、32 | 资产注册表、同步工具、私有桶量化过期签名URL | 前端完整消费/缓存刷新、后台运营上传全流程和生产运维验收；凭据不入库 |
| PLAT-006 / PR29、39～44 | 统一MyBatis迁移：六模块全部转 MyBatis(XML mapper)，SQL/事务语义原样，回执已登记 | 生产数据库迁移、生产启用与 PLAT-002 门禁仍待后续授权 |

PR6～34证据及逐PR合并提交见[历史同步报告](planning/progress/2026-09-16/PROGRESS_SYNC.md)；PR35/36当前事实与发号器核查见[整合收尾报告](planning/progress/2026-09-16/ID_INTEGRATION_CLOSEOUT.md)。商家、服务、排期、交易、治理页面等未因上述合并自动完成。

## 你现在看哪里

1. 当前进度看本页；操作和阅读顺序看[开发流程指南](05-开发流程与文档阅读指南.md)。
2. 可继续的阶段及前置检查见[Ready Queue](planning/READY_QUEUE_WAVE_2.md)；未解决项见[Blocked Queue](planning/BLOCKED_QUEUE.md)。
3. 用户/商家Figma来源已具备，原始素材只存本地；见[设计来源与保管规则](planning/DESIGN_SOURCES.md)。不重复索取已有链接或把原始资料整包上传。

当前候选已接入微信登录/手机号授权、会话查询/受保护请求/登出、资料查询及昵称保存、宠物列表/详情/新增/编辑/删除。详见[C端接入交接](planning/progress/2026-09-16/C_REAL_API_INTEGRATION.md)。性别/签名、芯片/医疗记录及头像上传继续沿用CCR；完整真实业务验收仍受可信退出证明、节点/高水位恢复与迁移启用门禁约束。本轮没有重跑真实微信或使用PR36临时放行；MyBatis全量迁移、商家、交易及生产发布不在范围。

## 验证与边界

- 最新已合并基线 3dfb253 的 **push / 合并后** [CI 35074218261](https://github.com/blueXYing/pet-platform/actions/runs/35074218261)六项成功。本轮分支验证单列于[C端接入报告](planning/progress/2026-09-16/C_REAL_API_INTEGRATION.md)及PR，不能用历史CI代替。
- PR36的池化连接时区推测未在当前基线复现：SET、查询和写入已处于同一个事务绑定连接。新增真实Hikari多连接、非UTC会话及发号/续租回归；没有虚构生产代码修复。受控续租ACK延迟触发OPERATION_TIMEOUT并保持fail-closed，历史Docker偶发卡顿根因仍未确定，1秒预算不变。
- CI通过不等于真实微信端到端、物理设备键盘/授权、全产品VIS、资金/支付退款闭环或生产启用通过。各Owner原交接中的未验收项保留，并按后续PR补齐事实解释。
- PR25 原视觉接受只绑定当时页面与证据；PR34 后续底栏改造、标题修正和差异见[C端最新交接](planning/issues/wave-2/C-002-pet-page/HANDOFF.md)，不扩写为全部C端验收完成。
- OD-W0-001资金、OD-W0-002签约、人工客服承载等未决项继续有效；迟到支付、退款/核销互斥、单运营及取消额外MFA规则不变。

## 历史与本轮范围

旧远端状态见[历史快照](planning/history/WORK_STATE_BEFORE_20260916_SYNC.md)，未推送的本地阶段记录见[本地历史快照](planning/history/WORK_STATE_LOCAL_THROUGH_20260915.md)。历史中的“尚未批准/待merge”等不得覆盖本页当前事实。

本轮唯一Writer为当前C端接入任务，独立分支 `codex/c-real-api-integration-20260916`，从最新 origin/develop 3dfb253 创建。范围限已批接口的现有页面接入、隔离回归与交接；无子代理或其他开发会话。未更改后端生产实现、Schema/API/Event/Scheduler或Issue AC，未新增JDBC持久层。主目录用户现有 `frontend-miniapp/project.config.json` 修改保留。AUTH-001、USR-001、PLAT-002、C-002及相关CCR不整体完成；本轮PR不自行合并。
