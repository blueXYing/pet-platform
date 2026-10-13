import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import {
  decodeFavoriteAddReceipt, decodeFavoriteCancelReceipt, decodeFavoritePage, decodeFavoriteRow,
  decodeFavoriteStatus, decodeFootprintDeleteReceipt, decodeFootprintPage, decodeFootprintRecordReceipt,
  decodeFootprintRow, formatTrackClock, groupFootprints, rowSubtitle, rowTitle, targetTypeLabels,
} from '../favtrack/model'
import { RealFavoriteFootprintRepository, isFavoriteFootprintUnauthorized, isTrackTargetNotFound } from '../favtrack/repository'
import { ConsumerApi, isFavoriteFootprintPath, type LocalStore } from '../../shared/consumer-api'
import { ApiError } from '../../shared/request'

// 线上形态是扁平行（控制器投影：display 列直接在行上），与解码器键集一致。
const wireDisplay = (over: Record<string, unknown> = {}) => ({
  targetAvailable: true, title: '宠物洗澡美容全套', categoryLabel: '宠物美容', salePrice: '80.00',
  storeId: '30001', coverUrl: null, ...over,
})
const wireFavorite = (over: Record<string, unknown> = {}) => ({
  favoriteId: '760101', targetType: 'SERVICE', targetId: '20001',
  favoritedAt: '2026-10-13T02:24:00.000Z', ...wireDisplay(), ...over,
})
const wireFootprint = (over: Record<string, unknown> = {}) => ({
  footprintId: '770101', targetType: 'SERVICE', targetId: '20001',
  lastViewedAt: '2026-10-13T02:24:00.000Z', ...wireDisplay(), ...over,
})

test('favorite row decode accepts the contract projection and rejects unknown keys', () => {
  const row = decodeFavoriteRow(wireFavorite())
  assert.equal(row.display.salePrice, '80.00')
  assert.equal(row.display.storeId, '30001')
  // 未知键失败关闭（exact-key）。
  assert.throws(() => decodeFavoriteRow(wireFavorite({ rating: '4.9' })), /INVALID_RESPONSE/)
  // targetType 越枚举拒绝（V1 目标只有服务/门店，原稿"动态/人员"行不进解码器）。
  assert.throws(() => decodeFavoriteRow(wireFavorite({ targetType: 'POST' })), /INVALID_RESPONSE/)
  // 金额串必须两位小数。
  assert.throws(() => decodeFavoriteRow(wireFavorite({ salePrice: '80' })), /INVALID_RESPONSE/)
})

test('unavailable rows keep null display columns for cancel/remove', () => {
  const row = decodeFavoriteRow(wireFavorite({ targetAvailable: false, title: null, categoryLabel: null, salePrice: null, storeId: null }))
  assert.equal(row.display.targetAvailable, false)
  assert.equal(rowTitle(row), '服务已下架')
  assert.equal(rowSubtitle(row), '内容已不可用')
})

test('page decoders enforce the fixed server ordering', () => {
  const ok = decodeFavoritePage({
    items: [wireFavorite(), wireFavorite({ favoriteId: '760100', favoritedAt: '2026-10-12T01:51:00.000Z' })],
    page: 1, pageSize: 20, total: 2,
  })
  assert.equal(ok.items.length, 2)
  assert.throws(() => decodeFavoritePage({
    // created_at 逆序：解码器拒绝（服务端固定 created_at DESC, id DESC）。
    items: [wireFavorite({ favoritedAt: '2026-10-12T01:51:00.000Z' }), wireFavorite()],
    page: 1, pageSize: 20, total: 2,
  }), /INVALID_RESPONSE/)
  const footprints = decodeFootprintPage({
    items: [wireFootprint(), wireFootprint({ footprintId: '770100', lastViewedAt: '2026-10-12T01:51:00.000Z' })],
    page: 1, pageSize: 20, total: 2,
  })
  assert.equal(footprints.items.length, 2)
  assert.throws(() => decodeFootprintPage({
    items: [wireFootprint({ lastViewedAt: '2026-10-12T01:51:00.000Z' }), wireFootprint()],
    page: 1, pageSize: 20, total: 2,
  }), /INVALID_RESPONSE/)
})

test('status probe decodes null as not favorited and rejects half states', () => {
  assert.deepEqual(decodeFavoriteStatus({ targetType: 'STORE', targetId: '30001', favoriteId: null, favoritedAt: null }), { favoriteId: null, favoritedAt: null })
  const set = decodeFavoriteStatus({ targetType: 'STORE', targetId: '30001', favoriteId: '760102', favoritedAt: '2026-10-12T01:51:00.000Z' })
  assert.equal(set.favoriteId, '760102')
  // favoriteId 与 favoritedAt 必须同空同有（契约 §3.16）。
  assert.throws(() => decodeFavoriteStatus({ targetType: 'STORE', targetId: '30001', favoriteId: '760102', favoritedAt: null }), /INVALID_RESPONSE/)
})

