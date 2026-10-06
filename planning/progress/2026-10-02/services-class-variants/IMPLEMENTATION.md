# services 页 WXSS 属性选择器迁移为类名变体 — IMPLEMENTATION

- 分支：`codex/services-class-variants-20261002`（基于 origin/develop @ e3b846e）
- 日期：2026-10-02
- 范围：frontend-miniapp 商家服务管理页（M-002 列表 + 编辑页），仅此 7 处属性选择器及直接配套。

## 1. 根因（引用在案证据）

2026-10-01 售后 PR#99 的 VIS 复验以四重独立证据（outerWxml、属性查询、尺寸测量、截图像素取色）证实：
**Taro 4.1.5 不把动态 `data-*` 渲染进原生 wxml，WXSS 属性选择器（`[data-x=y]`）永不命中且静默失效**；
typecheck / 单测 / 构建全绿也照错。本 PR 将同一根因在 services 页的残留全部迁移为类名变体，
映射方式参照 PR#99 `merchant/aftersale/model.ts` 的 `statusTagClass()` 模式（该文件在 PR#99 分支，develop 尚未合并，此处按模式等价实现）。

## 2. 改动清单

| 文件 | 改动 |
|---|---|
| `src/merchant/services/model.ts` | 新增 `serviceStatusTagClass()`（DRAFT/REVIEWING/ACTIVE/OFFLINE/REJECTED → 显式类名映射，无字符串插值）、`selectedPillClass()`、`toggleStateClass()`，含根因注释 |
| `src/merchant/pages/services/index.tsx` | 状态标签 `data-status` → `serviceStatusTagClass()`；开关 `data-on` → `toggleStateClass(status==='ACTIVE')`；移除卡片上无效的 `data-status` |
| `src/merchant/pages/services/edit.tsx` | 5 处分类/宠物类型/履约方式/核销胶囊 `data-selected` → `selectedPillClass(...)` |
| `src/merchant/pages/services/page.css` | 7 处 `[data-*]` 选择器改类名（颜色值逐字不变）；另对 4 条按钮规则做特异性加固（见 §4） |
| `src/merchant/tests/service-manage.test.ts` | 新增映射测试：5 状态穷举 + 开关/胶囊两态 + `serviceManageStatuses` 全覆盖断言 |

## 3. 迁移清单（7 处，设计色值不变）

1. `.msvc-card-status[data-status='REVIEWING']` → `.msvc-card-status-reviewing`（#fdeec8/#7a4a1d）
2. `.msvc-card-status[data-status='REJECTED']` → `.msvc-card-status-rejected`（#fbd9d9/#a83232）
3. `.msvc-card-status[data-status='ACTIVE']` → `.msvc-card-status-active`（#d7f0d0/#2f6b2f）
4. `.msvc-card-status[data-status='OFFLINE']` → `.msvc-card-status-offline`（#e5e7eb/#4b5563）
5. `.msvc-toggle[data-on='true']` → `.msvc-toggle-on`（#5baae8）
6. `.msvc-toggle[data-on='true'] .msvc-toggle-knob` → `.msvc-toggle-on .msvc-toggle-knob`（left 23.6u）
7. `.medit-pill[data-selected='true']` → `.medit-pill-selected`（#5baae8/白字）

## 4. 迁移中发现并修正的同族问题（在范围内：变体要真正生效的必要条件）

`.msvc-page button { background: transparent; border: 0; ... }`（特异性 0,1,1）会压制单类按钮规则（0,1,0）。
实测证实：仅改类名后开关滑轨仍为透明（开关"选中蓝"依旧不生效）。故对被迁移状态直接依赖的 4 条规则做显式特异性加固，声明值逐字不变：

- `.msvc-toggle` → `.msvc-page .msvc-toggle`（0,2,0，OFF 灰轨 #d1d5db 恢复）
- `.msvc-toggle-on` → `.msvc-toggle.msvc-toggle-on`（0,2,0，压过 reset）
- `.medit-pill` → `.msvc-page .medit-pill`（0,2,0，未选中态 #f9fafb/#e5e7eb 恢复）
- `.medit-pill-selected` → `.medit-pill.medit-pill-selected`（0,2,0）

## 5. 验证

