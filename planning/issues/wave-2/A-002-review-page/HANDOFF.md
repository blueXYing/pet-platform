# A-002 入驻审核页面切片 · 交接

2026-09-20，基线 develop `c04df90`，分支 `feat/a002-review-page-20260920`。授权与范围见 [DISPATCH.md](DISPATCH.md)，页面/字段契约见 [CONTRACT.md](CONTRACT.md)。

## 当前定性

**审核页面候选已完成：材料引用投影（CCR-A002-MATERIAL-REF-001，PR60 后端交付）已接入、人工核验提交已恢复；登录链路已按 PR60 受控代理方向完成真实浏览器→真实后端联调；含申请数据的详情/材料水印读取/核验/决定真实流程尚未联调（本地无 C 端播种通道）——审核工作台在上述范围内可用，整体不标 DONE。**

## 评审修复轮六（2026-09-21，用户复核 873c0e4/4f00914 后两项残留）

1. **正面材料枚举缺失（P1，已修）**：`KNOWN_MATERIAL_TYPES` 补 `ID_CARD_FRONT`——PR60 正常投影含身份证正面，此前被"材料引用类型未知"整体拒绝、人工核验永久禁用。测试投影同步补正面引用并按后端排序（materialType 字典序）；主流程断言证据仍为三条（正面被接受与查看、但不新增证据行，身份证据仍单条绑定 ID_CARD_BACK）。
2. **长期证件日期规则（P2，已修）**：生效日常驻显示且必填（后端对所有证件要求 validFrom 非空）；切换 LONG_TERM 即清空到期日（后端要求 validTo==null；"先填到期日再切长期"的路径不再残留隐藏值）；提交门禁镜像后端规则（DATED 需 validTo、LONG_TERM 需 validTo 为空）。新增专项测试：长期切换隐藏到期日、缺生效日禁用、补齐后提交体校验（CREDIT_CODE 行 LONG_TERM+validFrom+validTo=null；IDENTITY_NUMBER 行 DATED 双日期；共三条）。
3. **收尾路径（按用户建议）**：①两项修复+测试已完成；②含申请数据的"详情→查看材料→人工核验→审核决定"联调仍需申请数据——本地验收服务器 C 端为真实微信 Provider，无法播种合成申请；可行路径二选一：(a) 用户以手机向本地验收服务器真实提交申请（S9 模式，环境可按轮四记录约十分钟复活）后浏览器完成审核闭环；(b) 后端 Owner 提供测试范围播种工具。PR59/PR60 CI 均绿；③联调通过后进入合并审阅，不再因样式/文案/工具警告追加修改轮次（本轮未做范围外改动）。

## 评审修复轮五（2026-09-21，用户复核 6656ace/6aa572f 后三项残留）

1. **快照不得解除未决（P1，已修）**：撤销轮四的 `machineVerdict` 快照终态判定——申请快照与命令无 requestId 关联（可能读到陈旧状态、原请求可能仍在排队或迟到提交、状态可能来自其它操作），"自动猜测"不优于人工猜测。未决解除唯一路径=以同一 requestId 重放取得幂等回执；刷新仅更新展示。新增并发测试：刷新读到陈旧 AVAILABLE → 原请求迟到落地（刷新再见 CLAIMED）→ 快照仍不解除 → 同 requestId 重放解除（两次 /claim 断言同一 x-request-id）。
2. **入口 Host 统一预检（P2，已修）**：`decideProxyOrigin` 将入口 Host 精确校验置于一切转发/注入决策之前，覆盖已携带合法 Origin 的分支（评审复现"允许 Origin + 错误 Host 仍达上游"已封堵）。单测新增合法 Origin 搭配错误 Host、无 Origin 白名单 GET 搭配错误 Host、业务 GET 搭配错误 Host 均拒绝。
3. **一次性 token 不得入日志（P2，已修）**：弃用 vite http-proxy 接线（其默认 error 日志在 `proxyReq.destroy()` 时打印含 token 的完整路径），改为自研转发中间件 `api-proxy-middleware.ts`：拒绝发生在创建任何上游请求之前；全路径零日志（拒绝/转发/上游连接失败，502 响应不含路径与 token）；清除外来转发身份头；Set-Cookie 多值透传。接线单测（tests/api-proxy-middleware.spec.ts）断言：拒绝路径 fetch 从未被调用、控制台无任何输出包含合成 token、注入分支 Origin 正确、转发保头保体剥 X-Forwarded-*、上游失败 502 脱敏。
4. **状态纠正（按用户回执）**：材料引用 CCR-A002-MATERIAL-REF-001 **已获批准且后端已在 PR60 `4f00914` 交付**；当前状态是"前端已接入、等待含该实现的联调基线上的联合验收"，不是"等待批准"。PR60 未合并；后续含申请数据的真实联调须使用包含该实现的基线。

