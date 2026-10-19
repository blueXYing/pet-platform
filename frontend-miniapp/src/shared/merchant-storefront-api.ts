// M 端收尾切片三个读/写投影（后端同批 PR 交付，IMPLEMENTED_DEFAULT_OFF：
// pet.merchant.store-profile.http.enabled / pet.order.customer.http.enabled /
// pet.order.stats.http.enabled 默认关闭）。字段事实全部来自契约与本批实现：
// - 27号 §6.4 店铺资料：GET/PUT /api/v1/merchant/store-profile（全字段替换：JSON 显式 null
//   非法、文本空串清空、photoAssetIds/businessHours 空数组清空、promoLevel 0=未设置；
//   2026-10-19 扩展：storeName/address/phone 店铺事实、avatarAssetId 头像、businessStatus
//   营业状态开关 OPEN/RESTING，RESTING 投影进 readonly.operatingStatus）。
// - 69号 客户档案（只读派生 + 2026-10-19 扩展）：GET /api/v1/merchant/customers（nickname
//   包含搜索有界扫描；orderNoSuffix 订单号尾号搜索，仅命中本店订单尾号）、
//   GET /api/v1/merchant/customers/{userId}（详情带内部备注 note）、
//   PUT /api/v1/merchant/customers/{userId}/note（OWNER 写、幂等、乐观版本）；
//   手机号仅脱敏（1xx****xxxx），宠物仅名字/品种级。
// - 70号 经营统计（只读聚合 + 2026-10-19 扩展）：GET /api/v1/merchant/stats/overview|
//   services/top|revenue-trend|services/{id}/orders|schedule-utilization；range 词汇
//   TODAY/LAST_7D/LAST_30D/CUSTOM（CUSTOM 携 from/to，闭区间 ≤92 个 UTC 日），比率一位
//   小数无 % 号、null 显示 --；overview 含 rejectRate 拒单率。
// 所有列表/详情/写入必须携带等于当前工作区坐标的 merchantId+storeId（服务端 guard 内复验）。

import { ApiError, type RequestSpec } from './request'
import { id, object, type Command, type ConsumerApi } from './consumer-api'

// ---------------------------------------------------------------- store profile (27号 §6.4)

export type StoreProfileReadonly = Readonly<{
  storeName: string
  address: string
  cityCode: string | null
  phoneMasked: string | null
  longitude: string | null
  latitude: string | null
  merchantStatus: string
  storeStatus: string
  operatingStatus: 'OPERATING' | 'OFFLINE' | 'FROZEN' | 'RESTING'
  applicationStatus: string | null
}>

export type BusinessHour = Readonly<{ days: readonly number[]; start: string; end: string }>

export type StoreProfile = Readonly<{
  intro: string | null
  announcement: string | null
  photoAssetIds: readonly string[]
  businessHours: readonly BusinessHour[]
  promoLevel: number | null
  promoTitle: string | null
  promoText: string | null
  avatarAssetId: string | null
  businessStatus: 'OPEN' | 'RESTING'
  version: string
}>

export type StoreProfileView = Readonly<{
  merchantId: string
  storeId: string
  readonlyFacts: StoreProfileReadonly
  profile: StoreProfile
}>

/** 编辑载荷：全字段替换语义（与后端严格 JSON 一一对应；空串/空数组/0 表示清空）。
 *  2026-10-19 扩展：storeName/address 必填、phone 空串清空、avatarAssetId 空串清空、
 *  businessStatus 空串=回到 OPEN 投影。 */
export type StoreProfileInput = Readonly<{
  storeName: string
  address: string
  phone: string
  intro: string
  announcement: string
  photoAssetIds: readonly string[]
  businessHours: readonly BusinessHour[]
  promoLevel: number
  promoTitle: string
  promoText: string
  avatarAssetId: string
  businessStatus: string
  expectedVersion: string
}>

