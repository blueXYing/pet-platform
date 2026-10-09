// 预约下单 + 支付发起切片（10号 §3.4/§3.5/§3.6 + 11号 CreateOrderRequest/CreateOrderData）。
// 本文件是本切片的模块层：严格 exact-key 解码、事实→展示推导、逐错误码中文映射、preview 夹具
// 全部在这里（ARCH-005 分工；页面只消费现成展示值）。字段以契约为唯一来源：
// - GET /api/v1/c/services/{serviceId}/availability（§3.4）：登录态读（SCH-D1，Bearer 强制）；
//   Query 仅 storeId/startDate/endDate（未知参数 400）；items 元素六字段投影
//   start/end/effectiveCapacity/occupiedCount/remainingCapacity/available，分钟级带偏移 ISO-8601
//   （业务时区 +08:00），按 start 升序；available=false 统一表达不可约/已占满（不引入状态枚举）。
//   39号 selection（pet.schedule.selection.enabled 装配）开启时 item 增补 windowId/kind
//   （两键同进同出）；接送选窗 ID 的唯一展示来源（36号：可约 GET 只作展示，hold 锁内复核为最终授权）。
// - POST /api/v1/c/orders（§3.5）：X-Request-Id 必填；请求体 CreateOrderRequest 四键全必填
//   （storeId/serviceId/petId/fulfillmentType）+ 履约分支互斥（11号 oneOf，46号改期同惯例）：
//   到店 = appointmentStart/appointmentEnd（+可选 selectedGeneralWindowId，缺省由服务端锁内
//   解析唯一完整容纳原窗）；接送 = pickupStart/returnStart/selectedPickupWindowId/
//   selectedReturnWindowId/serviceAddress 五键必填、禁止到店键。接送强校验
//   returnStart >= pickupStart + 120min 与所选原窗开始值一致（C 端置灰联动，服务端 36/38号
//   内核锁内复核，SCH-D4/ROC-2）。回执 CreateOrderData 五字段
//   orderId/orderNo/displayStatus/payAmount/paymentExpireAt。
// - POST /api/v1/c/orders/{orderId}/payments（§3.6）：请求体固定 {channel:'WECHAT_MINI_PROGRAM'}
//   （V1 不展示支付方式选择）；回执 paymentId/paymentNo/channel/wechatPayParameters 五键。
//   支付渠道为无正式渠道参数的沙箱态（40号）：回执如实呈现，不虚构支付成功、不调起收银台。
// 边界（PR 登记）：优惠券选择（couponInstanceId）不在本切片，请求体一律不携带该键；
// 接送履约已随 36号公开选窗字段同步切片解锁（PICKUP_SELECTION_ENABLED=true，#128 失败关闭
// 注记移除）；本切片仍不发送 kind 查询参数。

import { ApiError } from '../../shared/request'
import { displayOrderStatuses, displayStatusLabels, statusVariant, type DisplayOrderStatus } from '../orders/model'

// ---- §3.4 可预约时段（严格 exact-key 解码，失败关闭） ----

/** 39号 selection 增补键的闭集；未装配 selection 时六字段投影无这两键。 */
export type WindowKind = 'GENERAL' | 'PICKUP' | 'RETURN'
export type AvailabilityItem = Readonly<{
  start: string; end: string
  effectiveCapacity: number; occupiedCount: number; remainingCapacity: number
  available: boolean
  windowId: string | null   // 39号 selection 装配后非空；null = 旧六字段投影（无选窗身份）
  kind: WindowKind | null
}>
export type AvailabilityView = Readonly<{ items: readonly AvailabilityItem[] }>

const invalid = (): never => { throw new Error('INVALID_RESPONSE') }
const objectLike = (value: unknown): Record<string, any> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid()
  return value as Record<string, any>
}
function exact(value: unknown, keys: readonly string[]): Record<string, any> {
  const v = objectLike(value)
  for (const key of Object.keys(v)) if (!keys.includes(key)) invalid()
  return v
}
function requiredKey(record: Record<string, any>, key: string): any {
  if (!(key in record)) invalid()
  return record[key]
}
// §3.4 时间投影：带偏移 ISO-8601 分钟精度（+08:00 业务时区投影；解码接受任意合法偏移）。
const offsetInstant = (value: any): string => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/.test(value)) invalid()
  if (!Number.isFinite(Date.parse(value))) invalid()
  return value
}
const count = (value: any): number => {
  if (!Number.isSafeInteger(value) || value < 0 || value > 1000000) invalid()
  return value
}
const isIdText = (value: any): string => {
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,18}(?![\s\S])/.test(value) || BigInt(value) > 9223372036854775807n) invalid()
  return value
}
const plainText = (value: any, maxLength: number): string => {
  if (typeof value !== 'string' || !value || value.length > maxLength) invalid()
  return value
}
const beijingAmount = (value: any): string => {
  if (typeof value !== 'string' || !/^(?:0|[1-9][0-9]{0,15})\.[0-9]{2}(?![\s\S])/.test(value)) invalid()
  return value
}
const booleanOf = (value: any): boolean => {
  if (typeof value !== 'boolean') invalid()
  return value
}

