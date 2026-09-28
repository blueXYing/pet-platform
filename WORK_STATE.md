# Work State

PROJECT: 宠物平台 V1.0
STATE_VERSION: 13.0
UPDATED_AT: 2026-09-28
CURRENT_PHASE: W3_INTEGRATION_REVIEW
CURRENT_STATUS: SELECTION_AND_BOOKING_EXPIRY_INTEGRATION
VERIFIED_BASELINE: develop fac6b73f344f1b6da3fd996c44d391d2a7068b92（PR85按用户批准合入，合并CI36322764978成功）
NEXT_PHASE: 审阅 PR86 及选窗/到期协调；接真实支付、优惠券与备注审核后才开放C创建
NEXT_PHASE_APPROVED: YES（用户批准合并85并按顺序推进；OTHER/EXOTIC映射已单独批准，新PR合并/生产启用未授权）

## 当前事实

- PR85已合入，保护基础可作为本轮共同基线。原工作目录三个用户改动保持，所有实现仍在独立worktree。
- PR86 仍开放，本轮从其已测 head 3e54ed7 开发，未代替用户合并批准。内部原子创建新增同事务 durable task，首次调度时间为原十分钟截止值。
- 新USER/MER/SERVICE Owner当前事实接口同事务读取，服务价格、资格、宠物归属和快照不取客户端伪造值。成功重放重验当前USER及结果owner，不重消耗容量/重新计算价格。
- 接送完整双claim，主区间只作外包络显示；地址和备注分别加密快照。OTHER只在适用性上匹配EXOTIC，宠物快照仍OTHER，见SSOT§32。
- 同键并发/异参、七阶段故障回滚、commit ACK丢失/回查断连、共享协议与类目行锁、默认关闭均有独立MySQL验收。全量/CI最终结果见实现PR。
- 新 selection 开关开启时，既有登录 GET 增加 windowId/kind，可按 kind 筛选并按双 claim 统计；关闭时保留旧六字段。原窗展示不代替最终人员可行性证明。
- 新内部到期 API 和可选 worker 在同店 guard 下确认无支付/无券记录后，同事务 CANCELED+EXPIRED；未知状态保留占用。历史缺任务支持有界分页补投，不自动运行生产补偿。SCH 独立过期不能借旧取消日志提交。
- 明确 UTC DATETIME 读写，修复 Windows 默认时区导致的八小时偏移。全量和独立 QA 执行中，以集成回执及 PR 门禁为准。
- 外部C创建、真实支付/优惠券、生产备注审核仍未开放；所有新增开关默认关闭。无生产迁移或自动启用。

## 下一步

1. 完成本轮组合门禁，按依赖审阅 PR86 和本轮 PR，合并仍需用户批准。
2. 接真实支付意图、渠道查询/回调与预约确认，处理支付未知和迟到支付，不能从无记录保护推定完整支付已实现。
3. 补优惠券冻结/释放、备注审核及真实会话创建 HTTP/页面验收，随后再开放消费者创建。
4. confirm/release/swap 与 SCH004 维护按已批准契约和依赖顺序继续。

## 证据

- [38号契约](docs/04-api/38-Atomic-Booking-Create-Contract-v0.1.md)
- [39号契约](docs/04-api/39-Selection-Booking-Expiry-Contract-v0.1.md)
- [选窗与到期集成回执](planning/progress/2026-09-28/selection-expiry/INTEGRATION.md)
- [实施范围与CCR](planning/ccr/CCR-W2-API-001/booking-create-implementation.md)
- [独立QA](planning/issues/wave-3/BOOKING-create-qa/RUN-REPORT.md)
- [本轮集成回执](planning/progress/2026-09-27/booking-create/INTEGRATION.md)
- [上一状态](planning/history/WORK_STATE_BEFORE_20260928_SELECTION_EXPIRY.md)
