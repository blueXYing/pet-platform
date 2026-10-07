// C 端改期前端切片（10号 §3.8 + 46号 R1/R2/R3 内核，后端同批 PR 交付，IMPLEMENTED_DEFAULT_OFF：
// pet.auth.c.enabled + pet.order.reschedule.http.enabled + pet.order.reschedule.enabled 多层默认
// 关闭）。本文件是改期 C 端投影契约口径的唯一替换点，字段事实全部来自契约与本批实现：
// - POST /api/v1/c/orders/{orderId}/reschedule，严格 JSON，OpenAPI oneOf 二选一分支：
//   到店 {expectedOrderVersion, appointmentStart, appointmentEnd, selectedGeneralWindowId} /
//   接送 {expectedOrderVersion, pickupStart, returnStart, selectedPickupWindowId, selectedReturnWindowId}；
//   46号硬规则：分钟对齐、秒/毫秒为零；接送返程开始 >= 接宠开始 + 120 分钟；时长沿用原订单
//   服务时长快照（页面按原 appointmentStart/End 差值推导，服务端最终校验）。X-Request-Id 由
//   ConsumerApi 分配，五元组幂等：首交与受保护重放同一 200 首次成功回执。
// - 回执恰十二键（46号）：orderId/reservationId/rescheduleId/confirmRound=1/orderVersion/
//   orderStageAtCommit=PENDING_CONFIRM/appointmentStart/appointmentEnd/pickupStart?/returnStart?/
//   rescheduledAt/confirmDeadline；接送两键到店为 null。改期成功后订单回 PENDING_CONFIRM，
//   商家 30 分钟确认窗口重启（confirmDeadline）。
// - 新时段选择复用 booking 的 §3.4 availability 交互范式（10号 §3.4）；39号选窗增补
//   （pet.schedule.selection.enabled）开启时 item 携带 windowId/kind——本页消费该增补构造
//   selected*WindowId；增补未开启（六字段投影）时无法构造 46号请求，页面失败关闭提示。
// - expectedOrderVersion 来自 §3.7 详情投影的 orderVersion（本切片读侧增补）；CAS 409 呈现
//   「重新读取」，重读后按新版本/新 actions 重新门控。
// - 错误面（46号/12号 §6）：每单一次 LIMIT、开始后禁改、已核销/状态、refund_order、容量不足、
//   区间无变化、版本冲突、幂等冲突等逐码中文；推导不放页面（ARCH-005），全部归本模块。

import { ApiError } from '../../shared/request'
import type { WorkspaceScope } from '../../shared/workspace'
import { object } from '../../shared/consumer-api'
import { formatOrderInstant, type OrderDetailView } from './model'

// ---- 回执解码（严格 exact-key，失败关闭） ----

export type RescheduleReceipt = Readonly<{
  orderId: string
  reservationId: string
  rescheduleId: string
  confirmRound: 1
  orderVersion: string
  orderStageAtCommit: 'PENDING_CONFIRM'
  appointmentStart: string
  appointmentEnd: string
  pickupStart: string | null
  returnStart: string | null
  rescheduledAt: string
  confirmDeadline: string
}>

export type RescheduleInput = Readonly<
  | { expectedOrderVersion: string; appointmentStart: string; appointmentEnd: string; selectedGeneralWindowId: string }
  | { expectedOrderVersion: string; pickupStart: string; returnStart: string; selectedPickupWindowId: string; selectedReturnWindowId: string }
>
export type PendingReschedule = Readonly<{ input: RescheduleInput }>

function fail(): never { throw new Error('INVALID_RESPONSE') }
const idPattern = /^[1-9][0-9]{0,18}(?![\s\S])/
const isIdText = (value: unknown): string =>
  typeof value === 'string' && idPattern.test(value) && BigInt(value) <= 9223372036854775807n ? value : fail()
const version = (value: unknown): string =>
  typeof value === 'string' && /^(0|[1-9][0-9]{0,18})(?![\s\S])/.test(value) ? value : fail()
