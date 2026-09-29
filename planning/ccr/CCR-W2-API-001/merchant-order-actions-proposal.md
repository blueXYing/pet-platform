# ORD-001 商家确认、拒单及退款联动：契约候选

状态：D1_D2_D3_APPROVED（2026-09-29）。基线：PR91 合并提交 `1eb96ac74316c2aa9b66dae89f5d208b4c89b6d9`。

用户在逐项解释三项方案后明确回复：“批准以上三项，按推荐方案继续”。授权D1/D2/D3的正式契约同步、实现、测试和提交PR；开关默认关闭，不合并或生产启用。下文候选/待批准措辞保留原审批记录，以45号实施契约为准，不表示业务验收已完成。

实施接续及逐项测试证据见[实现记录](../../progress/2026-09-29/merchant-order-actions/IMPLEMENTATION.md)，下文§7的NOT_EXECUTED为审批时历史状态。

## 1. 来源及查明的缺口

| 权威来源 | 既定要求 / 当前缺口 |
|---|---|
| [SSOT §三、退款规则](../../../docs/00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md) | 待确认阶段商家处理；30分钟自动确认；拒单全额原路退款；退款单创建即阻断核销，最终退款成功才释放预约。 |
| [商家最终 PRD §5.3.1、§5.6、§7](../../../docs/01-prd/03-PRD-商家端-V1.0-最终基线.docx) | 拒单分类必选、原因5–200字且过滤敏感词；接单内部备注0–200字；已确认不得普通拒单。主账号及核销员的订单处理权限有产品依据，不能擅自删除。 |
| [HTTP10 §4.2/4.3、准入矩阵](../../../docs/04-api/10-HTTP-API-Contract-v0.4.md) / [OpenAPI11](../../../docs/04-api/11-OpenAPI-Core-v0.4.yaml) | 已有 confirm/reject 路径，但请求、回执、原因编码未完整冻结；准入提示不是命令授权。FROZEN 写入边界仍未裁决。 |
| [内部API07 §7/§9](../../../docs/04-api/07-内部API-Contract-v0.6.md) | confirmOrder/rejectOrder/createRefund 有概念定义；缺当前调用所需完整 DTO、来源证明和成功后预约释放公共 API。 |
| [公共幂等23号](../../../docs/04-api/23-公共接口与幂等契约补充-v0.1.md) | 独立准入占号、执行时再次鉴权、不可变首成功回执；ORDER 日志 request_id 普通索引不能代替公共命令幂等存储。 |
| [退款42号](../../../docs/04-api/42-Late-Payment-Refund-Contract-v0.1.md) / [执行表42号](../../../docs/03-database/42-Late-Refund-Execution-Schema-v0.1.sql) | 当前执行器、PAYMENT退款校验和ORDER进度投影限定 LATE_PAYMENT_TIMEOUT；late_event_id 非空。不能给商家拒单伪造迟到支付事件。 |
| [自动接单44号](../../../docs/04-api/44-Auto-Confirm-Execution-Recovery-Contract-v0.1.md) | 已有同店 guard、正常付款/预约/全部退款来源事实核验。普通拒单须加入相同互斥协议。 |

产品拒单分类已有五项：排期冲突、人员不足、宠物情况不匹配、门店临时停业、其他；本次仅提议技术编码，不新增分类。

## 2. 推荐批准范围与实施顺序

**D1：先交付主账号、首轮订单的确认/拒单后端切片及拒单退款闭环，默认关闭。**

1. 先同步正式 Contract / Schema / Event / 错误映射，再实现可信主账号命令入口、幂等和当前授权检查。
2. 接确认/拒单原子事务，再扩展 REFUND/PAYMENT 的 MERCHANT_REJECT_ORDER 来源及退款最终成功投影、SCH 释放。
3. 完成真实 MySQL 并发、故障与回放验收，提交实现 PR 审阅；不合并、不生产启用。

本切片只支持 round0；不是重新定义角色权限。核销员等员工真实账号映射及授权仍须后续接通，不能用 staff_profile 或工作台 allowedActions 冒充授权。完整 ORD-001 不能因此记 DONE。用户主动服务前退款沿 REF-001 后续实现，不冒称本切片已支持所有普通退款来源。

既有范围归属：ORD-001 负责 ORDER；REF-003/PAY-001 负责退款/支付来源扩展；SCH-003 负责预约释放；MER-001/AUTH-001 负责主账号当前身份和门店授权。不得把这些模块直接塞进 ORD-001 的原 Allowed Modules；按既有 Issue Owner 划分子交付，不新增 Epic。

下一顺序保持 ORD-003 一次改期/round1 → 公开订单读侧、小程序流程及消息等消费者。内容审核真实依赖、员工授权、FROZEN 裁决、生产渠道参数未齐备时不对外启用。

