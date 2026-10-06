// C-006 slice（我的优惠券 / 我的积分，只读）。本文件仍是券/积分 C 端投影契约口径的唯一替换点：
// 契约已裁决并已由后端 PR#112 交付实现（docs/04-api/10-HTTP-API-Contract-v0.4.md §3.15.1/§3.15.2，
// 11 号 OpenAPI-Core cListMyCoupons/cGetMyCoupon/cGetPointsBalance/cListPointsLedger，
// IMPLEMENTED_DEFAULT_OFF，默认随 pet.auth.c.enabled 装配）。字段事实：
// - 券投影 CouponInstanceItem：couponId(PublicId)/name(1..128) 为必填；amountOff/thresholdAmount/
//   scopeSummary(≤64)/typeLabel(≤16)/usedAt 为 D1 服务端 rule_json 投影，结构未冻结（待 CPN-001），
//   字段缺失返回 null，前端不得解析 rule_json；validTo 为 ISO 日期；status 仅 AVAILABLE/USED/EXPIRED
//   三桶（D2：FROZEN/RISK_FROZEN 在任何桶、任何路由永不返回）。
// - 积分：balance 为非负整数 String（BIGINT 传输安全，无账户行读作 "0"）；流水 ledgerId/bizType
//   （SIGN_IN/INVITE/TASK/ORDER_REWARD/REFUND_CLAWBACK 五枚举）/delta（带符号非零整数 String，
//   扣回为负）/balanceAfter（非负整数 String）/createdAt（ISO 时间戳，毫秒精度）；
//   固定排序 created_at DESC, id DESC；券列表固定排序 expire_at ASC, id ASC。
// - 金额串按 11 号 DecimalAmount 模式解码（0-16 位整数 + 可选 1-2 位小数）；#112 实际按 D4
//   两位小数原样输出，页面按契约串渲染，不改写。
// 产品硬规则不变：积分只赚取/扣回，不消费；V1 无积分商城、无积分兑换、无券发放/冻结/核销写路径。
// 设计中出现的"5折"折扣券与"兑换 -500"流水均为范围外设计样例，不实现（见 wave-2/C-006-coupon-points/INVENTORY.md）。

export type CouponStatus = 'AVAILABLE' | 'USED' | 'EXPIRED'
// FROZEN/RISK_FROZEN 的 C 端呈现未裁决（提案 D2），且契约规定服务端在任何桶都不返回，解码直接拒绝。
export const couponStatuses: readonly CouponStatus[] = ['AVAILABLE', 'USED', 'EXPIRED']
export const isCouponStatus = (value?: string): value is CouponStatus =>
  (couponStatuses as readonly string[]).includes(value || '')

export type CouponView = Readonly<{
  couponId: string
  name: string
  // 面额展示串（D1 投影，可为 null；非空时为金额串，如 "20.00"）。
  amountOff: string | null
  // 使用门槛金额，null 表示无门槛（契约 §3.15.1：null=无门槛）。
  thresholdAmount: string | null
  // 适用范围摘要（D1 投影，来源 rule_json，结构未冻结，可为 null）。
  scopeSummary: string | null
  // 券类型标签（D1 投影，如 通用券/新人券；可为 null）。
  typeLabel: string | null
  // 有效期截止日，ISO 日期 "YYYY-MM-DD"，页面按设计渲染为 "YYYY.MM.DD"。
  validTo: string
  status: CouponStatus
  // USED 时的核销时间（ISO 毫秒时间戳）；其余状态为 null。
  usedAt: string | null
}>

export const statusTabLabels: Record<CouponStatus, string> = { AVAILABLE: '可用', USED: '已使用', EXPIRED: '已过期' }

// 设计样例仅给出整数简写（"¥20"、"满100元可用"）；金额基线要求两位小数原样输出，
// 页面按契约串渲染（"¥20.00"、"满100.00元可用"），差异登记在 INVENTORY D4。
export function thresholdLabel(coupon: CouponView): string {
  return coupon.thresholdAmount === null ? '无门槛' : `满${coupon.thresholdAmount}元可用`
}

export type PointsBalanceView = Readonly<{
  // BIGINT 走字符串传输（技术基线），非负整数串。
  balance: string
}>

export type PointsBizType = 'SIGN_IN' | 'INVITE' | 'TASK' | 'ORDER_REWARD' | 'REFUND_CLAWBACK'
export const pointsBizTypeLabels: Record<PointsBizType, string> = {
  SIGN_IN: '每日签到',
  INVITE: '邀请好友',
  TASK: '任务奖励',
  ORDER_REWARD: '下单奖励',
  REFUND_CLAWBACK: '退款扣回',
}

