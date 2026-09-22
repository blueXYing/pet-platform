import { ApiError } from '../../shared/request'
import { definiteRejection, id } from '../../shared/consumer-api'
import { StaleContextError, WorkspaceScope } from '../../shared/workspace'
import type { Agreement, MerchantAgreementRepository } from '../../shared/merchant-repositories'

export type ConsentPayload = import('../../shared/merchant-repositories').ConsentIntent
export type SigningDependencies = Pick<MerchantAgreementRepository, 'read' | 'consent' | 'pendingConsent' | 'retryConsent' | 'retireConsent'>

export type SigningPhase =
  | 'switching'   // workspace coordinates changed under the page; a fresh enter() is required
  | 'loading'
  | 'load-failed'
  | 'recovering'  // a journaled consent exists; only replaying that command may resolve it
  | 'conflict'    // the server definitively rejected the consent payload; fresh read + new confirmation required
  | 'unsigned'
  | 'signed'
  | 'denied'      // 403/404: not the merchant owner or the resource is unavailable to this principal
  | 'unauthorized'
  | 'invalid-merchant'

export type SigningReceipt = { agreementVersion: string; acceptedAt: string }

export type SigningState = Readonly<{
  phase: SigningPhase
  revision: number
  merchantId: string | null
  agreement: Agreement | null
  pending: ConsentPayload | null
  receipt: SigningReceipt | null
  checked: boolean
  busy: boolean
  notice: string
}>

const empty: Omit<SigningState, 'phase' | 'revision'> = { merchantId: null, agreement: null, pending: null, receipt: null, checked: false, busy: false, notice: '' }

