# 统一微信小程序 · C-001 工程壳

Taro + React + TypeScript，唯一微信入口，无 H5/App 或第二个商家应用。当前只有中性工程示例与内部 fixture；不是产品页面还原或真实登录/权限接入。

Node 22.23.1、npm 10.9.8；运行 `npm ci`、`npm run typecheck`、`npm test`、`npm run build:weapp`、`npm run check:package`。详细版本见 package.json/package-lock.json，证据见 src/shared/evidence。无 lint 脚本。

普通分包、共享文件唯一所有权、CCR-ACR-001 和未通过项见 [HANDOFF.md](HANDOFF.md)。缺少平台证据时不声称 Issue 完成。原始 Figma 切图未获取，产品视觉验收未通过。

- C-001：根配置、config/**、src/app*、src/consumer/**、src/shared/**，由C-End维护。
- M-001：src/merchant/**商家工作区，依赖C-001，不创建独立应用壳。共享文件由C-End作为唯一编辑者协作。
- 页面使用Figma原始切图一比一实现；具体验收见docs/02-architecture/20-前端技术基线-v0.7.md及docs/07-testing/21-前端与小程序验收补充-v0.1.md。
- 会话公共契约缺口按planning/ccr/CCR-ACR-001.md处理；内部fixture不是服务端协议。
