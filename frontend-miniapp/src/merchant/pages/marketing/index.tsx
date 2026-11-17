import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useCallback, useState, type CSSProperties } from 'react'
import { consumerApi, consumerSession } from '../../../shared/consumer-runtime'
import { MerchantMarketingApi, type Overview } from '../../marketing/api'
import { MerchantTabBar } from '../../components/tab-bar'
import navBack from '../aftersale/assets/nav-back@3x.png'
import iconToolCoupon from './assets/icon-tool-coupon@3x.png'
import iconToolMemberCard from './assets/icon-tool-member-card@3x.png'
import iconToolFlashSale from './assets/icon-tool-flash-sale@3x.png'
import iconToolReferral from './assets/icon-tool-referral@3x.png'
import iconToolGift from './assets/icon-tool-gift@3x.png'
import iconToolFestival from './assets/icon-tool-festival@3x.png'
import iconCampaign from './assets/icon-campaign@3x.png'
import './index.css'

// 营销工具 hub（Figma 12:7982，41号 §3.3 / HANDOFF §4.5.3）：P1 仅「节日营销」可用，其余工具卡
// 「筹备中/暂不开放」不导航；顶部概览卡只显示 4 个可证明计数（76号 §4.1，失败显示「—」，
// 不展示订单/营收/转化）；平台活动报名保留独立入口。Figma「本月营销效果/带来订单/提升幅度」
// 与营收统计为 P1 不可展示项（41号 §8），已按设计偏差登记替换为本页概览计数。
const api = new MerchantMarketingApi(consumerApi)

type MarketingTool = Readonly<{
  id: string; title: string; hint: string; icon: string; iconClass: string
  available: boolean; badge?: string; url?: string
}>
const MARKETING_TOOLS: readonly MarketingTool[] = [
  { id: 'mmk-tool-coupon', title: '优惠券', hint: '新客立减、满减券', icon: iconToolCoupon, iconClass: 'mmk-tool-icon-coupon', available: false, badge: '筹备中' },
  { id: 'mmk-tool-member-card', title: '会员卡', hint: '储值卡、次卡', icon: iconToolMemberCard, iconClass: 'mmk-tool-icon-member-card', available: false, badge: '筹备中' },
  { id: 'mmk-tool-flash-sale', title: '限时秒杀', hint: '爆款服务抢购', icon: iconToolFlashSale, iconClass: 'mmk-tool-icon-flash-sale', available: false, badge: '筹备中' },
  { id: 'mmk-tool-referral', title: '裂变拉新', hint: '老带新返券', icon: iconToolReferral, iconClass: 'mmk-tool-icon-referral', available: false, badge: '筹备中' },
  { id: 'mmk-tool-gift', title: '赠品活动', hint: '满额送礼', icon: iconToolGift, iconClass: 'mmk-tool-icon-gift', available: false, badge: '暂不开放' },
  { id: 'mmk-tool-festival', title: '节日营销', hint: '节日主题套餐', icon: iconToolFestival, iconClass: 'mmk-tool-icon-festival', available: true, url: '/merchant/pages/marketing/campaigns/index' },
]

function useMerchant() {
  const context = consumerApi.scope.current
  if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) return null
  return { merchantId: context.merchantId, storeId: context.storeId }
}

