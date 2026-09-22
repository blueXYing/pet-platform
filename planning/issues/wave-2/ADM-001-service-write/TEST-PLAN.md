# ADM-001 服务写入方切片 — 测试计划（第一阶段规划）

日期：2026-09-22。分支 `codex/adm001-service-write-20260922`。
**本文件仅定义测试，不代表任何测试已运行**（本轮为文档与核验阶段；实现切片交付时逐项报告 PASS/FAIL/NOT_EXECUTED 及所用规范、数据库、应用提交）。
前置：[CCR 草案 service-write-proposal.md](../../../ccr/CCR-W2-API-001/service-write-proposal.md)（DRAFT 未批）获批后按最终决定执行；若裁决改变状态机/错误码/路由，本计划同步修订。
编号风格对齐 [WAVE_2_TEST_ACCEPTANCE.md](../../WAVE_2_TEST_ACCEPTANCE.md)（W2-域-序号 | Owner Issue | 触发与预期）。**新编号 W2-SVCW-001～007 为提案，登记进 WAVE_2_TEST_ACCEPTANCE.md 由该文件唯一 Writer 执行，本轮不改动该共享文件。**

## 1. 验收条目（提案）

| ID | Owner Issue | 触发与预期 |
|---|---|---|
| W2-SVCW-001 | ADM-001（服务写入切片） | **状态机全路径**：create→DRAFT 可重复编辑；DRAFT/REJECTED/OFFLINE --submit--> REVIEWING（submitted_at/submission_no 递增）；REVIEWING --APPROVE--> ACTIVE；REVIEWING --REJECT(opinion 10-500)--> REJECTED；REJECTED --修改重提--> REVIEWING（历史驳回记录保留、重提次数+1）；ACTIVE --商家 offline--> OFFLINE；ACTIVE --运营 force-offline(reason)--> OFFLINE 并落治理审计；OFFLINE --重新 submit--> REVIEWING（防绕审改价）；**商家任何动作不得产生 ACTIVE**。非法转移（对 ACTIVE/REVIEWING 编辑、对非 DRAFT/REJECTED/OFFLINE 提交、对非 ACTIVE 下架、对非 REVIEWING 决定、对非 ACTIVE 强制下架）→ 409 SERVICE_STATE_NOT_ALLOWED（或裁决定案码）；expectedVersion 过期 → 409 COMMON_CONFLICT，不自动覆盖 |
| W2-SVCW-002 | 同上 | **幂等重放**（23 号 §3～§7）：全部六命令同 X-Request-Id 同参重放返回首次成功回执（200，业务副作用仅一次：版本/决定/审计不重复累加）；同 requestId 异参 → 409 IDEMPOTENCY_KEY_CONFLICT；首次创建 201/重放 200；提交前/后崩溃绑定恢复（RESERVED→业务事务）不重做旧业务；撤权后重放不返回旧 data；跨主体/跨命令同 requestId 按 scope 隔离 |
| W2-SVCW-003 | 同上 | **权限反例矩阵**（SCOPE-VERIFY A7 P1-P10 逐项）：无会话 401；消费者/无归属关系/跨商家 serviceId → 404 COMMON_NOT_FOUND（与不存在同响应，防枚举）；非 OWNER 成员关系 → 403；运营无动作码 → 403；运营端不存在商家写路由（仅 decision/force-offline，PRD"不代商家上下架服务"）；消费者打 admin 面 → 会话隔离拒绝 |
| W2-SVCW-004 | 同上（联动 SVC-001） | **读侧不破坏回归**：写入方产生的 REVIEWING/REJECTED 对 C 端不可见（列表不出现、详情 404 SERVICE_NOT_FOUND）；仅 APPROVE 后 ACTIVE 且商家/门店/新单资格四条件合取通过才可见；改价后 C 端再查得新值、旧快照副本不变（延续 W2-SVC-002 语义）；内部 checkBookable reasonCodes 语义不变 |
| W2-SVCW-005 | 同上 | **审核与治理审计**：驳回 opinion 10-500 边界（9/501 拒绝、REJECT 缺失 → SERVICE_REVIEW_REASON_REQUIRED 或定案码）；决定表 append-only 且含 operator/authz_version/scope_version/request_id/trace_id；force-offline 必填 reason 并落 service_governance_action；运营列表筛选（status/分类/商家）与 SLA 剩余时间按 submitted_at 计算、超 24h 标红数据正确；重提次数=决定表 REJECT 计数 |
| W2-SVCW-006 | 同上 | **字段与校验**：ID 全 String（>2^53 往返）；金额两位小数 String、price>0、listPrice≥price（低于拦截）、MoneyCodec 词法（128.0/128.00 合法、128.000 拒绝）；serviceName 2-50 字；categoryId 必须命中 ENABLED 字典（禁用类目提交 → 400）；fulfillmentType 枚举；durationMinutes>0；coverAssetId 提交审核时必填（DRAFT 可缺）；applicablePetTypes ∈ {DOG,CAT,EXOTIC,ALL}（ALL 互斥）；未知参数/未知字段/重复参数 → 400；未带/畸形 X-Request-Id → 400 |
| W2-SVCW-007 | 同上 | **事实失败关闭**：getFacts 依赖不可读/事实损坏（如 membership 缺失、状态未知枚举）→ 写命令 503 COMMON_DEPENDENCY_UNAVAILABLE，不得降级放行或伪 404；商家/门店非可经营（OFFLINE/FROZEN/未签约等，事实明确）→ 409（SVCW-D5 定案语义），不产生半写状态 |

