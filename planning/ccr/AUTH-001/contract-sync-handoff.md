# AUTH-001 契约同步与存储设计交接

## 已批准内容，通俗说明

你刚才同意的内容已经记录：

- **A：权限改动可靠保存。** 谁能做什么、能看哪些数据，都以数据库里的最新记录为准，不能靠旧缓存继续放行。
- **B1：断网后短时找回登录结果。** 密码仍按已交付的Argon2id方案保存（专门保护密码的算法）。找回窗口可能不足60秒；临时缓存坏了可能需要重新登录。**这不是登录会话60秒就过期**，原会话时长不变。另一条恢复路线B0未选。
- **C：操作有记录，唯一管理员可受控恢复。** 普通审计台账故障可补记和告警；恢复管理员账号只走受控维护流程，不加额外身份认证（MFA）或第二管理员。

接下来由我整理实现交接和仍未完整的内部字段。下一次需要单独决定的是是否合并更新后的PR15；没有合并或数据库执行授权时，我不会执行SQL或开始业务代码，也不会再让你重复批准A/B1/C。

## 技术追踪（供团队查阅）

批准回执（2026-09-14）：用户明确回复“可以 按照你描述的进行”，接受PR15/head `d42fdf3c260b612ef7ee4786f442b13bcb58000c`、tree `ac58dedd3ac18f425779da0ced38da165f94bb97` 中已交付的存储A、B1、C；B0未选。仅记录已批准范围，不改表结构、参数、样例或权威API，不执行SQL/实现。内部尚未完整字段/签名仍待具体审阅；PR15合并未授权，继续Draft。

状态：**SYNC_CANDIDATE_NOT_IMPLEMENTED / STORAGE_PROPOSAL_ACCEPTED**。基线develop `643f05cd3a9357772bb3029ff97b750afeebb1ca`。分支`codex/auth-001-contract-sync`，工作区ff85。D1/D2方案及取消MFA已批准，PR14已合入；本阶段同步候选尚未合并，不表示API已上线。

我负责：把已批准的36个AUTH外部操作及行为映射到07/10/11/12，保留原16交易操作检查，补Schema正反验证；给下一步真实Web纵向切片准备已接受存储方案的实现交接。QA独占4个已登记测试文件，其他角色只读审阅。用户不必重新审批D1/D2、六角色、取消MFA或存量履约规则。

本次A/B1/C已获批准，具体内容见[存储设计§1](storage-design.md)；无需再让用户逐项设计数据库。下述内部字段附录仍待具体审阅，不被本次存储批准一并覆盖；没有SQL、migration或业务代码。签约Provider、冻结未明确写动作、真实SMS/手机号证明接入仍是原门禁，不能默认成功。下一阶段优先实际运营Web账号密码登录→会话→当前权限查询，不依赖签约，不创建一批503空壳。

## 1. 精确Owner与改动

BackendCore唯一：docs/04-api的07/10/11/12；两个CCR；本目录contract-sync-handoff、storage-design、review-evidence、sources-and-gaps；review-guide仅同步审批状态。QA唯一：e2e/contract_smoke.py、e2e/test_contract_smoke.py、e2e/test_auth_contract.py、backend/pet-common/src/test/java/com/petplatform/common/S1ContractMappingTest.java。主Writer不修改QA文件。

禁止修改：SQL/DDL/migration、应用源码/POM/boot/前端/CI、SSOT/PRD、23号公共补充及S2。原spec分支保留。本阶段实际测试会生成忽略的target及本任务临时验证环境，不把它们提交为业务实现。

## 2. 已接受映射范围

| 文件 | 同步内容 | 不应据此宣称 |
|---|---|---|
| HTTP10 | 36操作的请求/响应/字段/用途与主认证、独立Web、分页/范围、当前权限、敏感回执、401/403/503、无MFA | 已真实登录/签约/撤权；物理存储已实施 |
| OpenAPI11 | OAS3.0.3结构、required/nullable/oneOf、错误data:null、安全组合与52操作显式清单 | OAS可以表达全部服务端资格；文档列接口即API上线 |
| Internal07 | 可信主体/模块Owner/准入与新单资格区别/D2最终检查和版本一致性/专用认证幂等语义 | 内部未完整定义的PrincipalRef/ResourceScope/versions或新签名已冻结 |
| Error12 | 已有码逐场景映射，无新全局码 | 不存在第二因素也可拒绝已获权动作；UNKNOWN等于成功 |

原16个交易path operation对象保持不变。历史HTTP§末尾“具体AUTH DTO尚待建立”等段落是PR14前状态，当前候选以新增AUTH附录为准；上线与未定输入状态以本交接为准。

## 3. 36个AUTH操作的显式映射