export type PointsLedgerView = Readonly<{
  ledgerId: string
  bizType: PointsBizType
  // 增加为正、扣回为负（schema 事实），带符号非零整数串。
  delta: string
  balanceAfter: string
  // ISO 毫秒时间戳；页面按"今天/昨天/M月D日 HH:mm"呈现（UTC 口径，D6 未裁决不擅改）。
  createdAt: string
}>

// 分页信封（11 号 CouponInstancePageData / PointsLedgerPageData：items/page/pageSize/total）。
export type CouponPage = Readonly<{ items: readonly CouponView[]; page: number; pageSize: number; total: number }>
export type PointsLedgerPage = Readonly<{ items: readonly PointsLedgerView[]; page: number; pageSize: number; total: number }>

const signedNonZero = (value: string): boolean => /^-?[1-9][0-9]{0,17}(?![\s\S])/.test(value)
const nonNegativeInteger = (value: string): boolean => /^(0|[1-9][0-9]{0,17})(?![\s\S])/.test(value)
const isoDate = (value: string): boolean => /^\d{4}-\d{2}-\d{2}(?![\s\S])/.test(value)
const isoTimestamp = (value: string): boolean => /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z(?![\s\S])/.test(value)
// 11 号 DecimalAmount：非负、最多 16 位整数、可选 1-2 位小数（#112 按 D4 恒输出两位）。
const amount = (value: string): boolean => /^(?:0|[1-9][0-9]{0,15})(?:\.[0-9]{1,2})?(?![\s\S])/.test(value)

const invalid = (): never => { throw new Error('INVALID_RESPONSE') }
/** 严格 exact-key：只允许契约键集合；未知键直接失败关闭（沿 decodeStore 惯例）。 */
function exactObject(value: unknown, keys: readonly string[]): Record<string, any> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid()
  const record = value as Record<string, unknown>
  for (const key of Object.keys(record)) if (!keys.includes(key)) invalid()
  return value as Record<string, any>
}
/** OpenAPI required 键：缺失即失败关闭；D1 可选键缺失按契约读作 null。 */
function requiredField(record: Record<string, any>, key: string): any {
  if (!(key in record)) invalid()
  return record[key]
}
function textRange(value: any, min: number, max: number): string {
  if (typeof value !== 'string' || [...value].length < min || [...value].length > max) invalid()
  return value
}
function nullableText(value: any, min: number, max: number): string | null {
  return value === null || value === undefined ? null : textRange(value, min, max)
}
function nullablePattern(value: any, pattern: (value: string) => boolean): string | null {
  if (value === null || value === undefined) return null
  if (typeof value !== 'string' || !pattern(value)) invalid()
  return value
}
const isIdText = (value: any): string => {
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,18}(?![\s\S])/.test(value) || BigInt(value) > 9223372036854775807n) invalid()
  return value
}
function decodePageNumbers(record: Record<string, any>): { page: number; pageSize: number; total: number } {
  const page = Number(record.page), pageSize = Number(record.pageSize), total = Number(record.total)
  if (!Number.isInteger(page) || page < 1 || page > 10000) invalid()
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 50) invalid()
  if (!Number.isInteger(total) || total < 0) invalid()
  return { page, pageSize, total }
}

const couponItemKeys = ['amountOff', 'couponId', 'name', 'scopeSummary', 'status', 'thresholdAmount', 'typeLabel', 'usedAt', 'validTo']

/** 单张券投影解码（列表元素与单券详情同构，§3.15.1）。 */
export function decodeCoupon(value: unknown): CouponView {
  const v = exactObject(value, couponItemKeys)
  const status = requiredField(v, 'status')
  if (!isCouponStatus(typeof status === 'string' ? status : undefined)) invalid()
  const usedAt = nullablePattern(v.usedAt, isoTimestamp)
  if ((status === 'USED') !== (usedAt !== null)) invalid()
  return {
    couponId: isIdText(requiredField(v, 'couponId')),
    name: textRange(requiredField(v, 'name'), 1, 128),
    amountOff: nullablePattern(v.amountOff, amount),
    thresholdAmount: nullablePattern(v.thresholdAmount, amount),
    scopeSummary: nullableText(v.scopeSummary, 1, 64),
    typeLabel: nullableText(v.typeLabel, 1, 16),
    validTo: (() => { const raw = requiredField(v, 'validTo'); if (typeof raw !== 'string' || !isoDate(raw)) invalid(); return raw })(),
    status, usedAt,
  }
}

