# Phase 2 执行手册：服务限时秒杀/限时价格

- 状态：**APPROVED / 2026-11-18**（依据 [41 号裁决补充](../../../../docs/01-prd/41-商家营销工具与会员卡裁决补充-v1.0.md) §4；P1 已合入 `develop` `7ed7a7a3`）
- 执行 worktree：`/mnt/d/wt-flash-price`
- 执行分支：`codex/svc-flash-price-20261118`
- 目标：按 Figma `18:9537`（限时秒杀列表）/ `18:9694`（创建秒杀）实现商家服务限时价格 + 运营审核 + C 端展示/下单价快照
- 一次只执行一个 Phase；本文只覆盖 P2。不要顺手实现 P3 商家券、P4 裂变、P5 会员卡。

> 本文固定了 P2 的产品/技术决策。执行智能体不要就字段、状态、价格叠加、配额释放、页面显示再问用户。遇到本文未覆盖且与现有契约冲突的事项，停止并报告「冲突点 + 文件/行号 + 建议」，不要自行改交易/退款/售后/核销硬规则。

---

## 0. 你必须先确认

1. 工作区与分支：
   ```bash
   git -C /mnt/d/wt-flash-price rev-parse --show-toplevel
   git -C /mnt/d/wt-flash-price branch --show-current
   git -C /mnt/d/wt-flash-price status --short
   ```
   必须是 `/mnt/d/wt-flash-price` + `codex/svc-flash-price-20261118`。不一致：停止报告，不要切换 worktree，不要操作 `/mnt/d/wt-marketing-tools` 或其他 `/mnt/d/wt-*`。
2. 先读：
   - `AGENTS.md`
   - `docs/01-prd/41-商家营销工具与会员卡裁决补充-v1.0.md` §4
   - `planning/progress/2026-11-17/merchant-marketing-tools/PLAN.md` §7
   - `planning/progress/2026-11-17/merchant-marketing-tools/HANDOFF.md` §2.2 / §5
   - `docs/04-api/38-Atomic-Booking-Create-Contract-v0.1.md`
   - `docs/04-api/11-...`（订单 HTTP/内部契约）、`docs/04-api/74-Service-Catalog-Page-Contract-v0.1.md`、`docs/04-api/75-Payment-Center-Contract-v0.1.md`
   - `backend/pet-order-biz/src/main/java/com/petplatform/order/biz/application/OrderCreationService.java`
   - `backend/pet-order-biz/src/main/java/com/petplatform/order/biz/apiimpl/OrderExpiryApiImpl.java`
   - `backend/pet-service-api/src/main/java/com/petplatform/service/api/query/BookingServiceFactsApi.java`
   - `backend/pet-service-biz/src/main/java/...` 现有服务查询/审核实现
3. 先落文档/契约（WP-00），再写代码。不要先写页面。

---

## 1. 固定决策（不得再问）

### 1.1 业务形态

1. 秒杀是**服务项目的限时价格**，不是新订单类型、不是套餐/次卡/商品；仍单次服务、必须预约。
2. 价格权威在 `pet-service`；营销模块只提供入口/展示，不改 `pay_amount` 权威。商家 UI 可挂在 `merchant/pages/marketing/flash/*`，但 API/表在 service 模块。
3. 商家创建 → 运营审核 → 按窗口生效；**商家不可直接发布**。Figma「立即上架」改为「提交审核」。
4. 同一服务同一时间最多一个有效/待生效/待审核的闪价窗口；重叠返回 409。
5. 闪价必须 >0 且 < 当前服务价；商家输入折扣（折，示例 `8.8`），服务端计算：
   `flashPrice = round(listPrice × discountRate / 10, 2, HALF_UP)`；`flashPrice > 0` 且 `< listPrice`。
