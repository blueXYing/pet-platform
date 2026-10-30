import { Button, Image, Input, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { ConsumerPageLayout } from '../../components/page-layout'
import { switchConsumerTab } from '../../components/navigation/switch'
import { SubpageHeader, SubpageHeaderSpacer } from '../../components/navigation/subpage-header'
import CityPicker from '../../city/picker'
import { readSelectedCity, writeSelectedCity } from '../../city/model'
import { PreviewStoreRepository, isStoreScenario, type StoreCity, type StoreDirectoryDeps, type StoreView } from '../../store/model'
import { RealStoreRepository, runCatalogRead } from '../../store/repository'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealServiceRepository } from '../../api/repositories'
import { PreviewServiceCatalogRepository, ServiceCatalogRepository, type ServiceCategoryView } from '../../service-catalog/model'
import { PreviewServiceRepository, decodeServicePage, type ServiceItemView, type ServicePage } from '../../service/model'
import iconLocation from './assets/690-6750-icon-location@2x.png'
import iconPhone from './assets/690-6766-icon-phone@2x.png'
import './page.css'

// C-003 门店列表（服务tab）— design source: user frame 110:480（服务, 402x1478）。九字段契约
// 之外的事实（评分/月售/距离）既不实现也不伪造；评级/资质标签 V1 不做（74 §10）。
// 2026-10-19 C 端接线裁决：搜索接线（§3.3.2 keyword + §3.3.3 服务搜索）与城市选择（§3.3.4
// /c/cities + 76:761 CityPicker 面板），见下方 load/pickCity。
// 2026-10-30 F-C-04（契约 74 §3.1/§3.2/§3.3）：sdir-top 旧画布头部退役，统一 SubpageHeader
// （标题行 + 底部工具行=城市芯片+搜索件）；分类 chips 数据来自 GET /c/service-categories
// （不再硬编码 11 类名称数组）；点分类用 categoryCode 精确筛选 /c/stores（与 city/keyword 按
// AND 组合，未知/停用 400 → 失败态）；服务搜索同步携带 categoryCode（§3.3 AND 组合）。分类
// 图标素材未到位是已知状态（G-03/D-01）：iconUrl 缺失渲染占位块，不伪造图标。
type Phase = 'loading' | 'ready' | 'load-error'
type CategoriesPhase = 'loading' | 'ready' | 'error'
const PAGE_SIZE = 20
// 74 §2.1 categoryCode 词法；路由带入的非法词法参数直接忽略（避免必失败的请求）。
const isCategoryCode = (value: string | undefined): value is string =>
  value !== undefined && /^[A-Z][A-Z0-9_]{1,31}(?![\s\S])/.test(value)

