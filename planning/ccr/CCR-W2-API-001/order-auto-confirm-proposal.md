# 正常支付三十分钟自动接单：CCR 候选方案与实施边界

状态：A_B_APPROVED（2026-09-29）。基线：develop `b1b2f8f4c434b6b638fc00f9bf5da405f1a28435`（PR90）。

2026-09-29 用户明确批准：“批准 A 推荐方案，继续完成 A 和 B 剩余实现及测试。沿用既定业务规则，运行开关保持默认关闭；完成后提交 PR 供审阅，暂不合并或生产启用”。本授权覆盖本文件 A 与 B 剩余内部实现、测试及提交 PR，不包含合并、生产启用或新业务范围。最终实施契约见[44号](../../../docs/04-api/44-Auto-Confirm-Execution-Recovery-Contract-v0.1.md)；无新增 DDL。下述 2026-09-28 记录为历史，不再表示 A 待批准。

2026-09-28 用户在A/B审批请求后回复“B”。仅B获批，A不推定获批。当前独立实施B的正常付款原子产任务和只读缺失/异常扫描，见[43号B实施契约](../../../docs/04-api/43-Auto-Confirm-Task-Preparation-Contract-v0.1.md)。B中需调用A资格核验/自动确认命令的实际补建、Worker和异常终态恢复仍依赖A，暂不装配或执行；不把整个B或完整自动接单记为完成。

审批来源：用户先要求“请你按照你所给的顺序推进”，授权收尾、契约梳理及后续开发顺序；随后对具体A/B回复“B”。依据 WORK_EXECUTION_PROTOCOL.md §4，重大 Contract 变更须人工批准。以下保留原候选供追溯；B独立部分以43号实施契约为准，A仍是未冻结草案。

## 1. 既定规则与本轮范围

- SSOT §1/§3：正常支付进入待确认；商家三十分钟未处理自动接单到待服务。首轮截止为渠道事实 `paidAt + 30min`（07 §26、40号契约），不使用回调收到时间或消费时间。
- 商家确认/拒单先提交时自动任务失效；自动确认先提交时普通商家确认/拒单返回既定状态冲突。改期最多一次，成功后重新待确认、重新三十分钟，旧轮任务失效。
- 迟到支付保持关闭，只进入退款链路，永不创建正常自动接单任务。
- 退款单创建后禁止核销；服务前退款自动全额，槽位在渠道退款最终成功后释放。本切片不改变退款金额、核销或释放规则。
- 首轮实现范围：正常支付与任务原子提交、SYSTEM 自动确认、REFUND 权威存在性查询、缺任务补建、内部默认关闭装配、真实 MySQL 验收。无公开 HTTP、无前端页面、无真实支付调用。
- 归属现有 ORD-002 / EPIC-08 / ST-CONF-01。ORD-002 原依赖 ORD-001；本次先交付内部切片不解除该依赖、不将完整 Issue 标 DONE。后续 ORD-001 商家接单/拒单、ORD-003 改期完成前不对外启用。

## 2. 仓库现状核对

| 事实 | 来源与含义 |
|---|---|
| 正常支付已有原子事务 | `OrderPaymentResultApiImpl.markNormal` 同库写 ORDER、确认 SCH、写正常付款结果及 OrderPaid Outbox；尚未生产自动接单任务。 |
| 没有自动确认业务实现 | 07 号只有 `autoConfirmOrder` 方法名称；当前 Java 没有对应命令/Handler，订单确认字段尚无写入。 |
| 已有可复用事实接口 | PAYMENT `PaymentSuccessFactsApi.requireSucceeded` 与 SCH `ReservationConfirmApi.assertConfirmed` 要求同 DataSource / 同店 guard，查询当前权威记录。 |
| REFUND 缺普通订单存在性接口 | `RefundExecutionFactsApi` 返回迟到退款执行/成功绑定，不能用“无迟到退款绑定”推断“没有退款单”。历史/其他来源退款单也必须阻断确认。 |
| TASK 已有幂等定时提交 | `JdbcAsyncTaskSubmitter.enqueueAt` 比较不可变 `submitted_execute_at`，不会拿重试后可变 `execute_at` 当原始截止。 |
| TASK 没有业务取消公共方法 | 当前仅 Worker 处理结果能 Cancelled；不能冒称改期已能取消 READY/RETRY_WAIT/RUNNING 旧任务。 |
| 字段存在文档偏差 | SQL06 是 `confirm_mode`，Scheduler13及CONF-002写 `confirmation_type`；以Schema为准同步文档，不创建第二个含义相同的字段。 |

## 3. 审批项 A：确认命令、当前事实与退款并发协议

### 3.1 候选内部命令与结果

