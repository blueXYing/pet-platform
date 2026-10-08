// One C-end navigation registry. The five top-level tabs are real native tabBar pages since the
// 2026-10-08 tabbar+login slice (app.config.ts tabBar); subpages route to them via switchTab.
// 本模块保持纯数据（无 Taro 依赖，node 测试可直接驱动）；switchTab 帮助函数在 switch.ts。
export const consumerNavigationItems = [
  { key: 'home', label: '首页' },
  { key: 'services', label: '服务' },
  { key: 'community', label: '宠友圈' },
  { key: 'messages', label: '消息' },
  { key: 'mine', label: '我的' },
] as const
export type ConsumerNavigationKey = typeof consumerNavigationItems[number]['key']
export type ConsumerNavigationItem = typeof consumerNavigationItems[number]

// Native tabBar page routes (must stay main-package pages; registered in app.config.ts tabBar
// and asserted by package-check.cjs).
export const consumerTabRoutes = {
  home: '/consumer/pages/home/index',
  services: '/consumer/pages/services/index',
  community: '/consumer/pages/community/index',
  messages: '/consumer/pages/messages/index',
  mine: '/consumer/pages/mine/index',
} as const satisfies Record<ConsumerNavigationKey, string>

export const consumerPageSections = {
  profileEdit: 'mine', petList: 'home', petDetail: 'home', petForm: 'home', merchantApplication: 'mine',
  storeServices: 'services', serviceDetail: 'services', storeDirectory: 'services',
  couponList: 'mine', couponDetail: 'mine', pointsPage: 'mine', orderVerify: 'mine', orderList: 'mine', orderDetail: 'mine',
  // Booking create + payment initiation slice (10号 §3.4/§3.5/§3.6).
  bookingCreate: 'services', bookingPay: 'mine',
} as const satisfies Record<string, ConsumerNavigationKey>
export type ConsumerNavigationPage = keyof typeof consumerPageSections
