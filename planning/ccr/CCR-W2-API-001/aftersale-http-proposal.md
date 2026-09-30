# 售后三端非出款 HTTP 切片

状态：APPROVED / IMPLEMENTATION_IN_PROGRESS，2026-09-30。用户在“先冻结售后HTTP契约、实现C本人/商家OWNER/获权运营非出款HTTP，再接页面”建议后明确“那么请你开始”，授权本技术切片。沿 [SSOT §40](../../../docs/00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md) 和 [Contract50](../../../docs/04-api/50-AfterSale-Workflow-Contract-v0.1.md) 既有规则，不重开七天、互斥、撤回及新问题裁决，不修改 SSOT。

基线 origin/develop=b15665b6573270e8c9123e667eedc606aac54398（PR97 合并），新分支 codex/aftersale-http-20260930。交付为 [Contract51](../../../docs/04-api/51-AfterSale-Http-Contract-v0.1.md)、OpenAPI11、AFS api/biz 授权分页及端别隔离、boot 三端路由、真实会话/OWNER/ADMIN 和私有图片链路；[SQL51](../../../docs/03-database/51-AfterSale-Http-Schema-v0.1.sql) 仅增加两项分页索引。

C 端只本人；商家端只真实当前 OWNER；运营端按 aftersale.read/handle/decide 和当前 MER 资源范围。RouteParty=USER/MERCHANT/OPS，由可信路由指定，不能从 body 自报。双身份用户走商家路由仍须 OWNER，并以 MERCHANT 入卷。成功回执和图片重放仍复验当前端别、会话和权限。

C 分页固定当前 user_id；商家/运营列表本批必须指定 merchantId+storeId，在 SQL COUNT/LIMIT 前授权。平台跨店聚合后续交付，不用工单创建城市快照授权，不全表取出后过滤、不跨域 SQL，也不逐行请求 ORDER/REFUND/资产。分页上限 50，稳定 created_at/id 倒序；详情才读已获权卷宗内容。

CreationResult(receipt,created) 直接取事务首次创建/持久成功重放分支，对应 HTTP 201/200，不从当前状态或时间猜测。新 HTTP 证据端别进入原 namespace/scope 的 canonical 输入，同键换端 409；原内部输入与接口兼容。领域和 typed 资产证明均有明确端别重载，新增 default 方法仅失败关闭。

FROZEN 当前用户与商家/店按既有只读边界允许查询及图片授权读取；上传、申请、补证、商家意见仍需 ACTIVE 与原写权限。资格 eligible 只表示订单业务资格，不授予账号写权限。MER.requireOwnerRead 为真实 OWNER 关联的只读扩展，不放宽 requireOwner。

公开决定仅 REJECT/RESERVICE/OTHER 形成终局；FULL_REFUND/PARTIAL_REFUND 在真实会话及 decide 权限通过、请求格式合法后固定返回 503 COMMON_DEPENDENCY_UNAVAILABLE，不调用内部决定/资金接口，即使安装资金 Provider 或打开内部资金开关也不开放。内部 A2 资金流程不改。

HTTP、workflow、worker、refund 开关均默认 false。HTTP 依赖真实 workflow 和 ADMIN 会话，workflow 继续依赖 REFUND application、VERIFY completion、private assets 和 C 会话。worker 独立控制；生产开放前须有补证任务 worker，隔离测试可关闭调度并主动驱动真实 worker。类型/诉求目录、内容审核、对象存储/扫描/水印、密钥和真实会话不可用均失败关闭。

本批不交付页面、STAFF、生产开关或迁移执行、实际出款、跨店运营聚合、复审或额外人工审批。验收覆盖真实 HTTP 会话、三端隔离、同键重放/换端、分页范围、冻结只读、补证/非退款终局和私有图片单次消费；测试及架构门禁由 root 串行执行，实施文档不冒充通过记录。


旧 HTTP10/OpenAPI11 的 `/admin/aftersales/{afterSaleId}/decision` 为未实现草案，本批正式采用 `/decisions`，不保留假的可用别名。createAftersale/decideAftersale 从 e2e/contract_smoke.py 的旧 legacy 集合迁到独立 AFTERSALE_OPERATIONS，明确保护本批23条操作；其余14条 legacy、11个写操作、3个首次创建保护继续保留，不松全局路由全集门禁。AFS专属门禁固定端别/会话/动作、默认关闭、严格DTO、分页上限与指定门店、201/200同结构、四字段回执和公开非出款限制，并有定向负例。
