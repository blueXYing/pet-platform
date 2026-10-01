# 售后页面物理微信真机验收：前置物料与执行清单

2026-10-01。基线：worktree `C:\Users\Administrator\.codex\worktrees\aftersale-pages-20261001\宠物平台V1.0`，分支 `codex/aftersale-pages-20261001`，head `4bb317f`（初始 git 状态 clean）。本清单只覆盖"物理真机验收"的准备与执行；VIS、CI、合并结论见同目录各 HANDOFF，不在本文件扩大 Scope。对应 PR #99 验收边界中"物理真机与 VIS 尚未通过"的未验收项。

## 0. 本批已完成的准备（2026-10-01 实测记录）

| 项 | 结果 |
|---|---|
| 真实 AppID 配置 | 已把用户主目录 `frontend-miniapp/project.config.json`（appid `wx1a64646b1d75306e`）复制到本 worktree `frontend-miniapp/project.config.json`，保持"已修改未提交"，**绝不 commit**（改动仅为 appid + DevTools 本地 setting，不含业务源码） |
| `npm run typecheck`（`tsc --noEmit`） | PASS |
| `npm run build:weapp`（默认无 origin，生产压缩） | PASS；主包 774305 字节、最大分包 `consumer/pages/pet-archive` 1815321 字节、总 4376887 字节（PR 描述为 773746/1815321/4376328，主包差 559 字节属构建环境差异，最大分包一致） |
| `npm run check:package`（MINI-005 静态门禁） | PASS（上表体积即其输出；5 个普通分包：merchant 177736、pet-archive 1815321、merchant-application 91838、store-services 1240400、aftersale 277287） |
| 带上传开关的 dev/preview 构建 | PASS（命令见 §2.3）；主包 779194、merchant 183170、pet-archive 1815321、merchant-application 101598、store-services 1240400、aftersale 277437、总 4397120、文件数 168；`check:package` PASS；Taro 日志确认"预览模式生成的文件较大"即开发模式生效，且 `192.168.1.44` origin 已编译进 `dist/common.js` |
| DevTools 预览二维码 | 已生成（wechatide `create_preview_qrcode`，qr-format=image）：`C:\Users\Administrator\AppData\Local\Temp\aftersale-20261001-preview-qr.png`（470×470 JPEG）。**预览二维码有时效（约 25 分钟）**，过期按 §2.4 重新生成；DevTools 编译计数：total 4364554、main 768003、aftersale 273500（DevTools 排除了部分静态资源，与 package-check 口径不同，均正常） |
| 临时进程清理 | 已用官方 `cli quit`（未生效）后 `taskkill` 结束本次自动化拉起的全部 DevTools 进程（14 个 PID，启动时间 23:43:37–23:44:00 均为本次调用产生），复核归零；既有项目 Redis `xinzitong-uat-local-20260930-redis`（127.0.0.1:16389）全程未受影响 |

## 1. 前置条件（不满足则不要开始真机验收）

### 1.1 网络：本机回环不能当手机后端

- 本批联调后端（`backend/tools/run-aftersale-joint.ps1` 启动的 Spring Boot/MySQL/Redis）**只监听 127.0.0.1 回环随机端口**（见同目录 BACKEND-HANDOFF.md），手机物理上不可达。
- 必须满足：PC 与手机接入**同一 Wi-Fi/局域网**；用 PC 局域网 IP 访问。本机实测（2026-10-01）：PC 以太网 IP **192.168.1.44**（网关 192.168.1.1）。验收当天先 `ipconfig` 复核该 IP 仍是 192.168.1.44，变了则全文替换。
- 后端本身不监听 0.0.0.0（这是既定隔离做法，不改生产代码），需在本机起一个**临时 LAN 反向代理**监听 `0.0.0.0`（如 `D:/Python/python.exe` 起一个 TCP/HTTP 转发），把 `192.168.1.44:18081` 转发到 runtime.json 里的回环 `baseUrl`。先例：MER-001-s9 `MER001-LocalAcceptance-18081` 专用防火墙规则——**只**放行指定程序（D:/Python/python.exe）+ 本机 IP 192.168.1.44:18081 + 192.168.1.0/24 网段；验收结束**删除该专用规则**。
- 小程序端 API base 指向该代理：构建时 `PET_C_API_ORIGIN=http://192.168.1.44:18081`（本批 dist 已按此值构建；若代理端口改动必须重新构建，见 §2.3）。
- 连通性验证：手机浏览器访问 `http://192.168.1.44:18081/api/v1/...`（任一业务 GET），返回**预期 401 未登录**即链路通（MER-001-s9 手机真机先例同款验证）；401 都拿不到说明代理/防火墙/同网段有问题，不要进入页面验收。

