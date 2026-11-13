import { Button, Image, Input, Picker, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi, consumerSession } from '../../../shared/consumer-runtime'
import { RealScheduleRepository } from '../../schedule/repository'
import {
  addBeijingDays, beijingToday, bookingLeadMinutesDefault, bookingMaxDaysDefault,
  bookingWindowFormProblems, defaultWeekDraft, formatWindowInterval, generateCapacityDefault,
  generateFormProblems, generateIntervalMinutes, isScheduleScenario, nextSevenDays,
  PreviewScheduleRepository, scheduleAvailability, scheduleClosedReason, scheduleMessage,
  timeMinutes, weekDraftProblems, weekdayIndexOf, weekdayLabels, windowCountPageSize,
  windowListPageSize, windowStatusTagClass, windowStatusText, windowStatuses,
  type BatchGenerateInput, type BookingWindowForm, type GenerateFormInput, type GenerateOutcome,
  type ScheduleDeps, type ScheduleWindowItem, type StaffWindowItem, type WeekDraft,
  type WindowStatus,
} from '../../schedule/model'
import {
  capsuleTimeText, dayGroupLabel, durationMinutesText, groupWindowsByDate, statNumberClass,
  windowCapsuleClass, windowDurationMinutes,
} from '../../schedule/view'
import { Chip, ScheduleShell, useScheduleStyle, type PagePhase } from './parts'
import gridSchedule from '../workspace/assets/grid-schedule.svg'
import gridMembers from '../workspace/assets/grid-members.svg'
import gridVerify from '../workspace/assets/grid-verify.svg'
import tGenerate from './assets/t-generate.svg'
import tTime from './assets/t-time.svg'
import tSettings from './assets/t-settings.svg'
import tSpark from './assets/t-spark.svg'
import tClipboard from './assets/t-clipboard.svg'
import './page.css'

// M-002 排期管理（模板编辑页，Figma 12:6214「首页-排期管理」整页还原）。CCR-SCH-SLOT-002
// （2026-11-13 批准，P-11b 随统一槽复活）后本页从列表页重做为模板编辑页：
// - 区块 A 一键生成：门店时间（默认 09:00/19:00）+ 容量（默认 6，CCR §7 裁决）→ 一次
//   batch-generate 覆盖今天起 7 天（intervalMinutes 固定 60，说明文案钉死）。
// - 区块 B 时间设置：周一~周日纯前端草稿态（开关+时段胶囊 ×/＋/📋）——dayOfWeek/
//   repeatWeekly/copyNextWeek 三字段永不上线，「保存并生效」按启用日把当日时段逐段物化为
//   真实分钟级统一槽窗口（nextSevenDays 今天起 7 个北京历日）。
// - 区块 C 通用设置：可预约范围（lead/maxDays，PUT booking-window）+ 不可约日期（本地清单，
//   保存时对该日历日 batch-close（reason 自动生成），不新增黑名单规则）。
// - 底栏：取消=重置草稿；保存并生效=B 区逐日逐段 batch-generate + C 区配置，结果弹层如实
//   展示 created/closed/skipped（被占用跳过的时段明确列出），部分失败照列。
// - P-11a 列表成果（状态筛选/三态计数/日期分组）不丢：收进底部折叠区（懒加载），入口宫格
//   与员工排班查询保留；windows/staff/capabilities 子页不变。
// 导航纪律：返回工作台 reLaunch（P-03）；merchant 域禁 navigateBack。
type StaffQuery = 'idle' | 'loading' | 'ready' | 'error'
type WindowListQuery = { page: number; pageSize: number; status?: WindowStatus }
type ListPhase = 'idle' | 'loading' | 'ready' | 'error'
/** 时段胶囊编辑器：index=null 表示添加新段。 */
type SegmentEditor = Readonly<{ day: number; index: number | null; start: string; end: string }>
type CopyEditor = Readonly<{ day: number; targets: readonly number[] }>
type OutcomePanel = Readonly<{ title: string; result: GenerateOutcome; notes: readonly string[] }>

