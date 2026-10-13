import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useState, type CSSProperties } from 'react'
import brandLogo from '../../assets/home/brand-logo@2x.png'
import textureGrid from '../../assets/home/huaban-grid@2x.jpg'
import './services.css'

// 服务 tab（轻页，用户 2026-10-08 裁决；2026-10-13 按 Figma 新版 690:6370 视觉对齐）。
// 信息架构选择（PR 说明）维持：独立轻页而非与首页分区合并——设计稿「服务」为独立 tab，
// 服务浏览的完整能力落在既有 store-services 子包（门店目录匿名可浏览 + 商家服务列表）。
// 新版头部（奶油纸纹带 + 品牌 logo + 72 高网格纸纹条）与 #f0fbff 主体落地；分类 chips 沿
// §4.4 裁决文案取自 110:480 分类区，药丸形态对齐新版排序按钮（#daf4ff 底）。原稿排序四键
// （综合排序/距离最近/评分最高/销量优先）与服务者信息流（评分/月售/距离/新客促销）无 V1
// 契约事实不实现，登记偏差留补稿轮；头部定位/搜索/铃铛沿 §4.4 口径（定位无能力；本页不
// 新增交互）不引入。营销位（商家钜惠）无 V1 后端，维持登记偏差不实现。
const categories = [
  '全部服务', '宠物美容', '遛狗陪护', '宠物寄养', '宠物训练', '上门喂养', '兽医助理',
  '小宠寄养', '异宠上门喂养', '异宠健康检查', '绿植养护', '植物代养',
] as const

export default function ServicesPage() {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = { '--svc2-unit': `${unit}px`, '--svc2-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  function openStores() {
    Taro.navigateTo({ url: '/consumer/pages/store-services/stores' }).catch(() => Taro.showToast({ title: '页面打开失败，请重试', icon: 'none' }))
  }
  return <View className='svc2-page' style={style}>
    <Image className='svc2-bg-grid' src={textureGrid} mode='scaleToFill' />
    <View className='svc2-bg-cream' />
    <View className='svc2-header'>
      <Image className='svc2-logo' src={brandLogo} mode='aspectFit' />
    </View>
    <ScrollView className='svc2-body' scrollY enhanced showScrollbar={false}>
      <View className='svc2-chips'>
        {categories.map(name => <Button key={name} className='svc2-chip' hoverClass='none' onClick={openStores}>
          <Text className='svc2-chip-text'>{name}</Text>
        </Button>)}
      </View>
      <Button id='svc2-stores' className='svc2-stores' hoverClass='none' onClick={openStores}>
        <Text className='svc2-stores-title'>全部门店目录</Text>
        <Text className='svc2-stores-sub'>按门店浏览服务、查看排期并预约（匿名可浏览；单次服务须预约 · 分钟级排期）</Text>
        <Text className='svc2-stores-go'>进入目录 ›</Text>
      </Button>
      <View className='svc2-tail'><Text>页面数据：入口聚合页（门店目录匿名可浏览；预约须登录）</Text></View>
    </ScrollView>
  </View>
}
