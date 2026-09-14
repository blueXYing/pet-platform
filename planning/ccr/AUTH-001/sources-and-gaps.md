# AUTH-001 来源、缺口和影响

**PROPOSED / PENDING_REVIEW · AUTH-001-draft-v1**。固定阅读基线develop `bcb269c2adc9405e669747d9b3bedfae2bf5ccbd`；S2 PR13未合入不影响本规范。根工作区WORK_STATE v2.9最新持续授权及正式Writer交接优先本分支旧“Wave2未启动”历史记录。未更改根dirty台账。

## 1. 已读资料与准确定位

DOCX只在内存读取word/document.xml；以下body[n]为XML body直接子节点一基序号，p[n]为所有w:p一基序号（含表格）。不是伪造文件行号。源内容保持原件。

| 资料 | 定位 | 影响 |
|---|---|---|
| AGENTS.md / WORK_EXECUTION_PROTOCOL.md | 全文 | 资料优先级、唯一Owner、合同缺口CCR、禁止跨biz与未测报完成 |
| planning/issues/wave-2/AUTH-001.md | AC1–6 | 两CCR先建立，真实实现/SDK后置 |
| planning/WAVE_2_PLAN.md / READY_QUEUE_WAVE_2.md | 协作/规范阶段 | BackendCore一次一个主Issue，规范阶段非完整DONE |
| docs/00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md | 第18–19行；§15第655行；§24第1115行起 | 三登录/不随机密码；下线存量履约；单运营权限批准 |
| docs/01-prd/02-PRD-C端用户-V1.0-最终基线.docx | §5.1/5.1.1，body[63–73]，p[132/142–144]；设置字段body[944] | 微信+手机号才成功，手机号冲突联系客服不合并；游客可浏览，登录回跳；换绑用微信证明 |
| docs/01-prd/03-PRD-商家端-V1.0-最终基线.docx | §5.2 p[151–215]；§5.6 p[819/826/835]；§5.7 p[933]；§5.9 p[1181/1190/1193–1194]；§6.6 p[2051–2055] | 每次重验/同账号切换；角色范围；冻结读取/处罚申诉；冻结普通售后写未明；下线按SSOT覆盖 |
| docs/01-prd/04-PRD-运营端-V1.0-最终基线.docx | §4.1 body[45]、§4.2 body[49]、§4.3 body[52]；六登录页body[105–117]；§6.14 body[750–763]；§10.4/10.5 body[813–822] | 原27行矩阵、敏感用途/水印、Web30分钟/踢旧登录/MFA6位5分钟、审计 |
| docs/01-prd/22-运营权限人工裁决补充-v1.0.md | 第22/33/39/51行及§1–4 | 确定性覆盖旧双人规定；财务显式赋权；超管不越业务/敏感约束 |
| docs/04-api/23-公共接口与幂等契约补充-v0.1.md | §3–6，第28–62行；§7，第68行 | 五字段Context、可信actor/scope、当前权限重放、失败关闭、AUTH撤权与匿名/秘密Owner边界 |
| docs/04-api/10-HTTP-API-Contract-v0.4.md | §2.2第54–62行；§3.1第199行；第1080/1191/1428/1460/1607行 | Bearer、登录仅用途、RBAC/异常关闭、服务端actions和审计、会话DTO未批 |
| docs/04-api/07-内部API-Contract-v0.6.md | 第28/110/155行及§21 | UserBasicDTO/新单资格不是准入、跨模块本地API边界 |
| docs/04-api/11-OpenAPI-Core-v0.4.yaml | paths第22行起、16操作 | 核心交易范围，没有AUTH与RBAC完整操作 |
| docs/04-api/12-Error-Code-Registry-v0.5.md | 第28–38行 | 通用401/403/404/409/429/503，沿用非新增错误码 |
| docs/03-database/06-核心数据库Schema-v0.1.sql | 第13/30/70/101行 | 用户身份已部分存在；签约标识不证成功；staff缺登录user绑定 |
| docs/03-database/13-Async-Infra-Schema-v0.1.sql | 全表清单 | 异步设施不承担账号/会话/角色持久化 |
| docs/05-events/08-Integration-Event-Catalog-v0.6.md | 第303行MerchantDisabledEvent | 下线事件已有，权限撤权事件未定 |
| planning/OPEN_DECISIONS.md | OD-W0-001/002/003 | 资金/签约未决，权限产品已决 |
| planning/ccr/CCR-ACR-001.md / CCR-PERM-001.md | 原规范要求 | 字段/准入/401403/撤权/角色范围/影响披露 |
| planning/WAVE_2_TEST_ACCEPTANCE.md | 第19–22行 | 规范与真实测试区分、W2-AUTH001–004 |
| docs/07-testing/21-前端与小程序验收补充-v0.1.md | 第8/20行 | MINI/WEB/平台证据不能互代 |

## 2. 已有物理事实不够做什么

SQL06 user_account有phone/password_enabled/status，user_auth_identity有(app_id,open_id)身份唯一键；不能从一个微信code字符串创建可信用户。merchant.owner_user_id可关联主账号；merchant_staff只有门店/姓名/电话/在职/服务启用，没有user_id/登录绑定或子账号动作授权；手机号相同不证明身份。主账号如何作为MERCHANT_STAFF/核销operator_staff_id是具体Schema/内部映射门禁。

merchant.status没有完整入驻审核四态，provider_merchant_no没有签约状态、失败原因、验真/查单版本；不能反推审批或签约成功。SQL没有平台运营账号/角色/授予/scope/会话/撤销及通用权限审计表。缺口说明逻辑事实仍待设计，不代表本次新增任何表。

