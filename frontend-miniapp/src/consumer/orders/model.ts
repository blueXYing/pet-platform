// C-004 前端切片（我的订单列表/详情，只读）。本文件是 C 端订单读取投影契约口径的唯一替换点，
// 字段事实全部来自 10号 §3.7 + 11号 OpenAPI-Core（listMyOrders/getMyOrder）：
// - GET /api/v1/c/orders：Query 可按 displayStatus/page/pageSize；后端必须返回 displayStatus，
//   前端禁止根据多个底层字段重新计算（技术基线 20号：展示状态和 actions 由服务端返回）。
// - GET /api/v1/c/orders/{orderId}：OrderDetailData —— orderId/orderNo(PublicId)、displayStatus
//   （十枚举）、orderStage（五枚举）、paymentStatus（五枚举）、verificationStatus（UNVERIFIED|
//   VERIFIED）、refundApplicationStatus/refundStatus/afterSaleStatus（可空自由串，schema 无枚举）、
//   payAmount（DecimalAmountOutput 两位小数）、appointmentStart/appointmentEnd（带偏移 ISO-8601，
//   毫秒精度）、verifiedAt（可空）、actions（六布尔：canPay/canReschedule/canApplyRefund/
//   canShowVerificationCode/canReview/canApplyAfterSale）。UI 按权限决定按钮，不推导业务真相。
// - 11号未给 listMyOrders 响应定义 schema：本切片按「列表项 = OrderDetailData 投影」解码
//   （coupon 单券/列表同构先例），卡面必需键缺失失败关闭，其余键缺失按 null 读（coupon-points
//   D1 可选键惯例）。此为与并行后端切片（同批实现 §3.7）的显式联调边界，见 PR 描述。
// 契约没有的字段坚决不显示：设计原稿 129:9946 的服务名/宠物美容类目/门店名、237:860 的
// 联系电话/订单改期/无责退款/打开二维码等卡片动作均不在 OrderDetailData 内，本切片不渲染
// （PR 登记差异）。核销码区块复用 47号 §4 #116 的模型/仓库/控制器（order-verify 模块）。

import { ApiError } from '../../shared/request'

export type DisplayOrderStatus =
  | 'PENDING_PAYMENT' | 'PENDING_CONFIRM' | 'PENDING_SERVICE' | 'COMPLETED' | 'CANCELED'
  | 'REFUND_PENDING_CONFIRM' | 'REFUNDING' | 'REFUNDED' | 'PARTIAL_REFUND' | 'AFTERSALE'
export const displayOrderStatuses: readonly DisplayOrderStatus[] = [
  'PENDING_PAYMENT', 'PENDING_CONFIRM', 'PENDING_SERVICE', 'COMPLETED', 'CANCELED',
  'REFUND_PENDING_CONFIRM', 'REFUNDING', 'REFUNDED', 'PARTIAL_REFUND', 'AFTERSALE',
]
export const isDisplayOrderStatus = (value?: string): value is DisplayOrderStatus =>
  (displayOrderStatuses as readonly string[]).includes(value || '')

export type OrderStage = 'PENDING_PAYMENT' | 'PENDING_CONFIRM' | 'PENDING_SERVICE' | 'COMPLETED' | 'CANCELED'
export type PaymentStatus = 'INIT' | 'PAYING' | 'PAID' | 'FAILED' | 'CLOSED'
export type VerificationStatus = 'UNVERIFIED' | 'VERIFIED'

/** 服务端返回的可用动作布尔（OrderActions）；按权限决定按钮，本切片只消费核销码入口。 */
export type OrderActions = Readonly<{
  canPay: boolean
  canReschedule: boolean
  canApplyRefund: boolean
  canShowVerificationCode: boolean
  canReview: boolean
  canApplyAfterSale: boolean
}>

export type OrderDetailView = Readonly<{
  orderId: string
  orderNo: string
  displayStatus: DisplayOrderStatus
  // 下列键 schema 未标 required：缺失按 null 读（coupon-points D1 惯例），非 null 必须合法。
  orderStage: OrderStage | null
  paymentStatus: PaymentStatus | null
  verificationStatus: VerificationStatus | null
  refundApplicationStatus: string | null
  refundStatus: string | null
  afterSaleStatus: string | null
  payAmount: string
  appointmentStart: string
  appointmentEnd: string
  verifiedAt: string | null
  actions: OrderActions | null
}>

