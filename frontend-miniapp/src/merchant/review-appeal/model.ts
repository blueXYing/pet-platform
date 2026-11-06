import type { MerchantAdmission } from '../../shared/merchant-repositories'
import { reviewStarKeys, type ReviewAggregate, type ReviewAppealStatus, type ReviewStarKey, type ReviewSummary, type ReviewVisibility } from '../../shared/review-appeal-api'

/** 申诉状态文案（06号 review_appeal.status；PROCESSING 为保留值，服务端不产生）。 */
export const appealStatusText: Record<ReviewAppealStatus, string> = {
  SUBMITTED: '申诉处理中',
  PROCESSING: '申诉处理中',
  APPROVED: '申诉成立',
  REJECTED: '申诉未成立',
}
export const visibilityText: Record<ReviewVisibility, string> = {
  PUBLISHED: '公开展示',
  HIDDEN: '已隐藏',
}
/** WXSS 属性选择器永不匹配：Taro 4.1.5 会丢弃动态 data-*，状态变体必须用类名（同 aftersale model）。 */
export function appealTagClass(review: Pick<ReviewSummary, 'appealStatus' | 'visibilityStatus'>): string {
  if (review.appealStatus === 'APPROVED' || review.visibilityStatus === 'HIDDEN') return 'mrv-tag mrv-tag-approved'
  if (review.appealStatus === 'REJECTED') return 'mrv-tag mrv-tag-closed'
  if (review.appealStatus === null) return 'mrv-tag mrv-tag-none'
  return 'mrv-tag'
}
export function appealTagText(review: Pick<ReviewSummary, 'appealStatus' | 'visibilityStatus'>): string {
  if (review.visibilityStatus === 'HIDDEN') return '已隐藏'
  return review.appealStatus === null ? '未申诉' : appealStatusText[review.appealStatus]
}

/** 综合分展示（SSOT §11.1 40/40/20 内核事实，页面只展示不推导）。 */
export function compositeText(review: Pick<ReviewSummary, 'compositeScore'>): string {
  return `${review.compositeScore} 分`
}
export function dimensionText(review: Pick<ReviewSummary, 'storeScore' | 'serviceScore' | 'staffScore'>): string {
  return `门店 ${review.storeScore} · 服务 ${review.serviceScore} · 人员 ${review.staffScore}`
}

// ---- CCR-M-REVIEW-VIEW-001（56号 §7）：买家身份与门店评分聚合的纯展示推导 ----

/** 买家昵称原样展示（不脱敏）；null/空白时显示「匿名用户」。 */
export function reviewerName(review: Pick<ReviewSummary, 'reviewerNickname'>): string {
  const name = review.reviewerNickname
  return name !== null && name.trim().length > 0 ? name : '匿名用户'
}

/** 头像占位首字：取展示名首个码点（「匿名用户」→「匿」），不截断代理对。 */
export function reviewerInitial(review: Pick<ReviewSummary, 'reviewerNickname'>): string {
  return Array.from(reviewerName(review).trim())[0] ?? '匿'
}

