import { useEffect, useRef, useState, type CSSProperties } from 'react'
import Taro, { useRouter } from '@tarojs/taro'
import { ConsumerPageLayout } from '../../components/page-layout'
import { navigationUnavailableMessage } from '../../components/navigation/model'
import { useWorkspace } from '../../../shared/workspace-react'
import type { ApplicationResult, DraftInput } from '../../../shared/merchant-repositories'
import { id, definiteRejection } from '../../../shared/consumer-api'
import { ApplicationCommands, applicationMessage, editableApplication, emptyDraft, unavailableDependencies, validateApplication, type City, type FieldErrors, type MaterialKind } from '../../merchant-application/model'
import { MerchantApplicationView } from './view'

export default function MerchantApplicationPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const { scope, revision } = useWorkspace(preview ? 'preview' : 'real')
  // A scope replacement discards every input and invalidates all callbacks from the old principal.
  return <ApplicationScreen key={`${preview}:${revision}`} preview={preview} reference={preview && route.params.referenceCanvas === '1'} scope={scope} />
}
function ApplicationScreen({ preview, reference, scope }: { preview: boolean; reference: boolean; scope: ReturnType<typeof useWorkspace>['scope'] }) {
  const [deps] = useState(unavailableDependencies)
  const [commands] = useState(() => new ApplicationCommands(deps.application, scope))
  const [draft, setDraft] = useState<DraftInput>(emptyDraft)
  const [result, setResult] = useState<ApplicationResult | null>(null)
  const [opinion, setOpinion] = useState<string | null>(null)
  const [errors, setErrors] = useState<FieldErrors>({})
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(false)
  const [typeOpen, setTypeOpen] = useState(false)
  const [cities, setCities] = useState<City[] | null>(null)
  const [cityName, setCityName] = useState('')
  const [locked, setLocked] = useState(false)
  const pending = useRef<{ submit: boolean; current: ApplicationResult | null; draft: DraftInput; saved?: ApplicationResult } | null>(null)
  const mounted = useRef(true)
  const running = useRef(false)
  const dirty = useRef(false)
  const revision = scope.revision
  const live = () => mounted.current && revision === scope.revision
  const loggedIn = !!scope.current && scope.current.workspace === 'consumer'
  const [info, setInfo] = useState(() => Taro.getWindowInfo())
  useEffect(() => { const resize = () => setInfo(Taro.getWindowInfo()); Taro.onWindowResize(resize); return () => Taro.offWindowResize(resize) }, [])
  const unit = reference ? 1 : info.windowWidth / 402
  const style = { '--application-unit': `${unit}px`, '--application-top': `${reference ? 0 : info.statusBarHeight || 0}px`, ...(reference ? { width: '402px', minHeight: '1142px' } : {}) } as CSSProperties
  async function perform(action: () => Promise<void>) {
    if (running.current) return
    running.current = true; setBusy(true); setNotice('')
    try { await action() } catch (error) { if (live()) setNotice(applicationMessage(error)) }
    finally { running.current = false; if (live()) setBusy(false) }
  }
  async function load() {
    if (running.current) return
    if (pending.current) { setNotice('上次操作结果尚未确认，请先重试原操作。'); return }
    if (dirty.current) {
      const answer = await Taro.showModal({ title: '重新读取申请？', content: '重新读取会替换本页尚未保存的修改。', confirmText: '重新读取', cancelText: '继续编辑' })
      if (!answer.confirm || !live()) return
    }
    if (preview) { setNotice('当前为交互预览，没有读取真实申请。'); return }
    if (!loggedIn) return
    setLoading(true)
    await perform(async () => {
      const current = await deps.application.current()
      if (!live()) return
      setResult(current); setDraft(current.currentRevision.draft); setCityName(current.currentRevision.draft.cityCode ? `已选城市（${current.currentRevision.draft.cityCode}）` : ''); setOpinion(current.latestDecision?.opinion || null); dirty.current = false
    })
    if (live()) setLoading(false)
  }
  useEffect(() => { mounted.current = true; if (!preview) void load(); return () => { mounted.current = false } }, [])
  function edit(key: keyof DraftInput, value: DraftInput[keyof DraftInput]) {
    if (running.current || pending.current || !editableApplication(result) || (!preview && !loggedIn)) return
    setDraft(previous => ({ ...previous, [key]: value })); dirty.current = true
    setErrors(previous => ({ ...previous, [key]: undefined })); if (key === 'merchantTypeCode') setTypeOpen(false)
  }
  async function write(submit: boolean) {
    if (running.current || !editableApplication(result) || (!preview && !loggedIn)) return
    if (submit && !pending.current) { const next = validateApplication(draft); setErrors(next); if (Object.keys(next).length) { setNotice('请完善标记的必填信息后再提交。'); void Taro.showToast({ title: '请完善标记的必填信息', icon: 'none' }); return } }
    if (preview) { setNotice('交互预览不会保存或提交申请。'); return }
    if (!pending.current) pending.current = { submit, current: result, draft: JSON.parse(JSON.stringify(draft)) as DraftInput }
    setLocked(true)
    await perform(async () => {
      const intent = pending.current!
      try {
        const saved = intent.saved || await commands.save(intent.current, intent.draft)
        if (!live()) return
        intent.saved = saved; setResult(saved); dirty.current = false
        if (intent.submit) { const submitted = await commands.submit(saved); if (!live()) return; setResult(submitted); setNotice('申请已提交，请等待审核。') }
        else setNotice('草稿已保存。')
        pending.current = null; setLocked(false)
      } catch (error) {
        if (live() && (definiteRejection(error) || (error instanceof Error && error.message === 'APPLICATION_NOT_CONNECTED'))) { pending.current = null; setLocked(false) }
        throw error
      }
    })
  }
  async function upload(kind: MaterialKind) {
    await perform(async () => {
      const asset = await deps.upload(kind)
      if (!asset || !live()) return
      const assetId = id(asset.assetId)
      setDraft(previous => kind === 'storePhotoAssetIds' ? { ...previous, storePhotoAssetIds: [...new Set([...(previous.storePhotoAssetIds || []), assetId])].slice(0, 6) } : { ...previous, [kind]: assetId }); dirty.current = true
    })
  }
  async function leave() {
    if (running.current) return
    if (pending.current) { setNotice('上次操作结果尚未确认，请先重试原操作后再离开。'); return }
    if (dirty.current) { const answer = await Taro.showModal({ title: '离开申请页面？', content: '尚未保存的修改将不会保留。', confirmText: '离开', cancelText: '继续填写' }); if (!answer.confirm || !live()) return }
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/consumer/pages/shell/index' })
  }
  return <ConsumerPageLayout page='merchantApplication' unit={unit} className={`application-page${reference ? ' application-reference' : ''}`} style={style} navigation={{ idPrefix: 'application', disabled: busy, onSelect: key => setNotice(navigationUnavailableMessage(key)) }}>
    <MerchantApplicationView draft={draft} result={result} errors={errors} notice={notice} busy={busy} loading={loading} preview={preview} loggedIn={loggedIn} locked={locked} opinion={opinion} cityName={cityName} cities={cities} typeOpen={typeOpen} edit={edit}
      onBack={() => void leave()} onTypes={() => setTypeOpen(value => !value)} onCity={() => void perform(async () => { const values = await deps.cities(); if (live()) setCities(values) })}
      onCitySelect={city => { if (running.current || pending.current || !editableApplication(result)) return; edit('cityCode', city.code); setCityName(city.name); setCities(null) }}
      onLocation={() => void perform(async () => { const value = await deps.location(); if (value && live()) { setDraft(previous => ({ ...previous, ...value })); dirty.current = true } })}
      onUpload={kind => void upload(kind)} onRemove={(kind, assetId) => edit(kind, kind === 'storePhotoAssetIds' ? (draft.storePhotoAssetIds || []).filter(value => value !== assetId) : null)}
      onSave={() => void write(false)} onSubmit={() => void write(true)} onReload={() => void load()} onLogin={() => void Taro.redirectTo({ url: '/consumer/pages/shell/index' })} />
  </ConsumerPageLayout>
}
