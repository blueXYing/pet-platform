# MER-001 S5 前端接口切片

日期：2026-09-17。工作区：C:/Users/Administrator/Desktop/wt-mer001-runtime。

## 本轮可审阅实现

- `frontend-miniapp/src/shared/merchant-repositories.ts`：独立 MerchantApplicationRepository（本人读取、创建、全量替换草稿、提交）及 MerchantAgreementRepository（协议读取、明确同意）。严格按 OpenAPI11 AppOwnerDetail / AppResult / MerAgreementView / MerConsentView 解码；不适配后端内部 records 的不同形状。
- 验证 String ID/版本上限、四态、申请编号、revision 关联、决定/时间、六类字典、坐标 Decimal String、资产引用、协议版本/hash/UTF-8文本上限、签署证据；拒绝未知字段（含敏感内部字段）与错误 merchant/application 回执，不把失败作为成功。
- ConsumerApi 复用已由 GET session 验证的 MINIAPP Bearer，不向页面暴露 token。merchant 仅允许 GET `/api/v1/merchant/agreement` 和 POST `/api/v1/merchant/agreement/consent`；要求当前 merchant 工作区、真实 session user 与 scope 一致、请求 merchantId 与工作区一致。不新增通用 merchant 路由入口；OWNER 仍由后端权威鉴权，不把前端坐标当权限。
- 写入复用持久化 requestId 意图、显式重试、在途合并及 epoch 旧响应隔离；发起时快照 payload，防止 await UUID 期间调用方修改。401/403等确定拒绝与409/503/未知结果分别处理；409不生成新key盲试。未确定的意图保留，重启同身份可重放；不存在自动清除409再提交新版本的流程。
- 新增13项回归测试：严格解码、私有字段拒绝、原key重放/重启恢复、草稿变更隔离、协议明确勾选、工作区/身份/路径边界、迟到响应、403/409/503、错商家回执。

## 本轮验证

| 命令 | 结果 |
|---|---|
| npm ci --ignore-scripts | 成功安装锁定依赖；未修改 package/lock |
| npm test | 77/77通过，包含本轮13项及既有64项 |
| npm run typecheck | 通过 |
| npm run build:weapp | 通过；既有大资源Webpack建议警告仍存在 |
| npm run check:package | 通过；main 742704 bytes，merchant 7180，pet-archive 1814753，总计2564637 |
| git diff --check -- frontend-miniapp | 通过 |

执行了 `backend/tools/run-frontend-gate.py` 的 miniapp-build 所要求的四个检查命令；未重复运行会重新安装/重新构建的包装脚本。包体输出为静态产物统计，不代表微信上传/设备/视觉验收。Admin无改动，未运行其构建或浏览器测试。

## 保留门禁与风险

新增仓储仅由测试import，未注册生产页面/路由、未部署API，不能声称真实申请审核签约闭环已通。测试transport是受控响应，不是HTTP联调；后端内部record到精确wire DTO适配/真实HTTP授权仍待主任务交付。

Figma插件本轮仍不可调用，未在线读取，不声称使用最新设计；沿用S4缺口清单，不自造C/M申请状态或签约页面。私有上传/受控读取、真实城市/地图、Admin业务UI与登录、审核通知落地/跳转、微信原稿截图验收仍待交接。本切片没有猜造私有上传端点，也没有修改 project.config.json、生产页面、提交或推送。
