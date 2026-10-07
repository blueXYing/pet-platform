// M-002 schedule maintenance view state (SCH-004 write side). Wire shapes follow the
// Schedule Write Contract v0.1 (docs/04-api/53-Schedule-Write-Contract-v0.1.md) as shipped by
// MerchantScheduleController (develop 02abe89, switch pet.schedule.command.http.enabled default
// OFF). Decoders stay strict exact-key (fail closed); the real repository is the single swap
// point and the preview repository is a contract mock, never a real integration claim.
//
// Contract anchors:
// - Service windows: identity storeId+serviceId+windowKind fixed at create; minute-precision
//   half-open [startAt,endAt) with NO fixed 60-minute slot (SSOT §12.1); statuses
//   OPEN/CLOSED/SOLD_OUT where SOLD_OUT is a system-derived state (occupied >= capacity) the
//   merchant can never set by hand and can lift only by raising capacity (same-transaction
//   re-judge). Occupied windows block close/time-change/capacity-decrease (409), allow
//   capacity-raise. No DELETE; weekly templates (dayOfWeek/repeatWeekly/copyNextWeek) are
//   explicitly not introduced. batch-close: single request capped at 200 intersecting
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

export type WindowKind = 'GENERAL' | 'PICKUP' | 'RETURN'
export const windowKinds: readonly WindowKind[] = ['GENERAL', 'PICKUP', 'RETURN']
export const windowKindText: Record<WindowKind, string> = {
  GENERAL: '通用（到店）', PICKUP: '上门接', RETURN: '返程送回',
}
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

