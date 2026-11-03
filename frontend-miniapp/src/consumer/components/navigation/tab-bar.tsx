import { Image, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import homeIcon from '../../assets/navigation/home.png'
import servicesIcon from '../../assets/navigation/services.png'
import communityIcon from '../../assets/navigation/community.png'
import messagesIcon from '../../assets/navigation/messages.png'
import mineIcon from '../../assets/navigation/mine.png'
import './tab-bar.css'

const items = [
  { pagePath: '/consumer/pages/home/index', text: '首页', icon: homeIcon },
  { pagePath: '/consumer/pages/services/index', text: '服务', icon: servicesIcon },
  { pagePath: '/consumer/pages/community/index', text: '宠友圈', icon: communityIcon },
  { pagePath: '/consumer/pages/messages/index', text: '消息', icon: messagesIcon },
  { pagePath: '/consumer/pages/mine/index', text: '我的', icon: mineIcon },
] as const

function safeAreaBottom() {
  try {
    const info = Taro.getWindowInfo()
    return Math.max(0, info.screenHeight - (info.safeArea?.bottom ?? info.screenHeight))
  } catch { return 0 }
}

// Taro 4.1.5 的框架 custom-tab-bar React 挂载是坏的（React #130，$taroInstances 恒空，
// 2026-11-02 控制台实测），因此五个根 tab 页各自内联渲染本栏，点击直接 Taro.switchTab。
// 二级页不渲染（2026-10-26 裁决保留：根页常驻、二级页无底栏）。
export function TabBar({ active }: { active: number }) {
  const bottom = safeAreaBottom()
  return <View
    className='tab-bar'
    style={{ height: `${50 + bottom}px`, paddingBottom: `${bottom}px`, borderTop: '1px solid rgba(0, 0, 0, 0.15)' }}
  >
    {items.map((item, index) => <View
      key={item.pagePath}
      className='tab-bar-item'
      style={{ height: '50px', paddingTop: '4px' }}
      onClick={() => void Taro.switchTab({ url: item.pagePath })}
    >
      <Image className='tab-bar-icon' style={{ width: '24px', height: '24px' }} src={item.icon} />
      <View className={`tab-bar-label${active === index ? ' is-selected' : ''}`}
        style={{ marginTop: '1px', fontSize: '10px', lineHeight: '14px' }}>{item.text}</View>
    </View>)}
  </View>
}
