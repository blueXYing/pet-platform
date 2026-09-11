# W0 Contract Change Requests（2026-09-11）

补充：ACR-001 R4方向已批准；新的[CCR-ACR-001](ccr/CCR-ACR-001.md)已独立登记为OPEN_SPEC_REQUIRED，由AUTH-001负责建立会话/工作台具体规范。此方向批准不关闭下列CCR，也不代表具体API/Schema已审批。

以下为本地 CCR 草案登记，尚未审批、未发送外部消息、未修改受保护契约。产品封板规则不重新讨论。Architect/Contract Owner 审批纯技术补齐；若发现新产品行为则转产品裁决。相关 Issue 维持原依赖状态，进入实现前必须消除其对应 CCR 阻断。

## CCR-W0-001 — Outbox 恢复字段与持久化契约

- 状态：OPEN / 待 Architect 与 Contract Owner 评审。
- 提出方：W0 审计；关联 Issue：PLAT-003；影响：event-core、task/调度、QA。
- 当前 Contract：核心 Schema 第752~769行；Event Catalog 第354~367行；Scheduler 第876~885行。
- 问题：核心 Outbox 表未提供 Scheduler 所要求的 PUBLISHING 租约恢复字段（lease_until 等），事件存储字段也需逐项对应。
- SSOT/技术支撑：Transactional Outbox 与可恢复异步处理已确定，不新增产品行为。
- 建议：由 Owner 明确 Outbox 状态、租约及事件元数据字段的权威映射与迁移方式；本草案不指定 DDL。
- API影响：事件发布/恢复的内部契约待评审；Schema影响：预计需补齐字段；Event影响：确认版本/元数据存储对应；前端影响：无已知直接影响。
- 测试影响：TASK-005、FLT-012、CON-020、租约宕机恢复与重复发布幂等；是否涉及产品裁决：NO（如评审新增行为则升级）。

## CCR-W0-002 — 迟到支付退款来源映射

- 状态：OPEN / 待 Architect 与 Contract Owner 评审。
- 提出方：W0 审计；关联 Issue：REF-003、PAY-004、CPN-002；影响：refund/payment/order、coupon、points、QA。
- 当前 Contract：核心 Schema 第416~444行（source_type 位于423行）；内部 API 第1406~1412行；SSOT §22。
- 问题：已有 source_type，但其注释枚举未包含 LATE_PAYMENT_TIMEOUT；API 使用 refundSource，需要明确命名和值域映射，不能误称完全没有来源字段。
- 建议：明确存储字段与退款来源、事件来源的对应，支持封板的迟到支付全额原路退款及权益 NOOP；不创建第二套来源语义。
- API影响：确认现有 refundSource 对应；Schema影响：枚举/字段映射待定；Event影响：退款来源语义须一致；前端影响：订单仍关闭，展示态服从 order。
- 测试影响：PAY-007~011、REF-019~020、CON-015；是否涉及产品裁决：NO。

## CCR-W0-003 — 关闭原因与确认轮次的事实映射

- 状态：OPEN / 待 Architect 与 Contract Owner 评审。
- 提出方：W0 审计；关联 Issue：TX-001、PAY-003、PAY-004、ORD-002、ORD-003；影响：order、payment、调度、QA。
- 当前 Contract：核心 Schema 第226~270行；Scheduler 第509~531行、第1117~1128行。
- 问题：Scheduler 依赖 cancelReason=PAYMENT_TIMEOUT 及 confirmRound；当前订单表未显式声明对应字段。reschedule_count 已存在，轮次是否可由它派生需要契约明确，不直接认定必须加列。
- 建议：明确权威事实读取/持久化或推导映射，并说明改期后旧任务失效与迟到支付识别条件。
- API影响：确认查询/任务 payload 语义；Schema影响：是否迁移待评审；Event影响：确认轮次与关闭原因传播；前端影响：无新增产品状态。
- 测试影响：CONF-005~006、RES-006、CON-006、TASK-003、PAY-007~011、CON-014~015；是否涉及产品裁决：NO。
