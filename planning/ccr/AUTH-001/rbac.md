# AUTH-001 单运营RBAC提案

**PROPOSAL_ACCEPTED / PENDING_CONTRACT_SYNC · AUTH-001-draft-v2**。产品规则已由SSOT§24及22号补充批准；本文件只提出稳定标识、动作映射、数据范围和执行协议。D1/D2技术方案已接受；新权限码/接口待权威Contract同步，具体Schema/持久化实现仍需后续审查。V1取消MFA已按SSOT§25及[24号补充](../../../docs/01-prd/24-取消MFA人工裁决补充-v1.0.md)批准，覆盖旧第二因素门禁；D1其余及D2已接受，尚未同步权威Contract。

批准回执（2026-09-14）：用户明确批准“D1其他参数和D2”，对象为取消MFA后的AUTH-001-draft-v2，head `2963e3918bbd62afdfdeb55c60bb2e2221c67ca1`，tree `9761eb9f1c5da0fde70ab6be466ad3f63eb6ce09`。仅同步接受状态，不改变技术参数、正文样例或枚举；MFA继续取消。OD002、冻结未明确写动作、真实主认证Provider及Schema/实现门禁保留，PR14合并未授权；两CCR不RESOLVED、Issue不DONE。

## 1. 角色和目录

| 稳定roleCode提案 | 已定名称 | 默认含义 |
|---|---|---|
| CONTENT_EDITOR | 运营编辑 | 平台内容获权编辑及直发；不因编辑有退款权 |
| REVIEWER | 审核员 | 已获权商家/用户内容业务审核、售后处理；无第二审核人 |
| OPERATIONS_ADMIN | 运营管理员 | 获权治理、配置、异常处置；不是超管别名 |
| FINANCE_READER | 财务只读 | 默认查看已批准财务事实；已有执行动作可显式授予 |
| AUDIT_READER | 审计只读 | 审计查询及获准导出，不存在日志修改/删除能力 |
| PLATFORM_SUPER_ADMIN | 平台超级管理员 | 默认全部已批准V1动作/全平台范围，仍核业务资格/脱敏/审计 |

角色code不可变，展示名可变；权限只取服务端已登记actionCode及真实授予。roleId为String主键，roleCode不能被普通角色编辑成超管；超级管理员身份由受控授予关系决定，不由名称或请求中的superuser=true决定。角色不可删除，仅停用。六角色是模板，不要求六人；不设计第二审批者、内部审批队列或本人复核隔离。

目录条目提案：`{actionCode,label,resourceType,riskLevel,requiresPurpose,requiresConfirmation,requiresSensitivePolicy,delegable,availability}`。actionCode小写ASCII字母/数字/点/连字符，1–100字符；label1–100字符、resourceType1–64稳定ASCII标识；riskLevel=READ/NORMAL/HIGH；requires*及delegable为Boolean；availability=PROPOSED_ONLY/CONTRACT_READY。delegable默认false，只有Contract Owner在已批准业务动作清单显式标true才可普通转授，管理/超管类固定false。本包所有新增码均PROPOSED_ONLY，只有对应业务Contract获批并同步后可注册到生产可授予目录。后台不接受客户端上传新权限码；未知码拒绝400，未知实际业务动作默认403。不能把未决资金码注册后靠按钮隐藏。

## 2. 原27行矩阵逐行映射

来源：运营PRD§4.2 word/document.xml body[49]。下列为**建议默认模板**；R=仅查看，D=该行已批准执行动作及必要查看，—=默认不授予。列序编/审/管/财/稽；超管单独按已批准目录闭包。▲→获权单人执行；○不推出导出/明文；●没有对应写业务时不创造写动作。权限码皆拟议，组内具体业务DTO仍归原Issue。

