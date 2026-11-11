// 商家端底部 tab 路由表与导航方式裁决（纯函数，无 Taro 依赖，供组件与测试共用）。

export type MerchantTabKey = 'home' | 'orders' | 'verify' | 'messages' | 'mine'

export const MERCHANT_TAB_ROUTES: Record<MerchantTabKey, string> = {
  home: '/merchant/pages/workspace/index',
  orders: '/merchant/pages/order-list/index',
  verify: '/merchant/pages/staff-verify/index',
  messages: '/merchant/pages/messages/index',
  mine: '/merchant/pages/mine/index',
}

export function merchantTabRoute(key: MerchantTabKey): string {
  return MERCHANT_TAB_ROUTES[key]
}

/** P-03（2026-11-11）：切到工作台（home tab）一律 reLaunch——Taro 在 navigateTo/redirectTo 时会
 * dispose 隐藏的工作台 React 树，redirectTo 还会让旧工作台实例残留在返回栈里，复访/返回即
 * stale placeholder 白屏；reLaunch 清栈重建工作台（商家端根页语义，MINI-003 每次进入重查准入）。
 * 其余 tab 互为 sibling：redirectTo 保持恰好一个 tab 页存活。 */
export function merchantTabNavMethod(key: MerchantTabKey): 'reLaunch' | 'redirectTo' {
  return key === 'home' ? 'reLaunch' : 'redirectTo'
}
