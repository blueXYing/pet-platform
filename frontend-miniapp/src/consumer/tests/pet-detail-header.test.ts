import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

// 2026-10-26 用户反馈：详情页顶部「宠物信息」固定，只让下方内容滚动；
// 设计参考画布（preview=1&referenceCanvas=1）仍保留设计 y 绝对定位。
test('detail navigation header is viewport-fixed with a reference-canvas override', () => {
  const css = readFileSync(new URL('../pages/pet-archive/detail.css', import.meta.url), 'utf8')
  for (const selector of ['pet-detail-header', 'pet-detail-back', 'pet-detail-navtitle', 'pet-detail-edit']) {
    assert.match(css, new RegExp(`\\.${selector} \\{[^}]*position: fixed;`), `${selector} must be fixed`)
  }
  for (const selector of ['pet-detail-header', 'pet-detail-back', 'pet-detail-navtitle', 'pet-detail-edit']) {
    assert.match(css, new RegExp(`\\.pet-reference-canvas \\.${selector} \\{[^}]*position: absolute;`), `${selector} reference override`)
  }
})
