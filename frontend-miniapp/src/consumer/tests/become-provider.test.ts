import assert from 'node:assert/strict'
import test from 'node:test'
import {
  applicationEntryUrl, isProviderTypeCode, providerCopy, providerTypeChoices, providerTypeHint,
  providerTypeLabel, serviceTypePageUrl, stageProviderType, takeStagedProviderType,
} from '../pages/become-provider/model'
import { merchantTypeOptions } from '../merchant-application/model'

// 32号 §2 七页批次·成为服务者入口模块：选项集与既有申请契约同源、入口参数为既有
// DraftInput 字段形态（merchantTypeCode）、两页间选择回传一次取回即清空。
test('provider type choices mirror the existing merchant-application contract options', () => {
  assert.equal(providerTypeChoices.length, merchantTypeOptions.length)
  assert.deepEqual(providerTypeChoices.map(choice => [choice.code, choice.label]), merchantTypeOptions.map(([code, label]) => [code, label]))
  for (const [code] of merchantTypeOptions) assert.ok(isProviderTypeCode(code))
})

test('type codes are validated against the contract dictionary only', () => {
  assert.equal(isProviderTypeCode('PET_GROOMING'), true)
  assert.equal(isProviderTypeCode('RUNNER'), false)
  assert.equal(isProviderTypeCode(null), false)
  assert.equal(isProviderTypeCode(undefined), false)
  assert.equal(isProviderTypeCode(1), false)
})

test('application entry passes the selection in the existing DraftInput field shape', () => {
  assert.equal(applicationEntryUrl('PET_GROOMING'), '/consumer/pages/merchant-application/index?merchantTypeCode=PET_GROOMING')
  // 传入的 code 必是契约字典成员（编译期收口 + 运行期校验双保险）。
  const code = providerTypeChoices.find(choice => choice.code === 'PET_HOSPITAL')!.code
  assert.equal(applicationEntryUrl(code).endsWith('merchantTypeCode=PET_HOSPITAL'), true)
})

test('select hint falls back to the design placeholder and labels come from the dictionary', () => {
  assert.equal(providerTypeHint(null), providerCopy.selectPlaceholder)
  assert.equal(providerTypeHint('PET_BOARDING'), '宠物寄养中心')
  assert.equal(providerTypeHint('NOT_A_CODE'), providerCopy.selectPlaceholder)
  assert.equal(providerTypeLabel('PET_LIFE_STORE'), '宠物生活馆')
  assert.equal(providerTypeLabel('NOT_A_CODE'), '')
})

test('service-type page url carries the current selection for round-trip state', () => {
  assert.equal(serviceTypePageUrl(null), '/consumer/pages/become-provider/service-type')
  assert.equal(serviceTypePageUrl('PET_TRAINING'), '/consumer/pages/become-provider/service-type?current=PET_TRAINING')
})

test('staged selection is consumed exactly once and has no cross-test residue', () => {
  assert.equal(takeStagedProviderType(), null)
  stageProviderType('OTHER')
  assert.equal(takeStagedProviderType(), 'OTHER')
  assert.equal(takeStagedProviderType(), null)
  stageProviderType('PET_HOSPITAL')
  stageProviderType('PET_GROOMING')
  assert.equal(takeStagedProviderType(), 'PET_GROOMING')
  assert.equal(takeStagedProviderType(), null)
})

test('copy baseline stays on the adjudicated contract wording', () => {
  assert.equal(providerCopy.title, '成为服务者')
  assert.equal(providerCopy.selectLabel, '服务类型 *')
  assert.equal(providerCopy.selectPlaceholder, '请选择')
  // 说明行不再主张「与开店商家是两种不同身份」（V1 无第二套入驻身份，偏差登记 §4.16）。
  assert.equal(providerCopy.note.includes('商家申请流程'), true)
  // 主按钮为导航动作而非本页提交（本模块零写操作）。
  assert.equal(providerCopy.primary, '进入入驻申请')
})
