import Taro from '@tarojs/taro'
import { useCallback, useRef, useState } from 'react'
import { useWorkspace } from '../../shared/workspace-react'
import { consumerApi } from '../../shared/consumer-runtime'
import { bookingPaymentMessage, isBookingScenario, PreviewBookingRepository, type PaymentReceipt } from './model'
import { paymentSlot, RealBookingRepository } from './repository'
import {
  PAY_CANCELLED_NOTICE, PAY_LOGIN_REQUIRED_NOTICE, PAY_PENDING_LAUNCH_NOTICE, PAY_PREVIEW_LAUNCH_NOTICE,
  PAY_SUBMITTED_NOTICE, isPayLaunchOrderId, requestPaymentFailure,
} from './pay-launch'

// 「去支付」直接拉起微信支付（43 号裁决 2026-11-27；10号 §3.6 / 40 号裁决 §1 / CCR-PAY-CENTER-001
// 阶段 7 的支付链路不变，只是去掉了中间的确认支付页/半屏）：
// - 必须由用户点击触发（微信要求拉起支付有用户手势）：宿主把本方法挂在「去支付」按钮上；
// - 真实模式：POST /api/v1/c/orders/{orderId}/payments 建支付单拿到渠道支付参数，随即调用微信原生
//   Taro.requestPayment(...) 调起收银台。上次发起结果尚未确认时，命令层沿用同一 X-Request-Id 重放；
// - 微信支付窗口正常结束不等于已支付：绝不本地虚构“已支付”态，交给宿主去订单详情看服务端结果；
// - 取消/失败/未登录/发起失败：toast 说明，留在当前页，「去支付」按钮仍在可再次点击；
// - preview=1 只读预览不调用 Taro.requestPayment，仅走本地样例回执。
export type PayLaunchResult = 'submitted' | 'cancelled' | 'failed' | 'login-required'

export type PayLaunchHandlers = Readonly<{
  /** 微信支付窗口已正常结束（支付已提交）：43 号规定直接去订单详情，宿主决定导航方式。 */
  onSubmitted: () => void
  /** 未登录时的登录引导；缺省回到「我的」。 */
  onLoginRequired?: () => void
}>

function toast(title: string) {
  void Taro.showToast({ title, icon: 'none', duration: 3000 })
}

export function usePayLaunch(preview: boolean, scenario = 'normal') {
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [bookingRepository] = useState(() => preview
    ? new PreviewBookingRepository(isBookingScenario(scenario) ? scenario : 'normal')
    : new RealBookingRepository(consumerApi))
  const [launching, setLaunching] = useState(false)
  const busy = useRef(false)

  const launch = useCallback(async (orderId: string, handlers: PayLaunchHandlers): Promise<PayLaunchResult> => {
    if (busy.current || !isPayLaunchOrderId(orderId)) return 'failed'
    busy.current = true
    setLaunching(true)
    try {
      if (preview) {
        try {
          await scope.run(undefined, () => bookingRepository.pay(orderId))
        } catch (error) {
          toast(bookingPaymentMessage(error))
          return 'failed'
        }
        toast(PAY_PREVIEW_LAUNCH_NOTICE)
        handlers.onSubmitted()
        return 'submitted'
      }
      if (!scope.current) { try { await consumerApi.restore() } catch { /* 未登录，走登录引导 */ } }
      if (!scope.current || scope.current.workspace !== 'consumer') {
        toast(PAY_LOGIN_REQUIRED_NOTICE)
        if (handlers.onLoginRequired) handlers.onLoginRequired()
        else void Taro.switchTab({ url: '/consumer/pages/mine/index' })
        return 'login-required'
      }
      if (consumerApi.pendingCommand(paymentSlot(orderId)) !== undefined) toast(PAY_PENDING_LAUNCH_NOTICE)
      let paid: PaymentReceipt
      try {
        paid = await scope.run(undefined, () => bookingRepository.pay(orderId))
      } catch (error) {
        // 发起失败或订单已不可支付：按错误码提示，留在当前页；订单状态以订单详情为准。
        toast(bookingPaymentMessage(error))
        return 'failed'
      }
      try {
        await Taro.requestPayment({
          timeStamp: paid.wechatPayParameters.timeStamp,
          nonceStr: paid.wechatPayParameters.nonceStr,
          package: paid.wechatPayParameters.package,
          signType: paid.wechatPayParameters.signType as 'RSA' | 'MD5',
          paySign: paid.wechatPayParameters.paySign,
        })
      } catch (payError) {
        const outcome = requestPaymentFailure(payError)
        toast(outcome.kind === 'cancelled' ? PAY_CANCELLED_NOTICE : outcome.message)
        return outcome.kind
      }
      // 微信支付窗口正常结束：支付结果尚未可知，去订单详情看服务端。
      toast(PAY_SUBMITTED_NOTICE)
      handlers.onSubmitted()
      return 'submitted'
    } finally {
      busy.current = false
      setLaunching(false)
    }
  }, [preview, scope, bookingRepository])

  return { launching, launch }
}
