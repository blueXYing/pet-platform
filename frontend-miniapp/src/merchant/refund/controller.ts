import { ApiError } from '../../shared/request'
import { StaleContextError, type WorkspaceScope } from '../../shared/workspace'
import { definitiveRefundNoWrite, type RefundApplicationSummary, type RefundApplicationDetail, type RefundDecisionReceipt } from '../../shared/merchant-refund-api'
import { canDecide, emptyRejectDraft, rejectReasonProblem, refundOwnerAccess, type RejectDraft } from './model'
import type { MerchantRefundDeps } from './repository'

export type PendingRefundDecision = { action: 'approve' | 'reject'; applicationId: string; reasonText: string | null }
export type MerchantRefundState = Readonly<{
  status: 'idle' | 'loading' | 'ready' | 'error' | 'entry' | 'denied'
  items: readonly RefundApplicationSummary[]; total: number; page: number
  detail: RefundApplicationDetail | null; writable: boolean; frozen: boolean
  busy: boolean; loadingMore: boolean; notice: string
  draft: RejectDraft; pending: PendingRefundDecision | null
  receipt: RefundDecisionReceipt | null
}>
const initial = (): MerchantRefundState => ({ status: 'idle', items: [], total: 0, page: 1, detail: null,
  writable: false, frozen: false, busy: false, loadingMore: false, notice: '', draft: emptyRejectDraft(), pending: null, receipt: null })

/**
 * Mounted-page controller for the contract-56 merchant refund face (49号 kernel unchanged):
 * no cached admission, no stale merchant/store data, no optimistic facts. Decisions journal
 * per-application X-Request-Id slots (merchant-refund:{merchantId}:{applicationId}:{action});
 * an unresolved write replays its exact journaled payload to recover the first receipt, a
 * definitive 409 proves no commit and retires the slot, and FROZEN workspaces stay read-only.
 */
