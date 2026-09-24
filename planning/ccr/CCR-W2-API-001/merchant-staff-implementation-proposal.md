# 员工基础五操作实现补充（MER-001）

状态：ACCEPTED_IMPLEMENTATION_MAPPING；2026-09-24。基线 develop 980a830（PR81/82已合入）。用户授权本轮继续多角色实现。27号已批准列表/单查/create/update/enable/disable的外部形状；本轮仅前五项，disable依旧IMPLEMENTATION_BLOCKED。

## 已批准且不重问

主账号管理、同merchant/store归属；手机11位ASCII，输出只掩码；PUT省略或null phone均清空；不绑定登录用户、不实现子账号/核销/离职/删除；String ID/version；请求幂等与CAS；未知字段/重复键/非法状态拒绝；默认关闭，不生产迁移。INACTIVE+serviceEnabled=true非法；enable要求员工ACTIVE。

## 本次已确认的写入门槛

推荐：列表/详情允许真实主账号读取本人门店员工，包括已知冻结/下线的历史资料；新增、编辑档案和启用均要求真实四条件可经营（merchant/store ACTIVE、申请APPROVED、协议SIGNED）。已知不可经营写入返回既有409 COMMON_CONFLICT；无归属404、无会话401、事实未知/读取失败503。停用/离职/删除仍不开放，不能通过update改employmentStatus或serviceEnabled绕过。

这是把27号“启用仍校验可经营”及工作台四态明确到本轮五操作：读取与写入分开，不改变订单核销/退款/存量履约的独立权限。已知LIMITED只读；读取只根据所需的当前身份/归属事实授权；未知/损坏事实失败关闭，不以读取结果推断经营资格。

## 技术实现映射（随上项确认后同步）

- MER新增独立员工管理内部API：list/get与create/update/enable；具体DTO沿用27号及OpenAPI MerStaffPage/View/Create/Update/Version请求形状。返回command结果包含created/replayed标记供HTTP201/200选择，不更改外部data。
- 主账号员工管理沿用现有ALLOWED_ACTIONS中的merchant.staff.manage，供当前准入响应表达已批准的员工管理能力；不生成STAFF权限/成员绑定。写命令的稳定幂等名沿用merchant.staff.create/update/enable，不把动作标识当命令名。
- 复用已存在SQL28 merchant_command_idempotency，不读user/admin幂等Store；Admission独立绑定→Execution锁意图/本域目标→当前主账号与四态复核→CAS/业务+审计+首次回执同事务。成功重放重验当前身份/归属/权限，不盲重做或覆盖。
- 新增MER自有merchant_staff_audit（新SQL35，隔离验证、不自动Flyway）：id、actor_type/actor_id、action_code、merchant_id/store_id/staff_id、request_id、trace_id、from/to employment_status、from/to service_enabled、from/to version、occurred_at；不含明文phone/姓名请求快照或令牌。命令作用域+操作者+requestId唯一，成功重放不重复审计。
- HTTP共享安全由主协调接线：staff精确路由族纳入MINIAPP bearer与统一信封；未知/未注册disable仍不开业务处理器。运行开关pet.merchant.staff.enabled默认false。
- 正式35号API补充与SQL35由主协调唯一Writer，A在契约同步后写MER生产实现，QA独立验证。额外Schema/API变化须先报告，不以本稿批准未知功能。

## 验收

真实申请/审核/签署后创建员工→分页/详情→档案替换/启用；其它用户/店/无会话/撤权重放、String大ID和版本、phone清空与脱敏、同键重放/异参、CAS并发、审计唯一；停用未开放；已创建员工通过SCH002真实查询读取（能力/排班仍SQL种子，分层披露）。
