import test from 'node:test'
import assert from 'node:assert/strict'
import { isMerchantStoreProfilePath, isMerchantReviewPath } from '../consumer-api'

// CCR-M-STORE-AVATAR-001 (contract 27 §6.4 2026-11-06 supplement): the avatar signing read
// is the only extra path in the store-profile family — GET on the exact avatar-url path.
test('avatar-url joins the store-profile allowlist as an exact GET only', () => {
  assert.equal(isMerchantStoreProfilePath({ method: 'GET', path: '/api/v1/merchant/store-profile/avatar-url' }), true)
  // The profile itself keeps both verbs.
  assert.equal(isMerchantStoreProfilePath({ method: 'GET', path: '/api/v1/merchant/store-profile' }), true)
  assert.equal(isMerchantStoreProfilePath({ method: 'PUT', path: '/api/v1/merchant/store-profile' }), true)
  // Lookalikes and other verbs stay outside the family.
  assert.equal(isMerchantStoreProfilePath({ method: 'PUT', path: '/api/v1/merchant/store-profile/avatar-url' }), false)
  assert.equal(isMerchantStoreProfilePath({ method: 'GET', path: '/api/v1/merchant/store-profile/avatar' }), false)
  assert.equal(isMerchantStoreProfilePath({ method: 'GET', path: '/api/v1/merchant/store-profile/avatar-url/x' }), false)
  assert.equal(isMerchantStoreProfilePath({ method: 'POST', path: '/api/v1/merchant/store-profile' }), false)
})

// CCR-M-REVIEW-VIEW-001 (contract 56 §7 2026-11-06 supplement): the score aggregate is a
// literal-path GET and must not be captured by the {reviewId} numeric rule.
test('reviews/summary is a literal GET before the numeric detail rule', () => {
  assert.equal(isMerchantReviewPath({ method: 'GET', path: '/api/v1/merchant/reviews/summary' }), true)
  assert.equal(isMerchantReviewPath({ method: 'GET', path: '/api/v1/merchant/reviews' }), true)
  assert.equal(isMerchantReviewPath({ method: 'GET', path: '/api/v1/merchant/reviews/710402' }), true)
  assert.equal(isMerchantReviewPath({ method: 'POST', path: '/api/v1/merchant/reviews/710402/appeal' }), true)
  assert.equal(isMerchantReviewPath({ method: 'POST', path: '/api/v1/merchant/reviews/summary' }), false)
  assert.equal(isMerchantReviewPath({ method: 'GET', path: '/api/v1/merchant/reviews/summaryx' }), false)
  assert.equal(isMerchantReviewPath({ method: 'GET', path: '/api/v1/merchant/reviews/summary/1' }), false)
})