export default function StoreDirectoryPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isStoreScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const initialKeyword = route.params.keyword ?? ''
  const initialCategoryCode = isCategoryCode(route.params.categoryCode) ? route.params.categoryCode : undefined
  const { scope, revision } = useWorkspace(preview ? 'preview' : 'real')
  const [repository] = useState<StoreDirectoryDeps>(() => preview
    ? new PreviewStoreRepository(undefined, scenario)
    : new RealStoreRepository(consumerApi))
  const services = useRef(preview ? new PreviewServiceRepository() : new RealServiceRepository(consumerApi))
  const catalog = useRef(preview ? new PreviewServiceCatalogRepository() : new ServiceCatalogRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>('loading')
  const [cities, setCities] = useState<StoreCity[]>([])
  const [city, setCity] = useState<string | undefined>(undefined)
  const [items, setItems] = useState<StoreView[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loadingMore, setLoadingMore] = useState(false)
  const [notice, setNotice] = useState('')
  // 搜索态：draft 为输入框内容，keyword 为已提交关键词（空串=非搜索态，恢复普通目录）。
  const [draft, setDraft] = useState(initialKeyword)
  const [keyword, setKeyword] = useState(initialKeyword)
  // 分类态：categoryCode 为已提交筛选（undefined=全部）；chips 字典来自 /c/service-categories。
  const [categoryCode, setCategoryCode] = useState<string | undefined>(initialCategoryCode)
  const [categories, setCategories] = useState<readonly ServiceCategoryView[]>([])
  const [categoriesPhase, setCategoriesPhase] = useState<CategoriesPhase>('loading')
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

  const load = useCallback(async (nextCity: string | undefined, nextKeyword: string, nextCategoryCode: string | undefined) => {
    setPhase('loading'); setNotice('')
    const current = ++sequence.current
    const currentRevision = scope.revision
    const trimmed = nextKeyword.trim()
    try {
      // Anonymous-browsable per STR-D8: with no session the catalog read runs bare.
      // 74 §3.2：categoryCode 精确筛选；未知/停用 400 走下方失败态（不吞错、不伪造空目录）。
      const result = await runCatalogRead(scope, () => repository.list({
        city: nextCity,
        keyword: trimmed === '' ? undefined : trimmed,
        categoryCode: nextCategoryCode,
        page: 1,
        pageSize: PAGE_SIZE,
      }))
      if (current !== sequence.current || currentRevision !== scope.revision) return
      setItems(result.items); setTotal(result.total); setPage(1); setPhase('ready')
      if (trimmed === '') { setServiceHits(null); setServiceTotal(0); return }
      try {
        const servicesPage: ServicePage = await runCatalogRead(scope, () => services.current!.search(trimmed, 1, PAGE_SIZE, nextCategoryCode))
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
  const loadCategories = useCallback(async (nextCity: string | undefined) => {
    // 分类字典失败只降级 chips 行（可重试），不拖垮门店目录（74 §3.1：503 整块隐藏）。
    setCategoriesPhase('loading')
    try {
      const rows = await runCatalogRead(scope, () => catalog.current!.serviceCategories(nextCity))
      setCategories(rows); setCategoriesPhase('ready')
    } catch { setCategoriesPhase('error') }
  }, [catalog, scope])
  useDidShow(() => {
    // 城市选择持久化于本地存储（首页城市芯片与面板共用）：每次展示重读，切换即时生效。
    const stored = readSelectedCity().cityCode
    const next = stored !== undefined ? stored : city
    if (next !== city) setCity(next)
    void load(next !== undefined ? next : city, keyword, categoryCode)
    void loadCategories(next !== undefined ? next : city)
    // The open-city catalog (server-side §3.3.4 anonymous directory; 入驻目录作回退注入已移除).
    void repository.cities().then(catalog2 => { if (catalog2.length) setCities(catalog2) }).catch(() => setNotice(''))
  })
  async function loadMore() {
    if (loadingMore || items.length >= total) return
    setLoadingMore(true)
    const current = sequence.current
    const currentRevision = scope.revision
    const trimmed = keyword.trim()
    try {
      const result = await runCatalogRead(scope, () => repository.list({
        city, keyword: trimmed === '' ? undefined : trimmed, categoryCode, page: page + 1, pageSize: PAGE_SIZE,
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
    void load(next, keyword, categoryCode)
    void loadCategories(next)
  }
  function submitSearch() {
    const trimmed = draft.trim()
    setKeyword(trimmed)
    void load(city, trimmed, categoryCode)
  }
  function clearSearch() {
    // 「清空条件」= 清关键词 + 清分类筛选（PRD 5.1.2 无结果清空入口语义）。
    setDraft(''); setKeyword(''); setCategoryCode(undefined)
    void load(city, '', undefined)
  }
  function pickCategory(next: string | undefined) {
    if (next === categoryCode) return
    setCategoryCode(next)
    void load(city, keyword, next)
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

  const ready = phase === 'ready'
  const searching = keyword.trim() !== ''
  const catalogCities = cities
  // 未选择 = 全部开放城市（省略 city 参数；面板 ALL 行高亮）。芯片默认显示目录首位城市名以贴
  // 设计稿（76:761 头部「成都」）；仅在未选择且目录非空时成立。
  const currentCityName = city === undefined
    ? (catalogCities[0]?.cityName || '全部开放城市')
    : (catalogCities.find(entry => entry.cityCode === city)?.cityName || city)
  const noResults = ready && searching && items.length === 0 && (serviceHits === null || serviceHits.length === 0)
  // chips：字典行 + 「全部」；路由带入/城市切换后不在字典里的选中 code 仍要可清除
  // （campaign 页 activeTag 同款处理），标签用字典名，缺字典名时展示 code 本身。
  const categoryChips: readonly { code?: string; name: string; iconUrl: string | null }[] = [
    { name: '全部', iconUrl: null },
    ...categories.map(category => ({ code: category.categoryCode, name: category.categoryName, iconUrl: category.iconUrl })),
    ...(categoryCode !== undefined && !categories.some(category => category.categoryCode === categoryCode)
      ? [{ code: categoryCode, name: categoryCode, iconUrl: null }]
      : []),
  ]
  const showCategories = categoriesPhase !== 'loading' && (categories.length > 0 || categoryCode !== undefined)
  return <ConsumerPageLayout page='storeDirectory' unit={unit} navigation={preview ? { idPrefix: 'sdir', disabled: !ready, onSelect: key => void switchConsumerTab(key), referencePlacement: undefined } : undefined} className='svc-page sdir-page' style={style}>
    {/* 二级页统一头部（2026-10-28 用户裁决标题左对齐）；底部工具行承载城市芯片+搜索件，
        分类 chips 在正文首行。110:480 原稿为 tab 页头部（logo/铃铛），此处为登记偏差
        （C-04 设计登记表：stores 页统一 SubpageHeader）。 */}
    <SubpageHeader title={searching ? '搜索结果' : '全部服务'} backId='sdir-back' height={113}
      bottom={<View className='sdir-toolbar'>
        <View className='sdir-city-anchor'>
          <Button id='sdir-city' className='sdir-city' onClick={() => setCityPickerOpen(open => !open)}>
            <Text>{currentCityName}</Text>
          </Button>
          {cityPickerOpen && <CityPicker idPrefix='sdir-city' unit={unit}
            cities={catalogCities} current={city} includeAll
            onPick={(next: string | undefined) => pickCity(next)} />}
        </View>
        {/* 搜索件（77:1267 词汇「搜索服务、商家...」）：提交后门店+服务两路搜索；PRD 5.1.2
            「搜索无结果时展示清空条件入口」由 sdir-empty 承载。 */}
        <Input id='sdir-search-input' className='sdir-search-input' type='text'
          value={draft} placeholder='搜索服务、商家...' placeholderClass='sdir-search-placeholder'
          confirmType='search' onInput={event => setDraft(event.detail.value)}
          onConfirm={() => submitSearch()} />
        <Button id='sdir-search-submit' className='sdir-search-button' onClick={() => submitSearch()}>搜索</Button>
        {(searching || categoryCode !== undefined) && <Button id='sdir-search-clear' className='sdir-search-clear' onClick={() => clearSearch()}>清空条件</Button>}
      </View>} />
    <SubpageHeaderSpacer height={113} />
    <View className='sdir-body'>
      {categoriesPhase === 'error' && <View className='sdir-state sdir-categories-state' role='status'>
        <Text>分类加载失败，可继续浏览全部门店。</Text>
        <Button id='sdir-categories-retry' className='sdir-state-action' onClick={() => void loadCategories(city)}>重新加载</Button>
      </View>}
      {showCategories && <View className='sdir-categories'>
        {categoryChips.map(chip => <Button key={chip.code ?? 'sdir-category-all'}
          className={`sdir-category${chip.code === categoryCode ? ' is-active' : ''}`}
          onClick={() => pickCategory(chip.code)}>
          {chip.iconUrl
            ? <Image className='sdir-category-icon' src={chip.iconUrl} mode='aspectFit' />
            : chip.code !== undefined && <View className='sdir-category-icon sdir-category-icon-placeholder' />}
          <Text>{chip.name}</Text>
        </Button>)}
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
        {phase === 'load-error' && <Button id='sdir-retry' className='sdir-state-action' onClick={() => void load(city, keyword, categoryCode)}>重新加载</Button>}
      </View>}
      {ready && !searching && categoryCode === undefined && items.length === 0 && <View className='sdir-state'><Text>当前城市暂无可展示的门店。</Text></View>}
      {/* 分类筛选无供给（74 §3.2 合法 code + 200 空页）：明示清空入口，不静默回到全量。 */}
      {ready && !searching && categoryCode !== undefined && items.length === 0 && <View className='sdir-state'>
        <Text>该分类下暂无可展示的门店。</Text>
        <Button id='sdir-empty-clear' className='sdir-state-action' onClick={() => clearSearch()}>清空条件</Button>
      </View>}
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
      <View className='sdir-tail'><Text>页面数据：{preview ? '契约 Mock（preview=1，不联调）' : '真实接口（门店/城市/服务分类/服务目录匿名可浏览）'}</Text></View>
    </View>
  </ConsumerPageLayout>
}