| # / 原行 | 建议动作码（同前缀按逗号展开） | 编/审/管/财/稽 | 边界 |
|---|---|---|---|
| 1 工作台看板 | dashboard.read | R/R/R/R/R | 管●也不产生看板写权限 |
| 2 商家入驻审核 | merchant.application.read, merchant.application.decide | —/D/D/R/R | 通过/驳回/补正按既有规则，签约不是审核自动成功 |
| 3 商家台账详情 | merchant.read | R/R/R/R/R | 详情●不包所有治理 |
| 4 商家冻结/下线/解冻 | merchant.penalty.read, merchant.freeze, merchant.disable, merchant.unfreeze | —/—/D/—/R | 经营治理不是资金冻结 |
| 5 服务审核/强制下架 | service.read, service.review, service.force-offline | R/D/D/—/R | 审核与下架分别校验 |
| 6 第三方渠道授权配置 | channel-authorization.read, channel-authorization.configure, channel-service-mapping.read, channel-service-mapping.configure | —/—/D/—/R | 凭证不得明文回传/记普通日志 |
| 7 订单总览详情 | order.read, order.status-log.read, order.verification-log.read, order.abnormal-close | R/R/D/R/R | 管的异常关闭来自HTTP§5.5，禁止正常代接单 |
| 8 退款处理重推 | refund.read, refund.channel-query, refund.retry | —/—/D/R/R | 财执行须显式授予；仅原退款重试，不手工改成功 |
| 9 账本补偿队列 | benefit-compensation.read, benefit-compensation.retry | —/—/D/R/R | 仅既有券返还/积分扣回补偿，retry等原Owner合同就绪 |
| 10 资金流水/分账/结算 | payment-ledger.read | —/—/R/R/R | 仅已定支付退款事实；监管冻结/分账/结算相关查询执行均OD001 |
| 11 对账差异 | payment-reconciliation.read, payment-reconciliation.channel-query | —/—/R/R/R | 人工修复无完整合同不注册；分账/结算/提现差异仍OD001 |
| 12 提现审核打款 | 无活动动作码 | —/—/—/待决/待决 | 整行OD001；不能由财●/超管解锁 |
| 13 券模板/活动 | coupon-template.read, coupon-template.manage, coupon-campaign.read, coupon-campaign.manage, coupon-campaign.start, coupon-campaign.stop | R/—/D/R/R | 状态与规则依业务Owner |
| 14 券实例作废返还 | coupon-instance.read, coupon-instance.void, coupon-instance.return | —/—/D/R/R | 不得违反全/部分退款返券规则 |
| 15 积分任务配置 | points-rule.read, points-rule.configure, points-task.read, points-task.configure | R/—/D/R/R | 无消费/任意改余额/历史流水动作 |
| 16 用户宠物档案 | customer.read, pet.read | R/R/R/R/R | 管●不凭空产生改档案权；原备注标签需原Owner独立映射 |
| 17 封禁/注销审核/申诉 | customer-account.read, customer-account.ban, customer-account.unban, account-closure.review, account-appeal.decide | —/D/D/—/R | 注销责任校验保留 |
| 18 售后投诉裁决 | aftersale.read, aftersale.assign, aftersale.handle, aftersale.decide, complaint.read, complaint.assign, complaint.handle, complaint.decide | —/D/D/R/R | 部分退款仅售后裁决；首次正式售后结论终局，无售后复审 |
| 19 评价/申诉 | review.read, review.review, review.offline, review-appeal.read, review-appeal.decide | R/D/D/—/R | 评价申诉不是售后复审 |
| 20 内容/举报 | community-content.read, community-content.review, community-content.offline, community-content.delete, report.read, report.handle | R/D/D/—/R | 业务审核单人，不能代改用户正文 |
| 21 百科/品种 | encyclopedia.read, encyclopedia.manage, encyclopedia.publish, breed.read, breed.manage | D/D/D/—/R | 获权编辑可直发 |
| 22 基础台账/字典 | dictionary.read, dictionary.manage, banner.read, banner.manage, banner.publish | D/—/D/—/R | 不更改历史快照 |
| 23 系统参数 | system-parameter.read, system-parameter.configure | —/—/D/—/R | 只限已批准可配置参数，不改封板硬规则 |
| 24 消息公告 | announcement.read, announcement.manage, announcement.publish, notification-template.read, notification-template.configure | D/—/D/—/R | 无内部审批，不创建私信IM |
| 25 统计导出 | statistics.read；每资源独立*.export候选 | R/R/D/R/R | `*.export`是文档占位表示，不是可授通配码；须逐个登记如order.export |
| 26 账号角色权限 | operator-account.read, operator-account.create, operator-account.update, operator-account.disable, operator-account.reset-password, role.read, role.configure, role.disable, grant.read, grant.configure | —/—/—/—/R | 原管●(超管)归超管；普通账号可显式获已有管理动作，但受§3防提权边界 |
| 27 审计查看 | audit-log.read, audit-log.export | —/—/R/R/D | 稽导出也需实际获权/用途/范围，不含改删 |