// appendInstant(3)：UTC 毫秒精度 ISO 串且可往返（refund/order-verify instant 同口径）。
const instant = (value: unknown): string => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)) fail()
  if (!Number.isFinite(Date.parse(value)) || new Date(value).toISOString() !== value) fail()
  return value
}
const nullable = <T>(value: unknown, decode: (value: unknown) => T): T | null => value === null ? null : decode(value)
// §3.4 时间投影：带偏移 ISO-8601 分钟精度（+08:00 业务时区投影；解码接受任意合法偏移）。
const offsetInstant = (value: unknown): string => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/.test(value)) fail()
  if (!Number.isFinite(Date.parse(value))) fail()
  return value
}

function exact(value: unknown, keys: readonly string[]): Record<string, any> {
  const v = object(value)
  if (Object.keys(v).sort().join(',') !== [...keys].sort().join(',')) fail()
  return v
}

const receiptKeys = ['orderId', 'reservationId', 'rescheduleId', 'confirmRound', 'orderVersion', 'orderStageAtCommit',
  'appointmentStart', 'appointmentEnd', 'pickupStart', 'returnStart', 'rescheduledAt', 'confirmDeadline']

export function decodeRescheduleReceipt(value: unknown): RescheduleReceipt {
  const v = exact(value, receiptKeys)
  if (v.confirmRound !== 1 || v.orderStageAtCommit !== 'PENDING_CONFIRM') fail()
  const storeBranch = v.pickupStart === null && v.returnStart === null
  const pickupBranch = v.pickupStart !== null && v.returnStart !== null
  if (!storeBranch && !pickupBranch) fail()
  if (storeBranch && (v.appointmentStart === null || v.appointmentEnd === null)) fail()
  return {
    orderId: isIdText(v.orderId), reservationId: isIdText(v.reservationId), rescheduleId: isIdText(v.rescheduleId),
    confirmRound: 1, orderVersion: version(v.orderVersion), orderStageAtCommit: 'PENDING_CONFIRM',
    appointmentStart: instant(v.appointmentStart), appointmentEnd: instant(v.appointmentEnd),
    pickupStart: nullable(v.pickupStart, instant), returnStart: nullable(v.returnStart, instant),
    rescheduledAt: instant(v.rescheduledAt), confirmDeadline: instant(v.confirmDeadline),
  }
}

// ---- 39号 §1 选窗增补解码（六字段 + windowId/kind；严格 exact-key） ----

export type SelectionWindow = Readonly<{
  start: string; end: string
  effectiveCapacity: number; occupiedCount: number; remainingCapacity: number
  available: boolean
  windowId: string
  kind: 'GENERAL' | 'PICKUP' | 'RETURN'
}>
export type SelectionView = Readonly<{ items: readonly SelectionWindow[] }>

const count = (value: any): number => {
  if (!Number.isSafeInteger(value) || value < 0 || value > 1000000) fail()
  return value
}
const selectionKeys = ['start', 'end', 'effectiveCapacity', 'occupiedCount', 'remainingCapacity', 'available', 'windowId', 'kind']

function decodeSelectionWindow(value: unknown): SelectionWindow {
  const v = exact(value, selectionKeys)
  const start = offsetInstant(v.start), end = offsetInstant(v.end)
  const kind = v.kind
  if (kind !== 'GENERAL' && kind !== 'PICKUP' && kind !== 'RETURN') fail()
  return {
    start, end, effectiveCapacity: count(v.effectiveCapacity), occupiedCount: count(v.occupiedCount),
    remainingCapacity: count(v.remainingCapacity), available: typeof v.available === 'boolean' ? v.available : fail(),
    windowId: isIdText(v.windowId), kind,
  }
}

export function decodeSelectionAvailability(value: unknown): SelectionView {
  const v = object(value)
  if (Object.keys(v).sort().join(',') !== 'items') fail()
  if (!Array.isArray(v.items) || v.items.length > 500) fail()
  const items = v.items.map(decodeSelectionWindow)
  for (let index = 1; index < items.length; index++) if (Date.parse(items[index].start) < Date.parse(items[index - 1].start)) fail()
  return { items }
}

