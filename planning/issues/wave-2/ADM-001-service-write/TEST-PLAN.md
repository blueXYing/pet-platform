# ADM-001 服务写入方切片 — 测试计划（v0.2，随裁决修订）

日期：2026-09-22。分支 `codex/adm001-service-write-20260922`。
前置：[CCR service-write-proposal.md v0.2](../../../ccr/CCR-W2-API-001/service-write-proposal.md) 已原则批准（[决定回执](../../../ccr/CCR-W2-API-001/service-write-decisions.md)）；实现切片交付时逐项报告 PASS/FAIL/NOT_EXECUTED 及所用规范、数据库、应用提交。
编号风格对齐 [WAVE_2_TEST_ACCEPTANCE.md](../../WAVE_2_TEST_ACCEPTANCE.md)。**新编号 W2-SVCW-001～009 为本切片验收条目，登记进 WAVE_2_TEST_ACCEPTANCE.md 由该文件唯一 Writer 执行，本切片不改该共享文件。**

## 0. v0.2 修订说明

按用户 2026-09-22 裁决新增/修订：W2-SVCW-004 由"读侧不破坏"改为"读侧兼容回归"（REVIEWING/REJECTED 是已知状态：不可见→404，不触发 503）；新增 W2-SVCW-008（消费者封面展示授权）与 W2-SVCW-009（审核事件 Outbox 落库）；§4 边界中"审核结果通知/事件/Outbox 不实现"改为"事件+Outbox 本切片交付，通知消费侧（角色E）未接通前完整审核流程不标完成"。

## 1. 验收条目

| ID | Owner Issue | 触发与预期 |
|---|---|---|
| W2-SVCW-001 | ADM-001（服务写入切片） | **状态机全路径**：create→DRAFT 可重复编辑；DRAFT/REJECTED/OFFLINE --submit--> REVIEWING（submitted_at/submission_no 递增）；REVIEWING --APPROVE--> ACTIVE；REVIEWING --REJECT(opinion 10-500)--> REJECTED；REJECTED --修改重提--> REVIEWING（历史驳回记录保留、重提次数+1）；ACTIVE --商家 offline--> OFFLINE；ACTIVE --运营 force-offline(reason)--> OFFLINE 并落治理审计；OFFLINE --重新 submit--> REVIEWING（防绕审改价）；**商家任何动作不得产生 ACTIVE**。非法转移（对 ACTIVE/REVIEWING 编辑、对非 DRAFT/REJECTED/OFFLINE 提交、对非 ACTIVE 下架、对非 REVIEWING 决定、对非 ACTIVE 强制下架）→ 409 SERVICE_STATE_NOT_ALLOWED；expectedVersion 过期 → 409 COMMON_CONFLICT，不自动覆盖 |
| W2-SVCW-002 | 同上 | **幂等重放**（23 号 §3～§7）：全部六命令同 X-Request-Id 同参重放返回首次成功回执（200，业务副作用仅一次：版本/决定/审计不重复累加）；同 requestId 异参 → 409 IDEMPOTENCY_KEY_CONFLICT；首次创建 201/重放 200；跨主体/跨命令同 requestId 按 scope 隔离 |
| W2-SVCW-003 | 同上 | **权限反例矩阵**（SCOPE-VERIFY A7 可构造项）：无会话 401；无归属关系/跨商家 serviceId → 404（防枚举，与不存在同响应）；运营无动作码 → 403；运营端不存在商家写路由（仅 decision/force-offline）；消费者打 admin 面 → 会话隔离拒绝；商家/门店不可经营（DENIED/LIMITED 事实）→ 409 SERVICE_STATE_NOT_ALLOWED |
| W2-SVCW-004 | 同上（联动 SVC-001） | **读侧兼容回归（v0.2）**：写入方产生的 REVIEWING/REJECTED 对 C 端不可见（列表不出现、详情 404，与 OFFLINE/DRAFT 同响应不可区分）；已知状态集合扩为五值后不触发 503（快照/资格内部查询对两新状态正常返回事实）；仅 APPROVE 后 ACTIVE 且四条件合取通过才可见；checkBookable 对非 ACTIVE 维持 SERVICE_OFFLINE 不新增码；ServiceQueryHttpTest 既有场景不破坏 |
| W2-SVCW-005 | 同上 | **审核与治理审计**：驳回 opinion 10-500 边界（9/501 拒绝、REJECT 缺失 → 400 SERVICE_REVIEW_REASON_REQUIRED）；决定表 append-only 且含 operator/authz_version/scope_version/request_id/trace_id；force-offline 必填 reason 并落 service_governance_action；运营列表筛选（status/分类/商家）与 SLA 剩余按 submitted_at 计算；重提次数=决定表 REJECT 计数 |
| W2-SVCW-006 | 同上 | **字段与校验**：ID 全 String（>2^53 往返）；金额两位小数 String、price>0、listPrice≥price、MoneyCodec 词法；serviceName 2-50 字；categoryId 命中 ENABLED 字典（禁用类目提交→400）；fulfillmentType 枚举；durationMinutes>0；coverAssetId 提交审核时必填（DRAFT 可缺，缺失提交→400）；applicablePetTypes ∈ {DOG,CAT,EXOTIC,ALL}（ALL 互斥）；未带/畸形 X-Request-Id → 400 |
| W2-SVCW-007 | 同上 | **事实失败关闭**：商家事实不可读/矛盾 → 写命令 503 COMMON_DEPENDENCY_UNAVAILABLE，不降级放行或伪 404；封面资产端口不可用 → 提交 503，不产生半写状态 |
| W2-SVCW-008 | 同上 | **消费者封面展示授权（v0.2 新增）**：C 端详情/列表 cover 字段（coverAssetId/coverUrl/coverUrlExpiresAt）仅在服务可见（ACTIVE+四条件）且有封面时返回；REVIEWING/REJECTED/OFFLINE/DRAFT → 404 不携带；无封面存量行 → cover 为 null 不报错；签名 URL 经端口产生（测试桩可验证调用与授权时序），端口不可用且有封面 → 503 失败关闭 |
| W2-SVCW-009 | 同上 | **审核事件 Outbox（v0.2 新增）**：APPROVE/REJECT 决定事务内 integration_event_outbox 落库（eventType=ServiceReviewedEvent.v1、aggregateType=SERVICE、aggregateId=serviceId、载荷字段逐一断言：serviceId/serviceName/merchantId/storeId/submissionNo/decisionType/opinion/decidedAt）；REJECT opinion 进入载荷；同 requestId 幂等重放不产生第二条事件；force-offline 不产生事件（剩余问题待裁决） |