## 3. 截止及竞争语义（D2，需明确批准）

推荐：首轮截止仍为渠道 `paidAt + 30min`。商家新命令在获得同店 guard、读取当前事实后，以 DB UTC 毫秒时间判断：`now < confirmDeadline` 才可确认/拒单；等于或超过截止不再执行商家新决定，返回409及刷新提示，自动任务继续按原截止执行。成功命令重放不重新受时间门槛限制，但必须重验当前访问权限。

截止前多个有效操作由同 guard 的事务提交结果串行决定；先回滚者不占用业务胜出资格。自动确认已提交后，普通拒单返回 ORDER_STATE_NOT_ALLOWED。错误使用既有 ORDER_CONFIRM_DEADLINE_PASSED 时须同步12号说明，明确“已到截止但任务尚未落库”也属于该错误；不能仅改代码扩义。

**需审阅的差异：** PRD写“以先到达服务端的请求为准”，已批准自动接单方案按先提交的权威事实处理。推荐采用上述“锁内资格校验＋事务提交”定义，不承诺跨实例网络到达顺序；截止前到达、等待锁直到截止后的请求也会被拒绝。这是本次明确提请裁决的边界，不当作已批准规则。若要求严格按入口到达时间保留资格，则需独立持久排序/超时协议，不能用客户端时间或无持久性内存时间替代。

## 4. HTTP、内部命令和鉴权候选

沿用 `POST /api/v1/merchant/orders/{orderId}/confirm`、`.../reject`，UUID `X-Request-Id`，未知字段/重复JSON键拒绝。orderId 等 ID 对外为正十进制 String，金额为两位小数 String。

| 命令 | 候选请求 |
|---|---|
| confirm | `expectedConfirmRound: 0` 必填；`internalNote` 可省略，提供时为0–200 Unicode码点字符串，null非法。保留原文；省略和空串在幂等规范输入中保持不同。 |
| reject | `expectedConfirmRound: 0`、`reasonCode`、`reasonText` 必填；reasonText为5–200 Unicode码点，非全空白，保留原文。 |

技术原因枚举建议：SCHEDULE_CONFLICT（排期冲突）、STAFF_UNAVAILABLE（人员不足）、PET_NOT_MATCHED（宠物情况不匹配）、TEMPORARY_CLOSURE（门店临时停业）、OTHER（其他）。拒单原因对C端可见；内部备注只能由本店有权人员读取，不能进入C端 DTO 或通用事件。

ORDER公共命令候选 `MerchantOrderCommandApi.confirm/reject`；context来自真实会话和服务器主账号解析，不能接受body传入actor/store权限。首切片沿现有主账号 USER principal，通过 MERCHANT Owner 公共接口核实其当前门店负责人关系。后续员工身份接入另有明确映射，不把USER伪装为任意MERCHANT_STAFF。

每次执行及重放均检查当前会话、成员有效性与资源范围；跨店/撤权/停用不能回放旧结果。OFFLINE门店已存订单依既有履约关系处理，不按新订单准入一刀切阻断；FROZEN未裁决的写入组合保持暂未支持，不将其固化为永久产品规则。服务下架不改变已付款订单快照。

拒单必须通过可信敏感词检查；没有真实审核依赖时入口保持关闭/明确依赖不可用，不装配生产“总是通过”实现。测试替身只用于隔离验收。文本审核在长锁外完成，并把结果与精确文本摘要、策略版本绑定；执行时验证绑定，失败不产生退款。

候选成功回执：orderId、decisionId、confirmRound、action(CONFIRM/REJECT)、orderStageAtCommit、decidedAt、refundOrderId（confirm为null）。不含内部备注或付款敏感信息。首次业务成功回执持久保存；重试返回原回执，当前状态由读侧查询，不能把异步变化混进同一幂等回执。需同步HTTP10“返回最新状态”的旧表述，并以23号幂等契约约束为准。

错误沿12号：非法字段 COMMON_INVALID_ARGUMENT；状态不允许 ORDER_STATE_NOT_ALLOWED；已有退款 ORDER_REFUND_ALREADY_CREATED；相同幂等键不同有效载荷 IDEMPOTENCY_KEY_CONFLICT；锁竞争 ORDER_OPERATION_BUSY；事实不全/审核不可用 COMMON_DEPENDENCY_UNAVAILABLE。权限与不存在沿既有会话和防枚举响应，不泄漏其他门店订单。

## 5. 事务与状态候选

公共幂等分两阶段：当前认证与静态校验后独立事务占号并提交；再开启独立 READ_COMMITTED 业务短事务，在该事务内先锁占号记录，再锁同店 guard。业务失败保留原输入绑定，后续只能同载荷重试。业务成功与回执同提交，提交ACK丢失后通过持久回执恢复。

