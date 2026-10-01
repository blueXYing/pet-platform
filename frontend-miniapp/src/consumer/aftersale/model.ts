import { ApiError } from '../../shared/request'
import type { WorkspaceScope } from '../../shared/workspace'
import type { AfterSaleStatus, CaseDetail, CasePage, CommandReceipt, CreateInput, Eligibility, EvidenceInput } from '../../shared/aftersale-api'
import { isDefiniteAfterSaleConflict } from '../../shared/aftersale-api'

export type CreateDraft = { typeCode: string; demandCode: string; description: string; requestedAmount: string; newProblemStatement: string }
export const emptyCreateDraft = (): CreateDraft => ({ typeCode: '', demandCode: '', description: '', requestedAmount: '', newProblemStatement: '' })
export type FieldErrors = Partial<Record<keyof CreateDraft | 'evidence' | 'text', string>>
const idPattern = /^[1-9][0-9]{0,18}(?![\s\S])/
export const isId = (value: string) => idPattern.test(value) && BigInt(value) <= 9223372036854775807n
export function validateAssets(ids: readonly string[]): string | undefined {
  if (ids.length > 6 || new Set(ids).size !== ids.length || ids.some(value => !isId(value))) return '最多提交6张有效且不重复的图片'
}
function textError(value: string, min: number, max: number, label: string): string | undefined {
  // The public contract counts Java UTF-16 characters; do not trim canonical payloads.
  if (!value.trim() || value.length < min || value.length > max) return `${label}需填写${min}至${max}字`
}
export function validateCreate(draft: CreateDraft, ids: readonly string[]): FieldErrors {
  const errors: FieldErrors = {}
  errors.typeCode = textError(draft.typeCode, 1, 64, '问题类型')
  errors.demandCode = textError(draft.demandCode, 1, 64, '诉求')
  errors.description = textError(draft.description, 10, 500, '问题说明')
  if (draft.requestedAmount && !/^(0|[1-9][0-9]{0,15})\.[0-9]{2}(?![\s\S])/.test(draft.requestedAmount)) errors.requestedAmount = '金额应为两位小数字符串，例如35.00'
  if (draft.newProblemStatement) errors.newProblemStatement = textError(draft.newProblemStatement, 10, 500, '新问题说明')
  errors.evidence = validateAssets(ids)
  return Object.fromEntries(Object.entries(errors).filter(([, value]) => value)) as FieldErrors
}
export function createInput(draft: CreateDraft, evidenceAssetIds: string[]): CreateInput {
  return { typeCode: draft.typeCode, demandCode: draft.demandCode, description: draft.description, evidenceAssetIds: [...evidenceAssetIds], requestedAmount: draft.requestedAmount || null, newProblemStatement: draft.newProblemStatement || null }
}
export function validateEvidence(text: string, ids: readonly string[]): FieldErrors {
  const errors: FieldErrors = {}
  if (text) errors.text = textError(text, 10, 500, '补充说明')
  if (!text.trim() && ids.length === 0) errors.text = '请填写10至500字说明或上传图片'
  errors.evidence = validateAssets(ids)
  return Object.fromEntries(Object.entries(errors).filter(([, value]) => value)) as FieldErrors
}
export const activeCase = (detail: CaseDetail | null) => !!detail && ['PENDING', 'PROCESSING', 'WAITING_SUPPLEMENT'].includes(detail.status)
export const statusLabel = (status: string) => ({ PENDING: '待受理', PROCESSING: '处理中', WAITING_SUPPLEMENT: '待补证', RESOLVED: '已裁决', INVALIDATED: '已失效', WITHDRAWN: '已撤回', CLOSED: '已关闭' }[status] || status)
export const decisionLabel = (type: string | null) => type ? ({ REJECT: '驳回申请', RESERVICE: '重新服务', OTHER: '其他处理', FULL_REFUND: '全额退款', PARTIAL_REFUND: '部分退款' }[type] || type) : ''
export function afterSaleMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401) return '登录已失效，请重新登录后查看'
    if (error.statusCode === 403) return '当前账号仅可查看，无法执行此操作'
    if (error.code === 'AFTERSALE_ALREADY_ACTIVE') return '此订单已有活动售后，请查看当前工单并追加问题'
    if (error.code === 'AFTERSALE_REFUND_APPLICATION_ACTIVE') return '此订单有普通退款申请，暂不能发起售后'
    if (error.code === 'AFTERSALE_SUPPLEMENT_EXPIRED') return '本轮补证已截止，请重新读取处理进度'
    if (error.code === 'COMMON_CONFLICT' || /IDEMPOTEN|IN_PROGRESS/.test(error.code)) return '原请求可能仍在处理，请保留当前内容并重试原操作'
    if (error.code === 'AFTERSALE_SUPPLEMENT_STALE') return '处理状态已更新，请重新读取后核对'
    if (error.statusCode === 410) return '图片查看授权已失效，请重新点击查看'
    if (error.statusCode === 422) return '内容或图片未通过校验，请修改后提交'
    if (error.statusCode === 404) return '工单或订单不存在，请返回重新选择'
    if (error.statusCode === 400) return '提交内容无效，请检查类型、诉求、说明及图片'
    if (error.statusCode === 409) return '当前状态不允许操作，请重新读取核对'
    if (error.statusCode === 503) return '服务暂不可用，请稍后重试原操作'
  }
  if (error instanceof Error && error.message === 'PENDING_WRITE_CHANGED') return '上次提交结果尚未确认，请先重试原操作'
  if (error instanceof Error && error.message === 'UPLOAD_FILE_INVALID') return '请选择JPG或PNG图片，单张不超过10MiB'
  if (error instanceof Error && error.message === 'UPLOAD_FILE_CHANGED') return '已保存的上传图片发生变化，请保留当前记录并联系平台处理'
  if (error instanceof Error && error.message === 'UPLOAD_REJECTED') return '图片校验失败，请移除该图片后重新选择'
  return '结果尚未确认，请重试原操作'
}

