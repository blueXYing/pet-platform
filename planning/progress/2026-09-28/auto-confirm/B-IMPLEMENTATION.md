# B独立部分实施与验收

## 授权和交付范围

用户对已展示的A/B方案仅回复“B”，已记录B_APPROVED/A_PENDING。基于PR91原文档分支继续，使用最新已核对develop `b1b2f8f`，未操作原用户工作区。43号契约只冻结B的独立部分，不冒称A已获批，也不将完整ORD-002或B全量完成。

交付：

1. `OrderPaymentResultApiImpl`新增默认关闭的任务生产路径，仅正常付款首次消费在已有订单/预约/结果/日志/事件事务内调用TASK的`enqueueAt`。原有构造方法保持关闭；boot通过显式配置注入。
2. `OrderAutoConfirmTaskSpec`固定键、round0、原渠道paidAt+30min、严格三字段payload、8次重试策略标签。初始execute_at和不可变submitted_execute_at一致。
3. TASK Owner只读提交快照；ORDER Owner分页扫描自己的待确认行，识别缺任务、活动任务、终态异常、绑定冲突、不自洽ORDER事实和未支持轮次。扫描不调用写命令、不推断退款不存在。
4. `worker.enabled`或`repair.enabled`误开时启动失败；没有A不消费任务为成功、不写待服务、不发OrderConfirmed。主开关与支付基础依赖也做启动校验。inspection独立opt-in。

## 实际验证

- Java21 + 独立MySQL8.4，监听仅127.0.0.1:23391，测试数据库均为随机qa_booking_*且由夹具清理；未连接原项目开发库/生产库或支付渠道。
- `AutoConfirmTaskPreparationAcceptanceTest`：9项真实MySQL验收通过，覆盖正常原子产任务、并发重放、任务写入故障全回滚、异参taskKey冲突、真实commit后ACK丢失恢复原任务、跨午夜与双JVM时区原截止、默认关闭历史缺任务、只读扫描不改变终态/租约/重试、权限/有界分页。
- `OrderAutoConfirmTaskConfigurationTest`：4项通过，覆盖默认关闭、依赖校验、缺A禁止worker/repair及独立只读装配。
- `PaymentFoundationAcceptanceTest`：10项通过；迟到支付用例额外开启B生产开关，确认始终零ORDER_AUTO_CONFIRM任务。
- 本地定向合计23项，failures/errors/skipped均0。证据见[b-local-tests.json](b-local-tests.json)。全仓CI结果以PR91最新head为准；不把旧head绿灯替代新代码验证。

## 当前限制与下一步

实际repairMissingTask、确认Handler、异常终态恢复执行和持久告警尚未完成：它们需要A批准的当前REFUND存在性与自动确认命令。当前只读扫描是诊断基础，不会自动补任务，不会把DEAD改回READY。可独立实施的B工作已经完成；A获批后继续这些依赖部分、商家动作和改期联动。未合并PR、启用生产开关或发送真实支付请求。

## 自查

- 生产新增SQL全部位于本Owner Mapper XML；无biz→biz或ORDER跨表查询TASK/REFUND。
- 原截止来自已通过PAYMENT权威校验的正常消费；任务去重比较submitted_execute_at而非重试后的execute_at。
- 扫描返回ACTIVE_TASK仅表示记录处于活动状态，不承诺worker运行。MISSING_TASK不是业务资格证明。
- 读扫描使用同库只读事务/有界游标；跨页并发变化保留诊断限制。
- 默认付款路径和迟到退款路径保留原行为；新正常任务仅在显式内部开关下产生。