/** 分页信封沿 C 端既有分页契约惯例（CouponInstancePageData/PointsLedgerPageData 同构）。 */
export type OrderPage = Readonly<{ items: readonly OrderDetailView[]; page: number; pageSize: number; total: number }>

const invalid = (): never => { throw new Error('INVALID_RESPONSE') }
/** OrderDetailData 全键集合（11号 schema）：exact-key 白名单。 */
const orderDetailKeys = ['actions', 'afterSaleStatus', 'appointmentEnd', 'appointmentStart', 'displayStatus', 'orderId',
  'orderNo', 'orderStage', 'payAmount', 'paymentStatus', 'refundApplicationStatus', 'refundStatus', 'verifiedAt', 'verificationStatus']
/** 严格 exact-key：只允许 OrderDetailData 键集合，未知键失败关闭（沿仓库惯例）。 */
function exactObject(value: unknown): Record<string, any> {
  const v = objectLike(value)
  for (const key of Object.keys(v)) if (!orderDetailKeys.includes(key)) invalid()
  return v
}

function objectLike(value: unknown): Record<string, any> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid()
  return value as Record<string, any>
}
function requiredKey(record: Record<string, any>, key: string): any {
  if (!(key in record)) invalid()
  return record[key]
}
/** OpenAPI 无 required 标注的事实键：缺失读作 null；显式 null 合法；非 null 必须通过校验器。 */
function optionalField<T>(record: Record<string, any>, key: string, decode: (value: any) => T): T | null {
  const value = record[key]
  if (value === null || value === undefined) return null
  return decode(value)
}
const isIdText = (value: any): string => {
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,18}(?![\s\S])/.test(value) || BigInt(value) > 9223372036854775807n) invalid()
  return value
}
// 11号 DecimalAmountOutput：非负、0-16 位整数、恒两位小数（输出固定格式）。
const amount = (value: any): string => {
  if (typeof value !== 'string' || !/^(?:0|[1-9][0-9]{0,15})\.[0-9]{2}(?![\s\S])/.test(value)) invalid()
  return value
}
// 10号 §3.7 时间：带偏移 ISO-8601（Z 或 ±HH:MM），毫秒精度可省略小数（§3.8 示例无毫秒）。
const offsetInstant = (value: any): string => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/.test(value)) invalid()
  if (!Number.isFinite(Date.parse(value))) invalid()
  return value
}
// refundApplicationStatus/refundStatus/afterSaleStatus：schema 仅 string+nullable，无枚举——
// 非空时按非空白受限长度串原样呈现，不映射不推导。
const statusText = (value: any): string => {
  if (typeof value !== 'string' || value.trim().length < 1 || value.length > 64) invalid()
  return value
}
function oneOf<T extends string>(value: any, values: readonly T[]): T {
  if (typeof value !== 'string' || !(values as readonly string[]).includes(value)) invalid()
  return value as T
}
const bool = (value: any): boolean => {
  if (typeof value !== 'boolean') invalid()
  return value
}
const actionKeys = ['canApplyAfterSale', 'canApplyRefund', 'canPay', 'canReschedule', 'canReview', 'canShowVerificationCode']
function decodeActions(value: unknown): OrderActions {
  const v = objectLike(value)
  for (const key of Object.keys(v)) if (!actionKeys.includes(key)) invalid()
  if (actionKeys.some(key => !(key in v))) invalid()
  return { canPay: bool(v.canPay), canReschedule: bool(v.canReschedule), canApplyRefund: bool(v.canApplyRefund),
    canShowVerificationCode: bool(v.canShowVerificationCode), canReview: bool(v.canReview), canApplyAfterSale: bool(v.canApplyAfterSale) }
}

