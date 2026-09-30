# AFS 退款、ORDER、核销与预约集成 CCR 候选

状态：PROPOSED / NOT IMPLEMENTED。2026-09-30。角色：退款 / ORDER / 核销集成。

本提案以 `ff983596cad899c7b4e1ce9c8278228787ca7ac6`（PR96 已合并）为调查基线，仅供本轮 CCR、API50 / SQL50 与实施分工使用；不表示以下新增契约已批准或代码已交付。未修改 SSOT、正式契约、Schema 或生产代码，未运行 Maven。资金可执行性复核补充：运营 PRD p2365 与 OD-W0-001 仍阻断真实 AFS 退款终裁及出款，以下资金链路只能作为待权威资金资格契约补齐的技术候选，不能据此声明 AFS-002 全链路已解锁。

依据：AGENTS、WORK_EXECUTION_PROTOCOL；SSOT §5～8、§37～39；正式 Contract48/Storage48/SQL48、Contract49/Storage49/SQL49；内部 API §9～11；Event Catalog §7～11；Scheduler §15～18。普通退款 §39 已批准的三阶段事务保持独立。

## 1. 必须先确定的结论

1. AFS 的 FULL/PARTIAL 最终裁决与真实 `refund_order` 创建应原子提交：AFS 不可变裁决、工单终态、REFUND 业务单和执行绑定、ORDER 引用和提交证明、相关日志、Outbox、渠道任务、首回执同一 DataSource 的可写 READ_COMMITTED 短事务提交。渠道网络调用在提交之后。
2. 不把普通退款“批准已提交、异步建单”的规则复制到未履约 AFS。若先把工单 RESOLVED，再等待任务创建退款，当前核销失效内核只失效活动工单，会留下“核销已成功但旧终局裁决仍可建单”的危险来源。原子提交没有这个可观察中间状态。
3. ORDER 使用既有共享门店 guard、订单行锁及 `order_operation_guard` 的 CREATE_REFUND 能力与 VERIFY 互斥。token 只在创建它的事务、相同 DataSource 和当前 ORDER 版本下有效，绑定真实命令、caseId / caseVersion / sourceStage / decisionId，不能作为跨事务审批凭据。
4. 不能按 `verificationStatus=VERIFIED` 全局拒绝退款：核销只废除当时活动的 `UNVERIFIED_POST_START` 工单。新 `VERIFIED` 工单按核销后七天准入，可以合法终裁退款；普通已批准全额退款仍按 §39 恢复。
5. PARTIAL 只能新增 `AFTERSALE_DECISION` 来源并由真实运营最终裁决授权；`0 < amount < 原渠道实付本金`，FULL 严格等于原实付。其他现有来源继续只允许 FULL。
6. 核销历史证明与当前售后指针必须分离。当前实现若不修正，合法新 AFS 或已结束历史 AFS 会破坏普通退款资格及部分历史证明复核。
7. 真实付款成功、订单无退款、仍在售后七天内都不等于资金可退。AFS 退款终裁必须另有权威的资金 / 分账资格事实及持久审计绑定；缺失、未知、过期、不匹配或未获准的适配一律失败关闭，不提交最终退款裁决和退款单。不提供生产恒定 `unsettled=true` / `eligible=true` 适配。

## 2. 实际代码约束与精确产品缺口

### 2.1 普通退款与活动 AFS 竞争

`RefundApplicationService.apply` 持久幂等准入后，业务事务依次锁命令、共享门店 guard、调用 `orders.requireEligible`，再校验本域唯一退款和活动申请。`decide`、`handle(timeout)`、`createApproved` 同样依赖 ORDER 的普通来源资格。

实际阻断位于 `OrderRefundApplicationApiImpl.normal`：

- 未核销分支只要 `currentAftersaleId != null` 就报依赖不可用；这也会误伤 WITHDRAWN / CLOSED / 非退款 RESOLVED 等历史指针。
- 已核销分支要求不可变 `order_verification_commit.aftersale_id/status` 完全等于当前 ORDER 指针 / 投影，而且非空只接受 INVALIDATED。核销后新建合法 VERIFIED AFS 会使此条件不成立；核销时存在已结束的非 INVALIDATED 历史工单也不成立。
- 此处没有查询 AFS 公共事实，也没有明确区分“活动 AFS 与新普通申请互斥”及“历史指针存在”。因此不能把代码的失败关闭当作产品已批准的互斥规则。

待人工回答的产品问题统一为总案 P1～P4（均未获答复，当前不得假定获准）：

| 问题 | 推荐候选 | 资金影响 |
|---|---|---|
| P1 一单多个处理中问题如何处理 | 一单一个活动工单，新问题并入当前工单 | 已有 `uk_aftersale_one_active` 支持；不能由数据库唯一约束反推产品已批准 |
| P2 终局前撤回后能否重新申请 | 仍满足原资格且仍在原七天窗口可新建，不重置期限 | 必须保留旧工单、旧命令、旧证据，不能复用旧来源授权 |
| P3 活动普通申请与活动 AFS 是否可共存 | PENDING_MERCHANT / APPROVED / AUTO_APPROVED 且尚未建单，与活动 AFS 双向互斥 | 防止商家 24h 自动全额承诺与运营部分裁决并存；后进入者明确冲突，不能静默取消先进入者 |
| P4 非退款终局后如何识别新问题 | 原窗口内提交新问题说明，运营受理前核对历史；新问题才受理，重复问题关闭并引用原终局 | 重复工单只 PENDING→CLOSED，不新建决定/退款；不能换caseId/requestId变相复审，不用描述hash或分类自动证明同一性 |

P4推荐获批后的具体候选：存在既往非退款终局时 `Create` 条件必填 `newProblemStatement`，服务端记录真实终局工单集合及版本；`Accept` 条件必填非空 `newProblemAssessment` 并原子保存核对的历史集合版本及真实受理证明。重复问题走 `CloseDuplicate(context, caseId, expectedVersion, priorFinalCaseId, reason)`，仅 PENDING→CLOSED / active=0，原因 `DUPLICATE_FINAL_PROBLEM`，同事务更新 ORDER 投影、关闭关联、日志、进度 Outbox 和首回执。priorFinalCaseId 仅能引用同单存在正式决定的旧非退款终局；WITHDRAWN/INVALIDATED 或没有正式决定的重复关闭 CLOSED 不能冒充终局。该命令不写新的 REJECT 决定、不改旧结论、不产生资金来源。尚未受理或新问题核验未成立的PENDING工单不能进入退款终裁。若用户选同履约阶段非退款终局后禁止再次受理，则不实现上述候选分支。

