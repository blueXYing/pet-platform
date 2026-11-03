import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { ApiError } from '../../../shared/request'
import { consumerApi } from '../../../shared/consumer-runtime'
import { readSelectedCity } from '../../city/model'
import { SubpageHeader } from '../../components/navigation/subpage-header'
import {
  MarketingRepository, type CampaignDetail, type CouponCampaignView,
  type MarketingTag, type PageComponent, type StoreCard,
} from '../../marketing/model'
import campaignBadge from '../../assets/marketing/campaign-badge.svg'
import campaignChevron from '../../assets/marketing/campaign-chevron.svg'
import storeBanner from '../store-services/assets/690-6908-store-banner@2x.png'
import storeAvatar from '../store-services/assets/690-6709-store-avatar@2x.png'
import locationIcon from '../store-services/assets/690-6750-icon-location@2x.png'
import '../../marketing/marketing.css'
import './campaign-page.css'

const repository = new MarketingRepository(consumerApi)

const DEFAULT_BANNER_BADGE = '限时优惠 · 先到先得'
const DEFAULT_STORE_TITLE = '参与活动商家'

const isId = (value: unknown): value is string =>
  typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n
const str = (value: unknown, max = 500): string | null =>
  typeof value === 'string' && value.length > 0 && Array.from(value).length <= max ? value : null
const strings = (value: unknown, max = 10): string[] =>
  Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string' && Array.from(item).length <= 500).slice(0, max) : []

function openStore(storeId: string) {
  Taro.navigateTo({ url: `/consumer/pages/store-services/index?preview=0&storeId=${encodeURIComponent(storeId)}` })
    .catch(() => Taro.showToast({ title: '页面打开失败，请重试', icon: 'none' }))
}

function componentOf(detail: CampaignDetail, type: string): PageComponent | undefined {
  return detail.schema?.components.find(component => component.type === type)
}

/**
 * 优惠活动（Figma 首页-优惠活动 96:3328）：奶油标题栏 + 活动分类胶囊 + 活动横幅 +
 * 参与活动商家卡片。活动/商家/优惠全部来自契约 71 的 campaign detail，分类来自 /marketing/tags；
 * 门店照片/头像在当前 C 端门店读契约中尚无公开字段，使用设计系统门店占位图，不伪造门店照片。
 */
