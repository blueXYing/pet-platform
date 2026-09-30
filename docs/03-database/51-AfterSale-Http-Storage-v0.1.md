# 售后 HTTP 查询存储 v0.1

状态：批准实施，验收结果由本批 closeout 记录；不表示已执行生产迁移。

来源：[Contract51](../04-api/51-AfterSale-Http-Contract-v0.1.md)、[SQL51](51-AfterSale-Http-Schema-v0.1.sql)。在 SQL50 后显式执行，仅增加 AFS 本域两项索引，不新增表、证明、资金或产品状态。

| 索引 | 字段顺序 | 用途 |
|---|---|---|
| idx_aftersale_user_created | user_id, created_at, id | 当前本人列表先限定 user_id，再按创建时间及 ID 倒序分页 |
| idx_aftersale_merchant_store_created | merchant_id, store_id, created_at, id | 当前获权门店列表先限定真实商家及门店，再稳定分页 |

生产查询在 AfterSaleWorkflowMapper.xml 中，COUNT 与 LIMIT 使用同一本域授权谓词，固定 workflow_revision=1；status/orderId 只能进一步收紧。page=1..10000，pageSize=1..50（默认 20），created_at DESC,id DESC。无先全表读取后 Java 授权过滤，无跨域 JOIN。READ_COMMITTED 下计数与页内容是当前读取，并发变动不承诺快照一致或跨页永久位置；页内来源证明无法核实时失败关闭。

商家和运营本期必须指定 merchantId/storeId。当前 OWNER 或 ADMIN 范围在查询前及提交前重验；MER 当前城市/范围在 shared store guard 内读取。aftersale_case.city_code/scope_version 仍是不可变创建快照，不能用于当前 CITY 范围授权。C 列表以不可变 user_id 归属过滤并首尾复验当前会话。摘要不含自由文本、证据、手机号或渠道数据。

原 SQL06 的 order_created/status_created/store_status 索引保留；SQL48/49/50 的来源和核销证明保持不变。新 HTTP 证据命令在同一 namespace/scope 的 canonical-v1 输入中绑定 routeParty；原内部格式不变，同键换端冲突。私有资产 grant 的端别纳入持久 proof_hash，不增加明文 token 或跨端通用凭证。

DDL 非全事务。迁移前检查 SQL50 已应用、上述名称不存在且有备份；中途失败按实际索引状态恢复，不盲目重跑。回退关闭 HTTP，保留 AFS 原业务/任务/证据与回执；仅在核实查询不再依赖且经运维批准后独立移除索引，不删除业务数据。
