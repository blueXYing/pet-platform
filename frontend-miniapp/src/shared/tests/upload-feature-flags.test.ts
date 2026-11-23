import assert from 'node:assert/strict'
import test from 'node:test'
import { merchantApplicationEnabled, privateMaterialUploadEnabled } from '../../../config/feature-flags'

const origin = { PET_C_API_ORIGIN: 'http://192.168.1.44:18081' }

// 2026-11-19：上传默认启用；无 API Origin 或显式 false 才关闭。
test('merchant application and material upload are enabled by default with an API origin', () => {
  assert.equal(merchantApplicationEnabled(origin), true)
  assert.equal(privateMaterialUploadEnabled(origin), true)
})

test('feature flags fail closed without an API origin and honor explicit opt-out', () => {
  assert.equal(merchantApplicationEnabled({}), false)
  assert.equal(privateMaterialUploadEnabled({}), false)
  assert.equal(merchantApplicationEnabled({ ...origin, PET_MERCHANT_APPLICATION_ENABLED: 'false' }), false)
  assert.equal(privateMaterialUploadEnabled({ ...origin, PET_PRIVATE_MATERIAL_UPLOAD_ENABLED: 'false' }), false)
})

test('legacy explicit true remains accepted', () => {
  assert.equal(merchantApplicationEnabled({ ...origin, PET_MERCHANT_APPLICATION_ENABLED: 'true' }), true)
  assert.equal(privateMaterialUploadEnabled({ ...origin, PET_PRIVATE_MATERIAL_UPLOAD_ENABLED: 'true' }), true)
})