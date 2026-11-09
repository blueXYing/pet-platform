import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import { AfterSaleClient, decodeDetail, decodePage, decodeSummary } from '../../shared/aftersale-api'
import type { UploadFiles } from '../../shared/private-asset-upload'
import type { Transport, WireRequest } from '../../shared/request'
import { merchantAfterSaleDeps } from '../aftersale/repository'
import { caseCustomerInitial, caseCustomerName, casePetLabel, caseServiceLine } from '../aftersale/model'

// CCR-M-AFTERSALE-VIEW-001（2026-11-06 批准）：商家售后读侧追加 5 个可空展示键
// customerNickname/serviceName/petName/petType/petBreed（仅商家路由；C/admin 面键集不变）。
// 本文件覆盖：商家严格解码（旧形状或旧形状+5 键）、C 端解码零变化、真实客户端/仓储端到端、
// 页面降级占位不伪造、无手机号、按钮 id/路由不变、新增样式 .mas-page 复合选择器。

const labels = { customerNickname: 'E2E体验用户', serviceName: 'E2E到店精细洗护', petName: '豆豆', petType: 'DOG', petBreed: '柯基' }
const nullLabels = { customerNickname: null, serviceName: null, petName: null, petType: null, petBreed: null }
const summary = { afterSaleId: '301', orderId: '401', merchantId: '501', storeId: '601', status: 'PENDING', version: '0', sourceStage: 'VERIFIED', typeCode: 'SERVICE_QUALITY', demandCode: 'REFUND', requestedAmount: '60.00', createdAt: '2026-11-05T08:15:32.649Z', deadline: '2026-11-12T08:15:32.649Z' }
const { merchantId: _merchant, storeId: _store, ...common } = summary
const detail = { ...common, description: '服务完成后发现毛发修剪不齐的问题', supplementRequestId: null, supplementTarget: null, supplementDeadline: null, supplementReason: null, finalSetVersion: 'a'.repeat(64), priorFinalCaseIds: [], newProblemStatement: null, decisionType: null, refundAmount: null, decisionReason: null,
  evidence: [{ batchId: '701', submitterType: 'USER', text: '服务完成后发现毛发修剪不齐的问题', opinionCode: null, submittedAt: '2026-11-05T08:15:32.649Z', assetIds: [] }] }
const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', message: 'ok', data, traceId: 'fixture-trace' } })
const root = fileURLToPath(new URL('../../..', import.meta.url))
const pageSource = (file: string) => readFileSync(path.join(root, 'src', 'merchant', 'pages', 'aftersale', file), 'utf8')

async function setup(handler: Transport) {
  const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
  const saved = new Map<string, unknown>([['pet.c.session.v1', { ...session, tokenType: 'Bearer', accessToken: 'test-only-unusable' }]])
  const store: LocalStore = { get: key => saved.get(key), set: (key, value) => { saved.set(key, structuredClone(value)) }, remove: key => { saved.delete(key) } }
  const calls: WireRequest[] = []
  const api = new ConsumerApi(async request => { calls.push(structuredClone(request)); return request.path.endsWith('/auth/session') ? ok(session) : handler(request) }, store, async () => randomUUID())
  await api.restore()
  return { api, store, calls }
}

test('merchant decoders accept the five nullable label keys and the legacy shape, always yielding all five', () => {
  assert.deepEqual(decodeSummary({ ...summary, ...labels }, true), { ...summary, ...labels })
  assert.deepEqual(decodeDetail({ ...detail, ...labels }, true), { ...detail, ...labels })
  // Explicit nulls stay null: degradation belongs to the page, never to the decoder.
  assert.deepEqual(decodeSummary({ ...summary, ...nullLabels }, true), { ...summary, ...nullLabels })
  assert.deepEqual(decodeDetail({ ...detail, ...nullLabels }, true), { ...detail, ...nullLabels })
  // Regression: the pre-CCR merchant shape (old backend) still decodes; the five keys read null.
  assert.deepEqual(decodeSummary({ ...summary }, true), { ...summary, ...nullLabels })
  assert.deepEqual(decodeDetail({ ...detail }, true), { ...detail, ...nullLabels })
  const page = decodePage({ page: 1, pageSize: 20, total: 2, items: [{ ...summary, ...labels }, { ...summary, afterSaleId: '302', ...nullLabels }] }, true)
  assert.deepEqual(page.items.map(item => item.customerNickname), ['E2E体验用户', null])
  assert.deepEqual(page.items.map(item => item.petBreed), ['柯基', null])
  // Unicode names (emoji/CJK) are carried verbatim.
  assert.equal(decodeSummary({ ...summary, ...labels, customerNickname: '小橘😀' }, true).customerNickname, '小橘😀')
})