### 1.2 后端：按既有隔离 recipe 启动，不启动生产服务

1. 按 BACKEND-HANDOFF.md：`powershell backend/tools/run-aftersale-joint.ps1 -Mode Start`（Java 21 / offline Maven；每批独立 MySQL 8.4 / Redis 7.4-alpine 容器、随机 schema、随机 Redis namespace、回环随机端口；控制入口仅回环且须 `X-Joint-Control` 随机 secret）。
2. 凭据与会话只读 ignored `backend/pet-boot/target/aftersale-joint/runtime.json`：`baseUrl`（含 `/api/v1` 的后端地址）、`admin`、`buyer`、`owner`、`scope`（测试 merchantId/storeId）、`control.baseUrl` + secret、`mini.orderId`（已核销、未创建售后的订单）。**禁止把其中令牌/凭据写入 Git、报告或截图可辨识区域。**
3. 隔离与清理要求（同 BACKEND-HANDOFF.md）：启动和清理都核对完整容器 ID/名称/任务 label/回环绑定；结束用 `-Mode Stop` 或控制入口 `POST /stop`；清理后 `docker ps -aq` 中不应再有本批两个 MySQL/Redis 容器，回环端口不再监听，**原项目 Redis 16389 与其它容器不得触碰**；`runtime.json`/`runtime.tmp` 凭据文件随批清理。
4. 固定端口历史先例（如需固定端口排障）：M-002/MER-001 先例曾用 MySQL `127.0.0.1:33452`（协调者临时实例）+ Redis `16383`，与本批随机端口制并存；用固定端口时同样每批建独立库名并进程退出 DROP。
5. **Hikari 池注意事项**（MER-001-s9 真机验收教训）：本机常驻服务若用"每请求新建物理连接"（DriverManagerDataSource 模式），常驻 worker 与 Snowflake 单飞行道续租会在手机高延迟场景下卡死（ID Provider `OPERATION_TIMEOUT` 持续 fail closed）；先例改为 **Hikari 连接池（最小 4、最大 8）**后连续 120 秒 9 次匿名登录 attempt 全部 201。临时 LAN 代理/长驻进程如涉及数据库连接，必须复用带 Hikari 的服务器进程，不得另起裸连接脚本直连业务库。
6. 健康验证（启动后、扫码前依次做）：
   - PC 上带 `X-Joint-Control` secret 调控制入口 `GET <control.baseUrl>/summary?caseId=<caseId>` 返回 JSON 计数（无令牌 403）；
   - PC 上 `GET <baseUrl>/api/v1/c/...` 匿名请求预期 401；
   - 手机浏览器过代理（§1.1）同一请求预期 401；
   - 已知先例：个别测试服务器 health 端点可能报 DOWN（某组件尝试连默认 6379，而本机 Redis 在其它端口），**只要业务 API 全部预期码即不当作整体失败**，如实记录即可；
   - 测试预约时钟固定 `2030-01-01T10:30:01Z`（页面显示北京时间），补证截止按该值构造；不要推进该时钟。

### 1.3 小程序构建与本地配置

1. 当前 worktree `frontend-miniapp/project.config.json` 已是真实 AppID（未提交）。**不要提交、不要还原。**
2. 新建 ignored `frontend-miniapp/project.private.config.json`（已在 frontend-miniapp/.gitignore）：`{"setting":{"urlCheck":false}}`，用于 DevTools 模拟器关闭域名校验；该文件不入 Git。真机预览版则以手机端"打开调试"跳过域名校验（见 §2.4）。
3. 脚本名（frontend-miniapp/package.json，勿猜）：`typecheck`=`tsc --noEmit`；`build:weapp`=`taro build --type weapp --disable-global-config`；`dev:weapp`=同参数 `--watch`（常驻，验收后才可停）；`check:package`=`node package-check.cjs`。
4. 开关（config/index.ts defineConstants，全部来自环境变量）：`C_API_ORIGIN`←`PET_C_API_ORIGIN`；`ALLOW_LOCAL_HTTP`←`NODE_ENV=development` 且 `PET_ALLOW_LOCAL_HTTP=true`（**只放行显式本机/私网 HTTP**，生产默认 HTTPS 不受影响）；`MERCHANT_APPLICATION_ENABLED`←`PET_MERCHANT_APPLICATION_ENABLED=true` 且需 origin（**缺失时 M 端准入客户端直接失败关闭 ADMISSION_NOT_CONNECTED**，M-002 WINDOW-E2E 先例曾因此返工）；`PRIVATE_MATERIAL_UPLOAD_ENABLED`←`PET_PRIVATE_MATERIAL_UPLOAD_ENABLED=true` 且需 origin（本批"上传开关"）。