## 评审修复轮四（2026-09-21，按 PR60 BACKEND-HANDOFF 接入并联调）

1. **材料引用接入（已交付）**：`ReviewDetail` 消费 `submittedRevision.materialReferences`；证据组由权威引用动态构建（身份证正反面合并一条 ID_CARD_BACK 绑定；行业许可证按申请类型存在才要求）；提交逐字引用登记 materialId/materialSha256；`validateMaterialReferences` 失败关闭（投影缺失/空/编号摘要枚举位置非法/必需类型缺失→禁用并说明，不替代、不当空数组）。未决恢复新增 `verify` 意图的机器判定（VERIFIED/PENDING 均为权威终态）。
2. **登录缺陷修复（联调发现）**：无验证码时前端发送 `captchaProof: ""`，而后端 `AdminSecretCodec.digest` 对空白 proof 判 unauthorized（写阶段回滚、审计 AUTH_REQUEST_REJECTED）——此前全部 401 的根因。已改为无验证码时整体省略该字段；curl 直连与真实浏览器双重验证 200。
3. **真实浏览器→真实后端联调（通过，范围如定性）**：PR60 分支后端以 JDK21 于本机构建并运行（`LocalMerchantAcceptanceServer`：真实 MySQL/挥发 Redis/真实 OSS/ClamAV，绑定 192.168.1.44:18082）；入口=生产构建 preview 127.0.0.1:18082，受控代理注入 Origin（`ADMIN_API_PROXY_TARGET/PROXY_ORIGIN`）；管理员账号按 bootstrap 语义 SQL 播种（Argon2 哈希由项目同版本 spring-security-crypto 生成）。`tests/live.spec.ts`（`LIVE_JOINT_BASE` 门控，CI/常规套件不执行）真实 Chromium 全链通过：attempts 201、requirements GET 经代理注入 200、`__Host-` Secure Cookie 回环接受并回传、登录/会话/权限/列表（空）/登出、零页面错误。联调环境已完全拆除（服务器优雅关闭自删库、容器/测试库/临时脚本/worktree/分支清理，凭据仅本地内存未入库）。
4. **联调边界**：含申请数据的流程未联调——该服务器 C 端为真实微信 Provider（需真实小程序凭据/手机），无法本地播种合成申请；待 PR60 合入后以真实申请数据执行（详情渲染 materialReferences、单次水印读取、核验提交、决定、PR60 必需集成验收清单其余项）。PR60 对 PR59 的其余评审点（代理受控校验、未决机器判定）已于轮三修复并有单测；本轮真实联调进一步实证代理注入链路。

## 评审修复轮三（2026-09-21，按 PR60 交接）

依据 [PR60 交接](../A-002-contract-review/DECISIONS-AND-HANDOFF.md)对 `15d852f` 的两项评审意见修复：

