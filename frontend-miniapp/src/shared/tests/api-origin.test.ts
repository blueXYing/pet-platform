import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { assertApiOrigin } from '../api-origin'
import { createHttpAdapter } from '../http-adapter'
import { createPrivateUploadTransport } from '../private-upload-transport'

test('HTTP requires explicit local development exception; HTTPS stays default', () => {
  assertApiOrigin('https://api.example.invalid')
  for (const host of ['192.168.1.44:18080', '10.0.0.1', '172.16.0.1', '172.31.255.255', '127.0.0.1', 'localhost:18080', '[::1]:18080']) {
    assert.throws(() => assertApiOrigin(`http://${host}`), /HTTPS_ORIGIN_REQUIRED/)
    assertApiOrigin(`http://${host}`, true)
  }
  const config = readFileSync(new URL('../../../config/index.ts', import.meta.url), 'utf8')
  assert.match(config, /process.env.NODE_ENV === 'development' && process.env.PET_ALLOW_LOCAL_HTTP === 'true'/)
})
test('exception cannot admit public hosts, DNS aliases, userinfo, alternate IP spellings or URL suffixes', () => {
  for (const host of ['example.com', '192.168.1.44.example.com', '172.15.0.1', '172.32.0.1', '169.254.169.254', '8.8.8.8', '0.0.0.0', '2130706433', '127.1', '0x7f000001', '010.0.0.1', '192.168.1.999', 'localhost.', 'user@127.0.0.1', '127.0.0.1:0', '127.0.0.1:65536', '127.0.0.1/path', '127.0.0.1?x=1', '127.0.0.1#x', '127.0.0.1\\example.com']) assert.throws(() => assertApiOrigin(`http://${host}`, true), /HTTPS_ORIGIN_REQUIRED/)
})
test('request and multipart enforce the same origin policy and retain fixed route/header semantics', async () => {
  const calls: unknown[] = []
  const send = async (value: unknown) => { calls.push(value); return { statusCode: 200, data: {} } }
  assert.throws(() => createHttpAdapter('http://192.168.1.44:18080', send))
  assert.throws(() => createPrivateUploadTransport('http://192.168.1.44:18080', send))
  await createHttpAdapter('http://192.168.1.44:18080', send, true)({ path: '/api/v1/c/auth/session', method: 'GET', headers: {} })
  await createPrivateUploadTransport('http://192.168.1.44:18080', send, true)({ filePath: '/private/test.png', requestId: 'test-uuid', authorization: 'Bearer test-only' })
  assert.equal((calls[0] as { url: string }).url, 'http://192.168.1.44:18080/api/v1/c/auth/session')
  assert.equal((calls[1] as { url: string }).url, 'http://192.168.1.44:18080/api/v1/c/private-assets')
})
