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
  scheduleClosedReason, scheduleMessage, windowCountPageSize, windowKindText, windowListPageSize,
  windowStatusTagClass, windowStatusText, windowStatuses, type ScheduleDeps,
  type ScheduleWindowItem, type StaffWindowItem, type WindowKind, type WindowStatus,
} from '../../schedule/model'
import { Chip, intervalText, ScheduleShell, useScheduleStyle, type PagePhase } from './parts'
import './page.css'

// M-002 排期工作台（读） — 商家工作台读（Contract 53号 §3 GET availability-windows +
// §4 GET staff availability-windows 的呈现）。设计源见 registry §4：无契约语义兼容原稿，
// 沿 M 端现行页面规范实现；SOLD_OUT 为系统派生态，只读呈现为「已约满」。
// #122/§3.3 后列表切服务端分页：类型/状态 chips 走服务端过滤（每次选择重拉第 1 页），
// 摘要三态计数用三个单行 status 过滤请求的信封 total（过滤后匹配总数），不再全量拉取。
type StaffQuery = 'idle' | 'loading' | 'ready' | 'error'
type WindowListQuery = { page: number; pageSize: number; kind?: WindowKind; status?: WindowStatus }

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
  const [listTotal, setListTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loadingMore, setLoadingMore] = useState(false)
  const [summary, setSummary] = useState<Readonly<Record<WindowStatus, number>>>({ OPEN: 0, SOLD_OUT: 0, CLOSED: 0 })
  const [names, setNames] = useState<Map<string, string>>(new Map())
  const [kind, setKind] = useState<WindowKind | ''>('')
  const [status, setStatus] = useState<WindowStatus | ''>('')
  const [staffId, setStaffId] = useState('')
  const [staffPhase, setStaffPhase] = useState<StaffQuery>('idle')
  const [staffWindows, setStaffWindows] = useState<readonly StaffWindowItem[]>([])
  const mounted = useRef(true)
  const loadSequence = useRef(0)
  // Server-side chips: load() reads the CURRENT selection through this ref so useDidShow and
  // chip handlers share one query builder without stale-closure risk.
  const filterRef = useRef<{ kind: WindowKind | ''; status: WindowStatus | '' }>({ kind: '', status: '' })

  const listQuery = (target: { page: number; kind?: WindowKind | ''; status?: WindowStatus | '' }): WindowListQuery => {
    const current = filterRef.current
    const chosenKind = target.kind !== undefined ? target.kind : current.kind
    const chosenStatus = target.status !== undefined ? target.status : current.status
    return { page: target.page, pageSize: windowListPageSize,
      ...(chosenKind ? { kind: chosenKind } : {}), ...(chosenStatus ? { status: chosenStatus } : {}) }
  }

  const load = useCallback(async () => {
    const sequence = ++loadSequence.current
    setNotice('')
    if (!preview) {
      const current = scope.current
      if (!current || current.workspace !== 'merchant' || !current.merchantId || !current.storeId) { setPhase('entry'); return }
    }
    setPhase('loading')
    try {
      // Summary three-state counts: §3.3 total is the filter-matched count, so one single-row
      // query per status yields the exact count without pulling every window.
      const [list, openCount, soldOutCount, closedCount] = await Promise.all([
        repository.windows(listQuery({ page: 1 })),
        repository.windows({ page: 1, pageSize: windowCountPageSize, status: 'OPEN' }),
        repository.windows({ page: 1, pageSize: windowCountPageSize, status: 'SOLD_OUT' }),
        repository.windows({ page: 1, pageSize: windowCountPageSize, status: 'CLOSED' }),
      ])
      if (!mounted.current || sequence !== loadSequence.current) return
      setWindows(list.items)
      setListTotal(list.total)
      setPage(1)
      setLoadingMore(false)
      setSummary({ OPEN: openCount.total, SOLD_OUT: soldOutCount.total, CLOSED: closedCount.total })
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
  /** Server-side chips: a selection changes the query, so it re-fetches page 1 of the new
   *  filter (a client-side filter over one page would lie about later pages). */
  function selectKind(target: WindowKind | '') {
    setKind(target)
    filterRef.current = { ...filterRef.current, kind: target }
    void load()
  }
  function selectStatus(target: WindowStatus | '') {
    setStatus(target)
    filterRef.current = { ...filterRef.current, status: target }
    void load()
  }
  /** Append the next server page while some of the filtered total is still unloaded. */
  async function loadMore() {
    if (loadingMore || phase !== 'ready' || windows.length >= listTotal || page >= 10000) return
    const sequence = loadSequence.current
    setLoadingMore(true)
    try {
      const next = await repository.windows(listQuery({ page: page + 1 }))
      if (!mounted.current || sequence !== loadSequence.current) return
      const known = new Set(windows.map(item => item.windowId))
      setWindows(current => [...current, ...next.items.filter(item => !known.has(item.windowId))])
      setPage(next.page)
      setListTotal(next.total)
    } catch (error) {
      if (!mounted.current || sequence !== loadSequence.current) return
      setNotice(scheduleMessage(error))
    } finally {
      if (mounted.current) setLoadingMore(false)
    }
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
        <Text>开放 {summary.OPEN} · 已约满 {summary.SOLD_OUT} · 已关闭 {summary.CLOSED}</Text>
        <Text className='sch-summary-hint'>「已约满」由系统按占用自动置位，释放后自动回位；商家不可手工置满或强制可约。</Text>
      </View>
      <View className='sch-chip-row'>
        <Chip selected={kind === ''} onClick={() => selectKind('')}>全部类型</Chip>
        {(['GENERAL', 'PICKUP', 'RETURN'] as const).map(target =>
          <Chip key={target} selected={kind === target} onClick={() => selectKind(target)}>{windowKindText[target]}</Chip>)}
      </View>
      <View className='sch-chip-row'>
        <Chip selected={status === ''} onClick={() => selectStatus('')}>全部状态</Chip>
        {windowStatuses.map(target =>
          <Chip key={target} selected={status === target} onClick={() => selectStatus(target)}>{windowStatusText[target]}</Chip>)}
      </View>
      {windows.length === 0 && <View className='sch-empty'><Text>当前筛选下没有服务时段；可到「服务时段管理」新建。</Text></View>}
      {windows.map(item => <View key={item.windowId} className='sch-card'>
        <View className='sch-card-head'>
          <Text className='sch-card-name'>{serviceName(item.serviceId)}</Text>
          <Text className={windowStatusTagClass(item.status)}>{windowStatusText[item.status]}</Text>
        </View>
        <Text className='sch-card-line'>{windowKindText[item.windowKind]} · {intervalText(item.startAt, item.endAt)}</Text>
        <Text className='sch-card-line'>容量 {item.configuredCapacity} · 版本 {item.version}</Text>
        {item.status === 'SOLD_OUT' && <Text className='sch-card-hint'>已约满：占用达容量自动置位，释放或提高容量后自动回位。</Text>}
      </View>)}
      {windows.length < listTotal && <Button id='sch-more' className='sch-load-more' disabled={loadingMore}
        onClick={() => void loadMore()}>{loadingMore ? '正在加载…' : `加载更多（已显示 ${windows.length}/${listTotal}）`}</Button>}
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
