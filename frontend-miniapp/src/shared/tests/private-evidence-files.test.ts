import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { PrivateEvidenceFiles } from '../private-evidence-files'

test('restart recovers only strict preview names and retries failed deletions', () => {
  const owned = `pet-aftersale-${randomUUID()}.img`, unrelated = `pet-material-${randomUUID()}.img`
  const files = new Map([[`/private/${owned}`, new ArrayBuffer(8)], [`/private/${unrelated}`, new ArrayBuffer(8)], ['/private/pet-aftersale-other.img', new ArrayBuffer(8)]])
  let fail = true
  const cache = new PrivateEvidenceFiles({ readdirSync: () => [...files.keys()].map(path => path.slice(9)), unlinkSync(path) { if (fail) throw new Error('busy'); files.delete(path) }, writeFileSync(path, data) { files.set(path, data) } }, '/private', true)
  cache.clear(); assert.ok(files.has(`/private/${owned}`))
  fail = false; cache.clear()
  assert.equal(files.has(`/private/${owned}`), false)
  assert.ok(files.has(`/private/${unrelated}`)); assert.ok(files.has('/private/pet-aftersale-other.img'))
})

test('partially written previews remain owned for cleanup and invalid names never write', () => {
  const removed: string[] = []
  const cache = new PrivateEvidenceFiles({ readdirSync: () => [], unlinkSync: path => { removed.push(path) }, writeFileSync() { throw new Error('disk full') } }, '/private')
  assert.throws(() => cache.save('invalid', new ArrayBuffer(8)), /INVALID_PREVIEW_NAME/)
  const uuid = randomUUID(); assert.throws(() => cache.save(uuid, new ArrayBuffer(8)), /disk full/)
  cache.clear(); assert.deepEqual(removed, [`/private/pet-aftersale-${uuid}.img`])
})