export default function MarketingCampaignPage() {
  const route = useRouter()
  const campaignId = typeof route.params.campaignId === 'string' ? route.params.campaignId : ''
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = {
    '--mkt-unit': `${unit}px`,
    '--mkt-top': `${info.statusBarHeight || 0}px`,
  } as CSSProperties
  const [detail, setDetail] = useState<CampaignDetail | null>(null)
  const [tags, setTags] = useState<readonly MarketingTag[]>([])
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [notice, setNotice] = useState('')
  const [claiming, setClaiming] = useState(false)
  const [switchingTag, setSwitchingTag] = useState<string | null>(null)
  const sequence = useRef(0)

  const load = useCallback(() => {
    if (!isId(campaignId)) { setPhase('error'); return }
    const seq = ++sequence.current
    setPhase('loading')
    setNotice('')
    repository.campaignDetail(campaignId)
      .then(next => {
        if (seq !== sequence.current) return
        setDetail(next)
        setPhase('ready')
      })
      .catch(() => {
        if (seq === sequence.current) setPhase('error')
      })
  }, [campaignId])

  useEffect(() => { load() }, [load])

  useEffect(() => {
    const cityCode = readSelectedCity().cityCode
    repository.tags(cityCode).then(next => setTags(next)).catch(() => setTags([]))
  }, [])

  async function claim(coupon: CouponCampaignView) {
    if (claiming) return
    if (coupon.claimed) { setNotice('您已领取过该活动券'); return }
    if (!coupon.canClaim) { setNotice('当前暂不可领取'); return }
    setClaiming(true)
    setNotice('')
    try {
      const receipt = await repository.claim(coupon.campaignId)
      setDetail(current => current === null ? current : {
        ...current,
        couponCampaign: current.couponCampaign === null ? null : { ...current.couponCampaign, claimed: true, canClaim: false, reason: 'CLAIMED' },
      })
      const amount = receipt.amountOff ? ` ¥${receipt.amountOff}` : ''
      await Taro.showToast({ title: `领取成功${amount}`, icon: 'none' })
    } catch (error) {
      if ((error instanceof ApiError && error.statusCode === 401) || (error instanceof Error && error.message === 'LOGIN_REQUIRED')) setNotice('请先登录后领取')
      else if (error instanceof ApiError && error.code === 'COUPON_CLAIMED') setNotice('您已领取过该活动券')
      else if (error instanceof ApiError && error.code === 'COUPON_CAMPAIGN_SOLD_OUT') setNotice('券已抢完，下次早点来')
      else setNotice('领取失败，请稍后重试')
    } finally {
      setClaiming(false)
    }
  }

  function back() {
    Taro.navigateBack().catch(() => Taro.switchTab({ url: '/consumer/pages/home/index' }))
  }

  function pickTag(tagCode?: string) {
    if (switchingTag) return
    if (tagCode === undefined) {
      Taro.navigateTo({ url: '/consumer/pages/marketing/campaigns' })
        .catch(() => Taro.showToast({ title: '页面打开失败，请重试', icon: 'none' }))
      return
    }
    if (detail?.campaign.tagCode === tagCode) return
    setSwitchingTag(tagCode)
    repository.campaigns(tagCode, readSelectedCity().cityCode, 1, 1)
      .then(page => {
        const next = page.items[0]
        if (!next) {
          void Taro.showToast({ title: '该分类暂无进行中活动', icon: 'none' })
          return
        }
        if (next.campaignId === campaignId) return
        return Taro.redirectTo({
          url: `/consumer/pages/marketing/campaign?campaignId=${encodeURIComponent(next.campaignId)}`,
        }).catch(() => Taro.showToast({ title: '活动切换失败，请重试', icon: 'none' }))
      })
      .catch(() => Taro.showToast({ title: '活动切换失败，请重试', icon: 'none' }))
      .finally(() => setSwitchingTag(null))
  }

  const campaign = detail?.campaign
  const activeTag = campaign?.tagCode ?? undefined
  const chips: Array<{ code?: string; name: string }> = [
    { name: '全部' },
    ...tags.map(tag => ({ code: tag.tagCode, name: tag.tagName })),
  ]
  if (activeTag && !chips.some(chip => chip.code === activeTag)) {
    chips.push({ code: activeTag, name: campaign?.tagName ?? activeTag })
  }

  return <View className='mkt-camp-page' style={style}>
    <SubpageHeader
      title='优惠活动'
      onBack={back}
      height={113}
      bottom={<ScrollView className='mkt-camp-chips' scrollX enhanced showScrollbar={false}>
        {chips.map((chip, index) => <View
          key={chip.code ?? `all-${index}`}
          className={`mkt-camp-chip${chip.code === activeTag ? ' mkt-camp-chip-active' : ''}${switchingTag === chip.code ? ' mkt-camp-chip-busy' : ''}`}
          onClick={() => pickTag(chip.code)}>
          <Text className='mkt-camp-chip-text'>{chip.name}</Text>
        </View>)}
      </ScrollView>}
    />

    <View className='mkt-camp-body'>
      {phase === 'loading' && <View className='mkt-camp-state'><Text>正在加载活动…</Text></View>}
      {phase === 'error' && <View className='mkt-camp-state'>
        <Text>活动不存在或已结束</Text>
        <View className='mkt-camp-retry' onClick={load}><Text>重新加载</Text></View>
      </View>}
      {phase === 'ready' && detail && <CampaignContent detail={detail} claiming={claiming}
        notice={notice} onClaim={claim} onStore={openStore} />}
    </View>
  </View>
}

