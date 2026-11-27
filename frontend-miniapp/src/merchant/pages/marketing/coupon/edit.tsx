import { Button, Image, Input, Picker, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { consumerApi, consumerSession } from '../../../../shared/consumer-runtime'
import { MerchantCouponApi, type CouponCampaignInput, type CouponCampaignRecord, type CouponType } from '../../../marketing/coupon-api'
import {
  COUPON_TYPE_CHIPS, beijingDateText, beijingDayToUtcIso, couponEditable, couponMessage, couponStatusPill,
  moneyAtLeast, normalizeMoneyInput, parseCountInput,
} from '../../../marketing/coupon-model'
import { MerchantTabBar } from '../../../components/tab-bar'
import navBack from '../../aftersale/assets/nav-back@3x.png'
import iconDate from '../assets/icon-date@3x.png'
import '../index.css'
import './edit.css'

// 创建/编辑优惠券活动（Figma 12:8575，79号 §3 / 41号 §2+§5 / CCR-MKT-MERCHANT-COUPON-001）：
// - 「活动头图」不做（CCR 不做清单）；「立即上架」开关不做（41号 §2.1 商家不可直接发布），
//   改为一行审核说明；按钮为「保存草稿 / 提交审核」（提交前二次确认）；
// - 优惠形式四 chip：满减券/立减券可选，折扣券/兑换券置灰「暂不支持」（79号 §9 不做）；
//   满减券需填使用门槛（thresholdAmount），立减券无门槛（请求省略 thresholdAmount）；
// - 预算（元）为 41号 §5.4 产品规则要求，设计稿无此字段，加在每人限领之下；
// - 有效期按北京时间日期选择：开始日 00:00:00 / 结束日 23:59:59 → UTC ISO 提交；
// - 金额一律字符串两位小数（整数分处理，不做浮点换算）；仅 DRAFT/REJECTED 可编辑；
// - 写命令走 ConsumerApi.write 槽位（同一 X-Request-Id 重放，创建结果未确认按原载荷还原）。
const api = new MerchantCouponApi(consumerApi)
// 详情 404/越界会抛 ApiError（couponMessage 含「活动不存在或不属于本店」），走 load-error 收口。
type Phase = 'idle' | 'loading' | 'ready' | 'load-error' | 'entry'
type CouponForm = {
  name: string; couponType: CouponType
  amountOff: string; thresholdAmount: string
  totalLimit: string; perUserLimit: string; budgetLimit: string
  startDate: string; endDate: string; description: string
}
const emptyForm: CouponForm = {
  name: '', couponType: 'THRESHOLD', amountOff: '', thresholdAmount: '',
  totalLimit: '', perUserLimit: '', budgetLimit: '', startDate: '', endDate: '', description: '',
}

function useMerchant() {
  const context = consumerApi.scope.current
  if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) return null
  return { merchantId: context.merchantId, storeId: context.storeId }
}

