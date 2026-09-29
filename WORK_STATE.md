# Work State

PROJECT: 宠物平台 V1.0
STATE_VERSION: 21.0
UPDATED_AT: 2026-09-29
CURRENT_PHASE: W3_INTEGRATION_REVIEW
CURRENT_STATUS: ORD001_D1_D2_D3_IMPLEMENTED_FOR_PR_REVIEW
VERIFIED_BASELINE: develop 1eb96ac74316c2aa9b66dae89f5d208b4c89b6d9（PR91已合并；合并CI36512780916六项通过，107份后端报告合计649测试、零失败/错误/跳过）
NEXT_PHASE: 当前商家命令切片PR审阅；随后ORD-003一次改期及round1
NEXT_PHASE_APPROVED: 用户“批准以上三项，按推荐方案继续”；D1/D2/D3已批准，当前PR暂不合并或生产启用

## 当前事实

- 用户随后授权“开始合入91”；PR91已于2026-09-29T02:28:44Z合入develop。旧台账的“暂不合并”已被此授权取代，仅保留在[历史记录](planning/history/WORK_STATE_BEFORE_20260929_MERCHANT_ORDER_ACTIONS.md)。
- 合并CI六项全部成功，见[CI证据](planning/progress/2026-09-29/merchant-order-actions/pr91-merge-ci.json)和[合并记录](planning/progress/2026-09-29/merchant-order-actions/pr91-merge.json)。实际下载解析107份Surefire XML，649测试通过；[汇总及报告哈希](planning/progress/2026-09-29/merchant-order-actions/pr91-backend-summary.json)包含21项自动确认执行、9项任务准备、10项支付基础和5项开关验收。
- A+B首轮内部自动确认、Worker及异常恢复已合并；运行开关仍默认关闭，未做生产迁移、真实渠道交易或生产启用。完整ORD-002依赖仍未解除。
- 用户现要求按推荐顺序推进，已从该develop基线建立 `codex/merchant-order-actions-20260929`；沿用现有隔离工作区，原用户目录未提交文件未改动。
- 用户随后批准[ORD-001具体CCR](planning/ccr/CCR-W2-API-001/merchant-order-actions-proposal.md)D1/D2/D3。正式同步SSOT §34、07/10/11/12、Event08、Scheduler09、Test14及45号Contract/Schema。
- 已落实主账号round0确认/拒单HTTP、独立幂等绑定、当前会话与门店权限复核、DB截止判断、拒单全额退款原子建单、来源隔离执行及最终成功后预约释放。实施及测试范围见[实现记录](planning/progress/2026-09-29/merchant-order-actions/IMPLEMENTATION.md)；当前PR检查和CI附件为完整CI证据源。

## 下一步

1. 审阅当前实现PR，通过后再单独决定是否合并；不重复审批已经批准的D1/D2/D3。
2. 全部运行开关默认关闭，不执行生产DDL或真实渠道交易；可信审核、密钥管理和正式渠道条件齐备后另行安排启用。
3. 接ORD-003一次改期、旧任务取消及round1。
4. 接完整角色、公开订单查询/DisplayOrderStatus、小程序、券积分通知及对账消费者；员工授权/内容审核真实依赖等未齐备前不对外启用。
5. 正式渠道条件齐备后真实联调；生产启用独立处理。

## 验收与限制

PR91的649测试证明已合并基线，不是ORD-001业务测试。本轮新验收见[实现记录](planning/progress/2026-09-29/merchant-order-actions/IMPLEMENTATION.md)。ORD-001/002/003等完整Issue状态不因内部切片提前改DONE。FROZEN写入边界、完整员工权限、公开读侧、round1及微信真机仍未接通；内容审核缺失拒绝装配；无外部告警送达证明。
