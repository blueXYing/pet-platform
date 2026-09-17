# MER-001 S3 协议持久化组件交接

基线：PR51合并`5ee8f75`，合并CI 35202619408成功。用户已授权合入PR51并继续；新实现PR不自动合并。分支 `codex/mer001-agreement-20260917`，执行分工见[DISPATCH](DISPATCH.md)。

## 已交付

- [SQL28](../../../../docs/03-database/28-Merchant-Agreement-Schema-v0.1.sql)：协议版本、当前指针、真实同意记录、merchant自有幂等四表。包含同模块FK、唯一键、字段/状态约束；只在隔离库验证，无默认Flyway或生产迁移。
- 已批协议查询/同意的域API与MyBatis XML实现：真实USER owner校验；未签读当前发布内容，已签读原版本，不因发布指针换版失效。
- 参数/内容hash/版本校验；首签插入同意记录、首次回执及SUCCEEDED同事务提交；失败执行回滚但保留RESERVED参数绑定；新key同一已签版本返回原事实，不新增强制重签。
- 严格字节幂等键，支持公共512-byte requestId与固定scope；同key并发锁后再判SUCCEEDED，不同key由merchant行串行，receipt保持原acceptedAt。已绑定成功重放不请求新ID，不重复一次性审核/未签资格。
- 提交确认未知时，原key在权威数据库恢复；申请源异常、损坏参数摘要、未知canonical版本、坏内容/回执等失败关闭，不泄露源内部错误信息。
- 协议资格适配器读取真实acceptance，要求在同DataSource事务内与S2 merchant/store快照组合；申请审核来源仍是明确端口，默认未装配503。

## 真实与未接通的边界

协议四表和读写是实际MySQL实现。测试的运营发布记录由隔离fixture初始化，`ApplicationReviewFactsReader`返回的APPROVED是测试替身；没有以此宣称运营发布、真实申请审核、真实HTTP/会话或生产新单资格已接通。

当前首次同意组件仅开放ACTIVE商家且必须读取到APPROVED；APPLYING如何激活尚未冻结，先503，不私自写ACTIVE。其它未开放状态同样不执行首次签署；历史已签读取/成功重放仍按当前owner重验，不消耗一次资格。这是当前实现限制，不是新增永久产品禁止。

Java DTO采用OffsetDateTime和可空accepted字段表达内部数据；未来HTTP投影仍须遵循OpenAPI固定三位毫秒、NOT_SIGNED省略acceptedVersion/acceptedAt及401/403等适配。当前无Controller、@Bean/@Component或boot自动注册，OpenAPI HTTP操作仍未标已实现。

## 验证

- 18项新协议真实MySQL用例：权限/字段/旧版效力、512-byte/大小写requestId、同key/异key并发、失败绑定、执行原子回滚、实际execution commit后丢ACK恢复、未知/损坏事实、APPLYING失败关闭和外层事务拒绝。见[QA-HANDOFF](QA-HANDOFF.md)。
- 根全后端`clean verify`返回0、BUILD SUCCESS：37套件/289 JUnit，0失败/错误/跳过，包含22项ArchUnit；91项离线契约、13项架构工具回归通过。[validation.json](validation.json)记录计数与被验证源码/DDL哈希。
- DDL在MySQL8.4.9实际执行；依赖和XML-only边界通过。请求参数摘要会与持久化原字节重新核验，未知版本不会覆盖绑定。
- 独立复审推动修正：申请事实源异常统一503；未知canonical版本不误报异参；已有回执不依赖新ID；拒绝外层活动事务；固定merchant/协议版本重放检查；APPLYING衔接不私自激活。最后定向与全量均已复测。
- 全量后确认本轮实例无非系统残留库，核对datadir与容器ID后关闭自建MySQL/Redis；原服务与用户配置保持原样。首次定向编译曾缺测试AtomicInteger import，补齐后通过，未放宽断言。

## 申请审核下一步

[申请审核CCR候选](../../../ccr/CCR-W2-API-001/merchant-application-review-proposal.md)已给出字段、路径、主体claim、材料版本、审核/审计、真实Admin权限、强制通知事件与Outbox方案。以下四项原文冲突/未冻结映射已列具体建议，批准前未实现：

1. 门店照片必填1～6张。
2. OCR不可用/失败时允许提交待人工核验；已知过期仍拦截，核验/去重未完成不得通过。
3. 补正作为决定类型，申请仍映射REJECTED，修改新材料版本后重提。
4. APPROVE建档时merchant设ACTIVE，但未SIGNED仍不开新业务；不由协议组件私自激活。

依据AGENTS“Contract缺失走CCR”和WORK_EXECUTION_PROTOCOL§4，未批的新产品解释/重大接口不写成生产事实；这不是再次确认已批准的协议换版或下线规则。缺真实申请/发布来源前，完整MER-001、工作台、审核强制通知、HTTP及生产启用均未完成。
