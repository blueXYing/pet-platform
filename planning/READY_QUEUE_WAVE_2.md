# Ready Queue — Wave 2 当前阶段台账

更新：2026-09-23（当前状态）：服务展示轮**PR66~75全部合入** develop `c8d0c10`（含PR74 QA文档、PR75门店列表匿名读修正），合并CI绿；**用户视觉验收通过**（见[VISUAL-ACCEPTANCE](progress/2026-09-23/VISUAL-ACCEPTANCE.md)）。仍开放的验证缺口：模拟器窗口级连续E2E（商家发布→运营审核→消费者看到）、服务审核通知投递与跳转走查、真机。下一段依赖链：核对可预约事实与契约缺口→SCH-001→SCH-002→SCH-003→TX-001（[依赖图](../DEPENDENCY_GRAPH.md)）。本表区分完整Issue与已交付切片，不将局部验收升级为DONE。证据与遗留裁决清单见[2026-09-23收官记录](progress/2026-09-23/ROUND-CLOSEOUT.md)。

| Issue / Owner | 已合入阶段 | 下一步需明确的范围/前置条件 | 原验收映射 |
|---|---|---|---|
| PLAT-002 / Backend Core | PR10/11公共约定与S1，PR13/16 Hutool适配与组件 | 真实宿主退出证明、节点/高水位恢复、迁移/生产装配；完整幂等范围逐项确认 | ORD-011/013；W2-IDEM-001～005 |
| PLAT-004 / Backend Core | Worker/Lease组件，S8/S9私有材料producer/handler接入已有证据 | 其他业务producer/handler、DEAD对账/告警及生产运维装配仍待完成 | TASK-001/002；W2-TASK-001～004 |
| AUTH-001 / Backend Core | 运营/C端认证与微信Provider、页面接入；S9微信手机号授权及真实手机登录已有证据 | 未实现SMS/密码/刷新、完整商家身份/准入页面、全设备键盘授权与正式环境门禁 | MINI002～004、WEB002/PERM；W2-AUTH-001～004 |
| PLAT-003 / Backend Core | Outbox组件及S5/S7申请审核事件、强制站内通知接入 | 其余业务接入、生产ID/迁移/启用、对账告警和归档；默认关闭生产装配 | TASK005/CON020/FLT012；W2-OUTBOX-001～003 |
| USR-001 / Backend Core | PR22/23契约、PR24宠物域与幂等服务；PR31接入HTTP；W3模拟器昵称/宠物读改写 UI→HTTP→DB 联调通过（替身后端） | 生产ID/迁移启用；完整真实业务E2E与真机 | W2-USR-001～004；宠物快照前置 |
| MER-001 / Backend Core | PR50～57、60、59、64、61～63已合入；PR67交付C端门店读侧切片（CCR v0.2 STR-D1~D8：/c/stores两路由+第五查询+四条浏览路由匿名+城市目录+完整性runbook+SERVICE_COVER管线31号）；QA抽检PR65发现的两项契约漂移由PR66修复 | 成员/核销映射/在途守卫/冻结写动作及生产门禁；分类筛选/关键词搜索延后（STR-D7）；评分/月售/距离/收藏等无事实源字段不实现；完整Issue仍IN_PROGRESS | MER001/ORD009/010；W2-MER-001～003；W2-STR（门店读侧测试计划） |
| SVC-001 / Backend Core | PR65读切片已合入；PR66勘误修复（§3.3.1补写+404码对齐SERVICE_NOT_FOUND+测试双断言）；读侧兼容REVIEWING/REJECTED回归已随PR68交付 | 不提前实现预约交易；完整预约流程未验收（预约/订单/排期范围外） | ORD008；W2-SVC-001～003 |
| ADM-001 服务操作 / Backend Core | PR68服务写入方切片已合入（CCR v0.2：状态机五值/六命令幂等/OWNER门禁/运营审核四路由/审核事件Outbox/封面展示/33号Schema+V27迁移）；PR69通知消费侧已合入；PR71 boot接线在途 | 售罄/硬删除/批量通过/类目CRUD延后；强制下架通知待裁决（B2）；完整Issue其余治理范围按原Owner继续 | PERM002/005；W2-SVCW（测试计划） |
| C-002 / C-End | PR25编辑资料；PR34宠物页、公共底栏、分包和标题修正；PR38真实接口接入（W3模拟器联调通过，替身后端） | 独立编辑页设计、芯片/记录字段CCR、上传/更多页面与真机/VIS | MINI004/VIS001～004；W2-FE-001～005 |
| C-003 / C-End | PR72交付门店列表/商家详情/服务详情切片（设计源还原+契约Mock+共享层真实API联调取证；PR75补门店列表匿名读修正；均已合入）；服务卡片部分此前已随PR72分支先行 | 模拟器VIS/真机留用户人工验收；评分/月售/距离/收藏等无契约字段不实现；预约入口仅跳转（不做预约表单，C004范围外） | MINI004/VIS；不做C004支付 |
| M-002 / Merchant | 签约页/准入契约/工作台真实准入已合入（PR61～63）；PR72交付服务管理列表/新增编辑页（设计源10:5255/11:5768确认+V1裁剪+20条VIS-004差异登记+NAVIGATION-BASIS导航依据）；PR72/PR75均已合入，用户视觉验收通过 | 订单/排期/核销等其余工作台业务页、子账号、真机与VIS | MINI002～004/VIS；W2-FE |
| A-002 / Admin | PR59运营入驻审核页面核心切片已交付并合入，真实浏览器方案b合成申请链路通过 | 生产HTTPS入口、完整视觉验收及其余商家/服务/排期/员工治理页面（service_item写入方登记于"服务操作"范围，SVC-001提案§7）；不得把切片完成扩为整项DONE | WEB002/PERM002/005；W2-FE |
| PLAT-005 / Backend Core | PR21与W3原AC核验通过，Catalog已DONE | 运维采集按原Issue范围，不把历史“可关闭”当作当前待关闭 | ARCH001～005、PLAT005-FILTER/HANDLER |
| PLAT-006 / Backend Core | PR29裁决；六模块迁移 PR #39～#44 已合入，22号裁决回执已登记，DONE | 生产数据库迁移/启用与 PLAT-002 门禁另行授权 | ARCH001～005及既有MySQL回归（见[迁移报告](progress/2026-09-16/PLAT006_MIGRATION_REPORT.md)） |

原规划的11个候选仍保留；PLAT-005/006来自已合并PR21/29的后续人工授权，不是此次新增任务。OSS阶段见[同步报告](progress/2026-09-16/PROGRESS_SYNC.md)，不自行新增一个Catalog Issue。

## 执行纪律

先看[当前状态](../WORK_STATE.md)、[剩余门禁](BLOCKED_QUEUE.md)和对应Issue的Allowed/AC。已交付阶段不重复起草、重复索取已提供的Figma或重复请求同一产品裁决；未覆盖范围按原Owner继续。代码/Contract有唯一Writer，下一阶段范围与授权需要明确；本次只同步文档。

组件测试、Mock页面、真实数据库/HTTP测试、物理真机及生产启用分别记录。真实后端已具备部分链路，不等于C端页面已接通；预览完成也不等于完整交易E2E或完整Issue DONE。

## 当前接续顺序

签约/准入主链已收尾（12bdc38），下一步按NTF-001+C-006通知子切片推进消息读取与受控跳转，再按MER/SVC/SCH依赖推进服务与排期。真实手机提交与方案b合成申请审核分别取证。位置限制已由SSOT §28取消，成都目录保持；不重审已批准方案。
