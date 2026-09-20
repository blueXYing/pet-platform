import assert from 'node:assert/strict'
import test from 'node:test'
import { nativeLocationAdapter } from '../merchant-application/location'

test('native picker preserves GCJ-02 coordinates as decimal strings without inventing cityCode', async () => {
  const value = await nativeLocationAdapter(async () => ({ address: '测试路1号', name: '宠物店', longitude: 121.123456789, latitude: 31.2 }))()
  assert.deepEqual(value, { address: '测试路1号 宠物店', longitude: '121.1234568', latitude: '31.2' })
  assert.equal('cityCode' in value!, false)
})
test('cancel returns no location while denial and invalid coordinates remain errors', async () => {
  assert.equal(await nativeLocationAdapter(async () => { throw { errMsg: 'chooseLocation:fail cancel' } })(), null)
  await assert.rejects(nativeLocationAdapter(async () => { throw { errMsg: 'chooseLocation:fail auth deny' } })(), /LOCATION_PERMISSION_DENIED/)
  await assert.rejects(nativeLocationAdapter(async () => ({ address: '地址', name: '', longitude: Infinity, latitude: 30 }))(), /LOCATION_INVALID/)
  await assert.rejects(nativeLocationAdapter(async () => ({ address: '地址', name: '', longitude: 180.1, latitude: 30 }))(), /LOCATION_INVALID/)
  assert.deepEqual(await nativeLocationAdapter(async () => ({ address: '地址', name: '地址', longitude: 120, latitude: 0 }))(), { address: '地址', longitude: '120', latitude: '0' })
})
