import { Image, ScrollView, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useEffect, useRef, useState } from 'react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { MarketingRepository, type StoreCard } from '../../marketing/model'
import { dealBadgeText, dealLines } from './view-model'
import headingGift from './assets/heading-gift.svg'
import linkChevron from './assets/link-chevron.svg'

// 服务 tab「商家钜惠」横排（F-C-02，110:480 SECTION-351；契约 71 §3.4 + 74 §3.5）。
// 数据走 C 端 merchant-deals（匿名读，与既有 MerchantDealsRail 同源）；71 v0.3 的
// imageUrl/imageUrlExpiresAt 解码已在 marketing/model.ts。无图降级为中性底版 +
// 占位头像（不虚构商家图）；空/失败整块隐藏（契约 71 口径，不阻塞页面其余区块）。
// 坑位口径：卡片点击进既有门店详情（预览通道不适用本 tab 页，preview 固定 0）。
const repository = new MarketingRepository(consumerApi)

function openStore(storeId: string) {
  Taro.navigateTo({ url: `/consumer/pages/store-services/index?preview=0&storeId=${encodeURIComponent(storeId)}` })
    .catch(() => undefined)
}

function openCampaigns() {
  Taro.navigateTo({ url: '/consumer/pages/marketing/campaigns' }).catch(() => undefined)
}

export function DealsRail({ cityCode }: { cityCode?: string }) {
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
  return <View className='svc2-deals'>
    <View className='svc2-sec-head'>
      <View className='svc2-sec-title-wrap'>
        <Image className='svc2-sec-gift' src={headingGift} mode='aspectFit' />
        <Text className='svc2-sec-title'>商家钜惠</Text>
      </View>
      <View className='svc2-sec-link' onClick={openCampaigns}>
        <Text className='svc2-sec-link-text'>限时特惠</Text>
        <Image className='svc2-sec-chevron' src={linkChevron} mode='aspectFit' />
      </View>
    </View>
    <ScrollView className='svc2-deals-scroll' scrollX enhanced showScrollbar={false}>
      {items.slice(0, 8).map(item => {
        const badge = dealBadgeText(item)
        const lines = dealLines(item)
        return <View key={item.storeId} className='svc2-deal-card' onClick={() => openStore(item.storeId)}>
          <View className='svc2-deal-media'>
            {item.imageUrl
              ? <Image className='svc2-deal-img' src={item.imageUrl} mode='aspectFill' />
              : <View className='svc2-deal-img svc2-deal-img-placeholder' />}
            {badge && <View className='svc2-deal-badge'><Text className='svc2-deal-badge-text'>{badge}</Text></View>}
          </View>
          <View className='svc2-deal-body'>
            <View className='svc2-deal-store'>
              {item.imageUrl
                ? <Image className='svc2-deal-avatar' src={item.imageUrl} mode='aspectFill' />
                : <View className='svc2-deal-avatar svc2-deal-avatar-placeholder' />}
              <Text className='svc2-deal-name'>{item.storeName}</Text>
            </View>
            {lines.primary && <Text className='svc2-deal-offer'>{lines.primary}</Text>}
            {lines.secondary && <Text className='svc2-deal-copy'>{lines.secondary}</Text>}
          </View>
        </View>
      })}
    </ScrollView>
  </View>
}
