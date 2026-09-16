import Taro from '@tarojs/taro'
import { ConsumerApi, type LocalStore } from './consumer-api'
import { createWechatTransport } from './platform'

declare const C_API_ORIGIN: string
const storage: LocalStore = {
  get: key => Taro.getStorageSync(`${C_API_ORIGIN}:${key}`),
  set: (key, value) => Taro.setStorageSync(`${C_API_ORIGIN}:${key}`, value),
  remove: key => Taro.removeStorageSync(`${C_API_ORIGIN}:${key}`),
}
export async function requestUuid() {
  const result = await Taro.getRandomValues({ length: 16 })
  const bytes = new Uint8Array(result.randomValues)
  bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
// Public deployment origin only. No secret and no implicit localhost/fixture fallback.
const transport = C_API_ORIGIN ? createWechatTransport(C_API_ORIGIN) : async () => { throw new Error('API_NOT_CONFIGURED') }
export const consumerApi = new ConsumerApi(transport, storage, requestUuid)