ORDER Owner 提供 `OrderAutoConfirmApi.autoConfirm(AutoConfirmOrderCommand)`；命令字段：

| 字段 | 候选约束 |
|---|---|
| context | CommandContext，SYSTEM，稳定 requestId=`TASK:ORDER_AUTO_CONFIRM:{orderId}:{expectedConfirmRound}`；仅内部调用 |
| orderId | 正十进制 Snowflake String，严格校验 |
| expectedConfirmRound | 整数0或1；首轮实现只生产/执行0，1留给改期联动，不能提前放行 |
| expectedConfirmDeadline | 带明确时区的 OffsetDateTime，毫秒精度；比较同一 instant，规范化为 UTC |

候选结果：`CONFIRMED`、`ALREADY_CONFIRMED`、`STALE`、`NOT_DUE`、`BLOCKED_BY_REFUND`。依赖未知/不一致为 `DEPENDENCY_UNAVAILABLE`（可重试），输入非法/非SYSTEM沿已有公共错误码。结果不包含跨模块持久化对象。不把整行 version 当轮次：无关订单写入不应使唯一当前任务永久失效。

- 同一已成功命令重放：必须有 ORDER 本轮自动确认事实与相同 requestId/期限证据，返回 ALREADY_CONFIRMED，无第二条日志/事件。
- 订单已被其他合法操作推进、取消或预期轮次/截止已过时：STALE，不再推进。
- 当前仍待确认但未到截止：NOT_DUE；任务需保留到原截止再执行，不能当成功/NOOP吞掉。
- 当前轮次未改变而 requestId对应的原截止不同：契约冲突，不能覆盖原任务/结果。损坏事实不当普通STALE。
- 首轮不支持 round1 执行：明确失败并保留可诊断结果，不标成功。旧 round0 遇已合法改期轮次1时 STALE。

### 3.2 同库短事务及事实检查

定位门店只作提示。业务事务使用同一主库、READ_COMMITTED、同店 guard；顺序为 guard → ORDER订单/正常支付结果 → PAYMENT当前成功事实 → SCH当前预约 → REFUND当前退款事实 → ORDER确认写入/日志/Outbox。保持各模块 Owner 访问自己的表；不引入 biz→biz，不由 ORDER 查 refund_order。

确认前重读并核对：

1. 订单 `PENDING_CONFIRM/PAID/UNVERIFIED`，无取消事实；轮次与预期相同；首轮 `confirm_deadline == paid_at + 30min` 且与命令预期相同；DB UTC 当前时间已到截止。
2. ORDER `order_payment_result=NORMAL`；paymentId、成功eventId、订单/用户/商户/门店、渠道流水、金额、币种与渠道paidAt逐项对上 PAYMENT `requireSucceeded`。当前 PAYMENT 已进入 RECONCILIATION_REQUIRED 等不可证明成功状态时回滚/重试，不能只相信历史 OrderPaid 或 `payment_status=PAID`。
3. SCH `assertConfirmed(orderId,reservationId,storeId,context)` 验证当前预约仍为本单、本店、CONFIRMED。过期或未知事实不释放占用、不接单，重试并记录错误。
4. REFUND 以新候选 `RefundOrderFactsApi.findByOrder(orderId,storeId,QueryContext)` 在同 guard、同事务查询本 Owner 的全部来源退款单，返回明确 `NONE` 或 `EXISTS(refundOrderId,orderId,status)`；查询失败抛异常，不能降级 NONE。无 execution 绑定、UNKNOWN/FAILED 等状态的业务退款单仍为 EXISTS。
5. `pet_order.refund_order_id` 非空或 REFUND 返回 EXISTS → BLOCKED_BY_REFUND，不进入待服务；指针不一致需留下诊断，不能删除退款记录来恢复确认。

REFUND API 返回 NONE 的安全依据是**所有同单退款创建方共享同店 guard，持锁直到事务提交**，不是空查询本身的行锁。未来普通退款/商家拒单必须在同协议下创建退款单并同步 ORDER 退款事实；不得依赖异步退款事件投影后才阻断。迟到退款现有生产者已持同店 guard；本轮用真实模块查询和事务并发验收此边界，但不把测试夹具称为普通退款业务入口已完成。

并发结果：退款建单先提交，自动确认看到 EXISTS 后不确认；自动确认先提交，后续合法服务前退款仍允许按自身规则执行，不能因订单已待服务而拒绝自动全额退款。数据库回滚的退款建单不构成永久退款事实。没有退款单时，不凭“曾请求退款”的日志捏造已创建事实。