6. P2 不支持「使用门槛（满 X 元可用）」：服务价格本身就是成交金额，没有购物车；表单不展示该字段。
7. P2 不做活动头图上传：列表/创建页使用所选服务的现有封面与名称；`title` 可覆盖展示名（默认服务名），`description` ≤300 字。不要复用 P1 营销素材上传到 service 模块（避免营销↔服务循环依赖）。
8. 优惠形式「限时折扣/限时秒杀」仅为展示标签，P2 统一落 `priceMode=DISCOUNT`；金额由折扣计算。
9. 总数/每人限领可选；不填=不限。总量按预占数（RESERVED）+已支付（CONSUMED）计。
10. C 端展示：服务卡/详情/订单确认显示限时价、倒计时、剩余名额（有总量时）；无闪价时行为与现状完全一致。

### 1.2 状态机（`service_flash_price`）

```text
DRAFT
  └─ submit → PENDING_REVIEW
        ├─ approve (startAt > now) → SCHEDULED
        ├─ approve (startAt <= now) → ACTIVE
        ├─ reject → REJECTED
        └─ cancel（商家，仅未审/驳回）→ CANCELED
SCHEDULED → ACTIVE（到 startAt；读侧按时间过滤即可，可选 durable task）
ACTIVE → PAUSED（运营）
SCHEDULED/ACTIVE/PAUSED → ENDED（运营；到 endAt 后读侧视为结束）
DRAFT/REJECTED → CANCELED（商家）
```

- 商家可编辑 `DRAFT/REJECTED`；`PENDING_REVIEW` 后不可编辑。
- 运营动作：`approve/reject/pause/end`；驳回原因 1–500 字；全部 append-only 审计。
- 审核通过时重新校验服务归属、服务 ACTIVE、窗口、折扣、价格、重叠；通过时快照 `list_price_snapshot`。
- 商家不可发布/暂停/结束；运营不可修改价格（只能驳回让商家改）。

### 1.3 配额与订单

1. 下单事务内预占：`reserve(flashPriceId, orderId, userId, expireAt)`。
   - `expireAt` = 订单支付截止时间（`held.holdExpireAt()`）。
   - 锁 `service_flash_price` 行；校验状态/窗口/剩余/每人限领；`sold_count + 1`；插 `service_flash_price_order(RESERVED, expire_at)`。
   - 任何后续下单步骤失败，整个事务回滚（预占自动消失）。
2. 支付成功：服务模块消费既有 `OrderPaidEvent.v1`，把该订单预占置 `CONSUMED`（幂等；已 `RELEASED` 的迟到支付保持 RELEASED，不回补配额；订单迟到支付退款由既有规则处理）。
3. 过期释放：服务模块自己的 durable AsyncTask 扫描 `RESERVED AND expire_at < now - 5min`，置 `RELEASED` 并 `sold_count - 1`；**不读订单表、不新增订单取消事件**。
4. 退款：已支付订单退款**不恢复**名额；`CONSUMED` 不变。
5. 每人限领：统计同一 `(flashPriceId,userId)` 的 `RESERVED/CONSUMED` 行；`RELEASED` 不计。
6. 无闪价/闪价不适用时：`priceSource=LIST`，订单行为与现状一致。
7. 价格叠加：闪价是基准价；券在闪价后计算（61 号 `amountOff < orderAmount`，折后应付 >0）；P2 每单仍最多一张券。
8. 分账口径（写契约，不在 P2 实现支付中心阶段 2）：
   `分账基数 = 服务原价(list_price_snapshot/当前原价) − 闪价优惠 − 商家券优惠`；平台券不扣商家。P2 只保证订单快照能提供这些字段。

### 1.4 订单字段（必须落库）

`pet_order` 新增：

| 字段 | 类型 | 说明 |
|---|---|---|
| `original_price` | DECIMAL(18,2) | 下单时服务原价（listPrice） |
| `flash_discount` | DECIMAL(18,2) | 原价 − 闪价；无闪价=0 |
| `price_source` | VARCHAR(16) | `LIST` / `FLASH` |
| `flash_price_id` | BIGINT/null | 闪价记录 |

