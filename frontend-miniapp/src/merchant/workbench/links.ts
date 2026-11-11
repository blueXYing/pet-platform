/**
 * P-01/P-07a-c（2026-11-11 用户拍板）：商家工作台「营收票券 + KPI 卡」跳转白名单。
 * 与消息中心 routeForNotification 同一条固定路由纪律——目标页常量收敛在此模块，
 * 页面不接收任何来自输入的任意跳转 URL。今日订单卡（P-07d）二级页口径待产品
 * 确认，本轮不跳转、不在此登记目标页。
 */
export const workbenchCardLinks = {
  /** P-01 营收票券 → 数据统计页（与八宫格 workbench-stats 同目标、同 openStats）。 */
  heroStats: '/merchant/pages/stats/index',
  /** P-07a/b 待确认 → 订单列表待接单 tab 深链。 */
  pendingConfirm: '/merchant/pages/order-list/index?tab=PENDING_CONFIRM',
  /** P-07a/b 待服务 → 订单列表待服务 tab 深链。 */
  pendingService: '/merchant/pages/order-list/index?tab=PENDING_SERVICE',
  /** P-07c 退款 → 售后列表页（页面已达标，仅补工作台入口）。 */
  refunds: '/merchant/pages/refund/index',
} as const

export type WorkbenchCardKey = keyof typeof workbenchCardLinks

/**
 * 页面注入本页的 handoff 漏斗（controller.handoffToMerchantPage + Taro.navigateTo +
 * 失败 handoffCancelled），得到每张可点卡片的 onClick；全部出口仍收口在页面的
 * handoff 单点上。单元测试注入 spy 以断言每张卡落的 url。
 */
export function createWorkbenchCardOpeners(handoff: (url: string) => void): Record<WorkbenchCardKey, () => void> {
  return {
    heroStats: () => handoff(workbenchCardLinks.heroStats),
    pendingConfirm: () => handoff(workbenchCardLinks.pendingConfirm),
    pendingService: () => handoff(workbenchCardLinks.pendingService),
    refunds: () => handoff(workbenchCardLinks.refunds),
  }
}
