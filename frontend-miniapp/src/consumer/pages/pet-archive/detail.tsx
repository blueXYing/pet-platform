import { ConsumerPageLayout } from '../../components/page-layout'
import { switchConsumerTab } from '../../components/navigation/switch'
import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useRouter, useDidShow } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { integrationMessage } from '../../../shared/consumer-api'
import { realPetRepository } from '../../api/page-repository'
import { deriveAgeLabel, formatWeightDisplay, isPreviewScenario, PreviewPetRepository, previewRepository, previewSupplement, sexLabel, breedAgeLine, type PetPhase, type PetView } from '../../pet/model'
import {
  dewormingDateLine, dewormingTypeLabels, vaccineDateLine, vaccineStatusLabel,
  decodeHealthRecordList, type HealthRecordView,
} from '../../pet/health-record'
import { HealthRecordFormSheet, type HealthRecordFormKind } from './record-form'
import stripMain from './assets/strip-main.png'
import stripBottom from './assets/strip-detail-bottom.png'
import panelBasic from './assets/panel-basic.png'
import panelVaccine from './assets/panel-vaccine.png'
import panelLower from './assets/panel-lower.png'
import tapeA from './assets/tape-a.png'
import tapeB from './assets/tape-b.png'
import petPhoto from './assets/pet-photo-detail.png'
import catPhoto from './assets/pet-photo-list-mimi.png'
import defaultCatAvatar from './assets/pet-form-default-cat.png'
import defaultDogAvatar from './assets/pet-form-default-dog.png'
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
  const [vaccineRecords, setVaccineRecords] = useState<HealthRecordView[]>([])
  const [dewormRecords, setDewormRecords] = useState<HealthRecordView[]>([])
  const [recordSheet, setRecordSheet] = useState<HealthRecordFormKind | null>(
    route.params.recordSheet === 'vaccine' ? 'VACCINATION' : route.params.recordSheet === 'deworm' ? 'DEWORMING' : null)
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
      // 契约 68 记录列表（真实态）：两路独立读取，单路失败不拖垮档案页（记录分区按空态呈现）。
      if (found && !preview) {
        try {
          const vaccine = await scope.run(undefined, () => realPetRepository().listHealthRecords(found.petId, 'vaccination-records'))
          if (mounted.current && current === sequence.current && currentRevision === scope.revision) setVaccineRecords(decodeHealthRecordList(vaccine))
        } catch { if (mounted.current && current === sequence.current) setVaccineRecords([]) }
        try {
          const deworm = await scope.run(undefined, () => realPetRepository().listHealthRecords(found.petId, 'deworming-records'))
          if (mounted.current && current === sequence.current && currentRevision === scope.revision) setDewormRecords(decodeHealthRecordList(deworm))
        } catch { if (mounted.current && current === sequence.current) setDewormRecords([]) }
      } else if (mounted.current && current === sequence.current && currentRevision === scope.revision) {
        setVaccineRecords([]); setDewormRecords([])
      }
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
    else await Taro.switchTab({ url: '/consumer/pages/mine/index' })
  }
  function editPet() {
    if (!pet) return
    Taro.navigateTo({ url: `/consumer/pages/pet-archive/form?preview=${preview ? '1' : '0'}&scenario=${scenario}&petId=${encodeURIComponent(pet.petId)}` }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  // 品种百科互跳（契约 62 / PRD 5.1.4 核心功能 4：从宠物档案进入对应品种的养护指南）：
  // 品种值沿用原稿一比一画布定位（Text→Button 同类同位，视觉零变化），带品种名关键词进百科列表。
  function openBaike() {
    if (!pet || !pet.breedName) return
    Taro.navigateTo({ url: `/consumer/pages/baike/index?keyword=${encodeURIComponent(pet.breedName)}` })
      .catch(() => setNotice('页面跳转失败，请重试'))
  }
  // 契约 68：添加按钮在原档案页打开底部半框（96:2580/96:3006），不再 navigateTo 三级页。
  function addVaccineRecord() {
    if (!pet) return
    setRecordSheet('VACCINATION')
  }
  function addDewormRecord() {
    if (!pet) return
    setRecordSheet('DEWORMING')
  }
  const ready = phase === 'ready' && pet
  const supplement = previewSupplement(preview && ready ? pet.petId : undefined)
  // 记录分区数据源：preview 走设计样例（无契约时代登记的视觉样例）；真实态走契约 68 读侧，
  // 行状态胶囊按 nextRecordDate 派生（68号 §字段：设计稿"接种状态"chips = 展示态，不入库）。
  const vaccineRecordLines = preview
    ? supplement.vaccineRecords
    : vaccineRecords.map(record => ({
        name: record.vaccineName || '', date: vaccineDateLine(record),
        status: vaccineStatusLabel(record, today),
      }))
  const dewormRecordLines = preview
    ? supplement.dewormRecords
    : dewormRecords.map(record => ({
        name: dewormingTypeLabels[record.dewormingType || 'EXTERNAL'],
        brand: record.productName || '', date: dewormingDateLine(record),
      }))
  const tag = supplement.tag
  const defaultPhoto = ready && pet ? (pet.petType === 'DOG' ? defaultDogAvatar : pet.petType === 'CAT' ? defaultCatAvatar : null) : null
  const photo = ready && pet ? pet.avatarUrl || (preview ? (pet.petId === '30001' ? petPhoto : pet.petId === '30002' ? catPhoto : null) : defaultPhoto) : null
  return <ConsumerPageLayout page='petDetail' unit={unit} navigation={preview ? { idPrefix: 'pet', disabled: phase !== 'ready', onSelect: key => void switchConsumerTab(key), referencePlacement: referenceCanvas ? { bottom: 0, height: 61 } : undefined } : undefined} className={`pet-page pet-detail-page${referenceCanvas ? ' pet-reference-canvas' : ''}`} style={style}>
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
        {!preview && phase === 'expired' && <Button className='pet-state-action' onClick={() => Taro.switchTab({ url: '/consumer/pages/mine/index' })}>去登录</Button>}
        {phase === 'load-error' && <Button id='pet-retry-load' className='pet-state-action' onClick={() => void load()}>重新加载</Button>}
      </View>}
      {ready && <>
        <View className='pet-abs pet-detail-identity' />
        {photo ? <Image className='pet-abs pet-detail-photo' src={photo} mode={(pet.avatarUrl || (!preview && defaultPhoto)) ? 'aspectFill' : 'aspectFit'} /> : <View className='pet-abs pet-detail-photo pet-avatar-empty'><Text>{pet.name.slice(0, 1)}</Text></View>}
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
        <Button id='pet-detail-value-breed' className='pet-abs pet-detail-value pet-detail-value-breed pet-detail-breed-link' ariaLabel='查看品种百科' onClick={openBaike}>{pet.breedName || ''}</Button>
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
        <Button id='pet-add-vaccine' className='pet-abs pet-detail-add pet-detail-add-vaccine' ariaLabel='添加疫苗记录' onClick={addVaccineRecord}>
          <Image className='pet-detail-add-icon' src={iconAdd} mode='scaleToFill' />
          <Text className='pet-detail-add-label'>添加</Text>
        </Button>
        <View className='pet-abs pet-detail-line pet-detail-line-r1' />
        <View className='pet-abs pet-detail-line pet-detail-line-r2' />
        <View className='pet-abs pet-detail-line pet-detail-line-r3' />
        <View className='pet-abs pet-detail-line pet-detail-line-r4' />
        {!vaccineRecordLines.length && <Text className='pet-abs pet-detail-record-empty pet-detail-vaccine-empty'>暂无记录</Text>}
        {vaccineRecordLines.map((record, index) => {
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
        <Button id='pet-add-deworm' className='pet-abs pet-detail-add pet-detail-add-deworm' ariaLabel='添加驱虫记录' onClick={addDewormRecord}>
          <Image className='pet-detail-add-icon' src={iconAdd} mode='scaleToFill' />
          <Text className='pet-detail-add-label'>添加</Text>
        </Button>
        <View className='pet-abs pet-detail-line pet-detail-line-d1' />
        <View className='pet-abs pet-detail-line pet-detail-line-d2' />
        <View className='pet-abs pet-detail-line pet-detail-line-d3' />
        {!dewormRecordLines.length && <Text className='pet-abs pet-detail-record-empty pet-detail-deworm-empty'>暂无记录</Text>}
        {dewormRecordLines.map((record, index) => {
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
    {phase === 'ready' && pet && recordSheet && <HealthRecordFormSheet kind={recordSheet} petId={pet.petId} preview={preview}
      onClose={() => setRecordSheet(null)} onSaved={() => { setRecordSheet(null); void load() }} />}
  </ConsumerPageLayout>
}
function todayISO() {
  const now = new Date()
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  const day = `${now.getDate()}`.padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}
