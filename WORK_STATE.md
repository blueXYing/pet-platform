# Work State

PROJECT: 宠物平台 V1.0
STATE_VERSION: 25.0
UPDATED_AT: 2026-09-29
CURRENT_PHASE: W2_WAVE_IN_PROGRESS
CURRENT_STATUS: PR93_MERGED__VER_CREDENTIAL_CONTRACT_PREPARED_FOR_REVIEW
VERIFIED_BASELINE: develop 324cba1243183a4cfc9cfef46373121848bdd6a8（PR93已合并；合并CI36539276218六项通过，112份后端报告698测试、零失败/错误/跳过）
NEXT_PHASE: VER-001真实凭证内核与改期失效；V1/V2新契约待确认
NEXT_PHASE_APPROVED: 用户已授权“93合入”并要求“开始下一步”。不重复申请PR93合并或既定业务规则批准；新发现的刷新写协议与风控歧义尚未明确裁决，不视为已批准。

## 当前事实

- [PR93](https://github.com/blueXYing/pet-platform/pull/93)已合入develop，旧待审状态保留在[历史记录](planning/history/WORK_STATE_BEFORE_20260929_VERIFICATION.md)。
- 合并CI六项成功，已下载解析112份Surefire XML、698测试零失败/错误/跳过；见[CI](planning/progress/2026-09-29/verification-foundation/pr93-merge-ci.json)和[汇总/哈希](planning/progress/2026-09-29/verification-foundation/pr93-merge-backend-summary.json)。这是改期基线证明，不是新核销业务验收。
- 在干净、无在途任务的既有隔离worktree创建`codex/verification-foundation-20260929`，基于PR93合并版本；原用户目录未改动。
- VER模块只有骨架和46号fence接口，SQL06只有核销记录/尝试表，没有真实动态码生命周期；不能把写一条成功标记称为真实旧码失效。
- 已核对原始PRD，形成[凭证基础V1/V2方案](planning/ccr/CCR-W2-API-001/verification-credential-proposal.md)和[准备记录](planning/progress/2026-09-29/verification-foundation/PREPARATION.md)，包含字段、事务、失效证明和12组验收计划；PROPOSED_REQUIRES_REVIEW。
- 五分钟有效期、服务开始后仍可核销、退款单创建后禁止核销、仅售后不失效等直接沿用；新决定为读/刷新协议与第三次/第四次失败及锁作用域。

## 下一步

1. V1/V2确认后同步正式Contract/Schema及必要SSOT差异，实现真实凭证内核、改期fence和MySQL并发/故障测试。
2. 默认关闭，完成后提交PR审阅；不自动合并、生产迁移或启用。
3. 后续补商家核销身份、完整OrderOperationGuard/markVerified及未履约售后失效，再接公开版本读侧、HTTP/小程序；不把主账号userId伪装为staffId。

## 验收与限制

本轮新核销业务NOT_EXECUTED。完整VER-001/VER-002/ORD-003保持未完成；正式SSOT/API/Schema/业务源码及运行开关未修改。未执行生产迁移、正式支付退款渠道或外部通知；不以既有698项报告冒充新凭证功能验收。
