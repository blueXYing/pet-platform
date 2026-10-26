import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

// 2026-10-26 用户裁决：健康记录添加是档案详情上的底部半框（Figma 96:2580/96:3006），
// 不是 navigateTo 的二级/三级页面；旧 vaccine-form/deworm-form 深链只负责重定向。
test('health record add is a detail-page bottom sheet and legacy routes redirect', () => {
  const detail = readFileSync(new URL('../pages/pet-archive/detail.tsx', import.meta.url), 'utf8')
  assert.match(detail, /HealthRecordFormSheet/)
  assert.match(detail, /setRecordSheet\('VACCINATION'\)/)
  assert.match(detail, /setRecordSheet\('DEWORMING'\)/)
  assert.doesNotMatch(detail, /vaccine-form\?petId/)

  const sheet = readFileSync(new URL('../pages/pet-archive/record-form.tsx', import.meta.url), 'utf8')
  assert.match(sheet, /export function HealthRecordFormSheet/)
  assert.match(sheet, /pet-record-sheet-mask/)
  assert.match(sheet, /pet-record-sheet-body/)
  assert.match(sheet, /pet-record-sheet-close/)
  assert.match(sheet, /pet-record-sheet-actions/)
  assert.match(sheet, /接种状态.*读侧展示态/)
  assert.match(sheet, /pendingLocked/)
  assert.match(sheet, /请直接点击「保存」重试/)

  const vaccine = readFileSync(new URL('../pages/pet-archive/vaccine-form.tsx', import.meta.url), 'utf8')
  const deworm = readFileSync(new URL('../pages/pet-archive/deworm-form.tsx', import.meta.url), 'utf8')
  assert.match(vaccine, /recordSheet=vaccine/)
  assert.match(deworm, /recordSheet=deworm/)
  assert.match(vaccine, /Taro\.redirectTo/)
  assert.match(deworm, /Taro\.redirectTo/)
})
