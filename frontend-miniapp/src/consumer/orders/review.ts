// REV-001 评价前端切片（10号 §3.14 + SSOT §11 40/40/20 + 07号 §7.7/§14，后端同批 PR 交付，
// IMPLEMENTED_DEFAULT_OFF：pet.auth.c.enabled + pet.review.http.enabled 双层默认关闭）。本文件是
// 评价 C 端投影契约口径的唯一替换点，字段事实全部来自契约与本批实现：
// - GET /api/v1/c/orders/{orderId}/review-eligibility：恰四键 eligible/scoreIncluded/
//   reviewDeadline?(毫秒 ISO)/rejectCode?(REVIEW_NOT_VERIFIED|REVIEW_WINDOW_EXPIRED|
//   REVIEW_ALREADY_EXISTS|REVIEW_NOT_ELIGIBLE)。退款成功/退款中不可评价为 2026-10-07 用户
//   裁决；已核销后部分退款 eligible=true 但 scoreIncluded=false（SSOT §11.2，展示不计分）。
// - POST /api/v1/c/orders/{orderId}/reviews：严格 JSON {storeScore,serviceScore,staffScore,
//   content?,mediaFileIds?}，三维 1~5 整数、content 0..2000 码点、媒体能力未开放（本页不
//   提供上传，非空数组服务端 400）。X-Request-Id 终端 UUID 由 ConsumerApi 分配，五元组幂等：
//   首报文 201、受保护重放 200 同回执；回执恰两键 reviewId/scoreIncluded。
// - 40/40/20 综合分（门店40%+服务40%+人员20%，1 位小数）为服务端内核事实；页面预览分仅是
//   展示推导（ARCH-005：不放页面，在本模块 compositePreview）。
// - 错误面：REVIEW_NOT_VERIFIED/REVIEW_WINDOW_EXPIRED/REVIEW_ALREADY_EXISTS/REVIEW_NOT_ELIGIBLE
//   按 12号 §11 如实映射；推导不放页面，文案映射全在本模块，页面只消费现成展示值。

import { ApiError } from '../../shared/request'
import type { WorkspaceScope } from '../../shared/workspace'
import { object } from '../../shared/consumer-api'
import { PreviewOrderReadRepository, type OrderDetailView } from './model'

export type ReviewRejectCode = 'REVIEW_NOT_VERIFIED' | 'REVIEW_WINDOW_EXPIRED' | 'REVIEW_ALREADY_EXISTS' | 'REVIEW_NOT_ELIGIBLE'
export type ReviewScoreKey = 'storeScore' | 'serviceScore' | 'staffScore'

export type ReviewEligibility = Readonly<{
  eligible: boolean
  scoreIncluded: boolean
  reviewDeadline: string | null
  rejectCode: ReviewRejectCode | null
}>

export type ReviewReceipt = Readonly<{ reviewId: string; scoreIncluded: boolean }>

export type ReviewCreateInput = Readonly<{
  storeScore: number; serviceScore: number; staffScore: number
  content: string | null
  mediaFileIds: readonly string[]
}>

/** 未评分用 0 表示（页面态）；提交前必须全部落在 1~5。 */
export type ReviewDraft = Readonly<{ storeScore: number; serviceScore: number; staffScore: number; content: string }>
export type ReviewDraftErrors = Readonly<Partial<Record<ReviewScoreKey | 'content', string>>>
export type PendingReview = Readonly<{ storeScore: number; serviceScore: number; staffScore: number; content: string | null }>

const idPattern = /^[1-9][0-9]{0,18}(?![\s\S])/
export const isOrderId = (value: string) => idPattern.test(value) && BigInt(value) <= 9223372036854775807n

function fail(): never { throw new Error('INVALID_RESPONSE') }
const id = (value: unknown): string => typeof value === 'string' && idPattern.test(value) && BigInt(value) <= 9223372036854775807n ? value : fail()
const bool = (value: unknown): boolean => typeof value === 'boolean' ? value : fail()
// appendInstant(3)：UTC 毫秒精度 ISO 串且可往返（refund/order-verify instant 同口径）。
const instant = (value: unknown): string => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value ? value : fail()
const nullable = <T>(value: unknown, decode: (value: unknown) => T): T | null => value === null ? null : decode(value)
const one = <T extends string>(value: unknown, values: readonly T[]): T => typeof value === 'string' && (values as readonly string[]).includes(value) ? value as T : fail()

