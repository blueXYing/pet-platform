import { Button, ScrollView, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { ApiError } from '../../../shared/request'
import { switchConsumerTab } from '../../components/navigation/switch'
import { ConsumerPageLayout } from '../../components/page-layout'
import { runCatalogRead } from '../../store/repository'
import { RealStoreRepository } from '../../store/repository'
import { PreviewServiceRepository, formatSalePrice, type ServiceDetailView } from '../../service/model'
import { RealServiceRepository } from '../../api/repositories'
import { PreviewStoreRepository } from '../../store/model'
import { PreviewPetRepository, type PetView } from '../../pet/model'
import { realPetRepository } from '../../api/page-repository'
import {
  PREVIEW_BOOKING_TODAY, PICKUP_SELECTION_ENABLED, availabilityReadMessage, beijingToday,
  bookingCreateMessage, bookingDates, bookingFormError, bookingFormErrorLabels, draftFromCommandData, fulfillmentModes,
  isBookingScenario, paymentDeadline, pickupCandidates, receiptBadge, returnCandidates, slotViews, windowLabel, PreviewBookingRepository,
  type AvailabilityItem, type AvailabilityView, type BookingDate, type BookingDraft, type CreateOrderReceipt,
} from '../../booking/model'
import { ORDER_CREATE_SLOT, RealBookingRepository } from '../../booking/repository'
import './booking.css'

// 预约下单页（10号 §3.4 可约时段 + §3.5 创建订单）。设计源登记表 §4.3：原稿 690:4506 为固定
// 60 分钟槽 + 上门/送回两栏，与已批分钟级 availability 契约（六字段投影、remaining>0 可选、
// 已满置灰「已约满」）语义冲突，视同设计缺稿——本页沿 C 端现行页面规范（orders/coupons tokens）
// 实现，布局参照原稿弹层结构（门店头 + 时段网格 + 底部确认条）。字段以契约为唯一来源：
// 服务/门店名来自 §3.3 只读投影，时段来自 availability（39号 selection 投影带 windowId/kind），
// 宠物来自 pet-archive；优惠券不在本切片（couponInstanceId 不携带，PR 登记边界）。**接送履约
// （PICKUP_DELIVERY）已随 36号公开选窗字段同步切片解锁**（PICKUP_SELECTION_ENABLED=true，
// #128 失败关闭呈现移除）：接送型服务呈现接宠（PICKUP 方向原窗）/送回（RETURN 方向原窗＋
// 120 分钟间隔置灰）两段选窗与接送服务地址（省市区＋详细地址）输入；提交体带双方向选窗 ID
// 与服务地址，全部一致性规则由 36/38号内核锁内复核。提交走幂等槽 order:create
//（23号 X-Request-Id 重放，未确认载荷锁死，页面按原命令还原选择后重试）；成功回执展示
// CreateOrderData 五字段并引导去支付/订单详情；失败逐错误码中文映射。ARCH-005 分工：回执展示
// 状态经 booking/model.receiptBadge 现成展示值，页面不触碰订单事实字段。preview=1 沿本地夹具
// 通道（scenario=pickup 演示接送成功预约）。
type Phase = 'loading' | 'ready' | 'invalid' | 'missing' | 'expired' | 'load-error' | 'receipt'
type SlotPhase = 'loading' | 'ready' | 'empty' | 'error'
const DATE_COUNT = 7

export default function BookingCreatePage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isBookingScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { revision } = useWorkspace(preview ? 'preview' : 'real')
  return <CreateScreen key={revision} preview={preview} scenario={scenario}
    serviceId={route.params.serviceId || ''} storeIdParam={route.params.storeId || ''} />
}