若批准互斥，应在双方写入口使用同一 store guard + ORDER 锁，再分别通过公共 API 校验真实当前事实；数据库唯一退款约束仅为最后兜底。AFS 新建遇到已批准普通退款不能撤销承诺；普通申请遇到活动 AFS 不能借再次申请覆盖 AFS。拒绝后的普通申请历史可作为未核销 AFS 准入证据，必须绑定真实 `REJECTED` 决定和商家身份，不接受客户端自报“被拒绝”。

若允许共存，则必须另行定义普通批准与 AFS 最终退款谁占有唯一资金承诺、落败的活动申请 / 工单如何结束以及用户如何知晓；不能仅靠 `uk_refund_order_once` 抛异常后无限恢复。该分支目前无足够产品依据，不建议提前实现。

七天边界已有正式依据，不新增产品问题：`docs/06-scheduler/09-Scheduler-Retry-Compensation-v0.5.md:1304` 与 `:1305` 分别明确 `now <= verifiedAt + 7d`、`now <= appointmentStart + 7d`。采用数据库 UTC 毫秒，恰好截止仍可新建、deadline+1ms 拒绝；受理后的终裁不因七天申请窗口结束自动失效，资金资格必须独立判断。

### 2.2 历史核销与 AFS 当前状态耦合

`OrderVerificationCommitApiImpl.requireCommitted` 当前要求 ORDER 的当前售后指针及状态等于核销快照；`AfterSaleVerificationApiImpl.requireCommitted` 当前要求同订单没有任何活动 AFS，并要求核销时关联工单当前版本等于当时证明。完整 AFS 上线后，合法的核销后新工单会违反这些条件。

修正候选：区分同事务的完成断言与历史证明读取。核销提交前仍严格验证当前指针、工单版本、没有遗留未履约活动工单及 ORDER/AFS/VER 原子完成；提交后的历史读取验证不可变核销 ID、command / attempt / credential / event、verifiedAt、当时 AFS 失效证明及版本下界，不能要求“今天不存在新工单”或“当前指针永远等于旧值”。当前工单一致性单独由 AFS 当前事实 + ORDER AFS 投影校验，不能直接删除一致性防护。

当前 `VerificationCompletionService` 的成功请求重放只调用 VER 自身 `receipt()`，没有调用上述 ORDER/AFS 方法，所以不声称现有重放已经坏掉；新增回归仍须覆盖“核销后新 AFS、后续终局退款后旧核销请求返回原首回执”。

### 2.3 PARTIAL 的实际阻断

| 位置 | 当前事实 | 必要修正 |
|---|---|---|
| `RefundExecutionFact` / `RefundSuccessFact` | 没有 refundType | 增加可信类型，保留既有构造器兼容 FULL，AFS 调用必须显式提供 |
| `LateRefundService.verifyRow` | 仅四种现有来源，强制 FULL / amount=paid / ratio=1 | 来源白名单增加 AFS，AFS 双证明，按类型验证金额；旧来源约束不变 |
| `RefundExecutionService.finish` | 成功事件 `refundType` 硬编码 FULL | 从已核实执行事实发布，类型与 AFS 决定/业务退款一致 |
| `PaymentRefundService` | 来源 switch 无 AFS，首次发送要求退款额等于多个实付事实 | 实付对实付比较；退款额与真实裁决比较；仅 AFS PARTIAL 可小于实付 |
| `ReservationRefundReleaseApiImpl.release` | 白名单只有拒单及两类普通来源，强制 amount=original | 增加可信 AFS FULL/PARTIAL 成功事实；仍仅最终成功释放 |
| `OrderApplicationRefundProjectionConsumer` / `OrderMerchantRefundProjectionConsumer` | 先检查 FULL，后按 source 跳过 | 先通用严格 payload 与来源分类，再做本来源 FULL 校验；否则 AFS PARTIAL 卡住旧消费者 |
| SQL42 `chk_late_refund_amount` | 全局强制 `refund_amount = channel_paid_amount` | SQL50 必须显式替换为来源与类型联合约束，不能只改 Java |

### 2.4 退款资金可执行性的真实阻断

已按原 DOCX 的 `word/document.xml` 中 `.//w:p`（含表格段落）自 1 编号核对 `docs/01-prd/04-PRD-运营端-V1.0-最终基线.docx`：p2365 明确“裁决结论涉及退款时，必须判断资金是否已分账”，并引用未提供的《资金管理 V1.4》区分未分账扣回与已分账追回；p1233 同时指出裁决晚于冻结期可能遇到已分账资金。p2365 是段落定位，不是 DOCX 第2365页或可伪造的文本行号。

`planning/OPEN_DECISIONS.md:31` 的 OD-W0-001 仍要求提供权威基线并确认 V1 有效范围，禁止自行把售后七天窗口转换成资金状态机或判定原文废弃。因此：

- 不能以 `PaymentSuccessFactsApi` 的 PAID / OBSERVED、refund_order 不存在、核销时间、售后申请期限推定“未分账、可从原渠道直接扣回”。七天申请窗口是申请资格，不能证明当前结算余额或可退款资金。
- 当前 PAYMENT / AFS 公共接口与实现未发现权威资金 / 分账状态或退款资金资格适配；原资金管理权威文档、状态 Owner、源 API、刷新/有效性规则、变更并发保护、已分账退款机制均未获准，不能由本提案补造。
- 本提案仅定义消费者需要的公共事实边界。UNSETTLED 也不能自动映射 ALLOWED，SETTLED 更不能直接映射可追回；这种资金政策映射须来自后续明确批准的权威契约。
- 不新增资金冻结、分账、下期冲抵、账户冻结、提现、保证金或追回规则；不改变已有获准普通/拒单/迟到退款来源的契约，仅阻止将这些来源的现有证明误当成新 AFS 的资金资格。

