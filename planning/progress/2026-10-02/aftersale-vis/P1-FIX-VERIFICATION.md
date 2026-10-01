# 售后页 P1 修复：像素级渲染叠图复验报告

日期：2026-10-02。分支 `codex/aftersale-pages-20261001`（PR #99），worktree 内 P1 修复为未提交源码修改。本报告只做修复后复验，不修改任何生产源码、不改 project.config.json、未 git add/commit/push。

## 0. 结论速览

| 编号 | 项 | 修后实测（402 设计px 坐标系） | 设计值 | 修复前实测 | 判定 |
|---|---|---|---|---|---|
| P1-1 | C apply 卡宽 | **376.005**（5 卡全部一致，left 12.498） | x=12.506，w=375.988 | 377.005（+1.017） | **CLOSED**（差 +0.017，亚像素取整） |
| P1-1b | M detail 卡宽 | **343.021**（4 卡全部一致，left 28.990） | x=29，w=343 | 344.019（+1.019） | **CLOSED**（差 +0.021，亚像素取整） |
| P1-2 | M list 金额字号/行高 | 计算字号 **15.386**，行高盒 **23.0796** | 15.388 / 23.081 | 13.464 / 行高未设（≈18.8） | **CLOSED**（字号差 0.002） |
| P1-3 | M 机器码→中文映射 | 列表「问题类型 未履约/质量问题」、详情「问题类型 未履约 / 用户诉求 退款」 | 全中文 | 直出 SERVICE_QUALITY/OTHER 等 | **CLOSED**（真实后端数据验证） |
| P1-4 | M list RESOLVED 态按钮描边灰变体 | 第一轮：属性选择器未命中（NOT_CLOSED，见 §2.4）；**第二轮类名变体修复后：RESOLVED 按钮 3 个实测归一 78.87×28.85、白底、描边 #d1d5db、文字 #6b7280（CLOSED，见 §2.4b）** | 78.876×28.852 白底描边 #d1d5db | 所有状态统一实心蓝 77×32 | **CLOSED**（2026-10-02 第二轮复验） |

字体回退、安全区、键盘、多窗口、相册权限等仍需物理真机，见 §5。

## 1. 复验环境与数据（可复核口径）

- **模拟器**：微信开发者工具 fullMode 窗口，`wx.getSystemInfoSync()` 实测 model iPhone 12/13 (Pro)、window **390×753**、screen 390×844、**pixelRatio 3**、基础库 3.17.2、statusBarHeight 47 —— 与 MINI-JOINT-HANDOFF 基线完全一致。归一口径：390 窗口实测值 ×402/390 归一到 402 设计px。
- **小程序构建**：`NODE_ENV=development PET_C_API_ORIGIN=http://127.0.0.1:18081 PET_ALLOW_LOCAL_HTTP=true PET_MERCHANT_APPLICATION_ENABLED=true PET_PRIVATE_MATERIAL_UPLOAD_ENABLED=true npm run build:weapp`，随后 `check:package` **PASS**（exit 0）；`127.0.0.1:18081` 已 grep 确认编译进 `dist/common.js`。模拟器走回环，未使用 LAN 反代。
- **隔离后端**：按 BACKEND-HANDOFF recipe `backend/tools/run-aftersale-joint.ps1 -Mode Start`（runId `5f0dcf644c5b…`）。每批独立 **MySQL 8.4 `127.0.0.1:6849`、Redis 7.4-alpine `127.0.0.1:6850`**（本 recipe 为回环随机端口制；任务书中 33452/16383 为 DEVICE-PREP §1.2.4 记载的更早固定端口先例，本批按现行 recipe 执行随机端口并如实记录）。后端 HTTP `127.0.0.1:7085`，测试控制入口 `127.0.0.1:7112`（仅回环 + `X-Joint-Control`）。另有临时 Python TCP 转发器 `127.0.0.1:18081 → 127.0.0.1:7085`（纯回环、不触数据库）。
  - **Hikari/Snowflake 说明**：本批模拟器方案没有 LAN 代理、没有自建"每请求新建连接"的长驻进程（转发器为纯 TCP 字节转发，不连任何数据库），MER-001-s9 的"裸连接续租卡死"场景不适用；业务库连接全部走 fixture 自身的 Spring 上下文连接池。
  - **启动波折（如实记录）**：第 1 次启动失败——PowerShell 5.1 中 `$env:AUTH_MYSQL_PASSWORD=''` 会**删除**该变量（Windows 不支持空值环境变量），fixture 因此报"set AUTH_MYSQL_URL/USER/PASSWORD…"；该脚本的此行为在 Windows pwsh/PS5.1 下均会复现（原 recipe 推断在 Linux pwsh 下运行过，空值可存在）。第 2 次启动**未改仓库任何文件**，用临时 PATH shim（`mvn.cmd` → Python `subprocess` 强制 `AUTH_MYSQL_PASSWORD=''` 进入子进程环境块）修复，其余 recipe 逻辑（容器创建/label 校验/回环绑定/清理）原样执行，启动成功。shim 属临时文件，已随清理删除。