export type ConsumerAfterSaleDeps = {
  list(query: { page: number; pageSize: number; status?: AfterSaleStatus; orderId?: string }): Promise<CasePage>
  detail(id: string): Promise<CaseDetail>
  eligibility(orderId: string): Promise<Eligibility>
  create(orderId: string, input: CreateInput): Promise<CommandReceipt>
  evidence(id: string, input: EvidenceInput): Promise<CommandReceipt>
  withdraw(id: string, version: string): Promise<CommandReceipt>
  retireConflict?(target: string, action: 'create' | 'evidence' | 'withdraw', error: unknown): void
}
export type Pending = { kind: 'create'; orderId: string; input: CreateInput } | { kind: 'evidence'; id: string; input: EvidenceInput } | { kind: 'withdraw'; id: string; version: string }
export type AfterSaleState = Readonly<{
  phase: 'idle' | 'loading' | 'ready' | 'error' | 'unauthorized'
  page: CasePage | null; detail: CaseDetail | null; eligibility: Eligibility | null
  busy: boolean; locked: boolean; readOnly: boolean; notice: string; receipt: CommandReceipt | null
}>
const initialState = (): AfterSaleState => ({ phase: 'idle', page: null, detail: null, eligibility: null, busy: false, locked: false, readOnly: false, notice: '', receipt: null })

/** A controller belongs to one mounted page and one principal. Unknown writes retain the
 * original payload; only an explicit retry resends it. The shared client journals its UUID. */
