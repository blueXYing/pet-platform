# AFS 契约独立交叉审查

日期：2026-09-30。状态：REVIEWED_WITH_OPEN_GATES；不是功能验收或实现批准。本轮只审查领域/资金候选及真实基线代码，功能测试全部 NOT_EXECUTED。

## 1. 审查结论与来源

原子提交AFS退款终裁与实际退款单、按来源处理核销资格、PARTIAL保持独立金额证明、旧来源消费者分派和历史证明解耦的方向可接受。**尚不能进入无条件生产写路径**：P1–P4产品回执未收到；同问题终局识别仍是新产品/协议候选；可退资金/分账事实没有真实已交付来源；ADMIN动作和AFS私有证据用途/读取协议必须同步实施。

审查输入：[总提案](../../../ccr/CCR-W2-API-001/aftersale-workflow-proposal.md)、[领域提案](AFS-DOMAIN-PROPOSAL.md)、[资金提案](MONEY-INTEGRATION-PROPOSAL.md)、[产品待答记录](PRODUCT-DECISIONS.md)、[验收矩阵](ACCEPTANCE-MATRIX.md)、[Contract48](../../../../docs/04-api/48-Verification-Completion-Contract-v0.1.md)、[Contract49](../../../../docs/04-api/49-Refund-Application-Contract-v0.1.md)、[私有资产31](../../../../docs/04-api/31-Private-Asset-Contract-v0.1.md)与[Scheduler09 §33](../../../../docs/06-scheduler/09-Scheduler-Retry-Compensation-v0.5.md)。基线为PR96 `ff983596cad899c7b4e1ce9c8278228787ca7ac6`。提案在本轮并行修订，下面区分已吸收建议与仍未封板的门禁。

## 2. 必须解决的准入门禁

| 编号 / 严重性 | 证据与可复现场景 | 可执行处理与关闭条件 |
|---|---|---|
| CR-01 / P0 产品 | P1一单单活动、P2撤回重提、P3普通退款并存均仍PENDING_USER_ANSWER。现有单指针/唯一active只是技术约束；普通已批准承诺不能因后来AFS被悄然取消。 | 将用户原话和被选择分支同步正式来源。只开启获批路径；若允许并存，补齐两个合法资金承诺的胜负/终态/通知语义，不用唯一退款键异常无限重试代替产品结果。 |
| CR-02 / P0 问题身份 | `UNIQUE(case_id)`保证每工单一次决定，`UNIQUE(active_order)`保证一单一活动，二者都不阻止REJECT/OTHER后换requestId/描述重开同一问题。以order+typeCode永久去重则会误拒核销后同类新事实。 | 领域§3.2已补人工新问题核验候选，总案§3进一步以P4候选定义重复问题CloseDuplicate关闭、不生成新决定；仍未批准，不能称已解决。将新增事实、服务端完整历史终局引用、受理资格记录和退款门禁写入正式DTO/Schema/状态机后才开放该分支。不能只加problemId而没有可信判定过程。验收同分类不同事实、不同分类同事实、遗漏旧终局、未履约失效后真实新问题、撤回后重提。 |
| CR-03 / P0 钱 | 领域§4指出运营PRD需判断已分账；原资金提案的permit/NormalPaymentOrigin/最终授权事实只有支付成功；最新总案§5已补可退资金port候选及明确上线门禁，但真实资金/分账/追回证据仍未交付。PAID、refund不存在、查不到分账记录、运行开关关闭均不能证明尚未分账。 | 正式定义真实资金Owner的事实接口、权威记录/外部回执来源、状态及版本、订单/原付款/币种/可退额绑定、并发与有效期。未知/已分账但追回未交付→退款终裁及建单失败关闭；非退款/证据流程可独立推进。缺生产适配时保持新退款入口关闭，测试替身只验接口行为，不报告真实资金路由已验收。 |
| CR-04 / P0 权限 | [AdminPermissionEvaluator](../../../../backend/pet-admin-biz/src/main/java/com/petplatform/admin/biz/domain/service/AdminPermissionEvaluator.java) DEPLOYED_ACTIONS现有六项无aftersale.*；通用RBAC接口存在不等于AFS动作已经可用。领域已披露此缺口。 | 注册获批read/handle/decide及真实集合入口，服务端绑定resourceType/merchant/city/scopeVersion；ADMIN_WEB解析当前principal，最终可写事务当前鉴权，历史回放重验读取权。不得常量true、客户端operatorId或SUPER_ADMIN直通。真实撤权/范围变更/旧RR快照需实测。 |
| CR-05 / P0 隐私 | 正式31用途仅商家材料/服务封面；现有读授权参数和适配绑定MER application/revision及identity.reveal。caseId塞入applicationId或复用封面签名会越界。 | 正式AFS purpose和typed资源授权；owner/READY/hash/版本/工单关联及角色可见范围均由真实公共事实复验。发放/消费检查当前会话、权限、工单/证据绑定和隔离状态，敏感字节访问审计。真实OSS/扫描未实测明确留缺口，不能用seed READY或内存对象宣称生产附件闭环。 |
| CR-06 / P0 兼容 | [OrderRefundApplicationApiImpl.normal](../../../../backend/pet-order-biz/src/main/java/com/petplatform/order/biz/apiimpl/OrderRefundApplicationApiImpl.java) 未核销只要currentAftersaleId非空就失败；已核销要求历史核销AFS快照等于当前指针且非空仅INVALIDATED。合法新VERIFIED工单或已撤回/非退款结束历史会破坏资格。ORDER/AFS requireCommitted也耦合当前指针/无活动工单。 | 分离提交瞬间断言、不可变历史来源读取、当前工单一致性三个职责，给明确公共API；保留正反孤儿检测及原失效记录完整性。不能简单删除AFS检查，也不应仅把caseVersion等号改为>=而允许终态证据被改写。覆盖合法后续新工单、历史非退款/撤回、普通已提交承诺和旧核销回执。 |
| CR-07 / P0 PARTIAL | [SQL42](../../../../docs/03-database/42-Late-Refund-Execution-Schema-v0.1.sql) `chk_late_refund_amount`全局退款额=实付；旧普通/拒单消费者在source分派前强制FULL。仅Java允许PARTIAL必定建单失败或使旧消费者无限报错。 | 资金提案已列正确增量；正式SQL/API/Event/部署顺序须同时冻结并验证：来源×类型×金额联合NOT NULL/CHECK、原来源仅FULL、AFS明确类型、可信事实贯穿PAYMENT/REFUND/ORDER/SCHEDULE；先通用结构与已知source分类，再执行本来源FULL限制。新来源事件生产前先部署所有消费者兼容。 |

