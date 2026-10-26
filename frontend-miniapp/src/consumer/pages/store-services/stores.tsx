import { Button, Image, Input, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { ConsumerPageLayout } from '../../components/page-layout'
import { switchConsumerTab } from '../../components/navigation/switch'
import CityPicker from '../../city/picker'
import { readSelectedCity, writeSelectedCity } from '../../city/model'
import { PreviewStoreRepository, directoryCategories, isStoreScenario, type StoreCity, type StoreDirectoryDeps, type StoreView } from '../../store/model'
import { RealStoreRepository, runCatalogRead } from '../../store/repository'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealServiceRepository } from '../../api/repositories'
import { PreviewServiceRepository, decodeServicePage, type ServiceItemView, type ServicePage } from '../../service/model'
import iconLocation from './assets/690-6750-icon-location@2x.png'
import iconPhone from './assets/690-6766-icon-phone@2x.png'
import './page.css'

// C-003 门店列表（服务tab）— design source: user frame 110:480（服务, 402x1478）with the V1
// cuts registered in C-003-design-inputs/INVENTORY.md: only the nine frozen /c/stores fields
// are bound; rating / monthly-sold / distance / hot services / promo strip have no contract
// facts and are neither implemented nor fabricated; category filtering (STR-D7) stays unwired.
// 2026-10-19 C 端接线裁决（审计 C1 #4/#5 回补）：
// ① 搜索接线——门店目录页承载首页搜索（设计稿 77:1267「搜索服务、商家...」展开态；工程落目录页
//    搜索件，登记偏差）：门店按 §3.3.2 keyword（storeName/merchantName 子串），服务按 §3.3.3
//    （serviceName/categoryName）；服务搜索无城市维度（service_item 无城市事实，PR 登记偏差），
//    门店结果按城市收窄；无结果时展示 PRD 5.1.2 要求的「清空条件」入口。
// ② 城市选择——目录来源 §3.3.4 /c/cities（匿名），选择持久化本地存储并即时刷新；面板形态沿
//    76:761（CityPicker 组件，与首页城市芯片共用）。
type Phase = 'loading' | 'ready' | 'load-error'
const PAGE_SIZE = 20