候选公共入口为 `RefundFundingEligibilityFactsApi`。在权威 Owner 未定前，仅可把它描述为 PAYMENT 对外的公共事实 facade（如放在 `pet-payment-api`）；这不是批准 PAYMENT 拥有结算账本。生产实现必须转接后来获准的真实 Owner / Provider，返回值不得由 AFS 或 REFUND 根据订单字段合成。建议表面如下：

```java
FundingEvidence requireForDecision(FundingCheck check, QueryContext trustedContext);
FundingEvidence requireForFirstSend(FundingCheck check, String committedEvidenceId,
        QueryContext trustedContext);
```

若 AFS 本域保留 `AfterSaleRefundabilityPort.requireCurrent`，它仅为 boot 组合上述公共API与本域事务/来源校验的适配入口，规范权威事实仍来自 `RefundFundingEligibilityFactsApi` 的 DECISION_COMMIT / FIRST_SEND 两阶段；不得产生第二套 proofId、资金资格枚举或本域 eligibleAmount 算法，不得缺少其中一阶段。REFUND/PAYMENT复核同一真实evidenceId与绑定，不将此组合port当作另一个资金Owner。

| DTO | 必需字段与含义 |
|---|---|
| `FundingCheck` | orderId/paymentId/paymentNo/paymentSuccessEventId/channelTradeNo/merchantId/storeId/userId/caseId/decisionId/commandId，均从已核实来源取得；refundType/requestedRefundAmount/originalPaidAmount/currency；phase=DECISION_COMMIT 或 FIRST_SEND；purpose=AFTERSALE_FINAL_REFUND；first send 增加 refundOrderId/refundNo/bindingVersion；规范载荷 hash 绑定此次金额和来源 |
| `FundingEvidence` 身份 | evidenceId、authorityId、authorityContractVersion、sourceFactId/sourceFactVersion、authorityEvidenceRef、requestBindingSha256；所有 ID/String 及原资金资源映射足以向真实 Owner 复核，不用 traceId 当资金证明 |
| `FundingEvidence` 资格 | settlementState=UNSETTLED/SETTLED/UNKNOWN（仅事实分类候选），eligibility=ALLOWED/BLOCKED/UNKNOWN、policyVersion、reasonCode、authorizedRefundAmount/currency；只有已获准政策下与本次金额精确绑定的 ALLOWED 可继续，其他结果均不能授权建单或首次出款 |
| `FundingEvidence` 时效 / 审计 | observedAt、checkedAt、validUntil（均 UTC 毫秒），真实来源版本或 fencingReference、已验证响应的摘要 / 受控证据引用；不能用客户端时间、无限有效期或明文渠道秘密替代 |

上述字段、枚举与方法仍需 CCR 定稿，不是已经存在的生产 API。`authorizedRefundAmount` 是真实来源对这次请求金额的确认，不是本系统自行定义的可用余额算法。若资金 Owner 只能提供结算状态而不能给出退款资格，还不足以返回 ALLOWED，须继续失败关闭。

最终 AFS 决策事务必须复核该证据和规范绑定，并把 evidenceId/源版本/时点/政策版本/摘要同不可变决定、退款执行授权绑定原子持久化。首次渠道发送前 PAYMENT 独立读取已提交绑定，并向真实权威入口核验当时资格；不能直接信任 AFS DTO 或只信一张数据库快照。源为同库时，只有获准 Owner 公共 API 可声明如何在该事务锁住/验证事实版本；源为远端时，一次读或签名快照并不保证读取后不会分账，必须有权威契约说明有效期、版本冲突、与退款提交并发时的保证。远端取证网络在共享门店锁及主业务短事务外；最终事务只通过公共API复核已取得证据及权威有效性保证，不能为了“当前”检查把外部资金网络调用放进锁内。不能声称本地 store guard 已锁住外部资金状态，也不凭空新增资金预留/冻结来填补缺口。远端证据没有可验证并发保证时，即使快照新鲜也不足以解除阻断。

缺失/UNKNOWN/过期/Provider不可用/金额或身份不匹配时，初次终裁保持工单原处理中状态，不写 RESOLVED/退款单/资金任务，不静默改成非退款结论。可独立持久记录脱敏失败审计，但失败审计不构成最终决定。若未来已建立退款单而 FIRST_SEND 复核失败，保留原终局、退款ID/金额/来源及明确待核查问题，不撤销退款承诺、不改来源、不新建退款；只能在获准的真实资金资格恢复规则下继续原号。MAY_HAVE_SENT 之后的原号查单与真实成功投影不能因资格状态后来改变而被抹除，否则会遗漏已经发生的出款。

测试 fixture 仅置于隔离测试源码/测试装配，能给出 ALLOWED/BLOCKED/UNKNOWN、过期、版本竞争等可控返回；必须标识 TEST authority，禁止被生产配置加载。它可验证事务回滚、来源/金额绑定、首次发送门禁及恢复，却不能证明真实资金可用、真实渠道扣回或 AFS-002 全链路已验收。生产缺适配时默认关闭退款终裁入口；显式请求开启但缺权威适配/配置，装配失败或命令失败关闭，绝不注册常量未分账兜底。

## 3. 建议事务边界与竞态结果

### 3.1 AFS FULL / PARTIAL 最终裁决

AFS 是顶层业务事务拥有者；`RefundAfterSaleCommandApi` 只参与当前事务，不开启 REQUIRES_NEW，不允许无事务直调。流程候选：

