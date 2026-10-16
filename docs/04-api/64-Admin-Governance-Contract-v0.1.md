# 运营治理查询 HTTP 契约 v0.1（商家台账 / 排期查看 / 员工治理）

2026-10-15；随 ADM-001 治理补齐 / A-002 第二期切片实施。来源：运营 PRD 6.2.2（商家台账与详情）、6.2.5（门店排期监管）、6.2.6（员工监管）、SSOT §24（单运营 RBAC）、§29（"运营只读监管延后至占用数据齐备后交付，不取消"——占用事实已由 SCH-004/53 号交付）、§30（员工基础管理门槛：运营侧无写动作授权）、AUTH-001 RBAC 提案矩阵第 3 行与 63 号先例（ADM-002/A-003 平台交易查询面）。本契约只把三块运营治理**只读查询面**装配为默认关闭 HTTP 切片；不新增产品规则，不新增写动作，不改变任何域内状态机。

## 范围与归属

- MERCHANT 拥有商家台账与员工治理读侧（merchant/merchant_store/merchant_application/merchant_agreement_*、merchant_staff、merchant_member 三表与 54 号邀请表均为 merchant 域所有权表，SQL 全部在本模块新增 `AdminMerchantGovernanceMapper.xml`）；SCHEDULE 拥有排期查看读侧（schedule_availability_window/staff_availability_window/schedule_reservation/schedule_reservation_claim，SQL 在本模块新增 `ScheduleAdminViewMapper.xml`）。两模块只读各自所有权表，无 JdbcTemplate/注解 SQL/内联 SQL，过 check-persistence-style.py；boot 装配页只做身份解析、参数校验、严格查询串、错误映射与组装，不做业务判断。
- 包括：商家台账列表/详情、排期时段分布/员工排班/预约占用记录、员工档案/成员绑定/邀请记录八条 GET 路由与 A-002 三块页面（商家治理、排期查看、员工治理）。
- 复用与缺口：商家入驻审核面（59 号 PR / `merchant.application.*`）与服务审核面（ADM-001 服务写入方 / `service.review.*`）已交付，本契约不重做；排期**写侧**是商家 53 号自用面（SCH-004），运营面只读、绝不新写排期写入；29 号裁决（排期人员容量、保护口径）是商家侧维护口径，运营面只呈现事实。
- 不包括（保持草案，本切片不实现、不注册新写动作）：
  - HTTP10 §5.6 商家下线/冻结/解冻（`merchant.freeze`/`merchant.disable`/`merchant.unfreeze`，AUTH-001 矩阵第 4 行 D 类）——处置动作属独立写内核切片，本切片不碰。**【2026-10-16 注记】`merchant.disable` 已由 33 号裁决（2026-10-15）授权、63 号 v0.2 交付（路由 `POST /api/v1/admin/merchants/{merchantId}/disable`，动作码 `merchant.disable`，装配开关 `pet.admin.trade.http.enabled`）；36 号裁决（2026-10-16 治理写动作最小授权）授权的是其治理页入口：A-002 商家治理详情页增"停用"动作区（带同一人二次确认），调用上述既有 63 号路由——本契约仍零写路由、零新动作码，入口的服务端权威与审计全在 63 号 v0.2；`merchant.freeze`/`merchant.unfreeze` 维持不做；
  - 运营对员工的任何写处置——SSOT §30/运营 PRD 6.2.6 只授权**只读**监管（授权状态、归属、授权与回收记录查看）；成员停用/恢复（`disableMember`/`enableMember`）是 54 号商家 OWNER 自用面，运营不代行；SSOT 未授权的运营处置动作不发明，如需开放走 CCR 登记；
  - `merchant_member_audit` 明细查询（授权与回收的逐条审计流属 `audit-log.read` 审计面，未建，本切片以成员/邀请当前状态投影承担纠纷溯源）；
  - 商家台账 PRD 字段表中的评分/月售单量/拒单率/退款率/在途订单数（评价/订单域跨模块聚合，不在 merchant 所有权事实内，不虚构；随各自 Owner 切片另行披露）、处罚次数（无 merchant 域处罚历史表）、结算资金字段（OD-W0-001 未决）；
  - 导出（CSV/Excel）不在 V1 已批准范围。

