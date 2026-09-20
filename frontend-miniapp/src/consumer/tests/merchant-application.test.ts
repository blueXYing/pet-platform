import assert from 'node:assert/strict'
import test from 'node:test'
import { ApplicationCommands, applicationMessage, editableApplication, merchantTypeOptions, unavailableDependencies, validateApplication, type ApplicationRepository } from '../merchant-application/model'
import { ApiError } from '../../shared/request'
import { WorkspaceScope } from '../../shared/workspace'
import type { ApplicationResult, DraftInput } from '../../shared/merchant-repositories'
const valid: DraftInput = { merchantName: '宠物生活馆', contactName: '张三', contactPhone: '12345678901', merchantTypeCode: 'PET_LIFE_STORE', cityCode: '310100', address: '测试路1号', longitude: '121.4', latitude: '31.2', storePhotoAssetIds: ['21'], businessLicenseAssetId: '22', idCardFrontAssetId: '23', idCardBackAssetId: '24' }
const result: ApplicationResult = { applicationId: '1', reservedMerchantId: '2', applicationNo: null, status: 'DRAFT', version: '0', currentRevisionId: '3' }
const scope = () => { const s = new WorkspaceScope(); s.replace({ userId: '1', workspace: 'consumer', merchantId: null, storeId: null }); return s }
test('approved fields, six merchant options, hospital license and 1–6 photos', () => {
  assert.equal(merchantTypeOptions.length, 6)
  assert.deepEqual(validateApplication(valid), {})
  assert.equal(validateApplication({ ...valid, merchantTypeCode: 'PET_HOSPITAL' }).industryLicenseAssetId, '宠物医院须上传行业许可证')
  assert.ok(validateApplication({ ...valid, storePhotoAssetIds: [] }).storePhotoAssetIds)
  assert.ok(validateApplication({ ...valid, storePhotoAssetIds: Array(7).fill('21') }).storePhotoAssetIds)
  assert.ok(validateApplication({ ...valid, contactName: 'A' }).contactName)
  assert.ok(validateApplication({ ...valid, email: 'invalid' }).email)
  assert.ok(validateApplication({ ...valid, latitude: null }).address)
})
test('only draft/rejected are editable; reviewing and approved cannot edit', () => {
  assert.ok(editableApplication(null)); assert.ok(editableApplication(result))
  assert.ok(editableApplication({ ...result, status: 'REJECTED' }))
  assert.equal(editableApplication({ ...result, status: 'REVIEWING' }), false)
  assert.equal(editableApplication({ ...result, status: 'APPROVED' }), false)
})
test('unavailable integrations fail closed and do not return fake assets or receipts', async () => {
  const d = unavailableDependencies()
  await assert.rejects(d.application.current(), /APPLICATION_NOT_CONNECTED/)
  await assert.rejects(d.application.create(valid), /APPLICATION_NOT_CONNECTED/)
  await assert.rejects(d.application.submit('1', '0', '3'), /APPLICATION_NOT_CONNECTED/)
  await assert.rejects(d.cities(), /CITY_NOT_CONNECTED/)
  await assert.rejects(d.location(), /LOCATION_NOT_CONNECTED/)
  await assert.rejects(d.upload('idCardFrontAssetId'), /UPLOAD_NOT_CONNECTED/)
})
test('save captures input, coalesces identical actions and rejects changed in-flight intent', async () => {
  let finish!: (value: ApplicationResult) => void
  let sent: DraftInput | undefined
  const repo = { create: (draft: DraftInput) => { sent = draft; return new Promise<ApplicationResult>(resolve => { finish = resolve }) } } as ApplicationRepository
  const commands = new ApplicationCommands(repo, scope())
  const draft = { ...valid }; const first = commands.save(null, draft)
  assert.equal(commands.save(null, draft), first)
  draft.merchantName = '已改变'
  await assert.rejects(commands.save(null, draft), /PENDING_WRITE_CHANGED/)
  assert.equal(sent?.merchantName, valid.merchantName)
  finish(result); assert.equal(await first, result)
})
test('save rejects late response after logout or identity switch', async () => {
  let finish!: (value: ApplicationResult) => void
  const currentScope = scope()
  const repo = { create: () => new Promise<ApplicationResult>(resolve => { finish = resolve }) } as ApplicationRepository
  const action = new ApplicationCommands(repo, currentScope).save(null, valid)
  currentScope.replace(null); finish(result)
  await assert.rejects(action, /STALE_CONTEXT/)
})
test('submit uses saved server version and revision without converting string IDs', async () => {
  let args: string[] = []
  const repo = { submit: async (applicationId: string, version: string, revisionId: string) => { args = [applicationId, version, revisionId]; return { ...result, status: 'REVIEWING' as const } } } as ApplicationRepository
  await new ApplicationCommands(repo, scope()).submit(result)
  assert.deepEqual(args, ['1', '0', '3'])
})
test('permission, conflict and unavailable errors remain distinct from success', () => {
  assert.match(applicationMessage(new ApiError('DENIED', 403)), /没有此操作权限/)
  assert.match(applicationMessage(new ApiError('CONFLICT', 409)), /版本或状态已变化/)
  assert.match(applicationMessage(new ApiError('UNAVAILABLE', 503)), /重试原操作/)
})