const availabilityKeys = ['start', 'end', 'effectiveCapacity', 'occupiedCount', 'remainingCapacity', 'available']
const selectionKeys = ['windowId', 'kind']
const windowKindOf = (value: any): WindowKind => {
  if (value !== 'GENERAL' && value !== 'PICKUP' && value !== 'RETURN') invalid()
  return value
}
export function decodeAvailabilityItem(value: unknown): AvailabilityItem {
  const v = exact(value, [...availabilityKeys, ...selectionKeys])
  // 39号 selection 增补：windowId/kind 同进同出；只有其一或值非法即失败关闭。
  const hasWindowId = 'windowId' in v
  const hasKind = 'kind' in v
  if (hasWindowId !== hasKind) invalid()
  return {
    start: offsetInstant(requiredKey(v, 'start')), end: offsetInstant(requiredKey(v, 'end')),
    effectiveCapacity: count(requiredKey(v, 'effectiveCapacity')), occupiedCount: count(requiredKey(v, 'occupiedCount')),
    remainingCapacity: count(requiredKey(v, 'remainingCapacity')), available: booleanOf(requiredKey(v, 'available')),
    windowId: hasWindowId ? isIdText(v.windowId) : null,
    kind: hasKind ? windowKindOf(v.kind) : null,
  }
}
export function decodeAvailability(value: unknown): AvailabilityView {
  const v = exact(value, ['items'])
  if (!Array.isArray(v.items) || v.items.length > 500) invalid()
  const items = v.items.map(decodeAvailabilityItem)
  for (let index = 1; index < items.length; index++) if (Date.parse(items[index].start) < Date.parse(items[index - 1].start)) invalid()
  return { items }
}

// ---- §3.4 查询参数（仅三键；日期为 yyyy-MM-DD，跨度按单日查询） ----

const isCalendarDate = (value: string): boolean =>
  /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(`${value}T00:00:00+08:00`))
export function availabilityQuery(serviceId: string, storeId: string, date: string): { path: string; data: Record<string, unknown> } {
  if (!/^[1-9][0-9]{0,18}$/.test(serviceId) || !/^[1-9][0-9]{0,18}$/.test(storeId)) throw new Error('INVALID_TARGET')
  if (!isCalendarDate(date)) throw new Error('INVALID_DATE')
  // 单日查询（startDate=endDate）：C 端按所选日期逐日拉取；跨度上限 31 天由服务端守卫。
  return { path: `/api/v1/c/services/${serviceId}/availability`, data: { storeId, startDate: date, endDate: date } }
}

// ---- 时段选择推导（可选 = available 且 remaining>0；已满置灰「已约满」，无状态枚举） ----

const pad = (value: number): string => String(value).padStart(2, '0')
/** 带偏移 ISO-8601 → 北京时间 HH:mm（设备时区不作假设，#116/#120 同口径）。 */
export function beijingClock(instant: string): string {
  const date = new Date(instant)
  if (!Number.isFinite(date.getTime())) return '—'
  const beijing = new Date(date.getTime() + 8 * 3600000)
  return `${pad(beijing.getUTCHours())}:${pad(beijing.getUTCMinutes())}`
}
function beijingDate(instant: string): string {
  return new Date(new Date(instant).getTime() + 8 * 3600000).toISOString().slice(0, 10)
}
/** 分钟级时段窗口文案：跨天（寄养/过夜）窗口如实标注「次日」。 */
export function windowLabel(start: string, end: string): string {
  const crossDay = beijingDate(end) > beijingDate(start)
  return `${beijingClock(start)}~${crossDay ? '次日' : ''}${beijingClock(end)}`
}

export type SlotView = Readonly<{ key: string; start: string; end: string; label: string; note: string; selectable: boolean; className: string }>
/** 可选 = available && remainingCapacity>0；已满（available=false 或 remaining=0）置灰「已约满」。
 *  className 变体闭集（WXSS 铁律：仅 className，禁 data-*）。 */
export function slotViews(items: readonly AvailabilityItem[], selected: string | null): readonly SlotView[] {
  return items.map(item => {
    const selectable = item.available && item.remainingCapacity > 0
    const chosen = selected !== null && item.start === selected
    return {
      key: item.start, start: item.start, end: item.end, label: windowLabel(item.start, item.end),
      note: selectable ? `剩${item.remainingCapacity}个` : '已约满',
      selectable,
      className: `bkg-slot${chosen ? ' is-selected' : ''}${selectable ? '' : ' is-full'}`,
    }
  })
}