test('merchant decoders stay strict: all-or-none label set, string|null values, no phone/avatar/nested key', () => {
  for (const bad of [
    { ...summary, customerNickname: 'E2E体验用户' },
    { ...summary, customerNickname: null, serviceName: null, petName: null, petType: null },
    { ...summary, ...labels, customerPhoneMasked: '138****0100' },
    { ...summary, ...labels, phone: '13800000100' },
    { ...summary, ...labels, customerAvatarUrl: null },
    { ...summary, ...labels, userId: '101' },
    { ...summary, labels },
    { ...summary, ...labels, customerNickname: 1 },
    { ...summary, ...labels, petName: {} },
    { ...summary, ...labels, petType: true },
    { ...summary, ...labels, serviceName: [] },
    { ...summary, ...labels, serviceName: '' },
    { ...summary, ...labels, petBreed: '   ' },
  ]) assert.throws(() => decodeSummary(bad, true), /INVALID_RESPONSE/, JSON.stringify(bad))
  for (const bad of [{ ...detail, customerNickname: '甲' }, { ...detail, ...labels, phoneMasked: '138****0100' }, { ...detail, ...labels, petType: 7 }])
    assert.throws(() => decodeDetail(bad, true), /INVALID_RESPONSE/, JSON.stringify(bad))
  // The original field rules are untouched by the relaxation.
  assert.throws(() => decodeSummary({ ...summary, ...labels, afterSaleId: 301 }, true), /INVALID_RESPONSE/)
  assert.throws(() => decodeDetail({ ...detail, ...labels, finalSetVersion: 'x' }, true), /INVALID_RESPONSE/)
})

test('consumer decoding keeps the exact pre-CCR shape and never grows label keys', () => {
  assert.deepEqual(decodeSummary({ ...summary }), { ...summary })
  assert.equal('customerNickname' in decodeSummary({ ...summary }), false)
  assert.deepEqual(decodeDetail({ ...detail }), { ...detail })
  assert.equal('petName' in decodeDetail({ ...detail }), false)
  assert.throws(() => decodeSummary({ ...summary, ...labels }), /INVALID_RESPONSE/)
  assert.throws(() => decodeDetail({ ...detail, ...labels }), /INVALID_RESPONSE/)
  assert.throws(() => decodePage({ page: 1, pageSize: 20, total: 1, items: [{ ...summary, ...labels }] }), /INVALID_RESPONSE/)
  // Array.map's index argument can never switch an item into the merchant shape.
  assert.throws(() => decodePage({ page: 1, pageSize: 20, total: 2, items: [{ ...summary }, { ...summary, afterSaleId: '302', ...labels }] }), /INVALID_RESPONSE/)
})

test('real merchant client and repository carry the labels end to end without changing the requests', async () => {
  const h = await setup(async request => request.path === '/api/v1/merchant/aftersales/301' ? ok({ ...detail, ...labels })
    : ok({ page: 1, pageSize: 20, total: 2, items: [{ ...summary, ...labels }, { ...summary, afterSaleId: '302', ...nullLabels }] }))
  h.api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '501', storeId: '601' })
  const files: Pick<UploadFiles, 'save' | 'inspect' | 'owns' | 'remove'> = { save: async (_path, requestId) => `/owned/${requestId}`, inspect: async () => ({ sha256: 'a'.repeat(64), bytes: 9 }), owns: (file, requestId) => file === `/owned/${requestId}`, remove: async () => {} }
  const deps = merchantAfterSaleDeps(new AfterSaleClient(h.api, 'merchant'), h.store, files)
  const page = await deps.list({ merchantId: '501', storeId: '601', page: 1, pageSize: 20 })
  assert.deepEqual(page.items, [{ ...summary, ...labels }, { ...summary, afterSaleId: '302', ...nullLabels }])
  assert.deepEqual(await deps.detail('301'), { ...detail, ...labels })
  // Same paths and the same query as before the CCR: no new parameter, no new route.
  const list = h.calls.find(call => call.path === '/api/v1/merchant/aftersales')
  assert.deepEqual(list?.data, { page: 1, pageSize: 20, merchantId: '501', storeId: '601' })
  assert.equal(h.calls.some(call => /phone|nickname|pet/i.test(JSON.stringify(call.data ?? {}))), false)
})

