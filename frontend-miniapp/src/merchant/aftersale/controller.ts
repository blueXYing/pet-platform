import { ApiError } from '../../shared/request'
import { WorkspaceScope, StaleContextError } from '../../shared/workspace'
import type { MerchantAdmission } from '../../shared/merchant-repositories'
import type { AfterSaleStatus, CaseSummary, CaseDetail, CasePage, EvidenceInput, OpinionInput, CommandReceipt } from '../../shared/aftersale-api'
import { isDefiniteAfterSaleConflict } from '../../shared/aftersale-api'
import type { PrivateAssetReceipt } from '../../shared/consumer-api'
import type { UploadFiles } from '../../shared/private-asset-upload'
import { canReply, emptyReply, ownerAccess, replyInput, type ReplyDraft } from './model'

export type PendingReply = { action: 'opinion'; input: OpinionInput } | { action: 'evidence'; input: EvidenceInput }
export type UploadAttempt = { filePath: string; requestId: string; sha256: string; bytes: number; attempted: boolean; receipt?: PrivateAssetReceipt }
export type MerchantAfterSaleDeps = {
  scope: WorkspaceScope
  admission(merchantId: string, storeId: string): Promise<MerchantAdmission>
  list(query: { page: number; pageSize: number; status?: AfterSaleStatus; merchantId: string; storeId: string }): Promise<CasePage>
  detail(caseId: string): Promise<CaseDetail>
  opinion(caseId: string, input: OpinionInput): Promise<CommandReceipt>
  evidence(caseId: string, input: EvidenceInput): Promise<CommandReceipt>
  pending(caseId: string): PendingReply | null
  retireConflict(caseId: string, action: 'opinion' | 'evidence', error: unknown): boolean
  loadDraft(caseId: string): ReplyDraft | null
  saveDraft(caseId: string, draft: ReplyDraft | null): void
  readEvidence(caseId: string, batchId: string, assetId: string, reason: string): Promise<string>
  clearImages(): void
  uuid(): Promise<string>
  upload(filePath: string, requestId: string): Promise<PrivateAssetReceipt>
  files: Pick<UploadFiles, 'save' | 'inspect' | 'owns' | 'remove'>
  uploadAttempt(caseId: string): UploadAttempt | null
  saveUploadAttempt(caseId: string, attempt: UploadAttempt | null): void
  now(): number
}
export type MerchantAfterSaleState = Readonly<{
  status: 'idle' | 'loading' | 'ready' | 'error' | 'entry' | 'denied'
  items: readonly CaseSummary[]; total: number; page: number; filter?: AfterSaleStatus
  detail: CaseDetail | null; writable: boolean; frozen: boolean; busy: boolean; loadingMore: boolean
  notice: string; draft: ReplyDraft; pending: PendingReply | null; uploadPending: boolean
  image: string | null
}>
const initial = (): MerchantAfterSaleState => ({ status: 'idle', items: [], total: 0, page: 1, detail: null,
  writable: false, frozen: false, busy: false, loadingMore: false, notice: '', draft: emptyReply(), pending: null, uploadPending: false, image: null })

