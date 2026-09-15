import assert from 'node:assert/strict'
import test from 'node:test'
import { codePointLength, validateDraft, PreviewProfileRepository, type ProfileDraft } from '../profile/model'
import { WorkspaceScope, StaleContextError } from '../../shared/workspace'
const sample: ProfileDraft = { nickname: '宠友小白', signature: '爱宠物的铲屎官一枚～', gender: null, avatarUrl: '/design-avatar.png', phoneMasked: '138****5678' }
test('draft validation counts Unicode code points and preserves literal content', () => {
  assert.equal(codePointLength('猫🐈'), 2)
  assert.equal(codePointLength(sample.signature), 10)
  assert.deepEqual(validateDraft({ ...sample, signature: '🐈'.repeat(60) }), {})
  assert.ok(validateDraft({ ...sample, signature: '🐈'.repeat(61) }).signature)
  assert.ok(validateDraft({ ...sample, nickname: '猫'.repeat(21) }).nickname)
  assert.ok(validateDraft({ ...sample, nickname: ' 猫' }).nickname)
  assert.ok(validateDraft({ ...sample, nickname: '' }).nickname)
})
test('preview keeps changes across repository reads and never mutates original fixture', async () => {
  const repo = new PreviewProfileRepository(sample)
  const changed: ProfileDraft = { ...sample, nickname: '新昵称', gender: 'FEMALE', signature: '' }
  await repo.save(changed, 'preview-1')
  assert.deepEqual(await repo.load(), changed)
  assert.equal(sample.nickname, '宠友小白')
  assert.equal((await repo.load()).phoneMasked, '138****5678')
})
test('preview write retry preserves receipt and rejects same identity with different content', async () => {
  const repo = new PreviewProfileRepository(sample, 'save-error')
  await assert.rejects(repo.save(sample, 'preview-1'), /PREVIEW_SAVE_FAILED/)
  assert.deepEqual(await repo.save(sample, 'preview-1'), sample)
  assert.deepEqual(await repo.save(sample, 'preview-1'), sample)
  await assert.rejects(repo.save({ ...sample, signature: '不同' }, 'preview-1'), /REQUEST_CONFLICT/)
})
test('load failure can be retried without inventing a successful response', async () => {
  const repo = new PreviewProfileRepository(sample, 'load-error')
  await assert.rejects(repo.load(), /PREVIEW_LOAD_FAILED/)
  assert.deepEqual(await repo.load(), sample)
})
test('late profile response is rejected after account or workspace revision changes', async () => {
  const scope = new WorkspaceScope()
  scope.replace({ userId: '9007199254740993', workspace: 'consumer', merchantId: null, storeId: null })
  let release!: () => void
  const repo = new PreviewProfileRepository(sample, 'normal', () => new Promise(resolve => { release = resolve }))
  const pending = scope.run('profile', () => repo.load())
  scope.replace({ userId: '9007199254740994', workspace: 'consumer', merchantId: null, storeId: null })
  release()
  await assert.rejects(pending, StaleContextError)
  assert.equal(scope.read('profile'), undefined)
})
