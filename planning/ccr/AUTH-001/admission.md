# AUTH-001 商家工作台准入提案

**PROPOSED / PENDING_REVIEW · AUTH-001-draft-v1**。以下HTTP/字段/枚举均为提案，不从现有fixture推定已批准。产品来源：商家PRD§5.2/5.6/5.7/5.9/6.6、SSOT§15；签约事实仍受OD-W0-002约束。

## 1. 选择与授权分开

推荐不创建服务端“切换身份”写命令。C/M同一MINIAPP会话；客户端仅保存当前展示选择，每次进入/返回前台通过服务端准入查询确认。URL/query中的merchantId/storeId只是候选资源；服务端从已认证userId、merchant.owner_user_id及明确子账号绑定/授予解析关系，不能信任客户端staffId、角色或workspace。

建议如下新增查询；现HTTP工作台dashboard路径只提供业务看板，不复用其DTO充当身份授权：

| 路径 | 请求及约束 | 成功data |
|---|---|---|
| GET /api/v1/c/auth/merchant-memberships | MINIAPP Bearer；page/pageSize，固定merchantId、storeId按Long数值升序 | `{items:[Membership],page,pageSize,total}`；仅本人可选择范围；total也先过滤 |
| GET /api/v1/merchant/auth/admission | MINIAPP Bearer；merchantId、storeId必填String，禁止staffId/workspace参数 | Admission；每次进入重查，不能使用前端旧allowed作为缓存命中 |

Membership必填：merchantId、merchantName(1–128字符)、storeId、storeName(1–128)、membershipKind=OWNER/STAFF；staffId仅STAFF时服务端可返回用于显示历史操作主体，不能变成下次请求授权依据；OWNER不虚构一个staffId。无门店的入驻申请不放进门店列表，由CurrentSession.merchantEntry引导。主账号多门店与子账号多店只展示已有明确归属/授予；不是允许客户端创建跨商家关系。SQL现状仅merchant.owner_user_id唯一，子账号绑定持久化缺口单列，不能用电话匹配员工。

CurrentSession.merchantEntry提案：`{kind,applicationId?}`；kind=APPLY/APPLICATION_DRAFT/APPLICATION_PENDING/APPLICATION_REJECTED/SIGNING_REQUIRED/WORKSPACE_AVAILABLE。有任一当前可正常或受限进入的OWNER/STAFF关系时kind=WORKSPACE_AVAILABLE，不需要本人applicationId；点入仍选店重验，包含冻结/下线受限关系。否则按本人申请：无申请→APPLY且无ID；DRAFT→APPLICATION_DRAFT续填、REVIEWING→APPLICATION_PENDING、REJECTED→APPLICATION_REJECTED并必填本人applicationId；APPROVED而未满足正常资格SIGNING_REQUIRED并必填本人申请ID。若本人待审但已获他店STAFF身份，优先WORKSPACE_AVAILABLE，入驻进度仍由申请页面独立展示；不引用他人申请详情。当前Schema没有完整申请事实，依赖缺失/查询失败503，不能把null默认通过；真实Adapter未接入前不生成入口可用的公共Mock。

## 2. Admission字段和来源

| 字段 | 类型 / 空值规则 | 来源与含义 |
|---|---|---|
| merchantId / storeId | String，必填 | 已验证属于当前会话可访问关系的目标 |
| membershipKind | OWNER/STAFF | 真实主账号/子账号绑定；与服务人员岗位不同 |
| admission | ALLOWED/LIMITED/DENIED | 仅入口模式；不是对所有资源的授权。未决冻结写动作不转换为一个生产布尔值 |
| checkedAt | 带偏移毫秒时间，必填 | 本次权威事实查询完成时刻，不是可重复使用的通行证 |
| authzVersion | String 1–128，必填 | 身份/归属/授权变化的服务端版本标签；客户端只做失效识别 |
| facts.application | null或`{status}` | null=权威查询确认无申请；status=DRAFT/REVIEWING/APPROVED/REJECTED；新增英文wire码对应PRD四态 |
| facts.signing | `{status}` | status=NOT_SIGNED/SIGNING/SIGNED/FAILED/UNKNOWN；前四对应PRD，UNKNOWN是技术未知，不是新产品成功态 |
| facts.storeStatus | ACTIVE/FROZEN/OFFLINE | 门店权威状态；未知不映射ACTIVE |
| facts.merchantStatus | APPLYING/ACTIVE/OFFLINE/FROZEN/CANCELED | 商家权威状态，独立于门店；需按组合复核 |
| facts.staffEnabled | Boolean或null | STAFF必填Boolean，OWNER固定null表示不适用，null绝不表示已启用 |
| allowedActions | String[]，唯一排序 | 本次已确认范围内的动作码提示，见§3；空数组不证明正常业务权限；执行仍查资源资格 |
| reasonCodes | String[] | NONE不使用；允许时可空数组；拒绝/受限应给具体原因：NO_APPLICATION/APPLICATION_PENDING/APPLICATION_REJECTED/SIGNING_REQUIRED/SIGNING_FAILED/SIGNING_UNKNOWN/MERCHANT_OFFLINE/STORE_OFFLINE/MERCHANT_FROZEN/STORE_FROZEN/STAFF_DISABLED |
| nextSteps | Step[]，必填 | 每个Step仅`{type}`；type=APPLY/VIEW_APPLICATION/COMPLETE_SIGNING/CONTACT_SUPPORT/VIEW_EXISTING_ORDERS/VIEW_AFTERSALES/APPEAL；客户端将固定type映射白名单路由，不传任意重定向URL |

签约详情与外链单独由签约Owner定义：只有主账号获权时可见脱敏失败说明和经校验域名的短期签约入口；子账号不读取主账号结算/签约敏感详情，不直接透传Provider原包、证照或bank account。未冻结Provider前本包不新增签约启动/回调endpoint、provider enum或成功样例实际映射。

