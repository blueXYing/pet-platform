# VER-001 真实核销码生命周期与改期失效方案

状态：PROPOSED_REQUIRES_REVIEW。2026-09-29用户授权“开始下一步”；这授权核对、设计和准备，不冒充下述新契约的明确批准。基线为PR93合并`324cba1243183a4cfc9cfef46373121848bdd6a8`；合并CI六项通过，112份后端报告698测试零失败/错误/跳过。

属于既有VER-001及ORD-003依赖衔接，不新增Epic。首切片是默认关闭的真实凭证内核，不把完整VER-001/VER-002/ORD-003标DONE，不接生产迁移或正式用户入口。

## 1. 已核对的依据与缺口

优先级：AGENTS.md → SSOT → 最终PRD → 技术基线 → Contract/Schema → Test。已批准的一次改期、五分钟有效期、取消/退款单创建/核销成功后失效、仅售后处理中不失效、服务开始后仍可核销等不重批。

| 来源 | 事实 | 本轮处理 |
|---|---|---|
| C端PRD §5.1.18；商家PRD §5.8 | 字母数字动态码，绑定订单/商家/服务时间，5分钟有效、自动/手动刷新；核销失败“超过3次”锁15分钟 | TTL直接沿用；次数歧义列V2 |
| 运营PRD §6.3.5及关键时限 | “失败3次锁定15分钟” | 与前项第4次触发不同，不能自行挑一个并称既定规则 |
| C端PRD §5.1.18 | 手动刷新一分钟超过5次受限；开始后仍可核销 | 第6次刷新拒绝；不给过预约开始的待服务订单增加取码禁令 |
| HTTP10 §3.11 | GET verification-code，未定义写入/刷新命令及requestId | GET不能暗中轮换凭证；列V1补协议 |
| SQL06 核销部分 | 只有verification_record与verification_attempt | 没有真实码/轮次/改期失效证据表，不能用qa_verification_fence冒充 |
| Internal07 §10、SQL06、MER S2交接 | staffId必填，但主账号是USER，没有合法staff映射 | 本切片不实现商家成功核销；主账号/员工操作者模型另行同步，禁止伪造staff |
| Contract46及现有Java接口 | 同一DataSource、同一guard事务失效旧码并返回持久fence | 真实Provider取代QA替身；不能只写一条“成功”记录而不影响码校验 |
| 现有ORDER/AFS | 无正式可执行核销资格/markVerified/未履约售后失效闭环 | 本轮新增ORDER凭证资格事实候选，保持成功核销入口未交付 |

PRD通过只读解包word/document.xml核对，未改写原文。C端§5.1.18的“网络超时不产生核销记录”不能用于推翻23号已批准的提交成功ACK丢失回放规则；数据库提交事实始终为准。

## 2. 两项需要确认的推荐决定

### V1：读取与换码分开，真实码和改期失效一起交付

推荐：读取只查看当前码，不偷偷换码；首次生成、自动到期换码和手动刷新统一为带requestId的写命令。刷新成功立刻废止前一张码，失败仍保留旧码至其原截止时间。同一个请求重试不重复换码、不重复计入限流。改期与码失效在同一事务提交；改期回滚则旧码恢复原有效性。

本次批准范围包含下面字段级候选、正式Contract/Schema同步、真实MySQL实现与测试、提交PR供审阅；运行开关默认false，不合并、不生产迁移或启用。公开取码/刷新HTTP、小程序、商家核销完成仍不在本切片交付范围。

### V2：第三次失败触发15分钟锁定，刷新不能绕过

推荐按运营PRD口径：滚动5分钟内，第3个不同核销尝试失败时触发15分钟锁定；同requestId重试只算一次。锁作用于订单凭证系列，换设备、换码或改期不会清空锁定/失败计数。锁定到期前不签发可用新码；15分钟到期后旧的5分钟码不复活，须按资格生成新码。

