import { Button, Input, Picker, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow, useDidHide, useRouter } from '@tarojs/taro'
import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { afterSaleClient } from '../../../shared/aftersale-runtime'
import { consumerStorage, consumerApi } from '../../../shared/consumer-runtime'
import { privateUploadFiles } from '../../../shared/private-upload-platform'
import { afterSaleCatalog, catalogSelectionErrors, consumerController, restorePending, type AfterSaleCatalogPort } from '../../aftersale/runtime'
import { AfterSaleEvidenceUpload } from '../../aftersale/upload'
import { afterSaleMessage, emptyCreateDraft, isId, validateCreate, type CreateDraft, type FieldErrors } from '../../aftersale/model'
import { AfterSaleFrame, Card, EvidenceDraft, Notice, formatTime } from './common'

export default function AfterSaleApplyPage() {
  const route = useRouter(), { revision } = useWorkspace('real')
  return <ApplyScreen key={`${revision}:${route.params.orderId || ''}`} orderId={route.params.orderId || ''} />
}
function ApplyScreen({ orderId, catalogPort }: { orderId: string; catalogPort?: AfterSaleCatalogPort }) {
  const [client] = useState(() => afterSaleClient('c'))
  const [controller] = useState(() => consumerController(client))
  const [uploads] = useState(() => new AfterSaleEvidenceUpload(client, consumerStorage, privateUploadFiles(), `create:${orderId}`))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [draft, setDraft] = useState<CreateDraft>(emptyCreateDraft)
  const [assets, setAssets] = useState<{ assetId: string }[]>([])
  const [errors, setErrors] = useState<FieldErrors>({})
  const [notice, setNotice] = useState('')
  const [uploading, setUploading] = useState(false)
  const [uploadPending, setUploadPending] = useState(false)
  const [uploadRejected, setUploadRejected] = useState(false)
  const catalog = state.catalog
  const initialized = useRef(false), mounted = useRef(true), loadGeneration = useRef(0), visible = useRef(true)
  const revision = consumerApi.scope.revision
  const live = () => mounted.current && visible.current && revision === consumerApi.scope.revision
  const slot = `aftersale:c:draft:create:${orderId}`
  const locked = state.phase !== 'ready' || state.locked || state.busy || state.readOnly || uploading || !state.eligibility?.eligible || !!state.eligibility.activeAfterSaleId || !catalog
  function save(nextDraft: CreateDraft, nextAssets: { assetId: string }[]) { client.api.saveIntent(slot, { draft: nextDraft, assets: nextAssets }) }
  async function load() {
    const generation = ++loadGeneration.current
    controller.clearCreateContext()
    setNotice('')
    if (!isId(orderId)) { setNotice('缺少有效订单号，请返回重新选择'); return }
    try {
      await client.api.restore()
      if (!live() || generation !== loadGeneration.current) return
      if (!initialized.current) {
        const saved = client.api.intent(slot) as { draft?: CreateDraft; assets?: { assetId: string }[] } | undefined
        if (saved?.draft && Object.keys(emptyCreateDraft()).every(key => typeof saved.draft?.[key as keyof CreateDraft] === 'string')) setDraft(saved.draft)
        if (Array.isArray(saved?.assets)) setAssets(saved.assets.filter(asset => typeof asset?.assetId === 'string' && isId(asset.assetId)).slice(0, 6))
        const pending = restorePending(client, orderId, true)
        if (pending) controller.restore(pending)
        initialized.current = true
      }
      const pendingUpload = uploads.pending(); setUploadPending(!!pendingUpload); setUploadRejected(!!pendingUpload?.rejected)
      await controller.loadCreateContext(orderId, controller.getSnapshot().locked, catalogPort || (() => afterSaleCatalog(client)))
    } catch (error) { if (live() && generation === loadGeneration.current) setNotice(afterSaleMessage(error)) }
  }
  useDidShow(() => { visible.current = true; void load() })
  useDidHide(() => { visible.current = false; loadGeneration.current++; controller.clearCreateContext(); client.clearImages() })
  useEffect(() => () => { mounted.current = false; controller.dispose(); client.dispose() }, [client, controller])
  useEffect(() => {
    if (!state.receipt || !live()) return
    client.api.saveIntent(slot, null)
    void Taro.redirectTo({ url: `/consumer/pages/aftersale/detail?afterSaleId=${state.receipt.afterSaleId}` })
  }, [state.receipt])
  function edit(key: keyof CreateDraft, value: string) {
    if (locked || uploadPending) return
    const next = { ...draft, [key]: value }; save(next, assets); setDraft(next); setErrors(previous => ({ ...previous, [key]: undefined })); setNotice('')
  }
  async function add() {
    if (locked || assets.length >= 6) return
    setUploading(true); setNotice('')
    try {
      const receipt = await uploads.upload()
      if (!receipt || !live()) return
      const next = assets.some(asset => asset.assetId === receipt.assetId) ? assets : [...assets, { assetId: receipt.assetId }]
      save(draft, next); setAssets(next); await uploads.acknowledge(receipt.assetId)
    } catch (error) { if (live()) setNotice(afterSaleMessage(error)) }
    finally { if (live()) { const pending = uploads.pending(); setUploadPending(!!pending); setUploadRejected(!!pending?.rejected); setUploading(false) } }
  }
  function remove(assetId: string) { if (locked || uploadPending) return; const next = assets.filter(asset => asset.assetId !== assetId); save(draft, next); setAssets(next) }
  async function submit() {
    if (!catalog) { setNotice('申请选项暂不可用，请稍后再试'); return }
    if (uploadPending) { setNotice('有图片上传结果尚未确认，请先重试原图片'); return }
    const next = { ...validateCreate(draft, assets.map(asset => asset.assetId)), ...catalogSelectionErrors(catalog, draft) }; setErrors(next)
    if (Object.keys(next).length) { setNotice('请完善申请信息后提交'); return }
    await controller.create(orderId, draft, assets.map(asset => asset.assetId))
  }
  return <AfterSaleFrame title='申请售后'>
    <Notice text={notice || state.notice} />
    {state.phase === 'loading' && <Notice text='正在读取申请选项和订单资格…' />}
    <Card><Text className='afs-heading'>订单号 {orderId || '—'}</Text><Text className='afs-hint'>申请截止：{formatTime(state.eligibility?.deadline || null)}</Text><Text className='afs-hint'>{state.eligibility?.eligible ? '当前订单可发起售后，提交时再次校验' : state.eligibility?.blockingReason || '请先读取申请资格'}</Text>
      {state.eligibility?.activeAfterSaleId && <Button className='afs-secondary afs-field' onClick={() => void Taro.navigateTo({ url: `/consumer/pages/aftersale/detail?afterSaleId=${state.eligibility!.activeAfterSaleId}` })}>查看当前活动工单</Button>}
    </Card>
    <Card title='售后类型'>{!catalog && <Text className='afs-hint'>申请选项暂不可用，请稍后再试</Text>}
      <View className='afs-field'><Text className='afs-label'>问题类型</Text><Picker mode='selector' disabled={locked || uploadPending} range={catalog?.types.map(item => item.label) || []} onChange={event => { const selected = catalog?.types[Number(event.detail.value)]; if (selected) edit('typeCode', selected.code) }}><View id='afs-type-picker' className='afs-input'>{catalog?.types.find(item => item.code === draft.typeCode)?.label || '请选择问题类型'}</View></Picker>{errors.typeCode && <Text className='afs-error'>{errors.typeCode}</Text>}</View>
      <View className='afs-field'><Text className='afs-label'>诉求</Text><Picker mode='selector' disabled={locked || uploadPending} range={catalog?.demands.map(item => item.label) || []} onChange={event => { const selected = catalog?.demands[Number(event.detail.value)]; if (selected) edit('demandCode', selected.code) }}><View id='afs-demand-picker' className='afs-input'>{catalog?.demands.find(item => item.code === draft.demandCode)?.label || '请选择诉求'}</View></Picker>{errors.demandCode && <Text className='afs-error'>{errors.demandCode}</Text>}</View>
      <View className='afs-field'><Text className='afs-label'>诉求金额（选填）</Text><Input id='afs-requested-amount' className='afs-input' type='text' disabled={locked || uploadPending} maxlength={19} value={draft.requestedAmount} placeholder='例如35.00' onInput={event => edit('requestedAmount', event.detail.value)} />{errors.requestedAmount && <Text className='afs-error'>{errors.requestedAmount}</Text>}</View>
    </Card>
    <Card title='补充说明'><Textarea id='afs-description' className='afs-textarea' disabled={locked || uploadPending} maxlength={500} value={draft.description} placeholder='请描述您遇到的问题（必填，10至500字）' onInput={event => edit('description', event.detail.value)} /><Text className='afs-count'>{draft.description.length}/500</Text>{errors.description && <Text className='afs-error'>{errors.description}</Text>}</Card>
    <Card title='新问题说明'><Text className='afs-hint'>已有非退款终局结论时，请说明本次新问题。运营核对后受理，原申请期限不变。</Text><Textarea id='afs-new-problem' className='afs-textarea afs-field' disabled={locked || uploadPending} maxlength={500} value={draft.newProblemStatement} placeholder='新问题说明（10至500字）' onInput={event => edit('newProblemStatement', event.detail.value)} />{errors.newProblemStatement && <Text className='afs-error'>{errors.newProblemStatement}</Text>}</Card>
    <EvidenceDraft assets={assets} disabled={locked} onAdd={() => void add()} onRemove={remove} />
    {errors.evidence && <Text className='afs-error'>{errors.evidence}</Text>}
    {uploadPending && <><Notice text='图片上传结果尚未确认，请点击添加图片重试原图片' />{uploadRejected && <Button className='afs-secondary' disabled={uploading} onClick={() => void uploads.discardRejected().then(() => { setUploadPending(false); setUploadRejected(false) })}>移除校验失败的图片</Button>}</>}
    {state.locked ? <Button id='afs-retry-create' className='afs-primary' disabled={state.busy || state.readOnly} onClick={() => void controller.retry()}>重试原申请</Button> : <Button id='afs-submit' className='afs-primary' disabled={locked || uploadPending} onClick={() => void submit()}>提交申请</Button>}
    <Button className='afs-secondary' disabled={state.busy || uploading} onClick={() => void load()}>重新读取申请选项与资格</Button>
  </AfterSaleFrame>
}
