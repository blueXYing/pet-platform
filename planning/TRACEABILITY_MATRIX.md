# Requirement Traceability Matrix

> 追踪矩阵以增量方式维护：各日期小节登记当时的细化与刷新，不重新从零构建。运营端逐节细化见 2026-09-17 小节。

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
| 运营 PRD 6.2 | 商家/服务/排期/员工治理（逐节细化见 2026-09-17 小节） | Admin Governance | EPIC-18 | ADM-001,A-002 | 运营治理验收 |
| 运营 PRD 6.3 | 订单/退款/核销监管（逐节细化见 2026-09-17 小节） | Admin Transaction | EPIC-18 | ADM-002,A-003 | 交易P0 |
| 运营 PRD 6.7~6.8 | 售后终裁/评价治理（逐节细化见 2026-09-17 小节） | Admin Aftersale/Review | EPIC-11/14/18 | AFS-002,ADM-003,A-004 | AFS/REV |

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

## 运营端逐节细化与状态刷新（2026-09-17）

本节为“追踪矩阵+Catalog 状态刷新”维护登记（用户指示）。W2-* 为 WAVE_2_TEST_ACCEPTANCE.md 的可执行验收定义；PERM-001~006/WEB-001~002 为既有权限与运营Web锚点；全部为待执行验收定义，不声明已通过（各域合入阶段的既有证据以对应报告为准）。

### 运营 PRD 6.2 商家管理（ADM-001 / A-002 为主）

| PRD 节 | 验收点 | Issues | 测试锚点 |
|---|---|---|---|
| 6.2.1 入驻审核 | 审核队列/驳回原因/重提；审核通过转签约 | ADM-001,A-002 | W2-AUTH-003、W2-AUTH-004、W2-MER-001、PERM-002、PERM-005、WEB-002、W2-FE-001/002/005 |
| 6.2.2 商家台账与详情 | 台账查询/过滤/四层状态展示 | ADM-001,A-002 | W2-MER-001（分页/过滤/错误）、W2-FE-001、PERM-002/005 |
| 6.2.3 资质与签约管理 | 协议签署=电子协议勾选同意（SSOT §26）；同意幂等、版本/时间/账号审计；子账号不可代签 | ADM-001,A-002,AUTH-001 | W2-AUTH-004（签约事实按 25号补充）、W2-MER-001、PERM-002/005、审计既有约束 |
| 6.2.4 服务项目审核与管理 | 上/下架审核、禁用反例 | ADM-001,A-002,SVC-001 | W2-SVC-001~003、W2-FE-001/002 |
| 6.2.5 门店排期监管 | 排期异常/占用查看 | ADM-001,A-002,SCH-001~003 | SCH 系列 P0（SCH Issue Tests）、PERM-002/005 |
| 6.2.6 员工监管 | 员工启用/停用与归属 | ADM-001,A-002 | W2-MER-001（归属/跨店拒绝）、PERM-002/005 |
| 6.2.7 第三方团购渠道授权与券品映射 | 渠道授权/映射 | THD-001,ADM-001 | 3RD 系列、PERM-002/005 |
| 6.2.8~6.2.9 商家订单/结算信息 | 只读台账；结算资金范围未决 | ADM-001,A-002 | PERM-002/005；资金事实待 OD-W0-001，不虚构结算验收 |

### 运营 PRD 6.3 交易管理（ADM-002 / A-003 为主）

| PRD 节 | 验收点 | Issues | 测试锚点 |
|---|---|---|---|
| 6.3.1~6.3.2 订单总览/详情 | 四层状态一致性；DisplayOrderStatus 由 order 统一计算 | ADM-002,A-003 | W2-AUTH-003、ORD-001~011 各域 P0、PERM-002/004~006、WEB-001/002、W2-FE-001/002/005 |
| 6.3.3 改期与时段占用监管 | 改期一次/时段占用事实查看 | ADM-002,A-003 | ORD 系列（改期规则）、PERM-002/004~006 |
| 6.3.4 退款单管理 | 迟到支付自动全额/服务后24小时/部分退款仅运营裁决 | ADM-002,A-003,REF-001~004 | REF 系列 P0、W2-AUTH-003、PERM-004~006（含互斥/终局规则反例） |
| 6.3.5 平台核销记录 | 核销与 refund_order 互斥事实 | ADM-002,A-003,VER-001~002 | VER 系列、PERM-002/004~006 |