function fail(): never { throw new Error('INVALID_RESPONSE') }
const nullableText = (v: unknown, max: number): string | null =>
  typeof v === 'string' && Array.from(v).length <= max ? v : null
const exact = (value: unknown, fields: string) => {
  const v = object(value)
  if (Object.keys(v).sort().join(',') !== fields.split(',').sort().join(',')) fail()
  return v
}

function businessHours(value: unknown): readonly BusinessHour[] {
  if (!Array.isArray(value)) fail()
  return value.map(item => {
    const v = exact(item, 'days,start,end')
    if (!Array.isArray(v.days) || v.days.length < 1 || v.days.length > 7) fail()
    const days = v.days.map(day => (Number.isInteger(day) && day >= 1 && day <= 7 ? day : fail()))
    if (new Set(days).size !== days.length || [...days].sort((a, b) => a - b).join() !== days.join()) fail()
    if (typeof v.start !== 'string' || typeof v.end !== 'string'
      || !/^([01][0-9]|2[0-3]):[0-5][0-9]$/.test(v.start)
      || !/^([01][0-9]|2[0-3]):[0-5][0-9]$/.test(v.end) || v.end <= v.start) fail()
    return { days: Object.freeze(days), start: v.start, end: v.end }
  })
}

export function decodeStoreProfile(value: unknown): StoreProfileView {
  const v = exact(value, 'merchantId,storeId,readonly,profile')
  const facts = exact(v.readonly, 'storeName,address,cityCode,phoneMasked,longitude,latitude,merchantStatus,storeStatus,operatingStatus,applicationStatus')
  const profile = exact(v.profile, 'intro,announcement,photoAssetIds,businessHours,promoLevel,promoTitle,promoText,avatarAssetId,businessStatus,version')
  if (typeof facts.storeName !== 'string' || !facts.storeName
    || typeof facts.address !== 'string' || !facts.address
    || !['ACTIVE', 'OFFLINE', 'FROZEN', 'APPLYING', 'CANCELED'].includes(facts.merchantStatus)
    || !['ACTIVE', 'OFFLINE', 'FROZEN'].includes(facts.storeStatus)
    || !['OPERATING', 'OFFLINE', 'FROZEN', 'RESTING'].includes(facts.operatingStatus)) fail()
  if (facts.phoneMasked !== null && (typeof facts.phoneMasked !== 'string' || !/^1[0-9]{2}\*{4}[0-9]{4}$/.test(facts.phoneMasked))) fail()
  if (!Array.isArray(profile.photoAssetIds)
    || profile.photoAssetIds.some(item => typeof item !== 'string' || !/^[1-9][0-9]{0,18}$/.test(item))
    || profile.photoAssetIds.length > 6) fail()
  if (profile.promoLevel !== null && (!Number.isInteger(profile.promoLevel) || profile.promoLevel < 1 || profile.promoLevel > 9)) fail()
  if (profile.avatarAssetId !== null && (typeof profile.avatarAssetId !== 'string' || !/^[1-9][0-9]{0,18}$/.test(profile.avatarAssetId))) fail()
  if (!['OPEN', 'RESTING'].includes(profile.businessStatus)) fail()
  if (typeof profile.version !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(profile.version)) fail()
  return {
    merchantId: id(v.merchantId), storeId: id(v.storeId),
    readonlyFacts: {
      storeName: facts.storeName, address: facts.address,
      cityCode: typeof facts.cityCode === 'string' ? facts.cityCode : null,
      phoneMasked: facts.phoneMasked,
      longitude: typeof facts.longitude === 'string' ? facts.longitude : null,
      latitude: typeof facts.latitude === 'string' ? facts.latitude : null,
      merchantStatus: facts.merchantStatus, storeStatus: facts.storeStatus,
      operatingStatus: facts.operatingStatus,
      applicationStatus: typeof facts.applicationStatus === 'string' ? facts.applicationStatus : null,
    },
    profile: {
      intro: nullableText(profile.intro, 500),
      announcement: nullableText(profile.announcement, 200),
      photoAssetIds: Object.freeze([...profile.photoAssetIds]),
      businessHours: businessHours(profile.businessHours),
      promoLevel: profile.promoLevel,
      promoTitle: nullableText(profile.promoTitle, 100),
      promoText: nullableText(profile.promoText, 100),
      avatarAssetId: profile.avatarAssetId,
      businessStatus: profile.businessStatus,
      version: profile.version,
    },
  }
}