// ---- 日期条（业务时区 Asia/Shanghai 的「今天」由调用方传入；默认 7 天，≤31 天跨度上限） ----

export type BookingDate = Readonly<{ iso: string; label: string; weekday: string; className: string }>
const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'] as const
export function beijingToday(now = Date.now()): string {
  return new Date(now + 8 * 3600000).toISOString().slice(0, 10)
}
function shiftDate(iso: string, days: number): string {
  return new Date(Date.parse(`${iso}T12:00:00+08:00`) + days * 86400000).toISOString().slice(0, 10)
}
export function bookingDates(today: string, count = 7, selected: string | null = null): readonly BookingDate[] {
  if (!isCalendarDate(today) || count < 1 || count > 31) throw new Error('INVALID_DATE')
  return Array.from({ length: count }, (_, index) => {
    const iso = index === 0 ? today : shiftDate(today, index)
    const weekday = weekdays[new Date(Date.parse(`${iso}T12:00:00+08:00`)).getUTCDay()]
    return {
      iso, weekday,
      label: index === 0 ? '今天' : `${Number(iso.slice(5, 7))}月${Number(iso.slice(8, 10))}日`,
      className: `bkg-date${selected === iso ? ' is-selected' : ''}`,
    }
  })
}

// ---- §3.5 下单请求构造（CreateOrderRequest 唯一来源；显式 null 不入 JSON） ----

export type FulfillmentKind = 'IN_STORE' | 'PICKUP_DELIVERY'

/** 接送履约开放开关（36号联合契约公开选窗字段同步切片，2026-10-07 解锁）：
 *  selectedPickupWindowId/selectedReturnWindowId/serviceAddress 已进 11号公开
 *  CreateOrderRequest，#128 的接送 400 失败关闭注记随之移除。false 可整体回退接送呈现
 *  （历史失败关闭语义），true 时接送选项可选、纯接送服务正常进入三段选窗。 */
export const PICKUP_SELECTION_ENABLED: boolean = true

/** 履约方式区呈现（模块层推导，页面只消费；接送项随 PICKUP_SELECTION_ENABLED 可选）。 */
export type FulfillmentModeView = Readonly<{ id: FulfillmentKind; label: string; note: string; className: string }>
export function fulfillmentModes(current: FulfillmentKind): readonly FulfillmentModeView[] {
  return [
    { id: 'IN_STORE', label: '到店服务', note: '本服务到店履约',
      className: `bkg-mode${current === 'IN_STORE' ? ' is-selected' : ''}` },
    { id: 'PICKUP_DELIVERY', label: '上门接送',
      note: PICKUP_SELECTION_ENABLED ? '接送履约（含接宠/送回时段）' : '暂未开放，待选窗契约同步',
      className: `bkg-mode${current === 'PICKUP_DELIVERY' ? ' is-selected' : ''}${PICKUP_SELECTION_ENABLED ? '' : ' is-disabled'}` },
  ]
}

/** 预约半屏弹层标题（690:4506 原稿头部为「预约上门」；到店履约按现有文案体系适配为
 *  「到店预约」，展示值由模块层唯一给出，弹层与直连页共用）。 */
export function bookingSheetTitle(fulfillmentType: FulfillmentKind): string {
  return fulfillmentType === 'PICKUP_DELIVERY' ? '预约上门' : '到店预约'
}

export type BookingDraft = Readonly<{
  storeId: string; serviceId: string; petId: string
  fulfillmentType: FulfillmentKind
  appointmentStart: string; appointmentEnd: string
  pickupStart: string | null; returnStart: string | null
  selectedPickupWindowId: string | null
  selectedReturnWindowId: string | null
  serviceAddress: string
  remark: string
}>
/** 接送硬规则（§3.5 强校验 + SSOT）：returnStart >= pickupStart + 120 分钟（C 端置灰联动）。 */
export function pickupReturnIntervalInvalid(pickupStart: string, returnStart: string): boolean {
  return Date.parse(returnStart) < Date.parse(pickupStart) + 120 * 60000
}
/** 接宠候选：接送服务的 PICKUP 方向原窗（36号 ROC-2：上门方向只能选 PICKUP 窗）。 */
export function pickupCandidates(items: readonly AvailabilityItem[]): readonly AvailabilityItem[] {
  return items.filter(item => item.kind === 'PICKUP')
}
/** 送回候选：RETURN 方向原窗且满足 120 分钟间隔（36号 ROC-2 + SCH-D4 C 端置灰联动）。 */
export function returnCandidates(items: readonly AvailabilityItem[], pickupStart: string): readonly AvailabilityItem[] {
  return items.filter(item => item.kind === 'RETURN' && !pickupReturnIntervalInvalid(pickupStart, item.start))
}
export type BookingFormError = 'store' | 'service' | 'pet' | 'window' | 'pickup' | 'return' | 'interval'
  | 'selection' | 'address' | 'remark'