/** 严格 exact-key：只允许资格四键 / 回执两键，未知键失败关闭（沿 refund/aftersale 惯例）。 */
function exact(value: unknown, keys: readonly string[]): Record<string, any> {
  const v = object(value)
  if (Object.keys(v).sort().join(',') !== [...keys].sort().join(',')) fail()
  return v
}

const eligibilityKeys = ['eligible', 'rejectCode', 'reviewDeadline', 'scoreIncluded']
const rejectCodes: readonly ReviewRejectCode[] = ['REVIEW_NOT_VERIFIED', 'REVIEW_WINDOW_EXPIRED', 'REVIEW_ALREADY_EXISTS', 'REVIEW_NOT_ELIGIBLE']

export function decodeReviewEligibility(value: unknown): ReviewEligibility {
  const v = exact(value, eligibilityKeys)
  return {
    eligible: bool(v.eligible),
    scoreIncluded: bool(v.scoreIncluded),
    reviewDeadline: nullable(v.reviewDeadline, instant),
    rejectCode: nullable(v.rejectCode, code => one(code, rejectCodes)),
  }
}

export function decodeReviewReceipt(value: unknown): ReviewReceipt {
  const v = exact(value, ['reviewId', 'scoreIncluded'])
  return { reviewId: id(v.reviewId), scoreIncluded: bool(v.scoreIncluded) }
}

// ---- 表单草稿（结构校验归本模块；资格准入归服务端内核） ----

export const emptyReviewDraft = (): ReviewDraft => ({ storeScore: 0, serviceScore: 0, staffScore: 0, content: '' })

const codePoints = (value: string): number => Array.from(value).length
const hasSurrogate = (value: string): boolean => Array.from(value).some(point => { const scalar = point.codePointAt(0)!; return scalar >= 0xd800 && scalar <= 0xdfff })
const scoreError = (value: number): string | undefined =>
  !Number.isInteger(value) || value < 1 || value > 5 ? '请选择 1~5 星评分' : undefined

/** 契约边界校验：三维评分必填 1~5；content 选填 0..2000 码点（无敏感词/字典规则）。 */
export function validateReviewDraft(draft: ReviewDraft): ReviewDraftErrors {
  const errors: { storeScore?: string; serviceScore?: string; staffScore?: string; content?: string } = {}
  const store = scoreError(draft.storeScore), service = scoreError(draft.serviceScore), staff = scoreError(draft.staffScore)
  if (store !== undefined) errors.storeScore = store
  if (service !== undefined) errors.serviceScore = service
  if (staff !== undefined) errors.staffScore = staff
  if (codePoints(draft.content) > 2000) errors.content = '评价内容不能超过 2000 个字符'
  if (hasSurrogate(draft.content)) errors.content = '评价内容包含无效字符'
  return errors
}

/** 提交载荷：content 去首尾空白，空串按契约省略（等价 null）；媒体能力未开放固定空数组。 */
export function reviewCreateInput(draft: ReviewDraft): ReviewCreateInput {
  const content = draft.content.trim()
  return {
    storeScore: draft.storeScore, serviceScore: draft.serviceScore, staffScore: draft.staffScore,
    content: content.length === 0 ? null : content,
    mediaFileIds: [],
  }
}

// ---- 展示映射（ARCH-005：事实→展示推导归本模块，页面只消费现成展示值） ----

/** SSOT §11.1 权重口径：门店 40% + 服务 40% + 人员 20%。 */
export const reviewDimensions: readonly { key: ReviewScoreKey; label: string; weight: string }[] = [
  { key: 'storeScore', label: '门店评分', weight: '40%' },
  { key: 'serviceScore', label: '服务评分', weight: '40%' },
  { key: 'staffScore', label: '人员评分', weight: '20%' },
]

/** 综合分预览（展示推导，一位小数；未评满三维返回 null；真实综合分由服务端内核计算）。 */
export function compositePreview(draft: ReviewDraft): string | null {
  const store = scoreError(draft.storeScore), service = scoreError(draft.serviceScore), staff = scoreError(draft.staffScore)
  if (store !== undefined || service !== undefined || staff !== undefined) return null
  const tenths = draft.storeScore * 4 + draft.serviceScore * 4 + draft.staffScore * 2
  return (tenths / 10).toFixed(1)
}