- **健康验证（全部通过）**：匿名 `GET /api/v1/c/aftersale-options` → **401**；控制入口无 secret → **403**；带 secret `GET /summary?caseId=…` → JSON 计数；经 18081 转发器同请求 → **401**（链路通）。测试时钟固定 `2030-01-01T10:30:01Z`，页面显示北京时间。
- **种子数据（后端启动时经真实 API 造好）**：3 笔完整流程终局 **RESOLVED**（`9160000000000094/110/126`，REJECT/RESERVICE/OTHER）+ 3 笔 **PENDING**（p4 `…147`、workflow `…156`、recovery `…161` 区间内）+ 1 个已核销、未创建售后的干净订单 `miniOrderId=8900000000000077`。本批另经**真实页面**新创建 1 笔 PENDING（见 §2.3）。
- **会话**：仅 wx.login 为隔离身份兑换码 mock（`qa-http:user-710100` buyer / `qa-http:user-710300` owner，与 runtime.json 一致），登录/退出/会话恢复均走真实 `/c/auth/*` 接口；无任何业务 HTTP mock、无 page data 注入。截图未包含 token/secret 可辨识内容。

## 2. 逐项 P1 复验明细

### 2.1 P1-1 C apply 卡宽 —— CLOSED

- 源码：`consumer/pages/aftersale/page.scss` `.afs-body` padding 左 12.506 / 右 13.506（已核对 diff）。
- automator `boundingClientRect` 实测 5 张卡（订单信息/售后类型/补充说明/新问题说明/上传图片）**全部** left=12.125、width=364.78125（390 窗口）→ 归一 **x=12.498、w=376.005**。liteMode 与 fullMode 两次开窗测量结果一致。
- 与设计 x=12.506 / w=375.988 差 **+0.017 设计px**（=0.017×390/402≈0.017 物理px 渲染取整），修复前 +1.017。
- 叠图佐证：`overlay-C-apply-card1-402.png` / `difference-x3-C-apply-card1-402.png`（按第一卡顶边对齐、402 坐标系）——卡片左右边缘无竖向错位条纹，仅内容区因业务替代文案（订单号/申请截止 vs 设计的服务快照）产生文字重影。
- 结论：**关闭**。

### 2.2 P1-1b M detail 卡宽 —— CLOSED

- 源码：`merchant/pages/aftersale/page.css` `.mas-detail-card` margin 左 29 / 右 30（已核对 diff）。
- 实测 4 张卡（状态/申请信息/售后申请/双方证据与意见）全部 left=28.125、width=332.78125 → 归一 **x=28.990、w=343.021**，与设计 x=29 / w=343 差 **+0.021 设计px**，修复前 +1.019。
- 叠图佐证：`overlay-M-detail-card1-402.png` / `difference-x3-M-detail-card1-402.png` —— 左右边缘归零（差分图边缘无竖向条纹；圆角处 ~0.4px 垂直对齐残差来自整数对齐，属亚像素）。
- 结论：**关闭**。

### 2.3 P1-2 M list 金额字号 —— CLOSED