/** 千分位整数（「1,790」）：不依赖运行时 locale。 */
export function groupedCount(value: number): string {
  return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/** 综合评分星行：服务端一位小数均分的四舍五入展示推导（同卡片 compositeScore 口径）；无均分时 0。 */
export function averageStars(aggregate: Pick<ReviewAggregate, 'averageScore'>): number {
  if (aggregate.averageScore === null) return 0
  const value = Math.round(Number(aggregate.averageScore))
  return Number.isFinite(value) ? Math.min(5, Math.max(0, value)) : 0
}

export type StarBar = Readonly<{ star: ReviewStarKey; count: number; widthPercent: number }>

/** 星级直方图（5 星在上）：比例条以最多一档为满格（原稿 23:14160 实测口径）；全 0 时空条，不伪造。 */
export function starBars(aggregate: Pick<ReviewAggregate, 'scoreCounts'>): readonly StarBar[] {
  const max = Math.max(0, ...reviewStarKeys.map(key => aggregate.scoreCounts[key]))
  return [...reviewStarKeys].reverse().map(star => {
    const count = aggregate.scoreCounts[star]
    return { star, count, widthPercent: max === 0 ? 0 : Math.round(count * 1000 / max) / 10 }
  })
}

/**
 * 好评率 =（4 星 + 5 星）/ scoredCount（用户 2026-11-06 批准的展示推导）。无计分评价时返回 null
 * （不显示，不伪造 0%）；整数百分比四舍五入，但「非全部好评」不显示 100%、「有好评」不显示 0%。
 */
export function goodRatePercent(aggregate: Pick<ReviewAggregate, 'scoredCount' | 'scoreCounts'>): number | null {
  const scored = aggregate.scoredCount
  if (scored <= 0) return null
  const good = aggregate.scoreCounts['4'] + aggregate.scoreCounts['5']
  if (good <= 0) return 0
  if (good >= scored) return 100
  const rounded = Math.floor((good * 200 + scored) / (scored * 2))
  return Math.min(99, Math.max(1, rounded))
}

/** 综合评分大卡文案：均分原样（服务端四舍五入）；无计分评价显示「暂无评分」。 */
export function averageText(aggregate: Pick<ReviewAggregate, 'averageScore'>): string {
  return aggregate.averageScore ?? '暂无评分'
}
export function scoredCountText(aggregate: Pick<ReviewAggregate, 'scoredCount'>): string {
  return `共 ${groupedCount(aggregate.scoredCount)} 条计分评价`
}

export function dateText(value: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return '—'
  // 产品时钟 UTC+8；设备时区设置不得移动展示时刻（同 aftersale dateText）。
  const local = new Date(date.getTime() + 8 * 60 * 60 * 1000)
  return `${local.toISOString().slice(0, 10)} ${local.toISOString().slice(11, 16)}`
}

/** UI 门控只补充展示，不授予任何权限；真实准入以服务端内核为准。 */
export function ownerAccess(view: MerchantAdmission, merchantId: string, storeId: string) {
  const readable = view.merchantId === merchantId && view.storeId === storeId
    && view.membershipKind === 'OWNER' && view.admission !== 'DENIED'
    && view.allowedActions.includes('merchant.review.read')
  const frozen = view.facts.merchantStatus === 'FROZEN' || view.facts.storeStatus === 'FROZEN'
  return { readable, writable: readable && !frozen, frozen }
}

/** 申诉入口：公开展示且尚未申诉的评价才可发起（每条评价最多一次，SSOT §11.3）。 */
export function canAppealEntry(review: Pick<ReviewSummary, 'appealStatus' | 'visibilityStatus'>, writable: boolean): boolean {
  return writable && review.visibilityStatus === 'PUBLISHED' && review.appealStatus === null
}

/** 申诉理由草稿边界（与契约一致；结构错误不出网络请求）。 */
export function reasonDraftError(reason: string): string | null {
  const points = Array.from(reason.trim())
  if (points.length < 1) return '请填写申诉理由'
  if (points.length > 1000) return '申诉理由不能超过 1000 个字符'
  if (points.some(point => { const scalar = point.codePointAt(0)!; return scalar >= 0xd800 && scalar <= 0xdfff })) return '申诉理由包含无效字符'
  return null
}

/** 页面错误文案（56号错误面：防枚举 404 同时覆盖他人/未知评价）。 */
export function appealMessage(error: unknown): string {
  if (error instanceof Error) {
    if (error.message === 'PENDING_WRITE_CHANGED') return '上次申诉结果尚未确认，请先重试原操作'
    if (error.message === 'APPEAL_REASON_LENGTH') return '请填写 1~1000 个字符的申诉理由'
    if (error.message === 'INVALID_RESPONSE') return '服务返回异常，请稍后重试'
    if (error.message === 'MERCHANT_ENTRY_REQUIRED' || error.message === 'WORKSPACE_PATH_MISMATCH') return '请从商家工作台进入评价管理'
  }
  const code = typeof error === 'object' && error !== null && 'code' in error ? String((error as { code: unknown }).code) : ''
  const status = typeof error === 'object' && error !== null && 'statusCode' in error ? Number((error as { statusCode: unknown }).statusCode) : 0
  if (code === 'REVIEW_APPEAL_ALREADY_USED') return '每条评价最多申诉一次'
  if (code === 'IDEMPOTENCY_KEY_CONFLICT') return '上次提交的申诉理由已变化，请核对后重新提交'
  if (code === 'COMMON_CONFLICT') return '申诉状态已变化，请刷新后核对'
  if (status === 401) return '登录已失效，请重新登录后再试'
  if (status === 403) return '当前账号无法执行该操作'
  if (status === 404) return '评价不存在或仅本店可申诉'
  if (status === 400) return '提交内容无效，请检查申诉理由后重试'
  if (status === 503) return '服务暂不可用，结果尚未确认，请稍后重试原操作'
  return '申诉结果尚未确认，请重试原操作；不会重复提交'
}
