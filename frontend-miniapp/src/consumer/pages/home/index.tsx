import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { switchConsumerTab } from '../../components/navigation/switch'
import CityPicker from '../../city/picker'
import { readSelectedCity, writeSelectedCity } from '../../city/model'
import type { StoreCity } from '../../store/model'
import { consumerApi } from '../../../shared/consumer-runtime'
import { decodeCityList } from '../../store/model'
import searchIcon from '../../assets/home/home-search.png'
import bellIcon from '../../assets/home/home-bell.png'
import brandLogo from '../../assets/home/brand-logo@2x.png'
import textureGrid from '../../assets/home/huaban-grid@2x.jpg'
import textureDoodle from '../../assets/home/huaban-doodle@2x.jpg'
import iconCertified from '../../assets/home/guarantee-certified@2x.png'
import iconRating from '../../assets/home/guarantee-rating@2x.png'
import iconInsurance from '../../assets/home/guarantee-insurance@2x.png'
import iconEscrow from '../../assets/home/guarantee-escrow@2x.png'
import placeholderAvatar from '../../assets/profile/avatar.png'
import { readOnboardingDone } from '../onboarding/model'
import './home.css'

// 首页（tabbar+login 切片，用户 2026-10-08 裁决；2026-10-13 按 Figma 新版 61:599 视觉对齐）。
// 设计源：登记表 §3「首页」——61:599（首页1，2026-10-10 新版）。新版头部（奶油纸纹带 + 品牌
// logo + 搜索/铃铛）与平台保障体系（白卡 + #c0ecff 图标芯片）逐块落地；宠物档案分区以入口卡
// 呈现（首页无读侧拉取——功能/契约零改动，不虚构宠物/疫苗数据，占位头像用平台既有 avatar）。
// 原稿营销位（热门活动）、热门服务与宠友圈热门板块无 V1 后端与社区域（COM-001 未启动），
// 维持 §4.4 裁决不实现；原稿「宠物百科」链接属品种百科（V1 范围外）不实现。
// 2026-10-19 城市选择裁决回补头部城市芯片（61:599 头部本有：定位 pin #5baae8 + 城市名 #383838
// + 下拉箭头 #3c3c3c；§4.11 偏差2 的「定位城市不实现」随裁决修订为「手动选择城市」）：目录来自
// §3.3.4 /c/cities（匿名），选择持久化本地存储、门店目录页展示时读取并按城市刷新；V1 无定位能力
// （§4.4 维持），pin 仅作芯片图形不入定位语义。全部入口仍指向已交付真实页面。
const guarantee = [
  { key: 'certified', title: '实名认证', body: '所有服务者均通过身份认证与资质审核', icon: iconCertified },
  { key: 'rating', title: '服务评分', body: '真实用户评价，透明公开的评分体系', icon: iconRating },
  { key: 'insurance', title: '保险保障', body: '每笔订单附带服务保险，为爱宠保驾护航', icon: iconInsurance },
  { key: 'escrow', title: '担保交易', body: '资金由平台托管，服务完成后安全结算', icon: iconEscrow },
] as const

