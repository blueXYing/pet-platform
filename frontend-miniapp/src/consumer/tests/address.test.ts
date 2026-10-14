import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import {
  addressFlat, addressWriteMessage, cardHeadline, emptyDraft, fixtureAddresses, formFixture, isAddressScenario,
  maskPhone, sameDraft, validateDraft,
  PreviewAddressRepository,
} from '../address/model'
import { decodeAddress, RealAddressRepository } from '../address/repository'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'

// 地址管理切片测试（契约 59 via HTTP10 §3.2.2，32号第二批）。覆盖：展示层脱敏/订单链接
// 平铺、草稿校验与后端 §28 同口径、preview 仓库幂等回执与场景守卫、真实仓库六路由的
// 方法/路径/载荷与 ConsumerApi 幂等槽（23号 X-Request-Id 跨重启重放）与严格解码。

// ---- 展示层脱敏 + 订单链接平铺 ----

test('maskPhone keeps the designed 138****5678 shape and passes through non-phone text', () => {
  assert.equal(maskPhone('13800005678'), '138****5678')
  assert.equal(maskPhone('13912345670'), '139****5670')
  assert.equal(maskPhone('12345'), '12345')
  assert.equal(maskPhone(''), '')
})

test('addressFlat flattens region + detail with one space for the order snapshot', () => {
  assert.equal(addressFlat({ region: '上海市 浦东新区', detail: '祖冲之路887弄12号3楼' }), '上海市 浦东新区 祖冲之路887弄12号3楼')
  assert.equal(addressFlat({ region: '上海市', detail: '某路1号' }), '上海市 某路1号')
  assert.equal(cardHeadline(fixtureAddresses[0]), '李小白 138****5678')
})

// ---- 草稿校验（契约 59 §28 与后端同口径） ----

test('validateDraft accepts the fixture and rejects each contract violation', () => {
  assert.deepEqual(validateDraft(formFixture), {})
  assert.deepEqual(validateDraft(emptyDraft), {
    contactName: '请填写收货人', contactPhone: '请填写正确的手机号', region: '请填写所在地区', detail: '请填写详细地址',
  })
  assert.equal(validateDraft({ ...formFixture, contactName: ' '.repeat(4) }).contactName, '请填写收货人')
  assert.equal(validateDraft({ ...formFixture, contactName: '李'.repeat(33) }).contactName, '收货人最多32字')
  assert.equal(validateDraft({ ...formFixture, contactPhone: '1380000123' }).contactPhone, '请填写正确的手机号')
  assert.equal(validateDraft({ ...formFixture, contactPhone: '12800001234' }).contactPhone, '请填写正确的手机号')
  assert.equal(validateDraft({ ...formFixture, contactPhone: '2380000123x' }).contactPhone, '请填写正确的手机号')
  assert.equal(validateDraft({ ...formFixture, region: '市'.repeat(65) }).region, '所在地区最多64字')
  assert.equal(validateDraft({ ...formFixture, detail: '路'.repeat(257) }).detail, '详细地址最多256字')
  assert.equal(sameDraft(formFixture, { ...formFixture }), true)
  assert.equal(sameDraft(formFixture, { ...formFixture, isDefault: true }), false)
})

test('addressWriteMessage maps the contract error codes to user copy', () => {
  assert.equal(addressWriteMessage({ code: 'ADDRESS_LIMIT_EXCEEDED' }), '地址已达上限（20条），请先删除不再使用的地址')
  assert.equal(addressWriteMessage({ code: 'IDEMPOTENCY_KEY_CONFLICT' }), '操作已在进行中，请稍后重试')
  assert.equal(addressWriteMessage({ code: 'ADDRESS_NOT_FOUND' }), '地址不存在或已删除')
  assert.equal(addressWriteMessage(new Error('boom')), '保存失败，请稍后重试')
})

test('scenario guard only accepts the registered preview scenarios', () => {
  for (const scenario of ['normal', 'empty', 'load-error', 'save-error', 'expired']) {
    assert.equal(isAddressScenario(scenario), true)
  }
  assert.equal(isAddressScenario('pickup'), false)
  assert.equal(isAddressScenario(undefined), false)
})

// ---- preview 仓库：幂等回执 + 默认唯一 + 上限 + 场景 ----

