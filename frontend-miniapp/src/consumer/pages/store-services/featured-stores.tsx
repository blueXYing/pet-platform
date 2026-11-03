import { Button, Image, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState } from 'react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { ServiceCatalogRepository, type FeaturedServiceItem, type FeaturedSort, type FeaturedStoreCard } from '../../service-catalog/model'
import featuredStar from './assets/featured-star.svg'

// 74 §3.4 优质商家聚合卡——分类门店页（store-services 分包）本地副本。
// 分包页面不能可靠复用主包页面目录内的组件/hook（DevTools 分包编译会丢失导出），
// 因此把卡面与分页 hook 放在分包内；服务 tab 维护同一卡面的主包版本。
// assets/featured-star.svg 保持内容唯一（带本地注释），避免 Taro 资产去重把共享模块
// 放到主包后 DevTools 分包编译报 missing module。距离/资质标签不做（74 §10 登记偏差）。

type Phase = 'loading' | 'ready' | 'load-error'
const FEATURED_PAGE_SIZE = 10

export type FeaturedStoresState = Readonly<{
  phase: Phase
  cards: readonly FeaturedStoreCard[]
  total: number
  appending: boolean
  appendError: boolean
  loadMore(): void
  retryAppend(): void
  reload(cityCode?: string, categoryCode?: string, sort?: FeaturedSort): void
}>

function hasMoreFeatured(loadedCount: number, total: number): boolean {
  return loadedCount < total
}

function ratingText(rating: Readonly<{ score: string | null; available: boolean }>): string | null {
  if (!rating.available) return null
  return rating.score ?? '暂无评分'
}

function soldText(prefix: string, sold: number | null): string | null {
  return sold === null ? null : `${prefix}${sold}`
}

function priceText(salePrice: string): string {
  const trimmed = salePrice.endsWith('.00') ? salePrice.slice(0, -3) : salePrice
  return `¥${trimmed}`
}

function storeTagLabel(card: Readonly<{ tags: readonly string[]; categoryName: string }>): string {
  return card.tags.length > 0 ? card.tags[0]! : card.categoryName
}

function promotionLine(
  promotion: Readonly<{ offerTitle: string | null; offerCopy: string | null }> | null,
): string | null {
  if (!promotion) return null
  return promotion.offerTitle ?? promotion.offerCopy
}