CR-01/02是产品/协议未定，不能由QA拍板；CR-03是实际依赖与出款授权门禁；CR-04至07是已识别的必需契约/实现增量。现阶段它们不意味着候选方向被否决，也不允许把未实现能力写成验收通过。

## 3. 资金授权必须补足的具体约束

CR-03不能仅增加`settled=false`布尔字段。候选事实至少需标明可信来源、付款/订单身份、币种与金额、资金路由类型、当前状态及不可变来源/版本、核验时间；真实Owner承担一致性，并通过公共API提供。具体字段与Owner由契约负责人定稿，本审查不发明已上线的结算域。

还需明确“判断未分账”与随后执行退款之间的竞争：如果结算能在AFS终裁后、渠道首次发送前完成，仅终裁时拍一张未分账快照仍不足。须选择并证明已存在的串行化/版本栅栏、可靠可退路由或首次发送前重新核实规则；不在此次擅加资金冻结产品。资格变化不能把已建退款无限挂起而不披露恢复路径，已发生MAY_HAVE_SENT则只能原号查询，不得因状态变化重建/重发。

若本切片没有任何真实可退资金适配，允许交付默认关闭的内部来源协议与隔离测试，但必须明确FULL/PARTIAL“真实运营到生产资金”的闭环仍未完成。模块单测注入受控FundsFacts可以测拒绝未知/已分账、绑定错金额/版本等负例，不能证明真实结算事实来源。任何测试报告须分列“协议替身通过”和“真实Provider未验”。

## 4. 可接受的技术选择及实施约束

