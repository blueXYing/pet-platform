import { Button, Input, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import {
  loadServiceOptions, RealScheduleRepository,
} from '../../schedule/repository'
import {
  formatWindowInterval, isScheduleScenario, PreviewScheduleRepository, scheduleAvailability,
  scheduleClosedReason, scheduleMessage, windowKindText, windowStatusTagClass, windowStatusText,
  windowStatuses, type ScheduleDeps, type ScheduleWindowItem, type StaffWindowItem,
  type WindowKind, type WindowStatus,
} from '../../schedule/model'
import { Chip, intervalText, ScheduleShell, useScheduleStyle, type PagePhase } from './parts'
import './page.css'

// M-002 排期工作台（读） — 商家工作台读（Contract 53号 §3 GET availability-windows +
// §4 GET staff availability-windows 的呈现）。设计源见 registry §4：无契约语义兼容原稿，
// 沿 M 端现行页面规范实现；SOLD_OUT 为系统派生态，只读呈现为「已约满」。
type StaffQuery = 'idle' | 'loading' | 'ready' | 'error'

export default function ScheduleWorkbenchPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isScheduleScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope, revision, context } = useWorkspace(preview ? 'preview' : 'real')
  const [repository] = useState<ScheduleDeps>(() => preview ? new PreviewScheduleRepository(undefined, undefined, undefined, scenario) : realRepository())
  const style = useScheduleStyle()
  const [phase, setPhase] = useState<PagePhase>('idle')
  const [closedReason, setClosedReason] = useState('')
  const [notice, setNotice] = useState('')
  const [windows, setWindows] = useState<readonly ScheduleWindowItem[]>([])
  const [names, setNames] = useState<Map<string, string>>(new Map())
  const [kind, setKind] = useState<WindowKind | ''>('')
  const [status, setStatus] = useState<WindowStatus | ''>('')
  const [staffId, setStaffId] = useState('')
  const [staffPhase, setStaffPhase] = useState<StaffQuery>('idle')
  const [staffWindows, setStaffWindows] = useState<readonly StaffWindowItem[]>([])
  const mounted = useRef(true)
  const loadSequence = useRef(0)

  const load = useCallback(async () => {
    const sequence = ++loadSequence.current
    setNotice('')
    if (!preview) {
      const current = scope.current
      if (!current || current.workspace !== 'merchant' || !current.merchantId || !current.storeId) { setPhase('entry'); return }
    }
    setPhase('loading')
    try {
      const page = await repository.windows({})
      if (!mounted.current || sequence !== loadSequence.current) return
      setWindows(page.items)
      setPhase('ready')
      const coords = scope.current
      if (coords?.merchantId && coords.storeId) {
        const options = await loadServiceOptions(consumerApi, coords.merchantId, coords.storeId)
        if (mounted.current && sequence === loadSequence.current && options.length) {
          setNames(new Map(options.map(option => [option.serviceId, option.serviceName])))
        }
      }
    } catch (error) {
      if (!mounted.current || sequence !== loadSequence.current) return
      if (scheduleAvailability(error) === 'closed') { setClosedReason(scheduleClosedReason(error)); setPhase('closed'); return }
      setNotice(scheduleMessage(error)); setPhase('load-error')
    }
  }, [preview, repository, scope])
  useDidShow(() => { void load() })
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; loadSequence.current++ } }, [])
  useEffect(() => {
    if (!preview && (!context || context.workspace !== 'merchant')) setPhase('entry')
  }, [context, preview])
  // Coordinates switched away invalidate the listing (workbench leave / account switch).
  const previousRevision = useRef(revision)
  useEffect(() => {
    if (previousRevision.current !== revision) {
      previousRevision.current = revision
      const current = scope.current
      if (!preview && (!current || current.workspace !== 'merchant')) setPhase('entry')
    }
  }, [revision, scope, preview])

  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack().catch(() => setNotice('返回失败，请重试'))
    else await Taro.redirectTo({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('返回失败，请重试'))
  }
  function open(sub: string) {
    Taro.navigateTo({ url: `/merchant/pages/schedule/${sub}?preview=${preview ? '1' : '0'}${preview ? `&scenario=${scenario}` : ''}` })
      .catch(() => setNotice('页面跳转失败，请重试'))
  }
  async function queryStaff() {
    if (!/^[1-9][0-9]{0,18}$/.test(staffId)) { setNotice('请输入正确的员工编号（数字）。'); return }
    setNotice(''); setStaffPhase('loading')
    try {
      const page = await repository.staffWindows(staffId)
      if (!mounted.current) return
      setStaffWindows(page.items); setStaffPhase('ready')
    } catch (error) {
      if (!mounted.current) return
      setStaffWindows([]); setStaffPhase('error'); setNotice(scheduleMessage(error))
    }
  }

  const filtered = windows
    .filter(item => (kind ? item.windowKind === kind : true))
    .filter(item => (status ? item.status === status : true))
  const count = (target: WindowStatus) => windows.filter(item => item.status === target).length
  const serviceName = (serviceId: string) => names.get(serviceId) || `服务 ${serviceId}`
  return <ScheduleShell title='排期工作台' style={style} phase={phase} notice={notice} closedReason={closedReason}
    onRetry={() => void load()} onBack={() => void back()} preview={preview}>
    <ScrollView className='sch-body' scrollY enhanced showScrollbar={false}>
      <View className='sch-entry-row'>
        <Button id='sch-go-windows' className='sch-entry' onClick={() => open('windows')}><Text>服务时段管理</Text></Button>
        <Button id='sch-go-staff' className='sch-entry' onClick={() => open('staff')}><Text>员工排班</Text></Button>
        <Button id='sch-go-capabilities' className='sch-entry' onClick={() => open('capabilities')}><Text>员工能力</Text></Button>
      </View>
      <View className='sch-summary' role='status'>
        <Text>开放 {count('OPEN')} · 已约满 {count('SOLD_OUT')} · 已关闭 {count('CLOSED')}</Text>
        <Text className='sch-summary-hint'>「已约满」由系统按占用自动置位，释放后自动回位；商家不可手工置满或强制可约。</Text>
      </View>
      <View className='sch-chip-row'>
        <Chip selected={kind === ''} onClick={() => setKind('')}>全部类型</Chip>
        {(['GENERAL', 'PICKUP', 'RETURN'] as const).map(target =>
          <Chip key={target} selected={kind === target} onClick={() => setKind(target)}>{windowKindText[target]}</Chip>)}
      </View>
      <View className='sch-chip-row'>
        <Chip selected={status === ''} onClick={() => setStatus('')}>全部状态</Chip>
        {windowStatuses.map(target =>
          <Chip key={target} selected={status === target} onClick={() => setStatus(target)}>{windowStatusText[target]}</Chip>)}
      </View>
      {filtered.length === 0 && <View className='sch-empty'><Text>当前筛选下没有服务时段；可到「服务时段管理」新建。</Text></View>}
      {filtered.map(item => <View key={item.windowId} className='sch-card'>
        <View className='sch-card-head'>
          <Text className='sch-card-name'>{serviceName(item.serviceId)}</Text>
          <Text className={windowStatusTagClass(item.status)}>{windowStatusText[item.status]}</Text>
        </View>
        <Text className='sch-card-line'>{windowKindText[item.windowKind]} · {intervalText(item.startAt, item.endAt)}</Text>
        <Text className='sch-card-line'>容量 {item.configuredCapacity} · 版本 {item.version}</Text>
        {item.status === 'SOLD_OUT' && <Text className='sch-card-hint'>已约满：占用达容量自动置位，释放或提高容量后自动回位。</Text>}
      </View>)}
      <View className='sch-section'>
        <Text className='sch-label'>员工排班查询</Text>
        <View className='sch-inline'>
          <Input className='sch-input sch-input-grow' value={staffId} placeholder='员工编号（如 958003）' type='number'
            onInput={event => setStaffId(event.detail.value)} />
          <Button id='sch-staff-query' className='sch-inline-action' disabled={staffPhase === 'loading'} onClick={() => void queryStaff()}>
            {staffPhase === 'loading' ? '查询中…' : '查询'}
          </Button>
        </View>
        {staffPhase === 'ready' && staffWindows.length === 0 && <Text className='sch-hint'>该员工暂无排班记录。</Text>}
        {staffPhase === 'ready' && staffWindows.map(item => <View key={item.windowId} className='sch-mini-card'>
          <Text className='sch-card-line'>{formatWindowInterval(item.startAt, item.endAt)}</Text>
          <Text className={item.status === 'AVAILABLE' ? 'sch-tag sch-tag-open' : 'sch-tag sch-tag-closed'}>
            {item.status === 'AVAILABLE' ? '可约' : '已关闭'}
          </Text>
        </View>)}
        {staffPhase === 'ready' && staffWindows.length > 0 && <Text className='sch-hint'>完整增改请进「员工排班」。</Text>}
      </View>
      {notice && <Text className='sch-notice'>{notice}</Text>}
      <View className='sch-tail'><Text>页面数据：{preview ? '契约 Mock（preview=1，不联调）' : '真实接口（开关未开放时失败关闭）'}</Text></View>
    </ScrollView>
  </ScheduleShell>
}

function realRepository(): ScheduleDeps {
  return new RealScheduleRepository(consumerApi,
    () => consumerApi.scope.current?.merchantId || '',
    () => consumerApi.scope.current?.storeId || '')
}
