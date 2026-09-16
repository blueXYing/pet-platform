# PR34 接手修复记录

日期：2026-09-16。原 PR head：771e7e39105dd368423ccb28f157c41dcce61a4f。工作区：`C:/Users/Administrator/Desktop/wt-c002-pet`，分支：`codex/c-002-pet-pages`。

本记录覆盖旧 HANDOFF 的“完成四步闭环”和“7 项交互已足以验证”表述。历史截图不自动代表当前实现，也不代表用户已接受。

## 核验结论

姓名、品种、日期、体重、年龄、备注原本已是 Text/Input/Textarea，并非整页切图。实际主要缺陷是：

- 公共定位、按钮重置和字体样式仅放在 list.css，详情/表单深链没有引入，实际会错位或被背景遮挡。
- 底栏五个按钮没有列位置；选中妹妹时，弟弟失去定位类，两项重叠。
- 每个页面单独创建数据仓库；表单保存未同步列表/详情，新增后重复保存会生成多只宠物。
- 详情固定显示豆豆的头像、芯片及健康记录，忽略当前宠物和已有 avatarUrl。
- 列表固定为两行高度，姓名/性别标签固定坐标，健康备注固定高度，不适应可变数据。
- 原采集脚本没有重置滚动、未读取真实滚动坐标，并将 414 视口整体压成 402 画布，叠图失真。

## 修复

所有入口引入本分包公共样式；底栏按五列定位并采用源节点 `203:16/203:19` 的底色/圆角；性别选项保持独立位置，原生输入框去除导致文字下偏的纵向 padding。列表随数据条数增高，姓名限制在身份栏内，长备注使用文档流撑开纸张与页面。

预览仓库按 WorkspaceScope 及 revision 共享并隔离，各场景独立；列表/详情返回时重新读取；创建后保存复用新 petId。头像优先消费 avatarUrl，设计样例按宠物区分；缺口值不再借用其他宠物数据。日期验证拒绝不存在的日期。

契约缺口已提交本目录 CCR-C002-PET-DISPLAY-001.md，仍为 PROPOSED；本 PR 没有新增公共接口或后端能力。

## 设计来源及证据边界

用户要求使用 Figma，已加载设计转代码技能并搜索工具，但本会话始终没有 Figma MCP 读取工具；用户表示重新连接后仍未获得工具。未声称在线读取成功。本次使用既有官方整帧 `78-3076@1x.png`、节点规格和原始素材（版本 `2397539525915641008`）。表单/列表基准为历史规格合成图，不是官方整帧，含图标占位/文本渲染偏差；不得以该差异反向替换官方图标。

`evidence/before-takeover.png` 是独立打开详情时复现的错误。`evidence/takeover/` 是修正采集流程后的对照目录。414 证据采于主体修复后、最终输入框/底栏颜色微调之前；390 证据用于最终代码验证。原生刘海/状态栏、胶囊、Home 指示条不属于设计内容；390 的 402 参考画布右侧 12px 不可见，正常页面按 390 实际宽度布局，另有整窗截图。

当前仍有待人工确认的字体渲染和平台区域差异，不能标记 VIS 已接受或完整 C-002 DONE。真实 HTTP/数据库、头像上传、真机键盘/授权、疫苗/驱虫添加及宠物类型编辑不在本轮完成范围。

## 验证入口

在 frontend-miniapp 执行 `npm run typecheck`、`npm test`、`npm run build:weapp`、`npm run check:package`、`node src/consumer/tests/pet-platform.cjs`。在根目录执行 `python backend/tools/check-module-deps.py`、`python backend/tools/check-display-status.py`。

采集使用 `PET_WINDOW_WIDTH` 指定并核验实际窗口宽度，`PET_EVIDENCE_DIR` 指向 `evidence/takeover`；`PET_CAPTURE_MODE=reference/device` 可分开采集。使用同一证据目录运行 `compare-visual.py 390` 或 `414`。最终结果见 `evidence/takeover-validation.json`。
