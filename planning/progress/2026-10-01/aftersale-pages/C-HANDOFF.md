# C-005 售后页面切片交接

2026-10-01；基线 develop / PR #98 `e3b846e`；分支 `codex/aftersale-c-ui-20261001`。本切片允许用户页面与其纯业务模型，不修改后端、Contract、Schema、公共壳/路由或生产开关。SSOT §40、PRD31、Contract51/OpenAPI11、前端20/验收21已读取。

## 可审阅交付

- 本人售后分页列表，服务端状态筛选、前后分页、空态及失败态；不派生 DisplayOrderStatus。
- 详情展示真实说明、当前补证轮次/期限、双方证据、终态/终裁原因及已有历史结论引用。
- 追加问题/补证使用服务端版本与当前补证轮次；终局前撤回后重读，不刷新七天期限。
- 创建视图按真实订单 ID读取资格；订单资格不代表 ACTIVE 写权限。目录选项使用内部可注入 catalog port/Picker，生产 port 当前返回 null，禁止新建提交。没有猜测或硬编码原因/诉求目录、订单封面/服务名称/金额。
- JSON写请求交共享 AfterSaleClient 的持久 UUID journal；结果丢失锁定原输入，显式重试保持原参数与 requestId，页面重挂可恢复 pending。明确 CAS/补证轮次409 retire后重读；幂等冲突/处理中不换 key。
- 图片预上传以本人+创建/补证表单范围持久化保存副本、SHA-256和UUID；超时原文件重试，收到回执并落草稿后删除应用保存副本，保留原选图。预上传与图片读取不暴露令牌/裸对象URL。
- 证据每次重新签发并消费当前本人 grant，使用页面内私有Image预览层。关闭、页面隐藏、卸载及身份切换立即清预览UI与应用自有文件；epoch阻止迟到图片返回恢复界面，不使用微信原生图库缓存。身份切换清空页面数据、输入及旧在途响应；403写失败保持只读。

## 根协调接线

注册 `consumer/pages/aftersale/index`、`consumer/pages/aftersale/detail`、`consumer/pages/aftersale/apply` 三路由。

用户售后入口指向 index；订单入口有真实 `orderId` 后指向 `/consumer/pages/aftersale/apply?orderId=...`；详情为 `/consumer/pages/aftersale/detail?afterSaleId=...`。当前列表也接受本人真实订单 ID以查询资格；服务器仍检查订单所有权。根协调负责公共导航、app.config和相关平台取证。

共享实现已取 `fbdea59`、`b8ac7e1`（本分支 cherry-pick 为 `1ad1bed`、`5fe642a`），根集成只取本代理业务提交，避免重复共享提交。

## Figma来源与视觉边界

已实际使用用户指定 Figma 插件与 `figma-design-to-code` 技能，`get_design_context(fileKey=bp2vpcjjA5vZbHvtKkA8wl,nodeId=129:10572,skillNames=figma-design-to-code)`初次认证失败，用户重新连接后读取完整高保真代码和截图成功，无 sparse。另取截图尺寸402×1141。登记表版本2401180846436413285仅作为来源登记版本，本次 MCP 响应未提供新的版本号。

本地参考图（不上传 GitHub、不用作实现资产）：`C:/Users/Administrator/.zcode/aftersale-pages-20261001/c-129-10572-reference.png`。可交互控件由 Taro/React实现，SCSS采用原稿#f0fbff背景、#fff6e5标题栏、#c0ecff卡片描边、13.154px圆角、17.539px间距及原始back/add SVG。字体复用已存在Roboto/Noto CJK资源，Outfit Bold标题字体来源未补齐。

静态素材来源、字节、哈希、原始尺寸/层节点/调用处见 `frontend-miniapp/src/consumer/pages/aftersale/assets/manifest.json`。无临时 Figma URL留在代码；原稿业务订单封面保持动态来源缺口，不用示例照片/硬编码服务资料替代。

已披露差异：原稿“仅退款/退货退款”、描述选填、3张/5MB+WEBP/GIF及首页/订单/核销底栏与本批 SSOT/契约/统一 C壳存在冲突；本批执行最新契约10..500说明、6张JPEG/PNG≤10MiB及既有 C导航。新问题说明、真实资格/非退款流程及列表/详情状态无对应已核原稿。未声称一比一还原或 VIS-001～004完成。需根记录原稿业务裁剪/补稿及字体缺口；原始SVG实际微信渲染几何仍需平台截图确认。

## 已执行验证与未完成前置

- 定向 `node node_modules/tsx/dist/cli.mjs --test src/consumer/tests/aftersale*.test.ts`：21/21通过，涵盖精度/字段校验、目录缺失/旧选项拒绝、资格/单活动工单、原payload重试/重挂UUID恢复、并发点击、补证当前轮、CAS重读、幂等冲突锁定、冻结只读、终态动作拒绝、身份切换/旧响应/写入期间登出、图片上传恢复/取消/指纹变化/隔离/确定拒绝清理及恢复UUID/hash/owned路径的严格校验。
- `npm run typecheck`：通过。
- `git diff --check`：通过。
- 本分支不单独改公共路由构建；最终微信目标构建、包体/架构/全集测试由根串行集成执行。真实微信上传/证据预览、三端后端闭环、真机、截图叠图与视觉验收尚未执行。
- 公开权威问题类型/诉求目录读取契约缺失，由根 CCR承接；当前生产创建页失败关闭，C-005完整申请链路不能标记已完成。
- 原订单列表/详情的正式真实order入口不在本次代理修改范围。公开FULL/PARTIAL终裁、资金执行/到账不开放；无生产启用/迁移/PR合并授权。

后续预览清理补丁已同步根协调对COMMON_CONFLICT忙态保留原请求及UUID/hash严锚的修复；同步提交仅供本代理验证，根不重复集成。根已提供真实weapp截图149×321并观察到C返回SVG可显示，该截图不等于全页面VIS验收。