test('a consumer client treats merchant-only label keys as a contract violation', async () => {
  const h = await setup(async request => request.path === '/api/v1/c/aftersales/301' ? ok({ ...detail, ...labels })
    : ok({ page: 1, pageSize: 20, total: 1, items: [{ ...summary, ...labels }] }))
  const client = new AfterSaleClient(h.api, 'c')
  await assert.rejects(client.detail('301'), /INVALID_RESPONSE/)
  await assert.rejects(client.list(), /INVALID_RESPONSE/)
})

test('page helpers degrade null facts to placeholders without fabricating a name, service or pet', () => {
  assert.equal(caseCustomerName(labels), 'E2E体验用户')
  assert.equal(caseCustomerName(nullLabels), '顾客')
  assert.equal(caseCustomerName({}), '顾客')
  assert.equal(caseCustomerInitial(labels), 'E')
  assert.equal(caseCustomerInitial({ customerNickname: '😀小橘' }), '😀')
  assert.equal(caseCustomerInitial({ customerNickname: null }), '客')
  assert.equal(caseCustomerInitial({}), '客')
  assert.equal(casePetLabel(labels), '豆豆 · 柯基')
  assert.equal(casePetLabel({ petName: '球球', petType: 'DOG', petBreed: null }), '球球')
  assert.equal(casePetLabel({ petName: null, petType: 'CAT', petBreed: '英短' }), '英短')
  assert.equal(casePetLabel(nullLabels), null)
  assert.equal(caseServiceLine(labels), 'E2E到店精细洗护 · 豆豆 · 柯基')
  assert.equal(caseServiceLine({ ...nullLabels, serviceName: '猫咪洗澡' }), '猫咪洗澡')
  assert.equal(caseServiceLine({ ...nullLabels, petName: '豆豆' }), '豆豆')
  assert.equal(caseServiceLine(nullLabels), null)
  assert.equal(caseServiceLine({}), null)
})

test('pages render labels without phone, keep button ids/routes, and scope new styles under .mas-page', () => {
  const list = pageSource('index.tsx'), detailPage = pageSource('detail.tsx'), shell = pageSource('page.tsx'), css = pageSource('page.css')
  for (const source of [list, detailPage, shell]) assert.equal(/phone|mobile/i.test(source), false)
  // Existing interaction surface is unchanged.
  assert.match(list, /id=\{`mas-case-\$\{item\.afterSaleId\}`\}/)
  assert.match(list, /\/merchant\/pages\/aftersale\/detail\?afterSaleId=\$\{encodeURIComponent\(item\.afterSaleId\)\}/)
  for (const id of ['mas-detail-refresh', 'mas-open-evidence', 'mas-open-opinion', 'mas-submit-reply']) assert.ok(detailPage.includes(`id='${id}'`), id)
  // Both faces render the shared customer block; the detail shows service/pet with a non-fabricated dash.
  assert.match(list, /<MerchantAfterSaleCustomer view=\{item\} line=\{caseServiceLine\(item\)\} \/>/)
  assert.match(detailPage, /<MerchantAfterSaleCustomer view=\{detail\} className='mas-detail-customer' \/>/)
  assert.match(detailPage, /detail\.serviceName \?\? '—'/)
  assert.match(detailPage, /casePetLabel\(detail\) \?\? '—'/)
  // No avatar image is ever rendered for the customer (the CCR has no avatar field).
  assert.equal(/<Image[^>]*mas-customer/.test(shell), false)
  // Every new CCR rule is a .mas-page compound selector.
  const block = css.slice(css.indexOf('CCR-M-AFTERSALE-VIEW-001'))
  const rules = block.split('\n').filter(line => /^\s*\.[\w-]/.test(line))
  assert.ok(rules.length >= 9, String(rules.length))
  for (const rule of rules) assert.match(rule.trim(), /^\.mas-page \.mas-[\w-]+/, rule)
})
