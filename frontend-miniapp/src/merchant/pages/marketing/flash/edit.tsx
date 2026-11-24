import { Button, Image, Input, Picker, ScrollView, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { consumerApi, consumerSession } from '../../../../shared/consumer-runtime'
import { RealServiceManageRepository } from '../../../services/repository'
import type { ManagedServiceItem } from '../../../services/model'
import { MerchantFlashPriceApi, type FlashPriceInput, type FlashPriceRecord, type PriceModeLabel } from '../../../marketing/flash-api'
import {
  PRICE_MODE_OPTIONS, beijingDateTimeParts, beijingDateTimeText, beijingDateTimeToUtcIso, discountRateValid,
  flashCancellable, flashEditable, flashPriceMessage, flashPricePreview, flashStatusPill, parseQuota, quotaProblem,
} from '../../../marketing/flash-model'
import { MerchantTabBar } from '../../../components/tab-bar'
import navBack from '../../aftersale/assets/nav-back@3x.png'
import iconToolFlashSale from '../assets/icon-tool-flash-sale@3x.png'
import iconDate from '../assets/icon-date@3x.png'
import '../index.css'
import './edit.css'

// 创建/编辑秒杀（Figma 18:9694，77号 §3.2/§3.3 / HANDOFF §8.1）：
// - 选择本店 ACTIVE 服务（名称+当前价；商家读侧只有 coverAssetId 锚点、无签名 URL，
//   封面以原样登记为偏差，选择卡用服务图标位承载）；
// - 优惠形式 chips 仅为标签（限时折扣/限时秒杀，服务端统一落 DISCOUNT，编辑回显时服务端
//   不回传标签 → 默认限时秒杀）；
// - 优惠力度（折）实时预览限时价（前端 round(listPrice×折/10, 2, HALF_UP)，以服务端复算为准）；
// - 发放总量/每人限领可空=不限；有效期按北京时间选择、UTC 提交，startAt<endAt 校验；
// - Figma「使用门槛」「立即上架」「活动头图上传」按 41号 §4/77号 §9 不展示；提交按钮为
//   「提交审核」（商家不可直接发布）；写命令走 ConsumerApi.write 槽位（同一 X-Request-Id 重放）。
const api = new MerchantFlashPriceApi(consumerApi)
const servicesRepository = new RealServiceManageRepository(consumerApi,
  () => consumerApi.scope.current?.merchantId || '', () => consumerApi.scope.current?.storeId || '')
type Phase = 'idle' | 'loading' | 'ready' | 'missing' | 'load-error' | 'entry'
type FlashForm = {
  serviceId: string; title: string; description: string
  priceModeLabel: PriceModeLabel; discountRate: string
  totalQuota: string; perUserLimit: string
  startDate: string; startTime: string; endDate: string; endTime: string
}
const emptyForm: FlashForm = {
  serviceId: '', title: '', description: '', priceModeLabel: 'FLASH_SALE', discountRate: '',
  totalQuota: '', perUserLimit: '', startDate: '', startTime: '', endDate: '', endTime: '',
}

function useMerchant() {
  const context = consumerApi.scope.current
  if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) return null
  return { merchantId: context.merchantId, storeId: context.storeId }
}

