// 工作台首页看板（Figma 95:1899）数据聚合。只读、可降级：准入仍是唯一放行权威，
// 看板任一路读取失败只把对应块显示为「—」，不阻断工作台入口。字段全部来自既有契约：
// - 今日营收/订单量/环比：70号统计 TODAY（merchant.order.read）
// - 待确认/待服务计数 + 待办预约卡：10号 §4.1 商家订单列表（displayStatus 由服务端投影）
// - 退款计数：57号待处理退款列表（merchant.refund.read）
// - 店名/营业状态：27号 §6.4 店铺资料（OWNER）
// 客户姓名/服务名/评分不在上述读侧，页面不虚构，按登记偏差以订单号/金额/时间呈现。

import type { MerchantOrderPage, MerchantOrderSummary } from '../order/model'
import type {
  StatsOverview, StoreProfileView,
} from '../../shared/merchant-storefront-api'
import type { RefundApplicationPage } from '../../shared/merchant-refund-api'

export type WorkbenchDashboardDeps = Readonly<{
  profile?: () => Promise<StoreProfileView>
  stats?: () => Promise<StatsOverview>
  orders?: (displayStatus: 'PENDING_CONFIRM' | 'PENDING_SERVICE') => Promise<MerchantOrderPage>
  refunds?: () => Promise<RefundApplicationPage>
}>

export type WorkbenchDashboardState = Readonly<{
  phase: 'idle' | 'loading' | 'ready' | 'error'
  notice: string
  storeName: string | null
  storeInitial: string
  businessStatus: 'OPEN' | 'RESTING' | null
  revenue: string | null
  revenueDelta: string | null
  todayOrders: number | null
  pendingConfirm: number | null
  pendingService: number | null
  refunds: number | null
  reviewAvg: string | null
  pending: readonly MerchantOrderSummary[]
  profileReady: boolean
  statsReady: boolean
  ordersReady: boolean
  refundsReady: boolean
}>

const INITIAL: WorkbenchDashboardState = Object.freeze({
  phase: 'idle', notice: '',
  storeName: null, storeInitial: '商', businessStatus: null,
  revenue: null, revenueDelta: null, todayOrders: null,
  pendingConfirm: null, pendingService: null, refunds: null, reviewAvg: null, pending: [],
  profileReady: false, statsReady: false, ordersReady: false, refundsReady: false,
})

export class MerchantWorkbenchDashboard {
  private state: WorkbenchDashboardState = INITIAL
  private listeners = new Set<() => void>()
  private active = true
  private run = 0
  constructor(private readonly deps: WorkbenchDashboardDeps) {}

  getSnapshot = () => this.state
  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
  private publish(next: WorkbenchDashboardState) {
    if (!this.active) return
    this.state = Object.freeze(next)
    this.listeners.forEach(listener => listener())
  }
  show() { this.active = true }
  hide() {
    this.active = false
    this.run++
    this.state = INITIAL
    this.listeners.forEach(listener => listener())
  }
  dispose() { this.active = false; this.run++; this.listeners.clear() }

  async load() {
    if (!this.active) return
    const run = ++this.run
    this.publish({ ...INITIAL, phase: 'loading' })
    const read = async <T>(fn: (() => Promise<T>) | undefined)
      : Promise<PromiseSettledResult<T> | null> => {
      if (!fn) return null
      try { return { status: 'fulfilled', value: await fn() } }
      catch (reason) {
        // 页面按准入动作关闭某一路读取时用该哨兵跳过；不计入失败，也不发请求。
        if (reason instanceof Error && reason.message === 'DASHBOARD_SKIPPED') return null
        return { status: 'rejected', reason }
      }
    }
    const [profile, stats, confirm, service, refunds] = await Promise.all([
      read(this.deps.profile),
      read(this.deps.stats),
      read(this.deps.orders ? () => this.deps.orders!('PENDING_CONFIRM') : undefined),
      read(this.deps.orders ? () => this.deps.orders!('PENDING_SERVICE') : undefined),
      read(this.deps.refunds),
    ])
    if (!this.active || run !== this.run) return
    const value = <T>(result: PromiseSettledResult<T> | null): T | null =>
      result?.status === 'fulfilled' ? result.value : null
    const profileView = value(profile)
    const statsView = value(stats)
    const confirmPage = value(confirm)
    const servicePage = value(service)
    const refundsPage = value(refunds)
    const attempted = [profile, stats, confirm, service, refunds].filter(result => result !== null)
    const succeeded = attempted.filter(result => result && result.status === 'fulfilled')
    if (attempted.length > 0 && succeeded.length === 0) {
      this.publish({ ...INITIAL, phase: 'error', notice: '经营数据读取失败，请稍后重试。' })
      return
    }
    const kept = attempted.length - succeeded.length
    const storeName = profileView?.readonlyFacts.storeName ?? null
    this.publish({
      ...INITIAL,
      phase: 'ready',
      notice: kept > 0 ? '部分经营数据暂不可用，已显示可用部分。' : '',
      storeName,
      storeInitial: storeName ? Array.from(storeName)[0] ?? '商' : '商',
      businessStatus: profileView?.profile.businessStatus ?? null,
      revenue: statsView?.revenue ?? null,
      revenueDelta: statsView?.deltas.revenue ?? null,
      todayOrders: statsView?.orderCount ?? null,
      pendingConfirm: confirmPage?.total ?? null,
      pendingService: servicePage?.total ?? null,
      refunds: refundsPage?.total ?? null,
      reviewAvg: statsView?.reviewAvg ?? null,
      pending: confirmPage?.items ?? [],
      profileReady: profileView !== null,
      statsReady: statsView !== null,
      ordersReady: confirmPage !== null || servicePage !== null,
      refundsReady: refundsPage !== null,
    })
  }
}