D不是无限“execute”。例如财务对refund.retry/benefit-compensation.retry可显式获权，但必须合同就绪且保留原交易资格；名称“只读”不得硬编码阻止已授权动作。导出、手机号敏感查找、证据/明文查看按独立码及条件再授予，不因行有R自动可用。

禁止登记：正常代商家接单、代用户支付、改渠道事实金额/手工标记到账或退款成功、代商家提现、手改订单完成/历史快照、代用户编辑社区正文、改用户评分、篡改审计、恢复失效旧售后、售后复审、积分消费、新内部审批。OD001待定行和未知动作对超管同样不开放。

## 3. 账号、角色与范围协议

推荐账号绑定多个角色并可有账号级extraActionCodes。有效动作=启用角色actionCodes并集∪该账号显式extraActionCodes，再与已批准CONTRACT_READY目录求交；账号禁用时无动作。extraActionCodes与角色授予分开持久化、审计与原子递增版本；移除某extraAction若仍由有效角色授予则仍有权，返回计算后真实权限，不能假称撤净。无deny优先级规则，不暗中实现覆盖禁用。这样可只给某个财务账号refund.retry，不改全体FINANCE_READER模板；角色停用不自动删除独立账号grant，界面需显示其来源。账号只有一个dataScope，应用于每个有效动作，避免跨角色scope交叉组合。按角色不同范围是未来变化，本版不支持。超管有效授予强制ALL，但仍有敏感规则。

Scope提案：`{mode,cityCodes,merchantIds}`。授予请求mode=ALL/CITY/MERCHANT；ALL两数组必须为空；CITY只接受非空cityCodes(唯一、最多100，来自服务端有效城市字典)，merchantIds为空；MERCHANT只接受非空merchantIds(唯一、最多1000且真实存在)，cityCodes为空。拒绝空范围隐式ALL；无有效授予的响应用mode=NONE且两数组为空；NONE不能用于授予请求。此wire枚举是提案。

资源所属城市/商家从资源Owner API及稳定归属事实取得；不是请求筛选器，不用用户住址/IP代替。跨城市历史订单按订单下单时所属门店/城市快照过滤；当前门店/商家按当前归属过滤；缺历史城市快照不能从当前地址猜，相关历史城市范围接口保留Schema/Owner门禁。跨域关联只取最小归属，不跨Repository。

列表/计数/看板/导出先做权限过滤再分页/total，不能查全平台后前端过滤。请求筛选条件与scope取交集，不扩大授权。单资源在范围外统一404 COMMON_NOT_FOUND避免枚举；没有该动作权403。全平台资源（系统参数、全局字典、账号角色管理）需要ALL范围；CITY/MERCHANT不能通过资源空merchantId绕过。这是本版推荐保守分配方案，可由Contract Owner审阅，不按角色名称自动放行。

以下全是**新增路径提案**，统一ADMIN_WEB Bearer、写X-Request-Id，管理接口核当前动作/范围/账号状态，用途/原因/同人确认按动作元数据；不追加认证因素。

