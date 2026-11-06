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
// - 2026-11-06 CCR-M-REVIEW-VIEW-001（56号 §7）：列表/详情条目增量可空键 reviewerNickname（买家
//   昵称原样，不脱敏）/reviewerAvatarUrl（https 头像外链）；键存在即严格校验，缺省按 null（旧形状
//   仍可解码），未知键仍失败关闭。新增只读 GET /api/v1/merchant/reviews/summary（merchantId+storeId
//   同列表坐标纪律）：恰四键 totalReviews/scoredCount/averageScore/scoreCounts（"1".."5" 五桶）。

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
  /** 买家昵称原样（56号 §7）；null（未设置/账号缺失/旧服务端）时页面显示「匿名用户」。 */
  reviewerNickname: string | null
  /** 微信头像 https 外链（不可信外部内容）；null 或加载失败时页面显示首字占位。 */
  reviewerAvatarUrl: string | null
}>

export type ReviewDetail = Readonly<ReviewSummary & {
  appealReason: string | null
  appealCreatedAt: string | null
  decisionReason: string | null
  decidedAt: string | null
}>

export type ReviewPage = Readonly<{ page: number; pageSize: number; total: number; items: readonly ReviewSummary[] }>
export type AppealReceipt = Readonly<{ appealId: string; reviewId: string; status: ReviewAppealStatus; createdAt: string }>

/** 56号 §7 星级桶键（按 ROUND(composite_score) 落桶，服务端计算）。 */
export type ReviewStarKey = '1' | '2' | '3' | '4' | '5'
export const reviewStarKeys: readonly ReviewStarKey[] = ['1', '2', '3', '4', '5']
/**
 * 门店评价聚合（只读事实，前端只展示不重算均分）：totalReviews 含不计分历史评价；
 * scoredCount/averageScore/scoreCounts 仅统计计分评价；averageScore 一位小数，scoredCount=0 时 null。
 */
export type ReviewAggregate = Readonly<{
  totalReviews: number
  scoredCount: number
  averageScore: string | null
  scoreCounts: Readonly<Record<ReviewStarKey, number>>
}>

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
/** 56号 §7 增量可空键：仅 OWNER 本店读侧返回；旧服务端不带这两键时按 null 解码。 */
const identityFields: readonly string[] = ['reviewerNickname', 'reviewerAvatarUrl']
const has = (v: Record<string, unknown>, key: string) => Object.prototype.hasOwnProperty.call(v, key)
/** 必备键须全部出现，额外只允许两枚身份增量键；其余未知键一律失败关闭。 */
const exactWithIdentity = (value: unknown, fields: string) => {
  const v = object(value), required = fields.split(',')
  if (required.some(key => !has(v, key)) || Object.keys(v).some(key => !required.includes(key) && !identityFields.includes(key))) fail()
  return v
}
/** user_account.nickname VARCHAR(64)：1..64 码点，原样（不脱敏）。 */
const nickname = (v: unknown): string => typeof v === 'string' && Array.from(v).length >= 1 && Array.from(v).length <= 64 ? v : fail()
/** 服务端只投影 https 外链（≤512，asset: 内部形式永不出域）。 */
const avatarUrl = (v: unknown): string => typeof v === 'string' && v.length <= 512 && /^https:\/\/\S+$/.test(v) ? v : fail()
const optional = <T>(v: Record<string, unknown>, key: string, decode: (x: unknown) => T): T | null => has(v, key) ? nullable(v[key], decode) : null

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
    reviewerNickname: optional(v, 'reviewerNickname', nickname),
    reviewerAvatarUrl: optional(v, 'reviewerAvatarUrl', avatarUrl),
  }
}

/** 列表/详情条目同样 exact-key（仅放宽两枚身份增量键）：未知键失败关闭（沿 refund/aftersale 惯例）。 */
function checkedSummary(value: unknown): ReviewSummary { return summary(exactWithIdentity(value, summaryFields)) }

export function decodeReviewPage(value: unknown): ReviewPage {
  const v = exact(value, 'page,pageSize,total,items')
  if (!Number.isInteger(v.page) || v.page < 1 || v.page > 10000 || !Number.isInteger(v.pageSize) || v.pageSize < 1 || v.pageSize > 50
    || !Number.isSafeInteger(v.total) || v.total < 0 || !Array.isArray(v.items) || v.items.length > v.pageSize) fail()
  return { page: v.page, pageSize: v.pageSize, total: v.total, items: v.items.map(x => checkedSummary(x)) }
}

export function decodeReviewDetail(value: unknown): ReviewDetail {
  const v = exactWithIdentity(value, `${summaryFields},appealReason,appealCreatedAt,decisionReason,decidedAt`)
  return { ...summary(v), appealReason: nullable(v.appealReason, x => text(x, 1000)), appealCreatedAt: nullable(v.appealCreatedAt, instant), decisionReason: nullable(v.decisionReason, x => text(x, 1000)), decidedAt: nullable(v.decidedAt, instant) }
}

const count = (v: unknown): number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0 ? v : fail()
/** 一位小数 0.0..5.0（服务端 HALF_UP，前端只展示不重算）。 */
const average = (v: unknown): string => typeof v === 'string' && /^(?:[0-4]\.[0-9]|5\.0)$/.test(v) ? v : fail()

/**
 * 56号 §7 门店评价聚合：恰四键 + 恰五桶；桶和须等于 scoredCount（每条计分评价恰落一桶），
 * scoredCount ≤ totalReviews，averageScore 为 null 当且仅当 scoredCount=0（空店 0/0/null/全 0）。
 */
export function decodeReviewAggregate(value: unknown): ReviewAggregate {
  const v = exact(value, 'totalReviews,scoredCount,averageScore,scoreCounts')
  const totalReviews = count(v.totalReviews), scoredCount = count(v.scoredCount)
  const buckets = exact(v.scoreCounts, reviewStarKeys.join(','))
  const scoreCounts = { '1': count(buckets['1']), '2': count(buckets['2']), '3': count(buckets['3']), '4': count(buckets['4']), '5': count(buckets['5']) }
  const averageScore = nullable(v.averageScore, average)
  const bucketed = reviewStarKeys.reduce((sum, key) => sum + scoreCounts[key], 0)
  if (scoredCount > totalReviews || bucketed !== scoredCount || (averageScore === null) !== (scoredCount === 0)) fail()
  return { totalReviews, scoredCount, averageScore, scoreCounts }
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

  /** 56号 §7 门店评价聚合（只读）：merchantId+storeId 须等于当前商家工作区（同列表纪律）。 */
  summary(merchantId: string, storeId: string): Promise<ReviewAggregate> {
    const data: Record<string, unknown> = { merchantId: id(merchantId), storeId: id(storeId) }
    return this.api.request({ path: '/api/v1/merchant/reviews/summary', method: 'GET', data }, decodeReviewAggregate)
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
