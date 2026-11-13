import { Button, Input, ScrollView, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi, consumerSession } from '../../../shared/consumer-runtime'
import { RealScheduleRepository } from '../../schedule/repository'
import {
  beijingToday, emptyWindowForm, isScheduleScenario, PreviewScheduleRepository,
  reasonProblem, scheduleAvailability, scheduleClosedReason, scheduleMessage, windowListPageSize,
  windowFormFromItem, windowFormProblems, windowStatusTagClass, windowStatusText,
  type ScheduleDeps, type ScheduleWindowItem, type WindowFormInput,
} from '../../schedule/model'
import { Field, IntervalFields, intervalText, ScheduleShell, useScheduleStyle, type PagePhase } from './parts'
import './page.css'

// M-002 服务时段管理（Contract 53号 §3/§3.1/§3.2，CCR-SCH-SLOT-001 统一槽改造）。设计源见
// registry §4（无契约语义兼容原稿，沿 M 端现行页面规范实现）。窗口 = 全店共享时间槽：一套
// 统一槽对所有服务开放（无按服务/按类型开窗步骤），容量为全店单池，任何服务的预约共同消耗，
// 占满即「已约满」；SOLD_OUT 为系统派生态，只读呈现，不设手工置满/强制可约入口；占用窗禁止
// 关闭/改期/降容量（409 呈现），升容量放行并自动回位。重叠校验同店不重叠（相邻半开可衔接）。
// 批量关闭按日历日范围提交，单次相交条目 >200 时整笔拒绝（400 呈现），部分成功明示受阻窗。
// #122/§3.3 后列表切服务端分页：首屏拉第 1 页（20 条），「加载更多」按 total 追加，
// 计数行用信封 total；每次写操作后整表重拉回第 1 页（与此前全量刷新语义一致）。
type Panel = 'none' | 'create' | 'batch'

