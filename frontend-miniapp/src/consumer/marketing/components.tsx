import { Image, ScrollView, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useEffect, useRef, useState } from 'react'
import { consumerApi } from '../../shared/consumer-runtime'
import { MarketingRepository, type PlacementCard, type StoreCard } from './model'
import { activityTemplate, activityTemplateCode } from './theme'
import activityCardPaper from '../assets/marketing/activity-card-paper.png'
import './marketing.css'

const repository = new MarketingRepository(consumerApi)

function openCampaign(campaignId: string) {
  Taro.navigateTo({ url: `/consumer/pages/marketing/campaign?campaignId=${encodeURIComponent(campaignId)}` })
    .catch(() => Taro.showToast({ title: '页面打开失败，请重试', icon: 'none' }))
}

function openStore(storeId: string) {
  Taro.navigateTo({ url: `/consumer/pages/store-services/index?preview=0&storeId=${encodeURIComponent(storeId)}` })
    .catch(() => Taro.showToast({ title: '页面打开失败，请重试', icon: 'none' }))
}

/** 首页「活动钜惠」资源位；空/失败时整块不渲染，不占位。 */
export function HomeActivitySlot({ cityCode }: { cityCode?: string }) {
  const [items, setItems] = useState<readonly PlacementCard[]>([])
  const [ready, setReady] = useState(false)
  const sequence = useRef(0)
  useEffect(() => {
    const seq = ++sequence.current
    setReady(false)
    repository.placements('HOME_ACTIVITY', cityCode)
      .then(next => { if (seq === sequence.current) { setItems(next); setReady(true) } })
      .catch(() => { if (seq === sequence.current) { setItems([]); setReady(true) } })
  }, [cityCode])
  if (!ready || items.length === 0) return null
  return <View className='mkt-home-slot'>
    <Text className='mkt-home-heading'>活动钜惠</Text>
    <Text className='mkt-home-heading-sub'>限时优惠 · 先到先得</Text>
    <ScrollView className='mkt-home-scroll' scrollX enhanced showScrollbar={false}>
      {items.slice(0, 6).map(item => {
        const templateCode = activityTemplateCode(item.templateCode ?? item.tagCode)
        const template = activityTemplate(templateCode)
        return <View key={item.placementId}
          className={`mkt-home-card mkt-home-card-${templateCode}${item.tagName ? '' : ' mkt-home-card-no-badge'}`}
          onClick={() => openCampaign(item.campaignId)}>
          <View className='mkt-home-card-paper-left'>
            <Image className='mkt-home-card-paper-img' src={activityCardPaper} mode='scaleToFill' />
          </View>
          <View className='mkt-home-card-paper-right'>
            <Image className='mkt-home-card-paper-img mkt-home-card-paper-right-img' src={activityCardPaper} mode='scaleToFill' />
          </View>
          {item.coverUrl
            ? <Image className={`mkt-home-card-art${templateCode === 'generic' ? ' mkt-home-card-art-generic' : ''}`}
                src={item.coverUrl} mode='aspectFit' />
            : null}
          {item.tagName
            ? <View className='mkt-home-badge'>
                {template ? <Image className='mkt-home-badge-icon' src={template.badge} mode='aspectFit' /> : null}
                <Text className='mkt-home-badge-text'>{item.tagName}</Text>
              </View>
            : null}
          <Text className='mkt-home-card-title'>{item.title}</Text>
          {item.subtitle ? <Text className='mkt-home-card-sub'>{item.subtitle}</Text> : null}
        </View>
      })}
    </ScrollView>
  </View>
}

/** 服务 tab「商家钜惠」横排；商家促销 + 活动门店合并，空/失败时隐藏。 */
export function MerchantDealsRail({ cityCode }: { cityCode?: string }) {
  const [items, setItems] = useState<readonly StoreCard[]>([])
  const [ready, setReady] = useState(false)
  const sequence = useRef(0)
  useEffect(() => {
    const seq = ++sequence.current
    setReady(false)
    repository.merchantDeals(cityCode, 8)
      .then(next => { if (seq === sequence.current) { setItems(next); setReady(true) } })
      .catch(() => { if (seq === sequence.current) { setItems([]); setReady(true) } })
  }, [cityCode])
  if (!ready || items.length === 0) return null
  return <View className='mkt-deals'>
    <View className='mkt-section-head'>
      <Text className='mkt-section-title'>商家钜惠</Text>
      <Text className='mkt-section-link' onClick={() => Taro.navigateTo({ url: '/consumer/pages/marketing/campaigns' }).catch(() => undefined)}>限时特惠 ›</Text>
    </View>
    <ScrollView className='mkt-deals-scroll' scrollX enhanced showScrollbar={false}>
      {items.slice(0, 8).map(item => <View key={item.storeId} className='mkt-deal-card' onClick={() => openStore(item.storeId)}>
        <View className='mkt-deal-badge'><Text>{item.offerTag || (item.promotion?.level ? `${item.promotion.level}折` : '特惠')}</Text></View>
        <View className='mkt-deal-body'>
          <Text className='mkt-deal-name'>{item.storeName}</Text>
          <Text className='mkt-deal-offer'>{item.offerTitle || item.offerCopy || '门店优惠进行中'}</Text>
          {item.offerCopy ? <Text className='mkt-deal-sub'>{item.offerCopy}</Text> : null}
        </View>
        <Text className='mkt-deal-go'>去看看 ›</Text>
      </View>)}
    </ScrollView>
  </View>
}
