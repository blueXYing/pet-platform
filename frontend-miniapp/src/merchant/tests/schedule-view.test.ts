import test from 'node:test'
import assert from 'node:assert/strict'
import {
  capsuleTimeText, dayGroupLabel, durationMinutesText, groupWindowsByDate,
  statNumberClass, weekdayLabel, windowCapsuleClass, windowDurationMinutes,
} from '../schedule/view'
import { composeTimestamp, decodeWindowItem, fixtureWindows, type ScheduleWindowItem } from '../schedule/model'

// P-11a 排期工作台视图纯函数（schedule/view.ts）。视觉源 Figma 12:6214 只取胶囊/日期分组
// token；分组与文案必须保持分钟级语义（SSOT §12.1 无固定 60 分钟槽）——本文件同时钉死
// 「45 分钟窗口不得被换算/对齐成整点槽」的回归防线，并确认分组维度是北京历日而非周模板。
// model.ts 系既有事实：2026-10-07 是周三；fixtures 六个窗口都在该日。

const item = (overrides: Record<string, unknown>): ScheduleWindowItem => decodeWindowItem({
  windowId: '61001', merchantId: '958001', storeId: '958002', serviceId: '30001',
  windowKind: 'GENERAL', startAt: '2026-10-07T01:00:00.000Z', endAt: '2026-10-07T04:00:00.000Z',
  configuredCapacity: 2, status: 'OPEN', version: '4', updatedAt: '2026-10-06T02:00:00.000Z',
  ...overrides,
})

test('weekdayLabel 按北京历日取星期（2026-10-07 周三锚点）', () => {
  assert.equal(weekdayLabel('2026-10-07'), '周三')
  assert.equal(weekdayLabel('2026-10-08'), '周四')
  assert.equal(weekdayLabel('2026-11-12'), '周四')
  assert.equal(weekdayLabel('bad-date'), '')
})

test('dayGroupLabel 输出完整日期 + 星期', () => {
  assert.equal(dayGroupLabel('2026-10-07'), '2026-10-07 周三')
  assert.equal(dayGroupLabel('bad-date'), 'bad-date')
})

test('windowDurationMinutes 按端点差取真实分钟数（含 Z 与 ±08:00 两种线格式）', () => {
  assert.equal(windowDurationMinutes('2026-10-07T01:00:00.000Z', '2026-10-07T04:00:00.000Z'), 180)
  assert.equal(windowDurationMinutes(composeTimestamp('2026-10-07', '09:00'), composeTimestamp('2026-10-07', '09:45')), 45)
  assert.equal(windowDurationMinutes(composeTimestamp('2026-10-07', '23:30'), composeTimestamp('2026-10-08', '01:15')), 105)
})

test('durationMinutesText 直读分钟：不做整点对齐或 60 分钟槽换算', () => {
  assert.equal(durationMinutesText(45), '45分钟')
  assert.equal(durationMinutesText(60), '1小时')
  assert.equal(durationMinutesText(90), '1小时30分钟')
  assert.equal(durationMinutesText(150), '2小时30分钟')
  assert.equal(durationMinutesText(0), '')
  assert.equal(durationMinutesText(-30), '')
  assert.equal(durationMinutesText(Number.NaN), '')
})

test('capsuleTimeText 同历日只显示时刻，跨历日带两端月日（北京墙钟）', () => {
  assert.equal(capsuleTimeText(composeTimestamp('2026-10-07', '09:00'), composeTimestamp('2026-10-07', '12:00')), '09:00 - 12:00')
  // 23:30(北京) = 15:30Z；次日 01:15(北京) = 前一日 17:15Z
  assert.equal(capsuleTimeText('2026-10-07T15:30:00.000Z', '2026-10-07T17:15:00.000Z'), '10.07 23:30 ~ 10.08 01:15')
})

test('groupWindowsByDate 按起始北京历日分组并保持到达顺序', () => {
  const fixtures = fixtureWindows().map(entry => decodeWindowItem({ ...entry.window, updatedAt: entry.updatedAt }))
  const oneDay = groupWindowsByDate(fixtures)
  assert.equal(oneDay.length, 1)
  assert.equal(oneDay[0].date, '2026-10-07')
  assert.equal(oneDay[0].weekday, '周三')
  assert.equal(oneDay[0].items.length, fixtures.length)
  assert.deepEqual(oneDay[0].items.map(row => row.windowId), fixtures.map(row => row.windowId))

  const mixed = groupWindowsByDate([
    item({ windowId: '61002', startAt: '2026-10-08T02:00:00.000Z', endAt: '2026-10-08T05:00:00.000Z' }),
    item({ windowId: '61003', startAt: '2026-10-07T02:00:00.000Z', endAt: '2026-10-07T03:00:00.000Z' }),
    item({ windowId: '61004', startAt: '2026-10-08T06:00:00.000Z', endAt: '2026-10-08T06:45:00.000Z' }),
  ])
  assert.deepEqual(mixed.map(group => group.date), ['2026-10-08', '2026-10-07'])
  assert.deepEqual(mixed[0].items.map(row => row.windowId), ['61002', '61004'])
  assert.equal(mixed[1].items.length, 1)

  // 跨日窗口归入起始日一组（不做跨日切分，端点差完整呈现）
  const overnight = groupWindowsByDate([
    item({ windowId: '61005', startAt: '2026-10-07T15:30:00.000Z', endAt: '2026-10-07T17:15:00.000Z' }),
  ])
  assert.equal(overnight.length, 1)
  assert.equal(overnight[0].date, '2026-10-07')
  assert.equal(capsuleTimeText(overnight[0].items[0].startAt, overnight[0].items[0].endAt), '10.07 23:30 ~ 10.08 01:15')
})

test('windowCapsuleClass 显式类名映射，未知值回落基类', () => {
  assert.equal(windowCapsuleClass('OPEN'), 'sch-cap sch-cap-open')
  assert.equal(windowCapsuleClass('SOLD_OUT'), 'sch-cap sch-cap-soldout')
  assert.equal(windowCapsuleClass('CLOSED'), 'sch-cap sch-cap-closed')
  assert.equal(windowCapsuleClass('PAUSED' as never), 'sch-cap')
})

test('statNumberClass 显式类名映射，未知值回落基类', () => {
  assert.equal(statNumberClass('OPEN'), 'sch-v-stat-num sch-v-stat-num-open')
  assert.equal(statNumberClass('SOLD_OUT'), 'sch-v-stat-num sch-v-stat-num-soldout')
  assert.equal(statNumberClass('CLOSED'), 'sch-v-stat-num sch-v-stat-num-closed')
  assert.equal(statNumberClass('PAUSED' as never), 'sch-v-stat-num')
})