- 源码：`.mas-requested` font-size 15.388、line-height 23.081（已核对 diff）。
- DevTools 无法直接量字形，采用三重辅助证据：
  1. **计算样式**：`automation_element_action style font-size` → **14.9287px**（390 窗口）×402/390 = **15.386**（设计 15.388，差 0.002；对照组 `.mas-case-number` 实测 13.0621px → 13.464，与设计 13.464 精确吻合，证明测量口径可信）。
  2. **行高盒**：`.mas-requested` boundingClientRect 高 22.3906px → 归一 **23.0796** vs 设计行高 23.081（修复前无行高声明，默认行高约 18.8）。
  3. **截图目测**：`M-list-top-pending-resolved.png` 中金额列明显大于工单号字号、蓝色 #5baae8 加粗。
- 结论：**关闭**。

### 2.4 P1-4 M list RESOLVED 态按钮变体 —— NOT_CLOSED（渲染层未命中，需后续源码级处理）

- 源码与产物均确认实现已就位：`page.css` 新增 `.mas-page .mas-card-action[data-status=RESOLVED]{…78.876×28.852 白底描边 #d1d5db…}`，`index.tsx` 按钮已挂 `data-status={item.status}`，且 dist `merchant/sub-vendors.wxss` 内规则完整存在。
- **但运行时未生效**，四重独立证据：
  1. automator `outerWxml`：渲染节点 `<button id="mas-case-…" class="mas-card-action" … data-sid="_Jg">查看详情</button>` —— **无 data-status 属性**；`.mas-tag` 同样无（`<text class="mas-tag" …>待受理</text>`）。
  2. `attribute data-status` 查询：按钮与标签均返回**空**（`data-sid` 能返回，证明查询通道正常）。
  3. automator 实测 7 个列表按钮**全部** 74.6875×31.0312（390 窗口）→ 归一 **76.986×31.986 = 77×32 基础变体**；无一个 78.876×28.852（归一 ≈76.53×28.00）。
  4. 截图像素取证（`M-list-scrolled-resolved-cards.png` 底部条带色彩统计）：已裁决卡（126/110/94）按钮为**实心蓝 #5baae8**、标签为**琥珀 #fffbeb**（基础样式），而非设计的白底描边灰/绿标。
- **根因**：Taro 4.1.5 模板渲染未把 JSX 上的动态 `data-*` 属性落到原生节点（编译产物 `index.js` 中 `"data-status":s.status` 存在，运行时 DOM 缺失）。因此 `[data-status=RESOLVED]` **WXSS 属性选择器永远匹配不上**。
- **波及面（超出本项但需登记）**：同一机制使**修复前就存在**的 `.mas-tag[data-status=RESOLVED/INVALIDATED/WITHDRAWN/CLOSED]` 状态标签变体同样从未在真实渲染中生效（此前 VIS 报告为规格级比对，未做过运行时验证）；merchant services 页 `msvc-card-status[data-status=…]`、`msvc-toggle[data-on=true]` 等仓库其他 data-* 属性选择器用法面临同样风险。
- **修复方向（后续 Issue，不属于本次复验范围）**：改用类名变体（如 `.mas-card-action.resolved`）或 Taro 层等价机制替代 data-* 属性选择器；不建议引入原生 `setData`。
- 结论：**未关闭**（实现正确地存在于源码/产物，但在真实渲染中不可达）。

### 2.4b P1-4 修复后复验（2026-10-02 第二轮）—— CLOSED

**修复方案**：aftersale 页面所有 WXSS 属性选择器改为**类名变体**——`page.css` 新增 `.mas-filter-selected` / `.mas-tag-resolved` / `.mas-tag-closed` / `.mas-card-action-resolved` / `.mas-opinion-choice-selected` 五条；`index.tsx`/`detail.tsx` 改模板字符串 className，`model.ts` 新增 `statusTagClass()`；动态 `data-*` 全部移除（修前失配根因即 §2.4 所述 Taro 4.1.5 不下传动态 data-*，属性选择器永不可达）。typecheck / 254 单测（新增 `src/merchant/tests/aftersale-model.test.ts` 2 例）/ build / check:package 全 PASS。