| 路径 | 请求 | 成功data / 权限 |
|---|---|---|
| GET /api/v1/admin/auth/permissions | 无body | PermissionSnapshot；所有有效Web账号只查自己 |
| GET /api/v1/admin/permission-actions | page/pageSize、resourceType? | Page<ActionDefinition>；grant.read；只返回CONTRACT_READY目录，提案环境可显式查看PROPOSED_ONLY但禁止写入生产 |
| GET /api/v1/admin/operator-accounts | page/pageSize、status?、roleId? | Page<OperatorAccount>；operator-account.read、ALL；不返回密码或认证秘密 |
| POST /api/v1/admin/operator-accounts | CreateAccount | 201 OperatorAccount；operator-account.create |
| PUT /api/v1/admin/operator-accounts/{operatorId} | `{displayName,expectedVersion,reason}` | 200 OperatorAccount；operator-account.update |
| PUT /api/v1/admin/operator-accounts/{operatorId}/authorization | `{roleIds,extraActionCodes,dataScope,expectedVersion,reason,confirmed}` | 200 OperatorAccount；grant.configure；角色/额外动作/scope整体原子替换并递增authzVersion |
| POST /api/v1/admin/operator-accounts/{operatorId}/disable | `{expectedVersion,reason,confirmed}` | 200 OperatorAccount；operator-account.disable，撤销会话 |
| POST /api/v1/admin/operator-accounts/{operatorId}/enable | 同上 | 200 OperatorAccount；提案新增operator-account.enable，纳入账号管理行，启用不恢复旧token |
| POST /api/v1/admin/operator-accounts/{operatorId}/password-reset | `{newPassword,expectedVersion,reason,confirmed}` | 200 `{operatorId,updated:true}`；operator-account.reset-password，撤销旧会话；密码不回显 |
| GET /api/v1/admin/roles | page/pageSize、status? | Page<Role>；role.read |
| PUT /api/v1/admin/roles/{roleId} | `{displayName,actionCodes,expectedVersion,reason,confirmed}` | 200 Role；role.configure；只允许既有已批准动作；roleCode不能修改 |
| POST /api/v1/admin/roles/{roleId}/disable | `{expectedVersion,reason,confirmed}` | 200 Role；role.disable；所有相关账号授权版本更新 |
| POST /api/v1/admin/roles/{roleId}/enable | 同上 | 200 Role；提案新增role.enable；不恢复旧会话令牌 |

本版固定六角色，无任意新角色创建/删除endpoint；可配置动作和名称、启停。原PRD新建账号由超管单人执行，没有secondApprover。超级角色默认全批准目录不可编辑缩成普通集合；停用/移除须遵循恢复安全规则。

字段定义：

- CreateAccount=`{account,displayName,initialPassword,roleIds,extraActionCodes,dataScope,reason,confirmed}`。account登记一种别名，1–128字符，手机号/邮箱/工号校验；displayName1–64；initialPassword8–64且至少大小写/数字/特殊字符中三类（PRD）；roleIds非空唯一String数组最多6；extraActionCodes必填唯一排序数组可空/最多500，码约束同目录；reason1–500字符、不可全空白；confirmed必须true仅同人确认，不证明动作获权。账号启用及密码初始化按当前权限和状态策略执行，不配置额外认证因素。
- OperatorAccount=`{operatorId,accountMasked,displayName,roleIds,extraActionCodes,dataScope,status,lastLoginAt,version}`；status=ENABLED/DISABLED，lastLoginAt可null，version=非负十进制String；登录别名在普通列表脱敏，受控详情展示仍需用途和审计。roleCode仅Role中返回。
- Role=`{roleId,roleCode,displayName,status,actionCodes,version}`；status=ENABLED/DISABLED；actionCodes唯一排序。固定模板初始化映射由本节/§2给出建议，不允许客户端导入通配码。
- PermissionSnapshot=`{operatorId,authzVersion,checkedAt,roles:[{roleId,roleCode,displayName}],dataScope,actionCodes}`；只返回当前有效授予；没有业务资源资格，不能代替订单actions。
- expectedVersion非负十进制String；状态变更CAS不匹配409 COMMON_CONFLICT，当前key同参重放仍先核当前权限；不能用版本“最新”替代requestId。

