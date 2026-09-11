# A-001 运营网页工程壳

React + TypeScript + Vite + React Router。仅工程概览、权限示例、独立 Web 请求层及私有 fixture；不含 A-002～005 业务页、真实登录或 Node 业务后端。

## 运行与验证

Windows、Node 22.23.1、npm 10.9.8；准确依赖已锁定在 package.json / package-lock.json。

```powershell
cd frontend-admin
npm ci
npx playwright install chromium
npm run dev
```

开发服务仅监听 127.0.0.1。示例身份保存在内存，刷新恢复未登录。

```powershell
npm run build
npm run check:boundaries
npm test
```

测试自行启动 4173 开发服务及 4174 生产预览，必须先 build。浏览器为 Chromium 153.0.8010.12 / revision 1243，1440×900。结果见 evidence/VALIDATION.md。

默认生产 build 显示“运营服务尚未接入”，不包含 fixture。可用 `npm run build:fixture` 生成明确的本地演示包，不得作为真实运营服务发布，之后重新 build 恢复默认产物。History 路由部署需将非 API 路径回退 index.html，不能将 `/api/**` 回退为 HTML。

## 接入约束

- request.ts 使用浏览器 fetch 和既有 `/api/v1/admin/` 分区，Bearer 仅内存持有，写请求生成 X-Request-Id。调用方重试同一写操作必须保留并传入原 UUID；适配器不自动重试。
- ID/金额字符串及服务端 displayStatus/actions 原样传递，不实现交易展示状态机。真实业务 DTO/schema 校验由后续批准的接口接入提供。
- 退出、换账号、数据范围变化或撤权时调用 resetContext，使旧响应失效；401/403清除请求层令牌并使同期请求失效。真实会话/UI失效绑定等待会话契约。
- fixture.ts 的动作、会话、角色、路径均是内部示例，不得作为公共 SDK、权限码或生产 DTO。私有路径仅供注入的内存 Transport 使用，不注册或请求真实后端。
- 菜单/路由/按钮共用允许与拒绝条件，模拟 Transport 再检查动作。生产由 Java 校验权限及数据范围；隐藏菜单不能代替鉴权。
- 普通及财务账号按显式 grant 检查，不按中文角色名授权，未新增财务功能。超管仅允许已知示例动作，未知动作拒绝；生产全部已批准动作及全平台范围映射由 AUTH-001 提供。
- 单个获权账号直接执行示例动作，无第二人及内部审批队列；没有实现发布内容、退款、审计、脱敏或资金业务。

依据：SSOT §24、运营原始PRD及22号补充、20号技术基线、21号测试补充、HTTP Contract §1～2、CCR-PERM-001/CCR-ACR-001。启动使用 EX-W1-001 固定资料 e8654c30b4dfe2cfc9e3c050110322d2a18bb6a1，不假定 GOV-001 已完成。

真实权限、登录/MFA、数据范围、审计、业务 E2E、视觉稿还原及集成 CI 尚未验收。PR 须 blueXYing 人工审核，壳通过不代表生产就绪。
