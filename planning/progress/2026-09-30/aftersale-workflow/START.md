# AFS 多角色接续与 PR96 收尾

日期：2026-09-30。当前阶段：并行契约与验收准备；新 AFS 业务代码尚未实现。用户已授权 CI 结束后按四角色方案实施，随后明确关闭定时任务并立即开始。

## 已核实基线

- [PR96](https://github.com/blueXYing/pet-platform/pull/96) 已合入 develop，提交 `ff983596cad899c7b4e1ce9c8278228787ca7ac6`。
- [合并后 CI36668953557](https://github.com/blueXYing/pet-platform/actions/runs/36668953557) 六项全部成功。[合并事实](pr96-merge.json)、[CI 元数据](pr96-merge-ci.json)、[实际报告摘要](pr96-merge-backend-summary.json) 已保存。
- 下载的121份后端报告共852项测试，失败0、错误0、跳过0；普通退款真实API验收25项、配置33项、ArchUnit22项均存在。相对PR95没有缺失或数量减少的测试套件。
- 合并代码树 `36bc3185a85b37ddaa90ac3c9b6e8a177967597b` 与 PR96 审阅通过的 `4232001` 一致。基线通过不代表新增 AFS 验收通过。
- 自动化 `pr96-ci` 已由应用工具设置为 PAUSED；不再定时启动。本聊天接手持续工作。

## 工作隔离与分工

复用附属 worktree `refund-aftersale`，由核实的 origin/develop 建立 `codex/aftersale-workflow-20260930`。原 Desktop 项目的 `frontend-miniapp/project.config.json`、`.zcodeignore`、设计源登记表等用户未提交文件保持原状。

| 角色 | 已采用模型/档位 | 本轮独占产物 | 后续实施边界 |
|---|---|---|---|
| 总协调 | 本聊天当前设置；用户建议 Astra/xhigh | CCR总案、状态/回执、共享契约及Schema协调 | 审阅、装配、集成、提交PR |
| 售后业务 | gpt-6-astra/high | [领域方案](AFS-DOMAIN-PROPOSAL.md) | AFTERSALE创建、证据、状态机、裁决；权限/资产适配按明确Owner接入 |
| 资金与核销集成 | gpt-6-astra/xhigh | [资金方案](MONEY-INTEGRATION-PROPOSAL.md) | REFUND/ORDER/PAYMENT/SCHEDULE来源、金额、投影与核销兼容 |
| 独立QA | gpt-6-astra/high | [验收矩阵](ACCEPTANCE-MATRIX.md) | 真实来源、权限、幂等、故障和竞态；关键审查可升xhigh |

共享文件单一Owner，Maven串行执行，各用例隔离数据库。子代理不自行提交、合并或改写别人的文件。

## 接续顺序与门禁

AFS-001真实资格/创建/受理/补证 → AFS-002运营终局与退款来源 → VER-002/QA-004真实售后×核销竞态。接口适配与QA准备并行，不能用seed工单证明真实售后闭环。

已批准规则不重问：两类7天窗口、核销先成功使旧未履约售后失效、refund_order先创建禁止核销、一单一次实际退款、仅运营部分退款、无复审、无额外MFA/双人复核，以及普通退款拒绝后可重申请。

四项产品问题已向用户发出（P4来自交叉审查补问），回答到达前保持待决，详见[裁决记录](PRODUCT-DECISIONS.md)。技术增量在[AFS工作流CCR](../../../ccr/CCR-W2-API-001/aftersale-workflow-proposal.md)集中审阅。依据根AGENTS不得自行变更产品规则；WORK_EXECUTION_PROTOCOL §4要求产品裁决与重大Contract变更获人工批准。已授权的盘点、设计、验收准备及PR96收尾持续推进，不重复索取整体开工许可。

本轮不自动合并新PR，不生产迁移/开启开关/调用真实资金渠道。REF-001、员工绑定、公开HTTP/小程序、实际通知送达及完整端到端仍按后续独立范围处理。尚缺资金账本基线的OD-W0-001继续保留，不以售后7天推导冻结/分账规则。

## 准备成果与验证

三个角色的提案和37项验收矩阵已完成，总案已收敛P4重复关闭、七天既有边界、补证截止、API职责及真实资金缺口。见[独立交叉审查](CONTRACT-REVIEW.md)和[实际检查记录](PREPARATION-CHECKS.md)：118项离线契约回归、18项检查器回归及模块/展示态/持久层检查通过；8份Markdown的61个本地链接及历史台账原样归档已核验。所有新增AFS功能仍NOT_EXECUTED，方案为待答/待审阅的可执行准备，不冒充业务实现。
