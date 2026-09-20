import test from 'node:test'
import assert from 'node:assert/strict'
import { runInNewContext } from 'node:vm'
import { privateUploadBytes } from '../merchant-application/upload-bytes'

test('native foreign-realm ArrayBuffer is accepted without instanceof and preserves actual bytes', () => {
  const foreign = runInNewContext('new Uint8Array([137, 80, 78, 71]).buffer')
  assert.equal(foreign instanceof ArrayBuffer, false)
  assert.deepEqual([...privateUploadBytes(foreign)], [137, 80, 78, 71])
  assert.deepEqual([...privateUploadBytes(new Uint8Array([1, 2]).buffer)], [1, 2])
})
test('optional diagnostics expose only bounded type and length facts, never the source object', () => {
  const facts: unknown[] = []
  const foreign = runInNewContext('new ArrayBuffer(4)')
  privateUploadBytes(foreign, value => facts.push(value))
  assert.deepEqual(facts, [
    { stage: 'native-length', nativeByteLength: 4, valueType: 'object' },
    { stage: 'byte-view', nativeByteLength: 4, viewByteLength: 4, valueType: 'object' },
  ])
  const invalidFacts: unknown[] = []
  assert.throws(() => privateUploadBytes({ byteLength: 4, privateContent: 'never-log' }, value => invalidFacts.push(value)))
  assert.deepEqual(invalidFacts.at(-1), { stage: 'invalid', valueType: 'object' })
})

test('byteLength, constructor, toStringTag and prototype forgeries cannot impersonate buffers', () => {
  for (const forged of [null, undefined, 'test', [1, 2, 3, 4], new Uint8Array(4), new DataView(new ArrayBuffer(4)), { byteLength: 4 }, { byteLength: 4, constructor: { name: 'ArrayBuffer' }, [Symbol.toStringTag]: 'ArrayBuffer' }, Object.create(ArrayBuffer.prototype), new Proxy(new ArrayBuffer(4), {}), new SharedArrayBuffer(4)]) {
    assert.throws(() => privateUploadBytes(forged), /UPLOAD_FILE_INVALID/)
  }
})

test('real buffer bounds remain 1..10MiB including foreign realm and ignore spoofed own byteLength', () => {
  assert.equal(privateUploadBytes(new ArrayBuffer(1)).byteLength, 1)
  assert.equal(privateUploadBytes(runInNewContext('new ArrayBuffer(10485760)')).byteLength, 10485760)
  for (const size of [0, 10485761]) {
    assert.throws(() => privateUploadBytes(new ArrayBuffer(size)), /UPLOAD_FILE_INVALID/)
    assert.throws(() => privateUploadBytes(runInNewContext(`new ArrayBuffer(${size})`)), /UPLOAD_FILE_INVALID/)
  }
  const oversized = new ArrayBuffer(10485761)
  Object.defineProperty(oversized, 'byteLength', { value: 4 })
  assert.throws(() => privateUploadBytes(oversized), /UPLOAD_FILE_INVALID/)
  const valid = new ArrayBuffer(4)
  Object.defineProperty(valid, 'byteLength', { value: 10485761 })
  assert.equal(privateUploadBytes(valid).byteLength, 4)
  const detached = new ArrayBuffer(4)
  structuredClone(detached, { transfer: [detached] })
  assert.throws(() => privateUploadBytes(detached), /UPLOAD_FILE_INVALID/)
})
