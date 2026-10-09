import { Button, Image, ScrollView, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { ApiError } from '../../../shared/request'
import { runCatalogRead, RealStoreRepository } from '../../store/repository'
import { PreviewServiceRepository, type ServiceDetailView } from '../../service/model'
import { usableCoverUrl } from '../../service/cover'
import { RealServiceRepository } from '../../api/repositories'
import { PreviewStoreRepository } from '../../store/model'
import { PreviewPetRepository, type PetView } from '../../pet/model'
import { realPetRepository } from '../../api/page-repository'
import {
  PREVIEW_BOOKING_TODAY, PICKUP_SELECTION_ENABLED, availabilityReadMessage, beijingToday,
  bookingCreateMessage, bookingDates, bookingFormError, bookingFormErrorLabels, bookingSheetTitle, draftFromCommandData, paymentDeadline,
  pickupCandidates, receiptBadge, returnCandidates, slotViews, windowLabel, PreviewBookingRepository,
  type AvailabilityItem, type AvailabilityView, type BookingDate, type BookingDraft, type BookingScenario, type CreateOrderReceipt,
} from '../../booking/model'
import { ORDER_CREATE_SLOT, RealBookingRepository } from '../../booking/repository'
import './booking-sheet.css'

// 预约下单半屏弹层（Figma 690:4506「立即预约」原稿形态：服务详情页/门店页之上的白色半屏
// 底部弹层）。业务逻辑全部由 booking/create 整页切片平移而来（状态自包含）：
// - 日期条/可约时段读取（§3.4 availability 六字段投影，分钟级窗口）；
// - 接送（PICKUP_DELIVERY）双时段选窗（接宠/送回，送回 >= 接宠+120 分钟置灰联动）；
// - 宠物选择/备注/接送地址/确认信息；
// - 幂等提交与恢复（order:create 槽 23号 X-Request-Id 重放，未确认载荷还原选择后重试）；
// - 成功回执（CreateOrderData 五字段）+ 去支付/订单详情入口（支付链路不变）。
// 原稿时段为固定 60 分钟槽，与已批分钟级 availability 契约冲突，沿既有登记视同设计缺稿：
// 时段格仍渲染契约窗口（slotViews 现成展示值），配色/几何按 690:4506（#f0fbff 底、
// #85d2f3 选中、#d3b7a6 置灰、#5baae8 确认按钮，PIL 对渲染图采样核对）。
// create 直连页（开发者工具/preview 夹具通道）与 store-services 两个宿主页共用本组件；
// idPrefix 默认 bks，直连通道传 bkg 保留原 id 族。WXSS 铁律：原生 button 不嵌套 button
// （行根节点用 View）；button 重置不用 margin !important；优先级用 .bks-sheet 两段
// 选择器；状态仅 className 变体，禁 data-*；金额两位小数原样渲染。
type Phase = 'loading' | 'ready' | 'invalid' | 'missing' | 'expired' | 'load-error' | 'receipt'
type SlotPhase = 'loading' | 'ready' | 'empty' | 'error'
const DATE_COUNT = 7

export type BookingSheetProps = Readonly<{
  /** 契约服务 ID（弹层自行读取 §3.3 详情投影，两宿主页均只携上下文进入）。 */
  serviceId: string
  /** 门店上下文校验（宿主页已知的 storeId；详情/列表行携带，不符按参数无效呈现）。 */
  storeId?: string
  /** preview=1 本地夹具通道（设计验收，不发起真实请求）。 */
  preview: boolean
  /** preview 场景（normal/pickup 等；仅 preview 生效）。 */
  scenario?: BookingScenario
  /** 关闭弹层（遮罩/×/不可预约态返回；宿主页自行决定退路）。 */
  onClose: () => void
  /** aria/id 前缀：宿主页内唯一即可，create 直连通道传 'bkg' 保留原 id 族。 */
  idPrefix?: string
}>

export function BookingSheet({ serviceId, storeId: storeIdParam, preview, scenario = 'normal', onClose, idPrefix = 'bks' }: BookingSheetProps) {
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [bookingRepository] = useState(() => new PreviewBookingRepository(scenario))
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
  const [coverNow, setCoverNow] = useState(Date.now)
  const mounted = useRef(true)
  const sequence = useRef(0)
  const restored = useRef(false)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--bks-unit': `${unit}px` } as CSSProperties

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
      setCoverNow(Date.now())
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
  }, [preview, scenario, scope, serviceId, storeIdParam])

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
  const dateView = dates.find(item => item.iso === date) || null
  const coverUrl = service === null ? null : usableCoverUrl(service.cover, coverNow)
  const sheetTitle = service === null ? '立即预约' : bookingSheetTitle(service.fulfillmentType)

  return <View className='bks-mask' style={style} onClick={onClose} catchMove>
    <View className='bks-sheet' onClick={event => event.stopPropagation()} catchMove>
      <View className='bks-head'>
        <Text className='bks-title'>{sheetTitle}</Text>
        <Button id={`${idPrefix}-close`} ariaLabel='关闭' className='bks-close' hoverClass='none' onClick={onClose}>
          <Text>×</Text>
        </Button>
      </View>
      <ScrollView className='bks-body' scrollY enhanced showScrollbar={false}>
        <View className='bks-body-inner'>
          {phase === 'loading' && <View className='bks-state' role='status'><Text id={`${idPrefix}-loading`}>正在读取服务与可约时段…</Text></View>}
          {phase === 'invalid' && <View className='bks-state' role='status'>
            <Text id={`${idPrefix}-invalid`}>服务参数无效，请从服务详情重新进入。</Text>
            <View className='bks-action'><Button id={`${idPrefix}-back`} className='bks-state-action' hoverClass='none' onClick={onClose}>返回</Button></View>
          </View>}
          {phase === 'missing' && <View className='bks-state' role='status'>
            <Text id={`${idPrefix}-missing`}>服务不存在或不可预约。</Text>
            <View className='bks-action'><Button id={`${idPrefix}-back-list`} className='bks-state-action' hoverClass='none' onClick={onClose}>返回</Button></View>
          </View>}
          {phase === 'expired' && <View className='bks-state' role='status'>
            <Text id={`${idPrefix}-login-hint`}>预约需要登录，登录后可选择宠物与可约时段。</Text>
            <View className='bks-action'><Button id={`${idPrefix}-login`} className='bks-state-action' hoverClass='none' onClick={goLogin}>去登录</Button></View>
          </View>}
          {phase === 'load-error' && <View className='bks-state' role='status'>
            <Text id={`${idPrefix}-error`}>服务或宠物档案读取失败，请稍后重试。</Text>
            <View className='bks-action'><Button id={`${idPrefix}-retry-load`} className='bks-state-action' hoverClass='none' onClick={() => void loadService()}>重新加载</Button></View>
          </View>}
          {phase === 'receipt' && receipt && badge && <>
            <View className='bks-receipt'>
              <View className='bks-receipt-head'>
                <Text className='bks-receipt-no'>订单号 {receipt.orderNo}</Text>
                <Text className={`bks-badge ${badge.className}`}>{badge.label}</Text>
              </View>
              <Text className='bks-receipt-amount'>¥{receipt.payAmount}</Text>
              <Text className='bks-receipt-hint'>支付截止 {paymentDeadline(receipt)}</Text>
              <Text className='bks-receipt-hint'>订单已创建，请在截止时间前完成支付；逾期订单将自动关闭。</Text>
            </View>
            <View className='bks-secondary-wrap'><Button id={`${idPrefix}-go-order`} className='bks-secondary' hoverClass='none' onClick={goOrderDetail}>查看订单详情</Button></View>
            <Text className='bks-note'>{preview ? '只读预览：本地样例回执，不发起真实请求。' : '弹层数据：真实接口（创建订单回执）。'}</Text>
          </>}
          {ready && service && <>
            <View className='bks-svc'>
              {coverUrl
                ? <Image className='bks-svc-cover' src={coverUrl} mode='aspectFill' />
                : <View className='bks-svc-cover bks-svc-cover-fallback' ariaLabel='暂无服务封面'><Text>{[...service.serviceName][0] || '宠'}</Text></View>}
              <View className='bks-svc-main'>
                <Text className='bks-svc-name'>{service.serviceName}</Text>
                <Text className='bks-svc-hint'>{storeName ?? `门店 ${service.storeId}`} · {service.categoryName} · 时长{service.durationMinutes}分钟 · {pickupFlow ? '上门接送' : '到店服务'}</Text>
                <Text className='bks-svc-price'>预约费用 ¥{service.salePrice}</Text>
              </View>
            </View>
            <View className='bks-section'>
              <Text className='bks-label'>选择日期</Text>
              <View className='bks-input' ariaLabel={`已选日期 ${dateView ? `${dateView.label} ${dateView.weekday}` : ''}`}>
                <Text className='bks-input-text'>{dateView ? `${dateView.label === '今天' ? '今天 ' : ''}${Number(dateView.iso.slice(5, 7))}月${Number(dateView.iso.slice(8, 10))}日 ${dateView.weekday}` : ''}</Text>
                <View className='bks-cal' />
              </View>
              <ScrollView scrollX className='bkg-dates' showScrollbar={false}>
                {dates.map(item => <View key={item.iso} className={item.className} ariaLabel={`${item.label} ${item.weekday}`} onClick={() => chooseDate(item.iso)}>
                  <Text className='bkg-date-label'>{item.label}</Text>
                  <Text className='bkg-date-weekday'>{item.weekday}</Text>
                </View>)}
              </ScrollView>
            </View>
            <View className='bks-section'>
              <Text className='bks-label'>{pickupFlow ? '选择接宠时段' : '选择预约时段'}</Text>
              {slotPhase === 'loading' && <Text className='bks-hint' id={`${idPrefix}-slots-loading`}>正在读取可约时段…</Text>}
              {slotPhase === 'empty' && <Text className='bks-hint' id={`${idPrefix}-slots-empty`}>当日暂无可约时段，请选择其他日期。</Text>}
              {slotPhase === 'error' && <View className='bks-error' role='status'>
                <Text id={`${idPrefix}-slots-error`}>{notice || '可约时段读取失败，请稍后重试。'}</Text>
                <View className='bks-action'><Button id={`${idPrefix}-slots-retry`} className='bks-state-action' hoverClass='none' onClick={() => void loadSlots(date)}>重新读取时段</Button></View>
                {slotAuthExpired && <View className='bks-action'><Button id={`${idPrefix}-slots-login`} className='bks-state-action' hoverClass='none' onClick={goLogin}>去登录</Button></View>}
              </View>}
              {(slotPhase === 'ready' || slotPhase === 'empty') && <View className='bks-slots'>
                {(pickupFlow ? pickupSlots : slots).map(slot => <Button
                  key={slot.key} id={`${idPrefix}-${pickupFlow ? 'pickup' : 'slot'}-${slot.key}`} className={slot.className}
                  ariaLabel={`${pickupFlow ? '接宠' : ''}${slot.label} ${slot.note}`}
                  disabled={busy || !slot.selectable} hoverClass='none'
                  onClick={() => { const item = itemByStart(slot.key); if (!item) return; if (pickupFlow) choosePickup(item); else chooseWindow(item) }}>
                  <Text className='bkg-slot-time'>{slot.label}</Text>
                  <Text className='bkg-slot-note'>{slot.note}</Text>
                </Button>)}
              </View>}
              {slotPhase === 'ready' && pickupFlow && <>
                <Text className='bks-subtitle'>选择送回时段（需不早于接宠后 120 分钟）</Text>
                <View className='bks-slots'>
                  {pickupStart === null
                    ? <Text className='bks-hint' id={`${idPrefix}-return-hint`}>请先选择接宠时段。</Text>
                    : returnSlots.map(slot => <Button key={`r-${slot.key}`} id={`${idPrefix}-return-${slot.key}`} className={slot.className} ariaLabel={`送回 ${slot.label} ${slot.note}`}
                      disabled={busy || !slot.selectable} hoverClass='none' onClick={() => { const item = returnItems.find(candidate => candidate.start === slot.key); if (item) chooseReturn(item) }}>
                      <Text className='bkg-slot-time'>{slot.label}</Text>
                      <Text className='bkg-slot-note'>{slot.note}</Text>
                    </Button>)}
                </View>
              </>}
            </View>
            <View className='bks-section'>
              <Text className='bks-label'>选择宠物</Text>
              {pets.length === 0 && <View className='bks-error' role='status'>
                <Text id={`${idPrefix}-no-pet`}>暂无可用宠物档案，预约前请先创建。</Text>
                <View className='bks-action'><Button id={`${idPrefix}-add-pet`} className='bks-state-action' hoverClass='none' onClick={goPetForm}>去添加宠物档案</Button></View>
              </View>}
              {pets.length > 0 && <View className='bks-pets'>
                {pets.map(pet => <Button key={pet.petId} id={`${idPrefix}-pet-${pet.petId}`} className={`bks-pet${pet.petId === petId ? ' is-selected' : ''}`}
                  ariaLabel={`宠物 ${pet.name}`} disabled={busy} hoverClass='none' onClick={() => setPetId(pet.petId)}>
                  <Text className='bks-pet-name'>{pet.name}</Text>
                  <Text className='bks-pet-note'>{petLine(pet)}</Text>
                </Button>)}
              </View>}
            </View>
            <View className='bks-section'>
              <Text className='bks-label'>备注（选填）</Text>
              <Textarea id={`${idPrefix}-remark`} className='bks-textarea' maxlength={500} value={remark} disabled={busy}
                placeholder='例如：怕生，请提前沟通' onInput={event => setRemark(event.detail.value)} />
            </View>
            {pickupFlow && <View className='bks-section'>
              <Text className='bks-label'>接送服务地址（必填）</Text>
              <Textarea id={`${idPrefix}-address`} className='bks-textarea' maxlength={65536} value={serviceAddress} disabled={busy}
                placeholder='省市区＋详细地址，例如：上海市徐汇区某路100弄5号201室' onInput={event => setServiceAddress(event.detail.value)} />
            </View>}
            <View className='bks-section'>
              <Text className='bks-label'>确认信息</Text>
              <View className='bks-facts'>
                <View className='bks-fact-row'><Text className='bks-fact-label'>服务</Text><Text className='bks-fact-value'>{service.serviceName}</Text></View>
                <View className='bks-fact-row'><Text className='bks-fact-label'>门店</Text><Text className='bks-fact-value'>{storeName ?? service.storeId}</Text></View>
                <View className='bks-fact-row'><Text className='bks-fact-label'>宠物</Text><Text className='bks-fact-value'>{chosenPet ? `${chosenPet.name}（${petTypeLabel(chosenPet)}）` : '—'}</Text></View>
                {!pickupFlow && <View className='bks-fact-row'><Text className='bks-fact-label'>服务时段</Text><Text className='bks-fact-value'>{appointment ? windowLabel(appointment.start, appointment.end) : '—'}</Text></View>}
                {pickupFlow && <View className='bks-fact-row'><Text className='bks-fact-label'>接宠时段</Text><Text className='bks-fact-value'>{pickupItem ? windowLabel(pickupItem.start, pickupItem.end) : '—'}</Text></View>}
                {pickupFlow && <View className='bks-fact-row'><Text className='bks-fact-label'>送回时段</Text><Text className='bks-fact-value'>{returnItem ? windowLabel(returnItem.start, returnItem.end) : '—'}</Text></View>}
                {pickupFlow && <View className='bks-fact-row'><Text className='bks-fact-label'>服务地址</Text><Text className='bks-fact-value'>{serviceAddress.trim() || '—'}</Text></View>}
                <View className='bks-fact-row'><Text className='bks-fact-label'>预约费用</Text><Text className='bks-fact-value bks-fact-amount'>¥{service.salePrice}</Text></View>
              </View>
            </View>
            {notice && <Text id={`${idPrefix}-notice`} className='bks-notice'>{notice}</Text>}
            <Text className='bks-note'>{preview ? '只读预览：本地样例数据，仅用于设计验收，不发起真实请求。' : '弹层数据：真实接口（可约时段 + 宠物档案 + 创建订单）。'}</Text>
          </>}
        </View>
      </ScrollView>
      {ready && service && <View className='bks-foot'>
        <Button id={`${idPrefix}-submit`} className='bks-foot-action' disabled={busy} hoverClass='none' onClick={() => void submit()}>
          {busy ? '正在提交…' : `确认预约 · ¥${service.salePrice}`}
        </Button>
      </View>}
      {phase === 'receipt' && receipt && <View className='bks-foot'>
        <Button id={`${idPrefix}-go-pay`} className='bks-foot-action' hoverClass='none' onClick={goPay}>去支付 ¥{receipt.payAmount}</Button>
      </View>}
    </View>
  </View>
}

const selectable = (item: AvailabilityItem): boolean => item.available && item.remainingCapacity > 0
const petTypeLabel = (pet: PetView): string => pet.petType === 'DOG' ? '狗' : pet.petType === 'CAT' ? '猫' : '其他'
const petLine = (pet: PetView): string => [pet.breedName || '', petTypeLabel(pet)].filter(Boolean).join(' · ')
function isContractId(value: string): boolean {
  return /^[1-9][0-9]{0,18}$/.test(value) && (value.length < 19 || BigInt(value) <= 9223372036854775807n)
}