export function isDefiniteProfileConflict(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 409
    && ['COMMON_CONFLICT', 'IDEMPOTENCY_KEY_CONFLICT'].includes(error.code)
}

export class StoreProfileClient {
  private rejectionProofs = new WeakMap<object, { slot: string; command: Command }>()
  constructor(private api: ConsumerApi) {}

  private coordinates(): { merchantId: string; storeId: string } {
    const c = this.api.scope.capture().context
    if (c.workspace !== 'merchant' || !c.merchantId || !c.storeId) throw new Error('MERCHANT_ENTRY_REQUIRED')
    return { merchantId: c.merchantId, storeId: c.storeId }
  }

  get(): Promise<StoreProfileView> {
    const { merchantId, storeId } = this.coordinates()
    return this.api.request(
      { path: '/api/v1/merchant/store-profile', method: 'GET', data: { merchantId, storeId } },
      value => {
        const view = decodeStoreProfile(value)
        if (view.merchantId !== merchantId || view.storeId !== storeId) fail()
        return view
      })
  }

  save(input: StoreProfileInput): Promise<StoreProfileView> {
    const { merchantId, storeId } = this.coordinates()
    const slot = `merchant-store-profile:${this.api.scope.capture().context.userId}:${merchantId}:${storeId}`
    const spec: RequestSpec = {
      path: '/api/v1/merchant/store-profile', method: 'PUT',
      data: { merchantId, storeId, ...input },
    }
    return this.api.write(slot, spec, value => {
      const view = decodeStoreProfile(value)
      if (view.merchantId !== merchantId || view.storeId !== storeId) fail()
      return view
    }, undefined, (error, command) => {
      // 409（版本冲突/幂等键冲突）对该 payload 终局：可退役槽位让用户改后重提。
      if (isDefiniteProfileConflict(error) && error && typeof error === 'object') {
        this.rejectionProofs.set(error, { slot, command })
      }
    })
  }

  /** 服务器对原 payload 的终局 409 之后，方可退役幂等槽（沿 56号/23号 §5.7 纪律）。 */
  retireConflict(error: unknown): void {
    if (!isDefiniteProfileConflict(error) || !error || typeof error !== 'object') throw new Error('UNCONFIRMED_WRITE')
    const proof = this.rejectionProofs.get(error)
    if (!proof) throw new Error('UNCONFIRMED_WRITE')
    this.api.retireRejectedCommand(proof.slot, proof.command)
    this.rejectionProofs.delete(error)
  }
}

// ---------------------------------------------------------------- customers (69号)

export type PetView = Readonly<{ petName: string; petType: string; breedName: string | null }>

export type CustomerRow = Readonly<{
  userId: string
  nickname: string | null
  phoneMasked: string | null
  orderCount: number
  totalSpend: string
  avgTicket: string | null
  lastVisitAt: string | null
  topPet: PetView | null
}>

export type CustomerDetail = Readonly<{ customer: CustomerRow; pets: readonly PetView[]; note: CustomerNote | null }>
export type CustomerPage = Readonly<{ page: number; pageSize: number; total: number; items: readonly CustomerRow[] }>

/** 商家内部备注（69号扩展）：单客户一条 ≤200 字；null=未设置。 */
export type CustomerNote = Readonly<{ content: string | null; version: string; updatedAt: string }>

