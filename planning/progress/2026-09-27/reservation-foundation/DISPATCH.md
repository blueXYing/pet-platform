# 预约保护基础轮派发

基线develop dda3492，PR83/84已合入且合并CI成功。用户要求继续子Agent、GPT-6 Sol xhigh；目标仅ORDER当前指派、SCH同店共同锁/完整容量证明基础，不开放预约创建或员工停用。

- 根协调：唯一Writer负责37号API/SQL与Java接口骨架、共享pom、MER当前员工事实、boot装配、台账及PR集成。
- A order_foundation：pet-order-biz实现/测试及ORDER规划；查询全部订单/当前指派并经SCH/MER API核验。不写他域表，不写订单创建/指派命令。
- B schedule_foundation：pet-schedule-biz共同锁、SCH当前事实、纯精确求解器和候选证明及模块测试。不能实现hold/swap/管理路由。
- C foundation_qa：独立boot ReservationProtectionFoundationAcceptanceTest.java及QA目录；真实MySQL多连接与语义反例，不修改实现。

四方独立worktree，先API冻结ed8a60f再并行。公共DTO改动必须根统一同步。隔离MySQL端口33457；不操作生产/OSS。SQL37按已批逻辑建立隔离表，无默认迁移与自动回填。所有生产新Bean默认关闭；已有SCH002读投影不改变。

验收：作者测试、独立跨域真实MySQL验收、默认关闭、架构检查、全量Maven及既有契约门禁。MER legacy INACTIVE+true遵35号作为坏事实，不放宽。缺claim/userId的存量受影响查询503；不得从请求猜事实，不把技术匹配写入最终指派。