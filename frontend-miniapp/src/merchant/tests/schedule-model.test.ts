import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../shared/request'
import {
  addBeijingDays, batchGenerateProblems, batchRangeDays, beijingToday, bookingWindowFormProblems,
  capabilityProblems, chipClass, composeTimestamp, decodeBatchCloseResult, decodeBatchGenerateResult,
  decodeBookingWindowReceipt, decodeBookingWindowView, decodeCapabilityView, decodeStaffWindowPage,
  decodeStaffWindowReceipt, decodeWindowItem, decodeWindowPage, decodeWindowReceipt,
  defaultWeekDraft, formatWindowInterval, fixtureCapability, fixtureStaffWindows, fixtureWindows,
  generateFormProblems, isVersion, nextSevenDays, reasonProblem,
  scheduleAvailability, scheduleClosedReason, scheduleMessage, splitBeijingParts,
  staffStatusTagClass, staffWindowFormProblems, weekdayIndexOf, weekdayLabels, weekDraftProblems,
  windowFormFromItem, windowFormProblems, windowStatusTagClass,
} from '../schedule/model'

const wire = (value: unknown): Record<string, unknown> => JSON.parse(JSON.stringify(value))
const withoutUpdatedAt = (): Record<string, any> => {
  const value: any = wire({
    windowId: '61001', merchantId: '958001', storeId: '958002',
    startAt: '2026-10-07T01:00:00.000Z', endAt: '2026-10-07T04:00:00.000Z',
    configuredCapacity: 2, status: 'OPEN', version: '4', updatedAt: '2026-10-06T02:00:00.000Z',
  })
  delete value.updatedAt
  return value
}
const baseWindowItem = () => wire({
  windowId: '61001', merchantId: '958001', storeId: '958002',
  startAt: '2026-10-07T01:00:00.000Z', endAt: '2026-10-07T04:00:00.000Z',
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
    // CCR-SCH-SLOT-001: the legacy per-service/kind fields are now UNKNOWN keys — a server
    // still answering the old shape must fail closed, never silently drop the dimension.
    (v: Record<string, any>) => { v.serviceId = '30001' },
    (v: Record<string, any>) => { v.windowKind = 'GENERAL' },
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
    windowId: '61001', merchantId: '958001', storeId: '958002',
    startAt: '2026-10-07T01:00:00.000Z', endAt: '2026-10-07T04:00:00.000Z',
    configuredCapacity: 2, status: 'OPEN', version: '4', updatedAt: '2026-10-06T02:00:00.000Z',
  })
  assert.deepEqual(windowFormProblems(good), [])
  // Unified slot: the form carries ONLY interval + capacity (no serviceId/windowKind fields
  // even exist to validate — CCR-SCH-SLOT-001 §8.1「开窗从三步变直接选时间+容量」).
  assert.deepEqual(Object.keys(good).sort(), ['configuredCapacity', 'endDate', 'endTime', 'startDate', 'startTime'])
  assert.ok(windowFormProblems({ ...good, configuredCapacity: 0 }).some(problem => problem.includes('容量')))
  assert.ok(windowFormProblems({ ...good, endTime: '09:00' }).some(problem => problem.includes('结束时间')))
  const staffGood = { startDate: '2026-10-07', startTime: '09:00', endDate: '2026-10-07', endTime: '12:00' }
  assert.deepEqual(staffWindowFormProblems(staffGood), [])
  assert.ok(staffWindowFormProblems({ ...staffGood, endTime: '09:00' }).length > 0)
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

// ---------------------------------------------------------------------------
// CCR-SCH-SLOT-002（batch-generate + booking-window + 周模板草稿态）。
// ---------------------------------------------------------------------------
const receipt = (windowId: string, startAt: string, endAt: string) => ({
  windowId, merchantId: '958001', storeId: '958002',
  startAt, endAt, configuredCapacity: 6, status: 'OPEN' as const, version: '3',
})