const instant = (v: unknown): string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(v)
  && Number.isFinite(Date.parse(v)) && new Date(v).toISOString() === v ? v : fail()
const money = (v: unknown): string => typeof v === 'string' && /^\d+\.\d{2}$/.test(v) ? v : fail()
const nullable = <T>(v: unknown, decode: (x: unknown) => T): T | null => v === null || v === undefined ? null : decode(v)

function petView(value: unknown): PetView {
  const v = exact(value, 'petName,petType,breedName')
  if (typeof v.petName !== 'string' || !v.petName || typeof v.petType !== 'string' || !v.petType) fail()
  return { petName: v.petName, petType: v.petType, breedName: typeof v.breedName === 'string' ? v.breedName : null }
}

function customerRow(value: unknown): CustomerRow {
  const v = exact(value, 'userId,nickname,phoneMasked,orderCount,totalSpend,avgTicket,lastVisitAt,topPet')
  if (!Number.isInteger(v.orderCount) || v.orderCount < 0) fail()
  if (v.phoneMasked !== null && (typeof v.phoneMasked !== 'string' || !/^1[0-9]{2}\*{4}[0-9]{4}$/.test(v.phoneMasked))) fail()
  return {
    userId: id(v.userId),
    nickname: nullable(v.nickname, x => typeof x === 'string' && x.length >= 1 && x.length <= 64 ? x : fail()),
    phoneMasked: v.phoneMasked,
    orderCount: v.orderCount,
    totalSpend: money(v.totalSpend),
    avgTicket: nullable(v.avgTicket, money),
    lastVisitAt: nullable(v.lastVisitAt, instant),
    topPet: nullable(v.topPet, petView),
  }
}

export function decodeCustomerPage(value: unknown): CustomerPage {
  const v = exact(value, 'page,pageSize,total,items')
  if (!Number.isInteger(v.page) || v.page < 1 || v.page > 10000 || !Number.isInteger(v.pageSize)
    || v.pageSize < 1 || v.pageSize > 100 || !Number.isSafeInteger(v.total) || v.total < 0
    || !Array.isArray(v.items) || v.items.length > v.pageSize) fail()
  return { page: v.page, pageSize: v.pageSize, total: v.total, items: v.items.map(customerRow) }
}

export function decodeCustomerDetail(value: unknown): CustomerDetail {
  const v = exact(value, 'userId,nickname,phoneMasked,orderCount,totalSpend,avgTicket,lastVisitAt,topPet,pets,note')
  if (!Array.isArray(v.pets) || v.pets.length > 20) fail()
  const note = v.note === null || v.note === undefined ? null : (() => {
    const n = exact(v.note, 'content,version,updatedAt')
    if (typeof n.version !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(n.version)) fail()
    if (n.content !== null && (typeof n.content !== 'string' || Array.from(n.content).length > 200)) fail()
    return { content: n.content, version: n.version, updatedAt: instant(n.updatedAt) }
  })()
  return { customer: customerRow(v), pets: Object.freeze(v.pets.map(petView)), note }
}

export function decodeCustomerNote(value: unknown): { userId: string; note: CustomerNote } {
  const v = exact(value, 'userId,note')
  const n = exact(v.note, 'content,version,updatedAt')
  if (typeof n.version !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(n.version)) fail()
  if (n.content !== null && (typeof n.content !== 'string' || Array.from(n.content).length > 200)) fail()
  return { userId: id(v.userId), note: { content: n.content, version: n.version, updatedAt: instant(n.updatedAt) } }
}

export class MerchantCustomersClient {
  constructor(private api: ConsumerApi) {}

  private coordinates(): { merchantId: string; storeId: string } {
    const c = this.api.scope.capture().context
    if (c.workspace !== 'merchant' || !c.merchantId || !c.storeId) throw new Error('MERCHANT_ENTRY_REQUIRED')
    return { merchantId: c.merchantId, storeId: c.storeId }
  }

