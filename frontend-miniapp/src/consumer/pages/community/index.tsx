import { Button, Image, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useState, type CSSProperties } from 'react'
import balloon from '../../assets/navigation/community.png'
import './community.css'

// 宠友圈占位 tab（用户 2026-10-08 裁决：COM-001 未启动，失败关闭占位，不虚构任何社区
// 功能）。设计源：登记表 §3「宠友圈」原稿（115:3818 等）存在但整体未还原——社区域未启动，
// 帖子/发布/互动均为未交付能力；本页只保留「即将上线」占位与可用的真实出口（首页/门店目录）。
export default function CommunityPage() {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = { '--cmy-unit': `${unit}px`, '--cmy-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  function openStores() {
    Taro.navigateTo({ url: '/consumer/pages/store-services/stores' }).catch(() => Taro.showToast({ title: '页面打开失败，请重试', icon: 'none' }))
  }
  return <View className='cmy-page' style={style}>
    <View className='cmy-status-area' />
    <View className='cmy-header'>
      <Text className='cmy-title'>宠友圈</Text>
    </View>
    <View className='cmy-body'>
      <View className='cmy-placeholder' role='status'>
        <Image className='cmy-balloon' src={balloon} mode='scaleToFill' />
        <Text className='cmy-soon'>即将上线</Text>
        <Text className='cmy-hint'>社区功能暂未开放，当前版本不提供帖子、互动或发布能力。</Text>
        <Button id='cmy-stores' className='cmy-action' hoverClass='none' onClick={openStores}>
          <Text className='cmy-action-text'>先去逛逛门店与服务</Text>
        </Button>
      </View>
    </View>
  </View>
}
