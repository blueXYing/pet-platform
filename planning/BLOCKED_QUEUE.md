# Blocked Queue

更新：2026-09-17，W3 验收基线 develop `37350d0`（PR38～#45 合入后）。本页只列当前未解决或未验收范围；已合入阶段详见[Ready Queue](READY_QUEUE_WAVE_2.md)和[合并台账](progress/2026-09-16/PROGRESS_SYNC.md)。不再把“Wave2未启动、无公共ID接口、商家Figma尚未提供”等历史事实作为当前阻断。

## 仍需处理的范围

| 范围 | 剩余项 | Owner与解除条件 |
|---|---|---|
| ID及基础设施生产启用 | Hutool组件已合入；真实宿主退出证明、节点/高水位恢复、生产初始化/迁移/装配仍待验收 | PLAT-002；按[组件交接](../backend/pet-id-core/HANDOFF.md)保留默认拒绝和生产限制 |
| Durable AsyncTask完整范围 | Worker/Lease已合入；producer、DEAD对账告警、真实业务Handler及生产装配尚缺 | PLAT-004和业务Owner；见[任务组件交接](../backend/pet-task-core/HANDOFF.md) |
| Outbox完整范围 | 契约与组件已合入；生产迁移、启用、业务生产者/消费者、对账告警/归档尚缺 | PLAT-003及各域Owner；见[实现交接](ccr/CCR-W0-001/implementation-handoff.md) |
| 真实认证接入与商家准入 | 已有运营/C端后端切片与PR38页面接入；W3已验真实wx.login→code2session→落库及替身全链路（模拟器）；真机getPhoneNumber/键盘/授权、正式环境链路、未实现认证端点、商家身份/签约等仍未完成 | AUTH-001、C/M/A及各域；CCR-ACR/PERM不因局部交付整体关闭；证据类型边界见[W3验收 §4.2](progress/2026-09-17/W3_INTEGRATION_ACCEPTANCE.md) |
| 宠物/资料页面真实业务 | PR38页面已接真实接口，W3模拟器完成昵称/宠物读改写UI→HTTP→DB联调（替身后端）；上传、独立编辑页、完整VIS与真机仍未完成 | C-002与AUTH/USR；生产数据、权限、ID和迁移条件分别核验 |
| 宠物独立编辑页和展示字段 | 独立编辑稿未提供；旧入口仍复用添加表单；芯片/疫苗驱虫明细不在当前PetView | C-002与USR；见[标题修正](issues/wave-2/C-002-pet-page/ADD-PET-TITLE-CORRECTION.md)及[字段CCR](issues/wave-2/C-002-pet-page/CCR-C002-PET-DISPLAY-001.md) |
| 用户/商家页面VIS与布局 | 原稿已提供，素材本地保留；V1删减、完整状态/字体、真机与跨设备验证仍须逐页完成 | C/M Owner；[设计来源](DESIGN_SOURCES.md)，原始素材不上传GitHub |
| 商家/服务/排期与运营治理接口 | CCR-W2-API-001各域未全部交付，MER/SVC/SCH/ADM事实不能由fixture代替 | 原各域Owner按[域清单](ccr/CCR-W2-API-001.md)推进 |
| OSS业务消费与运营全流程 | 注册表、同步与私有签名URL代码已合入；前端接入/临期刷新与后台运营全流程仍需证据 | 原Owner；凭据本地/环境配置，代码合并不等于生产启用 |
| 迟到支付退款来源 | CCR-W0-002 | REF-003/PAY-004/CPN-002实现前统一存储/API/Event来源映射 |
| 关闭原因/确认轮次事实 | CCR-W0-003 | TX-001/PAY-003/PAY-004/ORD-002/003实现前明确权威事实 |
| 资金冻结/分账/提现/保证金 | OD-W0-001 | 提供权威资金基线并明确V1范围，不以售后7天自行构造资金规则 |
| 入驻签约/工作台准入 | OD-W0-002已于2026-09-17人工裁决：电子协议+勾选同意模式（见SSOT §26/25号补充）；剩余为MER-001契约阶段确认协议版本管理子项，不阻塞启动 | MER-001/M-002/A-002按裁决实施；协议换版策略由MER-001契约阶段确认 |
| 人工客服承载 | OPEN_DECISIONS原第4项 / EPIC-19 | 保持既有未决，不自行新增IM/AI客服或重拆Issue |

## 验收边界

基线CI通过只证明实际执行的构建/自动化/架构与契约检查。MINI-006支付/扫码/上传、物理真机、全业务E2E及产品VIS未因组件或Mock检查自动通过。PR25视觉接受仍绑定原证据；PR34真机/键盘限制保留。

W3-TRACE-002的12项前端主链已在PR9按现有Story修正，次级关联保留在ISSUE_STORY_LINKS.csv；不再作为“未修复追踪关系”重复阻断，也不因此解除API和真实业务门禁。

本次没有新产品裁决、未执行迁移或生产发布、未关闭完整CCR，也没有按规划文字自动启动下一波任务。
