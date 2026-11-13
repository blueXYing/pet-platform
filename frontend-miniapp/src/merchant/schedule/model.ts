// M-002 schedule maintenance view state (SCH-004 write side). Wire shapes follow the
// Schedule Write Contract v0.1 (docs/04-api/53-Schedule-Write-Contract-v0.1.md) as shipped by
// MerchantScheduleController (develop 02abe89, switch pet.schedule.command.http.enabled default
// OFF). Decoders stay strict exact-key (fail closed); the real repository is the single swap
// point and the preview repository is a contract mock, never a real integration claim.
//
// Contract anchors (CCR-SCH-SLOT-001, 2026-11-13 approved: unified store-shared slots):
// - Store windows: identity storeId+时间区间 (all services share one slot wall; NO
//   serviceId/windowKind on the window any more); minute-precision
//   half-open [startAt,endAt) with NO fixed 60-minute slot (SSOT §12.1); statuses
//   OPEN/CLOSED/SOLD_OUT where SOLD_OUT is a system-derived state (occupied >= capacity,
//   occupied = whole-store effective claims across ALL services sharing the pool) the
//   merchant can never set by hand and can lift only by raising capacity (same-transaction
//   re-judge). Overlap discipline is same-store (not per service/kind). Occupied windows
//   block close/time-change/capacity-decrease (409), allow
//   capacity-raise. No DELETE; windows never carry periodic fields (dayOfWeek/repeatWeekly/
//   copyNextWeek stay banned) — the weekly template is a SEPARATE store config face
//   (CCR-SCH-SLOT-003 week-template: read-time expansion + booking-time materialization),
//   never a window attribute. batch-close: single request capped at 200 intersecting
//   entries (400 COMMON_INVALID_ARGUMENT beyond), partial success allowed with blockedWindows
//   named per window (reasonCode SCHEDULE_WINDOW_STATE_NOT_ALLOWED). The store-windows list
//   read is paged (§3.3, #122): page 1..10000 / pageSize 1..50, filter-matched total in the
//   envelope, order start_at/id ascending — the pages always consume the paged mode.
// - Staff windows: AVAILABLE/CLOSED, same overlap discipline; shrinking availability is
//   protected by current assignments (409) and a whole-store feasibility re-check (409/503).
// - Capabilities: whole-set replace on a versioned head (SCHC-2); stale expectedVersion 409
//   COMMON_CONFLICT (re-read, never last-write-wins); duplicate serviceIds 400; LEGACY_
//   UNVERSIONED heads answer 503 and stay quarantined; removals require a reason.
// - Writes carry terminal UUID X-Request-Id (23号): same key+params replays the original
//   receipt, same key+different params 409 IDEMPOTENCY_KEY_CONFLICT. First create answers
//   201, replays and other commands 200 — the page treats any 2xx envelope as success.
import { ApiError } from '../../shared/request'

export type WindowStatus = 'OPEN' | 'CLOSED' | 'SOLD_OUT'
export const windowStatuses: readonly WindowStatus[] = ['OPEN', 'CLOSED', 'SOLD_OUT']
export const windowStatusText: Record<WindowStatus, string> = {
  OPEN: '开放中', CLOSED: '已关闭', SOLD_OUT: '已约满',
}
export type StaffWindowStatus = 'AVAILABLE' | 'CLOSED'
export const staffWindowStatuses: readonly StaffWindowStatus[] = ['AVAILABLE', 'CLOSED']
export const staffWindowStatusText: Record<StaffWindowStatus, string> = {
  AVAILABLE: '可约', CLOSED: '已关闭',
}

/** GET /merchant/stores/{storeId}/availability-windows item (read projection carries
 *  updatedAt). Unified slot: no serviceId/windowKind — the window is the whole store's
 *  shared time interval (CCR-SCH-SLOT-001 §2). */
export type ScheduleWindowItem = Readonly<{
  windowId: string; merchantId: string; storeId: string
  startAt: string; endAt: string; configuredCapacity: number
  status: WindowStatus; version: string; updatedAt: string
}>
/** POST/PUT/close/open success data: the window receipt has no updatedAt on the wire. */
export type ScheduleWindowReceipt = Readonly<{
  windowId: string; merchantId: string; storeId: string
  startAt: string; endAt: string; configuredCapacity: number
  status: WindowStatus; version: string
}>
/** GET …/availability-windows paged envelope (§3.3, #122): the pages always send page/
 *  pageSize, so the wire always answers {storeId,items,page,pageSize,total}; total is the
 *  filter-matched count (same WHERE as the items, page-independent) and stays put past the
 *  last page. The workbench summary rides one-row status-filtered queries' total. */
export type ScheduleWindowPage = Readonly<{
  storeId: string; items: readonly ScheduleWindowItem[]
  page: number; pageSize: number; total: number
}>
export type BlockedWindow = Readonly<{ window: ScheduleWindowReceipt; reasonCode: string }>
export type BatchCloseResult = Readonly<{
  storeId: string; closedWindows: readonly ScheduleWindowReceipt[]; blockedWindows: readonly BlockedWindow[]
}>
// CCR-SCH-SLOT-002 §1 batch-generate: skipped windows carry reasonCode OCCUPIED (a window
// with effective claims cannot be closed, so generation leaves its interval untouched).
export type SkippedWindow = Readonly<{ window: ScheduleWindowReceipt; reasonCode: string }>
export type BatchGenerateResult = Readonly<{
  storeId: string
  createdWindows: readonly ScheduleWindowReceipt[]
  closedWindows: readonly ScheduleWindowReceipt[]
  skippedWindows: readonly SkippedWindow[]
}>
/** Store-level booking window rule (CCR-SCH-SLOT-002 §2): client-selectable slots must
 *  satisfy startAt >= now + leadMinutes and startAt <= now + maxDays days. GET answers the
 *  defaults (60/7) when no row exists; the read projection carries updatedAt like every
 *  other read in the family. */
export type BookingWindowView = Readonly<{
  leadMinutes: number; maxDays: number
  version: string; updatedAt: string | null
}>
/** PUT booking-window receipt: full replace answers the new head without updatedAt. */
export type BookingWindowReceipt = Readonly<{
  leadMinutes: number; maxDays: number; version: string
}>
export type StaffWindowItem = Readonly<{
  windowId: string; merchantId: string; storeId: string; staffId: string
  startAt: string; endAt: string; status: StaffWindowStatus; version: string; updatedAt: string
}>
export type StaffWindowReceipt = Readonly<{
  windowId: string; merchantId: string; storeId: string; staffId: string
  startAt: string; endAt: string; status: StaffWindowStatus; version: string
}>
export type StaffWindowPage = Readonly<{ storeId: string; staffId: string; items: readonly StaffWindowItem[] }>
export type CapabilityView = Readonly<{
  merchantId: string; storeId: string; staffId: string; serviceIds: readonly string[]; version: string
}>

const invalid = (): never => { throw new Error('INVALID_RESPONSE') }
function exact(value: unknown, keys: readonly string[]): Record<string, any> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid()
  const record = value as Record<string, unknown>
  if (Object.keys(record).some(key => !keys.includes(key))) invalid()
  return value as Record<string, any>
}
const isId = (value: unknown): value is string =>
  typeof value === 'string' && /^[1-9][0-9]{0,18}(?![\s\S])/.test(value) && BigInt(value) <= 9223372036854775807n
export const isVersion = (value: unknown): value is string =>
  typeof value === 'string' && /^(0|[1-9][0-9]{0,18})(?![\s\S])/.test(value) && BigInt(value) <= 9223372036854775807n
/** Wire timestamps: millisecond precision, Z or ±HH:MM offset; responses are UTC `…SSSZ`. */
const timestamp = (value: any): string => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2})(?![\s\S])/.test(value) || !Number.isFinite(Date.parse(value))) invalid()
  return value
}
const capacity = (value: any): number => {
  if (!Number.isSafeInteger(value) || value < 1 || value > 2147483647) invalid()
  return value
}
const statusOf = (value: any): WindowStatus =>
  windowStatuses.includes(value) ? value as WindowStatus : invalid()
const staffStatusOf = (value: any): StaffWindowStatus =>
  staffWindowStatuses.includes(value) ? value as StaffWindowStatus : invalid()

const windowItemKeys = ['windowId', 'merchantId', 'storeId', 'startAt',
  'endAt', 'configuredCapacity', 'status', 'version', 'updatedAt'] as const
const windowReceiptKeys = ['windowId', 'merchantId', 'storeId', 'startAt',
  'endAt', 'configuredCapacity', 'status', 'version'] as const
const staffItemKeys = ['windowId', 'merchantId', 'storeId', 'staffId', 'startAt', 'endAt',
  'status', 'version', 'updatedAt'] as const
const staffReceiptKeys = ['windowId', 'merchantId', 'storeId', 'staffId', 'startAt', 'endAt',
  'status', 'version'] as const
/** Sanity bounds only — the contract sets no page size for the staff-window read (§4 has
 *  no pagination); the decode must still fail closed on absurd payloads instead of freezing
 *  the page. The store-windows read carries its own §3.3 paged bound below. */
const MAX_LIST_ITEMS = 1000
const MAX_CAPABILITY_ITEMS = 2000
/** §3.3 paged-windows bounds: page 1..10000, pageSize 1..50; a conformant server never
 *  answers more items than the pageSize ceiling, so the paged envelope decodes fail closed
 *  beyond it (the flat 1000 cap no longer applies — the endpoint is never consumed
 *  unpaginated any more). */
const MAX_PAGE_NUMBER = 10000
const MAX_PAGE_SIZE = 50

