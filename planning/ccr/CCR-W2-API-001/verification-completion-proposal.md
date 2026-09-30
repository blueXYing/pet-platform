# 商家真实核销接续方案：K1 / K2

状态：APPROVED_IMPLEMENTING，2026-09-30。用户回复“批准”，批准 M94、K1、K2 及本文实施/验收顺序。PR94 已合入 develop，merge `eb083cbbb1ae6195db97e438c182c4ed817594e2`。以下调查和候选措辞保留准备时点历史；正式实现以新增 Contract48 为准。新 PR 仅供审阅，不合并、不生产迁移或启用。

## 1. 目标与已验证基线

目标：经真实当前身份授权的商家，在确认后使用当前有效码完成一次核销；订单完成、旧码不可用、当前未履约售后失效必须一致，不因请求重试或退款竞态产生部分成功。功能默认关闭，完成实现及测试后提交PR审阅；合并、生产迁移、生产启用分别保留显式授权边界。

PR94仍OPEN，head `89271e9428bcb087823ac36b7adf12c9c51d18c0`；CI36551704404六项成功，下载115份Surefire报告核对730测试、零失败/错误/跳过。尚无GitHub审阅记录。检查通过不等于已批准合并。

基于PR94开展准备，不把它说成已合入develop。原始用户工作目录保持不动，新准备分支为 `codex/verification-completion-plan-20260930`，复用空闲worktree。

## 2. 权威来源与真实缺口

| 来源 | 已有要求 / 现状 | 处理 |
|---|---|---|
| SSOT §7、§15、§36 | 退款单创建阻止后续核销；核销先成功使当前未履约售后失效；OFFLINE保持存量履约；V1/V2码与风险规则已批 | 沿用，不重新裁决次数/期限/退款规则 |
| 商家PRD §5.2、§5.6、§5.8 | 主账号与获权员工；子账号按门店授权；禁止共享账号；扫码后二次确认，手动输入需原因 | 主账号先交付不删除后续员工及手动兜底要求 |
| Contract27 §5、HTTP10准入补充 | OWNER用真实USER身份，不虚构staffId；服务人员档案不等于登录成员；STAFF绑定/门店授予未交付 | K1冻结主账号操作人表示及首切片范围 |
| Internal07 §10、SQL06核销表、Event08 §6 | 命令/成功记录/事件均依赖staffId，不能表达OWNER | 同步候选接口、存储和新事件版本，不能把userId塞进staffId |
| PR94 `VerificationCredentialService.check` | 顶层独立事务；VALID只是预检，不能在核销完成事务内直接嵌套调用并当最终授权 | 抽取仅VER模块内部复用的同事务校验逻辑；旧API继续保持原语义 |
| ORDER源码与Internal07 §7.3 | 当前有门店共享guard及支付/退款/确认事实；通用OrderOperationGuard与markVerified尚无实现 | 先冻结VERIFY提交协议；通用CREATE_REFUND依赖来源尚不齐全 |
| AFTERSALE源码 | 目前只有模块骨架，Schema有工单/日志；真实创建与裁决入口未交付 | K2仅引入真实Owner的当前工单事实/失效操作，不声称整个售后系统完成 |
| REFUND源码 | 已有迟到支付和商家拒单建单/渠道执行；没有完整未履约售后裁决退款来源 | 保持已批来源，未知来源拒绝；不造一个可随意建退款的测试API |

PRD只读提取自 `docs/01-prd/03-PRD-商家端-V1.0-最终基线.docx` 的word/document.xml，原文未改。SSOT优先于原PRD中已被裁决覆盖的下线、签约及失败次数条款。

## 3. 方案选择

| 方案 | 内容 | 取舍 |
|---|---|---|
| A（推荐） | 主账号真实授权先落地，再做核销完成与最小售后失效内核；之后补退款/售后完整来源和员工授权，最后开放HTTP/小程序 | 复用现有真实会话和归属，范围可验收；员工子账号暂不可核销 |
| B | 同批完成员工邀请、账号绑定、动作/门店授予、撤权，以及核销/完整售后退款 | 覆盖面广，但需要额外冻结成员开通、授权管理及售后裁决协议，明显增大本批范围 |
| C | 等完整员工与售后系统就绪，再开始核销完成 | 暂不改操作人协议，但核销主链路继续等待 |

不采用虚构staff账号、不采用手机号匹配授权、不把异步事件尚未送达作为允许旧工单退款的窗口。

## 4. K1：主账号真实授权与操作人协议（请求批准）

首切片只允许本人商家主账号；当前会话必须有效、用户ACTIVE，merchant.owner_user_id匹配，目标store确属该merchant。每次新执行和历史回放重新检查当前权限，不能凭旧准入响应授权。

