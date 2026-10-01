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
- App 模块级客户端保留当前会话的未知结果 journal，跨详情卸载/重进恢复原 UUID + 原 payload；只显式重试原操作取得幂等回执。刷新不会清除未知意图，未知期间新的处理操作禁用。补证截止经过后仍允许原命令回放取得回执。
- 409 清除旧填写/确认并重读，要求人工再次处理；不自动新建 UUID 再裁决。grant 成功回执与二进制消费分开：消费失败/410 时提示重新申请，不重试已消费图片 URL。
- 退出、会话/资源切换清除 journal；身份 epoch、页面 generation 和客户端 epoch 阻止迟到结果进入新上下文。Bearer、grant/token、图片没有持久化。对象 URL 在隐藏、刷新、卸载、撤权时回收。

## 运行结果

Windows / Node `npm ci` 使用现有锁文件，无依赖版本变更。

| 检查 | 结果 |
|---|---|
| `npm run build` | PASS，含 TypeScript 严格类型检查及生产 Vite 构建 |
| `npm run check:boundaries` | PASS，运营源引用仍在本端，生产包无私有 fixture、小程序 API |
| `npx playwright test tests/aftersale.spec.ts` | 15 passed |
| `npx playwright test` | 42 passed / 2 skipped（既有真实联调 opt-in 因未配置 LIVE_JOINT_BASE 跳过） |
| `git diff --check` | PASS |

新增测试运行真实生产 App 与 transport，使用明确 HTTP 合同 fixture；覆盖独立守卫及按钮权限、完整两笔 P4 历史、真实历史引用重复关闭、UTC/String CAS、三类非退款终裁边界、跨路由/刷新 ACK 丢失回放、409 重读、店切换迟到响应、403 撤权、grant 路径与 Bearer 消费、410 后重新授权、对象 URL 回收、数字 ID/错误四字段/跨端路径/尾随换行拒绝、过期补证原键回执恢复及身份变化迟到图片丢弃。

已用 `view_image` 人工检查浏览器截图（合成 fixture 数据）：

- `frontend-admin/test-results/aftersale-processing-handle.png`：处理中 / handle-only 页面。
- `frontend-admin/test-results/aftersale-p4-history.png`：P4 完整历史人工核对与受理/重复关闭。

截图及 JSON 测试输出为本地 `test-results` 产物，不提交生产或真实私有数据。

## 尚未覆盖的验收与风险

- 本次未运行真实 PR98 后端的运营页面联调，未开启任何生产开关；HTTP fixture 通过不等于真实 RBAC/门店资源/资金能力验收。
- 需根协调/QA 在受控后端环境走 C/M/A 联合非出款闭环及同单并发、真实单次证据消费、当前城市范围与权限变化。小程序真机及视觉验收属于 C/M/QA 的独立证据。
- 刷新浏览器会丢失未持久化的登录会话；新登录先重读工单，不自动重送旧未知命令。当前会话的跨路由重入可恢复 journal；用户切换门店或身份时敏感 journal 按隔离规则立即清理。
- 现有 Vite native configLoader 预警来自已有无扩展名配置 import，不影响本次构建；未扩大范围修改。
- 本切片没有 Contract/Schema/Event 变化，没有 push、创建 PR、merge 或部署。
