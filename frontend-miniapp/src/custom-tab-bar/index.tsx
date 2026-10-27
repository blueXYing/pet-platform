import { Component } from 'react'
import Taro from '@tarojs/taro'
import { CoverImage, CoverView } from '@tarojs/components'
import homeIcon from '../consumer/assets/navigation/home.png'
import servicesIcon from '../consumer/assets/navigation/services.png'
import communityIcon from '../consumer/assets/navigation/community.png'
import messagesIcon from '../consumer/assets/navigation/messages.png'
import mineIcon from '../consumer/assets/navigation/mine.png'
import './index.css'

// Native-look custom tabBar (2026-10-26 user ruling): root tab pages only. Keeping the bar in
// the tab page instead of the platform native layer removes the native hide gap when a
// second-level page is pushed; subpages still never render a bottom bar.
const items = [
  { pagePath: '/consumer/pages/home/index', text: '首页', icon: homeIcon },
  { pagePath: '/consumer/pages/services/index', text: '服务', icon: servicesIcon },
  { pagePath: '/consumer/pages/community/index', text: '宠友圈', icon: communityIcon },
  { pagePath: '/consumer/pages/messages/index', text: '消息', icon: messagesIcon },
  { pagePath: '/consumer/pages/mine/index', text: '我的', icon: mineIcon },
] as const

// Each tab page owns a tabBar instance; the module-level index keeps a newly created instance
// from flashing the previous selection before that page's onShow sync runs.
let selectedIndex = 0

function safeAreaBottom() {
  try {
    const info = Taro.getWindowInfo()
    return Math.max(0, info.screenHeight - (info.safeArea?.bottom ?? info.screenHeight))
  } catch { return 0 }
}
const safeBottom = safeAreaBottom()

export default class CustomTabBar extends Component<Record<string, never>, { selected: number }> {
  state = { selected: selectedIndex }

  setSelected(index: number) {
    selectedIndex = index
    if (index !== this.state.selected) this.setState({ selected: index })
  }

  private openTab(index: number, pagePath: string) {
    this.setSelected(index)
    void Taro.switchTab({ url: pagePath })
  }

  render() {
    const { selected } = this.state
    return <CoverView className='custom-tab-bar' style={{ height: `${50 + safeBottom}px`, paddingBottom: `${safeBottom}px`, borderTop: '1px solid rgba(0, 0, 0, 0.15)' }}>
      {items.map((item, index) => <CoverView key={item.pagePath} className='custom-tab-bar-item'
        style={{ height: '50px', paddingTop: '4px' }} onClick={() => this.openTab(index, item.pagePath)}>
        <CoverImage className='custom-tab-bar-icon' style={{ width: '24px', height: '24px' }} src={item.icon} />
        <CoverView className={`custom-tab-bar-label${selected === index ? ' is-selected' : ''}`}
          style={{ marginTop: '1px', fontSize: '10px', lineHeight: '14px' }}>{item.text}</CoverView>
      </CoverView>)}
    </CoverView>
  }
}
