import test from 'node:test'
import assert from 'node:assert/strict'
import {
  NotificationPreferencesController, PreviewPreferenceDeps, isPreferenceScenario,
  mandatoryNotice, preferenceMeta, type PreferenceDeps,
} from '../notifications/preferences'
import { ApiError } from '../../shared/request'

// SSOT §16.4 偏好面：仅两个开关（普通互动提醒 / 微信外部推送），订单/退款/核销/售后/审核
// 站内消息不可关闭；外部推送渠道 V1 搁置（#108），开关只保存偏好、不触发外发。
const saved = { interactionEnabled: true, externalPushEnabled: true, version: '2', updatedAt: '2026-10-06T01:00:00.000Z' }

function depsWith(overrides: Partial<PreferenceDeps>): PreferenceDeps {
  const base: PreferenceDeps = {
    load: async () => ({ ...saved }),
    save: async input => ({ ...input, version: '3', updatedAt: '2026-10-06T02:00:00.000Z' }),
  }
  return { ...base, ...overrides }
}

test('load maps the two schema switches; defaults never invent extra items', async () => {
  const controller = new NotificationPreferencesController(depsWith({}))
  await controller.load()
  const state = controller.getSnapshot()
  assert.equal(state.status, 'ready')
  assert.deepEqual(state.draft, { interactionEnabled: true, externalPushEnabled: true })
  assert.deepEqual(Object.keys(preferenceMeta).sort(), ['externalPushEnabled', 'interactionEnabled'])
  // The mandatory SSOT notice covers exactly the five und closable in-site kinds.
  for (const kind of ['订单', '退款', '核销', '售后', '审核']) assert.ok(mandatoryNotice.includes(kind))
})

test('read failures stay closed; 401 routes to the login guidance', async () => {
  const failing = new NotificationPreferencesController(depsWith({ load: async () => { throw new Error('network') } }))
  await failing.load()
  assert.equal(failing.getSnapshot().status, 'error')
  const unauthorized = new NotificationPreferencesController(
    depsWith({ load: async () => { throw new ApiError('COMMON_UNAUTHORIZED', 401) } }))
  await unauthorized.load()
  assert.equal(unauthorized.getSnapshot().status, 'unauthorized')
  assert.equal(unauthorized.getSnapshot().draft, null)
})

test('toggles edit the draft only; nothing is saved until save', async () => {
  let saves = 0
  const controller = new NotificationPreferencesController(depsWith({ save: async input => { saves++; return { ...input, version: '3', updatedAt: '2026-10-06T02:00:00.000Z' } } }))
  await controller.load()
  controller.toggle('interactionEnabled')
  assert.equal(saves, 0)
  assert.deepEqual(controller.getSnapshot().draft, { interactionEnabled: false, externalPushEnabled: true })
  assert.ok(controller.dirty())
  controller.reset()
  assert.deepEqual(controller.getSnapshot().draft, { interactionEnabled: true, externalPushEnabled: true })
  assert.ok(!controller.dirty())
})

test('save persists the full two-switch state and shows the confirmed result', async () => {
  const controller = new NotificationPreferencesController(depsWith({}))
  await controller.load()
  controller.toggle('externalPushEnabled')
  await controller.save()
  const state = controller.getSnapshot()
  assert.equal(state.status, 'ready')
  assert.equal(state.saved?.externalPushEnabled, false)
  assert.deepEqual(state.draft, { interactionEnabled: true, externalPushEnabled: false })
  assert.equal(state.saving, false)
})

test('failed save keeps the draft for an idempotent retry', async () => {
  const controller = new NotificationPreferencesController(
    depsWith({ save: async () => { throw new ApiError('COMMON_INTERNAL_ERROR', 500) } }))
  await controller.load()
  controller.toggle('interactionEnabled')
  await controller.save()
  const state = controller.getSnapshot()
  assert.equal(state.status, 'ready')
  assert.deepEqual(state.draft, { interactionEnabled: false, externalPushEnabled: true })
  assert.equal(state.saving, false)
  assert.equal(state.saved?.version, '2')
})

test('save-time 401 fails closed to the login guidance', async () => {
  const controller = new NotificationPreferencesController(
    depsWith({ save: async () => { throw new ApiError('COMMON_UNAUTHORIZED', 401) } }))
  await controller.load()
  controller.toggle('interactionEnabled')
  await controller.save()
  assert.equal(controller.getSnapshot().status, 'unauthorized')
  assert.equal(controller.getSnapshot().draft, null)
})

test('disposed controller ignores late results', async () => {
  let resolve!: (value: typeof saved) => void
  const late = new Promise<typeof saved>(ok => { resolve = ok })
  const controller = new NotificationPreferencesController(depsWith({ load: () => late }))
  const loading = controller.load()
  controller.dispose()
  resolve(saved)
  await loading
  assert.equal(controller.getSnapshot().status, 'loading')
})

test('preview fixture serves local data only and mirrors the save shape', async () => {
  assert.ok(isPreferenceScenario('normal') && isPreferenceScenario('off'))
  assert.ok(!isPreferenceScenario('bogus') && !isPreferenceScenario(undefined))
  const normal = new PreviewPreferenceDeps('normal')
  const loaded = await normal.load()
  assert.deepEqual(
    { interactionEnabled: loaded.interactionEnabled, externalPushEnabled: loaded.externalPushEnabled },
    { interactionEnabled: true, externalPushEnabled: true })
  const after = await normal.save({ interactionEnabled: false, externalPushEnabled: true })
  assert.equal(after.interactionEnabled, false)
  assert.equal(Number(after.version), Number(loaded.version) + 1)
  const off = await new PreviewPreferenceDeps('off').load()
  assert.equal(off.interactionEnabled, false)
  assert.equal(off.externalPushEnabled, false)
})
