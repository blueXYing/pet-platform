import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { ConsumerPageLayout } from '../../components/page-layout'
import { switchConsumerTab } from '../../components/navigation/switch'
import { SubpageHeader, SubpageHeaderSpacer } from '../../components/navigation/subpage-header'
import { readSelectedCity } from '../../city/model'
import { PreviewStoreRepository, isStoreScenario, type StoreDirectoryDeps, type StoreView } from '../../store/model'
import { RealStoreRepository, runCatalogRead } from '../../store/repository'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealServiceRepository } from '../../api/repositories'
import { PreviewServiceRepository, decodeServicePage, type ServiceItemView, type ServicePage } from '../../service/model'
import type { FeaturedSort } from '../../service-catalog/model'
import { FeaturedStoreCardView, useFeaturedStores } from './featured-stores'
import iconLocation from './assets/690-6750-icon-location@2x.png'
import iconPhone from './assets/690-6766-icon-phone@2x.png'
import './page.css'

// C-003 门店列表（服务tab）— design source: Figma 690:6370（服务-全部服务-分类）。九字段契约
// 之外的事实（评分/月售/距离）既不实现也不伪造；评级/资质标签 V1 不做（74 §10）。
// 2026-10-19 C 端接线裁决：搜索接线（§3.3.2 keyword + §3.3.3 服务搜索）与城市选择（§3.3.4）
// 保留在数据层；本页按 Figma 不展示城市选择器/搜索输入，城市沿用全局已选城市。
// 2026-10-30 F-C-04：sdir-top 旧画布头部退役，统一 SubpageHeader；分类由路由 categoryCode 固定。
// 2026-11-03 用户视觉验收修正（Figma 690:6370）：二级页不展示分类筛选、城市选择、搜索输入；
// 默认视图（无关键词、非 preview）用 cityCode+categoryCode 读 /c/stores/featured 渲染
// 74 §3.4 优质商家卡；关键词仅从路由深链带入（首页内容位），回退 /c/stores + /c/services，
// 本页不再读取 /c/service-categories。
type Phase = 'loading' | 'ready' | 'load-error'
const PAGE_SIZE = 20
const SORT_OPTIONS: readonly { value: FeaturedSort; label: string }[] = [
  { value: 'COMPREHENSIVE', label: '综合排序' },
  { value: 'RATING', label: '评分最高' },
  { value: 'SALES', label: '销量优先' },
]
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
  const [phase, setPhase] = useState<Phase>('loading')
  const [city, setCity] = useState<string | undefined>(() => readSelectedCity().cityCode)
  const [items, setItems] = useState<StoreView[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loadingMore, setLoadingMore] = useState(false)
  const [notice, setNotice] = useState('')
  // 关键词只来自路由深链（首页内容位）；本页不提供搜索输入。
  const keyword = initialKeyword
  // 分类态：只由路由固定（undefined=全部）；本页不提供分类切换控件。
  const categoryCode = initialCategoryCode
  // 排序态：三个真实排序键（CCR-SVC-SORT-001）；距离不在 V1 枚举内。
  const [sort, setSort] = useState<FeaturedSort>('COMPREHENSIVE')
  const [serviceHits, setServiceHits] = useState<ServiceItemView[] | null>(null)
  const [serviceTotal, setServiceTotal] = useState(0)
  const sequence = useRef(0)
  // 默认视图（未搜索、非 preview）复用服务 tab 的 74 §3.4 优质商家聚合卡；
  // 分类由路由固定，随 city/categoryCode 重载。preview 通道仍走契约目录 Mock。
  const featured = useFeaturedStores(city, categoryCode, sort, !preview)
  // 返回本页（如从门店详情/服务详情返回）时复用已加载的数据；仅城市变化、搜索态首次加载
  // 或上一轮失败时重读，避免 useDidShow 把 phase 重置为 loading 造成短时空白。
  const cityRef = useRef(city)
  cityRef.current = city
  const phaseRef = useRef(phase)
  phaseRef.current = phase
  const sortRef = useRef(sort)
  sortRef.current = sort
  const featuredRef = useRef(featured)
  featuredRef.current = featured
  const [platformInfo, setPlatformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = {
    '--svc-status-top': `${platformInfo.statusBarHeight || 0}px`,
    '--svc-unit': `${unit}px`,
  } as CSSProperties
  useEffect(() => {
    const handler = () => setPlatformInfo(Taro.getWindowInfo())
    Taro.onWindowResize(handler)
    return () => Taro.offWindowResize(handler)
  }, [])

  const load = useCallback(async (nextCity: string | undefined, nextKeyword: string, nextCategoryCode: string | undefined) => {
    setPhase('loading'); setNotice('')
    const current = ++sequence.current
    const trimmed = nextKeyword.trim()
    if (!preview && trimmed === '') {
      // 默认视图由 featured 聚合读承载（城市/分类变化时另行 reload）；这里只清空搜索态。
      setItems([]); setTotal(0); setPage(1); setServiceHits(null); setServiceTotal(0); setPhase('ready')
      return
    }
    const currentRevision = scope.revision
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
  }, [preview, repository, scope])
  useDidShow(() => {
    // 城市选择持久化于本地存储（首页/服务 tab 共用）：本页只读取，不提供切换控件。
    const stored = readSelectedCity().cityCode
    const next = stored
    const cityChanged = next !== cityRef.current
    if (cityChanged) setCity(next)
    const searchActive = keyword.trim() !== ''
    const keepDirectory = !cityChanged && phaseRef.current === 'ready' && (searchActive || preview)
    const needsDirectory = cityChanged || searchActive || preview
    if (needsDirectory && !keepDirectory) {
      void load(next, keyword, categoryCode)
    }
    const keepFeatured = !cityChanged && featuredRef.current.phase === 'ready'
    if (!keepFeatured) featuredRef.current.reload(next, categoryCode, sortRef.current)
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
  function pickSort(next: FeaturedSort) {
    if (next === sort) return
    setSort(next)
    featured.reload(city, categoryCode, next)
  }
  function openAllStores() {
    Taro.redirectTo({ url: `/consumer/pages/store-services/stores?preview=${preview ? '1' : '0'}` })
      .catch(() => setNotice('页面跳转失败，请重试'))
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
  // 默认视图（未搜索、非 preview）走 74 §3.4 优质商家聚合卡；搜索/preview 保留九字段目录。
  const richMode = !preview && !searching
  const richReady = featured.phase === 'ready'
  const noResults = ready && searching && items.length === 0 && (serviceHits === null || serviceHits.length === 0)
  return <ConsumerPageLayout page='storeDirectory' unit={unit} navigation={preview ? { idPrefix: 'sdir', disabled: !ready, onSelect: key => void switchConsumerTab(key), referencePlacement: undefined } : undefined} className='svc-page sdir-page' style={style}>
    {/* 二级页统一头部（2026-10-28 用户裁决标题左对齐）。2026-11-03 用户视觉验收修正：
        Figma 690:6370 的本页没有城市选择/搜索输入，城市沿用全局已选城市；本页只保留
        返回 + 标题（分类由服务 tab 路由参数固定）。 */}
    <SubpageHeader title={searching ? '搜索结果' : '全部服务'} backId='sdir-back' height={44} />
    <SubpageHeaderSpacer height={44} />
    <View className='sdir-body'>
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
      {richMode
        ? <View className='sdir-sortbar' role='tablist' ariaLabel='门店排序'>
            {SORT_OPTIONS.map(option => <Button key={option.value}
              id={`sdir-sort-${option.value.toLowerCase()}`}
              className={`sdir-sort-pill${sort === option.value ? ' is-active' : ''}`}
              hoverClass='none' ariaLabel={option.label} onClick={() => pickSort(option.value)}>
              {option.label}
            </Button>)}
          </View>
        : <Text className='sdir-heading'>{searching ? `门店${total > 0 ? `（${total}）` : ''}` : '门店'}</Text>}
      {richMode && !richReady && <View className='sdir-state' role='status'>
        <Text>{featured.phase === 'loading' ? '正在加载门店…' : '加载失败，请重试。'}</Text>
        {featured.phase === 'load-error' && <Button id='sdir-retry' className='sdir-state-action' onClick={() => featured.reload(city, categoryCode, sort)}>重新加载</Button>}
      </View>}
      {richMode && richReady && featured.cards.length === 0 && categoryCode !== undefined && <View className='sdir-state'>
        <Text>该分类下暂无可展示的门店。</Text>
        <Button id='sdir-empty-all' className='sdir-state-action' onClick={() => openAllStores()}>查看全部门店</Button>
      </View>}
      {richMode && richReady && featured.cards.length === 0 && categoryCode === undefined && <View className='sdir-state'>
        <Text>当前城市暂无可展示的门店。</Text>
      </View>}
      {richMode && richReady && featured.cards.length > 0 && <View className='sdir-rich-list'>
        {featured.cards.map((card, index) => <FeaturedStoreCardView key={card.storeId} card={card} index={index} preview={preview} />)}
      </View>}
      {richMode && richReady && !featured.appendError && featured.cards.length < featured.total && (
        <Button id='sdir-load-more' className='sdir-more' disabled={featured.appending} onClick={() => featured.loadMore()}>
          {featured.appending ? '正在加载…' : '加载更多'}
        </Button>
      )}
      {richMode && richReady && featured.appendError && <View className='sdir-state'>
        <Text>加载更多失败，请重试。</Text>
        <Button id='sdir-more-retry' className='sdir-state-action' onClick={() => featured.retryAppend()}>重试</Button>
      </View>}
      {!richMode && !ready && <View className='sdir-state' role='status'>
        <Text>{phase === 'loading' ? '正在加载门店…' : '加载失败，请重试。'}</Text>
        {phase === 'load-error' && <Button id='sdir-retry' className='sdir-state-action' onClick={() => void load(city, keyword, categoryCode)}>重新加载</Button>}
      </View>}
      {!richMode && ready && !searching && categoryCode === undefined && items.length === 0 && <View className='sdir-state'><Text>当前城市暂无可展示的门店。</Text></View>}
      {/* 分类筛选无供给（74 §3.2 合法 code + 200 空页）：明示全部门店入口，不静默回到全量。 */}
      {!richMode && ready && !searching && categoryCode !== undefined && items.length === 0 && <View className='sdir-state'>
        <Text>该分类下暂无可展示的门店。</Text>
        <Button id='sdir-empty-all' className='sdir-state-action' onClick={() => openAllStores()}>查看全部门店</Button>
      </View>}
      {!richMode && ready && searching && items.length === 0 && serviceHits !== null && serviceHits.length > 0
        && <View className='sdir-state'><Text>没有匹配的门店。</Text></View>}
      {noResults && <View className='sdir-state' id='sdir-empty'>
        <Text>未找到与「{keyword.trim()}」相关的门店或服务。</Text>
      </View>}
      {!richMode && ready && items.map(store => <Button key={store.storeId} id={`sdir-store-${store.storeId}`} className='sdir-card' onClick={() => openStore(store)}
        ariaLabel={`${store.storeName}，${store.address}`}>
        <Text className='sdir-store-name'>{store.storeName}</Text>
        <Text className='sdir-store-merchant'>{store.merchantName}</Text>
        <View className='sdir-store-line'><Image src={iconLocation} mode='scaleToFill' /><Text>{store.address}</Text></View>
        {store.phoneMasked && <View className='sdir-store-line'><Image src={iconPhone} mode='scaleToFill' /><Text>{store.phoneMasked}</Text></View>}
      </Button>)}
      {!richMode && ready && items.length < total && <Button id='sdir-load-more' className='sdir-more' disabled={loadingMore} onClick={() => void loadMore()}>
        {loadingMore ? '正在加载…' : '加载更多'}
      </Button>}
      {notice && <Text id='sdir-notice' className='sdir-notice'>{notice}</Text>}
      <View className='sdir-tail'><Text>页面数据：{preview ? '契约 Mock（preview=1，不联调）' : '真实接口（门店/城市/优质商家匿名可浏览）'}</Text></View>
    </View>
  </ConsumerPageLayout>
}
