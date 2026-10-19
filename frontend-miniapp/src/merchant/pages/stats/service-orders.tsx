import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { consumerApi, consumerStorage } from '../../../shared/consumer-runtime'
import { merchantStorefrontDeps } from '../../storefront/repository'
import { ServiceOrdersController } from '../../storefront/read-controllers'
import { merchantDisplayStatusLabels } from '../../order/model'
import { windowLabel } from '../../storefront/model'
import navBack from '../aftersale/assets/nav-back@3x.png'
import './page.css'

// 服务下钻 M 面（70号 2026-10-19 扩展）：Top5 行进入的本店订单明细分页，只读。
// displayStatus 为 order 域统一投影，页面只呈现不推导（ARCH-005）。

// 服务下钻 M 面（70号 2026-10-19 扩展）：Top5 行进入的本店订单明细分页，只读。
function useServiceOrders() {
  const [controller] = useState(() => {
    const context = consumerApi.scope.capture().context
    if (context.workspace !== 'merchant' || !context.merchantId || !context.storeId) {
      throw new Error('MERCHANT_ENTRY_REQUIRED')
    }
    const router = Taro.getCurrentInstance().router?.params ?? {}
    const serviceId = router.serviceId ?? ''
    if (!/^[1-9][0-9]{0,18}$/.test(serviceId)) throw new Error('INVALID_SERVICE_ID')
    const range = (['TODAY', 'LAST_7D', 'LAST_30D', 'CUSTOM'] as const).includes(
      router.range as never) ? router.range as 'TODAY' | 'LAST_7D' | 'LAST_30D' | 'CUSTOM' : 'LAST_7D'
    const custom = range === 'CUSTOM'
      ? { from: router.from ?? '', to: router.to ?? '' } : undefined
    return new ServiceOrdersController(
      merchantStorefrontDeps(consumerApi, consumerStorage),
      { merchantId: context.merchantId, storeId: context.storeId }, serviceId, range, custom)
  })
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  useDidShow(() => { void controller.load() })
  useDidHide(() => controller.hide())
  useEffect(() => () => controller.dispose(), [controller])
  const [info] = useState(() => Taro.getWindowInfo())
  const style = { '--mst-unit': `${info.windowWidth / 402}px`, '--mst-status-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  return { controller, state, style }
}

async function backToStats() {
  try {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/merchant/pages/stats/index' })
  } catch { await Taro.showToast({ title: '返回失败，请重试', icon: 'none' }) }
}

function serviceOrderTime(iso: string | null): string {
  if (!iso) return '--'
  return iso.slice(0, 16).replace('T', ' ') + 'Z'
}

export default function MerchantServiceOrdersPage() {
  const { controller, state, style } = useServiceOrders()
  const page = state.page
  return <View className='mst-page' style={style}>
    <View className='mst-status-area' />
    <View className='mst-header'>
      <Button className='mst-back' ariaLabel='返回' onClick={() => void backToStats()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mst-title'>服务订单明细</Text>
    </View>
    {state.phase !== 'ready' || !page ? <View className='mst-state' role='status'>
      <Text>{state.phase === 'loading' || state.phase === 'idle' ? '正在读取服务订单明细…' : state.notice || (state.phase === 'entry' ? '仅商家 OWNER 可查看本店订单明细' : '页面暂时不可用')}</Text>
      {state.phase === 'error' && <Button className='mst-primary' onClick={() => void controller.load()}>重新加载</Button>}
    </View> : <ScrollView className='mst-body' scrollY enhanced showScrollbar={false}
      onScrollToLower={() => void controller.loadMore()}>
      <Text className='mst-tail'>{page.serviceName ?? '已下架服务'} · {windowLabel(page.range)} · 共 {page.total} 单（只读）</Text>
      {page.items.map(row => <View key={row.orderId} className='mst-card'>
        <View className='mst-kv'><Text className='mst-kv-label'>订单号</Text><Text className='mst-kv-value'>{row.orderNo ?? row.orderId}</Text></View>
        <View className='mst-kv'><Text className='mst-kv-label'>状态</Text><Text className='mst-kv-value'>{merchantDisplayStatusLabels[row.displayStatus]}</Text></View>
        <View className='mst-kv'><Text className='mst-kv-label'>实付</Text><Text className='mst-kv-value'>¥{row.payAmount}</Text></View>
        <View className='mst-kv'><Text className='mst-kv-label'>已退</Text><Text className='mst-kv-value'>¥{row.refundedAmount}</Text></View>
        <View className='mst-kv'><Text className='mst-kv-label'>下单时间</Text><Text className='mst-kv-value'>{serviceOrderTime(row.createdAt)}</Text></View>
        <View className='mst-kv'><Text className='mst-kv-label'>预约开始</Text><Text className='mst-kv-value'>{serviceOrderTime(row.appointmentStartAt)}</Text></View>
        <View className='mst-kv'><Text className='mst-kv-label'>核销时间</Text><Text className='mst-kv-value'>{serviceOrderTime(row.verifiedAt)}</Text></View>
      </View>)}
      {page.items.length === 0 && <Text className='mst-empty'>该周期暂无订单</Text>}
      {state.notice && <View className='mst-notice' role='alert'><Text>{state.notice}</Text></View>}
      <Text className='mst-tail'>{(page.page * page.pageSize) >= page.total ? '已加载全部' : '上拉加载更多'}</Text>
      <View className='mst-bottom-space' />
    </ScrollView>}
  </View>
}
