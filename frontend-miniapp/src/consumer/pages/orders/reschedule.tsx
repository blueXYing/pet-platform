import { Button, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useDidHide, useRouter } from '@tarojs/taro'
import { useEffect, useRef, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { switchConsumerTab } from '../../components/navigation/switch'
import { ConsumerPageLayout } from '../../components/page-layout'
// ARCH-005 分工：本页不触碰订单原始事实字段，事实→展示的推导（入口门控/就绪度/时段槽位/回执口径
// 文案/错误面映射）全部委托 src/consumer/orders/reschedule.ts（rescheduleReadiness/originalDurationMinutes/
// rescheduleSlots/receiptHeadline/receiptBody/rescheduleMessage）；页面只消费现成展示值与坐标事实
// （serviceId/storeId/orderVersion 为 §3.7 读侧增补的原始事实，非推导）。
import { appointmentWindow, orderStatusBadge, type OrderDetailView } from '../../orders/model'
import { bookingDates, beijingToday, availabilityReadMessage } from '../../booking/model'
import {
  PreviewRescheduleRepository, RescheduleController, buildPickupInput, buildStoreInput, isRescheduleScenario,
  originalDurationMinutes, receiptBody, receiptHeadline, receiptRows, rescheduleReadiness, rescheduleSlots,
  returnWindowCandidates, selectionBranch, type RescheduleScenario, type SelectionWindow,
} from '../../orders/reschedule'
import { RealRescheduleRepository } from '../../orders/reschedule-repository'
import './orders.css'
import '../booking/booking.css'

// C 端改期页（10号 §3.8 + 46号 R1/R2/R3，订单详情 actions.canReschedule 门控进入）。新时段选择
// 复用 booking 的 §3.4 availability 交互范式（日期条 + 分钟级窗口槽位、已满置灰「已约满」），并按
// 39号 §1 选窗增补消费 windowId/kind 构造 46号请求（增补未开放时失败关闭，不猜窗口身份）：
// - 到店单：选 GENERAL 原窗，新预约 = 窗口开始 + 原订单时长快照（服务端完整容量证明）；
// - 接送单：选 PICKUP/RETURN 两原窗，送回开始 >= 接宠开始 + 120 分钟（C 端置灰联动）；
// - expectedOrderVersion 取详情 orderVersion；CAS/区间无变化 409 呈现重读（控制器终局 409 退槽
//   并自动回读，门控以最新 actions 为准）；幂等槽：未确认改期保留原 X-Request-Id 原载荷，仅显式
//   重试重发（重放返回首次成功回执）；成功回执展示新预约时间/新版本/30 分钟确认截止。
// preview=1 走本地夹具（normal/conflict/limit 三场景），真实模式走 §3.8 接口（后端同批交付，默认
// 关闭，联调留 E2E 轮，如实披露）。
type SlotPhase = 'loading' | 'ready' | 'empty' | 'closed' | 'error'
const DATE_COUNT = 7

export default function OrderReschedulePage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario: RescheduleScenario = isRescheduleScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const orderId = route.params.orderId || ''
  const { revision } = useWorkspace(preview ? 'preview' : 'real')
  return <RescheduleScreen key={`${revision}:${orderId}:${scenario}`} preview={preview} scenario={scenario} orderId={orderId} />
}

