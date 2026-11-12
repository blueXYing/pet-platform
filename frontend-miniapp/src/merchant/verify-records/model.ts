import { ApiError } from '../../shared/request'

// CCR-M-VERIFY-RECORD-001 §3（2026-11-12 批准）：商家端核销记录读侧
// GET /api/v1/merchant/verifications（开关 pet.verification.merchant-read.http.enabled
// 默认 false）。item 字段严格为 verificationId/orderId/serviceName/payAmount/
// customerNickname/customerAvatarUrl/verifiedAt/operatorKind('OWNER'|'STAFF')/
// operatorName（§9.1 裁决：STAFF=成员真名、OWNER=「店长」固定文案）。时间为 UTC 毫秒
// instant；ID 为 Snowflake 字符串；金额两位小数字符串（63 号纪律）。契约红线：无手机号、
// 无凭证密文/lookup/code、无 operatorId 真实 userId（CCR §4）——解码器按 exact-key 拒绝
// 契约外形状，任何 phone/operatorId 键都进不了 UI。

export type VerificationOperatorKind = 'OWNER' | 'STAFF'
const OPERATOR_KINDS: readonly VerificationOperatorKind[] = ['OWNER', 'STAFF']

export type VerificationRecordItem = Readonly<{
  verificationId: string
  orderId: string
  serviceName: string | null
  payAmount: string | null
  customerNickname: string | null
  customerAvatarUrl: string | null
  verifiedAt: string
  operatorKind: VerificationOperatorKind
  operatorName: string
}>

export type VerificationRecordPage = Readonly<{
  items: readonly VerificationRecordItem[]
  page: number
  pageSize: number
  total: number
}>

function invalid(): never {
  throw new Error('INVALID_RESPONSE')
}
function exact(value: unknown, keys: readonly string[]): Record<string, unknown> {
  if (typeof value !== 'object' || value === null) invalid()
  const record = value as Record<string, unknown>
  if (Object.keys(record).length !== keys.length || keys.some(key => !(key in record))) invalid()
  return record
}
function isId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n
}
function isInstant(value: unknown): value is string {
  // UTC millisecond instants（MerchantHttpSupport.time，同 aftersale/schedule 家族形状）。
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)
}

/** 可空投影文本：null 或非空字符串（昵称/头像/服务名为订单读侧投影，空值降级不伪造）。 */
function nullableText(value: unknown): string | null {
  if (value === null) return null
  if (typeof value !== 'string' || value.length === 0) invalid()
  return value
}

/** 金额两位小数字符串（同 63 号）；payAmount 可空（无支付事实的订单不伪造金额）。 */
function nullableAmount(value: unknown): string | null {
  if (value === null) return null
  if (typeof value !== 'string' || !/^(?:0|[1-9][0-9]{0,15})\.[0-9]{2}$/.test(value)) invalid()
  return value
}

export function decodeVerificationRecordItem(value: unknown): VerificationRecordItem {
  const v = exact(value, [
    'verificationId', 'orderId', 'serviceName', 'payAmount', 'customerNickname',
    'customerAvatarUrl', 'verifiedAt', 'operatorKind', 'operatorName',
  ])
  if (!isId(v.verificationId) || !isId(v.orderId)) invalid()
  if (!isInstant(v.verifiedAt)) invalid()
  const operatorKind = OPERATOR_KINDS.includes(v.operatorKind as VerificationOperatorKind)
    ? v.operatorKind as VerificationOperatorKind : invalid()
  if (typeof v.operatorName !== 'string' || v.operatorName.length === 0) invalid()
  return {
    verificationId: v.verificationId,
    orderId: v.orderId,
    serviceName: nullableText(v.serviceName),
    payAmount: nullableAmount(v.payAmount),
    customerNickname: nullableText(v.customerNickname),
    customerAvatarUrl: nullableText(v.customerAvatarUrl),
    verifiedAt: v.verifiedAt,
    operatorKind,
    operatorName: v.operatorName,
  }
}

export function decodeVerificationRecordPage(value: unknown): VerificationRecordPage {
  if (typeof value !== 'object' || value === null) invalid()
  const record = value as Record<string, unknown>
  if (Object.keys(record).length !== 4 || !['items', 'page', 'pageSize', 'total'].every(key => key in record)) invalid()
  const page = Number(record.page), pageSize = Number(record.pageSize), total = Number(record.total)
  if (!Number.isInteger(page) || page < 1 || page > 10000) invalid()
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 100) invalid()
  if (!Number.isSafeInteger(total) || total < 0) invalid()
  if (!Array.isArray(record.items) || record.items.length > pageSize) invalid()
  return { items: record.items.map(decodeVerificationRecordItem), page, pageSize, total }
}

