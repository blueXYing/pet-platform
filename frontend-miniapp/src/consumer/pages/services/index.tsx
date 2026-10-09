import { Button, ScrollView, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useState, type CSSProperties } from 'react'
import './services.css'

// 服务 tab（轻页，用户 2026-10-08 裁决）。信息架构选择（PR 说明）：独立轻页而非与首页分区
// 合并——设计稿「服务」为独立 tab（110:480：分类 chips + 商家列表），V1 保留该信息架构，
// 服务浏览的完整能力落在既有 store-services 子包（门店目录匿名可浏览 + 商家服务列表）。
// 分类 chips 文案取自原稿 110:480 的分类区；V1 无跨店分类筛选契约，点击分类进入全部门店
// 目录（stores 页同文案失败关闭提示），不虚构筛选结果。营销位（商家钜惠）无 V1 后端，登记
// 偏差不实现。
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
    <View className='svc2-status-area' />
    <View className='svc2-header'>
      <Text className='svc2-title'>服务</Text>
      <Text className='svc2-sub'>单次服务须预约 · 分钟级排期</Text>
    </View>
    <ScrollView className='svc2-body' scrollY enhanced showScrollbar={false}>
      <View className='svc2-card'>
        <Text className='svc2-card-title'>服务分类</Text>
        <Text className='svc2-card-hint'>点击分类进入门店目录；分类筛选将在后续版本提供。</Text>
        <View className='svc2-chips'>
          {categories.map(name => <Button key={name} className='svc2-chip' hoverClass='none' onClick={openStores}>
            <Text className='svc2-chip-text'>{name}</Text>
          </Button>)}
        </View>
      </View>
      <Button id='svc2-stores' className='svc2-stores' hoverClass='none' onClick={openStores}>
        <Text className='svc2-stores-title'>全部门店目录</Text>
        <Text className='svc2-stores-sub'>按门店浏览服务、查看排期并预约（匿名可浏览）</Text>
        <Text className='svc2-stores-go'>进入目录 ›</Text>
      </Button>
      <View className='svc2-tail'><Text>页面数据：入口聚合页（门店目录匿名可浏览；预约须登录）</Text></View>
    </ScrollView>
  </View>
}