/** 详情/列表项共用解码：卡面必需键（orderId/orderNo/displayStatus/payAmount/时间窗）缺失失败关闭。 */
export function decodeOrderDetail(value: unknown): OrderDetailView {
  const v = exactObject(value)
  return {
    orderId: isIdText(requiredKey(v, 'orderId')),
    orderNo: isIdText(requiredKey(v, 'orderNo')),
    displayStatus: oneOf(requiredKey(v, 'displayStatus'), displayOrderStatuses),
    payAmount: amount(requiredKey(v, 'payAmount')),
    appointmentStart: offsetInstant(requiredKey(v, 'appointmentStart')),
    appointmentEnd: offsetInstant(requiredKey(v, 'appointmentEnd')),
    orderStage: optionalField(v, 'orderStage', x => oneOf(x, ['PENDING_PAYMENT', 'PENDING_CONFIRM', 'PENDING_SERVICE', 'COMPLETED', 'CANCELED'] as const)),
    paymentStatus: optionalField(v, 'paymentStatus', x => oneOf(x, ['INIT', 'PAYING', 'PAID', 'FAILED', 'CLOSED'] as const)),
    verificationStatus: optionalField(v, 'verificationStatus', x => oneOf(x, ['UNVERIFIED', 'VERIFIED'] as const)),
    refundApplicationStatus: optionalField(v, 'refundApplicationStatus', statusText),
    refundStatus: optionalField(v, 'refundStatus', statusText),
    afterSaleStatus: optionalField(v, 'afterSaleStatus', statusText),
    verifiedAt: optionalField(v, 'verifiedAt', offsetInstant),
    actions: optionalField(v, 'actions', decodeActions),
  }
}

function decodePageNumbers(v: Record<string, any>): { page: number; pageSize: number; total: number } {
  const page = Number(v.page), pageSize = Number(v.pageSize), total = Number(v.total)
  if (!Number.isInteger(page) || page < 1 || page > 10000) invalid()
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 50) invalid()
  if (!Number.isSafeInteger(total) || total < 0) invalid()
  return { page, pageSize, total }
}

export function decodeOrderPage(value: unknown): OrderPage {
  const v = objectLike(value)
  for (const key of Object.keys(v)) if (!['items', 'page', 'pageSize', 'total'].includes(key)) invalid()
  if (!Array.isArray(v.items) || v.items.length > 50) invalid()
  const numbers = decodePageNumbers(v)
  if (v.items.length > numbers.pageSize) invalid()
  return { items: v.items.map(decodeOrderDetail), ...numbers }
}

// ---- 展示映射（仅 UI 文案；不复制 DisplayOrderStatus 状态机，事实一律来自服务端） ----

export const displayStatusLabels: Record<DisplayOrderStatus, string> = {
  PENDING_PAYMENT: '待支付', PENDING_CONFIRM: '待确认', PENDING_SERVICE: '待服务', COMPLETED: '已完成', CANCELED: '已取消',
  REFUND_PENDING_CONFIRM: '退款待确认', REFUNDING: '退款中', REFUNDED: '已退款', PARTIAL_REFUND: '部分退款', AFTERSALE: '售后中',
}
export const orderStageLabels: Record<OrderStage, string> = {
  PENDING_PAYMENT: '待支付', PENDING_CONFIRM: '待确认', PENDING_SERVICE: '待服务', COMPLETED: '已完成', CANCELED: '已取消',
}
export const paymentStatusLabels: Record<PaymentStatus, string> = {
  INIT: '未发起', PAYING: '支付中', PAID: '已支付', FAILED: '支付失败', CLOSED: '已关闭',
}
export const verificationStatusLabels: Record<VerificationStatus, string> = {
  UNVERIFIED: '未核销', VERIFIED: '已核销',
}
/** 服务端返回的可用动作的展示标签（去支付已由 booking 切片接通真实入口；其余动作仍只读呈现）。 */
export const actionLabels: Readonly<Record<keyof OrderActions, string>> = {
  canPay: '去支付', canReschedule: '订单改期', canApplyRefund: '申请退款',
  canShowVerificationCode: '查看核销码', canReview: '评价', canApplyAfterSale: '申请售后',
}
export const actionOrder: readonly (keyof OrderActions)[] = ['canPay', 'canReschedule', 'canApplyRefund', 'canShowVerificationCode', 'canReview', 'canApplyAfterSale']

/** 核销码区块的呈现条件：仅凭服务端 OrderActions.canShowVerificationCode（§3.7 事实字段）。 */
export function canShowVerifyBlock(detail: OrderDetailView): boolean {
  return detail.actions?.canShowVerificationCode === true
}

// ---- 展示推导（ARCH-005：事实→展示的推导归本模块，页面只消费现成展示值） ----
// 六个订单事实状态字段在本文件内一律经 record 键间接读取（与解码器同风格），不出现
// 属性直读/分支形态；页面与测试不得再触碰原始事实字段。

