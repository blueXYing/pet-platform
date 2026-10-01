# A-004 运营售后非出款页面切片

日期：2026-10-01。基线：PR #98 / `e3b846e`；分支：`codex/aftersale-a-ui-20261001`；工作树：`C:/Users/Administrator/.codex/worktrees/aftersale-a-ui-20261001`。

状态：运营前端切片已实现并通过下列前端检查，待根协调集成及独立 QA。不是完整 A-004/E2E 验收，不授权合并、生产迁移或启用开关。

## 依据及唯一修改范围

- 读取 `AGENTS.md`、`WORK_EXECUTION_PROTOCOL.md`、SSOT §40、PRD31、Contract50/51、OpenAPI11、前端技术基线 v0.7、Testing21，以及现有运营会话、申请审核页面与样式。
- 修改仅 `frontend-admin/**` 与本交接记录；未修改后端、契约、Schema、SSOT、小程序、锁文件或公共工程依赖。
- 所有页面沿 Contract51 的 `/api/v1/admin` 真实表面；无客户端主体授权、资金 Provider、STAFF、跨门店运营聚合、第二人审批或 MFA。

## Figma 插件阅读证据与设计边界

已读 `figma-design-to-code/SKILL.md`，实际调用 Figma 插件 `get_design_context`：`fileKey=bp2vpcjjA5vZbHvtKkA8wl`，`nodeId=129:10572`，`clientFrameworks=react`，`clientLanguages=typescript,css`，`skillNames=figma-design-to-code`，`excludeScreenshot=false`。

首次调用需要重新认证；用户恢复连接后重试成功，返回高保真节点代码和截图。核对到用户申请售后的订单信息、原因、说明、证据分区。该移动节点包含旧“退货退款”、选填描述、3 张 / 5MB / WEBP / GIF 字样；不覆盖 SSOT/Contract51 的已批准业务和证据限制。

设计登记表明确运营无 Figma 原稿。运营界面依据批准 PRD 与既有 React/Vite 工作台风格实现，移动节点仅为信息呈现参考，没有将用户端稿冒充运营桌面稿或宣称桌面像素还原。运营页面无新增静态素材，不含 Figma 临时 URL 或整页截图资产。

## 已实现

- `/aftersales`：必须指定 merchantId + storeId；状态和订单过滤、服务端分页、空列表、失败、加载状态。查询仅缩小后端资源范围，返回内容再验证所属商家/店一致。
- `/aftersales/:afterSaleId`：完整工单、原始说明、新问题说明、当前补证要求、双方不可变证据批次、已有最终决定、真实提交回执。
- 独立 `aftersale.read` 路由/菜单守卫；handle 控制受理、补证和重复关闭；decide 控制非退款最终决定。没有沿用 `merchant.application.read` 作为售后总守卫。登录首页等待完整会话与权限，再选择首个获权模块。
- 受理 P4 新问题前逐笔读取 `priorFinalCaseIds` 的完整集合，核对同订单、RESOLVED 和真实非退款决定，要求人工确认每笔历史并填写评估；引用原 `finalSetVersion` 与工单 String CAS。没有通过相似度自动判定重复。
- 重复关闭只针对 PENDING，人工引用已读取的同单旧非退款终局，不发送新的 decision。
- PROCESSING 的补证方、非空原因、未来 UTC 毫秒截止；WAITING 的截止/超时语义仅展示，不由前端自动恢复或裁决。
- PROCESSING 的 REJECT/RESERVICE/OTHER，强制 `refundAmount=null`；RESERVICE 记录人工安排。FULL/PARTIAL 不提供可提交选项，客户端也拒绝这些类型。
- 私有图片：当前 admin 路由、case/batch/asset 绑定、用途原因、UUID grant、Bearer 同端单次 GET。只接受精确 admin 相对 grant 路径，拒绝跨端、绝对 URL、query、尾随换行；禁用缓存/重定向/业务 cookie，校验 JPEG/PNG 和二进制体积。
- 所有新 JSON 响应严格验证 `code/message/data/traceId` 四字段；不接受额外 success。ID/版本/金额保持 String，校验 Long.MAX_VALUE、两位小数和 UTC 毫秒；UUID/hash/ID/版本/金额/grant 正则均严格拒绝尾随换行，避免 URL/Header 隐式归一化。
- 业务写 journal 在 POST 前同步保存到 `localStorage` 并回读确认，按后端已验证的 `operatorId + merchantId + storeId + caseId` 隔离。保存原 action、UUID、payload，跨路由、浏览器刷新、标签关闭、新浏览器会话、401、暂时离开门店仍保留；不保存 Bearer、grant URL/token、图片。读取/写入失败均停止新提交并给出安全错误；不能因存储不可用换新 UUID。收到回执时只退休与该发送快照完全相同的日志，迟到的旧回执不会删除另一客户端已保存的新未知命令。
- 恢复前先重新鉴权，校验 session/permissions 的 ADMIN_WEB/operatorId/authzVersion，再实际读取当前门店列表及工单，查询参数不能充当资源授权。不同运营或门店看不到原日志；返回原身份和门店后显式展示原处理内容，只重试原 UUID + 原 payload，不自动发送。未知期间新的处理操作禁用。补证截止经过后仍允许原命令回放取得回执。
- 429、幂等处理中/键冲突、通用 `COMMON_CONFLICT`、401/403/404 保留原请求。只有完整真实成功回执或确定的业务拒绝才退休。明确业务 409 拒绝后清除旧填写/确认并重读，要求人工重新核对；不自动新建 UUID 再裁决。`COMMON_CONFLICT` 同时代表版本变化和幂等锁忙，无法从 code 或快照证明原命令失败，按未知处理。
- grant 意图仅保存在当前模块会话内，鉴权/资源变化或浏览器刷新清除；grant 成功回执与二进制消费分开，消费失败/410 时提示重新申请，不重试已消费图片 URL。
- 退出、会话/资源切换立即清除内存主体证明、私有详情、grant 和图片，但保留隔离的未知业务日志。身份 epoch、页面 generation 和客户端 epoch 阻止迟到结果进入新上下文。`visibilitychange` 隐藏与 `pagehide` 清图片并拒绝迟到二进制，回到前台不会自动读取图片；手动刷新清原因、确认勾选和全部历史核对。

