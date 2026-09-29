> 2026-09-29: approved Contract 46 extends this initial-round slice to round 1; task keys and immutable deadlines are round-specific. See [46](46-Order-Reschedule-Contract-v0.1.md). Existing round-0 rules remain valid.

# 自动接单任务准备契约 v0.1（仅已批准B的独立部分）

2026-09-29 接续：用户已批准 A 推荐方案及 B 剩余实现。[44号执行与恢复契约](44-Auto-Confirm-Execution-Recovery-Contract-v0.1.md)覆盖下文关于“A未批准、worker/repair不可执行”的历史边界；本文件的原子任务格式及只读排查接口保持有效。运行开关仍默认关闭。

2026-09-28 用户对[CCR A/B](../../planning/ccr/CCR-W2-API-001/order-auto-confirm-proposal.md)回复“B”。B已批准，A未批准。本契约落实原子产任务和只读排查，默认关闭；不定义/实现A的自动确认命令、退款资格API或OrderConfirmed事件，也不启用依赖这些能力的补建/恢复写操作。

## 1. 正常支付与任务同事务

基于40号既有正常付款消费，在同一DataSource、同门店guard、READ_COMMITTED事务中原子提交：ORDER=PENDING_CONFIRM/PAID、SCH=CONFIRMED、order_payment_result=NORMAL、状态日志、消费记录、OrderPaidEvent.v1 Outbox和自动接单任务。只有`markNormal`成功分支新增任务；迟到付款分支不新增该任务。

| 字段 | 值 |
|---|---|
| task_key | `ORDER_AUTO_CONFIRM:{orderId}:0` |
| owner_module / biz_type | ORDER / ORDER |
| task_type / retry_policy | ORDER_AUTO_CONFIRM / ORDER_AUTO_CONFIRM |
| biz_id | 内部BIGINT订单ID；JSON中仍使用String |
| expected_version | NULL，不使用易变整行version代替轮次 |
| payload_json | 严格三个字段：`orderId` String、`expectedConfirmRound`整数0、`expectedConfirmDeadline` UTC毫秒精度时间字符串（Java ISO offset格式） |
| submitted_execute_at | 原渠道paidAt+30min，使用SQL39已有不可变列 |
| execute_at | 初始等于原截止；未来重试可改变它，不能反过来改变原截止 |
| max_retry_count | 8 |

首次消费失败全部回滚，已持久的PAYMENT成功事实不受影响，原事件可重试。提交ACK丢失重试按现有ORDER付款结果/消费记录恢复，保留原任务。已有相同taskKey异参冲突时回滚整笔消费，不覆盖任务。原子入库不表示已有执行Handler。

默认关闭期间成功消费的历史订单可能没有任务；后来启用后重放已完成付款事件仍NOOP，不能跳过A资格复核去隐式补建。此类行由下述扫描识别。

## 2. SYSTEM只读排查

`OrderAutoConfirmTaskInspectionApi.inspect(QueryContext context,String afterOrderId,int limit)`：

- 仅可信SYSTEM；非SYSTEM→COMMON_FORBIDDEN。无HTTP路由，不接收客户端自报SYSTEM权限。
- afterOrderId为NULL表示开始，其他值必须规范正十进制String；limit为1～100。非法→COMMON_INVALID_ARGUMENT。
- ORDER独立只读REPEATABLE_READ事务，不允许外层事务；按自己的PENDING_CONFIRM订单ID升序有界查询limit+1。仅ORDER自己的order_payment_result可联表。
- 返回Page：items、nextAfterOrderId（还有数据时为本页最后一个ID，否则NULL）、本页Finding计数。Item：orderId、confirmRound、confirmDeadline、due（根据DB UTC时间）、taskStatus、finding。不输出渠道报文、客户信息或密钥。
- TASK通过`TaskSubmissionInspector.find(taskKey)`提供本Owner的只读提交快照；ORDER不查async_task/Mapper。快照包含任务元数据、不可变原截止、当前execute_at/status/retryCount，用于诊断比较，不授权确认或恢复。
- 原taskKey按DB唯一键排序规则命中后，逐字段按严格大小写核对；大小写碰撞不能误报为缺任务。payload字段/类型/轮次/规范截止与原始submitted_execute_at都须一致。

| Finding | 含义 |
|---|---|
| MISSING_TASK | 首轮ORDER正常付款投影及截止自洽，但没有该任务；不等于已验证当前PAYMENT/SCH/REFUND资格 |
| ACTIVE_TASK | 提交绑定一致且状态为READY/RETRY_WAIT/RUNNING；不表示worker已启用或已执行 |
| TERMINAL_TASK_REQUIRES_REVIEW | 当前仍待确认但任务已SUCCEEDED/DEAD/CANCELED或未知状态；保留原终态/尝试记录，须复核 |
| TASK_BINDING_CONFLICT | 任务不可变字段/元数据与订单预期不一致，不能原号覆盖或改用新号 |
| ORDER_FACTS_REQUIRE_REVIEW | ORDER付款结果/截止/取消/退款关联/已确认字段等有缺失或异常；不猜测修复 |
| UNSUPPORTED_ROUND | 非首轮0；本切片不负责改期round1 |

扫描只读，不写任务、不改租约/次数、不产事件、不读取REFUND表。页面间可能出现新的并发业务变化，因此扫描不是整库固定快照，也不是接单资格证明。即使finding为MISSING_TASK，仍须A中的当前付款、预约、退款资格核验后才可补建。

## 3. 装配与当前不可执行边界

- `pet.order.auto-confirm.enabled=false`：正常付款生产任务开关；true要求`pet.payment.foundation.enabled=true`，付款消费仍要求既有依赖齐备。
- `pet.order.auto-confirm.inspection.enabled=false`：独立只读排查开关；可以在任务生产关闭时检查历史缺任务。
- `pet.order.auto-confirm.worker.enabled=false`、`pet.order.auto-confirm.repair.enabled=false`：本B独立版本任何一项为true均启动失败，明确提示依赖A实现，不静默忽略，也不使用空Handler把任务消费成成功。
- 没有自动确认Worker/TaskRegistration，不发生PENDING_SERVICE/confirm_mode/confirmed_at写入；没有OrderConfirmedEvent生产者、REFUND资格接口或改期取消API。
- B中“实际补建、DEAD/CANCELED/SUCCEEDED异常恢复”尚未完成，其前提A尚未获批。持久异常登记/外部告警及恢复执行随该后续切片完成；当前只读诊断不宣称已发送告警或恢复完成。

## 4. 存储、版本与测试边界

无新DDL，无修改公开HTTP/OpenAPI或SSOT产品规则；使用SQL06/13/39/40现有表。新增生产SQL在各Owner Mapper XML；跨模块仅公共API/TASK封装。

MySQL验收应覆盖正常付款原子任务、任务写入失败全回滚、异参唯一键冲突、真实提交后ACK丢失、并发事件重放、原渠道时间跨午夜/JVM时区、迟到付款零任务、默认关闭历史缺失、扫描不修改租约/重试/终态、分页/权限、缺A禁止启用。CONF-002/CONF-005的实际自动确认、商家动作和改期联动不计入本轮已通过范围。