1. 真实 ADMIN_WEB 会话及后端权限校验、理由/素材校验在既有 AFS 工作流办理；独立 Admission 持久绑定二进制五元组与加密规范参数。不是先保存可执行的最终裁决。
2. 最终短事务：命令行锁 → 共享门店 guard → ORDER 行及真实付款 / 退款存在性 → AFS 当前工单和版本 → REFUND 本域退款。所有写者必须先取得同一门店 guard；跨域仅调用公共 API，不跨表查询。已成功同载荷请求仍复验当前读取权限后返回原首回执。
3. 先满足 §2.4 的权威资金资格及并发有效性保证；在本次最终提交事务复核证据与 case/decision/command/金额完整绑定。工单必须是 PROCESSING，已真实受理且没有仍待完成的补证轮次；P4分支须有与历史终局集合版本匹配的新问题核验/受理证明，不接受PENDING直接终裁或WAITING_SUPPLEMENT绕过补证恢复。ORDER 获取 CREATE_REFUND token，绑定当前事务资源、commandId、可信操作者、orderVersion、caseId、caseVersion、sourceStage、decisionId、原付款、资金资格证明引用及预约身份。UNVERIFIED_POST_START 必须仍为未核销且为当前活动工单；VERIFIED 必须绑定真实不可变核销证明。检查任意来源 / 状态业务退款均不存在，检查没有活动 VERIFY/CREATE_REFUND 能力。
4. AFS 写不可变决定内容，但“待绑定”的状态只存在于当前未提交事务。决定金额、类型、真实 operator、case 原阶段及 command 证明固定。AFS 的同事务来源读取只接受当前活命令 + 当前 ORDER token，不把单独 INSERT 的裸决定行当成授权。
5. AFS 调用 REFUND `create`，REFUND 重读 ORDER token、AFS 决定、PAYMENT 正常实付事实及权威资金资格绑定。金额从真实决定、实付及匹配的资金资格确认验证得出，不能相信调用方金额。写一张 `refund_order`、一条 `refund_execution`，保存 `sourceType=AFTERSALE_DECISION`、case/decision/资金证据引用、唯一确定性 executionKey、真实创建事件及渠道任务。
6. AFS 保存退款绑定证明，工单 CAS 到 RESOLVED / active=0，decisionId/refundId 唯一绑定；ORDER `commitCreated` 再通过公共 API复核 AFS 绑定和 REFUND 创建证明，CAS 当前版本写退款引用、售后终态、ORDER 提交证明、审计并消耗 token。
7. AFS 发布裁决事件并保存加密首回执；ORDER/AFS/REFUND 的 beforeCommit 断言分别验证本域叶子证明及相互引用闭环，任何失败全部回滚。禁止证明 API 相互递归调用；叶子读取不调用对方复核方法，闭环由协调层分步验证。
8. commit 成功后才由 REFUND durable task 调用 PAYMENT。客户端超时后只按原 requestId 重放；不建立“已最终批准但没有业务退款”的常态恢复分支。

推荐决定事件时间与退款创建时间使用本事务同一数据库 UTC 毫秒锚点；首回执明确 `RESOLVED` + 原 refundOrderId 只表示已建立退款，不表示渠道成功。若设计允许时间不完全相同，仍必须持久验证 `createdAt >= decidedAt`。

| 获得并提交共享锁的先后 | 结果 |
|---|---|
| 核销先提交，旧工单 UNVERIFIED_POST_START | 当时当前工单已 INVALIDATED；旧 expectedCaseVersion / 旧 decision / 重试不得退款 |
| AFS 最终退款事务先提交 | refund_order 已真实存在；任何后续核销失败，包括 UNKNOWN / FAILED 渠道状态 |
| AFS 事务任意步骤回滚 | 没有最终裁决、退款或 ORDER 退款引用；核销仍可正常竞争；独立 Admission 保留原参数绑定 |
| 核销后新建合法 VERIFIED 工单 | 绑定新 caseId 与已核销事实；可按新问题终裁，不误用旧失效工单 |
| 普通批准待建单期间核销 | 普通批准保留；重新取得当前 ORDER 版本 CREATE_REFUND token，仍可全额退款 |

### 3.2 非退款终局

REJECT / RESERVICE / OTHER 同样仅从 PROCESSING 首次终裁，在 AFS 当前命令、工单、ORDER 当前投影、不可变决定、日志、Outbox、首回执同事务结束；refundAmount / refundOrderId / CREATE_REFUND token 均为空。P4重复关闭是独立PENDING→CLOSED命令，不能复用REJECT终裁。SSOT 允许非退款决定，不授权由 RESERVICE 自动创建第二订单、恢复已核销凭证、重开预约、修改原预约时间或增加改期次数。

保留既有单次服务、最多一次改期规则。RESERVICE/其他非退款在本切片记录终局理由与安排说明，不悄然生成新的履约能力；如产品要求平台内再预约/再核销，需要单独 CCR，不能借这次 AFS 扩范围。未核销原订单后续仍按原履约资格核销；已核销订单保留完成时间和唯一核销记录。

## 4. API / DTO 候选清单

以下候选名称与 root 总案统一，由正式 API50 冻结精确签名；不再并列使用聚合 `OrderAfterSaleApi` 或 `AfterSaleDecisionFactsApi`，不实现草案中的裸 `RefundCreateCommand(orderId, amount, source)` 作为出款入口。ORDER事实、ORDER写能力、ORDER已提交退款来源三种职责分开。