本版账号管理是ALL资源，只有ALL范围管理者能调用；普通获权管理者仍只能授自己当前拥有且delegable=true的非管理动作及自身scope子集。创建/授权/启用/角色启用/密码重置等所有管理路径均核目标最终有效动作/范围，普通管理者不能接管、重置或启停超管/含非转授管理权的账号，也不能修改自身或反向提升自身权限。普通grant.configure可设置目标extraActionCodes；角色增删只有其整个有效动作集合均满足可转授子集才允许，不能借FINANCE角色名绕过动作与数据范围检查。角色模板修改、管理类动作授予及超管授予仅有效超管可操作，普通账号不得编辑全局角色actionCodes。所有路径同用防提权策略，不能借新建/启用绕过授权接口。超管可单人管理自身权限，但不得停用/移除最后一个可恢复的启用超管；这是防锁死技术建议，不要求第二账号。账号初始化/密码恢复在部署手册后续设计，不增加额外因素恢复或第二人要求。

## 4. 敏感信息、导出与审计

| 数据 | 原规则与提案检查 |
|---|---|
| 用户手机 | 默认掩码；另授customer.phone.reveal，限定售后/风控/司法协查用途及关联工单，逐次审计；不存在工单/用途不符拒绝 |
| 昵称/头像、真实姓名/地址联系人 | 昵称头像常规可展示但批量导出脱敏；真实姓名、收货人、地址手机按原PRD§6.6掩码及用途控制；禁止导出地址明文用于营销，不因export或超管绕过 |
| 证照/身份证 | 默认掩码水印；另授merchant.identity.reveal，按真实审核/签约核验场景最小访问；单人用途确认代替旧内部审批，不把原图URL常驻缓存 |
| 银行账号/法人/子商户 | 原字段场景约束及掩码；银行卡明文不展示；OD001事实未冻结不造结算信息 |
| 宠物健康/体重/疫苗 | 运营默认不可见；需用户授权且关联在途订单，仅最小必要字段，不能单凭pet.read或超管显示全部 |
| 第三方完整券码/授权凭证 | 任何角色不可查看完整券码，页面/导出仅脱敏；不记普通日志/埋点 |
| 支付渠道交易号 | 仅交易/对账动作获权者可明文复制，导出水印；不能顺带导出渠道凭证或未知资金状态 |
| 导出 | 默认脱敏；单独每资源export权限+当前scope+用途，范围≤31天，水印实际操作人/时间；默认24小时短期链接（原PRD）且下载再次核当前权限；单独reveal不自动改变导出脱敏策略 |

以上reveal/export码均是独立候选，不由基础read默认派生；API路径及下载授权详细Contract由相关数据Owner制定，AUTH只定义授权检查输入：可信主体、actionCode、resourceRef、purpose、caseId、用户授权/在途事实引用。客户端purpose/caseId只提供待核声明，不能自证敏感资格。

审计记录建议：auditId、operatorType/operatorId、actionCode、resourceType/resourceId、requestId、traceId、occurredAt、sourceIp、reason/purpose、脱敏before/after、result=ALLOWED/DENIED/FAILED、authzVersion、checkedAt。密钥/密码/OTP/token/完整券码/完整银行卡不进日志；拒绝也记录实际主体，匿名失败使用受限尝试引用而非伪造用户。原PRD至少3年留痕与不可删改保持，本包不重订法律期限。

高风险业务变更与必要审计意图须原模块可靠提交，不能权限通过后审计静默丢失。登录审计写失败按原PRD异步降级并告警，不影响已经真实验证的登录；两类故障语义分别验证。记录单个实际操作者，不设置secondApprover/reviewer必填。

