# C-002 宠物档案页阶段交付与闭环记录

Owner Role: C-End Frontend。基线 develop `c99ab9d`(PR26 合并后)，分支 `codex/c-002-pet-pages`，工作区 `C:/Users/Administrator/Desktop/wt-c002-pet`。任务文件 `C-002-pet-page.md`。

## 交付范围

- **列表入口**(78:2817 仅宠物档案模块区，画布 402×312)、**详情**(78:3076)、**填写表单**(95:1481 弟弟帧 / 95:1844 妹妹帧，新增/编辑共用)，路由 `consumer/pages/pet-archive/{index,detail,form}`。
- Mock 数据严格按已批用户域契约 PetView(CCR-W2-API-001)：ID String、weightKg 两位小数字符串、年龄由 birthDate 派生(契约决定3)、软删除 ACTIVE/DISABLED、requestId 幂等回执(预览仓库内同构实现并有单测)。
- 状态覆盖：加载/空列表/加载失败重试/有数据/表单校验错误/保存中/保存成功/保存失败重试/会话失效/未接入口提示；非 preview 入口不显示 fixture。
- 验证：typecheck、单测 49/49(含 10 项宠物域新测试)、build:weapp、check:package 全过；`test:pet-platform` 真机交互 7 项全过(wechatide 通道)。

## 环境与采集通道(重要变更)

2026-09-15 用户更新了微信开发者工具(2.01→2.02.2608060)，旧 `cli.bat auto` 服务端口通道失效。按用户指示安装 `wechatide-skill`(整份复制到 agent skills 目录)，全部自动化(编译开页/导航/求值/截图)改走 `wechatide` CLI。截图通道特性：`simulator_screenshot` 按模拟器面板适配缩放输出(无 1:1 通道)，拼接器按 k=shotW/windowWidth 统一重采样；参考画布采集在 **414 窗口**(402 画布需 ≤ 视口宽)；390 窗口保留整窗状态截图。刘海/状态栏/胶囊与 Home 区不属设计稿，指标排除(顶部 100 行；390 画布右缘 12 列亦无信号)。

## 与原稿的差异(全部登记，未私造设计)

