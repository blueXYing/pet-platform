import { StaleContextError, WorkspaceScope } from '../shared/workspace'
import { loadEngineeringFixture, type EngineeringFixture } from '../shared/fixture'
import type { AdmissionAdapter } from './admission'

export type MerchantState = Readonly<{
  status: 'idle' | 'checking' | 'allowed' | 'denied' | 'error';
  revision: number; sample?: EngineeringFixture; loading?: boolean;
}>

// One controller per mounted page. All entry paths (including deep links) use enter().
export class MerchantWorkspace {
  private state: MerchantState
  private listeners = new Set<() => void>()
  private unsubscribe: () => void
  private active = true
  private sampleRun = 0
  private ownedRevision: number | undefined
  constructor(readonly scope: WorkspaceScope) {
    this.state = { status: 'idle', revision: scope.revision }
    this.unsubscribe = scope.subscribe(() => {
      this.sampleRun++
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
  async enter(query: AdmissionAdapter) {
    if (!this.active) return
    const previous = this.scope.current
    // Invalidate previous admission, cached samples and pending work BEFORE querying.
    this.scope.replace(previous ? { ...previous, workspace: 'consumer', merchantId: null, storeId: null } : null)
    this.ownedRevision = this.scope.revision
    if (!previous) {
      this.publish({ status: 'error', revision: this.scope.revision })
      return
    }
    const ticket = this.scope.capture()
    this.publish({ status: 'checking', revision: this.scope.revision })
    try {
      const result = await query(ticket.context)
      ticket.assertCurrent()
      if (!this.active) return
      if (!result.allowed) {
        this.publish({ status: 'denied', revision: this.scope.revision })
        return
      }
      this.scope.replace({ userId: ticket.context.userId, workspace: 'merchant',
        merchantId: result.merchantId, storeId: result.storeId })
      this.ownedRevision = this.scope.revision
      this.publish({ status: 'allowed', revision: this.scope.revision })
    } catch (error) {
      if (error instanceof StaleContextError || !this.active) return
      // A rejection from an older query must not replace the current page's status.
      try { ticket.assertCurrent() } catch { return }
      this.publish({ status: 'error', revision: this.scope.revision })
    }
  }
  async loadSample(operation = loadEngineeringFixture) {
    if (!this.active || this.state.status !== 'allowed' || this.scope.current?.workspace !== 'merchant') return
    const revision = this.scope.revision
    const run = ++this.sampleRun
    this.publish({ ...this.state, loading: true, sample: undefined })
    try {
      const sample = await this.scope.run('merchant-engineering', operation)
      if (this.active && revision === this.scope.revision && run === this.sampleRun)
        this.publish({ status: 'allowed', revision, sample })
    } catch {
      if (this.active && revision === this.scope.revision && run === this.sampleRun)
        this.publish({ status: 'allowed', revision, loading: false })
    }
  }
  leave() {
    // A hidden/disposing older page must not clear a newer page's admission/session.
    if (this.ownedRevision === this.scope.revision) {
      const current = this.scope.current
      this.scope.replace(current ? { ...current, workspace: 'consumer', merchantId: null, storeId: null } : null)
    }
    this.ownedRevision = undefined
    this.sampleRun++
    this.publish({ status: 'idle', revision: this.scope.revision })
  }
  dispose() {
    this.active = false
    this.leave()
    this.unsubscribe()
    this.listeners.clear()
  }
}
