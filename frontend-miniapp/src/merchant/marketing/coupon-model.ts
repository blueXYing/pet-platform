import { ApiError } from '../../shared/request'
import type { CouponCampaignRecord, CouponCampaignStatus, CouponType } from './coupon-api'

// Contract-79 / 41号 §5 pure view mapping: no Taro, no network — shared by the coupon pages
// and tests. 状态文案按任务线形状固定（ACTIVE 不再做读侧时间窗细分，服务端状态机为准）。

export type CouponStatusTone = 'draft' | 'review' | 'rejected' | 'canceled' | 'scheduled' | 'online' | 'paused' | 'ended'
export type CouponStatusPill = Readonly<{ text: string; tone: CouponStatusTone }>

/** 状态胶囊：79号 §2.4 八态固定文案。 */
export function couponStatusPill(status: CouponCampaignStatus | string): CouponStatusPill {
  switch (status) {
    case 'DRAFT': return { text: '草稿', tone: 'draft' }
    case 'PENDING_REVIEW': return { text: '待审核', tone: 'review' }
    case 'REJECTED': return { text: '已驳回', tone: 'rejected' }
    case 'CANCELED': return { text: '已取消', tone: 'canceled' }
    case 'SCHEDULED': return { text: '待开始', tone: 'scheduled' }
    case 'ACTIVE': return { text: '进行中', tone: 'online' }
    case 'PAUSED': return { text: '已暂停', tone: 'paused' }
    case 'ENDED': return { text: '已结束', tone: 'ended' }
    default: return { text: status, tone: 'draft' }
  }
}

/** 79号 §2.4：商家可编辑仅 DRAFT/REJECTED（REJECTED 编辑后须重新 submit）；其余状态只读。 */
export const EDITABLE_COUPON_STATUSES: readonly string[] = ['DRAFT', 'REJECTED']
export function couponEditable(status: string): boolean {
  return EDITABLE_COUPON_STATUSES.includes(status)
}

/** 优惠形式 chips（79号 §9 不做清单：折扣券/兑换券置灰「暂不支持」，不可选）。 */
export const COUPON_TYPE_CHIPS: readonly { value: CouponType | null; label: string; available: boolean; hint?: string }[] = [
  { value: 'THRESHOLD', label: '满减券', available: true },
  { value: null, label: '折扣券', available: false, hint: '暂不支持' },
  { value: 'NO_THRESHOLD', label: '立减券', available: true },
  { value: null, label: '兑换券', available: false, hint: '暂不支持' },
]

/** 列表卡副文案：满减券「满 X 元可用」（金额去尾零展示），无门槛券「本店服务通用」。 */
export function couponSubline(record: Pick<CouponCampaignRecord, 'couponType' | 'thresholdAmount'>): string {
  if (record.couponType === 'THRESHOLD' && record.thresholdAmount !== null) return `满 ${moneyDisplay(record.thresholdAmount)} 元可用`
  return '本店服务通用'
}

/** 展示用金额：两位小数字符串去尾零（"100.00"→"100"，"88.50"→"88.5"），纯字符串处理。 */
export function moneyDisplay(value: string): string {
  const match = /^(\d+)\.(\d{2})$/.exec(value)
  if (!match) return value
  const frac = match[2].replace(/0+$/, '')
  return frac === '' ? match[1] : `${match[1]}.${frac}`
}

/** 表单金额输入归一：非负、最多两位小数、> 0 → "x.xx"；非法或 0 返回 null（纯字符串/整数分，不用浮点）。 */
export function normalizeMoneyInput(value: string): string | null {
  const trimmed = value.trim()
  if (!/^(0|[1-9][0-9]*)(\.[0-9]{1,2})?$/.test(trimmed)) return null
  const [whole, frac = ''] = trimmed.split('.')
  const cents = BigInt(whole) * 100n + BigInt(frac === '' ? '0' : frac.padEnd(2, '0'))
  if (cents === 0n) return null
  return `${whole}.${frac === '' ? '00' : frac.padEnd(2, '0')}`
}

