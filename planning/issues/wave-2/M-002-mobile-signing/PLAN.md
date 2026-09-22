# M-002 手机协议签署与工作台准入 — 实施方案 v2

日期：2026-09-21。状态：已按用户五点意见修订，落档待批；本轮不编码、不合并、不部署。
基线：develop `66a28ec`（PR58/59/60 已合入，与 WORK_STATE 7.5 一致）。
前置方案：v1 见 2026-09-21 会话交付（盘点表仍有效，本文只记录修订与差异）。

## 1. 相对 v1 的修订

1. 接口路径统一为已批准的 `GET /api/v1/merchant/auth/admission` 与 `GET /api/v1/c/auth/merchant-memberships`；全文不再出现 `/merchant/admission` 变体。
2. 切片A（准入契约补充）只冻结线上传输细节（响应envelope、错误映射、分页排序、reasonCodes/nextSteps 机器可读映射），不重新审批 HTTP10/契约27号§5 已有规则。
3. ALLOWED 判定明确为五条件合取：入驻审核 APPROVED ∧ 协议 SIGNED ∧ 商家 ACTIVE ∧ 门店 ACTIVE ∧ 成员关系有效。任何一条未知=失败关闭（503/data=null），不映射为 DENIED。
4. OWNER 先交付是切片顺序，不是能力删减：契约字段 `membershipKind=OWNER/STAFF`、`staffEnabled` 全部保留；子账号路径待成员绑定持久化交付后启用。
5. 门店选择规则：memberships 仅返回一间可访问门店时自动选中并查准入；多门店必须用户显式选择，禁止默认第一间；零门店不进门店列表（HTTP10 既有语义）。
6. 归属修正：主任务 M-002；后端缺口归 MER-001/AUTH-001；通知后端归 NTF-001；C端消息与跳转归 C-006，独立排期（编号已核对 ISSUE_CATALOG.csv）。

## 2. Figma 核对结果（2026-09-21 本轮）

方法与限制：本轮会话未挂载 Figma MCP 工具；远程 `mcp.figma.com/mcp` 返回 401 需 OAuth，本机 3845 Dev Mode 服务未运行（Figma 桌面端未开启）。因此在线重读不可用，改为对本地全量归档做**深度复核**（含全部节点文本，非仅顶层 frame 盘点）：
- 商家端 `Usvn3d6UCVCAlDxou5KAK8`，版本 `2399015827492126503`（2026-09-15 抓取，92 顶层对象）；
- 用户端 `bp2vpcjjA5vZbHvtKkA8wl`，版本 `2397539525915641008`（2026-09-15 抓取）。

结论（基于上述归档版本的当前复核，非转引 S6 旧结论）：

| 目标页面 | 核对结果 | 证据 |
|---|---|---|
| 协议签署页（正文+勾选+签署） | **缺失** | 两文件全部节点文本中 `协议/签约/签署/同意/阅读/勾选` 零命中 |
| 审核结果页（独立呈现） | **缺失** | 商家端无；用户端唯一"审核结果"字样属"成为服务者"表单邮箱字段说明（129:14566），且成为服务者不在 V1 范围（OPEN_DECISIONS） |
| 签署成功页 | **缺失** | 仅存"退款成功/接单成功"属退款与订单域（10:2010、40:1296、23:18697） |
| 准入受限态（冻结/下线/未签引导） | **缺失** | 两文件均无对应状态稿 |
| 可复用节点 | 见下表 | |

可复用设计资产：
- `132:862`/`132:1170` 成为商家-填写（已按 S6 实现为申请页）：表单卡片、必填星号、`application-submit` 主按钮、`application-state`/`application-notice` 状态条——签约页直接沿用此页面语言。
- `126:675` 我的：成为商家入口卡（"入驻开店…"），未来替换工程壳按钮时对齐。
- 商家端 `95:2441`/`23:19357` 消息通知：消息列表原稿存在，归 M-004/NTF-001 后续切片参考，本轮不用。
- `C-002-design-inputs/handoff/design-tokens.json`：色彩/字号 token 来源。

若设计文件在 2026-09-15 后有更新，上述"缺失"需以在线重读为准（需开启 Figma 桌面端 Dev Mode MCP 或挂载已认证 MCP 后复核）。

## 3. 接口与判定（全部沿用已批准契约，此处仅汇编）

