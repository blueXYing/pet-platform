import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

// 2026-11-19：上传成功展示本地缩略图；营业执照上传后不再显示容易被误解为报错的引导文案。
test('uploaded material renders a retained thumbnail and hides the license upload hint', () => {
  const view = readFileSync(new URL('../pages/merchant-application/view.tsx', import.meta.url), 'utf8')
  assert.match(view, /const thumbnail = p\.previews\?\.\[assetId\]/)
  assert.match(view, /<Image className='application-thumbnail' src=\{thumbnail\} mode='aspectFill' \/>/)
  assert.match(view, /\{!p\.draft\.businessLicenseAssetId && <Text className='application-hint'>请上传清晰的营业执照照片<\/Text>\}/)

  const page = readFileSync(new URL('../pages/merchant-application/index.tsx', import.meta.url), 'utf8')
  assert.match(page, /retainPreview\(assetId\)/)
  assert.match(page, /pet-material-preview-/)
  assert.match(page, /discardPreview\(assetId\)/)
  assert.match(page, /cleanupPreviews\(\)/)
})