export function decodeWindowItem(value: unknown): ScheduleWindowItem {
  const v = exact(value, windowItemKeys)
  return {
    windowId: isId(v.windowId) ? v.windowId : invalid(),
    merchantId: isId(v.merchantId) ? v.merchantId : invalid(),
    storeId: isId(v.storeId) ? v.storeId : invalid(),
    startAt: timestamp(v.startAt), endAt: timestamp(v.endAt),
    configuredCapacity: capacity(v.configuredCapacity), status: statusOf(v.status),
    version: isVersion(v.version) ? v.version : invalid(), updatedAt: timestamp(v.updatedAt),
  }
}
export function decodeWindowReceipt(value: unknown): ScheduleWindowReceipt {
  const v = exact(value, windowReceiptKeys)
  return {
    windowId: isId(v.windowId) ? v.windowId : invalid(),
    merchantId: isId(v.merchantId) ? v.merchantId : invalid(),
    storeId: isId(v.storeId) ? v.storeId : invalid(),
    startAt: timestamp(v.startAt), endAt: timestamp(v.endAt),
    configuredCapacity: capacity(v.configuredCapacity), status: statusOf(v.status),
    version: isVersion(v.version) ? v.version : invalid(),
  }
}
const pageNumber = (value: any): number => {
  if (!Number.isSafeInteger(value) || value < 1 || value > MAX_PAGE_NUMBER) invalid()
  return value
}
const pageSizeNumber = (value: any): number => {
  if (!Number.isSafeInteger(value) || value < 1 || value > MAX_PAGE_SIZE) invalid()
  return value
}
const totalCount = (value: any): number => {
  if (!Number.isSafeInteger(value) || value < 0) invalid()
  return value
}
/** §3.3 paged envelope only: the page always requests pagination, so the legacy
 *  `{storeId,items}`-only shape is an INVALID_RESPONSE (fail closed, never a silent
 *  full-list fallback that would break the summary counts). */
export function decodeWindowPage(value: unknown): ScheduleWindowPage {
  const v = exact(value, ['storeId', 'items', 'page', 'pageSize', 'total'])
  if (!Array.isArray(v.items) || v.items.length > MAX_PAGE_SIZE) invalid()
  return {
    storeId: isId(v.storeId) ? v.storeId : invalid(),
    items: v.items.map(decodeWindowItem),
    page: pageNumber(v.page), pageSize: pageSizeNumber(v.pageSize), total: totalCount(v.total),
  }
}
export function decodeBatchCloseResult(value: unknown): BatchCloseResult {
  const v = exact(value, ['storeId', 'closedWindows', 'blockedWindows'])
  if (!Array.isArray(v.closedWindows) || v.closedWindows.length > MAX_LIST_ITEMS) invalid()
  if (!Array.isArray(v.blockedWindows) || v.blockedWindows.length > MAX_LIST_ITEMS) invalid()
  if (v.closedWindows.length + v.blockedWindows.length > 200) invalid() // contract §3.2 cap
  const decodeBlocked = (item: unknown): BlockedWindow => {
    const b = exact(item, ['window', 'reasonCode'])
    if (typeof b.reasonCode !== 'string' || b.reasonCode.length === 0 || b.reasonCode.length > 100) invalid()
    return { window: decodeWindowReceipt(b.window), reasonCode: b.reasonCode }
  }
  return {
    storeId: isId(v.storeId) ? v.storeId : invalid(),
    closedWindows: v.closedWindows.map(decodeWindowReceipt),
    blockedWindows: v.blockedWindows.map(decodeBlocked),
  }
}
export function decodeBatchGenerateResult(value: unknown): BatchGenerateResult {
  const v = exact(value, ['storeId', 'createdWindows', 'closedWindows', 'skippedWindows'])
  if (!Array.isArray(v.createdWindows) || v.createdWindows.length > MAX_LIST_ITEMS) invalid()
  if (!Array.isArray(v.closedWindows) || v.closedWindows.length > MAX_LIST_ITEMS) invalid()
  if (!Array.isArray(v.skippedWindows) || v.skippedWindows.length > MAX_LIST_ITEMS) invalid()
  // Contract §1: one batch command caps CANDIDATE entries (created+closed+skipped) at 200 —
  // a conformant server never answers more, so the decode fails closed beyond it.
  if (v.createdWindows.length + v.closedWindows.length + v.skippedWindows.length > 200) invalid()
  const decodeSkipped = (item: unknown): SkippedWindow => {
    const s = exact(item, ['window', 'reasonCode'])
    if (typeof s.reasonCode !== 'string' || s.reasonCode.length === 0 || s.reasonCode.length > 100) invalid()
    return { window: decodeWindowReceipt(s.window), reasonCode: s.reasonCode }
  }
  return {
    storeId: isId(v.storeId) ? v.storeId : invalid(),
    createdWindows: v.createdWindows.map(decodeWindowReceipt),
    closedWindows: v.closedWindows.map(decodeWindowReceipt),
    skippedWindows: v.skippedWindows.map(decodeSkipped),
  }
}
// Booking-window bounds (CCR-SCH-SLOT-002 §2): lead 0..1440 minutes, maxDays 1..90 days;
// missing store row = defaults 60/7 (the server materializes defaults, never the client).
export const bookingLeadMinutesDefault = 60
export const bookingMaxDaysDefault = 7
const leadMinutesOf = (value: any): number => {
  if (!Number.isSafeInteger(value) || value < 0 || value > 1440) invalid()
  return value
}
const maxDaysOf = (value: any): number => {
  if (!Number.isSafeInteger(value) || value < 1 || value > 90) invalid()
  return value
}
export function decodeBookingWindowView(value: unknown): BookingWindowView {
  // CCR-SCH-SLOT-002 §2 wire shape (pinned with the backend): exactly four keys — no
  // merchantId/storeId on this store-scoped read; updatedAt is null for the default row.
  const v = exact(value, ['leadMinutes', 'maxDays', 'version', 'updatedAt'])
  return {
    leadMinutes: leadMinutesOf(v.leadMinutes), maxDays: maxDaysOf(v.maxDays),
    version: isVersion(v.version) ? v.version : invalid(),
    updatedAt: v.updatedAt === null ? null : timestamp(v.updatedAt),
  }
}
export function decodeBookingWindowReceipt(value: unknown): BookingWindowReceipt {
  const v = exact(value, ['leadMinutes', 'maxDays', 'version'])
  return {
    leadMinutes: leadMinutesOf(v.leadMinutes), maxDays: maxDaysOf(v.maxDays),
    version: isVersion(v.version) ? v.version : invalid(),
  }
}
// CCR-SCH-SLOT-003（2026-11-13 批准）周循环模板：独立门店配置面（表 merchant_schedule_
// week_template，store 唯一，读时展开 + 下单物化，持久循环配置永不自动过期），窗口表零周期
// 字段。GET 缺行=七天全休 version "0"；PUT 全替换 + expectedVersion（改模板非减少可用性
// 动作——已物化窗口一律不动，reason 不需要）。每段自带容量（§4：1..999，默认 6）；
// 段为 "HH:mm" start<end 半开不重叠（空段=该天休），每天 0..24 段。
export type WeekTemplateSegment = Readonly<{ start: string; end: string; capacity: number }>
export type WeekTemplateDay = Readonly<{ enabled: boolean; segments: readonly WeekTemplateSegment[] }>
export type WeekTemplateView = Readonly<{ days: readonly WeekTemplateDay[]; version: string }>
/** PUT receipt：全替换回执即新头——与 view 同一线形态（同 booking-window，回执无 updatedAt）。 */
export type WeekTemplateReceipt = WeekTemplateView
/** PUT 请求形态：七天全量（停用日=空段）；expectedVersion 由仓储层随命令体携带。 */
export type WeekTemplateForm = Readonly<{ days: readonly WeekTemplateDay[] }>
const weekTemplateCapacityOf = (value: any): number => {
  if (!Number.isSafeInteger(value) || value < 1 || value > 999) invalid()
  return value
}
const decodeWeekTemplateDay = (value: unknown): WeekTemplateDay => {
  const v = exact(value, ['enabled', 'segments'])
  if (typeof v.enabled !== 'boolean') invalid()
  if (!Array.isArray(v.segments) || v.segments.length > 24) invalid()
  const segments = v.segments.map((segment: any) => {
    const s = exact(segment, ['start', 'end', 'capacity'])
    if (timeMinutes(s.start) >= timeMinutes(s.end)) invalid()
    return { start: s.start as string, end: s.end as string, capacity: weekTemplateCapacityOf(s.capacity) }
  })
  // 不重叠（相邻半开可衔接）：不要求线序，按 start 排序后成对校验；保持线序原样返回。
  const sorted = [...segments].sort((a, b) => timeMinutes(a.start) - timeMinutes(b.start))
  for (let index = 1; index < sorted.length; index++) {
    if (timeMinutes(sorted[index - 1]!.end) > timeMinutes(sorted[index]!.start)) invalid()
  }
  return { enabled: v.enabled, segments }
}
export function decodeWeekTemplateView(value: unknown): WeekTemplateView {
  const v = exact(value, ['days', 'version'])
  if (!Array.isArray(v.days) || v.days.length !== 7) invalid()
  return { days: v.days.map(decodeWeekTemplateDay), version: isVersion(v.version) ? v.version : invalid() }
}
export function decodeStaffWindowItem(value: unknown): StaffWindowItem {
  const v = exact(value, staffItemKeys)
  return {
    windowId: isId(v.windowId) ? v.windowId : invalid(),
    merchantId: isId(v.merchantId) ? v.merchantId : invalid(),
    storeId: isId(v.storeId) ? v.storeId : invalid(),
    staffId: isId(v.staffId) ? v.staffId : invalid(),
    startAt: timestamp(v.startAt), endAt: timestamp(v.endAt), status: staffStatusOf(v.status),
    version: isVersion(v.version) ? v.version : invalid(), updatedAt: timestamp(v.updatedAt),
  }
}
export function decodeStaffWindowReceipt(value: unknown): StaffWindowReceipt {
  const v = exact(value, staffReceiptKeys)
  return {
    windowId: isId(v.windowId) ? v.windowId : invalid(),
    merchantId: isId(v.merchantId) ? v.merchantId : invalid(),
    storeId: isId(v.storeId) ? v.storeId : invalid(),
    staffId: isId(v.staffId) ? v.staffId : invalid(),
    startAt: timestamp(v.startAt), endAt: timestamp(v.endAt), status: staffStatusOf(v.status),
    version: isVersion(v.version) ? v.version : invalid(),
  }
}
export function decodeStaffWindowPage(value: unknown): StaffWindowPage {
  const v = exact(value, ['storeId', 'staffId', 'items'])
  if (!Array.isArray(v.items) || v.items.length > MAX_LIST_ITEMS) invalid()
  return {
    storeId: isId(v.storeId) ? v.storeId : invalid(),
    staffId: isId(v.staffId) ? v.staffId : invalid(),
    items: v.items.map(decodeStaffWindowItem),
  }
}
export function decodeCapabilityView(value: unknown): CapabilityView {
  const v = exact(value, ['merchantId', 'storeId', 'staffId', 'serviceIds', 'version'])
  if (!Array.isArray(v.serviceIds) || v.serviceIds.length > MAX_CAPABILITY_ITEMS) invalid()
  const serviceIds = v.serviceIds.map((item: unknown) => isId(item) ? item : invalid())
  if (new Set(serviceIds).size !== serviceIds.length) invalid() // wire set is deduped
  return {
    merchantId: isId(v.merchantId) ? v.merchantId : invalid(),
    storeId: isId(v.storeId) ? v.storeId : invalid(),
    staffId: isId(v.staffId) ? v.staffId : invalid(),
    serviceIds, version: isVersion(v.version) ? v.version : invalid(),
  }
}

