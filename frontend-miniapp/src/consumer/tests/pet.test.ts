import assert from 'node:assert/strict'
import test from 'node:test'
import {
  PreviewPetRepository, breedAgeLine, deriveAgeLabel, fixturePets, formatWeightDisplay,
  sexLabel, validateDraft, weightContractToInput, weightInputToContract, type PetDraft,
} from '../pet/model'
import { WorkspaceScope, StaleContextError } from '../../shared/workspace'

const draft: PetDraft = { name: '豆豆', breedName: '金毛寻回犬', birthDate: '2024-06-18', sex: 'MALE', weightInput: '28.5kg', healthNote: '性格温顺' }
const today = '2026-09-15'

test('weight input keeps the designed kg shape and serializes to the two-decimal contract string', () => {
  assert.equal(weightInputToContract('28.5kg'), '28.50')
  assert.equal(weightInputToContract('28.50'), '28.50')
  assert.equal(weightInputToContract('0.009'), null)
  assert.equal(weightInputToContract('1000kg'), null)
  assert.equal(weightInputToContract('abc'), null)
  assert.equal(weightInputToContract('  4.20KG '), '4.20')
  assert.equal(weightContractToInput('28.50'), '28.5kg')
  assert.equal(weightContractToInput('4.20'), '4.2kg')
  assert.equal(weightContractToInput(null), '')
  assert.equal(formatWeightDisplay('28.50'), '28.5kg')
})

test('age is derived from birthDate at view time and never stored', () => {
  assert.equal(deriveAgeLabel('2024-06-18', today), '2岁')
  assert.equal(deriveAgeLabel('2025-09-15', today), '1岁')
  assert.equal(deriveAgeLabel('2026-09-14', today), '未满1岁')
  assert.equal(deriveAgeLabel('2027-01-01', today), '')
  assert.equal(deriveAgeLabel(null, today), '')
  assert.equal(deriveAgeLabel('2026-13-01', today), '')
})

test('validation mirrors the approved PetView field contract', () => {
  assert.deepEqual(validateDraft(draft, today), {})
  assert.equal(validateDraft({ ...draft, name: '  ' }, today).name, '请填写宠物名字')
  assert.equal(validateDraft({ ...draft, name: ' 豆豆' }, today).name, '名字首尾不能包含空格')
  assert.equal(validateDraft({ ...draft, name: '猫'.repeat(65) }, today).name, '名字最多64字')
  assert.equal(validateDraft({ ...draft, breedName: '金'.repeat(65) }, today).breedName, '品种最多64字')
  assert.equal(validateDraft({ ...draft, birthDate: today }, today).birthDate, undefined)
  assert.equal(validateDraft({ ...draft, birthDate: '2026-09-16' }, today).birthDate, '出生日期不能晚于今天')
  assert.equal(validateDraft({ ...draft, weightInput: ' heavy ' }, today).weightInput, '体重格式应如28.5kg')
  assert.equal(validateDraft({ ...draft, healthNote: '记'.repeat(1001) }, today).healthNote, '健康备注最多1000字')
})

test('breed/age and sex labels follow the design copy', () => {
  assert.equal(breedAgeLine(fixturePets[0], today), '金毛寻回犬 · 2岁')
  assert.equal(breedAgeLine(fixturePets[1], today), '英国短毛猫 · 1岁')
  assert.equal(sexLabel('MALE'), '弟弟')
  assert.equal(sexLabel('FEMALE'), '妹妹')
  assert.equal(sexLabel('UNKNOWN'), '')
})

test('preview repository save is idempotent per request and replay with other content conflicts', async () => {
  const repo = new PreviewPetRepository()
  const saved = await repo.save('30001', draft, 'request-1')
  assert.equal(saved.weightKg, '28.50')
  const replay = await repo.save('30001', draft, 'request-1')
  assert.deepEqual(replay, saved)
  await assert.rejects(repo.save('30001', { ...draft, name: '其他' }, 'request-1'), /REQUEST_CONFLICT/)
})

test('preview edit keeps contract-managed fields and create leaves petType unresolved', async () => {
  const repo = new PreviewPetRepository()
  await repo.save('30001', draft, 'request-2')
  const pets = await repo.load()
  const doudou = pets.find(pet => pet.petId === '30001')
  assert.equal(doudou?.petType, 'DOG')
  assert.equal(doudou?.vaccineStatus, 'COMPLETE')
  const created = await repo.save(null, { ...draft, name: '新宠' }, 'request-3')
  assert.equal(created.petType, 'OTHER')
  assert.match(created.petId, /^preview-/)
})

test('preview delete is soft, replayable, and hides the pet from later loads', async () => {
  const repo = new PreviewPetRepository()
  const removed = await repo.remove('30001', 'delete-1')
  assert.equal(removed.status, 'DISABLED')
  assert.deepEqual(await repo.remove('30001', 'delete-1'), removed)
  await assert.rejects(repo.remove('30002', 'delete-1'), /REQUEST_CONFLICT/)
  const pets = await repo.load()
  assert.equal(pets.find(pet => pet.petId === '30001'), undefined)
  await assert.rejects(repo.remove('30001', 'delete-2'), /PET_NOT_FOUND/)
})

test('load failure stays failed until an explicit retry succeeds', async () => {
  const repo = new PreviewPetRepository(fixturePets, 'load-error')
  await assert.rejects(repo.load(), /PREVIEW_LOAD_FAILED/)
  assert.equal((await repo.load()).length, 2)
})

test('failed save preserves the draft and the retry succeeds with the same request identity', async () => {
  const repo = new PreviewPetRepository(fixturePets, 'save-error')
  await assert.rejects(repo.save('30001', draft, 'request-4'), /PREVIEW_SAVE_FAILED/)
  const saved = await repo.save('30001', draft, 'request-4')
  assert.equal(saved.name, '豆豆')
})

test('late pet responses are rejected after account or workspace revision changes', async () => {
  const scope = new WorkspaceScope()
  scope.replace({ userId: '9007199254740993', workspace: 'consumer', merchantId: null, storeId: null })
  let release!: () => void
  const repo = new PreviewPetRepository(fixturePets, 'normal', () => new Promise(resolve => { release = resolve }))
  const pending = scope.run('pet', () => repo.load())
  scope.replace({ userId: '9007199254740994', workspace: 'consumer', merchantId: null, storeId: null })
  release()
  await assert.rejects(pending, StaleContextError)
  assert.equal(scope.read('pet'), undefined)
})