### 运营 PRD 6.7~6.8 售后/评价治理（ADM-003 / A-004 为主）

| PRD 节 | 验收点 | Issues | 测试锚点 |
|---|---|---|---|
| 6.7.1~6.7.2 工单池/处理 | 工单分派、处理留痕 | ADM-003,A-004 | W2-AUTH-003、AFS-001~002 系列、PERM-001/005/006 |
| 6.7.3 售后裁决终局 | 单运营首次正式裁决即终局（SSOT §24）；退款/核销互斥 | ADM-003,A-004 | AFS 系列 P0、PERM-001/005/006、W2-FE-002 |
| 6.8.1~6.8.2 评价审核/申诉 | 评价隐藏/恢复、申诉处理 | ADM-003,A-004,REV-001~002 | REV 系列、PERM-005/006、W2-FE-001/002 |

### 运营 PRD 6.1/6.4~6.6/6.9~6.13 其余运营范围（A-005 / A-001 为主）

| PRD 节 | 验收点 | Issues | 测试锚点 |
|---|---|---|---|
| 6.1 运营工作台 + 6.14 账号/角色/登录 | 运营登录（MFA 已取消按 SSOT §25）、RBAC、菜单权限 | AUTH-001,A-001 | WEB-001/002、PERM-001~006、W2-AUTH-003（PR17 已合入运营登录切片，完整权限范围仍按 CCR-PERM-001） |
| 6.4 优惠券管理 | 券模板/活动/实例台账 | CPN-001~002,A-005 | CPN 系列 P0/P1、PERM-002~006 |
| 6.5 积分与任务管理 | 积分只赚取/扣回不消费 | PTS-001~002,A-005 | PTS 系列、PERM-002~006 |
| 6.6 客户档案（平台视角） | 平台只读视角、数据范围隔离 | A-005,USR-001 | W2-AUTH-003（数据范围）、PERM-002/005 |
| 6.9~6.10 内容/社区/百科治理 | 动态审核、话题、百科配置 | COM-001,A-005 | 运营P1、PERM-002~006 |
| 6.11 基础台账表 | 字典/审计日志等台账只读与配置 | A-005 | PERM-002~006、审计既有约束（6.11.6 不可篡改） |
| 6.12 经营看板 | PRD 标注“可后期再做” | 未建 Issue | 不映射 V1 P0，启动时另登记 |
| 6.13 系统设置 | Banner/活动位等配置 | A-005 | 运营P1、PERM-002~006 |

### Catalog 状态刷新规则与清单

刷新依据为 2026-09-17 基线 `fd1fca3`（PR46/47 合并后）的已合入事实：DONE=整项 DoD 满足并经核验；IN_PROGRESS=已有合入阶段且剩余范围明确可推进；PLANNED=前置已解除待派发；BLOCKED=前置/门禁未解除。

| Issue | 旧 | 新 | 依据 |
|---|---|---|---|
| PLAT-005 | IN_PROGRESS | DONE | W3 按原 AC/DoD 核对全部满足（验收报告 §5，PR46 合并） |
| PLAT-006 | PLANNED | DONE | 六模块迁移+22号裁决§5回执（PR39~44/45 合并） |
| PLAT-002/003/004 | BLOCKED | IN_PROGRESS | 组件阶段已合入（PR10~20），生产启用等剩余范围见 BLOCKED_QUEUE |
| AUTH-001 | BLOCKED | IN_PROGRESS | PR14~17/31/33/36/38 阶段合入；W3 模拟器联调补 A/C 类证据；真机/正式环境/SMS 等剩余见挂账 |
| USR-001 | BLOCKED | IN_PROGRESS | PR22~24/31 合入；W3 联调验证读改写链路；生产 ID/迁移与完整 E2E 剩余 |
| C-002 | BLOCKED | IN_PROGRESS | PR25/34/38 合入；独立编辑页/VIS/真机剩余 |
| MER-001 | BLOCKED | PLANNED | OD-W0-002 已 RESOLVED（PR47，SSOT §26）；可派发 |

其余 Issue 维持 BLOCKED（真实前置未解除）。本次刷新不改任何 Allowed/AC/Tests 本体，不等于开发派发或验收通过声明。