## 5. 最终授权检查、并发撤权和回执

推荐新增内部AuthorizationQueryApi.check输入语义：可信Principal引用、稳定actionCode、资源Owner提供的ResourceScope、当前用途及阶段=EXECUTE/READ_RESULT；输出AuthDecision={allowed,checkedAt,versions,reasonCode}。这是本地*-api DTO提案，不扩五字段CommandContext、不把HTTP自报Principal作为参数可信来源。versions覆盖会话generation/账号状态/角色/授予/账号scope/员工归属；展示名称版本变化不授新动作。

执行过程：入口初检→按23号Admission绑定→获取本模块幂等/业务锁→**首个业务修改之前最终授权检查**→短本地事务业务与回执提交。最终检查通过身份/权限Owner强一致当前读，不继承调用者MySQL RR旧快照、不信任可过期允许缓存；跨模块不持Repository锁、不假设全局事务。依赖故障503且无业务修改。

一致性提案：每个Owner在自己的短只读事务内原子读取完整事实与单调版本；权限Owner把角色/账号grant/scope作为同一授权快照，不分别返回旧动作和新范围。跨Owner先收集全部快照，再按同样顺序强一致复核每个版本；全部相同才可使用，版本变化最多重取3次/总2秒，仍不稳定503。版本不得回退/重置/ABA，成员换店、停用、角色启停、scope/extra grant变化必须涵盖。相同两轮版本意味着存在两轮间一致观察区间，最终检查的逻辑时点在该区间；不声称多个Owner数据库事务同时提交。相关Owner未提供原子快照/版本契约则D2实现BLOCKED，不降级各自缓存拼接。副作用前若另有业务锁等待或重试仍重查。

承诺边界：撤权提交先于最终检查的权威读，则该检查拒绝；最终检查先通过、撤权后提交，则该次已经允许的短事务可以完成。不能宣称撤权一定先于后续业务commit生效。审计记录检查时刻及采用的版本。检查后发生长等待、业务锁失去、事务回滚重试、重新执行、异步代表用户发起新动作时必须重新检查，不复用旧allow；建议check完成至首写预算2秒，超限重新检查。该预算不制造跨模块锁或严格线性化保证。

已受理退款/可靠任务继续按原业务承诺执行，不能因原操作员撤权取消渠道退款；它们以既有SYSTEM逻辑身份和任务契约执行，不冒充已撤用户的新指令。UI在撤权后丢弃旧epoch结果并查询资源最新事实，不能承诺服务器已提交的动作被浏览器丢包撤销。

成功幂等重放、导出下载和敏感数据发送前独立READ_RESULT检查当前身份/动作/结果资源范围/用途及脱敏；401/403/防枚举404不返旧data，查询失败503。成功重放不再检查已消耗的一次性执行业务资格；历史actions剔除或标作历史，不可当当前授权。返回前检查同样是一次明确检查时点，网络传输期间撤权不能追回已发送字节，不承诺零时间窗口。

普通403只撤相关授权上下文；身份撤销401清该端会话。Web与小程序彼此不清令牌。权限读失败不能降级使用缓存旧allow。需要“撤权提交后任何在途均禁止commit”的更强保证时另列重大契约/一致性评审，不偷偷跨biz锁表。

## 6. 实现前影响

pet-admin承担运营账号/角色/授予/scope/授权版本、Web会话/认证attempt/账号密码凭据及运营审计逻辑Owner，使Web账号generation与签发可在本Owner原子处理；pet-user仅承担小程序用户/会话/认证attempt/凭据。两Owner使用公共纯接口而不共享持久化Repository。商家成员来源通过merchant-api交接，不读merchant表。Schema06缺这些持久化定义，需后续DDL/索引/CAS/恢复迁移审查，尤其authzVersion按授权事实原子更新、禁用旧session不可复活。同步权威07/10/11/12及必要Schema前本包不成为生产许可；此阶段不实现Auth Filter、不改boot、不引入认证依赖。