业务顺序：幂等执行锁 → 门店guard → MERCHANT当前授权 → ORDER订单/NORMAL付款结果 → PAYMENT当前成功事实 → SCH当前CONFIRMED预约 → REFUND全部来源存在性 → 资格与截止 → 本轮决定、业务写入、日志、Outbox、成功回执。各Owner只访问自己的Mapper XML，通过公共API在同DataSource、同事务中协作；拒绝嵌套调用产生另一个REQUIRES_NEW业务事务。跨模块不得互调biz或直接查表。

- 确认：PENDING_CONFIRM/PAID/UNVERIFIED 且无退款 → PENDING_SERVICE、confirm_mode=MERCHANT、confirmed_at；一次决定、一次状态日志、一次OrderConfirmedEvent。44号七字段事件扩展confirmMode允许MERCHANT，不加入内部备注。
- 拒单：同样核当前资格和无退款；推荐将order_stage写CANCELED、canceled_at写DB时间，以不可变拒单决定记录关闭原因；**同事务**创建REFUND的FULL/MERCHANT_REJECT_ORDER退款单、不可变执行绑定、durable任务，并写ORDER.refund_order_id、拒单日志/事件/回执。任何一步失败整体回滚；不能等异步事件到达才创建退款边界。
- 拒单退款初始不写“已退款”、不设置成功金额、不释放预约；ORDER显示状态由退款事实优先计算为退款中。refunded_amount仅在验证最终成功后投影；退款失败/UNKNOWN仍退款中，进入异常处理。
- REFUND最终成功由经过权威事实核验的事件投影驱动ORDER进度和SCH释放；消费claim、ORDER投影、SCH释放在同事务完成。允许投影延迟但绝不提前释放；重复/乱序事件不得倒退或释放别单预约。
- 仅有退款申请/售后引用但尚无权威getter时沿44号可重试依赖错误，不假定已经退款，也不改“refund_order创建才禁止核销”的硬规则。

支付成功事件再次消费必须识别“本次正常付款之后合法拒单/退款”的不可变证据，不恢复待确认、不补活自动任务；未知关闭原因不能吞作成功。拒单后旧自动任务依据当前状态返回STALE；不删除任务和attempt来伪造成功取消。

## 6. 新持久化及跨模块契约（D3，需明确批准）

以下是Schema字段级候选，不是生产迁移。批准后先形成版本化DDL及兼容测试，任何额外表/来源/语义超出本表须追加CCR。

| Owner / 候选对象 | 字段和关键约束 |
|---|---|
| ORDER `order_merchant_command` | Snowflake id；command_namespace、actor_type、actor_id、authority_scope、request_id全非空，逻辑五元组二进制比较唯一；canonical_version、payload_sha256、受保护canonical_bytes；state=RESERVED/SUCCEEDED；result_version、受保护result_bytes、created_at/updated_at。成功结果不覆盖；失败不释放原绑定；不借用USER/MERCHANT Owner的幂等Mapper。 |
| ORDER `order_merchant_decision` | Snowflake id；order_id、store_id、confirm_round、command_id、action、operator_id、decided_at、event_id、refund_order_id；reason_code/reason_text仅REJECT使用，internal_note仅CONFIRM使用；UNIQUE(order_id,confirm_round)、UNIQUE(command_id)、UNIQUE(event_id)。记录不变，成功退款不改写原决定。文本访问控制及日志脱敏与回执分离。 |
| REFUND `refund_execution` 增量 | 新增source_type及source_event_id；历史回填LATE_PAYMENT_TIMEOUT和原late_event_id。late_event_id改为可空：迟到来源必须与source_event_id相等且非空，MERCHANT_REJECT_ORDER必须为空；正常来源source_event_id绑定拒单eventId。旧唯一订单/付款/退款号和FULL金额约束保留。先升级兼容读写再收紧非空/check，不能运行一条ALTER就认定迁移完成。 |
| ORDER来源事实API | `OrderMerchantRejectFactsApi`提供门店定位及guard内权威拒单/正常付款绑定查询；依据决定、订单、付款结果、退款绑定逐项核验。拒单原子创建时先建立ORDER决定事实和预分配refundId再调用REFUND，均未提交；允许本事务读取自身写入。 |
| REFUND创建/执行事实API | 增加明确MERCHANT_REJECT_ORDER来源命令与sourceType/sourceEventId；REFUND重验ORDER来源、PAYMENT当前成功事实，原实付全额且原渠道。不得由传入金额/事件独自授权。避免既有lateEventId字段对普通来源无定义。 |
| PAYMENT退款校验 | 按两种已批准来源调用各自ORDER事实API；LATE仍须原CANCELED/PAYMENT_TIMEOUT+EXPIRED，MERCHANT_REJECT_ORDER核拒单+NORMAL付款。未知来源拒绝。相同refundNo、MAY_HAVE_SENT/UNKNOWN只查单、不重发退款。 |
| SCH `ReservationRefundReleaseApi` | guard内调用，以退款最终成功证明绑定orderId/reservationId/storeId/refundId；CONFIRMED→RELEASED，准确释放原占用；重放校验相同来源才NOOP。不能靠调用方boolean宣称退款成功，须核REFUND权威成功事实。 |