// ---------------------------------------------------------------------------
// 展示纯函数（页面只呈现事实，不推导）：视觉沿 Figma 12:7426 卡语言。
// ---------------------------------------------------------------------------

/** 卡头订单短号：orderId 尾 8 位（order-list 家族「订单 xxxxxxxx」惯例）。 */
export function verificationOrderNo(item: VerificationRecordItem): string {
  return `订单 ${item.orderId.slice(-8)}`
}

/** 核销人文案（CCR §9.1）：STAFF=operatorName 成员真名；OWNER 固定「店长」标签文案
 *  （服务端虽按契约回固定文案，客户端仍按裁决硬定，防后端偏离泄露店主真名）。 */
export function verificationOperatorText(item: VerificationRecordItem): string {
  return item.operatorKind === 'OWNER' ? '店长' : item.operatorName
}

/** 核销人胶囊样式（Figma 12:7426 胶囊配方）：STAFF=绿字浅蓝底、OWNER=琥珀字奶油底，
 *  两类身份视觉可区分（任务 §2）。状态样式一律 className 变体（WXSS 纪律）。 */
export function verificationOperatorClass(item: VerificationRecordItem): string {
  return item.operatorKind === 'OWNER' ? 'msvr-op msvr-op-owner' : 'msvr-op msvr-op-staff'
}

/** 客户显示名（CCR-M-ORDER-CARD-001 口径）：昵称可空 → 「顾客」占位，不伪造。 */
export function verificationCustomerName(item: VerificationRecordItem): string {
  return item.customerNickname ?? '顾客'
}

/** 头像圆的首字：昵称首码点；昵称为空用「客」占位（头像 URL 空/加载失败时用）。 */
export function verificationCustomerInitial(item: VerificationRecordItem): string {
  const nickname = item.customerNickname
  if (!nickname) return '客'
  return Array.from(nickname)[0] ?? '客'
}

/** 服务名：可空投影，空值降级为 stats 页同款「已下架服务」占位（家族既有文案）。 */
export function verificationServiceName(item: VerificationRecordItem): string {
  return item.serviceName ?? '已下架服务'
}

/** 金额文案：两位小数事实字符串；null（无支付事实）降级「--」（工作台评分占位先例）。 */
export function verificationAmountText(item: VerificationRecordItem): string {
  return item.payAmount === null ? '--' : item.payAmount
}

/** 卡面核销时间（紧凑家族惯例，refund dateLabel 同款）：今天/明天 HH:mm；其他日期
 *  11-05 14:20。产品时钟 UTC+8，设备设置不得移动核销时间（verifyTimeText 同口径）。 */
export function verifiedAtLabel(value: string): string {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return ''
  const local = new Date(date.getTime() + 8 * 60 * 60 * 1000)
  const now = new Date(Date.now() + 8 * 60 * 60 * 1000)
  const day = local.toISOString().slice(0, 10)
  const today = now.toISOString().slice(0, 10)
  const tomorrow = new Date(now.getTime() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10)
  const prefix = day === today ? '今天' : day === tomorrow ? '明天' : day.slice(5)
  return `${prefix} ${local.toISOString().slice(11, 16)}`
}

// ---------------------------------------------------------------------------
// 错误面（商家读侧家族）：403 不区分「非 OWNER/开关未开/跨店」——产品口径按
// 「仅商家店长可查看核销记录」引导（任务 §2 entry 态）；503 依赖故障整页失败关闭。
// ---------------------------------------------------------------------------

/** 商家核销记录列表错误面中文映射（12 号 COMMON_*）。 */
export function verificationRecordListMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 400) return '查询参数不合法，请刷新后重试。'
    if (error.statusCode === 401) return '登录已失效，请重新登录后再查看核销记录。'
    if (error.statusCode === 403) return '仅商家店长可查看核销记录；员工请在员工工作台完成核销。'
    if (error.statusCode === 503) return '核销记录读取暂时不可用，请稍后重试。'
    return '核销记录读取失败，请稍后重试。'
  }
  if (error instanceof Error && error.message === 'WORKSPACE_PATH_MISMATCH') {
    return '工作区已切换，请重新进入商家工作台。'
  }
  if (error instanceof Error && error.message === 'MERCHANT_ENTRY_REQUIRED') {
    return '请从商家工作台进入核销记录。'
  }
  return '核销记录读取失败，请稍后重试。'
}

/** 依赖故障（503）整页失败关闭分类（entry 的 403 由控制器单独映射，见 controller 注释）。 */
export function verificationRecordListAvailability(error: unknown): 'ok' | 'closed' {
  if (error instanceof ApiError && error.statusCode === 503) return 'closed'
  return 'ok'
}
