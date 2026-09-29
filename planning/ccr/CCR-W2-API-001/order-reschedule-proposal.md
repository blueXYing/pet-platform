# ORD-003 一次改期、预约交换与第二轮确认实施提案

状态：PROPOSED_REQUIRES_REVIEW。2026-09-29；基线：PR92合并`be1cb681057caf2276df8ed436ac2111b4fc7bd4`。用户已授权按推荐顺序推进；以下新增持久化、TASK取消协议和第一批交付边界尚未作为具体方案批准。本文是可审阅候选，不是生效Contract或生产DDL。

## 1. 已经批准，直接沿用

SSOT改期规则、最终C端PRD §5.1.19、商家PRD §5.3.2、36号ROC-6及09号§14共同规定：

- 每单最多一次，当前待确认/待服务且未核销，预约开始前，同商家、同门店、同服务；不得更换履约方式、宠物、地址、服务人员、实付金额或优惠权益快照。
- 新预约可行才交换；失败保留原预约、占用、指派、次数及确认周期。仍采用分钟区间、GENERAL一条或PICKUP/RETURN两条完整原窗，返程开始至少晚于上门开始120分钟。
- 保留商家原最终指派，必须能覆盖新时间；否则409，不暗中换人。无指派只作内部完整容量匹配，不写自动人员。
- 成功后重新待确认，次数=1，确认截止按改期成功DB时间+30分钟；旧自动任务不能覆盖新轮次。原退款时间基准切换为新预约开始时间。
- 改期不退款；改期后的待确认可由商家确认或拒单，拒单仍按原实付全额原路退款，最终退款成功才释放当前预约。
- C端PRD §5.1.17另明确改期使旧核销码失效。不能遗漏，也不能用“当前未核销”假装已经实现核销码失效。

以上业务规则不重新申请批准。完整ORD-003仍由原Issue承接，不新增Epic，也不把ORD-001/SCH-003整体标DONE。

## 2. 查明的实际缺口

| 位置 | 当前事实 | 必要衔接 |
|---|---|---|
| SQL06 `schedule_reservation` | UNIQUE(order_id)，ORDER也唯一绑定reservation_id | 不能直接为同一订单插入第二条预约；需冻结交换及历史保存方法 |
| SCH `ScheduleCapacityProofApi.checkNewReservation` | 只会在现有占用上增加新候选 | 改期应先验证旧占用，再用新候选替换旧预约作完整证明，不能把自己重复计两次，也不能先释放旧占用 |
| HTTP10 / OpenAPI11改期请求 | 仅时间字段，无完整原窗ID、版本及回执；接送仍错误要求到店起止 | 需同步分支输入、当前版本并发保护、23号独立幂等和首成功回执 |
| TASK公共接口 | 有提交、领取、心跳和Worker完成，没有业务事务内按原绑定取消的入口 | 不能让ORDER跨模块更新async_task；RUNNING租约及attempt必须一致处理 |
| 43/44/45实现 | 自动确认、修复、商家命令、拒单来源证明限定round0；45号有CHECK(confirm_round=0) | 仅把reschedule_count加一会导致后续自动/手工确认、拒单退款和支付重放失败 |
| 核销模块 | API/BIZ目前只有Marker/package-info；无真实动态码失效实现 | 首批不能宣称已开放完整用户改期。需要强制Owner依赖和明确未交付范围 |
| C端订单读侧 | 尚无已交付的完整订单版本/详情入口 | 不假造新页面已能提交expectedOrderVersion；公开读侧与小程序另接 |

## 3. 请求批准的三项（推荐一起批准）

### R1：保留预约身份，原子换时段并新增不可变历史

保留同一`reservationId`和`UNIQUE(order_id)`。在同店guard及同一个主库RC事务内先完整证明“其他有效预约＋本订单新时段”可行，再更新同一预约和既有1/2条claim、增加版本；一并保存新旧完整快照和交换审计。事务提交前外部仍看到原预约，失败整个回滚；不出现已提交的先释放后占新窗口，不另造第二个付款/订单。

建议新增三个Owner自有表（§5字段级清单）：ORDER命令幂等、ORDER改期事实、SCH预约变更历史。保留原下单服务/宠物/价格/权益快照；旧预约时间、原窗、claim和确认记录放在不可变历史中。现有claim身份和kind保留，只交换其windowId/区间，履约方式不变所以基数不变。历史不再计容量，当前预约仍CONFIRMED。

