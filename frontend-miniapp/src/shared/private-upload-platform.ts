import Taro from '@tarojs/taro'
import { sha256 } from '@noble/hashes/sha256'
import { bytesToHex } from '@noble/hashes/utils'
import type { UploadFiles } from './private-asset-upload'
import { privateUploadBytes } from './private-upload-bytes'
declare const ALLOW_LOCAL_HTTP: boolean

// Deliberately restricted facts: never pass paths, IDs, content, digests, credentials or errors.
function diagnostic(stage: string, facts: Record<string, string | number | boolean | undefined> = {}) {
  if (ALLOW_LOCAL_HTTP) console.info('[private-upload-diag-v2]', { ...facts, stage })
}

export function privateUploadFiles(): UploadFiles {
  diagnostic('adapter-ready')
  const fs = Taro.getFileSystemManager()
  const root = `${Taro.env.USER_DATA_PATH}/pet-private-material-`
  const owns = (path: string, requestId: string) => /^[a-f0-9-]{36}$/.test(requestId) && [`.jpg`, `.png`].some(extension => path === `${root}${requestId}${extension}`)
  return {
    owns,
    async choose() {
      try { return (await Taro.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'], sizeType: ['original'] })).tempFiles[0]?.tempFilePath || null }
      catch (error) { if (/cancel/.test(String((error as { errMsg?: string }).errMsg))) return null; throw error }
    },
    async save(path, requestId) {
      diagnostic('save-start')
      const info = fs.statSync(path)
      diagnostic('save-stat', { statSize: Array.isArray(info) ? undefined : info.size, statIsArray: Array.isArray(info) })
      if (Array.isArray(info) || info.size < 1 || info.size > 10485760) throw new Error('UPLOAD_FILE_INVALID')
      const bytes = privateUploadBytes(fs.readFileSync(path), facts => diagnostic(`save-${facts.stage}`, facts))
      const png = [137, 80, 78, 71, 13, 10, 26, 10].every((byte, index) => bytes[index] === byte)
      const jpeg = bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255
      diagnostic('save-format', { png, jpeg })
      if (!png && !jpeg) throw new Error('UPLOAD_FILE_INVALID')
      // WeChat infers multipart MIME from this trusted extension; no original name is retained.
      const target = `${root}${requestId}${png ? '.png' : '.jpg'}`
      if (!owns(target, requestId)) throw new Error('UPLOAD_JOURNAL_INVALID')
      fs.copyFileSync(path, target)
      diagnostic('save-copy-complete')
      return target
    },
    async inspect(path) {
      diagnostic('inspect-start')
      const info = fs.statSync(path)
      diagnostic('inspect-stat', { statSize: Array.isArray(info) ? undefined : info.size, statIsArray: Array.isArray(info) })
      if (Array.isArray(info) || info.size < 1 || info.size > 10485760) throw new Error('UPLOAD_FILE_INVALID')
      const bytes = privateUploadBytes(fs.readFileSync(path), facts => diagnostic(`inspect-${facts.stage}`, facts))
      const digest = bytesToHex(sha256(bytes))
      diagnostic('inspect-hash-complete', { hashValid: /^[a-f0-9]{64}$/.test(digest), byteLength: bytes.byteLength })
      return { sha256: digest, bytes: bytes.byteLength }
    },
    async remove(path) {
      if (!path.startsWith(root) || !owns(path, path.slice(root.length, -4))) throw new Error('UPLOAD_JOURNAL_INVALID')
      try { fs.unlinkSync(path) } catch { /* confirmed receipt stays recoverable even if local cleanup failed */ }
    },
  }
}
