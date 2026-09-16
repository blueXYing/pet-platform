import { ConsumerPageLayout } from '../../components/page-layout'
import { navigationUnavailableMessage } from '../../components/navigation/model'
import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useRouter, useDidShow } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { integrationMessage } from '../../../shared/consumer-api'
import { realPetRepository } from '../../api/page-repository'
import { deriveAgeLabel, formatWeightDisplay, isPreviewScenario, PreviewPetRepository, previewRepository, previewSupplement, sexLabel, breedAgeLine, type PetPhase, type PetView } from '../../pet/model'
import stripMain from './assets/strip-main.png'
import stripBottom from './assets/strip-detail-bottom.png'
import panelBasic from './assets/panel-basic.png'
import panelVaccine from './assets/panel-vaccine.png'
import panelLower from './assets/panel-lower.png'
import tapeA from './assets/tape-a.png'
import tapeB from './assets/tape-b.png'
import petPhoto from './assets/pet-photo-detail.png'
import catPhoto from './assets/pet-photo-list-mimi.png'
import navBack from './assets/nav-back.png'
import navEdit from './assets/nav-edit.png'
import tagIconVaccine from './assets/tag-icon-vaccine-blue.png'
import tagIconDeworm from './assets/tag-icon-deworm.png'
import iconCalendar from './assets/icon-calendar.png'
import iconWeight from './assets/icon-weight.png'
import iconAdd from './assets/icon-add.png'
import recordVaccine from './assets/record-icon-vaccine.png'
import recordDeworm from './assets/record-icon-deworm.png'
import './fonts.css'
import './detail.css'

