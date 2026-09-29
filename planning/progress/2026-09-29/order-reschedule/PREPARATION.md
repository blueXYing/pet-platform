# ORD-003 准备与基线核验

2026-09-29 用户授权按推荐顺序推进。复用空闲、干净的既有管理worktree，从origin/develop `be1cb681057caf2276df8ed436ac2111b4fc7bd4`建立`codex/order-reschedule-20260929`；没有修改原用户目录，也没有生产迁移或启用开关。

PR92已合并；[合并CI](pr92-merge-ci.json)六项全部成功。实际下载110份后端Surefire XML，[汇总](pr92-merge-backend-summary.json)为674测试、0失败、0错误、0跳过，含逐报告SHA256。这证明已合并基线，不是改期业务测试。

已核对：SSOT改期规则与§31/34；最终C端PRD §5.1.17/19及商家PRD §5.3.2（读取DOCX原XML，不改文档）；技术基线/36～45号Contract；HTTP10/OpenAPI11、内部API07、Schema06/13/45、Scheduler09§13/14、Test14 RES-001～006/CONF-006和Test15 CON-006；真实ORDER/SCH/TASK/VER实现。

关键发现：一单一预约唯一约束；SCH仅新增候选的证明不能直接用作交换；TASK无业务取消API；确认/拒单/修复和退款来源证明均有round0限制；PRD要求改期旧核销码失效但VER模块尚无实现。候选方案见[改期CCR](../../../ccr/CCR-W2-API-001/order-reschedule-proposal.md)，包括R1原子交换与历史、R2取消及第二轮、R3默认关闭内核及真实依赖门禁。

本轮只新增候选设计、基线证据和状态记录，不修改生效Contract、可执行DDL或业务代码；所有改期验收仍NOT_EXECUTED，完整Issue继续BLOCKED。批准具体方案后再同步正式合同、实现和测试；没有用静态检查或PR92旧报告冒充改期通过。

本轮检查：`git diff --check`通过；10个新增/当前记录中的本地引用均可解析；CI证据核对正确合并SHA及六个成功检查。既有contract smoke仍通过（90操作/57写requestId/1032引用/214 String ID）；本轮无业务变更，不重复跑既有整套Java验证。方案尚未获具体回执，因此未创建声称业务完成的PR。
