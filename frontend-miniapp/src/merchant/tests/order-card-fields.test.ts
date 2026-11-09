import test from 'node:test'
import assert from 'node:assert/strict'
import {
  decodeMerchantOrderPage, decodeMerchantOrderSummary, merchantCardSubtitle,
  merchantCustomerInitial, merchantCustomerName, merchantPetLabel,
} from '../order/model'

// ---------------------------------------------------------------------------
// CCR-M-ORDER-CARD-001（2026-11-06 批准）：商家订单列表卡片补客户/服务/宠物投影。
// 解码器 7→13 键：新 6 键严格校验 string|null；旧 7 键响应必须仍可解码（向后兼容）；
// 任何 phone 形状的键都属于契约外形状，直接 INVALID_RESPONSE。
// ---------------------------------------------------------------------------

const legacy = {
  orderId: '777001', orderNo: '2001', displayStatus: 'PENDING_CONFIRM', payAmount: '128.00',
  appointmentStart: '2030-01-01T02:00:00.000Z', appointmentEnd: '2030-01-01T03:30:00.000Z',
  paidAt: '2030-01-01T01:00:00.000Z',
}

const card = {
  customerNickname: 'E2E体验用户', customerAvatarUrl: null, serviceName: 'E2E到店精细洗护',
  petName: '豆豆', petType: 'DOG', petBreed: '柯基',
}

test('decode: the 13-key card shape carries the six additive fields', () => {
  const item = decodeMerchantOrderSummary({ ...legacy, ...card })
  assert.equal(item.customerNickname, 'E2E体验用户')
  assert.equal(item.customerAvatarUrl, null)
  assert.equal(item.serviceName, 'E2E到店精细洗护')
  assert.equal(item.petName, '豆豆')
  assert.equal(item.petType, 'DOG')
  assert.equal(item.petBreed, '柯基')
  // The original seven keys keep their contract validation untouched.
  assert.equal(item.orderNo, '2001')
  assert.equal(item.displayStatus, 'PENDING_CONFIRM')
  assert.equal(item.payAmount, '128.00')
})

test('decode: legacy 7-key responses still decode (backward compatibility)', () => {
  const item = decodeMerchantOrderSummary(legacy)
  assert.equal(item.orderId, '777001')
  assert.equal(item.customerNickname, null)
  assert.equal(item.customerAvatarUrl, null)
  assert.equal(item.serviceName, null)
  assert.equal(item.petName, null)
  assert.equal(item.petType, null)
  assert.equal(item.petBreed, null)
  // Mixed pages are legal: a server mid-rollout may answer old and new items together.
  const page = decodeMerchantOrderPage({ items: [legacy, { ...legacy, ...card }], page: 1, pageSize: 20, total: 2 })
  assert.equal(page.items.length, 2)
  assert.equal(page.items[0]!.serviceName, null)
  assert.equal(page.items[1]!.serviceName, 'E2E到店精细洗护')
})

test('decode: every new key accepts null (degradation never fabricates)', () => {
  const allNull = decodeMerchantOrderSummary({
    ...legacy, customerNickname: null, customerAvatarUrl: null, serviceName: null,
    petName: null, petType: null, petBreed: null,
  })
  assert.equal(allNull.customerNickname, null)
  assert.equal(allNull.petBreed, null)
})

async function rejects(call: () => unknown): Promise<unknown> {
  try { await call(); return new Error('NO_THROW') } catch (error) { return error }
}