export class MerchantRefundController {
  private state = initial()
  private active = true
  private run = 0
  private listeners = new Set<() => void>()
  private unsubscribe: () => void
  private entryCoordinates: { merchantId: string; storeId: string } | null = null
  constructor(private deps: MerchantRefundDeps, private scope: WorkspaceScope, private applicationId?: string) {
    this.unsubscribe = scope.subscribe(() => {
      this.run++
      this.entryCoordinates = null
      this.state = { ...initial(), status: 'entry', notice: '工作区已切换，请重新进入商家工作台。' }
      this.emit()
    })
  }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private emit() { this.listeners.forEach(listener => listener()) }
  private patch(value: Partial<MerchantRefundState>) { if (this.active) { this.state = Object.freeze({ ...this.state, ...value }); this.emit() } }
  private coordinates(): { merchantId: string; storeId: string } {
    if (!this.entryCoordinates) throw new Error('MERCHANT_ENTRY_REQUIRED')
    return this.entryCoordinates
  }
  private fail(error: unknown, run: number) {
    if (!this.active || run !== this.run || error instanceof StaleContextError) return
    const denied = error instanceof ApiError && [401, 403].includes(error.statusCode)
    const entry = error instanceof Error && (error.message === 'MERCHANT_ENTRY_REQUIRED' || error.message === 'WORKSPACE_PATH_MISMATCH')
    this.patch({ status: entry ? 'entry' : denied ? 'denied' : 'error', notice: merchantRefundMessage(error), busy: false, loadingMore: false,
      ...(denied || entry ? { items: [], total: 0, detail: null, writable: false, frozen: false, draft: emptyRejectDraft(), pending: null, receipt: null } : {}) })
  }
  /** Admission is re-read per load/submit; an old allowed view is never a cache hit. */
  private async access(run: number) {
    const { merchantId, storeId } = this.coordinates()
    const view = await this.deps.admission(merchantId, storeId)
    if (!this.active || run !== this.run) throw new StaleContextError()
    const access = refundOwnerAccess(view, merchantId, storeId)
    if (!access.readable) throw new ApiError('COMMON_FORBIDDEN', 403)
    this.patch({ writable: access.writable, frozen: access.frozen })
    return access
  }
  /** Every did-show: re-read the workspace and the unknown-outcome journal (no cached facts). */
  async load(context: { workspace: string; merchantId: string | null; storeId: string | null } | null) {
    if (!this.active) return
    if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) {
      this.entryCoordinates = null
      this.patch({ ...initial(), status: 'entry', notice: '请先登录，并从商家工作台选择门店后进入。' })
      return
    }
    this.entryCoordinates = { merchantId: context.merchantId, storeId: context.storeId }
    const run = ++this.run
    this.patch({ status: 'loading', items: [], total: 0, page: 1, detail: null, notice: '', receipt: null,
      writable: false, frozen: false, loadingMore: false })
    try {
      await this.access(run)
      let pending: PendingRefundDecision | null = null
      try { pending = this.deps.pending() } catch { pending = null }
      if (this.applicationId) {
        const detail = await this.deps.detail(this.applicationId)
        if (!this.active || run !== this.run) return
        this.patch({ status: 'ready', detail, pending, draft: this.deps.loadDraft(this.applicationId) || emptyRejectDraft() })
      } else {
        const page = await this.deps.list({ merchantId: context.merchantId, storeId: context.storeId, page: 1, pageSize: 20 })
        if (!this.active || run !== this.run) return
        this.patch({ status: 'ready', items: page.items, total: page.total, page: page.page, pending })
      }
    } catch (error) { this.fail(error, run) }
  }
  async loadMore() {
    if (!this.active || this.applicationId || this.state.status !== 'ready' || this.state.loadingMore
      || this.state.items.length >= this.state.total || this.state.page >= 10000) return
    const run = this.run
    const { merchantId, storeId } = this.coordinates()
    this.patch({ loadingMore: true, notice: '' })
    try {
      await this.access(run)
      const page = await this.deps.list({ merchantId, storeId, page: this.state.page + 1, pageSize: 20 })
      if (!this.active || run !== this.run) return
      const ids = new Set(this.state.items.map(item => item.applicationId))
      this.patch({ items: [...this.state.items, ...page.items.filter(item => !ids.has(item.applicationId))], total: page.total, page: page.page, loadingMore: false })
    } catch (error) {
      if (error instanceof ApiError && [401, 403].includes(error.statusCode)) this.fail(error, run)
      else if (this.active && run === this.run && !(error instanceof StaleContextError)) this.patch({ loadingMore: false, notice: merchantRefundMessage(error) })
    }
  }
  setDraft(patch: Partial<RejectDraft>) {
    if (!this.state.busy && !this.state.pending) {
      const draft = { ...this.state.draft, ...patch }
      if (this.applicationId) this.deps.saveDraft(this.applicationId, draft)
      this.patch({ draft, notice: '' })
    }
  }
  canDecide(now: number): boolean {
    return !!this.state.detail && canDecide(this.state.detail, this.state.writable, now)
  }
  /** Approve (full refund) or reject; a journaled unknown outcome replays its exact payload. */
  async submit(action: 'approve' | 'reject') {
    if (!this.active || this.state.busy || !this.state.detail || !this.applicationId) return
    const run = this.run, applicationId = this.applicationId
    this.patch({ busy: true, notice: '', receipt: null })
    try {
      await this.access(run)
      const pending = this.deps.pending()
      if (pending && pending.applicationId !== applicationId) throw new Error('PENDING_WRITE_CHANGED')
      // An unresolved write may already have committed before a later terminal state; replay
      // its exact input even when a fresh command would now be disallowed.
      if (!pending && !this.canDecide(Date.now())) throw new Error('READ_ONLY')
      let reasonText = this.state.draft.reasonText
      if (pending && pending.action === 'reject') {
        if (pending.reasonText === null) throw new Error('PENDING_WRITE_CHANGED')
        reasonText = pending.reasonText
      }
      if (!pending && action === 'reject') {
        const problem = rejectReasonProblem(reasonText)
        if (problem) { this.patch({ busy: false, notice: problem }); return }
      }
      const receipt = await (action === 'approve'
        ? this.deps.approve(applicationId)
        : this.deps.reject(applicationId, reasonText))
      if (!this.active || run !== this.run) return
      if (action === 'reject' && !pending) this.deps.saveDraft(applicationId, null)
      // A receipt is proof of the write; read the actual current application for display.
      const detail = await this.deps.detail(applicationId)
      if (!this.active || run !== this.run) return
      this.patch({ detail, status: 'ready', busy: false, pending: null, receipt,
        notice: action === 'approve' ? '已同意全额退款，系统将创建退款单并原路退回。' : '已拒绝该退款申请，买家仍可再次申请。' })
    } catch (error) {
      if (!this.active || run !== this.run || error instanceof StaleContextError) return
      if (definitiveRefundNoWrite(error)) {
        this.patch({ busy: false, pending: null, notice: merchantRefundMessage(error) })
        // A definitive conflict proves the state moved: re-read the current application.
        try {
          const detail = await this.deps.detail(applicationId)
          if (this.active && run === this.run) this.patch({ detail, status: 'ready', busy: false })
        } catch (readError) { this.fail(readError, run) }
      } else if (error instanceof ApiError && [401, 403].includes(error.statusCode)) this.fail(error, run)
      else this.patch({ busy: false, notice: merchantRefundMessage(error) })
    }
  }
  showNotice(notice: string) { this.patch({ notice }) }
  clearNotice() { this.patch({ notice: '' }) }
  /** Page refresh: re-read with the current workspace coordinates (no cached facts). */
  async reloadFromScope() { await this.load(this.scope.current) }
  hide() { this.run++; this.patch({ ...initial(), status: 'idle' }) }
  dispose() { this.active = false; this.run++; this.unsubscribe(); this.listeners.clear() }
}

