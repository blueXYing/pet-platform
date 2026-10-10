# Review Appeal Contract v0.1（评价申诉链路，REV-002）

状态：APPROVED_PENDING_REVIEW / IMPLEMENTATION_IN_PROGRESS，2026-10-10；不表示测试已通过或生产已开放。[CCR-REVIEW-APPEAL-001](../../planning/ccr/CCR-REVIEW-APPEAL-001.md) 记录范围裁决。产品规则唯一来源 [SSOT §11.3](../00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md)：商家可对违规评价申诉、**每条评价最多申诉一次**、评价申诉与售后复审无关（本切片不实现任何售后复审能力）。存储复用 [06号 §10](../03-database/06-核心数据库Schema-v0.1.sql) 既有 `review` / `review_appeal` 表，**零 Schema 变化**。

## 1. 边界与身份

本批为评价申诉最小闭环：M 端 OWNER 读本店评价（含申诉状态投影）并发起申诉；ADMIN_WEB 运营读申诉并做终局裁决。无评价回复（reply）、无评价删除、无售后复审、无二次申诉、无 C 端申诉状态展示（SSOT 未要求，C 端无公开评价读面）。`PROCESSING` 为 06号 保留状态值，本切片不产生：申诉创建即 `SUBMITTED`，裁决一次写入 `APPROVED`/`REJECTED` 终局。

- `/api/v1/merchant/reviews**`：MINIAPP Bearer，严格当前 OWNER（MER `requireOwner`/`requireOwnerRead` 在域事务与受保护重放内复验），不能仅因同一 USER 是买家而放行。
- `/api/v1/admin/review-appeals**`：ADMIN_WEB Bearer + 动作码 `review.appeal.read` / `review.appeal.decide`（AUTH 域 `AdminActionCheckQuery`，资源类型 `REVIEW_APPEAL`；V1 单运营、无内部双人审批，超管默认全权——AGENTS.md 已批准运营权限补充）。无客户端 context/actor 业务授权字段。

## 2. 路由清单

前缀 `/api/v1`。

| Method | Path | 权限/用途 |
|---|---|---|
| GET | /merchant/reviews | OWNER 本店评价分页（含申诉状态投影） |
| GET | /merchant/reviews/{reviewId} | OWNER 单条评价 + 申诉详情 |
| POST | /merchant/reviews/{reviewId}/appeal | OWNER 一次性申诉（201 首次 / 200 受保护重放） |
| GET | /admin/review-appeals | review.appeal.read 申诉分页（status 过滤） |
| GET | /admin/review-appeals/{appealId} | review.appeal.read 申诉详情（含被诉评价事实） |
| POST | /admin/review-appeals/{appealId}/decision | review.appeal.decide 终局裁决（200） |

## 3. 公共输入与回执

所有 POST 要求唯一 `X-Request-Id` 完整 UUID 原值（API23 幂等五元组：14号 `command_idempotency`，namespace `review.appeal` / `review.appeal.decide`，scope `REVIEW:{reviewId}` / `APPEAL:{appealId}`）。严格 JSON（未知/重复字段、附加文档 400），主 body ≤32768 字节。路径 ID 为正十进制 String，HTTP/JSON 中 ID 全 String（Snowflake BIGINT 仅库内）。

| DTO | 字段 |
|---|---|
| Appeal（body） | reason：必填 String，trim 后非空、1..1000 码点（06号 reason `NOT NULL VARCHAR(1000)`），拒绝孤立 surrogate |
| Decision（body） | decisionType：`APPROVED`/`REJECTED`（06号 已登记终态，仅此两值）；reason：必填 1..1000 码点 |
| AppealReceipt | appealId, reviewId, status, createdAt |
| DecisionReceipt | appealId, reviewId, status, decisionType, decisionReason, decidedAt, reviewVisibility |

