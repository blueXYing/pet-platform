import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

// 2026-10-28：详情页头部改由统一 SubpageHeader 提供（标题左对齐、固定）；
// 设计参考画布（preview=1&referenceCanvas=1）仍走 reference 绝对定位。
test('detail navigation header uses the shared fixed subpage header with a reference override', () => {
  const page = readFileSync(new URL('../pages/pet-archive/detail.tsx', import.meta.url), 'utf8')
  assert.match(page, /<SubpageHeader[\s\S]*title='宠物信息'/)
  assert.match(page, /height=\{86\}/)
  assert.match(page, /reference=\{referenceCanvas\}/)
  assert.match(page, /backId='pet-detail-back'/)
  assert.match(page, /titleId='pet-detail-navtitle'/)

  const css = readFileSync(new URL('../components/navigation/subpage-header.css', import.meta.url), 'utf8')
  assert.match(css, /\.sph-root \{[\s\S]*position: fixed;/)
  assert.match(css, /\.sph-reference \{ position: absolute; \}/)
})