复用PR94的AttemptAuthority端口，由boot组合真实USER会话和MER公共Owner事实；VER不直接依赖user-biz/merchant-biz。由可信适配层构造context；客户端不能提供operatorType/operatorId/staffId作为权限。下线门店按既定存量履约规则；FROZEN仍保持未决写动作关闭，不把此技术门禁冒充冻结政策的最终裁决。

候选 `VerificationActor`：`operatorType=USER, operatorId=当前userId, membershipKind=OWNER, operatorStaffId=null`。将来真实STAFF另经成员绑定和门店动作授予解析，才可带真实staffId；服务人员service_enabled、phone或角色名称均不自动授予核销权。

候选Schema扩展verification_record/verification_attempt：增加 `operator_type`、`operator_id`、`membership_kind`，原operator_staff_id改为可空；约束OWNER必须USER且staff为空，STAFF必须真实员工主体一致。历史行若有可验证员工来源可标记STAFF，无法核实的记录不得猜成OWNER；迁移预检失败则阻止启用。补充command_id及五元组Admission关联，取消旧的“全局request_id唯一”对不同主体同UUID的错误耦合，保留一单唯一成功核销。

原 `OrderVerifiedEvent.v1` 含必需staffId，推荐保留不偷改语义；新增 `.v2` 表达真实操作者。候选payload固定：orderId、verificationId、merchantId、storeId、operatorType、operatorId、membershipKind、operatorStaffId(nullable)、verifiedAt；所有ID为String、时间UTC毫秒，无码/手机号/昵称。ORDER拥有完成事实及事件，由一次提交只发布一个v2；通知/review消费者适配另验收，未接通前不启用。

K1批准包含这些API/Schema/Event差异的正式同步及实现验收；不等于员工账号系统或冻结写规则获批。

## 5. K2：核销完成与售后原子失效首切片（请求批准）

### 5.1 内部命令及回执

候选 `VerificationCompletionApi.verify` 输入：context、orderId、storeId、verificationCode、expectedCredentialVersion、confirmed。confirmed必须true；码词法沿用47号，version为非负十进制String。首切片method固定SCAN；手输核销码/订单号及原因字段留后续契约，不接受客户端把method改成MANUAL绕过验证。

候选回执：orderId、attemptId、resultCode、verificationId(nullable)、verifiedAt(nullable)、orderVersion(nullable)。成功为VERIFIED；无效码/过期码/风险锁为已提交业务拒绝，复用47号计数；无权/错店/参数错/版本冲突/依赖失败不累计码错误。响应不含原始码。成功重放返回同一verificationId/verifiedAt，不重写完成时间；不同requestId再次核销返回既有ALREADY_DONE错误，不能生成第二条记录。

namespace `verification.complete`；幂等五元组(namespace,operatorType,operatorId,STORE:storeId,requestId)，二进制唯一。规范输入和首结果加密，独立Admission保留失败绑定。同key异参冲突；完成结果重放仍须当前身份权限，但不要求订单再处于待服务，不能把历史回执当新授权。

公开核销HTTP仍NOT_IMPLEMENTED。未来界面展示确认卡片，服务开始后提示并再次确认；confirmed只是明确操作意图，服务端仍独立验证全部业务事实，不能证明用户确实阅读页面。

### 5.2 Owner分工及事务

VER顶层执行：独立Admission → 命令行锁 → 门店共享guard → ORDER当前资格/VERIFY保护 → REFUND真实存在事实 → VER当前码/版本/风险校验 → AFS当前工单事实。参与者必须同DataSource、同一个可写READ_COMMITTED事务和同guard实例；禁止子流程REQUIRES_NEW、各自提交或读取外域表。

ORDER候选 `OrderVerificationCommitApi` 负责VERIFY令牌及完成：令牌绑定order/store/command/currentVersion/当前事务，只供当前提交使用；不能持久化为跨请求通行证。`markVerified`只接受已核实的verificationId/credentialId/verifiedAt及AFS处理证据；原子更新COMPLETED、VERIFIED、verifiedAt、version，写ORDER日志及一次v2 Outbox。DisplayOrderStatus继续ORDER统一计算，保留原服务/支付/预约快照。

VER写唯一成功记录、成功尝试、凭证消费证据、幂等回执；ORDER及AFS各写自身事实。需要双方提交前证明避免孤立ORDER完成或孤立VER成功；业务/日志/Outbox/首回执任一点失败整体回滚。码消费后不可再使用；ORDER实时资格拒绝旧码，保留历史、风险锁和改期fence，不清零防刷记录。

AFS候选 `AfterSaleVerificationApi.invalidateCurrent` 由本模块查询真实当前工单，核对order/user/merchant/store/sourceStage、active_flag、状态及ORDER当前工单引用。没有当前工单是明确空结果；ORDER有引用但AFS缺行、AFS存在活动行但ORDER缺指针、跨单/未知状态都失败关闭，不能用空成功替身。

