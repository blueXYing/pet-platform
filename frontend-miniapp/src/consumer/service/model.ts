// Service-catalog view state mirroring the frozen C-end contract (CCR-W2-API-001 service
// domain v0.3, human-approved SVC-D1..D5; authoritative shapes as implemented by the backend
// CServiceController): IDs stay String, salePrice is a two-decimal decimal string, and the
// client never recomputes visibility/bookability (SVC-D1b: hidden simply means absent/404).
export type FulfillmentType = 'IN_STORE' | 'PICKUP_DELIVERY'

/** Consumer cover projection (10号 §3.3.1 封面增补): only visible rows carry it; rows without
 *  a cover binding keep cover null. coverUrl is a short-lived signed URL expiring at
 *  coverUrlExpiresAt (second precision — the signer's epoch-second format). */
export type ServiceCoverView = Readonly<{
  coverAssetId: string; coverUrl: string; coverUrlExpiresAt: string
}>
/** GET /api/v1/c/stores/{storeId}/services item (list projection, no description, no version). */
export type ServiceItemView = Readonly<{
  serviceId: string; merchantId: string; storeId: string
  serviceName: string; categoryId: string; categoryName: string
  salePrice: string; durationMinutes: number; fulfillmentType: FulfillmentType
  cover: ServiceCoverView | null
}>
/** GET /api/v1/c/services/{serviceId} detail = item fields + description. */
export type ServiceDetailView = Readonly<ServiceItemView & { description: string }>
/** Pagination envelope shared with the approved list route: items/page/pageSize/total. */
export type ServicePage = Readonly<{ items: ServiceItemView[]; page: number; pageSize: number; total: number }>

const invalid = (): never => { throw new Error('INVALID_RESPONSE') }
function exact(value: unknown, keys: readonly string[]): Record<string, any> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid()
  const record = value as Record<string, unknown>
  if (Object.keys(record).some(key => !keys.includes(key))) invalid()
  return value as Record<string, any>
}
const isId = (value: unknown): value is string =>
  typeof value === 'string' && /^[1-9][0-9]{0,18}(?![\s\S])/.test(value) && BigInt(value) <= 9223372036854775807n
const price = (value: any): string => {
  if (typeof value !== 'string' || !/^\d+\.\d{2}$/.test(value)) invalid()
  return value
}

const instant = (value: any): string => {
  if (typeof value !== 'string'
    || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})(?![\s\S])/.test(value)
    || !Number.isFinite(Date.parse(value))) invalid()
  return value
}
function coverOf(value: any): ServiceCoverView | null {
  if (value === null) return null
  const v = exact(value, ['coverAssetId', 'coverUrl', 'coverUrlExpiresAt'])
  // A bound cover always carries its asset anchor and its own signed-URL expiry.
  return { coverAssetId: isId(v.coverAssetId) ? v.coverAssetId : invalid(),
    coverUrl: typeof v.coverUrl === 'string' && v.coverUrl.length > 0 && v.coverUrl.length <= 2048 ? v.coverUrl : invalid(),
    coverUrlExpiresAt: instant(v.coverUrlExpiresAt) }
}
function checkServiceFields(v: Record<string, any>): ServiceItemView {
  if (!isId(v.serviceId) || !isId(v.merchantId) || !isId(v.storeId) || !isId(v.categoryId)) invalid()
  if (typeof v.serviceName !== 'string' || !v.serviceName || [...v.serviceName].length > 50) invalid()
  if (typeof v.categoryName !== 'string' || !v.categoryName || [...v.categoryName].length > 50) invalid()
  if (!Number.isSafeInteger(v.durationMinutes) || v.durationMinutes < 1 || v.durationMinutes > 10080) invalid()
  if (v.fulfillmentType !== 'IN_STORE' && v.fulfillmentType !== 'PICKUP_DELIVERY') invalid()
  return { serviceId: v.serviceId, merchantId: v.merchantId, storeId: v.storeId, serviceName: v.serviceName,
    categoryId: v.categoryId, categoryName: v.categoryName, salePrice: price(v.salePrice),
    durationMinutes: v.durationMinutes, fulfillmentType: v.fulfillmentType, cover: coverOf(v.cover) }
}
export function decodeServiceItem(value: unknown): ServiceItemView {
  return checkServiceFields(exact(value, ['serviceId', 'merchantId', 'storeId', 'serviceName', 'categoryId', 'categoryName', 'salePrice', 'durationMinutes', 'fulfillmentType', 'cover']))
}
export function decodeServiceDetail(value: unknown): ServiceDetailView {
  const v = exact(value, ['serviceId', 'merchantId', 'storeId', 'serviceName', 'categoryId', 'categoryName', 'salePrice', 'durationMinutes', 'fulfillmentType', 'cover', 'description'])
  if (typeof v.description !== 'string' || [...v.description].length > 1000) invalid()
  return { ...checkServiceFields(v), description: v.description }
}
export function decodeServicePage(value: unknown): ServicePage {
  const v = exact(value, ['items', 'page', 'pageSize', 'total'])
  if (!Array.isArray(v.items) || v.items.length > 50) invalid()
  const page = Number(v.page), pageSize = Number(v.pageSize), total = Number(v.total)
  if (!Number.isInteger(page) || page < 1 || page > 10000) invalid()
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 50) invalid()
  if (!Number.isInteger(total) || total < 0) invalid()
  return { items: v.items.map(decodeServiceItem), page, pageSize, total }
}

