import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { ConsumerPageLayout } from '../../components/page-layout'
import { switchConsumerTab } from '../../components/navigation/switch'
import { SubpageHeader, SubpageHeaderSpacer } from '../../components/navigation/subpage-header'
import { readSelectedCity } from '../../city/model'
import {
  MarketingRepository, PLACEMENT_POSITION_HOT_SERVICE_TOP, type PlacementCard,
} from '../../marketing/model'
import {
  PreviewServiceCatalogRepository, ServiceCatalogRepository, type ServiceCategoryView,
} from '../../service-catalog/model'
import { pickHotServiceBanner, placementJumpUrl, previewHotServiceBanner } from '../../service-catalog/hot-service'
import { runCatalogRead } from '../../store/repository'
import { consumerApi } from '../../../shared/consumer-runtime'
import './page.css'
import './hot-services.css'

// 热门服务二级页 — design source: Figma 97:7825（热门服务，402x966）。2026-10-30 F-C-03
// （契约 74 §3.1/§3.7，71 v0.3 §3.7/§10，设计登记表 §4.26 偏差口径）：
// - 头部按登记偏差统一 SubpageHeader（原稿首页式头部与底部五 tab 栏不照搬，无底部 tab 栏）；
// - 顶部 banner 读 HOT_SERVICE_TOP 投放位（C 端 placements 第一条；0 条/读取失败整块隐藏，
//   coverUrl 缺失渲染中性底版，不伪造图）；
// - 分类卡由 GET /c/service-categories 自动生成（启用+有可用商家+城市匹配）；iconUrl 缺失
//   用中性占位块，卡面插画槽无契约字段同口径占位（D-01/D-02 素材未到位是已知状态）；
// - 「全部 >」switchTab 服务 tab；分类卡点击进 stores?categoryCode=（F-C-04 已预留入口，
//   词法非法参数由 stores 页忽略）；
// - 首页内容位 HOT_SERVICE 入口落本页（首页侧接线不归本切片），本页可匿名独立直开；
// - 匿名可浏览（74 §1）；严格解码失败/空态走失败/空态呈现，不伪造数据。
type Phase = 'loading' | 'ready' | 'load-error'