// ---------------------------------------------------------------------------
// State variants: Taro 4.1.5 drops dynamic data-* from the native wxml, so status styles are
// class-name variants mapped by explicit functions (services/page.css convention). Unknown
// values fall back to the base class; no enum-to-string interpolation.
// ---------------------------------------------------------------------------
export function windowStatusTagClass(status: WindowStatus): string {
  if (status === 'OPEN') return 'sch-tag sch-tag-open'
  if (status === 'SOLD_OUT') return 'sch-tag sch-tag-soldout'
  if (status === 'CLOSED') return 'sch-tag sch-tag-closed'
  return 'sch-tag'
}
export function staffStatusTagClass(status: StaffWindowStatus): string {
  if (status === 'AVAILABLE') return 'sch-tag sch-tag-open'
  if (status === 'CLOSED') return 'sch-tag sch-tag-closed'
  return 'sch-tag'
}
/** Filter chips follow the edit-page pill language (selected = brand fill). */
export function chipClass(selected: boolean): string {
  return selected ? 'sch-chip sch-chip-selected' : 'sch-chip'
}

// ---------------------------------------------------------------------------
// Time helpers. The store operates in Asia/Shanghai (+08:00); date/time pickers compose
// `YYYY-MM-DDTHH:mm:00.000+08:00` (the wire accepts Z or ±HH:MM with exact millisecond
// precision) and responses (UTC …SSSZ) are split back into Beijing wall-clock parts.
// ---------------------------------------------------------------------------
const BEIJING_OFFSET_MS = 8 * 3600 * 1000
const isoDate = (value: string): string => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || !Number.isFinite(Date.parse(`${value}T00:00:00.000Z`))) invalid()
  return value
}
const isoTime = (value: string): string => {
  if (!/^\d{2}:\d{2}$/.test(value)) invalid()
  return value
}
/** Client input composition: minute-precision Beijing wall clock, :00.000 milliseconds. */
export function composeTimestamp(date: string, time: string): string {
  return `${isoDate(date)}T${isoTime(time)}:00.000+08:00`
}
export function composeBeijingDate(value: string): string {
  return isoDate(value)
}
/** Response split into Beijing wall-clock parts (minute granularity display). */
export function splitBeijingParts(iso: string): { date: string; time: string } {
  const parsed = new Date(iso)
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2})(?![\s\S])/.test(iso) || Number.isNaN(parsed.getTime())) invalid()
  const shifted = new Date(parsed.getTime() + BEIJING_OFFSET_MS)
  return { date: shifted.toISOString().slice(0, 10), time: shifted.toISOString().slice(11, 16) }
}
export function formatWindowInterval(startAt: string, endAt: string): string {
  const start = splitBeijingParts(startAt), end = splitBeijingParts(endAt)
  return start.date === end.date
    ? `${start.date} ${start.time}-${end.time}`
    : `${start.date} ${start.time} ~ ${end.date} ${end.time}`
}
/** Beijing calendar day today, for date-input defaults (no runtime locale dependence). */
export function beijingToday(now: Date = new Date()): string {
  return new Date(now.getTime() + BEIJING_OFFSET_MS).toISOString().slice(0, 10)
}
export function beijingNowMinutes(now: Date = new Date()): string {
  return new Date(now.getTime() + BEIJING_OFFSET_MS).toISOString().slice(11, 16)
}
export function addBeijingDays(date: string, days: number): string {
  const base = new Date(`${isoDate(date)}T00:00:00.000Z`)
  if (Number.isNaN(base.getTime())) invalid()
  return new Date(base.getTime() + days * 86400000).toISOString().slice(0, 10)
}
/** Inclusive calendar-day span for the batch range (Asia/Shanghai days, §3); from>to 400s. */
export function batchRangeDays(fromDate: string, toDate: string): number {
  const from = new Date(`${isoDate(fromDate)}T00:00:00.000Z`).getTime()
  const to = new Date(`${isoDate(toDate)}T00:00:00.000Z`).getTime()
  if (Number.isNaN(from) || Number.isNaN(to) || to < from) invalid()
  return Math.round((to - from) / 86400000) + 1
}

// ---------------------------------------------------------------------------
// Form models + client-side pre-validation (the server re-validates everything; the page
// mirrors the same answers so a well-formed draft never 400s on the wire).
// ---------------------------------------------------------------------------
/** Unified-slot form: the merchant picks ONLY the shared interval + capacity — no service
 *  and no kind step (CCR-SCH-SLOT-001 §8.1: opening windows must not scale with service
 *  count; one slot wall serves the whole store). */
export type WindowFormInput = Readonly<{
  startDate: string; startTime: string
  endDate: string; endTime: string
  configuredCapacity: number | null
}>
export type StaffWindowFormInput = Readonly<{
  startDate: string; startTime: string; endDate: string; endTime: string
}>
export function emptyWindowForm(today: string, now: string): WindowFormInput {
  return { startDate: today, startTime: now, endDate: today, endTime: now, configuredCapacity: null }
}
export function emptyStaffWindowForm(today: string, now: string): StaffWindowFormInput {
  return { startDate: today, startTime: now, endDate: today, endTime: now }
}
export function windowFormFromItem(item: ScheduleWindowItem): WindowFormInput {
  const start = splitBeijingParts(item.startAt), end = splitBeijingParts(item.endAt)
  return { startDate: start.date, startTime: start.time, endDate: end.date, endTime: end.time,
    configuredCapacity: item.configuredCapacity }
}
export function staffWindowFormFromItem(item: StaffWindowItem): StaffWindowFormInput {
  const start = splitBeijingParts(item.startAt), end = splitBeijingParts(item.endAt)
  return { startDate: start.date, startTime: start.time, endDate: end.date, endTime: end.time }
}
const MINUTES_PER_DAY = 24 * 60
function startMinute(startDate: string, startTime: string): number {
  const date = new Date(`${isoDate(startDate)}T00:00:00.000Z`).getTime()
  const [hour, minute] = isoTime(startTime).split(':')
  return Math.round(date / 60000) + Number(hour) * 60 + Number(minute)
}
export function windowFormProblems(form: WindowFormInput): string[] {
  const problems: string[] = []
  if (form.configuredCapacity === null || !Number.isSafeInteger(form.configuredCapacity) || form.configuredCapacity < 1) {
    problems.push('容量需为不小于1的整数')
  } else if (form.configuredCapacity > 2147483647) problems.push('容量超出上限')
  if (startMinute(form.startDate, form.startTime) >= startMinute(form.endDate, form.endTime)) {
    problems.push('结束时间需晚于开始时间（分钟级半开区间）')
  }
  if (startMinute(form.endDate, form.endTime) - startMinute(form.startDate, form.startTime) > 366 * MINUTES_PER_DAY) {
    problems.push('单个时段不能超过一年')
  }
  return problems
}
export function staffWindowFormProblems(form: StaffWindowFormInput): string[] {
  const problems: string[] = []
  if (startMinute(form.startDate, form.startTime) >= startMinute(form.endDate, form.endTime)) {
    problems.push('结束时间需晚于开始时间（分钟级半开区间）')
  }
  if (startMinute(form.endDate, form.endTime) - startMinute(form.startDate, form.startTime) > 366 * MINUTES_PER_DAY) {
    problems.push('单个时段不能超过一年')
  }
  return problems
}
/** reason（1..500）mandatory on close / batch-close / availability reductions; the page
 *  mirrors the server answer (400) before sending. */
export function reasonProblem(reason: string, required: boolean): string | null {
  const length = [...reason.trim()].length
  if (required && length < 1) return '请填写操作原因（必填，1-500字）'
  if (length > 500) return '操作原因最多500字'
  return null
}