### R2：明确旧任务取消及第二轮完整联动

TASK提供同DataSource、加入调用方事务的严格取消接口。旧READY/RETRY_WAIT变CANCELED；RUNNING也变CANCELED、递增version、清租约并完成其未结束attempt为NOOP，使旧Worker心跳/完成CAS失败。已经SUCCEEDED/DEAD/CANCELED保留原终态及尝试记录，不改写历史；任务原绑定冲突/未知状态503并回滚。

新round1任务与预约交换、ORDER更新、Outbox及首回执同提交，executeAt=本次改期成功时间+30分钟。round1手工确认、拒单、自动确认、修复扫描、付款事件重放和拒单退款来源证明必须同时接通。旧轮次Worker/repair只返回STALE，不复活round0。

### R3：先交默认关闭的后端内核，不用假依赖开放用户入口

首批交真实ORDER/SCH/TASK写入、round1确认及退款联动、正式接口/Schema同步和数据库并发测试；暂不启用C端改期HTTP/小程序，不声称完成核销模块或完整ORD-003。内部改期命令强制依赖`VerificationRescheduleFenceApi`等价的核销Owner接口（最终Java名随正式契约冻结），要求在同一guard事务内失效旧码并返回持久证据；没有真实实现则改期装配失败。仅隔离QA允许显式测试替身，证据必须标明，不能把它当真实核销链验收。

新增请求候选包含`expectedOrderVersion`，对应PRD“以服务端版本号为准”。当前公开订单读侧未交付，因此本轮只冻结HTTP请求/回执且标NOT_IMPLEMENTED，不新造临时版本读取路由；后续真实读侧和核销失效依赖到位后接HTTP。本轮也不新增短信、通知发送或生产迁移。

同一套原窗和履约区间完全不变的请求，建议作为409 `COMMON_CONFLICT`拒绝：不扣唯一改期次数、不重启30分钟。这个无实际变化的边界属于本项待批准的明确约定；不得静默把一次机会用掉。不同原窗但实际区间完全相同同样不通过改期刷新确认周期。

## 4. 拟冻结请求、回执和授权

沿既有`POST /api/v1/c/orders/{orderId}/reschedule`合同位置，不提前注册运行路由。`X-Request-Id`为UUID；actor只取真实USER会话，order.user_id必须为当前用户。执行与成功重放都检查当前会话/账号可用及订单归属；其他用户/不存在按现有防枚举规则拒绝。

| 字段 | 候选约定 |
|---|---|
| expectedOrderVersion | 必填非负十进制String，对应ORDER BIGINT version，避免JSON精度损失；版本不符409 COMMON_CONFLICT；成功重放不重新做版本门槛 |
| 到店 | appointmentStart/appointmentEnd为带偏移ISO-8601分钟精度；selectedGeneralWindowId为正十进制String。按原已支付服务时长快照校验，不使用当前商品改价/改时长 |
| 接送 | pickupStart/returnStart及selectedPickupWindowId/selectedReturnWindowId必填；开始必须匹配原窗，两方向完整claim与120分钟规则不变 |
| 互斥字段 | 到店不带接送字段，接送不带到店起止/GENERAL；无关字段、重复字段、显式null、未知字段拒绝；不接收金额、商家/门店/服务ID或员工ID |
| 成功回执 | orderId/reservationId/rescheduleId、confirmRound=1、orderVersion（String）、orderStageAtCommit=PENDING_CONFIRM、appointmentStart/appointmentEnd、pickupStart/returnStart（到店null）、rescheduledAt、confirmDeadline；不含人员姓名、地址、付款敏感信息 |

订单、原预约和原支付当前事实须一致；只允许NORMAL正常付款，无任何来源refund_order、无已核销。退款申请/售后引用存在而权威getter未接齐时503，不冒充“无退款”或修改退款硬规则。SERVICE后续价格/名称/时长不重算原快照；服务下架不能直接调用仅面向新下单的checkBookable而破坏既有履约。FROZEN等未裁决写入保持未支持；其他经营边界若需扩展另作明确裁决。

开始边界用锁内DB UTC毫秒时间，必须严格早于原预约开始且新实际开始仍在未来。接送基准为当前预约的pickup开始，主区间及两条claim遵循38号；不新增“新服务必须至少30分钟以后”的产品限制。round1商家决定继续严格早于本轮deadline，相等/之后由自动接单处理。

