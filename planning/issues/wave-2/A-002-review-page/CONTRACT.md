# A-002 入驻审核页面 · 页面/路由/状态/字段契约（评审输入）

状态：候选，供 Contract Owner 评审；本文不修改权威契约，仅映射已批准接口（30 号 Merchant-Application、31 号 Private-Asset、10 号 HTTP/AdminAuth、27 号商家域）到页面。

## 1. 路由与页面

| 路由 | 页面 | 所需动作 | 数据来源（已批准操作） |
|---|---|---|---|
| `/login` | 运营登录 | 未登录可访问 | POST `/api/v1/admin/auth/attempts`（JSON 体 `{}`）→ GET `.../attempts/{id}/requirements` →（CAPTCHA 时）POST `.../captcha/challenges` + `.../captcha/verify` → POST `.../auth/login`。attempt 绑定调用均携带 `X-Auth-Attempt`；**无验证码时 `captchaProof` 必须整体省略**（后端 `AdminSecretCodec.digest` 对空白 proof 判 unauthorized，真实联调证实）。 |
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
2. **登录来源校验冲突（已按受控代理方向完成首轮真实联调）**：浏览器对同源 fetch **GET 不携带 Origin**（且 Origin 属禁止手动设置的请求头），而后端 `AdminAuthController.cookie()` 对 attempt 绑定调用要求 `pet.auth.admin.origin` 精确匹配，缺失即 403。已按[PR60 交接](../A-002-contract-review/DECISIONS-AND-HANDOFF.md)批准的"同一 HTTPS 入口 + 受控代理"方向实施 dev/preview 受控代理（`vite.config.ts` + `proxy-guard.ts`，规则与约束见下）并于 2026-09-21 完成真实后端联调：PR60 分支后端（真实 MySQL/Redis/OSS/ClamAV，本机 192.168.1.44:18082）+ 入口（127.0.0.1:18082 生产构建）+ 真实 Chromium，`tests/live.spec.ts`（LIVE_JOINT_BASE 门控，CI 不执行）全链通过——attempts 201、**requirements GET 经代理注入 Origin 后 200**（Sec-Fetch-Site=same-origin + 入口 Host 核实）、`__Host-` Secure Cookie 在回环地址被 Chromium 接受并回传、登录/会话/权限/列表/登出全部真实通过、零页面错误。同轮联调发现并修复前端缺陷：无验证码时 `captchaProof` 必须省略而非空串（§1）。**未覆盖**：含申请数据的详情/材料水印读取/人工核验/决定的真实后端流程——本地验收服务器无 C 端播种通道（真实微信 Provider），待后续以真实申请数据联调。代理规则（评审轮五收紧）：显式成对配置（缺失即不启用）；**入口 Host 对每一个请求先于一切转发/注入决策精确校验**（含已携带合法 Origin 的分支——允许的 Origin 不得绕过入口检查）；已携带 Origin 仅放行唯一允许值，非法/null 拒绝且绝不覆盖；仅白名单 attempt 绑定 GET（requirements/result）缺 Origin 且 Sec-Fetch-Site=same-origin 时注入；清除外来转发身份头。传输层为自研转发中间件（api-proxy-middleware.ts，替换 http-proxy 接线）：**拒绝发生在创建任何上游请求之前**，全路径零日志——含拒绝、转发与上游连接失败，一次性读取 token 不出现在任何输出（接线单测断言 fetch 未被调用且控制台输出不含 token）。生产入口按同一基线另行部署验收。
3. **材料引用投影（CCR-A002-MATERIAL-REF-001 已批准，PR60 后端已交付，前端已接入）**：`GET .../{id}` 的 `submittedRevision.materialReferences` 提供 `{materialId, assetId, materialSha256, materialType, position}`；人工核验证据逐字引用登记的 materialId/materialSha256（身份证正反面均须查看但合并为一条 ID_CARD_BACK 绑定证据；每类一条，重复类型后端拒绝）。前端 `validateMaterialReferences` 失败关闭：投影缺失（旧后端）、空/非数组、编号/摘要/枚举/位置非法、必需类型缺失时提交保持禁用并说明原因，不以 assetId 或水印字节替代。含申请数据的真实后端核验流程尚未联调（见 §4.2 未覆盖项）。
4. **写操作重试幂等与未决恢复**：领取/释放/核验/决定/材料授权均为公共幂等写；结果未知（网络错误、坏响应、5xx 含 503 COMMON_DEPENDENCY_UNAVAILABLE——后端在提交结果未知时返回依赖不可用，不能据此认定未执行）时页面保留原 requestId 与参数并提供"重试原操作"。**未决的解除仅有一条路径：以同一 requestId 重放取得幂等回执**。申请快照与命令无 requestId 关联——快照可能读到旧状态、原请求可能仍在排队或稍后提交、当前状态也可能来自其它操作——因此刷新永不解除未决，仅更新展示（评审轮五撤销了轮四的快照终态判定；并发测试覆盖"刷新读到陈旧状态→原请求迟到落地→快照仍不解除→同 requestId 重放解除"）。未决存在期间阻止一切新写入（按钮禁用且入口拒绝新 UUID）。确定性 4xx 契约错误不进入未决状态。
5. 登录 `accessToken` 内存持有、刷新即重登；不引入持久化会话存储（PRD 未批准 remember-me）。

## 5. 硬规则落点（引用，不重定义）

- 写操作全部经统一客户端自动携带 `X-Request-Id`（幂等）；ID/金额 String 不换算；服务端 `actions`/状态不被前端重算。
- 单运营直接裁决：不出现提交上级/双人审批 UI；不出现 MFA 输入。
- 26 号四项、SSOT §24/§26/§27/§28 相关规则仅在界面呈现与拦截提示层面落实。