对应既有条目：ARCH001～005 持续通过（biz 不依赖 biz：service-biz 仅依赖 pet-merchant-api/pet-event-api；不跨模块 Repository）；W2-SVC-001～003 不回退。

## 2. 测试形态与执行条件（实现切片时）

- **HTTP 集成测试**（真实 MySQL/Redis + 真实登录链，先例 `ServiceQueryHttpTest`、`MerchantApplicationLifecycleHttpTest`）：
  - 夹具：真实申请→审批 APPROVED→签署链产生可经营商家/门店/主账号会话；服务数据**经写入方 HTTP 产生**（替代读切片时期的 SQL 播种——这是本切片存在意义，需在测试报告显式区分）；admin 侧用 admin-auth 登录链 + 动作码授权。
  - 封面资产：SERVICE_COVER 上传管线由角色B另行交付，本切片集成测试以 `ServiceCoverAssetPort`/`ServiceCoverUrlPort` 测试桩覆盖校验与签名授权逻辑（端口形状对齐已批 resolveOwned/PresignedAssetUrlService）；B 合入后补真实上传链回归（登记遗留）。
  - 反例构造：无归属会话、撤动作码、SQL 直改 status 破坏、商家状态破坏（SQL 改 merchant/application 状态构造 DENIED/LIMITED）。
- **门控与环境**：随机端口独立 boot 实例；MySQL 33452/Redis 16383 共享环境，跑前检查并发；模块单测 + boot 集成 + ARCH 守卫随 CI 六项。
- **不声明**：不把本切片通过声称为完整"发布→看到→预约"E2E；通知消费侧（角色E）未接通前不将完整审核流程标完成。

## 3. 覆盖矩阵（用例 ↔ 需求）

| 用例组 | PRD/契约依据 |
|---|---|
| 状态机全路径（W2-SVCW-001） | 商家端 §6.3 / 运营端 §7.2 逐行；HTTP10 §3.3 存量快照不受下架影响 |
| 防绕审改价（OFFLINE 重提过审、ACTIVE 不可编辑） | 运营端 §5.2 衔接点、§6.2.4 业务规则 |
| 权限（主账号专属、运营不代操作、防枚举） | 商家端 §5.6 矩阵；运营端 §3.3 边界；27 号 §3 404 映射；SVC-D1b |
| 幂等（W2-SVCW-002） | 23 号 §3～§7；10 号 §2.3；27 号 §7 |
| 审核记录/驳回原因/重提/SLA（W2-SVCW-005） | 运营端 §6.2.4（意见 10-500、SLA 24h、驳回记录、强制下架）；§6.1 SLA 规则 |
| 金额/ID/字段词法（W2-SVCW-006） | 23 号 §1/§2；10 号 §2.5/§2.7；27 号 §3 |
| 读侧兼容回归（W2-SVCW-004） | 07 号 §5.1.1；10 号 §3.3.1；SVC-D1/D1b；裁决第 2 条 |
| 封面展示授权（W2-SVCW-008） | 裁决第 3 条；PRD 封面必填+内容安全 |
| 审核事件 Outbox（W2-SVCW-009） | 裁决第 4/5 条；SSOT §一"审核必须保留站内消息"；Event08 ServiceReviewedEvent.v1 |

## 4. 明确不测/不实现（本轮边界）

- 售罄态、硬删除、批量通过、运营类目 CRUD、销量/预约量统计（SCOPE-VERIFY §10）。
- 通知消费侧（MERCHANT 收件箱、可靠消费、权限隔离）＝角色E；本切片只断言 Outbox 落库。
- 强制下架是否通知商家＝剩余问题，本轮不实现不测试。
- SERVICE_COVER 真实上传/扫描链＝角色B；本切片以端口桩覆盖（遗留登记）。
- 排期/库存锁/订单创建/支付路径（AGENTS 硬规则禁止提前）。