/** Mounted-page controller: no cached admission, no stale merchant/store data, no optimistic facts. */
export class MerchantAfterSaleController {
  private state = initial()
  private active = true
  private run = 0
  private listeners = new Set<() => void>()
  private unsubscribe: () => void
  constructor(private deps: MerchantAfterSaleDeps, private caseId?: string) {
    this.unsubscribe = deps.scope.subscribe(() => {
      this.run++; deps.clearImages(); this.state = { ...initial(), status: 'entry', notice: '工作区已切换，请重新进入商家工作台。' }; this.emit()
    })
  }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private emit() { this.listeners.forEach(listener => listener()) }
  private patch(value: Partial<MerchantAfterSaleState>) { if (this.active) { this.state = Object.freeze({ ...this.state, ...value }); this.emit() } }
  private ticket() {
    const current = this.deps.scope.current
    if (!current || current.workspace !== 'merchant' || !current.merchantId || !current.storeId) throw new Error('MERCHANT_ENTRY_REQUIRED')
    return this.deps.scope.capture()
  }
  private current(ticket: ReturnType<WorkspaceScope['capture']>, run: number) {
    ticket.assertCurrent(); if (!this.active || run !== this.run) throw new StaleContextError()
  }
  private async access(ticket: ReturnType<WorkspaceScope['capture']>, run: number) {
    const merchantId = ticket.context.merchantId!, storeId = ticket.context.storeId!
    const view = await this.deps.admission(merchantId, storeId); this.current(ticket, run)
    const access = ownerAccess(view, merchantId, storeId)
    if (!access.readable) throw new ApiError('COMMON_FORBIDDEN', 403)
    this.patch({ writable: access.writable, frozen: access.frozen })
    return access
  }
  private fail(error: unknown, run: number) {
    if (!this.active || run !== this.run || error instanceof StaleContextError) return
    const denied = error instanceof ApiError && [401, 403].includes(error.statusCode)
    const entry = error instanceof Error && error.message === 'MERCHANT_ENTRY_REQUIRED'
    this.patch({ status: entry ? 'entry' : denied ? 'denied' : 'error', notice: merchantAfterSaleMessage(error), busy: false, loadingMore: false,
      ...(denied || entry ? { items: [], total: 0, detail: null, writable: false, draft: emptyReply(), pending: null, image: null } : {}) })
    if (denied || entry) this.deps.clearImages()
  }
  async load(filter: AfterSaleStatus | null | undefined = this.state.filter) {
    if (!this.active || this.state.busy) return
    const run = ++this.run
    this.deps.clearImages()
    const selected = filter ?? undefined
    this.patch({ status: 'loading', items: [], total: 0, page: 1, detail: null, filter: selected, notice: '', image: null, writable: false, loadingMore: false, pending: null })
    try {
      const ticket = this.ticket(); await this.access(ticket, run)
      if (this.caseId) {
        const detail = await this.deps.detail(this.caseId); this.current(ticket, run)
        const pending = this.deps.pending(this.caseId)
        this.patch({ status: 'ready', detail, pending, uploadPending: !!this.deps.uploadAttempt(this.caseId),
          draft: pending ? { opinionCode: pending.action === 'opinion' ? pending.input.opinionCode : '', text: pending.action === 'opinion' ? pending.input.explanation : pending.input.text || '', assetIds: pending.input.evidenceAssetIds }
            : this.deps.loadDraft(this.caseId) || emptyReply() })
      } else {
        const page = await this.deps.list({ merchantId: ticket.context.merchantId!, storeId: ticket.context.storeId!, page: 1, pageSize: 20, ...(selected ? { status: selected } : {}) })
        this.current(ticket, run); this.patch({ status: 'ready', items: page.items, total: page.total, page: page.page })
      }
    } catch (error) { this.fail(error, run) }
  }
  async loadMore() {
    if (!this.active || this.caseId || this.state.status !== 'ready' || this.state.loadingMore || this.state.items.length >= this.state.total || this.state.page >= 10000) return
    const run = this.run; this.patch({ loadingMore: true, notice: '' })
    try {
      const ticket = this.ticket(); await this.access(ticket, run)
      const page = await this.deps.list({ merchantId: ticket.context.merchantId!, storeId: ticket.context.storeId!, page: this.state.page + 1, pageSize: 20, ...(this.state.filter ? { status: this.state.filter } : {}) })
      this.current(ticket, run)
      const ids = new Set(this.state.items.map(item => item.afterSaleId))
      this.patch({ items: [...this.state.items, ...page.items.filter(item => !ids.has(item.afterSaleId))], total: page.total, page: page.page, loadingMore: false })
    } catch (error) { if (error instanceof ApiError && [401, 403].includes(error.statusCode)) this.fail(error, run)
      else if (this.active && run === this.run && !(error instanceof StaleContextError)) this.patch({ loadingMore: false, notice: merchantAfterSaleMessage(error) }) }
  }
  setDraft(patch: Partial<ReplyDraft>) {
    if (!this.state.busy && !this.state.pending && !this.state.uploadPending) {
      const draft = { ...this.state.draft, ...patch }
      if (this.caseId) this.deps.saveDraft(this.caseId, draft)
      this.patch({ draft, notice: '' })
    }
  }
  removeAsset(assetId: string) { this.setDraft({ assetIds: this.state.draft.assetIds.filter(id => id !== assetId) }) }
  canReply() { return !!this.state.detail && canReply(this.state.detail, this.state.writable, this.deps.now()) }
  async submit(mode: 'opinion' | 'evidence') {
    if (!this.active || this.state.busy || !this.state.detail || !this.caseId || this.state.uploadPending) return
    const run = this.run, caseId = this.caseId
    let acknowledged = false
    this.patch({ busy: true, notice: '', image: null }); this.deps.clearImages()
    try {
      const ticket = this.ticket(); const access = await this.access(ticket, run)
      const pending = this.deps.pending(caseId)
      // An unresolved write may already have committed before a later terminal state;
      // replay its exact input even when a new command would now be disallowed.
      if (!access.writable || !pending && !canReply(this.state.detail!, access.writable, this.deps.now())) throw new Error('READ_ONLY')
      if (pending && pending.action !== mode) throw new Error('PENDING_ACTION_LOCKED')
      const input = pending ? pending.input : replyInput(this.state.detail!, this.state.draft, mode)
      await (mode === 'opinion' ? this.deps.opinion(caseId, input as OpinionInput) : this.deps.evidence(caseId, input as EvidenceInput))
      acknowledged = true
      this.current(ticket, run)
      this.deps.saveDraft(caseId, null)
      this.patch({ pending: null, draft: emptyReply() })
      // A receipt is proof of the write; read the actual current case for display.
      const detail = await this.deps.detail(caseId); this.current(ticket, run)
      this.patch({ detail, status: 'ready', busy: false, notice: '已提交，平台将结合双方证据处理。' })
    } catch (error) {
      if (!this.active || run !== this.run || error instanceof StaleContextError) return
      const pending = this.deps.pending(caseId)
      if (acknowledged && !(error instanceof ApiError && [401, 403].includes(error.statusCode))) {
        this.patch({ busy: false, status: 'error', detail: null, writable: false, pending: null, notice: '已提交，最新详情读取失败，请重新加载。' })
        return
      }
      if (isDefiniteAfterSaleConflict(error)) {
        const retired = this.deps.retireConflict(caseId, mode, error)
        this.patch({ pending: retired ? null : pending, busy: false, notice: retired ? '工单或补证轮次已变化，请核对最新详情后重新提交。' : '上次提交结果待确认，请按原内容重试。' })
        try { const ticket = this.ticket(); await this.access(ticket, run); const detail = await this.deps.detail(caseId); this.current(ticket, run); this.patch({ detail, status: 'ready' }) }
        catch (readError) { this.fail(readError, run) }
      } else if (error instanceof ApiError && [409, 429].includes(error.statusCode)) {
        this.patch({ busy: false, pending, notice: '上次提交结果待确认，请稍后按原内容重试。' })
      } else if (error instanceof ApiError && [401, 403].includes(error.statusCode)) this.fail(error, run)
      else this.patch({ busy: false, pending, notice: merchantAfterSaleMessage(error) })
    }
  }
  async upload(filePath?: string) {
    if (!this.active || this.state.busy || !this.caseId || !this.state.detail || this.state.pending) return
    const run = this.run, caseId = this.caseId
    let previouslyAttempted = true
    this.patch({ busy: true, notice: '' })
    try {
      const ticket = this.ticket(); const access = await this.access(ticket, run)
      if (!canReply(this.state.detail!, access.writable, this.deps.now())) throw new Error('READ_ONLY')
      let attempt = this.deps.uploadAttempt(caseId)
      if (this.state.draft.assetIds.length >= 6 && (!attempt?.receipt || !this.state.draft.assetIds.includes(attempt.receipt.assetId))) throw new Error('EVIDENCE_LIMIT')
      if (!attempt) {
        if (!filePath) throw new Error('UPLOAD_FILE_REQUIRED')
        const requestId = await this.deps.uuid(); this.current(ticket, run)
        const fingerprint = await this.deps.files.inspect(filePath); this.current(ticket, run)
        if (!/^[a-f0-9]{64}$/.test(fingerprint.sha256) || !Number.isSafeInteger(fingerprint.bytes) || fingerprint.bytes < 1 || fingerprint.bytes > 10485760) throw new Error('UPLOAD_FILE_INVALID')
        const saved = await this.deps.files.save(filePath, requestId)
        try { this.current(ticket, run) } catch (error) { await this.deps.files.remove(saved); throw error }
        if (!this.deps.files.owns(saved, requestId)) throw new Error('UPLOAD_JOURNAL_INVALID')
        attempt = { filePath: saved, requestId, ...fingerprint, attempted: false }
        try { this.deps.saveUploadAttempt(caseId, attempt) } catch (error) { await this.deps.files.remove(saved); throw error }
      }
      this.patch({ uploadPending: true })
      if (!this.deps.files.owns(attempt.filePath, attempt.requestId)) throw new Error('UPLOAD_JOURNAL_INVALID')
      let asset = attempt.receipt
      if (!asset) {
        const current = await this.deps.files.inspect(attempt.filePath); this.current(ticket, run)
        if (current.sha256 !== attempt.sha256 || current.bytes !== attempt.bytes) throw new Error('UPLOAD_FILE_CHANGED')
        previouslyAttempted = attempt.attempted
        attempt = { ...attempt, attempted: true }; this.deps.saveUploadAttempt(caseId, attempt)
        asset = await this.deps.upload(attempt.filePath, attempt.requestId); this.current(ticket, run)
        // Durable receipt precedes draft insertion: a crash never loses a successful upload.
        attempt = { ...attempt, receipt: asset }; this.deps.saveUploadAttempt(caseId, attempt)
      }
      const assetIds = [...new Set([...this.state.draft.assetIds, asset.assetId])]
      const draft = { ...this.state.draft, assetIds }
      this.deps.saveDraft(caseId, draft)
      await this.deps.files.remove(attempt.filePath); this.current(ticket, run)
      this.deps.saveUploadAttempt(caseId, null)
      this.patch({ busy: false, uploadPending: false, draft, notice: '证据图片已上传；提交意见或补证后入卷。' })
    } catch (error) {
      if (!this.active || run !== this.run || error instanceof StaleContextError) return
      // A later ingress failure cannot disprove an earlier successful unknown upload.
      const rejected = error instanceof ApiError && (error.statusCode === 422 && error.code === 'PRIVATE_ASSET_REJECTED'
        || !previouslyAttempted && [400, 413, 415].includes(error.statusCode))
      if (rejected) {
        const attempt = this.deps.uploadAttempt(caseId)
        if (attempt && !attempt.receipt) {
          try { await this.deps.files.remove(attempt.filePath); if (!this.active || run !== this.run) return; this.deps.saveUploadAttempt(caseId, null) }
          catch { this.patch({ busy: false, uploadPending: true, notice: '图片已被拒绝，本地恢复记录清理失败，请重试原操作。' }); return }
        }
      }
      if (error instanceof ApiError && [401, 403].includes(error.statusCode)) this.fail(error, run)
      else {
        const attempt = this.deps.uploadAttempt(caseId)
        this.patch({ busy: false, uploadPending: !!attempt, notice: attempt?.receipt ? '图片已上传，恢复证据图片后继续提交。' : merchantAfterSaleMessage(error) })
      }
    }
  }
  async readEvidence(batchId: string, assetId: string) {
    if (!this.active || this.state.busy || !this.caseId || !this.state.detail) return
    const run = this.run
    this.deps.clearImages(); this.patch({ busy: true, image: null, notice: '' })
    try {
      const ticket = this.ticket(); await this.access(ticket, run)
      const batch = this.state.detail!.evidence.find(item => item.batchId === batchId)
      if (!batch?.assetIds.includes(assetId)) throw new Error('EVIDENCE_NOT_IN_CASE')
      const image = await this.deps.readEvidence(this.caseId, batchId, assetId, '商家售后处理查看入卷证据')
      this.current(ticket, run); this.patch({ busy: false, image })
    } catch (error) {
      if (error instanceof ApiError && [401, 403].includes(error.statusCode)) this.fail(error, run)
      else if (this.active && run === this.run && !(error instanceof StaleContextError)) this.patch({ busy: false, image: null, notice: merchantAfterSaleMessage(error) })
    }
  }
  closeImage() { this.deps.clearImages(); this.patch({ image: null }) }
  showNotice(notice: string) { this.patch({ notice }) }
  clearNotice() { this.patch({ notice: '' }) }
  hide() { this.run++; this.deps.clearImages(); this.patch({ ...initial(), status: 'idle' }) }
  dispose() { this.active = false; this.run++; this.deps.clearImages(); this.unsubscribe(); this.listeners.clear() }
}