`order_service_snapshot` 增加 `service_list_price/service_price_source/service_flash_price_id`（或等价列）；`snapshotJson` 同步记录。

`BookingServiceFacts` 增加：`listPrice`、`priceSource`、`flashPriceId`、`flashDiscount`。`ServiceSnapshotDTO`/服务卡/详情增加 `flashPrice/flashStartAt/flashEndAt/flashRemaining/priceSource`（按实际读模型）。

### 1.5 开关/权限

- 开关：`pet.service.flash-price.enabled=false`（默认关闭；所有新端点/任务/消费者受它约束；关闭时服务查询不返回闪价字段、订单走 LIST）。
- 商家：C 会话 + 门店 OWNER；服务必须属于本人门店且 ACTIVE；员工不可写。
- 运营：`service.flash-price.read/review`（或沿用既有 service 审核动作码，二选一并登记）；数据范围复核、fail-closed。
- 所有写：`X-Request-Id` 幂等 + 乐观 `version` + append-only 审计。

---

## 2. 阶段门禁（客观判断，不向用户提问）

P2 没有支付中心外部阻塞；可开始条件：

1. worktree/branch 正确；
2. 41 号补充在仓库（已合并）；
3. 先创建 `CCR-SVC-FLASH-PRICE-001` + `77-Service-Flash-Price-Contract-v0.1.md`，并修订 38/11/74/75/12 与 Schema；
4. `pet.service.flash-price.enabled` 默认关闭。

**不满足就停止报告缺失项，不要 stub、不要先写页面。**

---

## 3. WP-00：文档/契约（先做）

### 3.1 新建

| 文件 | 内容 |
|---|---|
| `planning/ccr/CCR-SVC-FLASH-PRICE-001.md` | 问题、范围、决策、Schema/API/订单快照、配额、退款、测试、风险；状态 APPROVED（依据 41 §4） |
| `docs/04-api/77-Service-Flash-Price-Contract-v0.1.md` | 商家/运营/C 端接口、字段、状态、错误码、幂等、配额、订单价快照 |
| `docs/03-database/73-Service-Flash-Price-Schema-v0.1.sql` | `service_flash_price`、`service_flash_price_order`、审计、命令幂等 |
| `backend/pet-boot/src/main/resources/db/service-migration/V31__service_flash_price.sql` | 同 Schema 的增量迁移（编号若被占用则顺延） |

### 3.2 更新

- `docs/04-api/38-Atomic-Booking-Create-Contract-v0.1.md`：价格来源、`BookingServiceFacts`、订单快照字段、闪价预占；不得改变锁/事务顺序。
- `docs/04-api/07-内部API-Contract-v0.6.md`：订单快照/内部 DTO 的 `originalPrice/flashDiscount/priceSource`。
- `docs/04-api/10-HTTP-API-Contract-v0.4.md`：C 端订单详情/确认返回的闪价与优惠字段。
- `docs/04-api/11-OpenAPI-Core-v0.4.yaml`：如 schema 由该文件生成，同步 DTO；不生成则说明不涉及。
- `docs/04-api/74-Service-Catalog-Page-Contract-v0.1.md`：服务卡/详情闪价字段与展示规则。
- `docs/04-api/75-Payment-Center-Contract-v0.1.md` §5：分账基数扣除闪价优惠（契约口径；不实现阶段 2）。
- `docs/04-api/12-Error-Code-Registry-v0.5.md`：登记 §5 错误码。
- `docs/08-engineering/20-设计源登记表-figma-map.md`、Figma 覆盖审计：标注 `18:9537/18:9694` P2 实现/偏差。
- `backend/pet-boot/src/test/java/com/petplatform/boot/e2e/E2eCoreLoopServer.java` 的 `loadSchemas`：加入 `73-Service-Flash-Price-Schema-v0.1.sql`。
- `frontend-miniapp/src/app.config.ts` + `frontend-miniapp/package-check.cjs`：新增商家页路由必须同时登记（P1 CI 教训）。

