import { id, object, type ConsumerApi } from '../../shared/consumer-api'

// 服务目录页模型与严格解码（74 号契约 v0.1 §3.1/§3.4，F-C-01）。解码风格沿 marketing/model.ts：
// 未知字段忽略、可空字段显式处理（null/undefined 都落 null）、ID 一律十进制 String、越界即
// INVALID_RESPONSE 失败关闭（不伪造、不用假数据补位）。

export type ServiceCategoryView = Readonly<{
  categoryCode: string; categoryName: string; parentCode: string | null
  iconUrl: string | null; description: string | null
  sortNo: number; storeCount: number; serviceCount: number
}>

export type FeaturedRating = Readonly<{ score: string | null; count: number; available: boolean }>
export type FeaturedServiceItem = Readonly<{
  serviceId: string; serviceName: string; categoryCode: string; categoryName: string
  salePrice: string; listPrice: string | null; durationMinutes: number
  fulfillmentType: 'IN_STORE' | 'PICKUP_DELIVERY'; soldCount: number | null
}>
export type FeaturedPromotion = Readonly<{
  source: 'MERCHANT_PROMOTION' | 'CAMPAIGN'; offerTag: string | null
  offerTitle: string | null; offerCopy: string | null; campaignId: string | null
}>
export type FeaturedStoreCard = Readonly<{
  storeId: string; merchantId: string; storeName: string; merchantName: string
  cityCode: string; address: string; avatarUrl: string | null; avatarUrlExpiresAt: string | null
  intro: string | null; categoryCode: string; categoryName: string
  rating: FeaturedRating; monthlySold: number | null; tags: readonly string[]
  services: readonly FeaturedServiceItem[]; promotion: FeaturedPromotion | null
}>
export type FeaturedStorePage = Readonly<{
  items: readonly FeaturedStoreCard[]; page: number; pageSize: number; total: number
}>

const fail = (): never => { throw new Error('INVALID_RESPONSE') }
const text = (value: unknown, max: number): string =>
  typeof value === 'string' && value.length > 0 && Array.from(value).length <= max ? value : fail()
const nullableText = (value: unknown, max: number): string | null =>
  value === null || value === undefined ? null
    : typeof value === 'string' && Array.from(value).length <= max ? value : fail()
const instant = (value: unknown): string => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})(?![\s\S])/.test(value) || !Number.isFinite(Date.parse(value))) fail()
  return value as string
}
const nullableInstant = (value: unknown): string | null => value === null || value === undefined ? null : instant(value)
const nullableId = (value: unknown): string | null => value === null || value === undefined ? null : id(value)
const integer = (value: unknown, min: number, max: number): number =>
  typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max ? value : fail()
const nullableInteger = (value: unknown, min: number, max: number): number | null =>
  value === null || value === undefined ? null : integer(value, min, max)
// 74 §2.1 分类编号词法：2..32，^[A-Z][A-Z0-9_]{1,31}$，创建后不可改。
const categoryCode = (value: unknown): string =>
  typeof value === 'string' && /^[A-Z][A-Z0-9_]{1,31}(?![\s\S])/.test(value) ? value : fail()
const nullableCategoryCode = (value: unknown): string | null =>
  value === null || value === undefined ? null : categoryCode(value)
// DECIMAL(18,2) 线上字符串，两位小数（与服务域 price 同口径）。
const price = (value: unknown): string =>
  typeof value === 'string' && /^\d{1,16}\.\d{2}(?![\s\S])/.test(value) ? value : fail()
const nullablePrice = (value: unknown): string | null =>
  value === null || value === undefined ? null : price(value)
// 评分 0.0..5.0 一位小数字符串；null = 评价数 <5 或聚合不可用（74 §5.4/§6.1）。
const score = (value: unknown): string | null =>
  value === null || value === undefined ? null
    : typeof value === 'string' && /^(?:[0-4]\.\d|5\.0)(?![\s\S])/.test(value) ? value : fail()

