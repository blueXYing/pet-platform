import { Button, Input, ScrollView, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealScheduleRepository } from '../../schedule/repository'
import {
  beijingToday, emptyStaffWindowForm, isScheduleScenario, PreviewScheduleRepository,
  reasonProblem, scheduleAvailability, scheduleClosedReason, scheduleMessage,
  staffStatusTagClass, staffWindowFormFromItem, staffWindowFormProblems, staffWindowStatusText,
  type ScheduleDeps, type StaffWindowFormInput, type StaffWindowItem,
} from '../../schedule/model'
import { Field, IntervalFields, intervalText, ScheduleShell, useScheduleStyle, type PagePhase } from './parts'
import './page.css'

// M-002 员工排班维护（Contract 53号 §4）。设计源见 registry §4（无契约语义兼容原稿，沿
// M 端现行页面规范实现）。排班无删除语义：关闭保留历史、可再次开放。缩短/移动可用性受
// 当前指派与全店可行性复核保护（409 呈现），相邻半开时段可衔接（重叠 409 呈现）。
export default function ScheduleStaffPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isScheduleScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const staffParam = route.params.staffId || ''
  const { scope, revision, context } = useWorkspace(preview ? 'preview' : 'real')
  const [repository] = useState<ScheduleDeps>(() => preview ? new PreviewScheduleRepository(undefined, undefined, undefined, scenario) : realRepository())
  const style = useScheduleStyle()
  const [phase, setPhase] = useState<PagePhase>('idle')
  const [closedReason, setClosedReason] = useState('')
  const [notice, setNotice] = useState('')
  const [staffId, setStaffId] = useState(staffParam)
  const [windows, setWindows] = useState<readonly StaffWindowItem[]>([])
  const [form, setForm] = useState<StaffWindowFormInput>(() => emptyStaffWindowForm(beijingToday(), '09:00'))
  const [editing, setEditing] = useState<StaffWindowItem | null>(null)
  const [closeTarget, setCloseTarget] = useState<StaffWindowItem | null>(null)
  const [closeReason, setCloseReason] = useState('')
  const [busy, setBusy] = useState(false)
  const mounted = useRef(true)
  const loadSequence = useRef(0)

  const load = useCallback(async (target: string) => {
    const sequence = ++loadSequence.current
    setNotice('')
    if (!preview) {
      const current = scope.current
      if (!current || current.workspace !== 'merchant' || !current.merchantId || !current.storeId) { setPhase('entry'); return }
    }
    if (!/^[1-9][0-9]{0,18}$/.test(target)) { setPhase('ready'); setWindows([]); setNotice('请输入正确的员工编号后查询排班。'); return }
    setPhase('loading')
    try {
      const page = await repository.staffWindows(target)
      if (!mounted.current || sequence !== loadSequence.current) return
      setWindows(page.items)
      setPhase('ready')
    } catch (error) {
      if (!mounted.current || sequence !== loadSequence.current) return
      if (scheduleAvailability(error) === 'closed') { setClosedReason(scheduleClosedReason(error)); setPhase('closed'); return }
      setNotice(scheduleMessage(error)); setPhase('load-error')
    }
  }, [preview, repository, scope])
  useDidShow(() => { if (staffId) void load(staffId); else setPhase('ready') })
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
  async function submitCreate() {
    if (busy) return
    const problems = staffWindowFormProblems(form)
    if (problems.length) { setNotice(`请检查：${problems.join('；')}`); return }
    setNotice(''); setBusy(true)
    try {
      await repository.createStaffWindow(`schedule-staff:${staffId}:new`, staffId, form)
      await load(staffId)
      setNotice('排班已新增。')
    } catch (error) { setNotice(scheduleMessage(error)) } finally { if (mounted.current) setBusy(false) }
  }
  async function submitEdit() {
    if (busy || !editing) return
    const problems = staffWindowFormProblems(form)
    if (problems.length) { setNotice(`请检查：${problems.join('；')}`); return }
    setNotice(''); setBusy(true)
    try {
      await repository.updateStaffWindow(`schedule-staff:${staffId}:${editing.windowId}:update`, staffId, editing.windowId, editing.version, form)
      setEditing(null)
      await load(staffId)
      setNotice('排班已保存；缩短可用性将受已指派订单保护。')
    } catch (error) { setNotice(scheduleMessage(error)) } finally { if (mounted.current) setBusy(false) }
  }
  async function submitClose() {
    if (busy || !closeTarget) return
    const problem = reasonProblem(closeReason, true)
    if (problem) { setNotice(problem); return }
    setNotice(''); setBusy(true)
    try {
      await repository.closeStaffWindow(`schedule-staff:${staffId}:${closeTarget.windowId}:close`, staffId, closeTarget.windowId, closeTarget.version, closeReason)
      setCloseTarget(null); setCloseReason('')
      await load(staffId)
      setNotice('排班已关闭；减少可用性受已指派订单保护，关闭保留历史。')
    } catch (error) { setNotice(scheduleMessage(error)) } finally { if (mounted.current) setBusy(false) }
  }
  async function reopen(item: StaffWindowItem) {
    if (busy) return
    setNotice(''); setBusy(true)
    try {
      await repository.openStaffWindow(`schedule-staff:${staffId}:${item.windowId}:open`, staffId, item.windowId, item.version)
      await load(staffId)
      setNotice('排班已开放。')
    } catch (error) { setNotice(scheduleMessage(error)) } finally { if (mounted.current) setBusy(false) }
  }

  return <ScheduleShell title='员工排班' style={style} phase={phase} notice={notice} closedReason={closedReason}
    onRetry={() => void load(staffId)} onBack={() => void back()} preview={preview}>
    <ScrollView className='sch-body' scrollY enhanced showScrollbar={false}>
      <Field label='员工编号' required hint='员工档案在员工管理维护；此处按员工编号维护其排班。'>
        <View className='sch-inline'>
          <Input className='sch-input sch-input-grow' value={staffId} type='number' placeholder='员工编号（如 958003）'
            onInput={event => setStaffId(event.detail.value)} />
          <Button id='sch-staff-load' className='sch-inline-action' disabled={busy} onClick={() => void load(staffId)}>查询</Button>
        </View>
      </Field>
      {phase === 'ready' && <View>
        <View className='sch-count-row'>
          <Text className='sch-count'>{staffId ? `员工 ${staffId} 共 ${windows.length} 段排班` : '未选择员工'}</Text>
          <Button id='sch-staff-create-toggle' className='sch-count-action'
            disabled={!staffId}
            onClick={() => { setForm(emptyStaffWindowForm(beijingToday(), '09:00')); setEditing(null); setCloseTarget(null); setNotice('') }}>
            清空新增表单
          </Button>
        </View>
        <View className='sch-form'>
          <Text className='sch-form-title'>{editing ? '编辑排班' : '新增排班'}</Text>
          <IntervalFields startDate={form.startDate} startTime={form.startTime} endDate={form.endDate} endTime={form.endTime}
            onChange={next => setForm(current => ({ ...current, ...next }))} />
          <View className='sch-action-row'>
            <Button id='sch-staff-submit' className='sch-primary' disabled={busy || !staffId}
              onClick={() => void (editing ? submitEdit() : submitCreate())}>{editing ? '保存修改' : '新增排班'}</Button>
            {editing && <Button className='sch-secondary' disabled={busy} onClick={() => setEditing(null)}>取消编辑</Button>}
          </View>
          <Text className='sch-hint'>同一员工的可用排班不重叠，相邻可衔接；重叠提交会得到 409 提示。排班无删除：用「关闭」保留历史。</Text>
        </View>
        {closeTarget && <View className='sch-form'>
          <Text className='sch-form-title'>关闭排班</Text>
          <Text className='sch-banner-text'>{intervalText(closeTarget.startAt, closeTarget.endAt)}；关闭保留历史，可再次开放。</Text>
          <Field label='操作原因' required hint='减少人员可用性必填原因（1-500字），记入操作审计。'>
            <Textarea className='sch-textarea' value={closeReason} maxlength={500} placeholder='如：员工当日请假'
              onInput={event => setCloseReason(event.detail.value)} />
          </Field>
          <View className='sch-action-row'>
            <Button id='sch-staff-close-confirm' className='sch-primary' disabled={busy} onClick={() => void submitClose()}>确认关闭</Button>
            <Button className='sch-secondary' disabled={busy} onClick={() => { setCloseTarget(null); setCloseReason('') }}>取消</Button>
          </View>
        </View>}
        {staffId && windows.length === 0 && <View className='sch-empty'><Text>该员工暂无排班，用上方表单新增第一段。</Text></View>}
        {windows.map(item => <View key={item.windowId} className='sch-card'>
          <View className='sch-card-head'>
            <Text className='sch-card-name'>{intervalText(item.startAt, item.endAt)}</Text>
            <Text className={staffStatusTagClass(item.status)}>{staffWindowStatusText[item.status]}</Text>
          </View>
          {item.status === 'CLOSED'
            ? <View className='sch-action-row'>
                <Button id={`sch-staff-open-${item.windowId}`} className='sch-secondary' disabled={busy} onClick={() => void reopen(item)}>开放</Button>
              </View>
            : <View className='sch-action-row'>
                <Button id={`sch-staff-edit-${item.windowId}`} className='sch-secondary' disabled={busy}
                  onClick={() => { setEditing(item); setForm(staffWindowFormFromItem(item)); setCloseTarget(null); setNotice('') }}>编辑</Button>
                <Button id={`sch-staff-close-${item.windowId}`} className='sch-secondary' disabled={busy}
                  onClick={() => { setEditing(null); setCloseTarget(item); setCloseReason('') }}>关闭</Button>
              </View>}
        </View>)}
      </View>}
      {notice && <Text id='sch-staff-notice' className='sch-notice'>{notice}</Text>}
      <View className='sch-tail'><Text>页面数据：{preview ? '契约 Mock（preview=1，不联调）' : '真实接口（开关未开放时失败关闭）'}</Text></View>
    </ScrollView>
  </ScheduleShell>
}

function realRepository(): ScheduleDeps {
  return new RealScheduleRepository(consumerApi,
    () => consumerApi.scope.current?.merchantId || '',
    () => consumerApi.scope.current?.storeId || '')
}