对应既有条目：ARCH001～005 持续通过（biz 不依赖 biz：service-biz 仅依赖 pet-merchant-api；不跨模块 Repository）；W2-SVC-001～003 不回退。

## 2. 测试形态与执行条件（实现切片时）

- **HTTP 集成测试**（真实 MySQL/Redis + 真实登录链，先例 `ServiceQueryHttpTest`、`MerchantAdmissionHttpTest`、`MerchantApplicationLifecycleHttpTest`，backend/pet-boot/src/test/java/com/petplatform/boot/auth/）：
  - 夹具：真实申请→审批 APPROVED→签署链（复用读切片夹具）产生可经营商家/门店/主账号会话；服务数据**首次可经写入方 HTTP 产生**（替代读切片时期的 SQL 播种——这是本切片存在意义，需在测试报告显式区分）；admin 侧用 admin-auth 登录链 + 动作码授权（参照 MerchantApplicationAdminController 测试形态）。
  - 反例构造：非 OWNER（无关系/他商家）、撤动作码、状态破坏（SQL 直改 status 非法值验证失败关闭）。
- **门控与环境**：不占用共享端口（本地测试用随机端口/独立 boot 实例）；模块单测（service-api/biz）+ boot 集成 + ARCH 守卫随 CI 六项。
- **不声明**：不把本切片通过声称为完整"发布→看到→预约"E2E（排期/订单未实现）；不运行生产；不改权威文档。

## 3. 覆盖矩阵（用例 ↔ 需求）

| 用例组 | PRD/契约依据 |
|---|---|
| 状态机全路径（W2-SVCW-001） | 商家端 §6.3 / 运营端 §7.2 逐行；HTTP10 §3.3 存量快照不受下架影响 |
| 防绕审改价（OFFLINE 重提过审、ACTIVE 不可编辑） | 运营端 §5.2 衔接点、§6.2.4 业务规则 |
| 权限（主账号专属、运营不代操作、防枚举） | 商家端 §5.6 矩阵；运营端 §3.3 边界；27 号 §3 404 映射；SVC-D1b |
| 幂等（W2-SVCW-002） | 23 号 §3～§7；10 号 §2.3；27 号 §7 |
| 审核记录/驳回原因/重提/SLA（W2-SVCW-005） | 运营端 §6.2.4（意见 10-500、SLA 24h、驳回记录、强制下架）；§6.1 SLA 规则 |
| 金额/ID/字段词法（W2-SVCW-006） | 23 号 §1/§2；10 号 §2.5/§2.7；27 号 §3 |
| 读侧回归（W2-SVCW-004） | 07 号 §5.1.1；10 号 §3.3.1；SVC-D1/D1b |

## 4. 明确不测/不实现（本轮边界）

- 售罄态、硬删除、批量通过、运营类目 CRUD、审核结果通知/事件/Outbox、销量/预约量统计（SCOPE-VERIFY §10）——不编写对应"通过"声明，相关编号不注册。
- 排期/库存锁/订单创建/支付路径（AGENTS 硬规则禁止提前）。