## 路由与开关

`pet.admin.governance.http.enabled`，默认 false；开启前置 `pet.auth.admin.enabled=true`（装配在 AdminAuthSecurityConfiguration 的 ADMIN_WEB Bearer 链上，缺一即路由不装配）。全部路由 OPS/ADMIN_WEB、真实 Bearer 会话、`Cache-Control: no-store`、信封 `{code,message,data,traceId}`（SUCCESS/"ok"）。GET 不绑 X-Request-Id。动作码采用 AUTH-001 RBAC 词法（本切片随部署登记 `DEPLOYED_ACTIONS`；`merchant.read` = 提案矩阵第 3 行既有拟议码，`schedule.read`/`merchant.staff.read` = 6.2.5/6.2.6 无矩阵行缺口的本切片拟议码，语义=运营 PRD 只读监管，均已披露）：

| 方法/路径 | 动作码 | 说明 |
|---|---|---|
| GET `/api/v1/admin/governance/merchants` | `merchant.read` | 商家台账列表（四层状态+资质/签约事实） |
| GET `/api/v1/admin/governance/merchants/{merchantId}` | `merchant.read` | 商家台账详情（档案+门店+申请+签约） |
| GET `/api/v1/admin/governance/schedule/windows` | `schedule.read` | 服务时段分布（含占用/利用率事实） |
| GET `/api/v1/admin/governance/schedule/staff-windows` | `schedule.read` | 员工排班时段（AVAILABLE/CLOSED） |
| GET `/api/v1/admin/governance/schedule/reservations` | `schedule.read` | 预约占用/临时锁定记录（含超时释放） |
| GET `/api/v1/admin/governance/merchants/{merchantId}/staff` | `merchant.staff.read` | 员工档案列表（35 号词汇，只读） |
| GET `/api/v1/admin/governance/merchants/{merchantId}/staff-members` | `merchant.staff.read` | 成员绑定列表（52/54 号词汇，只读） |
| GET `/api/v1/admin/governance/merchants/{merchantId}/staff-members/invitations` | `merchant.staff.read` | 邀请记录列表（54 号状态机，只读） |

## 鉴权、数据范围与脱敏

- 三层鉴权不变：AdminBearerAuthenticationFilter 解析会话 → 控制器按 AdminSessionView 快照校验动作码与 ADMIN_WEB 受众 → 模块内核经注入的 `AdminAuthorizationQueryApi` 每次调用重验（列表 `checkCollection`、单资源 `check`+`AdminResourceScope`，READ_RESULT 相位）。控制面板动作码不是授权证明。
- 数据范围 v0.1：governance 面要求 `dataScope.mode == ALL`。商家/员工/排期行内无统一权威城市归属事实（merchant_profile_compat.city_code 仅覆盖已建档商家、排期与员工行无城市列），按 CCR-PERM-001 §3 "缺权威归属事实不能从请求过滤器猜" 全部 fail-closed：CITY/MERCHANT 会话对本面统一 403（控制器与内核双侧拒绝）。CITY/MERCHANT 范围支持待归属事实切片（PR 披露）。
- 脱敏（SSOT §24/22 号补充）：不回传任何手机号明文——员工/成员/邀请手机号一律 `1xx****xxxx` 掩码（与 35/54 号既有掩码同形）；门店电话、负责人姓名、证件号、协议内容、申请理由与凭证密文一律不出域；商家 `provider_merchant_no` 仅回传后 4 位（`****` + 后 4 位，PRD 6.2.2 子商户号脱敏展示）；用户以 `ownerUserId`/`userId`（String）呈现。排期行不携带用户身份（orderId 关联 63 号 trade 面查询）。
- 审计：登录/拒绝审计沿 admin auth 域既有实现（recordRejected）；本切片纯只读、无敏感明文读取动作，不新增写审计面（与 63 号同口径）。

## 查询参数与固定语义