/** 详情页评价入口条件：仅凭服务端 OrderActions.canReview（10号 §3.7 事实字段，不推导）。 */
export function canReviewEntry(detail: OrderDetailView): boolean {
  return detail.actions?.canReview === true
}

export function eligibilityHeadline(eligibility: ReviewEligibility): string {
  if (eligibility.eligible) return eligibility.scoreIncluded ? '本单可评价' : '本单可评价（不计入商家评分）'
  switch (eligibility.rejectCode) {
    case 'REVIEW_NOT_VERIFIED': return '服务核销完成后才能评价'
    case 'REVIEW_WINDOW_EXPIRED': return '核销后 30 天评价期已过'
    case 'REVIEW_ALREADY_EXISTS': return '本单已评价过'
    case 'REVIEW_NOT_ELIGIBLE': return '订单退款后不可评价'
    default: return '当前订单不可评价'
  }
}

/** 这些 409 对该 payload 是终局拒绝（资格/一单一评事实不会回退），可退幂等槽后重新读取。 */
export function isDefiniteReviewConflict(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 409
    && ['COMMON_CONFLICT', 'REVIEW_NOT_ELIGIBLE', 'REVIEW_NOT_VERIFIED',
      'REVIEW_WINDOW_EXPIRED', 'REVIEW_ALREADY_EXISTS'].includes(error.code)
}

/** 页面错误文案（12号 §11 + 10号 §3.14 映射；404 同时覆盖他人/未知订单的防探测语义）。 */
export function reviewCreateMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401) return '登录已失效，请重新登录后再试'
    if (error.code === 'REVIEW_NOT_VERIFIED') return '服务核销完成后才能评价'
    if (error.code === 'REVIEW_WINDOW_EXPIRED') return '核销后 30 天评价期已过，不能再评价'
    if (error.code === 'REVIEW_ALREADY_EXISTS') return '本单已评价过，一单仅可评价一次'
    if (error.code === 'REVIEW_NOT_ELIGIBLE') return '订单已退款（或退款中），不能评价'
    if (error.code === 'IDEMPOTENCY_KEY_CONFLICT') return '上次提交的评分内容已变化，请核对后重新提交'
    if (error.code === 'COMMON_CONFLICT') return '订单状态已变化，请刷新后重新提交'
    if (error.statusCode === 403) return '当前账号无法提交评价'
    if (error.statusCode === 404) return '订单不存在或仅订单本人可评价'
    if (error.statusCode === 400) return '提交内容无效，请检查三维评分（1~5 星）与评价内容后重试'
    if (error.statusCode === 503) return '服务暂不可用，结果尚未确认，请稍后重试原操作'
  }
  if (error instanceof Error && error.message === 'PENDING_WRITE_CHANGED') return '上次评价结果尚未确认，请先重试原操作'
  if (error instanceof Error && error.message === 'INVALID_RESPONSE') return '服务返回异常，请稍后重试'
  return '评价结果尚未确认，请重试原操作；不会重复提交'
}

/** 成功回执口径：如实展示 scoreIncluded（SSOT §11.2 部分退款展示不计分）。 */
export function receiptHeadline(receipt: ReviewReceipt): string {
  return receipt.scoreIncluded ? '评价提交成功' : '评价提交成功（本单部分退款，不计入商家评分）'
}
export function receiptBody(): string {
  return '感谢你的评价。门店 40% + 服务 40% + 人员 20% 的综合分由平台按规则计算，评价公开展示。'
}

// ---- preview=1 设计验收通道（本地夹具，不发任何网络请求） ----

export type ReviewScenario = 'fresh' | 'done' | 'ineligible'
export const isReviewScenario = (value?: string): value is ReviewScenario =>
  ['fresh', 'done', 'ineligible'].includes(value || '')

/** preview 夹具复用订单模型样例单：COMPLETED+VERIFIED+canReview=true 的 2026100100004 单。 */
export const PREVIEW_REVIEW_ORDER = '900101001990004'

/** 内核规则的 preview 模拟：按场景返回固定资格/回执；同单第二笔提交按一单一评拒绝。 */
export class PreviewReviewRepository {
  private reviewed = new Set<string>()
  private sequence = 0
  constructor(private scenario: ReviewScenario = 'fresh') {}