/** §3.4 查询（三键；单日跨度）：与 booking 同一路由，登录态读。 */
export function availabilityQuery(serviceId: string, storeId: string, date: string): { path: string; data: Record<string, unknown> } {
  if (!idPattern.test(serviceId) || !idPattern.test(storeId)) throw new Error('INVALID_TARGET')
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(Date.parse(`${date}T00:00:00+08:00`))) throw new Error('INVALID_DATE')
  return { path: `/api/v1/c/services/${serviceId}/availability`, data: { storeId, startDate: date, endDate: date } }
}

// ---- 入口门控与请求构造（46号准入的页面侧门面；真相一律以服务端内核为准） ----

/** 详情页改期入口条件：仅凭服务端 OrderActions.canReschedule（§3.7 事实字段，不推导）。 */
export function canRescheduleEntry(detail: OrderDetailView): boolean {
  return detail.actions?.canReschedule === true
}

export type RescheduleReadiness = Readonly<{ ok: true; version: string; serviceId: string; storeId: string } | { ok: false; notice: string }>

/** 改期页进入就绪度：canReschedule 门控 + 本切片读侧增补三事实齐备（缺失失败关闭）。 */
export function rescheduleReadiness(detail: OrderDetailView): RescheduleReadiness {
  if (!canRescheduleEntry(detail)) return { ok: false, notice: '当前订单不支持改期（以订单实时状态为准；改期次数用尽、已核销、已进入退款或已到预约开始时间后不可改期）。' }
  if (detail.orderVersion === null || detail.serviceId === null || detail.storeId === null)
    return { ok: false, notice: '订单读取服务未升级到改期所需事实（缺少版本/服务/门店坐标），请稍后重试。' }
  return { ok: true, version: detail.orderVersion, serviceId: detail.serviceId, storeId: detail.storeId }
}

/** 原订单服务时长（分钟）：按详情预约起止差推导（46号：改期沿用原时长快照，服务端复验）。 */
export function originalDurationMinutes(detail: OrderDetailView): number | null {
  const start = Date.parse(detail.appointmentStart), end = Date.parse(detail.appointmentEnd)
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return null
  const minutes = Math.round((end - start) / 60000)
  return minutes > 0 && minutes <= 1440 ? minutes : null
}

/** 分钟对齐 ISO 串（46号：秒/毫秒为零；统一 Z 偏移交服务端执行规范）。 */
export function minuteInstant(value: string): string | null {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return null
  if (date.getUTCSeconds() !== 0 || date.getUTCMilliseconds() !== 0) return null
  return date.toISOString()
}

/** 到店分支请求：新预约 = 所选原窗开始 + 原时长（分钟级、覆盖窗起点，内核做完整容量证明）。 */
export function buildStoreInput(window: SelectionWindow, detail: OrderDetailView, versionValue: string): RescheduleInput | null {
  const duration = originalDurationMinutes(detail)
  const start = minuteInstant(window.start)
  if (duration === null || start === null) return null
  const end = new Date(Date.parse(start) + duration * 60000).toISOString()
  return { expectedOrderVersion: versionValue, appointmentStart: start, appointmentEnd: end, selectedGeneralWindowId: window.windowId }
}

/** 接送分支请求：两完整原窗（46号：返程开始 >= 接宠开始 + 120 分钟，服务端复验）。 */
export function buildPickupInput(pickup: SelectionWindow, returning: SelectionWindow, versionValue: string): RescheduleInput | null {
  const pickupStart = minuteInstant(pickup.start), returnStart = minuteInstant(returning.start)
  if (pickupStart === null || returnStart === null) return null
  if (Date.parse(returnStart) < Date.parse(pickupStart) + 120 * 60000) return null
  return { expectedOrderVersion: versionValue, pickupStart, returnStart, selectedPickupWindowId: pickup.windowId, selectedReturnWindowId: returning.windowId }
}

// ---- 展示映射（ARCH-005：事实→展示推导归本模块，页面只消费现成展示值） ----