// ---------------------------------------------------------------------------
// CCR-SCH-SLOT-002（2026-11-13 批准）+ CCR-SCH-SLOT-003（2026-11-13 批准，取代 002 的
// 「生效天数铺排」保存模式）排期模板编辑草稿态。红线：周一~周日仅是前端编辑态草稿
// （dayOfWeek/repeatWeekly/copyNextWeek 三字段永不进窗口表）；「保存并生效」= 一次 PUT
// week-template 全替换（服务端持久循环配置：读时展开/下单物化，前端不再铺排窗口）。
// 容量仍是全店共享输入（002 §7 裁决：默认 6，一处填写，写入模板每段 capacity；
// batch-generate 手动批量入口保留自己的容量参数）；间隔固定 60 分钟仅用于 A 区填充草稿。
// ---------------------------------------------------------------------------
/** HH:mm → 当日分钟数（无时区换算，草稿态纯墙钟）。 */
export function timeMinutes(time: string): number {
  if (!/^\d{2}:\d{2}$/.test(time) || !Number.isFinite(Date.parse(`2000-01-01T${time}:00Z`))) invalid()
  return Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5))
}
export const generateIntervalMinutes = 60
export const generateCapacityDefault = 6
/** A 区输入：门店时间驱动「按门店时间填充一周」草稿便捷键；容量一处填写，写入模板每段。 */
export type GenerateFormInput = Readonly<{
  dailyStart: string; dailyEnd: string; configuredCapacity: number | null
}>
export function generateFormProblems(form: GenerateFormInput): string[] {
  const problems: string[] = []
  if (form.configuredCapacity === null || !Number.isSafeInteger(form.configuredCapacity)
    || form.configuredCapacity < 1 || form.configuredCapacity > 999) {
    problems.push('容量需为 1-999 的整数')
  }
  if (timeMinutes(form.dailyStart) >= timeMinutes(form.dailyEnd)) {
    problems.push('门店时间结束需晚于开始')
  }
  return problems
}
/** batch-generate 请求模型（Contract 53号增补，CCR §1；2026-11-13 用户裁决：一次请求带
 *  全部日期+预切好的时段段——不按段多次调用）：dates 1..366 个不重复北京历日，
 *  segments 1..24 段不重叠 "HH:mm" start<end，configuredCapacity 1..999，reason 1..500。 */
export type BatchGenerateInput = Readonly<{
  dates: readonly string[]
  segments: readonly WeekSegmentDraft[]
  configuredCapacity: number
  reason: string
}>
export function batchGenerateProblems(input: BatchGenerateInput): string[] {
  const problems: string[] = []
  if (!Array.isArray(input.dates) || input.dates.length < 1 || input.dates.length > 366
    || new Set(input.dates).size !== input.dates.length
    || input.dates.some(d => !/^\d{4}-\d{2}-\d{2}$/.test(d))) {
    problems.push('日期需为 1-366 个不重复的日历日')
  }
  if (!Array.isArray(input.segments) || input.segments.length < 1 || input.segments.length > 24) {
    problems.push('时段段需为 1-24 条')
  } else {
    const sorted = [...input.segments].sort((a, b) => a.start < b.start ? -1 : 1)
    for (const seg of sorted) {
      if (!/^[0-2]\d:[0-5]\d$/.test(seg.start) || !/^[0-2]\d:[0-5]\d$/.test(seg.end) || seg.start >= seg.end) {
        problems.push('每段需为开始早于结束的 HH:mm 时间')
        break
      }
    }
    for (let i = 1; i < sorted.length && !problems.some(p => p.includes('重叠')); i++) {
      if (sorted[i]!.start < sorted[i - 1]!.end) { problems.push('时段段之间不能重叠'); break }
    }
  }
  if (!Number.isSafeInteger(input.configuredCapacity) || input.configuredCapacity < 1 || input.configuredCapacity > 999) {
    problems.push('每时段容量需为 1-999 的整数')
  }
  const reasonIssue = reasonProblem(input.reason, true)
  if (reasonIssue) problems.push(reasonIssue)
  return problems
}
/** 周模板草稿：一天 = 开关 + 若干时段段（start/end 墙钟，半开语义）。 */
export type WeekSegmentDraft = Readonly<{ start: string; end: string }>
export type WeekdayDraft = Readonly<{ enabled: boolean; segments: readonly WeekSegmentDraft[] }>
export type WeekDraft = readonly WeekdayDraft[]
export const weekdayLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'] as const
/** 默认草稿：七天全启用，各一段 09:00-19:00（与一键生成门店时间默认一致）。 */
export function defaultWeekDraft(): WeekDraft {
  return weekdayLabels.map(() => ({ enabled: true, segments: [{ start: '09:00', end: '19:00' }] }))
}
/** 单日草稿校验：start<end、段间不重叠（相邻半开可衔接）、启用日至少一段。 */
export function weekdayDraftProblems(day: WeekdayDraft): string[] {
  const problems: string[] = []
  if (day.enabled && day.segments.length === 0) problems.push('启用的日期至少保留一个时段')
  const sorted = [...day.segments].sort((a, b) => timeMinutes(a.start) - timeMinutes(b.start))
  for (const segment of sorted) {
    if (timeMinutes(segment.start) >= timeMinutes(segment.end)) {
      problems.push('时段结束需晚于开始')
      break
    }
  }
  for (let index = 1; index < sorted.length; index++) {
    if (timeMinutes(sorted[index - 1]!.end) > timeMinutes(sorted[index]!.start)) {
      problems.push('同一天时段不能重叠')
      break
    }
  }
  return problems
}
export function weekDraftProblems(week: WeekDraft): string[] {
  const problems: string[] = []
  week.forEach((day, index) => {
    for (const problem of weekdayDraftProblems(day)) problems.push(`${weekdayLabels[index]}：${problem}`)
  })
  return problems
}
/** 北京历日 → 周一基索引（0=周一…6=周日；view.ts 同口径按 UTC 日取星期）。 */
export function weekdayIndexOf(date: string): number {
  const parsed = new Date(`${isoDate(date)}T00:00:00.000Z`)
  if (Number.isNaN(parsed.getTime())) invalid()
  return (parsed.getUTCDay() + 6) % 7
}
// ---------------------------------------------------------------------------
// CCR-SCH-SLOT-003 周模板草稿映射（B 区编辑态 ↔ week-template 契约形态）。「保存并生效」=
// weekTemplateFromDraft(草稿, 容量) 一次 PUT 全替换；GET 回读 = weekTemplateToDraft 进
// B 区。002 的 nextNDays/nextSevenDays 铺排路径随「生效天数」UI 一并删除（模板是服务端
// 持久循环配置，365 天/跨年自动生效，前端不再按日期物化）。
// ---------------------------------------------------------------------------
/** GET 缺行语义（CCR §3）：七天全休、version "0"（服务端无行时物化该默认，永不由客户端补行）。 */
export function emptyWeekTemplateView(): WeekTemplateView {
  return { days: weekdayLabels.map(() => ({ enabled: false, segments: [] })), version: '0' }
}
/** 草稿 → PUT 形态：停用日规范为空段（空=该天休，§1）；启用日如实保留（启用但无段
 *  保留原样交给 weekTemplateFormProblems 拦截——开关开了却没时段是编辑事故，不静默落休）；
 *  容量一处输入写入每段。 */
export function weekTemplateFromDraft(week: WeekDraft, capacity: number): WeekTemplateForm {
  return { days: week.map(day => day.enabled
    ? { enabled: true, segments: day.segments.map(segment => ({ start: segment.start, end: segment.end, capacity })) }
    : { enabled: false, segments: [] }) }
}
/** GET 回读 → B 区草稿：enabled 且有段才进编辑态启用（空段=该天休）；容量不进草稿。 */
export function weekTemplateToDraft(view: WeekTemplateView): WeekDraft {
  return view.days.map(day => ({ enabled: day.enabled && day.segments.length > 0,
    segments: day.segments.map(segment => ({ start: segment.start, end: segment.end })) }))
}
/** PUT 前校验（镜像服务端 400）：七天、启用日至少一段、段 HH:mm start<end 不重叠、每段
 *  容量 1-999 整数；停用日已被 weekTemplateFromDraft 规范化为空段，无需再校。 */
export function weekTemplateFormProblems(form: WeekTemplateForm): string[] {
  const problems: string[] = []
  if (form.days.length !== 7) { problems.push('周模板需为周一至周日七天'); return problems }
  form.days.forEach((day, index) => {
    const label = weekdayLabels[index]!
    if (day.enabled && day.segments.length === 0) problems.push(`${label}：启用的日期至少保留一个时段`)
    if (day.segments.length > 24) problems.push(`${label}：时段段最多 24 条`)
    const sorted = [...day.segments].sort((a, b) => timeMinutes(a.start) - timeMinutes(b.start))
    for (const segment of sorted) {
      if (timeMinutes(segment.start) >= timeMinutes(segment.end)) { problems.push(`${label}：时段结束需晚于开始`); break }
      if (!Number.isSafeInteger(segment.capacity) || segment.capacity < 1 || segment.capacity > 999) {
        problems.push(`${label}：每段容量需为 1-999 的整数`); break
      }
    }
    for (let at = 1; at < sorted.length; at++) {
      if (timeMinutes(sorted[at - 1]!.end) > timeMinutes(sorted[at]!.start)) { problems.push(`${label}：同一天时段不能重叠`); break }
    }
  })
  return problems
}
/** 折叠区顶部模板状态条文案（CCR §5 商家窗口列表页顶提示）：「周模板生效中：周一 2 段 ·
 *  周二 休 …（版本 N）」；七天全休时如实显示未配置。 */
export function weekTemplateSummary(view: WeekTemplateView): string {
  const parts = view.days.map((day, index) =>
    day.enabled && day.segments.length > 0 ? `${weekdayLabels[index]} ${day.segments.length} 段` : `${weekdayLabels[index]} 休`)
  const head = view.days.some(day => day.enabled && day.segments.length > 0) ? '周模板生效中' : '周模板未配置（七天全休）'
  return `${head}：${parts.join(' · ')}（版本 ${view.version}）`
}
/** booking-window 配置草稿与校验（lead 0..1440 分钟 / maxDays 1..90 天）。 */
export type BookingWindowForm = Readonly<{ leadMinutes: number | null; maxDays: number | null }>
export function bookingWindowFormProblems(form: BookingWindowForm): string[] {
  const problems: string[] = []
  if (form.leadMinutes === null || !Number.isSafeInteger(form.leadMinutes)
    || form.leadMinutes < 0 || form.leadMinutes > 1440) {
    problems.push('提前分钟需为 0-1440 的整数')
  }
  if (form.maxDays === null || !Number.isSafeInteger(form.maxDays)
    || form.maxDays < 1 || form.maxDays > 90) {
    problems.push('可约天数需为 1-90 的整数')
  }
  return problems
}
/** Capability set replace: duplicates 400; removals require a reason (any one removed item). */
export function capabilityProblems(selected: readonly string[], current: readonly string[], reason: string): string[] {
  const problems: string[] = []
  if (new Set(selected).size !== selected.length) problems.push('服务项不能重复')
  const removing = current.some(serviceId => !selected.includes(serviceId))
  const reasonIssue = reasonProblem(reason, removing)
  if (reasonIssue) problems.push(reasonIssue)
  return problems
}

