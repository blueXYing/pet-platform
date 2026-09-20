import test from 'node:test'
import assert from 'node:assert/strict'
import { readCurrentApplication, noCurrentApplicationNotice } from '../merchant-application/current'
import { ApplicationCommands, applicationMessage } from '../merchant-application/model'
import { ApiError } from '../../shared/request'
import { WorkspaceScope } from '../../shared/workspace'
import type { ApplicationDetail } from '../../shared/merchant-repositories'

test('first GET current not-found is a normal empty application', async () => {
  const result = await readCurrentApplication({ current: async () => { throw new ApiError('COMMON_NOT_FOUND', 404) } }, () => true)
  assert.deepEqual(result, { kind: 'empty' })
  assert.equal(noCurrentApplicationNotice, '尚未创建入驻申请，可填写后保存草稿。')
})
test('GET current other faults propagate without converting to empty', async () => {
  for (const error of [new ApiError('COMMON_UNAUTHORIZED', 401), new ApiError('COMMON_FORBIDDEN', 403), new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503), new ApiError('OTHER_NOT_FOUND', 404), new ApiError('COMMON_NOT_FOUND', 503), new Error('network')]) {
    await assert.rejects(readCurrentApplication({ current: async () => { throw error } }, () => true), actual => actual === error)
  }
})
test('late not-found cannot reset a replaced context or pending-write state', async () => {
  for (const blockedBy of ['context', 'pending-write', 'pending-upload']) {
    let apply = true
    let fail!: (error: unknown) => void
    const pending = readCurrentApplication({ current: () => new Promise((_resolve, reject) => { fail = reject }) }, () => apply)
    apply = false
    fail(new ApiError('COMMON_NOT_FOUND', 404))
    assert.deepEqual(await pending, { kind: 'ignored' }, blockedBy)
  }
})
test('write 404 is still an error and global error message remains unchanged', async () => {
  const error = new ApiError('COMMON_NOT_FOUND', 404)
  const scope = new WorkspaceScope()
  scope.replace({ userId: '1', workspace: 'consumer', merchantId: null, storeId: null })
  const reject = async (): Promise<never> => { throw error }
  const commands = new ApplicationCommands({ current: reject, create: reject, save: reject, submit: reject }, scope)
  await assert.rejects(commands.save(null, { storePhotoAssetIds: [] }), actual => actual === error)
  assert.equal(applicationMessage(error), '申请记录或服务暂不可用，请重新读取申请。')
})

test('pending upload still restores existing application identity and photos so subsequent save updates', async () => {
  const existing = { applicationId: '101', reservedMerchantId: '201', applicationNo: null, status: 'DRAFT', version: '3', currentRevisionId: '301', currentRevision: { draft: { storePhotoAssetIds: ['401'] } } } as ApplicationDetail
  const loaded = await readCurrentApplication({ current: async () => existing }, () => true, () => false)
  assert.equal(loaded.kind, 'current')
  if (loaded.kind !== 'current') throw new Error('Expected existing draft')
  assert.equal(loaded.current.applicationId, '101')
  assert.deepEqual(loaded.current.currentRevision.draft.storePhotoAssetIds, ['401'])
  const scope = new WorkspaceScope()
  scope.replace({ userId: '1', workspace: 'consumer', merchantId: null, storeId: null })
  const writes: unknown[] = []
  const commands = new ApplicationCommands({ current: async () => existing,
    create: async () => { throw new Error('Must not create after recovery') },
    save: async (id, version, draft) => { writes.push({ id, version, draft }); return existing },
    submit: async () => existing }, scope)
  await commands.save(loaded.current, { ...loaded.current.currentRevision.draft, storePhotoAssetIds: ['401', '402'] })
  assert.deepEqual(writes, [{ id: '101', version: '3', draft: { storePhotoAssetIds: ['401', '402'] } }])
})

test('pending upload blocks only empty reset; stale context and pending draft writes still block success', async () => {
  const notFound = { current: async (): Promise<never> => { throw new ApiError('COMMON_NOT_FOUND', 404) } }
  assert.deepEqual(await readCurrentApplication(notFound, () => true, () => false), { kind: 'ignored' })
  const existing = { applicationId: '101' } as ApplicationDetail
  assert.deepEqual(await readCurrentApplication({ current: async () => existing }, () => false, () => true), { kind: 'ignored' })
  assert.deepEqual(await readCurrentApplication(notFound, () => false, () => true), { kind: 'ignored' })
})