const pad = (value: number): string => String(value).padStart(2, '0')
const beijingClock = (value: string): string => {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return '—'
  const beijing = new Date(date.getTime() + 8 * 3600000)
  return `${pad(beijing.getUTCHours())}:${pad(beijing.getUTCMinutes())}`
}
const beijingDate = (value: string): string => new Date(new Date(value).getTime() + 8 * 3600000).toISOString().slice(0, 10)

/** 窗口文案（booking windowLabel 同口径：跨天标注「次日」）。 */
export function selectionWindowLabel(start: string, end: string): string {
  const crossDay = beijingDate(end) > beijingDate(start)
  return `${beijingClock(start)}~${crossDay ? '次日' : ''}${beijingClock(end)}`
}

export type RescheduleSlot = Readonly<{ key: string; window: SelectionWindow; label: string; note: string; selectable: boolean; className: string }>

/** 可选 = available && remaining>0；已满置灰「已约满」（booking slotViews 同范式；className 闭集）。 */
export function rescheduleSlots(items: readonly SelectionWindow[], kind: 'GENERAL' | 'PICKUP' | 'RETURN', selected: string | null, duration: number | null): readonly RescheduleSlot[] {
  return items.filter(item => item.kind === kind).map(item => {
    const selectable = item.available && item.remainingCapacity > 0
    const chosen = selected !== null && item.windowId === selected
    const suffix = kind === 'GENERAL' && duration !== null ? `（服务 ${duration} 分钟）` : ''
    return {
      key: item.windowId, window: item, label: `${selectionWindowLabel(item.start, item.end)}${suffix}`,
      note: selectable ? `剩${item.remainingCapacity}个` : '已约满',
      selectable, className: `bkg-slot${chosen ? ' is-selected' : ''}${selectable ? '' : ' is-full'}`,
    }
  })
}

/** 送回候选：仅 RETURN 窗且开始 >= 接宠开始 + 120 分钟（SCH-D4/46号，C 端置灰联动）。 */
export function returnWindowCandidates(items: readonly SelectionWindow[], pickupStart: string): readonly SelectionWindow[] {
  return items.filter(item => item.kind === 'RETURN' && Date.parse(item.start) >= Date.parse(pickupStart) + 120 * 60000)
}

/** 履约分支探测（39号：到店只输出 GENERAL，接送只输出 PICKUP/RETURN；不读订单事实推导）。 */
export function selectionBranch(items: readonly SelectionWindow[]): 'IN_STORE' | 'PICKUP_DELIVERY' | 'EMPTY' {
  if (items.some(item => item.kind === 'GENERAL')) return 'IN_STORE'
  if (items.some(item => item.kind === 'PICKUP' || item.kind === 'RETURN')) return 'PICKUP_DELIVERY'
  return 'EMPTY'
}

/** 成功回执口径（46号）：新预约时间 + 新版本 + 30 分钟确认窗口。 */
export function receiptHeadline(): string { return '改期成功，订单已回到待确认' }
export function receiptBody(receipt: RescheduleReceipt): string {
  const deadline = formatOrderInstant(receipt.confirmDeadline)
  return `新预约时间 ${formatOrderInstant(receipt.appointmentStart)} ~ ${formatOrderInstant(receipt.appointmentEnd).slice(11)}（北京时间）；商家将在 30 分钟内确认或超时自动接单，确认截止 ${deadline}。原核销码已失效，改期后如需核销码请重新获取。`
}
export function receiptRows(receipt: RescheduleReceipt): readonly { id: string; label: string; value: string }[] {
  return [
    { id: 'reservationId', label: '预约编号', value: receipt.reservationId },
    { id: 'rescheduleId', label: '改期编号', value: receipt.rescheduleId },
    { id: 'orderVersion', label: '订单版本', value: receipt.orderVersion },
    { id: 'confirmDeadline', label: '确认截止', value: formatOrderInstant(receipt.confirmDeadline) },
  ]
}

// ---- 错误面（46号/12号 §6 逐码中文；CAS 409 呈现重读） ----

/** CAS 版本冲突（expectedOrderVersion 过期）：呈现重读，重读后按新版本重新门控。 */
export function isVersionConflict(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 409 && error.code === 'COMMON_CONFLICT'
}

