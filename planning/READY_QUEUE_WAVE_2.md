# Ready Queue — Wave 2 候选

状态：PLAN_REVIEW，未启动。下面“阶段就绪”仅表示获批启动后可做该阶段；Catalog的BLOCKED表示完整Issue尚不满足所有实现/验收门禁。不能将SPEC_READY解释成整项业务实现READY。

| Issue / Owner | 首个可执行阶段 | 阶段状态 | 完整实现的解除条件 | 关键测试 |
|---|---|---|---|---|
| PLAT-002 / Backend Core | 公共ID/Clock/金额/CommandContext约定与CCR-W2-IDEMP-001草案 | SPEC_READY | 幂等事实/事务/迁移获批，公共接口固定 | ORD-011/013阶段映射；W2-IDEM-001～005 |
| PLAT-004 / Backend Core | 使用已批准Schema建立Worker/Lease | CONDITIONAL | PLAT-002先交接ID/Clock固定接口；pet-boot独占；MySQL测试可运行 | TASK-001/002基础映射；W2-TASK-001～004 |
| AUTH-001 / Backend Core | CCR-ACR-001/PERM-001具体规范与Mock样例 | SPEC_READY | Contract Owner审批；签约相关OD-W0-002；公共组件及pet-user Owner交接 | MINI002～004、WEB002/PERM；W2-AUTH-001～004 |
| PLAT-003 / Backend Core | CCR-W0-001逐字段映射与事务方案 | SPEC_READY | Outbox CCR获批、公共ID/Clock、pet-boot独占 | TASK005/CON020/FLT012；W2-OUTBOX-001～003 |
| USR-001 / Backend Core | 宠物查询/写入契约草案 | DEPENDENCY_BLOCKED | PLAT002完成；CCR-W2-API-001用户域获批；AUTH/USR文件隔离 | W2-USR-001～004，宠物快照前置 |
| MER-001 / Backend Core | 商家/门店/人员DTO及资格事实映射 | DEPENDENCY_BLOCKED | PLAT002、用户/准入规范；真实下线事件需PLAT003；签约相关OD002 | MER001/ORD009/010阶段映射；W2-MER-001～003 |
| SVC-001 / Backend Core | 可预约资格/快照查询契约草案 | DEPENDENCY_BLOCKED | MER001交接及CCR-W2-API服务域获批 | ORD008阶段映射；W2-SVC-001～003 |
| C-002 / C-End | 原稿页面/状态/资产与契约盘点 | INPUT_BLOCKED | CCR-ACR、用户域API、V1节点/原图及差异确认；整项真实完成需AUTH+USR | MINI004/VIS001～004；W2-FE-001～005 |
| C-003 / C-End | 查询页原稿/契约盘点 | INPUT_BLOCKED | C-End释放C002；商家/服务/可用性契约及原图，真实查询需MER/SVC/SCH | MINI004/VIS及W2-FE；不做C004支付 |
| M-002 / Merchant | 准入/页面/接口缺口盘点 | INPUT_BLOCKED | 商家Figma/资产；CCR-ACR/API；签约及AUTH/MER/SVC/SCH事实 | MINI002～004/VIS及W2-FE |
| A-002 / Admin | 已批准单运营动作矩阵/页面状态与API盘点 | INPUT_BLOCKED | CCR-PERM/API治理域；真实接口需ADM001，签约子项待OD002 | WEB002/PERM002/005及W2-FE |

## 启动与并行规则

第一项建议派发PLAT-002的已界定规范阶段；AUTH/Outbox规范可在Backend Core角色释放后接续。PLAT-004不是缺Schema而阻断，等待公共ID/Clock交接属于避免重复公共实现的工程门禁。公开规范草案文件可在所属Issue中写planning/ccr，不越权直接改保护Contract。

不存在“11项都已READY”。C/M/A尚缺输入时只参加已启动后端Issue的只读契约协作；不单开未就绪的长生命周期页面任务。QA和Transaction参与这些既有Issue的测试/审查，不新增Issue，不提前派发原交易或QA002～005。六岗位职责详见WAVE_2_PLAN.md。

完整Issue状态保持BLOCKED直到其执行门禁实际解除；阶段完成只登记里程碑，不减少原Scope或测试。获批Contract Mock可独立推进页面阶段，不要求等待整个后端；真实E2E的完成门禁仍需对应服务实现。所有门禁解除都登记精确commit/规范版本/审批和素材证据。

PLAT-004公共交接补充：接口与明确测试替身固定可开始开发，但完整生产Worker交付必须采用PLAT-002实际Snowflake提供器和Clock装配并验证；不以空接口判DoD，也无需等公共幂等整项DONE。