| operationId | HTTP | 路径（servers=/api/v1） |
|---|---|---|
| cAuthCreateAttempt | POST | /c/auth/attempts |
| cAuthWechatLogin | POST | /c/auth/wechat-login |
| cAuthSendSms | POST | /c/auth/sms-codes |
| cAuthSmsLogin | POST | /c/auth/sms-login |
| cAuthPasswordLogin | POST | /c/auth/password-login |
| cAccountResetPassword | POST | /c/account/password/reset |
| cAccountBindPhone | POST | /c/account/phone-binding |
| cAuthGetSession | GET | /c/auth/session |
| cAuthRefresh | POST | /c/auth/refresh |
| cAuthLogout | POST | /c/auth/logout |
| cAuthGetAttemptResult | GET | /c/auth/attempts/{attemptId}/result |
| cAuthGetSmsIntent | GET | /c/auth/attempts/{attemptId}/sms-intents/{requestId} |
| adminAuthCreateAttempt | POST | /admin/auth/attempts |
| adminAuthLogin | POST | /admin/auth/login |
| adminAuthGetRequirements | GET | /admin/auth/attempts/{attemptId}/requirements |
| adminAuthCreateCaptcha | POST | /admin/auth/captcha/challenges |
| adminAuthVerifyCaptcha | POST | /admin/auth/captcha/verify |
| adminAuthGetSession | GET | /admin/auth/session |
| adminAuthGetAttemptResult | GET | /admin/auth/attempts/{attemptId}/result |
| adminAuthLogout | POST | /admin/auth/logout |
| adminAuthActivity | POST | /admin/auth/activity |
| cAuthListMerchantMemberships | GET | /c/auth/merchant-memberships |
| merchantAuthCheckAdmission | GET | /merchant/auth/admission |
| adminAuthGetPermissions | GET | /admin/auth/permissions |
| adminListPermissionActions | GET | /admin/permission-actions |
| adminListOperatorAccounts | GET | /admin/operator-accounts |
| adminCreateOperatorAccount | POST | /admin/operator-accounts |
| adminUpdateOperatorAccount | PUT | /admin/operator-accounts/{operatorId} |
| adminSetOperatorAuthorization | PUT | /admin/operator-accounts/{operatorId}/authorization |
| adminDisableOperatorAccount | POST | /admin/operator-accounts/{operatorId}/disable |
| adminEnableOperatorAccount | POST | /admin/operator-accounts/{operatorId}/enable |
| adminResetOperatorPassword | POST | /admin/operator-accounts/{operatorId}/password-reset |
| adminListRoles | GET | /admin/roles |
| adminConfigureRole | PUT | /admin/roles/{roleId} |
| adminDisableRole | POST | /admin/roles/{roleId}/disable |
| adminEnableRole | POST | /admin/roles/{roleId}/enable |

仅两个CreateAttempt属于匿名bootstrap，security=[]。refresh也为security=[]但仅因OAS3不能表示请求体中的认证秘密，必须x-auth-mode=refresh-body、x-auth-credential=body.refreshToken及必填AuthRefreshRequest；测试单独识别，不能放宽任意匿名接口。其他attempt要求X-Auth-Attempt；Web同时要求绑定cookie及Origin（AND），cookie传输名为__Host-pet-admin-attempt。phone-binding的Bearer或attempt是OR，服务端还必须按模式检查body、拒绝同时两种凭据。OAS安全要求格式按[OAS3.0.3规范](https://spec.openapis.org/oas/v3.0.3.html)处理，body秘密与业务授权条件需要服务端校验。

## 4. 内部映射附录：新增完整字段仍待审

以下**只在planning提出**，不写进权威Java签名、不产生生产DTO。既定语义已同步07；本表用于下一步Contract Owner明确字段，非要求重裁D1/D2。

| 候选结构/端口 | 建议最小字段 | 来源/不能推定的点 |
|---|---|---|
| TrustedPrincipalRef | audience、sessionId、subjectId、operatorType、sessionGeneration | 仅服务端认证适配器构造，subject与operator映射需逐端确认；无客户端签名DTO自证授权 |
| ResourceScopeFact | resourceType/resourceId、merchantId?、storeId?、cityCode?、ownerUserId?、scopeVersion | 各资源Owner提供；历史订单城市应基于历史事实，缺快照不猜当前地址 |
| AuthorizationVersions | Owner→版本标签及快照引用 | 使用固定Owner白名单、每Owner单调版本，不接受任意客户端map；管理员原子revision与会话generation独立 |
| AuthDecision | allowed、checkedAt、versions、reasonCode | 不能作为后续长期通行证；未批准字段的准确类型/nullable/code域需附例复审 |
| SessionIdentityQuery | 当前服务端会话凭据引用 | 不通过公共CommandContext增加secret；分user/admin Owner |
| Membership/Admission queries | 已认证userId、候选merchantId/storeId、请求trace | Owner来源及STAFF绑定真实模型待MER交接，不能phone匹配或把userId塞staff列 |
| Resource authorization query | PrincipalRef、actionCode、ResourceScopeFact、purpose/case引用、EXECUTE或READ_RESULT | purpose是待核声明；调用方不得绕资源Owner构造虚假归属 |

关键未决：主账号执行核销如何对应MERCHANT_STAFF及SQL operator_staff_id；成员绑定/申请/签约事实的存储与API；跨Owner稳定版本的具体读取方法；ResourceScope历史城市事实。它们不会由HTTP字段名自动解决。冻结未明确写动作继续单列，不写允许或拒绝生产Mock。

## 5. 验证和完成边界

保留原28个Python合同回归及原16/13写/4创建的精确集合，新36操作有独立清单/安全/返回与Schema分支检查，不能仅把魔数16替换成52。必须使用真实OAS3 validator校验整份文档，并用实例validator验正反例，尤其nullable、oneOf互斥、未知字段、String ID、refresh秘密、phone-binding模式、scope、SMS未知状态和data:null。自足测试子集检查器仅补CI回归，不冒充完整标准validator。

运行Java21完整Maven和既有真实MySQL task-core测试；本任务新隔离MySQL数据目录/端口，创建与删除仅测试helper成功创建的随机库。不得缺数据库就skip，不连接现有业务服务，不以通过task-core测试证明AUTH存储已实现。

此阶段合同同步待合并，A/B1/C存储方案已接受、B0未选；后续仍须固定未完整内部字段与实际迁移/实现范围，再授权Web实际切片。两个CCR保持分项未完成，AUTH-001非DONE，不merge、不自动接业务代码。