/** 这些 409 对该 payload 是终局拒绝（次数/状态/容量/退款事实不回退），可退幂等槽后重新读取。 */
export function isDefiniteRescheduleConflict(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 409
    && ['COMMON_CONFLICT', 'IDEMPOTENCY_KEY_CONFLICT', 'ORDER_RESCHEDULE_LIMIT_REACHED', 'ORDER_RESCHEDULE_AFTER_START',
      'ORDER_STATE_NOT_ALLOWED', 'ORDER_REFUND_ALREADY_CREATED', 'SCHEDULE_CAPACITY_EXCEEDED', 'SCHEDULE_SWAP_FAILED'].includes(error.code)
}

/** 页面错误文案（46号语义 + 12号 §6 映射；403 同时覆盖未知订单的防探测语义）。 */
export function rescheduleMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401) return '登录已失效，请重新登录后再试'
    if (error.code === 'ORDER_RESCHEDULE_LIMIT_REACHED') return '该订单的改期次数已用完（每单最多 1 次）'
    if (error.code === 'ORDER_RESCHEDULE_AFTER_START') return '已到原预约开始时间，不能再改期'
    if (error.code === 'ORDER_STATE_NOT_ALLOWED') return '当前订单状态不支持改期（已核销、已完成或已取消的订单不可改期）'
    if (error.code === 'ORDER_REFUND_ALREADY_CREATED') return '订单已进入退款流程，不能改期'
    if (error.code === 'SCHEDULE_CAPACITY_EXCEEDED') return '所选时段已被占用，请重新选择时段'
    if (error.code === 'SCHEDULE_SWAP_FAILED') return '时段交换失败，原预约保持不变，请重新选择时段'
    if (error.code === 'ORDER_OPERATION_BUSY') return '操作冲突，请稍后按原请求重试；不会重复改期'
    if (error.code === 'IDEMPOTENCY_KEY_CONFLICT') return '上次提交的参数已变化，请核对后重新提交'
    if (error.code === 'COMMON_CONFLICT') return '订单状态已变化（版本冲突或时段无实质变化），请重新读取订单后再试'
    if (error.statusCode === 403) return '仅订单本人可改期，或订单不存在'
    if (error.statusCode === 404) return '改期服务未开放或订单不存在，请稍后再试'
    if (error.statusCode === 400) return '提交内容无效，请重新选择时段后重试'
    if (error.statusCode === 503) return '服务暂不可用，改期结果尚未确认，请稍后重试原操作'
  }
  if (error instanceof Error && error.message === 'PENDING_WRITE_CHANGED') return '上次改期结果尚未确认，请先重试原操作'
  if (error instanceof Error && error.message === 'INVALID_RESPONSE') return '服务返回异常，请稍后重试'
  return '改期结果尚未确认，请重试原操作；不会重复改期'
}

// ---- preview=1 设计验收通道（本地夹具，不发任何网络请求） ----

export type RescheduleScenario = 'normal' | 'conflict' | 'limit'
export const isRescheduleScenario = (value?: string): value is RescheduleScenario =>
  ['normal', 'conflict', 'limit'].includes(value || '')

/** preview 夹具：复用订单模型样例单（canReschedule=true + 读侧增补三事实）。 */
export const PREVIEW_RESCHEDULE_ORDER = '900101001990001'

type PreviewDeps = {
  detail(orderId: string): Promise<OrderDetailView>
  availability(serviceId: string, storeId: string, date: string): Promise<SelectionView>
  reschedule(orderId: string, input: RescheduleInput): Promise<RescheduleReceipt>
  pending(): PendingReschedule | null
  retire(): void
}