**复验环境**：同 recipe 重起隔离后端（本轮 runId `5f0dcf64…` 后续批次，baseUrl `127.0.0.1:21532`，MySQL/Redis 回环随机端口；PowerShell 5.1 空环境变量 shim 方案复用）；模拟器同 iPhone 12/13 (Pro) 390×753 pixelRatio 3。数据：种子 3 PENDING（146/158/167）+ 3 RESOLVED 终局（94/111/127），另经**真实 buyer API** 创建工单 323 并真实撤回（`POST /c/orders/8900000000000077/aftersales` → `POST /c/aftersales/9160000000000323/withdraw`，PENDING v0 → **WITHDRAWN v1**，控制入口核对零资金零渠道），凑齐灰标签用例。

**实测结果（390 窗口 ×402/390 归一）**：

| 检查点 | 修后实测 | 设计/期望 | 结论 |
|---|---|---|---|
| RESOLVED 卡「查看详情」按钮几何 | `.mas-card-action-resolved` 命中 **3 个**，76.52×27.98 → **78.87×28.85** | 78.876×28.852 | PASS |
| RESOLVED 按钮填充 | 计算样式 `rgb(255,255,255)`；截图区域白 1785px、**蓝 #5baae8 为 0px** | 白底 | PASS |
| RESOLVED 按钮描边 | 截图区域内 #d1d5db 族像素 96px（外缘抗锯齿 (234,235,238)） | 描边 #d1d5db 0.962 | PASS |
| RESOLVED 按钮文字 | 计算 `rgb(107,114,128)`=**#6b7280**；字号 11.1965px→**11.545**（设计 11.541） | #6b7280 / 11.541 | PASS |
| RESOLVED 状态标签 | `.mas-tag-resolved` 命中 3 个；bg 计算 **rgb(192,236,255)=#c0ecff**、文字 **rgb(21,128,61)=#15803d** | 绿变体 | PASS |
| 灰标签（WITHDRAWN） | `.mas-tag-closed` 命中 1 个（工单 323）；bg **rgb(243,244,246)=#f3f4f6**、文字 **#6b7280** | 灰变体 | PASS |
| 筛选选中页签 | 选中 `.mas-filter-selected`（已裁决）：color **rgb(91,170,232)=#5baae8**、font-weight **600**；未选中（全部）：**#6b7280** | 加粗+#5baae8 | PASS |
| 意见抽屉选中胶囊 | 点「同意用户意见」后 `.mas-opinion-choice-selected`：color **#5baae8**、background **rgb(240,251,255)=#f0fbff**；截图像素：#5baae8 边框/文字 61px、#f0fbff 底 1772px、#6b7280 残留 0px | #5baae8 边框/文字 + #f0fbff 底 | PASS |
| PENDING 卡按钮对照 | 4 个基础按钮 74.69×31.03 → **76.99×31.99 ≈77×32 实心蓝**（截图蓝 #5baae8 在场） | 77×32 实心蓝 | PASS |

**新截图（本目录，363×785 原始 PNG）**：`M-list-fix-top-withdrawn-pending.png`（灰标+琥珀标+蓝按钮对照）、`M-list-fix-scrolled-resolved.png`（绿标+白底描边按钮）、`M-list-fix-filter-resolved-selected.png`（选中筛选态）、`M-drawer-fix-chip-agree-selected.png`（选中意见胶囊）。

**最终结论：P1-4 CLOSED。** 上轮 §2.4 所述"data-* 属性选择器在 Taro 4.1.5 渲染层不可达"的机制问题经类名变体方案彻底规避；本条五个变体（按钮/标签/筛选/胶囊）全部在真实渲染中命中并匹配设计值。仓库内其他仍使用动态 data-* + WXSS 属性选择器的页面（如 merchant services 的 `.msvc-card-status[data-status=…]`、`.msvc-toggle[data-on=true]`）建议后续按同方案迁移（另行登记，不在本 PR 范围）。

### 2.5 P1-3 机器码中文映射 —— CLOSED（真实数据验证）