test('preview save keeps a single default and replays the first receipt for the same key', async () => {
  const repository = new PreviewAddressRepository(fixtureAddresses, 'normal')
  const created = await repository.save(null, { ...formFixture, isDefault: true }, 'r1')
  assert.equal(created.isDefault, true)
  const listed = await repository.list()
  assert.equal(listed.filter(address => address.isDefault).length, 1)
  assert.equal(listed.find(address => address.addressId === '40001')?.isDefault, false)
  const replay = await repository.save(null, { ...formFixture, isDefault: true }, 'r1')
  assert.deepEqual(replay, created)
  const conflict = await repository.save(null, { ...formFixture, isDefault: true, contactName: '改名' }, 'r1').catch(error => error)
  assert.ok(String(conflict).includes('REQUEST_CONFLICT'))
  assert.equal((await repository.list()).length, 3)
})

test('preview set-default flips atomically and replays; remove soft-deletes and replays', async () => {
  const repository = new PreviewAddressRepository(fixtureAddresses, 'normal')
  const flipped = await repository.setDefault('40002', 'd1')
  assert.equal(flipped.isDefault, true)
  const replay = await repository.setDefault('40002', 'd1')
  assert.equal(replay.addressId, '40002')
  assert.equal((await repository.list()).filter(address => address.isDefault).length, 1)
  const missing = await repository.setDefault('49999', 'd2').catch(error => error)
  assert.ok(String(missing).includes('ADDRESS_NOT_FOUND'))
  const removed = await repository.remove('40001', 'x1')
  assert.deepEqual(removed, { addressId: '40001', status: 'DELETED' })
  assert.deepEqual(await repository.remove('40001', 'x1'), removed)
  assert.equal((await repository.list()).length, 1)
})

test('preview cap mirrors the contract limit of twenty active addresses', async () => {
  const repository = new PreviewAddressRepository([], 'normal')
  for (let index = 0; index < 20; index++) {
    await repository.save(null, { ...formFixture, contactName: `收${index}`, detail: `路${index}号` }, `c${index}`)
  }
  const over = await repository.save(null, formFixture, 'c-overflow').catch(error => error)
  assert.ok(String(over).includes('ADDRESS_LIMIT_EXCEEDED'))
  await repository.remove((await repository.list())[0].addressId, 'free')
  assert.equal((await repository.save(null, formFixture, 'c-21')).addressId !== undefined, true)
})

test('preview scenarios fail closed once then recover, and expired list stays empty', async () => {
  const loadError = new PreviewAddressRepository(fixtureAddresses, 'load-error')
  await assert.rejects(loadError.list(), /PREVIEW_LOAD_FAILED/)
  assert.equal((await loadError.list()).length, 2)
  const saveError = new PreviewAddressRepository(fixtureAddresses, 'save-error')
  await assert.rejects(saveError.save(null, formFixture, 's1'), /PREVIEW_SAVE_FAILED/)
  assert.equal((await saveError.save(null, formFixture, 's1')).addressId !== undefined, true)
  assert.equal((await new PreviewAddressRepository([], 'empty').list()).length, 0)
})

// ---- 真实仓库：严格解码 + 六路由方法/路径/载荷 + 幂等槽（23号） ----

const sessionView = { userId: '957001', sessionId: '957101', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', message: 'ok', data, traceId: 'test' } })
const failure = (statusCode: number, code: string) => ({ statusCode, data: { code, message: 'x', data: null, traceId: 'test' } })
const wireAddress = {
  addressId: '40001', contactName: '李小白', contactPhone: '13800005678',
  region: '上海市 浦东新区', detail: '祖冲之路887弄12号3楼', isDefault: true, status: 'ACTIVE',
}

type WireRequest = { method: string; path: string; data?: Record<string, unknown>; requestId?: string; headers: Record<string, string> }
function authenticatedApi(transport: (request: WireRequest) => Promise<{ statusCode: number; data: unknown }>) {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, JSON.parse(JSON.stringify(value))), remove: key => { values.delete(key) } }
  store.set('pet.c.session.v1', { ...sessionView, accessToken: 'test-only', tokenType: 'Bearer' })
  const api = new ConsumerApi(transport, store, async () => randomUUID())
  return { api, restore: () => api.restore(), store }
}

test('decodeAddress accepts the contract projection and rejects foreign shapes', () => {
  assert.deepEqual(decodeAddress(JSON.parse(JSON.stringify(wireAddress))), wireAddress)
  for (const bad of [
    { ...wireAddress, addressId: '0' },
    { ...wireAddress, addressId: 'abc' },
    { ...wireAddress, contactName: 42 },
    { ...wireAddress, isDefault: 'yes' },
    { ...wireAddress, status: 'PAUSED' },
    { ...wireAddress, detail: null },
  ]) {
    assert.throws(() => decodeAddress(bad), /INVALID_RESPONSE/)
  }
  assert.throws(() => decodeAddress('not-an-object'), /INVALID/)
})

