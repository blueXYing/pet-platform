// REV-002 评价申诉前端切片（56号契约 + SSOT §11.3，后端同批 PR 交付，IMPLEMENTED_DEFAULT_OFF：
// pet.review.enabled + pet.review.appeal.enabled + pet.review.appeal.http.enabled 默认关闭）。
// 本文件是申诉 M 端投影契约口径的唯一替换点，字段事实全部来自契约与本批实现：
// - GET /api/v1/merchant/reviews：必须携带等于当前商家工作区坐标的 merchantId+storeId，分页
//   page(1..10000)/pageSize(1..50)；返回恰四键 page/pageSize/total/items，每条恰十二键。
// - GET /api/v1/merchant/reviews/{reviewId}：在列表十二键上追加 appealReason/appealCreatedAt/
//   decisionReason/decidedAt 四键（未申诉全 null）。
// - POST /api/v1/merchant/reviews/{reviewId}/appeal：严格 JSON {reason}（trim 后非空、1..1000 码点），
//   X-Request-Id 终端 UUID 由 ConsumerApi 分配，五元组幂等：首报文 201、受保护重放 200 同回执；
//   回执恰四键 appealId/reviewId/status/createdAt。
// - 每条评价最多申诉一次（SSOT §11.3）：重复申诉 409 REVIEW_APPEAL_ALREADY_USED；
//   评价不存在与非本店统一 404 REVIEW_NOT_FOUND（防枚举）；申诉理由随幂等键受保护。
// - PROCESSING 为 06号 保留状态值，解码器接受但本切片服务端不产生。

import { ApiError, type RequestSpec } from './request'
import { id, object, type Command, type ConsumerApi } from './consumer-api'

export type ReviewAppealStatus = 'SUBMITTED' | 'PROCESSING' | 'APPROVED' | 'REJECTED'
export type ReviewVisibility = 'PUBLISHED' | 'HIDDEN'

export type ReviewSummary = Readonly<{
  reviewId: string
  orderId: string
  storeScore: string
  serviceScore: string
  staffScore: string
  compositeScore: string
  scoreIncluded: boolean
  visibilityStatus: ReviewVisibility
  content: string | null
  createdAt: string
  appealStatus: ReviewAppealStatus | null
  appealId: string | null
}>

export type ReviewDetail = Readonly<ReviewSummary & {
  appealReason: string | null
  appealCreatedAt: string | null
  decisionReason: string | null
  decidedAt: string | null
}>

export type ReviewPage = Readonly<{ page: number; pageSize: number; total: number; items: readonly ReviewSummary[] }>
export type AppealReceipt = Readonly<{ appealId: string; reviewId: string; status: ReviewAppealStatus; createdAt: string }>

const appealStatuses: readonly ReviewAppealStatus[] = ['SUBMITTED', 'PROCESSING', 'APPROVED', 'REJECTED']

function fail(): never { throw new Error('INVALID_RESPONSE') }
const nullable = <T>(v: unknown, decode: (v: unknown) => T): T | null => v === null ? null : decode(v)
const one = <T extends string>(v: unknown, options: readonly T[]): T => typeof v === 'string' && (options as readonly string[]).includes(v) ? v as T : fail()
const bool = (v: unknown): boolean => typeof v === 'boolean' ? v : fail()
const instant = (v: unknown): string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(v) && Number.isFinite(Date.parse(v)) && new Date(v).toISOString() === v ? v : fail()
const score = (v: unknown): string => typeof v === 'string' && /^[1-5]\.[0-9]$/.test(v) ? v : fail()
const text = (v: unknown, max: number): string => typeof v === 'string' && v.length >= 1 && v.length <= max ? v : fail()
const exact = (value: unknown, fields: string) => { const v = object(value); if (Object.keys(v).sort().join(',') !== fields.split(',').sort().join(',')) fail(); return v }

const summaryFields = 'reviewId,orderId,storeScore,serviceScore,staffScore,compositeScore,scoreIncluded,visibilityStatus,content,createdAt,appealStatus,appealId'
function summary(v: Record<string, unknown>): ReviewSummary {
  return {
    reviewId: id(v.reviewId), orderId: id(v.orderId),
    storeScore: score(v.storeScore), serviceScore: score(v.serviceScore), staffScore: score(v.staffScore),
    compositeScore: score(v.compositeScore), scoreIncluded: bool(v.scoreIncluded),
    visibilityStatus: one(v.visibilityStatus, ['PUBLISHED', 'HIDDEN'] as const),
    content: nullable(v.content, x => text(x, 2000)),
    createdAt: instant(v.createdAt),
    appealStatus: nullable(v.appealStatus, x => one(x, appealStatuses)),
    appealId: nullable(v.appealId, id),
  }
}

/** 列表/详情条目同样 exact-key：未知键失败关闭（沿 refund/aftersale 惯例）。 */
function checkedSummary(value: unknown): ReviewSummary { return summary(exact(value, summaryFields)) }