const hhmm = (minutes: number): string =>
  `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`

export default function ScheduleTemplatePage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isScheduleScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope, revision, context } = useWorkspace(preview ? 'preview' : 'real')
  const [repository] = useState<ScheduleDeps>(() => preview ? new PreviewScheduleRepository(undefined, undefined, undefined, scenario) : realRepository())
  const style = useScheduleStyle()
  const [phase, setPhase] = useState<PagePhase>('idle')
  const [closedReason, setClosedReason] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  // CCR §2 booking-window 基线（GET 缺省=默认值 60/7，version 驱动 PUT 乐观锁）。
  const [bookingBase, setBookingBase] = useState<{ leadMinutes: number; maxDays: number; version: string } | null>(null)
  // 区块 A：门店时间 + 容量（容量同时驱动一键生成与 B 区模板保存）。
  const [generate, setGenerate] = useState<GenerateFormInput>({ dailyStart: '09:00', dailyEnd: '19:00', configuredCapacity: generateCapacityDefault })
  // 区块 B：周模板草稿（纯前端，不落库）。
  const [week, setWeek] = useState<WeekDraft>(defaultWeekDraft)
  const [segEditor, setSegEditor] = useState<SegmentEditor | null>(null)
  const [copying, setCopying] = useState<CopyEditor | null>(null)
  // 区块 C：可预约范围草稿（文本输入态）+ 不可约日期本地清单。
  const [bookingLeadText, setBookingLeadText] = useState(String(bookingLeadMinutesDefault))
  const [bookingDaysText, setBookingDaysText] = useState(String(bookingMaxDaysDefault))
  const [blackoutDates, setBlackoutDates] = useState<readonly string[]>([])
  // 结果弹层（一键生成 / 保存并生效共用，如实展示 created/closed/skipped）。
  const [outcome, setOutcome] = useState<OutcomePanel | null>(null)
  // P-11a 折叠区：状态筛选 + 三态计数 + 日期分组列表（懒加载）+ 员工排班查询。
  const [listOpen, setListOpen] = useState(false)
  const [listPhase, setListPhase] = useState<ListPhase>('idle')
  const [listNotice, setListNotice] = useState('')
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
  const listLoadedOnce = useRef(false)
  // Server-side chips: loadList() reads the CURRENT selection through this ref so the
  // collapse-open effect and chip handlers share one query builder without stale-closure risk.
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
      const view = await repository.bookingWindow()
      if (!mounted.current || sequence !== loadSequence.current) return
      setBookingBase({ leadMinutes: view.leadMinutes, maxDays: view.maxDays, version: view.version })
      setBookingLeadText(String(view.leadMinutes))
      setBookingDaysText(String(view.maxDays))
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
  // Coordinates switched away invalidate the page (workbench leave / account switch).
  const previousRevision = useRef(revision)
  useEffect(() => {
    if (previousRevision.current !== revision) {
      previousRevision.current = revision
      const current = scope.current
      if (!preview && (!current || current.workspace !== 'merchant')) setPhase('entry')
    }
  }, [revision, scope, preview])

  /** P-11a 折叠区列表：首次展开或筛选变化时拉取（服务端分页 + 状态过滤同源）。 */
  const loadList = useCallback(async () => {
    const sequence = loadSequence.current
    setListNotice('')
    setListPhase('loading')
    try {
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
      setListPhase('ready')
      listLoadedOnce.current = true
    } catch (error) {
      if (!mounted.current || sequence !== loadSequence.current) return
      setListNotice(scheduleMessage(error)); setListPhase('error')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- filterRef is a deliberate mutable ref
  }, [repository])
  useEffect(() => {
    if (listOpen && phase === 'ready' && !listLoadedOnce.current) void loadList()
  }, [listOpen, phase, loadList])

  async function back() {
    await Taro.reLaunch({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('返回失败，请重试'))
  }
  function open(sub: string) {
    Taro.navigateTo({ url: `/merchant/pages/schedule/${sub}?preview=${preview ? '1' : '0'}${preview ? `&scenario=${scenario}` : ''}` })
      .catch(() => setNotice('页面跳转失败，请重试'))
  }

  // ---------------------------------------------------------------------------
  // 草稿态操作（区块 A/B/C，纯本地；写操作只在「一键生成」和「保存并生效」）。
  // ---------------------------------------------------------------------------
  function bookingForm(): BookingWindowForm {
    const lead = bookingLeadText.trim()
    const days = bookingDaysText.trim()
    return {
      leadMinutes: lead === '' ? null : Math.trunc(Number(lead)),
      maxDays: days === '' ? null : Math.trunc(Number(days)),
    }
  }
  function toggleDay(index: number) {
    setWeek(current => current.map((day, at) => at === index ? { ...day, enabled: !day.enabled } : day))
  }
  function removeSegment(day: number, index: number) {
    setSegEditor(null)
    setWeek(current => current.map((row, at) => at === day
      ? { ...row, segments: row.segments.filter((_, position) => position !== index) } : row))
  }
  /** ＋ 默认时段：接当日最后一段结束时间顺延 1 小时（越界回落 09:00-10:00）。 */
  function openAddSegment(day: number) {
    const segments = week[day]!.segments
    const last = segments.length ? segments[segments.length - 1]! : null
    const start = last ? timeMinutes(last.end) : 9 * 60
    const end = start + 60
    setSegEditor(start + 60 <= 23 * 60 + 59
      ? { day, index: null, start: hhmm(start), end: hhmm(end) }
      : { day, index: null, start: '09:00', end: '10:00' })
  }
  function segmentProblems(editor: SegmentEditor): string[] {
    if (timeMinutes(editor.start) >= timeMinutes(editor.end)) return ['结束时间需晚于开始时间']
    const start = timeMinutes(editor.start), end = timeMinutes(editor.end)
    const overlaps = week[editor.day]!.segments.some((segment, position) => {
      if (position === editor.index) return false
      return start < timeMinutes(segment.end) && timeMinutes(segment.start) < end
    })
    return overlaps ? ['与同一天其他时段重叠'] : []
  }
  function saveSegment() {
    if (!segEditor) return
    const problems = segmentProblems(segEditor)
    if (problems.length) { setNotice(`请检查：${problems.join('；')}`); return }
    setNotice('')
    const { day, index, start, end } = segEditor
    setWeek(current => current.map((row, at) => at === day
      ? { ...row, segments: index === null
          ? [...row.segments, { start, end }]
          : row.segments.map((segment, position) => position === index ? { start, end } : segment) }
      : row))
    setSegEditor(null)
  }
  function toggleCopyTarget(target: number) {
    setCopying(current => !current ? current : {
      ...current,
      targets: current.targets.includes(target)
        ? current.targets.filter(item => item !== target)
        : [...current.targets, target],
    })
  }
  function confirmCopy() {
    if (!copying) return
    const source = week[copying.day]!
    const copies = source.segments.map(segment => ({ ...segment }))
    setWeek(current => current.map((row, at) =>
      at === copying.day || !copying.targets.includes(at) ? row : { ...row, segments: copies.map(segment => ({ ...segment })) }))
    setCopying(null)
  }
  function addBlackout(event: { detail: { value: string } }) {
    const date = event.detail.value
    if (!date || blackoutDates.includes(date)) { setNotice('该日期已在不可约清单中。'); return }
    setNotice('')
    setBlackoutDates(current => [...current, date].sort())
  }
  function removeBlackout(date: string) {
    setBlackoutDates(current => current.filter(item => item !== date))
  }
  /** 取消=重置草稿：回到基线（booking-window 上次读取值 + 默认模板），不动已生效数据。 */
  function resetDraft() {
    setGenerate({ dailyStart: '09:00', dailyEnd: '19:00', configuredCapacity: generateCapacityDefault })
    setWeek(defaultWeekDraft())
    setBookingLeadText(String(bookingBase?.leadMinutes ?? bookingLeadMinutesDefault))
    setBookingDaysText(String(bookingBase?.maxDays ?? bookingMaxDaysDefault))
    setBlackoutDates([])
    setSegEditor(null)
    setCopying(null)
    setNotice('')
  }

  const skippedOf = (result: { skippedWindows: readonly { window: { startAt: string; endAt: string }; reasonCode: string }[] }) =>
    result.skippedWindows.map(skipped => ({
      interval: formatWindowInterval(skipped.window.startAt, skipped.window.endAt),
      reasonCode: skipped.reasonCode,
    }))

  /** 区块 A：一键生成全部日期——今天起 7 天，1 小时间隔，覆盖现有配置（CCR §1）。 */
  async function quickGenerate() {
    if (busy) return
    const problems = generateFormProblems(generate)
    if (problems.length) { setNotice(`请检查：${problems.join('；')}`); return }
    setNotice(''); setBusy(true)
    try {
      const today = beijingToday()
      const input: BatchGenerateInput = {
        fromDate: today, toDate: addBeijingDays(today, 6),
        dailyStart: generate.dailyStart, dailyEnd: generate.dailyEnd,
        intervalMinutes: generateIntervalMinutes,
        configuredCapacity: generate.configuredCapacity!, reason: '一键生成',
      }
      const result = await repository.batchGenerate('schedule-generate:quick', input)
      setOutcome({
        title: '一键生成结果',
        result: {
          created: result.createdWindows.length, closed: result.closedWindows.length,
          skipped: skippedOf(result), failed: [],
        },
        notes: [`范围 ${input.fromDate} 至 ${input.toDate}，1 小时间隔，每时段容量 ${input.configuredCapacity}。`],
      })
      if (listOpen && listLoadedOnce.current) await loadList()
    } catch (error) {
      setNotice(scheduleMessage(error))
    } finally {
      if (mounted.current) setBusy(false)
    }
  }

  /** 底栏「保存并生效」：B 区启用日逐日逐段 batch-generate（每次一天一段，≤200 候选安全）→
   *  C 区 booking-window PUT（有改动时）→ 不可约日期逐日 batch-close；全部结果如实聚合。 */
  async function saveAll() {
    if (busy) return
    const booking = bookingForm()
    const problems = [...generateFormProblems(generate), ...weekDraftProblems(week), ...bookingWindowFormProblems(booking)]
    if (problems.length) { setNotice(`请检查：${problems.join('；')}`); return }
    setNotice(''); setBusy(true)
    // 聚合器（可变本地，收尾冻结为只读结果）。
    const acc: { created: number; closed: number; skipped: { interval: string; reasonCode: string }[]; failed: { label: string; message: string }[] } = { created: 0, closed: 0, skipped: [], failed: [] }
    const notes: string[] = []
    try {
      const capacity = generate.configuredCapacity!
      for (const { date, weekday } of nextSevenDays(beijingToday())) {
        const day = week[weekday]!
        if (!day.enabled || day.segments.length === 0) continue
        for (let segIndex = 0; segIndex < day.segments.length; segIndex++) {
          const segment = day.segments[segIndex]!
          const input: BatchGenerateInput = {
            fromDate: date, toDate: date,
            dailyStart: segment.start, dailyEnd: segment.end,
            intervalMinutes: generateIntervalMinutes,
            configuredCapacity: capacity, reason: '排期模板保存生效',
          }
          try {
            const result = await repository.batchGenerate(`schedule-generate:${date}:${segIndex}`, input)
            acc.created += result.createdWindows.length
            acc.closed += result.closedWindows.length
            for (const skipped of skippedOf(result)) {
              if (!acc.skipped.some(item => item.interval === skipped.interval)) acc.skipped.push(skipped)
            }
          } catch (error) {
            acc.failed.push({ label: `${date} ${weekdayLabels[weekday]} ${segment.start}-${segment.end}`, message: scheduleMessage(error) })
          }
        }
      }
      if (bookingBase && (bookingBase.leadMinutes !== booking.leadMinutes || bookingBase.maxDays !== booking.maxDays)) {
        try {
          await repository.saveBookingWindow('schedule-booking-window:put', booking, bookingBase.version)
          notes.push(`可预约范围已保存：客户可预约 ${booking.leadMinutes} 分钟后至 ${booking.maxDays} 天内的时段。`)
        } catch (error) {
          acc.failed.push({ label: '可预约范围', message: scheduleMessage(error) })
        }
      }
      for (const date of blackoutDates) {
        try {
          const closed = await repository.batchClose(`schedule-blackout:${date}`, date, date, `不可约日期 ${date}`)
          notes.push(`不可约日期 ${date}：关闭 ${closed.closedWindows.length} 个时段`
            + (closed.blockedWindows.length ? `，${closed.blockedWindows.length} 个因占用受阻` : '') + '。')
        } catch (error) {
          acc.failed.push({ label: `不可约日期 ${date}`, message: scheduleMessage(error) })
        }
      }
      setOutcome({ title: '保存并生效结果', result: {
        created: acc.created, closed: acc.closed,
        skipped: [...acc.skipped], failed: [...acc.failed],
      }, notes: [
        `模板覆盖 ${beijingToday()} 起 7 天内启用的日期（1 小时间隔，每时段容量 ${capacity}）。`, ...notes,
      ] })
      // 刷新基线与折叠区列表（失败不阻塞结果展示）。
      try {
        const view = await repository.bookingWindow()
        setBookingBase({ leadMinutes: view.leadMinutes, maxDays: view.maxDays, version: view.version })
      } catch { /* keep the pre-save baseline for the optimistic lock */ }
      if (listOpen && listLoadedOnce.current) await loadList()
    } finally {
      if (mounted.current) setBusy(false)
    }
  }

  function selectStatus(target: WindowStatus | '') {
    setStatus(target)
    filterRef.current = { ...filterRef.current, status: target }
    void loadList()
  }
  /** Append the next server page while some of the filtered total is still unloaded. */
  async function loadMore() {
    if (loadingMore || listPhase !== 'ready' || windows.length >= listTotal || page >= 10000) return
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
      setListNotice(scheduleMessage(error))
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
  return <ScheduleShell title='排期管理' style={style} phase={phase} notice={notice} closedReason={closedReason}
    onRetry={() => void load()} onBack={() => void back()} preview={preview} pageClass='sch-t-editor'>
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
      {/* 区块 A 一键生成（SECTION-17） */}
      <View className='sch-t-card'>
        <View className='sch-t-head'>
          <Image className='sch-t-head-icon' src={tGenerate} mode='aspectFit' />
          <Text className='sch-t-h2'>一键生成排期</Text>
          <Text className='sch-t-head-hint'>按门店时间每小时生成</Text>
        </View>
        <View className='sch-t-row'>
          <Text className='sch-t-label'>门店时间</Text>
          <View className='sch-t-picker'>
            <Picker mode='time' value={generate.dailyStart}
              onChange={event => setGenerate(current => ({ ...current, dailyStart: event.detail.value }))}>
              <View className='sch-t-field'><Text className='sch-t-field-text'>{generate.dailyStart}</Text></View>
            </Picker>
          </View>
          <Text className='sch-t-mid'>至</Text>
          <View className='sch-t-picker'>
            <Picker mode='time' value={generate.dailyEnd}
              onChange={event => setGenerate(current => ({ ...current, dailyEnd: event.detail.value }))}>
              <View className='sch-t-field'><Text className='sch-t-field-text'>{generate.dailyEnd}</Text></View>
            </Picker>
          </View>
        </View>
        <View className='sch-t-row'>
          <Text className='sch-t-label'>每时段容量</Text>
          <Input className='sch-t-field sch-t-field-input' type='number'
            value={generate.configuredCapacity === null ? '' : String(generate.configuredCapacity)}
            placeholder='如 6'
            onInput={event => setGenerate(current => ({ ...current,
              configuredCapacity: event.detail.value === '' ? null : Math.trunc(Number(event.detail.value)) }))} />
          <Text className='sch-t-mid'>人</Text>
        </View>
        <Button id='sch-t-generate' className='sch-t-generate' disabled={busy} onClick={() => void quickGenerate()}>
          <Image className='sch-t-generate-icon' src={tSpark} mode='aspectFit' />
          <Text className='sch-t-generate-text'>一键生成全部日期</Text>
        </Button>
        <Text className='sch-t-note'>以 1 小时为间隔，生成 7 天的时段，覆盖现有配置</Text>
      </View>
      {/* 区块 B 时间设置（SECTION-46）：周一~周日草稿态 */}
      <View className='sch-t-card'>
        <View className='sch-t-head'>
          <Image className='sch-t-head-icon' src={tTime} mode='aspectFit' />
          <Text className='sch-t-h2'>时间设置</Text>
          <Text className='sch-t-head-hint'>点击胶囊可编辑</Text>
        </View>
        {week.map((day, index) => <View key={weekdayLabels[index]} className={index === 0 ? 'sch-t-day' : 'sch-t-day sch-t-day-sep'}>
          <View className='sch-t-day-main'>
            <View className='sch-t-day-left'>
              <Text className='sch-t-day-name'>{weekdayLabels[index]}</Text>
              <Button className={day.enabled ? 'sch-t-switch sch-t-switch-on' : 'sch-t-switch'}
                ariaLabel={`${weekdayLabels[index]}${day.enabled ? '已启用，点击关闭' : '未启用，点击开启'}`}
                onClick={() => toggleDay(index)}><View className='sch-t-knob' /></Button>
            </View>
            <View className='sch-t-caps'>
              {day.segments.map((segment, position) => <View key={`${segment.start}-${segment.end}-${position}`} className='sch-t-cap'>
                <Button className='sch-t-cap-time'
                  ariaLabel={`${weekdayLabels[index]} ${segment.start} 至 ${segment.end}，点击编辑起止时间`}
                  onClick={() => { setCopying(null); setSegEditor({ day: index, index: position, start: segment.start, end: segment.end }) }}>
                  {segment.start} - {segment.end}
                </Button>
                <Button className='sch-t-cap-x'
                  ariaLabel={`删除 ${weekdayLabels[index]} ${segment.start} 至 ${segment.end}`}
                  onClick={() => removeSegment(index, position)}>×</Button>
              </View>)}
              <Button id={`sch-t-add-${index}`} className='sch-t-add'
                ariaLabel={`为${weekdayLabels[index]}添加时段`} onClick={() => { setCopying(null); openAddSegment(index) }}>+</Button>
              <Button id={`sch-t-copy-${index}`} className='sch-t-copy'
                ariaLabel={`复制${weekdayLabels[index]}的时段到其他日期`}
                onClick={() => { setSegEditor(null); setCopying({ day: index, targets: [] }) }}>
                <Image className='sch-t-copy-icon' src={tClipboard} mode='aspectFit' />
              </Button>
            </View>
          </View>
          {segEditor?.day === index && <View className='sch-t-edit'>
            <Text className='sch-t-edit-title'>{segEditor.index === null ? `添加时段 · ${weekdayLabels[index]}` : `编辑时段 · ${weekdayLabels[index]}`}</Text>
            <View className='sch-t-edit-row'>
              <View className='sch-t-picker'>
                <Picker mode='time' value={segEditor.start}
                  onChange={event => setSegEditor(current => current ? { ...current, start: event.detail.value } : current)}>
                  <View className='sch-t-field'><Text className='sch-t-field-text'>{segEditor.start}</Text></View>
                </Picker>
              </View>
              <Text className='sch-t-mid'>至</Text>
              <View className='sch-t-picker'>
                <Picker mode='time' value={segEditor.end}
                  onChange={event => setSegEditor(current => current ? { ...current, end: event.detail.value } : current)}>
                  <View className='sch-t-field'><Text className='sch-t-field-text'>{segEditor.end}</Text></View>
                </Picker>
              </View>
            </View>
            <View className='sch-t-edit-actions'>
              <Button className='sch-t-mini sch-t-mini-primary' onClick={saveSegment}>保存时段</Button>
              <Button className='sch-t-mini sch-t-mini-ghost' onClick={() => setSegEditor(null)}>取消</Button>
            </View>
          </View>}
          {copying?.day === index && <View className='sch-t-edit'>
            <Text className='sch-t-edit-title'>复制{weekdayLabels[index]}的时段到（覆盖目标日时段）</Text>
            <View className='sch-t-copy-row'>
              {weekdayLabels.map((label, at) => at !== index
                ? <Chip key={label} selected={copying.targets.includes(at)} onClick={() => toggleCopyTarget(at)}>{label}</Chip>
                : null)}
            </View>
            <View className='sch-t-edit-actions'>
              <Button className='sch-t-mini sch-t-mini-primary' disabled={copying.targets.length === 0} onClick={confirmCopy}>复制到选中日期</Button>
              <Button className='sch-t-mini sch-t-mini-ghost' onClick={() => setCopying(null)}>取消</Button>
            </View>
          </View>}
        </View>)}
        <Text className='sch-t-tip'>💡 点胶囊可修改起止时间，点「×」删除；「＋」添加时段，「📋」复制到其他日期；左侧开关控制当天是否开放</Text>
      </View>
      {/* 区块 C 通用设置（SECTION-256） */}
      <View className='sch-t-card'>
        <View className='sch-t-head'>
          <Image className='sch-t-head-icon' src={tSettings} mode='aspectFit' />
          <Text className='sch-t-h2'>通用设置</Text>
        </View>
        <Text className='sch-t-group'>可预约范围</Text>
        <View className='sch-t-booking'>
          <Text className='sch-t-booking-text'>客户可预约</Text>
          <Input className='sch-t-select' type='number' value={bookingLeadText}
            onInput={event => setBookingLeadText(event.detail.value)} />
          <Text className='sch-t-booking-text'>分钟后</Text>
          <Text className='sch-t-booking-text'>到</Text>
          <Input className='sch-t-select' type='number' value={bookingDaysText}
            onInput={event => setBookingDaysText(event.detail.value)} />
          <Text className='sch-t-booking-text'>天内</Text>
        </View>
        <Text className='sch-t-group'>不可约日期</Text>
        <View className='sch-t-dates'>
          {blackoutDates.length === 0 && <Text className='sch-t-dates-empty'>暂无不可约日期</Text>}
          {blackoutDates.map(date => <View key={date} className='sch-t-cap sch-t-date'>
            <Text className='sch-t-cap-time'>{date}（{weekdayLabelOf(date)}）</Text>
            <Button className='sch-t-cap-x' ariaLabel={`移除不可约日期 ${date}`} onClick={() => removeBlackout(date)}>×</Button>
          </View>)}
        </View>
        <View className='sch-t-blackout-actions'>
          <Picker mode='date' value={beijingToday()} start={beijingToday()} end={addBeijingDays(beijingToday(), 365)} onChange={addBlackout}>
            <Button id='sch-t-add-date' className='sch-t-add-date'>+ 添加日期</Button>
          </Picker>
        </View>
        <Text className='sch-t-tip'>💡 设为不可约的日期，用户端将无法选择该日期下单</Text>
      </View>
      {/* P-11a 成果折叠区：三态计数 + 状态筛选 + 日期分组列表（懒加载） */}
      <View className='sch-v-section'>
        <Button id='sch-t-list-toggle' className='sch-t-collapse' onClick={() => setListOpen(current => !current)}>
          {listOpen ? '收起当前排期时段' : `当前排期时段（${listPhase === 'ready' ? `${listTotal} 个` : '点击展开'}）`}
        </Button>
        {listOpen && <>
          {listPhase === 'loading' && <View className='sch-t-list-state'><Text>正在加载排期数据…</Text></View>}
          {listPhase === 'error' && <View className='sch-t-list-state'>
            <Text>{listNotice || '加载失败，请重试。'}</Text>
            <Button className='sch-state-action' onClick={() => void loadList()}>重新加载</Button>
          </View>}
          {listPhase === 'ready' && <>
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
            {windows.length === 0 && <View className='sch-empty'><Text>当前筛选下没有服务时段；可用上方「一键生成」或在「服务时段管理」新建。</Text></View>}
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
                </View>
              })}
            </View>)}
            {windows.length < listTotal && <Button id='sch-t-more' className='sch-load-more' disabled={loadingMore}
              onClick={() => void loadMore()}>{loadingMore ? '正在加载…' : `加载更多（已显示 ${windows.length}/${listTotal}）`}</Button>}
          </>}
          <View className='sch-v-head sch-t-staff-head'>
            <Text className='sch-v-h2'>员工排班查询</Text>
            <Text className='sch-v-head-hint'>按员工编号读取</Text>
          </View>
          <View className='sch-inline'>
            <Input className='sch-input sch-input-grow' value={staffId} placeholder='员工编号（如 958003）' type='number'
              onInput={event => setStaffId(event.detail.value)} />
            <Button id='sch-t-staff-query' className='sch-inline-action' disabled={staffPhase === 'loading'} onClick={() => void queryStaff()}>
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
        </>}
      </View>
      {notice && <Text id='sch-notice' className='sch-notice'>{notice}</Text>}
      {preview && <View className='sch-tail'><Text>页面数据：契约 Mock（preview=1，不联调）</Text></View>}
    </ScrollView>
    {/* 底栏（DIV-302）：取消=重置草稿；保存并生效=物化模板+配置 */}
    <View className='sch-t-bar'>
      <Button id='sch-t-cancel' className='sch-t-bar-cancel' disabled={busy} onClick={resetDraft}>取消</Button>
      <Button id='sch-t-save' className='sch-t-bar-save' disabled={busy} onClick={() => void saveAll()}>
        {busy ? '正在保存…' : '保存并生效'}
      </Button>
    </View>
    {/* 结果弹层：如实展示 created/closed/skipped，被占用跳过的时段明确列出 */}
    {outcome && <View className='sch-t-mask'>
      <View className='sch-t-modal' role='alert'>
        <Text className='sch-t-modal-title'>{outcome.title}</Text>
        <Text className='sch-t-modal-line'>新建时段 {outcome.result.created} 个；覆盖关闭 {outcome.result.closed} 个。</Text>
        {outcome.result.skipped.length > 0 && <>
          <Text className='sch-t-modal-sub'>被占用跳过 {outcome.result.skipped.length} 个（区间未覆盖，处理占用后可重试）：</Text>
          {outcome.result.skipped.map(skipped => <Text key={skipped.interval} className='sch-t-modal-item'>
            · {skipped.interval}（{skipped.reasonCode === 'OCCUPIED' ? '已有预约占用' : skipped.reasonCode}）
          </Text>)}
        </>}
        {outcome.notes.length > 0 && outcome.notes.map(note => <Text key={note} className='sch-t-modal-line'>{note}</Text>)}
        {outcome.result.failed.length > 0 && <>
          <Text className='sch-t-modal-sub'>部分请求未成功（可修正后重试）：</Text>
          {outcome.result.failed.map(failure => <Text key={failure.label} className='sch-t-modal-item'>· {failure.label}：{failure.message}</Text>)}
        </>}
        <Button id='sch-t-outcome-close' className='sch-t-modal-close' onClick={() => setOutcome(null)}>我知道了</Button>
      </View>
    </View>}
  </ScheduleShell>
}

function weekdayLabelOf(date: string): string {
  const weekday = weekdayIndexOf(date)
  return weekday >= 0 && weekday < weekdayLabels.length ? weekdayLabels[weekday]! : ''
}

function realRepository(): ScheduleDeps {
  return new RealScheduleRepository(consumerApi,
    () => consumerApi.scope.current?.merchantId || '',
    () => consumerApi.scope.current?.storeId || '')
}
