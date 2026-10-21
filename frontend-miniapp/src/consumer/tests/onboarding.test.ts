import test from 'node:test'
import assert from 'node:assert/strict'
import { predictOnboardingTarget } from '../pages/onboarding/model'

// 开屏引导滑动方向预测（37号裁决新增页面）。覆盖两个方向对称性、端点钳制与阈值。

test('左滑前进一屏，右滑后退一屏', () => {
  assert.equal(predictOnboardingTarget(0, -120), 1)
  assert.equal(predictOnboardingTarget(1, -120), 2)
  assert.equal(predictOnboardingTarget(2, 120), 1)
  assert.equal(predictOnboardingTarget(1, 120), 0)
})

test('端点不换屏', () => {
  assert.equal(predictOnboardingTarget(0, 120), null)
  assert.equal(predictOnboardingTarget(2, -120), null)
})

test('位移不足阈值不换屏', () => {
  assert.equal(predictOnboardingTarget(1, -20), null)
  assert.equal(predictOnboardingTarget(1, 20), null)
  assert.equal(predictOnboardingTarget(0, 29), null)
})
