import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ApiError } from '../../shared/request'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import {
  emptyWindowForm, PreviewScheduleRepository, ScheduleMockError,
  fixtureStaffWindows, fixtureWindows, scheduleAvailability, windowFormFromItem,
  type ScheduleWindowReceipt,
} from '../schedule/model'
import { RealScheduleRepository } from '../schedule/repository'

async function rejects(call: () => Promise<unknown> | unknown): Promise<unknown> {
  try { await call(); return new Error('NO_THROW') } catch (error) { return error }
}
const formFor = (serviceId: string, kind: 'GENERAL' | 'PICKUP' | 'RETURN', start: string, end: string, capacity: number) =>
  ({ serviceId, windowKind: kind, startDate: start.slice(0, 10), startTime: start.slice(11, 16),
    endDate: end.slice(0, 10), endTime: end.slice(11, 16), configuredCapacity: capacity })
const staffForm = (start: string, end: string) =>
  ({ startDate: start.slice(0, 10), startTime: start.slice(11, 16), endDate: end.slice(0, 10), endTime: end.slice(11, 16) })

test('preview workbench read decodes fixtures and derives SOLD_OUT', async () => {
  const repository = new PreviewScheduleRepository()
  const page = await repository.windows({ page: 1, pageSize: 20 })
  assert.equal(page.items.length, fixtureWindows().length)
  assert.equal(page.total, fixtureWindows().length)
  const full = page.items.find(item => item.windowId === '61003')!
  assert.equal(full.status, 'SOLD_OUT', 'occupied == capacity derives SOLD_OUT')
  const partial = page.items.find(item => item.windowId === '61002')!
  assert.equal(partial.status, 'OPEN', 'occupied < capacity stays OPEN')
  assert.ok(page.items.some(item => item.status === 'CLOSED'))
  const filtered = await repository.windows({ page: 1, pageSize: 20, kind: 'PICKUP' })
  assert.equal(filtered.items.length, 1)
  assert.equal(filtered.total, 1)
  const byService = await repository.windows({ page: 1, pageSize: 20, serviceId: '30007' })
  assert.equal(byService.items.length, 2)
  assert.equal(byService.total, 2)
})

test('preview windows paginate §3.3-style: wire order, slices, filter-matched totals, 400 bounds', async () => {
  const repository = new PreviewScheduleRepository()
  // Wire order is start_at ascending with id ascending tiebreak (§3.3), so page 1 of 2
  // carries the earliest two windows of 2026-10-07: 61001 09:00 then 61004 09:30.
  const all = await repository.windows({ page: 1, pageSize: 20 })
  assert.deepEqual(all.items.map(item => item.windowId), ['61001', '61004', '61002', '61005', '61003', '61006'])
  const page1 = await repository.windows({ page: 1, pageSize: 2 })
  assert.deepEqual(page1.items.map(item => item.windowId), ['61001', '61004'])
  assert.equal(page1.total, 6)
  const page3 = await repository.windows({ page: 3, pageSize: 2 })
  assert.deepEqual(page3.items.map(item => item.windowId), ['61003', '61006'])
  // Past the end: empty items, total unchanged (§3.3 / 10号通则).
  const over = await repository.windows({ page: 5, pageSize: 2 })
  assert.equal(over.items.length, 0)
  assert.equal(over.total, 6)
  // Summary count queries ride the status-filtered total (fixtures: 4 OPEN, 1 SOLD_OUT, 1 CLOSED).
  const open = await repository.windows({ page: 1, pageSize: 1, status: 'OPEN' })
  assert.equal(open.total, 4)
  assert.equal(open.items.length, 1)
  assert.equal((await repository.windows({ page: 1, pageSize: 1, status: 'SOLD_OUT' })).total, 1)
  assert.equal((await repository.windows({ page: 1, pageSize: 1, status: 'CLOSED' })).total, 1)
  assert.equal(4 + 1 + 1, all.total, 'three-state totals partition the unfiltered total')
  // kind × status compose into one WHERE (filter first, then count and slice).
  const pickupOpen = await repository.windows({ page: 1, pageSize: 20, kind: 'PICKUP', status: 'OPEN' })
  assert.equal(pickupOpen.total, 1)
  // Illegal paging values mirror the server's generic 400 before anything is read.
  for (const bad of [
    { page: 0, pageSize: 20 }, { page: 10001, pageSize: 20 }, { page: 1.5, pageSize: 20 },
    { page: 1, pageSize: 0 }, { page: 1, pageSize: 51 }, { page: 1, pageSize: 1.5 },
  ]) {
    const rejected = await rejects(() => repository.windows(bad))
    assert.equal((rejected as ScheduleMockError).code, 'COMMON_INVALID_ARGUMENT', JSON.stringify(bad))
  }
})

