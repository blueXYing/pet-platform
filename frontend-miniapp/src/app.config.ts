export default defineAppConfig({
  // Tab pages must be main-package pages; the first entry is the launch page (home).
  // 启动页必须是主包且为 pages[0]（子包不能作为启动页）：冷启动先呈现「初始界面」(Figma 10:89)，
  // 再按首启标记 redirectTo 引导页（子包）或 switchTab 首页——避免先渲染首页再跳转导致闪屏。
  pages: ['consumer/pages/splash/index', 'consumer/pages/home/index', 'consumer/pages/services/index', 'consumer/pages/community/index', 'consumer/pages/messages/index', 'consumer/pages/mine/index', 'consumer/pages/shell/index', 'consumer/pages/diagnostics/index', 'consumer/pages/profile-edit/index'],
  window: { navigationBarTitleText: '宠物生活服务', backgroundColor: '#f5f5f5' },
  // 用户 2026-10-26 裁决：为消除原生 tabBar 在二级页跳转时先隐藏的空档，五个根 tab 页改用同款自定义 tabBar；二级页仍不显示底栏。
  // 图标沿用导航登记表提取的原切图（无选中态变体，选中差异仅文字色，同登记偏差）。
  tabBar: {
    custom: true,
    color: '#3c3c3c', selectedColor: '#53bfee', backgroundColor: '#ffffff', borderStyle: 'black',
    list: [
      { pagePath: 'consumer/pages/home/index', text: '首页', iconPath: 'consumer/assets/navigation/home.png', selectedIconPath: 'consumer/assets/navigation/home.png' },
      { pagePath: 'consumer/pages/services/index', text: '服务', iconPath: 'consumer/assets/navigation/services.png', selectedIconPath: 'consumer/assets/navigation/services.png' },
      { pagePath: 'consumer/pages/community/index', text: '宠友圈', iconPath: 'consumer/assets/navigation/community.png', selectedIconPath: 'consumer/assets/navigation/community.png' },
      { pagePath: 'consumer/pages/messages/index', text: '消息', iconPath: 'consumer/assets/navigation/messages.png', selectedIconPath: 'consumer/assets/navigation/messages.png' },
      { pagePath: 'consumer/pages/mine/index', text: '我的', iconPath: 'consumer/assets/navigation/mine.png', selectedIconPath: 'consumer/assets/navigation/mine.png' },
    ],
  },
  permission: { 'scope.userLocation': { desc: '用于选择入驻店铺的位置与地址' } },
  requiredPrivateInfos: ['chooseLocation'],
  // M-001 internal shell shares this AppID; C-End owns ordinary subpackage registration.
  // The pet archive pages ship as an ordinary subpackage so the 2x design strips stay within
  // the platform per-package limit; routes are unchanged.
  subPackages: [
    { root: 'merchant', pages: ['pages/workspace/index', 'pages/services/index', 'pages/services/edit', 'pages/messages/index',
      'pages/schedule/index', 'pages/schedule/windows', 'pages/schedule/staff', 'pages/schedule/capabilities',
      // Contract 54 staff binding (D1-a): member management workbench child page, one compact
      // line so parallel M-side page PRs rebase cleanly.
      'pages/members/index', 'pages/aftersale/index', 'pages/aftersale/detail',
      // Merchant refund processing (57号 via 10号 §4.4-§4.6): pending list + detail with the
      // two OWNER decisions (reject reason mandatory); one compact line per the convention.
      'pages/refund/index', 'pages/refund/detail',
      // Staff workbench slice (52/54/48 K1, 2026-10-06 adjudication): staff landing with the
      // invitation confirm entry, invitation id lookup and the fail-closed verify entry page.
      'pages/staff-workbench/index', 'pages/staff-invitation/index', 'pages/staff-verify/index',
      // Merchant manual order decisions (45号 via 10号 §4.2/§4.3): OWNER confirm/reject page;
      // §4.1 store order list read slice adds the list entry page ahead of it.
      'pages/order-list/index', 'pages/order-confirm/index',
      // Review appeal M face (56号 / REV-002, one compact line per the app.config convention):
      // OWNER review list with the one-appeal-per-review entry and status overlay.
      'pages/reviews/index',
      // M-side finish slice (27 §6.4 store profile / 69 derived customers / 70 stats, switches
      // default OFF server-side): shop info form, customer list + detail, stats dashboard.
      'pages/shop-info/index', 'pages/customers/index', 'pages/customers/detail', 'pages/stats/index',
      // 2026-10-19 CCR expansion: the 70号 service drill-down page (Top5 row → paged
      // read-only order rows of one window service).
      'pages/stats/service-orders'] },
    // Contract 68 (2026-10-19 ruling): vaccine/deworming record add forms ride the pet-archive
    // subpackage (the two design frames 96:2580/96:3006), one compact line per the convention.
    { root: 'consumer/pages/pet-archive', pages: ['index', 'detail', 'form', 'vaccine-form', 'deworm-form'] },
    { root: 'consumer/pages/merchant-application', pages: ['index', 'signing'] },
    { root: 'consumer/pages/store-services', pages: ['index', 'service-detail', 'stores'] },
    { root: 'consumer/pages/coupon-points', pages: ['coupons', 'coupon-detail', 'points'] },
    { root: 'consumer/pages/aftersale', pages: ['index', 'detail', 'apply'] },
    // Order verification code slice (47号 §4 v0.2): explicit order-id entry, no order list yet.
    { root: 'consumer/pages/order-verify', pages: ['index'] },
    // C-004 order read slice (10号 §3.7): my orders list + detail, verify-code entry inside detail.
    // C-005 refund apply + §3.8 reschedule (46号) + REV-001 review ride the same orders
    // subpackage, all gated by detail actions; one compact line per the app.config convention.
    { root: 'consumer/pages/orders', pages: ['list', 'detail', 'refund-apply', 'reschedule', 'review'] },
    // NTF preference slice (SSOT §16.4): notification preference settings page.
    { root: 'consumer/pages/notification-preferences', pages: ['index'] },
    // Booking create + payment initiation slice (10号 §3.4/§3.5/§3.6): service-detail entry,
    // unpaid-order pay entry; one compact line per the app.config convention.
    { root: 'consumer/pages/booking', pages: ['create', 'pay'] },
    // Address book slice (contract 59 via HTTP10 §3.2.2, ruling-32 second batch): list with
    // inline set-default/delete, new/edit as the designed bottom-sheet form pages.
    { root: 'consumer/pages/address', pages: ['index', 'new', 'edit'] },
    // Become-provider entry slice (32号 §2 seven-page batch): landing sheet + service type
    // selection feeding the existing merchant-application flow via merchantTypeCode.
    { root: 'consumer/pages/become-provider', pages: ['index', 'service-type'] },
    // Points mall display-state page (32号裁决第二节: 只赚不花——商品陈列、兑换置灰禁用,
    // 商品为设计样例无商品后端; entry from the mine hub, one compact line per the convention).
    { root: 'consumer/pages/points-mall', pages: ['index'] },
    // Points earn slice (58号 / 32号 ruling second batch): sign-in calendar, referral tasks and
    // reward claim pages, entered from the mine tab; points only accrue in V1.
    { root: 'consumer/pages/points-tasks', pages: ['signin', 'tasks', 'rewards'] },
    // Favorite/footprint lists (contract 58, 32号裁决第二节批次, CCR-C-FAVTRACK-001):
    // my favorites + my footprints; detail rows route to the existing store-services pages.
    { root: 'consumer/pages/favtrack', pages: ['favorites', 'footprints'] },
    // Encyclopedia browse slice (contract 62, PRD 5.1.4): category/keyword/tag list + breed
    // detail with the ability radar; mine-grid entry, anonymous-browsable per PRD.
    { root: 'consumer/pages/baike', pages: ['index', 'detail'] },
    // Settings hub slice (contract 66, PRD 5.1.29; user ruling 2026-10-19 "全做"): the hub
    // page + the deletion flow page + the privacy-policy/user-agreement static content pages
    // (WeChat review hard requirement); one compact line per the app.config convention.
    { root: 'consumer/pages/settings', pages: ['index', 'deletion', 'privacy-policy', 'user-agreement'] },
    // Help & service slice (contract 67, PRD 5.1.25 minimum: static FAQ + hotline call).
    { root: 'consumer/pages/help', pages: ['index'] },
    // Feedback slice (contract 67, PRD 5.1.28 complaint class): create + my-ticket list.
    { root: 'consumer/pages/feedback', pages: ['index', 'list'] },
    // Onboarding splash (user ruling 2026-10-21; figma 10:108/33:40/33:58): first-launch-only
    // three-screen guide; subpackage keeps the ~1MB 2x strips within the per-package limit.
    { root: 'consumer/pages/onboarding', pages: ['index'] },
  ],
})
