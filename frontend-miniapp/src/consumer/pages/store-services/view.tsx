import { Button, Image, Text, View } from '@tarojs/components'
import type { CSSProperties, ReactNode } from 'react'
import { designSamples, formatSalePrice, type ServiceItemView } from '../../service/model'
import type { StoreDetailView } from '../../store/model'
import bgFlower from './assets/690-6661-bg-huaban@2x.png'
import storeBanner from './assets/690-6908-store-banner@2x.png'
import storeAvatar from './assets/690-6709-store-avatar@2x.png'
import starOrange from './assets/690-6722-star-orange@2x.png'
import starBlue from './assets/690-6889-star-blue@2x.png'
import iconLocation from './assets/690-6750-icon-location@2x.png'
import iconClock from './assets/690-6758-icon-clock@2x.png'
import iconPhone from './assets/690-6766-icon-phone@2x.png'
import navBack from './assets/690-6706-nav-back@2x.png'
import reviewer1 from './assets/690-6844-reviewer-1@2x.png'
import reviewer2 from './assets/690-6877-reviewer-2@2x.png'
import './page.css'

const at = (top: number | string, extra: CSSProperties = {}): CSSProperties => ({ top: typeof top === 'number' ? `calc(var(--svc-unit) * ${top})` : top, ...extra })

/** One-to-one cutouts of node 690:6660 (see assets/manifest.json); no substitute artwork. */
export const svcAssets = { bgFlower, storeBanner, storeAvatar, starOrange, starBlue, iconLocation, iconClock, iconPhone, navBack, reviewer1, reviewer2 }

// 团购套餐 card: rows are 82 tall with an 11.5 gap (12 after the last); title area 50; bottom pad 16.
export function serviceListCardHeight(count: number): number {
  if (count <= 0) return 50 + 24 + 16
  return 50 + count * 82 + (count - 1) * 11.5 + 16
}
// 用户评价 card: two design items of 72 with a 12 gap; head 50; bottom pad 18 (frame 343x224).
export function reviewCardHeight(): number { return 224 }

export type ServiceCardLine = {
  service: ServiceItemView
  // Frozen list projection has no description; the design card shows one. Preview rows use the
  // design sample; the detail page passes the contract description of the queried service.
  description?: string
}

export function ServiceRow({ line, idPrefix, onOpen, onBook }: { line: ServiceCardLine; idPrefix: string; onOpen?: () => void; onBook: () => void }) {
  const sold = designSamples.sold[line.service.serviceId]
  return <Button id={`${idPrefix}-${line.service.serviceId}`} className='svc-service-row' ariaLabel={`${line.service.serviceName}，价格${formatSalePrice(line.service.salePrice)}元`} onClick={onOpen}>
    <View className='svc-service-main'>
      <Text className='svc-service-name'>{line.service.serviceName}</Text>
      {line.description ? <Text className='svc-service-desc'>{line.description}</Text> : null}
      {sold ? <Text className='svc-service-sold'>{sold}</Text> : null}
    </View>
    <View className='svc-service-side' onClick={event => { event.stopPropagation(); onBook() }}>
      <Text className='svc-service-price'>¥{formatSalePrice(line.service.salePrice)}</Text>
      <Button id={`${idPrefix}-book-${line.service.serviceId}`} className='svc-service-book' ariaLabel={`预约${line.service.serviceName}`} onClick={onBook}><Text>预约</Text></Button>
    </View>
  </Button>
}

/**
 * Shared one-to-one chrome of node 690:6660 (服务-商家详情页) and its identical copies
 * 690:2025/690:4205 (热门服务-宠物美容-详情页). The services section and — since the /c/stores
 * slice — the store name/address/masked phone bind contract data when a store view is passed;
 * every field the nine-field projection does not carry (rating, monthly sold, distance, tags,
 * intro, opening hours, promo, reviews) stays DESIGN-SAMPLE copy registered as contract gaps.
 */
