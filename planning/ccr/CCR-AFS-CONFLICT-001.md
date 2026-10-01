# CCR-AFS-CONFLICT-001 — 售后确定版本冲突与幂等忙区分

状态：PROPOSED / NOT_IMPLEMENTED。2026-10-01。Owner：AFS Contract Owner；审阅人 @blueXYing。

Error12/API23 的 COMMON_CONFLICT 同时表示 CAS/状态冲突与有界幂等争锁忙；Contract51 无可区分字段。AfterSaleService 的 version、finalSetVersion、动作执行前 authz 复验都沿此码返回。前端无法从相同四字段 data:null 判断原操作是否确定未执行，因此不能自动退休原 UUID 再改写。

建议在不改变 HTTP409、状态机及 requestId 规则的前提下，为已确认的 case version 不匹配与 finalSetVersion 不匹配提供稳定独立业务码（建议 AFTERSALE_VERSION_CONFLICT / AFTERSALE_FINAL_SET_CONFLICT），幂等忙继续 COMMON_CONFLICT。Authz 复验必须独立说明确定拒绝与未知处理中，不复用纯 CAS 提示。冻结 Error12/Contract51/OpenAPI11 的码、边界及重放语义，并以真实并发测试证明确定拒绝不产生副作用；无 Schema/Event/Scheduler 或产品规则变化。

本批未新增码或改后端。COMMON_CONFLICT/IDEMPOTENCY_* /429 保留原 payload+UUID，仅允许显式重试；明确 AFTERSALE_* 业务拒绝白名单可退休并重读。真实 CAS 仍可能长期锁定旧期望版本，未宣称该分支已完成交互验收；须 CCR 冻结后补齐安全解锁。
