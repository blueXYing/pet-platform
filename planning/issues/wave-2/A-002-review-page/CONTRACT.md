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
- 人工核验：submissionRevisionId、expectedVersion/expectedTaskVersion、evidenceItems[{materialId,materialSha256,credentialType∈{CREDIT_CODE,IDENTITY_NUMBER,INDUSTRY_LICENSE},subjectName,identifier,validFrom,validityKind∈{LONG_TERM,DATED},validTo}]、reason、confirmed。
- 材料查看：purposeCode（界面固定 `MERCHANT_APPLICATION_REVIEW`）、reason（10–500 字）、confirmed；readUrl 为单次使用，消费后按钮回到"申请查看"，不得缓存复用。

## 4. 已知缝隙与假设（待 Contract Owner/联调确认）

1. **envelope 不一致**：`/auth/*` 响应为 `{code,message,data,traceId}`（无 `success`），商家/材料接口为统一 `{success,...}`。前端客户端按"有 `success` 用 `success`，否则 `code==='SUCCESS'`"兼容；建议后续按 23 号统一，前端再收紧。
2. **attempt 绑定 Cookie**：登录流程依赖 `__Host-pet-admin-attempt`（Secure/Strict），真实联调需 HTTPS 源；前端仅对 `/auth/*` 开启 credentials，业务请求保持 `omit` + Bearer。
3. **materialSha256 来源**：详情响应不含材料摘要。前端假设其应为**水印读取响应字节的 SHA-256**（消费 read-grant 时以 crypto.subtle 计算），作为"核验所见即所交"的绑定；若权威语义不同需 30/31 号补充明确，或详情响应提供摘要。
4. 登录 `accessToken` 内存持有、刷新即重登；不引入持久化会话存储（PRD 未批准 remember-me）。

## 5. 硬规则落点（引用，不重定义）

- 写操作全部经统一客户端自动携带 `X-Request-Id`（幂等）；ID/金额 String 不换算；服务端 `actions`/状态不被前端重算。
- 单运营直接裁决：不出现提交上级/双人审批 UI；不出现 MFA 输入。
- 26 号四项、SSOT §24/§26/§27/§28 相关规则仅在界面呈现与拦截提示层面落实。