| Owner / API | 方法与主要参数 | 输出与保证 |
|---|---|---|
| ORDER `OrderAfterSaleFactsApi` | `locate(orderId, QueryContext)` | 真实user/merchant/store/reservation及ADMIN所需cityCode/scopeVersion，不接受客户端归属 |
| ORDER 同 API | `requireCurrentEligible(orderId, storeId, QueryContext, transactionSource)` | 当前ORDER、付款、预约、不可变核销、AFS/普通申请投影及版本；只有事实快照，不授予稍后写入/退款能力；AFS自身七天、产品和问题判断仍由AFS负责 |
| ORDER 同 API | `current(orderId, storeId, QueryContext, transactionSource)` | ORDER当前指针/状态的明确NONE或当前事实；通过AFS本域叶子事实验证正反关联，不跨表，不与AFS资格协调者循环调用 |
| ORDER `OrderAfterSaleCommitApi` | `bindCreated(orderId, storeId, caseId, expectedOrderVersion, CommandContext, transactionSource)` | 同事务核验AFS真实创建来源并CAS绑定当前指针、来源证明、日志 |
| ORDER 同 API | `projectTransition(orderId, storeId, caseId, expectedCaseVersion, CommandContext, transactionSource)` | 读取AFS本域已写叶子变化事实，CAS当前投影和记录证明；覆盖受理/补证/撤回/重复关闭/非退款终局，不能自行构造退款授权 |
| ORDER `OrderAfterSaleCommitApi` | `acquireRefund(orderId, storeId, caseId, expectedCaseVersion, decisionId, commandId, trustedContext, transactionSource)` | `RefundPermit(token, orderVersion, CaseIdentity, NormalPaymentOrigin, VerificationOrigin)`；只当前事务有效 |
| ORDER 同 API | `requirePending(token, orderId, storeId, transactionSource)` | 返回刚才真实 permit；拒绝伪造、过期、跨事务、已消耗、异 DataSource token |
| ORDER 同 API | `commitCreated(token, caseId, decisionId, refundOrderId, createdAt, transactionSource)` | 写 ORDER 引用、状态投影和独占提交证明 |
| ORDER 同 API | `requireCreated(orderId, storeId, caseId, decisionId, refundOrderId, transactionSource)` | 复核本域提交与真实来源、唯一退款；与历史核销证明解耦当前指针 |
| ORDER `OrderAfterSaleRefundFactsApi` | `requireDecidedRefund(orderId, paymentId, storeId, QueryContext)` | 正常原付款身份、caseId、decisionId、refundId、refundType、授权金额、sourceStage、决策时核销证明 |
| AFS `AfterSaleRefundFactsApi` | `requirePendingDecision(caseId, decisionId, orderToken, storeId, transactionSource)` | 仅同事务活命令可读未提交授权；真实 case stage/version、原付款、金额、操作者、决定时间、commandId |
| AFS 同 API | `requireCreated(caseId, decisionId, refundId, storeId, QueryContext)` | 提交后资金授权事实；命令/决定/工单/退款绑定齐全才成立，不能调用递归 ORDER requireCreated |
| AFS `AfterSaleCaseFactsApi` | `requireCurrent(orderId, storeId, expectedCurrentCaseId, context, DataSource)`，供ORDER核验当前指针 | 只读AFS本域，返回NONE/当前活动/历史及版本，校验正反关联与归属；不能用null掩盖孤儿数据；绝不回调ORDER.current/checkEligibility，不混入资金授权requireCreated |
| REFUND `RefundAfterSaleCommandApi` | `create(Create(context, orderId, storeId, caseId, decisionId, orderToken), transactionSource)` | `Created(refundOrderId, refundNo, refundType, refundAmount, originalPaidAmount, createdEventId, createdAt)`；必需现有事务，金额不由命令自报 |
| REFUND `RefundAfterSaleFactsApi` | `requireCreated(refundId, caseId, decisionId, storeId, QueryContext)` | 本域退款/执行/事件任务关联叶子证明，用于跨 Owner 最终提交复核 |
| 权威资金 Owner 的公共 facade（Owner待OD-W0-001定稿） | `RefundFundingEligibilityFactsApi.requireForDecision` / `requireForFirstSend`，详见§2.4 | 对明确订单、原付款、AFS来源和金额返回可复核资金资格；无真实适配必须失败关闭 |
| REFUND 既有执行事实 | `RefundExecutionFact` 增加 `refundType`；`RefundSuccessFact` 同步增加 | 老构造器默认 FULL 只用于旧来源；AFS 必须显式类型；来源及类型不从金额临时猜测 |

`CaseIdentity` 至少包含 caseId/orderId/userId/merchantId/storeId/sourceStage/caseVersion/status/createdAt/sourceAnchorAt/sourceDeadline/rejectionApplicationId/rejectionDecisionId/verificationId/verifiedAt（按来源互斥可空）；资金permit另要求acceptedProofId及P4适用时的历史终局集合版本/新问题核验引用。`NormalPaymentOrigin` 包括 paymentId/paymentNo/paymentSuccessEventId/channelTradeNo/currency/paidAmount/paidAt/reservationId。ID 为 String；新增公共API版本是非负十进制 String；金额 BigDecimal 严格两位小数、DECIMAL(18,2) 范围；时间 UTC 毫秒。

最终决定事实至少包含 decisionId/caseId/sourceStage/caseVersionBefore/caseVersionAfter/decisionType/refundType/decisionAmount/operatorType/operatorId/commandId/decidedEventId/decidedAt/refundId/createdEventId/createdAt；退款决定另绑定 fundingEvidenceId/authorityId/sourceFactVersion/policyVersion/requestBindingSha256。运营身份使用现有 `PLATFORM_OPERATOR` 枚举及真实 ADMIN_WEB principal，不伪造 SYSTEM / OWNER 或要求已取消的 MFA、双人复核。新决定最终提交前复验当前 RBAC；决定已原子提交后的渠道恢复不要求原运营会话仍有效或仍获权，但不因此绕过权威资金契约对首次发送的资格约束。

## 5. 资金来源与原渠道执行

来源精确形状：

| sourceType | sourceEventId / lateEventId | sourceBizId / sourceDecisionId | 允许类型 |
|---|---|---|---|
| LATE_PAYMENT_TIMEOUT | 保持既有真实迟到支付事件及兼容规则 | NULL / NULL | FULL |
| MERCHANT_REJECT_ORDER | 真实拒单事件 / NULL | NULL / NULL | FULL |
| MERCHANT_APPROVED / MERCHANT_TIMEOUT_AUTO | NULL / NULL | 真实 applicationId / decisionId | FULL |
| AFTERSALE_DECISION | NULL / NULL | 真实 caseId / final decisionId | FULL / PARTIAL |

AFS `created_event_id` 仍为 REFUND 创建事件；不要把 caseId / decisionId 冒充 sourceEventId。`refund_order.aftersale_id=caseId`、`refund_application_id=NULL`、initiator_type=OPS、initiator_id=真实运营；业务 source_type 统一采用 `AFTERSALE_DECISION`，正式同步旧 Schema 注释中的 AFTERSALE，不能两个拼写混用。

首次发送 PAYMENT 必须独立读取 REFUND 执行事实、AFS 持久最终决定、ORDER 创建提交证明、PAYMENT 自己的原成功付款及 §2.4 权威资金资格，交叉核对：所有归属 ID、原支付号和原渠道交易号、支付成功事件、币种、实付和时间、case 原阶段、决策时核销证据、决定类型、金额、操作人、command、refundNo、bindingVersion、持久资金证明和当次 FIRST_SEND 权威复核。客户端 DTO、事件存在、付款成功或裸 RESOLVED 不构成充分出款授权。