// ---------------------------------------------------------------------------
// Error presentation. 「功能未开放」is a fail-closed STATE, not a toast: when the backend
// switch is off (route 404) or a dependency/fact read fails (503), the pages render a
// non-interactive closed panel instead of forms.
// ---------------------------------------------------------------------------
export type ScheduleAvailability = 'ok' | 'closed'
/** Both the real ApiError and the preview mock error carry {code, statusCode}; the pages
 *  classify either so the preview shows the same fail-closed panel as production. */
const apiLike = (error: unknown): error is { code: string; statusCode: number } =>
  error instanceof ApiError || error instanceof ScheduleMockError
export function scheduleAvailability(error: unknown): ScheduleAvailability {
  if (!apiLike(error)) return 'ok'
  if (error.statusCode === 503) return 'closed'
  // Switch off: the boot routes are absent, admission-hidden owners get 404 (防枚举).
  if (error.statusCode === 404) return 'closed'
  return 'ok'
}
/** CAS 版本冲突判定（COMMON_CONFLICT 409）：周模板/配置保存冲突需提示重读后再保存
 *  （CCR-SCH-SLOT-003：PUT 全替换走 expectedVersion 乐观锁，绝不 last-write-wins）。 */
export function isScheduleConflict(error: unknown): boolean {
  return apiLike(error) && error.code === 'COMMON_CONFLICT'
}
export function scheduleMessage(error: unknown): string {
  if (apiLike(error)) {
    const { code, statusCode } = error
    if (code === 'SCHEDULE_WINDOW_OVERLAP') return '时间区间与已有开放时段重叠；相邻可衔接，重叠需先调整原时段。'
    if (code === 'SCHEDULE_WINDOW_STATE_NOT_ALLOWED') return '该时段已有预约占用，不能关闭、改期或降低容量；可尝试提高容量，或先处理占用预约。'
    if (code === 'SCHEDULE_CAPACITY_EXCEEDED') return '超出该时段剩余容量，请调整容量或时间后重试。'
    if (code === 'SERVICE_STATE_NOT_ALLOWED') return '门店当前不可经营（冻结/下线），暂不能维护排期。'
    if (code === 'COMMON_CONFLICT') return '内容已被其他人修改（版本冲突），请刷新后按最新内容重新操作。'
    if (code === 'IDEMPOTENCY_KEY_CONFLICT') return '同一请求编号已被其他内容使用，请刷新页面后重试。'
    if (code === 'COMMON_INVALID_ARGUMENT') return '提交内容未通过校验（如批量范围一次最多处理200条时段），请调整后重试。'
    if (statusCode === 401) return '登录已失效，请重新进入工作台。'
    if (statusCode === 403) return '当前账号无权执行该操作（排期维护限商家主账号）。'
    if (statusCode === 404) return '目标不存在或不可见，请刷新列表后重试。'
    if (statusCode === 503) return '排期服务暂不可用，操作结果未确认；请用原按钮重试，不会重复创建。'
  }
  if (error instanceof Error && error.message === 'PENDING_WRITE_CHANGED') return '上次操作结果尚未确认，请先重试原操作。'
  if (error instanceof Error && error.message === 'WORKSPACE_PATH_MISMATCH') return '请从商家工作台进入排期维护。'
  return '操作未确认成功，请重试；不会重复创建。'
}
/** Read-side headline for the closed panel. */
export function scheduleClosedReason(error: unknown): string {
  if (error instanceof ApiError && error.statusCode === 503) return '排期事实暂时不可读（依赖或准入事实故障），功能暂时未开放。'
  return '排期维护功能未开放（平台开关未开启或账号不可见），请稍后再试。'
}

// ---------------------------------------------------------------------------
// Page-facing dependency surface; preview and real implementations are interchangeable.
// Slots carry the write identity for ConsumerApi journaling (retry replays the same
// X-Request-Id; definitive 409s retire the slot inside the real repository).
// ---------------------------------------------------------------------------
/** Store-windows list query (§3.3): page/pageSize are always explicit — the pages never
 *  fall back to the legacy unpaginated mode — and status is the optional server-side filter
 *  (status feeds both the list chips and the summary count queries). No serviceId/kind
 *  filters: the unified slot has neither dimension (CCR-SCH-SLOT-001 §2). */
export type WindowPageQuery = Readonly<{
  page: number; pageSize: number
  status?: WindowStatus | ''
}>
/** Both schedule pages page the windows list at 20 rows like the other M-side lists
 *  (services/aftersale); the summary count queries fetch a single row — only the
 *  filter-matched total matters. */
export const windowListPageSize = 20
export const windowCountPageSize = 1
export type ScheduleDeps = {
  windows(query: WindowPageQuery): Promise<ScheduleWindowPage>
  createWindow(slot: string, input: WindowFormInput): Promise<ScheduleWindowReceipt>
  updateWindow(slot: string, windowId: string, expectedVersion: string, input: WindowFormInput): Promise<ScheduleWindowReceipt>
  closeWindow(slot: string, windowId: string, expectedVersion: string, reason: string): Promise<ScheduleWindowReceipt>
  openWindow(slot: string, windowId: string, expectedVersion: string): Promise<ScheduleWindowReceipt>
  batchClose(slot: string, fromDate: string, toDate: string, reason: string): Promise<BatchCloseResult>
  /** CCR-SCH-SLOT-002 §1 覆盖重建：范围内既有 OPEN/SOLD_OUT 先逐个 close（占用窗跳过并
   *  明列 skippedWindows），再按 intervalMinutes 逐段创建；候选上限 200。 */
  batchGenerate(slot: string, input: BatchGenerateInput): Promise<BatchGenerateResult>
  /** §2 门店级可预约范围（GET 缺省返回默认值 60/7）。 */
  bookingWindow(): Promise<BookingWindowView>
  saveBookingWindow(slot: string, form: BookingWindowForm, expectedVersion: string): Promise<BookingWindowReceipt>
  /** CCR-SCH-SLOT-003 §3 门店周循环模板（GET 缺行=七天全休 version 0；读时展开/下单
   *  物化——保存不再铺排窗口，已物化窗口一律不动）。 */
  weekTemplate(): Promise<WeekTemplateView>
  /** PUT 全替换 + expectedVersion CAS（改模板非减少可用性动作，无 reason）。 */
  saveWeekTemplate(slot: string, form: WeekTemplateForm, expectedVersion: string): Promise<WeekTemplateReceipt>
  staffWindows(staffId: string): Promise<StaffWindowPage>
  createStaffWindow(slot: string, staffId: string, input: StaffWindowFormInput): Promise<StaffWindowReceipt>
  updateStaffWindow(slot: string, staffId: string, windowId: string, expectedVersion: string, input: StaffWindowFormInput): Promise<StaffWindowReceipt>
  closeStaffWindow(slot: string, staffId: string, windowId: string, expectedVersion: string, reason: string): Promise<StaffWindowReceipt>
  openStaffWindow(slot: string, staffId: string, windowId: string, expectedVersion: string): Promise<StaffWindowReceipt>
  capabilities(staffId: string): Promise<CapabilityView>
  replaceCapabilities(slot: string, staffId: string, serviceIds: readonly string[], expectedVersion: string, reason: string): Promise<CapabilityView>
}

export class ScheduleMockError extends Error {
  constructor(readonly code: string, readonly statusCode: number) { super(code) }
}
export type ScheduleScenario = 'normal' | 'empty' | 'load-error' | 'closed' | 'legacy'
export const isScheduleScenario = (value?: string): value is ScheduleScenario =>
  ['normal', 'empty', 'load-error', 'closed', 'legacy'].includes(value || '')

