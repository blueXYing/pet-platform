export default defineAppConfig({
  // Tab pages must be main-package pages; the first entry is the launch page (home).
  pages: ['consumer/pages/home/index', 'consumer/pages/services/index', 'consumer/pages/community/index', 'consumer/pages/messages/index', 'consumer/pages/mine/index', 'consumer/pages/shell/index', 'consumer/pages/diagnostics/index', 'consumer/pages/profile-edit/index'],
  window: { navigationBarTitleText: '宠物生活服务', backgroundColor: '#f5f5f5' },
  // 用户 2026-10-08 裁决：V1 用原生 tabBar 等分布局；设计稿“宠友圈中间凸起”登记为设计偏差留补稿轮。
  // 图标沿用导航登记表提取的原切图（无选中态变体，选中差异仅文字色，同登记偏差）。
  tabBar: {
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
      // Staff workbench slice (52/54/48 K1, 2026-10-06 adjudication): staff landing with the
      // invitation confirm entry, invitation id lookup and the fail-closed verify entry page.
      'pages/staff-workbench/index', 'pages/staff-invitation/index', 'pages/staff-verify/index',
      // Merchant manual order decisions (45号 via 10号 §4.2/§4.3): OWNER confirm/reject page;
      // §4.1 store order list read slice adds the list entry page ahead of it.
      'pages/order-list/index', 'pages/order-confirm/index'] },
    { root: 'consumer/pages/pet-archive', pages: ['index', 'detail', 'form'] },
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
  ],
})
