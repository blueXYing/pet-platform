import { ApiError } from '../../shared/request'
import { StaleContextError, type WorkspaceScope } from '../../shared/workspace'
import {
  merchantOrderListAvailability, merchantOrderListMessage,
  type MerchantOrderPage, type MerchantOrderSummary,
} from './model'
import type { MerchantOrderListDeps } from './repository'

export type MerchantOrderListTab = 'PENDING_CONFIRM' | null

export type MerchantOrderListState = Readonly<{
  /** entry = 无商家工作台坐标；closed = 403（开关关/无权/非本店）或 503 —— 整页失败关闭。 */
  status: 'idle' | 'loading' | 'ready' | 'entry' | 'closed' | 'error' | 'load-error'
  items: readonly MerchantOrderSummary[]
  total: number
  page: number
  tab: MerchantOrderListTab
  loadingMore: boolean
  notice: string
}>

const PAGE_SIZE = 20

const initial = (): MerchantOrderListState => ({
  status: 'idle', items: [], total: 0, page: 1, tab: 'PENDING_CONFIRM', loadingMore: false, notice: '',
})

/**
 * One controller per mounted merchant order list page (contract 10 §4.1 supplement). The
 * store scope is the merchant workspace coordinate (merchantId+storeId) re-proven by the
 * server under the store guard on every read; displayStatus stays server-computed — the page
 * never re-derives it. 待接单 tab = displayStatus=PENDING_CONFIRM（30 分钟确认窗口的重点
 * 面板），全部 tab 不发送该参数；分页沿用固定排序 created_at DESC, id DESC 的稳定页。
 * did-show 重读当前桶第一页（从处理页返回后刷新）；403/503 整页失败关闭不可交互。
 */
export class MerchantOrderListController {
  private state: MerchantOrderListState = initial()
  private listeners = new Set<() => void>()
  private active = true
  private run = 0
  private unsubscribe: () => void
  constructor(private deps: MerchantOrderListDeps & { scope: WorkspaceScope }) {
    // A workspace switch (store change / logout) invalidates the whole list immediately.
    this.unsubscribe = deps.scope.subscribe(() => {
      this.run++
      this.state = { ...initial(), status: 'entry', notice: '工作区已切换，请重新进入商家工作台。' }
      this.emit()
    })
  }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private emit() { this.listeners.forEach(listener => listener()) }
  private patch(value: Partial<MerchantOrderListState>) {
    if (this.active) { this.state = Object.freeze({ ...this.state, ...value }); this.emit() }
  }
  private ticket() {
    const current = this.deps.scope.current
    if (!current || current.workspace !== 'merchant' || !current.merchantId || !current.storeId) {
      throw new Error('MERCHANT_ENTRY_REQUIRED')
    }
    return this.deps.scope.capture()
  }

  /** Every did-show reloads the current tab's first page — no cached order facts. */
  async load(tab: MerchantOrderListTab = this.state.tab) {
    if (!this.active) return
    const run = ++this.run
    this.patch({ status: 'loading', items: [], total: 0, page: 1, tab, notice: '', loadingMore: false })
    try {
      const ticket = this.ticket()
      const page: MerchantOrderPage = await this.deps.scope.run(undefined, () => this.deps.list({
        merchantId: ticket.context.merchantId!, storeId: ticket.context.storeId!,
        page: 1, pageSize: PAGE_SIZE, ...(tab ? { displayStatus: tab } : {}),
      }))
      ticket.assertCurrent()
      if (!this.active || run !== this.run) return
      this.patch({ status: 'ready', items: page.items, total: page.total, page: page.page })
    } catch (error) {
      this.fail(error, run)
    }
  }

  async loadMore() {
    if (!this.active || this.state.status !== 'ready' || this.state.loadingMore
      || this.state.items.length >= this.state.total) return
    const run = this.run
    this.patch({ loadingMore: true, notice: '' })
    try {
      const ticket = this.ticket()
      const page: MerchantOrderPage = await this.deps.scope.run(undefined, () => this.deps.list({
        merchantId: ticket.context.merchantId!, storeId: ticket.context.storeId!,
        page: this.state.page + 1, pageSize: PAGE_SIZE, ...(this.state.tab ? { displayStatus: this.state.tab } : {}),
      }))
      ticket.assertCurrent()
      if (!this.active || run !== this.run) return
      const known = new Set(this.state.items.map(item => item.orderId))
      this.patch({
        items: [...this.state.items, ...page.items.filter(item => !known.has(item.orderId))],
        total: page.total, page: page.page, loadingMore: false,
      })
    } catch (error) {
      if (error instanceof StaleContextError) return
      if (error instanceof ApiError && [401, 403].includes(error.statusCode)) { this.fail(error, run); return }
      if (this.active && run === this.run) this.patch({ loadingMore: false, notice: '加载更多失败，请重试。' })
    }
  }

  chooseTab(tab: MerchantOrderListTab) {
    if (tab === this.state.tab || this.state.status === 'loading') return
    void this.load(tab)
  }

  private fail(error: unknown, run: number) {
    if (!this.active || run !== this.run || error instanceof StaleContextError) return
    const entry = error instanceof Error && error.message === 'MERCHANT_ENTRY_REQUIRED'
    const unauthorized = error instanceof ApiError && error.statusCode === 401
    const closed = merchantOrderListAvailability(error) === 'closed'
    this.patch({
      status: entry ? 'entry' : unauthorized ? 'entry' : closed ? 'closed' : 'load-error',
      items: closed ? [] : this.state.items, total: closed ? 0 : this.state.total,
      loadingMore: false, notice: merchantOrderListMessage(error),
    })
  }

  dispose() {
    this.active = false
    this.run++
    this.listeners.clear()
    this.unsubscribe()
  }
}
