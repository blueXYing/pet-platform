import assert from 'node:assert/strict'
import { test } from 'node:test'
import { demandLabel, statusTagClass, typeLabel } from '../aftersale/model'

test('type/demand labels mirror the official catalog and fall back to raw codes', () => {
  assert.equal(typeLabel('FEE_DISPUTE'), '费用争议')
  assert.equal(typeLabel('NON_PERFORMANCE'), '未履约')
  assert.equal(typeLabel('PET_SAFETY'), '宠物安全')
  assert.equal(typeLabel('SERVICE_QUALITY'), '质量问题')
  assert.equal(typeLabel('OTHER'), '其他')
  assert.equal(demandLabel('APOLOGY'), '道歉')
  assert.equal(demandLabel('PARTIAL_COMPENSATION'), '部分补偿')
  assert.equal(demandLabel('REFUND'), '退款')
  assert.equal(demandLabel('RESERVICE'), '重新服务')
  assert.equal(demandLabel('OTHER'), '其他')
  assert.equal(typeLabel('CONFIGURED_TYPE'), 'CONFIGURED_TYPE')
  assert.equal(demandLabel('UNKNOWN_DEMAND'), 'UNKNOWN_DEMAND')
})

test('status tag variants use class names because WXSS attribute selectors never match', () => {
  assert.equal(statusTagClass('PENDING'), 'mas-tag')
  assert.equal(statusTagClass('PROCESSING'), 'mas-tag')
  assert.equal(statusTagClass('WAITING_SUPPLEMENT'), 'mas-tag')
  assert.equal(statusTagClass('RESOLVED'), 'mas-tag mas-tag-resolved')
  assert.equal(statusTagClass('INVALIDATED'), 'mas-tag mas-tag-closed')
  assert.equal(statusTagClass('WITHDRAWN'), 'mas-tag mas-tag-closed')
  assert.equal(statusTagClass('CLOSED'), 'mas-tag mas-tag-closed')
})
