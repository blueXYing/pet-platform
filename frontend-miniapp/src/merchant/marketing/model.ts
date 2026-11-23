import { CAMPAIGN_THEME_CODES, type CampaignPlayType, type CampaignThemeCode, type MyCampaign } from './api'

// Contract-76 / 41号 §3 pure view mapping: no Taro, no network — shared by pages and tests.

/** 41号 §3.2 白名单展示名（服务端 themeLabel 同表；前端 chips 与解码共用一份 code 表）。 */
const CAMPAIGN_THEME_LABELS: Record<CampaignThemeCode, string> = {
  SPRING_FESTIVAL: '春节', MID_AUTUMN: '中秋', NATIONAL_DAY: '国庆',
  CHRISTMAS: '圣诞', NEW_YEAR: '元旦', DOUBLE_11: '双11', OTHER: '其他',
}
export const CAMPAIGN_THEMES: readonly { code: CampaignThemeCode; label: string }[] =
  CAMPAIGN_THEME_CODES.map(code => ({ code, label: CAMPAIGN_THEME_LABELS[code] }))

export function campaignThemeLabel(code: string | null): string {
  return code && code in CAMPAIGN_THEME_LABELS ? CAMPAIGN_THEME_LABELS[code as CampaignThemeCode] : '未选择'
}

/** 41号 §3.1: P1 仅 THEME 可选；P2 限时折扣 / P3 满减优惠置灰「待开放」（41号 §3.4：节日套餐/
 *  买赠有礼/抽奖活动不展示）。 */
export type PlayTypeOption = Readonly<{ type: string; label: string; available: boolean; hint?: string }>
export const PLAY_TYPE_OPTIONS: readonly PlayTypeOption[] = [
  { type: 'THEME', label: '主题活动', available: true },
  { type: 'FLASH_PRICE', label: '限时折扣', available: false, hint: '待开放' },
  { type: 'MERCHANT_COUPON', label: '满减优惠', available: false, hint: '待开放' },
]
export function playTypeOf(option: PlayTypeOption): CampaignPlayType | null {
  return option.available && option.type === 'THEME' ? 'THEME' : null
}

export type CampaignStatusTone = 'draft' | 'review' | 'rejected' | 'scheduled' | 'online' | 'ended' | 'canceled' | 'paused'
export type CampaignStatusPill = Readonly<{ text: string; tone: CampaignStatusTone }>

/** 状态胶囊（HANDOFF §4.5.3）：status + 时间窗共同决定展示；ONLINE 需再按窗口分待开始/进行中/已结束。 */
export function campaignStatusPill(status: string, now: number, startAt: string | null, endAt: string | null): CampaignStatusPill {
  const windowed = (before: CampaignStatusPill, during: CampaignStatusPill, after: CampaignStatusPill): CampaignStatusPill => {
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
    case 'ONLINE': return windowed({ text: '待开始', tone: 'scheduled' }, { text: '进行中', tone: 'online' }, { text: '已结束', tone: 'ended' })
    case 'ENDED': return { text: '已结束', tone: 'ended' }
    case 'CANCELED': return { text: '已取消', tone: 'canceled' }
    case 'PAUSED': return { text: '已暂停', tone: 'paused' }
    default: return { text: status, tone: 'draft' }
  }
}

/** 商家可编辑状态（76号 §4.4）：仅 DRAFT/REJECTED；其余只读。 */
export const EDITABLE_CAMPAIGN_STATUSES: readonly string[] = ['DRAFT', 'REJECTED']
export function campaignEditable(status: string): boolean {
  return EDITABLE_CAMPAIGN_STATUSES.includes(status)
}

/** 北京时间日期 → ISO-8601 UTC（41号 §3.1：前端按北京时间选择，提交 UTC）。 */
export function beijingDayToUtcIso(date: string, end = false): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new Error('INVALID_DATE')
  const value = new Date(`${date}T${end ? '23:59:59.999' : '00:00:00.000'}+08:00`)
  if (!Number.isFinite(value.getTime())) throw new Error('INVALID_DATE')
  return value.toISOString()
}

/** ISO UTC → 北京时间日期文本（列表/表单回显，缺失或非法显示 --）。 */
export function beijingDateText(iso: string | null): string {
  if (!iso) return '--'
  const date = new Date(iso)
  if (!Number.isFinite(date.getTime())) return '--'
  return new Date(date.getTime() + 8 * 60 * 60 * 1000).toISOString().slice(0, 10)
}

/** 列表卡副标题：subtitle 优先，缺省回退 description（不伪造内容）。 */
export function campaignSubtitleLine(campaign: Pick<MyCampaign, 'subtitle' | 'description'>): string | null {
  return campaign.subtitle ?? campaign.description ?? null
}