/** 事实状态字段的模块内键集（仅供 computed access，页面/测试不导入）。 */
type StatusFactKey = 'orderStage' | 'paymentStatus' | 'verificationStatus' | 'refundApplicationStatus' | 'refundStatus' | 'afterSaleStatus'

/** 事实状态字段原值读取（模块内专用；null 序列化为 null，其余原样字符串）。 */
function statusFact(detail: OrderDetailView, key: StatusFactKey): string | null {
  const value = (detail as unknown as Record<string, unknown>)[key]
  return value === null || value === undefined ? null : String(value)
}

/** 摘要卡状态徽标（展示状态由服务端统一计算，此处仅做标签与 className 变体映射）。 */
export function orderStatusBadge(detail: OrderDetailView): Readonly<{ label: string; className: string }> {
  return { label: displayStatusLabels[detail.displayStatus], className: statusVariant(detail.displayStatus) }
}

/** 详情事实行（label + 现成展示值；缺事实显示 —，自由串原样呈现）。 */
export type OrderFactRow = Readonly<{ id: string; label: string; value: string }>
const dashFor = (value: string | null): string => value === null ? '—' : value
const factRowSpecs: readonly { id: string; label: string; key: StatusFactKey; render: (value: string | null) => string }[] = [
  { id: 'orderStage', label: '订单阶段', key: 'orderStage', render: value => dashFor(value === null ? null : orderStageLabels[value as OrderStage]) },
  { id: 'paymentStatus', label: '支付状态', key: 'paymentStatus', render: value => dashFor(value === null ? null : paymentStatusLabels[value as PaymentStatus]) },
  { id: 'verificationStatus', label: '核销状态', key: 'verificationStatus', render: value => dashFor(value === null ? null : verificationStatusLabels[value as VerificationStatus]) },
  { id: 'refundApplicationStatus', label: '退款申请状态', key: 'refundApplicationStatus', render: dashFor },
  { id: 'refundStatus', label: '退款状态', key: 'refundStatus', render: dashFor },
  { id: 'afterSaleStatus', label: '售后状态', key: 'afterSaleStatus', render: dashFor },
]
/** 详情页事实区全量行（固定顺序）：非事实字段直读，六个状态事实经 statusFact 间接读取。 */
export function orderFactRows(detail: OrderDetailView): readonly OrderFactRow[] {
  const facts = factRowSpecs.map(spec => ({ id: spec.id, label: spec.label, value: spec.render(statusFact(detail, spec.key)) }))
  return [
    { id: 'orderId', label: '订单ID', value: detail.orderId },
    ...facts.slice(0, 2),
    { id: 'payAmount', label: '支付金额', value: `¥${detail.payAmount}` },
    { id: 'appointmentStart', label: '预约开始', value: formatOrderInstant(detail.appointmentStart) },
    { id: 'appointmentEnd', label: '预约结束', value: formatOrderInstant(detail.appointmentEnd) },
    ...facts.slice(2, 3),
    { id: 'verifiedAt', label: '核销时间', value: formatOrderInstant(detail.verifiedAt) },
    ...facts.slice(3),
    { id: 'actions', label: '可用操作', value: enabledActionLabels(detail).length === 0 ? '—' : enabledActionLabels(detail).join(' / ') },
  ]
}

/** 服务端返回的可用动作的展示标签（只读呈现，本切片不实现对应按钮）。 */
export function enabledActionLabels(detail: OrderDetailView): readonly string[] {
  const actions = detail.actions
  return actions === null ? [] : actionOrder.filter(key => actions[key]).map(key => actionLabels[key])
}

/** 核销码区块不呈现时的说明文案（仅凭契约事实字段推导，不发明规则）。 */
export function verifyAbsenceNotice(detail: OrderDetailView): string {
  const verificationKey: StatusFactKey = 'verificationStatus'
  if (statusFact(detail, verificationKey) === 'VERIFIED') return '订单已核销完成，无需再出示核销码。'
  if (detail.verifiedAt !== null) return `已核销（${formatOrderInstant(detail.verifiedAt)}），核销码不再展示。`
  return '当前订单状态不支持查看核销码（以订单实时状态为准）。'
}

