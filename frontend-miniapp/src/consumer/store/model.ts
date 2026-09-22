// C-003 store-directory view state mirroring the /c/stores read contract (CCR-W2-API-001
// store-read proposal v0.1 STR-D1..D8 with the 2026-09-22 user adjudication: nine-field
// projection, 404 STORE_NOT_FOUND indistinguishable for unknown/invisible stores, 503
// fail-closed, anonymous browsing allowed, city scoped by the server-side open-city catalog,
// fixed merchantId,storeId numeric-ascending order, no sort/keyword/categoryId parameters).
// Backend by role B; shapes here follow the proposal §2 and are the swap point if the frozen
// authoritative contract differs when B-side lands.
export type StoreView = Readonly<{
  storeId: string; merchantId: string
  storeName: string; merchantName: string
  address: string
  longitude: string | null; latitude: string | null
  phoneMasked: string | null
  cityCode: string
}>
/** Detail carries the same field set as the list item in this slice (proposal §2). */
export type StoreDetailView = StoreView
export type StorePage = Readonly<{ items: StoreView[]; page: number; pageSize: number; total: number }>
export type StoreCity = Readonly<{ cityCode: string; cityName: string }>

const invalid = (): never => { throw new Error('INVALID_RESPONSE') }
function exact(value: unknown, keys: readonly string[]): Record<string, any> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid()
  const record = value as Record<string, unknown>
  if (Object.keys(record).some(key => !keys.includes(key))) invalid()
  return value as Record<string, any>
}
const isId = (value: unknown): value is string =>
  typeof value === 'string' && /^[1-9][0-9]{0,18}(?![\s\S])/.test(value) && BigInt(value) <= 9223372036854775807n
const text = (value: any, max: number): string => {
  if (typeof value !== 'string' || [...value].length < 1 || [...value].length > max) invalid()
  return value
}
const textOrNull = (value: unknown, max: number): string | null => value === null ? null : text(value, max)
function coordinate(value: any, limit: 180 | 90): string | null {
  if (value === null) return null
  if (typeof value !== 'string' || !/^-?(0|[1-9][0-9]{0,2})(\.[0-9]{1,7})?(?![\s\S])/.test(value)) invalid()
  if (Math.abs(Number(value)) > limit) invalid()
  return value
}
const cityCode = (value: any): string => {
  if (typeof value !== 'string' || !/^[a-z][a-z0-9_-]{0,31}(?![\s\S])/.test(value)) invalid()
  return value
}

const storeKeys = ['storeId', 'merchantId', 'storeName', 'merchantName', 'address', 'longitude', 'latitude', 'phoneMasked', 'cityCode'] as const
export function decodeStore(value: unknown): StoreView {
  const v = exact(value, storeKeys)
  return {
    storeId: isId(v.storeId) ? v.storeId : invalid(),
    merchantId: isId(v.merchantId) ? v.merchantId : invalid(),
    storeName: text(v.storeName, 128),
    merchantName: text(v.merchantName, 128),
    address: text(v.address, 255),
    longitude: coordinate(v.longitude, 180),
    latitude: coordinate(v.latitude, 90),
    phoneMasked: textOrNull(v.phoneMasked, 32),
    cityCode: cityCode(v.cityCode),
  }
}
export function decodeStorePage(value: unknown): StorePage {
  const v = exact(value, ['items', 'page', 'pageSize', 'total'])
  if (!Array.isArray(v.items) || v.items.length > 50) invalid()
  const page = Number(v.page), pageSize = Number(v.pageSize), total = Number(v.total)
  if (!Number.isInteger(page) || page < 1 || page > 10000) invalid()
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 50) invalid()
  if (!Number.isInteger(total) || total < 0) invalid()
  const items = v.items.map(decodeStore)
  // STR-D4: fixed merchantId,storeId numeric ascending order.
  for (let index = 1; index < items.length; index++) {
    const previous = items[index - 1]!, current = items[index]!
    if (BigInt(previous.merchantId) > BigInt(current.merchantId)
      || (previous.merchantId === current.merchantId && BigInt(previous.storeId) >= BigInt(current.storeId))) invalid()
  }
  return { items, page, pageSize, total }
}
export function decodeCityList(value: unknown): StoreCity[] {
  const v = exact(value, ['items'])
  if (!Array.isArray(v.items) || v.items.length < 1 || v.items.length > 100) invalid()
  return v.items.map((item: any) => {
    const city = exact(item, ['cityCode', 'cityName'])
    return { cityCode: cityCode(city.cityCode), cityName: text(city.cityName, 64) }
  })
}

export type StoreListQuery = Readonly<{ city?: string; page: number; pageSize: number }>
export type StoreDirectoryDeps = {
  cities(): Promise<StoreCity[]>
  list(query: StoreListQuery): Promise<StorePage>
  detail(storeId: string): Promise<StoreDetailView>
}

