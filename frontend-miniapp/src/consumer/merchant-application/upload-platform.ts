import Taro from '@tarojs/taro'
import { sha256 } from '@noble/hashes/sha256'
import { bytesToHex } from '@noble/hashes/utils'
import type { UploadFiles } from './upload'

export function privateUploadFiles(): UploadFiles {
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
      const info = fs.statSync(path)
      if (Array.isArray(info) || info.size < 1 || info.size > 10485760) throw new Error('UPLOAD_FILE_INVALID')
      const data = fs.readFileSync(path)
      if (!(data instanceof ArrayBuffer)) throw new Error('UPLOAD_FILE_INVALID')
      const bytes = new Uint8Array(data)
      const png = [137, 80, 78, 71, 13, 10, 26, 10].every((byte, index) => bytes[index] === byte)
      const jpeg = bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255
      if (!png && !jpeg) throw new Error('UPLOAD_FILE_INVALID')
      // WeChat infers multipart MIME from this trusted extension; no original name is retained.
      const target = `${root}${requestId}${png ? '.png' : '.jpg'}`
      if (!owns(target, requestId)) throw new Error('UPLOAD_JOURNAL_INVALID')
      fs.copyFileSync(path, target)
      return target
    },
    async inspect(path) {
      const info = fs.statSync(path)
      if (Array.isArray(info) || info.size < 1 || info.size > 10485760) throw new Error('UPLOAD_FILE_INVALID')
      const data = fs.readFileSync(path)
      if (!(data instanceof ArrayBuffer) || data.byteLength < 1 || data.byteLength > 10485760) throw new Error('UPLOAD_FILE_INVALID')
      return { sha256: bytesToHex(sha256(new Uint8Array(data))), bytes: data.byteLength }
    },
    async remove(path) {
      if (!path.startsWith(root) || !owns(path, path.slice(root.length, -4))) throw new Error('UPLOAD_JOURNAL_INVALID')
      try { fs.unlinkSync(path) } catch { /* confirmed receipt stays recoverable even if local cleanup failed */ }
    },
  }
}
