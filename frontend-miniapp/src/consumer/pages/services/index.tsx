import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useCallback, useRef, useState, type CSSProperties } from 'react'
import { syncCustomTabBar } from '../../components/navigation/custom-tab-bar'
import { switchConsumerTab } from '../../components/navigation/switch'
import { RootTabBarSpacer } from '../../components/navigation/root-tab-bar-spacer'
import CityPicker from '../../city/picker'
import { readSelectedCity, writeSelectedCity } from '../../city/model'
import type { StoreCity } from '../../store/model'
import { runCatalogRead } from '../../store/repository'
import { consumerApi } from '../../../shared/consumer-runtime'
import { decodeCityList } from '../../store/model'
import { useWorkspace } from '../../../shared/workspace-react'
import { ServiceCatalogRepository, type ServiceCategoryView } from '../../service-catalog/model'
import { DealsRail } from './deals-rail'
import { FeaturedStoresSection, useFeaturedStores } from './featured-stores'
import { storesCategoryUrl } from './view-model'
import brandLogo from '../../assets/home/brand-logo@2x.png'
import searchIcon from '../../assets/home/home-search.png'
import bellIcon from '../../assets/home/home-bell.png'
import cityPawIcon from '../../assets/home/home-city-paw@3x.png'
import './services.css'