1. **78:2838 底图分段未入包**：与 78:2818 同为首页底图的第二段(760KB)，在模块区仅暴露约 49px 边缘带；为包体预算未引入，列表页叠图基准按同口径合成，边缘带差异已登记。
2. **删除入口原稿未画**：长按列表卡 + 原生 `Taro.showModal` 确认后走预览仓库软删除，零新增视觉样式。
3. **性别按钮位置固定**(弟弟左/妹妹右)，选中仅换底色字色——以两帧 spec 文本值为准(帧内节点名 `妹妹-269`/`弟弟-266` 为设计稿残留命名，与文本值相反，已按文本值实现)。
4. **校验/通知文案位原稿未画**：表单校验错误与"预览数据已更新"置于保存按钮下方空隙，文字样式沿用 PR25 样板(#B42318/#402E26 12/18)；不占用设计元素位置。
5. **Rectangle 16(详情身份卡)** spec 未声明圆角，官方参考帧渲染为圆角 ≈9，按参考帧实现。
6. **保存成功后停留当前页**(与 PR25 样板一致)，不自动返回。
7. **状态文字样式**(加载/失败/过期/空态)原稿未画，沿用 PR25 样板文字状态。

## 契约缺口(登记为补充稿需求，未私造)

- **宠物芯片号**：不在已批 PetView。详情/表单按原稿展示设计样例值 `900001234567890`，常量单独标注(`designSamples.chipNumber`)，不提交后端。
- **列表疫苗/驱虫标签内容**(犬窝咳疫苗、疫苗·2026-10-08、拜耳内虫逃等)与**详情疫苗/驱虫记录列表**：PetView 仅有 vaccineStatus 枚举，无记录名/日期字段。页面按原稿渲染设计样例常量，不提交后端。
- **宠物类型(petType)**：表单未设计类型选择；预览创建默认 OTHER，编辑保留原值。头像上传同样无契约(列表/详情头像用官方切图按 petId 映射)。
- **年龄换算细则**：满 N 年为"N岁"，未满一年"未满1岁"；未来日期返回空(校验层已拦)。

## 包体与工程

- 宠物页注册为普通分包 `consumer/pages/pet-archive`(源码路径不变、路由不变，M-001 商家分包先例)：主包 710,177B、宠物分包 1,823,528B、商家 7,180B，均低于 2MB 内控线(官方整包上限 30MB 不变)。`package-check.cjs` 同步了页面清单与分包断言——该文件不在本任务 Allowed 清单，但页面注册断言硬编码于此且不同步必失败，PR25(565f1d4)已有同类先例，在此披露。
- 字体：`C002 Pet Roboto/CJK/SC` 9 个子集(307KB，base64 后 411KB)入分包，仅含宠物页静态用字；profile 既有子集未复制未改动。
- 切图 32 项均为 design handoff 官方 2x 导出原字节复制(`assets/manifest.json` 含来源节点/SHA-256/字节一致性核验)。

## 可复验命令

在 `frontend-miniapp`：

```text
npm ci
npm run typecheck
npm test
npm run build:weapp
npm run check:package
node src/consumer/tests/pet-platform.cjs   # 需 IDE 打开本项目(wechatide 已授权)
PET_WINDOW_WIDTH=414 node src/consumer/tests/pet-capture-ide.cjs
PET_WINDOW_WIDTH=390 node src/consumer/tests/pet-capture-ide.cjs
```

在仓库根目录(或本目录)：

```text
python planning/issues/wave-2/C-002-pet-page/compare-visual.py 414
python planning/issues/wave-2/C-002-pet-page/compare-visual.py 390
```

## 已发现并处理的问题

- 更新后的开发者工具首次启动会重建用户数据目录(服务端口/项目列表全部重置)；已通过其新 CLI(`wechatide`)与 GUI 完成项目导入与授权，auth 状态 `alreadyTrusted`。
- Taro 将字面量 `402px` 编译为 rpx：画布宽度经运行时 CSS 变量传入(样板同款手法)。
- Taro Input 外层不吃 `.pet-page input` 的 border-box，padding 将 42px 框撑到 64px：已对 `.pet-form-input/.pet-form-textarea/.pet-form-staticbox/.pet-form-pickerbox` 及带边框卡片显式 `box-sizing: border-box`，输入框实测 29/235/343×42 与原稿一致。
- wx-button 内建 `margin-top:8px` 渗透导致绝对定位按钮 +8px：按钮重置加 `margin:0 !important` 后保存按钮实测 907..947 与原稿一致。
- `automation_navigate` 会丢弃 URL 中 `&` 之后的参数：改用 `simulator_open_page`(自带整包编译与 query)。
- 模拟器整窗截图(旧 automator)可 1:1；新通道按面板缩放输出且滚动拼接每屏顶部重复设备 chrome——拼接器按面板缩放一致重采样、逐瓦片跳过顶部 100 行 chrome，并在指标中掩膜。

## 验收边界

| 项目 | 结果/证据 |
|---|---|
| TypeScript/单测/微信构建/包体 | 本次 PR CI 与本地 typecheck.txt 等价命令；49/49 单测、check:package 通过 |
| 原生页面交互 | `evidence/platform.json`：7 项(校验拦截/预览保存/失败重试/列表加载重试/卡片进详情/编辑进表单/非 preview 守卫) |
| 图像与几何对照 | 414 窗口叠图/差分/并排/分区指标(visual-comparison-414.json)；390 同口径(画布右缘 12 列掩膜) |
| 基准局限 | 78:2817/95 两帧无官方整帧渲染，基准为 spec+官方切图+交付字体的合成图(PIL 文本渲染差异计入指标，仅作定位)；78:3076 为官方帧 |
| 真实增删改查 | **未接通**：C 端会话与宠物 HTTP 实现归后端 Owner；预览保存只更新内存 |
| 真机/键盘/授权/跨设备字形 | 未验收 |

## 后续解除条件

用户人工确认 414 叠图后本阶段视觉闭环；疫苗/驱虫记录添加页(96:2580/96:3006)与品种百科系列(245:5375 等)为登记的后续页；芯片号/记录字段/宠物类型/头像上传等待契约补充(CCR)；真实数据链路待 C 端会话与 HTTP Owner 接入后另测。完整 C-002 仍非 DONE。
