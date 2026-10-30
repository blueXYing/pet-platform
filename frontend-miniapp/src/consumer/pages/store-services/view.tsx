import { Button, Image, Text, View } from '@tarojs/components'
import type { CSSProperties, ReactNode } from 'react'
import { SubpageHeader } from '../../components/navigation/subpage-header'
import { designSamples, formatSalePrice, type ServiceItemView, type ServiceCoverView } from '../../service/model'
import { ServiceCover } from './cover'
import type { StoreDetailView } from '../../store/model'
import bgFlower from './assets/690-6661-bg-huaban@2x.png'
import storeBanner from './assets/690-6908-store-banner@2x.png'
import storeAvatar from './assets/690-6709-store-avatar@2x.png'
import starOrange from './assets/690-6722-star-orange@2x.png'
import starBlue from './assets/690-6889-star-blue@2x.png'
import iconLocation from './assets/690-6750-icon-location@2x.png'
import iconClock from './assets/690-6758-icon-clock@2x.png'
import iconPhone from './assets/690-6766-icon-phone@2x.png'
import reviewer1 from './assets/690-6844-reviewer-1@2x.png'
import reviewer2 from './assets/690-6877-reviewer-2@2x.png'
import './page.css'

const at = (top: number | string, extra: CSSProperties = {}): CSSProperties => ({ top: typeof top === 'number' ? `calc(var(--svc-unit) * ${top})` : top, ...extra })

/** One-to-one cutouts of node 690:6660 (see assets/manifest.json); no substitute artwork. */
export const svcAssets = { bgFlower, storeBanner, storeAvatar, starOrange, starBlue, iconLocation, iconClock, iconPhone, reviewer1, reviewer2 }

// 服务项目 card (Figma 96:4720): each row is 81.5 high (12 padding + 20 title +
// 2 + 16.5 description + 4 + 15 meta), 12 gap; title area 50; bottom pad 16.
export function serviceListCardHeight(count: number): number {
  // Empty state: title area 50 + .svc-list-empty (12 + 18 + 16).
  if (count <= 0) return 50 + 12 + 18 + 16
  return 50 + count * 81.5 + (count - 1) * 12 + 16
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
  // 行根节点用 View:微信原生 button 不允许嵌套,内层「预约」Button 在 button 里不渲染
  // (2026-10-08 真机验收发现)。第三行设计稿是"已售",列表契约无该字段:夹具行显示样例,
  // 真实行显示分类/服务时长/履约方式,不虚构销量。列表契约也没有 description，真实列表
  // 行用 categoryName 填第二行；详情页传入的 description 优先。
  const description = line.description ?? line.service.categoryName
  const fulfillment = line.service.fulfillmentType === 'PICKUP_DELIVERY' ? '接送服务' : '到店服务'
  const meta = sold ?? (line.service.durationMinutes > 0 ? `约 ${line.service.durationMinutes} 分钟 · ${fulfillment}` : fulfillment)
  return <View id={`${idPrefix}-${line.service.serviceId}`} className='svc-service-row' ariaLabel={`${line.service.serviceName}，价格${formatSalePrice(line.service.salePrice)}元`} onClick={onOpen}>
    <View className='svc-service-main'>
      <Text className='svc-service-name'>{line.service.serviceName}</Text>
      <Text className='svc-service-desc'>{description}</Text>
      <Text className='svc-service-sold'>{meta}</Text>
    </View>
    <View className='svc-service-side' onClick={event => { event.stopPropagation(); onBook() }}>
      <Text className='svc-service-price'>¥{formatSalePrice(line.service.salePrice)}</Text>
      <Button id={`${idPrefix}-book-${line.service.serviceId}`} className='svc-service-book' ariaLabel={`预约${line.service.serviceName}`} onClick={onBook}><Text>预约</Text></Button>
    </View>
  </View>
}

/**
 * Shared one-to-one chrome of node 690:6660 (服务-商家详情页) and its identical copies
 * 690:2025/690:4205 (热门服务-宠物美容-详情页). The services section and — since the /c/stores
 * slice — the store name/address/masked phone bind contract data when a store view is passed;
 * every field the nine-field projection does not carry (rating, monthly sold, distance, tags,
 * intro, opening hours, reviews) stays DESIGN-SAMPLE copy registered as contract gaps. The promo
 * strip is real when the store has a merchant promotion or an active campaign (contract 71 §3.5).
 */
