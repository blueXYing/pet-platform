# 运营交易查询 HTTP 契约 v0.1（订单 / 退款 / 核销）

2026-10-14；随 ADM-002 / A-003 切片实施。来源：SSOT §4（退款/核销/迟到支付规则）、§24（单运营 RBAC）、运营 PRD 6.3.1～6.3.5、HTTP10 §5.1～§5.2 既有草案、AUTH-001 RBAC 提案矩阵第 7/8 行动作码、TRADEABILITY 现状（订单/退款/核销三域既有读侧）。本契约只把运营平台级交易查询装配为默认关闭 HTTP 切片；不新增产品规则，不新增写动作，不改四层状态口径（DisplayOrderStatus 仍由 order 域统一计算）。

## 范围与归属

- ORDER 拥有订单/状态日志/改期记录/服务快照读侧；REFUND 拥有退款单/执行/申请关联读侧；VERIFICATION 拥有核销记录/尝试/风控锁读侧。三模块只读各自所有权表，SQL 全部在本模块 Mapper XML（无 JdbcTemplate/注解 SQL/内联 SQL，过 check-persistence-style.py）；boot 装配页只做身份解析、参数校验、严格查询串、错误映射与组装，不做业务判断。
- 包括：订单列表/详情/状态时间轴、退款单列表/详情、核销记录列表/详情七条 GET 路由与 A-003 页面。
- 不包括（保持既有契约草案，本切片不实现、不注册新写动作）：HTTP10 §5.2 `POST /admin/refunds/{refundId}/channel-query|retry`（需退款内核渠道查单/重试任务化切片）、§5.5 `POST /admin/orders/{orderId}/abnormal-close`（异常关闭+全额退款需独立写内核切片，涉及资金链与订单关闭原子性）、§5.6 `POST /admin/merchants/{merchantId}/disable`（商家治理切片）。运营对退款的既有杠杆是售后裁决（51 号，A-004 已交付）；部分退款仍仅运营售后裁决（SSOT 封板）。导出（CSV/Excel）不在 V1 已批准范围。

## 路由与开关

`pet.admin.trade.http.enabled`，默认 false；开启前置 `pet.auth.admin.enabled=true`（装配在 AdminAuthSecurityConfiguration 的 ADMIN_WEB Bearer 链上，缺一即 403 关闭）。全部路由 OPS/ADMIN_WEB、真实 Bearer 会话、`Cache-Control: no-store`、信封 `{code,message,data,traceId}`（SUCCESS/"ok"）。GET 不绑 X-Request-Id。动作码采用 AUTH-001 RBAC 提案矩阵第 7/8 行拟议码（本切片随部署登记 `DEPLOYED_ACTIONS`，语义=HTTP10 §5.1 既有草案）：

| 方法/路径 | 动作码 | 说明 |
|---|---|---|
| GET `/api/v1/admin/trade/orders` | `order.read` | 平台订单列表 |
| GET `/api/v1/admin/trade/orders/{orderId}` | `order.read` | 订单详情（履约层+退款层链接+核销链接） |
| GET `/api/v1/admin/trade/orders/{orderId}/status-logs` | `order.status-log.read` | 状态时间轴（order_status_log，append-only） |
| GET `/api/v1/admin/trade/refunds` | `refund.read` | 退款单列表 |
| GET `/api/v1/admin/trade/refunds/{refundOrderId}` | `refund.read` | 退款单详情（单+执行事实） |
| GET `/api/v1/admin/trade/verifications` | `order.verification-log.read` | 平台核销记录列表 |
| GET `/api/v1/admin/trade/verifications/{verificationId}` | `order.verification-log.read` | 核销记录详情（含尝试/风控锁定溯源） |

## 鉴权、数据范围与脱敏

- 三层鉴权不变：AdminBearerAuthenticationFilter 解析会话 → 控制器按 AdminSessionView 快照校验动作码与 ADMIN_WEB 受众 → 模块内核经注入的 `AdminAuthorizationQueryApi` 每次调用重验（列表 `checkCollection`、单资源 `check`+`AdminResourceScope`，READ_RESULT 相位）。控制面板动作码不是授权证明。
- 数据范围 v0.1：trade 面要求 `dataScope.mode == ALL`。order 域无城市事实（快照属商家/门店名，非授权归属），refund_order/verification_record 行内无商家归属列（execution/application 的 merchant_id 只覆盖部分行），按 CCR-PERM-001 §3 "缺权威归属事实不能从请求过滤器猜" 全部 fail-closed：CITY/MERCHANT 会话对 trade 面统一 403（控制器与内核双侧拒绝）。MERCHANT/CITY 范围支持待归属事实切片（PR 披露）。
- 脱敏（SSOT §24/22 号补充）：全部读侧不回传用户手机号、地址明文、联系人、宠物主人身份；用户以 `userId`（String）呈现。核销凭证密文（code_cipher）、申请理由密文（reason_text_cipher）不出域，凭证号 lookup/code 一律不回传。退款申请密文理由不展开；商家拒绝理由（refund_application.merchant_decision_reason 已禁明文）不回传。
- 审计：登录/拒绝审计沿 admin auth 域既有实现（recordRejected）；本切片纯只读、无敏感明文读取动作，不新增写审计面（与 51 号运营读侧同口径）。

