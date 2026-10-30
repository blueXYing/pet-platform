import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { switchConsumerTab } from '../../components/navigation/switch'
import { syncCustomTabBar } from '../../components/navigation/custom-tab-bar'
import { RootTabBarSpacer } from '../../components/navigation/root-tab-bar-spacer'
import CityPicker from '../../city/picker'
import { readSelectedCity, writeSelectedCity } from '../../city/model'
import type { StoreCity } from '../../store/model'
import { consumerApi } from '../../../shared/consumer-runtime'
import { decodeCityList } from '../../store/model'
import { useWorkspace } from '../../../shared/workspace-react'
import { HomeActivitySlot } from '../../marketing/components'
import { HomeContentSections } from '../../marketing/home-sections'
import { realPetRepository } from '../../api/page-repository'
import { breedAgeLine, sexLabel, type PetView } from '../../pet/model'
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
import defaultCatAvatar from '../pet-archive/assets/pet-default-cat.png'
import defaultDogAvatar from '../pet-archive/assets/pet-default-dog.png'
import cityPawIcon from '../../assets/home/home-city-paw@3x.png'
import petTape from '../../assets/home/pet-tape@3x.png'
import './home.css'

// 首页（tabbar+login 切片，用户 2026-10-08 裁决；2026-10-13 按 Figma 新版 61:599 视觉对齐）。
// 设计源：登记表 §3「首页」——61:599（首页1，2026-10-10 新版）。新版头部（奶油纸纹带 + 品牌
// logo + 搜索/铃铛）与平台保障体系（白卡 + #c0ecff 图标芯片）逐块落地；宠物档案分区以入口卡
// 呈现（首页无读侧拉取——功能/契约零改动，不虚构宠物/疫苗数据，占位头像用平台既有 avatar）。
// 营销位（活动钜惠）已由契约 71 的 HOME_ACTIVITY 投放位驱动（后台新增活动不改小程序代码）；
// 热门服务/宠友圈热门/植物&异宠由 CCR-MKT-003 运营内容位 + CCR-COM-001 真实社区驱动；
// 原稿「宠物百科」链接已接既有 baike 页面。
// 2026-10-19 城市选择裁决回补头部城市芯片（61:599 头部本有：定位 pin #5baae8 + 城市名 #383838
// + 下拉箭头 #3c3c3c；§4.11 偏差2 的「定位城市不实现」随裁决修订为「手动选择城市」）：目录来自
// §3.3.4 /c/cities（匿名），选择持久化本地存储、门店目录页展示时读取并按城市刷新；V1 无定位能力
// （§4.4 维持），pin 仅作芯片图形不入定位语义。全部入口仍指向已交付真实页面。
// 2026-10-23 修正芯片脚印切图：62:9 画框 39.9635 含 6.2px 透明内缩，恢复 117x96@3x
// 带留白切图并以 39x32 设计px 呈现（紧裁切图会贴住 button 左缘被 overflow 裁切）。
const guarantee = [
  { key: 'certified', title: '实名认证', body: '所有服务者均通过身份认证与资质审核', icon: iconCertified },
  { key: 'rating', title: '服务评分', body: '真实用户评价，透明公开的评分体系', icon: iconRating },
  { key: 'insurance', title: '保险保障', body: '每笔订单附带服务保险，为爱宠保驾护航', icon: iconInsurance },
  { key: 'escrow', title: '担保交易', body: '资金由平台托管，服务完成后安全结算', icon: iconEscrow },
] as const

type PetBadge = Readonly<{ title: string; line: string; tone: 'vaccine' | 'deworm' }>