**待批准的边缘策略：** 当前待确认订单若有 `current_refund_application_id` 或 `current_aftersale_id`，但尚无退款单，本首轮切片暂不具备申请/售后权威 getter：保留待确认，返回可重试依赖错误并记录 `APPLICATION_FACTS_UNAVAILABLE`，不终结任务、不永久禁止履约。后续相关 Owner 补权威资格后再开放此组合。这是内部首轮支持边界，不能升级为“仅申请售后即永久禁止核销”的产品规则；也意味着此切片不能单独对外完整启用。

### 3.3 原子写入与确认事件候选

全部检查通过后，ORDER CAS匹配当前状态、轮次、截止、version：写 `PENDING_SERVICE`、`confirm_mode=AUTO`、`confirmed_at=DB UTC now`、version+1；同一事务只写一次状态日志和 `OrderConfirmedEvent.v1` Outbox。事务提交 ACK 丢失则通过已持久 ORDER确认日志/Outbox恢复，不另造事件。

候选事件 envelope 沿既有 IntegrationEvent（aggregateType=ORDER、aggregateId=orderId、eventVersion=1）；payload：`orderId`、`reservationId`、`storeId`（均String）、`confirmRound`（整数）、`confirmMode`（AUTO，本轮）、`confirmDeadline`、`confirmedAt`（UTC毫秒时间字符串）。不携带手机号、宠物敏感资料、原始渠道报文。未来 MERCHANT 方式沿同语义扩展时须由 ORD-001 同步。通知消费者仍未实现，不以 Outbox 写成功宣称用户收到消息。

优先复用订单字段与日志/Outbox，不预设新DDL。如果实现发现现有日志无法保存/唯一校验本轮成功命令、原截止和事件证据，须在编码前补充具体Schema候选送审，不以附加未经审阅的表绕过本稿边界。

## 4. 审批项 B：任务原子性、补建与停机恢复

### 4.1 初始任务

启用内部生产任务后，正常支付消费在原同库事务内追加：taskKey=`ORDER_AUTO_CONFIRM:{orderId}:0`，ownerModule=ORDER，taskType=ORDER_AUTO_CONFIRM，bizType=ORDER，bizId=订单ID，expectedVersion=null（轮次在payload），`submitted_execute_at=confirmDeadline`，初始execute_at同值。

payload严格包含 `orderId`、`expectedConfirmRound`、`expectedConfirmDeadline`。TASK构建 Handler adapter 时校验 task key/type/owner/bizId 与 payload 一致，再生成固定命令requestId；不得相信任务载荷提供的付款、退款或预约状态。

订单、预约确认、付款结果、状态日志、OrderPaid Outbox、自动任务必须共同提交/回滚。事件重放使用原taskKey和原截止，不延长三十分钟。迟到分支零自动任务、零正常OrderPaid。

### 4.2 缺失任务补建候选

默认关闭的内部 SYSTEM 命令 `repairMissingTask(context,orderId)`，requestId=`REPAIR:ORDER_AUTO_CONFIRM:{orderId}:0`，只针对首轮合法PENDING_CONFIRM/NORMAL付款订单。按A同事务核当前付款、退款、预约及期限后，以相同taskKey/payload/原始截止调用enqueueAt；由TASK模块校验现有不可变任务内容。已存在相同任务是幂等命中，不重置状态、execute_at、重试次数或租约；绑定不同则冲突并登记。

扫描候选由ORDER自己的Mapper按有界ID游标分页提供，只读定位、每单独立短事务；默认dry-run，输出候选/无需处理/事实冲突/已有任务终局异常的计数和脱敏ID。超过截止的订单保持原截止，不用“补建时刻+30min”。截止尚未来到也可补建以免遗漏。首轮不处理轮次1或缺NORMAL结果的历史行，不猜测回填付款证据。

TASK通过本模块接口返回当前任务状态。READY/RETRY_WAIT/RUNNING交原worker；SUCCEEDED但仍待确认、DEAD/CANCELED但当前轮次仍有效属于持久异常，不能静默成功，也不能删旧任务换号。候选由幂等repair命令在A相同事实核验后对**已到期**且无活动任务的当前单调用同一自动确认业务命令；保留原任务终态、原attempt与诊断记录，不直接改状态绕过业务。未来期限的异常终态单保持异常并在到期扫描重评；生产恢复扫描的启用须独立确认。

### 4.3 Worker、告警及开关

候选开关 `pet.order.auto-confirm.enabled=false` 控制内部命令与生产任务，`pet.order.auto-confirm.worker.enabled=false` 控制worker，`pet.order.auto-confirm.repair.enabled=false` 控制补建执行；dry-run可独立只读运行。worker/repair缺主开关、PAYMENT/SCH/REFUND事实依赖或Outbox时不得装配成可运行状态。

