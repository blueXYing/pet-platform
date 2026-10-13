# mod-bepro — 成为服务者入口模块（32号裁决 §2 七页批次）

分支 `codex/mod-bepro-20261013`（基于 develop 6263f17）。交付：C 端「成为服务者」落地页 +
服务类型选择页（`frontend-miniapp/src/consumer/pages/become-provider/`，index + service-type 两页），
入口自「我的」更多服务格位（#150 原视觉占位转真实入口，id `mine-become-provider`）。

## 设计源与提取

- 结构：`D:/Temp/figma-mcp/user-end-page1.raw`（2026-10-10 版全量 SSE 缓存）提取
  `129:14566`（我的-成为服务者）与 `129:15547`（我的-成为服务者-服务类型选择）帧 XML
  （零 API 配额；产物 `art-129-bepro.xml` / `art-129-betype.xml`）。
- 渲染：REST `GET /v1/images/{key}?ids=129:14566,129:15547&scale=2`（dev token，
  `refs/bepro-render-url.json` + 两张 PNG）。
- 样式：REST `GET /v1/files/{key}/nodes?ids=129:14883,129:15969`（面板与下拉节点树
  fills/字阶/圆角，`refs/bepro-nodes.json`）。本模块共消耗 2 次 REST 调用。

## 关键实测（frame px，--bep-unit = windowWidth/402）

面板：白底 r17.155 顶角、内容边距 21.5；标题 17.146/700 #1d0d07 + 关闭钮 34.29 方
（X #3c3c3c）；简介 12.86/400 #3c3c3c lh20.9；说明行 #f0fbff r8.573 胶囊 pad12.86 +
info 图标 #5baae8 + 文字 #5baae8；输入件 #f0fbff + #c0ecff 1.07 描边 r8.573（SELECT
高 42.87、字 15.003 #1d0d07、箭头 8.573x4.287 #000000）；下拉（129:15547 Group 26）
白底 #c0ecff 1.19 描边、行高 32.23、字 15.516 #1d0d07、左距 15.516；主按钮 #5baae8
全圆角 47.15 高白字 15/500。官方色 #5baae8 / #c0ecff / #f0fbff 全程采用。

## 与既有入驻流程的接法

- 既有链路：`consumer/pages/merchant-application/index`（申请表单：商家名称/联系人/
  电话/邮箱/商家类型/城市/店铺位置/简介/门店照片/营业执照/身份证/医院行业许可证，
  current→save→submit 幂等恢复链齐全）+ `signing?merchantId=...`（APPROVED 后签署）。
  后端 `/api/v1/c/merchant-applications*`，本模块零新增接口、零写操作。
- 接法：落地页选服务类型 →「进入入驻申请」以 **merchantTypeCode**（既有 `DraftInput`
  字段）URL 参数进入申请页；申请页仅在新空草稿时采用该初始值（已有申请——含
  DRAFT/REJECTED——一律以服务端草稿为准，参数不覆盖数据）。选项集复用
  `merchantTypeOptions` import（单一字典来源，不复制）。

## 偏差登记

见登记表 §4.16（五条）：流程形态（原稿自含跑腿表单+第二身份 → 契约优先，表单移交既有
申请页）、文案对齐（说明行/主按钮）、选项集（原稿占位 → 契约六项）、独立路由页形态、
服务类型选择独立成页（原稿为面板内浮层下拉）。

## 验证

- `npm ci` → exit 0；`npx tsc --noEmit` → exit 0。
- `npm run build:weapp` → 成功（见 PR 描述数字）。
- `npm test` → 全部通过（含新增 `consumer/tests/become-provider.test.ts` 7 例）。
- `npm run check:package` → exit 0（期望清单已同步 become-provider 子包两页）。
