# M-004 商家 OWNER 售后页面切片交接

日期：2026-10-01。基线：PR #98 / develop `e3b846e`。本切片为可集成的非出款页面代码与定向测试，不表示完整 M-004、跨三端 E2E 或 VIS 已验收。

## 依据与范围

已读取 AGENTS.md、WORK_EXECUTION_PROTOCOL.md、AI_TEAM_OPERATING_MODEL.md、SSOT §40、前端技术基线20、验收21、PRD31、Contract51、OpenAPI11，并检查真实 AFS evidence/opinion 状态机与 MER OWNER 读写适配。

修改范围仅 `frontend-miniapp/src/merchant/aftersale/**`、`merchant/pages/aftersale/**`、`merchant/tests/aftersale*.test.ts` 及本交接。共享客户端为协调者提供的 `fbdea59`、`b8ac7e1`，在本工作树 cherry-pick 为 `84e3059`、`a82a1a9`；集成时只 cherry-pick 商家业务提交。无 Contract/Schema/Event 变更，未改 App、shared、workspace、package 或锁文件，未 push/PR/merge。

交付页面：

- `/merchant/pages/aftersale/index`：指定当前 merchant/store，七种正式工单状态筛选、分页与详情入口。
- `/merchant/pages/aftersale/detail?afterSaleId={String}`：真实申请说明、问题/诉求编码、用户诉求金额、补证轮次、双方不可变证据批次、平台真实结论；四种商家意见、图片/文字补证。

每次页面进入及写/私有证据读前重新查当前 OWNER 准入；STAFF、无准入、错门店、查询失败不放行。FROZEN 只读；OFFLINE 存量写仍由服务端 requireOwner 决定。未把账号/工作区标志当授权，不派生 DisplayOrderStatus，不把 requestedAmount 当实付/可退金额，无商家终裁/退款命令。

## Figma 插件真实读取与资产

用户明确指定 @Figma。读取本机 `figma-design-to-code/SKILL.md` 后调用插件 `get_design_context`，`skillNames=figma-design-to-code`、`clientFrameworks=react,taro`、`clientLanguages=typescript,css`，初次包含截图。初次列表调用实际返回 UNAUTHORIZED / Reauthentication required；用户重新连接后重试成功，下列四个节点均获得高保真 React 代码及截图，无 sparse 响应：

| 节点 | 原稿 | 尺寸 | 用途 |
|---|---|---|---|
| `40:1061` | 订单-退款 | 402×984 | 白卡列表/胶囊标签与按钮 |
| `40:1345` | 订单-退款-详情 | 402×1136 | 详情卡片、文本、证据槽位、按钮尺寸 |
| `40:1500` | 订单-退款-申诉 | 402×1136 | 原始遮罩及底部说明表单抽屉 |
| `40:1676` | 订单-退款-申诉（确认退款状态） | 402×1136 | 核查本批不实现的商家确认退款边界 |

节点来源是设计源登记表 `docs/08-engineering/20-设计源登记表-figma-map.md`，商家 fileKey `Usvn3d6UCVCAlDxou5KAK8`。登记版本为 `2401168563353004923` / lastModified `2026-09-20T07:05:58Z`；本次插件未提供版本标识，因此不声称该登记值就是本次在线读取版本。

另外读取 `figma-use/SKILL.md`、standalone API index、gotchas 及 exportAsync/base64Encode 定义后，以 `use_figma` 只读导出真实 `40:1349` 返回图标 PNG，scale=3，未修改 Figma 画布。文件 `pages/aftersale/assets/nav-back@3x.png`，27×44、471 bytes，SHA256 `582a810a8c9121d3229673b73cceb0a52a0819e32c3dc07f272e27ab0a89a043`。已经本地目视检查非空左向箭头；页面调用位于 `page.tsx`，有效尺寸 8.8575×14.5227 design px ×实际 windowWidth/402，x=17 design px。原始 Group 源尺寸 8.8575439453125×14.522697448730469。Manifest 记录上述关系，无临时 Figma URL，也无整页截图铺底。详情真实证据图片只从绑定当前端别/会话的单次 grant 下载为私有本地临时文件；不放静态假证据或源稿客户照片，不用 Taro.previewImage 形成系统图库缓存。

## 安全、幂等及恢复

意见/补证沿共享 ConsumerApi 持久同 UUID/原 body；未知结果锁定原动作和内容，重进恢复原输入，即使后来工单已经终态仍能在当前写权限成立时重放原命令。只有共享 `isDefiniteAfterSaleConflict` 白名单里的确定业务409可退休并重读真实工单；COMMON_CONFLICT可同时表示幂等争锁忙，不能称为确定CAS。COMMON_CONFLICT、IDEMPOTENCY/IN_PROGRESS、未知409及429一律保留原内容/UUID，显式原提交重试。已收到成功回执但详情读失败明确显示“已提交”，要求重新加载后再动作。

