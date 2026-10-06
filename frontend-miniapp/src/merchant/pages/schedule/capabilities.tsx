import { Button, Input, ScrollView, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { loadServiceOptions, RealScheduleRepository, type ScheduleServiceOption } from '../../schedule/repository'
import { fixtureManagedServices } from '../../services/model'
import {
  capabilityProblems, isScheduleScenario, PreviewScheduleRepository, scheduleAvailability,
  scheduleClosedReason, scheduleMessage,
  type CapabilityView, type ScheduleDeps,
} from '../../schedule/model'
import { Chip, Field, ScheduleShell, useScheduleStyle, type PagePhase } from './parts'
import './page.css'

// M-002 员工服务能力维护（Contract 53号 §5 / SCHC-2）。设计源见 registry §4（无契约语义
// 兼容原稿，沿 M 端现行页面规范实现）。能力按具体服务项授权、全量替换、集合头版本 CAS：
// 过期提交 409 呈现并引导重读；撤销项必填原因；LEGACY_UNVERSIONED 头 503 隔离呈现为
// 「功能未开放（待盘点）」，绝不当作空集合。
export default function ScheduleCapabilitiesPage() {
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
  const [view, setView] = useState<CapabilityView | null>(null)
  const [selected, setSelected] = useState<readonly string[]>([])
  const [reason, setReason] = useState('')
  const [options, setOptions] = useState<readonly ScheduleServiceOption[]>([])
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
    if (!/^[1-9][0-9]{0,18}$/.test(target)) { setPhase('ready'); setView(null); setSelected([]); setNotice('请输入正确的员工编号后读取能力集合。'); return }
    setPhase('loading')
    try {
      const loaded = await repository.capabilities(target)
      if (!mounted.current || sequence !== loadSequence.current) return
      setView(loaded); setSelected(loaded.serviceIds)
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
  function toggle(serviceId: string) {
    setSelected(current => current.includes(serviceId)
      ? current.filter(item => item !== serviceId)
      : [...current, serviceId])
  }
  async function save() {
    if (busy || !view) return
    const problems = capabilityProblems(selected, view.serviceIds, reason)
    if (problems.length) { setNotice(`请检查：${problems.join('；')}`); return }
    setNotice(''); setBusy(true)
    try {
      const saved = await repository.replaceCapabilities(`schedule-capability:${staffId}`, staffId, selected, view.version, reason)
      setView(saved); setSelected(saved.serviceIds); setReason('')
      setNotice('能力集合已保存（全量替换生效）。')
    } catch (error) { setNotice(scheduleMessage(error)) } finally { if (mounted.current) setBusy(false) }
  }
  const removing = view !== null && view.serviceIds.some(serviceId => !selected.includes(serviceId))
  const serviceName = (serviceId: string) => options.find(option => option.serviceId === serviceId)?.serviceName || `服务 ${serviceId}`
  return <ScheduleShell title='员工服务能力' style={style} phase={phase} notice={notice} closedReason={closedReason}
    onRetry={() => void load(staffId)} onBack={() => void back()} preview={preview}>
    <ScrollView className='sch-body' scrollY enhanced showScrollbar={false}>
      <Field label='员工编号' required hint='能力按具体服务项授权（类目不自动继承）；员工档案在员工管理维护。'>
        <View className='sch-inline'>
          <Input className='sch-input sch-input-grow' value={staffId} type='number' placeholder='员工编号（如 958003）'
            onInput={event => setStaffId(event.detail.value)} />
          <Button id='sch-cap-load' className='sch-inline-action' disabled={busy} onClick={() => void load(staffId)}>读取</Button>
        </View>
      </Field>
      {phase === 'ready' && view && <View>
        <View className='sch-summary'>
          <Text>当前集合版本 {view.version} · 已授权 {view.serviceIds.length} 项</Text>
          {view.version === '0' && <Text className='sch-summary-hint'>该员工还没有能力集合，勾选服务后保存将创建集合头。</Text>}
        </View>
        <Field label='可授权服务' hint={options.length ? '勾选=授权该具体服务；全量替换保存。' : '服务列表暂不可用；可先到服务管理维护服务后返回选择。'}>
          {options.length > 0
            ? <View className='sch-chip-row'>
                {options.map(option => <Chip key={option.serviceId} selected={selected.includes(option.serviceId)}
                  onClick={() => toggle(option.serviceId)}>{option.serviceName}</Chip>)}
              </View>
            : <Text className='sch-hint'>无可选服务项。</Text>}
        </Field>
        <Field label={`操作原因${removing ? '（撤销服务项时必填）' : '（可选）'}`} hint={removing ? '撤销授权会减少人员可用性，须先通过已指派订单保护；原因必填（1-500字）。' : '撤销任一已授权服务项时必填。'}>
          <Textarea className='sch-textarea' value={reason} maxlength={500} placeholder={removing ? '如：该员工不再承接上门喂养' : '撤销服务项时填写原因'}
            onInput={event => setReason(event.detail.value)} />
        </Field>
        <Button id='sch-cap-save' className='sch-primary' disabled={busy} onClick={() => void save()}>保存能力集合</Button>
        <Text className='sch-hint'>保存为全量替换并推进集合头版本；他人已修改时保存会得到版本冲突提示，请重新读取后再操作。</Text>
      </View>}
      {phase === 'ready' && !view && <View className='sch-empty'><Text>输入员工编号并读取能力集合后可编辑。</Text></View>}
      {notice && <Text id='sch-cap-notice' className='sch-notice'>{notice}</Text>}
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
