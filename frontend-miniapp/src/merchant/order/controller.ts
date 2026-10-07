import { ApiError } from '../../shared/request'
import { StaleContextError } from '../../shared/workspace'
import {
  REJECT_REASON_CODES, internalNoteProblem, isOrderId, orderDecisionAvailability, orderDecisionMessage,
  reasonTextProblem, type OrderDecisionDeps, type OrderDecisionReceipt, type RejectReasonCode,
} from './model'

export type OrderDecisionState = Readonly<{
  /** entry = no merchant workspace (must arrive from the workbench); closed = switch off
   *  (403 未挂路由) or dependency fault (503) — whole-page non-interactive panel. */
  status: 'idle' | 'entry' | 'form' | 'receipt' | 'closed'
  orderId: string
  action: 'confirm' | 'reject'
  reasonCode: RejectReasonCode
  reasonText: string
  internalNote: string
  busy: boolean
  notice: string
  /** Receipt panel fields come verbatim from the contract receipt; nothing is derived. */
  receipt: OrderDecisionReceipt | null
  /** True when this 200 arrived by replaying a journaled X-Request-Id (23号 §5.4): the
   *  receipt then carries the FIRST success's original decision time, which the panel
   *  states instead of implying a second decision happened. */
  replayed: boolean
  /** The journaled unknown-outcome command (crash/lost response) shown at entry. */
  pending: { action: 'confirm' | 'reject'; orderId: string } | null
}>

const initial = (): OrderDecisionState => ({
  status: 'idle', orderId: '', action: 'confirm', reasonCode: 'OTHER', reasonText: '', internalNote: '',
  busy: false, notice: '', receipt: null, replayed: false, pending: null,
})

/**
 * One controller per mounted merchant order-decision page (contract 45, first slice
 * round 0, OWNER main account only — the identity is re-proven server-side on every call
 * and replay). M 端没有商家订单列表读侧，页面按单号进入；接单可附 0~200 码点店内备注
 * （省略与空串幂等不同），拒单必填五类编码之一与 5~200 码点原因文本。提交按
 * merchant-confirm/reject:{merchantId}:{orderId} 槽位记 X-Request-Id；200 建回执面板，
 * 403/503 整页失败关闭；未确认提交以原参数重试并按原请求编号取回首回执。
 */
export class OrderDecisionController {
  private state: OrderDecisionState = initial()
  private listeners = new Set<() => void>()
  private active = true
  private run = 0
  constructor(private deps: OrderDecisionDeps) {}
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish(patch: Partial<OrderDecisionState>) {
    if (!this.active) return
    this.state = Object.freeze({ ...this.state, ...patch })
    this.listeners.forEach(listener => listener())
  }

  entry(notice: string) {
    this.publish({ ...initial(), status: 'entry', notice })
  }

  /** Every did-show: re-read the workspace and the unknown-outcome journal (no cached facts).
   *  A receipt/closed panel survives a re-show — only its journal view refreshes. */
  load(context: { workspace: string; merchantId: string | null; storeId: string | null } | null) {
    if (!this.active) return
    if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) {
      this.entry('请从商家工作台进入订单处理。')
      return
    }
    let pending: { action: 'confirm' | 'reject'; orderId: string } | null = null
    try { pending = this.deps.pending() } catch { pending = null }
    if (this.state.status === 'receipt' || this.state.status === 'closed') {
      this.publish({ pending })
      return
    }
    this.publish({ ...initial(), status: 'form', orderId: this.state.orderId, action: this.state.action,
      reasonCode: this.state.reasonCode, reasonText: this.state.reasonText, internalNote: this.state.internalNote, pending })
  }

  setOrderId(value: string) {
    if (!this.state.busy) this.publish({ orderId: value, notice: '' })
  }
  setAction(value: 'confirm' | 'reject') {
    if (!this.state.busy) this.publish({ action: value, notice: '' })
  }
  setReasonCode(value: string) {
    if (!this.state.busy && (REJECT_REASON_CODES as readonly string[]).includes(value)) {
      this.publish({ reasonCode: value as RejectReasonCode, notice: '' })
    }
  }
  setReasonText(value: string) {
    if (!this.state.busy) this.publish({ reasonText: value, notice: '' })
  }
  setInternalNote(value: string) {
    if (!this.state.busy) this.publish({ internalNote: value, notice: '' })
  }

  formProblems(): string | null {
    if (!isOrderId(this.state.orderId.trim())) return '请输入正确的订单编号（数字）。'
    if (this.state.action === 'reject') {
      const problem = reasonTextProblem(this.state.reasonText)
      if (problem) return problem
    } else {
      const problem = internalNoteProblem(this.state.internalNote)
      if (problem) return problem
    }
    return null
  }

  async submit() {
    if (!this.active || this.state.busy) return
    const problem = this.formProblems()
    if (problem) { this.publish({ notice: problem }); return }
    const run = ++this.run
    const orderId = this.state.orderId.trim()
    const action = this.state.action
    // A journaled command for this exact order+action means this send replays its requestId.
    let pending: { action: 'confirm' | 'reject'; orderId: string } | null = null
    try { pending = this.deps.pending() } catch { pending = null }
    const replayed = !!pending && pending.action === action && pending.orderId === orderId
    this.publish({ busy: true, notice: '', receipt: null, replayed: false, pending })
    try {
      const receipt = action === 'confirm'
        ? await this.deps.confirm(orderId, this.state.internalNote)
        : await this.deps.reject(orderId, this.state.reasonCode, this.state.reasonText)
      if (!this.active || run !== this.run) return
      this.publish({ status: 'receipt', busy: false, receipt, replayed, pending: null, notice: '' })
    } catch (error) {
      if (!this.active || run !== this.run) return
      if (error instanceof StaleContextError) {
        // A 401 mid-command clears the session (the workspace ticket goes stale with it):
        // the outcome stays unknown and journaled; re-entry decides what is still possible.
        this.publish({ status: 'entry', busy: false, receipt: null, replayed: false, notice: '工作区已切换或登录已失效，请重新登录后从工作台进入订单处理。' })
        return
      }
      const closed = orderDecisionAvailability(error) === 'closed'
      this.publish({
        status: closed ? 'closed' : 'form',
        busy: false,
        notice: orderDecisionMessage(error),
      })
    }
  }

  /** From a receipt/closed panel back to the editable form (inputs are kept). */
  reset(notice = '') {
    if (!this.active || this.state.busy) return
    this.publish({ status: 'form', notice, receipt: null, replayed: false })
  }

  dispose() {
    this.active = false
    this.run++
    this.listeners.clear()
  }
}
