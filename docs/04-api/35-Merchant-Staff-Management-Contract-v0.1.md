# 员工基础管理契约补充 v0.1

状态：IMPLEMENTED_DEFAULT_OFF。2026-09-24。依据27号、23号及[批准回执](../../planning/ccr/CCR-W2-API-001/merchant-staff-implementation-decisions.md)。本补充仅落实五操作，disable保持IMPLEMENTATION_BLOCKED，不新建成员/子账号权限。

## 1. 外部接口与门槛

沿用27号§6.1和11号的列表、单查、POST创建、PUT档案替换、POST enable。响应data为MerchantStaffDTO，列表为items/page/pageSize/total；ID和version为String，phone仅phoneMasked。

- 读取：有效MINIAPP会话+真实主账号+merchant/store/staff归属；已知OFFLINE/FROZEN/未签可只读，不能因未经营就伪装不存在。读权限与经营写权限分离，未用到的业务事实不自行推断为已批准；实际查询事实损坏/未知503。
- 写入：真实主账号，merchant/store均ACTIVE、申请APPROVED、协议SIGNED；每次执行及成功重放均重验当前身份/归属/权限。已知不可经营409 COMMON_CONFLICT；身份401、无归属404、明确缺权限403、依赖/未知503，data:null。未批准冻结写例外不开放。
- create字段和组合沿27号：staffName非空白1～64字符；phone可空且11位ASCII；employmentStatus=ACTIVE/INACTIVE、serviceEnabled显式boolean；INACTIVE+true非法。不得依DB默认自动上岗。
- update只允许staffName、phone和expectedVersion及目标merchant/store，不能变employee状态/归属；省略/null phone都清空。enable要求员工ACTIVE，仅设serviceEnabled=true。每个新成功update/enable以CAS递增version一次，即使enable目标已为true；同requestId成功重放返回原版本，不再写入/审计。版本上溢拒绝409，不回绕。
- 未知字段、重复JSON键、尾随内容、非法ID/version/枚举/boolean强制转换400；目标参数不构成Principal。命令requestId按HTTP UUID校验，内部沿用23号字节范围。默认pet.merchant.staff.enabled=false，disable无Controller业务实现。
- merchant.staff.manage已在现有ALLOWED_ACTIONS内，本轮沿用，不增造STAFF授权；读取LIMITED时不以写动作缺席作为拒读理由。

## 2. 内部API形状

类位于merchant.api.query/dto/command，名字及字段作为本次实现输入：

```java
interface MerchantStaffManagementQueryApi {
    MerchantStaffPageDTO listStaff(MerchantStaffListQuery query);
    MerchantStaffDTO getStaff(MerchantStaffQuery query);
}
record MerchantStaffListQuery(String merchantId, String storeId, int page, int pageSize,
    String employmentStatus, Boolean serviceEnabled, QueryContext context) {}
record MerchantStaffPageDTO(java.util.List<MerchantStaffDTO> items, int page, int pageSize, long total) {}
interface MerchantStaffCommandApi {
    MerchantStaffCommandResult createStaff(CreateMerchantStaffCommand command);
    MerchantStaffCommandResult updateStaff(UpdateMerchantStaffCommand command);
    MerchantStaffCommandResult enableStaff(EnableMerchantStaffCommand command);
}
record CreateMerchantStaffCommand(String merchantId, String storeId, String staffName, String phone,
    String employmentStatus, Boolean serviceEnabled, CommandContext context) {}
record UpdateMerchantStaffCommand(String merchantId, String storeId, String staffId, String staffName,
    String phone, String expectedVersion, CommandContext context) {}
record EnableMerchantStaffCommand(String merchantId, String storeId, String staffId,
    String expectedVersion, CommandContext context) {}
record MerchantStaffCommandResult(MerchantStaffDTO staff, boolean created, boolean replayed) {}
```

复用既有MerchantStaffQuery/DTO，不改变getStaff原形状；management实现按本补充增加HTTP门禁。created只在首次create为true；成功重放replayed=true、HTTP200，data仍原staff投影，无新增外部回执字段。

列表按staffId数值升序、先owner/店范围过滤再计数分页；page 1～10000，pageSize 1～100。仅27号列出的两个可选过滤。未知目标行状态/布尔/坏归属503；DTO与列表不可变，不回传手机号明文。

## 3. 持久化、幂等和最终授权

复用SQL28的merchant_command_idempotency，稳定命令名merchant.staff.create/update/enable，按23号及MerchantRequestKey编码；同操作者同命令同requestId跨目标参数变化也409，目标ID进入canonical参数，不能靠改target scope获得第二次执行。

Admission先静态校验/当前授权，在独立短事务绑定意图；Execution锁绑定，锁MER自有merchant/store/staff目标与必要申请/协议事实，主库当前读复核owner/四条件/expectedVersion；业务、SQL35审计、SUCCEEDED回执同事务提交。不能仅在事务外getAdmission一次后写入，也不能把REQUIRES_NEW快照当最终授权锁。成功重放须重新核验当前会话/owner/读写权限后返回首次脱敏回执，不重跑CAS或产生第二份审计。

phone按现有Schema06字段存储，返回掩码，普通日志及staff审计不落原手机/令牌/完整请求；审计物理映射见[SQL35](../03-database/35-Merchant-Staff-Audit-Schema-v0.1.sql)，只隔离验证，非生产迁移。disable/离职/删除须ORDER/SCH已批保护真正落地后另行实现。

## 实现状态与运维边界（2026-09-24）

五操作已实现，默认关闭，作者真实MySQL/HTTP与独立HTTP门禁证据见本轮集成回执；只读不要求保护密钥或申请事实源可用，写端所需依赖缺失503。姓名/手机号在SQL28规范参数中使用现有ProtectedValuePort的purpose隔离HMAC判等值，避免新增明文规范参数；主档仍按既有SQL06存储，HTTP回执仅返回手机号掩码。生产开关开启前须具备SQL06/28/29/35、真实审核/协议事实与保护密钥；测试建表不代表生产迁移。

列表当前按门店全量读取并校验后过滤/分页，员工数增长时成本线性上升；后续优化必须保留坏事实拒绝语义。本轮不扩大为SQL分页改造。