test('preview enforces occupied-window guards and the capacity-raise re-judge', async () => {
  const repository = new PreviewScheduleRepository()
  const full = (await repository.windows({ page: 1, pageSize: 20 })).items.find(item => item.windowId === '61003')!
  // Close a full (SOLD_OUT) window → occupied guard.
  const close = await rejects(() => repository.closeWindow('s1', '61003', full.version, '整理'))
  assert.equal((close as ScheduleMockError).code, 'SCHEDULE_WINDOW_STATE_NOT_ALLOWED')
  // Time change while occupied → guard; capacity raise → allowed and re-judges to OPEN (§3.1).
  const moving = { ...windowFormFromItem(full), startTime: '16:30' }
  const move = await rejects(() => repository.updateWindow('s2', '61003', full.version, moving))
  assert.equal((move as ScheduleMockError).code, 'SCHEDULE_WINDOW_STATE_NOT_ALLOWED')
  const raising = { ...windowFormFromItem(full), configuredCapacity: 2 }
  const raised = await repository.updateWindow('s3', '61003', full.version, raising)
  assert.equal(raised.status, 'OPEN')
  // CAS: a second command with the stale version answers COMMON_CONFLICT.
  const stale = await rejects(() => repository.closeWindow('s4', '61003', full.version, '整理'))
  assert.equal((stale as ScheduleMockError).code, 'COMMON_CONFLICT')
})

test('preview enforces overlap discipline and the CLOSED edit guard', async () => {
  const repository = new PreviewScheduleRepository()
  // Same service+kind overlap → 409; adjacent half-open → legal.
  const overlap = await rejects(() => repository.createWindow('a1',
    formFor('30001', 'GENERAL', '2026-10-07 10:00', '2026-10-07 11:00', 1)))
  assert.equal((overlap as ScheduleMockError).code, 'SCHEDULE_WINDOW_OVERLAP')
  const adjacent = await repository.createWindow('a2',
    formFor('30001', 'GENERAL', '2026-10-07 12:00', '2026-10-07 13:00', 1))
  assert.equal(adjacent.status, 'OPEN')
  // Different kind may share the wall clock.
  const other = await repository.createWindow('a3',
    formFor('30001', 'RETURN', '2026-10-07 10:00', '2026-10-07 11:00', 1))
  assert.equal(other.windowKind, 'RETURN')
  // CLOSED windows are not editable; reopen first (and reopen re-judges by occupancy).
  const closed = await rejects(() => repository.updateWindow('a4', '61006', '5',
    { serviceId: '30001', windowKind: 'GENERAL', startDate: '2026-10-07', startTime: '19:00',
      endDate: '2026-10-07', endTime: '20:00', configuredCapacity: 1 }))
  assert.equal((closed as ScheduleMockError).code, 'SCHEDULE_WINDOW_STATE_NOT_ALLOWED')
  const reopened = await repository.openWindow('a5', '61006', '5')
  assert.equal(reopened.status, 'OPEN')
  const staleOpen = await rejects(() => repository.openWindow('a6', '61006', '5'))
  assert.equal((staleOpen as ScheduleMockError).code, 'COMMON_CONFLICT')
})

