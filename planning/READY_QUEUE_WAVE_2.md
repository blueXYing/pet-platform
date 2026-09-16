# Ready Queue — Wave 2 当前阶段台账

更新：2026-09-16，核验基线 develop `b91a014`（PR36后）。Wave2 已有逐阶段实现与合并；旧“PLAN_REVIEW，未启动”不再描述当前情况。下表区分已合入阶段与剩余范围，不直接把 Catalog 的完整Issue状态改为 DONE，也不因本次文档同步自动派发新开发任务。发号器池化时区/续租核查与本次验证边界见[整合收尾报告](progress/2026-09-16/ID_INTEGRATION_CLOSEOUT.md)。

| Issue / Owner | 已合入阶段 | 下一步需明确的范围/前置条件 | 原验收映射 |
|---|---|---|---|
| PLAT-002 / Backend Core | PR10/11公共约定与S1，PR13/16 Hutool适配与组件 | 真实宿主退出证明、节点/高水位恢复、迁移/生产装配；完整幂等范围逐项确认 | ORD-011/013；W2-IDEM-001～005 |
| PLAT-004 / Backend Core | PR12 Worker/Lease组件 | producer调度、DEAD对账/告警、业务Handler及生产装配；不再说ID接口尚未交接 | TASK-001/002；W2-TASK-001～004 |
| AUTH-001 / Backend Core | PR14/15规范，PR17运营Web，PR31 C会话/HTTP，PR33微信Provider；PR36真实微信/手机号/会话/宠物查询/登出历史冒烟与配置修复 | C端页面真实接入及完整E2E、正式发号宿主/环境门禁；SMS/密码/刷新与商家准入另按既有门禁处理 | MINI002～004、WEB002/PERM；W2-AUTH-001～004 |
| PLAT-003 / Backend Core | PR18/19规范与权威映射，PR20 Outbox组件 | 生产ID/迁移、业务事件生产者/消费者、对账告警和归档策略；装配默认关闭 | TASK005/CON020/FLT012；W2-OUTBOX-001～003 |
| USR-001 / Backend Core | PR22/23契约、PR24宠物域与幂等服务；PR31接入HTTP | 生产ID/迁移启用；前端真实联调、订单等后续完整业务验收 | W2-USR-001～004；宠物快照前置 |
| MER-001 / Backend Core | 本轮未发现对应实现PR | 商家/门店/人员契约、准入/签约事实与Owner交接；OD-W0-002仍有效 | MER001/ORD009/010；W2-MER-001～003 |
| SVC-001 / Backend Core | 本轮未发现对应实现PR | MER交接、服务域契约及资格事实；不提前实现预约交易 | ORD008；W2-SVC-001～003 |
| C-002 / C-End | PR25编辑资料；PR34宠物页、公共底栏、分包和标题修正 | 独立编辑页设计、芯片/记录字段CCR、登录/上传/保存接入与真机/VIS；当前仍是预览页面 | MINI004/VIS001～004；W2-FE-001～005 |
| C-003 / C-End | 本轮未发现产品页交付PR | C-End文件所有权释放、商家/服务/可用性契约和原图；真实查询需MER/SVC/SCH | MINI004/VIS；不做C004支付 |
| M-002 / Merchant | 商家原稿已提供，导出保存在本地；无产品页交付证据 | V1布局/状态修订、AUTH/MER/SVC/SCH接口、签约事实及VIS | MINI002～004/VIS；W2-FE |
| A-002 / Admin | 运营工程壳之外无本轮治理页交付证据 | CCR-PERM治理动作及ADM接口，签约子项OD-W0-002；不新增内部双人审批 | WEB002/PERM002/005；W2-FE |
| PLAT-005 / Backend Core | PR21 Trace/MDC及统一响应/异常基座 | 不重做已合入基座；按原AC/证据核对整项DoD关闭，运维采集不是新增Issue范围 | ARCH001～005、PLAT005-FILTER/HANDLER |
| PLAT-006 / Backend Core | PR29统一MyBatis裁决；C登录前置已合入 | 迁移未实施；明确下一模块、唯一Writer与原有MySQL回归范围，不改业务行为 | ARCH001～005及既有MySQL回归 |

原规划的11个候选仍保留；PLAT-005/006来自已合并PR21/29的后续人工授权，不是此次新增任务。OSS阶段见[同步报告](progress/2026-09-16/PROGRESS_SYNC.md)，不自行新增一个Catalog Issue。

## 执行纪律

先看[当前状态](../WORK_STATE.md)、[剩余门禁](BLOCKED_QUEUE.md)和对应Issue的Allowed/AC。已交付阶段不重复起草、重复索取已提供的Figma或重复请求同一产品裁决；未覆盖范围按原Owner继续。代码/Contract有唯一Writer，下一阶段范围与授权需要明确；本次只同步文档。

组件测试、Mock页面、真实数据库/HTTP测试、物理真机及生产启用分别记录。真实后端已具备部分链路，不等于C端页面已接通；预览完成也不等于完整交易E2E或完整Issue DONE。
