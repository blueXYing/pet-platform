// 客户管理（69号 M 面，只读派生）+ 数据统计（70号 M 面，只读聚合）控制器。

import type {
  CustomerDetail, CustomerPage, StatsOverview, StatsRange,
  StatsServiceRow, TrendBucket,
} from '../../shared/merchant-storefront-api'
import { ownerAccess } from './model'
import type { MerchantStorefrontDeps } from './repository'

export type CustomersState = Readonly<{
  phase: 'idle' | 'loading' | 'entry' | 'error' | 'ready'
  notice: string
  frozen: boolean
  page: number
  pageSize: number
  total: number
  items: CustomerPage['items']
  loadingMore: boolean
  search: string
  committedSearch: string
  detail: CustomerDetail | null
  detailLoading: boolean
}>

const initialCustomers = (): CustomersState => ({
  phase: 'idle', notice: '', frozen: false, page: 1, pageSize: 20, total: 0,
  items: [], loadingMore: false, search: '', committedSearch: '',
  detail: null, detailLoading: false,
})

export class MerchantCustomersController {
  private state = initialCustomers()
  private listeners = new Set<() => void>()
  private active = true
  private epoch = 0
  private reads = 0
  private unsubscribe: () => void
  constructor(private deps: MerchantStorefrontDeps,
    private coordinates: { merchantId: string; storeId: string }) {
    this.unsubscribe = deps.scope.subscribe(() => { this.epoch++; this.publish(initialCustomers()) })
  }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish(state: CustomersState) { if (!this.active) return; this.state = Object.freeze(state); this.listeners.forEach(listener => listener()) }
  private live(epoch: number) { return this.active && epoch === this.epoch }

  hide() { this.active = false }
  dispose() { this.active = false; this.unsubscribe() }

  editSearch(value: string) { this.publish({ ...this.state, search: value }) }

  async load() {
    if (!this.active || this.state.loadingMore) return
    const epoch = this.epoch, run = ++this.reads
    this.publish({ ...this.state, phase: 'loading', notice: '' })
    try {
      const admission = await this.deps.admission(this.coordinates.merchantId, this.coordinates.storeId)
      if (!this.live(epoch) || run !== this.reads) return
      const access = ownerAccess(admission, this.coordinates.merchantId, this.coordinates.storeId)
      if (!access.readable) {
        this.publish({ ...this.state, phase: 'entry', frozen: access.frozen })
        return
      }
      const page = await this.deps.customers(this.state.committedSearch, 1, this.state.pageSize)
      if (!this.live(epoch) || run !== this.reads) return
      this.publish({ ...this.state, phase: 'ready', items: page.items, total: page.total, page: 1 })
    } catch (error) {
      if (!this.live(epoch) || run !== this.reads) return
      this.publish({ ...this.state, phase: 'error', notice: '客户档案读取失败，请稍后重试' })
    }
  }

  async search() {
    this.publish({ ...this.state, committedSearch: this.state.search.trim() })
    await this.load()
  }

  async loadMore() {
    if (this.state.phase !== 'ready' || this.state.loadingMore) return
    const next = this.state.page + 1
    if ((next - 1) * this.state.pageSize >= this.state.total) return
    this.publish({ ...this.state, loadingMore: true })
    const epoch = this.epoch
    try {
      const page = await this.deps.customers(this.state.committedSearch, next, this.state.pageSize)
      if (!this.live(epoch)) return
      this.publish({ ...this.state, loadingMore: false, page: next, items: [...this.state.items, ...page.items], total: page.total })
    } catch {
      if (this.live(epoch)) this.publish({ ...this.state, loadingMore: false, notice: '加载更多失败，请重试' })
    }
  }

  async openDetail(userId: string) {
    if (this.state.detailLoading) return
    this.publish({ ...this.state, detailLoading: true, detail: null })
    const epoch = this.epoch
    try {
      const detail = await this.deps.customerDetail(userId)
      if (!this.live(epoch)) return
      this.publish({ ...this.state, detailLoading: false, detail })
    } catch {
      if (this.live(epoch)) this.publish({ ...this.state, detailLoading: false, notice: '客户详情读取失败，请重试' })
    }
  }

  closeDetail() { this.publish({ ...this.state, detail: null }) }
}

export type StatsState = Readonly<{
  phase: 'idle' | 'loading' | 'entry' | 'error' | 'ready'
  notice: string
  frozen: boolean
  range: StatsRange
  overview: StatsOverview | null
  topItems: readonly StatsServiceRow[]
  trend: readonly TrendBucket[]
  loadingRange: boolean
}>

const initialStats = (): StatsState => ({
  phase: 'idle', notice: '', frozen: false, range: 'LAST_7D',
  overview: null, topItems: [], trend: [], loadingRange: false,
})