function RescheduleScreen({ preview, scenario, orderId }: { preview: boolean; scenario: RescheduleScenario; orderId: string }) {
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [previewRepository] = useState(() => new PreviewRescheduleRepository(scenario))
  const [realRepository] = useState(() => new RealRescheduleRepository(consumerApi))
  const [controller] = useState(() => new RescheduleController(preview ? {
    detail: target => previewRepository.detail(target),
    reschedule: (target, input) => previewRepository.reschedule(target, input),
    pendingReschedule: () => previewRepository.pending(),
    retireConflict: () => previewRepository.retire(),
  } : {
    detail: target => realRepository.detail(target),
    reschedule: (target, input) => realRepository.reschedule(target, input),
    pendingReschedule: target => realRepository.pendingReschedule(target),
    retireConflict: (target, error) => realRepository.retireConflict(target, error),
  }, scope))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [dates, setDates] = useState<readonly { iso: string; label: string; weekday: string; className: string }[]>([])
  const [date, setDate] = useState('')
  const [slotPhase, setSlotPhase] = useState<SlotPhase>('loading')
  const [items, setItems] = useState<readonly SelectionWindow[] | null>(null)
  const [selectedWindow, setSelectedWindow] = useState<string | null>(null)
  const [pickupWindow, setPickupWindow] = useState<string | null>(null)
  const [returnWindow, setReturnWindow] = useState<string | null>(null)
  const [slotNotice, setSlotNotice] = useState('')
  const mounted = useRef(true)
  const visible = useRef(true)
  const sequence = useRef(0)
  const loadedKey = useRef<string | null>(null)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--ord-status-top': `${platformInfo.statusBarHeight || 0}px`, '--ord-unit': `${unit}px` } as CSSProperties
  const detail: OrderDetailView | null = state.detail
  const readiness = detail === null ? null : rescheduleReadiness(detail)
  const busy = state.busy
  const locked = busy || state.pending !== null || state.receipt !== null

  async function load(target = orderId) {
    if (!/^[1-9][0-9]{0,18}$/.test(target)) return
    if (!preview && !scope.current) { try { await consumerApi.restore() } catch { /* 未登录，走下方登录引导 */ } }
    await controller.load(target)
  }

  async function loadSlots(targetDate: string, serviceId: string, storeId: string) {
    const current = ++sequence.current
    setSlotPhase('loading'); setSlotNotice('')
    try {
      const view = preview
        ? await scope.run(undefined, () => previewRepository.availability(serviceId, storeId, targetDate))
        : await scope.run(undefined, () => realRepository.availability(serviceId, storeId, targetDate))
      if (!mounted.current || current !== sequence.current) return
      setItems(view.items)
      setSlotPhase(view.items.length === 0 ? 'empty' : 'ready')
    } catch (error) {
      if (!mounted.current || current !== sequence.current) return
      setItems(null)
      // 39号选窗增补未开放（六字段投影）时解码失败关闭：无法构造 46号 selected*WindowId。
      if (error instanceof Error && error.message === 'INVALID_RESPONSE') { setSlotPhase('closed'); setSlotNotice(''); return }
      setSlotNotice(availabilityReadMessage(error))
      setSlotPhase('error')
    }
  }

  useEffect(() => {
    mounted.current = true
    const today = preview ? '2026-10-01' : beijingToday()
    setDates(bookingDates(today, DATE_COUNT, today))
    setDate(today)
    void load()
    return () => { mounted.current = false; sequence.current++ }
  }, [])
  useDidShow(() => { visible.current = true; void load() })
  useDidHide(() => { visible.current = false; sequence.current++ })

  // 详情就绪后加载首日时段（坐标来自读侧增补的原始事实）。
  useEffect(() => {
    if (readiness === null || !readiness.ok || !date) return
    const key = `${readiness.serviceId}:${readiness.storeId}:${date}`
    if (loadedKey.current === key) return
    loadedKey.current = key
    setSelectedWindow(null); setPickupWindow(null); setReturnWindow(null)
    void loadSlots(date, readiness.serviceId, readiness.storeId)
  }, [readiness === null ? '' : readiness.ok ? `${readiness.serviceId}:${readiness.storeId}` : 'no', date])

  function chooseDate(iso: string) {
    if (iso === date || locked) return
    setDate(iso)
    setDates(bookingDates(preview ? '2026-10-01' : beijingToday(), DATE_COUNT, iso))
    setSelectedWindow(null); setPickupWindow(null); setReturnWindow(null)
    if (readiness !== null && readiness.ok) void loadSlots(iso, readiness.serviceId, readiness.storeId)
  }

  const branch = items === null ? null : selectionBranch(items)
  const byId = (windowId: string | null): SelectionWindow | null =>
    windowId === null || items === null ? null : items.find(item => item.windowId === windowId) ?? null
  const duration = detail === null ? null : originalDurationMinutes(detail)
  const generalSlots = branch === 'IN_STORE' && items !== null ? rescheduleSlots(items, 'GENERAL', selectedWindow, duration) : []
  const pickupSlots = branch === 'PICKUP_DELIVERY' && items !== null ? rescheduleSlots(items, 'PICKUP', pickupWindow, null) : []
  const nextPickup = byId(pickupWindow)
  const returnItems = branch === 'PICKUP_DELIVERY' && items !== null && nextPickup !== null
    ? returnWindowCandidates(items, nextPickup.start) : []
  const returnSlots = pickupWindow === null ? [] : rescheduleSlots(returnItems, 'RETURN', returnWindow, null)
  const chosenGeneral = byId(selectedWindow)
  const chosenPickup = byId(pickupWindow)
  const chosenReturn = byId(returnWindow)

  function chooseGeneral(windowId: string) {
    if (locked) return
    setSelectedWindow(previous => previous === windowId ? null : windowId)
  }
  function choosePickup(windowId: string) {
    if (locked) return
    const picked = byId(windowId)
    setPickupWindow(windowId)
    // 送回联动：不满足 120 分钟间隔的原送回选择自动失效（46号/SCH-D4 C 端置灰联动）。
    if (picked !== null && returnWindow !== null && !returnWindowCandidates(items ?? [], picked.start).some(item => item.windowId === returnWindow)) setReturnWindow(null)
  }
  function chooseReturn(windowId: string) {
    if (locked) return
    setReturnWindow(previous => previous === windowId ? null : windowId)
  }

  function submit() {
    if (locked || detail === null || readiness === null || !readiness.ok || branch === null) return
    const input = branch === 'IN_STORE'
      ? chosenGeneral === null ? null : buildStoreInput(chosenGeneral, detail, readiness.version)
      : chosenPickup === null || chosenReturn === null ? null : buildPickupInput(chosenPickup, chosenReturn, readiness.version)
    if (input === null) { setSlotNotice('请先选择可用的新时段（接送需同时满足 120 分钟间隔）'); return }
    void controller.submit(detail.orderId, input)
  }

  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: `/consumer/pages/orders/detail?orderId=${encodeURIComponent(orderId)}${preview ? '?preview=1' : ''}` })
  }
  function goLogin() { void Taro.switchTab({ url: '/consumer/pages/mine/index' }) }

  const badge = detail === null ? null : orderStatusBadge(detail)
  return <ConsumerPageLayout page='orderDetail' unit={unit} className='ord-page' style={style}
    navigation={{ idPrefix: 'rsc', disabled: state.phase === 'loading', onSelect: key => { void switchConsumerTab(key) } }}>
    <View className='ord-design'>
      <View className='ord-status-area' />
      <View className='ord-nav'>
        <Button id='rsc-back' ariaLabel='返回' className='ord-nav-back' onClick={() => void goBack()}>
          <Text className='ord-nav-back-icon'>‹</Text>
        </Button>
        <Text className='ord-nav-title'>订单改期</Text>
      </View>
      {state.phase === 'loading' && <View className='ord-state' role='status'><Text id='rsc-loading'>正在读取订单…</Text></View>}
      {state.phase === 'unauthorized' && <View className='ord-state' role='status'>
        <Text id='rsc-login-hint'>登录后可改期。</Text>
        <Button id='rsc-login' className='ord-state-action' onClick={goLogin}>去登录</Button>
      </View>}
      {state.phase === 'load-error' && <View className='ord-state' role='status'>
        <Text id='rsc-error'>{state.notice || '订单读取失败，请稍后重试。'}</Text>
        <Button id='rsc-retry-read' className='ord-state-action' onClick={() => void load()}>重新加载</Button>
      </View>}
      {state.phase === 'ineligible' && state.receipt === null && <View className='ord-state' role='status'>
        <Text id='rsc-ineligible'>当前订单不支持改期（每单最多 1 次；预约开始后、已核销、已进入退款或状态变化后不可改期）。</Text>
        <Button id='rsc-back-detail' className='ord-state-action' onClick={() => void goBack()}>返回订单详情</Button>
      </View>}
      {(state.phase === 'ready' || state.receipt !== null) && detail !== null && <View className='ord-detail-body'>
        <View className='ord-summary'>
          <View className='ord-card-head'>
            <Text className='ord-card-no'>订单号 {detail.orderNo}</Text>
            {badge && <Text className={`ord-badge ${badge.className}`}>{badge.label}</Text>}
          </View>
          <Text className='ord-card-time'>当前预约 {appointmentWindow(detail)}</Text>
          <Text className='ord-verify-hint'>改期不改变服务、宠物与金额；每单最多 1 次，改期后回到待确认并重启 30 分钟商家确认。</Text>
        </View>
        {state.receipt !== null && <View className='ord-receipt' id='rsc-receipt'>
          <View className='ord-verify-head'>
            <Text className='ord-verify-title'>{receiptHeadline()}</Text>
            <Text className='ord-badge is-pending-confirm'>待确认</Text>
          </View>
          <Text className='ord-verify-hint'>{receiptBody(state.receipt)}</Text>
          {receiptRows(state.receipt).map(row => <View key={row.id} className='ord-fact-row'>
            <Text className='ord-fact-label'>{row.label}</Text><Text className='ord-fact-value'>{row.value}</Text>
          </View>)}
          <Button id='rsc-back-after' className='ord-verify-action' onClick={() => void goBack()}>返回订单详情</Button>
        </View>}
        {state.receipt === null && readiness !== null && !readiness.ok && <View className='ord-state' role='status'>
          <Text id='rsc-readiness'>{readiness.notice}</Text>
          <Button id='rsc-reload' className='ord-state-action' onClick={() => void load()}>重新读取</Button>
        </View>}
        {state.receipt === null && readiness !== null && readiness.ok && <View className='ord-refund'>
          <View className='ord-verify-head'><Text className='ord-verify-title'>选择新时段</Text></View>
          <View className='bkg-section'>
            <Text className='bkg-section-title'>选择日期</Text>
            <ScrollView scrollX className='bkg-dates'>
              {dates.map(item => <Button key={item.iso} className={item.className} ariaLabel={`${item.label} ${item.weekday}`} disabled={locked} onClick={() => chooseDate(item.iso)}>
                <Text className='bkg-date-label'>{item.label}</Text>
                <Text className='bkg-date-weekday'>{item.weekday}</Text>
              </Button>)}
            </ScrollView>
          </View>
          <View className='bkg-section'>
            <Text className='bkg-section-title'>{branch === 'PICKUP_DELIVERY' ? '选择接宠时段' : '选择新预约时段'}</Text>
            {slotPhase === 'loading' && <Text className='bkg-hint' id='rsc-slots-loading'>正在读取可约时段…</Text>}
            {slotPhase === 'empty' && <Text className='bkg-hint' id='rsc-slots-empty'>当日暂无可约时段，请选择其他日期。</Text>}
            {slotPhase === 'closed' && <View className='bkg-slot-error' role='status'>
              <Text id='rsc-slots-closed'>时段服务暂未开放选窗信息（39号增补），无法构造改期请求，请稍后再试。</Text>
              <Button id='rsc-slots-retry-closed' className='bkg-state-action' onClick={() => readiness.ok && void loadSlots(date, readiness.serviceId, readiness.storeId)}>重新读取时段</Button>
            </View>}
            {slotPhase === 'error' && <View className='bkg-slot-error' role='status'>
              <Text id='rsc-slots-error'>{slotNotice || '可约时段读取失败，请稍后重试。'}</Text>
              <Button id='rsc-slots-retry' className='bkg-state-action' onClick={() => readiness.ok && void loadSlots(date, readiness.serviceId, readiness.storeId)}>重新读取时段</Button>
            </View>}
            {(slotPhase === 'ready' || slotPhase === 'empty') && branch === 'IN_STORE' && <View className='bkg-slots'>
              {generalSlots.map(slot => <Button key={slot.key} id={`rsc-slot-${slot.key}`} className={slot.className} ariaLabel={`${slot.label} ${slot.note}`}
                disabled={locked || !slot.selectable} onClick={() => chooseGeneral(slot.key)}>
                <Text className='bkg-slot-time'>{slot.label}</Text>
                <Text className='bkg-slot-note'>{slot.note}</Text>
              </Button>)}
            </View>}
            {(slotPhase === 'ready' || slotPhase === 'empty') && branch === 'PICKUP_DELIVERY' && <>
              <View className='bkg-slots'>
                {pickupSlots.map(slot => <Button key={`p-${slot.key}`} className={slot.className} ariaLabel={`接宠 ${slot.label} ${slot.note}`}
                  disabled={locked || !slot.selectable} onClick={() => choosePickup(slot.key)}>
                  <Text className='bkg-slot-time'>{slot.label}</Text>
                  <Text className='bkg-slot-note'>{slot.note}</Text>
                </Button>)}
              </View>
              <Text className='bkg-section-subtitle'>选择送回时段（需不早于接宠后 120 分钟）</Text>
              <View className='bkg-slots'>
                {pickupWindow === null
                  ? <Text className='bkg-hint' id='rsc-return-hint'>请先选择接宠时段。</Text>
                  : returnSlots.map(slot => <Button key={`r-${slot.key}`} className={slot.className} ariaLabel={`送回 ${slot.label} ${slot.note}`}
                    disabled={locked || !slot.selectable} onClick={() => chooseReturn(slot.key)}>
                    <Text className='bkg-slot-time'>{slot.label}</Text>
                    <Text className='bkg-slot-note'>{slot.note}</Text>
                  </Button>)}
              </View>
            </>}
          </View>
          <View className='bkg-section bkg-confirm-card'>
            <Text className='bkg-section-title'>确认改期信息</Text>
            <View className='ord-fact-row'><Text className='ord-fact-label'>当前预约</Text><Text className='ord-fact-value'>{appointmentWindow(detail)}</Text></View>
            <View className='ord-fact-row'><Text className='ord-fact-label'>新预约</Text><Text className='ord-fact-value'>{branch === 'IN_STORE'
              ? (chosenGeneral !== null && duration !== null ? newTimeLabel(chosenGeneral.start, duration) : '—')
              : (chosenPickup !== null && chosenReturn !== null ? `${newTimeLabel(chosenPickup.start, 0)} 接 / ${newTimeLabel(chosenReturn.start, 0)} 送` : '—')}</Text></View>
            {branch === 'PICKUP_DELIVERY' && <View className='ord-fact-row'><Text className='ord-fact-label'>间隔校验</Text><Text className='ord-fact-value'>{chosenPickup !== null && chosenReturn !== null && Date.parse(chosenReturn.start) >= Date.parse(chosenPickup.start) + 120 * 60000 ? '满足 120 分钟' : '不满足（送回置灰）'}</Text></View>}
            <View className='ord-fact-row'><Text className='ord-fact-label'>订单版本</Text><Text className='ord-fact-value'>{readiness.version}</Text></View>
          </View>
          {state.pending !== null
            ? <Button id='rsc-retry' className='ord-verify-action' disabled={busy} onClick={() => detail && void controller.retry(detail.orderId)}>重试原改期</Button>
            : <Button id='rsc-submit' className='ord-verify-action' disabled={busy || slotPhase !== 'ready'} onClick={submit}>提交改期</Button>}
          {busy && <Text className='ord-verify-hint'>正在提交改期…</Text>}
          {state.notice && <Text id='rsc-notice' className='ord-error'>{state.notice}</Text>}
          {slotNotice && state.notice === '' && <Text id='rsc-slot-notice' className='ord-error'>{slotNotice}</Text>}
          {state.pending !== null && state.notice === '' && <Text className='ord-verify-hint'>已恢复上次未确认的改期，重试将沿用原请求编号与内容，不会重复改期。</Text>}
        </View>}
        <View className='ord-preview-note'><Text>{preview ? '只读预览：本地样例数据，仅用于设计验收，不发起真实请求。' : '页面数据：真实接口（订单只读 + §3.4 选窗 + 改期 no-store 幂等写）。'}</Text></View>
      </View>}
    </View>
  </ConsumerPageLayout>
}

/** 新预约文案：到店 = 窗口开始 + 原时长；接送 = 窗口开始（北京时间分钟粒度）。 */
function newTimeLabel(start: string, durationMinutes: number): string {
  const begin = new Date(Date.parse(start) + 8 * 3600000)
  const end = new Date(Date.parse(start) + durationMinutes * 60000 + 8 * 3600000)
  const pad = (value: number): string => String(value).padStart(2, '0')
  const clock = (value: Date): string => `${value.getUTCFullYear()}/${pad(value.getUTCMonth() + 1)}/${pad(value.getUTCDate())} ${pad(value.getUTCHours())}:${pad(value.getUTCMinutes())}`
  return durationMinutes > 0 ? `${clock(begin)} ~ ${clock(end).slice(11)}（北京时间）` : `${clock(begin)}（北京时间）`
}
