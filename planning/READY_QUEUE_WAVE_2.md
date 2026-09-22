# Ready Queue — Wave 2 当前阶段台账

更新：2026-09-22，PR61（MS-1签约页）、PR62（MS-2准入后端）、PR63（MS-3工作台准入）已顺序合入develop `12bdc38`，各PR六项CI全绿、MS-1/MS-3另有模拟器真实链路人工验收。本表区分完整Issue与已交付切片，不将局部验收升级为DONE。

| Issue / Owner | 已合入阶段 | 下一步需明确的范围/前置条件 | 原验收映射 |
|---|---|---|---|
| PLAT-002 / Backend Core | PR10/11公共约定与S1，PR13/16 Hutool适配与组件 | 真实宿主退出证明、节点/高水位恢复、迁移/生产装配；完整幂等范围逐项确认 | ORD-011/013；W2-IDEM-001～005 |
| PLAT-004 / Backend Core | Worker/Lease组件，S8/S9私有材料producer/handler接入已有证据 | 其他业务producer/handler、DEAD对账/告警及生产运维装配仍待完成 | TASK-001/002；W2-TASK-001～004 |
| AUTH-001 / Backend Core | 运营/C端认证与微信Provider、页面接入；S9微信手机号授权及真实手机登录已有证据 | 未实现SMS/密码/刷新、完整商家身份/准入页面、全设备键盘授权与正式环境门禁 | MINI002～004、WEB002/PERM；W2-AUTH-001～004 |
| PLAT-003 / Backend Core | Outbox组件及S5/S7申请审核事件、强制站内通知接入 | 其余业务接入、生产ID/迁移/启用、对账告警和归档；默认关闭生产装配 | TASK005/CON020/FLT012；W2-OUTBOX-001～003 |
| USR-001 / Backend Core | PR22/23契约、PR24宠物域与幂等服务；PR31接入HTTP；W3模拟器昵称/宠物读改写 UI→HTTP→DB 联调通过（替身后端） | 生产ID/迁移启用；完整真实业务E2E与真机 | W2-USR-001～004；宠物快照前置 |
| MER-001 / Backend Core | PR50～57、60、59、64已合入；PR61～63交付手机签约页、准入两端点（CCR-W2-ADMISSION-001）与工作台真实准入；PR64交付通知读取/消息页/受控跳转（NTF-001+C-006，CCR-W2-NOTIFICATION-001，模拟器人工验收通过） | 成员/核销映射/在途守卫/冻结写动作及生产门禁；另登记承接C端门店两条读路由（SVC-001提案§7）；完整Issue仍IN_PROGRESS | MER001/ORD009/010；W2-MER-001～003 |
| SVC-001 / Backend Core | SVC-D1～D5已批（v0.3+回执）；读切片实现完成：两条C端服务路由、D5展示资格第四查询（独立提交）、四条件可见性、错误两分、快照拷贝；ServiceQueryHttpTest通过；07/10/11/27已同步 | PR评审合并与人工核验；不提前实现预约交易；完整预约流程未验收（无写入方） | ORD008；W2-SVC-001～003 |
| C-002 / C-End | PR25编辑资料；PR34宠物页、公共底栏、分包和标题修正；PR38真实接口接入（W3模拟器联调通过，替身后端） | 独立编辑页设计、芯片/记录字段CCR、上传/更多页面与真机/VIS | MINI004/VIS001～004；W2-FE-001～005 |
| C-003 / C-End | 本轮未发现产品页交付PR | C-End文件所有权释放、商家/服务/可用性契约和原图；真实查询需MER/SVC/SCH | MINI004/VIS；不做C004支付 |
| M-002 / Merchant | 签约页/准入契约/工作台真实准入已合入（PR61～63，主链闭环） | 完整工作台业务页（订单/服务/排期/核销）、子账号、真机与VIS | MINI002～004/VIS；W2-FE |
| A-002 / Admin | PR59运营入驻审核页面核心切片已交付并合入，真实浏览器方案b合成申请链路通过 | 生产HTTPS入口、完整视觉验收及其余商家/服务/排期/员工治理页面（service_item写入方登记于"服务操作"范围，SVC-001提案§7）；不得把切片完成扩为整项DONE | WEB002/PERM002/005；W2-FE |
| PLAT-005 / Backend Core | PR21与W3原AC核验通过，Catalog已DONE | 运维采集按原Issue范围，不把历史“可关闭”当作当前待关闭 | ARCH001～005、PLAT005-FILTER/HANDLER |
| PLAT-006 / Backend Core | PR29裁决；六模块迁移 PR #39～#44 已合入，22号裁决回执已登记，DONE | 生产数据库迁移/启用与 PLAT-002 门禁另行授权 | ARCH001～005及既有MySQL回归（见[迁移报告](progress/2026-09-16/PLAT006_MIGRATION_REPORT.md)） |

原规划的11个候选仍保留；PLAT-005/006来自已合并PR21/29的后续人工授权，不是此次新增任务。OSS阶段见[同步报告](progress/2026-09-16/PROGRESS_SYNC.md)，不自行新增一个Catalog Issue。

## 执行纪律

先看[当前状态](../WORK_STATE.md)、[剩余门禁](BLOCKED_QUEUE.md)和对应Issue的Allowed/AC。已交付阶段不重复起草、重复索取已提供的Figma或重复请求同一产品裁决；未覆盖范围按原Owner继续。代码/Contract有唯一Writer，下一阶段范围与授权需要明确；本次只同步文档。

组件测试、Mock页面、真实数据库/HTTP测试、物理真机及生产启用分别记录。真实后端已具备部分链路，不等于C端页面已接通；预览完成也不等于完整交易E2E或完整Issue DONE。

## 当前接续顺序

签约/准入主链已收尾（12bdc38），下一步按NTF-001+C-006通知子切片推进消息读取与受控跳转，再按MER/SVC/SCH依赖推进服务与排期。真实手机提交与方案b合成申请审核分别取证。位置限制已由SSOT §28取消，成都目录保持；不重审已批准方案。