export default function HomePage() {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  // 静默登录在 app 启动（workspace-react launch）完成；本页无登录态依赖。
  const style = { '--home-unit': `${unit}px`, '--home-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  // 城市芯片（§3.3.4）：目录匿名读，失败静默退回本地选择；选择写本地存储（门店目录页展示时读取）。
  const [cities, setCities] = useState<StoreCity[]>([])
  const [selectedCity, setSelectedCity] = useState<string | undefined>(() => readSelectedCity().cityCode)
  const [cityPickerOpen, setCityPickerOpen] = useState(false)
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  // 开屏引导首启探测（用户 2026-10-21 裁决）：仅首次打开显示；完成/跳过由引导页写
  // consumer:onboarding-done 标记后 navigateBack，本页不再探测。静默登录在 app 层进行，不受影响。
  useEffect(() => {
    if (!readOnboardingDone()) {
      Taro.navigateTo({ url: '/consumer/pages/onboarding/index' }).catch(() => { /* 打开失败不阻断首页 */ })
    }
  }, [])
  const loadCities = useCallback(() => {
    consumerApi.anonymousRequest({ method: 'GET', path: '/api/v1/c/cities' }, decodeCityList)
      .then(catalog => { if (mounted.current && catalog.length) setCities(catalog) })
      .catch(() => { /* 目录不可用：芯片回退本地选择/默认名，面板不打开选项 */ })
  }, [])
  useDidShow(() => { setSelectedCity(readSelectedCity().cityCode); loadCities() })
  function pickCity(next: string | undefined) {
    setCityPickerOpen(false)
    setSelectedCity(next)
    writeSelectedCity(next)
  }
  const cityName = selectedCity !== undefined
    ? (cities.find(entry => entry.cityCode === selectedCity)?.cityName || selectedCity)
    : (cities[0]?.cityName || '成都')
  function openStores() {
    Taro.navigateTo({ url: '/consumer/pages/store-services/stores' }).catch(() => Taro.showToast({ title: '页面打开失败，请重试', icon: 'none' }))
  }
  function open(path: string) {
    Taro.navigateTo({ url: path }).catch(() => Taro.showToast({ title: '页面打开失败，请重试', icon: 'none' }))
  }
  function goMessages() { void switchConsumerTab('messages') }
  return <View className='home-page' style={style}>
    {/* 分层背景（设计 z 序）：网格纸纹（61:31，状态栏起 787 高）→ 奶油头带（0..86）→ 手绘涂鸦带（62:49，86..331）→ #f0fbff 主体 */}
    <Image className='home-bg-grid' src={textureGrid} mode='scaleToFill' />
    <View className='home-bg-cream' />
    <Image className='home-bg-doodle' src={textureDoodle} mode='scaleToFill' />
    <View className='home-header'>
      <Image className='home-brand-logo' src={brandLogo} mode='aspectFit' />
      <View className='home-city-anchor'>
        <Button id='home-city' ariaLabel={`当前城市${cityName}，点按切换`} className='home-city-chip' hoverClass='none'
          onClick={() => setCityPickerOpen(open => !open)}>
          <View className='home-city-pin' />
          <Text className='home-city-name'>{cityName}</Text>
          <View className='home-city-caret' />
        </Button>
        {cityPickerOpen && <CityPicker idPrefix='home-city' unit={unit}
          cities={cities} current={selectedCity} includeAll={false}
          onPick={(next: string | undefined) => pickCity(next)} />}
      </View>
      <View className='home-header-actions'>
        <Button id='home-search' ariaLabel='找门店与服务' className='home-icon-link' hoverClass='none' onClick={openStores}>
          <Image className='home-icon-search' src={searchIcon} mode='scaleToFill' />
        </Button>
        <Button id='home-bell' ariaLabel='消息中心' className='home-icon-link' hoverClass='none' onClick={goMessages}>
          <Image className='home-icon-bell' src={bellIcon} mode='scaleToFill' />
        </Button>
      </View>
    </View>
    <ScrollView className='home-body' scrollY enhanced showScrollbar={false}>
      {/* 宠物档案分区（新版 245:4155 标题行 + 卡片壳）：无首页读侧，入口卡不虚构宠物/疫苗数据 */}
      <View className='home-pet-section'>
        <Button id='home-pets' className='home-pet-card' hoverClass='none' onClick={() => open('/consumer/pages/pet-archive/index')}>
          <Image className='home-pet-avatar' src={placeholderAvatar} mode='scaleToFill' />
          <View className='home-pet-info'>
            <Text className='home-pet-title'>宠物档案</Text>
            <Text className='home-pet-sub'>爱宠资料 · 品种 · 疫苗与驱虫记录</Text>
          </View>
          <Text className='home-pet-go'>›</Text>
        </Button>
      </View>
      {/* 功能入口（§4.4 功能版子集；原稿该区域为营销位/热门服务——无 V1 后端维持裁剪，入口卡沿白卡家族呈现） */}
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
            <View className='home-guarantee-icon'><Image src={item.icon} mode='aspectFit' /></View>
            <View className='home-guarantee-text'>
              <Text className='home-guarantee-title'>{item.title}</Text>
              <Text className='home-guarantee-body'>{item.body}</Text>
            </View>
          </View>)}
        </View>
      </View>
      <View className='home-tail'><Text>页面数据：入口聚合页（门店目录匿名可浏览；其余页面自带真实/预览通道）</Text></View>
    </ScrollView>
  </View>
}