这是需明确批准的差异：C/商家原文“超过3次”意味着第4次；原文按单码锁也可能被刷新绕过。若选择保留第4次触发，需同步修改本提案阈值和对应测试后再实施，不同时保留两套行为。

仅经未来可信商家权限适配器到达VER、且目标订单归属匹配的实际无效/过期码尝试计数。会话失效、跨店无权、参数不合法、数据库/依赖故障不计码错误，不让第三方远程锁定不属于其权限范围的订单。消费者读取/刷新不计核销失败；本切片的内部风险接口不暴露无鉴权HTTP。风险记录及告警Outbox与失败计数同提交，通知送达另行交付。

## 3. 凭证与刷新协议候选（批准前不是正式API）

内部候选 `VerificationCredentialCommandApi.issue(context,orderId,expectedCredentialVersion,refreshKind)`，refreshKind为INITIAL/AUTO/MANUAL；所有写命令UUID requestId，可信USER只能访问自己的订单。`expectedCredentialVersion`非负十进制String，无凭证时为"0"，以服务端读结果为准；不是ORDER版本，不伪造公开ORDER读侧。

未来HTTP候选：现有GET `/api/v1/c/orders/{orderId}/verification-code`只读；新增POST同路径用于生成/刷新。本轮两者标NOT_IMPLEMENTED，不注册Controller。GET候选返回`orderId,credentialVersion,status,code?,expiresAt?,refreshAfter?,lockedUntil?`，status为NONE/ACTIVE/EXPIRED/INVALIDATED/LOCKED，非ACTIVE不返回码。无权/不存在统一现有防枚举错误，依赖不明503。

| 动作 | 服务端条件与结果 |
|---|---|
| INITIAL | 从未签发，或改期后上一轮已失效且重新待服务；不能用INITIAL无限刷新当前码或绕过限流 |
| AUTO | 当前码已经到期且仍可展示；到期前拒绝，不重置计时 |
| MANUAL | 当前订单可展示且未锁定；滚动60秒最多5个成功提交，第6个拒绝；最早一条移出窗口后恢复，不猜额外封禁时长 |
| 同key同参 | 当前会话和归属仍要检查；返回不可变首结果，不重签、不重新扣额度 |
| 同key异参/不同order | IDEMPOTENCY_KEY_CONFLICT；失败绑定也保留 |
| 不同key携带旧version | 冲突并要求读取当前版本，不连续替换另一设备刚生成的新码 |

首写回执含`orderId,credentialId,credentialVersion,code,issuedAt,expiresAt,refreshAfter`；全部ID/version是String，UTC毫秒时间，`expiresAt=issuedAt+5min`，`refreshAfter=expiresAt`。首回执受保护存储。重放不复活已到期、被刷新或改期失效的旧码：原始expiresAt保留，UI必须按当前时间/只读状态重新获取，不因200回执重置倒计时。

采用密码学随机源生成至少160bit的字母数字凭证（例如20随机字节的32字符Base32），禁止六位示例数值或Snowflake充当秘密。码与orderId/merchantId/storeId/reservationId/当前服务时间/改期轮次绑定。数据库保存加密正文、带keyId的查找摘要及绑定事实；保护密钥显式配置、无测试密钥兜底。明文不进日志/事件，响应禁止缓存；恢复/轮换历史密钥方案在启用前验证。

## 4. ORDER资格及真实失效

新增ORDER Owner事实候选 `OrderVerificationCredentialFactsApi`：同店guard下由ORDER判断资格，提供order/user/merchant/store/reservation、当前orderVersion、confirmRound、当前预约/接送时间与完整性证明引用。VER不读取pet_order或自行推导DisplayOrderStatus。

资格：PAID、PENDING_SERVICE、UNVERIFIED，正常付款/确认与当前预约证明齐全，无任何来源refund_order。原服务快照和当前轮次来源有效；服务下架不重新按新单资格拒绝，OFFLINE存量履约按既定MER规则，FROZEN未决写入不放行。确认中不能签发可用码。到达/超过预约开始不自动拒绝，五分钟到期边界为DB now >= expiresAt。