/** GET /api/v1/c/service-categories item（74 §3.1）：只含启用且有可用商家的分类。 */
function categoryOf(value: unknown): ServiceCategoryView {
  const v = object(value)
  return {
    categoryCode: categoryCode(v.categoryCode), categoryName: text(v.categoryName, 20),
    parentCode: nullableCategoryCode(v.parentCode), iconUrl: nullableText(v.iconUrl, 512),
    description: nullableText(v.description, 200),
    sortNo: integer(v.sortNo, 0, 9999),
    storeCount: integer(v.storeCount, 0, 1000000),
    serviceCount: integer(v.serviceCount, 0, 1000000),
  }
}

export function decodeServiceCategories(value: unknown): readonly ServiceCategoryView[] {
  const v = object(value)
  if (!Array.isArray(v.items) || v.items.length > 100) fail()
  const items = v.items.map(categoryOf)
  // §3.1 服务端按 sortNo ASC, categoryId ASC 排序；线上可见部分 sortNo 必须非降。
  for (let index = 1; index < items.length; index++) {
    if (items[index]!.sortNo < items[index - 1]!.sortNo) fail()
  }
  return items
}

function ratingOf(value: unknown): FeaturedRating {
  const v = object(value)
  // available=false 只表示聚合不可用（隐藏评分），不得用 0 分冒充——score=null 照原样保留。
  return { score: score(v.score), count: integer(v.count, 0, 1000000), available: v.available === true }
}

function featuredServiceOf(value: unknown): FeaturedServiceItem {
  const v = object(value)
  return {
    serviceId: id(v.serviceId), serviceName: text(v.serviceName, 50),
    categoryCode: categoryCode(v.categoryCode), categoryName: text(v.categoryName, 20),
    salePrice: price(v.salePrice), listPrice: nullablePrice(v.listPrice),
    durationMinutes: integer(v.durationMinutes, 1, 10080),
    fulfillmentType: v.fulfillmentType === 'IN_STORE' || v.fulfillmentType === 'PICKUP_DELIVERY'
      ? v.fulfillmentType : fail(),
    soldCount: nullableInteger(v.soldCount, 0, 1000000),
  }
}

function featuredPromotionOf(value: unknown): FeaturedPromotion | null {
  if (value === null || value === undefined) return null
  const v = object(value)
  return {
    source: v.source === 'CAMPAIGN' ? 'CAMPAIGN' : 'MERCHANT_PROMOTION',
    offerTag: nullableText(v.offerTag, 32), offerTitle: nullableText(v.offerTitle, 100),
    offerCopy: nullableText(v.offerCopy, 100), campaignId: nullableId(v.campaignId),
  }
}

function featuredStoreOf(value: unknown): FeaturedStoreCard {
  const v = object(value)
  const avatarUrl = nullableText(v.avatarUrl, 512)
  const avatarUrlExpiresAt = nullableInstant(v.avatarUrlExpiresAt)
  // 74 §3.4：avatarUrl 与 avatarUrlExpiresAt 同时为 null 或同时有值（私有签名素材）。
  if ((avatarUrl === null) !== (avatarUrlExpiresAt === null)) fail()
  if (!Array.isArray(v.tags) || v.tags.length > 3) fail()
  if (!Array.isArray(v.services) || v.services.length > 2) fail()
  return {
    storeId: id(v.storeId), merchantId: id(v.merchantId),
    storeName: text(v.storeName, 128), merchantName: text(v.merchantName, 128),
    cityCode: text(v.cityCode, 32), address: text(v.address, 255),
    avatarUrl, avatarUrlExpiresAt, intro: nullableText(v.intro, 500),
    categoryCode: categoryCode(v.categoryCode), categoryName: text(v.categoryName, 20),
    rating: ratingOf(v.rating), monthlySold: nullableInteger(v.monthlySold, 0, 1000000),
    tags: v.tags.map((tag: unknown) => text(tag, 20)),
    services: v.services.map(featuredServiceOf),
    promotion: featuredPromotionOf(v.promotion),
  }
}

/** GET /api/v1/c/stores/featured（74 §3.4）：评分↓、月售↓、storeId↑；单类事实缺失按 §6 降级。 */
export function decodeFeaturedStores(value: unknown): FeaturedStorePage {
  const v = object(value)
  if (!Array.isArray(v.items) || v.items.length > 20) fail()
  return {
    items: v.items.map(featuredStoreOf), page: integer(v.page, 1, 10000),
    pageSize: integer(v.pageSize, 1, 20), total: integer(v.total, 0, 1000000),
  }
}

