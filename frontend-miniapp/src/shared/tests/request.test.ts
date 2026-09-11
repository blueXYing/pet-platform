import test from 'node:test'
import assert from 'node:assert/strict'
import { createClient, selectTransport, ApiError, type WireRequest, type Transport } from '../request'
import { WorkspaceScope } from '../workspace'
import { consumerFixture, decodeEngineeringFixture, loadEngineeringFixture } from '../fixture'
import { createHttpAdapter, type PlatformRequest } from '../http-adapter'
function setup(transport: Transport) {
  const scope = new WorkspaceScope(); scope.replace(consumerFixture)
  return { scope, client: createClient(scope, transport) }
}
test('MINI-004 injected transport preserves string ID, amount, remote state/actions and write requestId', async () => {
  let wire: WireRequest | undefined
  const data = await loadEngineeringFixture()
  const { client } = setup(selectTransport('fixture', async request => {
    wire = request; return { statusCode: 200, data: { success: true, code: 'SUCCESS', data } }
  }))
  // A documented HTTP route is used only by this capture test; no network call or product operation.
  const request = { path: '/api/v1/c/orders', method: 'POST' as const,
    requestId: 'internal-write-001', data: { serviceId: data.id, payAmount: data.amount } }
  assert.deepEqual(await client.request(request, decodeEngineeringFixture), data)
  assert.equal(wire?.headers['X-Request-Id'], request.requestId)
  assert.equal(wire?.data?.serviceId, '9007199254740993')
  assert.equal(wire?.data?.payAmount, '128.00')
  await client.request(request, decodeEngineeringFixture)
  assert.equal(wire?.headers['X-Request-Id'], request.requestId)
})
test('MINI-004 all write verbs require requestId, cross-workspace and encoded traversal fail before transport', () => {
  let calls = 0
  const { client } = setup(async () => { calls++; throw new Error('should not call') })
  for (const method of ['POST', 'PUT', 'PATCH', 'DELETE'] as const) {
    assert.throws(() => client.request({ path: '/api/v1/c/orders', method }, v => v), /REQUEST_ID_REQUIRED/)
  }
  for (const path of ['/api/v1/merchant/orders', '/api/v1/admin/orders', '/api/v1/c/../merchant/orders', '/api/v1/c/%2e%2e/orders']) {
    assert.throws(() => client.request({ path, method: 'GET' }, v => v), /WORKSPACE_PATH_MISMATCH/)
  }
  assert.equal(calls, 0)
})
test('MINI-004 malformed IDs/amounts/actions rejected, unknown server displayStatus is not recomputed', async () => {
  const value = await loadEngineeringFixture()
  for (const invalid of [{ ...value, id: 9007199254740992 }, { ...value, amount: 128 }, { ...value, actions: { inspect: 'true' } }]) {
    assert.throws(() => decodeEngineeringFixture(invalid), /INVALID_FIXTURE/)
  }
  assert.equal(decodeEngineeringFixture({ ...value, displayStatus: 'REMOTE_VALUE' }).displayStatus, 'REMOTE_VALUE')
})
test('MINI-004 real mode blocked by unapproved CCR; errors use code rather than message', async () => {
  assert.throws(() => selectTransport('real', async () => { throw new Error('unused') }), /CCR_ACR_001_NOT_APPROVED/)
  for (const statusCode of [401, 403, 500]) {
    const { client } = setup(async () => ({ statusCode, data: { success: false, code: 'REMOTE_CODE', message: 'arbitrary' } }))
    await assert.rejects(client.request({ path: '/api/v1/c/orders', method: 'GET' }, v => v),
      (error: unknown) => error instanceof ApiError && error.code === 'REMOTE_CODE' && error.statusCode === statusCode)
  }
})
test('MINI-004 platform request port mock captures URL, headers, data, timeout; network failure propagates', async () => {
  let captured: Parameters<PlatformRequest>[0] | undefined
  const adapter = createHttpAdapter('https://example.invalid', async options => {
    captured = options
    return { statusCode: 200, data: { success: true, data: { id: '9007199254740993' } } }
  })
  const { client } = setup(adapter)
  assert.deepEqual(await client.request({ path: '/api/v1/c/orders', method: 'POST', requestId: 'write-1', data: { amount: '128.00' } }, v => v), { id: '9007199254740993' })
  assert.equal(captured?.url, 'https://example.invalid/api/v1/c/orders')
  assert.equal(captured?.timeout, 15000)
  assert.equal(captured?.header['X-Request-Id'], 'write-1')
  assert.deepEqual(captured?.data, { amount: '128.00' })
  const failure = setup(createHttpAdapter('https://example.invalid', async () => { throw new Error('platform timeout') }))
  await assert.rejects(failure.client.request({ path: '/api/v1/c/orders', method: 'GET' }, v => v), /platform timeout/)
})