export function decodeCouponPage(value: unknown): CouponPage {
  const v = exactObject(value, ['items', 'page', 'pageSize', 'total'])
  if (!Array.isArray(v.items) || v.items.length > 50) invalid()
  const numbers = decodePageNumbers(v)
  // 排序 expire_at ASC, id ASC 无法在客户端验证：投影只含 validTo（日期粒度），同日不同
  // 时刻的 id 顺序不可判定，不强行校验（区别于积分流水的毫秒时间戳）。
  return { items: v.items.map(decodeCoupon), ...numbers }
}

export function decodePointsBalance(value: unknown): PointsBalanceView {
  const v = exactObject(value, ['balance'])
  const balance = requiredField(v, 'balance')
  if (typeof balance !== 'string' || !nonNegativeInteger(balance)) invalid()
  return { balance }
}

const ledgerItemKeys = ['balanceAfter', 'bizType', 'createdAt', 'delta', 'ledgerId']

export function decodeLedgerItem(value: unknown): PointsLedgerView {
  const v = exactObject(value, ledgerItemKeys)
  const bizType = requiredField(v, 'bizType')
  if (typeof bizType !== 'string' || !(bizType in pointsBizTypeLabels)) invalid()
  const delta = requiredField(v, 'delta')
  if (typeof delta !== 'string' || !signedNonZero(delta)) invalid()
  const balanceAfter = requiredField(v, 'balanceAfter')
  if (typeof balanceAfter !== 'string' || !nonNegativeInteger(balanceAfter)) invalid()
  const createdAt = requiredField(v, 'createdAt')
  if (typeof createdAt !== 'string' || !isoTimestamp(createdAt)) invalid()
  return { ledgerId: isIdText(requiredField(v, 'ledgerId')), bizType, delta, balanceAfter, createdAt }
}

export function decodeLedgerPage(value: unknown): PointsLedgerPage {
  const v = exactObject(value, ['items', 'page', 'pageSize', 'total'])
  if (!Array.isArray(v.items) || v.items.length > 50) invalid()
  const numbers = decodePageNumbers(v)
  const items = v.items.map(decodeLedgerItem)
  // 固定排序 created_at DESC, id DESC（§3.15.2）：毫秒时间戳同长可字典序比较；同毫秒按 id 数值降序。
  for (let index = 1; index < items.length; index++) {
    const newer = items[index - 1]!, older = items[index]!
    if (newer.createdAt < older.createdAt) invalid()
    if (newer.createdAt === older.createdAt && !(BigInt(newer.ledgerId) > BigInt(older.ledgerId))) invalid()
  }
  return { items, ...numbers }
}

/** 夹具自检：坏夹具直接在测试期暴露，而不是渲染成乱数据。 */
export function validateCouponFixture(coupon: CouponView): boolean {
  if (!/^[1-9][0-9]{0,18}(?![\s\S])/.test(coupon.couponId)) return false
  if (coupon.name.length < 1 || coupon.name.length > 128) return false
  if (coupon.amountOff === null || !/^\d+\.\d{2}(?![\s\S])/.test(coupon.amountOff)) return false
  if (coupon.thresholdAmount !== null && !/^\d+\.\d{2}(?![\s\S])/.test(coupon.thresholdAmount)) return false
  if (!isoDate(coupon.validTo)) return false
  if (!couponStatuses.includes(coupon.status)) return false
  if ((coupon.status === 'USED') !== (coupon.usedAt !== null)) return false
  return true
}

export function validateLedgerFixture(entry: PointsLedgerView): boolean {
  if (!/^[1-9][0-9]{0,18}(?![\s\S])/.test(entry.ledgerId)) return false
  if (!(entry.bizType in pointsBizTypeLabels)) return false
  if (!signedNonZero(entry.delta)) return false
  if (!nonNegativeInteger(entry.balanceAfter)) return false
  if (!isoTimestamp(entry.createdAt)) return false
  return true
}

// 流水 delta 展示：正数带加号、负数自带减号；0 不会出现（schema：增加为正、扣回为负）。
export function deltaLabel(entry: PointsLedgerView): string {
  return entry.delta.startsWith('-') ? entry.delta : `+${entry.delta}`
}

const pad = (value: number): string => String(value).padStart(2, '0')

/** 流水时间呈现：今天/昨天显示"今天/昨天 HH:mm"，更早显示"M月D日 HH:mm"（跨年含年份）。 */
export function formatLedgerTime(now: Date, createdAt: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):\d{2}\.\d{3}Z$/.exec(createdAt)
  if (!match) return ''
  const created = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]), Number(match[4]), Number(match[5]), 0))
  if (Number.isNaN(created.getTime())) return ''
  const utc = (date: Date): number => Math.floor(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()) / 86400000)
  const diffDays = utc(now) - utc(created)
  const clock = `${pad(created.getUTCHours())}:${pad(created.getUTCMinutes())}`
  if (diffDays === 0) return `今天 ${clock}`
  if (diffDays === 1) return `昨天 ${clock}`
  const monthDay = `${created.getUTCMonth() + 1}月${created.getUTCDate()}日`
  return created.getUTCFullYear() === now.getUTCFullYear() ? `${monthDay} ${clock}` : `${created.getUTCFullYear()}年${monthDay} ${clock}`
}