1. **代理受控来源校验（P1，已修）**：撤销"无条件覆盖 Origin + 默认目标"的写法。`vite.config.ts` 改为消费 `proxy-guard.ts` 纯函数判定：显式成对配置（缺失即不启用、无默认转发）；已携带 Origin 的请求仅放行唯一允许值、其它值（含 `null`）拒绝且绝不覆盖；仅白名单 attempt 绑定 GET（auth requirements/result）缺 Origin 时注入，且须 `Sec-Fetch-Site: same-origin` + 入口 Host 匹配，否则失败关闭；清除外来 X-Forwarded-*/Forwarded 头；不记录敏感头/体。判定逻辑 5 组单测覆盖（tests/proxy-guard.spec.ts）。**浏览器级注入到真实后端未验证**，dev/preview 代理不等于生产入口部署或验收——按 PR60 必需集成验收清单执行真实浏览器/后端验证前，登录链路不算联调通过。
2. **未决恢复机器判定（P1，已修）**：撤销"我已核实结果，清除未决操作"人工确认按钮。未决意图携带类型（claim/release/decide/grant）：claim/release/decide 在刷新取得权威快照后按命令终态**机器判定**自动解除（提示判定依据，零额外写请求）；grant 签发结果无法由申请快照判定，刷新不解除，仅允许重试原操作（复用原 requestId 取幂等回执）。新增测试：快照判定解除（零写）、grant 刷新不解除且重试复用同一 requestId。

材料引用 CCR（[CCR-A002-MATERIAL-REF-001](../../../ccr/CCR-A002-MATERIAL-REF-001.md)，PR60 起草）尚待具体字段方案批准；人工核验提交继续保持禁用，不用替代值。本轮未改后端与权威契约，未合并、未部署。

## 评审修复轮二（2026-09-21 第二批）

用户复核 `76404bf`（CI 六项、13/13 复跑通过）后指出三项残留，已修复：

1. **requirements 同源 GET 与 Origin 校验冲突（P1）**：确认后端 `cookie()` 对 attempt 绑定调用强制精确 Origin 匹配，而浏览器同源 fetch GET 不携带 Origin（禁止手动设置）。前端无法单方面修复；已内置可信反向代理（vite dev/preview，`ADMIN_API_PROXY_TARGET`/`ADMIN_API_PROXY_ORIGIN`）注入受控 Origin、后端校验原样执行，并将该冲突升级为阻断性集成事实（CONTRACT.md §4.2），等待代理方案或后端 CCR 裁决 + 真实浏览器验证。
2. **503 误判为确定失败（P1）**：`unknownOutcome` 修订——5xx（含 503 COMMON_DEPENDENCY_UNAVAILABLE）与坏响应一律视为结果未知并保留原 requestId/参数；仅客户端前置拒绝（INVALID_ADMIN_PATH）与确定性 4xx 视为已知失败。新增测试覆盖 503→重试复用同一 requestId。
3. **未决操作被新写入顶替（P2）**：存在未决意图时，领取/释放/决定/材料授权按钮全部禁用且 `runWrite` 入口拒绝新 UUID；只允许重试原操作（复用原标识）、刷新权威状态、或运营员确认核实后清除未决意图。新增测试覆盖按钮禁用与清除恢复（清除本身不产生任何写请求）。

## 评审修复轮一（2026-09-21 第一批）

用户以真实客户端请求捕获复核提交 `375425f`，指出五项问题；已逐项对照后端源码确认并修复：

1. **登录协议（P1，已修）**：`POST /attempts` 补 `{}` 请求体；requirements/captcha/login 全部携带 `X-Auth-Attempt`（取 attempts 返回的 attemptToken）；logout 补 `{}` 请求体。与 AdminAuthController/HttpModels 实测要求一致。
2. **材料引用（P1，改为待契约）**：撤销“水印读取字节 SHA-256”假设与 assetId 充当 materialId 的提交路径——后端核对 merchant_material 自身编号与登记摘要且动态水印改变字节，替代值必然失败。人工核验提交禁用并明示待 CCR（CONTRACT.md §4.3）；证据录入 UI 保留为候选。
3. **身份证证据（P1，已修）**：正反面均须查看但合并为一条 `IDENTITY_NUMBER`（绑定 ID_CARD_BACK）主体证据，不再提交两条；三类证件各一条，重复类型按后端规则拒绝。
4. **决定门禁（P1，已修）**：核验未完成仅禁用 APPROVE；REJECT/REQUEST_CORRECTION 可正常提交（26 号裁决只限制“通过”）。
5. **重试幂等（P2，已修）**：所有业务写方法接受显式 requestId；结果未知（网络错误/坏响应）时页面保留原 requestId 与参数并提供“重试原操作”复用同一标识；确定性 4xx 不提供复用重试。

