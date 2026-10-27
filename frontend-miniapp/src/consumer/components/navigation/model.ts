// One C-end navigation registry. The five top-level tabs are custom-tabBar pages since the
// 2026-10-26 transition ruling (app.config.ts tabBar.custom); subpages route to them via switchTab.
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

// TabBar page routes (must stay main-package pages; registered in app.config.ts tabBar and
// asserted by package-check.cjs).
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
  // Address book slice (contract 59 via HTTP10 §3.2.2, ruling-32 second batch): the three
  // subpages ride the mine section like the other mine-hub destinations.
  addressList: 'mine', addressNew: 'mine', addressEdit: 'mine',
  // Points mall display-state page (32号裁决第二节: 只赚不花, 商城=能看不能换).
  mallPage: 'mine',
  // Favorite/footprint lists (contract 58, 32号裁决第二节批次): mine-section subpages.
  favoriteList: 'mine', footprintList: 'mine',
  // Booking create + payment initiation slice (10号 §3.4/§3.5/§3.6).
  bookingCreate: 'services', bookingPay: 'mine',
  // Become-provider entry slice (32号 §2): entered from the mine grid, rides the mine section.
  becomeProvider: 'mine',
  // Encyclopedia browse slice (contract 62, PRD 5.1.4): mine-grid entry, anonymous-browsable.
  baikeList: 'mine', baikeDetail: 'mine',
} as const satisfies Record<string, ConsumerNavigationKey>
export type ConsumerNavigationPage = keyof typeof consumerPageSections
