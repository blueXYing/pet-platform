# CCR-W2-API-001：首批业务查询/写入与页面DTO缺口

> **2026-09-30 R1/R2批准回执（覆盖下方准备状态）**：用户明确“批准”[普通退款方案](CCR-W2-API-001/refund-application-proposal.md)。[Contract49](../../docs/04-api/49-Refund-Application-Contract-v0.1.md)及SQL49承接申请/决定/24h恢复、普通真实来源、渠道执行和成功释放；默认关闭实现及本地隔离验收见[实施记录](../progress/2026-09-30/refund-aftersale/IMPLEMENTATION.md)，PR96当前提交全量CI另行核对。批准不包括PR合并或生产启用；TASK core补最小公共恢复入口，沿既有租约和元数据校验，不跨域改表。整个CCR、AFS及公开端到端仍未完成。

> **2026-09-30最新接续（覆盖下方历史状态）**：PR95已合入develop `eb5d16f`，合并后CI36659585415六项通过；116份后端报告751项，失败/错误/跳过均0。用户授权按建议顺序继续，并已批准普通退款被拒绝后可再次申请、每次重计24h、同单同时一笔待处理、创建refund_order后禁止再申请，见SSOT §38和[PRD30回执](../../docs/01-prd/30-普通退款重复申请人工裁决补充-v1.0.md)。[退款R1/R2候选](CCR-W2-API-001/refund-application-proposal.md)已具体化申请/决定/24h恢复及可信普通来源；重大技术契约仍待审阅，未改正式API/Schema或业务实现。收尾/来源/代码/验收证据见[准备记录](../progress/2026-09-30/refund-aftersale/PREPARATION.md)；[员工绑定核销权限](CCR-W2-API-001/staff-verification-authority-preparation.md)只做独立后续准备。完整Issue和整个CCR不提前关闭，新PR不合并、不生产启用。

> 2026-09-30接续：PR94六项CI通过，115份后端报告730测试零失败/错误/跳过，仍OPEN未合并。用户授权按推荐顺序开始，已完成[验收收尾](../progress/2026-09-30/verification-completion/REVIEW-AND-PREPARATION.md)及[核销完成K1/K2候选](CCR-W2-API-001/verification-completion-proposal.md)。主账号真实操作者协议、核销/最小AFS同事务失效待审阅批准；不假称员工绑定、通用CREATE_REFUND或公开入口已完成，不自动合并。

> 最新核销回执：2026-09-29用户明确“批准 V1、V2”。[凭证方案](CCR-W2-API-001/verification-credential-proposal.md)已批准，由[47号正式契约](../../docs/04-api/47-Verification-Credential-Contract-v0.1.md)、SQL47及SSOT §36承接；下面PROPOSED为历史状态。实现与测试、提交PR已授权，运行默认关闭，完整核销/身份/售后/HTTP仍未完成，不自动合并或生产启用。

> 2026-09-29核销接续：用户已“93合入”并要求“开始下一步”；PR93合并`324cba1`，合并CI六项通过、698后端测试零失败/错误/跳过。真实核销码生命周期仍缺实现与存储，已准备[VER-001凭证内核V1/V2方案](CCR-W2-API-001/verification-credential-proposal.md)，状态PROPOSED_REQUIRES_REVIEW：V1冻结只读/幂等刷新与真实改期失效协议；V2解决PRD第3/第4次失败锁定歧义及刷新绕过。正式契约/业务源码未修改，默认关闭。下面“不合并”是各切片当时的范围，PR93已由随后明确授权完成合并。

> 最新回执：2026-09-29用户明确“批准 R1、R2、R3”。下条改期提案的PROPOSED状态是历史记录，已由本回执取代。实施以[46号正式契约](../../docs/04-api/46-Order-Reschedule-Contract-v0.1.md)、SQL46和SSOT §35为准；真实核销/读侧依赖、公开入口及完整Issue仍未完成，不提前关闭整个CCR，不合并或生产启用。

> 2026-09-29改期接续：PR92已获用户单独授权合入develop `be1cb68`，合并CI六项通过、674后端测试零失败/错误/跳过。用户授权按推荐顺序继续，已形成[ORD-003改期R1/R2/R3具体提案](CCR-W2-API-001/order-reschedule-proposal.md)：一单一预约下的原子交换与历史、旧任务取消和round1联动、核销/读侧缺失下的默认关闭首批范围；状态PROPOSED_REQUIRES_REVIEW，未修改正式合同或业务实现。已批准改期业务规则不重批，证据见[准备记录](../progress/2026-09-29/order-reschedule/PREPARATION.md)。下条商家切片“PR供审阅”是历史节点，已被本次合并授权取代。

> 2026-09-29最新接续：PR91已合并develop `1eb96ac`，合并CI六项通过、649后端基线测试零失败。用户现已“批准以上三项，按推荐方案继续”，[ORD-001商家确认/拒单及退款联动](CCR-W2-API-001/merchant-order-actions-proposal.md)D1/D2/D3状态APPROVED；45号正式Contract/Schema与SSOT §34及配套接口/事件/任务/测试同步，实现提交PR供审阅。全部运行开关默认关闭，当前PR不合并或生产启用；完整Issue依赖未解除。证据见[实现记录](../progress/2026-09-29/merchant-order-actions/IMPLEMENTATION.md)。

> 2026-09-16 状态同步：用户域已经历契约同步与后端/HTTP交付；不能恢复本地旧副本的“尚未交付”表述。其他域仍按下表独立评审，整个CCR不因用户域进展而RESOLVED。

