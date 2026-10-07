import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../shared/request'
import {
  addBeijingDays, batchRangeDays, beijingToday, capabilityProblems, chipClass, composeTimestamp,
  decodeBatchCloseResult, decodeCapabilityView, decodeStaffWindowPage, decodeStaffWindowReceipt,
  decodeWindowItem, decodeWindowPage, decodeWindowReceipt, formatWindowInterval, fixtureCapability,
  fixtureStaffWindows, fixtureWindows, isVersion, kindsForFulfillment, reasonProblem,
  scheduleAvailability, scheduleClosedReason, scheduleMessage, splitBeijingParts,
  staffStatusTagClass, staffWindowFormProblems, windowFormFromItem, windowFormProblems,
  windowStatusTagClass,
} from '../schedule/model'

const wire = (value: unknown): Record<string, unknown> => JSON.parse(JSON.stringify(value))
const withoutUpdatedAt = (): Record<string, any> => {
  const value: any = wire({
    windowId: '61001', merchantId: '958001', storeId: '958002', serviceId: '30001',
    windowKind: 'GENERAL', startAt: '2026-10-07T01:00:00.000Z', endAt: '2026-10-07T04:00:00.000Z',
    configuredCapacity: 2, status: 'OPEN', version: '4', updatedAt: '2026-10-06T02:00:00.000Z',
  })
  delete value.updatedAt
  return value
}
const baseWindowItem = () => wire({
  windowId: '61001', merchantId: '958001', storeId: '958002', serviceId: '30001',
  windowKind: 'GENERAL', startAt: '2026-10-07T01:00:00.000Z', endAt: '2026-10-07T04:00:00.000Z',
  configuredCapacity: 2, status: 'OPEN', version: '4', updatedAt: '2026-10-06T02:00:00.000Z',
})

test('window decoders are exact-key and fail closed', () => {
  assert.ok(decodeWindowItem(baseWindowItem()))
  for (const mutate of [
    (v: Record<string, any>) => { v.extra = 1 },
    (v: Record<string, any>) => { delete v.updatedAt },
    (v: Record<string, any>) => { v.windowId = 'abc' },
    (v: Record<string, any>) => { v.windowId = '0' },
    (v: Record<string, any>) => { v.version = '01' },
    (v: Record<string, any>) => { v.status = 'PAUSED' },
    (v: Record<string, any>) => { v.status = 'sold_out' },
    (v: Record<string, any>) => { v.windowKind = 'WEEKLY' },
    (v: Record<string, any>) => { v.configuredCapacity = 0 },
    (v: Record<string, any>) => { v.configuredCapacity = 1.5 },
    (v: Record<string, any>) => { v.startAt = '2026-10-07T01:00:00Z' },
    (v: Record<string, any>) => { v.endAt = '2026-10-07T04:00:00.000123Z' },
  ]) {
    const value = baseWindowItem()
    mutate(value)
    assert.throws(() => decodeWindowItem(value), /INVALID_RESPONSE/, JSON.stringify(value))
  }
  // SOLD_OUT is a first-class read status (§3.1): it decodes like OPEN/CLOSED.
  assert.equal(decodeWindowItem({ ...baseWindowItem(), status: 'SOLD_OUT' }).status, 'SOLD_OUT')
  const receipt = decodeWindowReceipt(withoutUpdatedAt())
  assert.equal(receipt.windowId, '61001')
  // Receipt shape rejects the read-only field (separate wire shapes).
  assert.throws(() => decodeWindowReceipt(baseWindowItem()), /INVALID_RESPONSE/)
  assert.throws(() => decodeWindowItem(withoutUpdatedAt()), /INVALID_RESPONSE/)
})

