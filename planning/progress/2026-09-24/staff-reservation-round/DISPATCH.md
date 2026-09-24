# 员工接口与预约保护轮派发

基线develop 980a830，PR81/82已合入，合并CI通过。用户要求继续多角色+GPT-6 Sol/xhigh；本轮执行A员工五操作、B预约/订单契约、C独立QA，根Work协调及服务发布小程序验证。

- A唯一写MER api/biz/本域Mapper与模块测试；boot新MerchantStaffController/Configuration/ExceptionHandler、MerchantStaffHttpTest和默认关闭测试。35号API/SQL已先冻结，禁止disable/离职/删除/成员绑定与排期写入。
- B唯一写reservation-order-protection-proposal.md及SCH-003-contract规划；未批新产品/重大契约只提交候选，不写业务代码。
- C唯一写MerchantStaffAcceptanceHttpTest.java与MER-staff-qa规划；与A测试分开，真实数据库反例，不碰生产/共享配置。
- 根Work唯一写权威契约/SQL/台账、CSessionSecurityConfiguration及CBearerSessionFilter共享接线；拥有测试专用M002IntegrationServer真实封面验证增强与必要前端缺陷修复，其他角色不抢模拟器。
- 每角色独立worktree提交；不push main/develop；新PR先draft，合并另需批准；默认关，无生产迁移。SQL35只用于本轮隔离测试库。