仅售后/退款申请存在不拒绝核销码；退款存在与依赖不可读必须区分，未知不能按不存在。成功核销所需“核销先成功使旧未履约售后失效”的事务/事件门禁仍为VER-002/AFS后续，不以本凭证资格查询代替。

真实 `VerificationRescheduleFenceApi.invalidate` 复用46号签名：严格同DataSource、可写事务、已持同店guard；按order/reservation/store/user和本次rescheduleId验证调用来源。清除当前可用码引用、递增凭证epoch，当前历史码标失效，写唯一不可变fence；此前未发码也真实建立边界。返回fenceId/orderId/rescheduleId。无此前码不等于无须持久化边界。

必须核实ORDER本事务提交的改期事实与fence相互引用（用ORDER公共Owner证明，不能读外模块表），不允许孤立调用提交一条随意失效记录。重放同rescheduleId核原绑定并返回首证据，异参冲突。后续重新确认生成的码只属新epoch，旧epoch永不再有效。

码校验每次都重新核当前ORDER资格、码epoch/current指针、绑定、有效期和风险锁。取消、退款单创建、核销完成即使尚未异步清理历史行，也不能通过实时校验；不能仅依赖通知事件让旧码失效。所有未来真正核销入口必须用同一校验内核，首切片的验证测试不等于商家完成核销已交付。

## 5. 持久化与事务候选

新表全部VER Owner，仅本模块Mapper XML访问；候选不直接执行DDL。

| 表候选 | 关键字段与约束 |
|---|---|
| verification_credential_state | order_id PK、store_id、reservation_id、epoch、current_credential_id nullable、version、locked_until nullable、updated_at；版本非负，缺state但有历史不自动补为全新 |
| verification_credential | id PK、order_id、merchant_id/store_id/reservation_id、epoch、generation、confirm_round、appointment_start/end、pickup/return nullable、lookup_key_id/hash、code_key_id/cipher、issued_at/expires_at、invalidated_at/reason nullable；唯一(order_id,generation)、唯一(lookup_key_id,hash)，历史不可覆盖 |
| verification_reschedule_fence | id PK、order_id、reservation_id/store_id、reschedule_id UNIQUE、old/new_epoch、invalidated_generation nullable、rescheduled_at、created_at；new_epoch=old+1，订单仅一次改期边界唯一 |
| verification_credential_command | 独立Admission：二进制五元组namespace/actor_type/actor_id/authority_scope/request_id唯一，canonical_version/hash/protected_bytes、RESERVED/SUCCEEDED、受保护首结果及版本、创建更新时间；namespace区分刷新与内部核验尝试 |
| verification_credential_risk_attempt | id PK、order_id、credential_id nullable、可信调用者/门店绑定、command_id UNIQUE、结果、失败类别、attempted_at；用于滚动窗口与重放，不复用旧verification_attempt的staffId必填列来伪造主账号 |
| verification_credential_refresh | id PK、order_id/user_id、command_id UNIQUE、refresh_kind、committed_at；MANUAL成功记录用于滑动60秒限流，索引(order_id,user_id,committed_at) |

码生成/刷新：参数和真实会话/归属预检→独立Admission→命令执行锁→同店guard→ORDER/REFUND/MER事实→VER状态/风险→DB时间和version→废旧码/生成新码→写凭证/刷新记录/受保护首回执→原子提交。失败不作部分轮换；Admission绑定不随业务回滚删除。锁序不得在持店guard时开启独立Admission反向等待。

改期失效直接加入46号调用方事务，不另起REQUIRES_NEW、不单独提交。VER写失败或ORDER/SCH/TASK/Outbox任一点失败，所有码状态、epoch、fence完整回滚。更新当前指针与history一致性、beforeCommit双方证明均纳入验收。

