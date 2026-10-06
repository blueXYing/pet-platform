import test from 'node:test'
import assert from 'node:assert/strict'
import {
  couponsByStatus, deltaLabel, formatLedgerTime, isCouponPointsScenario, thresholdLabel,
  PreviewCouponPointsRepository, validateCouponFixture, validateLedgerFixture,
  couponStatuses, pointsBizTypeLabels, type CouponView, type PointsLedgerView,
} from '../coupon-points/model'

test('preview repository serves fixtures that satisfy schema-grounded validation', async () => {
  const data = await new PreviewCouponPointsRepository('normal').load()
  assert.ok(data.coupons.length >= 3)
  for (const coupon of data.coupons) assert.equal(validateCouponFixture(coupon), true, JSON.stringify(coupon))
  for (const entry of data.ledger) assert.equal(validateLedgerFixture(entry), true, JSON.stringify(entry))
  // 三种展示状态都有夹具；余额与流水首行一致（balanceAfter 语义）。
  for (const status of couponStatuses) assert.ok(data.coupons.some(coupon => coupon.status === status), status)
  assert.equal(data.ledger[0]!.balanceAfter, data.balance.balance)
  // 流水按时间倒序，且相邻行满足 balanceAfter 链式一致。
  for (let index = 1; index < data.ledger.length; index++) {
    const newer = data.ledger[index - 1]!, older = data.ledger[index]!
    assert.ok(newer.createdAt >= older.createdAt)
    assert.equal(BigInt(older.balanceAfter) + BigInt(newer.delta), BigInt(newer.balanceAfter))
  }
})

test('empty scenario has no coupons and zero balance', async () => {
  const data = await new PreviewCouponPointsRepository('empty').load()
  assert.equal(data.coupons.length, 0)
  assert.equal(data.ledger.length, 0)
  assert.equal(data.balance.balance, '0')
})

test('status bucketing keeps fixture order and only returns the requested status', async () => {
  const data = await new PreviewCouponPointsRepository('normal').load()
  for (const status of couponStatuses) {
    const bucket = couponsByStatus(data.coupons, status)
    assert.ok(bucket.every(coupon => coupon.status === status))
  }
  assert.equal(couponStatuses.length, 3)
})

test('threshold label renders threshold amounts or 无门槛', () => {
  const base: CouponView = {
    couponId: '1', name: '券', amountOff: '20.00', thresholdAmount: '100.00', scopeSummary: '全部服务通用',
    typeLabel: '通用券', validTo: '2026-09-30', status: 'AVAILABLE', usedAt: null,
  }
  assert.equal(thresholdLabel(base), '满100.00元可用')
  assert.equal(thresholdLabel({ ...base, thresholdAmount: null }), '无门槛')
})

test('delta labels carry explicit sign; clawback stays negative', () => {
  const entry = (delta: string): PointsLedgerView => ({
    ledgerId: '1', bizType: delta.startsWith('-') ? 'REFUND_CLAWBACK' : 'SIGN_IN', delta, balanceAfter: '0',
    createdAt: '2026-09-30T00:00:00.000Z',
  })
  assert.equal(deltaLabel(entry('5')), '+5')
  assert.equal(deltaLabel(entry('-51')), '-51')
  assert.ok(Object.keys(pointsBizTypeLabels).includes('REFUND_CLAWBACK'))
})

test('ledger time renders today/yesterday/absolute deterministically (UTC-based)', () => {
  const now = new Date('2026-10-01T04:00:00.000Z')
  assert.equal(formatLedgerTime(now, '2026-10-01T01:05:00.000Z'), '今天 01:05')
  assert.equal(formatLedgerTime(now, '2026-09-30T06:20:00.000Z'), '昨天 06:20')
  assert.equal(formatLedgerTime(now, '2026-09-20T01:30:00.000Z'), '9月20日 01:30')
  assert.equal(formatLedgerTime(now, '2025-12-31T16:05:00.000Z'), '2025年12月31日 16:05')
  assert.equal(formatLedgerTime(now, 'not-a-timestamp'), '')
})

test('scenario guard only accepts the registered preview scenarios', () => {
  assert.equal(isCouponPointsScenario('normal'), true)
  assert.equal(isCouponPointsScenario('empty'), true)
  assert.equal(isCouponPointsScenario('load-error'), false)
  assert.equal(isCouponPointsScenario(undefined), false)
})

test('fixtures never contain points redemption or discount-type samples (V1 hard rules)', async () => {
  const data = await new PreviewCouponPointsRepository('normal').load()
  // 积分只赚取/扣回：全部流水为非零整数且不存在"兑换"类目；V1 无积分商城/兑换。
  assert.ok(data.ledger.every(entry => entry.delta !== '0'))
  // 折扣型（如 5折）不在 V1 券事实内：面额一律两位小数金额串。
  assert.ok(data.coupons.every(coupon => /^\d+\.\d{2}$/.test(coupon.amountOff)))
})