test('preview batch close: partial success names blocked windows; >200 entries reject whole', async () => {
  const repository = new PreviewScheduleRepository()
  const result = await repository.batchClose('b1', '2026-10-07', '2026-10-07', '门店装修')
  assert.equal(result.closedWindows.length, 3, 'unoccupied windows close')
  assert.equal(result.blockedWindows.length, 2, 'occupied windows are named')
  for (const blocked of result.blockedWindows) {
    assert.equal(blocked.reasonCode, 'SCHEDULE_WINDOW_STATE_NOT_ALLOWED')
    assert.ok(blocked.window.status === 'SOLD_OUT' || blocked.window.status === 'OPEN')
  }
  // The whole command replays via the slot journal.
  const replay = await repository.batchClose('b1', '2026-10-07', '2026-10-07', '门店装修')
  assert.deepEqual(replay.closedWindows.length, result.closedWindows.length)
  // >200 intersecting candidates → 400 before any close (§3.2).
  const crowd = Array.from({ length: 201 }, (_, index) => ({
    window: { windowId: String(70000 + index), merchantId: '958001', storeId: '958002',
      serviceId: '30001', windowKind: 'GENERAL' as const,
      startAt: `2026-10-10T0${index % 10}:0${index % 10}:00.000+08:00`,
      endAt: `2026-10-10T1${index % 10}:0${index % 10}:00.000+08:00`,
      configuredCapacity: 1, status: 'OPEN' as const, version: '1' },
    updatedAt: '2026-10-06T02:00:00.000Z', occupied: 0,
  }))
  const crowded = new PreviewScheduleRepository([...fixtureWindows(), ...crowd])
  const over = await rejects(() => crowded.batchClose('b2', '2026-10-10', '2026-10-10', '停业'))
  assert.equal((over as ScheduleMockError).code, 'COMMON_INVALID_ARGUMENT')
  // Reason is mandatory; from>to is rejected client-side by the page and here via helper.
  const noReason = await rejects(() => repository.batchClose('b3', '2026-10-07', '2026-10-07', ' '))
  assert.equal((noReason as ScheduleMockError).code, 'COMMON_INVALID_ARGUMENT')
})

test('preview staff windows: overlap 409, assignment protection 409, CAS 409', async () => {
  const repository = new PreviewScheduleRepository()
  repository.protectedStaff = true
  const page = await repository.staffWindows('958003')
  assert.equal(page.items.length, fixtureStaffWindows().length)
  const overlap = await rejects(() => repository.createStaffWindow('c1', '958003',
    staffForm('2026-10-07 10:00', '2026-10-07 11:00')))
  assert.equal((overlap as ScheduleMockError).code, 'SCHEDULE_WINDOW_OVERLAP')
  const adjacent = await repository.createStaffWindow('c2', '958003', staffForm('2026-10-07 12:00', '2026-10-07 13:00'))
  assert.equal(adjacent.status, 'AVAILABLE')
  // Shrinking an AVAILABLE row with protected assignments → 409 (§4 protection).
  const available = page.items.find(item => item.status === 'AVAILABLE')!
  const shrink = await rejects(() => repository.updateStaffWindow('c3', '958003', available.windowId, available.version,
    staffForm('2026-10-07 09:30', '2026-10-07 11:30')))
  assert.equal((shrink as ScheduleMockError).code, 'SCHEDULE_WINDOW_STATE_NOT_ALLOWED')
  const close = await rejects(() => repository.closeStaffWindow('c4', '958003', available.windowId, available.version, '请假'))
  assert.equal((close as ScheduleMockError).code, 'SCHEDULE_WINDOW_STATE_NOT_ALLOWED')
  // Without protection the same close succeeds; CAS staleness still 409s.
  repository.protectedStaff = false
  const stale = await rejects(() => repository.closeStaffWindow('c5', '958003', available.windowId, '0', '请假'))
  assert.equal((stale as ScheduleMockError).code, 'COMMON_CONFLICT')
  const closedWindow = await repository.closeStaffWindow('c6', '958003', available.windowId, available.version, '请假')
  assert.equal(closedWindow.status, 'CLOSED')
  const reopened = await repository.openStaffWindow('c7', '958003', available.windowId, closedWindow.version)
  assert.equal(reopened.status, 'AVAILABLE')
  await assert.rejects(() => repository.staffWindows('not-an-id'))
})

