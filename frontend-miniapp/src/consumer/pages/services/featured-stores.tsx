import { Button, Image, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState } from 'react'
import { consumerApi } from '../../../shared/consumer-runtime'
import {
  ServiceCatalogRepository, type FeaturedServiceItem, type FeaturedStoreCard,
} from '../../service-catalog/model'
import { FEATURED_PAGE_SIZE, hasMoreFeatured, priceText, promotionLine, ratingText, soldText, storeTagLabel } from './view-model'
import headingPaw from './assets/heading-paw.svg'
import featuredStar from './assets/featured-star.svg'

// 服务 tab「优质商家」列表（F-C-02，110:480 A-101 族；契约 74 §3.4/§6）。
// 数据走 GET /c/stores/featured（F-C-01 解码器已就绪）：评分（一位小数/低于 5 不展示）、
// 月售、每店 2 个服务、促销标签；降级字段诚实 null 不虚构；分页加载（服务端 total 为准）；
// 空态/失败态显式呈现可重试。距离/资质标签不做（74 §10 登记偏差）。
// 74 §6.5：点击门店进商家详情、点击服务项目进服务详情（预览通道不适用本 tab 页，固定 0）。

type Phase = 'loading' | 'ready' | 'load-error'

export type FeaturedStoresState = Readonly<{
  phase: Phase
  cards: readonly FeaturedStoreCard[]
  total: number
  appending: boolean
  appendError: boolean
  loadMore(): void
  retryAppend(): void
  reload(): void
}>

export function useFeaturedStores(cityCode: string | undefined): FeaturedStoresState {
  const repository = useRef<ServiceCatalogRepository | null>(null)
  if (repository.current === null) repository.current = new ServiceCatalogRepository(consumerApi)
  const mounted = useRef(true)
  useEffect(() => () => { mounted.current = false }, [])
  const sequence = useRef(0)
  // 并发卫兵入 ref：loadMore/retryAppend 的闭包恒稳定，不读陈旧 state。
  const guard = useRef({ appending: false, appendError: false })
  const cityRef = useRef(cityCode)
  cityRef.current = cityCode
  const [phase, setPhase] = useState<Phase>('loading')
  const [cards, setCards] = useState<readonly FeaturedStoreCard[]>([])
  const [total, setTotal] = useState(0)
  const [appending, setAppending] = useState(false)
  const [appendError, setAppendError] = useState(false)
  const snapshot = useRef({ phase, cards, total })
  snapshot.current = { phase, cards, total }

  const load = useCallback(async () => {
    const seq = ++sequence.current
    guard.current = { appending: false, appendError: false }
    setPhase('loading'); setCards([]); setTotal(0)
    setAppending(false); setAppendError(false)
    try {
      const page = await repository.current!.featuredStores({ cityCode: cityRef.current, page: 1, pageSize: FEATURED_PAGE_SIZE })
      if (!mounted.current || seq !== sequence.current) return
      setCards(page.items); setTotal(page.total); setPhase('ready')
    } catch {
      if (mounted.current && seq === sequence.current) setPhase('load-error')
    }
  }, [])

  const loadMore = useCallback(async () => {
    const { phase: currentPhase, cards: currentCards, total: currentTotal } = snapshot.current
    if (currentPhase !== 'ready' || guard.current.appending || guard.current.appendError) return
    if (!hasMoreFeatured(currentCards.length, currentTotal)) return
    const seq = sequence.current
    guard.current.appending = true
    setAppending(true)
    try {
      const next = await repository.current!.featuredStores({
        cityCode: cityRef.current,
        page: Math.floor(currentCards.length / FEATURED_PAGE_SIZE) + 1,
        pageSize: FEATURED_PAGE_SIZE,
      })
      if (!mounted.current || seq !== sequence.current) return
      // 去重并入（同一 storeId 不重复渲染；服务端排序稳定时不会发生，防御性保留）。
      const seen = new Set(currentCards.map(card => card.storeId))
      const merged = [...currentCards, ...next.items.filter(item => !seen.has(item.storeId))]
      setCards(merged); setTotal(next.total)
      guard.current.appending = false
      setAppending(false)
    } catch {
      if (!mounted.current || seq !== sequence.current) return
      guard.current.appending = false
      guard.current.appendError = true
      setAppending(false); setAppendError(true)
    }
  }, [])

  const retryAppend = useCallback(() => {
    guard.current.appendError = false
    setAppendError(false)
    void loadMore()
  }, [loadMore])

  return {
    phase, cards, total, appending, appendError,
    loadMore: () => { void loadMore() },
    retryAppend,
    reload: () => { void load() },
  }
}

