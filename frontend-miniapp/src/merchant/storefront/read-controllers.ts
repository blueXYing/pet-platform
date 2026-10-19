// 客户管理（69号 M 面 + 2026-10-19 扩展：尾号搜索/内部备注）+ 数据统计（70号 M 面 +
// 2026-10-19 扩展：自定义区间/拒单率/服务下钻/排期利用率）控制器。

import type {
  CustomerDetail, CustomerNote, CustomerPage, StatsOverview, StatsRange,
  StatsServiceOrdersPage, StatsServiceRow, StatsUtilization, TrendBucket,
} from '../../shared/merchant-storefront-api'
import { defaultCustomWindow, ownerAccess, validCustomWindow } from './model'
import type { MerchantStorefrontDeps } from './repository'

export type CustomerSearchMode = 'nickname' | 'orderNoSuffix'

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
  searchMode: CustomerSearchMode
  detail: CustomerDetail | null
  detailLoading: boolean
  noteDraft: string
  noteSaving: boolean
  noteVersion: string | null
}>

const initialCustomers = (): CustomersState => ({
  phase: 'idle', notice: '', frozen: false, page: 1, pageSize: 20, total: 0,
  items: [], loadingMore: false, search: '', committedSearch: '',
  searchMode: 'nickname', detail: null, detailLoading: false,
  noteDraft: '', noteSaving: false, noteVersion: null,
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

  /** 搜索模式切换（2026-10-19 扩展）：昵称包含 / 订单号尾号（仅命中本店订单）。 */
  switchSearchMode(mode: CustomerSearchMode) {
    if (mode === this.state.searchMode) return
    this.publish({ ...this.state, searchMode: mode, search: '', committedSearch: '' })
  }

  private filter(): { nickname?: string; orderNoSuffix?: string } {
    if (this.state.searchMode === 'orderNoSuffix') {
      return this.state.committedSearch ? { orderNoSuffix: this.state.committedSearch } : {}
    }
    return this.state.committedSearch ? { nickname: this.state.committedSearch } : {}
  }

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
      const page = await this.deps.customers(this.filter(), 1, this.state.pageSize)
      if (!this.live(epoch) || run !== this.reads) return
      this.publish({ ...this.state, phase: 'ready', items: page.items, total: page.total, page: 1 })
    } catch (error) {
      if (!this.live(epoch) || run !== this.reads) return
      const bad = (error as Error)?.message === 'INVALID_QUERY'
      this.publish({ ...this.state, phase: bad ? 'ready' : 'error',
        notice: bad ? '搜索条件不正确：订单号尾号为 2-10 位数字' : '客户档案读取失败，请稍后重试' })
    }
  }

  async search() {
    const term = this.state.search.trim()
    if (this.state.searchMode === 'orderNoSuffix' && term && !/^[0-9]{2,10}$/.test(term)) {
      this.publish({ ...this.state, notice: '订单号尾号为 2-10 位数字' })
      return
    }
    this.publish({ ...this.state, committedSearch: term })
    await this.load()
  }

  async loadMore() {
    if (this.state.phase !== 'ready' || this.state.loadingMore) return
    const next = this.state.page + 1
    if ((next - 1) * this.state.pageSize >= this.state.total) return
    this.publish({ ...this.state, loadingMore: true })
    const epoch = this.epoch
    try {
      const page = await this.deps.customers(this.filter(), next, this.state.pageSize)
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

/** 客户详情页控制器（69号 §3 详情面 + 2026-10-19 扩展）：派生档案只读 + 内部备注 OWNER 写。 */
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

  editNote(value: string) {
    this.publish({ ...this.state, noteDraft: value })
  }

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
      this.publish({
        ...this.state, phase: 'ready', detail,
        noteDraft: detail.note?.content ?? '',
        noteVersion: detail.note?.version ?? '0',
      })
    } catch (error) {
      if (!this.live(epoch) || run !== this.reads) return
      const code = (error as { code?: string })?.code
      this.publish({
        ...this.state, phase: code === 'COMMON_NOT_FOUND' ? 'entry' : 'error',
        notice: code === 'COMMON_NOT_FOUND' ? '该客户不在本店客户档案中' : '客户详情读取失败，请稍后重试',
      })
    }
  }

  /** 商家内部备注保存（2026-10-19 扩展）：单条 ≤200 字、幂等槽、乐观版本；空串=清空。 */
  async saveNote(): Promise<void> {
    if (!this.state.detail || this.state.noteSaving) return
    const note = this.state.noteDraft.trim()
    if (Array.from(note).length > 200) {
      this.publish({ ...this.state, notice: '备注最多 200 字' })
      return
    }
    this.publish({ ...this.state, noteSaving: true, notice: '' })
    const epoch = this.epoch
    try {
      const saved = await this.deps.saveCustomerNote(this.userId, note, this.state.noteVersion ?? '0')
      if (!this.live(epoch)) return
      const detail = this.state.detail
      this.publish({
        ...this.state, noteSaving: false,
        noteVersion: saved.note.version, noteDraft: saved.note.content ?? '',
        detail: detail ? { ...detail, note: saved.note } : detail,
      })
    } catch (error) {
      if (!this.live(epoch)) return
      const code = (error as { code?: string })?.code
      if (code === 'COMMON_CONFLICT' || code === 'IDEMPOTENCY_KEY_CONFLICT') {
        try { this.deps.retireNoteConflict(error) } catch { /* 槽位校验失败保持锁定 */ }
        this.publish({ ...this.state, noteSaving: false, notice: '备注已被其他修改更新，请刷新后重试' })
        return
      }
      this.publish({ ...this.state, noteSaving: false, notice: noteNotice(code) })
    }
  }
}