/** 草稿里的时刻值按契约词法校验（复用解码器口径；非法值按表单错误呈现，不抛出）。 */
const isInstantLike = (value: string): boolean => {
  try { offsetInstant(value); return true } catch { return false }
}
const isIdLike = (value: string | null): boolean =>
  value !== null && /^[1-9][0-9]{0,18}$/.test(value) && (value.length < 19 || BigInt(value) <= 9223372036854775807n)
export const bookingFormErrorLabels: Readonly<Record<BookingFormError, string>> = {
  store: '门店参数无效，请从服务详情重新进入',
  service: '服务参数无效，请从服务详情重新进入',
  pet: '请选择宠物',
  window: '请选择预约时段',
  pickup: '请选择接宠时段',
  return: '请选择送回时段',
  interval: '送回开始需不早于接宠开始后 120 分钟，请重新选择送回时段',
  selection: '所选时段暂无选窗信息（排期选窗装配未开放），请稍后重试或选择到店服务',
  address: '请填写接送服务地址（省市区＋详细地址）',
  remark: '备注最多500字',
}
export function bookingFormError(draft: BookingDraft): BookingFormError | null {
  if (!/^[1-9][0-9]{0,18}$/.test(draft.storeId)) return 'store'
  if (!/^[1-9][0-9]{0,18}$/.test(draft.serviceId)) return 'service'
  if (!/^[1-9][0-9]{0,18}$/.test(draft.petId)) return 'pet'
  if (draft.fulfillmentType !== 'IN_STORE' && draft.fulfillmentType !== 'PICKUP_DELIVERY') return 'window'
  if (draft.fulfillmentType === 'PICKUP_DELIVERY') {
    if (draft.pickupStart === null || !isInstantLike(draft.pickupStart)) return 'pickup'
    if (draft.returnStart === null || !isInstantLike(draft.returnStart)) return 'return'
    if (pickupReturnIntervalInvalid(draft.pickupStart, draft.returnStart)) return 'interval'
    if (!isIdLike(draft.selectedPickupWindowId) || !isIdLike(draft.selectedReturnWindowId)) return 'selection'
    if (draft.selectedPickupWindowId === draft.selectedReturnWindowId) return 'selection'
    if (!draft.serviceAddress.trim()) return 'address'
  } else {
    if (!isInstantLike(draft.appointmentStart) || !isInstantLike(draft.appointmentEnd)) return 'window'
  }
  if ([...draft.remark].length > 500) return 'remark'
  return null
}
/** 构造 CreateOrderRequest（11号 oneOf 履约分支）：四键全必填；到店带 appointmentStart/
 *  appointmentEnd（selectedGeneralWindowId 缺省由服务端锁内解析，本切片不发送）；
 *  接送带 pickupStart/returnStart/selectedPickupWindowId/selectedReturnWindowId/serviceAddress
 *  五键、不携带到店键；remark 非空才带；couponInstanceId 不在本切片（PR 登记边界）；
 *  显式 null 不入 JSON（C 解析器拒绝 null）。 */
export function buildOrderRequest(draft: BookingDraft): Record<string, unknown> {
  const error = bookingFormError(draft)
  if (error !== null) throw new Error(`BOOKING_FORM_${error.toUpperCase()}`)
  const remark = draft.remark.trim()
  const request: Record<string, unknown> = {
    storeId: draft.storeId, serviceId: draft.serviceId, petId: draft.petId,
    fulfillmentType: draft.fulfillmentType,
  }
  if (draft.fulfillmentType === 'PICKUP_DELIVERY') {
    request.pickupStart = draft.pickupStart
    request.returnStart = draft.returnStart
    request.selectedPickupWindowId = draft.selectedPickupWindowId
    request.selectedReturnWindowId = draft.selectedReturnWindowId
    request.serviceAddress = draft.serviceAddress.trim()
  } else {
    request.appointmentStart = draft.appointmentStart
    request.appointmentEnd = draft.appointmentEnd
  }
  if (remark) request.remark = remark
  return request
}

/** 幂等恢复：从 pendingCommand 的 CreateOrderRequest 载荷还原草稿（同载荷重试同一
 *  X-Request-Id，23号 §5）。载荷非本切片形态（未知键/非法值）返回 null，页面按新单处理。 */
