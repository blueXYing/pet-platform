import { Button, Image, Input, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi, consumerSession } from '../../../shared/consumer-runtime'
import { RealScheduleRepository } from '../../schedule/repository'
import {
  isScheduleScenario, PreviewScheduleRepository, scheduleAvailability,
  scheduleClosedReason, scheduleMessage, windowCountPageSize, windowListPageSize,
  windowStatusTagClass, windowStatusText, windowStatuses, type ScheduleDeps,
  type ScheduleWindowItem, type StaffWindowItem, type WindowStatus,
} from '../../schedule/model'
import {
  capsuleTimeText, dayGroupLabel, durationMinutesText, groupWindowsByDate,
  statNumberClass, windowCapsuleClass, windowDurationMinutes,
} from '../../schedule/view'
import { Chip, ScheduleShell, useScheduleStyle, type PagePhase } from './parts'
import gridSchedule from '../workspace/assets/grid-schedule.svg'
import gridMembers from '../workspace/assets/grid-members.svg'
import gridVerify from '../workspace/assets/grid-verify.svg'
import './page.css'

// M-002 排期工作台（读） — 商家工作台读（Contract 53号 §3 GET availability-windows +
// §4 GET staff availability-windows 的呈现）。CCR-SCH-SLOT-001（2026-11-13 裁决）后窗口 =
// 全店共享时间槽：无服务/类型维度，筛选只留状态；SOLD_OUT 为系统派生态（全店单池占满），
// 只读呈现为「已约满」。
// #122/§3.3 后列表切服务端分页：状态 chips 走服务端过滤（每次选择重拉第 1 页），
// 摘要三态计数用三个单行 status 过滤请求的信封 total（过滤后匹配总数），不再全量拉取。
// P-11a（2026-11-11）视觉层对齐 Figma 12:6214：只取呈现 token（时段胶囊/日期分组/三态数字
// 卡/入口宫格，schedule/view.ts），分组维度是窗口 startAt 的北京历日——数据仍是分钟级窗口
// （SSOT §12.1 无固定 60 分钟槽），帧内「一键生成/周模板编辑/1 小时间隔」属 P-11b CCR 红线，
// 一律不做。页面结构、导航与数据链路不变。
type StaffQuery = 'idle' | 'loading' | 'ready' | 'error'
type WindowListQuery = { page: number; pageSize: number; status?: WindowStatus }

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
  const [status, setStatus] = useState<WindowStatus | ''>('')
  const [staffId, setStaffId] = useState('')
  const [staffPhase, setStaffPhase] = useState<StaffQuery>('idle')
  const [staffWindows, setStaffWindows] = useState<readonly StaffWindowItem[]>([])
  const mounted = useRef(true)
  const loadSequence = useRef(0)
  // Server-side chips: load() reads the CURRENT selection through this ref so useDidShow and
  // chip handlers share one query builder without stale-closure risk.
  const filterRef = useRef<{ status: WindowStatus | '' }>({ status: '' })

  const listQuery = (target: { page: number; status?: WindowStatus | '' }): WindowListQuery => {
    const current = filterRef.current
    const chosenStatus = target.status !== undefined ? target.status : current.status
    return { page: target.page, pageSize: windowListPageSize,
      ...(chosenStatus ? { status: chosenStatus } : {}) }
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
    } catch (error) {
      if (!mounted.current || sequence !== loadSequence.current) return
      if (scheduleAvailability(error) === 'closed') { setClosedReason(scheduleClosedReason(error)); setPhase('closed'); return }
      setNotice(scheduleMessage(error)); setPhase('load-error')
    }
  }, [preview, repository, scope])
  useDidShow(() => {
    void (async () => {
      try { await consumerSession.launch() } catch { /* load() renders the error state */ }
      await load()
    })()
  })
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
    await Taro.reLaunch({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('返回失败，请重试'))
  }
  function open(sub: string) {
    Taro.navigateTo({ url: `/merchant/pages/schedule/${sub}?preview=${preview ? '1' : '0'}${preview ? `&scenario=${scenario}` : ''}` })
      .catch(() => setNotice('页面跳转失败，请重试'))
  }
  /** Server-side chips: a selection changes the query, so it re-fetches page 1 of the new
   *  filter (a client-side filter over one page would lie about later pages). */
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

  const dayGroups = groupWindowsByDate(windows)
  return <ScheduleShell title='排期工作台' style={style} phase={phase} notice={notice} closedReason={closedReason}
    onRetry={() => void load()} onBack={() => void back()} preview={preview}>
    <ScrollView className='sch-body' scrollY enhanced showScrollbar={false}>
      <View className='sch-v-grid-card'>
        <View className='sch-v-grid'>
          <Button id='sch-go-windows' className='sch-v-grid-cell' onClick={() => open('windows')}>
            <View className='sch-v-grid-tile'><Image className='sch-v-grid-icon' src={gridSchedule} mode='aspectFit' /></View>
            <Text className='sch-v-grid-label'>服务时段管理</Text>
            <Text className='sch-v-grid-sub'>分钟级时段增改</Text>
          </Button>
          <Button id='sch-go-staff' className='sch-v-grid-cell' onClick={() => open('staff')}>
            <View className='sch-v-grid-tile'><Image className='sch-v-grid-icon' src={gridMembers} mode='aspectFit' /></View>
            <Text className='sch-v-grid-label'>员工排班</Text>
            <Text className='sch-v-grid-sub'>员工可用时间</Text>
          </Button>
          <Button id='sch-go-capabilities' className='sch-v-grid-cell' onClick={() => open('capabilities')}>
            <View className='sch-v-grid-tile'><Image className='sch-v-grid-icon' src={gridVerify} mode='aspectFit' /></View>
            <Text className='sch-v-grid-label'>员工能力</Text>
            <Text className='sch-v-grid-sub'>服务能力配置</Text>
          </Button>
        </View>
      </View>
      <View className='sch-v-stats' role='status'>
        {windowStatuses.map(target => <Button key={target}
          className={status === target ? 'sch-v-stat sch-v-stat-active' : 'sch-v-stat'}
          ariaLabel={`${windowStatusText[target]} ${summary[target]} 个，点击按该状态筛选`}
          onClick={() => selectStatus(status === target ? '' : target)}>
          <Text className={statNumberClass(target)}>{summary[target]}</Text>
          <Text className='sch-v-stat-label'>{windowStatusText[target]}</Text>
        </Button>)}
      </View>
      <Text className='sch-v-stats-hint'>「已约满」由系统按占用自动置位，释放后自动回位；商家不可手工置满或强制可约。</Text>
      <View className='sch-chip-row'>
        <Chip selected={status === ''} onClick={() => selectStatus('')}>全部状态</Chip>
        {windowStatuses.map(target =>
          <Chip key={target} selected={status === target} onClick={() => selectStatus(target)}>{windowStatusText[target]}</Chip>)}
      </View>
      <View className='sch-v-section'>
        <View className='sch-v-head'>
          <Text className='sch-v-h2'>服务时段</Text>
          <Text className='sch-v-head-hint'>全店共享时间槽 · 分钟级 · 按日期分组</Text>
        </View>
        {windows.length === 0 && <View className='sch-empty'><Text>当前筛选下没有服务时段；可到「服务时段管理」新建。</Text></View>}
        {dayGroups.map((group, index) => <View key={group.date} className={index === 0 ? 'sch-v-day' : 'sch-v-day sch-v-day-sep'}>
          <View className='sch-v-day-head'>
            <Text className='sch-v-day-date'>{dayGroupLabel(group.date)}</Text>
            <Text className='sch-v-day-count'>本页 {group.items.length} 个</Text>
          </View>
          {group.items.map(item => {
            const minutes = windowDurationMinutes(item.startAt, item.endAt)
            const duration = durationMinutesText(minutes)
            return <View key={item.windowId} className='sch-v-win'>
              <View className='sch-v-win-top'>
                <View className={windowCapsuleClass(item.status)}>
                  <Text className='sch-cap-time'>{capsuleTimeText(item.startAt, item.endAt)}</Text>
                  {duration && <Text className='sch-cap-dur'>· {duration}</Text>}
                </View>
                <Text className={windowStatusTagClass(item.status)}>{windowStatusText[item.status]}</Text>
              </View>
              <Text className='sch-v-win-meta'>全店共享 · 容量 {item.configuredCapacity} · 版本 {item.version}</Text>
              {item.status === 'SOLD_OUT' && <Text className='sch-card-hint'>已约满：占用达容量自动置位，释放或提高容量后自动回位。</Text>}
            </View>
          })}
        </View>)}
        {windows.length < listTotal && <Button id='sch-more' className='sch-load-more' disabled={loadingMore}
          onClick={() => void loadMore()}>{loadingMore ? '正在加载…' : `加载更多（已显示 ${windows.length}/${listTotal}）`}</Button>}
      </View>
      <View className='sch-v-section'>
        <View className='sch-v-head'>
          <Text className='sch-v-h2'>员工排班查询</Text>
          <Text className='sch-v-head-hint'>按员工编号读取</Text>
        </View>
        <View className='sch-inline'>
          <Input className='sch-input sch-input-grow' value={staffId} placeholder='员工编号（如 958003）' type='number'
            onInput={event => setStaffId(event.detail.value)} />
          <Button id='sch-staff-query' className='sch-inline-action' disabled={staffPhase === 'loading'} onClick={() => void queryStaff()}>
            {staffPhase === 'loading' ? '查询中…' : '查询'}
          </Button>
        </View>
        {staffPhase === 'ready' && staffWindows.length === 0 && <Text className='sch-hint'>该员工暂无排班记录。</Text>}
        {staffPhase === 'ready' && staffWindows.map(item => {
          const duration = durationMinutesText(windowDurationMinutes(item.startAt, item.endAt))
          return <View key={item.windowId} className='sch-v-staff-row'>
            <View className={item.status === 'AVAILABLE' ? 'sch-cap sch-cap-open' : 'sch-cap sch-cap-closed'}>
              <Text className='sch-cap-time'>{capsuleTimeText(item.startAt, item.endAt)}</Text>
              {duration && <Text className='sch-cap-dur'>· {duration}</Text>}
            </View>
            <Text className={item.status === 'AVAILABLE' ? 'sch-tag sch-tag-open' : 'sch-tag sch-tag-closed'}>
              {item.status === 'AVAILABLE' ? '可约' : '已关闭'}
            </Text>
          </View>
        })}
        {staffPhase === 'ready' && staffWindows.length > 0 && <Text className='sch-hint'>完整增改请进「员工排班」。</Text>}
      </View>
      {notice && <Text className='sch-notice'>{notice}</Text>}
      {preview && <View className='sch-tail'><Text>页面数据：契约 Mock（preview=1，不联调）</Text></View>}
    </ScrollView>
  </ScheduleShell>
}

function realRepository(): ScheduleDeps {
  return new RealScheduleRepository(consumerApi,
    () => consumerApi.scope.current?.merchantId || '',
    () => consumerApi.scope.current?.storeId || '')
}
