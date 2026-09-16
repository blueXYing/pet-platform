# PLAT-005 可观测与统一异常基座

Owner Role: Backend Core
Epic: EPIC-01
Story: ST-PLAT-02
Priority: P0
Wave: 2
Status: IN_PROGRESS
Dependencies: PLAT-001
Phase Readiness: BASELINE_MERGED_DOD_REVIEW

2026-09-15用户明确批准先做本基座再开USR-001。本Issue为用户直接授权新增，非扩大既有Issue范围；实现既有契约（HTTP10响应包裹/§2.4 Trace、Error12的ApiError与通用映射、技术基线"password/code/token不进普通日志"），不新增产品行为。

## 2026-09-16 当前阶段

PR21可观测与统一异常基座已合入。Catalog原IN_PROGRESS保留，按原AC/证据核对整项DoD后登记关闭；不能把已合入基座重新派作实现，也不把运维采集扩为本Issue新范围。

证据见[合并台账](../../progress/2026-09-16/PROGRESS_SYNC.md)；本次只同步事实，不扩大原Scope或启动新的实现阶段。

## Allowed / Forbidden

实现范围：`backend/pet-common backend/pet-boot`（boot仅新增config/adapter文件及AdminAuthExceptionHandler加一行@Order登记）。规划文件：本Issue文件、ISSUE_CATALOG.csv一行、docs/02-architecture/21号补充草案。不改权威10/12号正文、不改AUTH业务逻辑、不改前端、不改Schema。

## 范围

1. pet-common：ApiError（Error12 §1原文record）、ApiResponse（HTTP10 `{code,message,data,traceId}`，成功code=SUCCESS、错误data=null）、ApiException（携带稳定code）、通用错误码常量。
2. pet-boot：TraceContextFilter——按HTTP10 §2.4接受/生成X-Trace-Id并响应回显，X-Request-Id为合法UUID时入MDC并回显，traceId/requestId写入MDC供application.yml日志格式串使用，请求结束清理。
3. pet-boot：GlobalApiExceptionHandler——未覆盖异常统一映射Error12通用码与HTTP状态；MessageNotReadable/参数类→400 COMMON_INVALID_ARGUMENT（含未知字段/重复JSON键/类型错误，HTTP10 1630行）；兜底500 COMMON_INTERNAL_ERROR不泄露内部细节；错误响应Cache-Control: no-store。
4. 21号补充草案：日志级别/内容/MDC约定，多数为既有散落规则收敛（10号敏感字段、task-core异常类日志先例），作为技术基线补充待PR合并批准生效。

## Acceptance Criteria

1. 响应包裹/错误码/头行为与HTTP10、Error12逐字段一致，不发明新形状；AdminAuthController现有行为不变（其advice优先级在前）。
2. 每个HTTP请求日志行带traceId（有requestId时一并），MDC请求后无泄漏；X-Trace-Id始终回显。
3. 未知异常返回500 COMMON_INTERNAL_ERROR包裹体，日志只记异常类名不落业务载荷。
4. MockMvc独立测试覆盖过滤器与各映射分支；不引入新外部依赖（无DB/Redis需求）。

## Required Tests

新增：TraceContextFilterTest、GlobalApiHandlerTest、pet-common包裹体单测。既有AdminAuthHttpTest回归不受影响（CI真跑）。ARCH001~005持续通过。

## 交付与DoD

完整DoD含：全部既有契约一致、21号补充获批合入、CI六job通过、人工合并。生产日志采集/告警接入（Loki/ELK等）不属V1本Issue，留待运维阶段。