## 交付

- 生产入口由占位页切换为真实应用（`src/App.tsx`）：运营登录 → 会话/权限自举（失败关闭）→ 审核列表 `/merchant-applications` → 审核详情 `/merchant-applications/:id`；未登录深链重定向登录页；404 保持。
- 登录页实现完整 attempt→requirements→（CAPTCHA 时 challenges→verify）→login 流；无 MFA、无双人审批入口；账号内存持有 token，刷新重登。
- 列表页：状态/类型/城市/关键词筛选（仅已批准过滤字段）、服务端分页、String ID 原样展示。
- 详情页：提交版本快照（脱敏联系人字段原样）、材料一次性水印读取（理由 10–500 字 + confirmed，读取字节 SHA-256 记录绑定）、任务领取/释放（expectedTaskVersion 乐观锁）、人工核验（四类证件证据行，须先查看对应材料；materialSha256 取水印读取字节摘要）、审核决定（APPROVE/REJECT/REQUEST_CORRECTION，非通过必填意见，confirmed 必勾；核验未完成时 APPROVE 禁用并提示）。
- 共享文件最小改动：`request.ts`（envelope 兼容 + 仅 `/auth/*` 允许 credentials 的选项）、`main.tsx`（生产入口切换）、`style.css`（页面样式）、`package.json`（test 前置 build）、`tests/shell.spec.ts`（生产入口断言更新为真实应用且保持无 fixture 断言）。

## 验证（本地，Windows）

- `npm run typecheck` 通过；`npm run build`（生产）与 `npm run build:fixture` 通过；`npm run check:boundaries` 通过（生产 JS 无 fixture 标记）。
- `npm test`（build+Playwright，dev:4173 / preview:4174）：**27 通过 + 1 门控跳过**（轮六新增长期证件日期规则专项）（`tests/live.spec.ts` 仅 `LIVE_JOINT_BASE` 存在时执行）——契约测试 11 项（含**陈旧读+迟到落地快照不解除、同 requestId 重放解除**）、代理守卫单测（入口 Host 统一预检含合法 Origin 分支）、**转发中间件接线单测 3 项**（拒绝先于上游+零 token 日志、保头保体剥转发头+Set-Cookie 透传、注入+上游失败 502 脱敏）、原 6 项壳测试保持。
- 真实联调：`LIVE_JOINT_BASE=http://127.0.0.1:18082 npx playwright test tests/live.spec.ts` 1/1 通过（真实 PR60 后端 + 生产构建 + 真实 Chromium，详见评审修复轮四；轮五代理改自研中间件后该链路语义不变，复跑以含申请数据联调时一并执行）。
- 未运行：真实后端联调、真机、微信端（不在本轮范围）。模拟接口测试不能替代真实契约联调（本轮评审即证明）。

## 披露与遗留

1. `/auth/*` envelope 无 `success` 字段（PR17 时期实现）与商家/材料接口的统一信封并存；前端按二者其一判定，建议 Contract Owner 后续统一（CONTRACT.md §4.1）。
2. **登录来源校验冲突（阻断）**：同源 GET 无 Origin 与后端精确匹配校验冲突（CONTRACT.md §4.2）；已内置可信代理注入受控 Origin（后端校验不放宽），等待部署裁决/后端 CCR 并以真实浏览器到真实后端验证。HTTPS/`__Host` Cookie/`X-Auth-Attempt` 等其余联调条件不变。
3. **人工核验提交待 CCR**：详情投影需补充 merchant_material 的 materialId/materialSha256/materialType（CONTRACT.md §4.3）；补齐前页面禁用提交，不得用替代值。
4. 后续未完成：材料引用 CCR、真实服务器联调（LocalMerchantAcceptanceServer/生产测试环境）、手机端协议签署与通知跳转联合验收、A-002 其余治理页面与整项 AC、运营规范视觉验收。本轮不标 A-002 任何 DONE。

## PR

草稿 PR 待人工审阅合并；未改后端、权威契约、WORK_STATE/队列（PR58 在途），主目录用户配置未动。