export function StoreServicesDesign({ store: storeView, listTop, serviceCount, servicesNode, reviewTop, notice, footer, onBack, serviceCover, onRefreshCover, favoriteNode }: {
  store: StoreDetailView | null
  listTop: number
  /** Number of service rows inside the card; the card frame (Figma 96:4716) needs an
   *  explicit height because its title/rows are absolutely positioned. */
  serviceCount: number
  servicesNode: ReactNode
  reviewTop: number
  notice?: ReactNode
  /** Page-data footer (same copy family as the directory page's sdir-tail). */
  footer?: ReactNode
  onBack: () => void
  serviceCover?: ServiceCoverView | null
  onRefreshCover?: () => void
  /** Contract 58 favorite toggle entry (128:3200 BUTTON-25 形态): owned by the host page. */
  favoriteNode?: ReactNode
}) {
  const campaign = storeView?.campaign ?? null
  const promotion = storeView?.promotion ?? null
  const promoBadge = campaign?.offerTag ?? (promotion?.level ? `${promotion.level}折` : campaign ? '活动' : null)
  const promoTitle = campaign?.offerTitle ?? campaign?.title ?? promotion?.title ?? null
  const promoSub = campaign?.offerCopy ?? campaign?.subtitle ?? promotion?.text ?? null
  const hasPromo = Boolean(promoBadge || promoTitle || promoSub)
  const store = {
    ...designSamples.store,
    name: storeView?.storeName ?? designSamples.store.name,
    address: storeView?.address ?? designSamples.store.address,
    phone: storeView?.phoneMasked ?? designSamples.store.phone,
    promoBadge: promoBadge ?? '',
    promoTitle: promoTitle ?? '',
    promoSub: promoSub ?? '',
  }
  const reviewBottom = reviewTop + reviewCardHeight() + 20
  return <View className='svc-design' style={{ minHeight: `calc(var(--svc-unit) * ${reviewBottom + (footer ? 46 : 0)})` } as CSSProperties}>
    {/* Figma 96:4355 paint order: huaban (striped) sits underneath both the solid
        #f8f5ee body from y=75 and the blue/cream header bands; it is never visible.
        Starting the body at reviewBottom leaked the stripes through the whole page. */}
    <Image className='svc-abs svc-bg-flower' src={svcAssets.bgFlower} mode='scaleToFill' />
    <View className='svc-abs svc-bg-base' style={at(75, { height: `calc(var(--svc-unit) * ${reviewBottom - 75})` })} />
    <View className='svc-abs svc-bg-blue' />
    <View className='svc-abs svc-bg-cream' />
    <SubpageHeader title='商家详情' backId='svc-nav-back' height={74}
      onBack={onBack} right={favoriteNode} />
    {serviceCover !== undefined
      ? <ServiceCover cover={serviceCover} onRefresh={onRefreshCover || (() => {})} />
      : <Image className='svc-abs svc-banner' src={svcAssets.storeBanner} mode='aspectFill' />}
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
    {hasPromo && <View className='svc-abs svc-promo'>
      {store.promoBadge ? <View className='svc-promo-badge'><Text>{store.promoBadge}</Text></View> : null}
      <Text className='svc-promo-title'>{store.promoTitle}</Text>
      <Text className='svc-promo-sub'>{store.promoSub}</Text>
    </View>}
    <View className='svc-abs svc-list-card' style={at(listTop, {
      height: `calc(var(--svc-unit) * ${serviceListCardHeight(serviceCount)})`,
      minHeight: 0,
    })}>
      <Text className='svc-list-title'>服务项目</Text>
      <View className='svc-list-rows'>{servicesNode}</View>
    </View>
    <View className='svc-abs svc-review-card' style={at(reviewTop, {
      height: `calc(var(--svc-unit) * ${reviewCardHeight()})`,
    })}>
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
    {/* 底部「拨打电话/立即预约」固定按钮栏已按用户 2026-10-08 验收裁决移除:
        拨打电话 V1 无此能力,预约入口以服务项目行内「预约」按钮为准。 */}
    {footer && <View className='svc-tail' style={at(reviewBottom + 14, { left: `calc(var(--svc-unit) * 29)`, right: `calc(var(--svc-unit) * 29)` })}>{footer}</View>}
    {notice}
  </View>
}