  async detail(orderId: string): Promise<OrderDetailView> {
    const found = await new PreviewOrderReadRepository('normal').detail(isOrderId(orderId) ? orderId : PREVIEW_REVIEW_ORDER)
    if (this.scenario === 'ineligible') {
      // 未核销样例：入口门控按服务端 actions.canReview=false 如实关死。
      return { ...found, displayStatus: 'PENDING_SERVICE', verificationStatus: 'UNVERIFIED', verifiedAt: null,
        actions: { canPay: false, canReschedule: false, canApplyRefund: true, canShowVerificationCode: true, canReview: false, canApplyAfterSale: false } }
    }
    return found
  }

  async eligibility(orderId: string): Promise<ReviewEligibility> {
    const target = isOrderId(orderId) ? orderId : PREVIEW_REVIEW_ORDER
    if (this.scenario === 'ineligible') return { eligible: false, scoreIncluded: false, reviewDeadline: null, rejectCode: 'REVIEW_NOT_VERIFIED' }
    if (this.reviewed.has(target) || this.scenario === 'done') return { eligible: false, scoreIncluded: false, reviewDeadline: '2026-11-05T12:04:00.000Z', rejectCode: 'REVIEW_ALREADY_EXISTS' }
    return { eligible: true, scoreIncluded: true, reviewDeadline: '2026-11-05T12:04:00.000Z', rejectCode: null }
  }

  async create(orderId: string, input: ReviewCreateInput): Promise<ReviewReceipt> {
    const target = isOrderId(orderId) ? orderId : PREVIEW_REVIEW_ORDER
    if (this.scenario === 'ineligible') throw new ApiError('REVIEW_NOT_VERIFIED', 409)
    if (this.scenario === 'done' || this.reviewed.has(target)) throw new ApiError('REVIEW_ALREADY_EXISTS', 409)
    for (const score of [input.storeScore, input.serviceScore, input.staffScore]) {
      if (!Number.isInteger(score) || score < 1 || score > 5) throw new ApiError('COMMON_INVALID_ARGUMENT', 400)
    }
    this.reviewed.add(target)
    this.sequence += 1
    return { reviewId: String(9700000000000000 + this.sequence), scoreIncluded: true }
  }

  pendingReview(): PendingReview | null { return null }
  retireConflict(): void { /* preview 无幂等槽 */ }
}

// ---- 页面控制器（详情门控 + 资格回读 + 一个主体写操作；未知结果保留原 payload 仅显式重试重发） ----

export type ReviewDeps = {
  detail(orderId: string): Promise<OrderDetailView>
  eligibility(orderId: string): Promise<ReviewEligibility>
  create(orderId: string, input: ReviewCreateInput): Promise<ReviewReceipt>
  pendingReview(orderId: string): PendingReview | null
  retireConflict(orderId: string, error: unknown): void
}

export type ReviewState = Readonly<{
  phase: 'idle' | 'loading' | 'ready' | 'ineligible' | 'unauthorized' | 'load-error'
  orderId: string | null
  detail: OrderDetailView | null
  eligibility: ReviewEligibility | null
  busy: boolean
  receipt: ReviewReceipt | null
  pending: PendingReview | null
  notice: string
}>

const initialState = (): ReviewState => ({ phase: 'idle', orderId: null, detail: null, eligibility: null, busy: false, receipt: null, pending: null, notice: '' })

export class ReviewController {
  private state = initialState()
  private listeners = new Set<() => void>()
  private active = true
  private epoch = 0
  private reads = 0
  private unsubscribe: () => void
  constructor(private deps: ReviewDeps, private scope: WorkspaceScope) {
    this.unsubscribe = scope.subscribe(() => { this.epoch++; this.reads++; this.publish(initialState()) })
  }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish(state: ReviewState) { if (!this.active) return; this.state = Object.freeze(state); this.listeners.forEach(listener => listener()) }
  private live(epoch: number) { return this.active && epoch === this.epoch }