// CONTRACT MOCK (preview=1): no network, no session, no durable data. It enforces the
// command semantics the backend enforces — overlap 409, occupied-window guards, CAS 409,
// capability-set versioning, 200-entry batch cap, SOLD_OUT derivation with capacity raise
// re-judge — but it is NOT a real backend integration and never authorizes anyone.
type MockWindow = { window: ScheduleWindowReceipt; updatedAt: string; occupied: number }
type MockStaffWindow = { window: StaffWindowReceipt; updatedAt: string }
export class PreviewScheduleRepository implements ScheduleDeps {
  private store: MockWindow[]
  private staffStore: MockStaffWindow[]
  private capabilitiesByStaff: Map<string, CapabilityView>
  private nextId = 61000
  private nextVersion = 100
  private journal = new Map<string, { fingerprint: string; receipt: unknown }>()
  private uuidSeed = 0
  private clock = 0
  private failNextRead = false
  /** Store-level booking-window head (CCR-SCH-SLOT-002 §2); version 0 = defaults, never persisted. */
  private bookingHead: { leadMinutes: number; maxDays: number; version: string } | null = null
  /** CCR-SCH-SLOT-003 week-template head; null = never saved (GET materializes the
   *  all-rest version-0 default; the template is a persistent loop, never auto-expired). */
  private weekHead: { days: readonly WeekTemplateDay[]; version: string; updatedAt: string } | null = null
  /** Fixture switch: treat every AVAILABLE staff row as assignment-protected (the real
   *  backend answers 409 via getCurrentAssignments + whole-store feasibility re-check). */
  protectedStaff = false
  constructor(
    windows: MockWindow[] = fixtureWindows(),
    staffWindows: MockStaffWindow[] = fixtureStaffWindows(),
    capabilities: CapabilityView[] = [fixtureCapability()],
    private scenario: ScheduleScenario = 'normal',
  ) {
    this.store = windows.map(entry => ({ ...entry, window: { ...entry.window } }))
    this.staffStore = staffWindows.map(entry => ({ ...entry, window: { ...entry.window } }))
    this.capabilitiesByStaff = new Map(capabilities.map(view => [view.staffId, view]))
    if (scenario === 'empty') { this.store = []; this.staffStore = []; this.capabilitiesByStaff.clear() }
    this.failNextRead = scenario === 'load-error'
  }
  private unavailable(): never {
    // Switch-off / dependency failure: reads and writes both fail closed (the HTTP routes
    // answer 404 when pet.schedule.command.http.enabled=false; fact reads answer 503).
    throw new ScheduleMockError('COMMON_DEPENDENCY_UNAVAILABLE', 503)
  }
  private gate(read: boolean): void {
    if (this.scenario === 'closed') this.unavailable()
    if (this.failNextRead && read) { this.failNextRead = false; this.unavailable() }
  }
  private uuid(): string {
    const value = (this.uuidSeed++).toString(16).padStart(12, '0')
    return `${value.slice(0, 8)}-${value.slice(8, 12)}-4000-8000-${value}`
  }
  private now(): string { return `2026-10-06T02:${String(this.clock++ % 60).padStart(2, '0')}:00.000Z` }
  private bump(): string { return String(this.nextVersion++) }
  /** Same slot retried with the same payload replays the journaled receipt; a changed
   *  payload locks the slot (mirrors ConsumerApi.write), exactly like the aftersale-slot
   *  discipline the service page uses. */
  private command<T>(slot: string, fingerprint: string, apply: () => T): T {
    const saved = this.journal.get(slot)
    if (saved) {
      if (saved.fingerprint !== fingerprint) throw new Error('PENDING_WRITE_CHANGED')
      return JSON.parse(JSON.stringify(saved.receipt)) as T
    }
    const receipt = apply()
    this.journal.set(slot, { fingerprint, receipt: JSON.parse(JSON.stringify(receipt)) })
    return receipt
  }
  private statusFor(window: MockWindow): WindowStatus {
    return window.occupied >= window.window.configuredCapacity && window.window.status !== 'CLOSED'
      ? 'SOLD_OUT' : window.window.status
  }
  private view(entry: MockWindow): ScheduleWindowItem {
    return { ...entry.window, status: this.statusFor(entry), updatedAt: entry.updatedAt }
  }
  private receipts(entries: MockWindow[]): ScheduleWindowReceipt[] {
    return entries.map(entry => ({ ...entry.window, status: this.statusFor(entry) }))
  }
  private findWindow(windowId: string): MockWindow {
    const found = this.store.find(entry => entry.window.windowId === windowId)
    if (!found) throw new ScheduleMockError('COMMON_NOT_FOUND', 404)
    return found
  }
  /** Overlap against OPEN/SOLD_OUT peers of the SAME STORE (SOLD_OUT counts as an open
   *  slot for overlap, contract §3.1; unified slot: no service/kind dimension any more,
   *  CCR-SCH-SLOT-001 §2 — 同店不重叠); adjacent half-open intervals are legal. */
  private assertNoWindowOverlap(candidate: { windowId?: string; startAt: string; endAt: string }): void {
    const overlap = this.store.some(entry => {
      const window = entry.window
      if (window.windowId === candidate.windowId) return false
      if (this.statusFor(entry) === 'CLOSED') return false
      return candidate.startAt < window.endAt && window.startAt < candidate.endAt
    })
    if (overlap) throw new ScheduleMockError('SCHEDULE_WINDOW_OVERLAP', 409)
  }
  /** Occupied windows (any effective claim) block close/time-change/capacity-decrease. */
  private assertNotOccupiedFor(entry: MockWindow, next: { startAt: string; endAt: string; configuredCapacity?: number }): void {
    const moving = next.startAt !== entry.window.startAt || next.endAt !== entry.window.endAt
    const shrinking = next.configuredCapacity !== undefined && next.configuredCapacity < entry.window.configuredCapacity
    if (entry.occupied > 0 && (moving || shrinking)) {
      throw new ScheduleMockError('SCHEDULE_WINDOW_STATE_NOT_ALLOWED', 409)
    }
  }
  private storeWindow(entry: MockWindow, next: { startAt: string; endAt: string; configuredCapacity: number }): ScheduleWindowReceipt {
    entry.window = { ...entry.window, startAt: next.startAt, endAt: next.endAt,
      configuredCapacity: next.configuredCapacity, version: this.bump() }
    // SOLD_OUT is derived from occupancy (statusFor), so a capacity raise re-judges in the
    // same transaction exactly like contract §3.1: occupied < new capacity answers OPEN.
    entry.updatedAt = this.now()
    return { ...entry.window, status: this.statusFor(entry) }
  }
  async windows(query: WindowPageQuery): Promise<ScheduleWindowPage> {
    this.gate(true)
    // Mirror the server's §3.3 parameter discipline: illegal page/pageSize answers the
    // generic 400 before anything is read.
    if (!Number.isSafeInteger(query.page) || query.page < 1 || query.page > MAX_PAGE_NUMBER
      || !Number.isSafeInteger(query.pageSize) || query.pageSize < 1 || query.pageSize > MAX_PAGE_SIZE) {
      throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
    }
    const matched = this.store
      .filter(entry => (query.status ? this.statusFor(entry) === query.status : true))
      .map(entry => this.view(entry))
    // CCR-SCH-SLOT-003 §1 读侧并集（preview 演示）：已保存周模板按周几展开虚拟段并与真实
    // 窗口合并（重叠段已在 templateExpansion 去重）；虚拟段恒 OPEN，随状态过滤自然进出。
    // 真实后端的商家窗口列表仍=真实窗口（§5，页顶模板状态条指向模板编辑）——展开仅是
    // preview 里让商家看见模板效果的手段，mock 从来不是联调声明。
    matched.push(...this.templateExpansion().filter(item => (query.status ? item.status === query.status : true)))
    // §3.3 wire order — start_at ascending with id ascending tiebreak — so the preview
    // slices the same order the server slices; CLOSED windows stay listed (§3 read).
    matched.sort((a, b) => (a.startAt === b.startAt ? Number(a.windowId) - Number(b.windowId) : a.startAt < b.startAt ? -1 : 1))
    const start = (query.page - 1) * query.pageSize
    return {
      storeId: fixtureStoreId,
      items: matched.slice(start, start + query.pageSize), // past the end: empty, total intact
      page: query.page, pageSize: query.pageSize, total: matched.length,
    }
  }
  async createWindow(slot: string, input: WindowFormInput): Promise<ScheduleWindowReceipt> {
    this.gate(false)
    if (windowFormProblems(input).length) throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
    return this.command(slot, JSON.stringify(['create', input]), () => {
      const startAt = composeTimestamp(input.startDate, input.startTime)
      const endAt = composeTimestamp(input.endDate, input.endTime)
      this.assertNoWindowOverlap({ startAt, endAt })
      const window: ScheduleWindowReceipt = {
        windowId: String(this.nextId++), merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        startAt, endAt,
        configuredCapacity: input.configuredCapacity!, status: 'OPEN', version: this.bump(),
      }
      this.store.push({ window, updatedAt: this.now(), occupied: 0 })
      return window
    })
  }
  async updateWindow(slot: string, windowId: string, expectedVersion: string, input: WindowFormInput): Promise<ScheduleWindowReceipt> {
    this.gate(false)
    if (windowFormProblems(input).length) throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
    return this.command(slot, JSON.stringify(['update', windowId, expectedVersion, input]), () => {
      const entry = this.findWindow(windowId)
      // Only OPEN/SOLD_OUT windows are editable; a CLOSED window must be reopened first (§3).
      if (entry.window.status === 'CLOSED') throw new ScheduleMockError('SCHEDULE_WINDOW_STATE_NOT_ALLOWED', 409)
      if (entry.window.version !== expectedVersion) throw new ScheduleMockError('COMMON_CONFLICT', 409)
      const startAt = composeTimestamp(input.startDate, input.startTime)
      const endAt = composeTimestamp(input.endDate, input.endTime)
      this.assertNotOccupiedFor(entry, { startAt, endAt, configuredCapacity: input.configuredCapacity! })
      this.assertNoWindowOverlap({ windowId, startAt, endAt })
      return this.storeWindow(entry, { startAt, endAt, configuredCapacity: input.configuredCapacity! })
    })
  }
  async closeWindow(slot: string, windowId: string, expectedVersion: string, reason: string): Promise<ScheduleWindowReceipt> {
    this.gate(false)
    const reasonIssue = reasonProblem(reason, true)
    if (reasonIssue) throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
    return this.command(slot, JSON.stringify(['close', windowId, expectedVersion, reason]), () => {
      const entry = this.findWindow(windowId)
      if (entry.window.version !== expectedVersion) throw new ScheduleMockError('COMMON_CONFLICT', 409)
      // Occupied windows — including a full SOLD_OUT window — refuse to close (§3/§3.1).
      if (entry.occupied > 0) throw new ScheduleMockError('SCHEDULE_WINDOW_STATE_NOT_ALLOWED', 409)
      entry.window = { ...entry.window, status: 'CLOSED', version: this.bump() }
      entry.updatedAt = this.now()
      return { ...entry.window }
    })
  }
  async openWindow(slot: string, windowId: string, expectedVersion: string): Promise<ScheduleWindowReceipt> {
    this.gate(false)
    return this.command(slot, JSON.stringify(['open', windowId, expectedVersion]), () => {
      const entry = this.findWindow(windowId)
      if (entry.window.version !== expectedVersion) throw new ScheduleMockError('COMMON_CONFLICT', 409)
      this.assertNoWindowOverlap({ windowId, startAt: entry.window.startAt, endAt: entry.window.endAt })
      // Reopen re-judges by occupancy: the system may answer SOLD_OUT directly (§3.1).
      entry.window = { ...entry.window, status: 'OPEN', version: this.bump() }
      entry.updatedAt = this.now()
      return { ...entry.window, status: this.statusFor(entry) }
    })
  }
  /** Batch close: intersecting OPEN/SOLD_OUT windows close; occupied ones are named in
   *  blockedWindows; more than 200 candidates reject the whole command (§3.2). */
  async batchClose(slot: string, fromDate: string, toDate: string, reason: string): Promise<BatchCloseResult> {
    this.gate(false)
    const reasonIssue = reasonProblem(reason, true)
    if (reasonIssue) throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
    const rangeStart = `${composeBeijingDate(fromDate)}T00:00:00.000+08:00`
    const rangeEnd = `${composeBeijingDate(addBeijingDays(toDate, 1))}T00:00:00.000+08:00`
    return this.command(slot, JSON.stringify(['batch', fromDate, toDate, reason]), () => {
      const intersecting = this.store.filter(entry => {
        if (this.statusFor(entry) === 'CLOSED') return false
        return entry.window.startAt < rangeEnd && rangeStart < entry.window.endAt
      })
      if (intersecting.length > 200) throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
      const closedWindows: ScheduleWindowReceipt[] = []
      const blockedWindows: BlockedWindow[] = []
      for (const entry of intersecting) {
        if (entry.occupied > 0) {
          blockedWindows.push({ window: { ...entry.window, status: this.statusFor(entry) }, reasonCode: 'SCHEDULE_WINDOW_STATE_NOT_ALLOWED' })
          continue
        }
        entry.window = { ...entry.window, status: 'CLOSED', version: this.bump() }
        entry.updatedAt = this.now()
        closedWindows.push({ ...entry.window })
      }
      return { storeId: fixtureStoreId, closedWindows, blockedWindows }
    })
  }
  /** batch-generate（CCR-SCH-SLOT-002 §1 覆盖重建）：与「生成区间」（fromDate 起 dailyStart
   *  至 toDate 止 dailyEnd 的连续区间）相交的 OPEN/SOLD_OUT 窗先逐个 close——占用窗跳过
   *  （reasonCode OCCUPIED）且其区间内不再生成（同店重叠校验自然拦住）；随后按
   *  intervalMinutes 从 dailyStart 逐段创建（容量=请求值）。覆盖范围按生成区间（非整历日）
   *  理解：CCR §1 前端段明确「多段多次调用」组装启用日，若按历日关闭，同日后续段的调用会把
   *  前段刚建的窗再关掉——只有生成区间口径使逐段覆盖自洽。候选条目（created+closed+
   *  skipped 上界 = 相交窗数+计划槽数）超 200 整笔 400；部分成功允许，全受阻不伪称成功。 */
  async batchGenerate(slot: string, input: BatchGenerateInput): Promise<BatchGenerateResult> {
    this.gate(false)
    if (batchGenerateProblems(input).length) throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
    return this.command(slot, JSON.stringify(['generate', input]), () => {
      // One window per (date, segment) — the client pre-slices (2026-11-13 ruling).
      const slots: { startAt: string; endAt: string }[] = []
      const sortedDates = [...input.dates].sort()
      const sortedSegments = [...input.segments].sort((a, b) => a.start < b.start ? -1 : 1)
      for (const date of sortedDates) {
        for (const seg of sortedSegments) {
          slots.push({ startAt: composeTimestamp(date, seg.start), endAt: composeTimestamp(date, seg.end) })
        }
      }
      const rangeStart = composeTimestamp(sortedDates[0]!, sortedSegments[0]!.start)
      const rangeEnd = composeTimestamp(sortedDates[sortedDates.length - 1]!, sortedSegments[sortedSegments.length - 1]!.end)
      const intersecting = this.store.filter(entry => {
        if (this.statusFor(entry) === 'CLOSED') return false
        return entry.window.startAt < rangeEnd && rangeStart < entry.window.endAt
      })
      // §1/§3.2 candidate cap: intersecting windows + planned slots must fit one command.
      if (intersecting.length + slots.length > 200) throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
      const closedWindows: ScheduleWindowReceipt[] = []
      const skippedWindows: SkippedWindow[] = []
      const blocked: { startAt: string; endAt: string }[] = []
      for (const entry of intersecting) {
        if (entry.occupied > 0) {
          skippedWindows.push({ window: { ...entry.window, status: this.statusFor(entry) }, reasonCode: 'OCCUPIED' })
          blocked.push({ startAt: entry.window.startAt, endAt: entry.window.endAt })
          continue
        }
        entry.window = { ...entry.window, status: 'CLOSED', version: this.bump() }
        entry.updatedAt = this.now()
        closedWindows.push({ ...entry.window })
      }
      const createdWindows: ScheduleWindowReceipt[] = []
      for (const slot of slots) {
        const suppressed = blocked.some(zone => slot.startAt < zone.endAt && zone.startAt < slot.endAt)
        if (suppressed) continue // 被跳过窗的区间内不再生成（§1）
        const window: ScheduleWindowReceipt = {
          windowId: String(this.nextId++), merchantId: fixtureMerchantId, storeId: fixtureStoreId,
          startAt: slot.startAt, endAt: slot.endAt,
          configuredCapacity: input.configuredCapacity, status: 'OPEN', version: this.bump(),
        }
        this.store.push({ window, updatedAt: this.now(), occupied: 0 })
        createdWindows.push(window)
      }
      return { storeId: fixtureStoreId, createdWindows, closedWindows, skippedWindows }
    })
  }
  /** booking-window（§2）：缺行=默认值 60/7（version 0）；PUT 全替换 + expectedVersion CAS。 */
  async bookingWindow(): Promise<BookingWindowView> {
    this.gate(true)
    const head = this.bookingHead
    return {
      leadMinutes: head?.leadMinutes ?? bookingLeadMinutesDefault,
      maxDays: head?.maxDays ?? bookingMaxDaysDefault,
      // Wire shape (CCR §2): four keys, updatedAt null while the default row is unset.
      version: head ? head.version : '0', updatedAt: head ? this.now() : null,
    }
  }
  async saveBookingWindow(slot: string, form: BookingWindowForm, expectedVersion: string): Promise<BookingWindowReceipt> {
    this.gate(false)
    if (bookingWindowFormProblems(form).length) throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
    return this.command(slot, JSON.stringify(['booking-window', form, expectedVersion]), () => {
      const current = this.bookingHead
      if ((current?.version ?? '0') !== expectedVersion) throw new ScheduleMockError('COMMON_CONFLICT', 409)
      const next = { leadMinutes: form.leadMinutes!, maxDays: form.maxDays!, version: this.bump() }
      this.bookingHead = next
      return { ...next }
    })
  }
  /** CCR-SCH-SLOT-003 §3 week-template：GET 缺行=七天全休 version 0；PUT 全替换 +
   *  expectedVersion CAS，无 reason（改模板非减少可用性动作，已物化窗口不动）。 */
  async weekTemplate(): Promise<WeekTemplateView> {
    this.gate(true)
    const head = this.weekHead
    if (!head) return emptyWeekTemplateView()
    return {
      days: head.days.map(day => ({ enabled: day.enabled, segments: day.segments.map(segment => ({ ...segment })) })),
      version: head.version,
    }
  }
  async saveWeekTemplate(slot: string, form: WeekTemplateForm, expectedVersion: string): Promise<WeekTemplateReceipt> {
    this.gate(false)
    if (weekTemplateFormProblems(form).length) throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
    return this.command(slot, JSON.stringify(['week-template', form, expectedVersion]), () => {
      if ((this.weekHead?.version ?? '0') !== expectedVersion) throw new ScheduleMockError('COMMON_CONFLICT', 409)
      const days = form.days.map(day => ({ enabled: day.enabled, segments: day.segments.map(segment => ({ ...segment })) }))
      this.weekHead = { days, version: this.bump(), updatedAt: this.now() }
      return {
        days: days.map(day => ({ enabled: day.enabled, segments: day.segments.map(segment => ({ ...segment })) })),
        version: this.weekHead.version,
      }
    })
  }
  /** 模板展开（§1 读侧并集，preview 演示）：从锚点次日起到锚点+maxDays（booking-window
   *  口径）的北京历日按周几取段；与任一真实窗口（含 CLOSED——已物化/已关窗事实优先于
   *  展开）相交的段去重；虚拟段恒 OPEN、无占用、不落 store（读时投影，永不物化）。
   *  windowId 确定性（9,000,000+偏移×100+段序，远离 mock 自增 id 空间与 fixtures）。 */
  private templateExpansion(): ScheduleWindowItem[] {
    const head = this.weekHead
    if (!head) return []
    const maxDays = this.bookingHead?.maxDays ?? bookingMaxDaysDefault
    const items: ScheduleWindowItem[] = []
    for (let offset = 1; offset <= maxDays; offset++) {
      const date = addBeijingDays(fixtureScheduleToday, offset)
      const day = head.days[weekdayIndexOf(date)]
      if (!day?.enabled || day.segments.length === 0) continue
      day.segments.forEach((segment, index) => {
        const startAt = composeTimestamp(date, segment.start)
        const endAt = composeTimestamp(date, segment.end)
        const intersects = this.store.some(entry => entry.window.startAt < endAt && startAt < entry.window.endAt)
        if (intersects) return // 与真实窗口重叠的段去重（§1）
        items.push({
          windowId: String(9000000 + offset * 100 + index), merchantId: fixtureMerchantId, storeId: fixtureStoreId,
          startAt, endAt, configuredCapacity: segment.capacity, status: 'OPEN', version: '0', updatedAt: head.updatedAt,
        })
      })
    }
    return items
  }
  async staffWindows(staffId: string): Promise<StaffWindowPage> {
    this.gate(true)
    if (!isId(staffId)) throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
    const items = this.staffStore
      .filter(entry => entry.window.staffId === staffId)
      .map(entry => ({ ...entry.window, updatedAt: entry.updatedAt }))
      .sort((a, b) => (a.startAt < b.startAt ? 1 : -1))
    return { storeId: fixtureStoreId, staffId, items }
  }
  private assertNoStaffOverlap(candidate: { windowId?: string; staffId: string; startAt: string; endAt: string }): void {
    const overlap = this.staffStore.some(entry => {
      const window = entry.window
      if (window.windowId === candidate.windowId || window.staffId !== candidate.staffId) return false
      if (window.status === 'CLOSED') return false
      return candidate.startAt < window.endAt && window.startAt < candidate.endAt
    })
    if (overlap) throw new ScheduleMockError('SCHEDULE_WINDOW_OVERLAP', 409)
  }
  async createStaffWindow(slot: string, staffId: string, input: StaffWindowFormInput): Promise<StaffWindowReceipt> {
    this.gate(false)
    if (!isId(staffId) || staffWindowFormProblems(input).length) throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
    return this.command(slot, JSON.stringify(['staff-create', staffId, input]), () => {
      const startAt = composeTimestamp(input.startDate, input.startTime)
      const endAt = composeTimestamp(input.endDate, input.endTime)
      this.assertNoStaffOverlap({ staffId, startAt, endAt })
      const window: StaffWindowReceipt = {
        windowId: String(this.nextId++), merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        staffId, startAt, endAt, status: 'AVAILABLE', version: this.bump(),
      }
      this.staffStore.push({ window, updatedAt: this.now() })
      return window
    })
  }
  async updateStaffWindow(slot: string, staffId: string, windowId: string, expectedVersion: string, input: StaffWindowFormInput): Promise<StaffWindowReceipt> {
    this.gate(false)
    if (!isId(staffId) || staffWindowFormProblems(input).length) throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
    return this.command(slot, JSON.stringify(['staff-update', staffId, windowId, expectedVersion, input]), () => {
      const entry = this.staffStore.find(candidate => candidate.window.windowId === windowId)
      if (!entry || entry.window.staffId !== staffId) throw new ScheduleMockError('COMMON_NOT_FOUND', 404)
      if (entry.window.version !== expectedVersion) throw new ScheduleMockError('COMMON_CONFLICT', 409)
      const startAt = composeTimestamp(input.startDate, input.startTime)
      const endAt = composeTimestamp(input.endDate, input.endTime)
      // Shrinking availability (the new range no longer covers the old one) is protected —
      // the mock answers the assignment-protection 409 deterministically for occupied rows.
      const shrinks = startAt > entry.window.startAt || endAt < entry.window.endAt
      if (shrinks && entry.window.status === 'AVAILABLE' && this.protectedStaff) {
        throw new ScheduleMockError('SCHEDULE_WINDOW_STATE_NOT_ALLOWED', 409)
      }
      this.assertNoStaffOverlap({ windowId, staffId, startAt, endAt })
      entry.window = { ...entry.window, startAt, endAt, version: this.bump() }
      entry.updatedAt = this.now()
      return { ...entry.window }
    })
  }
  async closeStaffWindow(slot: string, staffId: string, windowId: string, expectedVersion: string, reason: string): Promise<StaffWindowReceipt> {
    this.gate(false)
    const reasonIssue = reasonProblem(reason, true)
    if (reasonIssue || !isId(staffId)) throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
    return this.command(slot, JSON.stringify(['staff-close', staffId, windowId, expectedVersion, reason]), () => {
      const entry = this.staffStore.find(candidate => candidate.window.windowId === windowId)
      if (!entry || entry.window.staffId !== staffId) throw new ScheduleMockError('COMMON_NOT_FOUND', 404)
      if (entry.window.version !== expectedVersion) throw new ScheduleMockError('COMMON_CONFLICT', 409)
      if (entry.window.status === 'AVAILABLE' && this.protectedStaff) {
        throw new ScheduleMockError('SCHEDULE_WINDOW_STATE_NOT_ALLOWED', 409)
      }
      entry.window = { ...entry.window, status: 'CLOSED', version: this.bump() }
      entry.updatedAt = this.now()
      return { ...entry.window }
    })
  }
  async openStaffWindow(slot: string, staffId: string, windowId: string, expectedVersion: string): Promise<StaffWindowReceipt> {
    this.gate(false)
    if (!isId(staffId)) throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
    return this.command(slot, JSON.stringify(['staff-open', staffId, windowId, expectedVersion]), () => {
      const entry = this.staffStore.find(candidate => candidate.window.windowId === windowId)
      if (!entry || entry.window.staffId !== staffId) throw new ScheduleMockError('COMMON_NOT_FOUND', 404)
      if (entry.window.version !== expectedVersion) throw new ScheduleMockError('COMMON_CONFLICT', 409)
      this.assertNoStaffOverlap({ windowId, staffId, startAt: entry.window.startAt, endAt: entry.window.endAt })
      entry.window = { ...entry.window, status: 'AVAILABLE', version: this.bump() }
      entry.updatedAt = this.now()
      return { ...entry.window }
    })
  }
  async capabilities(staffId: string): Promise<CapabilityView> {
    this.gate(true)
    if (!isId(staffId)) throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
    if (this.scenario === 'legacy') this.unavailable() // LEGACY_UNVERSIONED head → 503 quarantine
    const view = this.capabilitiesByStaff.get(staffId)
    if (view) return view
    // Only a staff with neither head nor details answers the empty-version-0 shape (§5).
    return { merchantId: fixtureMerchantId, storeId: fixtureStoreId, staffId, serviceIds: [], version: '0' }
  }
  async replaceCapabilities(slot: string, staffId: string, serviceIds: readonly string[], expectedVersion: string, reason: string): Promise<CapabilityView> {
    this.gate(false)
    if (!isId(staffId) || capabilityProblems(serviceIds, this.capabilitiesByStaff.get(staffId)?.serviceIds ?? [], reason).length) {
      throw new ScheduleMockError('COMMON_INVALID_ARGUMENT', 400)
    }
    return this.command(slot, JSON.stringify(['capability', staffId, [...serviceIds], expectedVersion, reason]), () => {
      const current = this.capabilitiesByStaff.get(staffId)
      if (!current || current.version !== expectedVersion) throw new ScheduleMockError('COMMON_CONFLICT', 409)
      const next: CapabilityView = { merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        staffId, serviceIds: [...serviceIds], version: this.bump() }
      this.capabilitiesByStaff.set(staffId, next)
      return next
    })
  }
}