/** 金额比较（两位小数字符串 → 整数分）：a ≥ b。 */
export function moneyAtLeast(a: string, b: string): boolean {
  const centsOf = (value: string): bigint => {
    const match = /^(\d+)\.(\d{2})$/.exec(value)
    if (!match) throw new Error('INVALID_MONEY')
    return BigInt(match[1]) * 100n + BigInt(match[2])
  }
  return centsOf(a) >= centsOf(b)
}

/** 正整数输入：空串/null 提示缺失；其余须为 min..max 的正整数，返回数字或 null。 */
export function parseCountInput(value: string, min: number, max: number): number | null {
  const trimmed = value.trim()
  if (!/^\d+$/.test(trimmed)) return null
  const parsed = Number(trimmed)
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) return null
  return parsed
}

/** 北京时间日期 → ISO-8601 UTC：开始日 00:00:00 / 结束日 23:59:59（79号 §3.1 示例口径）。 */
export function beijingDayToUtcIso(date: string, end = false): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new Error('INVALID_DATE')
  const value = new Date(`${date}T${end ? '23:59:59.000' : '00:00:00.000'}+08:00`)
  if (!Number.isFinite(value.getTime())) throw new Error('INVALID_DATE')
  return value.toISOString()
}

/** ISO UTC → 北京时间「YYYY-MM-DD」（列表「有效期至」与表单回显）。 */
export function beijingDateText(iso: string | null): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (!Number.isFinite(date.getTime())) return ''
  return new Date(date.getTime() + 8 * 60 * 60 * 1000).toISOString().slice(0, 10)
}

/** 核销率四位小数字符串 → 百分比文本（0.6200→"62%"；0.6250→"62.50%"；整数百分位去掉 ".00"）。 */
export function redemptionRatePercent(rate: string): string | null {
  const match = /^(\d+)\.(\d{4})$/.exec(rate)
  if (!match) return null
  const percentHundredths = BigInt(match[1]) * 10000n + BigInt(match[2])
  const whole = percentHundredths / 100n
  const frac = (percentHundredths % 100n).toString().padStart(2, '0')
  return frac === '00' ? `${whole}%` : `${whole}.${frac}%`
}

/** 79号 §7 错误码 → 商家端中文提示（数字不伪造，仅提示语义）。 */
export function couponMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'MERCHANT_COUPON_NAME_DUPLICATE') return '同一商家已有同名活动，请换一个名称。'
    if (error.code === 'MERCHANT_COUPON_INVALID') return '面额、门槛、预算或有效期不满足规则，请调整后重试。'
    if (error.code === 'MERCHANT_COUPON_STATE_NOT_ALLOWED') return '当前状态不允许该操作，请刷新列表后重试。'
    if (error.code === 'MERCHANT_COUPON_NOT_FOUND') return '活动不存在或不属于本店，请返回列表刷新。'
    if (error.code === 'MERCHANT_COUPON_NOT_ELIGIBLE') return '当前账号无权执行此操作（仅商家主账号可管理优惠券）。'
    if (error.code === 'COMMON_CONFLICT' && error.statusCode === 409) return '内容已被修改（版本冲突），请刷新后重试。'
    if (error.code === 'IDEMPOTENCY_KEY_CONFLICT') return '同一请求编号已被其他内容使用，请刷新后重试。'
    if (error.statusCode === 401) return '登录已失效，请重新登录。'
    if (error.statusCode === 404) return '活动不存在，请返回列表刷新。'
    if (error.statusCode === 503) return '服务暂不可用，操作结果未确认；请用原按钮重试（不会重复创建）。'
  }
  if (error instanceof Error) {
    if (error.message === 'PENDING_WRITE_CHANGED') return '上次提交结果尚未确认，请先按原内容重试原提交，不要修改后另发。'
    if (error.message === 'WORKSPACE_PATH_MISMATCH') return '请从商家工作台进入营销工具。'
    if (error.message === 'INVALID_RESPONSE') return '服务返回数据异常，请稍后重试。'
  }
  return '操作未确认成功，请重试；不会重复创建。'
}