function openStore(storeId: string) {
  Taro.navigateTo({ url: `/consumer/pages/store-services/index?preview=0&storeId=${encodeURIComponent(storeId)}` })
    .catch(() => undefined)
}

function openService(serviceId: string) {
  Taro.navigateTo({ url: `/consumer/pages/store-services/service-detail?preview=0&serviceId=${encodeURIComponent(serviceId)}` })
    .catch(() => undefined)
}

function ServiceRow({ service }: { service: FeaturedServiceItem }) {
  const sold = soldText('已售', service.soldCount)
  return <Button id={`svc2-service-${service.serviceId}`} className='svc2-fs-col' hoverClass='none'
    onClick={event => { event.stopPropagation(); openService(service.serviceId) }}>
    <Text className='svc2-fs-service-name'>{service.serviceName}</Text>
    <View className='svc2-fs-price-row'>
      <Text className='svc2-fs-price'>{priceText(service.salePrice)}</Text>
      {sold && <Text className='svc2-fs-sold'>{sold}</Text>}
    </View>
  </Button>
}

function StoreCardView({ card, index }: { card: FeaturedStoreCard; index: number }) {
  const rating = ratingText(card.rating)
  const monthly = soldText('月售', card.monthlySold)
  const intro = card.intro
  const tag = storeTagLabel(card)
  const promo = promotionLine(card.promotion)
  return <View className='svc2-fs-card'
    onClick={() => openStore(card.storeId)} ariaLabel={`查看门店${card.storeName}`}>
    <View className='svc2-fs-top'>
      {card.avatarUrl
        ? <Image className='svc2-fs-avatar' src={card.avatarUrl} mode='aspectFill' />
        : <View className='svc2-fs-avatar svc2-fs-avatar-placeholder' />}
      <View className='svc2-fs-info'>
        <View className='svc2-fs-name-row'>
          <Text className='svc2-fs-name'>{card.storeName}</Text>
          <View className='svc2-fs-tag'><Text className='svc2-fs-tag-text'>{tag}</Text></View>
        </View>
        {(rating || monthly) && <View className='svc2-fs-facts'>
          {rating && <View className='svc2-fs-rating'>
            <Image className='svc2-fs-star' src={featuredStar} mode='aspectFit' />
            <Text className='svc2-fs-score'>{rating}</Text>
          </View>}
          {monthly && <Text className='svc2-fs-monthly'>{monthly}</Text>}
        </View>}
        {intro && <Text className='svc2-fs-intro'>{intro}</Text>}
      </View>
    </View>
    {card.services.length > 0 && <View className='svc2-fs-services' id={`svc2-store-services-${index}`}>
      {card.services.map(service => <ServiceRow key={service.serviceId} service={service} />)}
    </View>}
    {promo && <View className='svc2-fs-promo'>
      {card.promotion?.offerTag && <View className='svc2-fs-promo-badge'>
        <Text className='svc2-fs-promo-badge-text'>{card.promotion.offerTag}</Text>
      </View>}
      <Text className='svc2-fs-promo-text'>{promo}</Text>
    </View>}
  </View>
}

export function FeaturedStoresSection(state: FeaturedStoresState) {
  const { phase, cards, total } = state
  return <View className='svc2-featured'>
    <View className='svc2-sec-head'>
      <View className='svc2-sec-title-wrap'>
        <Image className='svc2-sec-paw' src={headingPaw} mode='aspectFit' />
        <Text className='svc2-sec-title'>优质商家</Text>
      </View>
    </View>
    {phase !== 'ready' && <View className='svc2-state' role='status'>
      <Text>{phase === 'loading' ? '正在加载优质商家…' : '优质商家加载失败，请重试。'}</Text>
      {phase === 'load-error' && <Button className='svc2-state-action' hoverClass='none'
        onClick={state.reload}>重新加载</Button>}
    </View>}
    {phase === 'ready' && cards.length === 0 && <View className='svc2-state'>
      <Text>当前城市暂无优质商家。</Text>
    </View>}
    {phase === 'ready' && cards.length > 0 && <View className='svc2-fs-list'>
      {cards.map((card, index) => <StoreCardView key={card.storeId} card={card} index={index} />)}
    </View>}
    {phase === 'ready' && cards.length > 0 && <View className='svc2-foot'>
      {state.appendError
        ? <View className='svc2-foot-error'>
            <Text className='svc2-foot-text'>加载失败，</Text>
            <Button className='svc2-foot-retry' hoverClass='none' onClick={state.retryAppend}>点击重试</Button>
          </View>
        : <Text className='svc2-foot-text'>
            {state.appending ? '正在加载更多…' : hasMoreFeatured(cards.length, total) ? '上拉加载更多' : '没有更多了'}
          </Text>}
    </View>}
  </View>
}
