import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

// 2026-11-19：签到页接入统一 SubpageHeader（标题左对齐+返回，baike 同款静态头部），
// 原 ptk-status-area 状态栏占位由头部统一承载（static 头部高度含状态栏偏移）。
test('signin page uses the shared static subpage header with back to mine fallback', () => {
  const page = readFileSync(new URL('../pages/points-tasks/signin.tsx', import.meta.url), 'utf8')
  assert.match(page, /<SubpageHeader title='每日签到'/)
  assert.match(page, /backId='ptk-back'/)
  assert.match(page, /titleId='ptk-navtitle'/)
  assert.match(page, /fixed=\{false\}/)
  assert.match(page, /height=\{46\}/)
  assert.match(page, /onBack=\{goBack\}/)
  assert.match(page, /Taro\.navigateBack\(\)\.catch\(\(\) => Taro\.switchTab\(\{ url: '\/consumer\/pages\/mine\/index' \}\)\)/)
  assert.doesNotMatch(page, /ptk-status-area/)
})