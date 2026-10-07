import { ApiError } from '../../shared/request'
import { StaleContextError } from '../../shared/workspace'
import {
  codeProblem, isOrderId, normalizeCode, verificationAvailability, verificationMessage,
  type VerificationDeps, type VerificationReceipt,
} from './model'

export type VerificationState = Readonly<{
  /** entry = no merchant workspace (must arrive from a workbench); closed = switch off
   *  (403/未挂路由) or dependency fault (503) — whole-page non-interactive panel. */
  status: 'idle' | 'entry' | 'form' | 'receipt' | 'already' | 'closed'
  orderId: string
  code: string
  busy: boolean
  notice: string
  /** Receipt panel fields come verbatim from the contract receipt; nothing is derived. */
  receipt: VerificationReceipt | null
  /** True when this 200 arrived by replaying a journaled X-Request-Id (23号 §5.4): the
   *  receipt then carries the FIRST success's original time and version, which the panel
   *  states instead of implying a second verification happened. */
  replayed: boolean
  /** The journaled unknown-outcome command (crash/lost response) shown at entry. */
  pending: { orderId: string; verificationCode: string } | null
}>

const initial = (): VerificationState => ({
  status: 'idle', orderId: '', code: '', busy: false, notice: '', receipt: null, replayed: false, pending: null,
})

/**
 * One controller per mounted verification page (contract 48 K2 v0.3). OWNER and STAFF share
 * the same submit surface — the identity is resolved server-side by the K1 v0.2 chain, so
 * the page never branches on it. Submissions journal per-order request ids through the
 * repository; a 200 builds the receipt panel (VERIFIED, or a committed negative business
 * result), VERIFICATION_ALREADY_DONE gets its own already-verified panel, and switch-off /
 * dependency faults fail the whole page closed per the invitation-page discipline.
 */
export class VerificationController {
  private state: VerificationState = initial()
  private listeners = new Set<() => void>()
  private active = true
  private run = 0
  constructor(private deps: VerificationDeps) {}
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish(patch: Partial<VerificationState>) {
    if (!this.active) return
    this.state = Object.freeze({ ...this.state, ...patch })
    this.listeners.forEach(listener => listener())
  }

  entry(notice: string) {
    this.publish({ ...initial(), status: 'entry', notice })
  }

  /** Every did-show: re-read the workspace and the unknown-outcome journal (no cached facts).
   *  A receipt/already/closed panel survives a re-show — only its journal view refreshes. */
  load(context: { workspace: string; merchantId: string | null; storeId: string | null } | null) {
    if (!this.active) return
    if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) {
      this.entry('请从商家工作台或员工工作台进入核销。')
      return
    }
    let pending: { orderId: string; verificationCode: string } | null = null
    try { pending = this.deps.pending() } catch { pending = null }
    if (this.state.status === 'receipt' || this.state.status === 'already' || this.state.status === 'closed') {
      this.publish({ pending })
      return
    }
    this.publish({ ...initial(), status: 'form', orderId: this.state.orderId, code: this.state.code, pending })
  }

  setOrderId(value: string) {
    if (!this.state.busy) this.publish({ orderId: value, notice: '' })
  }

  setCode(value: string) {
    if (!this.state.busy) this.publish({ code: value, notice: '' })
  }

  formProblems(): string | null {
    if (!isOrderId(this.state.orderId.trim())) return '请输入正确的订单编号（数字）。'
    return codeProblem(normalizeCode(this.state.code))
  }

  async submit() {
    if (!this.active || this.state.busy) return
    const problem = this.formProblems()
    if (problem) { this.publish({ notice: problem }); return }
    const run = ++this.run
    const orderId = this.state.orderId.trim()
    const code = normalizeCode(this.state.code)
    // A journaled command for this exact payload means this send replays its requestId.
    let pending: { orderId: string; verificationCode: string } | null = null
    try { pending = this.deps.pending() } catch { pending = null }
    const replayed = !!pending && pending.orderId === orderId && pending.verificationCode === code
    this.publish({ busy: true, notice: '', receipt: null, replayed: false, pending })
    try {
      const receipt = await this.deps.verify(orderId, code)
      if (!this.active || run !== this.run) return
      this.publish({ status: 'receipt', busy: false, receipt, replayed, pending: null, notice: '' })
    } catch (error) {
      if (!this.active || run !== this.run) return
      if (error instanceof StaleContextError) {
        // A 401 mid-command clears the session (the workspace ticket goes stale with it):
        // the outcome stays unknown and journaled; re-entry decides what is still possible.
        this.publish({ status: 'entry', busy: false, receipt: null, replayed: false, notice: '工作区已切换或登录已失效，请重新登录后从工作台进入核销。' })
        return
      }
      if (error instanceof ApiError && error.statusCode === 409 && error.code === 'VERIFICATION_ALREADY_DONE') {
        // Another key already verified this order: terminal already-done panel, not a form error.
        this.publish({ status: 'already', busy: false, receipt: null, notice: '' })
        return
      }
      const closed = verificationAvailability(error) === 'closed'
      this.publish({
        status: closed ? 'closed' : 'form',
        busy: false,
        notice: verificationMessage(error),
      })
    }
  }

  /** From a receipt/already/closed panel back to the editable form (inputs are kept). */
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
