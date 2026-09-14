# CCR-PERM-001：单运营RBAC技术映射

## 最新实现阶段：本人权限查询候选

PR15已批准合入ab351ee。最小Web后端候选真实读取账号、有效角色/额外授予、范围及同Owner版本；本阶段生产领域动作目录为空，超管也不获未实现订单/退款占位动作，本人session/permissions正常鉴权。没有MFA、第二管理员或账号管理CRUD；默认Auth及迁移关闭。

代码be09654的CI34835331012真实MySQL/独立Redis/HTTP及原S2/Task组合196JUnit、82合同通过；最终head/CI以[Web交接](AUTH-001/web-login-handoff.md)及PR17回执为准。文件审计为可观察持久组件，生产不可篡改部署和宿主/迁移仍有门禁。此为本切片交付，不把全部27行治理或完整AUTH标DONE/CCR全RESOLVED，不merge。

批准回执（2026-09-14）：用户明确回复“可以 按照你描述的进行”，接受PR15/head `d42fdf3c260b612ef7ee4786f442b13bcb58000c`、tree `ac58dedd3ac18f425779da0ced38da165f94bb97` 中已交付的存储A、B1、C；B0未选。仅记录已批准范围，不改表结构、参数、样例或权威API，不执行SQL/实现。内部尚未完整字段/签名仍待具体审阅；PR15合并未授权，继续Draft。

## 最新阶段：权威映射候选与存储批准回执

PR14已按人工批准合入develop `643f05cd3a9357772bb3029ff97b750afeebb1ca`；下方旧合并门禁是历史记录。D1/D2、单运营及取消MFA不重复审批。

当前为 **ACCEPTED_MAPPING / SYNC_CANDIDATE_NOT_IMPLEMENTED**：外部RBAC/账号/角色/scope/extra授予/错误Schema候选同步至10/11/12，07只同步可信主体和D2原子快照/最终检查语义；尚未合并或上线。完整内部新字段/签名只在[映射附录](AUTH-001/contract-sync-handoff.md)待审。

[存储设计](AUTH-001/storage-design.md)中已交付A/B1/C表列索引、密码算法/回执介质与故障/恢复方案已接受，B0未选；不是已执行DDL或已实现存储。实际Web切片须后续精确授权、存储和S2生产ID等依赖就绪。未知领域动作/OD001资金范围不因此CONTRACT_READY，仍不RESOLVED或Issue DONE；[一页交接](AUTH-001/contract-sync-handoff.md)记录A/B1/C已批准，内部未完整字段仍需具体审阅，不泛化批准。

状态：**PROPOSAL_ACCEPTED / PENDING_CONTRACT_SYNC**。产品规则已批准，见SSOT §24与22-运营权限人工裁决补充-v1.0.md。AUTH-001-draft-v2的D1/D2技术方案已接受、待权威Contract同步，无需再次裁决是否单运营。

批准回执（2026-09-14）：用户明确批准“D1其他参数和D2”，对象为取消MFA后的AUTH-001-draft-v2，head `2963e3918bbd62afdfdeb55c60bb2e2221c67ca1`，tree `9761eb9f1c5da0fde70ab6be466ad3f63eb6ce09`。仅同步接受状态，不改变技术参数、正文样例或枚举；MFA继续取消。OD002、冻结未明确写动作、真实主认证Provider及Schema/实现门禁保留，PR14合并未授权；两CCR不RESOLVED、Issue不DONE。

## 当前审阅入口（2026-09-14取消MFA修订）

V1取消额外MFA的产品决定已批准，执行SSOT§25及[24号补充](../../docs/01-prd/24-取消MFA人工裁决补充-v1.0.md)。所有角色普通主认证完成后可进入会话，获权高风险动作不再要求第二因素；RBAC/范围/用途/审计/同人业务确认保留。D1其它参数及D2已明确接受，未授权PR合并或实现。根产品commit`9cdc8eeac94fbe673fef420cc7767caddf1ba219`已原样cherry-pick为`7e1c36e`，AUTH不编辑其4文件内容。

先读[一页审阅指南](AUTH-001/review-guide.md)。[RBAC提案](AUTH-001/rbac.md)包含六角色稳定码、原27行矩阵、账号动作/数据范围、具体请求响应、敏感信息和撤权时点；[会话](AUTH-001/sessions.md)包含独立Web账号密码登录/30分钟无操作/后登录踢旧协议。[正反例](AUTH-001/examples.md)、[来源与影响](AUTH-001/sources-and-gaps.md)、[审阅证据](AUTH-001/review-evidence.md)区分已接受方案与尚未同步权威Contract的字段。

推荐D2按当前可信动作/范围逐次检查，最终检查通过的短事务可完成；不承诺撤权追溯取消全部在途业务，不扩五字段Context或跨biz持锁。建议角色动作并集应用账号同一scope，超管仅已批准目录闭包。普通403不自动注销身份；敏感回执返回前仍核当前授权。

单人、编辑直发、超管范围不重裁，不新增secondApprover。财务默认读、已有执行显式授权，OD-W0-001资金范围不进入活动目录。Schema06缺账号/角色/会话等持久化定义，原主认证Provider、API/Schema同步和真实权限测试均未交付。本次仅继承根4个产品裁决文件并修订原9个规范Markdown，不把新权限码变成生产允许清单，不关闭CCR/完整Issue。

两CCR可分别阶段审查；后续重大Contract/同步/实现及PR合入按协议，未merge。根Work已正式释放并交接AUTH Writer，源基线为develop `bcb269c2adc9405e669747d9b3bedfae2bf5ccbd`。

## 原规范要求与产品批准范围（保留）

关联：AUTH-001（规范及服务端实现）、A-001（通用壳）、ADM-001～003与A-002～005及QA-005（相关业务接入）。Owner：Backend Core / Architect / Contract Owner；Admin与QA协作。保留既有Issue，不新增财务模块或审批流任务。

当前契约已有PLATFORM_OPERATOR、Bearer、RBAC、审计及服务端actions，缺少完整角色/动作/数据范围与权限管理DTO。本CCR按已批准规则补齐技术规范，具体接口/Schema尚未冻结。

规范输出要求：

1. 统一六角色的稳定标识；除超管默认全业务/全平台外，实际功能按RBAC配置，角色名称不作为后端授权捷径。
2. 由原PRD矩阵和22号补充生成动作权限映射；▲转换为获权单人执行，平台编辑可直接发布，财务功能仅限已批准动作，不出现新财务专用流程。
3. 记录账号/角色/权限及全部/城市/商家数据范围的请求响应、鉴权与缓存撤权语义；前端按钮与后端接口使用同一权限事实。
4. 不设计secondApprover、必须不同reviewer、待内部复核状态或审批队列。单人操作沿用既有requestId、原因/用途、二次确认及审计语义。
5. 超管不绕过交易资格/金额事实/脱敏/审计规则；用户或商家原有业务审核不因取消内部审批而失效。
6. 技术规范提交时披露API、Schema、Event是否变化，提供正反例、可复用Mock及迁移策略；不得直接以本草案新增字段写代码。

测试：PERM-001～006（定义于22号正式补充）、WEB-002与既有权限/资金/售后P0。A-001内部fixture可按确定产品规则验证通用壳，真实授权与SDK需等待具体契约批准；AUTH-001本身可建立规范，不形成自依赖。

这不是新的产品BLOCKER。OD-W0-003已经RESOLVED；本项是Contract Owner负责的实现前技术门禁。OD-W0-001资金范围、OD-W0-002签约接入仍独立未决。Wave 1工程壳已完成；本CCR仍限制后续真实接入，不因此关闭。