test('batch-generate decoder is exact-key and enforces the 200-candidate cap', () => {
  const base = () => ({
    storeId: '958002',
    createdWindows: [receipt('61101', '2026-11-13T01:00:00.000Z', '2026-11-13T02:00:00.000Z')],
    closedWindows: [receipt('61001', '2026-11-13T01:00:00.000Z', '2026-11-13T04:00:00.000Z')],
    skippedWindows: [{ window: receipt('61003', '2026-11-13T11:30:00.000Z', '2026-11-13T13:00:00.000Z'), reasonCode: 'OCCUPIED' }],
  })
  const good = decodeBatchGenerateResult(base())
  assert.equal(good.createdWindows.length, 1)
  assert.equal(good.skippedWindows[0]!.reasonCode, 'OCCUPIED')
  // batch-close naming stays a separate wire shape (blockedWindows ≠ skippedWindows).
  assert.throws(() => decodeBatchCloseResult(base()), /INVALID_RESPONSE/)
  assert.throws(() => decodeBatchGenerateResult(decodeBatchCloseResult({
    storeId: '958002', closedWindows: [], blockedWindows: [],
  })), /INVALID_RESPONSE/)
  for (const mutate of [
    (v: Record<string, any>) => { delete v.skippedWindows },
    (v: Record<string, any>) => { v.extra = 1 },
    (v: Record<string, any>) => { v.skippedWindows[0].reasonCode = '' },
    (v: Record<string, any>) => { v.skippedWindows[0].other = 1 },
  ]) {
    const value: any = base()
    mutate(value)
    assert.throws(() => decodeBatchGenerateResult(value), /INVALID_RESPONSE/)
  }
  // §1/§3.2 candidate cap: created+closed+skipped beyond 200 fails closed.
  const crowded = {
    storeId: '958002',
    createdWindows: Array.from({ length: 201 }, (_, index) => receipt(String(70000 + index),
      '2026-11-13T01:00:00.000Z', '2026-11-13T02:00:00.000Z')),
    closedWindows: [], skippedWindows: [],
  }
  assert.throws(() => decodeBatchGenerateResult(crowded), /INVALID_RESPONSE/)
})

test('booking-window decoders are exact-key with lead 0..1440 / maxDays 1..90 bounds', () => {
  // CCR-SCH-SLOT-002 §2 wire shape: four keys (no merchantId/storeId); updatedAt may be
  // null while the default row is unset (the acceptance round caught the six-key mismatch).
  const view = {
    leadMinutes: 60, maxDays: 7,
    version: '0', updatedAt: '2026-11-13T02:00:00.000Z',
  }
  assert.equal(decodeBookingWindowView(view).leadMinutes, 60)
  assert.equal(decodeBookingWindowView({ ...view, updatedAt: null }).updatedAt, null)
  assert.throws(() => decodeBookingWindowView({ ...view, merchantId: '958001' }), /INVALID_RESPONSE/)
  // Receipt is a separate shape (no updatedAt on the wire).
  assert.throws(() => decodeBookingWindowReceipt(view), /INVALID_RESPONSE/)
  const receiptShape = { leadMinutes: 30, maxDays: 14, version: '1' }
  assert.equal(decodeBookingWindowReceipt(receiptShape).maxDays, 14)
  assert.throws(() => decodeBookingWindowView(receiptShape), /INVALID_RESPONSE/)
  for (const mutate of [
    (v: Record<string, any>) => { v.leadMinutes = -1 },
    (v: Record<string, any>) => { v.leadMinutes = 1441 },
    (v: Record<string, any>) => { v.leadMinutes = 1.5 },
    (v: Record<string, any>) => { v.maxDays = 0 },
    (v: Record<string, any>) => { v.maxDays = 91 },
    (v: Record<string, any>) => { v.extra = 1 },
  ]) {
    const value: any = { ...view }
    mutate(value)
    assert.throws(() => decodeBookingWindowView(value), /INVALID_RESPONSE/)
  }
})

test('generate / booking draft validation mirrors the CCR §1/§2 bounds', () => {
  assert.deepEqual(generateFormProblems({ dailyStart: '09:00', dailyEnd: '19:00', configuredCapacity: 6 }), [])
  assert.ok(generateFormProblems({ dailyStart: '19:00', dailyEnd: '19:00', configuredCapacity: 6 }).some(p => p.includes('结束')))
  assert.ok(generateFormProblems({ dailyStart: '09:00', dailyEnd: '19:00', configuredCapacity: 1000 }).some(p => p.includes('容量')))
  assert.ok(generateFormProblems({ dailyStart: '09:00', dailyEnd: '19:00', configuredCapacity: null }).some(p => p.includes('容量')))
  const good = { dates: ['2026-11-13', '2026-11-19'],
    segments: [{ start: '09:00', end: '10:00' }, { start: '10:00', end: '11:00' }],
    configuredCapacity: 6, reason: '一键生成' }
  assert.deepEqual(batchGenerateProblems(good), [])
  assert.ok(batchGenerateProblems({ ...good, segments: [...good.segments, { start: '10:30', end: '11:30' }] }).some(p => p.includes('重叠')))
  assert.ok(batchGenerateProblems({ ...good, segments: [{ start: '19:00', end: '09:00' }] }).some(p => p.includes('每段')))
  assert.ok(batchGenerateProblems({ ...good, dates: [...good.dates, '2026-11-13'] }).some(p => p.includes('不重复')))
  assert.ok(batchGenerateProblems({ ...good, reason: ' ' }).some(p => p.includes('原因')))
  assert.deepEqual(bookingWindowFormProblems({ leadMinutes: 0, maxDays: 1 }), [])
  assert.deepEqual(bookingWindowFormProblems({ leadMinutes: 1440, maxDays: 90 }), [])
  assert.ok(bookingWindowFormProblems({ leadMinutes: 1441, maxDays: 7 }).some(p => p.includes('分钟')))
  assert.ok(bookingWindowFormProblems({ leadMinutes: 60, maxDays: 91 }).some(p => p.includes('天数')))
  assert.ok(bookingWindowFormProblems({ leadMinutes: null, maxDays: null }).length === 2)
})