test('page and batch decoders enforce their envelopes and the 200-entry cap', () => {
  const page = decodeWindowPage({ storeId: '958002', items: [baseWindowItem()], page: 1, pageSize: 20, total: 1 })
  assert.equal(page.items.length, 1)
  assert.equal(page.total, 1)
  // Legacy unpaginated shape (no envelope keys) is refused: the pages always request §3.3
  // pagination, so a legacy answer is an INVALID_RESPONSE instead of a silent full-list
  // fallback that would corrupt the summary counts.
  assert.throws(() => decodeWindowPage({ storeId: '958002', items: [baseWindowItem()] }), /INVALID_RESPONSE/)
  assert.throws(() => decodeWindowPage({ storeId: '958002', items: [] as unknown, page: 1, pageSize: 20, total: 0, extra: 1 }), /INVALID_RESPONSE/)
  assert.throws(() => decodeWindowPage({ storeId: '958002' }), /INVALID_RESPONSE/)
  const receipt = withoutUpdatedAt()
  const batch = decodeBatchCloseResult({ storeId: '958002', closedWindows: [receipt], blockedWindows: [{ window: receipt, reasonCode: 'SCHEDULE_WINDOW_STATE_NOT_ALLOWED' }] })
  assert.equal(batch.blockedWindows[0].reasonCode, 'SCHEDULE_WINDOW_STATE_NOT_ALLOWED')
  assert.throws(() => decodeBatchCloseResult({ storeId: '958002', closedWindows: [receipt], blockedWindows: [{ window: receipt }] }), /INVALID_RESPONSE/)
  assert.throws(() => decodeBatchCloseResult({ storeId: '958002', closedWindows: [receipt], blockedWindows: [{ window: receipt, reasonCode: '' }] }), /INVALID_RESPONSE/)
  const many = Array.from({ length: 201 }, () => receipt)
  assert.throws(() => decodeBatchCloseResult({ storeId: '958002', closedWindows: many, blockedWindows: [] }), /INVALID_RESPONSE/)
  assert.ok(decodeBatchCloseResult({ storeId: '958002', closedWindows: many.slice(0, 200), blockedWindows: [] }))
})

test('paged window envelope enforces the §3.3 bounds (fail closed)', () => {
  const envelope = () => ({ storeId: '958002', items: [] as unknown[], page: 1, pageSize: 20, total: 0 })
  assert.equal(decodeWindowPage(envelope()).total, 0)
  assert.equal(decodeWindowPage({ ...envelope(), items: [baseWindowItem()], page: 10000, pageSize: 50, total: 999999 }).page, 10000)
  for (const mutate of [
    (v: Record<string, any>) => { v.page = 0 },
    (v: Record<string, any>) => { v.page = 10001 },
    (v: Record<string, any>) => { v.page = 1.5 },
    (v: Record<string, any>) => { v.page = '1' },
    (v: Record<string, any>) => { v.pageSize = 0 },
    (v: Record<string, any>) => { v.pageSize = 51 },
    (v: Record<string, any>) => { v.pageSize = 1.5 },
    (v: Record<string, any>) => { delete v.pageSize },
    (v: Record<string, any>) => { v.total = -1 },
    (v: Record<string, any>) => { v.total = 2.5 },
    (v: Record<string, any>) => { delete v.total },
    // A conformant server never answers more rows than the pageSize ceiling (sanity cap).
    (v: Record<string, any>) => { v.items = Array.from({ length: 51 }, () => baseWindowItem()) },
  ]) {
    const value = envelope()
    mutate(value)
    assert.throws(() => decodeWindowPage(value), /INVALID_RESPONSE/, JSON.stringify(value))
  }
  // Fifty rows is the §3.3 pageSize ceiling: still decodable.
  assert.equal(decodeWindowPage({ ...envelope(), items: Array.from({ length: 50 }, () => baseWindowItem()), pageSize: 50, total: 50 }).items.length, 50)
})