function petAvatar(pet: PetView) {
  return pet.avatarUrl || (pet.petType === 'DOG' ? defaultDogAvatar : pet.petType === 'CAT' ? defaultCatAvatar : placeholderAvatar)
}
export default function HomePage() {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  // 静默登录在 app 启动（workspace-react launch）完成；本页无登录态依赖。
  const style = { '--home-unit': `${unit}px`, '--home-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  // 城市芯片（§3.3.4）：目录匿名读，失败静默退回本地选择；选择写本地存储（门店目录页展示时读取）。
  const [cities, setCities] = useState<StoreCity[]>([])
  const [selectedCity, setSelectedCity] = useState<string | undefined>(() => readSelectedCity().cityCode)
  const [cityPickerOpen, setCityPickerOpen] = useState(false)
  const todayISO = new Date().toISOString().slice(0, 10)
  // 头部对齐基准：微信右上角胶囊（开发者工具/真机都有），头部元素避让并与其垂直居中。
  const menuRect = useState(() => {
    try { return Taro.getMenuButtonBoundingClientRect() } catch { return null }
  })[0]
  const headerTop = menuRect ? menuRect.top : (info.statusBarHeight || 0) + 6
  const headerCenter = headerTop + (menuRect ? menuRect.height : 32) / 2
  const HEADER_LOGO_H = 37 * unit
  const HEADER_CHIP_H = 32 * unit
  const HEADER_ICON_H = 24 * unit
  const headerLayout = {
    logo: { top: `${headerCenter - HEADER_LOGO_H / 2}px` },
    chip: { top: `${headerCenter - HEADER_CHIP_H / 2}px` },
    actions: {
      top: `${headerCenter - HEADER_ICON_H / 2}px`,
      right: `${menuRect ? info.windowWidth - menuRect.left + 10 * unit : 24 * unit}px`,
    },
  } as const
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  // 宠物档案读侧（设计 61:599：标题「宠物档案 N 只萌宠」+ 宠物行 + 疫苗/驱虫状态 + 宠物百科入口）。
  // 匿名/未登录/读取失败一律回退到入口卡，不虚构数据；同屏最多展示 3 只，其余在模块内局部滚动查看。
  const { scope, revision, context } = useWorkspace('real')
  const petRepo = useRef(realPetRepository())
  const [pets, setPets] = useState<PetView[]>([])
  const [petBadges, setPetBadges] = useState<Record<string, PetBadge>>({})
  const petSequence = useRef(0)
  const loadPets = useCallback(async () => {
    const seq = ++petSequence.current
    if (!scope.current || scope.current.workspace !== 'consumer') { setPets([]); setPetBadges({}); return }
    try {
      const list = await scope.run(undefined, () => petRepo.current.load())
      if (!mounted.current || seq !== petSequence.current) return
      const active = list.filter(pet => pet.status === 'ACTIVE')
      setPets(active)
      const entries = await Promise.all(active.map(async (pet): Promise<readonly [string, PetBadge] | null> => {
        try {
          const [vaccines, deworms] = await Promise.all([
            scope.run(undefined, () => petRepo.current.listHealthRecords(pet.petId, 'vaccination-records')),
            scope.run(undefined, () => petRepo.current.listHealthRecords(pet.petId, 'deworming-records')),
          ])
          const latest = [
            ...vaccines.map(record => ({ record, tone: 'vaccine' as const })),
            ...deworms.map(record => ({ record, tone: 'deworm' as const })),
          ].sort((a, b) => b.record.recordDate.localeCompare(a.record.recordDate))[0]
          if (!latest) return null
          const title = latest.tone === 'vaccine'
            ? (latest.record.vaccineName || '疫苗')
            : (latest.record.productName || (latest.record.dewormingType === 'EXTERNAL' ? '体外驱虫' : latest.record.dewormingType === 'INTERNAL' ? '体内驱虫' : '内外同驱'))
          const line = `${latest.tone === 'vaccine' ? '疫苗' : '驱虫'} · ${latest.record.recordDate}`
          return [pet.petId, { title, line, tone: latest.tone }] as const
        } catch { return null }
      }))
      if (!mounted.current || seq !== petSequence.current) return
      const map: Record<string, PetBadge> = {}
      entries.forEach(entry => { if (entry) map[entry[0]] = entry[1] })
      setPetBadges(map)
    } catch {
      if (mounted.current && seq === petSequence.current) { setPets([]); setPetBadges({}) }
    }
  }, [scope])
  const loadCities = useCallback(() => {
    consumerApi.anonymousRequest({ method: 'GET', path: '/api/v1/c/cities' }, decodeCityList)
      .then(catalog => { if (mounted.current && catalog.length) setCities(catalog) })
      .catch(() => { /* 目录不可用：芯片回退本地选择/默认名，面板不打开选项 */ })
  }, [])
  useDidShow(() => { syncCustomTabBar(0); setSelectedCity(readSelectedCity().cityCode); loadCities(); void loadPets() })
  useEffect(() => { void loadPets() }, [loadPets, revision, context])
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
  const visiblePetRows = Math.min(pets.length, 3)
  const petListHeight = visiblePetRows > 0 ? visiblePetRows * 85 - 16 : 0
  return <View className='home-page' style={style}>
    {/* 分层背景（设计 z 序）：网格纸纹（61:31，状态栏起 787 高）→ 奶油头带（0..86）→ 手绘涂鸦带（62:49，86..331）→ #f0fbff 主体 */}
    {/* 网格纸纹铺到宠物分区底部，并继续覆盖活动钜惠的 22px 上间距（+2px 防亚像素缝），
        让纸卡与活动面板之间露出竖条纹纸底而不是页面蓝底；不延伸到后续模块。 */}
    <View className='home-bg-grid-clip' style={{ height: `${(444.41 + 85 * Math.max(1, visiblePetRows)) * unit}px` }}>
      <Image className='home-bg-grid' src={textureGrid} mode='scaleToFill' />
    </View>
    <View className='home-bg-cream' />
    <Image className='home-bg-doodle' src={textureDoodle} mode='scaleToFill' />
    <View className='home-header'>
      <Image className='home-brand-logo' style={headerLayout.logo} src={brandLogo} mode='aspectFit' />
      <View className='home-city-anchor' style={headerLayout.chip}>
        <Button id='home-city' ariaLabel={`当前城市${cityName}，点按切换`} className='home-city-chip' hoverClass='none'
          onClick={() => setCityPickerOpen(open => !open)}>
          <Image className='home-city-pin' src={cityPawIcon} mode='aspectFit' />
          <Text className='home-city-name'>{cityName}</Text>
          <View className='home-city-caret' />
        </Button>
        {cityPickerOpen && <CityPicker idPrefix='home-city' unit={unit}
          cities={cities} current={selectedCity} includeAll={false}
          onPick={(next: string | undefined) => pickCity(next)} />}
      </View>
      <View className='home-header-actions' style={headerLayout.actions}>
        <Button id='home-search' ariaLabel='找门店与服务' className='home-icon-link' hoverClass='none' onClick={openStores}>
          <Image className='home-icon-search' src={searchIcon} mode='scaleToFill' />
        </Button>
        <Button id='home-bell' ariaLabel='消息中心' className='home-icon-link' hoverClass='none' onClick={goMessages}>
          <Image className='home-icon-bell' src={bellIcon} mode='scaleToFill' />
        </Button>
      </View>
    </View>
    <ScrollView className='home-body' scrollY enhanced showScrollbar={false}>
      {/* 宠物档案（设计 61:599：标题 + N 只萌宠 + 宠物行（性别/品种年龄/最近疫苗或驱虫）+ 宠物百科）。
          已登录且读到 ACTIVE 宠物时展示真实明细；否则回退为建卡入口，不虚构数据。 */}
      <View className='home-pet-section'>
        <View className='home-pet-blue-paper' />
        <View className='home-pet-white-paper' />
        <Image className='home-pet-tape' src={petTape} mode='scaleToFill' />
        <View className='home-pet-content'>
          <View className='home-pet-head'>
            <View className='home-pet-head-left'>
              <Text className='home-pet-h1'>宠物档案</Text>
              {pets.length > 0 && <Text className='home-pet-count'>{`${pets.length} 只萌宠`}</Text>}
              <Button id='home-pet-add' className='home-pet-add' ariaLabel='添加宠物' hoverClass='none'
                onClick={() => open('/consumer/pages/pet-archive/form')}>
                <Text className='home-pet-add-text'>+</Text>
              </Button>
            </View>
            <View className='home-pet-more' onClick={() => open('/consumer/pages/baike/index')}>
              <View className='home-pet-more-icon' />
              <Text className='home-pet-more-text'>宠物百科</Text>
              <View className='home-pet-more-chevron' />
            </View>
          </View>
          {pets.length === 0 && (
            <Button id='home-pets' className='home-pet-empty' hoverClass='none' onClick={() => open('/consumer/pages/pet-archive/index')}>
              <Image className='home-pet-avatar' src={placeholderAvatar} mode='scaleToFill' />
              <View className='home-pet-info'>
                <Text className='home-pet-title'>创建爱宠档案</Text>
                <Text className='home-pet-sub'>爱宠资料 · 品种 · 疫苗与驱虫记录</Text>
              </View>
              <Text className='home-pet-go'>›</Text>
            </Button>
          )}
          {pets.length > 0 && (
            <ScrollView className='home-pet-scroll' style={{ height: `calc(var(--home-unit) * ${petListHeight})` }} scrollY enhanced showScrollbar={false}>
              {pets.map(pet => {
                const badge = petBadges[pet.petId]
                const sex = sexLabel(pet.sex)
                // Match the archive list/detail: a pet without an uploaded avatar falls back to
                // its type default (dog/cat); OTHER keeps the generic profile placeholder.
                return (
                  <Button key={pet.petId} className='home-pet-row' hoverClass='none' onClick={() => open(`/consumer/pages/pet-archive/detail?petId=${pet.petId}`)}>
                    <Image className='home-pet-row-avatar' src={petAvatar(pet)} mode='aspectFill' />
                    <View className='home-pet-row-main'>
                      <View className='home-pet-row-name'>
                        <Text className='home-pet-name'>{pet.name}</Text>
                        {sex !== '' && (
                          <View className={pet.sex === 'MALE' ? 'home-pet-sex home-pet-sex-male' : 'home-pet-sex home-pet-sex-female'}>
                            <Text className={pet.sex === 'MALE' ? 'home-pet-sex-text' : 'home-pet-sex-text home-pet-sex-text-female'}>{sex}</Text>
                          </View>
                        )}
                      </View>
                      <Text className='home-pet-breed'>{breedAgeLine(pet, todayISO)}</Text>
                    </View>
                    {badge && (
                      <View className='home-pet-chip'>
                        <View className={badge.tone === 'vaccine' ? 'home-pet-chip-pill home-pet-chip-pill-vaccine' : 'home-pet-chip-pill home-pet-chip-pill-deworm'}>
                          <View className={badge.tone === 'vaccine' ? 'home-pet-chip-icon home-pet-chip-icon-vaccine' : 'home-pet-chip-icon home-pet-chip-icon-deworm'} />
                          <Text className={badge.tone === 'vaccine' ? 'home-pet-chip-title' : 'home-pet-chip-title home-pet-chip-title-deworm'}>{badge.title}</Text>
                        </View>
                        <Text className='home-pet-chip-line'>{badge.line}</Text>
                      </View>
                    )}
                  </Button>
                )
              })}
            </ScrollView>
          )}
        </View>
      </View>
      {/* 活动钜惠：后台 HOME_ACTIVITY 资源位驱动；空/失败整块隐藏（契约 71）。 */}
      <HomeActivitySlot cityCode={selectedCity} />
      {/* 运营内容位：热门服务 / 宠友圈热门 / 植物&异宠（CCR-MKT-003；空/失败整块隐藏）。 */}
      <HomeContentSections cityCode={selectedCity} />
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
      <RootTabBarSpacer />
    </ScrollView>
  </View>
}