- 映射表（`merchant/aftersale/model.ts` typeLabels/demandLabels）与后端正式目录 `aftersale-catalog.yml` 5+5 同源；未知 code 回退原码（本批真实数据未出现未知 code，回退路径由单测覆盖，未做视觉验证——如实说明）。
- 真实渲染验证（截图为证）：
  - M list：新工单 1058 显示「问题类型 **未履约**」，其余「问题类型 **质量问题**」——无任何 SERVICE_QUALITY/NON_PERFORMANCE 机器码（`M-list-top-pending-resolved.png`、`M-list-scrolled-resolved-cards.png`）。
  - M detail（工单 1058）：申请信息卡「问题类型 **未履约**」「用户诉求 **退款**」＋发起窗口截止（`M-detail-pending-nonperformance-refund.png`）。
  - C apply 目录 Picker range 实测渲染「费用争议/未履约/其他/宠物安全/质量问题」与「道歉/其他/部分补偿/退款/重新服务」完整 5+5 中文（picker outerWxml 读取 + `C-apply-filled-nonperformance-refund.png`）。

## 3. 真实业务流程造数（无 mock、无 page data 注入）

| 动作 | 通道 | 结果 |
|---|---|---|
| buyer 真实登录 | shell「微信登录」（wx.login mock 隔离码）→ 真实 `/c/auth/*` | 会话 138****0100，恢复校验通过 |
| C apply 打开 | 列表页输入 `8900000000000077` →「查看申请资格」→ apply 页 | 真实资格校验 eligible，目录/截止时间真实加载 |
| **C 申请提交** | apply 页原生 Picker 选「未履约/退款」+ textarea 30 字 + 提交 | 自动进入详情，**新工单 `9160000000001058` PENDING v0**；控制入口核对 counts：commands 1 / evidenceBatches 1 / transitions 1 / statusLogs 1 / outbox 1 / 资金各项 0 / channelCalls 0（与创建基线一致） |
| owner 真实登录+准入 | 退出→owner 登录→工作台 memberships/admission | ALLOWED，「售后管理」入口出现（OWNER+售后读权限） |
| **M 意见提交** | 详情→意见抽屉选「同意用户意见(AGREE)」+文本→提交 | 详情新增 MERCHANT 批；控制入口核对：**PENDING v1、evidenceBatches 2、commands 2、资金 0、channelCalls 0** |
| 完整流程 RESOLVED | 启动种子（REJECT/RESERVICE/OTHER 三笔正式终局，真实 HTTP 创建→受理→终局） | M 列表可见 3 张已裁决卡，详情/列表与种子摘要一致 |

全环境资金计数（refundOrders/refundExecutions/fundings/refundAfterSaleProofs/orderRefundCommits/paymentDispatches）与 channelCalls 始终为 0。

## 4. 截图与叠图产物（本目录）

### 4.1 模拟器截图（363×785 原始 PNG，= 390×844 设备屏 ×0.9305；含 DevTools 渲染的状态栏，MINI-JOINT 口径）
| 文件 | 内容 |
|---|---|
| `C-apply-top-empty.png` | C apply 首屏初始态（几何测量态；P1-1） |
| `C-apply-filled-nonperformance-refund.png` | 填表态：未履约/退款中文目录、说明文本 |
| `C-apply-bottom-after-submit.png` | apply 下半屏（新问题说明/上传图片/提交按钮；提交后锁定态） |
| `C-detail-pending-new-case.png` | C 详情 PENDING（页面真实创建的 1058） |
| `M-list-top-pending-resolved.png` | M 列表顶部（PENDING 卡 + P1-3 中文 + P1-2 金额） |
| `M-list-scrolled-resolved-cards.png` | M 列表滚动后（已裁决卡 126/110/94——P1-4 失效证据） |
| `M-detail-pending-nonperformance-refund.png` | M 详情（P1-1b 卡宽 + P1-3 中文） |
| `M-drawer-opinion-open.png` | M 意见抽屉开启态 |
| `M-detail-after-opinion-agree.png` | AGREE 意见真实提交后的详情（商家批次出现） |
| `C-apply-before-submit-initial.png` | 149×321（liteMode 小窗时期首拍，仅留档） |