export function StoreServicesDesign({ store: storeView, listTop, servicesNode, reviewTop, notice, onBack, onCall, onBookNow, bookEnabled }: {
  store: StoreDetailView | null
  listTop: number
  servicesNode: ReactNode
  reviewTop: number
  notice?: ReactNode
  onBack: () => void
  onCall: () => void
  onBookNow: () => void
  bookEnabled: boolean
}) {
  const store = {
    ...designSamples.store,
    name: storeView?.storeName ?? designSamples.store.name,
    address: storeView?.address ?? designSamples.store.address,
    phone: storeView?.phoneMasked ?? designSamples.store.phone,
  }
  const reviewBottom = reviewTop + reviewCardHeight() + 20
  return <View className='svc-design' style={{ minHeight: `calc(var(--svc-unit) * ${reviewBottom})` } as CSSProperties}>
    <View className='svc-abs svc-bg-blue' />
    <View className='svc-abs svc-bg-cream' />
    <Image className='svc-abs svc-bg-flower' src={svcAssets.bgFlower} mode='scaleToFill' />
    <View className='svc-abs svc-bg-base' style={at(reviewBottom, { height: `calc(var(--svc-unit) * ${reviewBottom - 75})` })} />
    <Button id='svc-nav-back' className='svc-nav-back' ariaLabel='返回' onClick={onBack}><Image src={svcAssets.navBack} mode='scaleToFill' /></Button>
    <Text className='svc-abs svc-nav-title'>商家详情</Text>
    <Image className='svc-abs svc-banner' src={svcAssets.storeBanner} mode='aspectFill' />
    <View className='svc-abs svc-store-card'>
      <Image className='svc-store-avatar' src={svcAssets.storeAvatar} mode='scaleToFill' />
      <View className='svc-store-head'>
        <View className='svc-store-name'>
          <Text className='svc-store-name-text'>{store.name}</Text>
          <View className='svc-store-type-tag'><Text>{store.typeTag}</Text></View>
        </View>
        <View className='svc-store-stats'>
          <Image src={svcAssets.starOrange} mode='scaleToFill' />
          <Text className='svc-store-rating'>{store.rating}</Text>
          <Text className='svc-store-meta'>{store.monthlySold}</Text>
          <Text className='svc-store-meta'>{store.distance}</Text>
        </View>
        <View className='svc-store-tags'>
          {store.tags.map(tag => <View key={tag} className='svc-store-tag'><Text>{tag}</Text></View>)}
        </View>
      </View>
      <Text className='svc-store-intro'>{store.intro}</Text>
    </View>
    <View className='svc-abs svc-info-card'>
      <Text className='svc-info-title'>门店信息</Text>
      <View className='svc-info-row' style={at(54)}><Image src={svcAssets.iconLocation} mode='scaleToFill' /><Text>{store.address}</Text></View>
      <View className='svc-info-row' style={at(88)}><Image src={svcAssets.iconClock} mode='scaleToFill' /><Text>{store.hours}</Text></View>
      <View className='svc-info-row' style={at(122)}><Image src={svcAssets.iconPhone} mode='scaleToFill' /><Text>{store.phone}</Text></View>
    </View>
    <View className='svc-abs svc-promo'>
      <View className='svc-promo-badge'><Text>{store.promoBadge}</Text></View>
      <Text className='svc-promo-title'>{store.promoTitle}</Text>
      <Text className='svc-promo-sub'>{store.promoSub}</Text>
    </View>
    <View className='svc-abs svc-list-card' style={at(listTop, { minHeight: 'none' })}>
      <Text className='svc-list-title'>团购套餐</Text>
      <View className='svc-list-rows'>{servicesNode}</View>
    </View>
    <View className='svc-abs svc-review-card' style={at(reviewTop)}>
      <View className='svc-review-head'>
        <Text className='svc-review-title'>用户评价</Text>
        <Text className='svc-review-count'>{designSamples.reviews.count}</Text>
      </View>
      {designSamples.reviews.items.map((review, index) => <View key={review.name} className='svc-review-item' style={at(50 + index * 84)}>
        <Image className='svc-review-avatar' src={index === 0 ? svcAssets.reviewer1 : svcAssets.reviewer2} mode='scaleToFill' />
        <View className='svc-review-body'>
          <View className='svc-review-line'>
            <Text className='svc-review-name'>{review.name}</Text>
            <Text className='svc-review-time'>{review.time}</Text>
          </View>
          <View className='svc-review-stars'>
            {[0, 1, 2, 3, 4].map(star => <Image key={star} src={review.stars === 'orange' ? svcAssets.starOrange : svcAssets.starBlue} mode='scaleToFill' />)}
          </View>
          <Text className='svc-review-text'>{review.text}</Text>
        </View>
      </View>)}
    </View>
    {/* Fixed action bar sits above the shared bottom navigation (design frame overlays it on
        scrolling content at y1160; the draft tabbar itself is diff D1 in the design inventory). */}
    <View className='svc-bottombar'>
      <Button id='svc-bottom-call' className='svc-bottom-call' onClick={onCall}><Text>拨打电话</Text></Button>
      <Button id='svc-bottom-book' className='svc-bottom-book' disabled={!bookEnabled} onClick={onBookNow}><Text>立即预约</Text></Button>
    </View>
    {notice}
  </View>
}
