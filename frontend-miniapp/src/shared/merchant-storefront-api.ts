// M 端收尾切片三个读/写投影（后端同批 PR 交付，IMPLEMENTED_DEFAULT_OFF：
// pet.merchant.store-profile.http.enabled / pet.order.customer.http.enabled /
// pet.order.stats.http.enabled 默认关闭）。字段事实全部来自契约与本批实现：
// - 27号 §6.4 店铺资料：GET/PUT /api/v1/merchant/store-profile（全字段替换：JSON 显式 null
//   非法、文本空串清空、photoAssetIds/businessHours 空数组清空、promoLevel 0=未设置）。
// - 69号 客户档案（只读派生）：GET /api/v1/merchant/customers（nickname 包含搜索有界扫描）、
//   GET /api/v1/merchant/customers/{userId}；手机号仅脱敏（1xx****xxxx），宠物仅名字/品种级。
// - 70号 经营统计（只读聚合）：GET /api/v1/merchant/stats/overview|services/top|revenue-trend；
//   range 词汇 TODAY/LAST_7D/LAST_30D（默认 LAST_7D），比率一位小数无 % 号、null 显示 --。
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
  operatingStatus: 'OPERATING' | 'OFFLINE' | 'FROZEN'
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
  version: string
}>

export type StoreProfileView = Readonly<{
  merchantId: string
  storeId: string
  readonlyFacts: StoreProfileReadonly
  profile: StoreProfile
}>

/** 编辑载荷：全字段替换语义（与后端严格 JSON 一一对应；值可为空串/空数组/0 表示清空）。 */
export type StoreProfileInput = Readonly<{
  intro: string
  announcement: string
  photoAssetIds: readonly string[]
  businessHours: readonly BusinessHour[]
  promoLevel: number
  promoTitle: string
  promoText: string
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
  const profile = exact(v.profile, 'intro,announcement,photoAssetIds,businessHours,promoLevel,promoTitle,promoText,version')
  if (typeof facts.storeName !== 'string' || !facts.storeName
    || typeof facts.address !== 'string' || !facts.address
    || !['ACTIVE', 'OFFLINE', 'FROZEN', 'APPLYING', 'CANCELED'].includes(facts.merchantStatus)
    || !['ACTIVE', 'OFFLINE', 'FROZEN'].includes(facts.storeStatus)
    || !['OPERATING', 'OFFLINE', 'FROZEN'].includes(facts.operatingStatus)) fail()
  if (facts.phoneMasked !== null && (typeof facts.phoneMasked !== 'string' || !/^1[0-9]{2}\*{4}[0-9]{4}$/.test(facts.phoneMasked))) fail()
  if (!Array.isArray(profile.photoAssetIds)
    || profile.photoAssetIds.some(item => typeof item !== 'string' || !/^[1-9][0-9]{0,18}$/.test(item))
    || profile.photoAssetIds.length > 6) fail()
  if (profile.promoLevel !== null && (!Number.isInteger(profile.promoLevel) || profile.promoLevel < 1 || profile.promoLevel > 9)) fail()
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

export type CustomerDetail = Readonly<{ customer: CustomerRow; pets: readonly PetView[] }>
export type CustomerPage = Readonly<{ page: number; pageSize: number; total: number; items: readonly CustomerRow[] }>

const instant = (v: unknown): string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(v)
  && Number.isFinite(Date.parse(v)) && new Date(v).toISOString() === v ? v : fail()
const money = (v: unknown): string => typeof v === 'string' && /^\d+\.\d{2}$/.test(v) ? v : fail()
const nullable = <T>(v: unknown, decode: (x: unknown) => T): T | null => v === null ? null : decode(v)

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
  const v = exact(value, 'userId,nickname,phoneMasked,orderCount,totalSpend,avgTicket,lastVisitAt,topPet,pets')
  if (!Array.isArray(v.pets) || v.pets.length > 20) fail()
  return { customer: customerRow(v), pets: Object.freeze(v.pets.map(petView)) }
}

export class MerchantCustomersClient {
  constructor(private api: ConsumerApi) {}

  private coordinates(): { merchantId: string; storeId: string } {
    const c = this.api.scope.capture().context
    if (c.workspace !== 'merchant' || !c.merchantId || !c.storeId) throw new Error('MERCHANT_ENTRY_REQUIRED')
    return { merchantId: c.merchantId, storeId: c.storeId }
  }

