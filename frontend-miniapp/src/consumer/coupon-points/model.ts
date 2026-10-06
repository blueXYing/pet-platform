// C-006 slice（我的优惠券 / 我的积分，只读）。develop 上不存在可用的查询契约：pet-points-api
// 只有包骨架（内部契约文档 07 号 §13.1 PointsQueryApi 尚无实现），pet-coupon-api 仅有预订
// 无券证明 BookingCouponExposureApi（§12.1 CouponQueryApi 同样无实现），pet-boot adapter/web/c
// 无 coupon/points 控制器；后端 CPN-001/002、PTS-001/002 在 ISSUE_CATALOG 中全部 BLOCKED。
// 因此本切片仅交付 preview=1 本地夹具只读页，字段按下述事实与提案对齐：
// - 事实（docs/03-database/06 §8/§9 DDL 注释）：coupon_instance.status 枚举
//   AVAILABLE/FROZEN/USED/EXPIRED/RISK_FROZEN；points_ledger.biz_type 枚举
//   SIGN_IN/INVITE/TASK/ORDER_REWARD/REFUND_CLAWBACK；delta 增加为正、扣回为负；
//   balance/balance_after 为 BIGINT。
// - 提案（planning/ccr/CCR-C006-COUPON-POINTS-READ-001.md，待裁）：C 端只读展示 DTO。
//   面额/门槛/适用范围/券类型来自 coupon_template.rule_json，其结构未冻结，页面一律按提案
//   展示字段渲染且在 PR 中声明为待裁，不发明任何发放、兑换、消费、抵扣规则。
// 产品硬规则：积分只赚取/扣回，不消费；V1 无积分商城、无积分兑换。设计中出现的
// "5折"折扣券与"兑换 -500"流水均为范围外设计样例，不实现（见 wave-2/C-006-coupon-points/INVENTORY.md）。

export type CouponStatus = 'AVAILABLE' | 'USED' | 'EXPIRED'
// FROZEN/RISK_FROZEN 的 C 端呈现未裁决（提案 D2），本切片不展示、夹具不构造。
export const couponStatuses: readonly CouponStatus[] = ['AVAILABLE', 'USED', 'EXPIRED']
export const isCouponStatus = (value?: string): value is CouponStatus =>
  (couponStatuses as readonly string[]).includes(value || '')

export type CouponView = Readonly<{
  couponId: string
  name: string
  // 面额展示串，两位小数（金额基线 DECIMAL(18,2) 两位输出），如 "20.00"。
  amountOff: string
  // 使用门槛金额，null 表示无门槛（设计样例"无门槛"文案）。
  thresholdAmount: string | null
  // 适用范围摘要（提案展示字段，来源 rule_json，结构未冻结）。
  scopeSummary: string
  // 券类型标签（提案展示字段，如 通用券/新人券；来源模板属性，未冻结）。
  typeLabel: string
  // 有效期截止日，ISO 日期 "YYYY-MM-DD"，页面按设计渲染为 "YYYY.MM.DD"。
  validTo: string
  status: CouponStatus
  // USED 时的核销时间（ISO）；其余状态为 null。
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
  // 增加为正、扣回为负（schema 事实），整数串。
  delta: string
  balanceAfter: string
  // ISO 时间戳；页面按"今天/昨天/M月D日 HH:mm"呈现。
  createdAt: string
}>

const signedInteger = (value: string): boolean => /^-?(0|[1-9][0-9]{0,17})(?![\s\S])/.test(value)
const isoDate = (value: string): boolean => /^\d{4}-\d{2}-\d{2}(?![\s\S])/.test(value)
const isoTimestamp = (value: string): boolean => /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z(?![\s\S])/.test(value)

/** 夹具自检：坏夹具直接在测试期暴露，而不是渲染成乱数据。 */
export function validateCouponFixture(coupon: CouponView): boolean {
  if (!/^[1-9][0-9]{0,18}(?![\s\S])/.test(coupon.couponId)) return false
  if (coupon.name.length < 1 || coupon.name.length > 128) return false
  if (!/^\d+\.\d{2}(?![\s\S])/.test(coupon.amountOff)) return false
  if (coupon.thresholdAmount !== null && !/^\d+\.\d{2}(?![\s\S])/.test(coupon.thresholdAmount)) return false
  if (!isoDate(coupon.validTo)) return false
  if (!couponStatuses.includes(coupon.status)) return false
  if ((coupon.status === 'USED') !== (coupon.usedAt !== null)) return false
  return true
}

export function validateLedgerFixture(entry: PointsLedgerView): boolean {
  if (!/^[1-9][0-9]{0,18}(?![\s\S])/.test(entry.ledgerId)) return false
  if (!(entry.bizType in pointsBizTypeLabels)) return false
  if (!signedInteger(entry.delta) || entry.delta === '0') return false
  if (!signedInteger(entry.balanceAfter)) return false
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

/** preview=1 专用：数据全部来自本文件常量，不做任何网络请求。 */
export class PreviewCouponPointsRepository implements CouponPointsDeps {
  constructor(scenario: CouponPointsScenario = 'normal') { this.data = scenario === 'empty' ? emptyData : normalData }
  private readonly data: CouponPointsData
  async load(): Promise<CouponPointsData> { return this.data }
}

/** 只读分桶：按 schema 状态过滤；顺序保持夹具/未来契约的稳定顺序。 */
export function couponsByStatus(coupons: readonly CouponView[], status: CouponStatus): CouponView[] {
  return coupons.filter(coupon => coupon.status === status)
}