test('command receipts decode the 201/200 split flags', () => {
  assert.deepEqual(decodeFavoriteAddReceipt({
    favoriteId: '760101', targetType: 'SERVICE', targetId: '20001',
    favoritedAt: '2026-10-13T02:24:00.000Z', favorited: true, created: true,
  }), { favoriteId: '760101', created: true })
  // 业务重复/重放：created=false 仍是合法回执（200）。
  const replay = decodeFavoriteAddReceipt({
    favoriteId: '760101', targetType: 'SERVICE', targetId: '20001',
    favoritedAt: '2026-10-13T02:24:00.000Z', favorited: true, created: false,
  })
  assert.equal(replay.created, false)
  assert.throws(() => decodeFavoriteAddReceipt({
    favoriteId: '760101', targetType: 'SERVICE', targetId: '20001',
    favoritedAt: '2026-10-13T02:24:00.000Z', favorited: false, created: true,
  }), /INVALID_RESPONSE/)
  assert.equal(decodeFavoriteCancelReceipt({ targetType: 'SERVICE', targetId: '20001', removed: false }).removed, false)
  assert.equal(decodeFootprintRecordReceipt({
    footprintId: '770101', targetType: 'SERVICE', targetId: '20001',
    lastViewedAt: '2026-10-13T02:24:00.000Z', created: true,
  }).created, true)
  assert.equal(decodeFootprintDeleteReceipt({ footprintId: '770101', deleted: false }).deleted, false)
})

test('footprint grouping follows the 今天/昨天/更早 buckets on UTC calendar days', () => {
  const now = new Date('2026-10-13T12:00:00.000Z')
  const groups = groupFootprints(now, [
    decodeFootprintRow(wireFootprint({ footprintId: '1', lastViewedAt: '2026-10-13T02:24:00.000Z' })),
    decodeFootprintRow(wireFootprint({ footprintId: '2', targetType: 'STORE', title: '萌宠之家', lastViewedAt: '2026-10-12T22:18:00.000Z' })),
    decodeFootprintRow(wireFootprint({ footprintId: '3', lastViewedAt: '2026-10-11T08:00:00.000Z' })),
    decodeFootprintRow(wireFootprint({ footprintId: '4', targetType: 'STORE', title: '宠爱美容坊', lastViewedAt: '2026-09-30T08:00:00.000Z' })),
  ])
  assert.deepEqual(groups.map(group => [group.key, group.rows.length]), [['today', 1], ['yesterday', 1], ['earlier', 2]])
  assert.equal(formatTrackClock(now, '2026-10-13T02:24:00.000Z'), '02:24')
  assert.equal(formatTrackClock(now, '2026-10-12T22:18:00.000Z'), '昨天 22:18')
  assert.equal(formatTrackClock(now, '2026-10-11T08:00:00.000Z'), '10月11日 08:00')
})

test('row subtitles stay inside the catalog projection (no invented ratings/sales)', () => {
  assert.equal(rowSubtitle(decodeFavoriteRow(wireFavorite())), '宠物美容 · ¥80.00 起')
  assert.equal(rowSubtitle(decodeFavoriteRow(wireFavorite({ targetType: 'STORE', categoryLabel: null, salePrice: null, storeId: null, title: '萌宠之家宠物店' }))), '门店')
  assert.equal(targetTypeLabels.SERVICE, '服务')
  assert.equal(targetTypeLabels.STORE, '商家')
})

// —— 路由门与命令槽位（ConsumerApi 白名单 + write 日志幂等） ——

const sessionView = { userId: '90001', sessionId: '957101', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }

async function harness(failFirstWrite = false) {
  const spec: { method: string; path: string; requestId?: string }[] = []
  let writesFailed = 0
  const transport = async (request: { method: string; path: string; requestId?: string }) => {
    if (request.path !== '/api/v1/c/auth/session') spec.push({ method: request.method, path: request.path, requestId: request.requestId })
    if (request.path === '/api/v1/c/auth/session') return { statusCode: 200, data: { success: true, code: 'SUCCESS', message: 'ok', traceId: 't', data: sessionView } }
    // 首次写投递可注入网络丢失（结果未知）：日志槽位保留，重试必须沿用同一请求号。
    if (failFirstWrite && writesFailed++ === 0 && request.requestId !== undefined) throw new Error('REQUEST_TIMEOUT')
    return { statusCode: 200, data: { success: true, code: 'SUCCESS', message: 'ok', traceId: 't', data: dataFor(request.path) } }
  }
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, value), remove: key => { values.delete(key) } }
  store.set('pet.c.session.v1', { ...sessionView, accessToken: 'test-only', tokenType: 'Bearer' })
  const api = new ConsumerApi(transport, store, async () => randomUUID())
  await api.restore()
  return { api, spec, store }
}

