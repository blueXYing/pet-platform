import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import {
  HOME_BLOCK_TARGET_HOT_SERVICE, PLACEMENT_POSITION_HOT_SERVICE_TOP,
  decodeHomeBlocks, decodeMerchantDeals, decodePlacements,
} from '../marketing/model'
import {
  PreviewServiceCatalogRepository, ServiceCatalogRepository,
  decodeFeaturedStores, decodeServiceCategories,
} from '../service-catalog/model'
import { PreviewStoreRepository, StoreMockError } from '../store/model'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'

const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', data } })

// ── 74 §3.1 服务分类列表 ──

const category = {
  categoryCode: 'PET_GROOMING', categoryName: '宠物美容', parentCode: null,
  iconUrl: 'https://cdn.example/service-category/grooming.png',
  description: '专业美容师为您的爱宠提供洗澡、剪毛、造型等全方位护理服务',
  sortNo: 10, storeCount: 12, serviceCount: 37,
}

test('category decoder keeps the 74 §3.1 projection, tolerates unknown fields and nullable drops', () => {
  assert.deepEqual(decodeServiceCategories({ items: [category] }), [{ ...category }])
  assert.deepEqual(decodeServiceCategories({ items: [] }), [])
  // 无图/无描述/无父分类的降级行（契约可空字段显式为 null）。
  assert.deepEqual(decodeServiceCategories({
    items: [{ ...category, iconUrl: null, description: null, parentCode: null }],
  })[0]!.iconUrl, null)
  // 未知字段忽略（marketing 解码风格）。
  assert.equal(decodeServiceCategories({ items: [{ ...category, distanceKm: 1.5 }] }).length, 1)
  // 有父分类时必须满足词法。
  assert.equal(decodeServiceCategories({ items: [{ ...category, parentCode: 'PET_GROOMING' }] })[0]!.parentCode, 'PET_GROOMING')
})

test('category decoder fails closed on missing fields, bad lexicon, bounds and unordered sortNo', () => {
  for (const mutate of [
    (v: Record<string, unknown>) => { delete v.categoryCode },
    (v: Record<string, unknown>) => { delete v.sortNo },
    (v: Record<string, unknown>) => { delete v.storeCount },
    (v: Record<string, unknown>) => { v.categoryCode = 'pet_grooming' },   // 词法：须大写开头
    (v: Record<string, unknown>) => { v.categoryCode = 'A' },             // 2..32
    (v: Record<string, unknown>) => { v.categoryCode = 'PET GROOMING' },  // 仅大写字母数字下划线
    (v: Record<string, unknown>) => { v.categoryName = '' },
    (v: Record<string, unknown>) => { v.parentCode = 'grooming' },
    (v: Record<string, unknown>) => { v.iconUrl = 123 },
    (v: Record<string, unknown>) => { v.sortNo = -1 },
    (v: Record<string, unknown>) => { v.sortNo = 10000 },
    (v: Record<string, unknown>) => { v.sortNo = 1.5 },
    (v: Record<string, unknown>) => { v.storeCount = -1 },
    (v: Record<string, unknown>) => { v.serviceCount = '37' },
  ]) {
    const value = JSON.parse(JSON.stringify(category)) as Record<string, unknown>
    mutate(value)
    assert.throws(() => decodeServiceCategories({ items: [value] }), /INVALID_RESPONSE/)
  }
  // 服务端排序为 sortNo ASC（categoryId 决胜）；线上 sortNo 降序 = 契约破坏。
  const late = { ...category, categoryCode: 'DOG_WALKING', sortNo: 5 }
  assert.throws(() => decodeServiceCategories({ items: [category, late] }), /INVALID_RESPONSE/)
  assert.throws(() => decodeServiceCategories({ items: category }), /INVALID_RESPONSE/)      // items 非数组
  assert.throws(() => decodeServiceCategories({ items: new Array(101).fill(category) }), /INVALID_RESPONSE/)
})