### 5.1 门禁（全部 PASS）
- `npm run typecheck` PASS
- `npm test` 179/179 PASS（含新增映射测试）
- `npm run build:weapp` PASS；产物 `dist/merchant/sub-vendors.wxss` 含全部 7 条新类名规则（色值逐字一致），全 dist 无 `[data-` 选择器
- `npm run check:package` PASS（totalBytes 4,004,285，预算内）

### 5.2 视觉复验（真实渲染取值，微信开发者工具 + automation）
方式：`preview=1` 契约 Mock 自带 8 条跨 5 状态 fixtures，**无需商家登录/后端**；以 `wechatide` automation 在模拟器对真实渲染页面做 outerWxml + 计算样式取值 + tap 交互取证（本机登录态有效，appid 用主目录真实 project.config.json，已还原不提交）。

| 目标 | 实测计算样式 | 设计值 | 结论 |
|---|---|---|---|
| ACTIVE 标签 bg | rgb(215,240,208) | #d7f0d0 | 命中 |
| REVIEWING 标签 bg | rgb(253,238,200) | #fdeec8 | 命中 |
| REJECTED 标签 bg | rgb(251,217,217) | #fbd9d9 | 命中 |
| OFFLINE 标签 bg | rgb(229,231,235) | #e5e7eb | 命中 |
| DRAFT 基础标签 bg | rgb(192,236,255) | #c0ecff | 命中 |
| 开关 ON 滑轨 bg | rgb(91,170,232) | #5baae8 | 命中（修复前透明） |
| 开关 OFF 滑轨 bg | rgb(209,213,219) | #d1d5db | 命中（修复前透明） |
| 开关 ON 滑块 left | 22.90px ≈ 23.6u | 23.6u | 命中 |
| 开关 OFF 滑块 left | 2.04px ≈ 2.1u | 2.1u | 命中 |
| 选中胶囊 bg | rgb(91,170,232) | #5baae8 | 命中 |
| 未选中胶囊 bg | rgb(249,250,251) | #f9fafb | 命中 |
| tap 分类胶囊交互 | class 翻转为 `medit-pill medit-pill-selected`，bg 变 #5baae8 | — | 命中 |

outerWxml 取证：原生 wxml 中节点携带 `class="msvc-card-status msvc-card-status-reviewing"` 等（对照 PR#99 根因：动态 data-* 从不出现在原生 wxml）。截图见本目录 `vis-list.png`、`vis-edit.png`。

### 5.3 验证边界（如实声明）
- 模拟器验证（基础库 3.17.2，视口截图 149x321），**未做 iOS/Android 真机验证**。
- 列表数据为 preview 契约 Mock fixtures，**未走真实商家登录 + 门店 + 服务接口链路**；但被验证的是纯渲染层（类名→样式命中），数据来源不影响该层结论。
- 教训记录：DevTools 对已开项目存在 wxss 陈旧缓存（重编译后旧包仍生效），**关闭项目窗口重开后**新 wxss 才生效；复验数据以重开后的最终取值为准。

## 6. 相邻发现（未修，超出本 PR scope，建议跟进）

同一 reset 压制面还波及本页其他按钮规则（0,1,0 输给 `.msvc-page button` 0,1,1）：实测 `.medit-action`（提交审核主按钮）bg 为透明，设计值应为 #5baae8；`.medit-action-secondary`、`.medit-banner-action`、`.medit-cover`、`.msvc-load-more`、`.msvc-add` 等的 background/border 预期同样被压制（同类推断，未逐实测）。修复模式与 §4 相同（`.msvc-page .xxx` 复合），建议另开小 PR 统一处理，避免本 PR 扩大 Issue Scope。

## 7. 遗留风险

- 真机渲染未验证（见 5.3）；wxss 特异性结论已在模拟器实测成立，真机风险低。
- 若后续给 services 页新增按钮类样式，需沿用 `.msvc-page .xxx` 复合模式，否则会被本页 button reset 压制（已在 page.css 注释说明）。
- `statusTagClass` 等价实现与 PR#99 的 `aftersale/model.ts` 并存于两个模块（命名 `serviceStatusTagClass` 区分）；PR#99 合并后无需合并代码，模式一致即可。
