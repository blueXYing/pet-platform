import {
  staffInvitationAvailability, staffInvitationMessage,
  type StaffConfirmReceipt, type StaffInvitationDeps, type StaffInvitationDetail,
} from './model'

export type StaffInvitationState = Readonly<{
  status: 'idle' | 'loading' | 'ready' | 'load-error' | 'entry'
  invitationId: string
  detail: StaffInvitationDetail | null
  receipt: StaffConfirmReceipt | null
  busy: boolean
  notice: string
  /** True when the initial read failed as switch-off (404) / dependency fault (503): the page
   *  renders the whole-area non-interactive 功能未开放 panel instead of any editable form. */
  closed: boolean
}>

/**
 * One controller per mounted employee-invitation page (contract 54 §4 employee channel).
 * The page reads a single invitation by id and confirms it; stale responses are dropped via
 * run counters. A successful confirm keeps the receipt for the success panel and folds the
 * local detail to CONFIRMED — the server projection is authoritative on every later load.
 */
export class StaffInvitationController {
  private state: StaffInvitationState = {
    status: 'idle', invitationId: '', detail: null, receipt: null, busy: false, notice: '', closed: false,
  }
  private listeners = new Set<() => void>()
  private active = true
  private run = 0
  constructor(private deps: StaffInvitationDeps) {}
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
  private publish(patch: Partial<StaffInvitationState>) {
    this.state = Object.freeze({ ...this.state, ...patch })
    this.listeners.forEach(listener => listener())
  }
  entry(notice: string) { this.publish({ status: 'entry', notice, closed: false }) }

  async load(invitationId: string) {
    if (!this.active) return
    const run = ++this.run
    this.publish({ status: 'loading', invitationId, detail: null, receipt: null, notice: '', closed: false })
    try {
      const detail = await this.deps.read(invitationId)
      if (!this.active || run !== this.run) return
      this.publish({ status: 'ready', invitationId, detail })
    } catch (error) {
      if (!this.active || run !== this.run) return
      this.publish({
        status: 'load-error', invitationId,
        notice: staffInvitationMessage(error), closed: staffInvitationAvailability(error) === 'closed',
      })
    }
  }

  async confirm(slot: string): Promise<boolean> {
    const detail = this.state.detail
    if (this.state.busy || !detail || detail.status !== 'INVITED') return false
    this.publish({ busy: true, notice: '' })
    try {
      const receipt = await this.deps.confirm(slot, detail.invitationId)
      // CONFIRMED is terminal (54 §2); the local fold only reflects the server receipt.
      this.publish({
        busy: false, receipt,
        detail: { ...detail, status: 'CONFIRMED' },
        notice: receipt.replayed
          ? '该邀请此前已确认成功，本次为同一请求的重复回执。'
          : '确认成功，您已成为该门店的核销员工。',
      })
      return true
    } catch (error) {
      this.publish({ busy: false, notice: staffInvitationMessage(error) })
      return false
    }
  }

  dispose() {
    this.active = false
    this.listeners.clear()
  }
}
