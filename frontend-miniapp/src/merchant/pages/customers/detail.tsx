import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { consumerApi, consumerStorage } from '../../../shared/consumer-runtime'
import { merchantStorefrontDeps } from '../../storefront/repository'
import { CustomerDetailController } from '../../storefront/read-controllers'
import { lastVisitLabel } from '../../storefront/model'
import navBack from '../aftersale/assets/nav-back@3x.png'
import './page.css'

/** Contract 69 detail face: the route carries the derived customer's userId only. */
function useCustomerDetail() {
  const [controller] = useState(() => {
    const context = consumerApi.scope.capture().context
    if (context.workspace !== 'merchant' || !context.merchantId || !context.storeId) {
      throw new Error('MERCHANT_ENTRY_REQUIRED')
    }
    const userId = Taro.getCurrentInstance().router?.params?.userId ?? ''
    if (!/^[1-9][0-9]{0,18}$/.test(userId)) throw new Error('INVALID_CUSTOMER_ID')
    return new CustomerDetailController(
      merchantStorefrontDeps(consumerApi, consumerStorage),
      { merchantId: context.merchantId, storeId: context.storeId }, userId)
  })
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  useDidShow(() => { void controller.load() })
  useDidHide(() => controller.hide())
  useEffect(() => () => controller.dispose(), [controller])
  const [info] = useState(() => Taro.getWindowInfo())
  const style = { '--mcu-unit': `${info.windowWidth / 402}px`, '--mcu-status-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  return { state, style }
}

async function backToList() {
  try {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/merchant/pages/customers/index' })
  } catch { await Taro.showToast({ title: '返回失败，请重试', icon: 'none' }) }
}

// 客户详情 M 面（69号，帧 23:13478 偏差已登记）：消费统计 + 宠物名字/品种级列表。
// 帧内的体重/疫苗/驱虫/健康备注/商家备注属隐私红线与写能力，V1 不做（CCR 登记）。
export default function MerchantCustomerDetailPage() {
  const { state, style } = useCustomerDetail()
  const detail = state.detail
  const customer = detail?.customer
  return <View className='mcu-page' style={style}>
    <View className='mcu-status-area' />
    <View className='mcu-header'>
      <Button className='mcu-back' ariaLabel='返回' onClick={() => void backToList()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mcu-title'>客户详情</Text>
    </View>
    {state.phase !== 'ready' || !customer ? <View className='mcu-state' role='status'>
      <Text>{state.phase === 'loading' || state.phase === 'idle' ? '正在读取客户档案…' : state.notice || (state.phase === 'entry' ? '仅商家 OWNER 可查看本店客户档案' : '页面暂时不可用')}</Text>
      {state.phase === 'error' && <Button className='mcu-primary' onClick={() => { void 0 }}>重新加载</Button>}
      <Button className='mcu-primary' onClick={() => void backToList()}>返回客户列表</Button>
    </View> : <ScrollView className='mcu-list' scrollY enhanced showScrollbar={false}>
      <View className='mcu-card'>
        <View className='mcu-line mcu-card-head'>
          <Text className='mcu-name'>{customer.nickname ?? '未命名用户'}</Text>
          <Text className='mcu-phone'>{customer.phoneMasked ?? '未绑定手机号'}</Text>
          <Text className='mcu-visit'>{lastVisitLabel(customer.lastVisitAt, new Date())}到店</Text>
        </View>
        <View className='mcu-metrics mcu-metrics-row'>
          <View className='mcu-metric'><Text className='mcu-metric-value'>¥{customer.totalSpend}</Text><Text className='mcu-metric-label'>累计消费</Text></View>
          <View className='mcu-metric'><Text className='mcu-metric-value'>{customer.orderCount}</Text><Text className='mcu-metric-label'>累积订单</Text></View>
          <View className='mcu-metric'><Text className='mcu-metric-value'>{customer.avgTicket ? `¥${customer.avgTicket}` : '--'}</Text><Text className='mcu-metric-label'>客单价</Text></View>
        </View>
      </View>
      <View className='mcu-card'>
        <Text className='mcu-card-title'>宠物信息（名字/品种级，69号隐私口径）</Text>
        {detail.pets.length === 0 && <Text className='mcu-value'>本店订单暂无宠物快照</Text>}
        {detail.pets.map((pet, index) => <View key={index} className='mcu-line'>
          <Text className='mcu-value'>{pet.petName}（{pet.breedName ?? pet.petType}）</Text>
        </View>)}
        <Text className='mcu-hint'>体重/疫苗/驱虫/健康备注等健康字段不在商家端展示（CCR 待裁）。</Text>
      </View>
      <Text className='mcu-tail'>仅展示本店订单派生数据 · 只读（69号）</Text>
    </ScrollView>}
  </View>
}
