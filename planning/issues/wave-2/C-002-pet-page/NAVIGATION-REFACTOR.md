# C 端底栏统一与分包依赖改造

日期：2026-09-16。关联 C-002 / PR34；在 f7e6428 基础上继续。

## 授权与范围

用户在本任务中明确提出统一底栏、保留分包，并要求按已讨论方案改造。C-End 在原 PR 中承担唯一 Writer，登记工程范围扩展：`frontend-miniapp/src/consumer/components/navigation/**`、`src/consumer/components/page-layout/**`、`src/consumer/assets/navigation/**`、既有 profile-edit 页面及其导航素材引用、pet-archive 页面、consumer 导航测试与 `package-check.cjs`。

这是工程组件和资源归属调整，不新建 CCR；不修改业务规则、接口、Schema/Event、不接入尚未实现的一级导航业务。宠物芯片/记录字段仍走 CCR-C002-PET-DISPLAY-001。

## 约束与方案

- 一个小程序工程/AppID。保留主包页面、宠物普通分包和商家普通分包，不将业务大图/字体迁入主包。
- 底部导航的菜单、原始图标、布局和安全区集中在 C 端轻量公共组件；profile-edit 与宠物列表/详情/表单复用。
- 页面容器负责挂载一次底栏、内容底部留白、键盘弹起隐藏及保存中禁用；页面保留已有离开确认和通知。
- 未实现的首页/服务等入口统一提示，不能把宠物列表冒充首页，也不创建虚假的 switchTab 目标。正式 tabBar 一级路由待对应业务页就绪后接入。
- 列表普通页面挂底栏；其原始 402×312 模块的参考截图独立取内容区，不在模块中嵌入第二个底栏。
- 公共组件不得依赖宠物/商家分包。小图标集中存放；业务纸张、背景、头像和字体保持原分包。
- 新增包体/依赖检查，防止公共导航反向导入业务分包、主包加载宠物底图，或再次出现页面自定义底栏副本。

验证与最终包体、截图见本目录后续验收记录。视觉仍需人工复核，不自行合并 PR。

## 实现与接入方式

`ConsumerPageLayout` 是页面根容器。页面通过 `page` 声明当前栏目，通过可选 `navigation` 开关底栏，并传入保存中禁用、离开确认/通知回调。业务内容作为 children；组件内部统一处理安全区和键盘监听。原始画布截图可显式传 referencePlacement，正常页面始终固定到底部。宠物列表保留原 312px 业务内容区，正常入口现在带底栏。

菜单及栏目归属集中在 `consumer/components/navigation/model.ts`，图标集中在 `consumer/assets/navigation`，样式只有 `consumer/components/navigation/style.css` 一份。profile-edit 与宠物旧底栏 JSX/CSS 和重复图片已移除；图标是原始完整导出字节，来源与哈希见新目录 manifest.json。旧 profile 的证据目录及素材路径属于历史提交，当前路径映射登记在新 manifest 的 previousFile。

## 分包核验

| 输出 | 改造前 f7e6428 | 本轮构建 |
|---|---:|---:|
| 主包 | 710,183 B | 711,734 B |
| 宠物普通分包 | 1,831,721 B | 1,818,265 B |
| 商家普通分包 | 7,180 B | 7,180 B |
| 整包 | 2,549,084 B | 2,537,179 B |

公共模块进入主包 common.js/common.wxss；小图标按 Taro 默认规则内联或生成文件，原字节均可验证，不在分包重复打入。宠物纸张/背景与字体没有提升到主包。`check:package` 除体积外，检查共享导航的原图哈希、分包中无重复图标、业务资源位置，以及共享导航/布局不反向 import 业务分包。

## 运行证据与限制

`evidence/navigation/navigation-platform.json`：8 项共享导航模拟器测试；覆盖四个页面的一致底栏、五列、安全区、当前栏目、未实现入口提示、宠物/资料表单的离开确认和保存按钮不被底栏遮挡。确认弹窗使用明确 mock 的取消/确认结果，不宣称完成物理弹窗操作。

键盘显隐监听已统一；开发者工具拒绝 mock `wx.onKeyboardHeightChange`，物理键盘与真机显隐仍待验证，未计作通过。清理本项目编译缓存后验证了实际新组件，不能用此前未刷新的模拟器截图代替。

最终 390px 模拟器截图位于 `evidence/navigation/`；宠物原有业务回归单独输出到 `evidence/navigation/regression/`。本轮没有新的 414px 或真机验收，先前 414px 证据仍是其绑定版本的历史记录。