## 查询参数与固定语义

- 分页：`page` 1..10000 默认 1；`pageSize` 1..100 默认 20；固定排序 `created_at DESC, id DESC`；PageResult 信封 `{items,page,pageSize,total}`。未知查询参数一律 400（与 merchant-applications 列表同纪律）。
- 订单列表过滤器：`displayStatus`（十值 DisplayOrderStatus 词汇，XML 固定分支）、`paymentStatus`（INIT|PAID）、`merchantId`、`storeId`、`orderNo`（精确）、`keyword`（2..64 字符；纯数字且合法 Snowflake 形态按 order_no 精确，否则按服务快照 merchant_name LIKE）、`createdFrom`/`createdTo`（UTC，跨度 ≤366 天，仅提供其一也必须成对合法——单边不给默认）、`rescheduledOnly`（true=仅 reschedule_count>0，6.3.3 运营核对）。
- 订单详情：订单投影（含四层可得事实：order_stage/payment_status/refund/refundOrderId/refundedAmount/verification_status/displayStatus/金额 BigDecimal→两位字符串）+ 服务快照（order_service_snapshot：服务名/类目/商家名/门店名/地址——门店地址仅运营治理面需要，回传）+ 改期记录（order_reschedule_record：原始与当前预约、次数、每次申请/结果）。支付渠道交易号、结算层（费率/佣金/入账/冻结）不在本切片（结算口径 OD-W0-001 未决，不虚构）。
- 退款列表过滤器：`status`（CREATED|PROCESSING|SUCCESS|FAILED|UNKNOWN）、`sourceType`（PRESTART_AUTO|MERCHANT_REJECT_ORDER|MERCHANT_APPROVED|MERCHANT_TIMEOUT_AUTO|AFTERSALE_DECISION|LATE_PAYMENT_TIMEOUT，按内核实际写入词汇）、`refundType`（FULL|PARTIAL）、`orderId`、`createdFrom`/`createdTo`（同上）。详情含关联申请投影（applicationId/status/merchantDeadline/decidedAt，不含密文理由）与执行事实（refund_execution 存在时：refundNo/channelTradeNo/金额/时间戳）。
- 核销列表过滤器：`orderId`、`storeId`、`method`（SCAN|MANUAL）、`verifiedFrom`/`verifiedTo`。详情含该记录尝试链（verification_attempt：result/failReason/时间）与风控尝试（verification_credential_risk_attempt：result_code/counts_failure）用于争议溯源；不含凭证密文。
- 时间一律 UTC 毫秒 `YYYY-MM-DDTHH:mm:ss.SSSZ` 字符串；ID 一律 Snowflake String；金额两位小数字符串。

## 错误映射

控制器本地映射，未识别一律 500 不泄内核：400 `COMMON_INVALID_ARGUMENT`；401 `COMMON_UNAUTHORIZED`（会话失效）；403 `COMMON_FORBIDDEN`（动作未授予/受众不符/dataScope 非 ALL/内核 RESOURCE_SCOPE_DENIED→资源读一律 404 防枚举）；404 `COMMON_NOT_FOUND`（未知/越范围 ID 同形，防枚举）；503 `COMMON_DEPENDENCY_UNAVAILABLE`（授权决断不可用/投影不可读）。无写路由故无 409/幂等面；列表/详情 GET 不携带 requestId。

## 读侧实现

- pet-order-api `AdminOrderQueryApi` + order-biz 只读服务与 `AdminOrderQueryMapper.xml`（复用既有 OrderQueryStore 事务形状：REQUIRES_NEW READ_COMMITTED）；displayStatus 复用同一 `OrderDisplayStatus` 真值表。
- pet-refund-api `AdminRefundQueryApi` + refund-biz 只读服务与 `AdminRefundQueryMapper.xml`（refund_order LEFT JOIN refund_execution/refund_application 只读投影，不加锁、无 guard——纯只读无出款语义）。
- pet-verification-api `AdminVerificationQueryApi` + verification-biz 只读服务与 `AdminVerificationQueryMapper.xml`（verification_record/attempt/risk_attempt 只读）。
- boot `AdminTradeQueryController` + `AdminTradeConfiguration`（`pet.admin.trade.http.enabled`）；动作码登记 `AdminPermissionEvaluator.DEPLOYED_ACTIONS` 与 `AdminAuthorizationService.checkCollection` 白名单（order.read/order.status-log.read/order.verification-log.read/refund.read）。

## 交付与限制

全部增量默认关闭；无 Schema/Event/任务变更（纯只读 HTTP 契约与三模块读侧）。验收以模块隔离 MySQL 测试覆盖：列表过滤/分页/排序稳定、详情防枚举（未知 ID 与越权同形 404/403）、范围拒绝（CITY/MERCHANT fail-closed）、动作码未授予 403、会话失效 401、displayStatus 真值表与 C 端逐行一致、脱敏字段不出现在任何载荷、开关默认关。前端 A-003 页面（tsc/build）消费真实信封字段；运营网页主链 E2E 仍归 QA-005。