test('preview capabilities: whole-set CAS replace, removal reason, legacy quarantine', async () => {
  const repository = new PreviewScheduleRepository()
  const current = await repository.capabilities('958003')
  assert.deepEqual(current.serviceIds, ['30001', '30007'])
  const stale = await rejects(() => repository.replaceCapabilities('d1', '958003', ['30001'], '0', '撤销'))
  assert.equal((stale as ScheduleMockError).code, 'COMMON_CONFLICT')
  const noReason = await rejects(() => repository.replaceCapabilities('d2', '958003', ['30001'], current.version, ''))
  assert.equal((noReason as ScheduleMockError).code, 'COMMON_INVALID_ARGUMENT')
  const saved = await repository.replaceCapabilities('d3', '958003', ['30001', '30007', '30002'], current.version, '')
  assert.equal(saved.version !== current.version, true)
  assert.deepEqual(saved.serviceIds, ['30001', '30007', '30002'])
  // An unknown staff reads the empty-version-0 shape only when no head/details exist.
  const fresh = await repository.capabilities('958009')
  assert.deepEqual(fresh.serviceIds, [])
  assert.equal(fresh.version, '0')
  // LEGACY_UNVERSIONED heads answer 503 and stay quarantined (§5).
  const legacy = new PreviewScheduleRepository(undefined, undefined, undefined, 'legacy')
  const quarantined = await rejects(() => legacy.capabilities('958003'))
  assert.equal((quarantined as ScheduleMockError).statusCode, 503)
  assert.equal(scheduleAvailability(quarantined), 'closed')
})

test('preview scenarios fail closed and the slot journal replays/locks', async () => {
  const closed = new PreviewScheduleRepository(undefined, undefined, undefined, 'closed')
  const unavailable = await rejects(() => closed.windows({ page: 1, pageSize: 20 }))
  assert.equal((unavailable as ScheduleMockError).statusCode, 503)
  const empty = new PreviewScheduleRepository(undefined, undefined, undefined, 'empty')
  const emptyPage = await empty.windows({ page: 1, pageSize: 20 })
  assert.equal(emptyPage.items.length, 0)
  assert.equal(emptyPage.total, 0)
  const loadError = new PreviewScheduleRepository(undefined, undefined, undefined, 'load-error')
  const failed = await rejects(() => loadError.windows({ page: 1, pageSize: 20 }))
  assert.equal((failed as ScheduleMockError).statusCode, 503)
  const repository = new PreviewScheduleRepository()
  const first = await repository.openWindow('s', '61006', '5')
  const replay = await repository.openWindow('s', '61006', '5')
  assert.equal(replay.version, first.version, 'same slot+payload replays the original receipt')
  const locked = await rejects(() => repository.openWindow('s', '61001', '5'))
  assert.equal((locked as Error).message, 'PENDING_WRITE_CHANGED')
})

// ---------------------------------------------------------------------------
// Real wiring over a scripted transport: paths, bodies, journaled request ids,
// slot retirement after definitive 409s and idempotent replay after 5xx.
// ---------------------------------------------------------------------------
const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', success: true, data } })
const failure = (code: string, statusCode: number) => ({ statusCode, data: { code, data: null } })
type Call = { method: string; path: string; requestId?: string; data?: Record<string, unknown> }