function noteNotice(code: string | undefined): string {
  if (code === 'COMMON_FORBIDDEN') return '仅商家主账号可维护客户备注'
  return '备注保存失败，请稍后重试'
}

export type StatsState = Readonly<{
  phase: 'idle' | 'loading' | 'entry' | 'error' | 'ready'
  notice: string
  frozen: boolean
  range: StatsRange
  custom: { from: string; to: string }
  overview: StatsOverview | null
  topItems: readonly StatsServiceRow[]
  trend: readonly TrendBucket[]
  utilization: StatsUtilization | null
  loadingRange: boolean
}>

const initialStats = (): StatsState => ({
  phase: 'idle', notice: '', frozen: false, range: 'LAST_7D',
  custom: defaultCustomWindow(new Date()),
  overview: null, topItems: [], trend: [], utilization: null, loadingRange: false,
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

  private window(): { range: StatsRange; custom?: { from: string; to: string } } {
    return this.state.range === 'CUSTOM'
      ? { range: 'CUSTOM', custom: this.state.custom }
      : { range: this.state.range }
  }

  private async readAll(epoch: number): Promise<void> {
    const [overview, top, trend, utilization] = await Promise.all([
      this.deps.statsOverview(this.state.range, this.window().custom),
      this.deps.statsServiceTop(this.state.range, this.window().custom),
      this.deps.statsTrend(this.state.range, this.window().custom),
      this.deps.statsUtilization(this.state.range, this.window().custom),
    ])
    if (!this.live(epoch)) return
    this.publish({
      ...this.state, overview, topItems: top.items, trend: trend.buckets, utilization,
    })
  }

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
      await this.readAll(epoch)
      if (!this.live(epoch) || run !== this.reads) return
      this.publish({ ...this.state, phase: 'ready' })
    } catch (error) {
      if (!this.live(epoch) || run !== this.reads) return
      this.publish({ ...this.state, phase: 'error', notice: '经营数据读取失败，请稍后重试' })
    }
  }

  editCustomWindow(from: string, to: string) {
    this.publish({ ...this.state, custom: { from, to } })
  }

  /** 周期选择（2026-10-19 扩展含 CUSTOM）：CUSTOM 校验 ≤92 天再读。 */
  async selectRange(range: StatsRange) {
    if (this.state.phase !== 'ready' || this.state.loadingRange || range === this.state.range) return
    if (range === 'CUSTOM' && !validCustomWindow(this.state.custom.from, this.state.custom.to)) {
      this.publish({ ...this.state, notice: '自定义区间最长 92 天' })
      return
    }
    this.publish({ ...this.state, range, loadingRange: true, notice: '' })
    const epoch = this.epoch
    try {
      await this.readAll(epoch)
      if (!this.live(epoch)) return
      this.publish({ ...this.state, loadingRange: false })
    } catch {
      if (this.live(epoch)) this.publish({ ...this.state, loadingRange: false, notice: '该周期数据读取失败，请重试' })
    }
  }

  /** 已选 CUSTOM 区间生效重读（from/to 编辑后调用）。 */
  async applyCustomWindow() {
    if (this.state.phase !== 'ready' || this.state.loadingRange) return
    if (!validCustomWindow(this.state.custom.from, this.state.custom.to)) {
      this.publish({ ...this.state, notice: '自定义区间最长 92 天' })
      return
    }
    if (this.state.range !== 'CUSTOM') {
      await this.selectRange('CUSTOM')
      return
    }
    this.publish({ ...this.state, loadingRange: true, notice: '' })
    const epoch = this.epoch
    try {
      await this.readAll(epoch)
      if (!this.live(epoch)) return
      this.publish({ ...this.state, loadingRange: false })
    } catch {
      if (this.live(epoch)) this.publish({ ...this.state, loadingRange: false, notice: '该周期数据读取失败，请重试' })
    }
  }
}

