import Taro from '@tarojs/taro'
import { createPhoneGuard } from './phone-guard'
import { consumerApi, consumerSession } from './consumer-runtime'

// 真实端口：静默恢复走 silent-login 单飞；引导弹窗确认后切到“我的”tab 的手机号授权引导。
export const phoneGuard = createPhoneGuard({
  authenticated: () => !!consumerApi.currentSession,
  ensureLogin: () => consumerSession.ensure(),
  guide: async () => {
    const result = await Taro.showModal({ title: '需要先授权手机号', content: '该操作需要先在「我的」页授权手机号完成登录后才能继续。', confirmText: '去授权', cancelText: '暂不' })
    return result.confirm
  },
  goAuthorize: () => Taro.switchTab({ url: '/consumer/pages/mine/index' }),
})