### 1.4 A 端（运营网页）

PC 网页执行，手机非必须：按 ADMIN-QA-HANDOFF.md 口径，运营预览 origin `http://127.0.0.1:4174`（Playwright/真实浏览器登录），账号用 runtime.json `admin`；真机验收当天只需 PC 复核 A 端页面与 SQL 交叉核对。

## 2. 验收当天执行顺序（照做即可）

### 2.1 起环境

1. `-Mode Start`（§1.2）→ 读 runtime.json 得 `baseUrl`；
2. 起临时 LAN 代理 `0.0.0.0:18081` → runtime.json `baseUrl`（§1.1）+ 专用防火墙规则；
3. 按 §1.2-6 完成健康验证（PC 401、手机浏览器 401、control /summary 可用）。

### 2.2 播种可测订单

- 用 runtime.json 的 `mini.orderId`（已核销、未创建售后）做 C 端申请资格单；`buyer`/`owner` 手机号登录走真实 `/c/auth/session`。
- 如需更多订单，按 BACKEND-HANDOFF 口径经业务 API 真实创建/确认/核销，**页面不插入售后业务结果**。

### 2.3 构建小程序（origin 以实际代理为准）

```bash
cd frontend-miniapp
NODE_ENV=development \
PET_C_API_ORIGIN=http://192.168.1.44:18081 \
PET_ALLOW_LOCAL_HTTP=true \
PET_MERCHANT_APPLICATION_ENABLED=true \
PET_PRIVATE_MATERIAL_UPLOAD_ENABLED=true \
npm run build:weapp
npm run check:package   # 必须 PASS
```

- 换构建产物后在 DevTools 里先 `清缓存→编译缓存`（cleanCompileCache）再刷新模拟器（M-002 先例）；存储键跨刷新保留。
- 纯 UI 检查可离线构建（不配 origin 也能看页面/字体/布局），但涉及申请/补证/上传的用例必须带全开关 + origin。

### 2.4 预览二维码（手机扫码）

- 自动（本批已验证可用，wechatide 位于 `D:\soft\微信web开发者工具\wechatide.cmd`）：

```bash
cd /d/soft/微信web开发者工具
./wechatide.cmd -c ZCode create_preview_qrcode \
  --project "C:\Users\Administrator\.codex\worktrees\aftersale-pages-20261001\宠物平台V1.0\frontend-miniapp" \
  --qr-format image \
  --qr-output "C:\Users\Administrator\AppData\Local\Temp\aftersale-20261001-preview-qr.png"
```

- 手工等价路径：DevTools 打开上述项目 → 工具栏点"预览" → 手机微信扫码。
- 手机端必做：扫码进入预览版后，点右上角胶囊"…"→**打开调试**（跳过合法域名校验 + 开 vConsole 供取证）；预览二维码过期就重新生成。
- 用完关闭 DevTools（`./cli.bat quit`，若无效再按 PID 结束本批拉起的进程）。

## 3. 待测页面与流程（操作步骤 + 期望结果）

页面路由（app.config.ts）：C 端分包 `consumer/pages/aftersale/`：`index`（列表）、`detail`（详情/补证/撤回）、`apply`（申请）；M 端分包 `merchant/pages/aftersale/`：`index`、`detail`。正式目录（aftersale-catalog.yml，5+5）：问题类型 FEE_DISPUTE 费用争议 / NON_PERFORMANCE 未履约 / OTHER 其他 / PET_SAFETY 宠物安全 / SERVICE_QUALITY 质量问题；诉求 APOLOGY 道歉 / OTHER 其他 / PARTIAL_COMPENSATION 部分补偿 / REFUND 退款 / RESERVICE 重新服务。

### 3.1 C 端（手机真机，BUYER 账号）

