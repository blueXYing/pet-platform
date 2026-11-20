# Phase 2 服务限时闪价 E2E 验收报告

- 日期：2026-11-20（北京时间）
- worktree：`/mnt/d/wt-flash-price`，分支 `codex/svc-flash-price-20261118`
- 验收对象：77 号《服务限时秒杀契约 v0.1》+ CCR-MKT-TOOLS-001 的 P2 内核、三端页面与订单接入
- 结论：**通过**。商家创建→提交审核→运营审核→C 端生效→下单锁价→支付核销全链真实数据跑通。

## 1. 验收环境

| 项 | 值 |
|---|---|
| 启动器 | `E2eCoreLoopServer`（真实 Boot + MySQL + Redis，非 Mock） |
| 端口 | `127.0.0.1:18082` |
| 隔离库 | `auth001czq50f59_e2e_core_loop_ea2477481b9e433bbaecc0a981d547a5`（进程退出自动 DROP） |
| 开关 | `SERVICE_FLASH_PRICE_ENABLED=true`，E2E 启动器自带 `service.command.enabled=true` |
| 账号 | 运营 `e2e-round2-operator`（PLATFORM_SUPER_ADMIN / ALL）；C 端沙箱微信登录 |
| 支付 | E2E 沙箱渠道自动回调 |
| 门店/服务 | `storeId=710302`，`serviceId=710401`，服务标价 `60.00` |

## 2. 验收矩阵

| # | 步骤 | 结果 | 证据 |
|---|---|---|---|
| 1 | 商家创建 8.8 折闪价（配额 10、每用户 1） | 通过 | 创建 `DRAFT`，`listPriceSnapshot=60.00`，`flashPrice=52.80` |
| 2 | 商家提交审核 | 通过 | 状态 `PENDING_REVIEW`，版本递增 |
| 3 | 运营待审列表 | 通过（修复后） | 真实 ADMIN_WEB 会话 200，列表命中该 `flashPriceId` |
| 4 | 运营审核通过 | 通过 | 状态 `ACTIVE`（开始时间已到，非 `SCHEDULED`） |
| 5 | C 端服务详情读侧 | 通过 | `flashPrice=52.80`，`priceSource=FLASH` |
| 6 | C 端下单 | 通过 | `payAmount=52.80`；订单快照 `originalPrice=60.00`、`flashDiscount=7.20`、`priceSource=FLASH` |
| 7 | 沙箱支付 | 通过 | `paymentStatus=PAID`，`orderStage=PENDING_CONFIRM` |
| 8 | 预占消费与计数 | 通过 | `service_flash_price_order.status=CONSUMED`；`sold_count=1`；商家列表 `soldCount=1`、`participantCount=1` |

实测主键：

- `flashPriceId=117094200952483840`
- `orderId=117094201514520576`
- `payAmount=52.80`，`reservation=CONSUMED`，`soldCount=1`

## 3. 本次发现并修复的问题

**现象**：运营 `GET /api/v1/admin/service-flash-prices?status=PENDING_REVIEW` 返回 403，但运营会话与权限集正常。

**根因**：不是 RBAC 缺权，而是管理端安全链漏配路径：

1. `AdminBearerAuthenticationFilter` 的 Bearer 解析白名单没有 `/api/v1/admin/service-flash-prices`，请求未解析 ADMIN_WEB 会话；
2. `AdminAuthSecurityConfiguration` 在 `service.command.enabled` 分支内没有对该路径 `permitAll()`，最终被 `anyRequest().denyAll()` 拦截。

**修复**：两条路径分别补入过滤器白名单与安全链放行；控制器仍由 `service.flash-price.read/review` 动作码二次鉴权。提交 `b3776569`。

**复验**：同一 E2E 脚本重启全新库后全链 PASS，运营列表与 approve 均返回 200。

## 4. 变更范围与遗留风险

- 本次 E2E 修复仅改动 `pet-boot` 管理端安全链两条路径，无 Contract / Schema / Event 变化。
- 已覆盖单次购买主链；并发抢配额、未支付预占到期释放、退款不返还配额等边界由 P2 内核 MySQL 测试覆盖，本报告不将其重复计为 E2E 证据。
- E2E 使用超级管理员验收；普通运营角色仅持有 `service.flash-price.read` 或缺失 `review` 时的权限拆分，由控制器/内核动作码测试覆盖。
