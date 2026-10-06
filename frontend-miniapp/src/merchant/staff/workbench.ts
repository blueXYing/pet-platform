import type { MerchantAdmission } from '../../shared/merchant-repositories'

export type StaffWorkbenchState = Readonly<{
  /** entry = not logged in; unbound = logged in without merchant coordinates (pre-binding
   *  employee who still needs to confirm an invitation, or a staff member outside the
   *  admission flow — both see the invitation-confirm entry only). */
  status: 'idle' | 'entry' | 'unbound' | 'checking' | 'ready' | 'owner' | 'error'
  view: MerchantAdmission | null
  merchantId: string
  storeId: string
  notice: string
}>

export interface StaffWorkbenchDeps {
  admission(merchantId: string, storeId: string): Promise<MerchantAdmission>
}

/**
 * 员工工作台准入判定（2026-10-06 用户裁决：员工确认入口定在商家端小程序的员工工作台）。
 * The workbench is the STAFF landing page, distinct from the OWNER workbench: with merchant
 * coordinates it re-queries admission on every entry (every-entry rule, HTTP10 §4) and only a
 * membershipKind=STAFF projection unlocks the staff organization (verify entry + invitation
 * records). An OWNER projection is redirected back to the owner workbench; admission faults
 * fail closed to a non-interactive panel. Without merchant coordinates the workbench still
 * renders the invitation-confirm entry — the pre-binding employee has no membership to
 * admission-check yet (contract 52 §3 lists only ENABLED member × ENABLED grant rows).
 */
export class StaffWorkbenchController {
  private state: StaffWorkbenchState = { status: 'idle', view: null, merchantId: '', storeId: '', notice: '' }
  private listeners = new Set<() => void>()
  private active = true
  private run = 0
  constructor(private deps: StaffWorkbenchDeps) {}
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
  private publish(patch: Partial<StaffWorkbenchState>) {
    this.state = Object.freeze({ ...this.state, ...patch })
    this.listeners.forEach(listener => listener())
  }

  load(context: { userId: string; workspace: string; merchantId: string | null; storeId: string | null } | null) {
    if (!this.active) return
    if (!context) { this.publish({ status: 'entry', notice: '请先登录后进入员工工作台。', view: null }); return }
    if (context.workspace !== 'merchant' || !context.merchantId || !context.storeId) {
      this.publish({ status: 'unbound', notice: '', view: null, merchantId: '', storeId: '' })
      return
    }
    const run = ++this.run
    this.publish({ status: 'checking', notice: '', view: null, merchantId: context.merchantId, storeId: context.storeId })
    void this.deps.admission(context.merchantId, context.storeId).then(view => {
      if (!this.active || run !== this.run) return
      this.publish(view.membershipKind === 'STAFF'
        ? { status: 'ready', view }
        : { status: 'owner', view, notice: '当前账号是商家主账号，请使用商家工作台。' })
    }).catch(() => {
      if (!this.active || run !== this.run) return
      // Fail closed: admission faults never unlock the staff organization (48 K1 D5 discipline).
      this.publish({ status: 'error', notice: '员工准入查询失败，功能未开放；请从商家工作台重新进入。' })
    })
  }

  dispose() {
    this.active = false
    this.listeners.clear()
  }
}
