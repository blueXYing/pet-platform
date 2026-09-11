# C-001 公共壳交接（两个真实窗口已验证，待根Work完成审核）

最新完整结论见 src/shared/evidence/FINAL-ACCEPTANCE.md：真实开发工具/SDK3.17.2在390×753与414×672两个实际窗口通过，第二窗口安全区原生测量通过，独立截图未覆盖首窗口。未引用产品原稿的工程示例无产品视觉交付，原始素材验收留给对应业务页Issue。C-001完成标记和M-001解锁由根Work审核。

唯一编辑者：C-End Frontend。源资料基线 e8654c30b4dfe2cfc9e3c050110322d2a18bb6a1；按 EX-W1-001 独立推进，不代表 GOV-001 完成。固定代码提交由最终交接消息给出。

## 范围与运行

一个 Taro + React + TypeScript 微信目标，只有 src/app.tsx 一个入口。consumer 两个页面是中性工程示例，不是最终 PRD 四 Tab 导航，也不是 Figma 产品页。局部计数、navigateTo 和受控上下文用来验证壳；没有商家业务实现。

在本目录执行 npm ci、npm run typecheck、npm test、npm run build:weapp、npm run check:package。开发命令 npm run dev:weapp。微信开发者工具导入本目录的 project.config.json，游客 AppID 仅供工程导入，不代表已具备真实账号、合法域名、登录或支付能力；真实 AppID 在获授权环境配置。没有 H5/App 构建命令。

src/shared/evidence 承载实际日志和视觉清单。包体脚本只统计本地构建字节并按已读取的官方2M单包/30M总包（代开发20M）配额采用保守预算，不等于微信上传后的包体/兼容验证。来源快照见 wechat-package-rules.json。

## Merchant 普通分包接入约定

M-001 仍未解锁。待 Work 确认依赖与验收后，Merchant 在自己的工作区基于固定公共壳提交，只编辑 src/merchant/**。禁止第二套 package.json、锁文件、AppID、登录壳或独立小程序。

Merchant 提供相对 src/merchant 的页面列表、页面入口、共享依赖和分包素材计划。C-End 在关联 Issue 中作为唯一编辑者更新 src/app.config.ts，例如后续真实页面存在后登记 `{ root: 'merchant', pages: ['pages/workspace/index'] }`，不设 independent:true。当前没有虚构商家占位文件或无效分包条目。包内相对导航需给完整 `/merchant/...` 路径。主包 consumer/shared 不反向导入 merchant。

根配置、package.json、package-lock.json、config/**、app*、shared/** 和公共素材改动均由 C-End 落地并交接 commit，Merchant 不并发改写。merchant 内素材由 Merchant 管理，公共素材需申请共享变更。

## 上下文和请求

WorkspaceScope 的 userId/workspace/merchantId/storeId 是客户端隔离坐标，不是身份或授权声明。replace 每次推进修订号、清空缓存并通知 React，包括相同坐标重新进入。切换、登出、撤权都应调用 replace；run 在成功与失败返回时检查修订号，旧结果不能入缓存。消费方 UI 也须按 revision 校验后提交，并在 revision 变化时清除本地结果，参照 diagnostics 页面。

当前上下文来自 consumerFixture，未调用登录接口。工程样本不是公共 DTO，无会话 endpoint、SDK 或商家准入服务。真实会话、每次进入商家时查最新准入、401/403 刷新/失效映射仍等待 CCR-ACR-001。工作区选择本身不放行权限。不得用统一禁入抹掉 SSOT 中下线门店存量履约和售后例外。

createClient 注入 Transport、按工作区限定既有 API 分区，写请求必须由调用方提供并复用 X-Request-Id；不做自动写重试。解码器由获准接口的调用方提供。内部解码测试保持 ID/金额字符串、原样传递展示状态/actions，不实现订单状态机。selectTransport('real', ...) 明确阻断未获准接入。

平台层独立封装 Taro.request/navigateTo/login/scanCode/uploadFile/requestPayment。业务页面当前只用导航；真实平台能力、授权取消/失败、支付/扫码等 MINI-006 不在本 Issue 验收范围。

## 视觉与验证门禁

visual-manifest.json 记录 Figma 基线索引及后续节点、原始切图、哈希、派生资源、固定窗口/像素比/基础库截图、叠图和差异确认字段。原始切图未获取，视觉验收明确未通过；没有替代图或整页截图冒充产品页。后续产品页必须原稿 1:1，范围外入口删减需确认。

MINI-001 必须分别有 typecheck、微信目标构建、开发者工具启动及基础库版本。MINI-003 为本地内部上下文基础测试。MINI-004 的本地注入测试不代表真实 API 接入。MINI-005 的字节统计不能代替多窗口、安全区、原图清晰度和实际分包加载。缺项不得声称 C-001 DoD 完成，也不自行解除 M-001。

资料追踪：SSOT → EPIC-20 / ST-FE-C-01 → C-001 AC1–6 → typecheck / MINI-001,003,004,005；20号前端基线、21号验收补充优先于旧前端约定，HTTP/API公共契约没有修改。

构建参考：2026-09-11 查阅 https://docs.taro.zone/docs/config/ 与 https://docs.taro.zone/docs/config-detail/。微信分包官方地址 https://developers.weixin.qq.com/miniprogram/dev/framework/subpackages.html 本次读取失败，现行配额待平台实测确认。