test('real repository lists and decodes the six-route read face', async () => {
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    assert.equal(request.method, 'GET')
    if (request.path === '/api/v1/c/addresses') return ok([wireAddress])
    if (request.path === '/api/v1/c/addresses/40001') return ok(wireAddress)
    return failure(404, 'COMMON_NOT_FOUND')
  })
  await restore()
  const repository = new RealAddressRepository(api)
  const listed = await repository.list()
  assert.deepEqual(listed, [wireAddress])
  assert.deepEqual(await repository.get('40001'), wireAddress)
  await assert.rejects(() => repository.get('0'), /INVALID/)  // 网络前失败关闭（同步守卫）
})

test('real repository create walks the address:create slot with the five contract fields', async () => {
  const seen: WireRequest[] = []
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    seen.push(request)
    return ok({ ...wireAddress, addressId: '40009', isDefault: false })
  })
  await restore()
  const repository = new RealAddressRepository(api)
  const saved = await repository.save(null, { ...formFixture, contactName: '  李小白  ' })
  assert.equal(saved.addressId, '40009')
  assert.equal(seen[0]!.method, 'POST')
  assert.equal(seen[0]!.path, '/api/v1/c/addresses')
  assert.deepEqual(seen[0]!.data, { ...formFixture, contactName: '李小白' })   // 草稿姓名先去首尾空白
  assert.match(seen[0]!.headers['X-Request-Id'], /^[0-9a-f-]{36}$/)
  // 成功后槽位清空：再次保存可获新请求编号。
  await repository.save(null, formFixture)
  assert.notEqual(seen[1]!.requestId, seen[0]!.requestId)
})

test('real repository update/set-default/delete target the row with their own slots', async () => {
  const seen: WireRequest[] = []
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    seen.push(request)
    if (request.method === 'DELETE') return ok({ addressId: '40001', status: 'DELETED' })
    if (request.path.endsWith('/set-default')) return ok({ ...wireAddress, isDefault: true })
    return ok(wireAddress)
  })
  await restore()
  const repository = new RealAddressRepository(api)
  const updated = await repository.save('40001', formFixture)
  assert.equal(updated.addressId, '40001')
  assert.equal(seen[0]!.method, 'PUT')
  assert.equal(seen[0]!.path, '/api/v1/c/addresses/40001')
  const defaulted = await repository.setDefault('40001')
  assert.equal(defaulted.isDefault, true)
  assert.equal(seen[1]!.method, 'POST')
  assert.equal(seen[1]!.path, '/api/v1/c/addresses/40001/set-default')
  assert.equal(seen[1]!.data, undefined)   // 决策路由无业务体
  const removed = await repository.remove('40001')
  assert.deepEqual(removed, { addressId: '40001', status: 'DELETED' })
  assert.equal(seen[2]!.method, 'DELETE')
  await assert.rejects(() => repository.remove('zz'), /INVALID/)  // 网络前失败关闭
})

test('unconfirmed create replays the same X-Request-Id after a restart', async () => {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, JSON.parse(JSON.stringify(value))), remove: key => { values.delete(key) } }
  store.set('pet.c.session.v1', { ...sessionView, accessToken: 'test-only', tokenType: 'Bearer' })
  const down = async (request: WireRequest) => (
    request.path === '/api/v1/c/auth/session' ? ok(sessionView) : failure(503, 'COMMON_DEPENDENCY_UNAVAILABLE')
  )
  const first = new ConsumerApi(down, store, async () => randomUUID())
  await first.restore()
  const unknown = await new RealAddressRepository(first).save(null, formFixture).catch(error => error)
  assert.ok(unknown instanceof Error && 'statusCode' in (unknown as object))
  const journaled = first.pendingCommand('address:create')
  assert.ok(journaled !== undefined)
  const seen: WireRequest[] = []
  const second = new ConsumerApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    seen.push(request)
    return ok(wireAddress)
  }, store, async () => randomUUID())
  await second.restore()
  const replayed = await new RealAddressRepository(second).save(null, formFixture)
  assert.equal(replayed.addressId, '40001')
  assert.equal(seen[0]!.requestId, journaled!.requestId)   // 原编号随日志提交（23号 §5 重放）
  // 同载荷重试后槽位清空；重开新表单获新编号。
  assert.equal(second.pendingCommand('address:create'), undefined)
})