// ── 74 §3.4 优质商家聚合卡 ──

const featuredService = {
  serviceId: '710401', serviceName: '专业美容·小型犬', categoryCode: 'PET_GROOMING', categoryName: '宠物美容',
  salePrice: '80.00', listPrice: '100.00', durationMinutes: 60, fulfillmentType: 'IN_STORE', soldCount: 328,
}
const featured = {
  storeId: '710302', merchantId: '710301', storeName: '萌宠之家宠物店', merchantName: '萌宠之家',
  cityCode: '310100', address: '上海市浦东新区世纪大道 100 号',
  avatarUrl: 'https://cdn.example/store/710302/avatar.jpg', avatarUrlExpiresAt: '2026-11-30T12:00:00Z',
  intro: '一站式宠物生活馆，提供专业美容、家庭寄养、进口零食与玩具。',
  categoryCode: 'PET_GROOMING', categoryName: '宠物美容',
  rating: { score: '4.9', count: 328, available: true },
  monthlySold: 328, tags: ['宠物美容'], services: [featuredService],
  promotion: {
    source: 'MERCHANT_PROMOTION', offerTag: '5折', offerTitle: '全场美容套餐5折起',
    offerCopy: '专业美容师持证上岗', campaignId: null,
  },
}

test('featured decoder keeps scores, sold facts and the two-service cap with the page envelope', () => {
  const page = decodeFeaturedStores({ items: [featured], page: 1, pageSize: 10, total: 23 })
  assert.equal(page.total, 23)
  const card = page.items[0]!
  assert.equal(card.rating.score, '4.9')
  assert.equal(card.monthlySold, 328)
  assert.equal(card.services[0]!.salePrice, '80.00')
  assert.equal(card.promotion!.offerTag, '5折')
  assert.deepEqual(card.tags, ['宠物美容'])
  // 未知字段忽略。
  assert.equal(decodeFeaturedStores({ items: [{ ...featured, distance: '1.5km' }], page: 1, pageSize: 10, total: 1 }).items.length, 1)
  // 两家店补齐分页边界。
  assert.equal(decodeFeaturedStores({ items: [featured, featured], page: 1, pageSize: 20, total: 2 }).items.length, 2)
})

test('featured decoder degrades per 74 §6: unavailable rating, null sold facts, no promotion/avatar', () => {
  const degraded = decodeFeaturedStores({
    items: [{
      ...featured,
      avatarUrl: null, avatarUrlExpiresAt: null, intro: null,
      rating: { score: null, count: 0, available: false },
      monthlySold: null, tags: [],
      services: [{ ...featuredService, listPrice: null, soldCount: null, fulfillmentType: 'PICKUP_DELIVERY' }],
      promotion: null,
    }],
    page: 1, pageSize: 10, total: 1,
  }).items[0]!
  assert.equal(degraded.rating.available, false)
  assert.equal(degraded.rating.score, null)
  assert.equal(degraded.monthlySold, null)
  assert.equal(degraded.services[0]!.soldCount, null)
  assert.equal(degraded.promotion, null)
  // 评价数 <5：score=null 且 available=true（前端显示「暂无评分」）。
  const few = decodeFeaturedStores({
    items: [{ ...featured, rating: { score: null, count: 3, available: true } }],
    page: 1, pageSize: 10, total: 1,
  }).items[0]!
  assert.equal(few.rating.count, 3)
  assert.equal(few.rating.score, null)
})

