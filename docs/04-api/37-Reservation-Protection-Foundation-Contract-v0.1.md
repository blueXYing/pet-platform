# 预约保护基础实施契约 v0.1

状态：IMPLEMENTATION_IN_PROGRESS。2026-09-27。将已批准的[36号](36-Reservation-Order-Protection-Contract-v0.1.md)及[34号](34-Schedule-Protection-Contract-v0.1.md)细化为本轮Java/SQL切片，不改变产品规则。[SQL37](../03-database/37-Reservation-Protection-Foundation-Schema-v0.1.sql)只隔离验证，不自动迁移、回填生产或开放HTTP。hold/create/swap及SCH004维护写端点仍未实现。

## 1. 装配与公共形状

公共Java接口/DTO以三个api模块的`ScheduleProtectionTypes`、`OrderProtectionTypes`、`MerchantCurrentStaffTypes`为冻结输入。跨模块仅biz→api，不访问他域表。开关`pet.schedule.protection.enabled=false`；boot显式开启时以同一个主库DataSource对象装配独立guard、SCH当前事实、MER当前员工、ORDER完整性及容量证明Bean，避免构造依赖环。没有HTTP/OpenAPI增量，原SCH002六字段及展示查询不变。

## 2. Guard基础设施接缝

`ScheduleCapacityGuardApi.acquire(List<String> storeIds, QueryContext)`和`requireHeld(String storeId, DataSource callerSource)`是基础设施专用接口。DataSource只作对象身份核验，不进入业务DTO/日志/HTTP，不授权跨域表访问。

调用方必须已经开启Spring同步、主库可写READ_COMMITTED事务，实际连接autoCommit=false。guard不创建/提交事务，只对storeId稳定主键原子建行并FOR UPDATE，持锁至外层提交/回滚。ID按Long数值排序去重；后续扩展锁集合不得降序。requireHeld核验同一DataSource对象、当前ConnectionHolder/物理连接、事务登记及门店集合。登记随完成清除，不能跨线程、事务结束、不同DS或REQUIRES_NEW复用；缺锁、只读、RR、自动提交、不同DS一律503，失败不能继续提交部分业务。它不是可序列化权限token。

Spring元数据不能辨别一个在首次acquire之前新开的REQUIRES_NEW是否是业务顶层；后续命令由唯一orchestrator按23/36号保证顶层事务。本轮证明的是当前同库同连接同事务持锁，不声称尚未实现的全部业务写路径已参与。

## 3. SCH当前事实

`ScheduleProtectionFactsApi.readStore(storeId,context)`在requireHeld后，同连接当前读本店全部window/reservation/claim，complete=true只表示完整读取和结构校验成功。DTO包含原窗口ID/kind/归属/起止/容量/状态/版本，预约orderId/userId/归属/履约方式/主区间和两开始值/状态/版本，claim原窗ID/kind/起止。不得反调ORDER。

SQL37新增kind、claim、reservation.user_id和guard。user_id缺失是未恢复历史事实，保护查询503，不能从请求猜用户；双向用户关联由ORDER核验。活动预约缺完整claim、旧接送GENERAL、孤儿/跨店/错服务claim、未知枚举、非分钟区间、负版本/容量均503。活动IN_STORE恰一GENERAL，等于实际预约区间且被原OPEN窗包含；活动接送恰两完整OPEN方向窗，起点等于父预约两开始值、相隔至少120分钟。历史RELEASED/EXPIRED可保留无claim旧记录；已有claim仍校验归属和自身时间，不拿释放前快照要求当前窗口永不改变。

能力、排班当前读由SCH自己承担：能力仅ENABLED；员工属于门店由MER公共事实证明。不得复用原REQUIRES_NEW展示store。测试中显式播入完整事实，本轮没有业务预约写入或迁移补全工具。

## 4. MER当前员工

`MerchantCurrentStaffFactsApi.readStore`返回merchantId/storeId/complete及完整员工集合（含已知INACTIVE/不在岗），员工字段为staffId/merchantId/storeId/employmentStatus/serviceEnabled/version，无姓名电话。同guard、同DS、同事务当前读，不另开快照。确认门店不存在404；故障、坏归属、未知枚举/布尔、负版本503。沿用[35号](35-Merchant-Staff-Management-Contract-v0.1.md)既有不变量，INACTIVE+serviceEnabled=true为坏事实503；合法INACTIVE+false仍返回。此API不授予管理权限，不把手机号当登录身份。

## 5. ORDER完整性

`OrderProtectionFactsApi.readStore`先取本店所有订单及当前assignment，核查只有主字段/只有明细、人员不同、非法current和坏版本。另查全局current孤儿assignment：因无storeId无法可靠归店，任一孤儿使本轮所有门店保护查询503，明确接受这一保守可用性代价，不猜staff归属。SQL37给assignment独立非负version；0为隔离/既有行初始值，未来指派命令负责递增。验证现有store前缀索引与新增current/order查询路径。

经SCH公共完整事实核验order/reservation双向唯一、user/merchant/store/service/fulfillment一致；活动预约无主单亦503。本轮无hold/create，不开放PENDING_BIND。按36号状态表计算protectRequired；未知或未能证明的状态组合503，不用展示状态推断。需保护的当前人员必须可在MER同店完整集合定位；历史不倒算当前在职/能力资格。预约已释放仍待服务必须503。

`getByReservations`及`getCurrentAssignments`都先全店完整校验再过滤。前者每输入ID恰一对应已绑定单；后者affectedStaffIds=null表示全店current，空集合表示空子集。totalOrders/totalCurrentAssignments为过滤前总数。返回不可变，ID/version为十进制String。

## 6. 精确容量证明

`ScheduleCapacityProofApi.checkNewReservation(CapacityProofQuery)`要求已持guard，从真实SCH/MER/ORDER当前事实构造一个未持久化候选。IN_STORE传实际appointmentStart/end和可选selectedGeneralWindowId，必须唯一完整包容OPEN GENERAL；接送传两个方向原ID，起止从原窗获取，appointmentStart/end要求为空以免歧义。此方法不校验顾客下单身份/服务商业准入/支付，不生成订单ID、不占位。到店区间须由未来调用方按服务时长合同形成；此资源查询不授权用户任意改变时长。

纯求解器属SCH domain，按36号构造完整相交闭包，验证跨服务共人、每预约一人覆盖全部claim、固定商家指派及逐窗端点容量。候选人员须MER同店ACTIVE+在岗、具体服务能力ENABLED、AVAILABLE并集无空档覆盖每个claim；未知/不完整事实503。相邻半开允许，接送中间可无班，同预约重叠双段按并集一次，不同预约不可共用重叠员工。必须完整搜索，不用贪心失败当无解；数学匹配不持久化、不返回人员、不展示给C。

技术计算预算不构成业务数量上限；预算耗尽503，完整可信无解或配置容量不足409 SCHEDULE_CAPACITY_EXCEEDED。参数非法400，事实缺损503。成功`CapacityProofResult(storeId,true,evaluatedReservations)`没有租约或票据，不能缓存为后续hold授权。减员/停用/hold门禁仍未解锁；未来写必须在同guard事务重验并提交业务/审计/幂等回执。

## 7. 验收

真实MySQL验证同店串行、异店独立、首行并发、回滚释放、当前读及不同DS/事务拒绝；独立QA覆盖漏指派、孤儿、释放仍在途、固定指派、非贪心、双段同人、半开、配置容量、预算503。结果据实更新。无生产迁移，不push main/develop，新PR合并另需用户批准。