  /** nickname（包含匹配）与 orderNoSuffix（本店订单尾号，2-10 位数字）二选一。 */
  list(filter: { nickname?: string; orderNoSuffix?: string }, page: number, pageSize: number): Promise<CustomerPage> {
    const { merchantId, storeId } = this.coordinates()
    if (!Number.isInteger(page) || page < 1 || page > 10000 || !Number.isInteger(pageSize)
      || pageSize < 1 || pageSize > 100) throw new Error('INVALID_QUERY')
    const data: Record<string, unknown> = { merchantId, storeId, page, pageSize }
    const term = (filter.nickname ?? '').trim()
    if (term) {
      if (Array.from(term).length > 64) throw new Error('INVALID_QUERY')
      data.nickname = term
    }
    const suffix = filter.orderNoSuffix ?? ''
    if (suffix) {
      if (!/^[0-9]{2,10}$/.test(suffix)) throw new Error('INVALID_QUERY')
      if (term) throw new Error('INVALID_QUERY')
      data.orderNoSuffix = suffix
    }
    return this.api.request({ path: '/api/v1/merchant/customers', method: 'GET', data }, value => {
      const result = decodeCustomerPage(value)
      if (result.page !== page || result.pageSize !== pageSize) fail()
      return result
    })
  }

  detail(userId: string): Promise<CustomerDetail> {
    const { merchantId, storeId } = this.coordinates()
    const target = id(userId)
    return this.api.request(
      { path: `/api/v1/merchant/customers/${target}`, method: 'GET', data: { merchantId, storeId } },
      value => {
        const detail = decodeCustomerDetail(value)
        if (detail.customer.userId !== target) fail()
        return detail
      })
  }
}

/** 商家内部备注写客户端（69号扩展）：OWNER 写、supplement-23 幂等槽、乐观版本；
 *  409（版本/幂等键冲突）对该载荷终局后可退役槽位，沿 StoreProfileClient 同款纪律。 */
export class MerchantCustomerNoteClient {
  private rejectionProofs = new WeakMap<object, { slot: string; command: Command }>()
  constructor(private api: ConsumerApi) {}

  private coordinates(): { merchantId: string; storeId: string } {
    const c = this.api.scope.capture().context
    if (c.workspace !== 'merchant' || !c.merchantId || !c.storeId) throw new Error('MERCHANT_ENTRY_REQUIRED')
    return { merchantId: c.merchantId, storeId: c.storeId }
  }

  save(userId: string, note: string, expectedVersion: string): Promise<{ userId: string; note: CustomerNote }> {
    const { merchantId, storeId } = this.coordinates()
    const target = id(userId)
    if (Array.from(note).length > 200) throw new Error('INVALID_NOTE')
    if (!/^(0|[1-9][0-9]{0,18})$/.test(expectedVersion)) throw new Error('INVALID_VERSION')
    const slot = `merchant-customer-note:${this.api.scope.capture().context.userId}:${merchantId}:${storeId}:${target}`
    const spec: RequestSpec = {
      path: `/api/v1/merchant/customers/${target}/note`, method: 'PUT',
      data: { merchantId, storeId, note, expectedVersion },
    }
    return this.api.write(slot, spec, value => {
      const result = decodeCustomerNote(value)
      if (result.userId !== target) fail()
      return result
    }, undefined, (error, command) => {
      if (isDefiniteProfileConflict(error) && error && typeof error === 'object') {
        this.rejectionProofs.set(error, { slot, command })
      }
    })
  }

  retireConflict(error: unknown): void {
    if (!isDefiniteProfileConflict(error) || !error || typeof error !== 'object') throw new Error('UNCONFIRMED_WRITE')
    const proof = this.rejectionProofs.get(error)
    if (!proof) throw new Error('UNCONFIRMED_WRITE')
    this.api.retireRejectedCommand(proof.slot, proof.command)
    this.rejectionProofs.delete(error)
  }
}