风险失败结果是需要持久化的业务结果，不能在同事务抛异常导致计数回滚；先保存幂等失败结果/锁定事实及唯一告警Outbox，再在事务外映射错误。数据库故障回滚不计失败。告警只含ID/类别/时间，不含码、个人信息。

风险告警事件候选`VerificationRiskLockedEvent.v1`，Owner VERIFICATION，aggregateId=orderId，标准IntegrationEvent envelope；payload为`orderId,storeId,triggerAttemptId,lockedAt,lockedUntil,reasonCode=INVALID_CREDENTIAL_THRESHOLD`，ID均String。一次新锁定只发一个事件，锁定期间重试不反复续锁/发告警。消费者和送达不在本批验收范围。无效/过期/退款/风险锁沿用12号错误；版本和提前AUTO用COMMON_CONFLICT，刷新额度用既有公共限流错误，依赖缺失用COMMON_DEPENDENCY_UNAVAILABLE，不新增假成功状态。

## 6. 切片边界与装配

本批拟交付真实码生成/读取/内部校验/刷新限制、风险状态、改期fence、ORDER公共凭证资格和真实MySQL并发测试；boot单独默认关闭开关。所有新读写表与明确必需依赖通过启动门禁，不会因仅提供fence就自动开放改期HTTP。

不交付商家成功核销HTTP/markVerified/完整OrderOperationGuard、成员授权绑定、手动订单号兜底、C页面/公开读侧、外部通知或生产部署。主账号USER与staffId必填冲突独立登记为后续契约缺口，本轮不偷改operator_staff_id为userId。待这些Owner依赖冻结后才能完成扫码→二次确认→COMPLETED→售后失效全链路。

## 7. 具体验收计划（新业务均NOT_EXECUTED）

| 编号 | 数据库验收要求 |
|---|---|
| VC-01 | 真实支付→商家确认→生成5分钟码，当前绑定校验成功；匿名/非本人/跨店/坏来源拒绝 |
| VC-02 | 5分钟相等即过期；开始前后均遵守待服务资格；AUTO提前拒绝，到期才换码 |
| VC-03 | MANUAL成功旧码立即失效；失败仍留旧码至原截止；6次/60秒边界、并发限额、INITIAL/AUTO不绕过 |
| VC-04 | 同key并发一次生成，同key异参冲突，失败绑定保留，ACK丢失回放；旧回执不延长旧码有效期 |
| VC-05 | 真实码→改期→旧码永久失效→重新确认→新码成功；从未发码的改期也有持久epoch/fence |
| VC-06 | 改期逐点故障回滚后旧码仍有效；fence本身插入/当前码更新失败必须阻止整个改期 |
| VC-07 | 生成/刷新与改期并发，不出现改期后有效旧码；孤立fence提交失败；ORDER/VER相互证明不可篡改 |
| VC-08 | 取消/任何来源退款单/已核销立即拒绝旧码；仅售后或退款申请不使码失效；依赖异常503 |
| VC-09 | 第1/2/3次失败边界、同key一次、滚动5分钟和15分钟锁；刷新/改期/重启不能清锁；到期旧码不复活 |
| VC-10 | 计数/锁/风险Outbox失败回滚，业务拒绝仍持久；无权/跨店/基础设施故障不计码错误 |
| VC-11 | 明文码不进日志/事件；密钥缺失装配失败；默认关闭，无HTTP，MyBatis/模块依赖/架构通过 |
| VC-12 | 用真实Provider重跑PR93的19项改期数据库测试；替身测试保留为故障隔离，不能仅改配置测试即称联动完成 |

完成后提交实现PR供审阅，披露真实核销完成/员工权限/售后/HTTP/真机仍未交付。V1/V2获批前不修改正式SSOT、API/Schema/Event，不执行上述业务实现；本轮准备测试仅证明文档/既有基线。