/** 服务下钻页控制器（70号扩展）：该服务窗口订单明细分页，只读。 */
export class ServiceOrdersController {
  private state: {
    phase: 'idle' | 'loading' | 'entry' | 'error' | 'ready'
    notice: string
    page: ServiceOrdersPageState
    loadingMore: boolean
  }
  private listeners = new Set<() => void>()
  private active = true
  private epoch = 0
  private unsubscribe: () => void
  constructor(private deps: MerchantStorefrontDeps,
    private coordinates: { merchantId: string; storeId: string },
    private serviceId: string, private range: StatsRange,
    private custom?: { from: string; to: string }) {
    this.state = { phase: 'idle', notice: '', page: null, loadingMore: false }
    this.unsubscribe = deps.scope.subscribe(() => { this.epoch++; this.state = { phase: 'idle', notice: '', page: null, loadingMore: false }; this.publish() })
  }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish() { if (!this.active) return; this.state = Object.freeze(this.state); this.listeners.forEach(listener => listener()) }
  private live(epoch: number) { return this.active && epoch === this.epoch }

  hide() { this.active = false }
  dispose() { this.active = false; this.unsubscribe() }

  async load() {
    if (!this.active) return
    this.publishSet({ phase: 'loading', notice: '' })
    const epoch = this.epoch
    try {
      const admission = await this.deps.admission(this.coordinates.merchantId, this.coordinates.storeId)
      if (!this.live(epoch)) return
      const access = ownerAccess(admission, this.coordinates.merchantId, this.coordinates.storeId)
      if (!access.readable) {
        this.publishSet({ phase: 'entry', notice: '' })
        return
      }
      const page = await this.deps.statsServiceOrders(this.serviceId, this.range, 1, this.custom)
      if (!this.live(epoch)) return
      this.publishSet({ phase: 'ready', page })
    } catch (error) {
      if (!this.live(epoch)) return
      const code = (error as { code?: string })?.code
      this.publishSet({
        phase: code === 'COMMON_NOT_FOUND' ? 'entry' : 'error',
        notice: code === 'COMMON_NOT_FOUND' ? '该服务不在本店该周期订单中' : '服务订单明细读取失败，请稍后重试',
      })
    }
  }

  async loadMore() {
    const page = this.state.page
    if (this.state.phase !== 'ready' || !page || this.state.loadingMore) return
    const next = page.page + 1
    if ((next - 1) * page.pageSize >= page.total) return
    this.publishSet({ loadingMore: true })
    const epoch = this.epoch
    try {
      const more = await this.deps.statsServiceOrders(this.serviceId, this.range, next, this.custom)
      if (!this.live(epoch)) return
      this.publishSet({
        loadingMore: false,
        page: { ...more, items: [...page.items, ...more.items] },
      })
    } catch {
      if (this.live(epoch)) this.publishSet({ loadingMore: false, notice: '加载更多失败，请重试' })
    }
  }

  private publishSet(patch: Partial<{ phase: 'idle' | 'loading' | 'entry' | 'error' | 'ready'; notice: string; page: ServiceOrdersPageState; loadingMore: boolean }>) {
    this.state = { ...this.state, ...patch }
    this.publish()
  }
}

type ServiceOrdersPageState = StatsServiceOrdersPage
