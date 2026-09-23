import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import {
  PreviewStoreRepository, StoreMockError, decodeCityList, decodeStore, decodeStorePage,
  fixtureCities, fixtureInvisibleStoreId, fixtureStores,
} from '../store/model'
import { RealStoreRepository, isStoreNotFound } from '../store/repository'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'

async function rejects(promise: Promise<unknown>): Promise<StoreMockError> {
  try { await promise; throw new Error('NO_THROW') } catch (error) { return error as StoreMockError }
}

test('store decoders enforce the nine-field projection and the STR-D4 order', () => {
  const store = fixtureStores[0]!
  assert.equal(decodeStore(JSON.parse(JSON.stringify(store))).storeName, store.storeName)
  for (const mutate of [
    (v: Record<string, unknown>) => { v.rating = '4.9' },               // no extra fields
    (v: Record<string, unknown>) => { delete v.cityCode },
    (v: Record<string, unknown>) => { v.storeId = '0' },
    (v: Record<string, unknown>) => { v.longitude = '200.1' },
    (v: Record<string, unknown>) => { v.latitude = '30.12345678' },      // >7 decimals
    (v: Record<string, unknown>) => { v.cityCode = 'Chengdu' },
    (v: Record<string, unknown>) => { v.address = '' },
  ]) {
    const value = JSON.parse(JSON.stringify(store)) as Record<string, unknown>
    mutate(value)
    assert.throws(() => decodeStore(value), /INVALID_RESPONSE/)
  }
  const ordered = decodeStorePage({ items: fixtureStores.map(item => JSON.parse(JSON.stringify(item))), page: 1, pageSize: 20, total: fixtureStores.length })
  assert.equal(ordered.items.length, fixtureStores.length)
  const unordered = [...fixtureStores].reverse()
  assert.throws(() => decodeStorePage({ items: unordered.map(item => JSON.parse(JSON.stringify(item))), page: 1, pageSize: 20, total: unordered.length }), /INVALID_RESPONSE/)
  assert.throws(() => decodeStorePage({ items: [], page: 1, pageSize: 0, total: 0 }), /INVALID_RESPONSE/)
  assert.deepEqual(decodeCityList({ items: [{ cityCode: 'chengdu', cityName: '成都' }] }), [{ cityCode: 'chengdu', cityName: '成都' }])
})

test('mock list scopes by the open-city catalog and paginates in contract order', async () => {
  const repository = new PreviewStoreRepository()
  const all = await repository.list({ page: 1, pageSize: 20 })
  assert.equal(all.total, fixtureStores.length)
  assert.ok(BigInt(all.items[0]!.merchantId) <= BigInt(all.items[1]!.merchantId))
  const chengdu = await repository.list({ city: 'chengdu', page: 1, pageSize: 2 })
  assert.equal(chengdu.items.length, 2)
  assert.equal(chengdu.total, fixtureStores.length)
  const second = await repository.list({ city: 'chengdu', page: 2, pageSize: 2 })
  assert.equal(second.items.length, 1)
  const unknownCity = await rejects(repository.list({ city: 'beijing', page: 1, pageSize: 20 }))
  assert.equal(unknownCity.code, 'COMMON_INVALID_ARGUMENT')
  assert.equal(unknownCity.statusCode, 400)
  assert.equal((await repository.cities()).length, fixtureCities.length)
})

test('detail answers unknown and invisible stores with an indistinguishable 404; 503 fails closed', async () => {
  const repository = new PreviewStoreRepository()
  const found = await repository.detail(fixtureStores[0]!.storeId)
  assert.equal(found.storeName, fixtureStores[0]!.storeName)
  for (const storeId of [fixtureInvisibleStoreId, '7777777777777']) {
    const error = await rejects(repository.detail(storeId))
    assert.equal(error.code, 'STORE_NOT_FOUND')
    assert.equal(error.statusCode, 404)
  }
  assert.ok(isStoreNotFound(await rejects(repository.detail(fixtureInvisibleStoreId))))
  const failing = new PreviewStoreRepository(undefined, 'load-error')
  const failList = await rejects(failing.list({ page: 1, pageSize: 20 }))
  assert.equal(failList.code, 'COMMON_DEPENDENCY_UNAVAILABLE')
  const empty = new PreviewStoreRepository(undefined, 'empty')
  const emptyPage = await empty.list({ city: 'chengdu', page: 1, pageSize: 20 })
  assert.deepEqual({ items: emptyPage.items, total: emptyPage.total }, { items: [], total: 0 })
})

const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', success: true, data } })

test('real store repository reads anonymously with the approved query shape', async () => {
  const seen: { method: string; path: string; data?: Record<string, unknown>; headers: Record<string, string> }[] = []
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, value), remove: key => { values.delete(key) } }
  const api = new ConsumerApi(async request => {
    seen.push({ method: request.method, path: request.path, data: request.data, headers: request.headers })
    if (request.path === '/api/v1/c/stores') return ok({ items: [], page: 1, pageSize: 20, total: 0 })
    if (request.path === '/api/v1/c/stores/957002') return ok(JSON.parse(JSON.stringify(fixtureStores[0])))
    return { statusCode: 404, data: { code: 'STORE_NOT_FOUND', data: null } }
  }, store, async () => randomUUID())
  // No login, consumer workspace absent: anonymous reads must still flow.
  const repository = new RealStoreRepository(api, async () => [{ cityCode: 'chengdu', cityName: '成都' }])
  assert.equal((await repository.cities())[0]!.cityCode, 'chengdu')
  const page = await repository.list({ city: 'chengdu', page: 1, pageSize: 20 })
  assert.equal(page.total, 0)
  const listCall = seen[0]!
  assert.equal(listCall.path, '/api/v1/c/stores')
  assert.equal(listCall.data?.city, 'chengdu')
  assert.equal(listCall.data?.page, 1)
  assert.equal(listCall.headers.Authorization, undefined)
  const detail = await repository.detail('957002')
  assert.equal(detail.storeName, fixtureStores[0]!.storeName)
  const missing = await repository.detail('960002').catch(error => error)
  assert.ok(isStoreNotFound(missing))
})
