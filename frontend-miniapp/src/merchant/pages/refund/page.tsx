import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore, type CSSProperties, type PropsWithChildren } from 'react'
import { MerchantRefundClient } from '../../../shared/merchant-refund-api'
import { consumerApi } from '../../../shared/consumer-runtime'
import { MerchantRefundController } from '../../refund/controller'
import { merchantRefundDeps } from '../../refund/repository'
import { previewRefundDeps } from '../../workbench/preview'
import { MerchantTabBar } from '../../components/tab-bar'
import navBack from '../aftersale/assets/nav-back@3x.png'
import './page.css'

export function useMerchantRefund(applicationId?: string, enabled = true, preview = false) {
  // ?preview=1 视觉验收：先注入商户坐标，再用本地 deps；真实路径不变。
  useState(() => {
    if (!preview) return false
    const current = consumerApi.scope.current
    if (!current || current.workspace !== 'merchant' || !current.merchantId || !current.storeId) {
      consumerApi.scope.replace({
        userId: '9007199254740993', workspace: 'merchant',
        merchantId: '9007199254740995', storeId: '9007199254740996',
      })
    }
    return true
  })
  const [client] = useState(() => new MerchantRefundClient(consumerApi))
  const [controller] = useState(() => new MerchantRefundController(
    preview ? previewRefundDeps() : merchantRefundDeps(client), consumerApi.scope, applicationId))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  useDidShow(() => { if (enabled) void controller.load(consumerApi.scope.current) })
  useDidHide(() => controller.hide())
  useEffect(() => () => { controller.dispose() }, [controller])
  const [info] = useState(() => Taro.getWindowInfo())
  const style = { '--mrf-unit': `${info.windowWidth / 402}px`, '--mrf-status-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  return { controller, state, style }
}
export function MerchantRefundShell({ children, title, style, onBack }: PropsWithChildren<{ title: string; style: CSSProperties; onBack?: () => void }>) {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  async function back() {
    if (onBack) { onBack(); return }
    try {
      if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
      else await Taro.redirectTo({ url: '/merchant/pages/workspace/index' })
    } catch { await Taro.showToast({ title: '返回失败，请重试', icon: 'none' }) }
  }
  return <View className='mrf-page' style={style}>
    <View className='mrf-status-area' />
    <View className='mrf-header'>
      <Button id='mrf-back' className='mrf-back' ariaLabel='返回' onClick={() => void back()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mrf-title'>{title}</Text>
    </View>
    {children}
    <MerchantTabBar active='orders' unit={unit} />
  </View>
}
export function MerchantRefundState({ controller, text, status }: { controller: MerchantRefundController; text: string; status: string }) {
  return <View className='mrf-state' role='status'>
    <Text>{status === 'loading' || status === 'idle' ? '正在读取退款申请…' : text}</Text>
    {status === 'error' && <Button id='mrf-reload' className='mrf-primary' onClick={() => void controller.load(consumerApi.scope.current)}>重新加载</Button>}
    {(status === 'entry' || status === 'denied') && <Button className='mrf-primary' onClick={() => Taro.redirectTo({ url: '/merchant/pages/workspace/index' })}>去商家工作台</Button>}
  </View>
}