状态：OPEN_SPEC_REQUIRED；可按下表域独立评审关闭，整个CCR不得因一个域完成而全部RESOLVED。不新增Issue，不生成公共SDK，不在本草案选择具体字段/路径/Provider。

证据：HTTP10 §3.1～3.3、§4/§5多为路由和行为列表；内部API07 §3～5若干Query/DTO仅名称引用；原Wave1盘点时OpenAPI11的16个交易核心操作不覆盖完整登录/宠物/商家服务/人员排期/运营CRUD。Wave1 Contract Smoke绿灯不能证明本CCR已完整。

| 域 | 规范责任Issue | 消费者 | 缺口及关联门禁 |
|---|---|---|---|
| 会话/准入/权限 | AUTH-001 | C002/M002/A002 | 复用CCR-ACR/PERM，不另造第二套会话；签约OD002仍有效 |
| 用户/宠物/个人资料 | USR-001，AUTH协作 | C002、TX001 | [用户域提案](CCR-W2-API-001/user-pet-domain-proposal.md)及权威同步已由PR22/23合入，PR24领域实现、PR31 C会话/HTTP接入已合入；前端真实联调、生产启用及尚缺展示字段按原Owner继续，其他域不自动解除 |
| 商家/门店/人员 | MER-001 | C003/M002/ADM001 | 2026-09-17三项已获批准：[回执](CCR-W2-API-001/merchant-product-decisions.md)；[27号权威契约](../../docs/04-api/27-Merchant-Domain-Contract-v0.1.md)、存储映射、07/10/11/12与SSOT/PRD同步；8个OpenAPI操作标NOT_IMPLEMENTED。已批内容不重复审阅，明确依赖与完整MER验收仍未完成；2026-09-22登记：C端门店两条读路由（/c/stores、/c/stores/{storeId}）由MER-001后续门店读侧切片承接（SVC-001提案§7） |
| 服务/快照/资格 | SVC-001 | C003/M002/ADM001 | ServiceBookabilityDTO/Query与页面响应；不提前实现排期/订单；[服务域提案v0.3](CCR-W2-API-001/service-domain-proposal.md)（2026-09-22人工批准SVC-D1～D5，[回执](CCR-W2-API-001/service-domain-decisions.md)；07/10/11/27权威同步随实现切片PR） |
| 可用性/排期管理 | SCH-001/002及原所属Owner | C003/M002/A002 | 对既有可用时间契约补页面所需未定义内容；原Wave3依赖不强拉到本波 |
| 运营治理 | ADM-001，AUTH协作 | A002 | 商家/服务/员工/排期操作及动作权限、数据范围、错误；原后置依赖保留；2026-09-22登记：service_item写入方（商家/运营服务管理CRUD）在"服务操作"范围内由后续ADM切片承接，M-002工作台消费（SVC-001提案§7） |

每域交付：逐个现有操作来源与Scope、请求/响应/校验/分页/错误/鉴权、ID金额String、写requestId、示例及正反Mock、Schema/Event影响、审批记录。真实后端是权限事实源，前端选择工作区不授权。受保护HTTP/OpenAPI/Internal/Schema修改须在草案获批后由明确Owner执行。

2026-09-17：本索引中“签约OD002仍有效”等旧外部Provider阻塞按SSOT §26/PRD25已解除解释；不恢复已解决裁决。商家域字段/路径候选仅在附属提案中定义，需审阅后再同步；没有生成可执行公共Mock或把候选升级成已批DTO。

前端可提交页面消费需求和差异，不能把内部工程fixture升级为已批准DTO。Contract Owner确认该域契约后可先做业务Mock阶段；完整Issue DONE仍需原AC/E2E、平台和VIS验收。

## 2026-09-16 C端接入字段差异回执

本轮不扩展契约。芯片号、疫苗/驱虫记录名称及日期、头像上传仍缺公共协议，真实模式仅显示未接通；设计样例只在显式预览。petType已是创建必填且不可改的已批字段，但原表单没有选择控件，本轮在创建前用原生action sheet明确选择DOG/CAT/OTHER，不沿用预览默认值。后续需对该原生交互及缺字段状态作视觉/真机确认。资料性别/签名仍归CCR-C002-PROFILE-001，不隐式丢字段后称整页保存成功。见[C端接入报告](../progress/2026-09-16/C_REAL_API_INTEGRATION.md)。


## 2026-09-24 当前接续

- PR77/78/79已合入develop 0c2d7ae：排期查询切片、窗口匿名/导航/草稿修复、宽松草稿NULL安全。
- SCH-002/SCH-004业务裁决见[联合回执](CCR-W2-API-001/schedule-review-decisions.md)。SCH-002待07/27/11权威同步与实现；SCH-004仍须冻结人员/占用并发保护、能力集合版本、双方向占用匹配契约，不自动列READY。
- SERVICE_COVER内部签名[补充CCR](CCR-W2-API-001/service-cover-signing-amendment.md)已批；本分支实现待PR审阅，用户随后开启mtxoss2版本控制，新上传对象的真实版本签名GET及19项兼容性复验通过；旧ETag对象未迁移，完整UI/真机链未重验。
# 2026-09-30 K1/K2 批准接续

用户回复“批准”，确认 [主账号核销方案](CCR-W2-API-001/verification-completion-proposal.md)中的 M94/K1/K2。PR94 已合入 develop `eb083cb`，合并后 CI36654304138 六项成功。K1/K2 正式协议见 [Contract48](../../docs/04-api/48-Verification-Completion-Contract-v0.1.md)、Schema48、SSOT §37、OrderVerifiedEvent.v2。内部实现与测试进行中，后续 PR 仅审阅，默认关闭；整个 CCR 及 VER-002/AFS-001/QA-004 不提前关闭。