export class ConsumerAfterSaleController {
  private state = initialState()
  private listeners = new Set<() => void>()
  private pending: Pending | null = null
  private active = true
  private epoch = 0
  private reads = 0
  private unsubscribe: () => void
  constructor(private deps: ConsumerAfterSaleDeps, private scope: WorkspaceScope) {
    this.unsubscribe = scope.subscribe(() => { this.epoch++; this.reads++; this.pending = null; this.publish(initialState()) })
  }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish(state: AfterSaleState) { if (!this.active) return; this.state = Object.freeze(state); this.listeners.forEach(listener => listener()) }
  private live(epoch: number) { return this.active && epoch === this.epoch && this.scope.current?.workspace === 'consumer' }
  private principal() {
    if (this.scope.current?.workspace !== 'consumer') {
      this.publish({ ...initialState(), phase: 'unauthorized', notice: '请先登录宠物主工作区' }); return false
    }
    return true
  }
  private async read(action: () => Promise<Partial<AfterSaleState>>, preserveNotice = false) {
    if (!this.active || !this.principal() || this.state.busy) return
    const epoch = this.epoch, run = ++this.reads
    this.publish({ ...this.state, phase: 'loading', notice: preserveNotice ? this.state.notice : '' })
    try {
      const result = await action()
      if (this.live(epoch) && run === this.reads) this.publish({ ...this.state, ...result, phase: 'ready' })
    } catch (error) {
      if (this.live(epoch) && run === this.reads) this.publish({ ...this.state, phase: error instanceof ApiError && error.statusCode === 401 ? 'unauthorized' : 'error', page: null, detail: null, eligibility: null, notice: afterSaleMessage(error) })
    }
  }
  loadList(page = 1, status?: AfterSaleStatus, orderId?: string) {
    return this.read(async () => ({ page: await this.deps.list({ page, pageSize: 20, ...(status ? { status } : {}), ...(orderId ? { orderId } : {}) }) }))
  }
  loadDetail(id: string, preserveNotice = false) { return this.read(async () => ({ detail: await this.deps.detail(id) }), preserveNotice) }
  loadEligibility(orderId: string, preserveNotice = false) { return this.read(async () => ({ eligibility: await this.deps.eligibility(orderId) }), preserveNotice) }
  async create(orderId: string, draft: CreateDraft, assets: string[]) {
    if (!isId(orderId) || Object.keys(validateCreate(draft, assets)).length || !this.state.eligibility?.eligible || this.state.eligibility.activeAfterSaleId) return
    await this.start({ kind: 'create', orderId, input: createInput(draft, assets) })
  }
  async evidence(text: string, assets: string[]) {
    const detail = this.state.detail
    if (!detail || !activeCase(detail) || Object.keys(validateEvidence(text, assets)).length) return
    await this.start({ kind: 'evidence', id: detail.afterSaleId, input: { expectedVersion: detail.version, evidenceAssetIds: [...assets], text: text || null, supplementRequestId: detail.status === 'WAITING_SUPPLEMENT' ? detail.supplementRequestId : null } })
  }
  async withdraw() {
    const detail = this.state.detail
    if (!detail || !activeCase(detail)) return
    await this.start({ kind: 'withdraw', id: detail.afterSaleId, version: detail.version })
  }
  private async start(pending: Pending) {
    if (!this.active || !this.principal() || this.state.busy || this.state.readOnly || this.pending) return
    this.pending = pending
    await this.retry()
  }
  async retry() {
    if (!this.active || !this.principal() || this.state.busy || this.state.readOnly || !this.pending) return
    const epoch = this.epoch, pending = this.pending
    this.reads++
    this.publish({ ...this.state, busy: true, locked: true, notice: '' })
    let reread: (() => Promise<void>) | null = null
    try {
      const receipt = pending.kind === 'create' ? await this.deps.create(pending.orderId, pending.input)
        : pending.kind === 'evidence' ? await this.deps.evidence(pending.id, pending.input)
        : await this.deps.withdraw(pending.id, pending.version)
      if (!this.live(epoch)) return
      this.pending = null
      this.publish({ ...this.state, busy: false, locked: false, receipt, notice: pending.kind === 'create' ? '售后申请已提交' : pending.kind === 'withdraw' ? '售后申请已撤回，原申请期限不变' : '证据已提交' })
      reread = () => this.loadDetail(receipt.afterSaleId, true)
    } catch (error) {
      if (!this.live(epoch)) return
      // Exact definitive rejections can unlock edits. An idempotency conflict retains its
      // payload so the user cannot create a different operation under a new UUID.
      const rejected = error instanceof ApiError && ([400, 401, 403, 404, 422].includes(error.statusCode) || isDefiniteAfterSaleConflict(error))
      if (error instanceof ApiError && error.statusCode === 409 && rejected) this.deps.retireConflict?.(pending.kind === 'create' ? pending.orderId : pending.id, pending.kind, error)
      if (rejected) this.pending = null
      const unauthorized = error instanceof ApiError && error.statusCode === 401
      this.publish({ ...this.state, ...(unauthorized ? { phase: 'unauthorized', page: null, detail: null, eligibility: null } as const : {}), busy: false, locked: !rejected, readOnly: error instanceof ApiError && error.statusCode === 403, notice: afterSaleMessage(error) })
      if (error instanceof ApiError && error.statusCode === 409 && rejected) reread = pending.kind === 'create' ? () => this.loadEligibility(pending.orderId, true) : () => this.loadDetail(pending.id, true)
    }
    if (reread && this.live(epoch)) await reread()
  }
  restore(pending: Pending) {
    if (this.pending || !this.active) return
    this.pending = JSON.parse(JSON.stringify(pending)) as Pending
    this.publish({ ...this.state, locked: true, notice: '已恢复上次未确认的提交，请重试原操作' })
  }
  dispose() { this.active = false; this.epoch++; this.unsubscribe(); this.listeners.clear(); this.pending = null }
}
