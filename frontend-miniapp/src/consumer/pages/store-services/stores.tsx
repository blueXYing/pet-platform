import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { ConsumerPageLayout } from '../../components/page-layout'
import { navigationUnavailableMessage } from '../../components/navigation/model'
import { PreviewStoreRepository, directoryCategories, isStoreScenario, type StoreCity, type StoreDirectoryDeps, type StoreView } from '../../store/model'
import { RealStoreRepository } from '../../store/repository'
import { consumerApi } from '../../../shared/consumer-runtime'
import { MerchantApplicationRepository } from '../../../shared/merchant-repositories'
import iconLocation from './assets/690-6750-icon-location@2x.png'
import iconPhone from './assets/690-6766-icon-phone@2x.png'
import './page.css'

// C-003 门店列表（服务tab）— design source: user frame 110:480（服务, 402x1478）with the V1
// cuts registered in C-003-design-inputs/INVENTORY.md: only the nine frozen /c/stores fields
// are bound; rating / monthly-sold / distance / hot services / promo strip have no contract
// facts and are neither implemented nor fabricated; category filtering (STR-D7) and keyword
// search stay unwired. Store cards tap into the 商家详情页 (store-services index).
type Phase = 'loading' | 'ready' | 'load-error'
const PAGE_SIZE = 20

export default function StoreDirectoryPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isStoreScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope, revision } = useWorkspace(preview ? 'preview' : 'real')
  const [repository] = useState<StoreDirectoryDeps>(() => preview
    ? new PreviewStoreRepository(undefined, scenario)
    : new RealStoreRepository(consumerApi, () => new MerchantApplicationRepository(consumerApi).cities()))
  const [phase, setPhase] = useState<Phase>('loading')
  const [cities, setCities] = useState<StoreCity[]>([])
  const [city, setCity] = useState<string | undefined>(undefined)
  const [items, setItems] = useState<StoreView[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loadingMore, setLoadingMore] = useState(false)
  const [notice, setNotice] = useState('')
  const sequence = useRef(0)
  const [platformInfo, setPlatformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--svc-status-top': `${platformInfo.statusBarHeight || 0}px`, '--svc-unit': `${unit}px` } as CSSProperties
  useEffect(() => {
    const handler = () => setPlatformInfo(Taro.getWindowInfo())
    Taro.onWindowResize(handler)
    return () => Taro.offWindowResize(handler)
  }, [])

  const load = useCallback(async (nextCity: string | undefined) => {
    setPhase('loading'); setNotice('')
    const current = ++sequence.current
    const currentRevision = scope.revision
    try {
      const result = await scope.run(undefined, () => repository.list({ city: nextCity, page: 1, pageSize: PAGE_SIZE }))
      if (current !== sequence.current || currentRevision !== scope.revision) return
      setItems(result.items); setTotal(result.total); setPage(1); setPhase('ready')
    } catch {
      if (current === sequence.current && currentRevision === scope.revision) { setItems([]); setTotal(0); setPhase('load-error') }
    }
  }, [repository, scope])
  useDidShow(() => {
    void load(city)
    // The open-city catalog (server-side directory; default display name comes from it).
    void repository.cities().then(catalog => { if (catalog.length) setCities(catalog) }).catch(() => setNotice(''))
  })
  async function loadMore() {
    if (loadingMore || items.length >= total) return
    setLoadingMore(true)
    const current = sequence.current
    const currentRevision = scope.revision
    try {
      const result = await scope.run(undefined, () => repository.list({ city, page: page + 1, pageSize: PAGE_SIZE }))
      if (current !== sequence.current || currentRevision !== scope.revision) return
      const known = new Set(items.map(item => item.storeId))
      setItems([...items, ...result.items.filter(item => !known.has(item.storeId))])
      setPage(result.page); setTotal(result.total)
    } catch { setNotice('加载更多失败，请重试。') } finally { setLoadingMore(false) }
  }
  async function chooseCity() {
    if (cities.length === 0) { setNotice('城市目录暂不可用，当前展示全部开放城市。'); return }
    try {
      const selected = await Taro.showActionSheet({ itemList: ['全部开放城市', ...cities.map(entry => entry.cityName)] })
      const next = selected.tapIndex === 0 ? undefined : cities[selected.tapIndex - 1]?.cityCode
      if (next !== city) { setCity(next); void load(next) }
    } catch { /* cancelled */ }
  }
  function openStore(store: StoreView) {
    if (phase !== 'ready') return
    Taro.navigateTo({ url: `/consumer/pages/store-services/index?preview=${preview ? '1' : '0'}&storeId=${encodeURIComponent(store.storeId)}` })
      .catch(() => setNotice('页面跳转失败，请重试'))
  }
  function categoryNotWired(name: string) {
    setNotice(preview ? `“${name}”分类筛选未接入本次预览（依赖服务分类筛选契约）` : `“${name}”分类筛选将在后续版本提供`)
  }

  const ready = phase === 'ready'
  const currentCityName = city === undefined ? (cities[0]?.cityName || '成都') : (cities.find(entry => entry.cityCode === city)?.cityName || city)
  return <ConsumerPageLayout page='storeDirectory' unit={unit} navigation={{ idPrefix: 'sdir', disabled: !ready, onSelect: key => setNotice(navigationUnavailableMessage(key)), referencePlacement: undefined }} className='svc-page sdir-page' style={style}>
    <View className='svc-status-area' />
    <View className='sdir-top'>
      <View className='svc-abs svc-bg-blue' />
      <View className='svc-abs svc-bg-cream' />
      <Button id='sdir-city' className='sdir-city' onClick={() => void chooseCity()}><Text>{currentCityName}</Text></Button>
      <Text className='sdir-title'>全部服务</Text>
    </View>
    <View className='sdir-body'>
      <View className='sdir-categories'>
        {directoryCategories.map(name => <Button key={name} className='sdir-category' onClick={() => categoryNotWired(name)}><Text>{name}</Text></Button>)}
      </View>
      <Text className='sdir-heading'>门店</Text>
      {!ready && <View className='sdir-state' role='status'>
        <Text>{phase === 'loading' ? '正在加载门店…' : '加载失败，请重试。'}</Text>
        {phase === 'load-error' && <Button id='sdir-retry' className='sdir-state-action' onClick={() => void load(city)}>重新加载</Button>}
      </View>}
      {ready && items.length === 0 && <View className='sdir-state'><Text>当前城市暂无可展示的门店。</Text></View>}
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
      <View className='sdir-tail'><Text>页面数据：{preview ? '契约 Mock（preview=1，不联调）' : '真实接口（后端交付前失败关闭，可匿名浏览）'}</Text></View>
    </View>
  </ConsumerPageLayout>
}
