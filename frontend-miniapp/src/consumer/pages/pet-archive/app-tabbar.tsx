import { Button, Image, Text, View } from '@tarojs/components'
import type { CSSProperties } from 'react'
import homeIcon from './assets/tab-home.png'
import servicesIcon from './assets/tab-services.png'
import balloonIcon from './assets/tab-balloon.png'
import messagesIcon from './assets/tab-messages.png'
import mineIcon from './assets/tab-mine.png'

const tabs = [
  { key: 'home', label: '首页', icon: homeIcon },
  { key: 'services', label: '服务', icon: servicesIcon },
  { key: 'community', label: '宠友圈', icon: balloonIcon },
  { key: 'messages', label: '消息', icon: messagesIcon },
  { key: 'mine', label: '我的', icon: mineIcon },
]

// barTop is the design-space y of the bar rect (1191 detail, 990/991 form frames).
export function AppTabbar({ barTop, unit, onLeave }: { barTop: number; unit: number; onLeave: (label: string) => void }) {
  return <View className='pet-tabbar' style={{ '--pet-tabbar-top': `${unit * barTop}px` } as CSSProperties}>
    <View className='pet-tabbar-line' />
    {tabs.map((tab, index) => <Button key={tab.key} id={`pet-tab-${tab.key}`} className={`pet-tab pet-tab-${tab.key}`} style={{ left: `${index * 20}%` }} ariaLabel={tab.label} onClick={() => onLeave(tab.label)}>
      <Image src={tab.icon} mode='scaleToFill' />
      <Text>{tab.label}</Text>
    </Button>)}
  </View>
}