已知失败沿12号：ORDER_RESCHEDULE_LIMIT_REACHED、ORDER_RESCHEDULE_AFTER_START、ORDER_STATE_NOT_ALLOWED、ORDER_REFUND_ALREADY_CREATED、SCHEDULE_SWAP_FAILED/SCHEDULE_CAPACITY_EXCEEDED、SCHEDULE_PICKUP_RETURN_INTERVAL_INVALID、COMMON_CONFLICT、IDEMPOTENCY_KEY_CONFLICT。事实缺失/损坏/搜索预算耗尽/提供器失败为503 COMMON_DEPENDENCY_UNAVAILABLE；不能把未知归为容量不足。

## 5. 字段级Schema候选（非可执行DDL）

| Owner / 对象 | 字段与约束 |
|---|---|
| ORDER `order_reschedule_command` | Snowflake id；namespace/actor_type/actor_id/authority_scope/request_id二进制五元组唯一（namespace=order.reschedule、USER、当前userId、CONSUMER、UUID）；canonical_version/payload_sha256/受保护canonical_bytes；state RESERVED/SUCCEEDED、result_version/受保护result_bytes、created_at/updated_at。规范包含orderId、expectedOrderVersion和明确用户选择，不包含实时证明或生成ID。不同订单共用同用户同key必须冲突 |
| ORDER `order_reschedule_record` | id、order_id（唯一）、command_id（唯一）、event_id（唯一）、user_id、store_id、reservation_id、old_order_version/new_order_version、old_reservation_version/new_reservation_version、old_order_stage/old_confirm_mode/old_confirmed_at/old_confirm_deadline、old/new预约与接送时间、rescheduled_at/new_confirm_deadline、from_round=0/to_round=1、schedule_change_id（唯一）、verification_fence_id；记录不可变，作为第二轮计时及原支付重放的权威依据，不重写原订单快照 |
| SCH `schedule_reservation_change` | id、reservation_id、order_id、store_id、command_id、old_version/new_version、old_snapshot/new_snapshot JSON、changed_at；唯一(reservation_id,new_version)、唯一command_id。快照含完整父预约区间、状态、能力诊断值和claim的ID/kind/windowId/start/end，ID序列化String，不存用户敏感资料；只读历史，禁止计容量 |
| ORDER既有表 | pet_order不增确认轮次列，继续reschedule_count=confirmRound；确认字段清空并切新deadline、次数与version递增；金额/paidAt/创建快照不改。order_merchant_decision原round=0约束扩展0/1，查询和唯一决定均带round；历史round0保留 |
| TASK既有表 | 无新任务队列、无删除任务/attempt。cancel使用既有status/version/lease/finished_at/last_result_code及attempt.result/error_code/finished_at，保留原submit快照/重试次数。取消与新任务提交均在同一业务事务 |

未来正式DDL应显式命名新增CHECK，兼容45号匿名round0约束需先核实实例中的约束名称/表达式后精确替换，不能删除其他检查。先停新入口、扩展Schema及兼容读写、隔离验证和回填检查，再谈启用；本审批不授权生产DDL或二进制回退时删除历史。

## 6. 事务、容量与来源协议

1. 校验真实USER、请求字段和资源归属；独立Admission持久绑定原规范输入。不存在外层业务事务，不能在同店guard下独立占号造成反向锁序。
2. Execution独立顶层RC：命令执行锁 → 同店guard → 当前会话/USER和ORDER版本、原预约/指派完整性 → 正常PAYMENT当前成功及REFUND全来源事实。失败保留绑定。
3. SCH先读取全量已提交当前事实、核验旧预约及claim；对求解模型仅替换本订单旧claim为新claim，其他订单全部保留，固定原指派员工。新旧时段重叠也不双算本订单，但不能为其他订单删占用；完整相交闭包、逐窗容量和跨服务同人匹配按36号。
4. 通过后SCH保存旧/新历史并更新当前预约/claim；ORDER写改期事实和新时间/round/deadline。必须在提交前核对ORDER/SCH双向绑定及新预约时间。调用核销Owner失效旧码并获取同事务证明；失败全部回滚。
5. TASK按原taskKey、Owner、orderId、round0、原deadline、原提交快照核验取消，再写唯一round1原截止任务。旧任务确实缺失可记录MISSING并继续新任务；不伪造旧任务成功。未知/损坏绑定则回滚。任务已终态按R2保留并记取消结果；正在执行的业务仍须guard+round/deadline双校验。
6. 写ORDER状态日志、`OrderRescheduledEvent.v1`及首次回执同提交。事件精确包含orderId/reservationId/storeId/rescheduleId、confirmRound=1、旧/新预约起止及两接送开始（不适用null）、rescheduledAt/confirmDeadline；String ID、UTC毫秒时间。无地址/备注/手机号。通知消费仍后续实现，不声称发送成功。