export function draftFromCommandData(data: unknown): BookingDraft | null {
  try {
    const v = objectLike(data)
    const pickup = v.fulfillmentType === 'PICKUP_DELIVERY'
    const request = buildOrderRequest({
      storeId: isIdText(v.storeId), serviceId: isIdText(v.serviceId), petId: isIdText(v.petId),
      fulfillmentType: pickup ? 'PICKUP_DELIVERY' : 'IN_STORE',
      appointmentStart: pickup ? '' : offsetInstant(v.appointmentStart),
      appointmentEnd: pickup ? '' : offsetInstant(v.appointmentEnd),
      pickupStart: pickup ? offsetInstant(v.pickupStart) : null,
      returnStart: pickup ? offsetInstant(v.returnStart) : null,
      selectedPickupWindowId: pickup ? isIdText(v.selectedPickupWindowId) : null,
      selectedReturnWindowId: pickup ? isIdText(v.selectedReturnWindowId) : null,
      serviceAddress: pickup ? plainText(v.serviceAddress, 65536) : '',
      remark: v.remark === undefined ? '' : plainText(v.remark, 500),
    })
    // buildOrderRequest 会重排/裁剪键；与原载荷全等校验（顺序无关）确认无字段漂移。
    const original: Record<string, unknown> = {}
    for (const key of Object.keys(v)) original[key] = v[key]
    if (JSON.stringify(original) !== JSON.stringify(request)) return null
    return {
      storeId: String(request.storeId), serviceId: String(request.serviceId), petId: String(request.petId),
      fulfillmentType: pickup ? 'PICKUP_DELIVERY' : 'IN_STORE',
      appointmentStart: pickup ? '' : String(request.appointmentStart),
      appointmentEnd: pickup ? '' : String(request.appointmentEnd),
      pickupStart: pickup ? String(request.pickupStart) : null,
      returnStart: pickup ? String(request.returnStart) : null,
      selectedPickupWindowId: pickup ? String(request.selectedPickupWindowId) : null,
      selectedReturnWindowId: pickup ? String(request.selectedReturnWindowId) : null,
      serviceAddress: pickup ? String(request.serviceAddress) : '',
      remark: v.remark === undefined ? '' : String(v.remark),
    }
  } catch {
    return null
  }
}

// ---- §3.5 回执解码（CreateOrderData：卡面必需键缺失失败关闭） ----

export type CreateOrderReceipt = Readonly<{
  orderId: string; orderNo: string
  displayStatus: DisplayOrderStatus
  payAmount: string; paymentExpireAt: string
}>
export function decodeCreateOrderReceipt(value: unknown): CreateOrderReceipt {
  const v = exact(value, ['orderId', 'orderNo', 'displayStatus', 'payAmount', 'paymentExpireAt'])
  const display = requiredKey(v, 'displayStatus')
  if (typeof display !== 'string' || !(displayOrderStatuses as readonly string[]).includes(display)) invalid()
  return {
    orderId: isIdText(requiredKey(v, 'orderId')), orderNo: isIdText(requiredKey(v, 'orderNo')),
    displayStatus: display as DisplayOrderStatus,
    payAmount: beijingAmount(requiredKey(v, 'payAmount')), paymentExpireAt: offsetInstant(requiredKey(v, 'paymentExpireAt')),
  }
}
/** 回执状态徽标（展示值由服务端统一计算，模块只做标签与 className 变体映射）。 */
export function receiptBadge(receipt: CreateOrderReceipt): Readonly<{ label: string; className: string }> {
  return { label: displayStatusLabels[receipt.displayStatus], className: statusVariant(receipt.displayStatus) }
}
/** 支付截止文案（北京时间分钟粒度，#120 formatOrderInstant 同口径）。 */
export function paymentDeadline(receipt: CreateOrderReceipt): string {
  const beijing = new Date(new Date(receipt.paymentExpireAt).getTime() + 8 * 3600000)
  if (!Number.isFinite(beijing.getTime())) return '—'
  return `${beijing.toISOString().slice(0, 10).replace(/-/g, '/')} ${beijing.toISOString().slice(11, 16)}（北京时间）`
}

// ---- §3.6 发起支付（channel 固定传输；回执五键如实解码） ----

