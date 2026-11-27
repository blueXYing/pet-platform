import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useCallback, useState, type CSSProperties } from 'react'
import { consumerApi, consumerSession } from '../../../../shared/consumer-runtime'
import { MerchantCouponApi, type CouponCampaignRecord, type CouponOverview } from '../../../marketing/coupon-api'
import { beijingDateText, couponMessage, couponStatusPill, couponSubline, redemptionRatePercent } from '../../../marketing/coupon-model'
import { MerchantTabBar } from '../../../components/tab-bar'
import navBack from '../../aftersale/assets/nav-back@3x.png'
import iconToolCoupon from '../assets/icon-tool-coupon@3x.png'
import iconPlus from '../assets/icon-plus@3x.png'
import '../index.css'
import './index.css'

// 商家自建优惠券列表（Figma 12:8436，79号 §3 / 41号 §5 / CCR-MKT-MERCHANT-COUPON-001）：
// 顶部说明 banner + 三统计（本月发券/核销率/带动订单，全部来自 overview 接口，失败显示「—」
// 不伪造数字）+「共 N 个活动」与「创建活动」入口 + 分页活动卡（名称/副文案/状态胶囊/有效期至，
// 已驳回显示驳回原因）。底部五 tab 为设计残留（M 端导航），与 P2 一律 MerchantTabBar home。
// 登记偏差：Figma 无门槛卡副文案「全场服务通用」按任务线形状改「本店服务通用」；设计第三卡
// 「YYYY-MM-DD 截止」统一为「有效期至 YYYY-MM-DD」。
const api = new MerchantCouponApi(consumerApi)
const PAGE_SIZE = 20
const DASH = '—'

function useMerchant() {
  const context = consumerApi.scope.current
  if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) return null
  return { merchantId: context.merchantId, storeId: context.storeId }
}