新epoch的确认资格必须核`order_reschedule_record`、当前ORDER/SCH一致、原NORMAL付款实付及本轮deadline=rescheduledAt+30min，不能仍用paidAt+30min。43/44的任务解析、成功证明、旧轮STALE、扫描及修复全部按0/1分别处理。45号手工命令canonical和decision唯一键含round，成功回放返回原轮回执；当前状态变化不覆盖旧回执。

round1拒单仍使用已批准MERCHANT_REJECT_ORDER来源；sourceEventId必须对应本轮REJECT决定，退款ID及正常付款绑定一致。REFUND/PAYMENT不得从旧round0确认记录或任意OrderRescheduled事件单独授权退款。订单支付事件重放核原支付事实加不可变改期证明后NOOP，不重置deadline、金额或任务；退款成功仅释放当前已验证预约。

## 7. 验收计划（本轮业务全部NOT_EXECUTED）

| 编号 / 追踪 | 必验场景 |
|---|---|
| RS-01 / RES-001 | 到店/接送分别成功；一单一预约、旧新历史、当前claim、次数1、原金额/快照/指派不变、原子新任务与回执 |
| RS-02 / RES-002/003/005 | 第二次改期、锁内到达原开始、已过去新开始、跨店/换服务/履约类型/员工注入、版本错误、无实际时间变化，均无业务副作用 |
| RS-03 / RES-004、ROC-6 | 跨服务争人、新窗容量不足、原指派不能覆盖、接送两段不同人、新旧重叠、同窗换时段、求解超预算；失败完整保留旧事实 |
| RS-04 / 23号 | 同key同参并发、同key异参/异订单、执行失败绑定保留、成功ACK丢失、当前用户注销/撤权后回放；无第二次扣数或换位 |
| RS-05 / CON-006 | 改期×手动确认/拒单/自动确认/全来源退款；通过共享guard及版本决定合法结果，不能同时改期且留下旧来源退款 |
| RS-06 / TASK | READY/RETRY_WAIT/RUNNING、过期租约、缺失/损坏/终态旧任务；取消后旧Worker心跳/完成失败，attempt保留；取消后任意注入失败整事务回滚 |
| RS-07 / CONF-006/RES-006 | round1手动/自动确认与重复任务、修复扫描、旧round0到期和repair、原支付事件重放；不能重置新截止或复活旧周期 |
| RS-08 / REF/SCH | round1拒单唯一全额退款、UNKNOWN只查、成功前保留新预约、最终成功只释放当前预约；round0与迟到退款全套回归 |
| RS-09 / FLT | SCH历史、claim更新、ORDER记录、核销fence、旧TASK取消、新TASK提交、日志、Outbox和回执逐点故障，所有业务原子回滚 |
| RS-10 / VER | 测试替身仅证明强制调用/失败回滚边界，真实动态码失效另列NOT_IMPLEMENTED；无真实Provider装配失败，不能用空成功替身开放HTTP |
| RS-11 / 启用 | 新开关全部false；业务/Worker缺依赖失败关闭，不能默认打开旧开关；原PR92全部验收回归、MyBatis/架构/contract/Java21 CI通过后提交PR |

默认关闭内核通过不等于用户端改期E2E通过；HTTP、小程序、核销实际码、正式渠道和通知消费逐项标明范围。业务实现PR在R1/R2/R3批准后提交审阅，不合并、不生产启用。

## 8. 为何需要具体回执

[WORK_EXECUTION_PROTOCOL.md §4](../../../WORK_EXECUTION_PROTOCOL.md)明确“以下行为必须人工批准：……Contract 重大变更”；[AGENTS.md](../../../AGENTS.md)要求“Contract 缺失走 CCR”。本轮新表、运行中租约取消、跨Owner round1证明以及因真实核销/读侧缺失而采用的交付边界，超出43/44/45明确批准的首轮范围。用户的推进授权已覆盖核对、设计、准备和沿用既有业务规则；本回执只请求批准上述具体新增方案，不重复申请普通代码编辑、测试或已批准ROC规则的权限。