## 运行结果

Windows / Node `npm ci` 使用现有锁文件，无依赖版本变更。

| 检查 | 结果 |
|---|---|
| `npm run build` | PASS，含 TypeScript 严格类型检查及生产 Vite 构建 |
| `npm run check:boundaries` | PASS，运营源引用仍在本端，生产包无私有 fixture、小程序 API |
| 售后 Playwright 用例（完整测试中） | 36 passed |
| `npx playwright test` | 63 passed / 2 skipped（既有真实联调 opt-in 因未配置 LIVE_JOINT_BASE 跳过） |
| 最后 scope 校验变更后的相关定向回归 | 7 passed（四类刷新、关闭 context、缺失/非法/其他店 scope、双客户端迟到回执） |
| `git diff --check` | PASS |

新增页面测试运行真实生产 App 与 transport，使用明确 HTTP 合同 fixture；API 负例读取开发服务同一源模块。覆盖独立守卫及按钮权限、完整两笔 P4 历史、真实历史引用重复关闭、UTC/String CAS、三类非退款终裁边界、跨路由 ACK 丢失回放、受理/补证/重复关闭/决定四类整页刷新重新登录原键恢复、关闭旧浏览器 context 后持久 origin 状态在新 context 重新鉴权恢复、401 再鉴权、换运营/店及伪造/缺失/非法 scope 隔离、429/三种幂等或通用 409 原键回放、明确业务 409 重读、存储 setItem/getItem 故障不发新命令且无 unhandled 错误、双客户端旧回执不会删除新日志、grant 永不持久化、手动刷新清旧确认、实际浏览器 visibilitychange/pagehide 事件清对象 URL/迟到二进制不展示、店切换迟到响应、403 撤权、grant 路径与 Bearer 消费、410 后重新授权、数字 ID/错误四字段/跨端路径/尾随换行拒绝、过期补证原键回执恢复及身份变化迟到图片丢弃。

已用 `view_image` 人工检查浏览器截图（合成 fixture 数据）：

- `frontend-admin/test-results/aftersale-processing-handle.png`：处理中 / handle-only 页面。
- `frontend-admin/test-results/aftersale-p4-history.png`：P4 完整历史人工核对与受理/重复关闭。

截图及 JSON 测试输出为本地 `test-results` 产物，不提交生产或真实私有数据。

## 尚未覆盖的验收与风险

- 本次未运行真实 PR98 后端的运营页面联调，未开启任何生产开关；HTTP fixture 通过不等于真实 RBAC/门店资源/资金能力验收。
- 需根协调/QA 在受控后端环境走 C/M/A 联合非出款闭环及同单并发、真实单次证据消费、当前城市范围与权限变化。小程序真机及视觉验收属于 C/M/QA 的独立证据。
- 登录会话仍只在内存；持久日志不等于登录授权。测试通过关闭旧 context、复制持久 origin 状态到新 context 模拟浏览器持久 profile，不宣称已运行操作系统重启实测。日志依赖浏览器保留 localStorage；清除网站数据或丢失浏览器 profile 无法恢复原 UUID。
- 现契约对 CAS/P4 变化和幂等锁忙共用 `COMMON_CONFLICT`，客户端保留原命令并阻断新裁决。若后端始终回该通用 code，不能仅据快照自动退休或人工换新键；需要明确业务失败或原幂等回执。未自行更改错误契约。
- 现有 Vite native configLoader 预警来自已有无扩展名配置 import，不影响本次构建；未扩大范围修改。
- 本切片没有 Contract/Schema/Event 变化，没有 push、创建 PR、merge 或部署。