// One controller per mounted page. The route merchantId is only a candidate resource;
// every read and the consent itself are owner-checked by the server.
export class SigningController {
  private state: SigningState
  private listeners = new Set<() => void>()
  private unsubscribe: () => void
  private active = true
  private runs = 0
  private ownedRevision: number | undefined
  constructor(private scope: WorkspaceScope, private deps: SigningDependencies) {
    this.state = { ...empty, phase: 'switching', revision: scope.revision }
    this.unsubscribe = scope.subscribe(() => {
      this.runs++
      this.ownedRevision = undefined
      this.publish({ ...empty, phase: 'switching', revision: scope.revision, notice: '签署工作区已切换，请重新进入签署。' })
    })
  }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
  private publish(next: SigningState) {
    this.state = Object.freeze(next)
    this.listeners.forEach(listener => listener())
  }
  /** Preview mode shows the interaction only; no workspace switch and no transport is touched. */
  enterPreview(merchantParam: string | undefined) {
    try { id(merchantParam) } catch { this.publish({ ...empty, revision: this.scope.revision, phase: 'invalid-merchant', notice: '商家标识无效，请从申请页重新进入。' }); return }
    this.publish({ ...empty, revision: this.scope.revision, phase: 'unsigned', merchantId: id(merchantParam), notice: '交互预览 · 协议内容以服务端为准，不会提交签署。' })
  }
  async enter(merchantParam: string | undefined) {
    if (!this.active) return
    let merchantId: string
    try { merchantId = id(merchantParam) } catch { this.publish({ ...empty, revision: this.scope.revision, phase: 'invalid-merchant', notice: '商家标识无效，请从申请页重新进入。' }); return }
    const current = this.scope.current
    if (!current) { this.publish({ ...empty, revision: this.scope.revision, merchantId, phase: 'unauthorized', notice: '请先登录后继续签署。' }); return }
    if (current.workspace !== 'merchant' || current.merchantId !== merchantId)
      this.scope.replace({ userId: current.userId, workspace: 'merchant', merchantId, storeId: null })
    this.ownedRevision = this.scope.revision
    await this.load(merchantId)
  }
  private async load(merchantId: string) {
    const run = ++this.runs
    const ticket = this.scope.capture()
    this.publish({ ...empty, merchantId, phase: 'loading', revision: this.scope.revision })
    try {
      const pending = this.deps.pendingConsent(merchantId)
      ticket.assertCurrent()
      if (!this.active || run !== this.runs) return
      if (pending) {
        // A business read snapshot never resolves a journaled command; only its own result may.
        this.publish({ ...empty, merchantId, pending, phase: 'recovering', revision: this.scope.revision,
          notice: `上次签署（版本 ${pending.agreementVersion}）结果尚未确认，请重试原操作确认结果。` })
        return
      }
      const agreement = await this.deps.read(merchantId)
      ticket.assertCurrent()
      if (!this.active || run !== this.runs) return
      if (agreement.signingStatus === 'SIGNED') {
        this.publish({ ...empty, merchantId, agreement, phase: 'signed', revision: this.scope.revision,
          receipt: { agreementVersion: agreement.acceptedVersion, acceptedAt: agreement.acceptedAt }, notice: '协议已签署，原签署版本保持有效。' })
        return
      }
      this.publish({ ...empty, merchantId, agreement, phase: 'unsigned', revision: this.scope.revision })
    } catch (error) {
      this.settleReadError(merchantId, error, run, ticket)
    }
  }
  private settleReadError(merchantId: string, error: unknown, run: number, ticket: { assertCurrent(): void }) {
    if (error instanceof StaleContextError || !this.active || run !== this.runs) return
    try { ticket.assertCurrent() } catch { return }
    if (error instanceof ApiError && error.statusCode === 401) {
      this.publish({ ...empty, revision: this.scope.revision, merchantId, phase: 'unauthorized', notice: signingMessage(error) })
      return
    }
    if (error instanceof ApiError && (error.statusCode === 403 || error.statusCode === 404)) {
      this.publish({ ...empty, merchantId, phase: 'denied', revision: this.scope.revision, notice: signingMessage(error) })
      return
    }
    this.publish({ ...empty, merchantId, phase: 'load-failed', revision: this.scope.revision, notice: signingMessage(error) })
  }
  toggle() {
    if (this.state.phase !== 'unsigned' || this.state.busy) return
    this.publish({ ...this.state, checked: !this.state.checked })
  }
  reread() {
    if (this.state.busy || !this.state.merchantId) return
    return this.load(this.state.merchantId)
  }
  async submit() {
    const view = this.state.agreement
    if (!this.active || this.state.busy || this.state.phase !== 'unsigned' || !this.state.checked || !view) return
    const ticket = this.scope.capture()
    const run = ++this.runs
    this.publish({ ...this.state, busy: true, notice: '' })
    try {
      const receipt = await this.deps.consent(view, true)
      ticket.assertCurrent()
      if (!this.active || run !== this.runs) return
      this.publish({ phase: 'signed', revision: this.scope.revision, merchantId: view.merchantId, agreement: view,
        pending: null, receipt: { agreementVersion: receipt.agreementVersion, acceptedAt: receipt.acceptedAt },
        checked: false, busy: false, notice: '协议已签署。' })
    } catch (error) {
      this.settleWriteError(view.merchantId, error, run, ticket)
    }
  }
  async retryPending() {
    if (!this.active || this.state.busy || this.state.phase !== 'recovering' || !this.state.merchantId) return
    const merchantId = this.state.merchantId
    const pending = this.state.pending
    const ticket = this.scope.capture()
    const run = ++this.runs
    this.publish({ ...this.state, busy: true, notice: '' })
    try {
      const receipt = await this.deps.retryConsent(merchantId)
      ticket.assertCurrent()
      if (!this.active || run !== this.runs) return
      this.publish({ phase: 'signed', revision: this.scope.revision, merchantId, agreement: this.state.agreement, pending: null,
        receipt: { agreementVersion: receipt.agreementVersion, acceptedAt: receipt.acceptedAt }, checked: false, busy: false, notice: '协议已签署。' })
    } catch (error) {
      this.settleWriteError(merchantId, error, run, ticket, pending)
    }
  }
  private settleWriteError(merchantId: string, error: unknown, run: number, ticket: { assertCurrent(): void }, pending: ConsentPayload | null = null) {
    if (error instanceof StaleContextError || !this.active || run !== this.runs) return
    try { ticket.assertCurrent() } catch { return }
    const base = { ...empty, merchantId, revision: this.scope.revision, busy: false, checked: false }
    if (error instanceof ApiError && error.statusCode === 409) {
      // The server definitively rejected this exact payload; retire it by command result, then re-read.
      try {
        this.deps.retireConsent(merchantId, pending
          ? { agreementVersion: pending.agreementVersion, contentSha256: pending.contentSha256 }
          : (() => { const view = this.state.agreement; if (!view) throw new Error('PENDING_WRITE_CHANGED'); return { agreementVersion: view.agreementVersion, contentSha256: view.contentSha256 } })())
      } catch (retireError) {
        if (retireError instanceof Error && retireError.message === 'PENDING_WRITE_CHANGED') {
          this.publish({ ...base, phase: 'recovering', notice: '上次签署结果尚未确认，请先重试原操作。' })
          return
        }
        this.publish({ ...base, phase: 'load-failed', notice: signingMessage(retireError) })
        return
      }
      this.publish({ ...base, phase: 'conflict', notice: '协议版本或内容已变化，本次签署未完成；请重新阅读后再次确认。' })
      return
    }
    if (error instanceof ApiError && error.statusCode === 401) { this.publish({ ...base, phase: 'unauthorized', notice: signingMessage(error) }); return }
    if (error instanceof ApiError && (error.statusCode === 403 || error.statusCode === 404)) { this.publish({ ...base, phase: 'denied', notice: signingMessage(error) }); return }
    if (definiteRejection(error)) {
      // The command layer already dropped the journal entry on this definite rejection.
      this.publish({ ...base, phase: 'unsigned', notice: signingMessage(error) })
      return
    }
    // Unknown outcome: the command stays journaled; only replaying it may resolve the intent.
    const unresolved = this.deps.pendingConsent(merchantId)
    if (!this.active || run !== this.runs) return
    this.publish({ ...base, pending: unresolved, phase: 'recovering', notice: '签署结果尚未确认，请重试原操作；不要重复发起。' })
  }
  leave() {
    // A hidden or disposing older page must not clear a newer page's workspace coordinates.
    if (this.ownedRevision === this.scope.revision && this.scope.current) {
      const current = this.scope.current
      this.scope.replace({ userId: current.userId, workspace: 'consumer', merchantId: null, storeId: null })
    }
    this.ownedRevision = undefined
    this.runs++
  }
  dispose() {
    this.active = false
    this.leave()
    this.unsubscribe()
    this.listeners.clear()
  }
}

