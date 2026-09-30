# 退款接续方案的独立审阅记录

日期：2026-09-30。对象：普通退款R1/R2候选、SSOT §38/PRD30已批准产品回执、SOURCES-AND-GAPS及CODE-AND-TEST-MAP。两个独立子任务分别从跨域实现与产品/权限一致性角度只读审阅；根任务修订，之后独立复核。

## 发现与已完成修订

1. ORDER当前没有 `refund_application_status` 列：改为明确新增投影，不误称既有。
2. 普通来源有真实申请/决定，不能假造sourceEventId：明确late_event_id/source_event_id为NULL、source_biz_id/source_decision_id必填、created_event_id仍是真实创建事件；列出两种DTO和旧非空校验的兼容改动。
3. 24h提前执行不能把唯一任务成功吞掉：要求重排至持久deadline，并覆盖到期PENDING的缺失/DEAD/错误CANCELED任务恢复；不跨域操纵task表或窃取租约。
4. 两阶段批准后允许真实核销改变ORDER版本：增加真实批准API→暂停建单→核销→恢复建单用例，建单从当前版本重新取得能力。
5. 新普通任务对应严格二值来源组，不能用null expectedSource跳过校验；PAYMENT沿现有 `TASK:REFUND_SUBMIT` / `TASK:REFUND_CHANNEL_QUERY` 键转换。
6. 不把“核销后新售后”概括成“全额/部分退款后仍可售后”：回归原资格、退款事实重验，明确退款中/退款终态限制。
7. CODE事务方案与RF-03/07/08统一为真实两阶段恢复；移除纯SQL模拟半成品的正向验收表述。
8. 商家拒绝退款仅强制reasonText；拒绝分类字典保留后续扩展，不借用拒单字典、不新增隐含阻塞。申请reasonCode沿既有独立配置字典。
9. PRD补充引用修正为30号；添加申请/决定候选Outbox固定字段，可靠通知生产意图与后续实际送达区分。

## 复核结论

独立复核未发现提交技术审阅前必须再修改的问题。产品重复申请裁决同步准确；两阶段方案、普通退款核销规则、任务恢复、身份、来源兼容和默认关闭边界一致。

本记录只是候选方案的一致性/可行性审阅，不代表R1/R2人工批准、不代表业务实现/验收通过、不代表GitHub正式review或PR合并授权。新退款测试仍NOT_EXECUTED，已通过的本轮离线检查见[准备记录](PREPARATION.md)。