## 3. 准入矩阵与动作

判定先身份有效及归属，再子账号启用，再分别计算业务资格和例外，不能以商家正常新单资格提前返回整体禁入。无归属的资源403或防枚举404，查询不可用503且data=null；这是失败关闭，区别于成功查询返回DENIED/UNKNOWN状态。

| 已核事实 | 提案入口 | 已定允许范围 | 未定/拒绝范围 |
|---|---|---|---|
| 无申请、草稿、审核中、驳回 | DENIED | C端申请/进度引导，非商家正常工作台 | 正常经营动作不开放 |
| 已通过、未签/签约中/失败 | 无已证实例外则DENIED；例外非空则LIMITED | 主账号签约引导、子账号不敏感提示；已独立证实的存量履约/售后关系进入受限资源入口 | 正常经营不开放；不以缺签约事实抹掉已定责任 |
| 签约UNKNOWN | 无已证实例外则DENIED；例外非空则LIMITED | 可独立验证的既有存量例外由资源策略处理并返回相应allowedActions/nextSteps | 不默认成功，也不把未知当签约失败；事实查询整体失败503不伪造例外 |
| 已通过已签、商家门店ACTIVE且成员有效 | ALLOWED | 当前获授动作与范围 | 非本人店、未授动作、业务资格不符仍拒绝 |
| merchant/store OFFLINE且成员有效 | LIMITED | 历史读取、存量履约、退款/售后、处罚查看及已有申诉；每笔由订单/售后Owner核存量关系 | 新预约/新经营流量不得开放；不能代商家接单规则倒置到运营 |
| merchant/store FROZEN且成员有效 | LIMITED | 历史订单、待处理售后读取、处罚原因及申诉 | 冻结核销、退款处理、售后补证等写动作是B-FROZEN-WRITE；不得将SPEC_GAP当已批准的一律拒绝或允许 |
| 子账号停用/归属撤销 | DENIED | C端个人身份可仍有效；历史审计保留 | 子账号不能继续以历史staffId访问；存量责任由仍获权主账号/运营依法定产品动作处理，不给已撤账号保留写权 |
| 状态组合互相矛盾/来源不完整 | 不返回正常允许 | 明确错误/待核来源，已独立证实例外按资源契约 | 不按枚举优先级猜SIGNED或ACTIVE；具体矛盾BLOCKER |

建议工作台提示动作码（均拟议，非授权清单已批准）：`merchant.order.read`、`merchant.order.fulfill`、`merchant.refund.handle`、`merchant.aftersale.read`、`merchant.aftersale.respond`、`merchant.penalty.read`、`merchant.penalty.appeal`、`merchant.schedule.manage`、`merchant.service.manage`、`merchant.staff.manage`。`fulfill/handle/respond`仍是入口提示组，不直接作为每个命令的最终权限码；逐命令合同映射由业务Owner收窄，避免一枚入口权限授予全部行为。冻结已定读取与申诉可先规范，未定写入不生成可执行公共Mock。

商家主账号、门店店长、核销员、排期负责人来自PRD§5.2/5.6；服务人员记录不自动成为登录子账号。建议主账号管理所属店授权，店长仅获授门店管理；核销员仅获授订单核销与必要订单读取；排期负责人仅获授排期/人员时间配置，均不能看改主账号结算签约。精细授权初始化、子账号邀请/账号绑定需后续与MER-001逐字段交接，不能因这些角色名称直接放行业务API。

## 4. 切换、深链与在途

进入商家页面、重新show、用户/门店/账号变化时先失效旧epoch并清商家数据，展示checking。只在本次成功准入返回且epoch仍当前时显示内容。DENIED/503均不显示旧业务缓存；LIMITED只呈已确认入口。返回C端不登出，清商家敏感缓存；其他身份范围不作为缓存共享键。

缓存键至少包含audience/sessionId/userId/workspace/merchantId/storeId/本地epoch，业务查询再加资源/过滤；角色/范围/authzVersion变化失效该范围。scope标签由客户端用于隔离，不作服务端许可。请求发出时捕获epoch，成功和错误回包均比较，旧401/403不能清掉新的不同会话。页面卸载的旧清理回调也不得撤销新页面准入。

深链仅存白名单路由和String ID。未认证先登录，已认证仍调用准入和资源检查；不根据深链中staffId/workspace设权。重新授权后恢复原页仅在当前关系和资源仍有权时进行，不能恢复旧业务草稿中的秘密、MFA码或自动执行原写动作。

## 5. 内部边界与门禁

建议新增本地API语义（方法及DTO名均提案）：UserSessionQueryApi.resolvePrincipal、MerchantMembershipQueryApi.listForUser、MerchantAdmissionQueryApi.check；输出可信身份/关系/资格事实，调用方不读他域Repository。现MerchantQueryApi.checkOrderEligibility保持新单用途，不能替代这些方法。

MER负责申请、签约、门店与成员归属事实；AUTH负责会话和授权组合，订单/售后Owner负责资源存量/资格。主账号代表操作的operatorType/ID如何映射到MERCHANT_STAFF及核销operator_staff_id必填，当前Schema并无登录主账号员工身份事实，需明确主账号绑定的可操作员工主体或经审查的映射后实现；不能伪造staffId或把userId直接填staff列。本包登记此门禁，不代MER写Schema。

OD002未解除不完成签约集成；B-FROZEN-WRITE未核定不完成冻结写动作；成员绑定/主账号操作者、Schema/Internal变更未同步不完成真实商家鉴权。会话隔离、查询失败、已定存量规则可先独立审查，不能整包报RESOLVED。