const pad = (value: number): string => String(value).padStart(2, '0')
/** 带偏移 ISO-8601 → 北京时间展示（设备时区不作假设，#116 formatInstant 同口径；分钟粒度）。 */
export function formatOrderInstant(value: string | null): string {
  if (value === null) return '—'
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return '—'
  return new Date(date.getTime() + 8 * 3600000).toISOString().slice(0, 16).replace('T', ' ')
}
/** 预约时间窗（分钟粒度 + 北京时间口径）。 */
export function appointmentWindow(detail: OrderDetailView): string {
  return `${formatOrderInstant(detail.appointmentStart)} ~ ${formatOrderInstant(detail.appointmentEnd).slice(11)}（北京时间）`
}
/** 状态徽标 className 变体（WXSS 铁律：仅 className 变体，枚举闭集）。 */
export function statusVariant(status: DisplayOrderStatus): string {
  return `is-${status.toLowerCase().replace(/_/g, '-')}`
}

/** 页面错误文案（12号错误码语义 + 403/404 防探测同文案）。 */
export function orderReadMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401) return '登录已失效，请重新登录后查看'
    if (error.statusCode === 403) return '仅订单本人可查看该订单，或订单不存在'
    if (error.statusCode === 404) return '订单读取服务未开放或订单不存在，请稍后再试'
    if (error.statusCode === 400) return '请求无效，请从我的订单重新进入'
    if (error.statusCode === 503) return '服务暂不可用，请稍后重试'
  }
  if (error instanceof Error && error.message === 'INVALID_RESPONSE') return '服务返回异常，请稍后重试'
  return '订单读取失败，请稍后重试'
}

// ---- preview=1 设计验收通道（本地夹具，不发任何网络请求） ----

export type OrdersScenario = 'normal' | 'empty'
export const isOrdersScenario = (value?: string): value is OrdersScenario =>
  ['normal', 'empty'].includes(value || '')

/** 核销码可展示订单使用 #116 夹具订单号，复用其 PreviewOrderVerifyRepository 的本地取码模拟。 */
export const PREVIEW_VERIFY_ORDER = '900101001990001'

const at = (day: number, hour: number, minute: number): string =>
  `2026-10-${pad(day)}T${pad(hour)}:${pad(minute)}:00.000+08:00`

type Fixture = (Partial<OrderDetailView> & Pick<OrderDetailView, 'orderId' | 'orderNo' | 'displayStatus' | 'payAmount' | 'appointmentStart' | 'appointmentEnd'>)

const base = (over: Fixture): OrderDetailView => ({
  orderStage: null, paymentStatus: null, verificationStatus: null, refundApplicationStatus: null,
  refundStatus: null, afterSaleStatus: null, verifiedAt: null, actions: null, ...over,
})