只将当前活动 `UNVERIFIED_POST_START` 且PENDING/PROCESSING/WAITING_SUPPLEMENT工单置INVALIDATED、active_flag=0、invalidated_at=同一verifiedAt、version+1，并写不可变状态日志及verificationId来源证明。保留证据和历史，不碰已结束工单或将来核销后新工单。ORDER同步当前售后状态，历史关联保留；核销后7天售后/30天评价窗口仍从verifiedAt计算，不在本批创建新售后/评价。

原Internal07描述异步OrderVerifiedEvent驱动AFS失效；候选将“当前未履约工单失效”加入同事务，事件仍供通知、投影及幂等核对。延迟/重复事件不能再次失效新工单；只允许对匹配的旧caseId/verificationId作幂等处理。事件消费者不承担最终阻断旧售后退款的唯一责任。

### 5.3 退款互斥的真实范围

本批VERIFY始终在共享guard下检查任何来源refund_order；已创建即拒绝。已有迟到支付与商家拒单建单继续使用各自真实ORDER来源，重跑既有回归，不增加任意传source/金额即可创建退款的入口。

通用CREATE_REFUND与未履约售后裁决闭环须在AFS创建/正式裁决/REFUND来源协议具备后接续，不能仅用SQL插入退款、测试替身或令牌单测宣称VER-002/QA-004已完成。其后续硬约束已确定：同guard下重读AFS当前状态和不可变裁决来源；旧未履约case被核销失效后拒绝该sourceBizId；核销后合法新售后/退款不能因VERIFIED而被一刀切拒绝。

因此本次完成的是“主账号核销提交与当前售后失效内核”，不是完整全来源退款×核销闭环，也不是生产可开放版本。首切片实现时若真实来源依赖缺失必须明确阻断，不能暗中扩到AFS创建/运营裁决全功能。

## 6. 分步实施与验收计划候选

1. 同步K1/K2批准回执及正式Contract/Schema/Event，明确AllowedModules增量：VER/ORDER及其API、MER/User公共事实和boot适配、最小AFS API/biz；REFUND限既有来源兼容验证。保持完整Issue未DONE。
2. 接真实主账号会话+Owner权限，替换生产缺失的AttemptAuthority；核对撤权/会话失效/跨店、OFFLINE与FROZEN门禁。禁止新增账号绑定推断。
3. 落同事务VERIFY校验、ORDER完成、AFS最小失效和v2事件；补消费后的凭证状态完整性，不破坏PR94刷新/风控/改期回放。
4. 执行隔离MySQL并发/故障验收、真实会话测试、架构及既有回归；报告中分别标注AFS行种子与真正创建入口。通过后提交默认关闭的实现PR。
5. 后续补齐通用CREATE_REFUND真实来源及员工开通/授权，再接公开读写HTTP、小程序确认流程和真机验收，均不预先宣称完成。

| 验收编号 | 必须证明 |
|---|---|
| KC-01 | 当前真实Owner会话通过；无权/过期/停用/错店拒绝，员工档案/手机号/伪staffId不能授权 |
| KC-02 | 正常支付→确认→当前有效码→一次完成；OWNER审计与v2事件无伪造staff；人工/自动确认、到店/接送、改期后均覆盖 |
| KC-03 | confirmed=false不完成；扫描后刷新/改期/版本变化/过期/风险锁，最终提交重新验证 |
| KC-04 | 同key并发和提交ACK丢失返回首结果；异参保留冲突；不同key并发仅一条成功/一次事件 |
| KC-05 | 第三次无效/过期失败仍持久化锁；基础设施失败不增加计数；拒绝结果回放不重复累计 |
| KC-06 | 活动未履约工单同提交失效，无工单正常；缺失/错配/未知事实拒绝，不影响历史或后续新售后 |
| KC-07 | 在VER记录/凭证消费、ORDER更新/日志、AFS更新/日志、Outbox/回执逐点失败全部回滚 |
| KC-08 | refund_order存在拒绝；保持迟到支付与商家拒单回归；通用售后退款竞态保持NOT_IMPLEMENTED，不伪报QA-004完成 |
| KC-09 | 不记录码、无默认密钥、默认关闭、无公开HTTP；MyBatis、模块依赖、ArchUnit及契约负例通过 |

上述新业务验收目前均NOT_EXECUTED。PR94的730项只证明其自身代码，不作为本候选核销完成已验收证据。

## 7. 待确认清单

- M94：是否授权将已六项CI通过的PR94合入develop；合并不是生产启用。
- K1：选择方案A，先支持真实主账号，批准操作人字段/存储及OrderVerifiedEvent.v2协议；员工授权后续补齐。
- K2：批准上述默认关闭的核销完成与最小AFS同事务失效范围、协议及验收计划；完整退款裁决/子账号/公开HTTP和小程序单独后续。

审批可分别答复。当前不执行任何一项未明确授权的合并、新契约实现或生产操作。