/** GET /merchant/stores/{storeId}/availability-windows item (read projection carries updatedAt). */
export type ScheduleWindowItem = Readonly<{
  windowId: string; merchantId: string; storeId: string; serviceId: string
  windowKind: WindowKind; startAt: string; endAt: string; configuredCapacity: number
  status: WindowStatus; version: string; updatedAt: string
}>
/** POST/PUT/close/open success data: the window receipt has no updatedAt on the wire. */
export type ScheduleWindowReceipt = Readonly<{
  windowId: string; merchantId: string; storeId: string; serviceId: string
  windowKind: WindowKind; startAt: string; endAt: string; configuredCapacity: number
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
const kindOf = (value: any): WindowKind =>
  windowKinds.includes(value) ? value as WindowKind : invalid()
const statusOf = (value: any): WindowStatus =>
  windowStatuses.includes(value) ? value as WindowStatus : invalid()
const staffStatusOf = (value: any): StaffWindowStatus =>
  staffWindowStatuses.includes(value) ? value as StaffWindowStatus : invalid()

const windowItemKeys = ['windowId', 'merchantId', 'storeId', 'serviceId', 'windowKind', 'startAt',
  'endAt', 'configuredCapacity', 'status', 'version', 'updatedAt'] as const
const windowReceiptKeys = ['windowId', 'merchantId', 'storeId', 'serviceId', 'windowKind', 'startAt',
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
    serviceId: isId(v.serviceId) ? v.serviceId : invalid(),
    windowKind: kindOf(v.windowKind), startAt: timestamp(v.startAt), endAt: timestamp(v.endAt),
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
    serviceId: isId(v.serviceId) ? v.serviceId : invalid(),
    windowKind: kindOf(v.windowKind), startAt: timestamp(v.startAt), endAt: timestamp(v.endAt),
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
export type WindowFormInput = Readonly<{
  serviceId: string
  windowKind: WindowKind
  startDate: string; startTime: string
  endDate: string; endTime: string
  configuredCapacity: number | null
}>
export type StaffWindowFormInput = Readonly<{
  startDate: string; startTime: string; endDate: string; endTime: string
}>
export function emptyWindowForm(today: string, now: string): WindowFormInput {
  return { serviceId: '', windowKind: 'GENERAL', startDate: today, startTime: now, endDate: today, endTime: now, configuredCapacity: null }
}
export function emptyStaffWindowForm(today: string, now: string): StaffWindowFormInput {
  return { startDate: today, startTime: now, endDate: today, endTime: now }
}
export function windowFormFromItem(item: ScheduleWindowItem): WindowFormInput {
  const start = splitBeijingParts(item.startAt), end = splitBeijingParts(item.endAt)
  return { serviceId: item.serviceId, windowKind: item.windowKind,
    startDate: start.date, startTime: start.time, endDate: end.date, endTime: end.time,
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
  if (!isId(form.serviceId)) problems.push('服务不能为空')
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
/** PRD29 页面引导：IN_STORE services open GENERAL windows only, PICKUP_DELIVERY services
 *  open PICKUP/RETURN only; the server 400s on illegal pairs — the picker narrows choices. */
export function kindsForFulfillment(fulfillmentType: 'IN_STORE' | 'PICKUP_DELIVERY' | null): readonly WindowKind[] {
  if (fulfillmentType === 'IN_STORE') return ['GENERAL']
  if (fulfillmentType === 'PICKUP_DELIVERY') return ['PICKUP', 'RETURN']
  return windowKinds
}
/** reason（1..500）mandatory on close / batch-close / availability reductions; the page
 *  mirrors the server answer (400) before sending. */
export function reasonProblem(reason: string, required: boolean): string | null {
  const length = [...reason.trim()].length
  if (required && length < 1) return '请填写操作原因（必填，1-500字）'
  if (length > 500) return '操作原因最多500字'
  return null
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
 *  fall back to the legacy unpaginated mode — and serviceId/kind/status are the optional
 *  server-side filters (status feeds both the list chips and the summary count queries). */
export type WindowPageQuery = Readonly<{
  page: number; pageSize: number
  serviceId?: string; kind?: WindowKind | ''; status?: WindowStatus | ''
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
  /** Overlap against OPEN/SOLD_OUT peers of the same store+service+kind (SOLD_OUT counts as
   *  an open slot for overlap, contract §3.1); adjacent half-open intervals are legal. */
  private assertNoWindowOverlap(candidate: { windowId?: string; serviceId: string; windowKind: WindowKind; startAt: string; endAt: string }): void {
    const overlap = this.store.some(entry => {
      const window = entry.window
      if (window.windowId === candidate.windowId) return false
      if (window.serviceId !== candidate.serviceId || window.windowKind !== candidate.windowKind) return false
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
      .filter(entry => (query.serviceId ? entry.window.serviceId === query.serviceId : true))
      .filter(entry => (query.kind ? entry.window.windowKind === query.kind : true))
      .filter(entry => (query.status ? this.statusFor(entry) === query.status : true))
      .map(entry => this.view(entry))
      // §3.3 wire order — start_at ascending with id ascending tiebreak — so the preview
      // slices the same order the server slices; CLOSED windows stay listed (§3 read).
      .sort((a, b) => (a.startAt === b.startAt ? Number(a.windowId) - Number(b.windowId) : a.startAt < b.startAt ? -1 : 1))
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
      this.assertNoWindowOverlap({ serviceId: input.serviceId, windowKind: input.windowKind, startAt, endAt })
      const window: ScheduleWindowReceipt = {
        windowId: String(this.nextId++), merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        serviceId: input.serviceId, windowKind: input.windowKind, startAt, endAt,
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
      this.assertNoWindowOverlap({ windowId, serviceId: input.serviceId, windowKind: input.windowKind, startAt, endAt })
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
      this.assertNoWindowOverlap({ windowId, serviceId: entry.window.serviceId,
        windowKind: entry.window.windowKind, startAt: entry.window.startAt, endAt: entry.window.endAt })
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
export const fixtureServiceGrooming = '30001'
export const fixtureServiceDelivery = '30007'
/** 2026-10-07 is a Wednesday; windows sit inside one Beijing day (2026-10-07 09:00-12:00). */
const dayAt = (time: string): string => `2026-10-07T${time}:00.000+08:00`
export function fixtureWindows(): MockWindow[] {
  return [
    { window: { windowId: '61001', merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        serviceId: fixtureServiceGrooming, windowKind: 'GENERAL', startAt: dayAt('09:00'), endAt: dayAt('12:00'),
        configuredCapacity: 2, status: 'OPEN', version: '4' }, updatedAt: '2026-10-06T02:00:00.000Z', occupied: 0 },
    { window: { windowId: '61002', merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        serviceId: fixtureServiceGrooming, windowKind: 'GENERAL', startAt: dayAt('13:00'), endAt: dayAt('15:00'),
        configuredCapacity: 2, status: 'OPEN', version: '2' }, updatedAt: '2026-10-06T02:05:00.000Z', occupied: 1 },
    { window: { windowId: '61003', merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        serviceId: fixtureServiceGrooming, windowKind: 'GENERAL', startAt: dayAt('16:00'), endAt: dayAt('18:00'),
        configuredCapacity: 1, status: 'OPEN', version: '3' }, updatedAt: '2026-10-06T02:10:00.000Z', occupied: 1 },
    { window: { windowId: '61004', merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        serviceId: fixtureServiceDelivery, windowKind: 'PICKUP', startAt: dayAt('09:30'), endAt: dayAt('11:30'),
        configuredCapacity: 3, status: 'OPEN', version: '1' }, updatedAt: '2026-10-06T02:15:00.000Z', occupied: 0 },
    { window: { windowId: '61005', merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        serviceId: fixtureServiceDelivery, windowKind: 'RETURN', startAt: dayAt('14:00'), endAt: dayAt('16:00'),
        configuredCapacity: 2, status: 'OPEN', version: '1' }, updatedAt: '2026-10-06T02:20:00.000Z', occupied: 0 },
    { window: { windowId: '61006', merchantId: fixtureMerchantId, storeId: fixtureStoreId,
        serviceId: fixtureServiceGrooming, windowKind: 'GENERAL', startAt: dayAt('19:00'), endAt: dayAt('20:00'),
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
