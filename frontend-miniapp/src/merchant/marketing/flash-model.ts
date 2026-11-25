import { ApiError } from '../../shared/request'
import type { FlashPriceRecord, FlashPriceStatus, PriceModeLabel } from './flash-api'

// Contract-77 / 41号 §4 pure view mapping: no Taro, no network — shared by the flash pages
// and tests. 状态读侧语义（77号 §2.2）：SCHEDULED/ACTIVE 按时间窗细分展示；到 endAt 即已结束。

export type FlashStatusTone = 'draft' | 'review' | 'rejected' | 'scheduled' | 'online' | 'ended' | 'canceled' | 'paused'
export type FlashStatusPill = Readonly<{ text: string; tone: FlashStatusTone }>

/** 状态胶囊：SCHEDULED=待开始；ACTIVE 按窗口分待开始/进行中/已结束（读侧时间判定）。 */
export function flashStatusPill(status: FlashPriceStatus | string, now: number, startAt: string | null, endAt: string | null): FlashStatusPill {
  const windowed = (before: FlashStatusPill, during: FlashStatusPill, after: FlashStatusPill): FlashStatusPill => {
    const start = startAt ? Date.parse(startAt) : NaN
    const end = endAt ? Date.parse(endAt) : NaN
    if (Number.isFinite(start) && now < start) return before
    if (Number.isFinite(end) && now >= end) return after
    return during
  }
  switch (status) {
    case 'DRAFT': return { text: '草稿', tone: 'draft' }
    case 'PENDING_REVIEW': return { text: '待审核', tone: 'review' }
    case 'REJECTED': return { text: '已驳回', tone: 'rejected' }
    case 'SCHEDULED': return { text: '待开始', tone: 'scheduled' }
    case 'ACTIVE': return windowed({ text: '待开始', tone: 'scheduled' }, { text: '进行中', tone: 'online' }, { text: '已结束', tone: 'ended' })
    case 'PAUSED': return { text: '已暂停', tone: 'paused' }
    case 'ENDED': return { text: '已结束', tone: 'ended' }
    case 'CANCELED': return { text: '已取消', tone: 'canceled' }
    default: return { text: status, tone: 'draft' }
  }
}

/** 77号 §2.2：商家可编辑仅 DRAFT/REJECTED（REJECTED 编辑后须重新 submit）。 */
export const EDITABLE_FLASH_STATUSES: readonly string[] = ['DRAFT', 'REJECTED']
export function flashEditable(status: string): boolean {
  return EDITABLE_FLASH_STATUSES.includes(status)
}

/** 77号 §3.5：取消仅 DRAFT/REJECTED/PENDING_REVIEW；已生效场次只能由运营结束。 */
export const CANCELLABLE_FLASH_STATUSES: readonly string[] = ['DRAFT', 'REJECTED', 'PENDING_REVIEW']
export function flashCancellable(status: string): boolean {
  return CANCELLABLE_FLASH_STATUSES.includes(status)
}

/** 优惠形式 chips（41号 §4.2：仅为展示标签，两种都落 priceMode=DISCOUNT）。 */
export const PRICE_MODE_OPTIONS: readonly { value: PriceModeLabel; label: string }[] = [
  { value: 'LIMITED_DISCOUNT', label: '限时折扣' },
  { value: 'FLASH_SALE', label: '限时秒杀' },
]

/** 折扣合法性：0 < 折 < 10，最多 1 位小数（77号 §2.1）。 */
export function discountRateValid(rate: string): boolean {
  if (!/^[0-9](\.[0-9])?$/.test(rate)) return false
  const value = Number(rate)
  return value > 0 && value < 10
}

const centsOf = (money: string): bigint => {
  const match = /^(\d+)\.(\d{2})$/.exec(money)
  if (!match) throw new Error('INVALID_MONEY')
  return BigInt(match[1]) * 100n + BigInt(match[2])
}
const tenthsOf = (rate: string): bigint => {
  const match = /^([0-9])(?:\.([0-9]))?$/.exec(rate)
  if (!match) throw new Error('INVALID_RATE')
  return BigInt(match[1]) * 10n + (match[2] ? BigInt(match[2]) : 0n)
}
const moneyOfCents = (cents: bigint): string => `${cents / 100n}.${(cents % 100n).toString().padStart(2, '0')}`

