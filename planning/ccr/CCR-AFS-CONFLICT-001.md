# CCR-AFS-CONFLICT-001 — 售后确定版本冲突与幂等忙区分

状态：APPROVED / IMPLEMENTED_DEFAULT_OFF。2026-10-01。Owner：AFS Contract Owner；审阅人 @blueXYing。用户在两项具体 CCR 的批准请求后明确回复“批准”，授权错误码及相关契约增量；不含 PR 合并或生产启用。

Error12/API23 的 COMMON_CONFLICT 同时表示 CAS/状态冲突与有界幂等争锁忙；Contract51 无可区分字段。AfterSaleService 的 version、finalSetVersion、动作执行前 authz 复验都沿此码返回。前端无法从相同四字段 data:null 判断原操作是否确定未执行，因此不能自动退休原 UUID 再改写。

已批准在不改变 HTTP409、状态机及 requestId 规则的前提下，为已确认的 case version 不匹配与 finalSetVersion 不匹配提供稳定独立业务码 `AFTERSALE_VERSION_CONFLICT` / `AFTERSALE_FINAL_SET_CONFLICT`，幂等忙继续 `COMMON_CONFLICT`。Authz 复验独立于版本比较：当前身份/权限拒绝使用既有认证/授权码，授权版本变化仍通用冲突，不复用纯 CAS 提示。冻结 Error12/Contract51/OpenAPI11 的码、边界及重放语义，并以真实并发测试证明确定拒绝不产生副作用；无 Schema/Event/Scheduler 或产品规则变化。

批准前未新增码，真实 CAS 可能长期锁定旧期望版本。批准后两个确定码仅代表当次命令已验证当前权限并确定未提交业务作用，客户端只退休该次完整payload+UUID快照，重读卷宗/资格，并使旧人工确认及P4历史核对失效后才允许手动新提交。`COMMON_CONFLICT` / `IDEMPOTENCY_*` /429仍保留原payload+UUID、仅显式重试；403/404当前可见性不能证明旧命令未成功。成功原UUID重放仍先于当前版本校验。最终行为证据见本批CCR交付与独立QA记录。

实现及三端恢复已通过相关本地测试，真实并发败方、P4过期证明、原成功UUID重放和撤权优先均有独立验收。实际外部RBAC写等待AFS授权锁提交后生效；同事务revision变化的回滚另以明确FAULT_INJECTION验证，不能混称外部撤权竞态。详见[实施记录](../progress/2026-10-01/aftersale-pages/CCR-IMPLEMENTATION.md)与[独立QA](../progress/2026-10-01/aftersale-pages/CCR-QA-HANDOFF.md)。当前head CI和页面真实联调/真机/VIS独立验收，PR保持草稿。
