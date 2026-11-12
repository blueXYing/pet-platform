import { ApiError } from '../../shared/request'
import { StaleContextError, type WorkspaceScope } from '../../shared/workspace'
import {
  verificationRecordListAvailability, verificationRecordListMessage,
  type VerificationRecordItem,
} from './model'
import type { VerificationRecordListDeps } from './repository'

export type VerificationRecordListState = Readonly<{
  /** entry = 无商家工作台坐标、登录失效（401）或非 OWNER（403，CCR §9.3 仅店长可见，
   *  产品口径引导去工作台而非失败关闭）；closed = 503 依赖故障——整页失败关闭。 */
  status: 'idle' | 'loading' | 'ready' | 'entry' | 'closed' | 'load-error'
  items: readonly VerificationRecordItem[]
  total: number
  page: number
  loadingMore: boolean
  notice: string
}>

const PAGE_SIZE = 20

const initial = (): VerificationRecordListState => ({
  status: 'idle', items: [], total: 0, page: 1, loadingMore: false, notice: '',
})

/**
 * One controller per mounted verification-records page (CCR-M-VERIFY-RECORD-001 §3).
 * The store scope is the merchant workspace coordinate (merchantId+storeId) re-proven by
 * the server under the store guard + OWNER re-check on every read（与订单列表读侧同款）。
 * 记录只有已核销事实一种桶（verification_record），无 tab/过滤参数；分页沿服务端固定
 * 排序 verified_at DESC, id DESC 的稳定页，loadMore 按 verificationId 去重。
 * did-show 重读第一页（员工工作台完成核销返回后刷新出新记录，CCR §6.3）；403 映射
 * entry（仅商家店长可查看核销记录——本页与员工共用核销 tab，STAFF 落此页时按引导而非
 * 不可用收口）；503 整页失败关闭不可交互。
 */
export class VerificationRecordListController {
  private state: VerificationRecordListState = initial()
  private listeners = new Set<() => void>()
  private active = true
  private run = 0
  private unsubscribe: () => void
  constructor(private deps: VerificationRecordListDeps & { scope: WorkspaceScope }) {
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
  private patch(value: Partial<VerificationRecordListState>) {
    if (this.active) { this.state = Object.freeze({ ...this.state, ...value }); this.emit() }
  }
  private ticket() {
    const current = this.deps.scope.current
    if (!current || current.workspace !== 'merchant' || !current.merchantId || !current.storeId) {
      throw new Error('MERCHANT_ENTRY_REQUIRED')
    }
    return this.deps.scope.capture()
  }

  /** Every did-show reloads the first page — no cached record facts. */
  async load() {
    if (!this.active) return
    const run = ++this.run
    this.patch({ status: 'loading', items: [], total: 0, page: 1, loadingMore: false, notice: '' })
    try {
      const ticket = this.ticket()
      const page = await this.deps.scope.run(undefined, () => this.deps.list({
        merchantId: ticket.context.merchantId!, storeId: ticket.context.storeId!,
        page: 1, pageSize: PAGE_SIZE,
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
      const page = await this.deps.scope.run(undefined, () => this.deps.list({
        merchantId: ticket.context.merchantId!, storeId: ticket.context.storeId!,
        page: this.state.page + 1, pageSize: PAGE_SIZE,
      }))
      ticket.assertCurrent()
      if (!this.active || run !== this.run) return
      const known = new Set(this.state.items.map(item => item.verificationId))
      this.patch({
        items: [...this.state.items, ...page.items.filter(item => !known.has(item.verificationId))],
        total: page.total, page: page.page, loadingMore: false,
      })
    } catch (error) {
      if (error instanceof StaleContextError) return
      if (error instanceof ApiError && [401, 403].includes(error.statusCode)) { this.fail(error, run); return }
      if (this.active && run === this.run) this.patch({ loadingMore: false, notice: '加载更多失败，请重试。' })
    }
  }

  private fail(error: unknown, run: number) {
    if (!this.active || run !== this.run || error instanceof StaleContextError) return
    const entry = error instanceof Error && error.message === 'MERCHANT_ENTRY_REQUIRED'
    // 401 (re-login) and 403 (非 OWNER/通道未开——不可区分，产品口径按仅店长可见引导) both
    // re-entry instead of failing closed: this tab is shared with staff, whose expected
    // landing here is guidance, not an unavailable panel (任务 §2 entry 态).
    const unauthorized = error instanceof ApiError && error.statusCode === 401
    const forbidden = error instanceof ApiError && error.statusCode === 403
    const closed = verificationRecordListAvailability(error) === 'closed'
    this.patch({
      status: entry || unauthorized || forbidden ? 'entry' : closed ? 'closed' : 'load-error',
      items: [], total: 0, loadingMore: false, notice: verificationRecordListMessage(error),
    })
  }

  dispose() {
    this.active = false
    this.run++
    this.listeners.clear()
    this.unsubscribe()
  }
}
