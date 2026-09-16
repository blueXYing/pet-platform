# CCR-ACR-001：统一小程序会话与工作台上下文契约

> 2026-09-16 状态同步：PR14/15/17/31/33对应规范及运营/C端切片已合入；其余认证、商家准入和生产联调范围仍开放。CCR不整体RESOLVED，不重复请求历史已合并PR。 下文保留提案/交付时点的记录，本段只更新当前阶段事实，不改技术约定。

## 最新实现阶段：最小Web切片候选

PR15已批准合入ab351ee；已接受B1权威旧“提交后完整60秒”表述已按precommit数据库锚点同步。仅运营Web9配套+本人权限查询形成真实后端候选，默认关闭，17admin表放独立opt-in迁移目录，没有生产迁移、默认账号或C/M签约成功替身。Admin本Owner内部字段由团队复核并同步07，其余跨Owner/商家字段不泛化。

候选代码be09654的CI34835331012实际MySQL/专用Redis/HTTP及原组件组合196JUnit、82合同通过；最终交接head/CI见[Web交接](AUTH-001/web-login-handoff.md)与PR17回执。此阶段不代表统一小程序/C-M真实准入完成；OD002、冻结未明写动作、成员/Provider与生产宿主门禁保留，CCR非全RESOLVED、AUTH-001非DONE，不merge。

批准回执（2026-09-14）：用户明确回复“可以 按照你描述的进行”，接受PR15/head `d42fdf3c260b612ef7ee4786f442b13bcb58000c`、tree `ac58dedd3ac18f425779da0ced38da165f94bb97` 中已交付的存储A、B1、C；B0未选。仅记录已批准范围，不改表结构、参数、样例或权威API，不执行SQL/实现。内部尚未完整字段/签名仍待具体审阅；PR15合并未授权，继续Draft。

## 最新阶段：权威映射候选与存储批准回执

PR14已按人工批准合入develop `643f05cd3a9357772bb3029ff97b750afeebb1ca`，下方“PR14未授权合并”仅历史回执，不覆盖最新事实。D1/D2及取消MFA均已批准。

当前为 **ACCEPTED_MAPPING / SYNC_CANDIDATE_NOT_IMPLEMENTED**：07同步已批准内部语义；10/11/12同步外部36操作/字段/错误与安全组合候选，尚未合并或实现。内部PrincipalRef/ResourceScope/versions及新签名未完整冻结，仅放[映射附录](AUTH-001/contract-sync-handoff.md)待审；不把Merchant新单资格当工作台准入。

[存储设计](AUTH-001/storage-design.md)中已交付A/B1/C方案已接受，B0未选；待后续存储契约同步/迁移实施，无DDL执行。OD002、冻结未明写动作、成员绑定/主账号员工映射、真实主认证Provider及业务验收仍保留；本CCR非RESOLVED，AUTH-001非DONE。必看[一页交接](AUTH-001/contract-sync-handoff.md)，不重复D1/D2审批。

状态：**PROPOSAL_ACCEPTED / PENDING_CONTRACT_SYNC**。方向批准：2026-09-11人工确认ACR-001 R4。AUTH-001-draft-v2的D1其它参数及D2方案已接受，待权威Contract同步；本记录不是可直接实现的HTTP契约，也不解除真实签约/鉴权门禁。

批准回执（2026-09-14）：用户明确批准“D1其他参数和D2”，对象为取消MFA后的AUTH-001-draft-v2，head `2963e3918bbd62afdfdeb55c60bb2e2221c67ca1`，tree `9761eb9f1c5da0fde70ab6be466ad3f63eb6ce09`。仅同步接受状态，不改变技术参数、正文样例或枚举；MFA继续取消。OD002、冻结未明确写动作、真实主认证Provider及Schema/实现门禁保留，PR14合并未授权；两CCR不RESOLVED、Issue不DONE。

## 当前审阅入口（2026-09-14取消MFA修订）

V1取消额外MFA的产品决定已批准，来源为根Work固定`9cdc8eeac94fbe673fef420cc7767caddf1ba219`，本分支cherry-pick为`7e1c36e`；执行SSOT§25及[24号补充](../../docs/01-prd/24-取消MFA人工裁决补充-v1.0.md)。普通登录/手机号校验/密码重置证明保留；D1其它参数及D2已由后续明确批准接受，依据见本页回执。

