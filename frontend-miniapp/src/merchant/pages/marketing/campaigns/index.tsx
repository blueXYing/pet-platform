import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useCallback, useState, type CSSProperties } from 'react'
import { consumerApi, consumerSession } from '../../../../shared/consumer-runtime'
import { MerchantMarketingApi, type MyCampaign, type Overview } from '../../../marketing/api'
import { beijingDateText, campaignStatusPill, campaignSubtitleLine } from '../../../marketing/model'
import { MerchantTabBar } from '../../../components/tab-bar'
import navBack from '../../aftersale/assets/nav-back@3x.png'
import bannerIcon from '../assets/banner-icon@3x.png'
import iconCampaign from '../assets/icon-campaign@3x.png'
import iconPlus from '../assets/icon-plus@3x.png'
import '../index.css'
import './index.css'

// 节日营销列表（Figma 18:11090，HANDOFF §4.5.3）：banner + 概览计数（76号 §4.1：只展示活动数/
// 进行中/待审核；Figma「活动订单/活动营收」P1 不展示，按 41号 §8 偏差登记替换）。DRAFT/REJECTED
// 点击进编辑，其余状态只读进编辑页；状态胶囊按 status + 时间窗映射。
const api = new MerchantMarketingApi(consumerApi)

function useMerchant() {
  const context = consumerApi.scope.current
  if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) return null
  return { merchantId: context.merchantId, storeId: context.storeId }
}

export default function MerchantCampaignsPage() {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = {
    '--mmk-unit': `${info.windowWidth / 402}px`,
    '--mmk-status-top': `${info.statusBarHeight || 0}px`,
  } as CSSProperties
  const [merchant] = useState(() => useMerchant())
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [campaigns, setCampaigns] = useState<readonly MyCampaign[]>([])
  const [overview, setOverview] = useState<Overview | null>(null)
  const [overviewError, setOverviewError] = useState(false)
  const [notice, setNotice] = useState('')

  const load = useCallback(() => {
    if (!merchant) { setPhase('error'); return }
    setPhase('loading'); setNotice('')
    Promise.all([
      api.myCampaigns(merchant.merchantId, merchant.storeId),
      api.overview(merchant.merchantId, merchant.storeId).then(value => { setOverview(value); setOverviewError(false) }, () => { setOverview(null); setOverviewError(true) }),
    ]).then(([nextCampaigns]) => {
      setCampaigns(nextCampaigns); setPhase('ready')
    }).catch(() => setPhase('error'))
  }, [merchant])
  useDidShow(() => {
    void (async () => {
      try { await consumerSession.launch() } catch { /* load() renders the error state */ }
      load()
    })()
  })

  function openCampaign(campaign: MyCampaign) {
    Taro.navigateTo({ url: `/merchant/pages/marketing/campaigns/edit?campaignId=${campaign.campaignId}` })
      .catch(() => { void Taro.showToast({ title: '页面跳转失败，请重试', icon: 'none' }) })
  }
  function createCampaign() {
    Taro.navigateTo({ url: '/merchant/pages/marketing/campaigns/edit' })
      .catch(() => { void Taro.showToast({ title: '页面跳转失败，请重试', icon: 'none' }) })
  }
  function back() {
    // P-03 追缉（2026-11-12）：商家域返回不用 navigateBack（隐藏页树可能已被 dispose）。
    Taro.reLaunch({ url: '/merchant/pages/marketing/index' }).catch(() => {})
  }

  const stat = (value: number | undefined): string => overviewError || value === undefined ? '—' : String(value)
  const totalLabel = overview && !overviewError ? String(overview.totalCampaignCount) : String(campaigns.length)

  return <View className='mmk-page' style={style}>
    <View className='mmk-status' />
    <View className='mmk-header'>
      <Button className='mmk-back' ariaLabel='返回' onClick={back}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mmk-title'>节日营销</Text>
    </View>
    {!merchant && <View className='mmk-empty'><Text>请从商家工作台进入营销工具</Text></View>}
    {merchant && phase === 'loading' && <View className='mmk-empty'><Text>正在加载活动…</Text></View>}
    {merchant && phase === 'error' && <View className='mmk-empty'>
      <Text>活动加载失败</Text><Button className='mmk-primary' onClick={load}>重新加载</Button>
    </View>}
    {merchant && phase === 'ready' && <View className='mmk-campaigns'>
      <View className='mmk-banner'>
        <View className='mmk-banner-icon'><Image src={bannerIcon} mode='aspectFit' /></View>
        <View className='mmk-banner-main'>
          <Text className='mmk-banner-title'>节日营销</Text>
          <Text className='mmk-banner-sub'>节日主题套餐，蹭热点促销量</Text>
        </View>
      </View>
      <View className='mmk-stats'>
        <View className='mmk-stat'><Text className='mmk-stat-value'>{stat(overview?.totalCampaignCount)}</Text><Text className='mmk-stat-label'>活动数</Text></View>
        <View className='mmk-stat'><Text className='mmk-stat-value'>{stat(overview?.activeCampaignCount)}</Text><Text className='mmk-stat-label'>进行中</Text></View>
        <View className='mmk-stat'><Text className='mmk-stat-value'>{stat(overview?.pendingReviewCount)}</Text><Text className='mmk-stat-label'>待审核</Text></View>
      </View>
      <View className='mmk-listbar'>
        <Text className='mmk-listbar-count'>共 {totalLabel} 个活动</Text>
        <View className='mmk-listbar-create' id='mmk-create-campaign' onClick={createCampaign}>
          <Image className='mmk-listbar-plus' src={iconPlus} mode='aspectFit' />
          <Text>创建活动</Text>
        </View>
      </View>
      {campaigns.length === 0 && <View className='mmk-empty'><Text>还没有节日活动，点击「创建活动」发起第一个活动</Text></View>}
      <View className='mmk-cards'>
        {campaigns.map(campaign => {
          const pill = campaignStatusPill(campaign.status, Date.now(), campaign.startAt, campaign.endAt)
          const subtitle = campaignSubtitleLine(campaign)
          return <View key={campaign.campaignId} id={`mmk-campaign-${campaign.campaignId}`} className='mmk-campaign-card' onClick={() => openCampaign(campaign)}>
            <View className='mmk-campaign-cover'>
              {campaign.coverUrl
                ? <Image className='mmk-campaign-cover-image' src={campaign.coverUrl} mode='aspectFill' />
                : <Image className='mmk-campaign-cover-placeholder' src={iconCampaign} mode='aspectFit' />}
            </View>
            <View className='mmk-campaign-main'>
              <View className='mmk-campaign-head'>
                <Text className='mmk-campaign-title'>{campaign.name}</Text>
                <Text className={`mmk-pill mmk-pill-${pill.tone}`}>{pill.text}</Text>
              </View>
              {subtitle ? <Text className='mmk-campaign-sub'>{subtitle}</Text> : null}
              <Text className='mmk-campaign-meta'>{beijingDateText(campaign.startAt)} 至 {beijingDateText(campaign.endAt)}</Text>
              {campaign.rejectReason ? <Text className='mmk-reject'>驳回原因：{campaign.rejectReason}</Text> : null}
            </View>
          </View>
        })}
      </View>
      {notice ? <View className='mmk-notice'><Text>{notice}</Text></View> : null}
    </View>}
    <View className='mmk-tab-space' />
    <MerchantTabBar active='home' unit={unit} />
  </View>
}
