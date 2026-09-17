# MER-001 S4 前端准备与原稿映射

日期：2026-09-17。检查基线：`65df1c3`（PR52 已合入）。工作区：`C:/Users/Administrator/Desktop/wt-mer001-application`。本交付仅为准备文档，不代表申请、审核、签约前端完成；没有修改页面、创建路由、commit 或 push。

## 执行条件与依据

- 本子任务按用户指定的 GPT-6 中等配置执行（父任务指定 `gpt-6-astra / medium`）。
- 用户指定 Figma 插件；父任务已发现插件未安装并提出安装。本轮没有可用的 Figma 插件调用，也未读取最新在线设计；以下全部为本地历史归档核对。未用浏览器、REST 或个人令牌替代插件。插件安装/连接后仍须核对版本、节点和状态，不能以本文解除此要求。
- 已读 AGENTS、前端基线20、验收21、SSOT §26–27、PRD26、DESIGN_SOURCES、申请审核 CCR及WAVE_2_PLAN（Admin不强制另一个运营Figma）。产品规则优先于视觉稿；照片1–6张必填、OCR异常待人工、补正映射REJECTED、APPROVE建ACTIVE四项已批准，不再索取业务确认。
- 申请 HTTP/存储/事件在本次检查时仍以 CCR 候选为准，待父任务契约同步交接后才能绑定生产接口。27号协议读取/同意两路由已有批准契约。

## 设计来源和原始资料

本地资料不在当前 worktree，且不上传 GitHub。下列绝对路径为本机资料位置，不是 GitHub 文件链接。

| 标记 | 本地目录 | 文件key / 历史版本 | 归档证据 |
|---|---|---|---|
| C | `C:/Users/Administrator/Desktop/宠物平台V1.0/planning/issues/wave-2/C-002-design-inputs` | `bp2vpcjjA5vZbHvtKkA8wl` / `2397539525915641008` | 完整快照 `full-file/source-metadata.json`，2026-09-15 02:27:02Z，SHA256 `bdf6dde1fd8cc5dc1b949f5043005e69a71e51176afeb4e0a9a0add1a14ce4ec` |
| M | `C:/Users/Administrator/Desktop/宠物平台V1.0/planning/issues/wave-2/M-002-design-inputs` | `Usvn3d6UCVCAlDxou5KAK8` / `2399015827492126503` | `source-metadata.json`，2026-09-15 01:57:55Z，SHA256 `69bdf237c2c5decc892422d4d9a20f259b3498ef614f6b716604aa0d9653355d` |

## 页面到节点、路由与组件

以下“拟落点”只是实施交接建议，尚无这些路由注册或页面代码，须由父任务分配允许写范围。