/** 限时价预览：round(listPrice × discountRate / 10, 2, HALF_UP)（77号 §1.1.5 前端口径，服务端复算为准）。 */
export function flashPricePreview(listPrice: string, discountRate: string): string | null {
  if (!/^\d+\.\d{2}$/.test(listPrice) || !discountRateValid(discountRate)) return null
  // 分 × 折（十分位）→ 除以 100；正数 HALF_UP：floor((L×R + 50) / 100)。
  const flashCents = (centsOf(listPrice) * tenthsOf(discountRate) + 50n) / 100n
  if (flashCents <= 0n) return null
  return moneyOfCents(flashCents)
}

/** 正整数配额校验：空串=不限（null 提示）；其余须为 1..max 的正整数（77号 §2.1）。 */
export function quotaProblem(value: string, max: number, label: string): string | null {
  const trimmed = value.trim()
  if (trimmed === '') return null
  if (!/^\d+$/.test(trimmed) || Number(trimmed) < 1 || Number(trimmed) > max) return `${label}需为 1-${max} 的正整数，留空表示不限`
  return null
}
export function parseQuota(value: string): number | undefined {
  const trimmed = value.trim()
  return /^\d+$/.test(trimmed) && Number(trimmed) >= 1 ? Number(trimmed) : undefined
}

/** 北京时间日期+时刻 → ISO-8601 UTC（沿用 P1 campaigns 的北京时间选择/UTC 提交口径）。 */
export function beijingDateTimeToUtcIso(date: string, time: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^([01]\d|2[0-3]):[0-5]\d$/.test(time)) throw new Error('INVALID_DATE')
  const value = new Date(`${date}T${time}:00.000+08:00`)
  if (!Number.isFinite(value.getTime())) throw new Error('INVALID_DATE')
  return value.toISOString()
}

/** ISO UTC → 北京时间「YYYY-MM-DD HH:mm」展示文本。 */
export function beijingDateTimeText(iso: string | null): string {
  if (!iso) return '--'
  const shifted = new Date(Date.parse(iso) + 8 * 60 * 60 * 1000)
  if (!Number.isFinite(shifted.getTime())) return '--'
  return shifted.toISOString().slice(0, 16).replace('T', ' ')
}

/** ISO UTC → 北京时间日期/时刻两段（表单回显）。 */
export function beijingDateTimeParts(iso: string | null): { date: string; time: string } {
  if (!iso) return { date: '', time: '' }
  const shifted = new Date(Date.parse(iso) + 8 * 60 * 60 * 1000)
  if (!Number.isFinite(shifted.getTime())) return { date: '', time: '' }
  const isoText = shifted.toISOString()
  return { date: isoText.slice(0, 10), time: isoText.slice(11, 16) }
}

/** 场次卡数据行：已售/总量（无总量「不限」）。 */
export function flashQuotaLine(record: Pick<FlashPriceRecord, 'soldCount' | 'totalQuota'>): string {
  return `已售 ${record.soldCount}/${record.totalQuota === null ? '不限' : record.totalQuota}`
}

/** 77号 §7 错误码 → 商家端中文提示（数字不伪造，仅提示语义）。 */
export function flashPriceMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'FLASH_PRICE_WINDOW_OVERLAP') return '该服务已有重叠的秒杀窗口（含待审核/待开始/进行中），请调整有效期。'
    if (error.code === 'FLASH_PRICE_INVALID') return '折扣或价格不满足规则（限时价需低于服务当前价），请调整后重试。'
    if (error.code === 'FLASH_PRICE_STATE_NOT_ALLOWED') return '当前状态不允许该操作，请刷新列表后重试。'
    if (error.code === 'FLASH_PRICE_NOT_FOUND') return '秒杀场次不存在或不属于本店服务，请返回列表刷新。'
    if (error.code === 'COMMON_CONFLICT' && error.statusCode === 409) return '内容已被修改（版本冲突），请刷新后重试。'
    if (error.code === 'IDEMPOTENCY_KEY_CONFLICT') return '同一请求编号已被其他内容使用，请刷新后重试。'
    if (error.statusCode === 401) return '登录已失效，请重新登录。'
    if (error.statusCode === 404) return '服务或秒杀场次不存在，请返回列表刷新。'
    if (error.statusCode === 503) return '服务暂不可用，操作结果未确认；请用原按钮重试（不会重复创建）。'
  }
  if (error instanceof Error) {
    if (error.message === 'PENDING_WRITE_CHANGED') return '上次提交结果尚未确认，请先按原内容重试原提交，不要修改后另发。'
    if (error.message === 'WORKSPACE_PATH_MISMATCH') return '请从商家工作台进入营销工具。'
    if (error.message === 'INVALID_RESPONSE') return '服务返回数据异常，请稍后重试。'
  }
  return '操作未确认成功，请重试；不会重复创建。'
}
