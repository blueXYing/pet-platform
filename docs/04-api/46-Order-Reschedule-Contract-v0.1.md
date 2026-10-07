# 46 — ORD-003 一次改期与第二轮确认

状态：APPROVED，2026-09-29用户明确批准R1、R2、R3。依据：SSOT改期规则、最终C端PRD §5.1.19/§5.1.17、商家PRD §5.3.2、36号ROC-6，以及[具体批准方案](../../planning/ccr/CCR-W2-API-001/order-reschedule-proposal.md)。本契约覆盖43/44/45中的“仅round0”限制，其余规则不变。

## 交付边界与开关

本批提供ORDER/SCH/TASK内部内核、第二轮自动/手工确认、拒单退款衔接及隔离MySQL验收。`pet.order.reschedule.enabled=false`；`pet.order.reschedule.http.enabled=false`，HTTP置true直接装配失败。本批不注册C端改期路由，不提供小程序页面、临时版本查询、通知发送或生产迁移。

> **HTTP 切片注记（2026-10-07）**：前置"真实核销码失效"（47号真实凭证 Owner 失效）与"公开版本读侧"
> 均已到位，`POST /api/v1/c/orders/{orderId}/reschedule` 已注册为默认关闭的 C 端路由
> （`pet.order.reschedule.http.enabled=true` 且 `pet.order.reschedule.enabled=true` 时装配；HTTP 开而内核
> 关仍装配失败）。首交与受保护重放返回同一首次成功回执（200）；严格 JSON 与 oneOf 分支形状在 HTTP 边界
> 校验；本契约全部准入/交换/任务/核销失效语义不变，仍在内核。公开版本读侧以 10号 §3.7 详情/列表投影
> 新增 `orderVersion`（连同 `serviceId`/`storeId`）落实——该读侧增补由本切片登记。小程序改期页与
> 订单详情入口随本切片交付（canReschedule 门控、CAS 409 重读、§3.4+39号选窗 windowId 消费）；
> 通知消费与生产启用仍不在本切片。

内部装配必须具备支付、预约保护、首轮任务生产、商家命令基础能力，以及真实会话、保护密钥、全来源退款事实和`VerificationRescheduleFenceApi`。核销Owner接口在同一DataSource、同一guard事务中失效旧码，并返回持久证据ID、orderId、rescheduleId。没有真实Provider时装配失败；不能使用默认空成功实现。真实动态码生命周期仍NOT_IMPLEMENTED，隔离QA明确使用带持久化记录的测试替身，不将其视作真实核销链验收。

## 命令与回执

未来HTTP位置沿用`POST /api/v1/c/orders/{orderId}/reschedule`，目前NOT_IMPLEMENTED。Header `X-Request-Id`为UUID。actor取当前真实USER会话；首次执行、重放均验证当前账号及订单归属。

- `expectedOrderVersion`：必填非负十进制String，可表示signed BIGINT；不得浮点/前导零。失败409 COMMON_CONFLICT。成功重放不重新要求原版本仍为当前版本。
- 到店：`appointmentStart`、`appointmentEnd`、`selectedGeneralWindowId`必填；不接收接送字段。
- 接送：`pickupStart`、`returnStart`、`selectedPickupWindowId`、`selectedReturnWindowId`必填；不接收到店字段。仍为完整两原窗，返程开始至少晚于上门开始120分钟。
- 日期是带偏移ISO-8601，秒及小数必须为零；执行规范统一UTC。到店时长使用原订单服务时长快照；不重算当前商品价格、优惠或时长。未知字段、显式null、重复JSON字段不得放行；HTTP未接入前此条为接口验收要求。
- 不允许换商家、门店、服务、履约方式、宠物、地址、员工或支付/权益快照。服务下架不阻止既有履约；商家/门店当前ACTIVE、OFFLINE允许，FROZEN及未知经营状态不放行。

首次成功回执：orderId、reservationId、rescheduleId（String ID），confirmRound=1，orderVersion（String），orderStageAtCommit=PENDING_CONFIRM，appointmentStart/appointmentEnd，pickupStart/returnStart（到店null），rescheduledAt、confirmDeadline。回执保存首提交结果；后续确认/退款不会改写它。无用户敏感信息。

五元组幂等：`order.reschedule / USER / userId / CONSUMER / UUID`，二进制比较。独立Admission保留受保护规范输入、SHA-256与版本；业务失败保留绑定；同key异参/异订单409 IDEMPOTENCY_KEY_CONFLICT。同key同参并发只交换一次，提交成功但ACK丢失可返回首回执。独立占号不在同店guard下等待。

## 原子交换及历史

只接受PENDING_CONFIRM/PENDING_SERVICE、PAID、UNVERIFIED、reschedule_count=0。锁内DB UTC毫秒时间严格早于原预约开始且新开始仍在未来。无实质区间变化（即使换windowId）409 COMMON_CONFLICT，不扣次数、不刷新确认时钟。任何来源refund_order阻止改期；未接齐的售后/退款申请事实503，不默认为无退款。

