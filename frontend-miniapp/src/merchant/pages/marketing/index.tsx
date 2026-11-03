import { Button, Input, Picker, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useCallback, useState, type CSSProperties } from 'react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { MerchantMarketingApi, type MerchantCampaign, type MerchantEnrollment, type MyCampaign } from '../../marketing/api'
import './index.css'

const api = new MerchantMarketingApi(consumerApi)
type Tab = 'open' | 'mine' | 'campaigns'
const moneyDate = (date: string, end = false) => `${date}T${end ? '23:59:59' : '00:00:00'}.000Z`

function useMerchant() {
  const context = consumerApi.scope.capture().context
  if (context.workspace !== 'merchant' || !context.merchantId || !context.storeId) return null
  return { merchantId: context.merchantId, storeId: context.storeId }
}

export default function MerchantMarketingPage() {
  const [info] = useState(() => Taro.getWindowInfo())
  const style = { '--mmk-unit': `${info.windowWidth / 402}px` } as CSSProperties
  const merchant = useState(() => useMerchant())[0]
  const [tab, setTab] = useState<Tab>('open')
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [open, setOpen] = useState<readonly MerchantCampaign[]>([])
  const [mine, setMine] = useState<readonly MerchantEnrollment[]>([])
  const [campaigns, setCampaigns] = useState<readonly MyCampaign[]>([])
  const [notice, setNotice] = useState('')
  const [enrolling, setEnrolling] = useState<string | null>(null)
  const [offerTag, setOfferTag] = useState('')
  const [offerTitle, setOfferTitle] = useState('')
  const [offerCopy, setOfferCopy] = useState('')
  const [name, setName] = useState('')
  const [title, setTitle] = useState('')
  const [subtitle, setSubtitle] = useState('')
  const [startDate, setStartDate] = useState('')
  const [endDate, setEndDate] = useState('')
  const [editingCampaign, setEditingCampaign] = useState<MyCampaign | null>(null)

  const load = useCallback(() => {
    if (!merchant) { setPhase('error'); return }
    setPhase('loading')
    Promise.all([
      api.openCampaigns(merchant.merchantId, merchant.storeId),
      api.myEnrollments(merchant.merchantId, merchant.storeId),
      api.myCampaigns(merchant.merchantId, merchant.storeId),
    ]).then(([nextOpen, nextMine, nextCampaigns]) => {
      setOpen(nextOpen); setMine(nextMine); setCampaigns(nextCampaigns); setPhase('ready')
    }).catch(() => setPhase('error'))
  }, [merchant])
  useDidShow(load)

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

  async function saveCampaign() {
    if (!merchant || !name || !title || !startDate || !endDate) { setNotice('请填写活动名称、标题和起止日期'); return }
    const input = { name, title, subtitle: subtitle || undefined, startAt: moneyDate(startDate), endAt: moneyDate(endDate, true) }
    try {
      if (editingCampaign) {
        await api.updateCampaign(merchant.merchantId, merchant.storeId, editingCampaign.campaignId, input, editingCampaign.version)
      } else {
        await api.createCampaign(merchant.merchantId, merchant.storeId, input)
      }
      setName(''); setTitle(''); setSubtitle(''); setStartDate(''); setEndDate(''); setEditingCampaign(null)
      await Taro.showToast({ title: editingCampaign ? '活动已保存' : '活动草稿已创建', icon: 'none' })
      load()
    } catch { setNotice(editingCampaign ? '保存失败，请稍后重试' : '创建失败，请稍后重试') }
  }

  function editCampaign(campaign: MyCampaign) {
    setEditingCampaign(campaign)
    setName(campaign.name); setTitle(campaign.title); setSubtitle(campaign.subtitle ?? '')
    setStartDate(campaign.startAt.slice(0, 10)); setEndDate(campaign.endAt.slice(0, 10))
    setNotice('')
  }

  async function submitCampaign(campaign: MyCampaign) {
    if (!merchant) return
    try {
      await api.submitCampaign(merchant.merchantId, merchant.storeId, campaign.campaignId, campaign.version)
      await Taro.showToast({ title: '已提交审核', icon: 'none' })
      load()
    } catch { setNotice('提交失败，请稍后重试') }
  }

  async function cancelCampaign(campaign: MyCampaign) {
    if (!merchant) return
    try {
      await api.cancelCampaign(merchant.merchantId, merchant.storeId, campaign.campaignId, campaign.version)
      await Taro.showToast({ title: '已取消', icon: 'none' })
      load()
    } catch { setNotice('取消失败，请稍后重试') }
  }

  function back() {
    Taro.navigateBack().catch(() => Taro.redirectTo({ url: '/merchant/pages/workspace/index' }))
  }

  return <View className='mmk-page' style={style}>
    <View className='mmk-status' />
    <View className='mmk-header'>
      <Button className='mmk-back' ariaLabel='返回' onClick={back}>‹</Button>
      <Text className='mmk-title'>营销活动</Text>
    </View>
    <View className='mmk-tabs'>
      {([['open', '可报名活动'], ['mine', '我的报名'], ['campaigns', '我的活动']] as const).map(([key, label]) =>
        <View key={key} className={tab === key ? 'mmk-tab mmk-tab-active' : 'mmk-tab'} onClick={() => setTab(key)}><Text>{label}</Text></View>)}
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
        <Text className='mmk-meta'>报名 {campaign.enrollStartAt?.slice(0, 10) ?? '--'} 至 {campaign.enrollEndAt?.slice(0, 10) ?? '--'}</Text>
        {campaign.enrollmentStatus && ['SUBMITTED', 'UNDER_REVIEW', 'APPROVED'].includes(campaign.enrollmentStatus)
          ? <Text className='mmk-status-text'>报名状态：{campaign.enrollmentStatus}</Text>
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
        <Text className='mmk-status-text'>状态：{enrollment.status}</Text>
        {enrollment.offerTitle ? <Text className='mmk-card-sub'>{enrollment.offerTitle}</Text> : null}
        {enrollment.rejectReason ? <Text className='mmk-reject'>驳回原因：{enrollment.rejectReason}</Text> : null}
        {(enrollment.status === 'SUBMITTED' || enrollment.status === 'UNDER_REVIEW') &&
          <Button className='mmk-ghost' onClick={() => void withdraw(enrollment)}>撤回报名</Button>}
      </View>)}
    </View>}
    {merchant && phase === 'ready' && tab === 'campaigns' && <View className='mmk-list'>
      <View className='mmk-card'>
        <Text className='mmk-card-title'>{editingCampaign ? '编辑商家活动' : '发起商家活动'}</Text>
        <Input className='mmk-input' placeholder='活动名称（2-100字）' value={name} maxlength={100} onInput={event => setName(String(event.detail.value))} />
        <Input className='mmk-input' placeholder='活动标题（1-100字）' value={title} maxlength={100} onInput={event => setTitle(String(event.detail.value))} />
        <Input className='mmk-input' placeholder='活动副标题（可选）' value={subtitle} maxlength={200} onInput={event => setSubtitle(String(event.detail.value))} />
        <View className='mmk-date-row'>
          <Picker mode='date' value={startDate} onChange={event => setStartDate(String(event.detail.value))}><View className='mmk-date'><Text>{startDate || '开始日期'}</Text></View></Picker>
          <Picker mode='date' value={endDate} onChange={event => setEndDate(String(event.detail.value))}><View className='mmk-date'><Text>{endDate || '结束日期'}</Text></View></Picker>
        </View>
        <Button className='mmk-primary' onClick={() => void saveCampaign()}>{editingCampaign ? '保存修改' : '创建草稿'}</Button>
        {editingCampaign && <Button className='mmk-ghost' onClick={() => { setEditingCampaign(null); setName(''); setTitle(''); setSubtitle(''); setStartDate(''); setEndDate('') }}>取消编辑</Button>}
      </View>
      {campaigns.length === 0 && <View className='mmk-empty'><Text>还没有发起活动</Text></View>}
      {campaigns.map(campaign => <View key={campaign.campaignId} className='mmk-card'>
        <Text className='mmk-card-title'>{campaign.title}</Text>
        <Text className='mmk-status-text'>状态：{campaign.status}</Text>
        <Text className='mmk-meta'>{campaign.startAt.slice(0, 10)} 至 {campaign.endAt.slice(0, 10)}</Text>
        <View className='mmk-row'>
          {(campaign.status === 'DRAFT' || campaign.status === 'REJECTED') &&
            <Button className='mmk-ghost' onClick={() => editCampaign(campaign)}>编辑</Button>}
          {(campaign.status === 'DRAFT' || campaign.status === 'REJECTED') &&
            <Button className='mmk-primary' onClick={() => void submitCampaign(campaign)}>提交审核</Button>}
          {(campaign.status === 'DRAFT' || campaign.status === 'REJECTED' || campaign.status === 'PENDING_REVIEW') &&
            <Button className='mmk-ghost' onClick={() => void cancelCampaign(campaign)}>取消活动</Button>}
        </View>
      </View>)}
    </View>}
  </View>
}