先读[一页审阅指南](AUTH-001/review-guide.md)，D1推荐共享小程序可撤销会话、客户端选择加每次服务端准入查询；D2推荐最终授权检查时点及旧回执当前权限复核。请求响应/字段/匿名认证与秘密重试见[会话](AUTH-001/sessions.md)，商家/门店/子账号/存量例外见[准入](AUTH-001/admission.md)，[样例](AUTH-001/examples.md)、[来源与影响](AUTH-001/sources-and-gaps.md)、[审阅证据](AUTH-001/review-evidence.md)可独立检查。

源基线develop `bcb269c2adc9405e669747d9b3bedfae2bf5ccbd`。根Work已在PLAT002 S2固定提交并释放Writer后正式交接AUTH规范主写；初版只改两个CCR及AUTH-001附属Markdown。本修订按根明确授权继承4个产品裁决文件，AUTH仅编辑原9个规范Markdown；未自行编辑根产品内容，不改API/Schema/代码/SDK。以下旧登记中的“此次不派发”是原历史阶段记录，不覆盖最新交接。

OD-W0-002签约Provider、成功/待签/失败/未知事实仍BLOCKED；冻结核销/退款/售后补证等未明确动作另列B-FROZEN-WRITE。已定冻结读取/申诉与下线存量履约保持，不统一禁入。微信登录手机号绑定规则已实际读取，临时认证attempt不是业务登录。主账号/登录员工身份映射、原C端SMS主登录/重置Provider、Schema持久化仍有后续门禁；不因独立章节可审而整包RESOLVED。

本阶段仅已接受方案的状态记录及结构/样例检查，未运行真实登录/鉴权/签约及业务E2E。方案接受、文档合入、权威契约同步和实现分阶段授权，完整AUTH-001不DONE。

## 原缺口与解锁要求（保留）

提出方：ACR-001。关联Issue：AUTH-001（负责建立契约）、C-001/M-001（壳与内部fixture）、C-002/M-002及后续真实接入。责任：Backend Core提出完整规范，Architect/Contract Owner评审；C-End、Merchant、QA参加对应Issue协作。此次不创建开发任务或派发评审。

当前依据：商家最终PRD §5.2；HTTP Contract §2鉴权/幂等及§3.1登录；内部API与API分区。已有同微信账号双身份、切换不登出、工作台每次校验权限等产品规则，本CCR不新增身份/业务能力。

## 待补齐的规范

1. 登录会话与可用工作区/商家/门店权限响应：字段、类型、数据范围、真实服务端来源；不允许前端自报staffId获得授权。
2. 明确切换是客户端选择加服务端查询，还是需要服务端命令；是否新增路径待规范评审，不预造endpoint。若有写入必须沿用requestId幂等。
3. 准入判定映射：审核、签约、门店状态、子账号启用及被下线/冻结时存量履约/售后可用动作；不得以统一禁入抹掉SSOT例外。
4. 401/403/查询失败/权限撤销后的会话失效、刷新与重试语义；运营Web会话与小程序隔离。
5. 用户/商家/门店/工作区缓存键与在途请求失效规则；深链越权检查。
6. 提供正反例及已批准Mock/schema，供前端和QA使用；未批准内部fixture不得生成公共SDK。

API影响：需完整定义；Schema/Event影响：未决定，若需要变化必须一并披露批准；前端影响：统一共享登录、请求、上下文隔离；测试影响：MINI-002～004、WEB-002及原有权限P0。产品裁决：不新增规则；如果发现上位规则冲突，转产品裁决，不静默选择。

## 解锁条件

- AUTH-001可在自身范围内先建立本契约；该Issue不能在规范缺失时声称鉴权实现完成。
- C-001/M-001可用本地注入fixture推进通用壳；不依赖AUTH完成，也不得冒充真实签约或工作台准入已完成。
- 真实会话/权限接入前：Contract Owner批准字段/错误/接口与Mock，同步HTTP/OpenAPI及必要Internal API，运行破坏性变更检查，记录Schema/Event无变化或已披露变化，再解除本CCR。
- OD-W0-002签约仍未决；OD-W0-003已由后续人工权限决定关闭，单运营RBAC具体映射由CCR-PERM-001建立，不将产品批准误作API规范批准。
