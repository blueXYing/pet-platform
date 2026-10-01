# CCR-AFS-PAGE-OPTIONS-001 — 售后页面权威选项目录

状态：PROPOSED / NOT_IMPLEMENTED。日期：2026-10-01。Owner：AFS Contract Owner；真实审阅人 @blueXYing。来源：SSOT §40 / PRD31 / Contract51。仅阻断用户端真实创建表单选项接入，不阻断既有工单读取和处理。

## 具体缺口

Contract51的创建DTO要求typeCode、demandCode，后端`AfterSaleWorkflowConfiguration`仅从`pet.aftersale.type-codes/demand-codes`构建真实代码目录；现有资格结果没有选项目录，也没有代码对应名称的公共查询。Figma129:10572含旧“仅退款/退货退款”，不能将设计文案擅自映射成已批准类型代码；测试QA_QUALITY等不是生产选项。前端不展示原始技术代码输入、不自行造字典。

## 建议可审阅最小增量

- 新增只读、本人 MINIAPP 会话下 `GET /api/v1/c/aftersale-options`，沿既有AFS HTTP默认关闭及四字段envelope，禁止query/body。不会创建工单、上传资产或产生资金动作。
- `data`明确为`typeOptions`与`demandOptions`，各项仅`code`（非空1..64）与`label`（非空展示名称）；排除内部备注/自由文本。精确数量上限、排序/版本和错误语义由Owner在正式Contract/OpenAPI冻结。
- 由后端同一权威配置目录提供创建校验与查询结果；仅返回已获批、当前有效的完整code+label。缺目录、缺label或不一致失败关闭，不把代码转作label，不返回测试目录。
- 如果类型与诉求存在约束关系，必须先有上位规则再表达，不从Figma样例推导组合限制。目录版本不替代创建时服务端当前校验。
- 正式变更仅Contract51/Internal07/HTTP10/OpenAPI11/必要Error及typed查询/HTTP适配/测试；无Schema/Event/Scheduler变化，无新的字典管理产品或生产启用。

## 验收与当前处置

验收应含真实会话、目录与创建校验一致、未知/停用代码拒绝、缺label失败关闭、无业务副作用、未登录/错误query/default off以及前端Picker禁用/加载失败状态。重大契约变更按WORK_EXECUTION_PROTOCOL §4经人工审核后执行。

当前本批只接入已存在Contract51：创建支持注入目录port，目录缺失时不开放提交；测试明确使用fixture目录，不冒充生产已接通。建议冻结请求尚未执行，也不等于已获得产品目录内容。