export type FeaturedStoresQuery = Readonly<{
  cityCode?: string; categoryCode?: string; page?: number; pageSize?: number
}>

/** 服务目录页两条匿名读侧（74 §3.1/§3.4）；读取失败由页面整块降级，不阻塞门店目录。 */
export class ServiceCatalogRepository {
  constructor(private api: ConsumerApi) {}

  serviceCategories(cityCode?: string) {
    return this.api.anonymousRequest({
      path: '/api/v1/c/service-categories', method: 'GET',
      data: cityCode ? { cityCode } : undefined,
    }, decodeServiceCategories)
  }

  featuredStores(query: FeaturedStoresQuery = {}) {
    const data: Record<string, unknown> = { page: query.page ?? 1, pageSize: query.pageSize ?? 10 }
    if (query.cityCode) data.cityCode = query.cityCode
    if (query.categoryCode) data.categoryCode = query.categoryCode
    return this.api.anonymousRequest({ path: '/api/v1/c/stores/featured', method: 'GET', data }, decodeFeaturedStores)
  }
}

// CONTRACT MOCK（preview=1 设计验收通道）：74 §2 初始字典 11 行（code/名称/sortNo 为契约冻结
// 值），不联网。图标素材未到位是已知状态（G-03/D-01），iconUrl 全 null → 页面走占位，不伪造图。
export const previewServiceCategories: readonly ServiceCategoryView[] = [
  { categoryCode: 'PET_GROOMING', categoryName: '宠物美容', parentCode: null, iconUrl: null, description: null, sortNo: 10, storeCount: 2, serviceCount: 2 },
  { categoryCode: 'DOG_WALKING', categoryName: '遛狗陪护', parentCode: null, iconUrl: null, description: null, sortNo: 20, storeCount: 1, serviceCount: 1 },
  { categoryCode: 'PET_BOARDING', categoryName: '宠物寄养', parentCode: null, iconUrl: null, description: null, sortNo: 30, storeCount: 1, serviceCount: 1 },
  { categoryCode: 'PET_TRAINING', categoryName: '宠物训练', parentCode: null, iconUrl: null, description: null, sortNo: 40, storeCount: 1, serviceCount: 1 },
  { categoryCode: 'HOME_FEEDING', categoryName: '上门喂养', parentCode: null, iconUrl: null, description: null, sortNo: 50, storeCount: 1, serviceCount: 1 },
  { categoryCode: 'VET_ASSISTANT', categoryName: '兽医助理', parentCode: null, iconUrl: null, description: null, sortNo: 60, storeCount: 1, serviceCount: 1 },
  { categoryCode: 'SMALL_PET_BOARDING', categoryName: '小宠寄养', parentCode: null, iconUrl: null, description: null, sortNo: 70, storeCount: 1, serviceCount: 1 },
  { categoryCode: 'EXOTIC_HOME_FEEDING', categoryName: '异宠上门喂养', parentCode: null, iconUrl: null, description: null, sortNo: 80, storeCount: 1, serviceCount: 1 },
  { categoryCode: 'EXOTIC_HEALTH_CHECK', categoryName: '异宠健康检查', parentCode: null, iconUrl: null, description: null, sortNo: 90, storeCount: 1, serviceCount: 1 },
  { categoryCode: 'PLANT_CARE', categoryName: '绿植养护', parentCode: null, iconUrl: null, description: null, sortNo: 100, storeCount: 1, serviceCount: 1 },
  { categoryCode: 'PLANT_BOARDING', categoryName: '植物代养', parentCode: null, iconUrl: null, description: null, sortNo: 110, storeCount: 1, serviceCount: 1 },
]

export type ServiceCatalogPreviewDeps = {
  serviceCategories(cityCode?: string): Promise<readonly ServiceCategoryView[]>
}

export class PreviewServiceCatalogRepository implements ServiceCatalogPreviewDeps {
  async serviceCategories(_cityCode?: string): Promise<readonly ServiceCategoryView[]> {
    return previewServiceCategories.map(category => ({ ...category }))
  }
}
