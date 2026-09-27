# 预约保护基础实施同步

状态：APPROVED_SCOPE_IMPLEMENTATION_DETAILS，2026-09-27。

依据：用户已批准ROC-1～6并批准合入PR83/84；本轮明确要求按主协调、ORDER开发、SCH开发、独立QA四角色开始实现保护基础。产品裁决及共同锁/全量事实/容量证明逻辑不重开审批。

[37号实施契约](../../../docs/04-api/37-Reservation-Protection-Foundation-Contract-v0.1.md)及[SQL37](../../../docs/03-database/37-Reservation-Protection-Foundation-Schema-v0.1.sql)是36号的具体接口/存储映射：公共DTO和默认关闭内部装配；稳定门店锁；原窗kind/claim；预约用户关联以完成已批双向用户校验；assignment版本和查询索引。基础库不实现生产预约写入，不新增HTTP/业务动作。

技术细化：同一DataSource对象、可写RC事务与连接holder身份验证；仅本店行锁，全局孤儿旁查用RC新语句视图；无法归属的current孤儿导致全部门店保护查询503（不能凭空认定无影响）。历史user_id缺失、活动claim不全等旧数据隔离，不自动推断或回填。内部匹配不持久化/显示为商家指派，计算预算耗尽503而非虚报容量满。

不授权：生产迁移、默认开关开启、PR合并、新业务数量上限、跨店改期或员工停用。后续hold/create/swap及管理写入必须继续按36号同步、验证、接入。本轮测试不能替代P01～P24全部交易行为验收。
