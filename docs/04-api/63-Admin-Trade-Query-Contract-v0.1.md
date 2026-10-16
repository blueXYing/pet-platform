# 运营交易查询与处置 HTTP 契约 v0.1→v0.2（订单 / 退款 / 核销）

2026-10-14；随 ADM-002 / A-003 切片实施。来源：SSOT §4（退款/核销/迟到支付规则）、§24（单运营 RBAC）、运营 PRD 6.3.1～6.3.5、HTTP10 §5.1～§5.2 既有草案、AUTH-001 RBAC 提案矩阵第 7/8 行动作码、TRADEABILITY 现状（订单/退款/核销三域既有读侧）。v0.1 只把运营平台级交易查询装配为默认关闭 HTTP 切片；不新增产品规则，不改四层状态口径（DisplayOrderStatus 仍由 order 域统一计算）。

**v0.2 修订（2026-10-15，运营处置动作授权裁决补充 33 号）**：登记运营处置动作（HTTP10 §5.2 渠道查单/重试、§5.5 异常关单、§5.6 商家下线）为本契约处置面，见下文「处置动作（v0.2）」。修订只登记既有草案能力的实现口径，不放宽任何硬规则。

## 包括（v0.1 读侧）

- ORDER 拥有订单/状态日志/改期记录/服务快照读侧；REFUND 拥有退款单/执行/申请关联读侧；VERIFICATION 拥有核销记录/尝试/风控锁读侧。三模块只读各自所有权表，SQL 全部在本模块 Mapper XML（无 JdbcTemplate/注解 SQL/内联 SQL，过 check-persistence-style.py）；boot 装配页只做身份解析、参数校验、严格查询串、错误映射与组装，不做业务判断。
- 订单列表/详情/状态时间轴、退款单列表/详情、核销记录列表/详情七条 GET 路由与 A-003 页面。
- v0.1 未实现并保持草案的写动作（HTTP10 §5.2 `POST /admin/refunds/{refundId}/channel-query|retry`、§5.5 `POST /admin/orders/{orderId}/abnormal-close`、§5.6 `POST /admin/merchants/{merchantId}/disable`）由 v0.2 处置面登记实现，见下节。运营对退款的既有杠杆是售后裁决（51 号，A-004 已交付）；部分退款仍仅运营售后裁决（SSOT 封板）。导出（CSV/Excel）不在 V1 已批准范围。

## 处置动作（v0.2，2026-10-15 用户裁决 33 号）

按 HTTP10 §5.2/§5.5/§5.6 既有草案路径实现，沿用 `pet.admin.trade.http.enabled` 家族开关（默认 false，开启前置 `pet.auth.admin.enabled=true`）。全部动作 OPS/ADMIN_WEB、真实 Bearer 会话、`Cache-Control: no-store`、信封 `{code,message,data,traceId}`、写请求必带一个终端 `X-Request-Id`（23号幂等契约；幂等绑定存各内核 append-only 审计表，同 ID 同参重放返回原回执，同 ID 异参 409 `IDEMPOTENCY_KEY_CONFLICT`）。数据范围沿用 v0.1：仅 `dataScope.mode == ALL`（CITY/MERCHANT 一律 403，MERCHANT/CITY 范围支持仍待归属事实切片）。动作码采用 AUTH-001 提案矩阵第 4/7/8 行拟议码；提案拼写 `refund.channel-query`/`order.abnormal-close` 含连字符，部署词法只允许小写点分段（AdminActionCheckQuery），按 `service.force.offline` 先例做实现期拼写修正为 `refund.channel.query`/`order.abnormal.close`（PR 披露）：