| 选择 | 审查意见与验收约束 |
|---|---|
| AFS退款终裁+refund_order单短事务 | 接受作为最小候选。AFS决定/工单终局、REFUND单与execution、ORDER证明/指针、日志/Outbox/task/首回执一起提交。不要复制普通退款批准后异步建单，否则核销已完成的旧未履约终局单仍可出款。全程同DataSource可写RC与共享store guard；网络在commit之后。 |
| CREATE_REFUND与VERIFY同门店guard | 接受。核销先提交→旧未履约工单失效且无退款；AFS建单先提交→任何退款渠道状态均阻核销；AFS回滚→不留永久核销阻断。新VERIFIED工单与普通已批准退款可使用各自合法来源，不按VERIFIED全局拒款。 |
| 历史证明与当前指针分离 | 接受，必须有双层API而非弱化同事务断言。[VerificationCompletionService](../../../../backend/pet-verification-biz/src/main/java/com/petplatform/verification/biz/application/VerificationCompletionService.java)成功重放目前读VER自身receipt，不能声称现有旧回执路径已实际失败；明确是后续兼容风险和普通资格实际耦合。 |
| beforeCommit闭环与叶子证明 | 接受。明确每条证明的Owner和唯一键，叶子API不反向递归调用协调者；错误事务/token/单域孤立提交必须回滚。测试需真实commit后ACK损失而非commit前异常。 |
| PARTIAL严格金额、ratio仅派生 | 接受。金额为权威，旧来源永远FULL；6位ratio可因极端比例成为0或1，不能反推类型/直接连乘积分。SQL三值逻辑以NOT NULL和联合约束防空值放过。 |
| 原号耐久执行与逐行坏来源隔离 | 接受。缺任务按真实原来源恢复原key；DEAD/CANCELED/SUCCEEDED严格元数据及fencing，RUNNING不抢租约；损坏来源记录问题不改钱，DB整体故障不能吞成成功。孤立“AFS终裁无退款”不是合法补单来源。 |
| OWNER意见、单运营终裁 | 接受现已批准权限模型。OWNER意见的“部分同意”不授权PARTIAL；STAFF绑定未交付继续失败关闭，不能用员工表/手机号推导权限。已提交合法退款恢复不依赖原运营仍在职，但新命令与结果读取必须当前鉴权。 |
| RESERVICE只记录结论/安排 | 作为本切片范围声明可接受；不自动造第二预约、复活核销码或突破单次服务。不得对用户或验收声称再次服务实际完成。 |

## 5. 时间规则更正与补证竞争

此前审查消息把七天包含等号误判为不一致，已撤回。正式Scheduler09 §33明确`now <= verifiedAt+7d`及`now <= appointmentStart+7d`，因此领域提案沿用包含端点是正确的；不再保留半开候选，也不要求新增产品问题。

AFS申请统一UTC毫秒闭区间`[t0,t0+168h]`：起点、截止前1ms、截止等号均可（其他资格成立），截止后1ms拒绝。等待锁期间跨过截止，按取得协调锁后的权威当前时间判定。七天限制创建，不限制运营处理完成；不为每单生成资格关窗任务。

补证请求有运营指定的独立` supplementDeadline `，领域提案的超时任务在`now>=supplementDeadline`生效，只回PROCESSING而不自动退款/驳回。该机制与七天窗口、普通退款24h均不同。root现已明确待技术CCR批准的候选：只有锁内now<supplementDeadline的指定方提交才可满足该轮；deadline等号/之后无论worker是否已运行均不能提交满足该轮。timeout在now>=deadline回PROCESSING；一般新证据仅按当前状态追加，不能回写过期round为及时完成。该候选消除worker调度延迟影响，已写入矩阵oracle，但未测试、未正式批准，不增加产品询问。覆盖deadline−1ms、deadline、deadline+1ms、补证与超时双顺序、旧轮任务和已终局NOOP；不新增统一24h补证期限。

## 6. 新问题人工核验候选的审查边界

领域§3.2与总案§3已响应CR-02，提出新事实说明、服务端历史列表、运营受理前新问题核验及资格记录。总案采用P4独立补问：重复问题PENDING→CLOSED并引用原终局，不生成新REJECT决定；新问题才受理。该行为当前未批准，不得藏在普通Accept内部直接实现。领域原先“新REJECT终局”候选应统一为总案实际提问口径，避免两套实现。