// ---------------------------------------------------------------------------
// Fixtures: schedule samples spread across the state space so every list/panel state is
// previewable; ids are fixtures and never claim real rows.
// ---------------------------------------------------------------------------
export const fixtureMerchantId = '958001'
export const fixtureStoreId = '958002'
export const fixtureStaffId = '958003'
/** Mock 墙钟锚点（now() 的北京历日 2026-10-06，周二）：周模板展开只面向「未来日期」
 *  （锚点+1 .. 锚点+maxDays），确定性供 preview/测试。 */
export const fixtureScheduleToday = '2026-10-06'
export const fixtureServiceGrooming = '30001'
export const fixtureServiceDelivery = '30007'
/** 2026-10-07 is a Wednesday; unified store-shared slots sit inside one Beijing day
 *  (2026-10-07) without overlapping each other (同店不重叠，CCR-SCH-SLOT-001 §2). The
 *  service fixtures below remain only for the capability head (授权按服务不变). */
const dayAt = (time: string): string => `2026-10-07T${time}:00.000+08:00`
export function fixtureWindows(): MockWindow[] {
  return [
    { window: { windowId: '61001', merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        startAt: dayAt('09:00'), endAt: dayAt('12:00'),
        configuredCapacity: 2, status: 'OPEN', version: '4' }, updatedAt: '2026-10-06T02:00:00.000Z', occupied: 0 },
    { window: { windowId: '61002', merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        startAt: dayAt('15:00'), endAt: dayAt('17:00'),
        configuredCapacity: 2, status: 'OPEN', version: '2' }, updatedAt: '2026-10-06T02:05:00.000Z', occupied: 1 },
    { window: { windowId: '61003', merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        startAt: dayAt('19:30'), endAt: dayAt('21:00'),
        configuredCapacity: 1, status: 'OPEN', version: '3' }, updatedAt: '2026-10-06T02:10:00.000Z', occupied: 1 },
    { window: { windowId: '61004', merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        startAt: dayAt('13:00'), endAt: dayAt('14:30'),
        configuredCapacity: 3, status: 'OPEN', version: '1' }, updatedAt: '2026-10-06T02:15:00.000Z', occupied: 0 },
    { window: { windowId: '61005', merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        startAt: dayAt('17:30'), endAt: dayAt('19:00'),
        configuredCapacity: 2, status: 'OPEN', version: '1' }, updatedAt: '2026-10-06T02:20:00.000Z', occupied: 0 },
    { window: { windowId: '61006', merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        startAt: dayAt('21:30'), endAt: dayAt('22:30'),
        configuredCapacity: 1, status: 'CLOSED', version: '5' }, updatedAt: '2026-10-06T02:25:00.000Z', occupied: 0 },
  ]
}
export function fixtureStaffWindows(): MockStaffWindow[] {
  return [
    { window: { windowId: '62001', merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        staffId: fixtureStaffId, startAt: dayAt('09:00'), endAt: dayAt('12:00'),
        status: 'AVAILABLE', version: '2' }, updatedAt: '2026-10-06T02:30:00.000Z' },
    { window: { windowId: '62002', merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        staffId: fixtureStaffId, startAt: dayAt('14:00'), endAt: dayAt('18:00'),
        status: 'AVAILABLE', version: '1' }, updatedAt: '2026-10-06T02:35:00.000Z' },
    { window: { windowId: '62003', merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        staffId: fixtureStaffId, startAt: dayAt('18:00'), endAt: dayAt('20:00'),
        status: 'CLOSED', version: '3' }, updatedAt: '2026-10-06T02:40:00.000Z' },
  ]
}
export function fixtureCapability(): CapabilityView {
  return { merchantId: fixtureMerchantId, storeId: fixtureStoreId, staffId: fixtureStaffId,
    serviceIds: [fixtureServiceGrooming, fixtureServiceDelivery], version: '2' }
}