---

## 4. WP-01：Schema/迁移

```sql
CREATE TABLE service_flash_price (
    id                 BIGINT NOT NULL,
    service_id         BIGINT NOT NULL,
    merchant_id        BIGINT NOT NULL,
    store_id           BIGINT NOT NULL,
    title              VARCHAR(100) NOT NULL,
    description        VARCHAR(300) NULL,
    price_mode         VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DISCOUNT',
    discount_rate      DECIMAL(4,2) NOT NULL,
    flash_price        DECIMAL(18,2) NOT NULL,
    list_price_snapshot DECIMAL(18,2) NOT NULL,
    start_at           DATETIME(3) NOT NULL,
    end_at             DATETIME(3) NOT NULL,
    total_quota        INT NULL,
    per_user_limit     INT NULL,
    sold_count         INT NOT NULL DEFAULT 0,
    status             VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reject_reason      VARCHAR(500) NULL,
    version            INT NOT NULL DEFAULT 1,
    created_by_type    VARCHAR(16) NOT NULL,
    created_by_id      BIGINT NOT NULL,
    created_at         DATETIME(3) NOT NULL,
    updated_at         DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_flash_price_service_time (service_id, status, start_at, end_at),
    KEY idx_flash_price_store_status (store_id, status, start_at),
    CONSTRAINT chk_flash_price_mode CHECK (price_mode = 'DISCOUNT'),
    CONSTRAINT chk_flash_price_rate CHECK (discount_rate > 0 AND discount_rate < 10),
    CONSTRAINT chk_flash_price_amount CHECK (flash_price > 0 AND flash_price < list_price_snapshot),
    CONSTRAINT chk_flash_price_time CHECK (start_at < end_at),
    CONSTRAINT chk_flash_price_quota CHECK (total_quota IS NULL OR total_quota > 0),
    CONSTRAINT chk_flash_price_per_user CHECK (per_user_limit IS NULL OR per_user_limit > 0),
    CONSTRAINT chk_flash_price_sold CHECK (sold_count >= 0),
    CONSTRAINT chk_flash_price_status CHECK (status IN
        ('DRAFT','PENDING_REVIEW','SCHEDULED','ACTIVE','PAUSED','ENDED','REJECTED','CANCELED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='服务限时价格（价格权威在 service；订单快照引用）';

CREATE TABLE service_flash_price_order (
    id            BIGINT NOT NULL,
    flash_price_id BIGINT NOT NULL,
    order_id      BIGINT NOT NULL,
    user_id       BIGINT NOT NULL,
    reserved_price DECIMAL(18,2) NOT NULL,
    status        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expire_at     DATETIME(3) NOT NULL,
    reserved_at   DATETIME(3) NOT NULL,
    consumed_at   DATETIME(3) NULL,
    released_at   DATETIME(3) NULL,
    version       INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_flash_price_order (order_id),
    KEY idx_flash_price_order_user (flash_price_id, user_id, status),
    KEY idx_flash_price_order_expire (status, expire_at),
    CONSTRAINT chk_flash_price_order_status CHECK (status IN ('RESERVED','CONSUMED','RELEASED')),
    CONSTRAINT chk_flash_price_order_price CHECK (reserved_price > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE service_flash_price_audit (
    id BIGINT NOT NULL,
    flash_price_id BIGINT NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    from_status VARCHAR(24) NULL,
    to_status VARCHAR(24) NULL,
    operator_type VARCHAR(16) NOT NULL,
    operator_id BIGINT NOT NULL,
    request_id VARCHAR(191) NOT NULL,
    trace_id VARCHAR(128) NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_flash_price_audit_request (request_id),
    KEY idx_flash_price_audit_price (flash_price_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE service_flash_price_command (
    id BIGINT NOT NULL,
    request_key VARCHAR(191) NOT NULL,
    command_type VARCHAR(48) NOT NULL,
    params_sha256 CHAR(64) NOT NULL,
    params_canonical LONGBLOB NOT NULL,
    status VARCHAR(16) NOT NULL,
    receipt_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_flash_price_command_request (request_key),
    CONSTRAINT chk_flash_price_command_status CHECK (status IN ('IN_PROGRESS','SUCCEEDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

订单迁移：新建 `backend/pet-boot/src/main/resources/db/order-migration/V31__order_flash_price.sql`（目录不存在则创建；各模块迁移目录版本号独立），并同步更新 `docs/03-database/06-核心数据库Schema-v0.1.sql` 的 `pet_order` / `order_service_snapshot` 定义。`pet_order` + `order_service_snapshot` 新字段；不猜测存量，存量迁移后 `price_source='LIST'`、`flash_discount=0`、`original_price=price`。

---

## 5. WP-02：service 模块

### 5.1 API（`pet-service-api`）

- 商家：
  ```text
  GET  /api/v1/merchant/services/{serviceId}/flash-prices
  POST /api/v1/merchant/services/{serviceId}/flash-prices
  PUT  /api/v1/merchant/services/{serviceId}/flash-prices/{id}
  POST /api/v1/merchant/services/{serviceId}/flash-prices/{id}/submit
  POST /api/v1/merchant/services/{serviceId}/flash-prices/{id}/cancel
  ```
- 运营：
  ```text
  GET  /api/v1/admin/service-flash-prices
  POST /api/v1/admin/service-flash-prices/{id}/approve
  POST /api/v1/admin/service-flash-prices/{id}/reject
  POST /api/v1/admin/service-flash-prices/{id}/pause
  POST /api/v1/admin/service-flash-prices/{id}/end
  ```
- 内部（order 调用）：
  ```text
  ServiceFlashPriceReservationApi.reserve(ReserveCommand)
  ```
  `ReserveCommand(context, flashPriceId, orderId, userId, expireAt)` 返回
  `{flashPriceId, price, listPrice, flashDiscount, status}`。
  只负责预占；支付成功由 `OrderPaidEvent.v1` 消费者置 CONSUMED；过期由 service durable task 释放。
- C 端：服务卡/详情读模型增加闪价字段；订单确认用服务返回的生效价，客户端不得传价格。

### 5.2 校验

- 服务归属：service_id 属于 merchant_id/store_id，ACTIVE；否则 404/409。
- `title` 1..100（默认服务名）；`description` ≤300；`discountRate` (0,10)；`startAt < endAt`。
- `totalQuota` null 或 1..1,000,000；`perUserLimit` null 或 1..1,000。
- 窗口重叠：同 service 的 `PENDING_REVIEW/SCHEDULED/ACTIVE/PAUSED` 与新区间相交 → 409 `FLASH_PRICE_WINDOW_OVERLAP`。用服务行 `FOR UPDATE` 或等价串行化，不能只靠“先查后插”。
- 审核通过时重新读服务当前价；若 `flashPrice >= 当前价` → 409 `FLASH_PRICE_INVALID` 并提示商家调整。
- 商家不能传 `status/merchantId/storeId/listPriceSnapshot/soldCount`；服务端固定。

### 5.3 读侧价格解析

- `readCurrentService` / `ServiceSnapshotDTO` 返回：
  - `price` = 生效价（闪价 Active 且在窗口内且 `flashPrice < 当前价`，否则 LIST）；
  - `listPrice` / `priceSource` / `flashPriceId` / `flashDiscount` / `flashStartAt` / `flashEndAt` / `flashRemaining`（有总量时）。
- 闪价在窗口内但 `flashPrice >= 当前价`：按 LIST 返回，不展示闪价；不阻断服务读取。
- 无开关/无闪价：返回 LIST，字段为 null/0，行为与现状一致。

### 5.4 配额/消费者/任务

- `reserve`：锁闪价行 → 校验 status∈{SCHEDULED,ACTIVE}、`startAt<=now<endAt`、总量/每人限领 → `sold_count+1` + 插 RESERVED（`expire_at` 用命令传入）→ 返回权威价格。
- `OrderPaidEvent.v1` 消费者：按 `orderId` 找 RESERVED → CONSUMED；不存在/已 RELEASED → NOOP（迟到支付不恢复）。
- durable task `FLASH_PRICE_RELEASE_EXPIRED`：owner=SERVICE，扫描 `RESERVED AND expire_at < now - 5min`，CAS 置 RELEASED + `sold_count-1`；分批、幂等、可重入。
- 退款不恢复；不消费 `RefundSucceededEvent` 做配额回补。

---

## 6. WP-03：订单接入（高风险，独立 PR/严格测试）

- `BookingServiceFacts` 增加 `listPrice/priceSource/flashPriceId/flashDiscount`。
- `OrderCreationService.execute`：
  1. `readCurrentService` 得到候选生效价/闪价 id；
  2. `reservations.hold` 后，若有 `flashPriceId`：调用 `ServiceFlashPriceReservationApi.reserve(... expireAt=held.holdExpireAt())`，用返回的 `price/listPrice/flashDiscount` 作为权威值；
  3. 券冻结用生效价（61 号；`payAmount >0`）；
  4. `writeOrder` 写 `price=生效价`、`original_price=listPrice`、`flash_discount`、`price_source`、`flash_price_id`；服务快照同步；
  5. 任一步失败回滚，预占随事务消失。
- 无闪价：不调用 reserve；`price_source=LIST`、`flash_discount=0`、`original_price=price`；现有行为不变。
- 不新增订单取消事件；不读 service 表；不修改 30 分钟确认/预约/退款/售后/核销规则。
- `OrderExpiryApiImpl` 不需要改动（过期释放由 service durable task 按 `expire_at` 完成）；确认没有重复释放/回补。

---

## 7. WP-04：运营后台

- `frontend-admin` 新增/扩展秒杀审核列表/详情：服务名、门店、折扣/闪价、原价、窗口、总量/已售、每人限领、状态、提交人、驳回原因。
- 操作：通过/驳回（原因 1–500）/暂停/结束；展示审计时间。
- 不新增运营改价入口；价格只能由商家改后重新提交。
- 运营前端 `expectedVersion` 字符串/整数兼容（P1 已在后端修）；新 API 直接用同一 try 逻辑。

## 8. WP-05：小程序商家端/C 端

### 8.1 商家端

- 路由：
  ```text
  /merchant/pages/marketing/flash/index   (Figma 18:9537)
  /merchant/pages/marketing/flash/edit    (Figma 18:9694)
  ```
- 列表：场次、参与人数（RESERVED+CONSUMED 去重用户）、已售/名额（有总量时）；不展示转化率（无曝光数据）。
- 创建/编辑：
  - 选择本店 ACTIVE 服务（显示服务封面/名称/原价）；
  - 活动名称（默认服务名，可改）、活动说明（≤300）；
  - 优惠形式 chips（限时折扣/限时秒杀，P2 都落 DISCOUNT）；
  - 优惠力度（折，示例 8.8）→ 前端显示计算后的限时价（服务端复算）；
  - 发放总量/每人限领（可空）；
  - 有效期（startAt/endAt）；
  - **不展示使用门槛/头图上传**；
  - 「立即上架」→「提交审核」；
  - 状态：草稿/待审核/已驳回/待开始/进行中/已暂停/已结束/已取消；驳回显示原因并可编辑重提。
- 所有数字来自 API；不伪造名额/价格/参与人数。
- 新路由同步 `app.config.ts` + `package-check.cjs`。

### 8.2 C 端

- 服务列表/详情：有闪价时显示「限时价 ¥x」、原价划线、倒计时、剩余名额（有总量）；结束后恢复原价，不显示失效闪价。
- 订单确认：显示生效价/原价/闪价优惠；提交订单不传价格。
- 不做新的 C 端路由（除非服务详情已有页面无法承载，需另立 CCR/路由并更新 package-check）。

---

## 9. WP-06：测试与验收

### 9.1 必跑

```bash
cd /mnt/d/wt-flash-price/backend
export JAVA_HOME=/home/blueXYing/.local/opt/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
python3 tools/check-persistence-style.py
python3 tools/check-module-deps.py
mvn -q -pl pet-service-biz -am test
mvn -q -pl pet-order-biz -am test
mvn -q -pl pet-boot -am test -Dtest='*FlashPrice*Test,*Booking*Test' -Dsurefire.failIfNoSpecifiedTests=false