async function wiredApi(handlers: (call: Call) => { statusCode: number; data: unknown } | undefined) {
  const seen: Call[] = []
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, value), remove: key => { values.delete(key) } }
  const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
  const grant = { ...session, tokenType: 'Bearer', accessToken: 'unusable-test-token' }
  let uuidCall = 0
  const api = new ConsumerApi(async request => {
    if (request.path.endsWith('/attempts')) return ok({ attemptId: '301', attemptToken: 't', nextStep: 'PROVE_IDENTITY' })
    if (request.path.endsWith('/wechat-login')) return ok(grant)
    if (request.path.endsWith('/session')) return ok(session)
    const call: Call = { method: request.method, path: request.path, requestId: (request as { requestId?: string }).requestId, data: request.data }
    seen.push(call)
    return handlers(call) || failure('COMMON_DEPENDENCY_UNAVAILABLE', 503)
  }, store, async () => `00000000-0000-4000-8000-${String(++uuidCall).padStart(12, '0')}`)
  await api.startLogin(async () => ({ code: 'test-code' }))
  api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '958001', storeId: '958002' })
  const repository = new RealScheduleRepository(api, () => '958001', () => '958002')
  return { api, repository, seen }
}

test('real repository wires the contract routes with workspace coordinates', async () => {
  const windowReceipt: ScheduleWindowReceipt = {
    windowId: '61009', merchantId: '958001', storeId: '958002', serviceId: '30001',
    windowKind: 'GENERAL', startAt: '2026-10-07T01:00:00.000Z', endAt: '2026-10-07T04:00:00.000Z',
    configuredCapacity: 2, status: 'OPEN', version: '0',
  }
  const { repository, seen } = await wiredApi(call => {
    if (call.method === 'GET' && /\/availability-windows$/.test(call.path) && call.path.includes('/stores/')) {
      // §3.3 paged envelope only — the legacy {storeId,items} shape would fail the decode.
      return ok({ storeId: '958002', items: [], page: 2, pageSize: 20, total: 41 })
    }
    if (call.method === 'POST' && /stores\/958002\/availability-windows$/.test(call.path)) {
      return { statusCode: 201, data: { code: 'SUCCESS', success: true, data: windowReceipt } }
    }
    if (call.path.endsWith('/close')) return ok({ ...windowReceipt, status: 'CLOSED', version: '1' })
    if (call.path.endsWith('/open')) return ok({ ...windowReceipt, status: 'OPEN', version: '2' })
    if (call.path.endsWith('/batch-close')) return ok({ storeId: '958002', closedWindows: [{ ...windowReceipt, status: 'CLOSED', version: '1' }], blockedWindows: [] })
    if (call.path.includes('/staff/958003/availability-windows') && call.method === 'GET') {
      return ok({ storeId: '958002', staffId: '958003', items: [] })
    }
    if (call.path.includes('/staff/958003/service-capabilities')) {
      return ok({ merchantId: '958001', storeId: '958002', staffId: '958003', serviceIds: ['30001'], version: '3' })
    }
    return undefined
  })
  const page = await repository.windows({ page: 2, pageSize: 20, kind: 'GENERAL', status: 'OPEN' })
  assert.equal(page.items.length, 0)
  assert.equal(page.total, 41)
  const listCall = seen.find(item => item.method === 'GET' && item.path === '/api/v1/merchant/stores/958002/availability-windows')!
  assert.equal(listCall.data?.merchantId, '958001')
  // §3.3 paged mode is explicit on every query: page/pageSize ride the wire alongside the
  // optional server-side filters (status feeds the summary count queries).
  assert.equal(listCall.data?.page, 2)
  assert.equal(listCall.data?.pageSize, 20)
  assert.equal(listCall.data?.kind, 'GENERAL')
  assert.equal(listCall.data?.status, 'OPEN')
  // The count queries send no kind/status-less variant: a plain query carries only the paging.
  await repository.windows({ page: 1, pageSize: 20 })
  const plainCall = seen.filter(item => item.method === 'GET' && item.path === '/api/v1/merchant/stores/958002/availability-windows').pop()!
  assert.equal(plainCall.data?.kind, undefined)
  assert.equal(plainCall.data?.status, undefined)
  assert.equal(plainCall.data?.serviceId, undefined)
  const created = await repository.createWindow('r1', formFor('30001', 'GENERAL', '2026-10-07 09:00', '2026-10-07 12:00', 2))
  assert.equal(created.windowId, '61009')
  const createCall = seen.find(item => item.method === 'POST' && item.path === '/api/v1/merchant/stores/958002/availability-windows')!
  // Minute-precision Beijing offsets on the wire (no fixed 60-minute slots implied).
  assert.equal(createCall.data?.startAt, '2026-10-07T09:00:00.000+08:00')
  assert.equal(createCall.data?.endAt, '2026-10-07T12:00:00.000+08:00')
  assert.equal(createCall.data?.windowKind, 'GENERAL')
  assert.equal(createCall.data?.configuredCapacity, 2)
  assert.match(String(createCall.requestId), /^[0-9a-f-]{36}$/)
  await repository.closeWindow('r2', '61009', '0', '  员工不足  ')
  const closeCall = seen.find(item => item.path === '/api/v1/merchant/stores/958002/availability-windows/61009/close')!
  assert.equal(closeCall.data?.reason, '员工不足')
  assert.equal(closeCall.data?.expectedVersion, '0')
  await repository.openWindow('r3', '61009', '1')
  await repository.batchClose('r4', '2026-10-07', '2026-10-08', '装修')
  const batchCall = seen.find(item => item.path.endsWith('/batch-close'))!
  assert.equal(batchCall.data?.fromDate, '2026-10-07')
  assert.equal(batchCall.data?.toDate, '2026-10-08')
  await repository.staffWindows('958003')
  const staffListCall = seen.find(item => item.path === '/api/v1/merchant/staff/958003/availability-windows')!
  assert.equal(staffListCall.data?.storeId, '958002')
  await repository.capabilities('958003')
  const capCall = seen.find(item => item.path === '/api/v1/merchant/staff/958003/service-capabilities')!
  assert.equal(capCall.data?.merchantId, '958001')
})