test('featured decoder fails closed on missing fields, pairing, caps and wire formats', () => {
  for (const mutate of [
    (v: Record<string, unknown>) => { delete v.storeId },
    (v: Record<string, unknown>) => { delete v.rating },
    (v: Record<string, unknown>) => { delete v.services },
    (v: Record<string, unknown>) => { delete v.cityCode },
    (v: Record<string, unknown>) => { v.storeId = 710302 },                  // ID 必须十进制 String
    (v: Record<string, unknown>) => { delete v.avatarUrl },                   // 签名素材必须成对
    (v: Record<string, unknown>) => { v.avatarUrlExpiresAt = null },
    (v: Record<string, unknown>) => { v.avatarUrlExpiresAt = '2026-11-30 12:00:00Z' },
    (v: Record<string, unknown>) => { v.services = [featuredService, featuredService, featuredService] },
    (v: Record<string, unknown>) => { v.tags = ['宠物美容', '宠物寄养', '宠物训练', '遛狗陪护'] },
    (v: Record<string, unknown>) => { v.rating = { score: '4.95', count: 1, available: true } }, // 非一位小数
    (v: Record<string, unknown>) => { v.rating = { score: '5.1', count: 1, available: true } },
    (v: Record<string, unknown>) => { v.rating = { score: 4.9, count: 1, available: true } },
    (v: Record<string, unknown>) => { v.rating = { score: null, count: -1, available: true } },
    (v: Record<string, unknown>) => { v.monthlySold = -1 },
  ]) {
    const value = JSON.parse(JSON.stringify(featured)) as Record<string, unknown>
    mutate(value)
    assert.throws(() => decodeFeaturedStores({ items: [value], page: 1, pageSize: 10, total: 1 }), /INVALID_RESPONSE/)
  }
  const badService = (mutate: (v: Record<string, unknown>) => void) => {
    const service = JSON.parse(JSON.stringify(featuredService)) as Record<string, unknown>
    mutate(service)
    return decodeFeaturedStores({ items: [{ ...featured, services: [service] }], page: 1, pageSize: 10, total: 1 })
  }
  for (const mutate of [
    (v: Record<string, unknown>) => { v.salePrice = '80' },
    (v: Record<string, unknown>) => { v.salePrice = 80 },
    (v: Record<string, unknown>) => { v.listPrice = '100.000' },
    (v: Record<string, unknown>) => { v.durationMinutes = 0 },
    (v: Record<string, unknown>) => { v.durationMinutes = 10081 },
    (v: Record<string, unknown>) => { v.fulfillmentType = 'HOME' },
    (v: Record<string, unknown>) => { v.serviceId = '0' },
    (v: Record<string, unknown>) => { v.soldCount = -1 },
    (v: Record<string, unknown>) => { v.soldCount = '328' },
  ]) assert.throws(() => badService(mutate), /INVALID_RESPONSE/)
  // 可空字段显式降级：soldCount 缺省/解为 null（marketing 可空口径），不视为破坏。
  assert.equal(badService(v => { delete v.soldCount }).items[0]!.services[0]!.soldCount, null)
  // 分页包络：74 §3.4 pageSize 1..20。
  assert.throws(() => decodeFeaturedStores({ items: [], page: 1, pageSize: 21, total: 0 }), /INVALID_RESPONSE/)
  assert.throws(() => decodeFeaturedStores({ items: [], page: 0, pageSize: 10, total: 0 }), /INVALID_RESPONSE/)
})

// ── 74 §3.5 / 71 v0.3 §3.4 商家钜惠图片增量 ──

const deal = {
  storeId: '957002', storeName: '萌宠之家宠物店', merchantName: '萌宠之家', cityCode: 'chengdu',
  address: '四川省成都市锦江区春熙路100号', offerTag: '5折', offerTitle: '全场美容套餐5折起',
  offerCopy: '专业美容师持证上岗', source: 'MERCHANT_PROMOTION', campaignId: null,
  promotion: { level: 5, title: '全场美容套餐5折起', text: '专业美容师持证上岗' },
}

