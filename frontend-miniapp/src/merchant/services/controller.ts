import { ApiError } from '../../shared/request'
import type { ManagedServiceItem, ServiceManageDeps } from './model'

export type ServiceListState = Readonly<{
  // entry = real mode without merchant workspace coordinates (deep link / logged out);
  // expired = the coordinates were switched away mid-flight.
  status: 'idle' | 'loading' | 'ready' | 'load-error' | 'entry' | 'expired'
  items: readonly ManagedServiceItem[]
  total: number
  page: number
  loadingMore: boolean
  notice: string
}>

/** One controller per mounted list page; stale responses are dropped via run counters. */
export class ServiceListController {
  private state: ServiceListState = { status: 'idle', items: [], total: 0, page: 1, loadingMore: false, notice: '' }
  private listeners = new Set<() => void>()
  private active = true
  private run = 0
  constructor(private deps: ServiceManageDeps) {}
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
  private publish(patch: Partial<ServiceListState>) {
    this.state = Object.freeze({ ...this.state, ...patch })
    this.listeners.forEach(listener => listener())
  }
  entry() { this.publish({ status: 'entry', notice: '请从商家工作台进入服务管理。' }) }
  async load() {
    if (!this.active) return
    const run = ++this.run
    this.publish({ status: 'loading', items: [], total: 0, page: 1, notice: '' })
    try {
      const page = await this.deps.list(1, 20)
      if (!this.active || run !== this.run) return
      this.publish({ status: 'ready', items: page.items, total: page.total, page: 1 })
    } catch (error) {
      if (!this.active || run !== this.run) return
      this.state = Object.freeze({ ...this.state, status: 'load-error', notice: noticeFor(error) })
      this.listeners.forEach(listener => listener())
    }
  }
  async loadMore() {
    if (!this.active || this.state.status !== 'ready' || this.state.loadingMore) return
    if (this.state.items.length >= this.state.total) return
    const run = this.run
    this.publish({ loadingMore: true })
    try {
      const next = await this.deps.list(this.state.page + 1, 20)
      if (!this.active || run !== this.run) return
      const known = new Set(this.state.items.map(item => item.serviceId))
      const merged = [...this.state.items, ...next.items.filter(item => !known.has(item.serviceId))]
      this.publish({ items: merged, page: next.page, total: next.total, loadingMore: false })
    } catch (error) {
      if (!this.active || run !== this.run) return
      this.publish({ loadingMore: false, notice: noticeFor(error) })
    }
  }
  /** Offline from the list toggle (ACTIVE only); the page layer shows the required confirm. */
  async applyReceipt(patch: Partial<ServiceListState>) { this.publish(patch) }
  dispose() { this.active = false; this.run++; this.listeners.clear() }
}

export function noticeFor(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401) return '登录已失效，请重新进入工作台。'
    if (error.statusCode === 404) return '服务不存在或已删除，请刷新列表。'
    if (error.statusCode === 503) return '服务暂不可用，请稍后重试。'
  }
  return '加载失败，请重试。'
}