金额规则：先验证 `originalPaidAmount` 等于 ORDER / PAYMENT 全部实付事实；然后 FULL 要求 `refundAmount=originalPaidAmount`，PARTIAL 要求来源恰为 AFS 且 `0<refundAmount<originalPaidAmount`。禁止将 PAYMENT 的所有 `amount=paid` 条件直接改成 `<=`。拒绝 0、负数、超过两位小数、超过 DECIMAL(18,2)、PARTIAL=paid、PARTIAL>paid、FULL<paid、未核实币种。

`refund_ratio` 不是渠道金额和后续积分扣回的权威来源。候选确定为 amount / originalPaidAmount、6 位 HALF_UP 派生值（需随 CCR 定义），PARTIAL 在极小金额或接近全额时可能舍入为 0.000000 / 1.000000，不能据比例反推 FULL/PARTIAL。后续积分消费者按原金额比精确计算及已批准舍入，不使用已舍入 ratio 连乘。

比例存储证据：`docs/03-database/06-核心数据库Schema-v0.1.sql:426` 定义 `refund_ratio DECIMAL(10,6) NOT NULL`；紧邻 `:425` 的资金金额为 DECIMAL(18,2)。该比例列有4位整数、6位小数，能容纳候选闭区间 `[0.000000, 1.000000]`，因此这项比例候选本身无需扩列精度。`backend/pet-refund-biz/src/main/resources/mapper/RefundApplicationMapper.xml:23` 现有普通退款写 1.000000；`backend/pet-refund-biz/src/main/java/com/petplatform/refund/biz/application/LateRefundService.java:222` 当前强制比率等于 BigDecimal.ONE，AFS PARTIAL 校验须改为核对按已定稿舍入算法派生的值，而非沿用等于1。SQL列容量已核实，HALF_UP仍是契约候选，不把容量证明当成产品舍入规则批准。

保留 PAYMENT “MAY_HAVE_SENT 先落库，再网络调用，之后仅原号查询”的协议；绑定和源决定不可改金额。UNKNOWN / 重启 / 请求丢失不新建退款号，不盲目重发，不释放预约。PAYMENT 已观察到退款后，查询恢复不能再要求付款行仍为 PAID；保留 `loadForQuery` 与首次发送分离的现有保护。

所有退款执行来源分支显式穷举；当前 `taskType` / `issueCode` 对非普通非迟到来源隐式落入拒单分支，AFS 必须显式增加且未知来源失败，不能错误地拿 MERCHANT task/source 发款。

## 6. ORDER 成功投影与 SCHEDULE 释放

新增 `ORDER_AFTERSALE_REFUND` 消费 `RefundSucceededEvent.v1`，仅拥有 AFS 来源；严格字段集不变，类型从可信执行事实输出。消费事务：store guard → ORDER / AFS 来源证明 → REFUND 最终渠道成功事实 → 唯一消费 claim → ORDER refund amount/type/status/success proof → SCHEDULE 原预约释放，全体同 DataSource / RC 提交。

- FULL 成功表现为已退款；PARTIAL 成功表现为部分退款、订单业务结束。退款创建后至最终成功期间为退款中。DisplayOrderStatus 只能由 ORDER 推导，AFS 与前端不自行拼状态。当前代码未发现已交付的完整 ORDER 展示派生器，正式计划须明确该内部读侧交付点，不能用写入 refunded_amount 代称全部展示已完成。
- 不覆盖 `verified_at/completed_at`、核销证明或把未核销订单伪造为 COMPLETED/VERIFIED。PARTIAL 终局通过可信退款状态表达；保持原履约历史。
- SCHEDULE 接口复核 `RefundSuccessFact.refundType/source/amount`，AFS PARTIAL 合法时释放原已确认预约仍占用的资源；已释放必须有同一退款的既有释放证明才幂等成功。失败或 UNKNOWN 不释放。
- ORDER 成功证明唯一绑定 successEventId/refundId/succeededAt/type/amount，重复事件核对一致后 NOOP；并发重复、claim成功但后续失败、释放失败均整体回滚。不能先 claim 后单独异步释放。
- 非退款 AFS 终局不走任何退款释放接口，不复用已关闭退款来释放资源。
- 现有迟到付款的关闭订单投影独立保留；不把该来源改为正常订单路径，不恢复原预约或优惠券。

## 7. SQL50 增量候选与所有权

所有应用生产 SQL 位于本 Owner MyBatis XML。以下不是可执行迁移，不可在生产运行。

