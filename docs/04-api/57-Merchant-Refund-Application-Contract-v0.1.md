# 商家退款处理 HTTP 契约 v0.1

2026-10-10；候选随切片实施（[提案](../../planning/ccr/CCR-REF-MERCHANT-HTTP-001/merchant-refund-http-proposal.md)）。来源：SSOT §4.2/§4.3/§38/§39、49号、HTTP10 §4.4/§4.5/§4.6 草案、11号 OpenAPI 既有 Merchant-Refund 路由。本契约只把 49 号已批内核的商家决定面装配为默认关闭 HTTP 切片并补读侧；不新增产品规则，不做超时任务（既有 REFUND_MERCHANT_TIMEOUT 不动）。

## 范围与归属

REFUND 拥有申请/决定/读侧查询与全部准入、幂等、期限、来源规则；本 HTTP 面是薄边界（身份解析、参数校验、严格 JSON、错误映射），不做任何业务判断。ORDER/PAYMENT/SCHEDULE 事实经既有公共 API；商家归属重验沿 `MerchantOrderAuthorityApi`。模块只消费公共 API；refund-biz 不新增模块依赖（OWNER 只读重验经端口注入）。

包括：商家待处理列表、单笔详情、同意全额、拒绝（必填理由）四路由与 M 端处理页面。不包括：部分退款（仅运营售后裁决）、金额参数、拒绝理由字典、员工账号授权、通知送达、超时任务实现、生产启用。

## 路由与开关

`pet.refund.merchant.http.enabled`，默认 false；开启前置 `pet.refund.application.enabled=true`（装配校验失败关闭，内核经 payment.foundation+order.merchant+auth.c 硬互锁）。全部路由 MERCHANT/MINIAPP、真实 bearer 会话、Cache-Control: no-store、信封 `{success,code,message,data,traceId}`（商家家族既有信封）。

- `GET /api/v1/merchant/refund-applications?merchantId&storeId&page&pageSize`：仅该商家有权限处理的 `PENDING_MERCHANT` 申请（HTTP10 §4.4）。page 1..10000 默认 1；pageSize 1..100 默认 20；固定排序 created_at DESC, id DESC；PageResult 信封 items/page/pageSize/total。GET 不绑 X-Request-Id。
- `GET /api/v1/merchant/refund-applications/{applicationId}?merchantId&storeId`：单笔详情，本店任意状态（决定后回看）。未知/跨店/非 OWNER 一律同一 403（读侧防枚举，同订单列表）。
- `POST /api/v1/merchant/refund-applications/{applicationId}/approve`：无业务体（无体或空对象；无部分金额参数，HTTP10 §4.5）。Header `X-Request-Id` 必填（终态 UUID）。
- `POST /api/v1/merchant/refund-applications/{applicationId}/reject`：体严格 `{reasonText}`（HTTP10 §4.6），1..500 Unicode 码点、非全空白；Header `X-Request-Id` 必填。

商家坐标（merchantId+storeId）在共享门店 guard 事务内以 `requireOwnerRead`（读侧）重验（ACTIVE/OFFLINE/FROZEN 存量读侧语义）；决定命令沿内核既有 `requireOwner`（FROZEN 新决定失败关闭）。两读路由对非本店坐标/非 OWNER/未知门店同一 403，跨店行不泄漏。

## 决定语义（内核 49 号原样）

- approve → 内核 `decide(APPROVE)`：APPROVED 决定 + Decided Outbox + 唯一 `REFUND_APPLICATION_CREATE` 恢复任务同事务；随后 durable 任务建 refund_order（来源 `MERCHANT_APPROVED`）并原路执行。回执不表示退款成功。
- reject → 内核 `decide(REJECT)`：REJECTED 决定，无 refund_order；理由密文留存（`refund_application_decision.reason_cipher`），未核销/已核销订单恢复语义由 ORDER 域既有投影承担。
- HTTP 面固定传 expectedApplicationVersion="0"：49 号不变式 PENDING_MERCHANT 行 version 恒 0，重放在版本检查前返回首回执；该取值与任意合法调用等价，不新增客户端字段。
- 幂等沿 23 号五元组（decide scope=`REFUND_APPLICATION:{applicationId}`）：同 requestId 同参重放返回首回执并重验当前 OWNER；同 key 异参 `IDEMPOTENCY_KEY_CONFLICT`；期限竞态/已处理见错误映射。仅 now<merchantDeadline（数据库时间，门店锁内）可决定；到期 `REFUND_MERCHANT_DEADLINE_PASSED`，系统超时任务接管，HTTP 面不吞不绕。