| # | 用例 | 操作步骤 | 期望结果 |
|---|---|---|---|
| C1 | 列表 | 微信登录（真实会话）→ 进入售后列表页 | 本人工单列表真实加载；空态/有单态与 DevTools 一致；无 JS 报错（vConsole 无 pageerror） |
| C2 | 资格 | 打开 runtime.json `mini.orderId` 对应订单 → 进售后入口 | 本人资格检查通过（eligible），可进入申请页；非本人/已售后订单不可申请 |
| C3 | 申请（正式目录） | apply 页：原生 Picker 先后选问题类型、诉求（如 SERVICE_QUALITY + REFUND），textarea 输入说明，点提交 | 目录为完整 5+5 中文名（不得出现 QA_*/英文码/测试字典）；提交后自动进入工单详情，状态 PENDING、version 0；SQL/summary 新增 1 command、1 evidence batch、1 transition/statusLog/outbox |
| C4 | 详情 | 进入详情页 | 状态、订单信息、证据批次、时间线与后端一致；当前 USER 补证轮次为空时不显示补证控件 |
| C5 | 补证（含相册上传） | A 端要求 USER 补证后 → C 详情读当前轮次 → 输入文字 → **从手机相册选图**（真实 chooseMedia）上传 → 提交 | 真实 JPEG/PNG 上传成功（真机原图常见 >1MB；边界 10MiB/16M 像素，超限稳定报无效材料而非卡死）；提交后详情新增第二 USER 批且含图片，当前轮次清除，version+1 |
| C6 | 私图读取 | 详情点开证据图 | 私图经真实 grant/consume 加载并带水印；关闭后旧 grant 不可重放（再次读取需新 grant） |
| C7 | 撤回 | 详情点撤回控件并确认 | 工单撤回成功、状态迁移真实落库；终局后详情不再出现补证/撤回控件（原生渲染树消失，DevTools 联调先例同口径） |

### 3.2 M 端（手机真机，当前店 OWNER 账号）

| # | 用例 | 操作步骤 | 期望结果 |
|---|---|---|---|
| M1 | OWNER 准入 | 退出 BUYER → OWNER 手机号真实登录 → memberships/admission → 工作台 | 准入经后端校验进入当前店；工作台出现"售后管理"入口 |
| M2 | 列表/详情 | 售后管理 → 打开 §3.1 同一工单 | 指定店列表与详情真实读取，与 C 端所见同单同版本 |
| M3 | 私图读取 | 详情查看用户上传的私图 | OWNER 可读且带水印；grant/consume 单次有效 |
| M4 | 四类意见 | 意见抽屉选 AGREE（及其它三类逐一覆盖）→ 输入意见 → 提交 | 每次提交新增唯一 MERCHANT 批、version+1；意见内容与后端一致 |
| M5 | 商家补证 | 按要求走 M 端补证入口上传/提交 | 同 C5 口径，MERCHANT 批真实落库 |
| M6 | 冻结只读 | 工单被受理中/已冻结/已终局后刷新 M 端 | 只读提示出现、写控件消失或禁用，无隐藏可写入口 |
| M7 | 私图清理 | 清图/登出 → 重新登录 | 本机私图缓存清除；重登后原私图必须重新 grant 才可见 |

### 3.3 A 端（PC 网页，手机非必须）

按 ADMIN-QA-HANDOFF.md 既有三个 Playwright 用例口径人工复核：指定店列表 → 详情受理 → 指定补证 → 完整 P4 历史逐笔核对 → REJECT/RESERVICE/OTHER 非退款终局 → RESOLVED 落库。真机当天只需在 PC 重放关键路径并与 SQL 交叉核对（§5）。

### 3.4 失败关闭态（必须逐项触发）

| # | 场景 | 触发方式 | 期望结果 |
|---|---|---|---|
| F1 | 目录缺失 | 停用/破坏目录装配后（仅测试环境）读目录或首次创建 | 目录读取及首次创建 503 `COMMON_DEPENDENCY_UNAVAILABLE`，页面失败关闭；不返回空成功、半份目录或测试回退字典 |
| F2 | 权限失效 | control `POST /admin/permissions {"mode":"READ_ONLY"}` 后重发原写请求 | 原请求 403；A/C/M 页面登出并清图；恢复 `FULL_AFS` 后需重新真实登录 |
| F3 | 未知写结果 | 受控丢 ACK（真实 POST 成功后 `route.abort()`，仅单次指定请求） | journal 保留原业务记录；无 fulfill/假响应；重试提交同 UUID |
| F4 | 冲突 409 | 按 CCR-AFS-CONFLICT-001 触发 case/finalSet 比较失败 | 返回两个专用 409 码之一；不做静默重试/改写 |
| F5 | 同 UUID 重放 | F3 后重新登录并显式原 UUID+原 body 重试 | 返回原 200 首回执；三次发送 UUID/body 一致；重放前后完整 SQL 摘要**严格相等**（命令/批次/迁移/日志/Outbox 计数不增）；不得换 UUID 重建 |
| F6 | 未知目录码 | 提交非法 typeCode/demandCode | 创建被拒，无售后迁移/证据批/资金动作 |