export function useFeaturedStores(
  cityCode: string | undefined,
  categoryCode?: string,
  sort: FeaturedSort = 'COMPREHENSIVE',
  enabled = true,
): FeaturedStoresState {
  const repository = useRef<ServiceCatalogRepository | null>(null)
  if (repository.current === null) repository.current = new ServiceCatalogRepository(consumerApi)
  const mounted = useRef(true)
  useEffect(() => () => { mounted.current = false }, [])
  const sequence = useRef(0)
  const guard = useRef({ appending: false, appendError: false })
  const cityRef = useRef(cityCode)
  cityRef.current = cityCode
  const categoryRef = useRef(categoryCode)
  categoryRef.current = categoryCode
  const sortRef = useRef(sort)
  sortRef.current = sort
  const [phase, setPhase] = useState<Phase>(enabled ? 'loading' : 'ready')
  const [cards, setCards] = useState<readonly FeaturedStoreCard[]>([])
  const [total, setTotal] = useState(0)
  const [appending, setAppending] = useState(false)
  const [appendError, setAppendError] = useState(false)
  const snapshot = useRef({ phase, cards, total })
  snapshot.current = { phase, cards, total }

  const load = useCallback(async (
    nextCity: string | undefined = cityRef.current,
    nextCategory: string | undefined = categoryRef.current,
    nextSort: FeaturedSort = sortRef.current,
  ) => {
    if (!enabled) return
    cityRef.current = nextCity
    categoryRef.current = nextCategory
    sortRef.current = nextSort
    const seq = ++sequence.current
    guard.current = { appending: false, appendError: false }
    setPhase('loading'); setCards([]); setTotal(0)
    setAppending(false); setAppendError(false)
    try {
      const page = await repository.current!.featuredStores({
        cityCode: nextCity, categoryCode: nextCategory, sort: nextSort,
        page: 1, pageSize: FEATURED_PAGE_SIZE,
      })
      if (!mounted.current || seq !== sequence.current) return
      setCards(page.items); setTotal(page.total); setPhase('ready')
    } catch {
      if (mounted.current && seq === sequence.current) setPhase('load-error')
    }
  }, [enabled])

  const loadMore = useCallback(async () => {
    if (!enabled) return
    const { phase: currentPhase, cards: currentCards, total: currentTotal } = snapshot.current
    if (currentPhase !== 'ready' || guard.current.appending || guard.current.appendError) return
    if (!hasMoreFeatured(currentCards.length, currentTotal)) return
    const seq = sequence.current
    guard.current.appending = true
    setAppending(true)
    try {
      const next = await repository.current!.featuredStores({
        cityCode: cityRef.current,
        categoryCode: categoryRef.current,
        sort: sortRef.current,
        page: Math.floor(currentCards.length / FEATURED_PAGE_SIZE) + 1,
        pageSize: FEATURED_PAGE_SIZE,
      })
      if (!mounted.current || seq !== sequence.current) return
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
  }, [enabled])

  const retryAppend = useCallback(() => {
    guard.current.appendError = false
    setAppendError(false)
    void loadMore()
  }, [loadMore])

  return {
    phase, cards, total, appending, appendError,
    loadMore: () => { void loadMore() },
    retryAppend,
    reload: (cityCode?: string, categoryCode?: string, sort?: FeaturedSort) => {
      void load(cityCode, categoryCode, sort)
    },
  }
}

function openStore(storeId: string, preview: boolean) {
  Taro.navigateTo({ url: `/consumer/pages/store-services/index?preview=${preview ? '1' : '0'}&storeId=${encodeURIComponent(storeId)}` })
    .catch(() => undefined)
}

function openService(serviceId: string, preview: boolean) {
  Taro.navigateTo({ url: `/consumer/pages/store-services/service-detail?preview=${preview ? '1' : '0'}&serviceId=${encodeURIComponent(serviceId)}` })
    .catch(() => undefined)
}

function ServiceRow({ service, preview }: { service: FeaturedServiceItem; preview: boolean }) {
  const sold = soldText('已售', service.soldCount)
  return <Button id={`sdir-service-${service.serviceId}`} className='sdir-fs-col' hoverClass='none'
    onClick={event => { event.stopPropagation(); openService(service.serviceId, preview) }}>
    <Text className='sdir-fs-service-name'>{service.serviceName}</Text>
    <View className='sdir-fs-price-row'>
      <Text className='sdir-fs-price'>{priceText(service.salePrice)}</Text>
      {sold && <Text className='sdir-fs-sold'>{sold}</Text>}
    </View>
  </Button>
}

/** 74 §3.4 优质商家卡（分类门店页）。 */
export function FeaturedStoreCardView({ card, index, preview = false }: {
  card: FeaturedStoreCard; index: number; preview?: boolean
}) {
  const rating = ratingText(card.rating)
  const monthly = soldText('月售', card.monthlySold)
  const intro = card.intro
  const tag = storeTagLabel(card)
  const promo = promotionLine(card.promotion)
  return <View id={`sdir-featured-${card.storeId}`} className='sdir-fs-card'
    onClick={() => openStore(card.storeId, preview)} ariaLabel={`查看门店${card.storeName}`}>
    <View className='sdir-fs-top'>
      {card.avatarUrl
        ? <Image className='sdir-fs-avatar' src={card.avatarUrl} mode='aspectFill' />
        : <View className='sdir-fs-avatar sdir-fs-avatar-placeholder' />}
      <View className='sdir-fs-info'>
        <View className='sdir-fs-name-row'>
          <Text className='sdir-fs-name'>{card.storeName}</Text>
          <View className='sdir-fs-tag'><Text className='sdir-fs-tag-text'>{tag}</Text></View>
        </View>
        {(rating || monthly) && <View className='sdir-fs-facts'>
          {rating && <View className='sdir-fs-rating'>
            <Image className='sdir-fs-star' src={featuredStar} mode='aspectFit' />
            <Text className='sdir-fs-score'>{rating}</Text>
          </View>}
          {monthly && <Text className='sdir-fs-monthly'>{monthly}</Text>}
        </View>}
        {intro && <Text className='sdir-fs-intro'>{intro}</Text>}
      </View>
    </View>
    {card.services.length > 0 && <View className='sdir-fs-services' id={`sdir-store-services-${index}`}>
      {card.services.map(service => <ServiceRow key={service.serviceId} service={service} preview={preview} />)}
    </View>}
    {promo && <View className='sdir-fs-promo'>
      {card.promotion?.offerTag && <View className='sdir-fs-promo-badge'>
        <Text className='sdir-fs-promo-badge-text'>{card.promotion.offerTag}</Text>
      </View>}
      <Text className='sdir-fs-promo-text'>{promo}</Text>
    </View>}
  </View>
}