  list(nickname: string, page: number, pageSize: number): Promise<CustomerPage> {
    const { merchantId, storeId } = this.coordinates()
    if (!Number.isInteger(page) || page < 1 || page > 10000 || !Number.isInteger(pageSize)
      || pageSize < 1 || pageSize > 100) throw new Error('INVALID_QUERY')
    const data: Record<string, unknown> = { merchantId, storeId, page, pageSize }
    const term = nickname.trim()
    if (term) {
      if (Array.from(term).length > 64) throw new Error('INVALID_QUERY')
      data.nickname = term
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

// ---------------------------------------------------------------- stats (70号)

export type StatsRange = 'TODAY' | 'LAST_7D' | 'LAST_30D'
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
  deltas: StatsDeltas
  generatedAt: string
}>
export type StatsServiceRow = Readonly<{ serviceId: string; serviceName: string | null; orderCount: number; revenue: string }>
export type TrendBucket = Readonly<{ date: string; revenue: string; orderCount: number }>

const rate = (v: unknown): string | null =>
  v === null ? null : typeof v === 'string' && /^\d+\.\d$/.test(v) ? v : fail()
const delta = (v: unknown): string | null =>
  v === null ? null : typeof v === 'string' && /^-?\d+(\.\d+)?$/.test(v) ? v : fail()
const counts = (v: unknown): number => typeof v === 'number' && Number.isInteger(v) && v >= 0 ? v : fail()

export function decodeStatsOverview(value: unknown): StatsOverview {
  const v = exact(value, 'range,revenue,orderCount,newCustomers,avgTicket,verifiedOrderCount,verifyRate,refundOrderCount,refundRate,repeatRate,reviewAvg,deltas,generatedAt')
  if (!['TODAY', 'LAST_7D', 'LAST_30D'].includes(v.range)) fail()
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
    deltas: { revenue: delta(deltas.revenue), orderCount: delta(deltas.orderCount),
      newCustomers: delta(deltas.newCustomers), avgTicket: delta(deltas.avgTicket) },
    generatedAt: instant(v.generatedAt),
  }
}

export function decodeStatsServiceTop(value: unknown): { range: StatsRange; items: readonly StatsServiceRow[] } {
  const v = exact(value, 'range,items')
  if (!['TODAY', 'LAST_7D', 'LAST_30D'].includes(v.range) || !Array.isArray(v.items) || v.items.length > 5) fail()
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
  if (!['TODAY', 'LAST_7D', 'LAST_30D'].includes(v.range) || !Array.isArray(v.buckets)
    || v.buckets.length < 1 || v.buckets.length > 30) fail()
  return {
    range: v.range,
    buckets: Object.freeze(v.buckets.map(bucket => {
      const row = exact(bucket, 'date,revenue,orderCount')
      if (typeof row.date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(row.date)) fail()
      return { date: row.date, revenue: money(row.revenue), orderCount: counts(row.orderCount) }
    })),
  }
}

export class MerchantStatsClient {
  constructor(private api: ConsumerApi) {}

  private coordinates(): { merchantId: string; storeId: string } {
    const c = this.api.scope.capture().context
    if (c.workspace !== 'merchant' || !c.merchantId || !c.storeId) throw new Error('MERCHANT_ENTRY_REQUIRED')
    return { merchantId: c.merchantId, storeId: c.storeId }
  }

  overview(range: StatsRange): Promise<StatsOverview> {
    const { merchantId, storeId } = this.coordinates()
    return this.api.request(
      { path: '/api/v1/merchant/stats/overview', method: 'GET', data: { merchantId, storeId, range } },
      value => {
        const result = decodeStatsOverview(value)
        if (result.range !== range) fail()
        return result
      })
  }

  serviceTop(range: StatsRange): Promise<{ range: StatsRange; items: readonly StatsServiceRow[] }> {
    const { merchantId, storeId } = this.coordinates()
    return this.api.request(
      { path: '/api/v1/merchant/stats/services/top', method: 'GET', data: { merchantId, storeId, range } },
      value => {
        const result = decodeStatsServiceTop(value)
        if (result.range !== range) fail()
        return result
      })
  }

  revenueTrend(range: StatsRange): Promise<{ range: StatsRange; buckets: readonly TrendBucket[] }> {
    const { merchantId, storeId } = this.coordinates()
    return this.api.request(
      { path: '/api/v1/merchant/stats/revenue-trend', method: 'GET', data: { merchantId, storeId, range } },
      value => {
        const result = decodeStatsTrend(value)
        if (result.range !== range) fail()
        return result
      })
  }
}