test('staff window and capability decoders are exact-key', () => {
  const item = {
    windowId: '62001', merchantId: '958001', storeId: '958002', staffId: '958003',
    startAt: '2026-10-07T01:00:00.000Z', endAt: '2026-10-07T04:00:00.000Z',
    status: 'AVAILABLE', version: '2', updatedAt: '2026-10-06T02:30:00.000Z',
  }
  assert.ok(decodeStaffWindowPage({ storeId: '958002', staffId: '958003', items: [item] }))
  assert.throws(() => decodeStaffWindowPage({ storeId: '958002', staffId: '958003', items: [{ ...item, status: 'OPEN' }] }), /INVALID_RESPONSE/)
  const { updatedAt: _omitted, ...receiptOnly } = item
  assert.equal(decodeStaffWindowReceipt(receiptOnly).staffId, '958003')
  assert.throws(() => decodeStaffWindowReceipt(item), /INVALID_RESPONSE/)
  assert.deepEqual(decodeCapabilityView(fixtureCapability()).serviceIds, ['30001', '30007'])
  assert.throws(() => decodeCapabilityView({ ...fixtureCapability(), serviceIds: ['30001', '30001'] }), /INVALID_RESPONSE/)
  assert.throws(() => decodeCapabilityView({ ...fixtureCapability(), extra: 1 }), /INVALID_RESPONSE/)
  assert.throws(() => decodeCapabilityView({ ...fixtureCapability(), version: '-1' }), /INVALID_RESPONSE/)
  assert.ok(isVersion('0'))
  assert.ok(!isVersion('00'))
})

test('status variants are explicit class names (no data-* reliance)', () => {
  assert.equal(windowStatusTagClass('OPEN'), 'sch-tag sch-tag-open')
  assert.equal(windowStatusTagClass('SOLD_OUT'), 'sch-tag sch-tag-soldout')
  assert.equal(windowStatusTagClass('CLOSED'), 'sch-tag sch-tag-closed')
  assert.equal(staffStatusTagClass('AVAILABLE'), 'sch-tag sch-tag-open')
  assert.equal(staffStatusTagClass('CLOSED'), 'sch-tag sch-tag-closed')
  assert.equal(chipClass(true), 'sch-chip sch-chip-selected')
  assert.equal(chipClass(false), 'sch-chip')
})

test('Beijing time helpers compose minute-precision offsets and split back', () => {
  const composed = composeTimestamp('2026-10-07', '09:30')
  assert.equal(composed, '2026-10-07T09:30:00.000+08:00')
  assert.throws(() => composeTimestamp('2026-10-07', '9:30'), /INVALID_RESPONSE/)
  assert.throws(() => composeTimestamp('2026-1-07', '09:30'), /INVALID_RESPONSE/)
  const parts = splitBeijingParts('2026-10-07T01:05:00.000Z')
  assert.deepEqual(parts, { date: '2026-10-07', time: '09:05' })
  const offsetInput = splitBeijingParts('2026-10-07T09:05:00.000+08:00')
  assert.deepEqual(offsetInput, { date: '2026-10-07', time: '09:05' })
  assert.equal(formatWindowInterval('2026-10-07T01:00:00.000Z', '2026-10-07T04:00:00.000Z'), '2026-10-07 09:00-12:00')
  assert.equal(formatWindowInterval('2026-10-07T01:00:00.000Z', '2026-10-08T02:00:00.000Z'), '2026-10-07 09:00 ~ 2026-10-08 10:00')
  assert.match(beijingToday(), /^\d{4}-\d{2}-\d{2}$/)
  assert.equal(addBeijingDays('2026-10-07', 1), '2026-10-08')
  assert.equal(batchRangeDays('2026-10-07', '2026-10-07'), 1)
  assert.equal(batchRangeDays('2026-10-07', '2026-10-08'), 2)
  assert.throws(() => batchRangeDays('2026-10-08', '2026-10-07'), /INVALID_RESPONSE/)
})