Worker复用租约/重试实现，建议本类型maxRetryCount=8、显式 `ORDER_AUTO_CONFIRM` 重试策略。NOT_DUE不得提前确认，保留原截止并安排重试；暂时依赖错误可重试，STALE/BLOCKED_BY_REFUND取消当前业务任务，成功/已成功结束。耗尽保留DEAD、错误码和attempt证据；恢复由上一节显式repair沿同一业务幂等命令处理，不能降级为运营普通代接单。

任务DEAD/长期依赖未知必须可查询和产生持久异常记录；复用仓库已存在且Owner合适的异常基础设施，若需新增表/事件则先追加候选DDL契约审阅。外部告警接收配置未具备时披露未接通；日志不是已送达告警。生产启用必须同时具备异常发现与恢复路径。

## 5. 后续切片和明确排除项

| 顺序 | 后续交付 | 本轮不能宣称 |
|---|---|---|
| 1 | A/B批准后同步正式API/Event/Scheduler/字段名与测试映射，实施首轮内部自动确认和恢复 | 本草案不是冻结契约；静态检查不是业务验收 |
| 2 | ORD-001商家确认/拒单及普通退款创建，接入同锁协议、RBAC、requestId和409语义 | 直接更新测试库模拟竞争不等于商家HTTP已实现 |
| 3 | ORD-003改期与TASK取消公共契约，READY/RETRY_WAIT/RUNNING旧任务取消和新round1原子创建 | 首轮已能完整改期或取消运行中旧任务 |
| 4 | 订单查询/DisplayOrderStatus、公开API、小程序流程，券积分通知和对账消费者 | 内部Outbox已完成用户可见流程 |
| 5 | 正式渠道参数、时区、终局语义齐备后真实联调与生产启用 | 离线fixture等于真实支付退款验收 |

## 6. 变更所有权与权威同步清单

- ORDER API/biz：自动确认/repair命令、正常付款产任务、自己的Mapper XML、状态日志与确认事件。
- REFUND API/biz：按order查询所有来源退款单事实，强制同库事务/同店guard；不新增普通退款渠道流程。
- TASK core：类型适配、状态读取/不可变提交核对；不在本轮假装已有改期取消能力。
- boot：默认关闭、依赖验证、离线MySQL集成；模块间只用公共API。
- 批准后同步07内部API、08事件、09调度、14/15测试映射及新增专项实施契约；SQL06字段名不改成confirmation_type。无HTTP/OpenAPI修改、无SSOT产品规则修改；若批准时改变产品规则须另记裁决。

## 7. 最低验收（原审批清单；2026-09-29执行证据见实施记录）

| 编号 | 必须证明的行为 | 关联既有测试 |
|---|---|---|
| AC-AUTO-01 | 正常付款产生唯一原截止任务；任一写入失败订单/预约/结果/Outbox/任务整体回滚 | PAY-012、CONF-002 |
| AC-AUTO-02 | 迟到付款、取消、LATE投影绝不产生或执行自动接单 | PAY迟到支付规则 |
| AC-AUTO-03 | 未到期不确认；跨时区/跨午夜/已过截止消费仍以paidAt+30min判断 | CONF-002 |
| AC-AUTO-04 | 到期一次推进；重复、并发worker、lease重领、提交ACK丢失仅一次日志与事件 | CONF-005、TASK-003 |
| AC-AUTO-05 | 退款建单与自动确认两种提交先后；普通来源/无执行绑定/失败状态退款单仍阻断 | REF-001、核销硬规则 |
| AC-AUTO-06 | 付款撤销/对账、预约失效、依赖不可用、伪造payload不放行 | PAYMENT/SCH权威事实 |
| AC-AUTO-07 | 缺任务补建保留原截止；重扫不重置任务；绑定冲突/DEAD/SUCCEEDED异常可发现和恢复 | Scheduler §31 |
| AC-AUTO-08 | 旧轮次/旧截止被当前事实挡住；round1未接入前不能声称成功 | CON-006部分防护；完整测试待ORD-003 |
| AC-AUTO-09 | 默认关闭/缺依赖不装配；生产SQL XML扫描、模块边界、契约、CI通过 | 架构门禁 |

商家真实确认/拒单并发和改期完整验收留给各自入口实现，不能在本切片记CONF-001/003/004/006全部通过。

## 8. 精确审批请求

推荐批准 A（SYSTEM命令、当前PAYMENT/SCH/REFUND事实、同guard退款并发、申请事实未知时暂缓、确认事件候选）与 B（原子定时任务、保留原截止的幂等补建、异常终态沿同业务命令恢复、默认关闭开关）。授权限于内部契约同步、实现及隔离测试；PR合并、生产迁移/开关、真实渠道交易仍走各自门禁。需要新持久表或改变上述边界时追加具体契约，不能用本稿概括批准尚不存在的DDL。
