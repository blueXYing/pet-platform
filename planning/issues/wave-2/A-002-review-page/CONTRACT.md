# A-002 入驻审核页面 · 页面/路由/状态/字段契约（评审输入）

状态：候选，供 Contract Owner 评审；本文不修改权威契约，仅映射已批准接口（30 号 Merchant-Application、31 号 Private-Asset、10 号 HTTP/AdminAuth、27 号商家域）到页面。

## 1. 路由与页面

| 路由 | 页面 | 所需动作 | 数据来源（已批准操作） |
|---|---|---|---|
| `/login` | 运营登录 | 未登录可访问 | POST `/api/v1/admin/auth/attempts` → GET `.../attempts/{id}/requirements` →（CAPTCHA 时）POST `.../captcha/challenges` + `.../captcha/verify` → POST `.../auth/login` |
| `/` | 重定向 `/merchant-applications` | — | — |
| `/merchant-applications` | 审核队列表 | `merchant.application.read` | GET `/api/v1/admin/merchant-applications`（page/pageSize/status/merchantTypeCode/cityCode/submittedFrom/submittedTo/keyword） |
| `/merchant-applications/:id` | 审核详情+操作 | 读=`merchant.application.read`；领取/释放/决定/核验/材料授权=`merchant.application.decide` +（核验与材料）`merchant.identity.reveal` | GET `.../{id}`；POST `.../{id}/claim|release|manual-verification|decision`；POST `.../{id}/private-assets/{assetId}/read-grants`；GET `/api/v1/admin/private-asset-read-grants/{token}` |
| `*` | 404 | — | — |

菜单按会话 `actionCodes`（GET `/auth/permissions`）显隐；`superuser` 语义不由前端推断，动作缺失即隐藏/拒绝，401/403 由客户端统一失效上下文。

## 2. 状态机（全部以服务端返回为准，前端不复算）

- 申请：`REVIEWING / APPROVED / REJECTED`（`REQUEST_CORRECTION` 是决定类型，不新增公开状态；REJECTED 显示"需补充资料/未通过"按最新决定类型区分）。
- 审核任务：`AVAILABLE / CLAIMED`（以 task.status 为准）；领取/释放/决定携带 `expectedTaskVersion`，申请写携带 `expectedVersion`；版本冲突（HTTP CONFLICT）提示刷新后重试，不静默覆盖。
- 核验：`subjectVerificationStatus ∈ PENDING / VERIFIED`；非 VERIFIED 时 APPROVE 提交按钮禁用并提示（26 号：核验完成前不能审核通过；后端仍兜底）。

## 3. 字段（严格按 30 号/31 号响应，不增删改）

- 列表 Summary：applicationId/applicationNo/reservedMerchantId/status/version/merchantName/merchantTypeCode/cityCode/submittedRevisionId/submittedAt/subjectVerificationStatus（ID/版本一律 String）。
- 详情：上表 + submittedRevision{revisionId,revisionNo,createdAt,snapshot{...脱敏联系人字段原样展示}} + task + latestDecision。
- 决定表单：decisionType∈{APPROVE,REJECT,REQUEST_CORRECTION}；opinion（REJECT/REQUEST_CORRECTION 必填）、internalNote、confirmed 必勾。
- 人工核验（**提交暂不可用，待契约**，见 §4.3）：submissionRevisionId、expectedVersion/expectedTaskVersion、evidenceItems、reason、confirmed。后端语义（MerchantApplicationService.verify）：每类证件一条证据；`IDENTITY_NUMBER` 绑定 `ID_CARD_BACK` 单条（正反面均须查看，合并为一条主体证据，重复类型被拒）；`LONG_TERM` 的 validityBasis 由服务端派生。证据行的 materialId/materialSha256 必须引用提交版本登记的 merchant_material 编号与摘要。
- 材料查看：purposeCode（界面固定 `MERCHANT_APPLICATION_REVIEW`）、reason（10–500 字）、confirmed；readUrl 为单次使用，消费后按钮回到"申请查看"，不得缓存复用。

## 4. 已知缝隙与假设（待 Contract Owner/联调确认）

1. **envelope 不一致**：`/auth/*` 响应为 `{code,message,data,traceId}`（无 `success`），商家/材料接口为统一 `{success,...}`。前端客户端按"有 `success` 用 `success`，否则 `code==='SUCCESS'`"兼容；建议后续按 23 号统一，前端再收紧。
2. **attempt 绑定 Cookie**：登录流程依赖 `__Host-pet-admin-attempt`（Secure/Strict），真实联调需 HTTPS 源；前端仅对 `/auth/*` 开启 credentials，业务请求保持 `omit` + Bearer。
3. **材料引用契约缺口（已确认，走 CCR）**：审核详情投影不提供提交版本 merchant_material 的编号与登记摘要，而后端核验强制比对二者。前端不得以 assetId 或水印读取字节摘要替代（动态水印会改变字节，且 assetId≠materialId）。已按后端实现确认，非待验证假设；需 Contract Owner 经 CCR 补充权威投影（含 materialId、materialSha256、materialType）后，前端方可接入人工核验提交。当前页面呈现证据录入候选但禁用提交。
4. **写操作重试幂等**：领取/释放/决定/材料授权均为公共幂等写；结果未知（网络错误/坏响应）时页面保留原 requestId 与参数并提供"重试原操作"，复用同一请求标识取得幂等回执；确定性失败（4xx 契约错误）不提供复用重试。自动携带请求头本身不构成重试幂等，须按操作意图保留标识。
5. 登录 `accessToken` 内存持有、刷新即重登；不引入持久化会话存储（PRD 未批准 remember-me）。

## 5. 硬规则落点（引用，不重定义）

- 写操作全部经统一客户端自动携带 `X-Request-Id`（幂等）；ID/金额 String 不换算；服务端 `actions`/状态不被前端重算。
- 单运营直接裁决：不出现提交上级/双人审批 UI；不出现 MFA 输入。
- 26 号四项、SSOT §24/§26/§27/§28 相关规则仅在界面呈现与拦截提示层面落实。