- 分页：`page` 1..10000 默认 1；`pageSize` 1..100 默认 20；未知查询参数一律 400（与 63 号同纪律）。PageResult 信封 `{items,page,pageSize,total}`；时间一律 UTC 毫秒 `YYYY-MM-DDTHH:mm:ss.SSSZ` 字符串；ID 一律 Snowflake String；无金额字段。
- 商家台账列表过滤器：`status`（merchant.status 词汇 APPLYING|ACTIVE|OFFLINE|FROZEN|CANCELED）、`storeStatus`（ACTIVE|OFFLINE|FROZEN，按名下任一门店匹配）、`cityCode`（精确）、`merchantTypeCode`（精确，29 号词汇）、`keyword`（2..64 字符，按 merchant_name/名下 store_name LIKE）、`createdFrom`/`createdTo`（UTC，成对，跨度 ≤366 天）。固定排序 `created_at DESC, id DESC`。行字段：merchantId/merchantName/status/merchantTypeCode/cityCode/storeCount/activeStoreCount/applicationStatus/signingStatus/providerMerchantNoMasked/createdAt。
- 商家台账详情：列表行全字段 + 门店列表（storeId/storeName/address/status，无电话无坐标）+ 申请事实（applicationId/applicationNo/status/submittedAt/reviewedAt，DRAFT 商家申请事实为空对象缺省）+ 签约事实（signingStatus SIGNED|NOT_SIGNED/agreementVersion/acceptedAt——以当前已发布协议版本的 `merchant_agreement_acceptance` 行为准，沿 28 号语义）。未知/越范围 merchantId 与 403 同形 404 防枚举。
- 排期时段分布过滤器：`merchantId`/`storeId`/`serviceId`（可选精确）、`windowKind`（GENERAL|PICKUP|RETURN）、`status`（OPEN|CLOSED|SOLD_OUT）、`startFrom`/`startTo`（成对，按窗口 start_at，跨度 ≤92 天）。固定排序 `start_at DESC, id DESC`。行字段：windowId/merchantId/storeId/serviceId/windowKind/startAt/endAt/configuredCapacity/status/version/updatedAt/confirmedCount/tempLockedCount——占用口径沿 53 号 SOLD_OUT 同一事实：`schedule_reservation_claim` 原窗 claim 关联 TEMP_LOCKED/CONFIRMED 预约计数（tempLockedCount 只计 lock_expire_at 未过期行）；利用率由页面以 confirmedCount/configuredCapacity 呈现，服务端不派生百分比。
- 员工排班过滤器：`storeId`/`staffId`（可选精确）、`status`（AVAILABLE|CLOSED）、`startFrom`/`startTo`（成对，≤92 天）。排序 `start_at DESC, id DESC`。行字段：windowId/storeId/staffId/startAt/endAt/status/version/updatedAt（staff_availability_window 无商家列，门店归属经 storeId 承载；不跨模块连 merchant 表，不虚构 merchantId 字段）。
- 预约占用记录过滤器：`merchantId`/`storeId`/`serviceId`/`orderId`（可选精确）、`status`（TEMP_LOCKED|CONFIRMED|RELEASED|EXPIRED）、`startFrom`/`startTo`（成对，按预约 start_at，≤92 天）。排序 `start_at DESC, id DESC`。行字段：reservationId/orderId/storeId/serviceId/fulfillmentType/startAt/endAt/pickupStartAt/returnStartAt/status/lockExpireAt/capacitySnapshot/qualifiedStaffCountSnapshot/createdAt——6.2.5 临时锁定超时释放记录即 `status=EXPIRED/RELEASED` 行；接送返程 ≥上门+120 分钟的还原核查字段为 pickup/return 原始事实。
- 员工档案列表过滤器：`storeId`、`employmentStatus`（ACTIVE|INACTIVE）、`serviceEnabled`（true|false）。排序 `staff_id ASC`（沿 27 号列表口径）。行字段：staffId/storeId/staffName/phoneMasked/employmentStatus/serviceEnabled/version/createdAt/updatedAt。
- 成员绑定列表过滤器：`storeId`、`status`（ENABLED|DISABLED|REVOKED，52 号 member 状态词汇；REVOKED 随 D3 裁决才可能落行，词汇按存储约束登记）。排序 `member_id ASC`。行字段：memberId/storeId/status/memberName/phoneMasked/grantedActions[]/grantRevoked/memberVersion/grantVersion/createdAt/updatedAt——姓名/掩码手机号来自其 CONFIRMED 邀请行（54 号 §4 同源），无邀请行的历史成员该两列为 null。
- 邀请记录列表过滤器：`storeId`、`status`（INVITED|CONFIRMED|CANCELED）。排序 `id DESC`（沿 54 号 §7 口径）。行字段：invitationId/storeId/memberName/phoneMasked/status/grantedActions[]/invitedBy/invitedAt/updatedAt。
- 单商家子资源（staff/staff-members/invitations）先按 merchantId `check`（AdminResourceScope=MERCHANT 详情语义）重验，未知商家同形 404；已知商家空列表是合法成功。