function dataFor(path: string): unknown {
  if (path === '/api/v1/c/favorites') return { items: [wireFavorite()], page: 1, pageSize: 20, total: 1 }
  if (path === '/api/v1/c/footprints') return { items: [wireFootprint()], page: 1, pageSize: 20, total: 1 }
  if (path === '/api/v1/c/favorites/SERVICE/20001') {
    return { favoriteId: '760101', targetType: 'SERVICE', targetId: '20001', favoritedAt: '2026-10-13T02:24:00.000Z', favorited: true, created: true }
  }
  if (path === '/api/v1/c/footprints/SERVICE/20001') {
    return { footprintId: '770101', targetType: 'SERVICE', targetId: '20001', lastViewedAt: '2026-10-13T02:24:00.000Z', created: true }
  }
  if (path === '/api/v1/c/footprints/770101') return { footprintId: '770101', deleted: true }
  return {}
}

test('path gate accepts exactly the contract-58 shapes and rejects case drift', () => {
  assert.equal(isFavoriteFootprintPath({ method: 'GET', path: '/api/v1/c/favorites' }), true)
  assert.equal(isFavoriteFootprintPath({ method: 'GET', path: '/api/v1/c/footprints' }), true)
  assert.equal(isFavoriteFootprintPath({ method: 'POST', path: '/api/v1/c/favorites/SERVICE/20001' }), true)
  assert.equal(isFavoriteFootprintPath({ method: 'DELETE', path: '/api/v1/c/favorites/SERVICE/20001' }), true)
  assert.equal(isFavoriteFootprintPath({ method: 'GET', path: '/api/v1/c/favorites/SERVICE/20001' }), true)
  assert.equal(isFavoriteFootprintPath({ method: 'POST', path: '/api/v1/c/footprints/STORE/30001' }), true)
  assert.equal(isFavoriteFootprintPath({ method: 'DELETE', path: '/api/v1/c/footprints/770101' }), true)
  // 非 SERVICE|STORE 枚举、小写漂移、错误方法族均拒绝（白名单防误配）。
  assert.equal(isFavoriteFootprintPath({ method: 'POST', path: '/api/v1/c/favorites/service/20001' }), false)
  assert.equal(isFavoriteFootprintPath({ method: 'POST', path: '/api/v1/c/favorites/COUPON/1' }), false)
  assert.equal(isFavoriteFootprintPath({ method: 'POST', path: '/api/v1/c/favorites' }), false)
  assert.equal(isFavoriteFootprintPath({ method: 'DELETE', path: '/api/v1/c/footprints/SERVICE/20001' }), false)
})

test('repository commands ride the write journal with a stable request id', async () => {
  const { api, spec } = await harness(true)
  const repository = new RealFavoriteFootprintRepository(api)
  // 首投网络丢失（结果未知）：write 槽位日志保留原命令。
  await assert.rejects(() => repository.addFavorite('SERVICE', '20001'), /REQUEST_TIMEOUT/)
  // 重试同一操作：槽位复用日志命令，沿用同一 X-Request-Id（23号 §5 重放），不二次铸造。
  const receipt = await repository.addFavorite('SERVICE', '20001')
  assert.equal(receipt.created, true)
  const writes = spec.filter(entry => entry.path === '/api/v1/c/favorites/SERVICE/20001')
  assert.equal(writes.length, 2)
  assert.equal(writes[0]!.requestId, writes[1]!.requestId)
  // 成功后的再次收藏是新的用户动作（新请求号），服务端业务重复塌缩防重（58号 §写语义）。
  const duplicate = await repository.addFavorite('SERVICE', '20001')
  assert.equal(duplicate.favoriteId, receipt.favoriteId)
  assert.notEqual(writes[1]!.requestId, spec.filter(entry => entry.path === '/api/v1/c/favorites/SERVICE/20001')[2]!.requestId)
  const deleted = await repository.deleteFootprint('770101')
  assert.equal(deleted.deleted, true)
  const page = await repository.listFootprints(1, 20)
  assert.equal(page.total, 1)
})

test('unauthorized and target-not-found surface as typed errors', () => {
  assert.equal(isFavoriteFootprintUnauthorized(new ApiError('COMMON_UNAUTHORIZED', 401)), true)
  assert.equal(isFavoriteFootprintUnauthorized(new ApiError('SERVICE_NOT_FOUND', 404)), false)
  assert.equal(isTrackTargetNotFound(new ApiError('SERVICE_NOT_FOUND', 404)), true)
  assert.equal(isTrackTargetNotFound(new ApiError('STORE_NOT_FOUND', 404)), true)
  assert.equal(isTrackTargetNotFound(new ApiError('COMMON_NOT_FOUND', 404)), false)
})
