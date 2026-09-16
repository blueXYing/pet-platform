import { ConsumerPageLayout } from '../../components/page-layout'
import { navigationUnavailableMessage, type ConsumerNavigationKey } from '../../components/navigation/model'
import { Button, Image, Input, Text, Textarea, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { integrationMessage } from '../../../shared/consumer-api'
import { RealProfileRepository } from '../../api/repositories'
import { isPreviewScenario, PreviewProfileRepository, sameDraft, validateDraft, codePointLength, type Gender, type ProfileDraft, type ProfilePhase } from '../../profile/model'
import back from '../../assets/profile/back.png'
import chevron from '../../assets/profile/chevron.png'
import avatar from '../../assets/profile/avatar.png'
import './fonts.css'
import './profile.css'

const initial: ProfileDraft = { nickname: '宠友小白', gender: null, signature: '爱宠物的铲屎官一枚～', avatarUrl: avatar, phoneMasked: '138****5678' }
const genderOptions: [Gender, string][] = [['MALE', '男'], ['FEMALE', '女']]
const sleep = () => new Promise<void>(resolve => setTimeout(resolve, 450))
export default function ProfileEdit() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  // A real 402x812 component canvas inside the actual simulator; no spoofed device APIs.
  const referenceCanvas = preview && route.params.referenceCanvas === '1'
  const scenario = isPreviewScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope, revision, context } = useWorkspace(preview ? 'preview' : 'real')
  const [phase, setPhase] = useState<ProfilePhase>('loading')
  const [draft, setDraft] = useState<ProfileDraft>(initial)
  const [baseline, setBaseline] = useState<ProfileDraft>(initial)
  const [errors, setErrors] = useState<ReturnType<typeof validateDraft>>({})
  const [notice, setNotice] = useState('')
  const repository = useRef(preview ? new PreviewProfileRepository(scenario === 'empty' ? { ...initial, nickname: '', signature: '' } : initial, scenario, sleep) : new RealProfileRepository(consumerApi))
  const request = useRef<{ id: string; draft: ProfileDraft } | null>(null)
  const saving = useRef(false)
  const mounted = useRef(true)
  const sequence = useRef(0)
  const previousRevision = useRef(revision)
  const [platformInfo, setPlatformInfo] = useState(() => Taro.getWindowInfo())
  // This page has custom navigation; its full-screen renderer includes the OS status area.
  // DevTools can retain the previous native-navigation screenTop in getWindowInfo after routing.
  const topInset = platformInfo.statusBarHeight || 0
  const style = { '--profile-status-top': `${referenceCanvas ? 0 : topInset}px`, '--profile-design-width': referenceCanvas ? '402px' : '100vw', '--profile-unit': `${referenceCanvas ? 1 : platformInfo.windowWidth / 402}px` } as CSSProperties

  const load = useCallback(async () => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    setPhase('loading'); setNotice(''); setErrors({})
    if ((preview && scenario === 'expired') || !scope.current || scope.current.workspace !== 'consumer') { setPhase('expired'); return }
    try {
      const value = await scope.run(undefined, () => repository.current.load())
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      const pending = !preview && consumerApi.pendingCommand('profile')
      setDraft(pending ? { ...value, nickname: String(pending.data?.nickname || '') } : value); setBaseline(value); request.current = null; setPhase('ready')
      if (!preview) setNotice(pending ? '上次保存结果尚未确认，请重试原操作' : '本次可保存昵称；性别、签名和头像上传尚未接通')
    } catch {
      if (mounted.current && current === sequence.current && currentRevision === scope.revision) setPhase('load-error')
    }
  }, [preview, scenario, scope])
  useEffect(() => {
    mounted.current = true
    void load()
    return () => { mounted.current = false; sequence.current++ }
  }, [load])
  useEffect(() => {
    if (previousRevision.current !== revision || !context || context.workspace !== 'consumer') {
      previousRevision.current = revision
      sequence.current++; request.current = null; saving.current = false
      const empty: ProfileDraft = { nickname: '', signature: '', avatarUrl: '', phoneMasked: '', gender: null }
      setDraft(empty); setBaseline(empty); setNotice(''); setErrors({})
      repository.current = preview ? new PreviewProfileRepository(empty, 'normal', sleep) : new RealProfileRepository(consumerApi)
      setPhase('expired')
      if (!preview && context?.workspace === 'consumer') void load()
    }
  }, [revision, context, preview, load])
  useEffect(() => {
    const handler = () => setPlatformInfo(Taro.getWindowInfo())
    Taro.onWindowResize(handler)
    return () => Taro.offWindowResize(handler)
  }, [])
  function edit<K extends keyof ProfileDraft>(key: K, value: ProfileDraft[K]) {
    if (saving.current || (!preview && consumerApi.pendingCommand('profile'))) return
    setDraft(old => ({ ...old, [key]: value })); setNotice(''); setErrors({})
    if (phase === 'save-error') setPhase('ready')
  }
  async function save() {
    if (saving.current || !['ready', 'save-error'].includes(phase)) return
    const invalid = validateDraft(draft); setErrors(invalid)
    if (Object.keys(invalid).length) return
    saving.current = true; setPhase('saving'); setNotice('')
    const currentRevision = scope.revision
    if (!request.current || !sameDraft(request.current.draft, draft)) {
      // Preview transaction identity only; real writes require the approved UUID provider.
      request.current = { id: `preview-${Date.now()}-${++sequence.current}`, draft: { ...draft } }
    }
    const pending = request.current
    try {
      const saved = await scope.run(undefined, () => repository.current.save(pending.draft, pending.id))
      if (!mounted.current || currentRevision !== scope.revision) return
      setDraft(saved); setBaseline(saved); setPhase('ready'); setNotice(preview ? '预览数据已更新' : '昵称已保存'); request.current = null
    } catch (error) {
      if (mounted.current && currentRevision === scope.revision) { setPhase('save-error'); setNotice(preview ? '保存失败，请重试；已填写内容保留' : integrationMessage(error)) }
    } finally { saving.current = false }
  }
  async function chooseAvatar() {
    if (!preview) { setNotice('头像上传尚未接通'); return }
    if (saving.current) return
    const currentRevision = scope.revision
    try {
      const selected = await Taro.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'] })
      if (mounted.current && currentRevision === scope.revision && selected.tempFiles[0]) {
        edit('avatarUrl', selected.tempFiles[0].tempFilePath)
        setNotice('头像仅在本次预览中使用')
      }
    } catch (error) {
      if (!String((error as { errMsg?: string }).errMsg).includes('cancel') && mounted.current && currentRevision === scope.revision) setNotice('未能选择头像，请检查相册权限后重试')
    }
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
  return <ConsumerPageLayout page='profileEdit' unit={referenceCanvas ? 1 : platformInfo.windowWidth / 402} navigation={{ idPrefix: 'profile', disabled: phase === 'saving', onSelect: key => leave(key), referencePlacement: referenceCanvas ? { bottom: 0 } : undefined }} className={`profile-page${referenceCanvas ? ' profile-reference-canvas' : ''}`} style={style}>
    <View className='profile-status-area' />
    <View className='profile-design' data-phase={phase}>
      <View className='profile-header'>
        <Button id='profile-back' className='profile-back' ariaLabel='返回' disabled={phase === 'saving'} onClick={() => void leave()}><Image src={back} mode='scaleToFill' /></Button>
        <Text className='profile-title'>编辑资料</Text>
      </View>
      {!editable && <View className='profile-state' role='status'>
        <Text>{phase === 'loading' ? '正在加载资料…' : phase === 'expired' ? '登录已失效，请重新登录' : phase === 'load-error' ? '加载失败，请重试' : '资料服务暂不可用，请稍后再试'}</Text>
        {phase === 'load-error' && <Button id='profile-retry-load' className='profile-state-action' onClick={() => void load()}>重新加载</Button>}
        {!preview && phase === 'expired' && <Button className='profile-state-action' onClick={() => Taro.redirectTo({ url: '/consumer/pages/shell/index' })}>去登录</Button>}
      </View>}
      {editable && <View className='profile-form'>
        <View className='profile-card profile-avatar-card'>
          <Text className='profile-label'>头像</Text>
          <Button id='profile-avatar' className='profile-avatar-action' ariaLabel='更换头像' disabled={phase === 'saving'} onClick={() => void chooseAvatar()}>
            {draft.avatarUrl && <Image className='profile-avatar-image' src={draft.avatarUrl} mode='aspectFill' />}
            <Image className='profile-chevron' src={chevron} mode='scaleToFill' />
          </Button>
        </View>
        <View className='profile-card profile-nickname-card'>
          <Text className='profile-label'>昵称</Text>
          <Input id='profile-nickname' className='profile-nickname' value={draft.nickname} maxlength={-1} disabled={phase === 'saving' || (!preview && !!consumerApi.pendingCommand('profile'))} ariaLabel='昵称' onInput={event => edit('nickname', event.detail.value)} adjustPosition />
        </View>
        {errors.nickname && <Text className='profile-field-error'>{errors.nickname}</Text>}
        <View className='profile-card profile-gender-card'>
          <Text className='profile-label'>性别</Text>
          <View className='profile-genders'>{genderOptions.map(([value, label]) => <Button key={value} id={`profile-gender-${value}`} className={`profile-gender${draft.gender === value ? ' is-selected' : ''}`} ariaLabel={`${label}${draft.gender === value ? '，已选择' : ''}`} disabled={!preview || phase === 'saving'} onClick={() => edit('gender', value)}>{label}</Button>)}</View>
        </View>
        <View className='profile-card profile-phone-card'><Text className='profile-label'>手机号</Text><View className='profile-phone'><Text>{draft.phoneMasked}</Text><Text className='profile-bound'>（已绑定）</Text></View></View>
        <View className='profile-card profile-signature-card'>
          <Text className='profile-label'>个性签名</Text>
          <Textarea id='profile-signature' className='profile-signature' value={draft.signature} maxlength={-1} disabled={!preview || phase === 'saving'} placeholder={preview ? '' : '暂未接通'} ariaLabel='个性签名' onInput={event => edit('signature', event.detail.value)} adjustPosition showConfirmBar={false} />
          <Text id='profile-signature-count' className='profile-count'>{codePointLength(draft.signature)}/60</Text>
        </View>
        {errors.signature && <Text className='profile-field-error'>{errors.signature}</Text>}
        <Button id='profile-save' className='profile-save' disabled={phase === 'saving'} onClick={() => void save()}><Text className='profile-save-label'>{phase === 'saving' ? '保存中…' : '保存'}</Text></Button>
        {notice && <Text id='profile-notice' className='profile-notice'>{notice}</Text>}
      </View>}
    </View>
  </ConsumerPageLayout>
}
