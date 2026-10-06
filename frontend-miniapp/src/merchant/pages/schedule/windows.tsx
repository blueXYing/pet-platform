import { Button, Input, ScrollView, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { loadServiceOptions, RealScheduleRepository, type ScheduleServiceOption } from '../../schedule/repository'
import { fixtureManagedServices } from '../../services/model'
import {
  beijingToday, emptyWindowForm, isScheduleScenario, kindsForFulfillment, PreviewScheduleRepository,
  reasonProblem, scheduleAvailability, scheduleClosedReason, scheduleMessage,
  windowFormFromItem, windowFormProblems, windowKindText, windowStatusTagClass, windowStatusText,
  type ScheduleDeps, type ScheduleWindowItem, type WindowFormInput, type WindowKind,
} from '../../schedule/model'
import { Chip, Field, IntervalFields, intervalText, ScheduleShell, useScheduleStyle, type PagePhase } from './parts'
import './page.css'

// M-002 服务时段管理（Contract 53号 §3/§3.1/§3.2）。设计源见 registry §4（无契约语义兼容
// 原稿，沿 M 端现行页面规范实现）。SOLD_OUT 为系统派生态：只读呈现「已约满」，不设手工
// 置满/强制可约入口；占用窗禁止关闭/改期/降容量（409 呈现），升容量放行并自动回位。
// 批量关闭按日历日范围提交，单次相交条目 >200 时整笔拒绝（400 呈现），部分成功明示受阻窗。
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
  const [options, setOptions] = useState<readonly ScheduleServiceOption[]>([])
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
      const page = await repository.windows({})
      if (!mounted.current || sequence !== loadSequence.current) return
      setWindows(page.items)
      setPhase('ready')
      const coords = scope.current
      if (coords?.merchantId && coords.storeId) {
        const loaded = preview ? previewOptions() : await loadServiceOptions(consumerApi, coords.merchantId, coords.storeId)
        if (mounted.current && sequence === loadSequence.current) setOptions(loaded)
      }
    } catch (error) {
      if (!mounted.current || sequence !== loadSequence.current) return
      if (scheduleAvailability(error) === 'closed') { setClosedReason(scheduleClosedReason(error)); setPhase('closed'); return }
      setNotice(scheduleMessage(error)); setPhase('load-error')
    }
  }, [preview, repository, scope])
  useDidShow(() => { void load() })
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
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack().catch(() => setNotice('返回失败，请重试'))
    else await Taro.redirectTo({ url: '/merchant/pages/schedule/index' }).catch(() => setNotice('返回失败，请重试'))
  }
  const serviceName = (serviceId: string) => options.find(option => option.serviceId === serviceId)?.serviceName || `服务 ${serviceId}`

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

  const createKinds = kindsForFulfillment(options.find(option => option.serviceId === create.serviceId)?.fulfillmentType ?? null)
  return <ScheduleShell title='服务时段管理' style={style} phase={phase} notice={notice} closedReason={closedReason}
    onRetry={() => void load()} onBack={() => void back()} preview={preview}>
    <ScrollView className='sch-body' scrollY enhanced showScrollbar={false}>
      <View className='sch-count-row'>
        <Text className='sch-count'>共 {windows.length} 个时段</Text>
        <Button id='sch-create-toggle' className='sch-count-action' onClick={() => { setPanel(panel === 'create' ? 'none' : 'create'); setEditing(null); setBatchResult('') }}>
          {panel === 'create' ? '收起新建' : '+ 新建时段'}
        </Button>
      </View>
      <View className='sch-count-row'>
        <Text className='sch-hint'>分钟级排期，不固定1小时槽；相邻半开时段可衔接。</Text>
        <Button id='sch-batch-toggle' className='sch-count-action' onClick={() => { setPanel(panel === 'batch' ? 'none' : 'batch'); setEditing(null); setBatchResult('') }}>
          {panel === 'batch' ? '收起批量' : '批量关闭'}
        </Button>
      </View>
      {panel === 'create' && <View className='sch-form'>
        <Field label='服务' required hint={options.length ? '按服务履约方式收敛可选时段类型；服务类型由创建时的身份固定。' : '服务列表暂不可用，可直接输入服务编号。'}>
          {options.length > 0
            ? <View className='sch-chip-row'>
                {options.map(option => <Chip key={option.serviceId} selected={create.serviceId === option.serviceId}
                  onClick={() => setCreate(current => ({ ...current, serviceId: option.serviceId }))}>{option.serviceName}</Chip>)}
              </View>
            : <Input className='sch-input' value={create.serviceId} type='number' placeholder='服务编号（Snowflake 数字）'
                onInput={event => setCreate(current => ({ ...current, serviceId: event.detail.value }))} />}
        </Field>
        <Field label='时段类型' required hint='到店型仅通用时段；上门接送型分「上门接」与「返程送回」。'>
          <View className='sch-chip-row'>
            {createKinds.map(target => <Chip key={target} selected={create.windowKind === target}
              onClick={() => setCreate(current => ({ ...current, windowKind: target }))}>{windowKindText[target]}</Chip>)}
          </View>
        </Field>
        <IntervalFields startDate={create.startDate} startTime={create.startTime} endDate={create.endDate} endTime={create.endTime}
          onChange={next => setCreate(current => ({ ...current, ...next }))} />
        <Field label='容量' required hint='同一时段可承接的预约数。'>
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
        <Text className='sch-form-title'>编辑时段（服务与类型固定，换目标=关闭后新建）</Text>
        <Text className='sch-banner-text'>{serviceName(editing.item.serviceId)} · {windowKindText[editing.item.windowKind]}</Text>
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
        <Text className='sch-banner-text'>{serviceName(closeTarget.serviceId)} · {intervalText(closeTarget.startAt, closeTarget.endAt)}；关闭保留历史，可再次开放。</Text>
        <Field label='操作原因' required hint='关闭原因必填（1-500字），记入操作审计。'>
          <Textarea className='sch-textarea' value={closeReason} maxlength={500} placeholder='如：该时段员工不足'
            onInput={event => setCloseReason(event.detail.value)} />
        </Field>
        <View className='sch-action-row'>
          <Button id='sch-close-confirm' className='sch-primary' disabled={busy} onClick={() => void submitClose()}>确认关闭</Button>
          <Button className='sch-secondary' disabled={busy} onClick={() => { setCloseTarget(null); setCloseReason('') }}>取消</Button>
        </View>
      </View>}
      {windows.length === 0 && <View className='sch-empty'><Text>还没有服务时段，点击「+ 新建时段」创建第一个。</Text></View>}
      {windows.map(item => <View key={item.windowId} className='sch-card'>
        <View className='sch-card-head'>
          <Text className='sch-card-name'>{serviceName(item.serviceId)}</Text>
          <Text className={windowStatusTagClass(item.status)}>{windowStatusText[item.status]}</Text>
        </View>
        <Text className='sch-card-line'>{windowKindText[item.windowKind]} · {intervalText(item.startAt, item.endAt)} · 容量 {item.configuredCapacity}</Text>
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
      {notice && <Text id='sch-notice' className='sch-notice'>{notice}</Text>}
      <View className='sch-tail'><Text>页面数据：{preview ? '契约 Mock（preview=1，不联调）' : '真实接口（开关未开放时失败关闭）'}</Text></View>
    </ScrollView>
  </ScheduleShell>
}

function previewOptions(): readonly ScheduleServiceOption[] {
  return fixtureManagedServices.map(service => ({ serviceId: service.serviceId,
    serviceName: service.serviceName || `服务 ${service.serviceId}`, fulfillmentType: service.fulfillmentType }))
}
function realRepository(): ScheduleDeps {
  return new RealScheduleRepository(consumerApi,
    () => consumerApi.scope.current?.merchantId || '',
    () => consumerApi.scope.current?.storeId || '')
}