## 回执与读侧字段

决定回执（approve/reject 同形）：orderId/applicationId/applicationStatus（APPROVED|REJECTED）/applicationVersion/merchantDeadline/decidedAt/decisionId。ID String、时间 UTC 毫秒、金额不出现于决定回执。

读侧 Summary（列表项与详情同形，详情另含终态字段）：applicationId/applicationNo/orderId/status（PENDING_MERCHANT|APPROVED|AUTO_APPROVED|REJECTED）/applicationVersion/reasonCode/refundAmount（真实实付全额，JSON 两位小数字符串）/createdAt/merchantDeadline/decidedAt/decisionId/refundOrderId（可空）。买家申请说明明文与买家身份字段（userId/手机号）不回传商家（49 号密文留存，仅内核证明可解密）。

## 错误映射

控制器本地映射，未识别一律 500 不泄内核：400 `COMMON_INVALID_ARGUMENT`/`REFUND_MERCHANT_REASON_REQUIRED`（拒绝缺/空白理由）；401 `COMMON_UNAUTHORIZED`；403 `COMMON_FORBIDDEN`（含读侧防枚举与决定命令跨店）；404 `REFUND_APPLICATION_NOT_FOUND`（决定命令未知 applicationId）；409 `REFUND_APPLICATION_ALREADY_PROCESSED`/`REFUND_MERCHANT_DEADLINE_PASSED`/`IDEMPOTENCY_KEY_CONFLICT`/`COMMON_CONFLICT`/`ORDER_OPERATION_BUSY`/`REFUND_NOT_ELIGIBLE`/`REFUND_ORDER_ALREADY_EXISTS`/`REFUND_ALREADY_EXISTS`；503 `COMMON_DEPENDENCY_UNAVAILABLE`。

## 读侧实现

`MerchantRefundApplicationQueryApi`（pet-refund-api）+ refund-biz 只读服务：REQUIRES_NEW READ_COMMITTED 事务内先共享门店 guard 再 OWNER 只读重验，后按 merchant_id+store_id 双键过滤；SQL 全部在本模块 `RefundApplicationQueryMapper.xml`（无 JdbcTemplate/注解 SQL/内联 SQL，过 check-persistence-style.py）。OWNER 只读端口 `RefundApplicationPorts.OwnerReadAuthority` 由 boot 接 `MerchantOrderAuthorityApi.requireOwnerRead`。

## M 端与准入提示码

M 端商家子包新增 pages/refund 列表+详情（拒绝必填理由 1..500 码点；写沿 `merchant-refund:{merchantId}:{applicationId}:{approve|reject}` 槽位幂等重放；OWNER 准入、FROZEN 只读沿 aftersale 模式；401/403/404 保留隐藏首回执日志）。准入动作目录（10 号 §4.1 拟议提示码，非授权清单）补 `merchant.refund.read`（ACTIVE/OFFLINE/FROZEN）与 `merchant.refund.handle`（ACTIVE/OFFLINE）：OFFLINE 可处理存量退款、FROZEN 只读沿 49 号条款；真正授权仍是内核 requireOwner。

## 交付与限制

全部增量默认关闭；无 Schema/Event/任务变更（仅 HTTP 契约与读侧查询）。商家端 Figma 退款 frame（9:779/9:891/10:2010/40:1061 系列）经本机缓存复核为第三方聚合语义（底部 tab、申诉、客户手机号、订单短码、服务流程），与已批契约冲突，视同设计缺稿，页面沿 M 端现行规范实现并在 20 号登记表 §4 补记。

验收以真实回环 HTTP（隔离 MySQL/Redis、真实会话）覆盖：决定/重放/异参冲突、列表/详情可见性与防枚举、期限竞态、拒绝理由边界、严格 JSON、开关默认关与装配失败关闭、kernel 事实（决定行/事件/任务/无直接出款）与前端 tsc/build/单测。