// ---------------------------------------------------------------- stats (70号 + 2026-10-19 扩展)

export type StatsRange = 'TODAY' | 'LAST_7D' | 'LAST_30D' | 'CUSTOM'
/** CUSTOM 的闭区间 [from,to]（UTC 日）；服务端护栏 ≤92 天。 */
export type StatsWindow = Readonly<{ from: string; to: string }>
export type StatsDeltas = Readonly<{
  revenue: string | null
  orderCount: string | null
  newCustomers: string | null
  avgTicket: string | null
}>
export type StatsOverview = Readonly<{
  range: StatsRange
  revenue: string
  orderCount: number
  newCustomers: number
  avgTicket: string | null
  verifiedOrderCount: number
  verifyRate: string | null
  refundOrderCount: number
  refundRate: string | null
  repeatRate: string | null
  reviewAvg: string | null
  rejectRate: string | null
  deltas: StatsDeltas
  generatedAt: string
}>
export type StatsServiceRow = Readonly<{ serviceId: string; serviceName: string | null; orderCount: number; revenue: string }>
export type TrendBucket = Readonly<{ date: string; revenue: string; orderCount: number }>
/** 服务下钻只读行：本店自身订单事实（displayStatus 为 order 域统一投影，10 值呈现词汇），
 *  无客户字段；页面只呈现不推导（ARCH-005）。 */
export type StatsServiceOrderRow = Readonly<{
  orderId: string
  orderNo: string | null
  displayStatus: 'PENDING_PAYMENT' | 'PENDING_CONFIRM' | 'PENDING_SERVICE' | 'COMPLETED' | 'CANCELED'
    | 'REFUND_PENDING_CONFIRM' | 'REFUNDING' | 'REFUNDED' | 'PARTIAL_REFUND' | 'AFTERSALE'
  payAmount: string
  refundedAmount: string
  createdAt: string | null
  appointmentStartAt: string | null
  verifiedAt: string | null
}>
export type StatsServiceOrdersPage = Readonly<{
  serviceId: string
  serviceName: string | null
  range: StatsRange
  page: number
  pageSize: number
  total: number
  items: readonly StatsServiceOrderRow[]
}>
/** 排期利用率（53号排期读侧聚合）：全 null = 未装配（页面 --）。 */
export type StatsUtilization = Readonly<{
  range: StatsRange
  from: string
  to: string
  availableMinutes: number | null
  bookedMinutes: number | null
  utilizationRate: string | null
}>

const rate = (v: unknown): string | null =>
  v === null ? null : typeof v === 'string' && /^\d+\.\d$/.test(v) ? v : fail()
const delta = (v: unknown): string | null =>
  v === null ? null : typeof v === 'string' && /^-?\d+(\.\d+)?$/.test(v) ? v : fail()
const counts = (v: unknown): number => typeof v === 'number' && Number.isInteger(v) && v >= 0 ? v : fail()
const isRange = (v: unknown): v is StatsRange =>
  v === 'TODAY' || v === 'LAST_7D' || v === 'LAST_30D' || v === 'CUSTOM'
const dayText = (v: unknown): string =>
  typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) ? v : fail()

export function decodeStatsOverview(value: unknown): StatsOverview {
  const v = exact(value, 'range,revenue,orderCount,newCustomers,avgTicket,verifiedOrderCount,verifyRate,refundOrderCount,refundRate,repeatRate,reviewAvg,rejectRate,deltas,generatedAt')
  if (!isRange(v.range)) fail()
  const deltas = exact(v.deltas, 'revenue,orderCount,newCustomers,avgTicket')
  return {
    range: v.range, revenue: money(v.revenue), orderCount: counts(v.orderCount),
    newCustomers: counts(v.newCustomers), avgTicket: nullable(v.avgTicket, money),
    verifiedOrderCount: counts(v.verifiedOrderCount), verifyRate: rate(v.verifyRate),
    refundOrderCount: counts(v.refundOrderCount), refundRate: rate(v.refundRate),
    repeatRate: rate(v.repeatRate), reviewAvg: nullable(v.reviewAvg, x => {
      if (typeof x !== 'string' || !/^[0-5]\.\d$/.test(x)) fail()
      return x
    }),
    rejectRate: rate(v.rejectRate),
    deltas: { revenue: delta(deltas.revenue), orderCount: delta(deltas.orderCount),
      newCustomers: delta(deltas.newCustomers), avgTicket: delta(deltas.avgTicket) },
    generatedAt: instant(v.generatedAt),
  }
}