export function merchantRefundMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401) return '登录已失效，请重新登录后进入商家工作台。'
    if (error.statusCode === 403) return '当前身份或权限不允许此操作，请从商家工作台重新进入。'
    if (error.statusCode === 404) return '退款申请不存在或不在当前门店权限范围内。'
    if (error.statusCode === 400) return error.code === 'REFUND_MERCHANT_REASON_REQUIRED' ? '请填写拒绝原因（1至500字）。' : '请求参数不合法，请检查后重试。'
    if (error.statusCode === 409) {
      if (error.code === 'REFUND_MERCHANT_DEADLINE_PASSED') return '24小时处理期限已过，系统已接管并自动全额退款。'
      if (error.code === 'REFUND_APPLICATION_ALREADY_PROCESSED') return '该退款申请已处理，请刷新查看最新状态。'
      if (error.code === 'IDEMPOTENCY_KEY_CONFLICT') return '原请求编号已用于不同内容，请刷新后重试。'
      return '上次提交结果待确认，请按原内容重试。'
    }
    if (error.statusCode === 503) return '退款服务暂不可用，请稍后重试。'
  }
  const code = error instanceof Error ? error.message : ''
  if (code === 'MERCHANT_ENTRY_REQUIRED' || code === 'WORKSPACE_PATH_MISMATCH') return '请先登录，并从商家工作台选择门店后进入。'
  if (code === 'READ_ONLY') return '当前申请只读或处理期限已到，请刷新查看最新状态。'
  if (code === 'REJECT_REASON_INVALID') return '请填写拒绝原因（1至500字）。'
  if (code === 'PENDING_WRITE_CHANGED') return '上次提交内容已变化，请按原内容重试或刷新。'
  if (code === 'INVALID_QUERY' || code === 'INVALID_RESPONSE') return '数据异常，请刷新重试。'
  return '操作结果尚未确认，请重试原操作；提交前请核对最新申请。'
}