| 项 | 内容 |
|---|---|
| 协议读取/签署 | `GET /api/v1/merchant/agreement`、`POST /api/v1/merchant/agreement/consent`（已实现并合入，主账号only、版本+hash校验、requestId幂等恢复） |
| 申请结果 | `GET /api/v1/merchant/applications/current`（已实现，APPROVED 含 reservedMerchantId） |
| 成员关系 | `GET /api/v1/c/auth/merchant-memberships`（契约已有，**待实现**；本人范围、Long升序、无默认选店） |
| 准入 | `GET /api/v1/merchant/auth/admission`（契约已有，**待实现**；merchantId+storeId 必填，禁止 staffId/workspace 参数；每次进入重查） |
| ALLOWED | 审核 APPROVED ∧ 签约 SIGNED ∧ 商家 ACTIVE ∧ 门店 ACTIVE ∧ 成员有效（五条件合取） |
| 未签 | DENIED + reasonCodes=[SIGNING_REQUIRED] + nextSteps=[COMPLETE_SIGNING] → 签约页（签约不要求先获 ALLOWED，契约27号§5防死锁条款） |
| 冻结/下线 | LIMITED（存量履约提示按 SSOT §26；冻结写动作维持未决，不扩展） |
| 依赖不可读 | 503、data=null，失败关闭；无归属 403/防枚举 404 |
| 新单资格 | `checkOrderEligibility` 为内部API归订单域，不并入准入，不替代服务/排期/订单最终校验 |

## 4. 文件归属（唯一 Writer 登记）

| 目录/文件 | Writer | 说明 |
|---|---|---|
| `frontend-miniapp/src/consumer/pages/merchant-application/**`（含新 signing 页、申请页入口接线） | M-002（C-End 协作登记） | 沿用 S6 先例：入驻流程页在 consumer 分包 |
| `frontend-miniapp/src/app.config.ts`、`config/**`、`package*.json`、`src/shared/**` | **C-End 唯一Writer** | 新页注册、共享客户端（协议Repository已存在不动）由 C-End 落地 |
| `frontend-miniapp/src/merchant/**`（工作台准入页改造） | M-002 | realAdmission 接真实接口，fixture 保留为测试注入 |
| 后端 admission/memberships controller + biz 组装 | MER-001/AUTH-001（Backend Core） | 复用既有 Facts 读者，不新增 biz 依赖 |
| 通知后端（读取API/CCR） | NTF-001 | 独立排期 |
| C端消息列表/详情/跳转 | C-006 | 独立排期 |

## 5. 实施切片（依赖序）

- **A｜准入线上契约补充（CCR，可立即开始）**：只补技术缺口（见§1-2）。Owner=AUTH-001/MER-001。验收：CCR 评审通过并登记 planning/ccr/。
- **B｜MS-1：手机签约页＋申请页签约入口（前置：§6 设计裁决）**：范围=签约页（读/勾选/签/恢复/已签）+ 申请页 APPROVED 行入口 + C-End 登记 app.config.ts。不改后端、不做工作台、不做通知。
- **C｜准入后端实现（前置A）**：两端点实现+e2e契约测试。OWNER 先交付，字段保留 STAFF。
- **D｜工作台准入前端接线（前置C）**：改造 `merchant/pages/workspace`，五态（查询中/ALLOWED占位看板/LIMITED/DENIED+nextSteps白名单路由/失败关闭）；单门店自动选、多门店显式选；签后返回重查服务端，前端不置"可经营"。
- **E｜通知读取与受控跳转（NTF-001/C-006，独立排期不阻塞B-D）**：先CCR（端点归属与白名单路由），receiver=本人过滤，跳转不绕过目标页归属/状态校验。

## 6. 待用户确认的唯一设计选择

签约页/审核结果呈现/签署成功/准入受限态四类页面在两份设计文件中均无原稿（§2）。选项：
- (a) 用户补充 Figma 原稿后按 21号验收补充一比一还原；
- **(b) 推荐：批准"沿用现有页面风格"实施**——复用申请页（132:862 交付版）的页面骨架、design-tokens.json token、`application-*` 系列样式；勾选框为新增控件按现有 token 样式实现；协议正文为可滚动文本块；签署成功以页内状态条呈现（不建独立成功页）。交接明确记录"无原稿、不声称一比一还原、VIS 不适用"，后续补稿再对齐。

## 7. MS-1 验收清单（第一张任务单）

范围：手机签约页＋申请页审核通过后的签约入口。逐项：
1. 本人主账号（OWNER）可读取协议（版本/hash/正文）并勾选签署；非 owner、跨商家、失效会话分别被拒（403/404/401 呈现对应态，前端不伪装通过）。
2. 版本/hash 变化：consent 返回 `COMMON_CONFLICT` → 重读新版本重新确认，旧勾选清空。
3. 重复点击防重（同 slot 在途 Promise）；响应丢失/重进页面经 `pendingConsent→retryConsent`（同 requestId）恢复；回执 replay（200）与首次（201）均正确闭环。
4. 未决意图只能由命令回执清除；GET 快照 SIGNED 不清除未决命令。
5. 换账号/登出：待决命令按会话隔离，不串扰（沿用 ConsumerApi 会话级 journal）。
6. 已签状态：回显原版本+时间，无签署按钮，不强制重签。
7. 申请页 APPROVED 行从"签约入口暂未接通"改为进入签约页；REVIEWING/REJECTED 行为不变。
8. 测试证据分层记录：注入 transport 单测、真实 Boot 联调（扩展 merchant-http-integration.ts：APPROVED→GET→consent→replay）、模拟器预览；真机与 VIS 另列，不混称。