锁序：命令执行行→同店guard→ORDER/Owner事实。RC顶层事务内：

1. 核真实USER会话、版本、订单与原NORMAL支付证明、当前退款事实、原预约/完整claim及当前指派。
2. SCH先完整验证旧预约，再仅在求解模型替换本订单旧claim；不在DB提前释放旧预约，也不把新旧预约双算。其他订单占用不变；逐原窗容量、跨服务共享员工、完整区间相交闭包及预算规则沿用36号。原最终指派固定，不可悄悄换人。
3. 保留预约ID和1/2条claim身份/kind，原子交换windowId及完整时间。写SCH不可变新旧快照、ORDER不可变改期事实，版本各增1；ORDER改PENDING_CONFIRM、次数1、清原确认信息，新截止=改期DB成功时间+30分钟。
4. 核销Owner失效旧码，TASK取消旧任务并创建round1任务，状态日志、`OrderRescheduledEvent.v1`、首成功回执一起提交。任一失败全部回滚。
5. SCH提交前通过ORDER公开`OrderRescheduleCommitApi`要求相同command/change/reservation及版本的本事务改期事实，重新核SCH最终父预约/claim，不能独立提交只改预约的事务。

Schema见[SQL46](../03-database/46-Order-Reschedule-Schema-v0.1.sql)：ORDER `order_reschedule_command`、`order_reschedule_record`，SCH `schedule_reservation_change`。保留`UNIQUE(order_id)`，不新建第二个订单/付款/预约。历史不参与容量。ORDER事实含旧stage/mode/confirmedAt/deadline、新旧时间/版本、round0→1、SCH变更/核销fence/事件ID和旧任务处理结果。

## 旧任务取消和第二轮

TASK `TaskCancellation`要求调用方可写事务并严格核对原taskKey、Owner、业务绑定、原payload、原submittedExecuteAt、重试策略。

| 原状态 | 结果 |
|---|---|
| 不存在 | MISSING，可继续新任务 |
| READY / RETRY_WAIT | CANCELED，保留提交快照及历史 |
| RUNNING | CANCELED，version增1、清租约，未结束attempt完成NOOP/OWNER_CANCELED；旧Worker心跳和完成CAS均失败 |
| SUCCEEDED / DEAD / CANCELED | TERMINAL，不重写终态或尝试历史 |
| 绑定冲突 / 未知状态 | 503，整个业务事务回滚 |

新键`ORDER_AUTO_CONFIRM:{orderId}:1`，payload的expectedConfirmRound=1、expectedConfirmDeadline为新截止，submittedExecuteAt保持不可变，executeAt用于调度。round0接口及任务格式保持兼容。repair requestId为`REPAIR:ORDER_AUTO_CONFIRM:{orderId}:{round}`，旧round0 repair/Worker返回STALE，不能复活首轮任务。

round1资格由不可变改期事实及原NORMAL支付证明共同授权，不再使用paidAt+30分钟。手工命令expectedConfirmRound接受0/1，canonical及决定唯一键包含轮次；重放原轮决定返回原回执。新决定必须锁内DB时间严格早于本轮截止，自动确认从截止开始。原支付事件重放必须核改期事实后NOOP，不重置时间/任务。

round1拒单继续使用MERCHANT_REJECT_ORDER，sourceEventId必须是当前轮REJECT决定；仍全额原路退款，成功前保留当前预约，最终成功才释放。不得让round0决定或任意改期事件独立授权退款。UNKNOWN仅查询原退款号，禁止重复提交；沿用45号。

## 事件

`OrderRescheduledEvent.v1`，Owner ORDER，aggregate=ORDER/orderId，与业务同事务Outbox。payload精确字段：orderId、reservationId、storeId、rescheduleId、confirmRound=1、oldAppointmentStart、oldAppointmentEnd、oldPickupStart、oldReturnStart、appointmentStart、appointmentEnd、pickupStart、returnStart、rescheduledAt、confirmDeadline。接送不适用字段null；ID为String、时间UTC毫秒。无地址、备注、姓名或手机号。通知消费未交付。

## 验收与迁移

RES-001～006/CON-006及提案RS-01～11由`OrderRescheduleAcceptanceTest`、既有确认/支付/退款验收和装配门禁覆盖；真实核销/公开读侧/C HTTP/微信端E2E仍单列未实现。所有通过数量以最终实际Surefire/CI记录为准。

SQL46不自动执行。已有实例先核实SQL45自动生成的round CHECK名称及表达式，再精确替换该约束，保留其余检查；默认关闭下先扩Schema再部署兼容读写。本仓库的SQL46用于全新规范Schema的隔离测试。生产迁移、启用、PR合并不在本批准范围。
