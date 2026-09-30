# 售后流程存储 v0.1

来源：[Contract50](../04-api/50-AfterSale-Workflow-Contract-v0.1.md)、2026-09-30 用户批准四项产品规则及 A1–A3。[SQL50](50-AfterSale-Workflow-Schema-v0.1.sql) 在 SQL49 后显式执行，不接启动迁移；生产迁移未执行。

| Owner | 表/增量 | 保证 |
|---|---|---|
| AFS | aftersale_case workflow_revision=1、来源密文、期限、当前补证/决定/退款引用 | 一单一活动工单；保留旧 SQL48 历史；新工作流必须有真实命令、创建事件及 ORDER 来源 |
| AFS | aftersale_command | 二进制命令作用域及 requestId 唯一；独立准入保留 RESERVED；规范参数及首回执 AEAD 加密 |
| AFS | aftersale_transition | 工单版本、命令及事件唯一；迁移同事务留痕 |
| AFS | aftersale_evidence_batch / asset | 不可变批次，保存真实资产 owner/hash/version；描述密文；每批最多六张由 API 校验 |
| AFS | aftersale_supplement / recovery_issue | 每工单一 OPEN 轮次；明确截止及完成命令或核销证明；耐久任务缺失恢复和冲突隔离 |
| AFS | aftersale_decision | 每工单一终局决定；终裁时当前授权/范围版本及来源/资金资格密文证明；退款原子绑定 |
| ORDER | order_aftersale_source_proof / refund_commit | 原资格、版本与最终退款提交证明；不改写历史核销；成功事件唯一 |
| REFUND | refund_aftersale_proof；refund_execution / refund_order CHECK | 唯一 case/decision/command；新来源允许 FULL/PARTIAL，旧来源仅 FULL；防止 NULL 绕过金额约束 |
| PAYMENT | payment_refund_funding_proof | 首发资格证据和绑定摘要；保留原 refundNo 后续查询 |
| THIRD_PARTY | aftersale_asset_read_grant / access_audit | 独立 typed grant，绑定工单/批次/对象/会话/权限版本；五分钟有效、单次消费、无裸 token 存储 |

金额使用 DECIMAL(18,2)，比率沿已有 DECIMAL(10,6) 六位 HALF_UP，仅展示派生，不作为授权输入。ID 为 BIGINT，公共 JSON 为 String；时间为 UTC DATETIME(3)。ORDER的source_json来源快照使用LONGTEXT + JSON_VALID保留精确JSON格式，防止数据库JSON数字重写影响证明核对；AFS的来源、内容、决定证明及首回执使用MEDIUMBLOB保存密文。生产 SQL 均在 Owner 模块的 MyBatis XML。

工单eligibility_anchor保留真实verifiedAt或appointmentStart，eligibility_deadline固定为anchor加七天；服务写事务以数据库UTC毫秒校验闭区间 `anchor <= now <= deadline`，到期等号可新建、下一毫秒拒绝。Schema固定锚点与截止关系，不能替代写事务资格核验，也不限制既有工单在七天后的处理。

aftersale_supplement的OPEN状态必须无closed_at和任何完成引用。正常SUBMITTED/TIMED_OUT/CANCELED使用completion_command_id且completion_verification_id为空；真实核销取消则仅CANCELED，使用completion_verification_id且completion_command_id为空。SQL50的CHECK明确约束这两类完成引用互斥，终态均须closed_at。核销取消、工单INVALIDATED/清current_supplement_id及aftersale_verification_proof同事务提交；代码还核对真实同单同店核销证明和完成时点，不能只凭一个ID视为完成。已取消轮次不再进入OPEN到期恢复扫描。

aftersale_decision.authz_version/scope_version及加密DecisionEnvelope保存本次Authority.requireAdmin返回的AdminAuthority当前版本；aftersale_case.scope_version仍是创建来源快照。终裁提交前再次核对当前授权和资源范围，版本变化则回滚。二者不得互相替代。

成功命令首回执由aftersale_command加密结果与aftersale_transition原迁移共同校验。重放先核输入和原参数绑定，再复验当前读取及原动作权限；不重新依赖原因目录或内容审核Provider，不因其后续不可用而改写既有结果。未成功命令仍须正常审核及真实业务写入。

SQL48 的旧工单只可走原历史核销证明路径；没有真实新命令/事件/来源的行不能成为新售后工作流。不得根据 seed、当前订单指针或猜测金额回填证明。新增代码需先 SQL50；默认关闭不代表兼容缺列的旧 Schema。

退款裁决、refund_order/execution、ORDER 提交证明、AFS 终态、Outbox 和首回执共享同一可写 READ_COMMITTED 短事务及 store guard。提交前各 Owner 校验自己的证明，不跨模块读表。真实出款依赖尚未提供的资金权威服务；不能以本地 PAID/无结算记录推断可退。

MySQL DDL 非全事务。迁移需先备份并核对 SQL49、现有约束及历史数据，失败不能盲目重跑；不删表、不清空历史。回退时先关闭新准入，保留新来源读取、已承诺退款和原号查询能力；不能回滚为不认识 AFTERSALE_DECISION 的旧消费者。

命令 request_id 与 ORDER 状态日志承接 API23 最多512 UTF8字节的原始内部键，使用 VARBINARY(512) 保留大小写及尾空格；不把内部键强制转换为HTTP UUID。恢复问题的 store_id 在父工单缺失时允许 NULL，不编造门店ID；该行隔离不阻塞其余可恢复工单。