export class StoreMockError extends Error {
  constructor(readonly code: string, readonly statusCode: number) { super(code) }
}

export type StoreScenario = 'normal' | 'empty' | 'load-error'
export const isStoreScenario = (value?: string): value is StoreScenario =>
  ['normal', 'empty', 'load-error'].includes(value || '')

// CONTRACT MOCK (approved-contract preview): no network, no session, no durable data. The
// server-side semantics it mirrors: the open-city catalog bounds the `city` parameter (unknown
// city = 400, omitted = all open cities), invisible stores are absent from the list and answer
// detail with an indistinguishable 404, and facts failures fail closed with 503. NOT a real
// backend integration.
export class PreviewStoreRepository implements StoreDirectoryDeps {
  private failNext = false
  constructor(private stores: StoreView[] = fixtureStores, private scenario: StoreScenario = 'normal', private pause: () => Promise<void> = async () => {}) {
    if (scenario === 'empty') this.stores = []
    this.failNext = scenario === 'load-error'
  }
  async cities(): Promise<StoreCity[]> {
    await this.pause()
    return fixtureCities.map(city => ({ ...city }))
  }
  async list({ city, page = 1, pageSize = 20 }: StoreListQuery): Promise<StorePage> {
    await this.pause()
    if (this.failNext) { this.failNext = false; throw new StoreMockError('COMMON_DEPENDENCY_UNAVAILABLE', 503) }
    if (city !== undefined && !fixtureCities.some(entry => entry.cityCode === city)) {
      throw new StoreMockError('COMMON_INVALID_ARGUMENT', 400)
    }
    const bounded = Math.min(Math.max(1, Math.trunc(page)), 10000)
    const size = Math.min(Math.max(1, Math.trunc(pageSize)), 50)
    const scoped = city === undefined ? this.stores : this.stores.filter(store => store.cityCode === city)
    const sorted = [...scoped].sort((a, b) => BigInt(a.merchantId) < BigInt(b.merchantId) ? -1 : BigInt(a.merchantId) > BigInt(b.merchantId) ? 1 : BigInt(a.storeId) < BigInt(b.storeId) ? -1 : 1)
    const start = (bounded - 1) * size
    return { items: sorted.slice(start, start + size).map(store => ({ ...store })), page: bounded, pageSize: size, total: sorted.length }
  }
  async detail(storeId: string): Promise<StoreDetailView> {
    await this.pause()
    if (this.failNext) { this.failNext = false; throw new StoreMockError('COMMON_DEPENDENCY_UNAVAILABLE', 503) }
    if (!isId(storeId)) throw new StoreMockError('COMMON_INVALID_ARGUMENT', 400)
    const found = this.stores.find(store => store.storeId === storeId)
    // STR-D5: unknown, invisible or ineligible stores answer indistinguishably with 404.
    if (!found) throw new StoreMockError('STORE_NOT_FOUND', 404)
    return { ...found }
  }
}

// Design samples of frame 690:6660 (萌宠之家) plus two extra catalog rows; only fields the
// nine-field contract actually carries are present — no rating/sold/distance/promo fabrication.
export const fixtureCities: StoreCity[] = [{ cityCode: 'chengdu', cityName: '成都' }]
export const fixtureStores: StoreView[] = [
  { storeId: '957002', merchantId: '957001', storeName: '萌宠之家宠物店', merchantName: '萌宠之家（成都）有限公司',
    address: '四川省成都市锦江区春熙路100号', longitude: '104.0812345', latitude: '30.6571234',
    phoneMasked: '138****5678', cityCode: 'chengdu' },
  { storeId: '958002', merchantId: '958001', storeName: '安心宠物医院（锦江店）', merchantName: '安心宠物医疗有限公司',
    address: '四川省成都市锦江区东大街上东大街段58号', longitude: '104.0935621', latitude: '30.6498110',
    phoneMasked: null, cityCode: 'chengdu' },
  { storeId: '959002', merchantId: '959001', storeName: '绿意园艺坊', merchantName: '绿意园艺服务工作室',
    address: '四川省成都市武侯区人民南路四段12号', longitude: null, latitude: null,
    phoneMasked: '189****2468', cityCode: 'chengdu' },
]
/** A store that fails the visibility conjunction: absent from lists, detail answers 404. */
export const fixtureInvisibleStoreId = '960002'

/** The 11-category unified service dictionary rendered by the design grid (PRD §5.1.13;
 *  category filtering itself is deferred with the /c/stores contract — taps stay unwired). */
export const directoryCategories = ['宠物美容', '遛狗陪护', '宠物寄养', '宠物训练', '上门喂养', '兽医助理', '小宠寄养', '异宠上门喂养', '异宠健康检查', '绿植养护', '植物代养'] as const
