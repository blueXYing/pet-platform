import test from 'node:test'
import assert from 'node:assert/strict'
import {
  decodeBreedCard, decodeBreedDetail, decodeBreedPage, decodeCategory, highlightParts,
  isBreedNotFound, normalizeKeyword, radarAxes, sanitizeRichText,
  fixtureCards, fixtureDetail,
} from '../baike/model'
import { PreviewBaikeRepository, RealBaikeRepository } from '../baike/repository'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'

// 契约 62 百科读侧模型/仓库（PRD 5.1.4）：严格解码、关键词归一与高亮、富文本白名单、
// 匿名仓库的路径门、preview 仓库的过滤与 404 语义。
const card = fixtureCards[0]!

function ok(data: unknown) {
  return { statusCode: 200, data: { success: true, code: 'SUCCESS', message: 'ok', data } }
}
function memoryStore(): LocalStore {
  const values = new Map<string, unknown>()
  return { get: key => values.get(key), set: (key, value) => values.set(key, value), remove: key => { values.delete(key) } }
}

test('baike decoders enforce the category dictionary and breed card shape', () => {
  const decoded = decodeCategory({ categoryCode: 'DOG', categoryName: '狗狗', sortOrder: 1 })
  assert.equal(decoded.categoryName, '狗狗')
  assert.throws(() => decodeCategory({ categoryCode: 'FISH', categoryName: '鱼', sortOrder: 1 }), /INVALID_RESPONSE/)
  assert.throws(() => decodeCategory({ categoryCode: 'DOG', categoryName: '狗狗' }), /INVALID_RESPONSE/)

  const cardDecoded = decodeBreedCard(JSON.parse(JSON.stringify(card)))
  assert.equal(cardDecoded.breedId, '711001')
  assert.deepEqual(cardDecoded.aliases, ['泰迪', '迷你/玩具贵宾'])
  for (const mutate of [
    (v: Record<string, unknown>) => { v.breedId = '0711001' },
    (v: Record<string, unknown>) => { v.extra = 1 },
    (v: Record<string, unknown>) => { v.coverUrl = 3 },
    (v: Record<string, unknown>) => { v.aliases = '泰迪' },
    (v: Record<string, unknown>) => { v.categoryCode = 'FISH' },
  ]) {
    const value = JSON.parse(JSON.stringify(card)) as Record<string, unknown>
    mutate(value)
    assert.throws(() => decodeBreedCard(value), /INVALID_RESPONSE/)
  }
  assert.doesNotThrow(() => decodeBreedCard({ ...JSON.parse(JSON.stringify(card)), coverUrl: null }))
})

test('baike detail decoder carries radar bounds, status and related cards', () => {
  const decoded = decodeBreedDetail(JSON.parse(JSON.stringify(fixtureDetail)))
  assert.equal(decoded.status, 'PUBLISHED')
  assert.equal(decoded.radar?.companion, 5)
  assert.equal(decoded.related.length, 2)
  assert.throws(() => decodeBreedDetail({ ...JSON.parse(JSON.stringify(fixtureDetail)), radar: { ...fixtureDetail.radar!, companion: 6 } }), /INVALID_RESPONSE/)
  assert.throws(() => decodeBreedDetail({ ...JSON.parse(JSON.stringify(fixtureDetail)), status: 'OFFLINE' }), /INVALID_RESPONSE/)
  assert.throws(() => decodeBreedDetail({ ...JSON.parse(JSON.stringify(fixtureDetail)), extra: 1 }), /INVALID_RESPONSE/)
  assert.doesNotThrow(() => decodeBreedDetail({ ...JSON.parse(JSON.stringify(fixtureDetail)), radar: null, related: [] }))

  const page = decodeBreedPage({ items: [JSON.parse(JSON.stringify(card))], page: 1, pageSize: 20, total: 8 })
  assert.equal(page.total, 8)
  assert.throws(() => decodeBreedPage({ items: [], page: 1, pageSize: 20 }), /INVALID_RESPONSE/)
})

test('baike keyword helper applies the PRD 40-codepoint cap after trimming', () => {
  assert.equal(normalizeKeyword('  泰迪  '), '泰迪')
  assert.throws(() => normalizeKeyword('长'.repeat(41)), /KEYWORD_TOO_LONG/)
  assert.equal(normalizeKeyword('长'.repeat(40)), '长'.repeat(40))
})

test('highlight helper splits hits case-insensitively and passes through misses', () => {
  assert.deepEqual(highlightParts('贵宾犬', '贵宾'), [{ text: '贵宾', hit: true }, { text: '犬', hit: false }])
  assert.deepEqual(highlightParts('ABC', 'b'), [{ text: 'A', hit: false }, { text: 'B', hit: true }, { text: 'C', hit: false }])
  assert.deepEqual(highlightParts('贵宾犬', ''), [{ text: '贵宾犬', hit: false }])
  assert.deepEqual(highlightParts('贵宾犬', '猫'), [{ text: '贵宾犬', hit: false }])
})

