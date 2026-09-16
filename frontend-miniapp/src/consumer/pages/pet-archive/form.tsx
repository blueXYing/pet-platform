import { ConsumerPageLayout } from '../../components/page-layout'
import { navigationUnavailableMessage, type ConsumerNavigationKey } from '../../components/navigation/model'
import { Button, Image, Input, Picker, Text, Textarea, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { integrationMessage } from '../../../shared/consumer-api'
import { realPetRepository } from '../../api/page-repository'
import {
  deriveAgeLabel, emptyDraft, formFixture, isPreviewScenario,
  PreviewPetRepository, previewRepository, previewSupplement, sameDraft, validateDraft, weightContractToInput, type PetDraft, type PetPhase, type PetSex,
} from '../../pet/model'
import stripMain from './assets/strip-main.png'
import stripFormBottom from './assets/strip-form-bottom.png'
import navBack from './assets/nav-back.png'
import navEdit from './assets/nav-edit.png'
import formChevron from './assets/form-chevron.png'
import iconDate from './assets/icon-date.png'
import './fonts.css'
import './form.css'

// Design states 95:1481 (brother) / 95:1844 (sister): identical fixture values, opposite sex selection.
function initialDraft(petId: string | undefined, scenario: string, loaded: PetDraft | null): PetDraft {
  if (loaded) return loaded
  if (scenario === 'form-brother') return { ...formFixture, sex: 'MALE' }
  if (scenario === 'form-sister') return { ...formFixture, sex: 'FEMALE' }
  return { ...emptyDraft }
}
export default function PetArchiveForm() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const referenceCanvas = preview && route.params.referenceCanvas === '1'
  const scenario = preview && isPreviewScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const petId = route.params.petId
  const { scope, revision, context } = useWorkspace(preview ? 'preview' : 'real')
  const [phase, setPhase] = useState<PetPhase>('loading')
  const [draft, setDraft] = useState<PetDraft>({ ...emptyDraft })
  const [baseline, setBaseline] = useState<PetDraft>({ ...emptyDraft })
  const [errors, setErrors] = useState<ReturnType<typeof validateDraft>>({})
  const [notice, setNotice] = useState('')
  const [noticeKind, setNoticeKind] = useState<'info' | 'error'>('info')
  const [designHeight, setDesignHeight] = useState(1066)
  const mounted = useRef(true)
  const sequence = useRef(0)
  const previousRevision = useRef(revision)
  const repository = useRef(preview ? previewRepository(scope, scenario) : realPetRepository())
  const savedPetId = useRef(petId || null)
  const saving = useRef(false)
  const request = useRef<{ id: string; draft: PetDraft } | null>(null)
  const [platformInfo, setPlatformInfo] = useState(() => Taro.getWindowInfo())
  const unit = referenceCanvas ? 1 : platformInfo.windowWidth / 402
  const style = { '--pet-status-top': `${referenceCanvas ? 0 : platformInfo.statusBarHeight || 0}px`, '--pet-unit': `${unit}px`, '--pet-canvas-width': referenceCanvas ? '402px' : '100vw' } as CSSProperties
  const today = todayISO()

  const load = useCallback(async () => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    setPhase('loading'); setNotice(''); setErrors({})
    if ((preview && scenario === 'expired') || !scope.current || scope.current.workspace !== 'consumer') { setPhase('expired'); return }
    try {
      const pet = petId ? await scope.run(undefined, () => repository.current.get(petId)) : null
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      let initial: PetDraft = { ...emptyDraft }
      let height = 1066
      if (petId) {
        if (!pet) { setPhase('load-error'); return }
        initial = { name: pet.name, breedName: pet.breedName || '', birthDate: pet.birthDate || '', sex: pet.sex, weightInput: weightContractToInput(pet.weightKg), healthNote: pet.healthNote || '' }
      } else {
        initial = initialDraft(petId, scenario, null)
        if (scenario === 'form-sister') height = 1067
      }
      const pending = !preview && consumerApi.pendingCommand(petId ? `pet:${petId}` : 'pet:create')
      if (pending && pending.data) {
        const p = pending.data
        initial = { name: String(p.name), breedName: String(p.breedName || ''), birthDate: String(p.birthDate || ''), sex: p.sex as PetSex, weightInput: weightContractToInput(p.weightKg as string | null), healthNote: String(p.healthNote || '') }
      }
      setDesignHeight(height)
      setDraft(initial); setBaseline(initial); request.current = null; setPhase('ready')
      if (pending) { setPhase('save-error'); setNotice('上次保存结果尚未确认，请重试原操作') }
    } catch {
      if (mounted.current && current === sequence.current && currentRevision === scope.revision) setPhase('load-error')
    }
  }, [preview, scenario, scope, petId])
  useEffect(() => {
    mounted.current = true
    void load()
    return () => { mounted.current = false; sequence.current++ }
  }, [load])
  useEffect(() => {
    if (previousRevision.current !== revision || !context || context.workspace !== 'consumer') {
      previousRevision.current = revision
      sequence.current++; request.current = null; saving.current = false
      savedPetId.current = petId || null
      setDraft({ ...emptyDraft }); setBaseline({ ...emptyDraft }); setNotice(''); setErrors({})
      repository.current = preview ? new PreviewPetRepository([]) : realPetRepository()
      setPhase('expired')
      if (!preview && context?.workspace === 'consumer') void load()
    }
  }, [revision, context, preview, load])
  useEffect(() => {
    const handler = () => setPlatformInfo(Taro.getWindowInfo())
    Taro.onWindowResize(handler)
    return () => Taro.offWindowResize(handler)
  }, [])
  function edit<K extends keyof PetDraft>(key: K, value: PetDraft[K]) {
    if (saving.current || (!preview && consumerApi.pendingCommand(savedPetId.current ? `pet:${savedPetId.current}` : 'pet:create'))) return
    setDraft(old => ({ ...old, [key]: value })); setNotice(''); setErrors({})
  }
  function pickSex(value: PetSex) {
    edit('sex', value)
  }
  async function save() {
    if (saving.current || !['ready', 'save-error'].includes(phase)) return
    const invalid = validateDraft(draft, today); setErrors(invalid)
    if (Object.keys(invalid).length) { setNoticeKind('error'); setNotice(Object.values(invalid)[0]); return }
    saving.current = true; setPhase('saving'); setNotice('')
    const currentRevision = scope.revision
    if (!request.current || !sameDraft(request.current.draft, draft)) {
      // Preview transaction identity only; real writes require the approved UUID provider.
      request.current = { id: `preview-${Date.now()}-${++sequence.current}`, draft: { ...draft } }
    }
    const pending = request.current
    try {
      const saved = await scope.run(undefined, () => repository.current.save(savedPetId.current, pending.draft, pending.id))
      if (!mounted.current || currentRevision !== scope.revision) return
      savedPetId.current = saved.petId
      const confirmed = preview ? pending.draft : { name: saved.name, breedName: saved.breedName || '', birthDate: saved.birthDate || '', sex: saved.sex, weightInput: weightContractToInput(saved.weightKg), healthNote: saved.healthNote || '' }
      setDraft(confirmed); setBaseline(confirmed); setPhase('ready'); setNoticeKind('info'); setNotice(preview ? '预览数据已更新' : '已保存'); request.current = null
    } catch (error) {
      if (mounted.current && currentRevision === scope.revision) { setPhase('save-error'); setNoticeKind('error'); setNotice(preview ? '保存失败，请重试；已填写内容保留' : integrationMessage(error)) }
    } finally { saving.current = false }
  }
  async function leave(tab?: ConsumerNavigationKey) {
    if (saving.current) return
    const currentRevision = scope.revision
    if (!sameDraft(draft, baseline)) {
      const result = await Taro.showModal({ title: '放弃修改？', content: '尚未保存的修改将不会保留。', confirmText: '放弃修改', cancelText: '继续编辑' })
      if (!result.confirm) return
    }
    if (!mounted.current || currentRevision !== scope.revision) return
    if (tab) { setNotice(navigationUnavailableMessage(tab)); return }
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/consumer/pages/shell/index' })
  }
  const editable = ['ready', 'saving', 'save-error'].includes(phase)
  const fieldsLocked = phase === 'saving' || (!preview && !!consumerApi.pendingCommand(savedPetId.current ? `pet:${savedPetId.current}` : 'pet:create'))
  return <ConsumerPageLayout page='petForm' unit={unit} navigation={{ idPrefix: 'pet', disabled: !editable || phase === 'saving', onSelect: key => leave(key), referencePlacement: referenceCanvas ? { top: designHeight === 1067 ? 991 : 990 } : undefined }} className={`pet-page pet-form-page${referenceCanvas ? ' pet-reference-canvas' : ''}`} style={style}>
    <View className='pet-status-area' />
    <View className='pet-design pet-form-design' data-phase={phase} style={{ height: `calc(var(--pet-unit) * ${designHeight})` }}>
      <Image className='pet-abs pet-form-strip1' src={stripMain} mode='scaleToFill' />
      <Image className='pet-abs pet-form-strip2' src={stripFormBottom} mode='scaleToFill' />
      <View className='pet-abs pet-form-header' />
      <View className='pet-abs pet-form-cardshadow' />
      <View className='pet-abs pet-form-card' />
      <View className='pet-abs pet-form-panel' />
      <Button id='pet-form-back' className='pet-abs pet-form-back' ariaLabel='返回' disabled={phase === 'saving'} onClick={() => void leave()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='pet-abs pet-form-navtitle'>宠物信息</Text>
      <Image className='pet-abs pet-form-navedit' src={navEdit} mode='scaleToFill' />
      {!editable && <View className='pet-state pet-form-state' role='status'>
        <Text>{phase === 'loading' ? '正在加载宠物档案…' : phase === 'expired' ? '登录已失效，请重新登录' : phase === 'unavailable' ? '宠物服务暂不可用，请稍后再试' : '加载失败，请重试'}</Text>
        {!preview && phase === 'expired' && <Button className='pet-state-action' onClick={() => Taro.redirectTo({ url: '/consumer/pages/shell/index' })}>去登录</Button>}
        {phase === 'load-error' && <Button id='pet-retry-load' className='pet-state-action' onClick={() => void load()}>重新加载</Button>}
      </View>}
      {editable && <>
        <Text className='pet-abs pet-form-title'>添加宠物信息</Text>
        <Image className='pet-abs pet-form-chevron' src={formChevron} mode='scaleToFill' />
        <View className='pet-abs pet-form-field pet-form-field-name'>
          <Text className='pet-form-label'>宠物名字</Text>
          <Input id='pet-form-name' className='pet-form-input' value={draft.name} maxlength={-1} disabled={fieldsLocked} ariaLabel='宠物名字' onInput={event => edit('name', event.detail.value)} adjustPosition />
        </View>
        <View className='pet-abs pet-form-field pet-form-field-breed'>
          <Text className='pet-form-label'>品种</Text>
          <Input id='pet-form-breed' className='pet-form-input' value={draft.breedName} maxlength={-1} disabled={fieldsLocked} ariaLabel='品种' onInput={event => edit('breedName', event.detail.value)} adjustPosition />
        </View>
        <View className='pet-abs pet-form-field pet-form-field-sex'>
          <Text className='pet-form-label'>性别</Text>
          <View className='pet-form-sexwrap'>
            {(['MALE', 'FEMALE'] as PetSex[]).map(sex => <Button key={sex} id={`pet-form-sex-${sex}`} className={`pet-form-sexbtn sex-${sex.toLowerCase()}${draft.sex === sex ? ' is-selected' : ''}`} ariaLabel={`${sex === 'MALE' ? '弟弟' : '妹妹'}${draft.sex === sex ? '，已选择' : ''}`} disabled={fieldsLocked} onClick={() => pickSex(sex)}>{sex === 'MALE' ? '弟弟' : '妹妹'}</Button>)}
          </View>
        </View>
        <View className='pet-abs pet-form-field pet-form-field-age'>
          <Text className='pet-form-label'>年龄</Text>
          <View className='pet-form-staticbox'>
            <Text className={`pet-form-staticvalue${deriveAgeLabel(draft.birthDate, today) ? '' : ' is-placeholder'}`}>{deriveAgeLabel(draft.birthDate, today) || '如：2岁'}</Text>
          </View>
        </View>
        <View className='pet-abs pet-form-field pet-form-field-birth'>
          <Text className='pet-form-label'>出生日期</Text>
          <Picker mode='date' end={today} value={draft.birthDate || today} disabled={fieldsLocked} onChange={event => edit('birthDate', event.detail.value)}>
            <View className='pet-form-pickerbox'>
              <Text className='pet-form-staticvalue'>{draft.birthDate}</Text>
              <Image className='pet-form-dateicon' src={iconDate} mode='scaleToFill' />
            </View>
          </Picker>
        </View>
        <View className='pet-abs pet-form-field pet-form-field-weight'>
          <Text className='pet-form-label'>体重</Text>
          <Input id='pet-form-weight' className='pet-form-input' value={draft.weightInput} maxlength={-1} disabled={fieldsLocked} ariaLabel='体重' onInput={event => edit('weightInput', event.detail.value)} adjustPosition />
        </View>
        <View className='pet-abs pet-form-field pet-form-field-chip'>
          <Text className='pet-form-label'>宠物芯片号</Text>
          <View className='pet-form-staticbox'>
            <Text className='pet-form-staticvalue'>{previewSupplement(petId || (scenario === 'form-brother' || scenario === 'form-sister' ? '30001' : undefined)).chipNumber || '—'}</Text>
          </View>
        </View>
        <View className='pet-abs pet-form-field pet-form-field-note'>
          <Text className='pet-form-label'>健康备注</Text>
          <Textarea id='pet-form-note' className='pet-form-textarea' value={draft.healthNote} maxlength={-1} disabled={fieldsLocked} ariaLabel='健康备注' onInput={event => edit('healthNote', event.detail.value)} adjustPosition showConfirmBar={false} />
        </View>
        <Button id='pet-form-cancel' className='pet-abs pet-form-cancel' disabled={phase === 'saving'} onClick={() => void leave()}><Text className='pet-form-cancel-label'>取消</Text></Button>
        <Button id='pet-form-save' className='pet-abs pet-form-save' disabled={phase === 'saving'} onClick={() => void save()}><Text className='pet-form-save-label'>{phase === 'saving' ? '保存中…' : '保存'}</Text></Button>
        {notice && <Text id='pet-notice' className={`pet-notice pet-form-notice${noticeKind === 'error' ? ' pet-form-notice-error' : ''}`}>{notice}</Text>}
      </>}
    </View>
  </ConsumerPageLayout>
}
function todayISO() {
  const now = new Date()
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  const day = `${now.getDate()}`.padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}