MerchantReviewSummary（列表）：`reviewId, orderId, storeScore, serviceScore, staffScore, compositeScore, scoreIncluded, visibilityStatus, content, createdAt, appealStatus, appealId`；`appealStatus` 为 `SUBMITTED/APPROVED/REJECTED` 或 null（未申诉；`PROCESSING` 保留值可解码但本切片不产生）。MerchantReviewDetail 在 Summary 上加 `appealReason, appealCreatedAt, decisionReason, decidedAt`（未申诉全 null）。ReviewScore 输出为一位小数字符串（如 `"4.0"`，与 06号 DECIMAL 一致）；compositeScore 为内核 40/40/20 事实（SSOT §11.1）。

AppealSummary（admin 列表）：`appealId, reviewId, merchantId, storeId, orderId, status, reason, createdAt, decidedAt`；AppealDetail 再加被诉评价事实 `review`（storeScore/serviceScore/staffScore/compositeScore/scoreIncluded/visibilityStatus/content/createdAt）与 `decisionReason, decidedBy`。分页参数仅 `page`（1..10000 默认1）、`pageSize`（1..50 默认20）、`status`（admin 列表可空=全部）；M 列表另必须 `merchantId+storeId` 且等于会话工作区坐标。排序固定 `created_at DESC, id DESC`（M 按评价 created_at；admin 按申诉 created_at）。

JSON envelope 固定 `code/message/data/traceId`；成功 `SUCCESS/ok`，失败 data=null。

## 4. 规则与错误映射

- 一次性申诉：已存在任何状态申诉 → 409 `REVIEW_APPEAL_ALREADY_USED`（12号 §11 已登记）。`uk_review_appeal_once` 物理兜底，竞态下同码。
- 防枚举：评价不存在或非本店 → 404 `REVIEW_NOT_FOUND`（M 面统一，不区分两种事实）；admin 申诉不存在 → 404 `COMMON_NOT_FOUND`。
- 裁决终局：非 `SUBMITTED`（已裁决）再裁决 → 409 `COMMON_CONFLICT`；成功裁决 `APPROVED` 同事务写 `review.visibility_status='HIDDEN'`（评价停止公开展示；评分统计口径沿用 score_included，本切片不改既有聚合索引语义），`REJECTED` 维持 `PUBLISHED`。
- 幂等：同 key 同参重放返回原回执（重验当前权限）；同 key 异参 409 `IDEMPOTENCY_KEY_CONFLICT`；请求锁忙 409 `COMMON_CONFLICT`。
- 主状态映射：400 参数；401 会话；403 权限（含 M 面 OWNER/ACTIVE 门禁、O 面动作码）；404 防枚举；409 一次性/终局/幂等；503 依赖不可证；意外 500 安全包装。无新增错误码。
- 申诉写面沿 MER `requireOwner` 写语义（FROZEN 拒绝；OFFLINE 沿既有写规则可写）；读面沿 `requireOwnerRead` 读语义（ACTIVE/OFFLINE/FROZEN 存量可读）。写面拒绝与防枚举统一按 404 `REVIEW_NOT_FOUND` 收口（不区分"冻结/非本店/不存在"）。

## 5. 开关与装配

`pet.review.appeal.enabled` / `pet.review.appeal.http.enabled` 默认 false。[硬] guard：appeal.enabled 要求 `pet.review.enabled`（评价内核/06号 表基础）+ `pet.auth.c.enabled`（MINIAPP 会话与 MER OWNER 权限）+ `pet.auth.admin.enabled`（ADMIN_WEB 会话）+ `pet.schedule.protection.enabled`（门店守卫，MER 权限复核在守卫事务内执行）；appeal.http.enabled 要求 appeal.enabled。关闭时控制器/映射不注册（安全链 catch-all deny 语义）。登记见 [21号 部署开关组合清单](../08-engineering/21-部署开关组合清单-v1.0.md)。

## 6. 验收要点

真实隔离 MySQL/Redis + 真实三端会话：一次性申诉（重复 409）、owner 校验与防枚举（买家/他人评价 404）、开关默认关（装配缺席测试）、幂等重放（201/200 同回执、异参 409）、裁决后终局（改判 409、APPROVED→visibility HIDDEN、REJECTED→维持）、M 列表/详情申诉状态投影、admin 列表 status 过滤与详情事实、动作码缺失 403。