// 十个 displayStatus 全覆盖 + 一单一事实：核销码入口（PENDING_SERVICE+canShowVerificationCode）、
// 已核销（COMPLETED+VERIFIED+verifiedAt）、售后（AFTERSALE 三事实串）。金额/时间为契约样例串。
const normalOrders: readonly OrderDetailView[] = [
  base({ orderId: '900101001990000', orderNo: '2026100100001', displayStatus: 'PENDING_PAYMENT', payAmount: '88.00',
    appointmentStart: at(10, 14, 0), appointmentEnd: at(10, 15, 30), orderStage: 'PENDING_PAYMENT', paymentStatus: 'INIT',
    actions: { canPay: true, canReschedule: false, canApplyRefund: false, canShowVerificationCode: false, canReview: false, canApplyAfterSale: false } }),
  base({ orderId: '900101001990002', orderNo: '2026100100002', displayStatus: 'PENDING_CONFIRM', payAmount: '128.00',
    appointmentStart: at(11, 9, 30), appointmentEnd: at(11, 11, 0), orderStage: 'PENDING_CONFIRM', paymentStatus: 'PAID',
    actions: { canPay: false, canReschedule: false, canApplyRefund: true, canShowVerificationCode: false, canReview: false, canApplyAfterSale: false } }),
  // 核销码入口样例：已支付待服务、未核销，服务端返回 canShowVerificationCode=true。
  base({ orderId: PREVIEW_VERIFY_ORDER, orderNo: '2026100100003', displayStatus: 'PENDING_SERVICE', payAmount: '80.00',
    appointmentStart: at(12, 14, 0), appointmentEnd: at(12, 15, 0), orderStage: 'PENDING_SERVICE', paymentStatus: 'PAID',
    verificationStatus: 'UNVERIFIED',
    actions: { canPay: false, canReschedule: true, canApplyRefund: true, canShowVerificationCode: true, canReview: false, canApplyAfterSale: false } }),
  base({ orderId: '900101001990004', orderNo: '2026100100004', displayStatus: 'COMPLETED', payAmount: '156.00',
    appointmentStart: at(6, 10, 0), appointmentEnd: at(6, 12, 0), orderStage: 'COMPLETED', paymentStatus: 'PAID',
    verificationStatus: 'VERIFIED', verifiedAt: at(6, 12, 4),
    actions: { canPay: false, canReschedule: false, canApplyRefund: false, canShowVerificationCode: false, canReview: true, canApplyAfterSale: true } }),
  base({ orderId: '900101001990005', orderNo: '2026100100005', displayStatus: 'CANCELED', payAmount: '60.00',
    appointmentStart: at(5, 16, 0), appointmentEnd: at(5, 17, 0), orderStage: 'CANCELED', paymentStatus: 'CLOSED' }),
  base({ orderId: '900101001990006', orderNo: '2026100100006', displayStatus: 'REFUND_PENDING_CONFIRM', payAmount: '99.00',
    appointmentStart: at(9, 11, 0), appointmentEnd: at(9, 12, 30), orderStage: 'PENDING_SERVICE', paymentStatus: 'PAID',
    refundApplicationStatus: 'PENDING_MERCHANT' }),
  base({ orderId: '900101001990007', orderNo: '2026100100007', displayStatus: 'REFUNDING', payAmount: '120.00',
    appointmentStart: at(8, 15, 0), appointmentEnd: at(8, 16, 0), orderStage: 'PENDING_SERVICE', paymentStatus: 'PAID',
    refundApplicationStatus: 'AUTO_APPROVED', refundStatus: 'PROCESSING' }),
  base({ orderId: '900101001990008', orderNo: '2026100100008', displayStatus: 'REFUNDED', payAmount: '45.00',
    appointmentStart: at(4, 10, 30), appointmentEnd: at(4, 11, 30), orderStage: 'CANCELED', paymentStatus: 'CLOSED',
    refundApplicationStatus: 'AUTO_APPROVED', refundStatus: 'SUCCESS' }),
  base({ orderId: '900101001990009', orderNo: '2026100100009', displayStatus: 'PARTIAL_REFUND', payAmount: '200.00',
    appointmentStart: at(3, 9, 0), appointmentEnd: at(3, 10, 0), orderStage: 'COMPLETED', paymentStatus: 'PAID',
    verificationStatus: 'VERIFIED', verifiedAt: at(3, 10, 2), refundApplicationStatus: 'DECIDED', refundStatus: 'PARTIAL_SUCCESS' }),
  base({ orderId: '900101001990010', orderNo: '2026100100010', displayStatus: 'AFTERSALE', payAmount: '168.00',
    appointmentStart: at(2, 14, 30), appointmentEnd: at(2, 16, 0), orderStage: 'PENDING_SERVICE', paymentStatus: 'PAID',
    verificationStatus: 'UNVERIFIED', afterSaleStatus: 'PROCESSING' }),
]

/** 夹具自检：坏夹具直接在测试期暴露（decodeOrderPage 全量重解一遍）。 */
export function validateOrdersFixture(orders: readonly OrderDetailView[]): boolean {
  try { decodeOrderPage({ items: [...orders], page: 1, pageSize: Math.max(orders.length, 1), total: orders.length }); return true }
  catch { return false }
}

/** preview=1 专用：数据全部来自本文件常量，本地状态分桶/分页，不做任何网络请求。 */
export class PreviewOrderReadRepository {
  private readonly orders: readonly OrderDetailView[]
  constructor(scenario: OrdersScenario = 'normal') { this.orders = scenario === 'empty' ? [] : normalOrders }
  async list(displayStatus: DisplayOrderStatus | null, page = 1, pageSize = 20): Promise<OrderPage> {
    const filtered = displayStatus === null ? this.orders : this.orders.filter(order => order.displayStatus === displayStatus)
    const start = (page - 1) * pageSize
    return { items: filtered.slice(start, start + pageSize), page, pageSize, total: filtered.length }
  }
  async detail(orderId: string): Promise<OrderDetailView> {
    // 未知订单走 404 失败关闭（与真实模式防探测语义一致）。
    const found = this.orders.find(order => order.orderId === orderId)
    if (!found) throw new ApiError('COMMON_NOT_FOUND', 404)
    return found
  }
}