// Design shows integer prices ("¥80"); the contract string keeps two decimals ("80.00") purely
// as the wire format (SVC-D3). Display trims the zero fraction without touching the value.
export function formatSalePrice(salePrice: string): string {
  return salePrice.replace(/\.?0+$/, '') || salePrice
}

// Design-sample copy with NO approved contract field yet (list "已售N" counts; store header,
// opening hours, promo strip, reviews depend on the unfrozen /c/stores and review contracts).
// Rendered for visual fidelity only and keyed by the fixture serviceId, following the C-002
// designSamples precedent; registered as contract gaps in C-003-design-inputs/INVENTORY.md.
export const designSamples = {
  store: {
    name: '萌宠之家宠物店', typeTag: '宠物生活馆', rating: '4.8', monthlySold: '月售1200', distance: '1.5km',
    tags: ['美容', '寄养', '用品'],
    intro: '一站式宠物生活馆，提供专业美容、家庭寄养、进口零食与玩具，配备24小时监控。',
    address: '上海市浦东新区世纪大道 100 号', hours: '09:00 - 21:00', phone: '021-6888-1234',
    promoBadge: '5折', promoTitle: '全场美容套餐5折起', promoSub: '专业美容师持证上岗',
  },
  sold: { '20001': '已售328', '20002': '已售156', '20003': '已售89' } as Record<string, string>,
  reviews: {
    count: '2 条',
    items: [
      { name: '林小姐', time: '3天前', stars: 'orange', text: '美容师手法专业，豆豆剪完造型特别可爱，全程配合，非常满意。' },
      { name: '王女士', time: '1周前', stars: 'blue', text: '环境干净整洁，用品都是进口的，猫猫在这里很安心。' },
    ],
  },
  // The frozen list projection carries no description; the design cards show one. The list page
  // renders this sample line for fixture ids only, never for real data; the detail page always
  // uses the contract description field.
  listDescription: { '20001': '含洗护、造型、指甲修剪', '20002': '独立空间、定时喂养、遛弯', '20003': '深层清洁 + 精油护理' } as Record<string, string>,
} as const

