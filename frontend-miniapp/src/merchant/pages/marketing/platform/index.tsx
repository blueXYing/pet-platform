import { Button, Image, Input, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useCallback, useState, type CSSProperties } from 'react'
import { consumerApi, consumerSession } from '../../../../shared/consumer-runtime'
import { MerchantMarketingApi, type MerchantCampaign, type MerchantEnrollment } from '../../../marketing/api'
import { MerchantTabBar } from '../../../components/tab-bar'
import { merchantOrderTimeText } from '../../../order/model'
import navBack from '../../aftersale/assets/nav-back@3x.png'
import '../index.css'

// 平台活动报名独立页（71号 §5.1/§5.2 原流程迁入，HANDOFF §4.5.3）：保留可报名活动/我的报名
// 两 tab 的加载、报名、撤回与错误文案；「我的活动」已迁至节日营销列表页，不再在本页显示。
const api = new MerchantMarketingApi(consumerApi)
type Tab = 'open' | 'mine'
// Contract enums were rendered raw (报名状态：APPROVED); map to the product copy.
const enrollmentStatusText: Record<string, string> = {
  SUBMITTED: '已提交', UNDER_REVIEW: '审核中', APPROVED: '已通过', REJECTED: '已驳回', WITHDRAWN: '已撤回',
}
const beijingDate = (iso: string | null): string => merchantOrderTimeText(iso).slice(0, 10) || '--'

function useMerchant() {
  const context = consumerApi.scope.current
  if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) return null
  return { merchantId: context.merchantId, storeId: context.storeId }
}

