# PR95收尾与普通退款/售后接续准备

日期：2026-09-30。基线：`eb5d16ffff5a2db2799545b4f5fd490e1431f99c`。分支：`codex/refund-aftersale-prep-20260930`。

## 本轮已完成的实质工作

- 核实PR95于2026-09-30 10:22:58北京时间合并到develop；[合并元数据](pr95-merge.json)、[合并后六项CI](pr95-merge-ci.json)均从GitHub实时取得。
- 下载CI36659585415的backend-test-reports，解析116份Surefire XML，751测试、失败/错误/跳过均0；逐报告路径与SHA-256保存在[汇总](pr95-merge-backend-summary.json)。这些是PR95基线证据，不是本轮新业务测试。
- 使用独立托管worktree refund-aftersale，原用户目录的旧分支和未提交配置/设计登记表未动；旧台账原样归档，WORK_STATE和CCR索引以真实合并事实更新。
- 独立审查SSOT、三份最终PRD及API/Schema/Event/Scheduler，记录[原件哈希、精确来源与缺口](SOURCES-AND-GAPS.md)；完成[代码复用与19项验收映射](CODE-AND-TEST-MAP.md)。
- 用户明确批准“拒绝后可再次申请、每次重新计24h、同单同时一笔待处理、创建退款单后禁止再申请”。此项同步SSOT §38/[PRD30](../../../../docs/01-prd/30-普通退款重复申请人工裁决补充-v1.0.md)，不改原Word。
- 准备[普通退款R1/R2技术候选](../../../ccr/CCR-W2-API-001/refund-application-proposal.md)及[员工授权后续准备](../../../ccr/CCR-W2-API-001/staff-verification-authority-preparation.md)。前者仅技术候选，后者不阻塞本批退款。

## 当前范围与审批事实

用户已授权推进工作，已批准的产品规则无需重批；本轮没有把用户未看过的多域来源API、存储约束和任务增量伪称已批准。

R1/R2新增普通退款批准来源，必须同时调整REFUND/ORDER/PAYMENT/SCHEDULE来源证明、SQL45来源CHECK、任务恢复和旧事件消费者，属于[WORK_EXECUTION_PROTOCOL §4](../../../../WORK_EXECUTION_PROTOCOL.md)“Contract重大变更”。[CODEOWNERS](../../../../.github/CODEOWNERS)指定技术契约审核人为blueXYing，故在具体方案审阅前，正式API/Schema/业务代码保持现状。该门禁不影响已批准SSOT产品增量与所有准备工作的完成。

首批建议REF-002 + REF-004 + REF-003普通全额来源，默认关闭；服务前退款、AFS创建/裁决、员工、HTTP/前端/通知消费者后续。申请仍可核销，普通申请核销后仍可批准退款；旧未履约AFS退款失效是另一来源规则。

## 本轮验证

| 验证 | 实际结果 |
|---|---|
| `python e2e/contract_smoke.py` | PASS_OFFLINE_DOCUMENT_SMOKE：92 operations，58 writes带requestId，1056引用。不是HTTP实测。 |
| `python -m unittest discover -s e2e -p 'test_*.py' -v` | 118 tests，OK；[原始日志](contract-tests.txt)。 |
| `python -m unittest discover -s backend/tools -p 'test_*.py' -v` | 18 tests，OK；[原始日志](architecture-tests.txt)。 |
| 模块依赖/DisplayOrderStatus归属/持久层检查 | 三项PASS；41 reactor modules / 17 biz POMs；无biz→biz，生产SQL仍归属MyBatis XML。 |
| PR95基线证据一致性 | 合并提交=CI head=摘要commit；六项全绿；116份报告751项、零失败/错误/跳过。 |
| 文档引用、历史归档与diff | 本轮Markdown本地目标存在；历史台账与基线一致（仅Git工作树换行表示可不同）；`git diff --check` PASS。 |
| 独立方案复核 | 两位子任务独立审查提出技术/语义修订；全部修正后复核无提交技术审阅前阻断项，见[审阅记录](REVIEW.md)。不是GitHub正式批准。 |

新退款业务验收均NOT_EXECUTED，不借用PR95测试标完成。本轮未改Java/正式API/Schema/任务/事件，故不重复运行基线751项Java测试；相应代码树与基线相同。新候选Outbox字段只存在CCR，不会注册消费者或触发真实通知。CI工具输出的历史“BLOCKED real session/RBAC integration”属于该离线脚本的固定范围提示，不覆盖项目已合并的真实会话实现事实。

## 后续

1. 具体审阅R1/R2后，按原Issue对应Owner同步正式契约与实施，保持默认关闭，提交实现PR并验收。
2. 接真实AFS资格/创建/运营终局裁决，补完整来源敏感CREATE_REFUND和QA-004。PRD“同一问题”与Schema单active工单差异需对应CCR解决。
3. 员工授权独立规范与实施，最后接公开HTTP/读侧、小程序、必要通知/评价消费者和真机验收。

不提前关闭完整Issue，不合并PR，不运行生产DDL/开关或真实资金操作。