// Fixture services follow the approved StoreServiceItemView/ServiceDetailView shapes exactly
// (design samples of node 690:6660: 专业美容套餐 ¥80 / 家庭寄养·天 ¥60 / 洗护SPA ¥128).
/** Mock signed-URL values — the real ones refresh per backend response (§3.3.1 封面增补). */
export const fixtureCoverUrl = 'https://design.example/covers/mock-cover.png'
export const fixtureCoverUrlExpiresAt = '2026-09-22T12:00:00Z'
const cover = (coverAssetId: string): ServiceCoverView => ({ coverAssetId, coverUrl: fixtureCoverUrl, coverUrlExpiresAt: fixtureCoverUrlExpiresAt })
export const fixtureServices: ServiceDetailView[] = [
  { serviceId: '20001', merchantId: '957001', storeId: '957002', serviceName: '专业美容套餐',
    categoryId: '957003', categoryName: '宠物美容', salePrice: '80.00', durationMinutes: 60,
    fulfillmentType: 'IN_STORE', description: '含洗护、造型、指甲修剪', cover: cover('40001') },
  { serviceId: '20002', merchantId: '957001', storeId: '957002', serviceName: '家庭寄养·天',
    categoryId: '957004', categoryName: '宠物寄养', salePrice: '60.00', durationMinutes: 60,
    fulfillmentType: 'IN_STORE', description: '独立空间、定时喂养、遛弯', cover: cover('40002') },
  { serviceId: '20003', merchantId: '957001', storeId: '957002', serviceName: '洗护SPA',
    categoryId: '957003', categoryName: '宠物美容', salePrice: '128.00', durationMinutes: 45,
    fulfillmentType: 'IN_STORE', description: '深层清洁 + 精油护理', cover: null },
]
export const fixtureStoreId = '957002'

export type ServiceScenario = 'normal' | 'empty' | 'load-error' | 'expired'
export const isServiceScenario = (value?: string): value is ServiceScenario =>
  ['normal', 'empty', 'load-error', 'expired'].includes(value || '')

export type ServiceCatalogDeps = {
  list(storeId: string, page: number, pageSize: number): Promise<ServicePage>
  detail(serviceId: string): Promise<ServiceDetailView>
}

// CONTRACT MOCK (approved-contract preview): no network, no session, no durable user data.
// It answers with the frozen response semantics — an invisible/unknown store lists as an empty
// page (indistinguishable), an unknown service details as 404 SERVICE_NOT_FOUND, and a facts
// failure fails closed with 503 — but it is NOT a real backend integration and never authorizes
// anyone. Swap point: pages take ServiceCatalogDeps; realServiceRepository() provides the live
// wiring once the deployed service is enabled (pet.service.query.enabled).
export class PreviewServiceRepository implements ServiceCatalogDeps {
  private failLoad = false
  constructor(private services: ServiceDetailView[] = fixtureServices, scenario: ServiceScenario = 'normal', private pause: () => Promise<void> = async () => {}) {
    if (scenario === 'empty') this.services = []
    this.failLoad = scenario === 'load-error'
  }
  async list(storeId: string, page = 1, pageSize = 20): Promise<ServicePage> {
    await this.pause()
    if (this.failLoad) { this.failLoad = false; throw new ServiceMockError('COMMON_DEPENDENCY_UNAVAILABLE', 503) }
    if (!isId(storeId)) throw new ServiceMockError('COMMON_INVALID_ARGUMENT', 400)
    const bounded = Math.min(Math.max(1, Math.trunc(page)), 10000)
    const size = Math.min(Math.max(1, Math.trunc(pageSize)), 50)
    const visible = this.services.filter(service => service.storeId === storeId)
    const start = (bounded - 1) * size
    return { items: visible.slice(start, start + size).map(({ description: _description, ...item }) => ({ ...item })),
      page: bounded, pageSize: size, total: visible.length }
  }
  async detail(serviceId: string): Promise<ServiceDetailView> {
    await this.pause()
    if (this.failLoad) { this.failLoad = false; throw new ServiceMockError('COMMON_DEPENDENCY_UNAVAILABLE', 503) }
    if (!isId(serviceId)) throw new ServiceMockError('COMMON_INVALID_ARGUMENT', 400)
    const found = this.services.find(service => service.serviceId === serviceId)
    // Contract SVC-D1b: not found and not visible answer indistinguishably with 404.
    if (!found) throw new ServiceMockError('SERVICE_NOT_FOUND', 404)
    return { ...found }
  }
}
export class ServiceMockError extends Error {
  constructor(readonly code: string, readonly statusCode: number) { super(code) }
}
