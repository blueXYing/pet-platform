// 意见反馈（契约 67 / PRD 5.1.28 投诉类；设计稿 130:17185）。纯视图模型。
// 字段口径=PRD 原文：四类型胶囊（功能建议/问题反馈/服务投诉/其他）、联系方式选填
// （提示语「手机号或微信号，方便我们联系您」）、内容必填 500 字以内。图片附件 V1 不做
// （CCR-CS-FEEDBACK-001 §3.1：私有资产通道为售后专用，反馈附件另立项）。

export type FeedbackType = 'FEATURE_SUGGESTION' | 'BUG_REPORT' | 'SERVICE_COMPLAINT' | 'OTHER'

export type FeedbackTypeOption = Readonly<{ code: FeedbackType; label: string }>

// PRD 5.1.28 原文四类型，词表冻结不新增。
export const feedbackTypeOptions: readonly FeedbackTypeOption[] = [
  { code: 'FEATURE_SUGGESTION', label: '功能建议' },
  { code: 'BUG_REPORT', label: '问题反馈' },
  { code: 'SERVICE_COMPLAINT', label: '服务投诉' },
  { code: 'OTHER', label: '其他' },
]

export function isFeedbackType(value: unknown): value is FeedbackType {
  return typeof value === 'string' && feedbackTypeOptions.some(option => option.code === value)
}

export function feedbackTypeLabel(code: string): string {
  return feedbackTypeOptions.find(option => option.code === code)?.label ?? '其他'
}

// PRD 5.1.28 统一工单状态机词表（冻结）；V1 只会出现 PENDING。
export const feedbackStatusLabels: Record<string, string> = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  WAITING_SUPPLEMENT: '待补充',
  RESOLVED: '已处理',
  CLOSED: '已关闭',
}

export const feedbackStatusFallback = '待处理'

export const feedbackContactPlaceholder = '手机号或微信号，方便我们联系您'
export const feedbackContentPlaceholder = '请描述您遇到的问题或建议（500字以内）'
export const feedbackContentMax = 500
export const feedbackContactMax = 64

export const codePointLength = (value: string) => Array.from(value).length

export function validateFeedback(draft: { type: FeedbackType | null; contact: string; content: string }): string | null {
  if (draft.type === null) return '请选择反馈类型'
  if (codePointLength(draft.contact.trim()) > feedbackContactMax) return '联系方式最多64字'
  if (draft.content.trim() === '') return '请填写反馈内容'
  if (codePointLength(draft.content) > feedbackContentMax) return '反馈内容最多500字'
  return null
}

export type FeedbackTicketSummary = Readonly<{
  ticketId: string
  type: FeedbackType
  status: string
  contentPreview: string
  createdAt: string
}>

export type FeedbackTicketDetail = Readonly<{
  ticketId: string
  type: FeedbackType
  status: string
  content: string
  contact: string | null
  contentPreview: string
  createdAt: string
  events: readonly { action: string; createdAt: string }[]
  replies: readonly unknown[]
  resolution: null
}>

export type FeedbackPage = Readonly<{
  items: readonly FeedbackTicketSummary[]
  page: number
  pageSize: number
  total: number
}>

function requireString(value: unknown): string {
  if (typeof value !== 'string') throw new Error('INVALID_RESPONSE')
  return value
}

export function decodeTicketSummary(value: unknown): FeedbackTicketSummary {
  if (typeof value !== 'object' || value === null) throw new Error('INVALID_RESPONSE')
  const v = value as Record<string, unknown>
  if (!isFeedbackType(v.type) || typeof v.status !== 'string'
      || !(v.status in feedbackStatusLabels) || typeof v.contentPreview !== 'string') {
    throw new Error('INVALID_RESPONSE')
  }
  const ticketId = requireString(v.ticketId)
  if (!/^[1-9][0-9]{0,18}$/.test(ticketId)) throw new Error('INVALID_RESPONSE')
  return {
    ticketId,
    type: v.type,
    status: v.status,
    contentPreview: v.contentPreview,
    createdAt: requireString(v.createdAt),
  }
}

export function decodeTicketPage(value: unknown): FeedbackPage {
  if (typeof value !== 'object' || value === null) throw new Error('INVALID_RESPONSE')
  const v = value as Record<string, unknown>
  if (!Array.isArray(v.items) || typeof v.page !== 'number' || typeof v.pageSize !== 'number'
      || typeof v.total !== 'number') throw new Error('INVALID_RESPONSE')
  return {
    items: v.items.map(decodeTicketSummary),
    page: v.page,
    pageSize: v.pageSize,
    total: v.total,
  }
}

export function decodeTicketDetail(value: unknown): FeedbackTicketDetail {
  if (typeof value !== 'object' || value === null) throw new Error('INVALID_RESPONSE')
  const v = value as Record<string, unknown>
  const summary = decodeTicketSummary({ ...v, contentPreview: v.contentPreview })
  if (typeof v.content !== 'string' || !Array.isArray(v.events) || !Array.isArray(v.replies)) {
    throw new Error('INVALID_RESPONSE')
  }
  if (v.contact !== null && typeof v.contact !== 'string') throw new Error('INVALID_RESPONSE')
  if (v.resolution !== null) throw new Error('INVALID_RESPONSE')
  // 冻结字段位：V1 运营处置面不存在，replies 恒为空数组（OpenAPI maxItems 0）。
  if (v.replies.length > 0) throw new Error('INVALID_RESPONSE')
  const events = v.events.map(item => {
    if (typeof item !== 'object' || item === null) throw new Error('INVALID_RESPONSE')
    const row = item as Record<string, unknown>
    return { action: requireString(row.action), createdAt: requireString(row.createdAt) }
  })
  return {
    ...summary,
    content: v.content,
    contact: v.contact as string | null,
    events,
    replies: v.replies,
    resolution: null,
  }
}

export function decodeCreateReceipt(value: unknown): FeedbackTicketSummary {
  if (typeof value !== 'object' || value === null) throw new Error('INVALID_RESPONSE')
  const v = value as Record<string, unknown>
  return decodeTicketSummary({ ...v, contentPreview: '' })
}

// ISO 时间戳 → 人读短句（MM-DD HH:mm；时间原文 ISO-8601 UTC 毫秒）。
export function formatTicketTime(iso: string): string {
  const parsed = new Date(iso)
  if (Number.isNaN(parsed.getTime())) return iso
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`
}
