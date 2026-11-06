import { Image, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import type { CSSProperties } from 'react'
import { markTabHandoff } from '../../tab-handoff'
import tabHome from './assets/tab-home.svg'
import tabHomeInactive from './assets/tab-home-inactive.svg'
import tabOrder from './assets/tab-order.svg'
import tabOrderInactive from './assets/tab-order-inactive.svg'
import tabVerify from './assets/tab-verify.svg'
import tabMessages from './assets/tab-messages.svg'
import tabMessagesInactive from './assets/tab-messages-inactive.svg'
import tabMine from './assets/tab-mine.svg'
import tabMineInactive from './assets/tab-mine-inactive.svg'
import './index.css'

// 商家端底部五 tab（Figma 95:1899 153:3434 / 149:926）：首页/订单/核销/消息/我的。
// 视觉与 402 设计稿一致：61 高 #f0fbff + 1px #c0ecff 顶线，中间 53x53 #5baae8 凸起核销键。
// M 端不是原生 tabBar（子包页面不能注册为 tab 页）：切换用 navigateTo；目标页已在返回栈时
// 用 navigateBack 回收，避免无限堆栈。工作台在离开前通过 onBeforeSwitch 调 handoffToChild，
// 保留准入坐标供子页复验（MINI-003 每次进入仍重查准入）。
export type MerchantTabKey = 'home' | 'orders' | 'verify' | 'messages' | 'mine'

const ROUTES: Record<MerchantTabKey, string> = {
  home: '/merchant/pages/workspace/index',
  orders: '/merchant/pages/order-list/index',
  verify: '/merchant/pages/staff-verify/index',
  messages: '/merchant/pages/messages/index',
  mine: '/merchant/pages/mine/index',
}

export function merchantTabRoute(key: MerchantTabKey): string {
  return ROUTES[key]
}

export function MerchantTabBar(props: {
  active: MerchantTabKey
  unit: number
  onBeforeSwitch?: (key: MerchantTabKey) => void
  onError?: () => void
}) {
  const style = { '--mtb-unit': `${props.unit}px` } as CSSProperties
  function switchTo(key: MerchantTabKey) {
    if (key === props.active) return
    // The tab bar owns the handoff marker: even when the page-level onBeforeSwitch prop is
    // unavailable, the workspace's onHide must not clear the merchant coordinates for a tab
    // sibling. The marker is one-shot and short-lived.
    markTabHandoff()
    props.onBeforeSwitch?.(key)
    // Tabs are siblings, not a back stack. redirectTo keeps exactly one tab page alive, so a
    // disposed workbench controller can never be resurrected into a stale placeholder state.
    Taro.redirectTo({ url: ROUTES[key] }).catch(() => {
      props.onError?.()
      void Taro.showToast({ title: '页面跳转失败，请重试', icon: 'none' })
    })
  }
  return <View className='mtb-bar' style={style}>
    <View className='mtb-items'>
      <View id='mtb-home' className={props.active === 'home' ? 'mtb-item mtb-active' : 'mtb-item'}
        onClick={() => switchTo('home')}>
        <Image className='mtb-icon mtb-icon-home' src={props.active === 'home' ? tabHome : tabHomeInactive} mode='aspectFit' />
        <Text className='mtb-label'>首页</Text>
      </View>
      <View id='mtb-orders' className={props.active === 'orders' ? 'mtb-item mtb-active' : 'mtb-item'}
        onClick={() => switchTo('orders')}>
        <Image className='mtb-icon mtb-icon-order' src={props.active === 'orders' ? tabOrder : tabOrderInactive} mode='aspectFit' />
        <Text className='mtb-label'>订单</Text>
      </View>
      <View id='mtb-verify' className={props.active === 'verify' ? 'mtb-item mtb-item-center mtb-active' : 'mtb-item mtb-item-center'}
        onClick={() => switchTo('verify')}>
        <View className='mtb-center-button'>
          <Image className='mtb-center-icon' src={tabVerify} mode='aspectFit' />
        </View>
        <Text className='mtb-label mtb-center-label'>核销</Text>
      </View>
      <View id='mtb-messages' className={props.active === 'messages' ? 'mtb-item mtb-active' : 'mtb-item'}
        onClick={() => switchTo('messages')}>
        <Image className='mtb-icon mtb-icon-messages' src={props.active === 'messages' ? tabMessages : tabMessagesInactive} mode='aspectFit' />
        <Text className='mtb-label'>消息</Text>
      </View>
      <View id='mtb-mine' className={props.active === 'mine' ? 'mtb-item mtb-active' : 'mtb-item'}
        onClick={() => switchTo('mine')}>
        <Image className='mtb-icon mtb-icon-mine' src={props.active === 'mine' ? tabMine : tabMineInactive} mode='aspectFit' />
        <Text className='mtb-label'>我的</Text>
      </View>
    </View>
  </View>
}
