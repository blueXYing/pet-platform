# CCR-REVIEW-APPEAL-001：评价申诉链路（REV-002）

状态：PROPOSED / IMPLEMENTED_LOCAL_VERIFIED（本工作树交付，等待用户审阅；未授权合并或生产部署）。

## 问题与已有依据

SSOT §11.3（[01-SSOT](../../docs/00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md) :575）封板规则：商家可以对违规评价发起申诉；**每条评价最多申诉一次**；评价申诉与售后复审是两个概念（售后无复审，红线：本切片不得实现任何售后复审能力）。

已有依据：

- 内部契约 [07 §14.2](../../docs/04-api/07-内部API-Contract-v0.6.md)：`ReviewCommandApi.appeal(ReviewAppealCommand)` 签名已登记（"评价申诉每条最多一次；该机制与售后复审无关"），但命令/回执字段未定义、内核未实现（REV-001 注释明确 appeal 属 REV-002）。
- HTTP 草案 [10 §4.13/§5.7](../../docs/04-api/10-HTTP-API-Contract-v0.4.md)：`GET /api/v1/merchant/reviews`、`GET /api/v1/merchant/reviews/{reviewId}`、`POST /api/v1/merchant/reviews/{reviewId}/appeal`、`GET /api/v1/admin/review-appeals`、`GET /api/v1/admin/review-appeals/{appealId}`、`POST /api/v1/admin/review-appeals/{appealId}/decision` 路由已列草案，未定义请求/回执/错误映射。
- 错误码 [12 §11](../../docs/04-api/12-Error-Code-Registry-v0.5.md)：`REVIEW_APPEAL_ALREADY_USED`（每条评价最多申诉一次）、`REVIEW_NOT_FOUND` 已登记。
- 存储 [06 §10](../../docs/03-database/06-核心数据库Schema-v0.1.sql)：`review_appeal` 表已存在（`uk_review_appeal_once(review_id)` 物理一次申诉约束、status `SUBMITTED/PROCESSING/APPROVED/REJECTED`、reason `NOT NULL VARCHAR(1000)`、decision_reason/decided_by/decided_at），`review.visibility_status`（`PUBLISHED/HIDDEN`）已存在。**本切片零 Schema 变化**。

缺 Contract：申诉命令/裁决命令/读写 DTO 字段、HTTP 状态映射、权限口径、开关组合。按 AGENTS.md「Contract 缺失走 CCR」建本 CCR + [56号新契约](../../docs/04-api/56-Review-Appeal-Contract-v0.1.md)。

## 最小变更提案（不新增产品规则）

产品规则只取 SSOT §11.3 与 Schema06 既有事实，不自创：

1. **申诉**：OWNER 对本店 PUBLISHED 评价提交 reason（必填、trim 后非空、≤1000 码点，来自 06号 `NOT NULL VARCHAR(1000)`）；`uk_review_appeal_once` + 服务层双保险；重复申诉（含异 requestId）409 `REVIEW_APPEAL_ALREADY_USED`。
2. **运营裁决**：单运营（AGENTS.md 已批准运营权限补充；超管全权），动作码 `review.appeal.read` / `review.appeal.decide`；裁决结果类型只用 06号 已登记的 `APPROVED`（申诉成立 → `review.visibility_status='HIDDEN'`，评价停止公开展示）与 `REJECTED`（维持展示）；decided_by/decided_at/decision_reason 一次写入，**终局、不可改判、不可再诉**（每条评价一次申诉已封顶，天然无复审）。`PROCESSING` 为 06号 保留态，本切片不使用（SUBMITTED → 裁决终局，两步）。
3. **商家读面**：`GET /merchant/reviews`（OWNER 本店分页，含每条评价的申诉状态投影）+ `GET /merchant/reviews/{reviewId}`（含申诉详情），草案 §4.13 的 `reply` 路由不在本切片（评价回复是独立能力，未列 SSOT §11.3）。
4. **运营读面**：申诉分页（status 过滤）+ 详情（含被诉评价事实）+ 裁决命令。
5. 幂等：申诉/裁决均走 14号 `command_idempotency` 五元组绑定（23号 补充），首报 201 / 受保护重放 200；HTTP ID 全 String。
6. C 端：SSOT §11.3 未要求向用户展示申诉影响，且 C 端无公开评价读面（REV-001 只交付资格+创建），本切片不动 C 端（PR 披露）。

## 权限与事务

- M 面：路由 `/api/v1/merchant/**` → MINIAPP Bearer；内核经 MER `MerchantOrderAuthorityApi.requireOwner`（写）/`requireOwnerRead`（读）复核 OWNER 与门店坐标；非本店评价与不存在评价统一 404 防枚举。
- O 面：`ADMIN_WEB` Bearer + `review.appeal.read/decide` 动作码（AUTH 域 `AdminActionCheckQuery`，资源范围 `REVIEW_APPEAL`），复验在执行事务内；登记进 `AdminPermissionEvaluator.DEPLOYED_ACTIONS`（超管自动全权）。
- biz 不依赖 biz：pet-review-biz 以端口接口（`ReviewAppealPorts`）承接权限，boot 装配适配器（同 Contract50/51 先例）；持久层全在本模块 Mapper XML。
- 开关：`pet.review.appeal.enabled` / `pet.review.appeal.http.enabled` 默认 false；硬 guard：appeal 要求 `pet.review.enabled` + `pet.auth.c.enabled`（M 会话/MER 权限）+ `pet.auth.admin.enabled`（O 会话）。

## 实施与验收清单

- 56号契约 + OpenAPI11 同步 + 07号 §14 实现状态注记 + 10号 §4.13/§5.7 指针 + 12号 §11 映射注记 + 21号 开关清单。
- 后端真实 MySQL/HTTP 验收：一次性申诉（重复 409）、owner 校验/防枚举（404）、开关默认关、幂等重放（201/200 同回执）、裁决后终局（改判 409、APPROVED→HIDDEN）。
- M 前端：评价管理页（列表+申诉弹层+申诉状态）+ 工作台入口；admin 运营网页：申诉裁决列表/详情页。
- 不实现：售后复审/二次申诉、评价回复（reply）、C 端申诉状态展示、评价删除、PROCESSING 中间态流转、公开评价列表。任何偏离回本 CCR 审阅。