test('week draft defaults validate; overlap / empty / inverted segments are named per day', () => {
  const draft = defaultWeekDraft()
  assert.equal(draft.length, 7)
  assert.deepEqual(weekDraftProblems(draft), [])
  assert.deepEqual(draft[0], { enabled: true, segments: [{ start: '09:00', end: '19:00' }] })
  const overlapped = draft.map((day, index) => index === 1
    ? { enabled: true, segments: [{ start: '09:00', end: '12:00' }, { start: '11:00', end: '13:00' }] }
    : day)
  assert.ok(weekDraftProblems(overlapped).some(problem => problem.startsWith('周二：') && problem.includes('重叠')))
  // 相邻半开可衔接（12:00 起 = 上一段终点）。
  const adjacent = draft.map((day, index) => index === 1
    ? { enabled: true, segments: [{ start: '09:00', end: '12:00' }, { start: '12:00', end: '13:00' }] }
    : day)
  assert.deepEqual(weekDraftProblems(adjacent), [])
  const emptyEnabled = draft.map((day, index) => index === 2 ? { enabled: true, segments: [] } : day)
  assert.ok(weekDraftProblems(emptyEnabled).some(problem => problem.startsWith('周三：') && problem.includes('至少')))
  const inverted = draft.map((day, index) => index === 0
    ? { enabled: true, segments: [{ start: '12:00', end: '12:00' }] } : day)
  assert.ok(weekDraftProblems(inverted).some(problem => problem.startsWith('周一：') && problem.includes('结束')))
})

test('weekdayIndexOf / nextSevenDays map Beijing dates onto 周一..周日 (2026-11-13 周五锚点)', () => {
  assert.equal(weekdayIndexOf('2026-11-13'), 4, '2026-11-13 is Friday')
  assert.equal(weekdayIndexOf('2026-11-16'), 0, '2026-11-16 is Monday')
  assert.equal(weekdayIndexOf('2026-11-15'), 6, '2026-11-15 is Sunday')
  const week = nextSevenDays('2026-11-13')
  assert.deepEqual(week.map(day => day.date),
    ['2026-11-13', '2026-11-14', '2026-11-15', '2026-11-16', '2026-11-17', '2026-11-18', '2026-11-19'])
  assert.deepEqual(week.map(day => weekdayLabels[day.weekday]),
    ['周五', '周六', '周日', '周一', '周二', '周三', '周四'])
})

test('fixtures cover the open/partial/full/closed state space for preview', () => {
  const windows = fixtureWindows()
  assert.ok(windows.some(entry => entry.occupied >= entry.window.configuredCapacity)) // SOLD_OUT candidate
  assert.ok(windows.some(entry => entry.window.status === 'CLOSED'))
  assert.ok(fixtureStaffWindows().some(entry => entry.window.status === 'CLOSED'))
  assert.equal(fixtureCapability().version, '2')
  // CCR-SCH-SLOT-001 §2 同店不重叠：fixture 态本身必须服从统一槽不变量（CLOSED 不参与）。
  for (const entry of windows) {
    if (entry.window.status === 'CLOSED') continue
    for (const peer of windows) {
      if (peer === entry || peer.window.status === 'CLOSED') continue
      const overlaps = entry.window.startAt < peer.window.endAt && peer.window.startAt < entry.window.endAt
      assert.equal(overlaps, false, `${entry.window.windowId} overlaps ${peer.window.windowId}`)
    }
  }
})
