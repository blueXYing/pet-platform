# frontend-miniapp

唯一微信小程序工程，技术方案已由ACR-001批准：Taro + React + TypeScript。用户和商家共用一个AppID与构建入口，切换身份；无本期用户Web或App。

当前仅建立目录说明，尚无脚手架或业务代码；开发须等待Wave 1批准。

- C-001：根配置、config/**、src/app*、src/consumer/**、src/shared/**，由C-End维护。
- M-001：src/merchant/**商家工作区，依赖C-001，不创建独立应用壳。共享文件由C-End作为唯一编辑者协作。
- 页面使用Figma原始切图一比一实现；具体验收见docs/02-architecture/20-前端技术基线-v0.7.md及docs/07-testing/21-前端与小程序验收补充-v0.1.md。
- 会话公共契约缺口按planning/ccr/CCR-ACR-001.md处理；内部fixture不是服务端协议。