export default function HotServicePage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const { scope, revision } = useWorkspace(preview ? 'preview' : 'real')
  const catalog = useRef(preview ? new PreviewServiceCatalogRepository() : new ServiceCatalogRepository(consumerApi))
  const marketing = useRef(new MarketingRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>('loading')
  const [categories, setCategories] = useState<readonly ServiceCategoryView[]>([])
  const [banner, setBanner] = useState<PlacementCard | null>(preview ? previewHotServiceBanner : null)
  const [city, setCity] = useState<string | undefined>(() => readSelectedCity().cityCode)
  const sequence = useRef(0)
  const bannerSequence = useRef(0)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--svc-status-top': `${platformInfo.statusBarHeight || 0}px`, '--svc-unit': `${unit}px` } as CSSProperties

  // 分类字典（74 §3.1）：本页核心数据；失败/空态显式呈现（可重试），不吞错、不伪造。
  const loadCategories = useCallback(async (nextCity: string | undefined) => {
    setPhase('loading')
    const current = ++sequence.current
    const currentRevision = scope.revision
    try {
      const rows = await runCatalogRead(scope, () => catalog.current!.serviceCategories(nextCity))
      if (current !== sequence.current || currentRevision !== scope.revision) return
      setCategories(rows); setPhase('ready')
    } catch {
      if (current === sequence.current && currentRevision === scope.revision) setPhase('load-error')
    }
  }, [catalog, scope])

  const loadBanner = useCallback(async (nextCity: string | undefined) => {
    // 74 §3.7：投放位读取失败/0 条整块隐藏，不阻塞分类卡；preview 走契约 Mock（不联网）。
    if (preview) return
    setBanner(null)
    const current = ++bannerSequence.current
    try {
      const items = await marketing.current!.placements(PLACEMENT_POSITION_HOT_SERVICE_TOP, nextCity)
      if (current !== bannerSequence.current) return
      setBanner(pickHotServiceBanner(items))
    } catch {
      if (current === bannerSequence.current) setBanner(null)
    }
  }, [marketing, preview])

  useDidShow(() => {
    // 城市选择持久化于本地存储（与 stores/首页城市芯片共用）：每次展示重读并刷新两路读侧。
    const stored = readSelectedCity().cityCode
    const next = stored !== undefined ? stored : city
    if (next !== city) setCity(next)
    void loadCategories(next)
    void loadBanner(next)
  })

  function openCategory(categoryCode: string) {
    if (phase !== 'ready') return
    Taro.navigateTo({ url: `/consumer/pages/store-services/stores?preview=${preview ? '1' : '0'}&categoryCode=${encodeURIComponent(categoryCode)}` })
      .catch(() => undefined)
  }
  function openBanner() {
    if (!banner) return
    const url = placementJumpUrl(banner)
    if (!url) return
    Taro.navigateTo({ url }).catch(() => undefined)
  }

  const ready = phase === 'ready'
  return <ConsumerPageLayout page='storeDirectory' unit={unit} navigation={preview ? { idPrefix: 'hotsvc', disabled: !ready, onSelect: key => void switchConsumerTab(key), referencePlacement: undefined } : undefined} className='svc-page hotsvc-page' style={style}>
    {/* 二级页统一头部（登记偏差：97:7825 原稿首页式头部不照搬；标题左对齐，胶囊避让同组件）。 */}
    <SubpageHeader title='热门服务' backId='hotsvc-back' height={47} />
    <SubpageHeaderSpacer height={47} />
    <View className='hotsvc-body'>
      {banner && <Button id='hotsvc-banner' className='hotsvc-banner' hoverClass='none'
        onClick={() => openBanner()} ariaLabel={`${banner.title}${banner.subtitle ? `，${banner.subtitle}` : ''}`}>
        {banner.coverUrl
          ? <Image className='hotsvc-banner-img' src={banner.coverUrl} mode='aspectFill' />
          : <View className='hotsvc-banner-img hotsvc-banner-placeholder' />}
        <View className='hotsvc-banner-shade' />
        <View className='hotsvc-banner-body'>
          {banner.tagName ? <View className='hotsvc-banner-badge'>
            <Text className='hotsvc-banner-badge-text'>{banner.tagName}</Text>
          </View> : null}
          <Text className='hotsvc-banner-title'>{banner.title}</Text>
          {banner.subtitle ? <Text className='hotsvc-banner-sub'>{banner.subtitle}</Text> : null}
        </View>
      </Button>}
      {/* 标题行：H2「热门服务」+「全部 >」（97:7729）；「全部 >」切服务 tab（Taro switchTab）。 */}
      <View className='hotsvc-heading'>
        <Text className='hotsvc-heading-title'>热门服务</Text>
        <Button id='hotsvc-all-services' className='hotsvc-heading-link' hoverClass='none'
          onClick={() => void switchConsumerTab('services')}>{'全部 >'}</Button>
      </View>
      {!ready && <View className='hotsvc-state' role='status'>
        <Text>{phase === 'loading' ? '正在加载热门服务…' : '分类加载失败，请重试。'}</Text>
        {phase === 'load-error' && <Button id='hotsvc-retry' className='hotsvc-state-action' hoverClass='none'
          onClick={() => void loadCategories(city)}>重新加载</Button>}
      </View>}
      {ready && categories.length === 0 && <View className='hotsvc-state'>
        <Text>当前城市暂无可展示的服务分类。</Text>
      </View>}
      {ready && <View className='hotsvc-grid'>
        {categories.map(category => <Button key={category.categoryCode}
          id={`hotsvc-category-${category.categoryCode}`} className='hotsvc-card' hoverClass='none'
          onClick={() => openCategory(category.categoryCode)}
          ariaLabel={`${category.categoryName}${category.description ? `，${category.description}` : ''}`}>
          {/* 卡面插画槽（97:7738 IMG）：契约无对应字段，D-02 素材待补 → 中性占位块，不伪造图。 */}
          <View className='hotsvc-card-art' />
          <View className='hotsvc-card-body'>
            <View className='hotsvc-card-head'>
              <View className='hotsvc-card-icon'>
                {category.iconUrl
                  ? <Image className='hotsvc-card-icon-img' src={category.iconUrl} mode='aspectFit' />
                  : <View className='hotsvc-card-icon-block' />}
              </View>
              <Text className='hotsvc-card-name'>{category.categoryName}</Text>
            </View>
            {category.description ? <Text className='hotsvc-card-desc'>{category.description}</Text> : null}
          </View>
        </Button>)}
      </View>}
      <View className='hotsvc-tail'>
        <Text>页面数据：{preview ? '契约 Mock（preview=1，不联调）' : '真实接口（服务分类字典/营销投放位匿名可浏览）'}</Text>
      </View>
    </View>
  </ConsumerPageLayout>
}