test('merchant deals carry the image pair; legacy rows without it stay decodable as null', () => {
  const withImage = decodeMerchantDeals({
    items: [{ ...deal, imageUrl: 'https://cdn.example/store/957002/avatar.jpg', imageUrlExpiresAt: '2026-11-30T12:00:00Z' }],
  })[0]!
  assert.equal(withImage.imageUrl, 'https://cdn.example/store/957002/avatar.jpg')
  assert.equal(withImage.imageUrlExpiresAt, '2026-11-30T12:00:00Z')
  // 公开素材 URL：imageUrlExpiresAt 可为 null（71 v0.3 §3.4），不做跨字段绑定。
  const publicOnly = decodeMerchantDeals({ items: [{ ...deal, imageUrl: 'https://cdn.example/p.jpg' }] })[0]!
  assert.equal(publicOnly.imageUrl, 'https://cdn.example/p.jpg')
  assert.equal(publicOnly.imageUrlExpiresAt, null)
  // 既有响应（无 imageUrl 键）与显式 null 都解为 null。
  assert.equal(decodeMerchantDeals({ items: [deal] })[0]!.imageUrl, null)
  assert.equal(decodeMerchantDeals({ items: [{ ...deal, imageUrl: null, imageUrlExpiresAt: null }] })[0]!.imageUrl, null)
  // 非法过期时间 / 超长 URL 失败关闭。
  assert.throws(() => decodeMerchantDeals({ items: [{ ...deal, imageUrl: 'https://x/p.jpg', imageUrlExpiresAt: '2026-11-30 12:00:00Z' }] }), /INVALID_RESPONSE/)
  assert.throws(() => decodeMerchantDeals({ items: [{ ...deal, imageUrl: `https://x/${'a'.repeat(512)}.jpg` }] }), /INVALID_RESPONSE/)
})

// ── 71 v0.3 §3.7 HOT_SERVICE_TOP 投放位 / §10 HOT_SERVICE 首页内容位 ──

const placement = {
  placementId: '880001', positionCode: 'HOT_SERVICE_TOP', campaignId: '880000',
  title: '热门服务', subtitle: null, tagCode: null, tagName: '热门',
  coverUrl: 'https://cdn.example/banner.png', templateCode: null,
  jumpType: 'CAMPAIGN', jumpParams: '{"campaignId":"880000"}',
  startAt: '2026-10-30T00:00:00Z', endAt: '2026-11-30T00:00:00Z',
}
const hotBlock = {
  blockId: '890001', positionCode: 'HOME_SERVICE', templateCode: 'HOT_SERVICE_FEATURE',
  title: '热门服务', subtitle: null, tagName: null, imageUrl: null,
  targetType: 'HOT_SERVICE', targetValue: null, cityCode: null,
  sponsorMerchantId: null, sponsorStoreId: null, sortWeight: 10,
}

test('placement and home-block decoders accept the v0.3 enum values and nullable landing', () => {
  const decodedPlacement = decodePlacements({ items: [placement] })[0]!
  assert.equal(decodedPlacement.positionCode, PLACEMENT_POSITION_HOT_SERVICE_TOP)
  const decodedBlock = decodeHomeBlocks({ items: [hotBlock] })[0]!
  assert.equal(decodedBlock.targetType, HOME_BLOCK_TARGET_HOT_SERVICE)
  assert.equal(decodedBlock.targetValue, null) // HOT_SERVICE 允许 targetValue=null（71 v0.3 §10）
  // 未知枚举不在这里收口（positionCode/targetType 沿通用 String 投影），但缺字段仍失败。
  assert.throws(() => decodePlacements({ items: [{ ...placement, title: null }] }), /INVALID_RESPONSE/)
  assert.throws(() => decodeHomeBlocks({ items: [{ ...hotBlock, title: '' }] }), /INVALID_RESPONSE/)
})

// ── 真实/预览仓储接线 ──