export function unavailableSigningDependencies(): SigningDependencies {
  const unavailable = async (): Promise<never> => { throw new Error('SIGNING_NOT_CONNECTED') }
  return { read: unavailable, consent: unavailable, pendingConsent: () => { throw new Error('SIGNING_NOT_CONNECTED') }, retryConsent: unavailable, retireConsent: () => { throw new Error('SIGNING_NOT_CONNECTED') } }
}

export function signingMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 409) return '协议版本或内容已变化，本次签署未完成；请重新阅读后再次确认。'
    if (error.statusCode === 401) return '登录已失效，请重新登录。'
    if (error.statusCode === 403 || error.statusCode === 404) return '当前账号无权查看或签署该商家协议。'
    if (error.statusCode === 503) return '服务暂不可用，签署结果尚未确认；请重试原操作，不要重复发起。'
  }
  const message = error instanceof Error ? error.message : ''
  const messages: Record<string, string> = {
    SIGNING_NOT_CONNECTED: '签署服务暂未接通，请稍后重试。',
    NO_PENDING_CONSENT: '没有待确认的签署请求，请重新读取协议。',
    PENDING_WRITE_CHANGED: '上次签署结果尚未确认，请先重试原操作。',
    WORKSPACE_PATH_MISMATCH: '签署工作区已切换，请从申请页重新进入。',
    EXPLICIT_AGREEMENT_REQUIRED: '请先阅读协议并勾选同意后再签署。',
  }
  return messages[message] || '操作未完成，请重试或稍后再试。未确认成功前不会显示已签署。'
}