业务ID与epoch建议来自已接受23号公共约定；本AUTH不实现ID/Clock、不修改S2。身份注册等实际写入需要生产公共提供器及已批准持久化适配，不能用test SequenceIdStub上线。

## 3. 三端当前实际边界

| 端 | 已有源码与行为 | 本包后续接入要求 |
|---|---|---|
| C共享 | frontend-miniapp/src/shared/workspace.ts:20–45，replace增epoch/清缓存；workspace-react.tsx:6注入consumerFixture；request.ts:12只有路径/非空requestId | 不注入默认用户当登录；增加受控匿名认证链路、Bearer、刷新、401/403；UUID词法按23号验证 |
| M | merchant/admission.ts:3的allowed布尔和realAdmission抛CCR_ACR_001_NOT_APPROVED；workspace.ts:34及页面show/hide重验 | LIMITED/DENIED状态、具体来源与动作，不能用fixture生成SDK；深链和旧请求隔离保持 |
| Admin | frontend-admin/src/fixture.ts:3 sample.*私有；request.ts:27–29对401/403都清token；README:32真实绑定待规范 | 普通403保持身份且刷新权限；Web独立会话/MFA、真实动作范围与资源actions分开 |
| QA | e2e/contract_smoke.py:96要求非空security；e2e/test_contract_smoke.py:18与backend/pet-common/src/test/java/com/petplatform/common/S1ContractMappingTest.java:81固定16操作 | 未来同步匿名安全继承/白名单和新增操作覆盖；本阶段不改测试代码、不拿旧CI证明AUTH |

## 4. 提案影响清单及Owner

| 类别 | 后续拟影响 | 解除条件；本阶段未修改 |
|---|---|---|
| HTTP10 / OAS11 | 会话文档各表新增认证attempt/session/refresh/logout、WebMFA/activity；准入两查询；RBAC账号角色授权接口及schema/响应/错误 | AUTH/Contract Owner逐操作审查、生成获批Mock；已有路径只复用名称，不冒充DTO已定 |
| Internal07 | Principal解析、成员/准入事实、AuthorizationQueryApi及资源范围DTO | api Owner审查，五字段Context保持，不跨biz Repository |
| Error12 | 通用码沿用；准入reasonCodes为成功资格查询业务状态，不擅自新增全局错误码 | 核对错误映射及防枚举；将来新增专码须另披露 |
| Schema06 / 迁移 | 会话/refresh撤销generation、认证attempt证明/回执墓碑、Web账号/MFA因素、六角色与授权/scope/version、成员绑定、申请/签约事实、必要审计 | 原Owner逐字段/唯一键/加密/保留/历史兼容与回滚审查；无DDL自动授权 |
| Event08 / Scheduler09 | 本提案首选权威同步查询核授权，无新增撤权事件；若缓存通知/认证秘密清理采用事件任务，须后续登记版本/Owner | 不借已有MerchantDisabledEvent重载角色撤权；不写Scheduler |
| Provider | 微信身份及手机号交换、短信验证码/重置、WebMFA因素投递和恢复；签约OD002接口/状态/回调/查单 | 提供实际官方材料、能力/错误映射与安全配置；不擅选签约商、不默认成功 |
| 前端/测试/CI | 私有fixture迁移、匿名OAS测试、新增登录权限操作覆盖、真实MFA/准入/越权/撤权E2E | 后续明确Writer/批准公共合同；本包不改任何这些文件 |

## 5. 真正缺失与无需重裁

- OD002 Provider成功/失败/待签/未知事实不可自行补；支付渠道身份不能当签约接口。
- B-FROZEN-WRITE：冻结明确历史/待处理售后读取和处罚申诉；冻结核销/退款处理/售后补证逐动作缺明确规则。此部分不提供虚构生产允许/拒绝矩阵；下线存量继续履约已由SSOT定，不重裁。
- B-MFA-PROVIDER：两阶段6位/5分钟已定，具体投递/校验因素及单运营绑定/丢失恢复输入缺失；建议已登记验证手机号短信方案需审阅，不以账号字符串直接作为第二因素。
- B-AUTH-STORAGE：逻辑协议可审，生产会话/认证专用幂等/成员归属/权限版本一致性与迁移尚未实现或批准DDL。
- D1/D2技术建议负责形成具体方案；JWT/opaque、路径、角色码、失败阈值、版本读取/撤权时点不是让用户重新做产品设计。
- 单运营、编辑直发、超管仅已批准范围、财务通用RBAC、原因用途/脱敏/审计、交易硬规则均按批准来源直接执行。

## 6. 源文件指纹

只读SHA-256如下，用于固定原始Word及关键公共源文件；Git基线固定其余受控文档。指纹不代表产品/Contract新增批准。

| 源文件（路径见§1） | SHA-256 |
|---|---|
| C端PRD02 | D4277076820CC8953CCBCE9E672827F1B4D7A9A4EC4CB3081EB661AAB7D66FF0 |
| 商家PRD03 | FD1A60AD64B42CC1191567A9961242A359688A99E6750DABB8A7224147B2753B |
| 运营PRD04 | 43C5578A23A882B1CD77ACD3E24F88C75380DE012371D75DFC61FD09403E41F9 |
| 22号权限补充 | 1790A6391988E1F3E18BE111A83081B35BB2525D0B033DBAD054A612FF260974 |
| SSOT01 | EF6CF2B845150FCE6366291214E63AA3E16DD20E93DC774177E65AE0FE343614 |
| 23号公共补充 | 952B094013B9A55D58C851AAD90DC9D9569D102500AD1C91E58F394A4487FDF9 |