export default function MerchantMarketingPlatformPage() {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = {
    '--mmk-unit': `${info.windowWidth / 402}px`,
    '--mmk-status-top': `${info.statusBarHeight || 0}px`,
  } as CSSProperties
  const [merchant] = useState(() => useMerchant())
  const [tab, setTab] = useState<Tab>('open')
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [open, setOpen] = useState<readonly MerchantCampaign[]>([])
  const [mine, setMine] = useState<readonly MerchantEnrollment[]>([])
  const [notice, setNotice] = useState('')
  const [enrolling, setEnrolling] = useState<string | null>(null)
  const [offerTag, setOfferTag] = useState('')
  const [offerTitle, setOfferTitle] = useState('')
  const [offerCopy, setOfferCopy] = useState('')

  const load = useCallback(() => {
    if (!merchant) { setPhase('error'); return }
    setPhase('loading')
    Promise.all([
      api.openCampaigns(merchant.merchantId, merchant.storeId),
      api.myEnrollments(merchant.merchantId, merchant.storeId),
    ]).then(([nextOpen, nextMine]) => {
      setOpen(nextOpen); setMine(nextMine); setPhase('ready')
    }).catch(() => setPhase('error'))
  }, [merchant])
  useDidShow(() => {
    void (async () => {
      try { await consumerSession.launch() } catch { /* load() renders the error state */ }
      load()
    })()
  })

  async function submitEnroll(campaign: MerchantCampaign) {
    if (!merchant || enrolling) return
    setEnrolling(campaign.campaignId)
    setNotice('')
    try {
      await api.enroll(merchant.merchantId, merchant.storeId, campaign.campaignId,
        { offerTag: offerTag || undefined, offerTitle: offerTitle || undefined, offerCopy: offerCopy || undefined })
      setEnrolling(null); setOfferTag(''); setOfferTitle(''); setOfferCopy('')
      await Taro.showToast({ title: '报名已提交，等待运营审核', icon: 'none' })
      load()
    } catch {
      setEnrolling(null); setNotice('报名失败，请稍后重试')
    }
  }

  async function withdraw(enrollment: MerchantEnrollment) {
    if (!merchant) return
    try {
      await api.withdraw(merchant.merchantId, merchant.storeId, enrollment.enrollmentId, enrollment.version)
      await Taro.showToast({ title: '已撤回', icon: 'none' })
      load()
    } catch { setNotice('撤回失败，请稍后重试') }
  }

  function back() {
    // P-03 追缉（2026-11-12）：商家域返回不用 navigateBack（隐藏页树可能已被 dispose）。
    Taro.reLaunch({ url: '/merchant/pages/marketing/index' }).catch(() => {})
  }

  return <View className='mmk-page' style={style}>
    <View className='mmk-status' />
    <View className='mmk-header'>
      <Button className='mmk-back' ariaLabel='返回' onClick={back}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mmk-title'>平台活动</Text>
    </View>
    <View className='mmk-tabs'>
      {([['open', '可报名活动'], ['mine', '我的报名']] as const).map(([key, label]) =>
        <View key={key} id={`mmk-tab-${key}`} className={tab === key ? 'mmk-tab mmk-tab-active' : 'mmk-tab'} onClick={() => setTab(key)}><Text>{label}</Text></View>)}
    </View>
    {notice ? <View className='mmk-notice'><Text>{notice}</Text></View> : null}
    {!merchant && <View className='mmk-empty'><Text>请从商家工作台进入营销活动</Text></View>}
    {merchant && phase === 'loading' && <View className='mmk-empty'><Text>正在加载活动…</Text></View>}
    {merchant && phase === 'error' && <View className='mmk-empty'>
      <Text>活动加载失败</Text><Button className='mmk-primary' onClick={load}>重新加载</Button>
    </View>}
    {merchant && phase === 'ready' && tab === 'open' && <View className='mmk-list'>
      {open.length === 0 && <View className='mmk-empty'><Text>当前没有可报名的平台活动</Text></View>}
      {open.map(campaign => <View key={campaign.campaignId} className='mmk-card'>
        <Text className='mmk-card-title'>{campaign.title}</Text>
        {campaign.subtitle ? <Text className='mmk-card-sub'>{campaign.subtitle}</Text> : null}
        <Text className='mmk-meta'>报名 {beijingDate(campaign.enrollStartAt)} 至 {beijingDate(campaign.enrollEndAt)}</Text>
        {campaign.enrollmentStatus && ['SUBMITTED', 'UNDER_REVIEW', 'APPROVED'].includes(campaign.enrollmentStatus)
          ? <Text className='mmk-status-text'>报名状态：{enrollmentStatusText[campaign.enrollmentStatus] ?? campaign.enrollmentStatus}</Text>
          : enrolling === campaign.campaignId
            ? <View className='mmk-form'>
              <Input className='mmk-input' placeholder='优惠角标（如 5折）' value={offerTag} maxlength={32} onInput={event => setOfferTag(String(event.detail.value))} />
              <Input className='mmk-input' placeholder='优惠标题（≤100字）' value={offerTitle} maxlength={100} onInput={event => setOfferTitle(String(event.detail.value))} />
              <Textarea className='mmk-textarea' placeholder='优惠说明（≤100字）' value={offerCopy} maxlength={100} onInput={event => setOfferCopy(String(event.detail.value))} />
              <View className='mmk-row'>
                <Button className='mmk-primary' onClick={() => void submitEnroll(campaign)}>提交报名</Button>
                <Button className='mmk-ghost' onClick={() => setEnrolling(null)}>取消</Button>
              </View>
            </View>
            : <Button className='mmk-primary' onClick={() => { setEnrolling(campaign.campaignId); setNotice('') }}>报名</Button>}
      </View>)}
    </View>}
    {merchant && phase === 'ready' && tab === 'mine' && <View className='mmk-list'>
      {mine.length === 0 && <View className='mmk-empty'><Text>还没有报名记录</Text></View>}
      {mine.map(enrollment => <View key={enrollment.enrollmentId} className='mmk-card'>
        <Text className='mmk-card-title'>{enrollment.campaignTitle}</Text>
        <Text className='mmk-status-text'>状态：{enrollmentStatusText[enrollment.status] ?? enrollment.status}</Text>
        {enrollment.offerTitle ? <Text className='mmk-card-sub'>{enrollment.offerTitle}</Text> : null}
        {enrollment.rejectReason ? <Text className='mmk-reject'>驳回原因：{enrollment.rejectReason}</Text> : null}
        {(enrollment.status === 'SUBMITTED' || enrollment.status === 'UNDER_REVIEW') &&
          <Button className='mmk-ghost' onClick={() => void withdraw(enrollment)}>撤回报名</Button>}
      </View>)}
    </View>}
    <View className='mmk-tab-space' />
    <MerchantTabBar active='home' unit={unit} />
  </View>
}
