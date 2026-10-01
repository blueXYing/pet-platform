import { Button, Image, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow, useDidHide, useRouter } from '@tarojs/taro'
import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { afterSaleClient } from '../../../shared/aftersale-runtime'
import { consumerApi, consumerStorage } from '../../../shared/consumer-runtime'
import { privateUploadFiles } from '../../../shared/private-upload-platform'
import { consumerController, restorePending } from '../../aftersale/runtime'
import { AfterSaleEvidenceUpload } from '../../aftersale/upload'
import { activeCase, afterSaleMessage, decisionLabel, isId, statusLabel, validateEvidence, type FieldErrors } from '../../aftersale/model'
import { AfterSaleFrame, Card, EvidenceDraft, Notice, formatTime } from './common'

export default function AfterSaleDetailPage() {
  const route = useRouter(), { revision } = useWorkspace('real')
  return <DetailScreen key={`${revision}:${route.params.afterSaleId || ''}`} caseId={route.params.afterSaleId || ''} />
}
function DetailScreen({ caseId }: { caseId: string }) {
  const [client] = useState(() => afterSaleClient('c'))
  const [controller] = useState(() => consumerController(client))
  const [uploads] = useState(() => new AfterSaleEvidenceUpload(client, consumerStorage, privateUploadFiles(), `evidence:${caseId}`))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [text, setText] = useState(''), [assets, setAssets] = useState<{ assetId: string }[]>([])
  const [errors, setErrors] = useState<FieldErrors>({}), [notice, setNotice] = useState('')
  const [uploading, setUploading] = useState(false), [uploadPending, setUploadPending] = useState(false), [uploadRejected, setUploadRejected] = useState(false)
  const [image, setImage] = useState<string | null>(null), [reading, setReading] = useState(false)
  const initialized = useRef(false), mounted = useRef(true), visible = useRef(true), imageEpoch = useRef(0), imageFlight = useRef(false)
  const revision = consumerApi.scope.revision
  const live = () => mounted.current && revision === consumerApi.scope.revision
  const slot = `aftersale:c:draft:evidence:${caseId}`
  const locked = state.locked || state.busy || state.readOnly || uploading || !activeCase(state.detail)
  function save(nextText: string, nextAssets: { assetId: string }[]) { client.api.saveIntent(slot, { text: nextText, assets: nextAssets }) }
  async function load() {
    if (!isId(caseId)) { setNotice('缺少有效工单编号，请返回重新选择'); return }
    try {
      await client.api.restore(); if (!live()) return
      if (!initialized.current) {
        const saved = client.api.intent(slot) as { text?: string; assets?: { assetId: string }[] } | undefined
        if (typeof saved?.text === 'string') setText(saved.text.slice(0, 500))
        if (Array.isArray(saved?.assets)) setAssets(saved.assets.filter(asset => typeof asset?.assetId === 'string' && isId(asset.assetId)).slice(0, 6))
        const pending = restorePending(client, caseId, false); if (pending) controller.restore(pending)
        initialized.current = true
      }
      const pending = uploads.pending(); setUploadPending(!!pending); setUploadRejected(!!pending?.rejected)
      await controller.loadDetail(caseId, state.locked)
    } catch (error) { if (live()) setNotice(afterSaleMessage(error)) }
  }
  function closeImage() {
    imageEpoch.current++; imageFlight.current = false
    setImage(null); setReading(false); client.clearImages()
  }
  useDidShow(() => { visible.current = true; void load() })
  useDidHide(() => { visible.current = false; closeImage() })
  useEffect(() => {
    // Clear rendered content synchronously with the scope notification; a subsequent page
    // remount also disposes the old principal's client and app-owned files.
    const unsubscribe = client.api.scope.subscribe(closeImage)
    return () => { unsubscribe(); mounted.current = false; visible.current = false; imageEpoch.current++; imageFlight.current = false; controller.dispose(); client.dispose() }
  }, [controller, client])
  useEffect(() => { if (!state.receipt || !live()) return; client.api.saveIntent(slot, null); setText(''); setAssets([]) }, [state.receipt])
  function edit(value: string) { if (locked || uploadPending) return; save(value, assets); setText(value); setErrors({}); setNotice('') }
  async function add() {
    if (locked || assets.length >= 6) return
    setUploading(true); setNotice('')
    try {
      const receipt = await uploads.upload(); if (!receipt || !live()) return
      const next = assets.some(asset => asset.assetId === receipt.assetId) ? assets : [...assets, { assetId: receipt.assetId }]
      save(text, next); setAssets(next); await uploads.acknowledge(receipt.assetId)
    } catch (error) { if (live()) setNotice(afterSaleMessage(error)) }
    finally { if (live()) { const pending = uploads.pending(); setUploadPending(!!pending); setUploadRejected(!!pending?.rejected); setUploading(false) } }
  }
  function remove(assetId: string) { if (locked || uploadPending) return; const next = assets.filter(asset => asset.assetId !== assetId); save(text, next); setAssets(next) }
  async function evidence() {
    if (uploadPending) { setNotice('有图片上传结果尚未确认，请先重试原图片'); return }
    const next = validateEvidence(text, assets.map(asset => asset.assetId)); setErrors(next); if (Object.keys(next).length) return
    await controller.evidence(text, assets.map(asset => asset.assetId))
  }
  async function withdraw() {
    if (locked || uploadPending) return
    const answer = await Taro.showModal({ title: '撤回售后申请', content: '撤回后保留历史证据与处理记录。再次申请仍需满足原资格与原申请期限。', confirmText: '确认撤回' })
    if (answer.confirm && live()) await controller.withdraw()
  }
  async function readImage(batchId: string, assetId: string) {
    if (imageFlight.current || !visible.current) return
    closeImage()
    const run = imageEpoch.current
    imageFlight.current = true; setReading(true); setNotice('')
    try {
      const path = await client.readEvidence(caseId, batchId, assetId, '本人查看售后卷宗证据')
      if (!live() || !visible.current || run !== imageEpoch.current) return
      setImage(path)
    } catch (error) { if (live() && visible.current && run === imageEpoch.current) setNotice(afterSaleMessage(error)) }
    finally { if (live() && run === imageEpoch.current) { imageFlight.current = false; setReading(false) } }
  }
  const detail = state.detail
  return <AfterSaleFrame title='售后进度'>
    <Notice text={notice || state.notice} />
    {state.phase === 'loading' && <Notice text='正在读取工单…' />}
    {detail && <>
      <Card><View className='afs-row'><Text className='afs-heading'>售后工单 {detail.afterSaleId}</Text><Text className='afs-status'>{statusLabel(detail.status)}</Text></View><Text className='afs-hint'>订单号 {detail.orderId}</Text><Text className='afs-hint'>申请时间：{formatTime(detail.createdAt)}</Text><Text className='afs-hint'>申请截止：{formatTime(detail.deadline)}</Text>{detail.requestedAmount && <Text className='afs-value'>诉求金额：¥{detail.requestedAmount}</Text>}</Card>
      <Card title='问题说明'><Text className='afs-value'>{detail.description}</Text>{detail.newProblemStatement && <><Text className='afs-heading afs-divider'>新问题说明</Text><Text className='afs-value'>{detail.newProblemStatement}</Text></>}</Card>
      {detail.status === 'WAITING_SUPPLEMENT' && <Card title={detail.supplementTarget === 'USER' ? '请补充证据' : '等待商家补证'}><Text className='afs-value'>{detail.supplementReason}</Text><Text className='afs-hint'>补证截止：{formatTime(detail.supplementDeadline)}</Text><Text className='afs-hint'>本轮补证须在截止前提交，逾期由运营继续处理。</Text></Card>}
      {(detail.status === 'RESOLVED' || detail.status === 'CLOSED' || detail.status === 'INVALIDATED' || detail.status === 'WITHDRAWN') && <Card title='处理结果'><Text className='afs-value'>{decisionLabel(detail.decisionType) || statusLabel(detail.status)}</Text>{detail.decisionReason && <Text className='afs-value'>{detail.decisionReason}</Text>}{detail.refundAmount && <Text className='afs-value'>裁决退款金额：¥{detail.refundAmount}</Text>}{detail.status === 'RESOLVED' && <Text className='afs-hint'>本次正式裁决为终局。</Text>}{detail.status === 'WITHDRAWN' && <Text className='afs-hint'>历史记录已保留，原申请期限不变。</Text>}{detail.status === 'INVALIDATED' && <Text className='afs-hint'>当前申请已失效，后续申请须重新校验订单资格。</Text>}{detail.priorFinalCaseIds.map(id => <Button key={id} className='afs-secondary afs-field' onClick={() => void Taro.navigateTo({ url: `/consumer/pages/aftersale/detail?afterSaleId=${id}` })}>查看历史结论 {id}</Button>)}</Card>}
      <Card title='证据记录'>{detail.evidence.length === 0 && <Text className='afs-hint'>暂无证据记录</Text>}{detail.evidence.map(batch => <View className='afs-evidence-batch' key={batch.batchId}><Text className='afs-label'>{batch.submitterType === 'USER' ? '本人提交' : '商家提交'} · {formatTime(batch.submittedAt)}</Text>{batch.text && <Text className='afs-value'>{batch.text}</Text>}{batch.opinionCode && <Text className='afs-hint'>商家意见：{({ AGREE: '同意', PARTLY_AGREE: '部分同意', DISAGREE: '不同意', NEED_USER_SUPPLEMENT: '需要补充说明' }[batch.opinionCode])}</Text>}<View className='afs-image-draft'>{batch.assetIds.map((id, index) => <Button key={id} className='afs-secondary' disabled={reading} ariaLabel={`查看证据图片${index + 1}`} onClick={() => void readImage(batch.batchId, id)}>{`查看图片 ${index + 1}`}</Button>)}</View></View>)}</Card>
      {activeCase(detail) && <>
        <Card title='追加问题与证据'><Text className='afs-hint'>同订单的新问题追加当前活动工单。补充说明与图片至少提交一项。</Text><Textarea id='afs-evidence-text' className='afs-textarea afs-field' value={text} disabled={locked || uploadPending} maxlength={500} placeholder='补充说明（10至500字）' onInput={event => edit(event.detail.value)} /><Text className='afs-count'>{text.length}/500</Text>{errors.text && <Text className='afs-error'>{errors.text}</Text>}</Card>
        <EvidenceDraft assets={assets} disabled={locked} onAdd={() => void add()} onRemove={remove} />
        {errors.evidence && <Text className='afs-error'>{errors.evidence}</Text>}
        {uploadPending && <><Notice text='图片上传结果尚未确认，请点击添加图片重试原图片' />{uploadRejected && <Button className='afs-secondary' disabled={uploading} onClick={() => void uploads.discardRejected().then(() => { setUploadPending(false); setUploadRejected(false) })}>移除校验失败的图片</Button>}</>}
        {!state.locked && <><Button id='afs-submit-evidence' className='afs-primary' disabled={locked || uploadPending} onClick={() => void evidence()}>提交补充证据</Button><Button id='afs-withdraw' className='afs-secondary' disabled={locked || uploadPending} onClick={() => void withdraw()}>撤回申请</Button></>}
      </>}
    </>}
    {state.locked && <Button id='afs-retry-write' className='afs-primary' disabled={state.busy || state.readOnly} onClick={() => void controller.retry()}>重试原操作</Button>}
    <Button id='afs-refresh' className='afs-secondary' disabled={state.busy || uploading} onClick={() => void load()}>重新读取进度</Button>
    {(reading || image) && <View className='afs-image-overlay' role='dialog' ariaLabel='入卷证据图片'>
      {image ? <Image className='afs-private-image' mode='aspectFit' src={image} /> : <Text className='afs-image-loading'>正在读取证据图片…</Text>}
      <Button id='afs-close-image' className='afs-primary' onClick={closeImage}>关闭证据</Button>
    </View>}
  </AfterSaleFrame>
}