test('form pre-validation mirrors the server answers', () => {
  const good = windowFormFromItem({
    windowId: '61001', merchantId: '958001', storeId: '958002', serviceId: '30001',
    windowKind: 'GENERAL', startAt: '2026-10-07T01:00:00.000Z', endAt: '2026-10-07T04:00:00.000Z',
    configuredCapacity: 2, status: 'OPEN', version: '4', updatedAt: '2026-10-06T02:00:00.000Z',
  })
  assert.deepEqual(windowFormProblems(good), [])
  assert.ok(windowFormProblems({ ...good, serviceId: '' }).some(problem => problem.includes('服务')))
  assert.ok(windowFormProblems({ ...good, configuredCapacity: 0 }).some(problem => problem.includes('容量')))
  assert.ok(windowFormProblems({ ...good, endTime: '09:00' }).some(problem => problem.includes('结束时间')))
  const staffGood = { startDate: '2026-10-07', startTime: '09:00', endDate: '2026-10-07', endTime: '12:00' }
  assert.deepEqual(staffWindowFormProblems(staffGood), [])
  assert.ok(staffWindowFormProblems({ ...staffGood, endTime: '09:00' }).length > 0)
  // PRD29 页面引导: IN_STORE→GENERAL only, PICKUP_DELIVERY→PICKUP/RETURN, unknown→all.
  assert.deepEqual(kindsForFulfillment('IN_STORE'), ['GENERAL'])
  assert.deepEqual(kindsForFulfillment('PICKUP_DELIVERY'), ['PICKUP', 'RETURN'])
  assert.equal(kindsForFulfillment(null).length, 3)
  assert.ok(reasonProblem('', true))
  assert.equal(reasonProblem('装修', true), null)
  assert.ok(reasonProblem('x'.repeat(501), true))
  assert.equal(reasonProblem('', false), null)
  assert.ok(capabilityProblems(['30001', '30001'], ['30001'], 'reason').some(problem => problem.includes('重复')))
  assert.ok(capabilityProblems(['30001'], ['30001', '30007'], '').some(problem => problem.includes('原因')))
  assert.deepEqual(capabilityProblems(['30001', '30007'], ['30001', '30007'], ''), [])
})

test('availability classifies switch-off/dependency failure as closed; error text maps by code', () => {
  assert.equal(scheduleAvailability(new ApiError('COMMON_NOT_FOUND', 404)), 'closed')
  assert.equal(scheduleAvailability(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), 'closed')
  assert.equal(scheduleAvailability(new ApiError('COMMON_CONFLICT', 409)), 'ok')
  assert.equal(scheduleAvailability(new ApiError('COMMON_INVALID_ARGUMENT', 400)), 'ok')
  assert.equal(scheduleAvailability(new Error('x')), 'ok')
  assert.ok(scheduleClosedReason(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)).includes('事实'))
  assert.ok(scheduleClosedReason(new ApiError('COMMON_NOT_FOUND', 404)).includes('未开放'))
  assert.equal(scheduleMessage(new ApiError('SCHEDULE_WINDOW_OVERLAP', 409)).includes('重叠'), true)
  assert.equal(scheduleMessage(new ApiError('SCHEDULE_WINDOW_STATE_NOT_ALLOWED', 409)).includes('占用'), true)
  assert.equal(scheduleMessage(new ApiError('COMMON_CONFLICT', 409)).includes('版本冲突'), true)
  assert.equal(scheduleMessage(new ApiError('IDEMPOTENCY_KEY_CONFLICT', 409)).includes('请求编号'), true)
  assert.equal(scheduleMessage(new ApiError('COMMON_INVALID_ARGUMENT', 400)).includes('200'), true)
  assert.equal(scheduleMessage(new ApiError('SERVICE_STATE_NOT_ALLOWED', 409)).includes('不可经营'), true)
  assert.equal(scheduleMessage(new ApiError('COMMON_FORBIDDEN', 403)).includes('无权'), true)
  assert.equal(scheduleMessage(new Error('PENDING_WRITE_CHANGED')).includes('尚未确认'), true)
})

test('fixtures cover the open/partial/full/closed state space for preview', () => {
  const windows = fixtureWindows()
  assert.ok(windows.some(entry => entry.occupied >= entry.window.configuredCapacity)) // SOLD_OUT candidate
  assert.ok(windows.some(entry => entry.window.status === 'CLOSED'))
  assert.ok(fixtureStaffWindows().some(entry => entry.window.status === 'CLOSED'))
  assert.equal(fixtureCapability().version, '2')
})