function CreateScreen({ preview, scenario, serviceId, storeIdParam }: { preview: boolean; scenario: string; serviceId: string; storeIdParam: string }) {
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [bookingRepository] = useState(() => new PreviewBookingRepository(isBookingScenario(scenario) ? scenario : 'normal'))
  const [realRepository] = useState(() => new RealBookingRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>(isContractId(serviceId) ? 'loading' : 'invalid')
  const [slotPhase, setSlotPhase] = useState<SlotPhase>('loading')
  const [service, setService] = useState<ServiceDetailView | null>(null)
  const [storeName, setStoreName] = useState<string | null>(null)
  const [pets, setPets] = useState<readonly PetView[]>([])
  const [petId, setPetId] = useState<string | null>(null)
  const [dates, setDates] = useState<readonly BookingDate[]>([])
  const [date, setDate] = useState('')
  const [availability, setAvailability] = useState<AvailabilityView | null>(null)
  const [selectedStart, setSelectedStart] = useState<string | null>(null)
  const [pickupStart, setPickupStart] = useState<string | null>(null)
  const [returnStart, setReturnStart] = useState<string | null>(null)
  const [serviceAddress, setServiceAddress] = useState('')
  const [remark, setRemark] = useState('')
  const [notice, setNotice] = useState('')
  const [slotAuthExpired, setSlotAuthExpired] = useState(false)
  const [busy, setBusy] = useState(false)
  const [receipt, setReceipt] = useState<CreateOrderReceipt | null>(null)
  const mounted = useRef(true)
  const sequence = useRef(0)
  const restored = useRef(false)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--bkg-status-top': `${platformInfo.statusBarHeight || 0}px`, '--bkg-unit': `${unit}px` } as CSSProperties

  const loadService = useCallback(async () => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    if (!isContractId(serviceId)) { setPhase('invalid'); return }
    setNotice('')
    const catalog = preview ? new PreviewServiceRepository() : new RealServiceRepository(consumerApi)
    const petRepository = preview ? new PreviewPetRepository() : realPetRepository()
    const storeRepository = preview ? new PreviewStoreRepository() : new RealStoreRepository(consumerApi, () => Promise.resolve([]))
    try {
      const loaded = await runCatalogRead(scope, () => catalog.detail(serviceId))
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      // preview scenario=pickup：本地投影为接送型，供设计验收通道演示接送成功预约链路。
      const detail = preview && scenario === 'pickup'
        ? { ...loaded, fulfillmentType: 'PICKUP_DELIVERY' as const } : loaded
      if (storeIdParam && detail.storeId !== storeIdParam) { setPhase('invalid'); return }
      setService(detail)
      // 门店名：§3.3.2 匿名详情投影（可选增强，失败不阻断预约，显示门店ID占位）。
      void runCatalogRead(scope, () => storeRepository.detail(detail.storeId))
        .then(store => { if (mounted.current && current === sequence.current) setStoreName(store.storeName) })
        .catch(() => { if (mounted.current && current === sequence.current) setStoreName(null) })
      if (preview) {
        const list = await petRepository.load()
        if (!mounted.current || current !== sequence.current) return
        setPets(list)
        setPetId(previous => previous ?? list.find(pet => pet.isDefault)?.petId ?? list[0]?.petId ?? null)
        setPhase('ready')
        return
      }
      if (!scope.current) { try { await consumerApi.restore() } catch { /* 未登录，走下方登录引导 */ } }
      if (current !== sequence.current || currentRevision !== scope.revision) return
      if (!scope.current || scope.current.workspace !== 'consumer') { setPhase('expired'); return }
      const list = await scope.run(undefined, () => petRepository.load())
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setPets(list)
      setPetId(previous => previous ?? list.find(pet => pet.isDefault)?.petId ?? list[0]?.petId ?? null)
      // 幂等恢复：上次提交结果未确认时按原命令载荷还原选择（23号：同载荷重试同一 X-Request-Id）。
      const pending = consumerApi.pendingCommand(ORDER_CREATE_SLOT)
      const draft = pending ? draftFromCommandData(pending.data) : null
      if (draft && draft.serviceId === serviceId) {
        setNotice('检测到上次提交结果尚未确认，请核对以下预约信息后重试原提交（不会重复创建订单）。')
        if (!restored.current) {
          restored.current = true
          setPetId(draft.petId); setRemark(draft.remark); setServiceAddress(draft.serviceAddress)
          setSelectedStart(draft.appointmentStart || null); setPickupStart(draft.pickupStart); setReturnStart(draft.returnStart)
          const restoredDate = (draft.appointmentStart || draft.pickupStart || '').slice(0, 10)
          if (/^\d{4}-\d{2}-\d{2}$/.test(restoredDate)) {
            setDate(restoredDate)
            setDates(bookingDates(preview ? PREVIEW_BOOKING_TODAY : beijingToday(), DATE_COUNT, restoredDate))
          }
        }
      }
      setPhase('ready')
    } catch (error) {
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      if (error instanceof ApiError && error.statusCode === 404) { setPhase('missing'); return }
      if (error instanceof ApiError && error.statusCode === 401) { setPhase('expired'); return }
      if (error instanceof Error && 'statusCode' in error && (error as { statusCode?: unknown }).statusCode === 404) { setPhase('missing'); return }
      setPhase('load-error')
    }
  }, [preview, scope, serviceId, storeIdParam])

  const loadSlots = useCallback(async (targetDate: string) => {
    if (!service || phase !== 'ready') return
    const currentRevision = scope.revision
    const current = ++sequence.current
    setSlotPhase('loading')
    setSlotAuthExpired(false)
    try {
      const view = preview
        ? await scope.run(undefined, () => bookingRepository.availability(service.serviceId, service.storeId, targetDate))
        : await scope.run(undefined, () => realRepository.availability(service.serviceId, service.storeId, targetDate))
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setAvailability(view)
      setSlotPhase(view.items.length === 0 ? 'empty' : 'ready')
    } catch (error) {
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setAvailability(null)
      setNotice(availabilityReadMessage(error))
      setSlotAuthExpired(error instanceof ApiError && error.statusCode === 401)
      setSlotPhase('error')
    }
  }, [preview, scope, service, phase, bookingRepository, realRepository])

  useEffect(() => {
    mounted.current = true
    const today = preview ? PREVIEW_BOOKING_TODAY : beijingToday()
    setDates(bookingDates(today, DATE_COUNT, today))
    setDate(today)
    if (!preview && !isContractId(serviceId)) { setPhase('invalid'); return }
    void loadService()
    return () => { mounted.current = false; sequence.current++ }
  }, [loadService, preview, serviceId])

  useEffect(() => { if (phase === 'ready' && service && date) void loadSlots(date) }, [phase, service, date, loadSlots])
  useDidShow(() => { if (phase === 'ready' && service) { void loadService(); void loadSlots(date) } })

  function chooseDate(iso: string) {
    if (iso === date || busy) return
    setDate(iso)
    setDates(bookingDates(preview ? PREVIEW_BOOKING_TODAY : beijingToday(), DATE_COUNT, iso))
    setSelectedStart(null); setPickupStart(null); setReturnStart(null)
    void loadSlots(iso)
  }
  const itemByStart = (start: string | null): AvailabilityItem | null =>
    start === null || availability === null ? null : availability.items.find(item => item.start === start) ?? null
  function chooseWindow(item: AvailabilityItem) {
    if (busy || !selectable(item)) return
    setSelectedStart(previous => previous === item.start ? null : item.start)
  }
  function choosePickup(item: AvailabilityItem) {
    if (busy || !selectable(item)) return
    setPickupStart(item.start)
    // 送回联动：不满足 120 分钟间隔的原送回选择自动失效（SCH-D4 C 端置灰联动）。
    if (returnStart !== null && Date.parse(returnStart) < Date.parse(item.start) + 120 * 60000) setReturnStart(null)
  }
  function chooseReturn(item: AvailabilityItem) {
    if (busy || !selectable(item)) return
    if (pickupStart !== null && Date.parse(item.start) < Date.parse(pickupStart) + 120 * 60000) return
    setReturnStart(previous => previous === item.start ? null : item.start)
  }
  async function submit() {
    if (!service || busy || phase !== 'ready') return
    const appointment = itemByStart(selectedStart)
    const pickup = service.fulfillmentType === 'PICKUP_DELIVERY' && PICKUP_SELECTION_ENABLED
    const pickupItem = pickup ? itemByStart(pickupStart) : null
    const returnItem = pickup ? itemByStart(returnStart) : null
    const draft: BookingDraft = {
      storeId: service.storeId, serviceId: service.serviceId, petId: petId || '',
      fulfillmentType: service.fulfillmentType,
      appointmentStart: pickup ? '' : appointment?.start || '',
      appointmentEnd: pickup ? '' : appointment?.end || '',
      pickupStart: pickup ? pickupItem?.start ?? null : null,
      returnStart: pickup ? returnItem?.start ?? null : null,
      selectedPickupWindowId: pickup ? pickupItem?.windowId ?? null : null,
      selectedReturnWindowId: pickup ? returnItem?.windowId ?? null : null,
      serviceAddress: pickup ? serviceAddress : '',
      remark,
    }
    const error = bookingFormError(draft)
    if (error !== null) { setNotice(bookingFormErrorLabels[error]); return }
    if (slotPhase !== 'ready') { setNotice('可约时段尚未就绪，请稍候或重新选择日期'); return }
    setBusy(true); setNotice('')
    try {
      const created = preview
        ? await scope.run(undefined, () => bookingRepository.create(draft))
        : await scope.run(undefined, () => realRepository.create(draft))
      if (!mounted.current) return
      setReceipt(created); setPhase('receipt')
    } catch (error) {
      if (mounted.current) setNotice(bookingCreateMessage(error))
    } finally {
      if (mounted.current) setBusy(false)
    }
  }
  function goPay() {
    if (!receipt) return
    void Taro.navigateTo({ url: `/consumer/pages/booking/pay?${preview ? 'preview=1&' : ''}orderId=${encodeURIComponent(receipt.orderId)}` })
      .catch(() => setNotice('页面跳转失败，请重试'))
  }
  function goOrderDetail() {
    if (!receipt) return
    void Taro.navigateTo({ url: `/consumer/pages/orders/detail?${preview ? 'preview=1&' : ''}orderId=${encodeURIComponent(receipt.orderId)}` })
      .catch(() => setNotice('页面跳转失败，请重试'))
  }
  function goPetForm() {
    void Taro.navigateTo({ url: `/consumer/pages/pet-archive/form${preview ? '?preview=1' : ''}` })
      .catch(() => setNotice('页面跳转失败，请重试'))
  }
  function goLogin() { void Taro.switchTab({ url: '/consumer/pages/mine/index' }) }
  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: `/consumer/pages/store-services/service-detail?${preview ? 'preview=1&' : ''}serviceId=${encodeURIComponent(serviceId)}` })
  }

  const ready = phase === 'ready' && service !== null
  // 接送选窗（36号切片解锁）：接宠 = PICKUP 方向原窗，送回 = RETURN 方向原窗＋120 分钟置灰。
  const pickupFlow = service?.fulfillmentType === 'PICKUP_DELIVERY' && PICKUP_SELECTION_ENABLED
  const items = availability?.items ?? []
  const slots = slotViews(items, selectedStart)
  const pickupSlots = slotViews(pickupCandidates(items), pickupStart)
  const returnItems = pickupStart === null ? [] : returnCandidates(items, pickupStart)
  const returnSlots = pickupStart === null ? [] : slotViews(returnItems, returnStart)
  const appointment = itemByStart(selectedStart)
  const pickupItem = itemByStart(pickupStart)
  const returnItem = itemByStart(returnStart)
  const chosenPet = pets.find(pet => pet.petId === petId) || null
  const badge = receipt === null ? null : receiptBadge(receipt)

  return <ConsumerPageLayout page='bookingCreate' unit={unit} className='bkg-page' style={style}
    navigation={{ idPrefix: 'bkg', disabled: phase === 'loading', onSelect: key => { void switchConsumerTab(key) } }}>
    <View className='bkg-design'>
      <View className='bkg-status-area' />
      <View className='bkg-nav'>
        <Button id='bkg-back' ariaLabel='返回' className='bkg-nav-back' onClick={() => void goBack()}><Text className='bkg-nav-back-icon'>‹</Text></Button>
        <Text className='bkg-nav-title'>立即预约</Text>
      </View>
      {phase === 'loading' && <View className='bkg-state' role='status'><Text id='bkg-loading'>正在读取服务与可约时段…</Text></View>}
      {phase === 'invalid' && <View className='bkg-state' role='status'><Text id='bkg-invalid'>服务参数无效，请从服务详情重新进入。</Text></View>}
      {phase === 'missing' && <View className='bkg-state' role='status'>
        <Text id='bkg-missing'>服务不存在或不可预约。</Text>
        <Button id='bkg-back-list' className='bkg-state-action' onClick={() => void goBack()}>返回上一页</Button>
      </View>}
      {phase === 'expired' && <View className='bkg-state' role='status'>
        <Text id='bkg-login-hint'>预约需要登录，登录后可选择宠物与可约时段。</Text>
        <Button id='bkg-login' className='bkg-state-action' onClick={goLogin}>去登录</Button>
      </View>}
      {phase === 'load-error' && <View className='bkg-state' role='status'>
        <Text id='bkg-error'>服务或宠物档案读取失败，请稍后重试。</Text>
        <Button id='bkg-retry-load' className='bkg-state-action' onClick={() => void loadService()}>重新加载</Button>
      </View>}
      {phase === 'receipt' && receipt && badge && <View className='bkg-body'>
        <View className='bkg-service-card'>
          <View className='bkg-card-head'>
            <Text className='bkg-card-no'>订单号 {receipt.orderNo}</Text>
            <Text className={`bkg-badge ${badge.className}`}>{badge.label}</Text>
          </View>
          <Text className='bkg-summary-amount'>¥{receipt.payAmount}</Text>
          <Text className='bkg-hint'>支付截止 {paymentDeadline(receipt)}</Text>
          <Text className='bkg-hint'>订单已创建，请在截止时间前完成支付；逾期订单将自动关闭。</Text>
        </View>
        <Button id='bkg-go-pay' className='bkg-primary' onClick={goPay}>去支付 ¥{receipt.payAmount}</Button>
        <Button id='bkg-go-order' className='bkg-secondary' onClick={goOrderDetail}>查看订单详情</Button>
        <View className='bkg-preview-note'><Text>{preview ? '只读预览：本地样例回执，不发起真实请求。' : '页面数据：真实接口（创建订单回执）。'}</Text></View>
      </View>}
      {ready && <View className='bkg-body'>
        <View className='bkg-service-card'>
          <View className='bkg-card-head'>
            <Text className='bkg-service-name'>{service.serviceName}</Text>
            <Text className='bkg-service-price'>¥{formatSalePrice(service.salePrice)}</Text>
          </View>
          <Text className='bkg-hint'>{storeName ?? `门店 ${service.storeId}`} · {service.categoryName} · 时长{service.durationMinutes}分钟 · {pickupFlow ? '上门接送' : '到店服务'}</Text>
        </View>
        <View className='bkg-section'>
          <Text className='bkg-section-title'>履约方式</Text>
          <View className='bkg-modes'>
            {fulfillmentModes(service.fulfillmentType).map(mode => <View key={mode.id} id={`bkg-mode-${mode.id}`}
              className={mode.className} ariaLabel={`${mode.label}，${mode.note}`}>
              <Text className='bkg-mode-label'>{mode.label}</Text>
              <Text className='bkg-mode-note'>{mode.note}</Text>
            </View>)}
          </View>
        </View>
        <View className='bkg-section'>
          <Text className='bkg-section-title'>选择日期</Text>
          <ScrollView scrollX className='bkg-dates'>
            {dates.map(item => <Button key={item.iso} className={item.className} ariaLabel={`${item.label} ${item.weekday}`} disabled={busy} onClick={() => chooseDate(item.iso)}>
              <Text className='bkg-date-label'>{item.label}</Text>
              <Text className='bkg-date-weekday'>{item.weekday}</Text>
            </Button>)}
          </ScrollView>
        </View>
        <View className='bkg-section'>
          <Text className='bkg-section-title'>{pickupFlow ? '选择接宠时段' : '选择预约时段'}</Text>
          {slotPhase === 'loading' && <Text className='bkg-hint' id='bkg-slots-loading'>正在读取可约时段…</Text>}
          {slotPhase === 'empty' && <Text className='bkg-hint' id='bkg-slots-empty'>当日暂无可约时段，请选择其他日期。</Text>}
          {slotPhase === 'error' && <View className='bkg-slot-error' role='status'>
            <Text id='bkg-slots-error'>{notice || '可约时段读取失败，请稍后重试。'}</Text>
            <Button id='bkg-slots-retry' className='bkg-state-action' onClick={() => void loadSlots(date)}>重新读取时段</Button>
            {slotAuthExpired && <Button id='bkg-slots-login' className='bkg-state-action' onClick={() => void Taro.switchTab({ url: '/consumer/pages/mine/index' })}>去登录</Button>}
          </View>}
          {(slotPhase === 'ready' || slotPhase === 'empty') && <View className='bkg-slots'>
            {(pickupFlow ? pickupSlots : slots).map(slot => <Button
              key={slot.key} id={`${pickupFlow ? 'bkg-pickup' : 'bkg-slot'}-${slot.key}`} className={slot.className}
              ariaLabel={`${pickupFlow ? '接宠' : ''}${slot.label} ${slot.note}`}
              disabled={busy || !slot.selectable}
              onClick={() => { const item = itemByStart(slot.key); if (!item) return; if (pickupFlow) choosePickup(item); else chooseWindow(item) }}>
              <Text className='bkg-slot-time'>{slot.label}</Text>
              <Text className='bkg-slot-note'>{slot.note}</Text>
            </Button>)}
          </View>}
          {slotPhase === 'ready' && pickupFlow && <>
            <Text className='bkg-section-subtitle'>选择送回时段（需不早于接宠后 120 分钟）</Text>
            <View className='bkg-slots'>
              {pickupStart === null
                ? <Text className='bkg-hint' id='bkg-return-hint'>请先选择接宠时段。</Text>
                : returnSlots.map(slot => <Button key={`r-${slot.key}`} className={slot.className} ariaLabel={`送回 ${slot.label} ${slot.note}`}
                  disabled={busy || !slot.selectable} onClick={() => { const item = returnItems.find(candidate => candidate.start === slot.key); if (item) chooseReturn(item) }}>
                  <Text className='bkg-slot-time'>{slot.label}</Text>
                  <Text className='bkg-slot-note'>{slot.note}</Text>
                </Button>)}
            </View>
          </>}
        </View>
        <View className='bkg-section'>
          <Text className='bkg-section-title'>选择宠物</Text>
          {pets.length === 0 && <View className='bkg-slot-error' role='status'>
            <Text id='bkg-no-pet'>暂无可用宠物档案，预约前请先创建。</Text>
            <Button id='bkg-add-pet' className='bkg-state-action' onClick={goPetForm}>去添加宠物档案</Button>
          </View>}
          {pets.length > 0 && <View className='bkg-pets'>
            {pets.map(pet => <Button key={pet.petId} id={`bkg-pet-${pet.petId}`} className={`bkg-pet${pet.petId === petId ? ' is-selected' : ''}`}
              ariaLabel={`宠物 ${pet.name}`} disabled={busy} onClick={() => setPetId(pet.petId)}>
              <Text className='bkg-pet-name'>{pet.name}</Text>
              <Text className='bkg-pet-note'>{petLine(pet)}</Text>
            </Button>)}
          </View>}
        </View>
        <View className='bkg-section'>
          <Text className='bkg-section-title'>备注（选填）</Text>
          <Textarea id='bkg-remark' className='bkg-textarea' maxlength={500} value={remark} disabled={busy}
            placeholder='例如：怕生，请提前沟通' onInput={event => setRemark(event.detail.value)} />
        </View>
        {pickupFlow && <View className='bkg-section'>
          <Text className='bkg-section-title'>接送服务地址（必填）</Text>
          <Textarea id='bkg-address' className='bkg-textarea' maxlength={65536} value={serviceAddress} disabled={busy}
            placeholder='省市区＋详细地址，例如：上海市徐汇区某路100弄5号201室' onInput={event => setServiceAddress(event.detail.value)} />
        </View>}
        <View className='bkg-section bkg-confirm-card'>
          <Text className='bkg-section-title'>确认信息</Text>
          <View className='bkg-fact-row'><Text className='bkg-fact-label'>服务</Text><Text className='bkg-fact-value'>{service.serviceName}</Text></View>
          <View className='bkg-fact-row'><Text className='bkg-fact-label'>门店</Text><Text className='bkg-fact-value'>{storeName ?? service.storeId}</Text></View>
          <View className='bkg-fact-row'><Text className='bkg-fact-label'>宠物</Text><Text className='bkg-fact-value'>{chosenPet ? `${chosenPet.name}（${petTypeLabel(chosenPet)}）` : '—'}</Text></View>
          {!pickupFlow && <View className='bkg-fact-row'><Text className='bkg-fact-label'>服务时段</Text><Text className='bkg-fact-value'>{appointment ? windowLabel(appointment.start, appointment.end) : '—'}</Text></View>}
          {pickupFlow && <View className='bkg-fact-row'><Text className='bkg-fact-label'>接宠时段</Text><Text className='bkg-fact-value'>{pickupItem ? windowLabel(pickupItem.start, pickupItem.end) : '—'}</Text></View>}
          {pickupFlow && <View className='bkg-fact-row'><Text className='bkg-fact-label'>送回时段</Text><Text className='bkg-fact-value'>{returnItem ? windowLabel(returnItem.start, returnItem.end) : '—'}</Text></View>}
          {pickupFlow && <View className='bkg-fact-row'><Text className='bkg-fact-label'>服务地址</Text><Text className='bkg-fact-value'>{serviceAddress.trim() || '—'}</Text></View>}
          <View className='bkg-fact-row'><Text className='bkg-fact-label'>预约费用</Text><Text className='bkg-fact-value bkg-fact-amount'>¥{service.salePrice}</Text></View>
        </View>
        {notice && <Text id='bkg-notice' className='bkg-notice'>{notice}</Text>}
        <View className='bkg-footer'>
          <View className='bkg-footer-amount'><Text className='bkg-footer-label'>预约费用</Text><Text className='bkg-footer-price'>¥{service.salePrice}</Text></View>
          <Button id='bkg-submit' className='bkg-footer-action' disabled={busy} onClick={() => void submit()}>{busy ? '正在提交…' : `确认预约 · ¥${service.salePrice}`}</Button>
        </View>
        <View className='bkg-preview-note'><Text>{preview ? '只读预览：本地样例数据，仅用于设计验收，不发起真实请求。' : '页面数据：真实接口（可约时段 + 宠物档案 + 创建订单）。'}</Text></View>
      </View>}
    </View>
  </ConsumerPageLayout>
}

const selectable = (item: AvailabilityItem): boolean => item.available && item.remainingCapacity > 0
const petTypeLabel = (pet: PetView): string => pet.petType === 'DOG' ? '狗' : pet.petType === 'CAT' ? '猫' : '其他'
const petLine = (pet: PetView): string => [pet.breedName || '', petTypeLabel(pet)].filter(Boolean).join(' · ')
function isContractId(value: string): boolean {
  return /^[1-9][0-9]{0,18}$/.test(value) && (value.length < 19 || BigInt(value) <= 9223372036854775807n)
}
