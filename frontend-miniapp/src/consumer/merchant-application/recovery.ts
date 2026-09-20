import { definiteRejection, id, object } from '../../shared/consumer-api'
import { decodeApplicationResult, decodeDraft, decodeVersion, type ApplicationResult, type DraftInput, type MerchantApplicationRepository } from '../../shared/merchant-repositories'
import type { WorkspaceScope } from '../../shared/workspace'

export type ApplicationIntent = {
  stage: 'save' | 'submit' | 'done'; submit: boolean; draft: DraftInput
  applicationId?: string; expectedVersion?: string; revisionId?: string; receipt?: ApplicationResult
}
type JournalRepository = Pick<MerchantApplicationRepository, 'pending' | 'intent' | 'saveIntent' | 'create' | 'save' | 'submit'>
function decodeIntent(value: unknown): ApplicationIntent {
  const v = object(value)
  if (!['save', 'submit', 'done'].includes(v.stage) || typeof v.submit !== 'boolean') throw new Error('INVALID_RESPONSE')
  const intent: ApplicationIntent = { stage: v.stage, submit: v.submit, draft: decodeDraft(v.draft) }
  if (v.applicationId !== undefined) { intent.applicationId = id(v.applicationId); intent.expectedVersion = decodeVersion(v.expectedVersion) }
  if (v.stage === 'submit') { intent.applicationId = id(v.applicationId); intent.expectedVersion = decodeVersion(v.expectedVersion); intent.revisionId = id(v.revisionId) }
  if (v.receipt !== undefined) intent.receipt = decodeApplicationResult(v.receipt)
  if (v.stage === 'done' && !intent.receipt) throw new Error('INVALID_RESPONSE')
  return intent
}
/** A page is disposable; the journal owns the operation, parameters and acknowledged stage. */
export class ApplicationRecovery {
  private flight: Promise<ApplicationResult> | null = null
  lastReceipt: ApplicationResult | null = null
  constructor(private repository: JournalRepository, private scope: WorkspaceScope) {}
  restore(): ApplicationIntent | null {
    if (this.scope.current?.workspace !== 'consumer') return null
    const journal = this.repository.intent()
    if (journal) return decodeIntent(journal)
    // Also recover commands created before the UI journal was introduced. Never GET a newer
    // version and use it to replace the original command's optimistic version or request ID.
    const pending = this.repository.pending()
    if (!pending.length) return null
    if (pending.length !== 1) throw new Error('PENDING_WRITE_CHANGED')
    const { command } = pending[0]
    const data = object(command.data)
    let recovered: ApplicationIntent
    const match = /^\/api\/v1\/c\/merchant-applications\/([1-9][0-9]*)\/(draft|submit)$/.exec(command.path)
    if (command.path === '/api/v1/c/merchant-applications' && command.method === 'POST') recovered = { stage: 'save', submit: false, draft: decodeDraft(data) }
    else if (match && ((match[2] === 'draft' && command.method === 'PUT') || (match[2] === 'submit' && command.method === 'POST'))) {
      recovered = { stage: match[2] === 'submit' ? 'submit' : 'save', submit: match[2] === 'submit', draft: match[2] === 'draft' ? decodeDraft(data.draft) : {}, applicationId: id(match[1]), expectedVersion: decodeVersion(data.expectedVersion), ...(match[2] === 'submit' ? { revisionId: id(data.revisionId) } : {}) }
    } else throw new Error('INVALID_RESPONSE')
    this.repository.saveIntent(recovered)
    return recovered
  }
  begin(current: ApplicationResult | null, draft: DraftInput, submit: boolean) {
    const existing = this.restore()
    if (existing) return existing
    const intent: ApplicationIntent = { stage: 'save', submit, draft: decodeDraft(draft), ...(current ? { applicationId: current.applicationId, expectedVersion: current.version } : {}) }
    this.repository.saveIntent(intent)
    return intent
  }
  /** Closing a dialog is not cancellation of a server write with an unknown result. */
  cancel() {
    if (this.flight || this.repository.pending().length) throw new Error('PENDING_WRITE_CHANGED')
    this.repository.saveIntent(null)
  }
  retry(): Promise<ApplicationResult> {
    if (this.flight) return this.flight
    const ticket = this.scope.capture()
    if (ticket.context.workspace !== 'consumer') return Promise.reject(new Error('WORKSPACE_PATH_MISMATCH'))
    this.flight = (async () => {
      let intent = this.restore()
      if (!intent) throw new Error('NO_PENDING_APPLICATION')
      this.lastReceipt = intent.receipt || null
      try {
        if (intent.stage === 'save') {
          const original = intent
          const checkpoint = { slot: 'merchant-application', value: (receipt: ApplicationResult): ApplicationIntent => ({ ...original, stage: original.submit ? 'submit' : 'done', applicationId: receipt.applicationId, expectedVersion: receipt.version, revisionId: receipt.currentRevisionId, receipt }) }
          if (intent.applicationId) await this.repository.save(intent.applicationId, intent.expectedVersion!, intent.draft, checkpoint)
          else await this.repository.create(intent.draft, checkpoint)
          ticket.assertCurrent(); intent = this.restore()!; this.lastReceipt = intent.receipt || null
        }
        if (intent.stage === 'submit') {
          const original = intent
          await this.repository.submit(intent.applicationId!, intent.expectedVersion!, intent.revisionId!, { slot: 'merchant-application', value: receipt => ({ ...original, stage: 'done', receipt }) })
          ticket.assertCurrent(); intent = this.restore()!
        }
        const receipt = decodeApplicationResult(intent.receipt)
        ticket.assertCurrent(); this.repository.saveIntent(null)
        return receipt
      } catch (error) {
        ticket.assertCurrent()
        if (definiteRejection(error)) this.repository.saveIntent(null)
        throw error
      }
    })().finally(() => { this.flight = null })
    return this.flight
  }
}