  /** 读取订单详情（入口门控 actions.canReview）+ 资格面（rejectCode 驱动不可评文案）。 */
  async load(orderId: string) {
    if (!this.active || this.state.busy || !isOrderId(orderId)) return
    const epoch = this.epoch, run = ++this.reads
    this.publish({ ...this.state, phase: 'loading', notice: '' })
    try {
      const detail = await this.deps.detail(orderId)
      if (!this.live(epoch) || run !== this.reads) return
      if (!canReviewEntry(detail)) {
        this.publish({ ...this.state, phase: 'ineligible', orderId, detail, receipt: null, pending: this.deps.pendingReview(orderId), notice: '' })
        return
      }
      const eligibility = await this.deps.eligibility(orderId)
      if (!this.live(epoch) || run !== this.reads) return
      const ineligible = eligibility.eligible ? 'ready' : 'ineligible'
      this.publish({ ...this.state, phase: ineligible, orderId, detail, eligibility, receipt: null, pending: this.deps.pendingReview(orderId), notice: '' })
    } catch (error) {
      if (!this.live(epoch) || run !== this.reads) return
      const unauthorized = error instanceof ApiError && error.statusCode === 401
      this.publish({ ...this.state, phase: unauthorized ? 'unauthorized' : 'load-error', orderId, detail: null, eligibility: null, pending: null, notice: reviewCreateMessage(error) })
    }
  }

  /** 提交评价（结构校验后的载荷）；有未确认命令时只能重试原操作。 */
  async submit(orderId: string, draft: ReviewDraft) {
    if (!this.active || this.state.phase !== 'ready' || this.state.busy) return
    if (this.state.pending) return this.retry(orderId)
    const errors = validateReviewDraft(draft)
    if (errors.storeScore !== undefined || errors.serviceScore !== undefined || errors.staffScore !== undefined || errors.content !== undefined) {
      this.publish({ ...this.state, notice: '请完成三维评分后再提交' })
      return
    }
    await this.run(orderId, reviewCreateInput(draft))
  }

  /** 重试未确认的原操作（同 X-Request-Id、同 payload）。 */
  async retry(orderId: string) {
    const pending = this.state.pending
    if (!pending || !this.active || this.state.busy) return
    await this.run(orderId, {
      storeScore: pending.storeScore, serviceScore: pending.serviceScore, staffScore: pending.staffScore,
      content: pending.content, mediaFileIds: [],
    })
  }

  private async run(orderId: string, input: ReviewCreateInput) {
    const epoch = this.epoch
    this.reads++
    this.publish({ ...this.state, busy: true, notice: '' })
    try {
      const receipt = await this.deps.create(orderId, input)
      if (!this.live(epoch)) return
      // 成功后清除未确认槽并回读资格（no-store）；一单一评使资格随即翻为不可评，回执保留展示。
      this.publish({ ...this.state, busy: false, pending: this.deps.pendingReview(orderId), notice: '' })
      await this.load(orderId)
      if (this.live(epoch)) this.publish({ ...this.getSnapshot(), receipt })
    } catch (error) {
      if (!this.live(epoch)) return
      // 终局 409 可解锁重试（退幂等槽后重新读取）；未知结果保留原命令继续重试原操作。
      let rejected = false
      if (isDefiniteReviewConflict(error)) {
        try { this.deps.retireConflict(orderId, error); rejected = true } catch { rejected = false }
      }
      const unauthorized = error instanceof ApiError && error.statusCode === 401
      this.publish({ ...this.state, busy: false,
        pending: rejected || unauthorized ? null : this.deps.pendingReview(orderId),
        ...(unauthorized ? { phase: 'unauthorized' as const, detail: null, orderId: null } : {}),
        notice: reviewCreateMessage(error) })
      // 终局拒绝后的回读保持拒绝文案可见（重读清空的提示在此恢复），门控以最新事实为准。
      if (rejected && this.live(epoch)) {
        await this.load(orderId)
        if (this.live(epoch)) this.publish({ ...this.getSnapshot(), notice: reviewCreateMessage(error) })
      }
    }
  }

  /** 页面挂载时恢复未确认命令（仅提示 + 重试入口，不自动发送）。 */
  restore(orderId: string) {
    if (!this.active || this.state.busy) return
    const pending = this.deps.pendingReview(orderId)
    if (pending) this.publish({ ...this.state, pending, notice: '已恢复上次未确认的评价，请重试原操作' })
  }
  dispose() { this.active = false; this.epoch++; this.unsubscribe(); this.listeners.clear() }
}
