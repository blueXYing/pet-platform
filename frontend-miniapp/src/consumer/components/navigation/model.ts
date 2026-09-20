// One C-end navigation registry. Top-level business routes are not implemented yet.
// A pet archive module must not be presented as the real home route.
export const consumerNavigationItems = [
  { key: 'home', label: '首页' },
  { key: 'services', label: '服务' },
  { key: 'community', label: '宠友圈' },
  { key: 'messages', label: '消息' },
  { key: 'mine', label: '我的' },
] as const
export type ConsumerNavigationKey = typeof consumerNavigationItems[number]['key']
export type ConsumerNavigationItem = typeof consumerNavigationItems[number]

export function navigationUnavailableMessage(key: ConsumerNavigationKey): string {
  const item = consumerNavigationItems.find(item => item.key === key)!
  return `“${item.label}”页面尚未接入本次预览`
}

export const consumerPageSections = {
  profileEdit: 'mine', petList: 'home', petDetail: 'home', petForm: 'home', merchantApplication: 'mine',
} as const satisfies Record<string, ConsumerNavigationKey>
export type ConsumerNavigationPage = keyof typeof consumerPageSections
