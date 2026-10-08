import Taro from '@tarojs/taro'
import { ConsumerApi, type LocalStore } from './consumer-api'
import { createSilentLogin, type SilentLogin } from './silent-login'
import { createWechatTransport, platform } from './platform'
import { createPrivateUploadTransport } from './private-upload-transport'
import { assertApiOrigin } from './api-origin'

declare const C_API_ORIGIN: string
declare const ALLOW_LOCAL_HTTP: boolean
export const consumerStorage: LocalStore = {
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
const transport = C_API_ORIGIN ? createWechatTransport(C_API_ORIGIN, ALLOW_LOCAL_HTTP) : async () => { throw new Error('API_NOT_CONFIGURED') }
export const consumerApi = new ConsumerApi(transport, consumerStorage, requestUuid, C_API_ORIGIN ? createPrivateUploadTransport(C_API_ORIGIN, async options => {
  const result = await Taro.uploadFile(options)
  return { statusCode: result.statusCode, data: result.data }
}, ALLOW_LOCAL_HTTP) : undefined, C_API_ORIGIN ? {
  async upload(input) {
    assertApiOrigin(C_API_ORIGIN, ALLOW_LOCAL_HTTP)
    const result = await Taro.uploadFile({ url: `${C_API_ORIGIN}/api/v1/c/aftersale-evidence-assets`, filePath: input.filePath, name: 'file', header: { Authorization: input.authorization, 'X-Request-Id': input.requestId }, timeout: 30000 })
    return { statusCode: result.statusCode, data: result.data }
  },
  async read(input) {
    assertApiOrigin(C_API_ORIGIN, ALLOW_LOCAL_HTTP)
    const result = await Taro.request({ url: C_API_ORIGIN + input.path, method: 'GET', header: { Authorization: input.authorization }, responseType: 'arraybuffer', timeout: 15000 })
    return { statusCode: result.statusCode, data: result.data }
  },
} : undefined,
// 401 自动恢复的静默重登通道（用户 2026-10-08 裁决）：闭包引用自身实例，模块初始化期无环。
() => consumerSession.ensure())

// 登录体验层单例：app 启动静默登录（workspace-react 的 launch()）与「我的」页手机号引导共用。
export const consumerSession: SilentLogin = createSilentLogin(consumerApi, platform.login)
