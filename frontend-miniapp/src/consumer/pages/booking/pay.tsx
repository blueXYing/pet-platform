import Taro, { useRouter } from '@tarojs/taro'
import { useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { switchConsumerTab } from '../../components/navigation/switch'
import { ConsumerPageLayout } from '../../components/page-layout'
import { isBookingScenario, PREVIEW_PAY_ORDER } from '../../booking/model'
import { PaySheet } from '../../components/pay-sheet/pay-sheet'
import './booking.css'

// 支付发起兜底页（10号 §3.6 / 40 号裁决 §1 / CCR-PAY-CENTER-001 阶段 7；43 号裁决 2026-11-27）。
// 确认支付已是底部半屏（components/pay-sheet）：预约回执与订单详情的「去支付」都在当前页面弹出。
// 本页只保留为直连/深链接兜底——页面自身不承载内容，只托管同一个 PaySheet（id 族沿用 bkp）。
// 支付规则不变（见 PaySheet 文件头）：真实模式调起微信支付，绝不本地虚构“已支付”态；
// preview=1 只读预览不调起支付。

export default function BookingPayPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isBookingScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const orderId = route.params.orderId || (preview ? PREVIEW_PAY_ORDER : '')
  const { revision } = useWorkspace(preview ? 'preview' : 'real')
  return <PaySheetHost key={revision} preview={preview} scenario={scenario} orderId={orderId} />
}

function PaySheetHost({ preview, scenario, orderId }: { preview: boolean; scenario: string; orderId: string }) {
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--bkg-status-top': `${platformInfo.statusBarHeight || 0}px`, '--bkg-unit': `${unit}px` } as CSSProperties
  const detailUrl = `/consumer/pages/orders/detail?${preview ? 'preview=1&' : ''}orderId=${encodeURIComponent(orderId)}`
  const listUrl = `/consumer/pages/orders/list${preview ? '?preview=1' : ''}`
  // 兜底页没有「上一页」可退时回到订单详情；有则按导航栈返回。
  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: detailUrl }).catch(() => undefined)
  }
  return <ConsumerPageLayout page='bookingPay' unit={unit} className='bkg-page bkg-pay-host' style={style}
    navigation={preview ? { idPrefix: 'bkp', disabled: false, onSelect: key => { void switchConsumerTab(key) } } : undefined}>
    <PaySheet orderId={orderId} preview={preview} scenario={scenario} idPrefix='bkp'
      onClose={() => { void goBack() }}
      onOpenOrderDetail={() => { void Taro.redirectTo({ url: detailUrl }).catch(() => undefined) }}
      onOpenOrderList={() => { void Taro.redirectTo({ url: listUrl }).catch(() => undefined) }} />
  </ConsumerPageLayout>
}