WAITING_SUPPLEMENT 无论目标 USER/MERCHANT 都带当前真实 supplementRequestId；目标 USER 时商家意见不声称完成用户补证。截止等号及以后不发起本轮新提交，等待后端 worker/read 显示恢复状态，不自动推断终裁。所有 ID/version 为 String，说明按 Java UTF-16 长度10..500、每次0..6唯一 assetId。

上传使用共享 `privateUploadFiles()`：JPEG/PNG 签名与10MiB上限校验、复制为 app 拥有的私有文件、保存原文件 sha256/bytes；每次真实网络重试先校验副本 fingerprint。独立 journal key 绑定当前 userId+merchantId+storeId+caseId，只保存 UUID、owned filePath、fingerprint、attempted、READY receipt 及坐标，无 token。它跨进程重启/退出登录保留未知结果，只有原用户重新登录、选中同门店且再次通过真实 OWNER 准入后可恢复。另一用户无法读出原 journal。

保存顺序为 attempted journal → 上传 → READY receipt journal → 包含 assetId 的草稿 → 删除拥有副本 → 清 journal。若 receipt 后草稿保存失败，恢复使用已知 receipt，不重复上传。若原副本变更/丢失，失败关闭，不换新 UUID 猜测旧结果；后续 ingress 415 不能证明先前未知上传未成功。删除只调用 owns 校验后的文件适配，不删除相册原图。尚未确认的副本和独立 journal保留至原身份恢复，未增加无依据的自动过期销毁。

切用户/门店/工作区、同坐标重新校验、隐藏/销毁页面会立即清 UI 和私有证据引用；迟到响应不能回写新的上下文。图片查看410会要求新 grant；原 grant 不复用。

## 已运行验证

本工作树使用协调者完成 npm ci 的 node_modules 只读 junction（未改变依赖/锁文件）：

- `npm run typecheck`：通过。
- `node node_modules/tsx/dist/cli.mjs --test src/merchant/tests/aftersale*.test.ts`：21/21通过（含独立QA发现后的补测）。覆盖 OWNER/STAFF、FROZEN/OFFLINE、无准入失败关闭、真实门店筛选、延迟响应隔离、期限等号、四种意见与字符限制、未知结果及终态重放、白名单确定补证409、COMMON_CONFLICT/IDEMPOTENCY/未知409/429不退休、真实客户端争锁后重构同UUID/body、ACK后详情失败、保存副本及UUID重启恢复、receipt→草稿故障恢复、fingerprint变更、后续415不丢原UUID、私有证据410、merchant/store/case/owner持久隔离和登出后同人恢复/换人不可见。
- `git diff --check`：通过；PNG文件尺寸/非空、SHA、源节点、调用位置和CSS几何已静态核查。

此处测试使用内部可控依赖与真实 ConsumerApi 的存储/会话恢复机制；不伪装真实微信网络、扫描、后端数据或生产账号。

QA修复时已按协调者授权将根审定共享文件从 `6f865a4` 同步至本树，仅供验证（本树同步commit `1d43c26`，根不需 cherry-pick）。共享未知命令跨401重新认证恢复由协调者负责；商家修复只使用其公共helper、未自行改共享实现。

## 根接线与剩余验收

根 app.config 在既有 merchant 分包追加 `pages/aftersale/index`、`pages/aftersale/detail`；workspace 的真实 OWNER `merchant.aftersale.read` / VIEW_AFTERSALES 入口走 `controller.handoffToChild()` 再 navigateTo 列表，失败时 handoffCancelled()。协调者已负责这两处唯一编辑。

根串行完成集成目标构建、包体/架构检查及三端测试。本子代理尚未运行真实微信开发者工具、真机上传/单次图片读取、业务账号三端闭环或叠图；MINI-005/006、E2E与VIS-003仍待实际证据，不宣称通过。

VIS-004差异必须登记并评审：原稿是退款订单（客户姓名/头像/电话、服务名/预约时间、实付金额、履约时间线、店铺头信息和全局底部导航），Contract51无上述投影且本批商家终裁/确认退款关闭。实现保留可承接的卡片/字号/颜色/圆角/抽屉视觉语言，改为真实工单信息/入卷证据和“补充证据/提交意见”，没有假造客资、订单、履约或金额。列表变为正式工单状态筛选；未创建不存在路由的源稿导航按钮。字体 Roboto/Outfit/Inter 未获得可发布的准确字体文件，系统状态区按当前微信窗口实际安全区域安排。上述裁剪/字体/系统区域仍是明确视觉验收缺口，不能按原稿一比一或“95%还原”交付完整M-004。
