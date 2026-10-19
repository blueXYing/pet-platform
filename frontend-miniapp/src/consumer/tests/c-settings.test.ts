import test from 'node:test'
import assert from 'node:assert/strict'
import {
  blockerFallbackLabels, decodeDeletionReceipt, decodePrecheck, faqGroups,
  privacyPolicyDoc, SERVICE_HOTLINE, userAgreementDoc,
} from '../settings/model'
import {
  codePointLength, decodeCreateReceipt, decodeTicketDetail, decodeTicketPage,
  feedbackTypeLabel, feedbackTypeOptions, formatTicketTime, validateFeedback,
} from '../feedback/model'

// 契约 66/67 收尾批次模型单测：注销前置校验投影词表、回执解码、反馈四类型/500 字口径、
// 状态词表、预览截断与 FAQ/静态协议内容完整性（帮助与客服承载=静态内容+热线，CCR-CS-FEEDBACK-001）。

test('deletion precheck decode keeps the frozen blocker catalog and blocked/len pairing', () => {
  const clean = decodePrecheck({ blocked: false, blockers: [] })
  assert.equal(clean.blocked, false)
  assert.deepEqual(clean.blockers, [])
  const blocked = decodePrecheck({
    blocked: true,
    blockers: [{ code: 'USER_ORDERS_UNSETTLED', label: '存在未完结订单', detail: '先完结订单' }],
  })
  assert.equal(blocked.blocked, true)
  assert.deepEqual(Object.keys(blockerFallbackLabels).sort(), [
    'OWNER_STORE_AFTERSALES_OPEN', 'OWNER_STORE_ORDERS_UNSETTLED',
    'USER_AFTERSALES_OPEN', 'USER_ORDERS_UNSETTLED',
  ])
  assert.throws(() => decodePrecheck({ blocked: false, blockers: [{ code: 'HACK', label: 'x', detail: 'y' }] }))
  assert.throws(() => decodePrecheck({ blocked: true, blockers: [] }))
})

test('deletion receipt decode only accepts the CANCELED outcome', () => {
  const receipt = decodeDeletionReceipt({ userId: '620001', status: 'CANCELED', deletedAt: '2026-10-19T03:00:00.000Z' })
  assert.equal(receipt.status, 'CANCELED')
  assert.throws(() => decodeDeletionReceipt({ userId: '620001', status: 'DELETED', deletedAt: 'x' }))
})

test('feedback type vocabulary stays the four PRD values with stable labels', () => {
  assert.deepEqual(feedbackTypeOptions.map(option => option.code), [
    'FEATURE_SUGGESTION', 'BUG_REPORT', 'SERVICE_COMPLAINT', 'OTHER',
  ])
  assert.equal(feedbackTypeLabel('SERVICE_COMPLAINT'), '服务投诉')
  assert.equal(feedbackTypeLabel('UNKNOWN'), '其他')
})

test('feedback validation enforces type/contact/content bounds exactly', () => {
  assert.equal(validateFeedback({ type: 'OTHER', contact: '', content: '问题' }), null)
  assert.equal(validateFeedback({ type: null, contact: '', content: '问题' }), '请选择反馈类型')
  assert.equal(validateFeedback({ type: 'OTHER', contact: '', content: '   ' }), '请填写反馈内容')
  assert.equal(validateFeedback({ type: 'OTHER', contact: '', content: '好'.repeat(501) }), '反馈内容最多500字')
  assert.equal(validateFeedback({ type: 'OTHER', contact: '微'.repeat(65), content: '问题' }), '联系方式最多64字')
  assert.equal(codePointLength('宠物a🐱'), 4)
})

test('ticket decoders keep owner-scoped shapes and reject unexpected fields', () => {
  const summary = {
    ticketId: '730001', type: 'BUG_REPORT', status: 'PENDING',
    contentPreview: '摘要', createdAt: '2026-10-19T03:00:00.000Z',
  }
  const page = decodeTicketPage({ items: [summary], page: 1, pageSize: 20, total: 1 })
  assert.equal(page.total, 1)
  const created = decodeCreateReceipt({ ticketId: '730001', type: 'BUG_REPORT', status: 'PENDING', createdAt: 'x' })
  assert.equal(created.ticketId, '730001')
  const detail = decodeTicketDetail({
    ...summary, content: '全文', contact: null,
    events: [{ action: 'SUBMITTED', createdAt: '2026-10-19T03:00:00.000Z' }],
    replies: [], resolution: null,
  })
  assert.equal(detail.content, '全文')
  assert.equal(detail.replies.length, 0)
  assert.throws(() => decodeTicketDetail({ ...summary, content: '全文', contact: null, events: [], replies: [{ text: '提前出现回复' }], resolution: null }))
  // CLOSED 在冻结状态词表内，列表解码接受；越界状态才拒绝。
  assert.throws(() => decodeTicketPage({ items: [{ ...summary, status: 'REOPENED' }], page: 1, pageSize: 20, total: 1 }))
  assert.equal(decodeTicketPage({ items: [{ ...summary, status: 'CLOSED' }], page: 1, pageSize: 20, total: 1 }).total, 1)
})

test('ticket time renders a compact local form and passes unknown strings through', () => {
  assert.equal(formatTicketTime('2026-10-19T03:00:00.000Z'), formatTicketTime(new Date('2026-10-19T03:00:00.000Z').toISOString()))
  assert.equal(formatTicketTime('不是时间'), '不是时间')
})

test('help FAQ stays grouped and rewritten to approved rules; hotline constant exists', () => {
  assert.deepEqual(faqGroups.map(group => group.group), ['订单与服务', '账户与支付', '服务者与安全'])
  const cancel = faqGroups[0].items.find(item => item.question === '如何取消订单？')
  assert.ok(cancel)
  // CCR-CS-FEEDBACK-001 §4：V1 无 C 端普通取消入口，答案必须按"不支持自行取消+服务前退款全额"改写。
  assert.ok(cancel.answer.includes('不支持自行取消'))
  assert.ok(cancel.answer.includes('自动全额'))
  assert.match(SERVICE_HOTLINE, /^400-/)
})

test('static policy pages carry review-required sections (privacy policy + user agreement)', () => {
  assert.equal(privacyPolicyDoc.title, '隐私政策')
  assert.ok(privacyPolicyDoc.sections.length >= 5)
  assert.ok(privacyPolicyDoc.sections.some(section => section.heading.includes('信息的存储')))
  assert.equal(userAgreementDoc.title, '用户协议')
  assert.ok(userAgreementDoc.sections.some(section => section.heading.includes('退款与售后')))
})