| 方法/路径 | 动作码（部署拼写） | 语义 |
|---|---|---|
| POST `/api/v1/admin/refunds/{refundId}/channel-query` | `refund.channel.query` | 渠道查单：重推该退款既有 `<前缀>REFUND_CHANNEL_QUERY` durable 任务（任务族按 source_type 前缀解析，与 worker 注册一致）；不创建第二张业务退款单（SSOT §22）。任务不存在则按其原排期补建；DEAD/CANCELED 由 owner recovery 复活；READY/RUNNING/RETRY_WAIT 原样返回现状。退款 SUCCESS 或尚未提交过渠道（CREATED）时 409 |
| POST `/api/v1/admin/refunds/{refundId}/retry` | `refund.retry` | 渠道动作重试：重推既有 `<前缀>REFUND_SUBMIT` 任务；只重试渠道动作，PAYMENT 持久边界自行决定可提交或需查单。不创建第二张业务退款单。退款 SUCCESS 时 409 |
| POST `/api/v1/admin/orders/{orderId}/abnormal-close` | `order.abnormal.close` | 异常关闭 + 全额退款（HTTP10 §5.5 固定语义；非正常运营代接单）。走 order 既有状态机：`order_stage → CANCELED`（不新增订单状态），`cancel_reason='ADMIN_ABNORMAL_CLOSE'`（既有事实列新值，PR 披露），原因码 `STORE_OFFLINE|QUALIFICATION_INVALID|MERCHANT_UNREACHABLE|OTHER` + reason 10..500 字。守卫与商家拒单关闭同款（PAID+UNVERIFIED、无在途售后/退款申请、无既有退款单），有在途售后时拒绝而非失效（未履约售后在 refund_order 创建前仍可核销、refund_order 创建后禁止核销——不因本动作改变）。全额退款经退款内核以新 source `ADMIN_ABNORMAL_CLOSE` 创建（仅退款内核既有 source 族增一员，任务/对账/问题单机制复用），金额=渠道实付；order 侧退款成功投影与预约释放复用既有消费者纪律（新消费者 ORDER_ADMIN_REFUND，同事务 claim+投影+释放）。无完成事件 by design（C/M 面读 order 事实，退款链既有事件照常）——PR 披露 |
| POST `/api/v1/admin/merchants/{merchantId}/disable` | `merchant.disable` | 商家下线（HTTP10 §5.6）：立即停止新订单（既有 booking 准入事实读 merchant.status 对非 ACTIVE fail-closed），存量订单继续履约、存量退款/售后继续处理（既有内核对 OFFLINE 商家在途工作照常准入）。复用既有商家状态词表 ACTIVE→OFFLINE（不新增状态）；有在途订单不拒绝本动作。reason 10..500 字。无事件 by design（消费方读权威状态事实，同服务强制下架先例）——PR 披露 |

每个动作在各自内核事务内完成：会话快照校验 → 内核经注入 `AdminAuthorizationQueryApi` 重验（EXECUTE 相位、资源范围）→ 业务状态机/守卫 → append-only 审计行（操作者、authzVersion、requestId、traceId）+ 幂等绑定 → 任务重推/状态迁移/退款创建同 commit。新增表：`refund_admin_action`/`order_admin_action`/`merchant_governance_action`（V32 隔离 opt-in 迁移，Schema 变化 PR 披露）。异常关单退款以既有事件证明形态加入 `refund_execution.chk_refund_source` 白名单（仅约束扩展、既有分支全保留、无数据回填，同 49/50 号先例，V32 与 63 号 Schema 文件双侧同步）。既有四个退款成功投影消费者对 ADMIN 族静默忽略，预约释放白名单同族加入；三个处置内核的同 ID 异参重放一律 409 `IDEMPOTENCY_KEY_CONFLICT`。错误映射沿用 v0.1，另加 409（`COMMON_CONFLICT`/`IDEMPOTENCY_KEY_CONFLICT`/`ORDER_STATE_NOT_ALLOWED`/`ORDER_REFUND_ALREADY_CREATED`/`ORDER_OPERATION_BUSY`）。

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
- 退款列表过滤器：`status`（CREATED|PROCESSING|SUCCESS|FAILED|UNKNOWN）、`sourceType`（PRESTART_AUTO|MERCHANT_REJECT_ORDER|MERCHANT_APPROVED|MERCHANT_TIMEOUT_AUTO|AFTERSALE_DECISION|LATE_PAYMENT_TIMEOUT|ADMIN_ABNORMAL_CLOSE，按内核实际写入词汇；v0.2 增处置来源）、`refundType`（FULL|PARTIAL）、`orderId`、`createdFrom`/`createdTo`（同上）。详情含关联申请投影（applicationId/status/merchantDeadline/decidedAt，不含密文理由）与执行事实（refund_execution 存在时：refundNo/channelTradeNo/金额/时间戳）。
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