export function decodeReviewPage(value: unknown): ReviewPage {
  const v = exact(value, 'page,pageSize,total,items')
  if (!Number.isInteger(v.page) || v.page < 1 || v.page > 10000 || !Number.isInteger(v.pageSize) || v.pageSize < 1 || v.pageSize > 50
    || !Number.isSafeInteger(v.total) || v.total < 0 || !Array.isArray(v.items) || v.items.length > v.pageSize) fail()
  return { page: v.page, pageSize: v.pageSize, total: v.total, items: v.items.map(x => checkedSummary(x)) }
}

export function decodeReviewDetail(value: unknown): ReviewDetail {
  const v = exact(value, `${summaryFields},appealReason,appealCreatedAt,decisionReason,decidedAt`)
  return { ...summary(v), appealReason: nullable(v.appealReason, x => text(x, 1000)), appealCreatedAt: nullable(v.appealCreatedAt, instant), decisionReason: nullable(v.decisionReason, x => text(x, 1000)), decidedAt: nullable(v.decidedAt, instant) }
}

export function decodeAppealReceipt(value: unknown): AppealReceipt {
  const v = exact(value, 'appealId,reviewId,status,createdAt')
  return { appealId: id(v.appealId), reviewId: id(v.reviewId), status: one(v.status, appealStatuses), createdAt: instant(v.createdAt) }
}

/** 每条评价最多申诉一次：该 409 对该 payload 终局（申诉事实不会回退），可退幂等槽后重读。
 *  COMMON_CONFLICT 不在其中（23号 §5.7：请求锁忙结局未知，只能按原 X-Request-Id 重试）。 */
export function isDefiniteAppealConflict(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 409 && error.code === 'REVIEW_APPEAL_ALREADY_USED'
}

/** 契约边界校验：reason trim 后非空、1..1000 码点、无孤立 surrogate（06号 VARCHAR(1000)）。 */
export function appealReasonInput(reason: string): string {
  const trimmed = reason.trim()
  const points = Array.from(trimmed)
  if (points.length < 1 || points.length > 1000 || points.some(point => { const scalar = point.codePointAt(0)!; return scalar >= 0xd800 && scalar <= 0xdfff })) throw new Error('APPEAL_REASON_LENGTH')
  return trimmed
}

/** Contract56 client；仅商家工作区（OWNER）可用，无夹具回退、无换身份面。 */
export class ReviewAppealClient {
  private rejectionProofs = new WeakMap<object, { slot: string; command: Command }>()
  constructor(private api: ConsumerApi) {}

  private slot(reviewId: string): string {
    const c = this.api.scope.capture().context
    if (c.workspace !== 'merchant' || !c.merchantId || !c.storeId) throw new Error('MERCHANT_ENTRY_REQUIRED')
    return `merchant-review-appeal:${id(c.userId)}:${c.merchantId}:${c.storeId}:${id(reviewId)}:appeal`
  }

  list(merchantId: string, storeId: string, page: number, pageSize: number): Promise<ReviewPage> {
    const data: Record<string, unknown> = { merchantId: id(merchantId), storeId: id(storeId), page, pageSize }
    if (!Number.isInteger(page) || page < 1 || page > 10000 || !Number.isInteger(pageSize) || pageSize < 1 || pageSize > 50) throw new Error('INVALID_QUERY')
    return this.api.request({ path: '/api/v1/merchant/reviews', method: 'GET', data }, value => {
      const result = decodeReviewPage(value)
      if (result.page !== page || result.pageSize !== pageSize) fail()
      return result
    })
  }

  detail(reviewId: string): Promise<ReviewDetail> {
    const target = id(reviewId)
    return this.api.request({ path: `/api/v1/merchant/reviews/${target}`, method: 'GET' }, value => {
      const detail = decodeReviewDetail(value)
      if (detail.reviewId !== target) fail()
      return detail
    })
  }

  appeal(reviewId: string, reason: string): Promise<AppealReceipt> {
    const target = id(reviewId)
    const body = appealReasonInput(reason)
    const slot = this.slot(target)
    return this.api.write(slot, { path: `/api/v1/merchant/reviews/${target}/appeal`, method: 'POST', data: { reason: body } }, value => {
      const receipt = decodeAppealReceipt(value)
      if (receipt.reviewId !== target) fail()
      return receipt
    }, undefined, (error, command) => {
      if (isDefiniteAppealConflict(error) && error && typeof error === 'object') this.rejectionProofs.set(error, { slot, command })
    })
  }

  pendingAppeal(reviewId: string): { reason: string } | null {
    const command = this.api.pendingCommand(this.slot(id(reviewId)))
    const data = command?.data as { reason?: unknown } | undefined
    return command && data && typeof data.reason === 'string' ? { reason: data.reason } : null
  }

  /** 服务器对原 payload 的终局拒绝（一次性申诉/状态冲突）后，方可退役幂等槽。 */
  retireConflict(reviewId: string, error: unknown): void {
    if (!isDefiniteAppealConflict(error) || !error || typeof error !== 'object') throw new Error('UNCONFIRMED_WRITE')
    const proof = this.rejectionProofs.get(error), slot = this.slot(id(reviewId))
    if (!proof || proof.slot !== slot) throw new Error('UNCONFIRMED_WRITE')
    this.api.retireRejectedCommand(slot, proof.command)
    this.rejectionProofs.delete(error)
  }
}