export default function PetArchiveDetail() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const referenceCanvas = preview && route.params.referenceCanvas === '1'
  const scenario = preview && isPreviewScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const petId = route.params.petId || (preview ? '30001' : '')
  const { scope, revision, context } = useWorkspace(preview ? 'preview' : 'real')
  const [phase, setPhase] = useState<PetPhase>('loading')
  const [pet, setPet] = useState<PetView | null>(null)
  const [notice, setNotice] = useState('')
  const mounted = useRef(true)
  const sequence = useRef(0)
  const previousRevision = useRef(revision)
  const repository = useRef(preview ? previewRepository(scope, scenario) : realPetRepository())
  const [platformInfo, setPlatformInfo] = useState(() => Taro.getWindowInfo())
  const unit = referenceCanvas ? 1 : platformInfo.windowWidth / 402
  const style = { '--pet-status-top': `${referenceCanvas ? 0 : platformInfo.statusBarHeight || 0}px`, '--pet-unit': `${unit}px`, '--pet-canvas-width': referenceCanvas ? '402px' : '100vw' } as CSSProperties
  const today = todayISO()

  const load = useCallback(async () => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    setPhase('loading'); setNotice('')
    if ((preview && scenario === 'expired') || !scope.current || scope.current.workspace !== 'consumer') { setPhase('expired'); return }
    try {
      const found = await scope.run(undefined, () => repository.current.get(petId))
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setPet(found); setPhase(found ? 'ready' : 'load-error')
      if (!found) setNotice('没有找到这只宠物')
    } catch {
      if (mounted.current && current === sequence.current && currentRevision === scope.revision) setPhase('load-error')
    }
  }, [preview, scenario, scope, petId])
  useDidShow(() => { void load() })
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; sequence.current++ }
  }, [load])
  useEffect(() => {
    if (previousRevision.current !== revision || !context || context.workspace !== 'consumer') {
      previousRevision.current = revision
      sequence.current++
      setPet(null); setNotice('')
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
  async function leave() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/consumer/pages/shell/index' })
  }
  function editPet() {
    if (!pet) return
    Taro.navigateTo({ url: `/consumer/pages/pet-archive/form?preview=${preview ? '1' : '0'}&scenario=${scenario}&petId=${encodeURIComponent(pet.petId)}` }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  function notWired(label: string) {
    setNotice(preview ? `“${label}”页面尚未接入本次预览` : `“${label}”功能尚未接通`)
  }
  const ready = phase === 'ready' && pet
  const supplement = previewSupplement(preview && ready ? pet.petId : undefined)
  const tag = supplement.tag
  const photo = ready ? pet.avatarUrl || (preview ? (pet.petId === '30001' ? petPhoto : pet.petId === '30002' ? catPhoto : null) : null) : null
  return <ConsumerPageLayout page='petDetail' unit={unit} navigation={{ idPrefix: 'pet', disabled: phase !== 'ready', onSelect: key => { setNotice(navigationUnavailableMessage(key)) }, referencePlacement: referenceCanvas ? { bottom: 0, height: 61 } : undefined }} className={`pet-page pet-detail-page${referenceCanvas ? ' pet-reference-canvas' : ''}`} style={style}>
    <View className='pet-status-area' />
    <View className='pet-design pet-detail-design' data-phase={phase}>
      <Image className='pet-abs pet-detail-strip1' src={stripMain} mode='scaleToFill' />
      <Image className='pet-abs pet-detail-strip2' src={stripBottom} mode='scaleToFill' />
      <View className='pet-abs pet-detail-bottombar' />
      <View className='pet-abs pet-detail-header' />
      <Image className='pet-abs pet-detail-panel-vaccine' src={panelVaccine} mode='scaleToFill' />
      <Image className='pet-abs pet-detail-panel-lower' src={panelLower} mode='scaleToFill' />
      <Image className='pet-abs pet-detail-panel-basic' src={panelBasic} mode='scaleToFill' />
        <Button id='pet-detail-back' className='pet-abs pet-detail-back' ariaLabel='返回' onClick={() => void leave()}><Image src={navBack} mode='scaleToFill' /></Button>
        <Text className='pet-abs pet-detail-navtitle'>宠物信息</Text>
        <Button id='pet-detail-edit' className='pet-abs pet-detail-edit' ariaLabel='编辑宠物信息' disabled={!ready} onClick={editPet}><Image src={navEdit} mode='scaleToFill' /></Button>
      {!ready && <View className='pet-state pet-detail-state' role='status'>
        <Text>{notice || (phase === 'loading' ? '正在加载宠物档案…' : phase === 'expired' ? '登录已失效，请重新登录' : phase === 'unavailable' ? '宠物服务暂不可用，请稍后再试' : '加载失败，请重试')}</Text>
        {!preview && phase === 'expired' && <Button className='pet-state-action' onClick={() => Taro.redirectTo({ url: '/consumer/pages/shell/index' })}>去登录</Button>}
        {phase === 'load-error' && <Button id='pet-retry-load' className='pet-state-action' onClick={() => void load()}>重新加载</Button>}
      </View>}
      {ready && <>
        <View className='pet-abs pet-detail-identity' />
        {photo ? <Image className='pet-abs pet-detail-photo' src={photo} mode={pet.avatarUrl ? 'aspectFill' : 'aspectFit'} /> : <View className='pet-abs pet-detail-photo pet-avatar-empty'><Text>{pet.name.slice(0, 1)}</Text></View>}
        <View className='pet-abs pet-detail-identity-line'><Text className='pet-detail-name'>{pet.name}</Text>
        {sexLabel(pet.sex) && <View className={`pet-detail-sexpill${pet.sex === 'FEMALE' ? ' pet-detail-sexpill-female' : ''}`}><Text>{sexLabel(pet.sex)}</Text></View>}</View>
        <Text className='pet-abs pet-detail-breed'>{breedAgeLine(pet, today)}</Text>
        {tag && <View className='pet-abs pet-detail-tagpill'>
          <Image className='pet-detail-tagicon' src={tag.pill === 'vaccine' ? tagIconVaccine : tagIconDeworm} mode='scaleToFill' />
          <Text className='pet-detail-tagname'>{tag.name}</Text>
        </View>}
        {tag && <Text className='pet-abs pet-detail-tagdate'>{tag.date}</Text>}
        <Image className='pet-abs pet-detail-icon-calendar' src={iconCalendar} mode='scaleToFill' />
        <Text className='pet-abs pet-detail-birth'>{pet.birthDate || ''}</Text>
        <Image className='pet-abs pet-detail-icon-weight' src={iconWeight} mode='scaleToFill' />
        <Text className='pet-abs pet-detail-weight'>{formatWeightDisplay(pet.weightKg)}</Text>
        <Text className='pet-abs pet-detail-basic-title'>基本信息</Text>
        <View className='pet-abs pet-detail-line pet-detail-line-h1' />
        <View className='pet-abs pet-detail-line pet-detail-line-h2' />
        <View className='pet-abs pet-detail-line pet-detail-line-h3' />
        <View className='pet-abs pet-detail-line pet-detail-line-v' />
        <Text className='pet-abs pet-detail-label pet-detail-label-breed'>品种</Text>
        <Text className='pet-abs pet-detail-value pet-detail-value-breed'>{pet.breedName || ''}</Text>
        <Text className='pet-abs pet-detail-label pet-detail-label-sex'>性别</Text>
        <Text className='pet-abs pet-detail-value pet-detail-value-sex'>{sexLabel(pet.sex)}</Text>
        <Text className='pet-abs pet-detail-label pet-detail-label-birth'>出生日期</Text>
        <Text className='pet-abs pet-detail-value pet-detail-value-birth'>{pet.birthDate || ''}</Text>
        <Text className='pet-abs pet-detail-label pet-detail-label-weight'>体重</Text>
        <Text className='pet-abs pet-detail-value pet-detail-value-weight'>{formatWeightDisplay(pet.weightKg)}</Text>
        <Text className='pet-abs pet-detail-label pet-detail-label-chip'>宠物芯片</Text>
        <Text className='pet-abs pet-detail-value pet-detail-value-chip'>{supplement.chipNumber || '—'}</Text>
        <Text className='pet-abs pet-detail-label pet-detail-label-age'>年龄</Text>
        <Text className='pet-abs pet-detail-value pet-detail-value-age'>{deriveAgeLabel(pet.birthDate, today)}</Text>
        <Image className='pet-abs pet-detail-tape-a' src={tapeA} mode='scaleToFill' />
        <Text className='pet-abs pet-detail-section pet-detail-section-vaccine'>疫苗记录</Text>
        <Button id='pet-add-vaccine' className='pet-abs pet-detail-add pet-detail-add-vaccine' ariaLabel='添加疫苗记录' onClick={() => notWired('疫苗记录添加')}>
          <Image className='pet-detail-add-icon' src={iconAdd} mode='scaleToFill' />
          <Text className='pet-detail-add-label'>添加</Text>
        </Button>
        <View className='pet-abs pet-detail-line pet-detail-line-r1' />
        <View className='pet-abs pet-detail-line pet-detail-line-r2' />
        <View className='pet-abs pet-detail-line pet-detail-line-r3' />
        <View className='pet-abs pet-detail-line pet-detail-line-r4' />
        {!supplement.vaccineRecords.length && <Text className='pet-abs pet-detail-record-empty pet-detail-vaccine-empty'>{preview ? '暂无记录' : '记录功能尚未接通'}</Text>}
        {supplement.vaccineRecords.map((record, index) => {
          const circle = [538, 614, 697.5][index]
          const expiring = record.status === '即将到期'
          return <View key={record.name} className={`pet-detail-record pet-detail-record-${index}`} style={{ top: `calc(var(--pet-unit) * ${circle})` }}>
            <Image className='pet-detail-record-icon' src={recordVaccine} mode='scaleToFill' />
            <Text className='pet-detail-record-name'>{record.name}</Text>
            <Text className='pet-detail-record-date'>{record.date}</Text>
            <View className={`pet-detail-record-pill${expiring ? ' pet-detail-record-pill-expiring' : ''}`}>
              <Text>{record.status}</Text>
            </View>
          </View>
        })}
        <Image className='pet-abs pet-detail-tape-b' src={tapeB} mode='scaleToFill' />
        <Text className='pet-abs pet-detail-section pet-detail-section-deworm'>驱虫记录</Text>
        <Button id='pet-add-deworm' className='pet-abs pet-detail-add pet-detail-add-deworm' ariaLabel='添加驱虫记录' onClick={() => notWired('驱虫记录添加')}>
          <Image className='pet-detail-add-icon' src={iconAdd} mode='scaleToFill' />
          <Text className='pet-detail-add-label'>添加</Text>
        </Button>
        <View className='pet-abs pet-detail-line pet-detail-line-d1' />
        <View className='pet-abs pet-detail-line pet-detail-line-d2' />
        <View className='pet-abs pet-detail-line pet-detail-line-d3' />
        {!supplement.dewormRecords.length && <Text className='pet-abs pet-detail-record-empty pet-detail-deworm-empty'>{preview ? '暂无记录' : '记录功能尚未接通'}</Text>}
        {supplement.dewormRecords.map((record, index) => {
          const base = [873, 949][index]
          return <View key={record.name} className='pet-detail-record' style={{ top: `calc(var(--pet-unit) * ${base})` }}>
            <Image className='pet-detail-record-icon' src={recordDeworm} mode='scaleToFill' />
            <View className='pet-detail-record-titlerow'>
              <Text className='pet-detail-record-name'>{record.name}</Text>
              <Text className='pet-detail-record-brand'>{record.brand}</Text>
            </View>
            <Text className='pet-detail-record-date'>{record.date}</Text>
          </View>
        })}
        <View className='pet-detail-health-panel'>
          <Text className='pet-detail-section'>健康备注</Text>
          <Text className='pet-detail-health'>{pet.healthNote || '—'}</Text>
        </View>
      </>}
      {notice && ready && <Text id='pet-notice' className='pet-notice pet-detail-notice'>{notice}</Text>}
    </View>
  </ConsumerPageLayout>
}
function todayISO() {
  const now = new Date()
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  const day = `${now.getDate()}`.padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}