export class MerchantStatsController {
  private state = initialStats()
  private listeners = new Set<() => void>()
  private active = true
  private epoch = 0
  private reads = 0
  private unsubscribe: () => void
  constructor(private deps: MerchantStorefrontDeps,
    private coordinates: { merchantId: string; storeId: string }) {
    this.unsubscribe = deps.scope.subscribe(() => { this.epoch++; this.publish(initialStats()) })
  }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish(state: StatsState) { if (!this.active) return; this.state = Object.freeze(state); this.listeners.forEach(listener => listener()) }
  private live(epoch: number) { return this.active && epoch === this.epoch }

  hide() { this.active = false }
  dispose() { this.active = false; this.unsubscribe() }

  async load() {
    if (!this.active) return
    const epoch = this.epoch, run = ++this.reads
    this.publish({ ...this.state, phase: 'loading', notice: '' })
    try {
      const admission = await this.deps.admission(this.coordinates.merchantId, this.coordinates.storeId)
      if (!this.live(epoch) || run !== this.reads) return
      const access = ownerAccess(admission, this.coordinates.merchantId, this.coordinates.storeId)
      if (!access.readable) {
        this.publish({ ...this.state, phase: 'entry', frozen: access.frozen })
        return
      }
      const [overview, top, trend] = await Promise.all([
        this.deps.statsOverview(this.state.range),
        this.deps.statsServiceTop(this.state.range),
        this.deps.statsTrend(this.state.range),
      ])
      if (!this.live(epoch) || run !== this.reads) return
      this.publish({ ...this.state, phase: 'ready', overview, topItems: top.items, trend: trend.buckets })
    } catch (error) {
      if (!this.live(epoch) || run !== this.reads) return
      this.publish({ ...this.state, phase: 'error', notice: '经营数据读取失败，请稍后重试' })
    }
  }

  async selectRange(range: StatsRange) {
    if (this.state.phase !== 'ready' || this.state.loadingRange || range === this.state.range) return
    this.publish({ ...this.state, range, loadingRange: true, notice: '' })
    const epoch = this.epoch
    try {
      const [overview, top, trend] = await Promise.all([
        this.deps.statsOverview(range),
        this.deps.statsServiceTop(range),
        this.deps.statsTrend(range),
      ])
      if (!this.live(epoch)) return
      this.publish({ ...this.state, loadingRange: false, overview, topItems: top.items, trend: trend.buckets })
    } catch {
      if (this.live(epoch)) this.publish({ ...this.state, loadingRange: false, notice: '该周期数据读取失败，请重试' })
    }
  }
}

/** 客户详情页控制器（69号 §3 详情面）：按路由 userId 读取派生档案（只读）。 */
export class CustomerDetailController {
  private state: CustomersState
  private listeners = new Set<() => void>()
  private active = true
  private epoch = 0
  private reads = 0
  private unsubscribe: () => void
  constructor(deps: MerchantStorefrontDeps,
    private coordinates: { merchantId: string; storeId: string }, private userId: string) {
    this.state = { ...initialCustomers(), phase: 'loading' }
    this.unsubscribe = deps.scope.subscribe(() => { this.epoch++; this.publish({ ...initialCustomers(), phase: 'loading' }) })
    this.deps = deps
  }
  private deps: MerchantStorefrontDeps
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish(state: CustomersState) { if (!this.active) return; this.state = Object.freeze(state); this.listeners.forEach(listener => listener()) }
  private live(epoch: number) { return this.active && epoch === this.epoch }

  hide() { this.active = false }
  dispose() { this.active = false; this.unsubscribe() }

  async load() {
    if (!this.active || this.state.detailLoading) return
    const epoch = this.epoch, run = ++this.reads
    this.publish({ ...this.state, phase: 'loading', notice: '' })
    try {
      const admission = await this.deps.admission(this.coordinates.merchantId, this.coordinates.storeId)
      if (!this.live(epoch) || run !== this.reads) return
      const access = ownerAccess(admission, this.coordinates.merchantId, this.coordinates.storeId)
      if (!access.readable) {
        this.publish({ ...this.state, phase: 'entry', frozen: access.frozen })
        return
      }
      const detail = await this.deps.customerDetail(this.userId)
      if (!this.live(epoch) || run !== this.reads) return
      this.publish({ ...this.state, phase: 'ready', detail })
    } catch (error) {
      if (!this.live(epoch) || run !== this.reads) return
      const code = (error as { code?: string })?.code
      this.publish({
        ...this.state, phase: code === 'COMMON_NOT_FOUND' ? 'entry' : 'error',
        notice: code === 'COMMON_NOT_FOUND' ? '该客户不在本店客户档案中' : '客户详情读取失败，请稍后重试',
      })
    }
  }
}