test('decode rejects contract-foreign card shapes', async () => {
  for (const bad of [
    { ...legacy, ...card, customerPhoneMasked: '138****0100' }, // no phone key ever
    { ...legacy, ...card, phone: '13800000100' },               // any phone-shaped key
    { ...legacy, ...card, customerNickname: 5 },                // wrong type
    { ...legacy, ...card, customerAvatarUrl: 1 },
    { ...legacy, ...card, petName: undefined },                 // undefined is not null
    { ...legacy, ...card, serviceName: '' },                    // empty string is contract-foreign
    { ...legacy, ...card, petBreed: {} },
    { ...legacy, ...card, extra: 1 },                           // 14 keys
    { ...legacy, customerNickname: 5 },                         // 8 keys is neither shape
  ]) {
    assert.equal(((await rejects(() => decodeMerchantOrderSummary(bad))) as Error).message,
      'INVALID_RESPONSE', JSON.stringify(bad))
  }
  // Exactly 12 keys (one card key missing) is neither legacy nor card shape.
  const missingPetType = { ...legacy, ...card } as Record<string, unknown>
  delete missingPetType.petType
  assert.equal(((await rejects(() => decodeMerchantOrderSummary(missingPetType))) as Error).message, 'INVALID_RESPONSE')
})

test('display helpers: nickname null degrades to placeholder, never fabricates', () => {
  const anonymous = decodeMerchantOrderSummary({
    ...legacy, customerNickname: null, customerAvatarUrl: null, serviceName: null,
    petName: null, petType: null, petBreed: null,
  })
  assert.equal(merchantCustomerName(anonymous), '顾客')
  assert.equal(merchantCustomerInitial(anonymous), '客')
  assert.equal(merchantCardSubtitle(anonymous), null)
  assert.equal(merchantPetLabel(anonymous), null)

  const known = decodeMerchantOrderSummary({ ...legacy, ...card })
  assert.equal(merchantCustomerName(known), 'E2E体验用户')
  assert.equal(merchantCustomerInitial(known), 'E')
  assert.equal(merchantCardSubtitle(known), 'E2E到店精细洗护 · 豆豆 · 柯基')
  assert.equal(merchantPetLabel(known), '豆豆 · 柯基')
})

test('display helpers: partial card facts render what exists and skip the rest', () => {
  // Avatar URL present but nickname absent: image renders, initial stays the avatar fallback.
  const noNickname = decodeMerchantOrderSummary({
    ...legacy, customerNickname: null, customerAvatarUrl: 'https://wx.qlogo.cn/a.png',
    serviceName: '猫咪洗澡', petName: '小橘', petType: 'CAT', petBreed: null,
  })
  assert.equal(merchantCustomerName(noNickname), '顾客')
  assert.equal(merchantCustomerInitial(noNickname), '客')
  assert.equal(noNickname.customerAvatarUrl, 'https://wx.qlogo.cn/a.png')
  assert.equal(merchantPetLabel(noNickname), '小橘')
  assert.equal(merchantCardSubtitle(noNickname), '猫咪洗澡 · 小橘')

  // Pet name present without breed, breed present without pet name: join skips nulls.
  const breedOnly = decodeMerchantOrderSummary({
    ...legacy, customerNickname: '王先生', customerAvatarUrl: null,
    serviceName: null, petName: null, petType: 'DOG', petBreed: '柯基',
  })
  assert.equal(merchantPetLabel(breedOnly), '柯基')
  assert.equal(merchantCardSubtitle(breedOnly), '柯基')

  // First code point (not first UTF-16 unit): emoji昵称的首字取完整码点。
  const emoji = decodeMerchantOrderSummary({
    ...legacy, customerNickname: '😀豆豆主人', customerAvatarUrl: null,
    serviceName: null, petName: null, petType: null, petBreed: null,
  })
  assert.equal(merchantCustomerInitial(emoji), '😀')
})

test('no phone field exists on the decoded summary surface', () => {
  const item = decodeMerchantOrderSummary({ ...legacy, ...card })
  const keys = Object.keys(item)
  assert.deepEqual([...keys].sort(), ['appointmentEnd', 'appointmentStart', 'customerAvatarUrl',
    'customerNickname', 'displayStatus', 'orderNo', 'orderId', 'paidAt', 'payAmount',
    'petBreed', 'petName', 'petType', 'serviceName'].sort())
  assert.equal(keys.some(key => key.toLowerCase().includes('phone')), false)
})