export const PAYMENT_CHANNEL = 'WECHAT_MINI_PROGRAM'
export type WechatPayParameters = Readonly<{ timeStamp: string; nonceStr: string; package: string; signType: string; paySign: string }>
export type PaymentReceipt = Readonly<{
  paymentId: string; paymentNo: string; channel: string
  wechatPayParameters: WechatPayParameters
}>
const wechatParameterKeys = ['timeStamp', 'nonceStr', 'package', 'signType', 'paySign']
export function decodePaymentReceipt(value: unknown): PaymentReceipt {
  const v = exact(value, ['paymentId', 'paymentNo', 'channel', 'wechatPayParameters'])
  const parameters = requiredKey(v, 'wechatPayParameters')
  const p = exact(parameters, wechatParameterKeys)
  const decoded: WechatPayParameters = {
    timeStamp: plainText(requiredKey(p, 'timeStamp'), 64), nonceStr: plainText(requiredKey(p, 'nonceStr'), 256),
    package: plainText(requiredKey(p, 'package'), 256), signType: plainText(requiredKey(p, 'signType'), 32),
    paySign: plainText(requiredKey(p, 'paySign'), 512),
  }
  return { paymentId: isIdText(requiredKey(v, 'paymentId')), paymentNo: isIdText(requiredKey(v, 'paymentNo')),
    channel: plainText(requiredKey(v, 'channel'), 64), wechatPayParameters: decoded }
}
/** 渠道沙箱态提示（40号：无正式渠道参数；不虚构支付成功，不调起收银台）。 */
export const paymentSandboxNotice = '当前为支付渠道沙箱环境：已按契约创建支付单并返回渠道参数，但未接通真实微信支付收银台，不会自动扣款。请回到订单详情或列表查看支付状态。'

// ---- 支付入口门禁（仅凭服务端 OrderActions.canPay，§3.7 #123；不推导业务真相） ----

export function canInitiatePayment(detail: { actions: { canPay: boolean } | null }): boolean {
  return detail.actions?.canPay === true
}
/** 支付入口不可用时的说明（仅凭契约布尔缺失/为假推导，不发明规则）。 */
export function paymentGateNotice(canPay: boolean): string {
  return canPay ? '' : '当前订单状态不支持发起支付（以订单实时状态为准，订单过期或状态变化后不可支付）。'
}

// ---- 逐错误码中文映射（12号错误码语义 + §3.5/§3.6 典型错误 + 404 防探测/开关关） ----

const createCodeMessages: Readonly<Record<string, string>> = {
  MERCHANT_DISABLED: '商家已停用，暂不可预约',
  STORE_DISABLED: '门店已停用，暂不可预约',
  SERVICE_NOT_BOOKABLE: '该服务当前不可预约，请重新选择',
  SCHEDULE_NOT_AVAILABLE: '排期已变化，请重新选择时段',
  SCHEDULE_CAPACITY_EXCEEDED: '该时段已约满，请重新选择时段',
  SCHEDULE_PICKUP_RETURN_INTERVAL_INVALID: '接送间隔不足 120 分钟，请重新选择送回时段',
  COUPON_NOT_AVAILABLE: '优惠券不可用，请移除后重试',
  IDEMPOTENCY_KEY_CONFLICT: '该请求编号已被其他内容使用，请勿重复提交，稍后重试',
}
export function bookingCreateMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401) return '登录已失效，请重新登录后提交'
    if (error.code === 'COMMON_UNAUTHORIZED') return '登录已失效，请重新登录后提交'
    if (error.statusCode === 400 || error.code === 'COMMON_INVALID_ARGUMENT') return '提交内容无效，请检查预约信息后重试'
    if (error.statusCode === 404 || error.code === 'COMMON_NOT_FOUND') return '下单服务未开放或服务不存在，请稍后再试'
    if (error.statusCode === 403 || error.code === 'COMMON_FORBIDDEN') return '当前账号无权预约该服务'
    if (error.statusCode === 409 && createCodeMessages[error.code]) return createCodeMessages[error.code]
    if (createCodeMessages[error.code]) return createCodeMessages[error.code]
    if (error.statusCode === 409) return '预约状态已变化，请重新读取核对；不要更换请求编号盲目重试'
    if (error.statusCode === 503 || error.code === 'COMMON_DEPENDENCY_UNAVAILABLE') return '服务暂不可用，提交结果尚未确认，请保留原操作后重试'
  }
  if (error instanceof Error && error.message === 'INVALID_RESPONSE') return '服务返回异常，请稍后重试'
  if (error instanceof Error && error.message === 'PENDING_WRITE_CHANGED') return '上次提交结果尚未确认，请先按原内容重试或刷新后重新选择'
  return '提交未确认成功，请重试原操作；不会重复创建订单'
}
export function bookingPaymentMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401 || error.code === 'COMMON_UNAUTHORIZED') return '登录已失效，请重新登录后发起支付'
    if (error.statusCode === 400 || error.code === 'COMMON_INVALID_ARGUMENT') return '支付请求无效，请重新进入本页重试'
    if (error.statusCode === 404 || error.code === 'COMMON_NOT_FOUND') return '订单不存在或支付服务未开放，请回到订单详情查看'
    if (error.statusCode === 403 || error.code === 'COMMON_FORBIDDEN') return '仅订单本人可发起支付'
    if (error.code === 'IDEMPOTENCY_KEY_CONFLICT') return createCodeMessages.IDEMPOTENCY_KEY_CONFLICT
    if (error.statusCode === 409) return '订单支付状态已变化，请回到订单详情查看；不要更换请求编号盲目重试'
    if (error.statusCode === 503 || error.code === 'COMMON_DEPENDENCY_UNAVAILABLE') return '支付服务暂不可用，发起结果尚未确认，请保留原操作后重试'
  }
  if (error instanceof Error && error.message === 'INVALID_RESPONSE') return '服务返回异常，请稍后重试'
  if (error instanceof Error && error.message === 'PENDING_WRITE_CHANGED') return '上次支付发起结果尚未确认，请先重试原操作'
  return '支付发起未确认成功，请重试原操作；不会重复发起'
}
export function availabilityReadMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401 || error.code === 'COMMON_UNAUTHORIZED') return '登录已失效，请重新登录后查看可约时段'
    if (error.statusCode === 400 || error.code === 'COMMON_INVALID_ARGUMENT') return '时段查询参数无效，请重新选择日期'
    if (error.statusCode === 404 || error.code === 'SERVICE_NOT_FOUND' || error.code === 'COMMON_NOT_FOUND') return '服务不存在或不可预约，请从服务详情重新进入'
    if (error.statusCode === 503 || error.code === 'COMMON_DEPENDENCY_UNAVAILABLE') return '排期事实暂不可用，已停止展示，请稍后重试'
  }
  if (error instanceof Error && error.message === 'INVALID_RESPONSE') return '服务返回异常，请稍后重试'
  return '可约时段读取失败，请稍后重试'
}

