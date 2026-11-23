import { Button, Image, Input, Picker, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { consumerApi, consumerSession, consumerStorage } from '../../../../shared/consumer-runtime'
import { integrationMessage, type MarketingAssetReceipt } from '../../../../shared/consumer-api'
import { privateUploadFiles } from '../../../../shared/private-upload-platform'
import { MerchantMarketingApi, type CampaignInput, type CampaignThemeCode, type MyCampaign } from '../../../marketing/api'
import { MarketingCoverUpload } from '../../../marketing/cover-upload'
import {
  CAMPAIGN_THEMES, PLAY_TYPE_OPTIONS, beijingDateText, beijingDayToUtcIso, campaignEditable, campaignStatusPill, campaignThemeLabel,
} from '../../../marketing/model'
import { MerchantTabBar } from '../../../components/tab-bar'
import navBack from '../../aftersale/assets/nav-back@3x.png'
import iconCamera from '../assets/icon-camera@3x.png'
import iconDate from '../assets/icon-date@3x.png'
import '../index.css'
import './edit.css'

// 创建/编辑节日活动（Figma 18:11706，HANDOFF §4.5.3 / 41号 §3.4 设计偏差）：
// - 「立即上架」开关不展示，改为「保存草稿 / 提交审核」（提交前二次确认，运营审核后展示）；
// - 「优惠力度（元）」不采集；玩法仅「主题活动」可选，其余置灰「待开放」；
// - 活动名称一个输入同时写 name/title（同值）；有效期按北京时间选择、ISO-8601 UTC 提交；
// - 封面走 76号 §4.2 营销素材端点（独立 journal，成功保存/放弃后删除本地副本）；
// - 仅 DRAFT/REJECTED 可编辑，其余状态只读展示；不伪造上传/提交成功。
const api = new MerchantMarketingApi(consumerApi)
type Phase = 'idle' | 'loading' | 'ready' | 'missing' | 'load-error' | 'entry'
// 71号 subtitle 字段沿用：P1 表单不展示输入，编辑既有活动时原值透传，避免静默清空。
type CampaignForm = {
  name: string; subtitle: string; description: string
  themeCode: CampaignThemeCode | null; highlightText: string
  startDate: string; endDate: string; coverAssetId: string
}
const emptyForm: CampaignForm = { name: '', subtitle: '', description: '', themeCode: null, highlightText: '', startDate: '', endDate: '', coverAssetId: '' }

function useMerchant() {
  const context = consumerApi.scope.current
  if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) return null
  return { merchantId: context.merchantId, storeId: context.storeId }
}