export default function MerchantFlashEditPage() {
  const route = useRouter()
  const flashPriceIdParam = route.params.id || ''
  const serviceIdParam = route.params.serviceId || ''
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = {
    '--mmk-unit': `${info.windowWidth / 402}px`,
    '--mmk-status-top': `${info.statusBarHeight || 0}px`,
  } as CSSProperties
  const [merchant] = useState(() => useMerchant())
  const [phase, setPhase] = useState<Phase>('idle')
  const [services, setServices] = useState<readonly ManagedServiceItem[]>([])
  const [record, setRecord] = useState<FlashPriceRecord | null>(null)
  const [form, setForm] = useState<FlashForm>(emptyForm)
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const mounted = useRef(true)
  const loadSequence = useRef(0)
  const version = useRef<string>('')
  const createdId = useRef<string>('')

  const load = useCallback(async () => {
    const sequence = ++loadSequence.current
    const revision = consumerApi.scope.revision
    setNotice('')
    if (!merchant) { setPhase('entry'); return }
    setPhase('loading')
    try {
      const collected: ManagedServiceItem[] = []
      let page = 1
      for (;;) {
        const result = await servicesRepository.list(page, 50)
        collected.push(...result.items)
        if (collected.length >= result.total || page >= 200) break
        page += 1
      }
      const active = collected.filter(item => item.status === 'ACTIVE' && item.price !== null)
      let loaded: FlashPriceRecord | null = null
      if (flashPriceIdParam) {
        if (!serviceIdParam) { setPhase('missing'); return }
        const records = await api.list(serviceIdParam, undefined, 1, 50)
        loaded = records.find(item => item.flashPriceId === flashPriceIdParam) ?? null
        if (!loaded) { setPhase('missing'); return }
      }
      if (!mounted.current || sequence !== loadSequence.current || revision !== consumerApi.scope.revision) return
      setServices(active)
      setRecord(loaded)
      if (loaded) {
        version.current = loaded.version; createdId.current = loaded.flashPriceId
        const start = beijingDateTimeParts(loaded.startAt), end = beijingDateTimeParts(loaded.endAt)
        setForm({
          serviceId: loaded.serviceId, title: loaded.title, description: loaded.description ?? '',
          // 服务端只落 priceMode=DISCOUNT，不回传优惠形式标签 → 默认「限时秒杀」。
          priceModeLabel: 'FLASH_SALE', discountRate: loaded.discountRate,
          totalQuota: loaded.totalQuota === null ? '' : String(loaded.totalQuota),
          perUserLimit: loaded.perUserLimit === null ? '' : String(loaded.perUserLimit),
          startDate: start.date, startTime: start.time, endDate: end.date, endTime: end.time,
        })
      } else {
        version.current = ''; createdId.current = ''
        const preset = serviceIdParam && active.some(item => item.serviceId === serviceIdParam) ? serviceIdParam : active[0]?.serviceId || ''
        const presetName = active.find(item => item.serviceId === preset)?.serviceName ?? ''
        setForm({ ...emptyForm, serviceId: preset, title: presetName })
        // 幂等恢复：创建结果未确认时按原命令载荷还原表单（同载荷重试同一 X-Request-Id）。
        if (preset) {
          const pending = consumerApi.pendingCommand(`flash-price-create:${preset}`)
          if (pending) {
            const data = pending.data as Record<string, unknown>
            const startAt = typeof data.startAt === 'string' ? beijingDateTimeParts(data.startAt) : null
            const endAt = typeof data.endAt === 'string' ? beijingDateTimeParts(data.endAt) : null
            setForm({
              ...emptyForm, serviceId: preset,
              title: typeof data.title === 'string' ? data.title : presetName,
              description: typeof data.description === 'string' ? data.description : '',
              priceModeLabel: data.priceModeLabel === 'LIMITED_DISCOUNT' ? 'LIMITED_DISCOUNT' : 'FLASH_SALE',
              discountRate: typeof data.discountRate === 'string' ? data.discountRate : '',
              totalQuota: typeof data.totalQuota === 'number' ? String(data.totalQuota) : '',
              perUserLimit: typeof data.perUserLimit === 'number' ? String(data.perUserLimit) : '',
              startDate: startAt?.date ?? '', startTime: startAt?.time ?? '',
              endDate: endAt?.date ?? '', endTime: endAt?.time ?? '',
            })
            setNotice('检测到上次提交结果尚未确认，请核对以下内容后重试原提交（不会重复创建）。')
          }
        }
      }
      setPhase('ready')
    } catch (error) {
      if (!mounted.current || sequence !== loadSequence.current || revision !== consumerApi.scope.revision) return
      setNotice(flashPriceMessage(error)); setPhase('load-error')
    }
  }, [merchant, flashPriceIdParam, serviceIdParam])
  useDidShow(() => {
    void (async () => {
      try { await consumerSession.launch() } catch { /* load() renders the error state */ }
      await load()
    })()
  })
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; loadSequence.current++ } }, [])

  const editing = record !== null
  const readonly = editing && !flashEditable(record.status)
  const pill = record ? flashStatusPill(record.status, Date.now(), record.startAt, record.endAt) : null
  const selectedService = services.find(item => item.serviceId === form.serviceId) || null
  const previewPrice = selectedService && selectedService.price ? flashPricePreview(selectedService.price, form.discountRate.trim()) : null

  function patch(next: Partial<FlashForm>) { setForm(current => ({ ...current, ...next })) }

  function chooseService(serviceId: string) {
    if (readonly || busy || serviceId === form.serviceId) return
    const next = services.find(item => item.serviceId === serviceId)
    if (!next) return
    // 活动名称默认=所选服务名：用户未改名（或仍为上一个默认名）时跟随切换，改过则保留。
    const previousDefault = services.find(item => item.serviceId === form.serviceId)?.serviceName ?? ''
    const keepTitle = form.title !== '' && form.title !== previousDefault
    patch({ serviceId, title: keepTitle ? form.title : next.serviceName })
  }

  function back() {
    // P-03 追缉（2026-11-12）：商家域返回不用 navigateBack（隐藏页树可能已被 dispose）。
    Taro.reLaunch({ url: '/merchant/pages/marketing/flash/index' }).catch(() => { void Taro.showToast({ title: '返回失败，请重试', icon: 'none' }) })
  }

  function formProblems(): string[] {
    const problems: string[] = []
    if (!form.serviceId) problems.push('请选择参与秒杀的服务')
    const titleLength = Array.from(form.title.trim()).length
    if (titleLength < 1 || titleLength > 100) problems.push('活动名称需 1-100 字（默认为服务名）')
    if (!discountRateValid(form.discountRate.trim())) problems.push('优惠力度需为 0-10 折且最多 1 位小数（如 8.8）')
    if (!form.startDate || !form.startTime || !form.endDate || !form.endTime) problems.push('请选择完整的开始/结束时间')
    else if (!(beijingDateTimeToUtcIso(form.startDate, form.startTime) < beijingDateTimeToUtcIso(form.endDate, form.endTime))) problems.push('开始时间需早于结束时间')
    const quotaProblems = [quotaProblem(form.totalQuota, 1000000, '发放总量'), quotaProblem(form.perUserLimit, 1000, '每人限领')]
    for (const problem of quotaProblems) if (problem) problems.push(problem)
    return problems
  }

  async function persist(): Promise<boolean> {
    if (!merchant) return false
    const problems = formProblems()
    if (problems.length) { setNotice(`请检查：${problems.join('；')}`); return false }
    const input: FlashPriceInput = {
      title: form.title.trim(),
      description: form.description.trim() || undefined,
      priceModeLabel: form.priceModeLabel,
      discountRate: form.discountRate.trim(),
      startAt: beijingDateTimeToUtcIso(form.startDate, form.startTime),
      endAt: beijingDateTimeToUtcIso(form.endDate, form.endTime),
      totalQuota: parseQuota(form.totalQuota),
      perUserLimit: parseQuota(form.perUserLimit),
    }
    const saved = createdId.current
      ? await api.update(form.serviceId, createdId.current, input, version.current)
      : await api.create(form.serviceId, input)
    createdId.current = saved.flashPriceId
    version.current = saved.version
    return true
  }

  function returnToList() {
    if (!mounted.current) return
    void Taro.reLaunch({ url: '/merchant/pages/marketing/flash/index' })
  }

  async function saveDraft() {
    if (readonly || busy) return
    setNotice(''); setBusy(true)
    try {
      if (await persist()) {
        await Taro.showToast({ title: '草稿已保存', icon: 'none' })
        setTimeout(returnToList, 600)
      }
    } catch (error) { setNotice(flashPriceMessage(error)) } finally { setBusy(false) }
  }

  async function submitForReview() {
    if (readonly || busy) return
    const problems = formProblems()
    if (problems.length) { setNotice(`请检查：${problems.join('；')}`); return }
    // 41号 §4：商家不可直接发布；提交后由运营审核，通过后按窗口生效。
    const confirmed = await Taro.showModal({ title: '提交审核', content: '提交后由运营审核，审核通过后按有效期自动生效。', confirmText: '提交审核', cancelText: '继续编辑' })
    if (!confirmed.confirm) return
    setNotice(''); setBusy(true)
    try {
      if (!await persist()) return
      if (!createdId.current) return
      await api.submit(form.serviceId, createdId.current, version.current)
      await Taro.showToast({ title: '已提交审核', icon: 'none' })
      setTimeout(returnToList, 600)
    } catch (error) { setNotice(flashPriceMessage(error)) } finally { setBusy(false) }
  }

  async function cancelFlash() {
    if (!record || busy || !flashCancellable(record.status)) return
    const confirmed = await Taro.showModal({ title: '确认取消秒杀', content: '取消后该场次不再生效；已生效场次不可由商家取消。', confirmText: '确认取消', cancelText: '再想想' })
    if (!confirmed.confirm) return
    setNotice(''); setBusy(true)
    try {
      await api.cancel(record.serviceId, record.flashPriceId, record.version)
      await Taro.showToast({ title: '已取消', icon: 'none' })
      setTimeout(returnToList, 600)
    } catch (error) { setNotice(flashPriceMessage(error)) } finally { setBusy(false) }
  }

  const canCancel = record !== null && flashCancellable(record.status)
  const startAtText = record ? beijingDateTimeText(record.startAt) : ''

  return <View className='mmk-page' style={style}>
    <View className='mmk-status' />
    <View className='mmk-header'>
      <Button className='mmk-back' ariaLabel='返回' onClick={back}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mmk-title'>{editing ? (readonly ? '秒杀详情' : '编辑秒杀') : '创建秒杀'}</Text>
    </View>
    {phase !== 'ready' && <View className='mmk-empty' role='status'>
      <Text>{phase === 'loading' || phase === 'idle' ? '正在加载…' : phase === 'missing' ? '秒杀场次不存在或已删除。' : phase === 'entry' ? '请从商家工作台进入营销工具。' : notice || '加载失败，请重试。'}</Text>
      {phase === 'load-error' && <Button className='mmk-primary' onClick={() => void load()}>重新加载</Button>}
      {phase === 'entry' && <Button className='mmk-primary' onClick={() => Taro.reLaunch({ url: '/merchant/pages/workspace/index' }).catch(() => {})}>去商家工作台</Button>}
      {(phase === 'missing' || phase === 'entry') && <Button className='mmk-ghost' onClick={back}>返回限时秒杀</Button>}
    </View>}
    {phase === 'ready' && <View className='mmfe-edit'>
      {record && record.status === 'REJECTED' && record.rejectReason && <View className='mmfe-banner mmfe-banner-reject' role='alert'>
        <Text className='mmfe-banner-title'>审核驳回</Text>
        <Text className='mmfe-banner-text'>驳回原因：{record.rejectReason}</Text>
        <Text className='mmfe-banner-text'>请按驳回原因修改后重新提交审核。</Text>
      </View>}
      {readonly && <View className='mmfe-banner' role='status'>
        <Text className='mmfe-banner-title-plain'>当前场次为只读展示（{pill?.text}）</Text>
        <Text className='mmfe-banner-text'>{record?.status === 'PENDING_REVIEW' ? '请等待运营审核结果；驳回后可查看原因并修改重提。' : `有效期 ${startAtText} 起，生效与结束由平台按窗口执行。`}</Text>
      </View>}
      <View className='mmk-section'>
        <Text className='mmk-label'>选择服务{!readonly && <Text className='mmk-required'>*</Text>}</Text>
        {selectedService && <View className='mmfe-service'>
          <View className='mmfe-service-icon'><Image src={iconToolFlashSale} mode='aspectFit' /></View>
          <View className='mmfe-service-main'>
            <Text className='mmfe-service-name'>{selectedService.serviceName}</Text>
            <Text className='mmfe-service-price'>当前价 ¥{selectedService.price}</Text>
          </View>
        </View>}
        {!readonly && services.length > 0 && <ScrollView scrollX className='mmfe-service-scroll' showScrollbar={false}>
          <View className='mmfe-service-chips'>
            {services.map(item => <View key={item.serviceId} id={`mmfe-service-${item.serviceId}`}
              className={item.serviceId === form.serviceId ? 'mmfe-chip mmfe-chip-active' : 'mmfe-chip'}
              ariaLabel={`服务 ${item.serviceName} 价格${item.price ?? ''}元`}
              onClick={() => chooseService(item.serviceId)}>
              <Text>{item.serviceName}</Text>
              <Text className='mmfe-chip-price'>¥{item.price}</Text>
            </View>)}
          </View>
        </ScrollView>}
        {!readonly && services.length === 0 && <Text className='mmk-hint'>暂无可参与的 ACTIVE 服务，请先在服务管理完成服务上架。</Text>}
      </View>
      <View className='mmk-section'>
        <Text className='mmk-label'>活动名称{!readonly && <Text className='mmk-required'>*</Text>}</Text>
        <Input id='mmfe-title' className='mmk-edit-input' disabled={readonly} value={form.title} maxlength={100}
          placeholder='默认为服务名，可修改' onInput={event => patch({ title: String(event.detail.value) })} />
      </View>
      <View className='mmk-section'>
        <Text className='mmk-label'>优惠形式</Text>
        <View className='mmfe-chips'>
          {PRICE_MODE_OPTIONS.map(option => <View key={option.value} id={`mmfe-mode-${option.value}`}
            className={form.priceModeLabel === option.value ? 'mmfe-mode-chip mmfe-mode-chip-active' : 'mmfe-mode-chip'}
            onClick={() => { if (!readonly) patch({ priceModeLabel: option.value }) }}>
            <Text>{option.label}</Text>
          </View>)}
        </View>
        <Text className='mmk-hint'>两种形式均为限时折扣价（按折扣计算限时价），仅展示标签不同。</Text>
      </View>
      <View className='mmk-section'>
        <Text className='mmk-label'>优惠力度（折）{!readonly && <Text className='mmk-required'>*</Text>}</Text>
        <Input id='mmfe-rate' className='mmk-edit-input' disabled={readonly} value={form.discountRate} type='digit'
          placeholder='例如：8.8' onInput={event => patch({ discountRate: String(event.detail.value) })} />
        {previewPrice
          ? <Text className='mmfe-preview'>限时价 ¥{previewPrice}（预览，以服务端计算为准）</Text>
          : !readonly && form.discountRate !== '' ? <Text className='mmk-hint'>请输入 0-10 折且最多 1 位小数（如 8.8）。</Text> : null}
      </View>
      <View className='mmk-section'>
        <View className='mmfe-quota-head'>
          <Text className='mmk-label'>发放总量</Text>
          <Text className='mmk-label mmfe-quota-second'>每人限领</Text>
        </View>
        <View className='mmfe-quota-row'>
          <Input id='mmfe-total-quota' className='mmk-edit-input mmfe-quota-input' disabled={readonly} value={form.totalQuota} type='number'
            placeholder='例如：100' onInput={event => patch({ totalQuota: String(event.detail.value) })} />
          <Input id='mmfe-per-user-limit' className='mmk-edit-input mmfe-quota-input' disabled={readonly} value={form.perUserLimit} type='number'
            placeholder='例如：1' onInput={event => patch({ perUserLimit: String(event.detail.value) })} />
        </View>
        <Text className='mmk-hint'>留空表示不限；总量 1-1,000,000，每人限领 1-1,000。</Text>
      </View>
      <View className='mmk-section'>
        <Text className='mmk-label'>活动有效期{!readonly && <Text className='mmk-required'>*</Text>}</Text>
        <View className='mmk-date-row'>
          {readonly
            ? <View className='mmk-date'><Text>{form.startDate || '开始日期'}</Text></View>
            : <Picker mode='date' value={form.startDate} onChange={event => patch({ startDate: String(event.detail.value) })}>
              <View className='mmk-date'><Text>{form.startDate || '开始日期'}</Text><Image className='mmk-date-icon' src={iconDate} mode='aspectFit' /></View>
            </Picker>}
          {readonly
            ? <View className='mmk-date mmk-date-time'><Text>{form.startTime || '时间'}</Text></View>
            : <Picker mode='time' value={form.startTime} onChange={event => patch({ startTime: String(event.detail.value) })}>
              <View className='mmk-date mmk-date-time'><Text>{form.startTime || '时间'}</Text></View>
            </Picker>}
        </View>
        <View className='mmk-date-row'>
          {readonly
            ? <View className='mmk-date'><Text>{form.endDate || '结束日期'}</Text></View>
            : <Picker mode='date' value={form.endDate} onChange={event => patch({ endDate: String(event.detail.value) })}>
              <View className='mmk-date'><Text>{form.endDate || '结束日期'}</Text><Image className='mmk-date-icon' src={iconDate} mode='aspectFit' /></View>
            </Picker>}
          {readonly
            ? <View className='mmk-date mmk-date-time'><Text>{form.endTime || '时间'}</Text></View>
            : <Picker mode='time' value={form.endTime} onChange={event => patch({ endTime: String(event.detail.value) })}>
              <View className='mmk-date mmk-date-time'><Text>{form.endTime || '时间'}</Text></View>
            </Picker>}
        </View>
        <Text className='mmk-hint'>按北京时间选择，提交时转换为 UTC 时间；开始时间需早于结束时间。</Text>
      </View>
      <View className='mmk-section'>
        <Text className='mmk-label'>活动说明<Text className='mmk-counter'>{Array.from(form.description).length}/300</Text></Text>
        <Textarea id='mmfe-description' className='mmk-textarea mmfe-textarea' disabled={readonly} value={form.description} maxlength={300}
          placeholder='介绍活动规则、注意事项等' onInput={event => patch({ description: String(event.detail.value) })} />
      </View>
      {notice ? <View className='mmk-notice' id='mmfe-notice' role='status'><Text>{notice}</Text></View> : null}
      {!readonly && <View className='mmfe-actions'>
        <View className='mmfe-action mmfe-action-secondary' id='mmfe-save-draft' onClick={() => { if (!busy) void saveDraft() }}>
          <Text className={busy ? 'mmfe-action-text mmfe-action-text-secondary mmfe-action-disabled' : 'mmfe-action-text mmfe-action-text-secondary'}>{busy ? '处理中…' : '保存草稿'}</Text>
        </View>
        <View className='mmfe-action' id='mmfe-submit' onClick={() => { if (!busy) void submitForReview() }}>
          <Text className={busy ? 'mmfe-action-text mmfe-action-disabled' : 'mmfe-action-text'}>{busy ? '处理中…' : '提交审核'}</Text>
        </View>
      </View>}
      {canCancel && <View className='mmk-link-action mmfe-cancel' id='mmfe-cancel' onClick={() => void cancelFlash()}>
        <Text>{busy ? '处理中…' : '取消秒杀'}</Text>
      </View>}
      <Text className='mmk-hint mmfe-tail'>提交后由运营审核，审核通过后按有效期自动生效；商家不可直接发布。</Text>
    </View>}
    <View className='mmk-tab-space' />
    <MerchantTabBar active='home' unit={unit} />
  </View>
}