export type CouponPointsScenario = 'normal' | 'empty'
export const isCouponPointsScenario = (value?: string): value is CouponPointsScenario =>
  ['normal', 'empty'].includes(value || '')

export type CouponPointsData = Readonly<{
  coupons: readonly CouponView[]
  balance: PointsBalanceView
  ledger: readonly PointsLedgerView[]
}>

export interface CouponPointsDeps {
  load(): Promise<CouponPointsData>
}

const coupon = (over: Partial<CouponView> & Pick<CouponView, 'couponId' | 'name' | 'status'>): CouponView => ({
  amountOff: '20.00', thresholdAmount: '100.00', scopeSummary: '全部服务通用', typeLabel: '通用券',
  validTo: '2026-09-30', usedAt: null, ...over,
})
const ledger = (over: Partial<PointsLedgerView> & Pick<PointsLedgerView, 'ledgerId' | 'bizType' | 'delta'>): PointsLedgerView => ({
  balanceAfter: '1280', createdAt: '2026-09-30T08:00:00.000Z', ...over,
})

// 夹具是设计稿样例与 schema 事实的组合：券卡片对齐 128:2078 的"满100减20/新人券"文本，
// 积分对齐 129:8174 的余额 1280 与 +5/+100/+10/+50 样例；范围外的"5折"券与"兑换 -500"
// 流水不进入夹具（INVENTORY §3 D3/D5）。
const normalData: CouponPointsData = {
  coupons: [
    coupon({ couponId: '730101', name: '满100减20', status: 'AVAILABLE' }),
    coupon({ couponId: '730102', name: '首单立减', amountOff: '30.00', thresholdAmount: null, scopeSummary: '宠物美容 / 寄养', typeLabel: '新人券', validTo: '2026-08-31', status: 'AVAILABLE' }),
    coupon({ couponId: '730103', name: '开业赠券', amountOff: '15.00', thresholdAmount: '50.00', typeLabel: '新人券', validTo: '2026-06-30', status: 'USED', usedAt: '2026-06-01T10:20:00.000Z' }),
    coupon({ couponId: '730104', name: '服务满减券', amountOff: '10.00', thresholdAmount: '30.00', typeLabel: '通用券', validTo: '2026-09-15', status: 'EXPIRED' }),
  ],
  balance: { balance: '1280' },
  ledger: [
    ledger({ ledgerId: '740901', bizType: 'SIGN_IN', delta: '5', balanceAfter: '1280', createdAt: '2026-09-30T01:05:00.000Z' }),
    ledger({ ledgerId: '740902', bizType: 'ORDER_REWARD', delta: '100', balanceAfter: '1275', createdAt: '2026-09-29T06:20:00.000Z' }),
    ledger({ ledgerId: '740903', bizType: 'TASK', delta: '10', balanceAfter: '1175', createdAt: '2026-09-28T01:15:00.000Z' }),
    ledger({ ledgerId: '740904', bizType: 'INVITE', delta: '50', balanceAfter: '1165', createdAt: '2026-09-25T08:40:00.000Z' }),
    ledger({ ledgerId: '740905', bizType: 'SIGN_IN', delta: '5', balanceAfter: '1115', createdAt: '2026-09-24T01:02:00.000Z' }),
    ledger({ ledgerId: '740906', bizType: 'REFUND_CLAWBACK', delta: '-51', balanceAfter: '1110', createdAt: '2026-09-20T09:30:00.000Z' }),
  ],
}

const emptyData: CouponPointsData = { coupons: [], balance: { balance: '0' }, ledger: [] }

/** preview=1 专用：数据全部来自本文件常量，不做任何网络请求（设计验收通道）。 */
export class PreviewCouponPointsRepository implements CouponPointsDeps {
  constructor(scenario: CouponPointsScenario = 'normal') { this.data = scenario === 'empty' ? emptyData : normalData }
  private readonly data: CouponPointsData
  async load(): Promise<CouponPointsData> { return this.data }
}

/** 只读分桶（preview 模式本地分桶）：按 schema 状态过滤；真实模式由服务端 status 参数过滤。 */
export function couponsByStatus(coupons: readonly CouponView[], status: CouponStatus): CouponView[] {
  return coupons.filter(coupon => coupon.status === status)
}