说明：C 售后页底部出现的产品五项底导航（首页/服务/宠友圈/消息/我的）来自 consumer 侧既有共享组件 `ConsumerPageLayout`（`consumer/components/navigation/model.ts`），属于 app 现有结构，非 DevTools 产物、非本批新增。

### 4.2 Figma 导出与叠图（C 项完成度）
- 已导出（PAT + `images?scale=2&format=png`，存本目录）：`figma-C-apply-129-10572@2x.png`、`figma-M-list-40-1061@2x.png`、`figma-M-detail-40-1345@2x.png`、`figma-M-drawer-40-1500@2x.png`。
- 已叠图（402 坐标系，按第一卡顶边对齐；方法沿用 2026-09-16 c-integration-evidence/visual 的 overlay/difference 口径）：
  - `overlay-C-apply-card1-402.png` / `difference-x3-C-apply-card1-402.png` —— **P1-1 修后卡左右边缘归零**。
  - `overlay-M-detail-card1-402.png` / `difference-x3-M-detail-card1-402.png` —— **P1-1b 修后卡左右边缘归零**。
  - `overlay-M-list-card1-402.png` / `difference-x3-M-list-card1-402.png` —— 列表卡边缘归零；按钮区域差异即 P1-4 设计描边灰 vs 实渲染实心蓝的可视化。
  - `sidebyside-{C-apply,M-list,M-detail}-402.png` —— 设计稿|实渲染全页并排（内容差异为已登记的业务替代/投影缺口，不计入几何结论）。
- **未做**：M 抽屉（40:1500）逐像素叠图——设计帧为旧"发起申诉"单字段表单（固定高 355.904），实现为意见胶囊+补证上传的新范围表单（VIS-OVERLAY §S9/§7 已登记设计缺稿），无可一比一对照的状态，叠图无判定意义。
- 差分图阅读口径：卡边缘无竖向条纹=宽度对齐；文字重影=业务替代内容差异（已登记，非几何差异）；圆角处少量残影=整数对齐的亚像素残差（≤0.5 设计px）。

## 5. 仍需物理真机验收的项（本次未关闭、也不应在模拟器关闭）

1. **字体渲染**：C AftersaleCjk 子集缺字后的系统回退、M 无 @font-face 时 Roboto/Outfit 全落系统字体、placeholder Inter 500（VIS-002）。
2. SVG `scaleY(-1)` 镜像方向（C back/add）与 M `nav-back@3x.png` 方向。
3. CSS `dashed` 虚线线型 vs 设计 dash[4.385,4.385]。
4. 头部垂直几何（真实 statusBarHeight 叠加，P2-7）——本次实测 C 首卡顶距设计坐标恰差 48.46 设计px（=47 CSS statusBarHeight），与 P2-7 登记一致，需真机实测后按内容区叠图。
5. 键盘弹起/收起、全面屏安全区（顶部胶囊/底部横条）、切后台、权限生命周期、相册真实选图与上传（10MiB/16M 像素边界）。
6. **P1-4 真机复核**：类名变体已在模拟器真实渲染验证（§2.4b）；真机验收时复核描边/字重在真实字体下的观感即可。另：仓库内其余动态 data-* + WXSS 属性选择器用法（merchant services 页等）尚未迁移，真机/模拟器上同样不生效，需另行登记迁移。
7. `selectAll('button')` 空数组遗留（本次用标签选择器与 SelectorQuery 绕行实测，未解原生 button 匹配问题）。

## 6. 遗留风险与边界

- 本批 dist 已被 dev 开关构建覆盖（origin=`http://127.0.0.1:18081` 编译进产物）；复验结束后已恢复默认生产构建并通过 check:package（见 §7 清理记录）。若后续需要带 origin 的包需按 DEVICE-PREP §2.3 重新构建。
- project.config.json（真实 appid）保持"已修改未提交"，未还原、未提交；`project.private.config.json`（urlCheck:false）为 ignored 临时文件，已删除。
- 后端第 1 次启动失败根因（PS5.1 空环境变量删除）已在本报告 §1 如实记录；若他人复现本流程需注意该 Windows 陷阱。
- runtime.json/runtime.tmp 凭据随 fixture 关闭自动删除；本报告不含任何令牌/secret/手机号明文。