export default function MerchantCouponEditPage() {
  const route = useRouter()
  const campaignIdParam = route.params.id || ''
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = {
    '--mmk-unit': `${info.windowWidth / 402}px`,
    '--mmk-status-top': `${info.statusBarHeight || 0}px`,
  } as CSSProperties
  const [merchant] = useState(() => useMerchant())
  const [phase, setPhase] = useState<Phase>('idle')
  const [record, setRecord] = useState<CouponCampaignRecord | null>(null)
  const [form, setForm] = useState<CouponForm>(emptyForm)
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
      let loaded: CouponCampaignRecord | null = null
      if (campaignIdParam) {
        loaded = await api.detail(merchant.merchantId, merchant.storeId, campaignIdParam)
      }
      if (!mounted.current || sequence !== loadSequence.current || revision !== consumerApi.scope.revision) return
      setRecord(loaded)
      if (loaded) {
        version.current = loaded.version; createdId.current = loaded.campaignId
        setForm({
          name: loaded.name, couponType: loaded.couponType,
          amountOff: loaded.amountOff, thresholdAmount: loaded.thresholdAmount ?? '',
          totalLimit: String(loaded.totalLimit), perUserLimit: String(loaded.perUserLimit),
          budgetLimit: loaded.budgetLimit,
          startDate: beijingDateText(loaded.startAt), endDate: beijingDateText(loaded.endAt),
          description: loaded.description ?? '',
        })
      } else {
        version.current = ''; createdId.current = ''
        setForm(emptyForm)
        // 幂等恢复：创建结果未确认时按原命令载荷还原表单（同载荷重试同一 X-Request-Id）。
        const pending = consumerApi.pendingCommand(`merchant-coupon-create:${merchant.merchantId}`)
        if (pending) {
          const data = pending.data as Record<string, unknown>
          const threshold = typeof data.thresholdAmount === 'string' ? data.thresholdAmount : ''
          setForm({
            ...emptyForm,
            name: typeof data.name === 'string' ? data.name : '',
            couponType: threshold === '' ? 'NO_THRESHOLD' : 'THRESHOLD',
            amountOff: typeof data.amountOff === 'string' ? data.amountOff : '',
            thresholdAmount: threshold,
            totalLimit: typeof data.totalLimit === 'number' ? String(data.totalLimit) : '',
            perUserLimit: typeof data.perUserLimit === 'number' ? String(data.perUserLimit) : '',
            budgetLimit: typeof data.budgetLimit === 'string' ? data.budgetLimit : '',
            startDate: typeof data.startAt === 'string' ? beijingDateText(data.startAt) : '',
            endDate: typeof data.endAt === 'string' ? beijingDateText(data.endAt) : '',
            description: typeof data.description === 'string' ? data.description : '',
          })
          setNotice('检测到上次提交结果尚未确认，请核对以下内容后重试原提交（不会重复创建）。')
        }
      }
      setPhase('ready')
    } catch (error) {
      if (!mounted.current || sequence !== loadSequence.current || revision !== consumerApi.scope.revision) return
      setNotice(couponMessage(error)); setPhase('load-error')
    }
  }, [merchant, campaignIdParam])
  useDidShow(() => {
    void (async () => {
      try { await consumerSession.launch() } catch { /* load() renders the error state */ }
      await load()
    })()
  })
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; loadSequence.current++ } }, [])

  const editing = record !== null
  const readonly = editing && !couponEditable(record.status)
  const pill = record ? couponStatusPill(record.status) : null

  function patch(next: Partial<CouponForm>) { setForm(current => ({ ...current, ...next })) }

  function chooseType(next: CouponType) {
    if (readonly || busy || next === form.couponType) return
    // 切到无门槛券清空门槛输入（请求里 thresholdAmount 省略），切回满减券重新填写。
    patch(next === 'NO_THRESHOLD' ? { couponType: next, thresholdAmount: '' } : { couponType: next })
  }

  function back() {
    // P-03 追缉（2026-11-12）：商家域返回不用 navigateBack（隐藏页树可能已被 dispose）。
    Taro.reLaunch({ url: '/merchant/pages/marketing/coupon/index' }).catch(() => { void Taro.showToast({ title: '返回失败，请重试', icon: 'none' }) })
  }

  function formProblems(): string[] {
    const problems: string[] = []
    const nameLength = Array.from(form.name.trim()).length
    if (nameLength < 2 || nameLength > 50) problems.push('活动名称需 2-50 字')
    if (normalizeMoneyInput(form.amountOff) === null) problems.push('优惠力度需为大于 0 的金额，最多两位小数')
    if (form.couponType === 'THRESHOLD' && normalizeMoneyInput(form.thresholdAmount) === null) problems.push('使用门槛需为大于 0 的金额，最多两位小数')
    if (parseCountInput(form.totalLimit, 1, 1000000) === null) problems.push('发放总量需为 1-1,000,000 的正整数')
    if (parseCountInput(form.perUserLimit, 1, 10) === null) problems.push('每人限领需为 1-10 的正整数')
    const totalLimit = parseCountInput(form.totalLimit, 1, 1000000)
    const perUserLimit = parseCountInput(form.perUserLimit, 1, 10)
    if (totalLimit !== null && perUserLimit !== null && perUserLimit > totalLimit) problems.push('每人限领不能大于发放总量')
    const budget = normalizeMoneyInput(form.budgetLimit)
    const amountOff = normalizeMoneyInput(form.amountOff)
    if (budget === null) problems.push('预算需为大于 0 的金额，最多两位小数')
    else if (amountOff !== null && !moneyAtLeast(budget, amountOff)) problems.push('预算不能小于优惠力度')
    if (!form.startDate || !form.endDate) problems.push('请选择活动有效期')
    else if (!(beijingDayToUtcIso(form.startDate) < beijingDayToUtcIso(form.endDate, true))) problems.push('开始日期需早于结束日期')
    return problems
  }

  function buildInput(): CouponCampaignInput {
    return {
      name: form.name.trim(),
      amountOff: normalizeMoneyInput(form.amountOff)!,
      thresholdAmount: form.couponType === 'THRESHOLD' ? normalizeMoneyInput(form.thresholdAmount)! : undefined,
      totalLimit: parseCountInput(form.totalLimit, 1, 1000000)!,
      perUserLimit: parseCountInput(form.perUserLimit, 1, 10)!,
      budgetLimit: normalizeMoneyInput(form.budgetLimit)!,
      startAt: beijingDayToUtcIso(form.startDate),
      endAt: beijingDayToUtcIso(form.endDate, true),
      description: form.description.trim() || undefined,
    }
  }

  async function persist(): Promise<boolean> {
    if (!merchant) return false
    const problems = formProblems()
    if (problems.length) { setNotice(`请检查：${problems.join('；')}`); return false }
    const input = buildInput()
    const saved = createdId.current
      ? await api.update(merchant.merchantId, merchant.storeId, createdId.current, input, version.current)
      : await api.create(merchant.merchantId, merchant.storeId, input)
    createdId.current = saved.campaignId
    version.current = saved.version
    return true
  }

  function returnToList() {
    if (!mounted.current) return
    void Taro.reLaunch({ url: '/merchant/pages/marketing/coupon/index' })
  }

  async function saveDraft() {
    if (readonly || busy) return
    setNotice(''); setBusy(true)
    try {
      if (await persist()) {
        await Taro.showToast({ title: '草稿已保存', icon: 'none' })
        setTimeout(returnToList, 600)
      }
    } catch (error) { setNotice(couponMessage(error)) } finally { setBusy(false) }
  }

  async function submitForReview() {
    if (readonly || busy) return
    const problems = formProblems()
    if (problems.length) { setNotice(`请检查：${problems.join('；')}`); return }
    // 41号 §2.1：商家不可直接发布；提交后由运营审核，审核通过后展示。
    const confirmed = await Taro.showModal({ title: '提交审核', content: '提交后由运营审核，审核通过后展示', confirmText: '提交审核', cancelText: '继续编辑' })
    if (!confirmed.confirm) return
    setNotice(''); setBusy(true)
    try {
      if (!await persist()) return
      if (!merchant || !createdId.current) return
      await api.submit(createdId.current, version.current)
      await Taro.showToast({ title: '已提交审核', icon: 'none' })
      setTimeout(returnToList, 600)
    } catch (error) { setNotice(couponMessage(error)) } finally { setBusy(false) }
  }

  return <View className='mmk-page' style={style}>
    <View className='mmk-status' />
    <View className='mmk-header'>
      <Button className='mmk-back' ariaLabel='返回' onClick={back}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mmk-title'>{editing ? (readonly ? '优惠券详情' : '编辑优惠券活动') : '创建优惠券活动'}</Text>
    </View>
    {phase !== 'ready' && <View className='mmk-empty' role='status'>
      <Text>{phase === 'loading' || phase === 'idle' ? '正在加载…' : phase === 'entry' ? '请从商家工作台进入营销工具。' : notice || '加载失败，请重试。'}</Text>
      {phase === 'load-error' && <Button className='mmk-primary' onClick={() => void load()}>重新加载</Button>}
      {phase === 'load-error' && <Button className='mmk-ghost' onClick={back}>返回优惠券</Button>}
      {phase === 'entry' && <Button className='mmk-primary' onClick={() => Taro.reLaunch({ url: '/merchant/pages/workspace/index' }).catch(() => {})}>去商家工作台</Button>}
      {phase === 'entry' && <Button className='mmk-ghost' onClick={back}>返回优惠券</Button>}
    </View>}
    {phase === 'ready' && <View className='mmce-edit'>
      {record && record.status === 'REJECTED' && record.rejectReason && <View className='mmce-banner mmce-banner-reject' role='alert'>
        <Text className='mmce-banner-title'>审核驳回</Text>
        <Text className='mmce-banner-text'>驳回原因：{record.rejectReason}</Text>
        <Text className='mmce-banner-text'>请按驳回原因修改后重新提交审核。</Text>
      </View>}
      {readonly && <View className='mmce-banner' role='status'>
        <Text className='mmce-banner-title-plain'>当前活动为只读展示（{pill?.text}）</Text>
        <Text className='mmce-banner-text'>{record?.status === 'PENDING_REVIEW' ? '请等待运营审核结果；驳回后可查看原因并修改重提。' : '该活动已进入平台流程，商家不可修改；已发放的券按券面有效期继续有效。'}</Text>
      </View>}
      <View className='mmk-section'>
        <Text className='mmk-label'>活动名称{!readonly && <Text className='mmk-required'>*</Text>}</Text>
        <Input id='mmce-name' className='mmk-edit-input' disabled={readonly} value={form.name} maxlength={50}
          placeholder='例如：新客立减20元券' onInput={event => patch({ name: String(event.detail.value) })} />
      </View>
      <View className='mmk-section'>
        <Text className='mmk-label'>优惠形式</Text>
        <View className='mmce-chips'>
          {COUPON_TYPE_CHIPS.map(chip => <View key={chip.label} id={`mmce-type-${chip.label}`}
            className={chip.available ? (form.couponType === chip.value ? 'mmce-chip mmce-chip-active' : 'mmce-chip') : 'mmce-chip mmce-chip-disabled'}
            onClick={() => { if (chip.available) chooseType(chip.value!) }}>
            <Text>{chip.label}</Text>
            {chip.hint ? <Text className='mmce-chip-hint'>{chip.hint}</Text> : null}
          </View>)}
        </View>
        <Text className='mmk-hint'>{form.couponType === 'THRESHOLD' ? '满减券：需填写使用门槛，满 X 元可用。' : '立减券：无门槛，本店服务通用。'}</Text>
      </View>
      <View className='mmk-section'>
        <Text className='mmk-label'>优惠力度（元）{!readonly && <Text className='mmk-required'>*</Text>}</Text>
        <Input id='mmce-amount-off' className='mmk-edit-input' disabled={readonly} value={form.amountOff} type='digit'
          placeholder='例如：20' onInput={event => patch({ amountOff: String(event.detail.value) })} />
      </View>
      {form.couponType === 'THRESHOLD' && <View className='mmk-section'>
        <Text className='mmk-label'>使用门槛（满 X 元可用）{!readonly && <Text className='mmk-required'>*</Text>}</Text>
        <Input id='mmce-threshold' className='mmk-edit-input' disabled={readonly} value={form.thresholdAmount} type='digit'
          placeholder='例如：100' onInput={event => patch({ thresholdAmount: String(event.detail.value) })} />
      </View>}
      <View className='mmk-section'>
        <View className='mmce-quota-head'>
          <Text className='mmk-label'>发放总量{!readonly && <Text className='mmk-required'>*</Text>}</Text>
          <Text className='mmk-label mmce-quota-second'>每人限领{!readonly && <Text className='mmk-required'>*</Text>}</Text>
        </View>
        <View className='mmce-quota-row'>
          <Input id='mmce-total-limit' className='mmk-edit-input mmce-quota-input' disabled={readonly} value={form.totalLimit} type='number'
            placeholder='例如：100' onInput={event => patch({ totalLimit: String(event.detail.value) })} />
          <Input id='mmce-per-user-limit' className='mmk-edit-input mmce-quota-input' disabled={readonly} value={form.perUserLimit} type='number'
            placeholder='例如：1' onInput={event => patch({ perUserLimit: String(event.detail.value) })} />
        </View>
        <Text className='mmk-hint'>总量 1-1,000,000；每人限领 1-10，且不能大于发放总量。</Text>
      </View>
      <View className='mmk-section'>
        <Text className='mmk-label'>预算（元）{!readonly && <Text className='mmk-required'>*</Text>}</Text>
        <Input id='mmce-budget' className='mmk-edit-input' disabled={readonly} value={form.budgetLimit} type='digit'
          placeholder='例如：2000' onInput={event => patch({ budgetLimit: String(event.detail.value) })} />
        <Text className='mmk-hint'>即最多发放的优惠总金额，用完即止；不能小于优惠力度。</Text>
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
        <Text className='mmk-hint'>按北京时间选择，提交时转换为 UTC 时间；有效期既是领取期也是券有效期。</Text>
      </View>
      <View className='mmk-section'>
        <Text className='mmk-label'>活动说明<Text className='mmk-counter'>{Array.from(form.description).length}/300</Text></Text>
        <Textarea id='mmce-description' className='mmk-textarea mmce-textarea' disabled={readonly} value={form.description} maxlength={300}
          placeholder='介绍活动规则、适用服务、注意事项等' onInput={event => patch({ description: String(event.detail.value) })} />
      </View>
      <View className='mmce-review-note' role='status'>
        <Text className='mmce-review-title'>提交审核</Text>
        <Text className='mmce-review-text'>提交后由运营审核，审核通过后生效；商家不可直接发布。</Text>
      </View>
      {notice ? <View className='mmk-notice' id='mmce-notice' role='status'><Text>{notice}</Text></View> : null}
      {!readonly && <View className='mmce-actions'>
        <View className='mmce-action mmce-action-secondary' id='mmce-save-draft' onClick={() => { if (!busy) void saveDraft() }}>
          <Text className={busy ? 'mmce-action-text mmce-action-text-secondary mmce-action-disabled' : 'mmce-action-text mmce-action-text-secondary'}>{busy ? '处理中…' : '保存草稿'}</Text>
        </View>
        <View className='mmce-action' id='mmce-submit' onClick={() => { if (!busy) void submitForReview() }}>
          <Text className={busy ? 'mmce-action-text mmce-action-disabled' : 'mmce-action-text'}>{busy ? '处理中…' : '提交审核'}</Text>
        </View>
      </View>}
      <Text className='mmk-hint mmce-tail'>领取与核销数据以平台统计为准；已发放的券在活动结束后仍按券面有效期继续有效。</Text>
    </View>}
    <View className='mmk-tab-space' />
    <MerchantTabBar active='home' unit={unit} />
  </View>
}