test('real service-catalog repository reads the two anonymous routes with the contract query shape', async () => {
  const seen: { method: string; path: string; data?: Record<string, unknown>; headers: Record<string, string> }[] = []
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, value), remove: key => { values.delete(key) } }
  const api = new ConsumerApi(async request => {
    seen.push({ method: request.method, path: request.path, data: request.data, headers: request.headers })
    if (request.path === '/api/v1/c/service-categories') return ok({ items: [category] })
    if (request.path === '/api/v1/c/stores/featured') return ok({ items: [featured], page: 1, pageSize: 10, total: 1 })
    return { statusCode: 404, data: { code: 'COMMON_NOT_FOUND', data: null } }
  }, store, async () => randomUUID())
  const repository = new ServiceCatalogRepository(api)
  const categories = await repository.serviceCategories('chengdu')
  assert.equal(seen[0]!.path, '/api/v1/c/service-categories')
  assert.equal(seen[0]!.data?.cityCode, 'chengdu')
  assert.equal(seen[0]!.headers.Authorization, undefined)
  assert.equal(categories[0]!.categoryCode, 'PET_GROOMING')
  await repository.serviceCategories()
  assert.equal(seen[1]!.data?.cityCode, undefined)
  const page = await repository.featuredStores({ cityCode: 'chengdu', categoryCode: 'PET_GROOMING', page: 2, pageSize: 10 })
  const featuredCall = seen[2]!
  assert.equal(featuredCall.path, '/api/v1/c/stores/featured')
  assert.equal(featuredCall.data?.cityCode, 'chengdu')
  assert.equal(featuredCall.data?.categoryCode, 'PET_GROOMING')
  assert.equal(featuredCall.data?.page, 2)
  assert.equal(featuredCall.data?.pageSize, 10)
  assert.equal(page.items[0]!.storeName, '萌宠之家宠物店')
})

test('preview catalog ships the 11 contract dictionary rows without fabricated icons', async () => {
  const rows = await new PreviewServiceCatalogRepository().serviceCategories()
  assert.equal(rows.length, 11)
  assert.ok(rows.every(row => row.iconUrl === null))
  assert.ok(rows.every((row, index) => index === 0 || row.sortNo > rows[index - 1]!.sortNo))
  assert.equal(new Set(rows.map(row => row.categoryCode)).size, 11)
  assert.equal(rows[0]!.categoryCode, 'PET_GROOMING')
  assert.equal(rows[10]!.categoryCode, 'PLANT_BOARDING')
})

async function rejectsMock(promise: Promise<unknown>): Promise<StoreMockError> {
  try { await promise; throw new Error('NO_THROW') } catch (error) { return error as StoreMockError }
}

test('preview store directory honors the categoryCode filter and its 400/empty-page semantics', async () => {
  const repository = new PreviewStoreRepository()
  const grooming = await repository.list({ categoryCode: 'PET_GROOMING', page: 1, pageSize: 20 })
  assert.deepEqual(grooming.items.map(store => store.storeId), ['957002'])
  const plants = await repository.list({ categoryCode: 'PLANT_CARE', page: 1, pageSize: 20 })
  assert.deepEqual(plants.items.map(store => store.storeId), ['959002'])
  // keyword 与 categoryCode 按 AND 组合。
  const combined = await repository.list({ categoryCode: 'PET_GROOMING', keyword: '萌宠之家', page: 1, pageSize: 20 })
  assert.equal(combined.items.length, 1)
  const combinedMiss = await repository.list({ categoryCode: 'PLANT_CARE', keyword: '萌宠之家', page: 1, pageSize: 20 })
  assert.deepEqual({ items: combinedMiss.items, total: combinedMiss.total }, { items: [], total: 0 })
  // 词法合法但无供给 = 200 空页；词法非法 = 400。
  const unsupplied = await repository.list({ categoryCode: 'DOG_WALKING', page: 1, pageSize: 20 })
  assert.equal(unsupplied.total, 0)
  const invalid = await rejectsMock(repository.list({ categoryCode: 'pet', page: 1, pageSize: 20 }))
  assert.equal(invalid.code, 'COMMON_INVALID_ARGUMENT')
  assert.equal(invalid.statusCode, 400)
})
