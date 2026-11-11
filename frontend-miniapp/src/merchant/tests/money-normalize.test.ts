import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeMoneyInput } from '../services/model'

// P-08 (2026-11-11): the price field let merchants type free-form digits ("128") while the
// submit check demanded the exact two-decimal contract form ("128.00") — the mismatch was
// reported as "销售价格未填写". normalizeMoneyInput bridges typing to the contract form and
// returns null for garbage so the strict problems list still catches it.
test('normalizeMoneyInput bridges integer and one-decimal input to the contract form', () => {
  assert.equal(normalizeMoneyInput('128'), '128.00')
  assert.equal(normalizeMoneyInput('128.5'), '128.50')
  assert.equal(normalizeMoneyInput('128.50'), '128.50')
  assert.equal(normalizeMoneyInput('0'), '0.00')
  assert.equal(normalizeMoneyInput(' 60 '), '60.00')
})

test('normalizeMoneyInput keeps empty and null as null for the missing-field check', () => {
  assert.equal(normalizeMoneyInput(null), null)
  assert.equal(normalizeMoneyInput(''), null)
})

test('normalizeMoneyInput refuses non-money garbage instead of rewriting it', () => {
  assert.equal(normalizeMoneyInput('12.345'), null)
  assert.equal(normalizeMoneyInput('1.2.3'), null)
  assert.equal(normalizeMoneyInput('abc'), null)
  assert.equal(normalizeMoneyInput('-5'), null)
})

test('normalized prices pass the existing draft price checks without problems', () => {
  // Sanity: the normalized form satisfies the strict two-decimal submit regex.
  for (const raw of ['128', '0', '9.9']) {
    const normalized = normalizeMoneyInput(raw)!
    assert.match(normalized, /^\d+\.\d{2}$/)
  }
})
