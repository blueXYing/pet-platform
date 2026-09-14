# Requirement Traceability Matrix（初始高层版）

> 这是启动级追踪矩阵。Work 后续只需增量细化，不需要重新从零构建。

| PRD来源 | 需求 | Capability | Epic | Issues | Tests |
|---|---|---|---|---|---|
| C PRD 5.1.15/5.1.16 | 预约服务/订单确认支付 | Schedule + Order + Payment | EPIC-05/06/07 | SCH-001~003,TX-001,PAY-001~003,C-003,C-004 | ORD/PAY/CON系列 |
| C PRD 5.1.17~5.1.19 | 订单详情/核销码/改期退款 | Order + Verification + Refund | EPIC-06/08/09/10 | TX-002,ORD-003,REF-001~004,VER-001,C-004,C-005 | REF/VER/RES系列 |
| C PRD 5.1.27~5.1.28 | 评价/售后投诉 | Review + Aftersale | EPIC-11/14 | AFS-001,AFS-002,REV-001,C-005 | AFS/REV系列 |
| C PRD 5.1.21~5.1.22 | 优惠券/积分 | Coupon + Points | EPIC-12/13 | CPN-001~002,PTS-001~002,C-006 | CPN/PTS系列 |
| C PRD 5.1.5~5.1.12 | 社区/创作/消息 | Community + Notification | EPIC-15/16 | COM-001,NTF-001,C-006 | MSG + 社区验收 |
| 商家 PRD 5.3 | 订单接单/改期/退款/核销联动 | Order/Refund/Verification | EPIC-08/09/10 | ORD-001~003,REF-001~004,VER-001~002,M-003,M-004 | CONF/RES/REF/VER |
| 商家 PRD 5.4~5.6 | 排期/服务/员工 | Schedule/Service/Merchant | EPIC-03/04/05 | MER-001,SVC-001,SCH-001~003,M-002 | ORD/SCH |
| 商家 PRD 5.9 | 第三方团购核销 | ThirdParty | EPIC-17 | THD-001,M-004 | 3RD系列 |
| 运营 PRD 6.2 | 商家/服务/排期/员工治理 | Admin Governance | EPIC-18 | ADM-001,A-002 | 运营治理验收 |
| 运营 PRD 6.3 | 订单/退款/核销监管 | Admin Transaction | EPIC-18 | ADM-002,A-003 | 交易P0 |
| 运营 PRD 6.7~6.8 | 售后终裁/评价治理 | Admin Aftersale/Review | EPIC-11/14/18 | AFS-002,ADM-003,A-004 | AFS/REV |

## Wave 1 工程基础追踪补充（2026-09-11）

仅补齐已有 Issue 的来源，不新增产品需求。具体 AC 见 READY_QUEUE_WAVE_1.md。

| 来源 | Capability | Epic / Story | Issues | Tests |
|---|---|---|---|---|
| 工程流水线规范；AGENTS.md；Work Protocol §5/6 | 工程治理/基线/架构门禁 | EPIC-00 / ST-GOV-01 | GOV-001,GOV-002 | ARCH-001~005（各 Issue 取其要求子集） |
| 技术基线 §1~3；Maven骨架设计 §1~9 | Platform Foundation | EPIC-01 / ST-PLAT-01 | PLAT-001 | ARCH-001~003、Maven verify |
| 三端最终 PRD 的对应终端形态；HTTP Contract §1~2；工程流水线规范 | 三端工程基础 | EPIC-20 / ST-FE-C-01,ST-FE-M-01,ST-FE-A-01 | C-001,M-001,A-001 | typecheck、smoke |
| 已批准ACR-001 R1/R2/R3/R6；前端基线v0.7 | 单小程序双工作区 + React运营Web | EPIC-20 / ST-FE-C-01,ST-FE-M-01,ST-FE-A-01,ST-QA-01,ST-QA-03 | C-001,M-001,A-001,QA-001,QA-005 | MINI-001~006、WEB-001~002（各任务取对应阶段），原E2E-01~08 |
| 商家PRD §5.2；ACR-001 R4；CCR-ACR-001 | 会话与工作台上下文隔离 | EPIC-02 / ST-ID-01；EPIC-20 / ST-FE-C-01,ST-FE-M-01 | AUTH-001,C-001,M-001,C-002,M-002 | MINI-002~004、WEB-002；真实接入须CCR批准 |
| 人工原稿切图一比一要求；ACR-001 R5；SSOT V1范围 | 原始素材追踪与逐页还原 | EPIC-20 / ST-FE-C-01,ST-FE-M-01,ST-QA-03及原页面Epic/Story | C-002~006,M-002~004,QA-005 | VIS-001~004；逐页节点映射在对应Issue细化 |
| 全链路测试矩阵 ARCH-001~005；测试发布门禁；OpenAPI-Core | QA / Contract Smoke | EPIC-20 / ST-QA-01 | QA-001 | ARCH-001~005、Contract Smoke、CI首轮 |

## OD-W0-003权限裁决追踪

| 来源 | 能力 | Epic/Story | Issues | Tests |
|---|---|---|---|---|
| 人工P1～P6；SSOT §24；22号PRD补充；CCR-PERM-001 | 单运营RBAC、直接发布、超管权限与审计 | EPIC-02/ST-ID-01；EPIC-18/ST-ADM-01～04；EPIC-20/ST-FE-A-01、ST-QA-03 | AUTH-001,A-001～005,ADM-001～003,QA-005 | PERM-001～006、WEB-002及既有业务P0 |

## W4/Wave2追踪修正（2026-09-14）

W3-TRACE-002已以现有Story修正12个Issue的主链：Catalog Story表示主要业务Story，与Issue Epic一致；ISSUE_STORY_LINKS.csv保留原ST-FE锚点及跨域次级Story。未增加Story/Issue或删除复合页面Scope。下游验收同时覆盖主链和次级关联，不能仅验主Story。

| Issues | 主Story | 次级能力保留 |
|---|---|---|
| C-002 | ST-ID-01 | 宠物/个人资料及原用户前端工程 |
| C-003 | ST-SVC-01 | 商家查询、可用性及原用户前端工程 |
| C-004 | ST-ORD-01 | 展示、支付、核销码及原前端工程 |
| C-005 | ST-AFS-01 | 改期、退款、评价及原前端工程 |
| C-006 | ST-COM-01 | 百科、优惠券、积分、消息及原前端工程 |
| M-002 | ST-MER-01 | 身份、入驻、服务、排期及原商家前端工程 |
| M-003 | ST-CONF-01 | 改期、订单查询及原商家前端工程 |
| M-004 | ST-AFS-01 | 退款、核销、评价、第三方核销、消息及原前端工程 |
| A-002～005 | ST-ADM-01～04对应条目 | 原运营前端工程Story保留为次级 |

EPIC-19/ST-CS-01仅为原人工客服承载追踪，未生成新实现Issue，解除条件见OPEN_DECISIONS第4项。本规划补充测试定义见WAVE_2_TEST_ACCEPTANCE.md，都是待执行用例，不声明已通过。