test('rich text sanitizer keeps only the contract whitelist', () => {
  const html = '<h3>起源历史</h3><p>正文<strong>重点</strong></p><script>alert(1)</script><img src=x onerror=1>'
  const clean = sanitizeRichText(html)
  assert.match(clean, /<h3>起源历史<\/h3>/)
  assert.match(clean, /<strong>重点<\/strong>/)
  assert.doesNotMatch(clean, /<script/)
  assert.doesNotMatch(clean, /<img/)
  assert.equal(sanitizeRichText(null), '')
})

test('breed-not-found mapping and radar axis order hold', () => {
  assert.equal(isBreedNotFound({ code: 'BREED_NOT_FOUND' }), true)
  assert.equal(isBreedNotFound(new Error('x')), false)
  assert.deepEqual(radarAxes.map(axis => axis.label), ['陪伴需求', '掉毛程度', '聪明度', '易养度', '运动需求'])
})

test('preview repository filters by category, tag and keyword with alias matching', async () => {
  const repo = new PreviewBaikeRepository(fixtureCards)
  const dogs = await repo.page({ categoryCode: 'DOG' })
  assert.equal(dogs.total, 3)
  const aliasHit = await repo.page({ categoryCode: 'DOG', keyword: '泰迪' })
  assert.deepEqual(aliasHit.items.map(breed => breed.name), ['贵宾犬'])
  const tagHit = await repo.page({ categoryCode: 'DOG', tag: '少掉毛' })
  assert.deepEqual(tagHit.items.map(breed => breed.name), ['贵宾犬', '比熊犬'])
  const empty = await repo.page({ categoryCode: 'CAT' })
  assert.equal(empty.total, 0)
  const categories = await repo.categories()
  assert.equal(categories.length, 5)
})

test('preview repository paginates without duplicates and surfaces missing details', async () => {
  const repo = new PreviewBaikeRepository(fixtureCards)
  const first = await repo.page({ categoryCode: 'DOG', page: 1, pageSize: 2 })
  const second = await repo.page({ categoryCode: 'DOG', page: 2, pageSize: 2 })
  assert.equal(first.items.length, 2)
  assert.equal(second.items.length, 1)
  await assert.rejects(() => repo.detail('999'), /BREED_NOT_FOUND/)
  const detail = await repo.detail(fixtureDetail.breedId)
  assert.equal(detail.related.length, 2)
})

test('preview load-error scenario fails the first load exactly once', async () => {
  const repo = new PreviewBaikeRepository(fixtureCards, 'load-error')
  await assert.rejects(() => repo.page({ categoryCode: 'DOG' }), /PREVIEW_LOAD_FAILED/)
  const page = await repo.page({ categoryCode: 'DOG' })
  assert.equal(page.total, 3)
})

test('real repository validates breed ids and the anonymous GET path gate stays closed', async () => {
  const seen: { method: string; path: string; data?: Record<string, unknown> }[] = []
  const api = new ConsumerApi(async request => {
    seen.push({ method: request.method, path: request.path, data: request.data })
    if (request.path === '/api/v1/c/encyclopedia/categories') return ok({ items: [] })
    if (request.path === '/api/v1/c/encyclopedia/breeds') return ok({ items: [], page: 1, pageSize: 20, total: 0 })
    if (request.path === '/api/v1/c/encyclopedia/breeds/711001') return ok(JSON.parse(JSON.stringify(fixtureDetail)))
    return { statusCode: 404, data: { success: false, code: 'BREED_NOT_FOUND', message: 'x', data: null } }
  }, memoryStore(), async () => '00000000-0000-4000-8000-000000000000')
  const repo = new RealBaikeRepository(api)
  await assert.rejects(() => repo.detail('abc'), /INVALID_RESPONSE/)
  await assert.rejects(() => api.anonymousRequest({ method: 'POST', path: '/api/v1/c/encyclopedia/breeds' }, () => null), /INVALID_PATH/)
  await assert.rejects(() => api.anonymousRequest({ method: 'GET', path: '/api/v1/c/encyclopedia/breeds/0711001' }, () => null), /INVALID_PATH/)

  await repo.categories()
  await repo.page({ categoryCode: 'DOG', keyword: ' 泰迪 ', tag: null, page: 1, pageSize: 20 })
  const detail = await repo.detail('711001')
  assert.equal(seen.length, 3)
  assert.equal(seen[0]!.path, '/api/v1/c/encyclopedia/categories')
  // Trimmed keyword rides the query; page defaults carry explicitly.
  assert.deepEqual(seen[1]!.data, { categoryCode: 'DOG', keyword: '泰迪', page: 1, pageSize: 20 })
  assert.equal(detail.name, '贵宾犬')
})