| Owner | 表 / 增量 | 必须约束或检查 |
|---|---|---|
| AFS | `aftersale_case` 新增 command/event、final_decision_id/refund_order_id、来源锚点/版本、原付款或源证明引用、敏感文本密文 | 保留 active_order_guard 唯一；case 与最终决定、退款单引用不能任意重绑；历史失效不抹除 |
| AFS | `aftersale_command` | 23号二进制五元组唯一；加密规范参数及首回执；失败保留 RESERVED；同 key 异参冲突 |
| AFS | `aftersale_decision` | UNIQUE(case_id)、UNIQUE(command_id)、真实 PLATFORM_OPERATOR、类型/金额、版本、时间、原因密文；首次终局不可二次裁决 |
| AFS | P4受理核验/重复关闭关联（仅候选获批后） | 服务端真实历史终局集合版本、新问题说明/核验的受保护引用、真实操作者/时间；重复关闭绑定priorFinalCaseId，明确不生成decision或refund来源 |
| AFS | `aftersale_refund_commit` | UNIQUE(case_id)、UNIQUE(decision_id)、UNIQUE(order_id)、UNIQUE(refund_order_id)；保存退款号、创建事件、金额、类型、command与来源阶段；与决定同事务 |
| AFS / REFUND 各自审计所有权 | AFS资金检查尝试审计；REFUND `refund_funding_eligibility_proof` 候选及执行绑定引用 | 记录真实 authority/源fact/version/政策/phase/时效/身份/原付款/金额/绑定hash/受控证据引用；AFS决定及退款commit绑定同一DECISION_COMMIT证据，FIRST_SEND证据追加不覆写；失败尝试审计不充当资金授权。该表仅保存来源证明，不自建余额或结算账本 |
| ORDER | `order_aftersale_source_proof` | 每 case 来源准入 / 绑定证明，核销或真实普通拒绝来源，真实归属和原支付；版本及时间可核实 |
| ORDER | `order_aftersale_refund_commit` | PRIMARY/UNIQUE(order_id)、UNIQUE(refund_order_id)、UNIQUE(decision_id)、UNIQUE(success_event_id)；case/sourceStage/原付款/授权类型金额/当前orderVersion/token/创建与成功证明 |
| ORDER | pet_order 当前 AFS 投影 | 已有 current_aftersale_id/aftersale_status，复用并 CAS；如内部展示需要新增 refund_type/status 列，必须明确只由本域提交写，不由 AFS 跨表写 |
| ORDER | `order_operation_guard` | 复用 CREATE_REFUND/VERIFY，不新增资金冻结；保留同事务 token / 状态 / 版本 fencing |
| REFUND | `refund_order` | 现有唯一 `uk_refund_order_once(order_id)` 保留，包括 FAILED/UNKNOWN；现有 aftersale_id 绑定真实 case |
| REFUND | `refund_execution.refund_type` 候选新增 | 将业务类型固定为执行绑定的一部分；历史先验证只含已认可来源且业务 FULL，再回填 FULL；不可 default FULL 掩盖未知来源 |
| REFUND | `chk_refund_source` / `chk_late_refund_amount` | 新增 AFS 精确来源形状；金额联合 CHECK：原来源只FULL且=paid；AFS FULL=paid / PARTIAL在(0,paid)，显式 NOT NULL 避免SQL三值逻辑放过NULL |
| REFUND | 既有 `refund_reconciliation_issue` 或 AFS执行附属问题表 | 脱敏固定码、OPEN/RESOLVED；逐笔坏证明隔离，不改变金额或凭异常推定最终失败 |
| PAYMENT | `payment_refund_dispatch` | 既有 originalPaidAmount 与 refundAmount 足以容纳PARTIAL；类型在可信执行绑定中取，不随渠道响应改写。如增加类型镜像同属PAYMENT迁移 |
| AFS | 既有 `aftersale_verification_proof` | 原不可变证据保留；修改查询语义不改写旧 proof 指向当前新工单 |

迁移不能因已经有核销历史就要求清空表。SQL48/49 已承诺的迟到、拒单、普通退款、核销、幂等记录须兼容保存；对缺少可核实准入/裁决来源的旧 AFS 行，必须门禁报告并另行做真实映射审核，不凭 status 猜造正式授权。测试夹具 AFS 与生产历史区别披露。MySQL DDL 非全事务，提供前置检查及分步失败核查，不宣称失败自动回滚。

## 8. 事件、任务、恢复与兼容部署

### 8.1 事件

`RefundOrderCreatedEvent.v1` 与 `RefundSucceededEvent.v1` 字段集合保持原契约；新 source/refundSource=`AFTERSALE_DECISION`，refundType 可 FULL/PARTIAL，真实金额及事件时间保持。caseId/decisionId 不追加到严格 v1，消费者从受信任公共事实查询。

AFS 可复用已有 `AfterSaleResolvedEvent.v1` 精确 payload：afterSaleId/orderId/decisionType/decisionRefundAmount/decidedAt；非退款金额 NULL，退款金额为真实最终授权额。不要把该事件当成退款创建器或禁止核销的单独证明。已有 API `OTHER` 与核心 Schema 注释 `OTHER_NON_REFUND` 的命名不一致，API50 必须定稿统一映射；不能私自生成两个决策语义。需要新增运营人、门店等 payload 时应使用 v2，不能扩张严格 v1。

核销失效通知沿 `AfterSaleInvalidatedEvent.v1`（reasonCode=VERIFICATION_WON_RACE）候选，同核销事务由 AFS 唯一产生；当前最小失效实现只写日志和 proof，没有发布该事件，需由 AFS Owner 披露补齐。不重复生产 OrderVerifiedEvent.v2。

### 8.2 任务

| taskType / key | Owner / payload | 行为 |
|---|---|---|
| `AFTERSALE_REFUND_SUBMIT:{refundId}:0` | REFUND；refundOrderId/storeId，bizType=REFUND，expectedVersion=0 | 仅 AFTERSALE_DECISION；映射 PAYMENT 既有 TASK:REFUND_SUBMIT:{refundId}:0 |
| `AFTERSALE_REFUND_CHANNEL_QUERY:{refundId}:0` | REFUND；相同不可变绑定 | 仅原号查询，映射 PAYMENT TASK:REFUND_CHANNEL_QUERY:{refundId}:0 |
| AFS 来源/渠道恢复扫描 | REFUND 主导，AFS/ORDER/PAYMENT 各公共事实参与 | 扫 CREATED/PROCESSING/UNKNOWN，严格重建原任务参数并恢复原key；不重新创建退款 |

不新增“AFS 最终裁决后 CREATE_REFUND”任务，因为该事务形态不产生已终裁无退款的合法状态。发现这种孤立历史应记录 AFS_REFUND_COMMIT_INVALID 并停止出款，不能通过补单把未核实旧决定升级为真实来源。

现有 `RefundExecutionService.reconcileDeadTasks` 只给 DEAD/CANCELED 写异常，并未自动恢复所有渠道任务；增加 AFS handler 不等于已恢复完整耐久链路。候选复用49号 `JdbcAsyncTaskRecoverer`，逐笔复核源事实和任务完整不可变元数据后，只恢复合法 DEAD/CANCELED/SUCCEEDED 的原任务；READY/RETRY_WAIT/RUNNING 不动，不抢租约、不换key。缺失任务可按本域真实执行事实 enqueue 原key；存在参数冲突记录问题不覆盖。渠道 MAY_HAVE_SENT 时恢复 submit handler 也只能查原号。

### 8.3 部署顺序

