import { ApiError } from '../../shared/request'
import type { WorkspaceScope } from '../../shared/workspace'
import type { ReviewSummary } from '../../shared/review-appeal-api'
import { appealMessage, canAppealEntry, ownerAccess, reasonDraftError } from './model'
import type { MerchantReviewDeps } from './repository'

export type MerchantReviewState = Readonly<{
  phase: 'idle' | 'loading' | 'entry' | 'denied' | 'unauthorized' | 'error' | 'ready'
  notice: string
  busy: boolean
  frozen: boolean
  writable: boolean
  page: number
  pageSize: number
  total: number
  items: readonly ReviewSummary[]
  loadingMore: boolean
  /** 申诉弹层：目标评价与其未确认命令（仅提示 + 重试，不自动发送）。 */
  sheetReviewId: string | null
  sheetPending: { reason: string } | null
  receiptReviewId: string | null
}>

const initialState = (): MerchantReviewState => ({
  phase: 'idle', notice: '', busy: false, frozen: false, writable: false,
  page: 1, pageSize: 20, total: 0, items: [], loadingMore: false,
  sheetReviewId: null, sheetPending: null, receiptReviewId: null,
})

/** M 端评价管理页控制器（56号 M 面）：列表读取 + 一个主体写操作（一次性申诉）。 */
export class MerchantReviewController {
  private state = initialState()
  private listeners = new Set<() => void>()
  private active = true
  private epoch = 0
  private reads = 0
  private unsubscribe: () => void
  constructor(private deps: MerchantReviewDeps, private scope: WorkspaceScope,
    private coordinates: { merchantId: string; storeId: string }) {
    this.unsubscribe = scope.subscribe(() => { this.epoch++; this.publish(initialState()) })
  }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish(state: MerchantReviewState) { if (!this.active) return; this.state = Object.freeze(state); this.listeners.forEach(listener => listener()) }
  private live(epoch: number) { return this.active && epoch === this.epoch }

  /** 首屏：OWNER 准入 → 本店评价分页（含申诉状态投影）。 */
  async load() {
    if (!this.active || this.state.busy) return
    const epoch = this.epoch, run = ++this.reads
    this.publish({ ...this.state, phase: 'loading', notice: '' })
    try {
      const view = await this.deps.admission(this.coordinates.merchantId, this.coordinates.storeId)
      if (!this.live(epoch) || run !== this.reads) return
      const access = ownerAccess(view, this.coordinates.merchantId, this.coordinates.storeId)
      if (!access.readable) {
        this.publish({ ...this.state, phase: 'entry', notice: '', frozen: access.frozen })
        return
      }
      const page = await this.deps.list(this.coordinates.merchantId, this.coordinates.storeId, 1, this.state.pageSize)
      if (!this.live(epoch) || run !== this.reads) return
      this.publish({ ...this.state, phase: 'ready', notice: '', writable: access.writable, frozen: access.frozen,
        page: page.page, total: page.total, items: page.items })
    } catch (error) {
      if (!this.live(epoch) || run !== this.reads) return
      const unauthorized = error instanceof ApiError && error.statusCode === 401
      this.publish({ ...this.state, phase: unauthorized ? 'unauthorized' : 'error', notice: appealMessage(error), items: [] })
    }
  }

  async loadMore() {
    if (!this.active || this.state.phase !== 'ready' || this.state.loadingMore) return
    if (this.state.items.length >= this.state.total) return
    const epoch = this.epoch, run = ++this.reads
    this.publish({ ...this.state, loadingMore: true })
    try {
      const next = this.state.page + 1
      const page = await this.deps.list(this.coordinates.merchantId, this.coordinates.storeId, next, this.state.pageSize)
      if (!this.live(epoch) || run !== this.reads) return
      const known = new Set(this.state.items.map(item => item.reviewId))
      this.publish({ ...this.state, loadingMore: false, page: page.page, total: page.total,
        items: [...this.state.items, ...page.items.filter(item => !known.has(item.reviewId))] })
    } catch (error) {
      if (!this.live(epoch) || run !== this.reads) return
      this.publish({ ...this.state, loadingMore: false, notice: appealMessage(error) })
    }
  }

  /** 打开申诉弹层：恢复未确认命令或本地草稿（仅提示，不自动发送）。 */
  openSheet(reviewId: string) {
    if (!this.active || this.state.busy || this.state.sheetReviewId !== null) return
    const review = this.state.items.find(item => item.reviewId === reviewId)
    if (!review || !canAppealEntry(review, this.state.writable)) return
    const pending = this.deps.pendingAppeal(reviewId)
    this.publish({ ...this.state, sheetReviewId: reviewId, sheetPending: pending,
      notice: pending ? '已恢复上次未确认的申诉，请重试原操作' : '' })
  }
  closeSheet() {
    if (!this.active || this.state.busy) return
    this.publish({ ...this.state, sheetReviewId: null, sheetPending: null, notice: '' })
  }
  saveDraft(reviewId: string, reason: string) {
    if (!this.active || this.state.sheetReviewId !== reviewId) return
    this.deps.saveDraft(reviewId, reason)
  }
  draft(reviewId: string): string {
    return this.state.sheetPending ? this.state.sheetPending.reason : this.deps.loadDraft(reviewId)
  }

  /** 提交一次性申诉；有未确认命令时只能重试原操作（同 X-Request-Id、同理由）。 */
  async submit(reviewId: string, reason: string) {
    if (!this.active || this.state.busy || this.state.sheetReviewId !== reviewId) return
    if (this.state.sheetPending) return this.retry(reviewId)
    const error = reasonDraftError(reason)
    if (error !== null) { this.publish({ ...this.state, notice: error }); return }
    await this.run(reviewId, reason.trim())
  }
  async retry(reviewId: string) {
    const pending = this.state.sheetPending
    if (!pending || !this.active || this.state.busy) return
    await this.run(reviewId, pending.reason)
  }
  private async run(reviewId: string, reason: string) {
    const epoch = this.epoch
    this.reads++
    this.publish({ ...this.state, busy: true, notice: '' })
    try {
      await this.deps.appeal(reviewId, reason)
      if (!this.live(epoch)) return
      this.deps.clearDraft(reviewId)
      this.publish({ ...this.state, busy: false, sheetReviewId: null, sheetPending: null, receiptReviewId: reviewId, notice: '' })
      await this.load()
      if (this.live(epoch)) this.publish({ ...this.getSnapshot(), receiptReviewId: reviewId })
    } catch (error) {
      if (!this.live(epoch)) return
      // 终局 409（一次性申诉已用/状态冲突）可退幂等槽后重读；未知结果保留原命令重试原操作。
      let rejected = false
      try { this.deps.retireConflict(reviewId, error); rejected = true } catch { rejected = false }
      const unauthorized = error instanceof ApiError && error.statusCode === 401
      this.publish({ ...this.state, busy: false,
        sheetPending: rejected || unauthorized ? null : this.deps.pendingAppeal(reviewId),
        ...(unauthorized ? { phase: 'unauthorized' as const, items: [] } : {}),
        notice: appealMessage(error) })
      if (rejected && this.live(epoch)) {
        await this.load()
        if (this.live(epoch)) this.publish({ ...this.getSnapshot(), notice: appealMessage(error) })
      }
    }
  }

  hide() { this.publish({ ...this.state, notice: '' }) }
  dismissReceipt() { if (!this.active) return; this.publish({ ...this.state, receiptReviewId: null }) }
  dispose() { this.active = false; this.epoch++; this.unsubscribe(); this.listeners.clear() }
}
