# CCR-AFS-PAGE-OPTIONS-001 — 售后页面权威选项目录

状态：APPROVED / IMPLEMENTED_DEFAULT_OFF。日期：2026-10-01。Owner：AFS Contract Owner；真实审阅人 @blueXYing。来源：SSOT §40 / PRD31 / Contract51。用户在两项具体 CCR 的批准请求后明确回复“批准”，授权本接口及契约增量；不等于批准具体生产选项、生产启用或 PR 合并。已接通用户端真实创建表单选项；正式配置缺失时仍禁新申请。

## 具体缺口

Contract51的创建DTO要求typeCode、demandCode，后端`AfterSaleWorkflowConfiguration`仅从`pet.aftersale.type-codes/demand-codes`构建真实代码目录；现有资格结果没有选项目录，也没有代码对应名称的公共查询。Figma129:10572含旧“仅退款/退货退款”，不能将设计文案擅自映射成已批准类型代码；测试QA_QUALITY等不是生产选项。前端不展示原始技术代码输入、不自行造字典。

## 已批准的最小增量

- 新增只读、本人 MINIAPP 会话下 `GET /api/v1/c/aftersale-options`，沿既有AFS HTTP默认关闭及四字段envelope，禁止query/body。不会创建工单、上传资产或产生资金动作。
- `data`明确为`typeOptions`与`demandOptions`，各项仅`code`与`label`，排除内部备注/自由文本。每类1..100项，code为`[A-Z][A-Z0-9_]{0,63}`，唯一且按ASCII升序；label为1..64 Unicode code points的非空展示名称，精确边界见正式Contract51/OpenAPI。无目录版本字段；创建仍检验当前目录。
- 由后端同一权威配置目录提供创建校验与查询结果；仅返回已获批、当前有效的完整code+label。缺目录、缺label或不一致失败关闭，不把代码转作label，不返回测试目录。
- 如果类型与诉求存在约束关系，必须先有上位规则再表达，不从Figma样例推导组合限制。目录版本不替代创建时服务端当前校验。
- 正式变更仅Contract51/Internal07/HTTP10/OpenAPI11/必要Error及typed查询/HTTP适配/测试；无Schema/Event/Scheduler变化，无新的字典管理产品或生产启用。

## 验收与当前处置

验收应含真实会话、目录与创建校验一致、未知/停用代码拒绝、缺label失败关闭、无业务副作用、未登录/错误query/default off以及前端Picker禁用/加载失败状态。重大契约变更按WORK_EXECUTION_PROTOCOL §4经人工审核后执行。

批准前仅接入原Contract51，目录port返回null时禁止新创建。批准后接通认证目录客户端与服务端同源校验：只有当前会话、完整有效目录和本人订单资格均成功才允许新创建；缺目录、加载失败或会话变化仍失败关闭。测试fixture目录不进入生产；本CCR不批准具体生产目录内容或生产环境启用。实施与最终验收另记于本批CCR交付记录。

实现已通过本地单元、客户端及独立真实HTTP/数据库验证，含literal HTTP尾部空query和GET body拒绝；详见[实施记录](../progress/2026-10-01/aftersale-pages/CCR-IMPLEMENTATION.md)与[独立QA](../progress/2026-10-01/aftersale-pages/CCR-QA-HANDOFF.md)。PR99仍为草稿，当前head CI及页面真实联调/真机/VIS独立验收。