// 服务 tab — design source: Figma 110:480（服务，402x1478）。2026-10-30 F-C-02 整页对齐
// （契约 74 §3.1/§3.4/§3.5 + 71 v0.3 §3.4，设计登记表 §4.11/§4.26 偏差口径）：
// - 头部按首页三块胶囊避让对齐模式（logo/城市芯片/搜索+铃铛，与 61:599 同款切图同款定位）；
//   城市手动选择持久化（2026-10-19 裁决），搜索进门店目录、铃铛进消息 tab；
// - 11 类图标网格由 /c/service-categories 字典生成（启用+有可用商家+城市匹配）；D-01 图标
//   素材未到位是已知状态，iconUrl=null 渲染中性底版（#e4f6f3 r12 tile），不伪造图；点击进
//   stores?categoryCode=（F-C-04 已支持精确筛选）；
// - 商家钜惠带图（71 v0.3 imageUrl 投影，无图降级）与优质商家列表（74 §3.4）分区块独立
//   降级；距离/资质标签不做（74 §10）；排序四键不实现（登记偏差④）；
// - 原版式（690:6370）的分类药丸/网格纸纹条/目录入口卡按 110:480 登记移除：110:480 无
//   纸纹条与入口卡，「全部门店」入口由头部搜索承接。
// 匿名可浏览；分类/优质商家读取失败走显式失败态（可重试），不吞错、不伪造数据。
export default function ServicesPage() {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = { '--svc2-unit': `${unit}px`, '--svc2-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  // 头部对齐基准：微信右上角胶囊（与首页头部同一避让口径）。
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
  // 城市芯片（§3.3.4）：目录匿名读，失败静默退回本地选择；选择写本地存储并刷新三路读侧。
  const { scope } = useWorkspace('real')
  const [cities, setCities] = useState<StoreCity[]>([])
  const [city, setCity] = useState<string | undefined>(() => readSelectedCity().cityCode)
  const [cityPickerOpen, setCityPickerOpen] = useState(false)
  // 分类字典（74 §3.1）：本页核心数据；失败/空态显式呈现（可重试）。
  const catalog = useRef(new ServiceCatalogRepository(consumerApi))
  const categorySequence = useRef(0)
  const [categoryPhase, setCategoryPhase] = useState<'loading' | 'ready' | 'load-error'>('loading')
  const [categories, setCategories] = useState<readonly ServiceCategoryView[]>([])
  const featured = useFeaturedStores(city)
  const loadCategories = useCallback(async (nextCity: string | undefined) => {
    setCategoryPhase('loading')
    const current = ++categorySequence.current
    const currentRevision = scope.revision
    try {
      const rows = await runCatalogRead(scope, () => catalog.current.serviceCategories(nextCity))
      if (current !== categorySequence.current || currentRevision !== scope.revision) return
      setCategories(rows); setCategoryPhase('ready')
    } catch {
      if (current === categorySequence.current && currentRevision === scope.revision) setCategoryPhase('load-error')
    }
  }, [scope])
  const loadCities = useCallback(() => {
    consumerApi.anonymousRequest({ method: 'GET', path: '/api/v1/c/cities' }, decodeCityList)
      .then(catalog => { if (catalog.length) setCities(catalog) })
      .catch(() => { /* 目录不可用：芯片回退本地选择/默认名，面板不打开选项 */ })
  }, [])
  useDidShow(() => {
    syncCustomTabBar(1)
    const stored = readSelectedCity().cityCode
    if (stored !== city) setCity(stored)
    void loadCategories(stored)
    featured.reload()
    loadCities()
  })
  function pickCity(next: string | undefined) {
    setCityPickerOpen(false)
    setCity(next)
    writeSelectedCity(next)
    void loadCategories(next)
    featured.reload()
  }
  const cityName = city !== undefined
    ? (cities.find(entry => entry.cityCode === city)?.cityName || city)
    : (cities[0]?.cityName || '成都')
  function openStores() {
    Taro.navigateTo({ url: '/consumer/pages/store-services/stores' }).catch(() => Taro.showToast({ title: '页面打开失败，请重试', icon: 'none' }))
  }
  function openCategory(categoryCode: string) {
    if (categoryPhase !== 'ready') return
    const url = storesCategoryUrl(categoryCode)
    if (!url) return
    Taro.navigateTo({ url }).catch(() => undefined)
  }
  function goMessages() { void switchConsumerTab('messages') }
  return <View className='svc2-page' style={style}>
    <View className='svc2-bg-cream' />
    <View className='svc2-header'>
      <Image className='svc2-logo' style={headerLayout.logo} src={brandLogo} mode='aspectFit' />
      <View className='svc2-city-anchor' style={headerLayout.chip}>
        <Button id='svc2-city' ariaLabel={`当前城市${cityName}，点按切换`} className='svc2-city-chip' hoverClass='none'
          onClick={() => setCityPickerOpen(open => !open)}>
          <Image className='svc2-city-pin' src={cityPawIcon} mode='aspectFit' />
          <Text className='svc2-city-name'>{cityName}</Text>
          <View className='svc2-city-caret' />
        </Button>
        {cityPickerOpen && <CityPicker idPrefix='svc2-city' unit={unit}
          cities={cities} current={city} includeAll={false}
          onPick={(next: string | undefined) => pickCity(next)} />}
      </View>
      <View className='svc2-header-actions' style={headerLayout.actions}>
        <Button id='svc2-search' ariaLabel='找门店与服务' className='svc2-icon-link' hoverClass='none' onClick={openStores}>
          <Image className='svc2-icon-search' src={searchIcon} mode='scaleToFill' />
        </Button>
        <Button id='svc2-bell' ariaLabel='消息中心' className='svc2-icon-link' hoverClass='none' onClick={goMessages}>
          <Image className='svc2-icon-bell' src={bellIcon} mode='scaleToFill' />
        </Button>
      </View>
    </View>
    <ScrollView className='svc2-body' scrollY enhanced showScrollbar={false}
      onScrollToLower={() => featured.loadMore()}>
      <View className='svc2-h2'><Text className='svc2-h2-text'>全部服务</Text></View>
      {categoryPhase !== 'ready' && <View className='svc2-state' role='status'>
        <Text>{categoryPhase === 'loading' ? '正在加载服务分类…' : '服务分类加载失败，请重试。'}</Text>
        {categoryPhase === 'load-error' && <Button id='svc2-cat-retry' className='svc2-state-action' hoverClass='none'
          onClick={() => void loadCategories(city)}>重新加载</Button>}
      </View>}
      {categoryPhase === 'ready' && categories.length === 0 && <View className='svc2-state'>
        <Text>当前城市暂无可展示的服务分类。</Text>
      </View>}
      {categoryPhase === 'ready' && <View className='svc2-grid'>
        {categories.map(category => <Button key={category.categoryCode}
          id={`svc2-category-${category.categoryCode}`} className='svc2-cat' hoverClass='none'
          onClick={() => openCategory(category.categoryCode)}
          ariaLabel={category.categoryName}>
          {/* 分类图标 tile（110:480 DIV-24）：48x48 r12 #e4f6f3 即中性底版；D-01 素材到位后
              iconUrl 渲染 20x20 切图，缺失保持占位，不伪造图标。 */}
          <View className='svc2-cat-tile'>
            {category.iconUrl
              ? <Image className='svc2-cat-icon' src={category.iconUrl} mode='aspectFit' />
              : <View className='svc2-cat-tile-block' />}
          </View>
          <Text className='svc2-cat-name'>{category.categoryName}</Text>
        </Button>)}
      </View>}
      {/* 商家钜惠（带图）：空/失败整块隐藏（契约 71 口径）。 */}
      <DealsRail cityCode={city} />
      {/* 优质商家（74 §3.4）：分页加载；空态/失败态显式呈现。 */}
      <FeaturedStoresSection {...featured} />
      <View className='svc2-tail'><Text>页面数据：真实接口（分类字典/商家钜惠/优质商家匿名可浏览；预约须登录）</Text></View>
      <RootTabBarSpacer />
    </ScrollView>
  </View>
}