export default function ScheduleWindowsPage() {
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
  const [panel, setPanel] = useState<Panel>('none')
  const [create, setCreate] = useState<WindowFormInput>(() => emptyWindowForm(beijingToday(), '09:00'))
  const [editing, setEditing] = useState<{ item: ScheduleWindowItem; form: WindowFormInput } | null>(null)
  const [closeTarget, setCloseTarget] = useState<ScheduleWindowItem | null>(null)
  const [closeReason, setCloseReason] = useState('')
  const [batch, setBatch] = useState({ fromDate: beijingToday(), toDate: beijingToday(), reason: '' })
  const [batchResult, setBatchResult] = useState('')
  const [busy, setBusy] = useState(false)
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
      const first = await repository.windows({ page: 1, pageSize: windowListPageSize })
      if (!mounted.current || sequence !== loadSequence.current) return
      setWindows(first.items)
      setListTotal(first.total)
      setPage(1)
      setLoadingMore(false)
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
  useEffect(() => { if (!preview && (!context || context.workspace !== 'merchant')) setPhase('entry') }, [context, preview])
  const previousRevision = useRef(revision)
  useEffect(() => {
    if (previousRevision.current !== revision) {
      previousRevision.current = revision
      const current = scope.current
      if (!preview && (!current || current.workspace !== 'merchant')) setPhase('entry')
    }
  }, [revision, scope, preview])

  async function back() {
    // P-03 追缉（2026-11-12）：商家域返回不用 navigateBack（隐藏页树可能已被 dispose）。
    await Taro.reLaunch({ url: '/merchant/pages/schedule/index' }).catch(() => setNotice('返回失败，请重试'))
  }
  /** Append the next server page while some of the total is still unloaded (§3.3). */
  async function loadMore() {
    if (loadingMore || phase !== 'ready' || windows.length >= listTotal || page >= 10000) return
    const sequence = loadSequence.current
    setLoadingMore(true)
    try {
      const next = await repository.windows({ page: page + 1, pageSize: windowListPageSize })
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

  async function submitCreate() {
    if (busy) return
    const problems = windowFormProblems(create)
    if (problems.length) { setNotice(`请检查：${problems.join('；')}`); return }
    setNotice(''); setBusy(true)
    try {
      await repository.createWindow('schedule-window:new', create)
      await load()
      setPanel('none')
      setNotice('服务时段已创建。')
    } catch (error) { setNotice(scheduleMessage(error)) } finally { if (mounted.current) setBusy(false) }
  }
  function startEdit(item: ScheduleWindowItem) {
    setPanel('none'); setCloseTarget(null); setBatchResult('')
    setEditing({ item, form: windowFormFromItem(item) })
  }
  async function submitEdit() {
    if (busy || !editing) return
    const problems = windowFormProblems(editing.form)
    if (problems.length) { setNotice(`请检查：${problems.join('；')}`); return }
    setNotice(''); setBusy(true)
    try {
      await repository.updateWindow(`schedule-window:${editing.item.windowId}:update`,
        editing.item.windowId, editing.item.version, editing.form)
      setEditing(null)
      await load()
      setNotice('服务时段已保存；提高容量后系统会按占用自动回位。')
    } catch (error) { setNotice(scheduleMessage(error)) } finally { if (mounted.current) setBusy(false) }
  }
  async function submitClose() {
    if (busy || !closeTarget) return
    const problem = reasonProblem(closeReason, true)
    if (problem) { setNotice(problem); return }
    setNotice(''); setBusy(true)
    try {
      await repository.closeWindow(`schedule-window:${closeTarget.windowId}:close`, closeTarget.windowId, closeTarget.version, closeReason)
      setCloseTarget(null); setCloseReason('')
      await load()
      setNotice('服务时段已关闭；关闭保留历史，可再次开放。')
    } catch (error) { setNotice(scheduleMessage(error)) } finally { if (mounted.current) setBusy(false) }
  }
  async function reopen(item: ScheduleWindowItem) {
    if (busy) return
    setNotice(''); setBusy(true)
    try {
      const receipt = await repository.openWindow(`schedule-window:${item.windowId}:open`, item.windowId, item.version)
      await load()
      setNotice(receipt.status === 'SOLD_OUT' ? '已重新开放；因占用已满，系统置为「已约满」。' : '服务时段已开放。')
    } catch (error) { setNotice(scheduleMessage(error)) } finally { if (mounted.current) setBusy(false) }
  }
  async function submitBatch() {
    if (busy) return
    const problem = reasonProblem(batch.reason, true)
    if (problem) { setNotice(problem); return }
    setNotice(''); setBusy(true)
    try {
      const result = await repository.batchClose('schedule-batch-close', batch.fromDate, batch.toDate, batch.reason)
      setBatchResult(`批量关闭完成：已关闭 ${result.closedWindows.length} 个时段` +
        (result.blockedWindows.length ? `；${result.blockedWindows.length} 个因占用受阻（见列表标记）` : '') + '。')
      await load()
    } catch (error) { setNotice(scheduleMessage(error)) } finally { if (mounted.current) setBusy(false) }
  }

  return <ScheduleShell title='服务时段管理' style={style} phase={phase} notice={notice} closedReason={closedReason}
    onRetry={() => void load()} onBack={() => void back()} preview={preview}>
    <ScrollView className='sch-body' scrollY enhanced showScrollbar={false}>
      <View className='sch-count-row'>
        <Text className='sch-count'>共 {listTotal} 个时段</Text>
        <Button id='sch-create-toggle' className='sch-count-action' onClick={() => { setPanel(panel === 'create' ? 'none' : 'create'); setEditing(null); setBatchResult('') }}>
          {panel === 'create' ? '收起新建' : '+ 新建时段'}
        </Button>
      </View>
      <View className='sch-count-row'>
        <Text className='sch-hint'>全店共享时间槽：分钟级排期，不固定1小时槽；相邻半开时段可衔接。</Text>
        <Button id='sch-batch-toggle' className='sch-count-action' onClick={() => { setPanel(panel === 'batch' ? 'none' : 'batch'); setEditing(null); setBatchResult('') }}>
          {panel === 'batch' ? '收起批量' : '批量关闭'}
        </Button>
      </View>
      {panel === 'create' && <View className='sch-form'>
        <Text className='sch-banner-text'>全店共享时间槽：无需选服务、无需选类型，直接定时间与容量；所有服务共用这一时段承接预约。</Text>
        <IntervalFields startDate={create.startDate} startTime={create.startTime} endDate={create.endDate} endTime={create.endTime}
          onChange={next => setCreate(current => ({ ...current, ...next }))} />
        <Field label='容量' required hint='该时段全店可承接的预约总数（所有服务共用一个容量池，占满即「已约满」）。'>
          <Input className='sch-input' value={create.configuredCapacity === null ? '' : String(create.configuredCapacity)} type='number'
            placeholder='如 2' onInput={event => setCreate(current => ({ ...current,
              configuredCapacity: event.detail.value === '' ? null : Math.trunc(Number(event.detail.value)) }))} />
        </Field>
        <Button id='sch-create-submit' className='sch-primary' disabled={busy} onClick={() => void submitCreate()}>创建时段</Button>
      </View>}
      {panel === 'batch' && <View className='sch-form'>
        <Text className='sch-banner-text'>按日历日范围批量关闭相交的开放/约满时段（跨天相交按整窗处理）；单次最多处理 200 条，超出整笔拒绝。</Text>
        <IntervalFields startDate={batch.fromDate} startTime='00:00' endDate={batch.toDate} endTime='00:00'
          dateOnly labels={{ start: '开始日', end: '结束日' }}
          onChange={next => setBatch(current => ({ ...current,
            fromDate: next.startDate ?? current.fromDate, toDate: next.endDate ?? current.toDate }))} />
        <Field label='操作原因' required hint='临时停业原因必填（1-500字），记入操作审计。'>
          <Textarea className='sch-textarea' value={batch.reason} maxlength={500} placeholder='如：门店装修暂停营业'
            onInput={event => setBatch(current => ({ ...current, reason: event.detail.value }))} />
        </Field>
        <Button id='sch-batch-submit' className='sch-primary' disabled={busy} onClick={() => void submitBatch()}>批量关闭</Button>
        {batchResult && <View role='status'><Text className='sch-banner-text'>{batchResult}</Text></View>}
      </View>}
      {editing && <View className='sch-form'>
        <Text className='sch-form-title'>编辑时段</Text>
        <Text className='sch-banner-text'>全店共享时间槽 · {intervalText(editing.item.startAt, editing.item.endAt)}</Text>
        <IntervalFields startDate={editing.form.startDate} startTime={editing.form.startTime}
          endDate={editing.form.endDate} endTime={editing.form.endTime}
          onChange={next => setEditing(current => current ? { ...current, form: { ...current.form, ...next } } : current)} />
        <Field label='容量' required hint='已有占用时不可降容量或改期；提高容量后系统按占用自动回位。'>
          <Input className='sch-input' value={String(editing.form.configuredCapacity ?? '')} type='number'
            onInput={event => setEditing(current => current ? { ...current, form: { ...current.form,
              configuredCapacity: event.detail.value === '' ? null : Math.trunc(Number(event.detail.value)) } } : current)} />
        </Field>
        <View className='sch-action-row'>
          <Button id='sch-edit-save' className='sch-primary' disabled={busy} onClick={() => void submitEdit()}>保存修改</Button>
          <Button className='sch-secondary' disabled={busy} onClick={() => setEditing(null)}>取消</Button>
        </View>
      </View>}
      {closeTarget && <View className='sch-form'>
        <Text className='sch-form-title'>关闭时段</Text>
        <Text className='sch-banner-text'>全店共享时间槽 · {intervalText(closeTarget.startAt, closeTarget.endAt)}；关闭保留历史，可再次开放。</Text>
        <Field label='操作原因' required hint='关闭原因必填（1-500字），记入操作审计。'>
          <Textarea className='sch-textarea' value={closeReason} maxlength={500} placeholder='如：该时段员工不足'
            onInput={event => setCloseReason(event.detail.value)} />
        </Field>
        <View className='sch-action-row'>
          <Button id='sch-close-confirm' className='sch-primary' disabled={busy} onClick={() => void submitClose()}>确认关闭</Button>
          <Button className='sch-secondary' disabled={busy} onClick={() => { setCloseTarget(null); setCloseReason('') }}>取消</Button>
        </View>
      </View>}
      {windows.length === 0 && <View className='sch-empty'><Text>还没有时间槽，点击「+ 新建时段」创建第一个（全店共享）。</Text></View>}
      {windows.map(item => <View key={item.windowId} className='sch-card'>
        <View className='sch-card-head'>
          <Text className='sch-card-name'>{intervalText(item.startAt, item.endAt)}</Text>
          <Text className={windowStatusTagClass(item.status)}>{windowStatusText[item.status]}</Text>
        </View>
        <Text className='sch-card-line'>全店共享 · 容量 {item.configuredCapacity}</Text>
        {item.status === 'SOLD_OUT' && <Text className='sch-card-hint'>已约满：占用达容量由系统自动置位，释放或提高容量后自动回位；不可手工置位或强制可约。</Text>}
        {item.status === 'CLOSED'
          ? <View className='sch-action-row'>
              <Button id={`sch-open-${item.windowId}`} className='sch-secondary' disabled={busy} onClick={() => void reopen(item)}>开放</Button>
            </View>
          : <View className='sch-action-row'>
              <Button id={`sch-edit-${item.windowId}`} className='sch-secondary' disabled={busy} onClick={() => startEdit(item)}>编辑</Button>
              <Button id={`sch-close-${item.windowId}`} className='sch-secondary' disabled={busy}
                onClick={() => { setEditing(null); setBatchResult(''); setCloseTarget(item); setCloseReason('') }}>关闭</Button>
            </View>}
      </View>)}
      {windows.length < listTotal && <Button id='sch-windows-more' className='sch-load-more' disabled={loadingMore}
        onClick={() => void loadMore()}>{loadingMore ? '正在加载…' : `加载更多（已显示 ${windows.length}/${listTotal}）`}</Button>}
      {notice && <Text id='sch-notice' className='sch-notice'>{notice}</Text>}
      {preview && <View className='sch-tail'><Text>页面数据：契约 Mock（preview=1，不联调）</Text></View>}
    </ScrollView>
  </ScheduleShell>
}

function realRepository(): ScheduleDeps {
  return new RealScheduleRepository(consumerApi,
    () => consumerApi.scope.current?.merchantId || '',
    () => consumerApi.scope.current?.storeId || '')
}
