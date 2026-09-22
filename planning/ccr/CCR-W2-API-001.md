# CCR-W2-API-001：首批业务查询/写入与页面DTO缺口

> 2026-09-16 状态同步：用户域已经历契约同步与后端/HTTP交付；不能恢复本地旧副本的“尚未交付”表述。其他域仍按下表独立评审，整个CCR不因用户域进展而RESOLVED。

状态：OPEN_SPEC_REQUIRED；可按下表域独立评审关闭，整个CCR不得因一个域完成而全部RESOLVED。不新增Issue，不生成公共SDK，不在本草案选择具体字段/路径/Provider。

证据：HTTP10 §3.1～3.3、§4/§5多为路由和行为列表；内部API07 §3～5若干Query/DTO仅名称引用；原Wave1盘点时OpenAPI11的16个交易核心操作不覆盖完整登录/宠物/商家服务/人员排期/运营CRUD。Wave1 Contract Smoke绿灯不能证明本CCR已完整。

| 域 | 规范责任Issue | 消费者 | 缺口及关联门禁 |
|---|---|---|---|
| 会话/准入/权限 | AUTH-001 | C002/M002/A002 | 复用CCR-ACR/PERM，不另造第二套会话；签约OD002仍有效 |
| 用户/宠物/个人资料 | USR-001，AUTH协作 | C002、TX001 | [用户域提案](CCR-W2-API-001/user-pet-domain-proposal.md)及权威同步已由PR22/23合入，PR24领域实现、PR31 C会话/HTTP接入已合入；前端真实联调、生产启用及尚缺展示字段按原Owner继续，其他域不自动解除 |
| 商家/门店/人员 | MER-001 | C003/M002/ADM001 | 2026-09-17三项已获批准：[回执](CCR-W2-API-001/merchant-product-decisions.md)；[27号权威契约](../../docs/04-api/27-Merchant-Domain-Contract-v0.1.md)、存储映射、07/10/11/12与SSOT/PRD同步；8个OpenAPI操作标NOT_IMPLEMENTED。已批内容不重复审阅，明确依赖与完整MER验收仍未完成 |
| 服务/快照/资格 | SVC-001 | C003/M002/ADM001 | ServiceBookabilityDTO/Query与页面响应；不提前实现排期/订单；[服务域提案草案](CCR-W2-API-001/service-domain-proposal.md)（2026-09-22，含依赖核验，待人工批准SVC-D1～D4） |
| 可用性/排期管理 | SCH-001/002及原所属Owner | C003/M002/A002 | 对既有可用时间契约补页面所需未定义内容；原Wave3依赖不强拉到本波 |
| 运营治理 | ADM-001，AUTH协作 | A002 | 商家/服务/员工/排期操作及动作权限、数据范围、错误；原后置依赖保留 |

每域交付：逐个现有操作来源与Scope、请求/响应/校验/分页/错误/鉴权、ID金额String、写requestId、示例及正反Mock、Schema/Event影响、审批记录。真实后端是权限事实源，前端选择工作区不授权。受保护HTTP/OpenAPI/Internal/Schema修改须在草案获批后由明确Owner执行。

2026-09-17：本索引中“签约OD002仍有效”等旧外部Provider阻塞按SSOT §26/PRD25已解除解释；不恢复已解决裁决。商家域字段/路径候选仅在附属提案中定义，需审阅后再同步；没有生成可执行公共Mock或把候选升级成已批DTO。

前端可提交页面消费需求和差异，不能把内部工程fixture升级为已批准DTO。Contract Owner确认该域契约后可先做业务Mock阶段；完整Issue DONE仍需原AC/E2E、平台和VIS验收。

## 2026-09-16 C端接入字段差异回执

本轮不扩展契约。芯片号、疫苗/驱虫记录名称及日期、头像上传仍缺公共协议，真实模式仅显示未接通；设计样例只在显式预览。petType已是创建必填且不可改的已批字段，但原表单没有选择控件，本轮在创建前用原生action sheet明确选择DOG/CAT/OTHER，不沿用预览默认值。后续需对该原生交互及缺字段状态作视觉/真机确认。资料性别/签名仍归CCR-C002-PROFILE-001，不隐式丢字段后称整页保存成功。见[C端接入报告](../progress/2026-09-16/C_REAL_API_INTEGRATION.md)。