// ---- preview=1 夹具通道（本地样例数据，不发任何网络请求；设计验收专用） ----

export type BookingScenario = 'normal' | 'unavailable' | 'conflict' | 'pay-error' | 'pickup'  // pickup：接送成功场景（选窗字段同步后解锁）
export const isBookingScenario = (value?: string): value is BookingScenario =>
  ['normal', 'unavailable', 'conflict', 'pay-error', 'pickup'].includes(value || '')

export const PREVIEW_BOOKING_SERVICE = '20001'   // service/model 夹具：专业美容套餐 ¥80/60min IN_STORE
export const PREVIEW_BOOKING_STORE = '957002'
export const PREVIEW_PICKUP_SERVICE = '20002'    // 家庭寄养（同夹具店）；接送演示用
/** preview 日期条锚点（夹具 2026-10-01~07；真实模式用 beijingToday()）。 */
export const PREVIEW_BOOKING_TODAY = '2026-10-01'
/** preview 下单回执订单 = orders 夹具待支付单（actions.canPay=true），支付链路可续走。 */
export const PREVIEW_PAY_ORDER = '900101001990000'
function fixtureInstant(dayOffset: number, hour: number, minute: number): string {
  // 夹具锚定 2026-10（与 orders 夹具同期），稳定可重复；跨小时加法由分钟数自然进位。
  const base = Date.parse('2026-10-01T00:00:00+08:00') + dayOffset * 86400000 + hour * 3600000 + minute * 60000
  const beijing = new Date(base + 8 * 3600000)
  return `${beijing.toISOString().slice(0, 10)}T${beijing.toISOString().slice(11, 16)}:00.000+08:00`
}
/** 夹具日以 2026-10-01 为第 0 天：39号 selection 投影（windowId/kind 与六键同发）。 */
function fixtureWindow(dayOffset: number, index: number, kind: WindowKind, hour: number, minute: number, durationMinutes: number, remaining: number, capacity: number): AvailabilityItem {
  const start = fixtureInstant(dayOffset, hour, minute)
  const end = fixtureInstant(dayOffset, hour, minute + durationMinutes)
  return {
    start, end, effectiveCapacity: capacity, occupiedCount: capacity - remaining,
    remainingCapacity: remaining, available: remaining > 0,
    windowId: `2019${String(dayOffset).padStart(2, '0')}${String(index).padStart(2, '0')}`, kind,
  }
}