function CampaignContent({ detail, claiming, notice, onClaim, onStore }: {
  detail: CampaignDetail
  claiming: boolean
  notice: string
  onClaim: (coupon: CouponCampaignView) => void
  onStore: (storeId: string) => void
}) {
  const hero = componentOf(detail, 'hero')
  const storeList = componentOf(detail, 'storeList')
  const rich = componentOf(detail, 'richText')
  const couponComponent = componentOf(detail, 'coupon')
  const couponButtonText = str(couponComponent?.buttonText, 20)
  const cover = str(hero?.imageUrl, 512) ?? detail.campaign.coverUrl
  const title = str(hero?.title, 100) ?? detail.campaign.title
  const subtitle = str(hero?.subtitle, 200) ?? detail.campaign.subtitle
  const badge = str(hero?.tag, 32) ?? detail.campaign.tagName ?? DEFAULT_BANNER_BADGE
  const storeTitle = str(storeList?.title, 100) ?? DEFAULT_STORE_TITLE
  const richTitle = str(rich?.title, 100)
  const richParagraphs = strings(rich?.paragraphs, 10)

  return <>
    <View className='mkt-camp-banner-wrap'>
      <View className='mkt-camp-banner'>
        {cover
          ? <Image className='mkt-camp-banner-img' src={cover} mode='aspectFill' />
          : <View className='mkt-camp-banner-img mkt-camp-banner-fallback' />}
        <View className='mkt-camp-banner-shade' />
        <View className='mkt-camp-banner-body'>
          <View className='mkt-camp-badge'>
            <Image className='mkt-camp-badge-icon' src={campaignBadge} mode='aspectFit' />
            <Text className='mkt-camp-badge-text'>{badge}</Text>
          </View>
          <Text className='mkt-camp-banner-title'>{title}</Text>
          {subtitle ? <Text className='mkt-camp-banner-sub'>{subtitle}</Text> : null}
        </View>
      </View>
    </View>

    {notice ? <View className='mkt-camp-notice'><Text>{notice}</Text></View> : null}
    {detail.couponCampaign
      ? <CampaignCoupon coupon={detail.couponCampaign} buttonText={couponButtonText}
          claiming={claiming} onClaim={onClaim} />
      : null}

    <View className={`mkt-camp-store-head${detail.couponCampaign ? ' mkt-camp-store-head-coupon' : ''}`}>
      <Text className='mkt-camp-store-title'>{storeTitle}</Text>
      <Text className='mkt-camp-store-count'>{detail.stores.length} 家</Text>
    </View>
    {detail.stores.length === 0
      ? <View className='mkt-camp-store-empty'><Text>暂无参与活动商家</Text></View>
      : <View className='mkt-camp-store-list'>
        {detail.stores.map(store => <CampaignStoreCard key={store.storeId} store={store} onOpen={onStore} />)}
      </View>}

    {(richTitle || richParagraphs.length > 0) && <View className='mkt-camp-note'>
      {richTitle ? <Text className='mkt-camp-note-title'>{richTitle}</Text> : null}
      {richParagraphs.map((paragraph, index) => <Text key={index} className='mkt-camp-note-p'>{paragraph}</Text>)}
    </View>}

    <View className='mkt-camp-tail'><Text>活动内容与优惠以页面展示及门店实际服务为准</Text></View>
  </>
}

function CampaignStoreCard({ store, onOpen }: { store: StoreCard; onOpen: (storeId: string) => void }) {
  return <View className='mkt-camp-store' onClick={() => onOpen(store.storeId)}>
    <View className='mkt-camp-store-cover'>
      <Image className='mkt-camp-store-cover-img' src={storeBanner} mode='aspectFill' />
      {store.offerTag ? <View className='mkt-camp-offer-badge'><Text className='mkt-camp-offer-badge-text'>{store.offerTag}</Text></View> : null}
    </View>
    <View className='mkt-camp-store-body'>
      <View className='mkt-camp-store-row'>
        <Image className='mkt-camp-store-avatar' src={storeAvatar} mode='aspectFill' />
        <View className='mkt-camp-store-main'>
          <View className='mkt-camp-store-name-row'>
            <Text className='mkt-camp-store-name'>{store.storeName}</Text>
            <View className='mkt-camp-store-pill'><Text className='mkt-camp-store-pill-text'>活动商家</Text></View>
          </View>
          <View className='mkt-camp-store-meta'>
            <Image className='mkt-camp-store-meta-icon' src={locationIcon} mode='aspectFit' />
            <Text className='mkt-camp-store-address'>{store.address || store.merchantName || '活动门店'}</Text>
          </View>
        </View>
        <View className='mkt-camp-store-go'>
          <Text className='mkt-camp-store-go-text'>去看看</Text>
          <Image className='mkt-camp-store-chevron' src={campaignChevron} mode='aspectFit' />
        </View>
      </View>
      {(store.offerTitle || store.offerCopy) && <View className='mkt-camp-store-offer'>
        {store.offerTitle ? <Text className='mkt-camp-store-offer-title'>{store.offerTitle}</Text> : null}
        {store.offerCopy ? <Text className='mkt-camp-store-offer-copy'>{store.offerCopy}</Text> : null}
      </View>}
    </View>
  </View>
}

function CampaignCoupon({ coupon, buttonText, claiming, onClaim }: {
  coupon: CouponCampaignView
  buttonText: string | null
  claiming: boolean
  onClaim: (coupon: CouponCampaignView) => void
}) {
  const label = coupon.claimed ? '已领取' : coupon.canClaim ? (buttonText ?? '立即领取') : (coupon.reason === 'SOLD_OUT' ? '已抢完' : '暂不可领')
  return <View className='mkt-camp-coupon'>
    <View className='mkt-camp-coupon-main'>
      <Text className='mkt-camp-coupon-amount'>{coupon.amountOff ? `¥${coupon.amountOff}` : coupon.typeLabel || '活动券'}</Text>
      <Text className='mkt-camp-coupon-meta'>{coupon.thresholdAmount ? `满 ¥${coupon.thresholdAmount} 可用` : '无门槛'} · {coupon.typeLabel ?? '平台券'}</Text>
    </View>
    <Button className='mkt-camp-coupon-btn' disabled={claiming || coupon.claimed || !coupon.canClaim}
      onClick={() => onClaim(coupon)}>{claiming ? '领取中…' : label}</Button>
  </View>
}
