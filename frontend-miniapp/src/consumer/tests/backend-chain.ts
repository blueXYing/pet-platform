// Invoked by CAuthHttpTest against its real Boot/MySQL/Redis fixture. Never a WeChat proof.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import type { Transport } from '../../shared/request'
import { RealPetRepository, RealProfileRepository } from '../api/repositories'
import { emptyDraft } from '../pet/model'

async function main() {
  const base = process.env.C_TEST_HTTP_ORIGIN || ''
  if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) throw new Error('Isolated loopback fixture required')
  const state = new Map<string, unknown>()
  const store: LocalStore = { get: key => state.get(key), set: (key, value) => { state.set(key, JSON.parse(JSON.stringify(value))) }, remove: key => { state.delete(key) } }
  let loseAck = ''
  const writes: { path: string; key: string }[] = []
  const transport: Transport = async request => {
    if (request.method !== 'GET') writes.push({ path: request.path, key: request.requestId! })
    const response = await fetch(base + request.path, { method: request.method, headers: request.headers, body: request.data ? JSON.stringify(request.data) : undefined })
    const data = await response.json()
    if (request.path === loseAck && response.ok) { loseAck = ''; throw new Error('test-only lost ACK after real commit') }
    return { statusCode: response.status, data }
  }
  const makeApi = () => new ConsumerApi(transport, store, async () => randomUUID())
  let api = makeApi()
  await api.startLogin(async () => ({ code: 'ok:frontend-chain-owner' }))
  assert.equal(api.authStep, 'phone')
  await assert.rejects(api.bindPhone({ errMsg: 'getPhoneNumber:fail user deny' }))
  await api.bindPhone({ code: 'phone:13800007881', errMsg: 'getPhoneNumber:ok' })
  const userId = api.currentSession!.userId
  const profile = new RealProfileRepository(api)
  const initial = await profile.load()
  loseAck = '/api/v1/c/profile'
  const profileDraft = { ...initial, nickname: '真实库昵称😀' }
  await assert.rejects(profile.save(profileDraft, ''))
  const key = writes.at(-1)!.key
  await profile.save(profileDraft, '')
  assert.equal(writes.at(-1)!.key, key)
  api = makeApi(); await api.restore()
  assert.equal((await new RealProfileRepository(api).load()).nickname, profileDraft.nickname)
  let pets = new RealPetRepository(api, async () => 'DOG')
  const draft = { ...emptyDraft, name: '持久宠物', breedName: '柯基', weightInput: '12.50kg', sex: 'MALE' as const }
  loseAck = '/api/v1/c/pets'
  await assert.rejects(pets.save(null, draft, ''))
  const createKey = writes.at(-1)!.key
  api = makeApi(); await api.restore(); pets = new RealPetRepository(api, async () => { throw new Error('retry must keep selected type') })
  const created = await pets.save(null, draft, '')
  assert.equal(writes.at(-1)!.key, createKey)
  assert.equal((await pets.load()).length, 1)
  assert.equal((await pets.get(created.petId)).petType, 'DOG')
  const edit = { ...draft, name: '修改后的宠物', weightInput: '13.25kg' }
  await Promise.all([pets.save(created.petId, edit, ''), pets.save(created.petId, edit, '')])
  api = makeApi(); await api.restore(); pets = new RealPetRepository(api, async () => 'CAT')
  assert.equal((await pets.get(created.petId)).name, edit.name)
  assert.equal((await pets.get(created.petId)).weightKg, '13.25')
  await api.logout(); assert.equal(api.scope.current, null)
  await assert.rejects(api.restore())
  await api.startLogin(async () => ({ code: 'ok:frontend-chain-other' }))
  await api.bindPhone({ code: 'phone:13800007882', errMsg: 'getPhoneNumber:ok' })
  assert.notEqual(api.currentSession!.userId, userId)
  assert.deepEqual(await pets.load(), [])
  await assert.rejects(pets.get(created.petId))
  await assert.rejects(pets.remove(created.petId, ''))
  await api.logout()
  await api.startLogin(async () => ({ code: 'ok:frontend-chain-owner' }))
  assert.equal(api.currentSession!.userId, userId)
  loseAck = `/api/v1/c/pets/${created.petId}`
  await assert.rejects(pets.remove(created.petId, ''))
  const deleteKey = writes.at(-1)!.key
  await pets.remove(created.petId, '')
  assert.equal(writes.at(-1)!.key, deleteKey)
  api = makeApi(); await api.restore()
  assert.deepEqual(await new RealPetRepository(api, async () => 'CAT').load(), [])
  await api.logout()
  console.log('PASS frontend ConsumerApi/repositories → real Boot HTTP/MySQL/Redis: login, phone denial/binding, session, profile persistence, pet CRUD/persistence, lost-ACK same-key recovery, duplicate write, cross-user 404, logout. WeChat Provider=FIXED TEST DOUBLE; ID=isolated virgin node with original lease protections.')
}
main().catch(error => { console.error('FAIL frontend/backend isolated chain:', error instanceof Error ? error.message : 'unknown'); process.exitCode = 1 })