export function decodeStatsServiceTop(value: unknown): { range: StatsRange; items: readonly StatsServiceRow[] } {
  const v = exact(value, 'range,items')
  if (!isRange(v.range) || !Array.isArray(v.items) || v.items.length > 5) fail()
  return {
    range: v.range,
    items: Object.freeze(v.items.map(item => {
      const row = exact(item, 'serviceId,serviceName,orderCount,revenue')
      return {
        serviceId: id(row.serviceId),
        serviceName: nullable(row.serviceName, x => typeof x === 'string' && x.length >= 1 ? x : fail()),
        orderCount: counts(row.orderCount), revenue: money(row.revenue),
      }
    })),
  }
}

export function decodeStatsTrend(value: unknown): { range: StatsRange; buckets: readonly TrendBucket[] } {
  const v = exact(value, 'range,buckets')
  if (!isRange(v.range) || !Array.isArray(v.buckets)
    || v.buckets.length < 1 || v.buckets.length > 92) fail()
  return {
    range: v.range,
    buckets: Object.freeze(v.buckets.map(bucket => {
      const row = exact(bucket, 'date,revenue,orderCount')
      if (typeof row.date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(row.date)) fail()
      return { date: row.date, revenue: money(row.revenue), orderCount: counts(row.orderCount) }
    })),
  }
}

export function decodeStatsServiceOrders(value: unknown): StatsServiceOrdersPage {
  const v = exact(value, 'serviceId,serviceName,range,page,pageSize,total,items')
  if (!isRange(v.range) || !Array.isArray(v.items) || v.items.length > 100) fail()
  if (!Number.isInteger(v.page) || v.page < 1 || v.page > 10000 || !Number.isInteger(v.pageSize)
    || v.pageSize < 1 || v.pageSize > 100 || !Number.isSafeInteger(v.total) || v.total < 0) fail()
  return {
    serviceId: id(v.serviceId),
    serviceName: nullable(v.serviceName, x => typeof x === 'string' && x.length >= 1 ? x : fail()),
    range: v.range, page: v.page, pageSize: v.pageSize, total: v.total,
    items: Object.freeze(v.items.map(item => {
      const row = exact(item, 'orderId,orderNo,displayStatus,payAmount,refundedAmount,createdAt,appointmentStartAt,verifiedAt')
      const statuses = ['PENDING_PAYMENT', 'PENDING_CONFIRM', 'PENDING_SERVICE', 'COMPLETED', 'CANCELED',
        'REFUND_PENDING_CONFIRM', 'REFUNDING', 'REFUNDED', 'PARTIAL_REFUND', 'AFTERSALE']
      return {
        orderId: id(row.orderId),
        orderNo: nullable(row.orderNo, x => typeof x === 'string' && /^[0-9]{1,19}$/.test(x) ? x : fail()),
        displayStatus: statuses.includes(row.displayStatus as string) ? row.displayStatus as never : fail(),
        payAmount: money(row.payAmount), refundedAmount: money(row.refundedAmount),
        createdAt: nullable(row.createdAt, instant),
        appointmentStartAt: nullable(row.appointmentStartAt, instant),
        verifiedAt: nullable(row.verifiedAt, instant),
      }
    })),
  }
}