## 错误映射

控制器本地映射，未识别一律 500 不泄内核：400 `COMMON_INVALID_ARGUMENT`；401 `COMMON_UNAUTHORIZED`（会话缺失/失效/吊销）；403 `COMMON_FORBIDDEN`（动作码未授予、受众非 ADMIN_WEB、dataScope 非 ALL——CITY/MERCHANT fail-closed；内核 RESOURCE_SCOPE_DENIED 对单资源读一律 404 防枚举）；404 `COMMON_NOT_FOUND`（未知/越范围 ID 同形）；503 `COMMON_DEPENDENCY_UNAVAILABLE`（授权决断不可用/投影不可读/事实损坏）。无写路由故无 409/幂等面；全部 GET 不携带 requestId。无新增错误码（Error12 登记状态映射，词汇沿用通用码）。

## 读侧实现

- pet-merchant-api `AdminMerchantGovernanceQueryApi` + merchant-biz 只读服务（`AdminMerchantGovernanceQueryService`）与 `AdminMerchantGovernanceMapper.xml`（merchant LEFT JOIN profile_compat/application/acceptance 聚合投影；staff/member/invitation 独立只读查询；掩码在内核内完成，明文手机号不进服务记录）。不加锁、只读事务 REQUIRES_NEW READ_COMMITTED（沿 63 号 AdminOrderQueryStore 形状）。
- pet-schedule-api `AdminScheduleQueryApi` + schedule-biz 只读服务（`AdminScheduleQueryService`）与 `ScheduleAdminViewMapper.xml`（window 占用聚合计数以 claim×reservation 标量子查询呈现）。只读，无 guard。
- boot `AdminGovernanceQueryController` + `AdminGovernanceConfiguration`（`pet.admin.governance.http.enabled`）；动作码登记 `AdminPermissionEvaluator.DEPLOYED_ACTIONS` 与 `AdminAuthorizationService.checkCollection` 白名单（merchant.read/schedule.read/merchant.staff.read）；两模块授权端口在 boot 适配（biz 不依赖 admin-biz，沿 63 号 AdminTradeConfiguration 先例）。

## 交付与限制

全部增量默认关闭；无 Schema/Event/任务变更（纯只读 HTTP 契约与两模块读侧；不新增表、不新增列）。验收以模块隔离 MySQL 测试覆盖：列表过滤/分页/排序稳定、详情防枚举（未知 ID 与越权同形 404/403）、范围拒绝（CITY/MERCHANT fail-closed）、动作码未授予 403、占用计数与 SOLD_OUT 口径一致、掩码与脱敏字段不出现在任何载荷、申请/签约/成员状态词汇校验、开关默认关。前端 A-002 三页面（tsc/build）消费真实信封字段；运营网页主链 E2E 仍归 QA-005。**【2026-10-16 注记】商家治理详情页新增"停用"入口（36 号裁决）：前端调用 63 号 v0.2 既有处置路由（非本面路由），未授予 `merchant.disable` 时入口 fail-closed 不渲染提交；验收归 governance 前端 spec 与 63 号既有内核测试，本契约读侧验收矩阵不变。**遗留：CITY/MERCHANT 数据范围、§5.6 其余处置动作（冻结/解冻）、员工处置（36 号明确未授权）、成员审计明细、台账跨域经营指标均为已披露缺口，不阻塞本切片。