1. 正式 CCR/API50/SQL50/事件/调度/测试映射先获准；真实退款终裁另以 OD-W0-001 权威资金资格来源和并发保证获准为必要门槛，技术测试通过不替代。先隔离 QA 跑迁移并验证原来源数据兼容。
2. SQL50先落地，再部署读取新列的二进制。AFS命令开关默认关闭，整个付款/退款读侧缺真实依赖失败关闭。
3. 先部署全部消费者和来源分派兼容，再启用任何 AFS 事件生产。旧普通与拒单消费者须允许识别合法 AFS FULL/PARTIAL 并跳过；迟到消费者也须显式识别新来源。未知来源仍失败关闭。
4. 同批部署 AFS / ORDER / REFUND / PAYMENT / SCHEDULE / task handler 完整公共接口装配与验证，旧来源回归通过后才允许独立申请启用；本提案不授权生产开关或迁移。
5. 回退仅关闭新准入；保留已提交决定、退款、proof、任务和新列，继续原号执行/查单。产生 AFS 事件后不可直接回退到只接受旧来源的消费者二进制。

## 9. 故障点、验收与依赖

| 验收组 | 必须可证实的结果 |
|---|---|
| AFS原子事务 | 在决定插入、退款插入、执行绑定、AFS绑定、ORDER CAS、日志、两类Outbox、任务、首回执、beforeCommit任一点注入故障；无任何孤立终局/退款/投影，Admission独立保留 |
| 真竞态 | 两连接/线程分别先提交VERIFY或AFS FULL/PARTIAL；仅合法一方成功；未提交裁决不抢永久核销禁令 |
| 来源隔离 | 旧INVALIDATED case、伪造decision、未核实运营、非退款decision、金额篡改、其他订单/门店/paymentNo、跨事务token全部阻断；新VERIFIED工单退款成功 |
| P4来源与状态隔离 | 未受理PENDING、WAITING_SUPPLEMENT不得终裁；存在历史终局却缺新问题核验或历史集合版本过期拒绝；CloseDuplicate只引用真实同单终局、仅PENDING→CLOSED且零新decision/退款 |
| 资金资格缺失 / 失真 | 无Provider、UNKNOWN、BLOCKED、过期、证据身份/金额/hash不符、源版本改变、测试authority进入生产全部阻断；无最终裁决和退款，失败审计不是决定 |
| 资金资格恢复 | 已建退款但首次发送前资格失效时保留原承诺及原号、渠道零发送；MAY_HAVE_SENT后仍查询原号并接受真实成功，不把变更后的资金状态用来吞掉已发生出款；真实并发保证须单独权威验收 |
| 普通退款回归 | PENDING或已批准未建单时核销后全额建单仍成功；历史AFS存在不破坏已批准承诺；拒绝再申请和旧任务不覆盖新轮 |
| PARTIAL边界 | 0、负值、=paid、>paid、三位小数、超DECIMAL范围失败；最小0.01、接近全额、六位ratio舍入边界通过且类型仍PARTIAL |
| 旧来源保护 | 迟到实际实付全额、关闭订单不恢复；拒单真实决定授权；普通全额金额和原本金仍严格相等；任何旧来源PARTIAL失败 |
| 渠道恢复 | MAY_HAVE_SENT前后崩溃、渠道成功响应丢失、UNKNOWN、付款行后来不再PAID、重复callback/query；原refundNo、一次业务退款、无重复出款 |
| 最终投影 | FULL/PARTIAL成功事件真实性核验；claim/ORDER/SCH任一步失败全回滚；重复事件只释放一次；UNKNOWN不释放 |
| 历史证据 | 核销后新AFS、非退款结束、撤回再申请不修改核销历史；当前AFS反向孤儿或错误pointer仍失败关闭；旧首回执固定 |
| 事件混合 | 新AFS PARTIAL同时经过旧消费者时合法跳过；未知source、异字段集、伪造事件不被当成出款授权 |
| 恢复隔离 | 损坏第一笔来源不饿死后面合法项；任务元数据冲突不覆盖；DB整体故障不得被吞成扫描成功 |
| 架构/迁移 | 无biz→biz、无跨域Mapper/Repository/DO；所有生产SQL本域XML；持久层检查、架构检查、SQL50数据兼容与完整相关测试通过 |

依赖 AFS Owner 交付真实创建/受理/补充/证据/撤回/终裁及运营身份/RBAC、公用当前事实和叶子证明；ORDER Owner 交付准入/当前投影/历史证明拆分及CREATE能力；REFUND/PAYMENT/SCHEDULE资金Owner各自实施上述本域增量；QA负责隔离MySQL真并发、原子故障注入和旧来源回归。权威资金 Owner / Provider、来源契约和并发有效性必须由 OD-W0-001 补齐，不能由 fixture 或已有 PAYMENT成功事实替代。正式契约Owner定稿所有DTO/SQL50/错误码/事件任务精确载荷后方可进入获准范围内的生产代码。

最小可交付切片建议：在当前工作流产品问题与技术 CCR 获准后，先交付真实 AFS 创建/受理/补充/证据/撤回、已有未履约核销失效衔接、历史核销证明解耦、ORDER当前售后投影，以及 REJECT/RESERVICE/OTHER 非退款终局。资金侧仅交付默认关闭的资格公共port、明确缺失失败、审计绑定和隔离测试中的原子终裁/退款来源内核；不接生产常量资金状态，不启用真实 FULL/PARTIAL 最终裁决或渠道出款。该切片可分别验收已交付工作流和技术内核，AFS-002 退款终裁全链路、真实资金资格与实际出款仍标 BLOCKED / 未验收，不把所有AFS能力一并称为完成。

## 10. 后续联动边界与完成声明

本候选不新增资金冻结、分账、支付异常裁决、积分消费、复审、MFA或内部双人审批。部分退款成功后的积分比例扣回、FULL返券/PARTIAL不返券、已核销评价可展示但不计分、未核销部分退款不可评价、站内通知真实送达均是后续消费者范围；本技术候选的目标是具备权威资金资格后产出可信且可靠的退款成功事件和准确金额，目前不声明真实资金来源、实际出款或后续联动完成。

RESERVICE平台内再预约/再核销、STAFF授权、HTTP/小程序端到端也不由此提案隐式实现。产品 P1～P4 四项答复均未收到、最终CCR未定稿以及 OD-W0-001 权威资金资格/已分账处理边界为当前真实阻断；相关代码/迁移/测试尚未实施，不以PR96旧测试或隔离资金fixture通过替代AFS验收。售后七天包含端点已由正式Scheduler契约确定，不再作为新增产品阻断。