export default function MerchantMarketingHubPage() {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = {
    '--mmk-unit': `${info.windowWidth / 402}px`,
    '--mmk-status-top': `${info.statusBarHeight || 0}px`,
  } as CSSProperties
  const [merchant] = useState(() => useMerchant())
  const [overview, setOverview] = useState<Overview | null>(null)
  const [overviewPhase, setOverviewPhase] = useState<'loading' | 'ready' | 'error'>('loading')

  const load = useCallback(() => {
    if (!merchant) { setOverviewPhase('error'); return }
    setOverviewPhase('loading')
    api.overview(merchant.merchantId, merchant.storeId)
      .then(value => { setOverview(value); setOverviewPhase('ready') })
      .catch(() => { setOverview(null); setOverviewPhase('error') }) // 76号 §4.1：读取失败显示「—」
  }, [merchant])
  useDidShow(() => {
    void (async () => {
      try { await consumerSession.launch() } catch { /* load() renders the error state */ }
      load()
    })()
  })

  function openTool(tool: MarketingTool) {
    if (!tool.available || !tool.url) return
    Taro.navigateTo({ url: tool.url }).catch(() => { void Taro.showToast({ title: '页面跳转失败，请重试', icon: 'none' }) })
  }
  function openPlatform() {
    Taro.navigateTo({ url: '/merchant/pages/marketing/platform/index' }).catch(() => { void Taro.showToast({ title: '页面跳转失败，请重试', icon: 'none' }) })
  }
  function back() {
    // P-03 追缉（2026-11-12）：商家域返回不用 navigateBack（隐藏页树可能已被 dispose）。
    Taro.reLaunch({ url: '/merchant/pages/workspace/index' }).catch(() => {})
  }

  const count = (value: number | undefined): string =>
    overviewPhase !== 'ready' || value === undefined ? '—' : String(value)

  return <View className='mmk-page' style={style}>
    <View className='mmk-status' />
    <View className='mmk-header'>
      <Button className='mmk-back' ariaLabel='返回' onClick={back}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mmk-title'>营销工具</Text>
    </View>
    {!merchant && <View className='mmk-empty'><Text>请从商家工作台进入营销工具</Text></View>}
    {merchant && <View className='mmk-hub'>
      <View className='mmk-overview' id='mmk-overview'>
        <View className='mmk-overview-row'>
          <View className='mmk-overview-item'><Text className='mmk-overview-value'>{count(overview?.totalCampaignCount)}</Text><Text className='mmk-overview-label'>活动总数</Text></View>
          <View className='mmk-overview-item'><Text className='mmk-overview-value'>{count(overview?.activeCampaignCount)}</Text><Text className='mmk-overview-label'>进行中</Text></View>
        </View>
        <View className='mmk-overview-row'>
          <View className='mmk-overview-item'><Text className='mmk-overview-value'>{count(overview?.pendingReviewCount)}</Text><Text className='mmk-overview-label'>待审核</Text></View>
          <View className='mmk-overview-item'><Text className='mmk-overview-value'>{count(overview?.enrolledCampaignCount)}</Text><Text className='mmk-overview-label'>已报名平台活动</Text></View>
        </View>
        {overviewPhase === 'error' && <Text className='mmk-overview-error'>概览读取失败，稍后重试</Text>}
      </View>
      <View className='mmk-tools'>
        {MARKETING_TOOLS.map(tool => <View key={tool.id} id={tool.id}
          className={tool.available ? 'mmk-tool' : 'mmk-tool mmk-tool-disabled'}
          onClick={() => openTool(tool)}>
          <View className={`mmk-tool-icon ${tool.iconClass}`}><Image src={tool.icon} mode='aspectFit' /></View>
          <View className='mmk-tool-main'>
            <Text className='mmk-tool-title'>{tool.title}</Text>
            <Text className='mmk-tool-hint'>{tool.hint}</Text>
          </View>
          {!tool.available && tool.badge ? <Text className='mmk-tool-badge'>{tool.badge}</Text> : null}
        </View>)}
      </View>
      <View className='mmk-platform' id='mmk-platform-entry' onClick={openPlatform}>
        <View className='mmk-tool-icon mmk-platform-icon'><Image src={iconCampaign} mode='aspectFit' /></View>
        <View className='mmk-tool-main'>
          <Text className='mmk-tool-title'>平台活动</Text>
          <Text className='mmk-tool-hint'>平台活动报名 / 我的报名</Text>
        </View>
        <Text className='mmk-platform-chevron'>›</Text>
      </View>
      <Text className='mmk-hub-tail'>仅「节日营销」已开放；其余工具按平台节奏陆续开放。</Text>
    </View>}
    <View className='mmk-tab-space' />
    <MerchantTabBar active='home' unit={unit} />
  </View>
}
