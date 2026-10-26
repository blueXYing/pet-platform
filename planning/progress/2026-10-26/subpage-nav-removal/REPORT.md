# 二级及以下页面移除全局底部导航（2026-10-26）

## 裁决

用户 2026-10-26 确认：只保留五个根 tab 页（首页/服务/宠友圈/消息/我的）的原生 tabBar；其余页面实际产品不显示自定义底部导航。设计预览/对照通道（`preview=1`，含 `referenceCanvas=1`）保留自定义底栏，仅用于与 Figma 逐帧比对。

## 改动范围

审计到 `consumer/pages` 共 29 处 `ConsumerPageLayout` 的 `navigation` prop：

- **26 处改为预览条件传入**：`navigation={preview ? {...原对象...} : undefined}`。真实模式不卸载 `ConsumerPageLayout`，只省略导航，`consumer-page-layout-without-navigation` 自动去除 80px 预留。
  - pet-archive：`index` / `detail` / `form` / `record-form`
  - orders：`list` / `detail` / `refund-apply` / `reschedule` / `review`
  - profile-edit、order-verify
  - store-services：`index` / `service-detail` / `stores`
  - merchant-application：`index` / `signing`
  - booking：`create` / `pay`
  - coupon-points：`coupons` / `points`
  - points-mall、points-tasks：`signin` / `tasks` / `rewards`
  - favtrack：`favorites` / `footprints`
- **3 处无预览通道，直接移除**：`aftersale/common.tsx`（覆盖 index/detail/apply）、`become-provider/index.tsx`、`become-provider/service-type.tsx`；同时移除不再使用的 `switchConsumerTab` import。
- `app.config.ts` 原生 tabBar 未改；五个根 tab 页继续使用原生栏。
- 布局适配：`become-provider` 半屏面板 `.bep-design` 改为贴 `env(safe-area-inset-bottom)`；`store-services` 固定操作条在无导航布局下同样贴安全区（预览仍保留 62px 导航条位）；`points-tasks` 任务/奖励面板补底部安全区。
- 登记：`docs/08-engineering/20-设计源登记表-figma-map.md` §4.20。
- 门禁：新增 `frontend-miniapp/src/consumer/tests/subpage-navigation.test.ts`，静态扫描 `consumer/pages`，禁止无条件 `navigation` 回归。

## 验证

- `npm run typecheck`：PASS
- `npm test`：**615 / 615**，0 失败（原 614 + 新增静态门禁 1）
- `python3 backend/tools/run-frontend-gate.py miniapp-build`：**PASS**
- `check:package`：总包 **8,124,001** 字节，20 个普通分包，预算内
- 微信开发者工具（Windows，iPhone 模拟器，真实 E2E 后端 `127.0.0.1:18081`）：
  - 真实模式 `.consumer-bottom-nav` 数量为 **0** 的代表页：宠物档案列表/添加表单、我的订单、门店目录、我的售后、积分商城、积分任务、成为服务者、入驻申请、确认支付
  - `preview=1` 宠物档案添加表单：`.consumer-bottom-nav` 数量 **1**，底栏贴视口底部（390x844 下 top 749.86 / height 94.14）
  - 根 tab 首页：原生 tabBar 正常显示，未挂载自定义底栏

## 证据

- `real-pet-list.png`、`real-form.png`、`real-orders-list.png`、`real-store-directory.png`、`real-aftersale.png`、`real-points-mall.png`、`real-points-tasks.png`、`real-points-tasks-bottom.png`、`real-become-provider.png`、`real-booking-pay.png`：真实模式无底栏；任务页底部截图确认面板安全区收口
- `preview-form.png`：`preview=1` 保留底栏
- `root-home-native-tabbar.png`：根 tab 首页原生 tabBar

## 遗留风险

- 未执行物理真机 VIS；当前为开发者工具 + 真实 E2E 后端。
- Figma 二级页底栏为设计残留；宠物详情/添加表单底部的原导航带仍属于画布高度的一部分（操作按钮均在带上方，无遮挡），未为复刻底栏补图。
- 刘海/Home Indicator 设备上，`points-tasks` 面板已补 `env(safe-area-inset-bottom)`；其余页面底部间距仍建议物理真机复核。
- `store-services` 的 `.svc-bottombar` 当前无 TSX 引用（既有死样式）；已加无导航安全区兜底，防后续复活时再次假设 62px 导航条。