export default function MerchantCouponIndexPage() {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = {
    '--mmk-unit': `${info.windowWidth / 402}px`,
    '--mmk-status-top': `${info.statusBarHeight || 0}px`,
  } as CSSProperties
  const [merchant] = useState(() => useMerchant())
  const [pagePhase, setPagePhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [overview, setOverview] = useState<CouponOverview | null>(null)
  const [statsPhase, setStatsPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [records, setRecords] = useState<readonly CouponCampaignRecord[]>([])
  const [total, setTotal] = useState(0)
  const [listPhase, setListPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)

  const loadOverview = useCallback(async () => {
    if (!merchant) return
    setStatsPhase('loading')
    try {
      const value = await api.overview(merchant.merchantId, merchant.storeId)
      setOverview(value); setStatsPhase('ready')
    } catch {
      setOverview(null); setStatsPhase('error') // 79号 §3：读取失败显示「—」，不伪造数字
    }
  }, [merchant])

  const loadFirstPage = useCallback(async () => {
    if (!merchant) return
    setListPhase('loading')
    try {
      const page = await api.list(merchant.merchantId, merchant.storeId, 1, PAGE_SIZE)
      setRecords(page.items); setTotal(page.total); setListPhase('ready')
    } catch {
      setRecords([]); setTotal(0); setListPhase('error')
    }
  }, [merchant])

  useDidShow(() => {
    // 进入/返回本页都重读：从编辑页回来后立即呈现最新活动（数字全部来自 API）。
    void (async () => {
      try { await consumerSession.launch() } catch { /* load renders the error state */ }
      if (!merchant) { setPagePhase('error'); return }
      setPagePhase('loading'); setNotice('')
      await Promise.all([loadOverview(), loadFirstPage()])
      setPagePhase('ready')
    })()
  })

  async function loadMore() {
    if (!merchant || busy || listPhase !== 'ready' || records.length >= total) return
    setBusy(true); setNotice('')
    try {
      const page = await api.list(merchant.merchantId, merchant.storeId, Math.floor(records.length / PAGE_SIZE) + 1, PAGE_SIZE)
      setRecords(current => [...current, ...page.items])
      setTotal(current => Math.max(current, page.total))
    } catch (error) { setNotice(couponMessage(error)) } finally { setBusy(false) }
  }

  function createCoupon() {
    Taro.navigateTo({ url: '/merchant/pages/marketing/coupon/edit' })
      .catch(() => { void Taro.showToast({ title: '页面跳转失败，请重试', icon: 'none' }) })
  }
  function editCoupon(record: CouponCampaignRecord) {
    Taro.navigateTo({ url: `/merchant/pages/marketing/coupon/edit?id=${record.campaignId}` })
      .catch(() => { void Taro.showToast({ title: '页面跳转失败，请重试', icon: 'none' }) })
  }
  function back() {
    // P-03 追缉（2026-11-12）：商家域返回不用 navigateBack（隐藏页树可能已被 dispose）。
    Taro.reLaunch({ url: '/merchant/pages/marketing/index' }).catch(() => {})
  }

  const statValue = (render: () => string): string => statsPhase === 'ready' && overview ? render() : DASH
  const hasMore = listPhase === 'ready' && records.length < total

  return <View className='mmk-page' style={style}>
    <View className='mmk-status' />
    <View className='mmk-header'>
      <Button className='mmk-back' ariaLabel='返回' onClick={back}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mmk-title'>优惠券</Text>
    </View>
    {!merchant && <View className='mmk-empty'><Text>请从商家工作台进入营销工具</Text></View>}
    {merchant && pagePhase === 'loading' && <View className='mmk-empty'><Text>正在加载优惠券活动…</Text></View>}
    {merchant && pagePhase === 'error' && <View className='mmk-empty'>
      <Text>优惠券加载失败</Text>
      <Button className='mmk-primary' onClick={() => Taro.reLaunch({ url: '/merchant/pages/marketing/coupon/index' }).catch(() => {})}>重新加载</Button>
    </View>}
    {merchant && pagePhase === 'ready' && <View className='mmc-body'>
      <View className='mmk-banner'>
        <View className='mmk-banner-icon'><Image src={iconToolCoupon} mode='aspectFit' /></View>
        <View className='mmk-banner-main'>
          <Text className='mmk-banner-title'>优惠券</Text>
          <Text className='mmk-banner-sub'>新客立减、满减券，快速提升下单转化</Text>
        </View>
      </View>
      <View className='mmk-stats' id='mmc-stats'>
        <View className='mmk-stat'>
          <Text className='mmk-stat-value'>{statValue(() => `${overview!.monthIssuedCount} 张`)}</Text>
          <Text className='mmk-stat-label'>本月发券</Text>
        </View>
        <View className='mmk-stat'>
          <Text className='mmk-stat-value'>{statValue(() => `${redemptionRatePercent(overview!.redemptionRate) ?? DASH}`)}</Text>
          <Text className='mmk-stat-label'>核销率</Text>
        </View>
        <View className='mmk-stat'>
          <Text className='mmk-stat-value'>{statValue(() => `${overview!.drivenOrderCount} 笔`)}</Text>
          <Text className='mmk-stat-label'>带动订单</Text>
        </View>
      </View>
      {statsPhase === 'error' && <Text className='mmc-stats-error'>统计读取失败，稍后重试</Text>}
      <View className='mmc-listbar'>
        <Text className='mmc-listbar-count'>{listPhase === 'ready' ? `共 ${total} 个活动` : '共 — 个活动'}</Text>
        <View className='mmc-listbar-create' id='mmc-create' onClick={createCoupon}>
          <Image className='mmc-listbar-plus' src={iconPlus} mode='aspectFit' />
          <Text>创建活动</Text>
        </View>
      </View>
      {listPhase === 'loading' && <View className='mmk-empty'><Text>正在加载活动…</Text></View>}
      {listPhase === 'error' && <View className='mmk-empty' role='status'>
        <Text>活动加载失败</Text>
        <Button className='mmk-primary' onClick={() => void loadFirstPage()}>重新加载</Button>
      </View>}
      {listPhase === 'ready' && records.length === 0 && <View className='mmk-empty'>
        <Text>还没有优惠券活动，点击「创建活动」发起第一张券</Text>
      </View>}
      {listPhase === 'ready' && records.length > 0 && <View className='mmc-cards'>
        {records.map(record => {
          const pill = couponStatusPill(record.status)
          return <View key={record.campaignId} id={`mmc-card-${record.campaignId}`} className='mmc-card'
            onClick={() => editCoupon(record)}>
            <View className='mmc-card-head'>
              <Text className='mmc-card-title'>{record.name}</Text>
              <Text className={`mmc-pill mmc-pill-${pill.tone}`}>{pill.text}</Text>
            </View>
            <Text className='mmc-card-sub'>{couponSubline(record)}</Text>
            <Text className='mmc-card-window'>有效期至 {beijingDateText(record.endAt)}</Text>
            {record.status === 'REJECTED' && record.rejectReason
              ? <Text className='mmc-card-reject'>驳回原因：{record.rejectReason}</Text> : null}
          </View>
        })}
        {hasMore && <View className='mmc-load-more' id='mmc-load-more' onClick={() => void loadMore()}>
          <Text>{busy ? '正在加载…' : '加载更多'}</Text>
        </View>}
      </View>}
      {notice ? <View className='mmk-notice' role='status'><Text>{notice}</Text></View> : null}
      <Text className='mmc-tail'>提交后由运营审核，审核通过后展示；统计与列表全部来自平台接口。</Text>
    </View>}
    <View className='mmk-tab-space' />
    <MerchantTabBar active='home' unit={unit} />
  </View>
}
