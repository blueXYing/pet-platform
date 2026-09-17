# 最终资料索引

资料顺序：SSOT > 最终PRD及已批准补充 > 技术基线 > Schema/API/Event/Scheduler > Test > 工程规范。原件保持不变，历史暂停描述按根WORK_STATE最新批准记录解释。

| 层级 | 权威目录/文件 |
|---|---|
| SSOT | [01最终业务基线](00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md)，包含§24运营权限裁决 |
| 最终PRD | 01-prd/ 中02 C端、03商家端、04运营端Word原件，以及[22运营权限补充](01-prd/22-运营权限人工裁决补充-v1.0.md)、[26申请审核裁决](01-prd/26-商家申请审核人工裁决补充-v1.0.md) |
| 技术基线 | [05后端v0.6](02-architecture/05-技术基线-v0.6.md)、[20前端v0.7](02-architecture/20-前端技术基线-v0.7.md)、17 Maven骨架设计 |
| Schema | 03-database/06核心Schema、13 Async Infra Schema；[27商家域存储映射](03-database/27-Merchant-Domain-Storage-v0.1.md)、[28协议四表DDL](03-database/28-Merchant-Agreement-Schema-v0.1.sql)（隔离验证，未生产迁移；申请/成员DDL待交付） |
| API | 04-api/07内部API v0.6、10 HTTP v0.4、11 OpenAPI v0.4、12错误码v0.5；[27商家域契约补充](04-api/27-Merchant-Domain-Contract-v0.1.md)（契约已批，业务未实现） |
| Event/Scheduler | 05-events/08事件v0.6、06-scheduler/09调度重试补偿v0.5 |
| 测试 | 07-testing/14全链路、15并发故障、16发布门禁、21前端与小程序验收补充 |
| 协作 | 08-engineering/18与19、根AGENTS/WORK_EXECUTION_PROTOCOL、repository-policy.md |

上述22份编号资料的原始SHA-256见 `08-engineering/evidence/GOV-001/original-sha256.json`（同时覆盖全部430份导入文件）。旧MANIFEST.csv及BOOTSTRAP_STATS.json作为历史资料保留，不能当作本次新增文件的实时清单。

统一小程序使用Taro + React + TypeScript，运营网页使用React + TypeScript + Vite + React Router。公共文件所有权见 `.ai/OWNERSHIP.md`。会话具体规范仍待CCR-ACR-001，不因技术方向已批准而视为完成。

视觉来源依20号§4与21号VIS-001～004：用户Figma文件bp2vpcjjA5vZbHvtKkA8wl，记录版本2397539525915641008；商家Figma尚缺，原始切图尚未全部导出。后续页面必须按节点/版本/素材哈希/真实微信截图追踪，一比一还原；不在GOV-001实现或宣称视觉通过。
