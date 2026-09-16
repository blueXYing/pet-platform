import { ConsumerPageLayout } from '../../components/page-layout'
import { navigationUnavailableMessage } from '../../components/navigation/model'
import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useRouter, useDidShow } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { isPreviewScenario, PreviewPetRepository, previewRepository, previewSupplement, breedAgeLine, sexLabel, type PetPhase, type PetView } from '../../pet/model'
import stripMain from './assets/strip-main.png'
import backing from './assets/panel-list-backing.png'
import sheet from './assets/panel-list-sheet.png'
import tape from './assets/tape-a.png'
import paw from './assets/entry-paw.png'
import chevron from './assets/entry-chevron.png'
import petDoudou from './assets/pet-photo-list-doudou.png'
import petMimi from './assets/pet-photo-list-mimi.png'
import tagVaccine from './assets/tag-icon-vaccine.png'
import tagDeworm from './assets/tag-icon-deworm.png'
import './fonts.css'
import './list.css'

// Source cutouts are fallbacks for the preview pets; avatarUrl takes precedence.
const petPhotos: Record<string, { src: string; x: number; y: number; w: number; h: number }> = {
  '30001': { src: petDoudou, x: 7, y: 12, w: 55, h: 46 },
  '30002': { src: petMimi, x: 13, y: 12, w: 45, h: 47 },
}
// Node 78:2817 is the home frame; this page implements only its pet-archive module region (y 282..594).
export default function PetArchiveList() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const referenceCanvas = preview && route.params.referenceCanvas === '1'
  const scenario = isPreviewScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope, revision, context } = useWorkspace()
  const [phase, setPhase] = useState<PetPhase>(preview ? 'loading' : 'unavailable')
  const [pets, setPets] = useState<PetView[]>([])
  const [notice, setNotice] = useState('')
  const mounted = useRef(true)
  const sequence = useRef(0)
  const previousRevision = useRef(revision)
  const [platformInfo, setPlatformInfo] = useState(() => Taro.getWindowInfo())
  const unit = referenceCanvas ? 1 : platformInfo.windowWidth / 402
  const style = { '--pet-status-top': `${referenceCanvas ? 0 : platformInfo.statusBarHeight || 0}px`, '--pet-unit': `${unit}px`, '--pet-canvas-width': referenceCanvas ? '402px' : '100vw' } as CSSProperties

  const load = useCallback(async () => {
    if (!preview) return
    const currentRevision = scope.revision
    const current = ++sequence.current
    setPhase('loading'); setNotice('')
    if (scenario === 'expired' || !scope.current || scope.current.workspace !== 'consumer') { setPhase('expired'); return }
    try {
      const value = await scope.run(undefined, () => repository.current.load())
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setPets(value); setPhase('ready')
    } catch {
      if (mounted.current && current === sequence.current && currentRevision === scope.revision) setPhase('load-error')
    }
  }, [preview, scenario, scope])
  const repository = useRef(previewRepository(scope, scenario))
  useDidShow(() => { void load() })
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; sequence.current++ }
  }, [load])
  useEffect(() => {
    if (previousRevision.current !== revision || !context || context.workspace !== 'consumer') {
      previousRevision.current = revision
      sequence.current++
      setPets([]); setNotice('')
      repository.current = new PreviewPetRepository([])
      setPhase('expired')
    }
  }, [revision, context])
  useEffect(() => {
    const handler = () => setPlatformInfo(Taro.getWindowInfo())
    Taro.onWindowResize(handler)
    return () => Taro.offWindowResize(handler)
  }, [])
  function requirePreview() {
    if (!preview) { setNotice('宠物服务暂不可用，请稍后再试'); return false }
    return true
  }
  function openDetail(pet: PetView) {
    if (!requirePreview()) return
    Taro.navigateTo({ url: `/consumer/pages/pet-archive/detail?preview=1&scenario=${scenario}&petId=${encodeURIComponent(pet.petId)}` }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  function openForm() {
    if (!requirePreview()) return
    Taro.navigateTo({ url: `/consumer/pages/pet-archive/form?preview=1&scenario=${scenario}` }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  async function removePet(pet: PetView) {
    if (!requirePreview() || phase !== 'ready') return
    const result = await Taro.showModal({ title: '删除宠物档案？', content: `确定删除“${pet.name}”吗？删除后不可恢复。`, confirmText: '删除', cancelText: '取消' })
    if (!result.confirm || !mounted.current) return
    const currentRevision = scope.revision
    try {
      await scope.run(undefined, () => repository.current.remove(pet.petId, `preview-delete-${Date.now()}`))
      if (!mounted.current || currentRevision !== scope.revision) return
      setNotice('预览数据已更新')
      await load()
    } catch { if (mounted.current && currentRevision === scope.revision) setNotice('删除失败，请重试') }
  }
  // Out-of-scope entries stay explicit no-ops; the encyclopedia node is a later C-002 page.
  function notWired(label: string) {
    if (!requirePreview()) return
    setNotice(`“${label}”页面尚未接入本次预览`)
  }
  const extraRows = Math.max(0, pets.length - 2) * 85
  const ready = phase === 'ready'
  return <ConsumerPageLayout page='petList' unit={unit} navigation={{ idPrefix: 'pet', disabled: phase !== 'ready', onSelect: key => { setNotice(navigationUnavailableMessage(key)) }, referencePlacement: undefined }} className={`pet-page pet-list-page${referenceCanvas ? ' pet-reference-canvas' : ''}`} style={style}>
    <View className='pet-status-area' />
    <View className='pet-design pet-list-design' data-phase={phase} style={{ '--pet-list-extra': `${extraRows * unit}px` } as CSSProperties}>
      <Image className='pet-list-strip' src={stripMain} mode='scaleToFill' />
      <Image className='pet-list-blob pet-list-blob-backing' src={backing} mode='scaleToFill' />
      <Image className='pet-list-blob pet-list-blob-sheet' src={sheet} mode='scaleToFill' />
      <Image className='pet-list-tape' src={tape} mode='scaleToFill' />
      {!ready && <View className='pet-state pet-list-state' role='status'>
        <Text>{phase === 'loading' ? '正在加载宠物档案…' : phase === 'expired' ? '登录已失效，请重新登录' : phase === 'load-error' ? '加载失败，请重试' : '宠物服务暂不可用，请稍后再试'}</Text>
        {phase === 'load-error' && <Button id='pet-retry-load' className='pet-state-action' onClick={() => void load()}>重新加载</Button>}
      </View>}
      {ready && pets.length === 0 && <View className='pet-state pet-list-state' role='status'>
        <Text>还没有宠物档案，点击上方「+」添加</Text>
      </View>}
      {ready && <View className='pet-list-head'>
        <Text className='pet-list-title'>宠物档案</Text>
        <Button id='pet-list-count' className='pet-list-count' ariaLabel='添加宠物' onClick={openForm}><Text space='nbsp'>{pets.length} 只萌宠     +</Text></Button>
        <Button id='pet-list-encyc' className='pet-list-encyc' ariaLabel='宠物百科' onClick={() => notWired('宠物百科')}>
          <Image className='pet-list-encyc-paw' src={paw} mode='scaleToFill' />
          <Text className='pet-list-encyc-label'>宠物百科</Text>
          <Image className='pet-list-encyc-chevron' src={chevron} mode='scaleToFill' />
        </Button>
      </View>}
      {ready && pets.map((pet, index) => {
        const photo = petPhotos[pet.petId]
        const tag = previewSupplement(pet.petId).tag
        const top = 95 + index * 85
        return <Button key={pet.petId} id={`pet-card-${pet.petId}`} className='pet-list-card' style={{ top: `calc(var(--pet-unit) * ${top})` }} ariaLabel={`${pet.name}的档案`} onClick={() => openDetail(pet)} onLongPress={() => void removePet(pet)}>
          {pet.avatarUrl ? <Image className='pet-list-photo pet-list-avatar' src={pet.avatarUrl} mode='aspectFill' /> : photo ? <Image className='pet-list-photo' src={photo.src} mode='scaleToFill' style={{ left: `calc(var(--pet-unit) * ${photo.x})`, top: `calc(var(--pet-unit) * ${photo.y})`, width: `calc(var(--pet-unit) * ${photo.w})`, height: `calc(var(--pet-unit) * ${photo.h})` }} /> : <View className='pet-list-photo pet-list-avatar pet-avatar-empty'><Text>{pet.name.slice(0, 1)}</Text></View>}
          <View className='pet-list-identity'><Text className='pet-list-name'>{pet.name}</Text>
          {sexLabel(pet.sex) && <View className={`pet-list-sex${pet.sex === 'FEMALE' ? ' pet-list-sex-female' : ''}`}><Text>{sexLabel(pet.sex)}</Text></View>}</View>
          <Text className='pet-list-breed'>{breedAgeLine(pet, todayISO())}</Text>
          {tag && <View className={`pet-list-tag pet-list-tag-${tag.pill}`}>
            <View className='pet-list-tag-pill'>
              <Image className='pet-list-tag-icon' src={tag.pill === 'vaccine' ? tagVaccine : tagDeworm} mode='scaleToFill' />
              <Text className='pet-list-tag-name'>{tag.name}</Text>
            </View>
            <Text className='pet-list-tag-date'>{tag.date}</Text>
          </View>}
        </Button>
      })}
      {notice && <Text id='pet-notice' className='pet-notice pet-list-notice'>{notice}</Text>}
    </View>
  </ConsumerPageLayout>
}
function todayISO() {
  const now = new Date()
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  const day = `${now.getDate()}`.padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}