test('real repository: staff and capability commands address staffId in the path', async () => {
  const staffReceipt = {
    windowId: '62009', merchantId: '958001', storeId: '958002', staffId: '958003',
    startAt: '2026-10-07T01:00:00.000Z', endAt: '2026-10-07T04:00:00.000Z',
    status: 'AVAILABLE' as const, version: '0',
  }
  const { repository, seen } = await wiredApi(call => {
    if (call.method === 'POST' && /staff\/958003\/availability-windows$/.test(call.path)) return ok(staffReceipt)
    if (call.path.endsWith('/958003/availability-windows/62009/close')) return ok({ ...staffReceipt, status: 'CLOSED', version: '1' })
    if (call.method === 'PUT' && /staff\/958003\/service-capabilities$/.test(call.path)) {
      return ok({ merchantId: '958001', storeId: '958002', staffId: '958003', serviceIds: [], version: '4' })
    }
    return undefined
  })
  await repository.createStaffWindow('e1', '958003', staffForm('2026-10-08 09:00', '2026-10-08 12:00'))
  const createCall = seen.find(item => item.path === '/api/v1/merchant/staff/958003/availability-windows')!
  assert.equal(createCall.data?.storeId, '958002')
  assert.equal(createCall.data?.startAt, '2026-10-08T09:00:00.000+08:00')
  await repository.closeStaffWindow('e2', '958003', '62009', '0', '请假')
  await repository.replaceCapabilities('e3', '958003', [], '3', '全部撤销')
  const putCall = seen.find(item => item.method === 'PUT' && item.path === '/api/v1/merchant/staff/958003/service-capabilities')!
  assert.deepEqual(putCall.data?.serviceIds, [])
  assert.equal(putCall.data?.expectedVersion, '3')
  assert.equal(putCall.data?.reason, '全部撤销')
})

