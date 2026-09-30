# 普通退款申请存储 v0.1

来源：[Contract49](../04-api/49-Refund-Application-Contract-v0.1.md)，2026-09-30 R1/R2人工批准。[SQL49](49-Refund-Application-Schema-v0.1.sql)仅在隔离QA手工运行，不接启动迁移。

| Owner | 表/增量 | 保证 |
|---|---|---|
| REFUND | refund_application追加原正常付款、归属、命令、事件、版本、决定/退款引用及加密原因 | 一轮一行；活动生成列唯一order；拒绝释放活动槽；旧request_id全局唯一删除；原文本列只允许NULL |
| REFUND | refund_application_command | 二进制五元组唯一、独立Admission、加密规范参数和首回执、失败保留RESERVED |
| REFUND | refund_application_decision | 每申请仅一不可变决定；引用不可变申请原付款快照；USER真实OWNER或SYSTEM超时；拒绝原因加密 |
| REFUND | refund_application_reconciliation_issue | 申请/任务损坏逐行隔离；固定脱敏问题码、出现次数与OPEN/RESOLVED状态，修复后重新核实，不改资金状态 |
| ORDER | pet_order.refund_application_status | 当前轮投影与current_refund_application_id配合CAS，不覆盖新轮或核销历史 |
| ORDER | order_refund_application_proof/commit | 申请/决定本域来源；本次CREATE退款ID、当前order版本和成功事件唯一证明 |
| REFUND | refund_execution.source_biz_id/source_decision_id | 普通来源关联申请/决定，late/source_event_id均NULL；真实created_event_id不变；旧来源空值与语义兼容 |

金额DECIMAL(18,2)，ID BIGINT（JSON String），时间DATETIME(3) UTC，敏感原文采用带用途绑定的AES-GCM字节，不进入Outbox。所有应用SQL在本模块MyBatis XML。

命令五元组中 namespace/operatorType/scope/requestId 用二进制比较，actor_id 为 BIGINT；SYSTEM命令以内部0作为规范主体键，决定表SYSTEM.operator_id仍为NULL，对外不暴露虚构用户。渠道证明损坏沿既有 refund_reconciliation_issue 记录 REFUND_SOURCE_PROOF_INVALID；不据异常记录改变退款金额或资金状态，成功确认时由既有关闭逻辑处理。

旧申请没有可核实的身份/准入/来源证明，迁移门禁拒绝任何历史申请行；不得清空或猜测回填。MySQL DDL非全事务，失败需人工查明前置状态并制定迁移方案，不能盲目在生产重跑。现有迟到付款、商家拒单数据通过SQL49兼容检查，未知来源拒绝。生产迁移另行安排。