export default function MerchantCampaignEditPage() {
  const route = useRouter()
  const campaignIdParam = route.params.campaignId || ''
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = {
    '--mmk-unit': `${info.windowWidth / 402}px`,
    '--mmk-status-top': `${info.statusBarHeight || 0}px`,
  } as CSSProperties
  const [merchant] = useState(() => useMerchant())
  const [phase, setPhase] = useState<Phase>('idle')
  const [campaign, setCampaign] = useState<MyCampaign | null>(null)
  const [form, setForm] = useState<CampaignForm>(emptyForm)
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [coverPreview, setCoverPreview] = useState('')
  const [coverPending, setCoverPending] = useState(false)
  const [coverRejected, setCoverRejected] = useState(false)
  const picking = useRef(false)
  const mounted = useRef(true)
  const loadSequence = useRef(0)
  const version = useRef<string>('')
  const createdId = useRef<string>('')
  const [coverUpload] = useState(() => new MarketingCoverUpload(consumerApi, consumerStorage, privateUploadFiles(), async path => {
    const result = await Taro.getImageInfo({ src: path })
    return { width: result.width ?? 0, height: result.height ?? 0 }
  }))

  const load = useCallback(async () => {
    const sequence = ++loadSequence.current
    const revision = consumerApi.scope.revision
    setNotice('')
    if (!merchant) { setPhase('entry'); return }
    setPhase('loading')
    try {
      let loaded: MyCampaign | null = null
      if (campaignIdParam) {
        const list = await api.myCampaigns(merchant.merchantId, merchant.storeId)
        loaded = list.find(item => item.campaignId === campaignIdParam) ?? null
        if (!loaded) { setPhase('missing'); return }
      }
      if (!mounted.current || sequence !== loadSequence.current || revision !== consumerApi.scope.revision) return
      setCampaign(loaded)
      if (loaded) {
        version.current = loaded.version; createdId.current = loaded.campaignId
        setForm({
          name: loaded.name, subtitle: loaded.subtitle ?? '', description: loaded.description ?? '',
          themeCode: loaded.themeCode, highlightText: loaded.highlightText ?? '',
          startDate: beijingDateText(loaded.startAt), endDate: beijingDateText(loaded.endAt),
          coverAssetId: loaded.coverAssetId ?? '',
        })
        setCoverPreview(loaded.coverUrl ?? '')
      } else {
        version.current = ''; createdId.current = ''; setForm(emptyForm); setCoverPreview('')
      }
      setCoverRejected(false)
      const pending = coverUpload.pending()
      setCoverPending(!!pending && !pending.receipt)
      setCoverRejected(!!pending?.rejected)
      if (pending?.receipt) {
        setForm(current => ({ ...current, coverAssetId: pending.receipt!.assetId }))
        setCoverPreview(pending.filePath)
        setNotice('已恢复上传成功的封面，请保存草稿。')
      } else if (pending) setNotice('封面上传结果尚未确认，请点击头图继续原上传。')
      setPhase('ready')
    } catch (error) {
      if (!mounted.current || sequence !== loadSequence.current || revision !== consumerApi.scope.revision) return
      setNotice(integrationMessage(error)); setPhase('load-error')
    }
  }, [merchant, campaignIdParam, coverUpload])
  useDidShow(() => {
    if (picking.current) return
    void (async () => {
      try { await consumerSession.launch() } catch { /* load() renders the error state */ }
      await load()
    })()
  })
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; loadSequence.current++ } }, [])

  const readonly = campaign !== null && !campaignEditable(campaign.status)
  const editing = campaign !== null
  const pill = campaign ? campaignStatusPill(campaign.status, Date.now(), campaign.startAt, campaign.endAt) : null
  // 渲染期读取 journal 只做尽力恢复：workspace 已切换或 journal 异常时不阻塞页面（渲染安全）。
  function pendingCoverReceipt(): MarketingAssetReceipt | null {
    try { return coverUpload.pending()?.receipt ?? null } catch { return null }
  }
  const pendingReceipt = pendingCoverReceipt()
  const unacknowledgedCover = pendingReceipt !== null && pendingReceipt.assetId === form.coverAssetId

  function patch(next: Partial<CampaignForm>) { setForm(current => ({ ...current, ...next })) }

  function back() {
    // P-03 追缉（2026-11-12）：商家域返回不用 navigateBack（隐藏页树可能已被 dispose）。
    Taro.reLaunch({ url: '/merchant/pages/marketing/campaigns/index' }).catch(() => { void Taro.showToast({ title: '返回失败，请重试', icon: 'none' }) })
  }

  async function pickCover() {
    if (readonly || busy) return
    const revision = consumerApi.scope.revision
    picking.current = true
    setBusy(true)
    try {
      const receipt = await coverUpload.upload()
      if (!mounted.current || revision !== consumerApi.scope.revision) return
      if (!receipt) return // picker cancelled before any network intent
      patch({ coverAssetId: receipt.assetId })
      setCoverPreview(coverUpload.pending()?.filePath || '')
      setCoverPending(false); setCoverRejected(false)
      setNotice('封面上传成功，请保存草稿或提交审核。')
    } catch (error) {
      if (!mounted.current || revision !== consumerApi.scope.revision) return
      let pending: ReturnType<MarketingCoverUpload['pending']> = null
      try { pending = coverUpload.pending() } catch { pending = null }
      setCoverPending(!!pending && !pending.receipt); setCoverRejected(!!pending?.rejected)
      setNotice(pending?.rejected ? '图片未通过上传校验，请重新选择。' : pending
        ? '上传结果尚未确认，请点击头图继续原上传。'
        : '未完成图片选择或上传，请重试（仅支持 2MB 内 JPG/PNG，最长边不超过 2000 像素）。')
    } finally { picking.current = false; if (mounted.current) setBusy(false) }
  }

  async function removePendingCover() {
    if (busy || !unacknowledgedCover) return
    setBusy(true)
    try {
      await coverUpload.discard()
      patch({ coverAssetId: campaign?.coverAssetId ?? '' })
      setCoverPreview(campaign?.coverUrl ?? '')
      setCoverPending(false); setCoverRejected(false)
      setNotice('已移除未保存的封面，请重新选择。')
    } catch { setNotice('尚不能移除封面，请重试原上传。') } finally { setBusy(false) }
  }

  function formProblems(): string[] {
    const problems: string[] = []
    const nameLength = Array.from(form.name).length
    if (nameLength < 2 || nameLength > 100) problems.push('活动名称需 2-100 字')
    if (!form.themeCode) problems.push('请选择节日主题')
    if (!form.startDate || !form.endDate) problems.push('请选择活动有效期')
    else if (!(beijingDayToUtcIso(form.startDate) < beijingDayToUtcIso(form.endDate, true))) problems.push('开始日期需早于结束日期')
    return problems
  }

  async function persist(): Promise<boolean> {
    if (!merchant) return false
    if (coverPending) { setNotice('请先确认封面上传结果。'); return false }
    const problems = formProblems()
    if (problems.length) { setNotice(`请检查：${problems.join('；')}`); return false }
    const input: CampaignInput = {
      name: form.name, title: form.name,
      subtitle: form.subtitle || undefined,
      description: form.description || undefined,
      themeCode: form.themeCode!,
      highlightText: form.highlightText || undefined,
      playType: 'THEME',
      coverAssetId: form.coverAssetId || undefined,
      startAt: beijingDayToUtcIso(form.startDate),
      endAt: beijingDayToUtcIso(form.endDate, true),
    }
    const receipt = createdId.current
      ? await api.updateCampaign(merchant.merchantId, merchant.storeId, createdId.current, input, version.current)
      : await api.createCampaign(merchant.merchantId, merchant.storeId, input)
    createdId.current = receipt.id
    version.current = receipt.version
    // 封面素材被本次保存确认 → 删除本地副本并清 journal（76号 §4.2）。
    const uploaded = coverUpload.pending()?.receipt
    if (uploaded && uploaded.assetId === input.coverAssetId) {
      await coverUpload.acknowledge(uploaded.assetId)
      setCoverPreview('')
    }
    return true
  }

  function returnToList() {
    if (!mounted.current) return
    void Taro.reLaunch({ url: '/merchant/pages/marketing/campaigns/index' })
  }

  async function saveDraft() {
    if (readonly || busy) return
    setNotice(''); setBusy(true)
    try {
      if (await persist()) {
        await Taro.showToast({ title: '草稿已保存', icon: 'none' })
        setTimeout(returnToList, 600)
      }
    } catch (error) { setNotice(integrationMessage(error)) } finally { setBusy(false) }
  }

  async function submitForReview() {
    if (readonly || busy) return
    if (coverPending) { setNotice('请先确认封面上传结果。'); return }
    if (!form.coverAssetId) { setNotice('提交审核前需上传活动封面。'); return }
    const problems = formProblems()
    if (problems.length) { setNotice(`请检查：${problems.join('；')}`); return }
    // 41号 §2.1：商家不可直接发布；提交后由运营审核。
    const confirmed = await Taro.showModal({ title: '提交审核', content: '提交后由运营审核，审核通过后展示', confirmText: '提交审核', cancelText: '继续编辑' })
    if (!confirmed.confirm) return
    setNotice(''); setBusy(true)
    try {
      if (!await persist()) return
      if (!merchant || !createdId.current) return
      await api.submitCampaign(merchant.merchantId, merchant.storeId, createdId.current, version.current)
      await Taro.showToast({ title: '已提交审核', icon: 'none' })
      setTimeout(returnToList, 600)
    } catch (error) { setNotice(integrationMessage(error)) } finally { setBusy(false) }
  }

  async function cancelActivity() {
    if (!campaign || busy) return
    if (!['DRAFT', 'REJECTED', 'PENDING_REVIEW'].includes(campaign.status)) return
    const confirmed = await Taro.showModal({ title: '确认取消活动', content: '取消后活动不再展示；已发布活动不可由商家取消。', confirmText: '确认取消', cancelText: '再想想' })
    if (!confirmed.confirm) return
    if (!merchant) return
    setNotice(''); setBusy(true)
    try {
      await api.cancelCampaign(merchant.merchantId, merchant.storeId, campaign.campaignId, campaign.version)
      await Taro.showToast({ title: '已取消', icon: 'none' })
      setTimeout(returnToList, 600)
    } catch (error) { setNotice(integrationMessage(error)) } finally { setBusy(false) }
  }

  const coverChosen = coverPreview !== '' || form.coverAssetId !== ''
  const canCancel = campaign !== null && ['DRAFT', 'REJECTED', 'PENDING_REVIEW'].includes(campaign.status)

  return <View className='mmk-page' style={style}>
    <View className='mmk-status' />
    <View className='mmk-header'>
      <Button className='mmk-back' ariaLabel='返回' onClick={back}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mmk-title'>{editing ? (readonly ? '活动详情' : '编辑节日活动') : '创建节日活动'}</Text>
    </View>
    {phase !== 'ready' && <View className='mmk-empty' role='status'>
      <Text>{phase === 'loading' || phase === 'idle' ? '正在加载…' : phase === 'missing' ? '活动不存在或已删除。' : phase === 'entry' ? '请从商家工作台进入营销工具。' : notice || '加载失败，请重试。'}</Text>
      {phase === 'load-error' && <Button className='mmk-primary' onClick={() => void load()}>重新加载</Button>}
      {phase === 'entry' && <Button className='mmk-primary' onClick={() => Taro.reLaunch({ url: '/merchant/pages/workspace/index' }).catch(() => {})}>去商家工作台</Button>}
      {(phase === 'missing' || phase === 'entry') && <Button className='mmk-ghost' onClick={back}>返回节日营销</Button>}
    </View>}
    {phase === 'ready' && <View className='mmk-edit'>
      {campaign && campaign.status === 'REJECTED' && campaign.rejectReason && <View className='mmk-edit-banner mmerk-edit-reject' role='alert'>
        <Text className='mmk-edit-banner-title'>审核驳回</Text>
        <Text className='mmk-edit-banner-text'>驳回原因：{campaign.rejectReason}</Text>
        <Text className='mmk-edit-banner-text'>请按驳回原因修改后重新提交审核。</Text>
      </View>}
      {readonly && <View className='mmk-edit-banner' role='status'>
        <Text className='mmk-edit-banner-title'>当前活动为只读展示（{pill?.text}）</Text>
        <Text className='mmk-edit-banner-text'>{campaign?.status === 'PENDING_REVIEW' ? '请等待运营审核结果；驳回后可查看原因并修改重提。' : '该活动已进入平台流程，商家不可修改。'}</Text>
      </View>}
      <View className='mmk-section'>
        <Text className='mmk-label'>活动头图{!readonly && <Text className='mmk-required'>*</Text>}</Text>
        <View className='mmk-cover' id='mmedit-cover' onClick={() => void pickCover()}>
          {coverPreview
            ? <Image className='mmk-cover-image' src={coverPreview} mode='aspectFill' />
            : coverChosen
              ? <Text className='mmk-cover-placeholder'>已绑定封面素材（展示链接由平台投影提供）</Text>
              : <Text className='mmk-cover-placeholder'>上传活动封面（2MB 内 JPG / PNG，最长边 ≤2000 像素）</Text>}
          {!readonly && <View className='mmk-cover-badge'>
            <Image className='mmk-cover-camera' src={iconCamera} mode='aspectFit' />
            <Text>更换头图</Text>
          </View>}
        </View>
        {coverPending && <Text className='mmk-hint'>封面上传中或结果未确认，提交前请先点击头图完成上传。</Text>}
        {coverRejected && <Text className='mmk-hint'>图片未通过上传校验，点击头图重新选择。</Text>}
        {!readonly && unacknowledgedCover && <View className='mmk-link-action' id='mmedit-cover-remove' onClick={() => void removePendingCover()}><Text>移除未保存的封面</Text></View>}
      </View>
      <View className='mmk-section'>
        <Text className='mmk-label'>活动名称{!readonly && <Text className='mmk-required'>*</Text>}</Text>
        <Input id='mmedit-name' className='mmk-edit-input' disabled={readonly} value={form.name} maxlength={100}
          placeholder='例如：中秋团圆礼 · 宠物月饼礼盒' onInput={event => patch({ name: String(event.detail.value) })} />
      </View>
      <View className='mmk-section'>
        <Text className='mmk-label'>节日主题{!readonly && <Text className='mmk-required'>*</Text>}</Text>
        <View className='mmk-chips'>
          {CAMPAIGN_THEMES.map(theme => <View key={theme.code} id={`mmedit-theme-${theme.code}`}
            className={form.themeCode === theme.code ? 'mmk-chip mmk-chip-active' : 'mmk-chip'}
            onClick={() => { if (!readonly) patch({ themeCode: theme.code }) }}>
            <Text>{theme.label}</Text>
          </View>)}
        </View>
        {editing && campaign?.themeCode ? <Text className='mmk-hint'>当前主题：{campaignThemeLabel(campaign.themeCode)}</Text> : null}
      </View>
      <View className='mmk-section'>
        <Text className='mmk-label'>活动玩法</Text>
        <View className='mmk-chips'>
          {PLAY_TYPE_OPTIONS.map(option => <View key={option.type} id={`mmedit-play-${option.type}`}
            className={option.available && !readonly ? 'mmk-chip mmk-chip-active' : option.available ? 'mmk-chip mmk-chip-active mmk-chip-locked' : 'mmk-chip mmk-chip-disabled'}>
            <Text>{option.label}</Text>
            {!option.available && option.hint ? <Text className='mmk-chip-hint'>{option.hint}</Text> : null}
          </View>)}
        </View>
        <Text className='mmk-hint'>P1 仅支持主题活动；限时折扣、满减优惠将随后续版本开放。</Text>
      </View>
      <View className='mmk-section'>
        <Text className='mmk-label'>节日主打项目</Text>
        <Input id='mmedit-highlight' className='mmk-edit-input' disabled={readonly} value={form.highlightText} maxlength={100}
          placeholder='例如：宠物月饼礼盒、国庆寄养特惠、圣诞宠物写真' onInput={event => patch({ highlightText: String(event.detail.value) })} />
      </View>
      <View className='mmk-section'>
        <Text className='mmk-label'>活动有效期{!readonly && <Text className='mmk-required'>*</Text>}</Text>
        <View className='mmk-date-row'>
          {readonly
            ? <View className='mmk-date'><Text>{form.startDate || '开始日期'}</Text></View>
            : <Picker mode='date' value={form.startDate} onChange={event => patch({ startDate: String(event.detail.value) })}>
              <View className='mmk-date'><Text>{form.startDate || '开始日期'}</Text><Image className='mmk-date-icon' src={iconDate} mode='aspectFit' /></View>
            </Picker>}
          <Text className='mmk-date-sep'>至</Text>
          {readonly
            ? <View className='mmk-date'><Text>{form.endDate || '结束日期'}</Text></View>
            : <Picker mode='date' value={form.endDate} onChange={event => patch({ endDate: String(event.detail.value) })}>
              <View className='mmk-date'><Text>{form.endDate || '结束日期'}</Text><Image className='mmk-date-icon' src={iconDate} mode='aspectFit' /></View>
            </Picker>}
        </View>
        <Text className='mmk-hint'>按北京时间选择，提交时转换为 UTC 时间。</Text>
      </View>
      <View className='mmk-section'>
        <Text className='mmk-label'>活动说明<Text className='mmk-counter'>{Array.from(form.description).length}/300</Text></Text>
        <Textarea id='mmedit-description' className='mmk-textarea mmedit-textarea' disabled={readonly} value={form.description} maxlength={300}
          placeholder='介绍节日活动规则、参与方式、适用项目等' onInput={event => patch({ description: String(event.detail.value) })} />
      </View>
      {notice ? <View className='mmk-notice' id='mmedit-notice' role='status'><Text>{notice}</Text></View> : null}
      {!readonly && <View className='mmk-edit-actions'>
        <View className='mmk-edit-action mmk-edit-action-secondary' id='mmedit-save-draft' onClick={() => void saveDraft()}>
          <Text className={busy || coverPending ? 'mmk-edit-action-text mmk-edit-action-disabled' : 'mmk-edit-action-text'}>保存草稿</Text>
        </View>
        <View className='mmk-edit-action' id='mmedit-submit' onClick={() => { if (!busy && !coverPending) void submitForReview() }}>
          <Text className={busy || coverPending ? 'mmk-edit-action-text mmk-edit-action-disabled' : 'mmk-edit-action-text'}>提交审核</Text>
        </View>
      </View>}
      {canCancel && <View className='mmk-link-action mmedit-cancel' id='mmedit-cancel' onClick={() => void cancelActivity()}>
        <Text>{busy ? '处理中…' : '取消活动'}</Text>
      </View>}
      <Text className='mmk-hint mmedit-tail'>提交后由运营审核，审核通过后展示；商家不可直接发布。</Text>
    </View>}
    <View className='mmk-tab-space' />
    <MerchantTabBar active='home' unit={unit} />
  </View>
}