cd /mnt/d/wt-flash-price/frontend-miniapp
npm run typecheck
npm test
npm run build:weapp
npm run check:package

cd /mnt/d/wt-flash-price/frontend-admin
npm run typecheck
npm run build
npm run check:boundaries
```

### 9.2 必测场景

1. 商家创建 DRAFT → 编辑 → 提交 PENDING_REVIEW；运营通过 → SCHEDULED/ACTIVE；驳回可改重提；商家不能发布。
2. 同服务重叠窗口被 409 拒绝（并发两个请求只成功一个）。
3. 审核通过时服务价变化导致 `flashPrice >= 当前价` → 拒绝。
4. 下单：有闪价 → `price_source=FLASH`、`original_price/list/flash_discount` 正确、`pay_amount=闪价−券` 且 >0；无闪价 → LIST 行为不变。
5. 配额：总量 1 时并发两单只成功 1；每人限领 1 时第二单 409。
6. 支付成功 → 预占 CONSUMED；未支付过期 → durable task RELEASED 且 `sold_count-1`；已支付退款 → CONSUMED 不变、名额不恢复。
7. 迟到支付（已 RELEASED 后支付）→ 不恢复名额，订单按既有迟到支付规则处理。
8. 券叠加：闪价后冻结券、折后应付 >0；无券路径不变。
9. C 端展示/订单确认价格与服务端权威一致；客户端传价格被忽略/拒绝。
10. 关闭开关：服务查询不返回闪价、订单走 LIST，现有测试全绿。

### 9.3 E2E（真实 Spring+MySQL+Redis，不要 Docker）

- 在 `E2eCoreLoopServer.loadSchemas` 加入新 Schema 文件；
- 起独立 18082/新库/新 node；
- 商家创建 → 运营审核 → C 下单（闪价）→ 沙箱支付 → 预占 CONSUMED；另造一单不支付 → 过期释放；
- 断言订单快照/支付金额/名额/服务卡展示；
- 输出 `planning/progress/2026-11-18/svc-flash-price/E2E-REPORT.md`，含命令、ID、结果、遗留。

---

## 10. 不做/设计偏差

- 不做使用门槛、活动头图、买赠/套餐/次卡、拼团、直播。
- 不展示转化率（无曝光/点击数据）；只展示场次/参与人数/已售/名额。
- 不实现支付中心阶段 2 分账；只写契约口径与订单快照。
- 不让商家直接发布；不新增订单类型/支付方式。
- 不读取/修改其他模块表；不跨模块访问 Mapper/DO。

---

## 11. 完成报告模板

```text
Phase: P2
状态: 完成 / 部分完成 / 阻塞
工作区/分支: /mnt/d/wt-flash-price / codex/svc-flash-price-20261118
变更文件: ...
契约/Schema/Event 变化: ...
测试命令与结果: ...
E2E: ...
未完成/阻塞: ...
遗留风险: ...
```

**禁止**：无测试证据说完成；伪造价格/名额；绕过订单事务/券规则；把 P2 做成新订单类型。
