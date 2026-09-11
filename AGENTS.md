# AGENTS.md — 宠物平台 V1.0

所有 Codex/AI Worker 开始任务前必须读取本文件。

## 资料优先级
SSOT > 最终 PRD > 技术基线 > Schema/API/Event/Scheduler > Test。

## 通用禁止
- 不自行改变产品规则；
- 不实现 V1 明确不做的能力；
- 不把 `biz` 直接依赖另一个 `biz`；
- 不跨模块访问 Repository/Mapper/DO/Entity；
- 不直接 push `main` / `develop`；
- 不扩大 Issue Scope；
- Contract 缺失走 CCR；
- 不通过测试就声称完成。

## 技术基线
- Java 21；
- 前端：统一微信小程序采用 Taro + React + TypeScript，运营网页采用 React + TypeScript + Vite + React Router；
- C/M 在 frontend-miniapp 内分目录协作，原始切图一比一还原按前端技术基线 v0.7 与21号验收补充执行；
- Spring Boot 模块化单体；
- MySQL / Redis；
- Snowflake BIGINT，HTTP/JSON ID 使用 String；
- 金额 BigDecimal / DECIMAL(18,2)；
- Transactional Outbox；
- durable AsyncTask；
- 所有写操作具备 requestId 幂等；
- DisplayOrderStatus 由 order 统一计算。

## 关键产品硬规则
- 商家确认 30 分钟；
- 单次服务，必须预约；
- 分钟级排期，不固定 60 分钟槽；
- 接送返程开始 >= 上门开始 + 120 分钟；
- 改期最多一次；
- 服务前退款自动全额；
- 服务后退款商家 24 小时处理；
- refund_order 创建后禁止后续核销；
- 未履约售后在 refund_order 创建前仍可核销；
- 核销先成功使当前未履约售后失效；
- 部分退款仅运营售后裁决；
- 迟到支付：订单保持关闭，按渠道真实支付金额自动全额原路退款；
- V1 无 AI 客服、无用户私信 IM、无直播；
- 积分只赚取/扣回，不消费。

## Definition of Done
- Acceptance Criteria 满足；
- 相关测试通过；
- 架构检查通过；
- 无未披露 Contract/Schema/Event 变化；
- PR 描述完整；
- 已说明遗留风险。

## 已批准运营权限补充
V1单运营、无内部双人审批/复核；运营编辑获权直接发布；超管默认全V1已批准业务权限和全平台范围。财务在既有范围内通用RBAC配置。具体执行SSOT §24及docs/01-prd/22-运营权限人工裁决补充-v1.0.md；原因/用途、后端鉴权、脱敏、审计及交易硬规则不变。
