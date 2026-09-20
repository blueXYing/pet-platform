const byteLengthGetter = Object.getOwnPropertyDescriptor(ArrayBuffer.prototype, 'byteLength')!.get!
const MAX_UPLOAD_BYTES = 10 * 1024 * 1024

/** Native wx buffers can belong to another realm. Validate the internal slot, not prototypes or tags. */
export function privateUploadBytes(value: unknown): Uint8Array {
  try {
    const length: number = byteLengthGetter.call(value)
    if (!Number.isSafeInteger(length) || length < 1 || length > MAX_UPLOAD_BYTES) throw new Error('UPLOAD_FILE_INVALID')
    const bytes = new Uint8Array(value as ArrayBuffer)
    if (bytes.byteLength !== length) throw new Error('UPLOAD_FILE_INVALID')
    return bytes
  } catch {
    throw new Error('UPLOAD_FILE_INVALID')
  }
}