export function decodeStatsUtilization(value: unknown): StatsUtilization {
  const v = exact(value, 'range,from,to,availableMinutes,bookedMinutes,utilizationRate')
  if (!isRange(v.range)) fail()
  const minutes = (x: unknown): number | null =>
    x === null ? null : (Number.isInteger(x) && (x as number) >= 0 ? x as number : fail())
  return {
    range: v.range, from: dayText(v.from), to: dayText(v.to),
    availableMinutes: minutes(v.availableMinutes), bookedMinutes: minutes(v.bookedMinutes),
    utilizationRate: rate(v.utilizationRate),
  }
}

export class MerchantStatsClient {
  constructor(private api: ConsumerApi) {}

  private coordinates(): { merchantId: string; storeId: string } {
    const c = this.api.scope.capture().context
    if (c.workspace !== 'merchant' || !c.merchantId || !c.storeId) throw new Error('MERCHANT_ENTRY_REQUIRED')
    return { merchantId: c.merchantId, storeId: c.storeId }
  }

  private params(range: StatsRange, custom?: StatsWindow): Record<string, unknown> {
    const { merchantId, storeId } = this.coordinates()
    const data: Record<string, unknown> = { merchantId, storeId, range }
    if (range === 'CUSTOM') {
      if (!custom || !/^\d{4}-\d{2}-\d{2}$/.test(custom.from) || !/^\d{4}-\d{2}-\d{2}$/.test(custom.to)
        || custom.from > custom.to) throw new Error('INVALID_QUERY')
      data.from = custom.from
      data.to = custom.to
    }
    return data
  }

  overview(range: StatsRange, custom?: StatsWindow): Promise<StatsOverview> {
    const data = this.params(range, custom)
    return this.api.request(
      { path: '/api/v1/merchant/stats/overview', method: 'GET', data },
      value => {
        const result = decodeStatsOverview(value)
        if (result.range !== range) fail()
        return result
      })
  }

  serviceTop(range: StatsRange, custom?: StatsWindow): Promise<{ range: StatsRange; items: readonly StatsServiceRow[] }> {
    const data = this.params(range, custom)
    return this.api.request(
      { path: '/api/v1/merchant/stats/services/top', method: 'GET', data },
      value => {
        const result = decodeStatsServiceTop(value)
        if (result.range !== range) fail()
        return result
      })
  }

  revenueTrend(range: StatsRange, custom?: StatsWindow): Promise<{ range: StatsRange; buckets: readonly TrendBucket[] }> {
    const data = this.params(range, custom)
    return this.api.request(
      { path: '/api/v1/merchant/stats/revenue-trend', method: 'GET', data },
      value => {
        const result = decodeStatsTrend(value)
        if (result.range !== range) fail()
        return result
      })
  }

  /** 服务下钻（2026-10-19 扩展）：该服务在窗口内的订单明细分页，只读。 */
  serviceOrders(serviceId: string, range: StatsRange, page: number,
      custom?: StatsWindow): Promise<StatsServiceOrdersPage> {
    const data = this.params(range, custom)
    data.page = page
    const target = id(serviceId)
    return this.api.request(
      { path: `/api/v1/merchant/stats/services/${target}/orders`, method: 'GET', data },
      value => {
        const result = decodeStatsServiceOrders(value)
        if (result.serviceId !== target || result.page !== page || result.range !== range) fail()
        return result
      })
  }

  /** 排期利用率（2026-10-19 扩展）：53号排期读侧聚合。 */
  scheduleUtilization(range: StatsRange, custom?: StatsWindow): Promise<StatsUtilization> {
    const data = this.params(range, custom)
    return this.api.request(
      { path: '/api/v1/merchant/stats/schedule-utilization', method: 'GET', data },
      value => {
        const result = decodeStatsUtilization(value)
        if (result.range !== range) fail()
        return result
      })
  }
}
