# Work State

PROJECT: 宠物平台 V1.0
STATE_VERSION: 20.0
UPDATED_AT: 2026-09-29
CURRENT_PHASE: W4_NEXT_WAVE_PLANNING
CURRENT_STATUS: PR91_MERGE_VERIFIED__ORD001_CCR_PROPOSED
VERIFIED_BASELINE: develop 1eb96ac74316c2aa9b66dae89f5d208b4c89b6d9（PR91已合并；合并CI36512780916六项通过，107份后端报告合计649测试、零失败/错误/跳过）
NEXT_PHASE: ORD-001商家确认/拒单与退款联动契约审阅，批准后正式契约同步、实现和测试
NEXT_PHASE_APPROVED: 用户已授权按推荐顺序开始；本轮D1/D2/D3新增契约及截止边界候选尚未批准

## 当前事实

- 用户随后授权“开始合入91”；PR91已于2026-09-29T02:28:44Z合入develop。旧台账的“暂不合并”已被此授权取代，仅保留在[历史记录](planning/history/WORK_STATE_BEFORE_20260929_MERCHANT_ORDER_ACTIONS.md)。
- 合并CI六项全部成功，见[CI证据](planning/progress/2026-09-29/merchant-order-actions/pr91-merge-ci.json)和[合并记录](planning/progress/2026-09-29/merchant-order-actions/pr91-merge.json)。实际下载解析107份Surefire XML，649测试通过；[汇总及报告哈希](planning/progress/2026-09-29/merchant-order-actions/pr91-backend-summary.json)包含21项自动确认执行、9项任务准备、10项支付基础和5项开关验收。
- A+B首轮内部自动确认、Worker及异常恢复已合并；运行开关仍默认关闭，未做生产迁移、真实渠道交易或生产启用。完整ORD-002依赖仍未解除。
- 用户现要求按推荐顺序推进，已从该develop基线建立 `codex/merchant-order-actions-20260929`；沿用现有隔离工作区，原用户目录未提交文件未改动。
- 已核对SSOT、最终商家/C端PRD、HTTP/OpenAPI、公共幂等及现有实现。当前退款执行仅支持迟到支付，不能直接套用商家拒单；公共命令幂等、普通退款来源与成功释放预约存在契约缺口。
- 已形成[ORD-001具体CCR候选](planning/ccr/CCR-W2-API-001/merchant-order-actions-proposal.md)，含字段级Schema建议、HTTP/鉴权、时间竞争差异、跨Owner原子事务及11组验收。只修改规划文档与证据，未修改正式Contract、DDL或业务代码。

## 下一步

1. 审阅并批准CCR的D1/D2/D3：主账号首轮切片、锁内截止判断、新Schema及普通拒单退款协议；不重复审批PR91。
2. 批准后按现有ORD-001/REF-003/PAY-001/SCH-003及身份Owner分工同步正式契约，实现确认/拒单及全额退款闭环；真实MySQL并发/故障和原迟到退款、自动接单回归通过后提交PR供审阅。
3. 接ORD-003一次改期、旧任务取消及round1。
4. 接完整角色、公开订单查询/DisplayOrderStatus、小程序、券积分通知及对账消费者；员工授权/内容审核真实依赖等未齐备前不对外启用。
5. 正式渠道条件齐备后真实联调；生产启用独立处理。

## 验收与限制

PR91的649测试证明已合并基线，不是ORD-001业务测试。本轮文档检查及未决边界见[准备与验证记录](planning/progress/2026-09-29/merchant-order-actions/PREPARATION.md)。ORD-001/002/003等完整Issue状态不因内部切片提前改DONE。FROZEN写入边界、完整员工权限仍未裁决/接通；内容审核不得默认为放行；无外部告警送达证明。
