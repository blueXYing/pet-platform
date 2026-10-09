import { Button, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { switchConsumerTab } from '../../components/navigation/switch'
import { ConsumerPageLayout } from '../../components/page-layout'
import { BookingSheet } from '../../components/booking-sheet/booking-sheet'
import { isBookingScenario } from '../../booking/model'
import './booking.css'

// 预约下单直连页（10号 §3.4 可约时段 + §3.5 创建订单）。2026-10 半屏弹层改造（Figma
// 690:4506「立即预约」）：预约不再独立整页表单，服务详情页/门店页行内「预约」直接弹出
// BookingSheet 半屏弹层；本页保留为开发者工具直连通道与 preview 夹具通道（shell/设计
// 验收按 URL 直入），内部复用同一 BookingSheet 组件，业务逻辑（日期条/可约时段/pickup
// 双时段/宠物/备注/接送地址/确认信息/幂等提交与恢复/回执）全部在组件内，此处只保留
// 页面 chrome（返回导航 + 弹层宿主）。字段以契约为唯一来源（见 booking/model 头注），
// preview=1 沿本地夹具通道（scenario=pickup 演示接送成功预约）。
export default function BookingCreatePage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isBookingScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { revision } = useWorkspace(preview ? 'preview' : 'real')
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--bkg-status-top': `${platformInfo.statusBarHeight || 0}px`, '--bkg-unit': `${unit}px` } as CSSProperties
  const serviceId = route.params.serviceId || ''
  const storeIdParam = route.params.storeId || ''

  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: `/consumer/pages/store-services/service-detail?${preview ? 'preview=1&' : ''}serviceId=${encodeURIComponent(serviceId)}` })
  }

  // 弹层渲染在 .bkg-page 之外（页面根为无样式 View）：booking.css 的 .bkg-page button 重置
  //（margin: 0 !important 压 wx-button 内建 margin）不影响弹层内按钮边距；弹层自身
  // position:fixed 全屏遮罩，不依赖页面容器。
  return <View>
    <ConsumerPageLayout page='bookingCreate' unit={unit} className='bkg-page bks-lock' style={style}
      navigation={{ idPrefix: 'bkg', onSelect: key => { void switchConsumerTab(key) } }}>
      <View className='bkg-design'>
        <View className='bkg-status-area' />
        <View className='bkg-nav'>
          <Button id='bkg-back' ariaLabel='返回' className='bkg-nav-back' onClick={() => void goBack()}><Text className='bkg-nav-back-icon'>‹</Text></Button>
          <Text className='bkg-nav-title'>立即预约</Text>
        </View>
      </View>
    </ConsumerPageLayout>
    <BookingSheet key={revision} idPrefix='bkg' preview={preview} scenario={scenario}
      serviceId={serviceId} storeId={storeIdParam || undefined}
      onClose={() => void goBack()} />
  </View>
}
