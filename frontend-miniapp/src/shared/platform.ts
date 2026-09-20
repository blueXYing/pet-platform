import Taro from '@tarojs/taro'
import type { Transport } from './request'
import { createHttpAdapter } from './http-adapter'

// Low-level adapter only; not wired to the app until approved session integration.
export function createWechatTransport(baseUrl: string, allowLocalHttp = false): Transport {
  return createHttpAdapter(baseUrl, async options => {
    const result = await Taro.request(options)
    return { statusCode: result.statusCode, data: result.data }
  }, allowLocalHttp)
}
export const platform = {
  navigate: (url: string) => Taro.navigateTo({ url }),
  login: () => Taro.login(),
  scan: () => Taro.scanCode({}),
  upload: (options: Taro.uploadFile.Option) => Taro.uploadFile(options),
  pay: (options: Taro.requestPayment.Option) => Taro.requestPayment(options),
}