/** 内核 46号规则的 preview 模拟：同单仅一次成功改期（limit 场景直接拒绝）；conflict 场景容量 409。 */
export class PreviewRescheduleRepository implements PreviewDeps {
  private applied = new Set<string>()
  private sequence = 0
  constructor(private scenario: RescheduleScenario = 'normal', private detailRepository: { detail(orderId: string): Promise<OrderDetailView> } | null = null) {}
  async detail(orderId: string): Promise<OrderDetailView> {
    if (this.detailRepository !== null) return this.detailRepository.detail(orderId)
    const { PreviewOrderReadRepository } = await import('./model')
    return new PreviewOrderReadRepository('normal').detail(orderId)
  }
  async availability(serviceId: string, storeId: string, date: string): Promise<SelectionView> {
    // 与 booking 夹具同源的日窗，按 39号 §1 增补 windowId/kind（到店型：仅 GENERAL）。
    if (serviceId !== '20001' || storeId !== '957002' || !date.startsWith('2026-10-')) return { items: [] }
    const day = Number(date.slice(8, 10)) - 1
    if (day < 0 || day > 6) return { items: [] }
    const window = (hour: number, minute: number, duration: number, remaining: number): SelectionWindow => {
      const start = Date.parse(`${date}T00:00:00+08:00`) + hour * 3600000 + minute * 60000
      const end = start + duration * 60000
      return {
        start: new Date(start).toISOString(), end: new Date(end).toISOString(),
        effectiveCapacity: Math.max(remaining, 2), occupiedCount: Math.max(2 - remaining, 0),
        remainingCapacity: remaining, available: remaining > 0,
        windowId: String(710500 + day * 10 + hour), kind: 'GENERAL',
      }
    }
    return { items: [
      window(9, 15, 90, 2), window(10, 15, 60, 0), window(14, 0, 90, 1), window(16, 30, 90, 0),
    ] }
  }
  async reschedule(orderId: string, input: RescheduleInput): Promise<RescheduleReceipt> {
    if (this.scenario === 'conflict') throw new ApiError('SCHEDULE_CAPACITY_EXCEEDED', 409)
    if (this.scenario === 'limit' || this.applied.has(orderId)) throw new ApiError('ORDER_RESCHEDULE_LIMIT_REACHED', 409)
    this.applied.add(orderId)
    this.sequence += 1
    const start = 'appointmentStart' in input ? input.appointmentStart : input.pickupStart
    const end = 'appointmentEnd' in input ? input.appointmentEnd : new Date(Date.parse(input.returnStart) + 50 * 60000).toISOString()
    const now = new Date()
    return {
      orderId, reservationId: '900102001990001', rescheduleId: String(9601000000000000 + this.sequence),
      confirmRound: 1, orderVersion: '1', orderStageAtCommit: 'PENDING_CONFIRM',
      appointmentStart: start, appointmentEnd: end,
      pickupStart: 'pickupStart' in input ? input.pickupStart : null,
      returnStart: 'returnStart' in input ? input.returnStart : null,
      rescheduledAt: now.toISOString(), confirmDeadline: new Date(now.getTime() + 30 * 60000).toISOString(),
    }
  }
  pending(): PendingReschedule | null { return null }
  retire(): void { /* preview 无幂等槽 */ }
}

// ---- 页面控制器（详情门控 + 一个主体写操作；未知结果写保留原 payload，仅显式重试重发） ----

export type RescheduleDeps = {
  detail(orderId: string): Promise<OrderDetailView>
  reschedule(orderId: string, input: RescheduleInput): Promise<RescheduleReceipt>
  pendingReschedule(orderId: string): PendingReschedule | null
  retireConflict(orderId: string, error: unknown): void
}

export type RescheduleState = Readonly<{
  phase: 'idle' | 'loading' | 'ready' | 'ineligible' | 'unauthorized' | 'load-error'
  orderId: string | null
  detail: OrderDetailView | null
  busy: boolean
  receipt: RescheduleReceipt | null
  pending: PendingReschedule | null
  notice: string
}>

const initialState = (): RescheduleState => ({ phase: 'idle', orderId: null, detail: null, busy: false, receipt: null, pending: null, notice: '' })

export class RescheduleController {
  private state = initialState()
  private listeners = new Set<() => void>()
  private active = true
  private epoch = 0
  private reads = 0
  private unsubscribe: () => void
  constructor(private deps: RescheduleDeps, private scope: WorkspaceScope) {
    this.unsubscribe = scope.subscribe(() => { this.epoch++; this.reads++; this.publish(initialState()) })
  }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish(state: RescheduleState) { if (!this.active) return; this.state = Object.freeze(state); this.listeners.forEach(listener => listener()) }
  private live(epoch: number) { return this.active && epoch === this.epoch }