export function merchantAfterSaleMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401) return '登录已失效，请重新登录后进入商家工作台。'
    if (error.statusCode === 403) return '当前身份或权限不允许此操作，请从商家工作台重新进入。'
    if (error.statusCode === 404) return '工单不存在或不在当前门店权限范围内。'
    if (error.statusCode === 409) return isDefiniteAfterSaleConflict(error) ? '工单状态或补证轮次已变化，请刷新详情。' : '上次提交结果待确认，请按原内容重试。'
    if (error.statusCode === 410) return '证据查看凭证已过期或已使用，请重新查看。'
    if (error.statusCode === 413) return '图片超过10MiB，请重新选择。'
    if (error.statusCode === 415) return '仅支持JPEG、PNG图片。'
    if (error.statusCode === 422) return '内容或图片未通过审核，请修改后提交。'
    if (error.statusCode === 429) return '操作频繁，请稍后重试。'
    if (error.statusCode === 503) return '售后服务暂不可用，请稍后重试。'
  }
  const code = error instanceof Error ? error.message : ''
  if (code === 'MERCHANT_ENTRY_REQUIRED') return '请先登录，并从商家工作台选择门店后进入。'
  if (code === 'READ_ONLY') return '当前工单只读或补证期限已到，请刷新后查看最新状态。'
  if (code === 'REPLY_TEXT_LENGTH') return '说明需填写10至500字。'
  if (code === 'OPINION_REQUIRED') return '请选择商家意见，并填写10至500字说明。'
  if (code === 'EVIDENCE_REQUIRED') return '请填写10至500字说明或上传证据图片。'
  if (code === 'EVIDENCE_LIMIT') return '每次最多提交6张不同的证据图片。'
  if (code === 'UPLOAD_FILE_INVALID') return '请选择有效的JPEG、PNG图片，每张不超过10MiB。'
  if (code === 'UPLOAD_FILE_CHANGED') return '原图片文件已发生变化，已停止重试，请保留原提交记录。'
  if (code === 'UPLOAD_JOURNAL_INVALID' || code === 'INVALID_UPLOAD_JOURNAL') return '原图片恢复记录不可验证，已停止上传。'
  if (code === 'PENDING_ACTION_LOCKED' || code === 'PENDING_WRITE_CHANGED') return '上次提交结果待确认，请按原内容重试。'
  return '操作结果尚未确认，请重试原操作；提交前请核对最新工单。'
}