export default function StoreDirectoryPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isStoreScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope, revision } = useWorkspace(preview ? 'preview' : 'real')
  const [repository] = useState<StoreDirectoryDeps>(() => preview
    ? new PreviewStoreRepository(undefined, scenario)
    : new RealStoreRepository(consumerApi))
  const services = useRef(preview ? new PreviewServiceRepository() : new RealServiceRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>('loading')
  const [cities, setCities] = useState<StoreCity[]>([])
  const [city, setCity] = useState<string | undefined>(undefined)
  const [items, setItems] = useState<StoreView[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loadingMore, setLoadingMore] = useState(false)
  const [notice, setNotice] = useState('')
  // 搜索态：draft 为输入框内容，keyword 为已提交关键词（空串=非搜索态，恢复普通目录）。
  const [draft, setDraft] = useState('')
  const [keyword, setKeyword] = useState('')
  const [serviceHits, setServiceHits] = useState<ServiceItemView[] | null>(null)
  const [serviceTotal, setServiceTotal] = useState(0)
  const [cityPickerOpen, setCityPickerOpen] = useState(false)
  const sequence = useRef(0)
  const [platformInfo, setPlatformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--svc-status-top': `${platformInfo.statusBarHeight || 0}px`, '--svc-unit': `${unit}px` } as CSSProperties
  useEffect(() => {
    const handler = () => setPlatformInfo(Taro.getWindowInfo())
    Taro.onWindowResize(handler)
    return () => Taro.offWindowResize(handler)
  }, [])

  const load = useCallback(async (nextCity: string | undefined, nextKeyword: string) => {
    setPhase('loading'); setNotice('')
    const current = ++sequence.current
    const currentRevision = scope.revision
    const trimmed = nextKeyword.trim()
    try {
      // Anonymous-browsable per STR-D8: with no session the catalog read runs bare.
      const result = await runCatalogRead(scope, () => repository.list({
        city: nextCity,
        keyword: trimmed === '' ? undefined : trimmed,
        page: 1,
        pageSize: PAGE_SIZE,
      }))
      if (current !== sequence.current || currentRevision !== scope.revision) return
      setItems(result.items); setTotal(result.total); setPage(1); setPhase('ready')
      if (trimmed === '') { setServiceHits(null); setServiceTotal(0); return }
      try {
        const servicesPage: ServicePage = await runCatalogRead(scope, () => services.current!.search(trimmed, 1, PAGE_SIZE))
        if (current !== sequence.current || currentRevision !== scope.revision) return
        setServiceHits(servicesPage.items); setServiceTotal(servicesPage.total)
      } catch {
        // 服务搜索失败不拖垮门店列表（两读侧独立失败关闭），提示后仅展示门店结果。
        if (current !== sequence.current || currentRevision !== scope.revision) return
        setServiceHits(null); setServiceTotal(0)
        setNotice('服务搜索暂不可用，已展示门店结果。')
      }
    } catch {
      if (current === sequence.current && currentRevision !== scope.revision) return
      if (current === sequence.current) { setItems([]); setTotal(0); setServiceHits(null); setServiceTotal(0); setPhase('load-error') }
    }
  }, [repository, scope])
  useDidShow(() => {
    // 城市选择持久化于本地存储（首页城市芯片与面板共用）：每次展示重读，切换即时生效。
    const stored = readSelectedCity().cityCode
    const next = stored !== undefined ? stored : city
    if (next !== city) setCity(next)
    void load(next !== undefined ? next : city, keyword)
    // The open-city catalog (server-side §3.3.4 anonymous directory; 入驻目录作回退注入已移除).
    void repository.cities().then(catalog => { if (catalog.length) setCities(catalog) }).catch(() => setNotice(''))
  })
  async function loadMore() {
    if (loadingMore || items.length >= total) return
    setLoadingMore(true)
    const current = sequence.current
    const currentRevision = scope.revision
    const trimmed = keyword.trim()
    try {
      const result = await runCatalogRead(scope, () => repository.list({
        city, keyword: trimmed === '' ? undefined : trimmed, page: page + 1, pageSize: PAGE_SIZE,
      }))
      if (current !== sequence.current || currentRevision !== scope.revision) return
      const known = new Set(items.map(item => item.storeId))
      setItems([...items, ...result.items.filter(item => !known.has(item.storeId))])
      setPage(result.page); setTotal(result.total)
    } catch { setNotice('加载更多失败，请重试。') } finally { setLoadingMore(false) }
  }
  function pickCity(next: string | undefined) {
    setCityPickerOpen(false)
    if (next === city) return
    setCity(next)
    writeSelectedCity(next)
    void load(next, keyword)
  }
  function submitSearch() {
    const trimmed = draft.trim()
    setKeyword(trimmed)
    void load(city, trimmed)
  }
  function clearSearch() {
    setDraft(''); setKeyword('')
    void load(city, '')
  }
  function openStore(store: StoreView) {
    if (phase !== 'ready') return
    Taro.navigateTo({ url: `/consumer/pages/store-services/index?preview=${preview ? '1' : '0'}&storeId=${encodeURIComponent(store.storeId)}` })
      .catch(() => setNotice('页面跳转失败，请重试'))
  }
  function openService(service: ServiceItemView) {
    Taro.navigateTo({ url: `/consumer/pages/store-services/service-detail?preview=${preview ? '1' : '0'}&serviceId=${encodeURIComponent(service.serviceId)}` })
      .catch(() => setNotice('页面跳转失败，请重试'))
  }
  function categoryNotWired(name: string) {
    setNotice(preview ? `“${name}”分类筛选未接入本次预览（依赖服务分类筛选契约）` : `“${name}”分类筛选将在后续版本提供`)
  }

  const ready = phase === 'ready'
  const searching = keyword.trim() !== ''
  const catalogCities = cities
  // 未选择 = 全部开放城市（省略 city 参数；面板 ALL 行高亮）。芯片默认显示目录首位城市名以贴
  // 设计稿（76:761 头部「成都」）；仅在未选择且目录非空时成立。
  const currentCityName = city === undefined
    ? (catalogCities[0]?.cityName || '全部开放城市')
    : (catalogCities.find(entry => entry.cityCode === city)?.cityName || city)
  const noResults = ready && searching && items.length === 0 && (serviceHits === null || serviceHits.length === 0)
  return <ConsumerPageLayout page='storeDirectory' unit={unit} navigation={preview ? { idPrefix: 'sdir', disabled: !ready, onSelect: key => void switchConsumerTab(key), referencePlacement: undefined } : undefined} className='svc-page sdir-page' style={style}>
    <View className='svc-status-area' />
    <View className='sdir-top'>
      <View className='svc-abs svc-bg-blue' />
      <View className='svc-abs svc-bg-cream' />
      <View className='sdir-city-anchor'>
        <Button id='sdir-city' className='sdir-city' onClick={() => setCityPickerOpen(open => !open)}>
          <Text>{currentCityName}</Text>
        </Button>
        {cityPickerOpen && <CityPicker idPrefix='sdir-city' unit={unit}
          cities={catalogCities} current={city} includeAll
          onPick={(next: string | undefined) => pickCity(next)} />}
      </View>
      <Text className='sdir-title'>{searching ? '搜索结果' : '全部服务'}</Text>
    </View>
    <View className='sdir-body'>
      {/* 搜索件（77:1267 词汇「搜索服务、商家...」）：提交后门店+服务两路搜索；PRD 5.1.2
          「搜索无结果时展示清空条件入口」由 sdir-empty 承载。 */}
      <View className='sdir-search'>
        <Input id='sdir-search-input' className='sdir-search-input' type='text'
          value={draft} placeholder='搜索服务、商家...' placeholderClass='sdir-search-placeholder'
          confirmType='search' onInput={event => setDraft(event.detail.value)}
          onConfirm={() => submitSearch()} />
        <Button id='sdir-search-submit' className='sdir-search-button' onClick={() => submitSearch()}>搜索</Button>
        {searching && <Button id='sdir-search-clear' className='sdir-search-clear' onClick={() => clearSearch()}>清空条件</Button>}
      </View>
      {!searching && <View className='sdir-categories'>
        {directoryCategories.map(name => <Button key={name} className='sdir-category' onClick={() => categoryNotWired(name)}><Text>{name}</Text></Button>)}
      </View>}
      {searching && serviceHits !== null && <View className='sdir-services'>
        <Text className='sdir-heading'>服务{serviceTotal > 0 ? `（${serviceTotal}）` : ''}</Text>
        {serviceHits.length === 0 && <View className='sdir-state'><Text>没有匹配的服务。</Text></View>}
        {serviceHits.map(service => <Button key={service.serviceId} id={`sdir-service-${service.serviceId}`}
          className='sdir-card' onClick={() => openService(service)}
          ariaLabel={`${service.serviceName}，价格${service.salePrice}元`}>
          <Text className='sdir-store-name'>{service.serviceName}</Text>
          <Text className='sdir-store-merchant'>{service.categoryName}</Text>
          <View className='sdir-store-line'><Text>¥{service.salePrice}</Text><Text>{service.durationMinutes}分钟</Text></View>
        </Button>)}
      </View>}
      <Text className='sdir-heading'>{searching ? `门店${total > 0 ? `（${total}）` : ''}` : '门店'}</Text>
      {!ready && <View className='sdir-state' role='status'>
        <Text>{phase === 'loading' ? '正在加载门店…' : '加载失败，请重试。'}</Text>
        {phase === 'load-error' && <Button id='sdir-retry' className='sdir-state-action' onClick={() => void load(city, keyword)}>重新加载</Button>}
      </View>}
      {ready && !searching && items.length === 0 && <View className='sdir-state'><Text>当前城市暂无可展示的门店。</Text></View>}
      {ready && searching && items.length === 0 && serviceHits !== null && serviceHits.length > 0
        && <View className='sdir-state'><Text>没有匹配的门店。</Text></View>}
      {noResults && <View className='sdir-state' id='sdir-empty'>
        <Text>未找到与「{keyword.trim()}」相关的门店或服务。</Text>
        <Button id='sdir-empty-clear' className='sdir-state-action' onClick={() => clearSearch()}>清空条件</Button>
      </View>}
      {ready && items.map(store => <Button key={store.storeId} id={`sdir-store-${store.storeId}`} className='sdir-card' onClick={() => openStore(store)}
        ariaLabel={`${store.storeName}，${store.address}`}>
        <Text className='sdir-store-name'>{store.storeName}</Text>
        <Text className='sdir-store-merchant'>{store.merchantName}</Text>
        <View className='sdir-store-line'><Image src={iconLocation} mode='scaleToFill' /><Text>{store.address}</Text></View>
        {store.phoneMasked && <View className='sdir-store-line'><Image src={iconPhone} mode='scaleToFill' /><Text>{store.phoneMasked}</Text></View>}
      </Button>)}
      {ready && items.length < total && <Button id='sdir-load-more' className='sdir-more' disabled={loadingMore} onClick={() => void loadMore()}>
        {loadingMore ? '正在加载…' : '加载更多'}
      </Button>}
      {notice && <Text id='sdir-notice' className='sdir-notice'>{notice}</Text>}
      <View className='sdir-tail'><Text>页面数据：{preview ? '契约 Mock（preview=1，不联调）' : '真实接口（门店/城市/服务目录匿名可浏览）'}</Text></View>
    </View>
  </ConsumerPageLayout>
}