若选此候选，必须明确首次新工单在未完成问题核验前不能退款终裁；历史列表由服务端完整获取并在同一guard下验证，不能由用户省略旧决定；资格记录和受理/重复关闭结果原子提交，错误分类不能修改旧终局。重复关闭只引用原决定，不得建立新决定、改变原金额或原事实裁定。problemId、typeCode、描述hash只是标识/完整性工具，不是自动判定证据。

核销导致原未履约工单INVALIDATED与正式运营终局不同，撤回也不是正式决定。两者都不能被“存在任意历史case”规则永久封锁；已核销后相同类别但不同具体事实的新问题必须可按原七天窗口进入批准路径。是否允许正式终局后其他新问题、如何人工确认由产品裁决，不由本报告决定。

## 7. 正式契约收敛与范围审查清单

在正式API50/SQL50冻结前，把领域`OrderAfterSaleApi`与资金`OrderAfterSaleCommitApi`、AFS决定facts命名及Owner收敛为单一方法表；区分当前事实、同事务permit和历史已提交事实，避免实现者选择冲突版本。统一AFTERSALE_DECISION/OTHER命名、状态RESOLVED/CLOSED映射及终局非退款金额NULL、ID/版本String和UTC毫秒。

锁序需有真实调用图：领域候选包含REFUND事实→AFS→ADMIN→资产，资金候选包含ORDER→AFS→REFUND；共享store guard可串行同店业务，但资产读取/ADMIN撤权入口不一定先拿该guard，故须审查附件授权回调反序。正式方案必须说明共享锁之外的行锁顺序，不能仅靠“大家共用guard”排除所有死锁。

所有新增动作/资源类型/purpose、决定/补证事件严格字段、任务key/载荷、错误码、SQL CHECK/索引和既有消费者跳过策略必须披露。补证/撤回/受理新增通知意图不等于通知已送达。默认关闭并不豁免新增读侧依赖列的部署顺序，也不允许回退删除已有退款/证明/任务。

独立验收按矩阵进行：真用户与ADMIN会话、真普通拒绝/核销、真API建立私有资产→AFS→终裁；故障与损坏负例可seed，正向来源不可seed。串行Maven、随机schema/Redis命名、真实回滚与双顺序竞态。既有PR96 CI通过不替代这批测试。

当前范围不得声明完整AFS生产就绪、真实已分账追回、STAFF、HTTP/小程序/真机、积分/券/评价全部联动或站内通知实际送达完成。若只实现内部默认关闭的协议，应按此限定写PR与风险，不能关闭所有相关大Issue。

## 8. 总提案交叉检查补记

已实际读取根总提案（状态PROPOSED_REQUIRES_REVIEW / NOT IMPLEMENTED）。总案已吸收原子终裁、真实资金port缺依赖关闭、P4问题核验、七天闭区间、历史证明拆分、PARTIAL联合CHECK与来源分派，且明确A2完整上线仍阻断；这些属于可审阅方案完善，不关闭对应实现/验收门禁。

[OPEN_DECISIONS](../../../OPEN_DECISIONS.md) 的OD-W0-001明确资金权威规格缺失，禁止自行把售后七天推导为资金冻结状态机；总案引用准确。故允许分别交付A1和test-only A2协议内核，但缺依赖拒绝测试通过不能等同AFS-002全链路交付。

已复核文案同步：领域§3.2重复问题已改为总案P4实际询问的CloseDuplicate/CLOSED且无新决定，字段已统一newProblemStatement/newProblemAssessment及历史集合版本；PRODUCT-DECISIONS已新增P4待答记录。这消除文档冲突，但P4仍未获批准。补证截止提交规则按§5候选已明确待测试。root正统一接口名为OrderAfterSaleFactsApi、OrderAfterSaleCommitApi、OrderAfterSaleRefundFactsApi及AfterSaleRefundFactsApi；冻结前仍需核对全部引用和真实锁序调用图。领域新增总案相对链接层级错误已告知Owner修复，本审查不越权改他人文件。

## 9. 检查记录

已执行只读源码/文档审查；没有运行Maven、数据库或任何AFS功能测试，没有Git提交。已补审实际落盘的根总提案；文档静态检查已执行：UTF-8有效、无BOM、LF，本文14个本地链接均存在；同时复查验收矩阵25个本地链接均存在且仍为UTF-8无BOM/LF。全部AFS功能仍NOT_EXECUTED。