## 4. 真机专属检查项（对应 PR #99 已披露未验收项，逐项给结论）

1. **精确字体与中文回退渲染**：真机核对目录十项中文 label、意见/理由长文本；观察字体子集缺字与回退字体（DevTools 无法证明）。
2. **新状态稿**：C index/detail 及新状态视觉稿在真机的实际渲染（此前"新状态缺稿"差异项）。
3. **机器 code 显示**：M/A 页面此前显示机器 code（如 OTHER/RESERVICE 原始码）的条目，真机复核实际显示内容并记录是否仍为差异。
4. **卡片宽度**：真机（对照 390 设计像素）量测 C/M 五卡片 left/width；DevTools 基线：C left12.125/width365.75、M left28.125/width333.75（窗口 390×753）；与原稿仍有约 1 设计像素宽度差，真机实测数据作为继续校正依据，不新增容差。
5. **键盘/安全区/多窗口/权限生命周期**：textarea 聚焦键盘弹起与收起、全面屏安全区（顶部胶囊/底部横条）、切后台再回、相册/摄像头权限首次授予与拒绝后再试、杀进程重进后的会话与草稿恢复。
6. **相册选图与上传**：真实 chooseMedia 多选/取消/大图 JPEG 上传（MER-001-s9 先例：保留 JPEG quality0.95 清 EXIF、不缩小分辨率、10MiB/16M 像素上限）。
7. **离线签名付款通知**：属已知测试依赖（fixture provider），真机上**不作为通过条件**，仅记录现象。
8. 明确声明：DevTools 390 窗口（截图 363×785 是显示结果）不是物理手机；本节每项须在真机给出"通过/差异/阻塞"结论并附证据，不得用模拟器截图替代。

## 5. 证据记录格式与核对点

### 5.1 截图与原始材料

- 命名规则：`2026-10-01_<端>_<页面或场景>_<用例号>_<序号>.png`，如 `2026-10-01_C_apply_picker_C3_01.png`、`2026-10-01_M_opinion_sheet_M4_02.png`；同一用例多张递增序号。
- 入库仅放脱敏截图/文字结论；**真实会话 bearer、control secret、原始网络内容、手机私图原图不入仓库**，放 ignored 本地路径并在本文件登记"存过、已清"。

### 5.2 SQL / HTTP 侧核对点（复用 MINI-JOINT-HANDOFF / ADMIN-QA-HANDOFF 口径）

- 控制入口 `GET /summary?caseId=<String decimal>`（带 `X-Joint-Control`）：status/version（caseVersion）、orderVersion、commands、evidenceBatches、decisions、transitions、statusLogs、AFTERSALE outbox，及全环境零资金计数与 channelCalls。
- 同单全链路终局基线（联调先例，本批真机复测应同构）：**RESOLVED、caseVersion 5、证据批 3、决定 1、命令/迁移/状态日志/Outbox 各 6、orderVersion 9**；创建基线 PENDING/version0 各计数 1。
- **资金产物必须为 0**：退款单/退款执行/资金证明/售后资金证明/订单退款 commit/渠道 dispatch 各 0，channelCalls 0（每阶段断言，不只终局）。
- 版本一致性：RESOLVED 后 C/M 列表与详情读到同一 version 与结论；C 端补证/撤回控件从渲染树消失。
- 幂等：F3/F5 的三次发送 UUID/body 一致记录 + SQL 摘要前后严格相等。
- 会话：恢复后 `/c/auth/session` 真实复验；撤权 403 与恢复 FULL_AFS 前后各记录一次。

## 6. 遗留与边界（验收执行者须知）

- 本清单与构建产物不改变任何生产源码；`project.config.json` 的 appid 修改保持未提交。
- 未启动/未验收：MySQL/Redis/Spring Boot 本批未常驻启动（只写清启动方式）；物理真机操作须由人执行手机侧步骤。
- 微信上游 exchange、内容审核/病毒扫描、内存私有 OSS、离线签名付款通知仍是测试依赖；真实资金 Provider、公开退款裁决、worker/出款保持关闭。
- C-005/M-004/A-004/QA-005 不因真机准备置 DONE；PR 保持草稿；本清单不宣称 VIS 通过。