事件：OrderRejectedEvent.v1候选payload为orderId/reservationId/storeId/decisionId/refundOrderId（String）、confirmRound（整数）、rejectedAt（UTC毫秒）、reasonCode；reasonText按授权查询获取，不广播到所有消费者。事件用于通知/验证已建退款，不能再异步创建第二笔退款。RefundCreated/RefundSucceeded载荷及事实DTO需同步来源字段和严格解码器；所有现有迟到来源消费者保持兼容。新来源被旧消费者识别为不归属时安全NOOP，不把未知或损坏来源静默忽略。

新增业务/HTTP/Worker/成功投影开关全部默认false。启用命令而缺少退款执行、投影或SCH释放依赖时拒绝装配；不得默认开启旧迟到退款worker。自动接单现有开关也不修改默认值。方案不包含生产执行DDL、真实支付退款、自动部署或合并。

## 7. 验收计划（全部 NOT_EXECUTED，批准后实现）

| 编号 / 追踪 | 必须通过的真实模块验收 |
|---|---|
| MO-01 / CONF-001 | 当前主账号、首轮合法付款预约确认成功；MERCHANT字段、唯一日志/事件/回执；内部备注不泄漏。 |
| MO-02 / CONF-003 | 五类拒单、原因长度/空白/敏感词；创建唯一全额退款/任务；拒单原因能由合法C读侧消费，首切片仅验证投影契约。 |
| MO-03 / CONF-004 | 手动或自动已确认后拒单409；其他状态、round1、跨店、撤权/过期会话均拒绝。 |
| MO-04 / CONF-005 | 截止前/相等/之后、时区/午夜、等待锁越过截止；商家×自动任务双方竞争及事务回滚后重试。 |
| MO-05 / 23号幂等 | 并发同键同载荷、同键异载荷、不同目标、执行失败后的绑定、成功ACK丢失、成功后撤权；均无重复决定/退款/事件。 |
| MO-06 / CONF-003、VER硬规则 | 所有来源/无执行绑定/失败退款单阻断；拒单建单及指针同提交；注入每个写入故障验证ORDER/REFUND/Outbox/TASK/回执整体回滚。 |
| MO-07 / REF-011～013、FLT-006/007 | 已验签付款原金额与原渠道；一次发送意图、UNKNOWN只查、超时/ACK丢失/回调查单竞态、不可伪造成功。 |
| MO-08 / SCH-003 | SUCCESS前预约保持CONFIRMED；成功投影与释放原子；重复/乱序/错单/无权威证明不错误释放；失败保持占用。 |
| MO-09 / PAY-004回归 | 历史迟到退款绑定回填、旧载荷/DTO兼容；原订单关闭及EXPIRED预约不恢复；全部既有迟到退款测试继续通过。 |
| MO-10 / ORD-002 | 拒单后付款事件重放、自动任务和repair不复活订单；商家确认后无第二个自动确认；全部PR91自动确认验收继续通过。 |
| MO-11 / 权限及故障 | OFFLINE已存履约按关系授权；员工未接入/FROZEN未决明确未支持；审核依赖缺失关闭；所有运行开关默认false、依赖不齐启动失败。 |

后续实现执行架构依赖、SQL/XML、DisplayOrderStatus门禁、契约smoke、Java21 Maven verify及完整CI；PR须列出实现范围与尚未完成的员工/读侧/小程序/真实渠道。文档静态通过不计为上述业务验收通过。

## 8. 审批与下一动作

推荐一并批准D1（切片与角色边界）、D2（截止/竞争定义）、D3（新增存储和普通退款来源/释放协议），随后完成正式契约、实现和测试，提交PR供审阅，暂不合并或生产启用。

申请这次审批的依据是 [WORK_EXECUTION_PROTOCOL.md §4](../../../WORK_EXECUTION_PROTOCOL.md)：“以下行为必须人工批准：……产品裁决；……Contract 重大变更”；[AGENTS.md](../../../AGENTS.md)要求“Contract 缺失走 CCR”。此次跨ORDER/REFUND/PAYMENT/SCH的新来源协议、新Schema及截止语义不在PR91已批的“无新DDL、无HTTP”范围内。PR91的批准、合并及测试无需重新批准。
