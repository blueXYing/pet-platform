import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

// 2026-11-18：列表页接入统一 SubpageHeader（标题左对齐、固定，高 86 同 detail/form）；
// 原稿仅为首页模块区域、无头部，参考画布（preview=1&referenceCanvas=1）不渲染头部且保持原稿坐标。
test('list navigation header uses the shared subpage header, skipped on the reference canvas', () => {
  const page = readFileSync(new URL('../pages/pet-archive/index.tsx', import.meta.url), 'utf8')
  assert.match(page, /\{!referenceCanvas && <SubpageHeader title='宠物档案'/)
  assert.match(page, /backId='pet-list-back'/)
  assert.match(page, /titleId='pet-list-navtitle'/)
  assert.match(page, /height=\{86\}/)
  assert.match(page, /pet-list-with-header/)
  assert.match(page, /\{referenceCanvas && <Text className='pet-list-title'>宠物档案<\/Text>\}/)
  assert.match(page, /const top = \(referenceCanvas \? 95 : 181\) \+ index \* 85/)

  const css = readFileSync(new URL('../pages/pet-archive/list.css', import.meta.url), 'utf8')
  assert.match(css, /\.pet-list-with-header \.pet-list-head \{ top: calc\(var\(--pet-unit\) \* 86\); \}/)
  assert.match(css, /\.pet-list-with-header \.pet-list-design \{ height: calc\(var\(--pet-unit\) \* 398 \+ var\(--pet-list-extra, 0px\)\); \}/)
})