  /** 读取订单详情：入口门控仅凭服务端 actions.canReschedule；401 进未登录态。 */
  async load(orderId: string) {
    if (!this.active || this.state.busy || !isOrderIdText(orderId)) return
    const epoch = this.epoch, run = ++this.reads
    this.publish({ ...this.state, phase: 'loading', notice: '' })
    try {
      const detail = await this.deps.detail(orderId)
      if (!this.live(epoch) || run !== this.reads) return
      this.publish({ ...this.state, phase: canRescheduleEntry(detail) ? 'ready' : 'ineligible', orderId, detail, receipt: null, pending: this.deps.pendingReschedule(orderId), notice: '' })
    } catch (error) {
      if (!this.live(epoch) || run !== this.reads) return
      const unauthorized = error instanceof ApiError && error.statusCode === 401
      this.publish({ ...this.state, phase: unauthorized ? 'unauthorized' : 'load-error', orderId, detail: null, pending: null, notice: rescheduleMessage(error) })
    }
  }

  /** 提交改期（模块构造的契约载荷）；有未确认命令时只能重试原操作。 */
  async submit(orderId: string, input: RescheduleInput) {
    if (!this.active || this.state.phase !== 'ready' || this.state.busy) return
    if (this.state.pending !== null) return this.retry(orderId)
    await this.run(orderId, input)
  }

  /** 重试未确认的原操作（同 X-Request-Id、同 payload；重放返回首次成功回执）。 */
  async retry(orderId: string) {
    const pending = this.state.pending
    if (!pending || !this.active || this.state.busy) return
    await this.run(orderId, pending.input)
  }

  private async run(orderId: string, input: RescheduleInput) {
    const epoch = this.epoch
    this.reads++
    this.publish({ ...this.state, busy: true, notice: '' })
    try {
      const receipt = await this.deps.reschedule(orderId, input)
      if (!this.live(epoch)) return
      // 成功后清除未确认槽并回读订单详情（no-store）；入口门控随即失效，回执仍保留展示。
      this.publish({ ...this.state, busy: false, pending: this.deps.pendingReschedule(orderId), notice: '' })
      await this.load(orderId)
      if (this.live(epoch)) this.publish({ ...this.getSnapshot(), receipt })
    } catch (error) {
      if (!this.live(epoch)) return
      // 终局 409 可解锁（退幂等槽后重新读取）；未知结果保留原命令继续重试原操作。
      let rejected = false
      if (isDefiniteRescheduleConflict(error)) {
        try { this.deps.retireConflict(orderId, error); rejected = true } catch { rejected = false }
      }
      const unauthorized = error instanceof ApiError && error.statusCode === 401
      this.publish({ ...this.state, busy: false,
        pending: rejected || unauthorized ? null : this.deps.pendingReschedule(orderId),
        ...(unauthorized ? { phase: 'unauthorized' as const, detail: null, orderId: null } : {}),
        notice: rescheduleMessage(error) })
      // 终局拒绝后的回读保持拒绝文案可见（重读清空的提示在此恢复），门控以最新 actions 为准。
      if (rejected && this.live(epoch)) {
        await this.load(orderId)
        if (this.live(epoch)) this.publish({ ...this.getSnapshot(), notice: rescheduleMessage(error) })
      }
    }
  }

  /** 页面挂载时恢复未确认命令（仅提示 + 重试入口，不自动发送）。 */
  restore(orderId: string) {
    if (!this.active || this.state.busy) return
    const pending = this.deps.pendingReschedule(orderId)
    if (pending) this.publish({ ...this.state, pending, notice: '已恢复上次未确认的改期，请重试原操作（沿用原请求编号，不会重复改期）' })
  }
  dispose() { this.active = false; this.epoch++; this.unsubscribe(); this.listeners.clear() }
}

const isOrderIdText = (value: string): boolean => idPattern.test(value) && BigInt(value) <= 9223372036854775807n
