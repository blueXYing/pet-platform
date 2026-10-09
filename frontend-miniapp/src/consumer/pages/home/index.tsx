import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useState, type CSSProperties } from 'react'
import { switchConsumerTab } from '../../components/navigation/switch'
import searchIcon from '../../assets/home/home-search.png'
import bellIcon from '../../assets/home/home-bell.png'
import './home.css'

// 首页（tabbar+login 切片，用户 2026-10-08 裁决）。设计源：登记表 §3「首页」——33:79 为开屏
// 感谢页、61:599（首页1）为真实首页原稿。本页为功能版骨架：品牌/文案/保障体系区块与搜索/
// 铃铛图标取自原稿（62:27/62:43 原切图），原稿的营销位（首单立减/活动钜惠）、热门服务与
// 宠友圈热门板块无 V1 后端与社区域（COM-001 未启动），登记偏差留补稿轮，不虚构内容。
// 全部入口指向已交付真实页面：门店目录（匿名可浏览）/订单/宠物档案/优惠券/成为商家。
const guarantee = [
  { key: 'certified', title: '实名认证', body: '所有服务者均通过身份认证与资质审核' },
  { key: 'rating', title: '服务评分', body: '真实用户评价，透明公开的评分体系' },
  { key: 'insurance', title: '保险保障', body: '每笔订单附带服务保险，为爱宠保驾护航' },
  { key: 'escrow', title: '担保交易', body: '资金由平台托管，服务完成后安全结算' },
] as const

export default function HomePage() {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  // 静默登录在 app 启动（workspace-react launch）完成；本页无登录态依赖。
  const style = { '--home-unit': `${unit}px`, '--home-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  function openStores() {
    Taro.navigateTo({ url: '/consumer/pages/store-services/stores' }).catch(() => Taro.showToast({ title: '页面打开失败，请重试', icon: 'none' }))
  }
  function open(path: string) {
    Taro.navigateTo({ url: path }).catch(() => Taro.showToast({ title: '页面打开失败，请重试', icon: 'none' }))
  }
  function goMessages() { void switchConsumerTab('messages') }
  return <View className='home-page' style={style}>
    <View className='home-status-area' />
    <View className='home-header'>
      <View className='home-brand'>
        <Text className='home-brand-title'>宠物生活服务管家</Text>
        <Text className='home-brand-sub'>宠事轻松管 · 服务一键达</Text>
      </View>
      <View className='home-header-actions'>
        <Button id='home-search' ariaLabel='找门店与服务' className='home-icon-button' onClick={openStores}>
          <Image className='home-icon' src={searchIcon} mode='scaleToFill' />
        </Button>
        <Button id='home-bell' ariaLabel='消息中心' className='home-icon-button' onClick={goMessages}>
          <Image className='home-icon' src={bellIcon} mode='scaleToFill' />
        </Button>
      </View>
    </View>
    <ScrollView className='home-body' scrollY enhanced showScrollbar={false}>
      <Button id='home-stores' className='home-hero' hoverClass='none' onClick={openStores}>
        <Text className='home-hero-title'>找门店 · 找服务</Text>
        <Text className='home-hero-sub'>浏览门店目录与服务，单次服务须预约（分钟级排期）</Text>
        <Text className='home-hero-go'>进入目录 ›</Text>
      </Button>
      <View className='home-grid'>
        <Button id='home-orders' className='home-cell' hoverClass='none' onClick={() => open('/consumer/pages/orders/list')}>
          <Text className='home-cell-title'>我的订单</Text>
          <Text className='home-cell-sub'>预约/服务/售后</Text>
        </Button>
        <Button id='home-pets' className='home-cell' hoverClass='none' onClick={() => open('/consumer/pages/pet-archive/index')}>
          <Text className='home-cell-title'>宠物档案</Text>
          <Text className='home-cell-sub'>爱宠资料管理</Text>
        </Button>
        <Button id='home-coupons' className='home-cell' hoverClass='none' onClick={() => open('/consumer/pages/coupon-points/coupons')}>
          <Text className='home-cell-title'>优惠券</Text>
          <Text className='home-cell-sub'>可用券与明细</Text>
        </Button>
        <Button id='home-merchant' className='home-cell' hoverClass='none' onClick={() => open('/consumer/pages/merchant-application/index')}>
          <Text className='home-cell-title'>成为商家</Text>
          <Text className='home-cell-sub'>入驻开店</Text>
        </Button>
      </View>
      <View className='home-section'>
        <Text className='home-section-title'>平台保障体系</Text>
        <View className='home-guarantee'>
          {guarantee.map(item => <View key={item.key} className='home-guarantee-card'>
            <Text className='home-guarantee-title'>{item.title}</Text>
            <Text className='home-guarantee-body'>{item.body}</Text>
          </View>)}
        </View>
      </View>
      <View className='home-tail'><Text>页面数据：入口聚合页（门店目录匿名可浏览；其余页面自带真实/预览通道）</Text></View>
    </ScrollView>
  </View>
}
