import { StaleContextError, WorkspaceScope, type Workspace } from '../shared/workspace'
import type { MerchantAdmission, MerchantMembership } from '../shared/merchant-repositories'
import { ApiError } from '../shared/request'
import type { AdmissionDeps } from './admission'

export type MerchantState = Readonly<{
  status: 'idle' | 'checking' | 'no-stores' | 'choose-store' | 'allowed' | 'limited' | 'denied' | 'error';
  revision: number;
  stores?: readonly MerchantMembership[];
  view?: MerchantAdmission;
  notice?: string;
}>

// One controller per mounted page. All entry paths (including deep links) use enter().
// Store selection lives here: a single accessible store is auto-selected, several stores
// require an explicit user choice, and admission is re-queried on every entry.
export class MerchantWorkspace {
  private state: MerchantState
  private listeners = new Set<() => void>()
  private unsubscribe: () => void
  private active = true
  private run = 0
  private ownedRevision: number | undefined
  // F1 (window E2E 2026-09-24): revision of a workbench-initiated child navigation whose
  // hide-leave donated the coordinates, and the pending handoff itself. See handoffToChild.
  private childHandoff: number | undefined
  private donatedRevision: number | undefined
  constructor(readonly scope: WorkspaceScope) {
    this.state = { status: 'idle', revision: scope.revision }
    // Our own coordinate switches also pass through here; stale guards rely on ticket
    // revision checks instead of a counter so self-driven selection is not cancelled.
    this.unsubscribe = scope.subscribe(() => {
      this.ownedRevision = undefined
      this.publish({ status: 'idle', revision: scope.revision })
    })
  }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
  private publish(next: MerchantState) {
    this.state = Object.freeze(next)
    this.listeners.forEach(listener => listener())
  }
  private settle(error: unknown, run: number, ticket: { assertCurrent(): void }) {
    if (error instanceof StaleContextError || !this.active || run !== this.run) return
    try { ticket.assertCurrent() } catch { return }
    const message = error instanceof ApiError && error.statusCode === 401
      ? '登录已失效，请重新登录。'
      : error instanceof Error && error.message === 'WORKSPACE_PATH_MISMATCH'
        ? '工作区已切换，请重新进入工作台。'
        : '准入查询失败，未放行；请稍后重试。'
    this.publish({ status: 'error', revision: this.scope.revision, notice: message })
  }
  async enter(deps: AdmissionDeps) {
    if (!this.active) return
    const previous = this.scope.current
    // Invalidate any previous admission BEFORE querying; entry always starts on consumer coordinates.
    this.scope.replace(previous ? { ...previous, workspace: 'consumer', merchantId: null, storeId: null } : null)
    this.ownedRevision = this.scope.revision
    if (!previous) {
      this.publish({ status: 'error', revision: this.scope.revision, notice: '请先登录后进入工作台。' })
      return
    }
    const run = ++this.run
    const ticket = this.scope.capture()
    this.publish({ status: 'checking', revision: this.scope.revision })
    try {
      const page = await deps.memberships()
      ticket.assertCurrent()
      if (!this.active || run !== this.run) return
      if (!page.items.length) {
        this.publish({ status: 'no-stores', revision: this.scope.revision })
        return
      }
      if (page.items.length > 1) {
        // Multiple stores: never default to the first; the user must choose explicitly.
        this.publish({ status: 'choose-store', revision: this.scope.revision, stores: [...page.items] })
        return
      }
      const only = page.items[0]!
      await this.select(only.merchantId, only.storeId, deps)
    } catch (error) {
      this.settle(error, run, ticket)
    }
  }
  async select(merchantId: string, storeId: string, deps: AdmissionDeps) {
    if (!this.active) return
    const run = ++this.run
    const current = this.scope.current
    if (!current) {
      this.publish({ status: 'error', revision: this.scope.revision, notice: '请先登录后进入工作台。' })
      return
    }
    this.scope.replace({ userId: current.userId, workspace: 'merchant', merchantId, storeId })
    this.ownedRevision = this.scope.revision
    const ticket = this.scope.capture()
    this.publish({ status: 'checking', revision: this.scope.revision })
    try {
      const view = await deps.admission(ticket.context, merchantId, storeId)
      ticket.assertCurrent()
      if (!this.active || run !== this.run) return
      const status = view.admission === 'ALLOWED' ? 'allowed' : view.admission === 'LIMITED' ? 'limited' : 'denied'
      this.publish({ status, revision: this.scope.revision, view })
    } catch (error) {
      this.settle(error, run, ticket)
    }
  }
  /**
   * F1 fix: call immediately BEFORE a workbench-initiated navigateTo to its own management
   * child pages (M-002 service pages). navigateTo hides this page first, and the child gate
   * reads the same merchant coordinates — so this page's on-hide leave() must donate, not
   * revoke, them. Returning to the workbench re-runs enter() which re-owns and re-checks
   * admission (the MINI-003 every-entry rule is unchanged); only leaving the workbench
   * without entering a child still resets to consumer coordinates.
   */
  handoffToChild() {
    this.childHandoff = this.scope.revision
  }
  /**
   * The handed-off navigation failed without hiding this page (navigateTo reject): reclaim
   * ownership so a later leave() (back to shell, account switch) still resets the coordinates.
   * No-op once the handoff actually fired (leave consumed it).
   */
  handoffCancelled() {
    if (this.childHandoff === undefined) return
    const revision = this.childHandoff
    this.childHandoff = undefined
    // Re-claim only the coordinates this controller still owned when the handoff started;
    // a scope switch in between already cleared ownership for everyone.
    if (this.ownedRevision === undefined && revision === this.scope.revision) {
      this.ownedRevision = revision
    }
  }
  leave() {
    // A hidden or disposing older page must not clear a newer page's admission coordinates
    // (ownedRevision check); F1 donation is the one deliberate exception — the workbench's
    // own child navigation keeps the coordinates its target page gates on.
    const donated = this.childHandoff !== undefined
    const revision = this.childHandoff
    this.childHandoff = undefined
    if (donated) {
      // F1: the child page keeps the admission coordinates; remember the donated revision so
      // dispose() can still reset them if the whole workbench stack goes away (reLaunch).
      this.donatedRevision = revision
    } else if (this.ownedRevision === this.scope.revision && this.scope.current) {
      const current = this.scope.current
      this.scope.replace({ userId: current.userId, workspace: 'consumer', merchantId: null, storeId: null })
    }
    this.ownedRevision = undefined
    this.run++
  }
  dispose() {
    this.active = false
    // F1: a donation outlived the workbench page itself (stack destroyed while a child held
    // the coordinates, e.g. reLaunch home from a service page) — the merchant context is over
    // and consumer personal pages gate on consumer coordinates, so reset them. A newer owner
    // (re-entered workbench, account switch) has bumped the revision and is left untouched.
    if (this.donatedRevision !== undefined && this.donatedRevision === this.scope.revision
      && this.scope.current && this.scope.current.workspace === 'merchant') {
      const current = this.scope.current
      this.scope.replace({ userId: current.userId, workspace: 'consumer', merchantId: null, storeId: null })
    }
    this.donatedRevision = undefined
    this.leave()
    this.unsubscribe()
    this.listeners.clear()
  }
}