// 分钟级样例（非整点）：09:15~10:45 可约、10:15~11:15 已满、14:00~15:30 可约、16:30~18:00 已满；
// 跨天窗口（寄养）：20:00~次日 09:00 可约。到店服务全 GENERAL；接送服务前两窗 PICKUP、后三窗
// RETURN（36号 ROC-2 双方向原窗；09:15+120=11:15 起 14:00/16:30/20:00 均为合法送回）。
const fixtureWindows = (dayOffset: number, pickup = false): AvailabilityItem[] => pickup
  ? [
    fixtureWindow(dayOffset, 1, 'PICKUP', 9, 15, 90, 2, 3),
    fixtureWindow(dayOffset, 2, 'PICKUP', 10, 15, 60, 0, 2),
    fixtureWindow(dayOffset, 3, 'RETURN', 14, 0, 90, 1, 1),
    fixtureWindow(dayOffset, 4, 'RETURN', 16, 30, 90, 0, 4),
    fixtureWindow(dayOffset, 5, 'RETURN', 20, 0, 780, 3, 5),
  ]
  : [
    fixtureWindow(dayOffset, 1, 'GENERAL', 9, 15, 90, 2, 3),
    fixtureWindow(dayOffset, 2, 'GENERAL', 10, 15, 60, 0, 2),
    fixtureWindow(dayOffset, 3, 'GENERAL', 14, 0, 90, 1, 1),
    fixtureWindow(dayOffset, 4, 'GENERAL', 16, 30, 90, 0, 4),
    fixtureWindow(dayOffset, 5, 'GENERAL', 20, 0, 780, 3, 5),
  ]

export type BookingDeps = {
  availability(serviceId: string, storeId: string, date: string): Promise<AvailabilityView>
  create(draft: BookingDraft): Promise<CreateOrderReceipt>
  pay(orderId: string): Promise<PaymentReceipt>
}

/** CONTRACT MOCK（preview=1）：按冻结契约语义本地应答——404 SERVICE_NOT_FOUND 防探测、
 *  503 失败关闭、SCHEDULE_CAPACITY_EXCEEDED 排期冲突——但不是真实后端联调，永不授权任何人。 */
export class PreviewBookingRepository implements BookingDeps {
  constructor(private scenario: BookingScenario = 'normal') {}
  async availability(serviceId: string, storeId: string, date: string): Promise<AvailabilityView> {
    if (this.scenario === 'unavailable') throw new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)
    if (serviceId !== PREVIEW_BOOKING_SERVICE && serviceId !== PREVIEW_PICKUP_SERVICE) throw new ApiError('SERVICE_NOT_FOUND', 404)
    if (storeId !== PREVIEW_BOOKING_STORE) throw new ApiError('SERVICE_NOT_FOUND', 404)
    const offset = Number(date.slice(8, 10)) - 1
    if (!(date.startsWith('2026-10-')) || offset < 0 || offset > 6) return { items: [] }
    // 接送服务按 39号 selection 投影返回 PICKUP/RETURN 方向窗；到店服务为 GENERAL。
    return { items: fixtureWindows(offset, serviceId === PREVIEW_PICKUP_SERVICE).map(item => ({ ...item })) }
  }
  async create(draft: BookingDraft): Promise<CreateOrderReceipt> {
    if (this.scenario === 'conflict') throw new ApiError('SCHEDULE_CAPACITY_EXCEEDED', 409)
    if (bookingFormError(draft) !== null) throw new ApiError('COMMON_INVALID_ARGUMENT', 400)
    if (draft.serviceId !== PREVIEW_BOOKING_SERVICE && draft.serviceId !== PREVIEW_PICKUP_SERVICE) throw new ApiError('SERVICE_NOT_FOUND', 404)
    const amount = draft.serviceId === PREVIEW_BOOKING_SERVICE ? '80.00' : '60.00'
    // 回执订单号沿用 orders 夹具的待支付单（canPay=true），preview 链路可继续进入支付发起页。
    return {
      orderId: PREVIEW_PAY_ORDER, orderNo: '2026100100001', displayStatus: 'PENDING_PAYMENT',
      payAmount: amount, paymentExpireAt: '2026-10-01T09:55:00.000+08:00',
    }
  }
  async pay(orderId: string): Promise<PaymentReceipt> {
    if (this.scenario === 'pay-error') throw new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)
    if (!/^[1-9][0-9]{0,18}$/.test(orderId)) throw new ApiError('COMMON_INVALID_ARGUMENT', 400)
    return {
      paymentId: '900103001990001', paymentNo: '2026100200101', channel: 'LAKALA_WECHAT',
      wechatPayParameters: { timeStamp: '1791234567', nonceStr: 'preview-nonce', package: 'prepay_id=wxpreview01', signType: 'RSA', paySign: 'preview-sign' },
    }
  }
}
/** 夹具自检：坏夹具直接在测试期暴露（全量重解一遍，含接送方向窗）。 */
export function validateBookingFixtures(): boolean {
  try {
    for (let day = 0; day <= 6; day++) {
      decodeAvailability({ items: fixtureWindows(day) })
      decodeAvailability({ items: fixtureWindows(day, true) })
    }
    return true
  } catch { return false }
}
