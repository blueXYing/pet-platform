import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ApiError } from '../../shared/request'
import { appealMessage, appealTagClass, appealTagText, canAppealEntry, reasonDraftError } from '../review-appeal/model'
import { appealReasonInput, decodeAppealReceipt, decodeReviewDetail, decodeReviewPage, isDefiniteAppealConflict } from '../../shared/review-appeal-api'

const summary = { reviewId: '9001', orderId: '8001', storeScore: '5.0', serviceScore: '4.0', staffScore: '3.0', compositeScore: '4.2', scoreIncluded: true, visibilityStatus: 'PUBLISHED' as const, content: '服务尚可', createdAt: '2026-10-01T02:00:00.000Z', appealStatus: null, appealId: null }

test('appeal tag variants use class names because WXSS attribute selectors never match', () => {
  assert.equal(appealTagClass(summary), 'mrv-tag mrv-tag-none')
  assert.equal(appealTagClass({ ...summary, appealStatus: 'SUBMITTED' }), 'mrv-tag')
  assert.equal(appealTagClass({ ...summary, appealStatus: 'APPROVED' }), 'mrv-tag mrv-tag-approved')
  assert.equal(appealTagClass({ ...summary, appealStatus: 'REJECTED' }), 'mrv-tag mrv-tag-closed')
  assert.equal(appealTagClass({ ...summary, visibilityStatus: 'HIDDEN' }), 'mrv-tag mrv-tag-approved')
})

test('appeal tag text prefers the terminal visibility fact', () => {
  assert.equal(appealTagText(summary), '未申诉')
  assert.equal(appealTagText({ ...summary, appealStatus: 'SUBMITTED' }), '申诉处理中')
  assert.equal(appealTagText({ ...summary, appealStatus: 'APPROVED' }), '申诉成立')
  assert.equal(appealTagText({ ...summary, appealStatus: 'REJECTED' }), '申诉未成立')
  assert.equal(appealTagText({ ...summary, visibilityStatus: 'HIDDEN' }), '已隐藏')
})

test('appeal entry only for a published not-yet-appealed review with write access', () => {
  assert.equal(canAppealEntry(summary, true), true)
  assert.equal(canAppealEntry(summary, false), false)
  assert.equal(canAppealEntry({ ...summary, appealStatus: 'REJECTED' }, true), false)
  assert.equal(canAppealEntry({ ...summary, appealStatus: 'SUBMITTED' }, true), false)
  assert.equal(canAppealEntry({ ...summary, visibilityStatus: 'HIDDEN' }, true), false)
})

test('reason boundary: trimmed non-blank 1..1000 code points, no lone surrogates', () => {
  assert.equal(reasonDraftError('  内容失实  '), null)
  assert.equal(reasonDraftError('   '), '请填写申诉理由')
  assert.equal(reasonDraftError('长'.repeat(1001)), '申诉理由不能超过 1000 个字符')
  assert.equal(reasonDraftError('无效\u{D800}字符'), '申诉理由包含无效字符')
  assert.equal(appealReasonInput('  平台规则  '), '平台规则')
  assert.throws(() => appealReasonInput('  '), /APPEAL_REASON_LENGTH/)
})

test('contract decoders fail closed on unknown or malformed shapes', () => {
  const page = { page: 1, pageSize: 20, total: 1, items: [summary] }
  assert.deepEqual(decodeReviewPage(page).items[0].compositeScore, '4.2')
  assert.throws(() => decodeReviewPage({ ...page, items: [{ ...summary, extra: 1 }] }), /INVALID_RESPONSE/)
  assert.throws(() => decodeReviewPage({ ...page, items: [{ ...summary, compositeScore: '4.25' }] }), /INVALID_RESPONSE/)
  assert.throws(() => decodeReviewPage({ ...page, items: [{ ...summary, reviewId: 'x' }] }), /INVALID_RESPONSE/)
  assert.throws(() => decodeReviewPage({ ...page, total: '1' }), /INVALID_RESPONSE/)
  const detail = { ...summary, appealReason: '内容失实', appealCreatedAt: '2026-10-02T02:00:00.000Z', decisionReason: null, decidedAt: null }
  assert.equal(decodeReviewDetail(detail).appealReason, '内容失实')
  assert.throws(() => decodeReviewDetail({ ...detail, decidedAt: '2026-10-02' }), /INVALID_RESPONSE/)
  const receipt = { appealId: '9500', reviewId: '9001', status: 'SUBMITTED', createdAt: '2026-10-02T02:00:00.000Z' }
  assert.equal(decodeAppealReceipt(receipt).status, 'SUBMITTED')
  // PROCESSING is a reserved Schema06 value: decodable, never produced by this slice.
  assert.equal(decodeAppealReceipt({ ...receipt, status: 'PROCESSING' }).status, 'PROCESSING')
  assert.throws(() => decodeAppealReceipt({ ...receipt, status: 'DONE' }), /INVALID_RESPONSE/)
})

test('definite appeal conflicts are exactly the terminal one-time 409 family', () => {
  assert.equal(isDefiniteAppealConflict(new ApiError('REVIEW_APPEAL_ALREADY_USED', 409)), true)
  // COMMON_CONFLICT 是锁忙/未知结局（23号 §5.7）：不退役命令，只能按原 UUID 重试。
  assert.equal(isDefiniteAppealConflict(new ApiError('COMMON_CONFLICT', 409)), false)
  assert.equal(isDefiniteAppealConflict(new ApiError('IDEMPOTENCY_KEY_CONFLICT', 409)), false)
  assert.equal(isDefiniteAppealConflict(new ApiError('REVIEW_APPEAL_ALREADY_USED', 400)), false)
  assert.equal(isDefiniteAppealConflict(new Error('x')), false)
})

test('appeal page messages map the 56号 error face for the merchant', () => {
  assert.equal(appealMessage(new ApiError('REVIEW_APPEAL_ALREADY_USED', 409)), '每条评价最多申诉一次')
  assert.equal(appealMessage(new ApiError('IDEMPOTENCY_KEY_CONFLICT', 409)), '上次提交的申诉理由已变化，请核对后重新提交')
  assert.equal(appealMessage(new ApiError('REVIEW_NOT_FOUND', 404)), '评价不存在或仅本店可申诉')
  assert.equal(appealMessage(new ApiError('COMMON_FORBIDDEN', 403)), '当前账号无法执行该操作')
  assert.equal(appealMessage(new Error('PENDING_WRITE_CHANGED')), '上次申诉结果尚未确认，请先重试原操作')
  assert.equal(appealMessage(new Error('APPEAL_REASON_LENGTH')), '请填写 1~1000 个字符的申诉理由')
})