test('real repository: definitive 409 retires the slot; 503 keeps it and replays the requestId', async () => {
  const windowReceipt: ScheduleWindowReceipt = {
    windowId: '61009', merchantId: '958001', storeId: '958002', serviceId: '30001',
    windowKind: 'GENERAL', startAt: '2026-10-07T01:00:00.000Z', endAt: '2026-10-07T04:00:00.000Z',
    configuredCapacity: 2, status: 'OPEN', version: '7',
  }
  let mode: 'conflict' | 'unavailable' | 'ok' = 'conflict'
  const { repository, seen } = await wiredApi(() => {
    if (mode === 'conflict') return failure('SCHEDULE_WINDOW_OVERLAP', 409)
    if (mode === 'unavailable') return failure('COMMON_DEPENDENCY_UNAVAILABLE', 503)
    return ok(windowReceipt)
  })
  const form = formFor('30001', 'GENERAL', '2026-10-07 09:00', '2026-10-07 12:00', 2)
  // Definitive per-payload rejection → the slot is retired…
  const overlap = await rejects(() => repository.createWindow('slot-a', form))
  assert.ok(overlap instanceof ApiError)
  // …so a corrected payload can reuse the slot with a fresh requestId.
  mode = 'ok'
  await repository.createWindow('slot-a', { ...form, startTime: '13:00', endTime: '15:00' })
  const posts = seen.filter(item => item.method === 'POST' && item.path === '/api/v1/merchant/stores/958002/availability-windows')
  assert.equal(posts.length, 2)
  assert.notEqual(posts[0]!.requestId, posts[1]!.requestId)

  // Unknown outcome → the command stays journaled; an explicit retry replays the SAME
  // requestId with the same payload (23号 idempotent replay), and a changed payload locks.
  mode = 'unavailable'
  const slotBForm = formFor('30001', 'GENERAL', '2026-10-11 09:00', '2026-10-11 12:00', 2)
  await assert.rejects(() => repository.createWindow('slot-b', slotBForm))
  await assert.rejects(() => repository.createWindow('slot-b', { ...slotBForm, configuredCapacity: 3 }), /PENDING_WRITE_CHANGED/)
  mode = 'ok'
  const receipt = await repository.createWindow('slot-b', slotBForm)
  assert.equal(receipt.windowId, '61009')
  const slotBCalls = seen.filter(item => item.method === 'POST' && item.path === '/api/v1/merchant/stores/958002/availability-windows' && item.data?.startAt === '2026-10-11T09:00:00.000+08:00')
  assert.equal(slotBCalls.length, 2, 'failed attempt plus idempotent retry')
  assert.equal(slotBCalls[0]!.requestId, slotBCalls[1]!.requestId, 'retry replays the same X-Request-Id')
})

test('real repository fails closed on consumer coordinates and validates forms before the wire', async () => {
  const { api, repository } = await wiredApi(() => undefined)
  api.scope.replace({ userId: '101', workspace: 'consumer', merchantId: null, storeId: null })
  await assert.rejects(() => repository.windows({ page: 1, pageSize: 20 }), /WORKSPACE_PATH_MISMATCH/)
  api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '958001', storeId: '958002' })
  // Client-side pre-validation mirrors the server answers; nothing reaches the wire.
  const badForm = { ...emptyWindowForm('2026-10-07', '09:00'), serviceId: '' }
  await assert.rejects(() => repository.createWindow('slot-c', badForm), /WORKSPACE_FORM_INVALID/)
  await assert.rejects(() => repository.closeWindow('slot-d', '61009', '0', '   '), /WORKSPACE_FORM_INVALID/)
})
