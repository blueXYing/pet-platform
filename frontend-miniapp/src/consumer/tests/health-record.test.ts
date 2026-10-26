// 契约 68 健康记录模型 + §3.3.4 城市模块的离线单测（解码失败关闭 + 展示派生 + 存储回退）。
import assert from 'node:assert/strict'
import { test } from 'node:test'

import {
  decodeHealthRecord, decodeHealthRecordList, decodeHealthRecordReceipt, dewormingDateLine, dewormingTypeLabels,
  healthRecordErrorMessage, validateDewormDraft, validateVaccineDraft, vaccineDateLine,
  vaccineStatusLabel, emptyVaccineDraft, emptyDewormDraft,
} from '../pet/health-record'
import { cityName, effectiveCity } from '../city/model'

const wireRecord = {
  recordId: '9600000000000001', petId: '30001', recordType: 'VACCINATION',
  recordDate: '2026-04-10', vaccineName: '狂犬疫苗', doseNumber: 3,
  dewormingType: null, productName: null, nextRecordDate: '2027-04-10', note: null, status: 'ACTIVE',
}

test('health record decoder accepts the contract-68 projection and reads absent optionals', () => {
  const decoded = decodeHealthRecord(JSON.parse(JSON.stringify(wireRecord)))
  assert.equal(decoded.vaccineName, '狂犬疫苗')
  assert.equal(decoded.doseNumber, 3)
  assert.equal(decoded.nextRecordDate, '2027-04-10')
  const minimal = decodeHealthRecord({ ...wireRecord, doseNumber: null, nextRecordDate: null, vaccineName: '犬六联疫苗', recordType: 'VACCINATION' })
  assert.equal(minimal.doseNumber, null)
  const list = decodeHealthRecordList([wireRecord])
  assert.equal(list.length, 1)
})

test('health record decoder fails closed on contract violations', () => {
  for (const over of [
    { vaccineStatus: 'COMPLETE' },                       // 契约外键（宠物档案字段混入）
    { recordId: 'abc' },                                 // recordId 非十进制 ID 形
    { recordDate: '2026/04/10' },                        // 非 ISO 日期
    { doseNumber: 21 },                                  // 针次越界
    { status: 'DELETED' },                               // 68号无删除路由，status 恒 ACTIVE
    { recordType: 'DEWORMING', dewormingType: '内外同驱' }, // 枚举外
  ]) {
    assert.throws(() => decodeHealthRecord({ ...wireRecord, ...over }), /INVALID_RESPONSE/, JSON.stringify(over))
  }
  assert.throws(() => decodeHealthRecordList({ items: [] }), /INVALID_RESPONSE/)
})

test('health record POST receipt decoder accepts the contract-68 acknowledgement, not a full record', () => {
  const receipt = { recordId: '9600000000000002', petId: '30001', recordType: 'VACCINATION', status: 'ACTIVE' }
  assert.deepEqual(decodeHealthRecordReceipt(receipt), receipt)
  // 2026-10-26 线上回归：POST 只返回回执，详情页保存后另行 GET 列表；
  // 若误用 decodeHealthRecord 会因缺 recordDate 等字段抛 INVALID_RESPONSE，服务端已写入却显示保存失败。
  assert.throws(() => decodeHealthRecordReceipt(wireRecord), /INVALID_RESPONSE/)
  assert.throws(() => decodeHealthRecordReceipt({ ...receipt, status: 'DELETED' }), /INVALID_RESPONSE/)
  assert.throws(() => decodeHealthRecordReceipt({ petId: receipt.petId, recordType: receipt.recordType, status: receipt.status }), /INVALID_RESPONSE/)
})

test('health record save errors explain pending replay and expired login', () => {
  assert.equal(healthRecordErrorMessage(new Error('PENDING_WRITE_CHANGED')), '上次保存结果尚未确认，请保持原内容点击保存重试')
  assert.equal(healthRecordErrorMessage(new Error('STALE_CONTEXT')), '登录状态已变化，请重新进入页面后再试')
  assert.equal(healthRecordErrorMessage(new Error('INVALID_RESPONSE')), '保存失败，请稍后重试')
})

test('status/date display derivations follow the registered display rules', () => {
  const today = '2026-10-19'
  assert.equal(vaccineStatusLabel(decodeHealthRecord(wireRecord), today), '已接种')
  const expiring = decodeHealthRecord({ ...wireRecord, nextRecordDate: '2026-11-01' })
  assert.equal(vaccineStatusLabel(expiring, today), '即将到期')
  const due = decodeHealthRecord({ ...wireRecord, nextRecordDate: '2026-10-01' })
  assert.equal(vaccineStatusLabel(due, today), '待接续')
  const none = decodeHealthRecord({ ...wireRecord, nextRecordDate: null })
  assert.equal(vaccineStatusLabel(none, today), '已接种')
  assert.equal(vaccineDateLine(decodeHealthRecord(wireRecord)), '接种 2026-04-10 · 下次 2027-04-10')
  const deworm = decodeHealthRecord({ ...wireRecord, recordType: 'DEWORMING', vaccineName: null, dewormingType: 'INTERNAL', productName: '拜宠清', nextRecordDate: '2026-09-01', doseNumber: null })
  assert.equal(dewormingTypeLabels[deworm.dewormingType!], '体内驱虫')
  assert.equal(dewormingDateLine(deworm), '驱虫 2026-04-10 · 下次 2026-09-01')
})

test('form validators mirror the PRD 5.1.3 field tables', () => {
  const today = '2026-10-19'
  assert.deepEqual(validateVaccineDraft({ ...emptyVaccineDraft }, today), { vaccineName: '请填写疫苗名称（50 字以内）', recordDate: '请选择接种日期' })
  assert.deepEqual(validateVaccineDraft({ ...emptyVaccineDraft, vaccineName: '狂犬疫苗', recordDate: '2026-10-20' }, today), { recordDate: '接种日期不得晚于今天' })
  assert.deepEqual(validateVaccineDraft({ ...emptyVaccineDraft, vaccineName: '狂犬疫苗', recordDate: today, doseNumber: '21' }, today), { doseNumber: '针次为 1~20 的整数' })
  assert.deepEqual(validateVaccineDraft({ ...emptyVaccineDraft, vaccineName: '狂犬疫苗', recordDate: today, nextRecordDate: '2026-10-18' }, today), { nextRecordDate: '下次接种日期不得早于接种日期' })
  assert.deepEqual(validateVaccineDraft({ ...emptyVaccineDraft, vaccineName: '狂犬疫苗', recordDate: today }, today), {})
  assert.deepEqual(validateDewormDraft({ ...emptyDewormDraft }, today), { recordDate: '请选择驱虫日期' })
  assert.deepEqual(validateDewormDraft({ ...emptyDewormDraft, dewormingType: 'INTERNAL', recordDate: today, nextRecordDate: today }, today), {})
  assert.equal(healthRecordErrorMessage({ code: 'PET_HEALTH_RECORD_LIMIT_EXCEEDED' }).includes('上限'), true)
})

test('city helpers resolve the effective city with storage-free fallbacks', () => {
  const catalog = [{ cityCode: 'beijing', cityName: '北京' }, { cityCode: 'chengdu', cityName: '成都' }]
  // Taro storage is unavailable in node tests: readSelectedCity falls back to {} silently.
  assert.equal(effectiveCity(catalog), 'beijing', '无本地选择时默认目录首位')
  assert.equal(effectiveCity([]), undefined, '空目录回退未选择（=全部开放城市）')
  assert.equal(cityName(catalog, 'chengdu'), '成都')
  assert.equal(cityName(catalog, undefined), '全部开放城市')
})