| 场景 | 原稿定位 | 当前代码与拟落点 | 缺项 |
|---|---|---|---|
| 个人中心成为商家入口 | C `126:675`；文案 `127:1407`、副文案 `127:1409`、图标 `127:1400`；`C/handoff/pages/126-675/` | 现有 `consumer/pages/shell/index.tsx` 是工程壳，非完整个人中心；不能将其称为原稿我的页 | 真实入口/currentSession.merchantEntry 接入与个人中心产品页范围 |
| 申请草稿表单 | [C 132:862](https://www.figma.com/design/bp2vpcjjA5vZbHvtKkA8wl?node-id=132-862)，成为商家-填写，402×1124 | 拟 `consumer/pages/merchant-application/index`；复用 `consumer/components/page-layout`、`consumer/components/navigation` 的单一导航机制及 `shared/consumer-api.ts` 会话/请求能力 | 保存草稿/恢复/提交中/字段错误/已上传/材料失败/医院许可证/地图权限状态未在该稿证明 |
| 商家类型展开 | [C 132:1170](https://www.figma.com/design/bp2vpcjjA5vZbHvtKkA8wl?node-id=132-1170)，402×1124 | 同申请页内选择控件，不另造业务页 | 原稿六选项与已批字典冲突，见下表；键盘、遮挡和收起状态需验证 |
| 审核中只读、驳回/补正修改、审核通过转签约 | 本次查阅C handoff页面目录、M page-catalog/handoff目录未定位相应专用节点 | 拟同申请模块的状态页/组件；后端四态和latestDecision驱动 | 缺原稿节点、状态图、意见布局、重提入口、通知跳转状态；不得拿售后申诉“审核中”稿替代 |
| 主账号电子协议读取/勾选同意/已签凭证 | 本次历史目录未定位签约专用节点；M `25:20684`为店铺信息，不能代用 | 拟 `merchant/pages/agreement/index`；当前merchant只有 `pages/workspace/index` 工程样例 | 缺阅读页、版本与同意状态设计；不能把“签约中/失败”设计成业务状态 |
| 运营申请列表、详情、领取/释放、人工核验、决定 | 尚无运营Figma来源，C/M稿不作为运营稿 | 当前 `frontend-admin/src/main.tsx` 生产仅“运营服务尚未接入”；`FixtureApp.tsx`仅内部演示；拟生产业务路由由Admin Owner交接 | 登录/真实权限接入、脱敏及受控原件交互未完成；WAVE_2_PLAN明确不强制运营Figma，可按既有工程一致风格实现，不声称像素级原稿还原 |

现有 `ConsumerPageLayout` 持有唯一底部导航和键盘隐藏逻辑；`consumer/components/navigation/model.ts` 目前只注册profileEdit/petList/petDetail/petForm，添加申请页需C-End修改。`src/app.config.ts` 仅已有工程、资料及宠物页面和merchant workspace；不能只新建页面文件就称路由已通。shared/根配置仅由获分配唯一Owner修改。

## 申请字段精确映射

| 已批准/候选契约字段 | 132:862节点（标题/输入或动作） | 132:1170对应节点 | 前置约束 |
|---|---|---|---|
| merchantName | `132:1038 / 1040` | `132:1207 / 1209` | 提交2–50字，批准时与核验主体一致 |
| contactName / contactPhone | `132:1047 / 1049`；`132:1054 / 1056` | `132:1216 / 1218`；`132:1223 / 1225` | 中文姓名2–20、11位手机号；不能自动成为公开门店电话 |
| email | `132:1062 / 1064` | `132:1231 / 1233` | 可选；原稿“用于接收审核结果”不证明邮箱通知通道已实现；强制站内消息仍独立交付 |
| merchantTypeCode | `132:1071 / 1073` | `132:1240 / 1242`；选项 `132:1381–1385,1387` | 原稿宠物店/宠物医院/园艺/异宠工作室/宠物美容/其他；契约为宠物生活馆/宠物医院/宠物美容院/宠物寄养中心/宠物训练机构/其他；按产品字典，登记文案/宽度差异 |
| cityCode | `132:1079 / 1081` | `132:1248 / 1250` | 真实已开通城市字典；不能提交自由文本“上海”作为code |
| address / longitude / latitude | `132:1087 / 1090 / 1097` | `132:1256 / 1259 / 1266` | 地图选择、拒绝/取消/失败；实际平台适配和合理性验证待接 |
| introduction | `132:1102 / 1104` | `132:1271 / 1273` | 0–500字 |
| storePhotoAssetIds | `132:1110 / 1113 / 1119` | `132:1279 / 1282 / 1288` | 必填1–6张；原稿未标必填需要记录差异；上传成功必须是真实资产ID |
| businessLicenseAssetId | `132:1124 / 1127 / 1133` | `132:1293 / 1296 / 1302` | 私有原件，无公开URL |
| idCardFrontAssetId / idCardBackAssetId | `132:1140 / 1144 / 1150 / 1153 / 1159` | `132:1309 / 1313 / 1319 / 1322 / 1328` | 私有正反面；不保存明文证件号到一般缓存/日志 |
| industryLicenseAssetId | 无对应节点 | 无对应节点 | 医院类必需，属于缺失设计，不能悄悄省略或自创布局 |
| submit | `132:1163` | `132:1332` | 保存与提交不同命令；REVIEWING只读，REJECTED新revision重提 |

素材路径为 `C/handoff/pages/132-862/{README.md,spec.json,assets.json}` 和 `132-1170`同名文件。两个assets.json各列12项，本轮逐项验证24个引用的文件存在且SHA256匹配，零缺失/不符。元素实际PNG在 `C/handoff/assets/`，文件名、sha256、倍率2×、sourceBounds、bitmapPlacementBounds全部逐项存在清单，不以assetKey冒充文件哈希。返回/下拉/定位/门店上传/执照上传/身份证正反图标节点分别为`132:885/1075/1093/1113/1127/1144/1153`；底栏5项为`132:983/991/1003/1008/1016`。不复制整套素材入库，只在代码获批后按实际引用派生。

字体原稿有Roboto、Inter、Outfit；现有profile字体仅有Roboto/Noto子集，不能认定现有子集包含新文案或自动替代Inter/Outfit。精确字号、行高、位置、颜色、圆角和布局在spec.json；402px映射系数750/402不是截图验收结果。

## 必要接口与依赖

| 能力 | 需要的契约/接口 | 当前前端准备事实 |
|---|---|---|
| 本人申请 | CCR候选 GET `/api/v1/c/merchant-applications/current`；POST集合；PUT `/{id}/draft`；POST `/{id}/submit` | ConsumerApi已有真实会话、epoch隔离及request/幂等写基础；尚无申请repository/DTO解码器 |
| 材料 | 私有上传、归属/hash/status读取、短期受控读取；OCR/人工核验事实 | shared/platform.upload只是Taro底层调用，不是私有资产协议；接口路径尚未冻结不得猜造 |
| 审核 | CCR候选 GET admin列表/详情，POST claim/release/manual-verification/decision | createWebClient可复用transport/epoch边界；真实ADMIN_WEB登录/动作/scope及幂等意图保存仍需实现；fixture权限不授权 |
| 协议 | 27号 GET `/api/v1/merchant/agreement?merchantId=…`；POST `/api/v1/merchant/agreement/consent` | ConsumerApi仅允许`/api/v1/c/`路径，不能直接拿它调用merchant；需获权共享会话运输适配，不能暴露token给页面 |
| 准入 | 当前session.merchantEntry、真实membership/admission及四态 | `merchant/admission.ts` realAdmission直接抛错，workspace页面明确fixture；其状态不能转换为真实APPROVED或SIGNED |
| 结果通知 | MerchantApplicationReviewedEvent及notification真实站内持久化、受控进入申请/签约 | 页面轮询不能替代必需通知，消息顶级页面也尚未接入 |

申请命令带稳定UUID requestId及expectedVersion/revisionId；审核另带expectedTaskVersion、submissionRevisionId，决定确认不引入第二审批人或MFA。409不盲目改key重试，先重读版本并重新形成用户意图；未知提交结果保留原key查证。401/403/404/409/503各自显示有效失败状态，不降级mock。所有ID为String，主体重复只显示通用冲突，内部备注永不回申请人。

协议仅真实OWNER可读取/同意。未签者必须能到签约页，不以ALLOWED工作台作为签署前提；读取纯文本及版本/hash后明确勾选，未勾选禁止写；409版本/hash变更重新读取确认，SIGNED以服务器记录为准，不自动重签已签商家。

## 下一步与验收门禁

1. 安装连接指定Figma插件后核对两个已知节点和历史版本差异；定位C/M缺失的审核结果/签约状态稿。已批准六类业务字典直接实施并登记VIS文案/布局适配，不重复询问产品取舍。C/M设计缺口要具体处理，不能自创“已获批原稿”；运营没有另一个Figma不构成全面实现阻塞，按既有工程一致风格推进。
2. 父任务交接最终HTTP/DTO、私有资产、城市字典/地图、Admin会话权限及通知真实依赖；记录端点能力状态。分配C-End/shared根配置、Merchant、Admin的独占写范围后再实施。
3. 在获批页面范围内完成申请/状态/签约/审核代码及真实接口接入。若依赖未交付，明确失败关闭；不让fixture成为真实业务数据。
4. 执行小程序 `npm test`、`npm run typecheck`、`npm run build:weapp`、`npm run check:package`；运营 `npm run build`、`npm test`、`npm run check:boundaries`。再验证MINI-002/003/004/006、WEB-002及VIS-001–004；固定402参考尺寸/像素比/基础库/滚动状态，真实微信截图叠图，Web用Playwright。具体业务链须覆盖草稿续填、只读审核、补正重提、OCR待人工、并发领取冲突、拒权、版本冲突、签约换版、消息跳转与旧响应隔离。

本轮仅完成文件读取、节点/字段盘点及素材引用哈希检查；没有运行构建、业务测试、微信真机或视觉验收，没有将既有历史证据当成本轮通过。仍缺Figma插件在线核验、多个必要状态设计、最终接口交接和真实业务接入，不能声称MER-001前端或完整闭环达到DoD。
