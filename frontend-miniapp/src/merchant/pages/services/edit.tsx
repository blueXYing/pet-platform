import { Button, Image, Input, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealServiceManageRepository, serviceManageMessage } from '../../services/repository'
import {
  PreviewServiceManageRepository, draftFromDetail, draftInputProblems, editableStatuses, emptyDraft,
  fulfillmentText, fulfillments, isServiceManageScenario, manageStatusText, missingSubmitFields,
  petTypeText, petTypes, type ApplicablePetType, type ManagedServiceDetail,
  type ServiceCategoryView, type ServiceDraftInput, type ServiceManageDeps,
} from '../../services/model'
import navBack from './assets/nav-back@2x.png'
import iconCamera from './assets/icon-camera@2x.png'
import './page.css'

// M-002 服务新增/编辑（design source: merchant frame 11:5768「添加商品」, 402x1611,
// adjudicated 2026-09-22): direct-sale/package-included rows cut per V1, fulfillment uses the
// two contract values, categories come from GET /merchant/service-categories, and the
// PRD/contract fields the original lacks (人员要求/售后说明/备注) are appended in the same
// form language (VIS-004 registered differences — see C-003-design-inputs/INVENTORY.md).
type Phase = 'idle' | 'loading' | 'ready' | 'missing' | 'load-error' | 'entry'

export default function MerchantServiceEditPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isServiceManageScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const serviceIdParam = route.params.serviceId || ''
  const { scope, context } = useWorkspace(preview ? 'preview' : 'real')
  const [repository] = useState<ServiceManageDeps>(() => preview ? new PreviewServiceManageRepository(undefined, scenario) : realRepository())
  const [phase, setPhase] = useState<Phase>('idle')
  const [categories, setCategories] = useState<ServiceCategoryView[]>([])
  const [detail, setDetail] = useState<ManagedServiceDetail | null>(null)
  const [draft, setDraft] = useState<ServiceDraftInput>(emptyDraft())
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const version = useRef<string>('')
  const createdId = useRef<string>('')
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--msvc-status-top': `${platformInfo.statusBarHeight || 0}px`, '--msvc-unit': `${unit}px` } as CSSProperties

  const load = useCallback(async () => {
    setNotice('')
    if (preview && scenario === 'expired') { setPhase('entry'); return }
    if (!preview) {
      const current = scope.current
      if (!current || current.workspace !== 'merchant' || !current.merchantId || !current.storeId) { setPhase('entry'); return }
    }
    setPhase('loading')
    try {
      const categoryList = await repository.categories()
      setCategories(categoryList)
      if (serviceIdParam) {
        const loaded = await repository.detail(serviceIdParam)
        setDetail(loaded); version.current = loaded.version; createdId.current = loaded.serviceId
        setDraft(draftFromDetail(loaded))
      } else {
        setDetail(null); version.current = ''; createdId.current = ''; setDraft(emptyDraft())
      }
      setPhase('ready')
    } catch (error) {
      if (error instanceof Error && /SERVICE_NOT_FOUND/.test(error.message)) setPhase('missing')
      else if (isStatus(error, 404)) setPhase('missing')
      else { setNotice(serviceManageMessage(error)); setPhase('load-error') }
    }
  }, [repository, preview, scenario, scope, serviceIdParam])
  useDidShow(() => { void load() })
  useEffect(() => { if (!preview && context && context.workspace !== 'merchant') setPhase('entry') }, [context, preview])

  const readonly = detail !== null && !editableStatuses.includes(detail.status)
  const editing = detail !== null

  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack().catch(() => setNotice('返回失败，请重试'))
    else await Taro.redirectTo({ url: '/merchant/pages/services/index' }).catch(() => setNotice('返回失败，请重试'))
  }
  function patch(next: Partial<ServiceDraftInput>) { setDraft(current => ({ ...current, ...next })) }
  function togglePetType(type: ApplicablePetType) {
    if (readonly) return
    setDraft(current => {
      if (type === 'ALL') return { ...current, applicablePetTypes: current.applicablePetTypes.includes('ALL') ? [] : ['ALL'] }
      const withoutAll = current.applicablePetTypes.filter(item => item !== 'ALL' && item !== type)
      const has = current.applicablePetTypes.includes(type)
      return { ...current, applicablePetTypes: has ? withoutAll : [...withoutAll, type] }
    })
  }
  function pickCover() {
    if (readonly || busy) return
    if (!preview) { setNotice('封面上传依赖门店素材管线（SERVICE_COVER），尚未接通；当前无法选择真实封面。'); return }
    void Taro.chooseImage({ count: 1, sizeType: ['compressed'], sourceType: ['album', 'camera'] }).then(result => {
      const path = result.tempFilePaths[0]
      if (!path) return
      // Preview-only mock asset id; the real coverAssetId comes from the SERVICE_COVER pipeline.
      patch({ coverAssetId: `4${Date.now().toString().slice(-8)}` })
      setNotice('已选择封面（Mock）；真实上传待素材管线接通。')
      setCoverPreview(path)
    }).catch(() => setNotice('已取消选择封面'))
  }
  const [coverPreview, setCoverPreview] = useState('')
  // The workbench row carries a flat coverAssetId anchor only (§4.10.1 字段定稿); the signed
  // display URL is the C-end cover projection (§3.3.1 封面增补), so the editor previews the
  // local pick and otherwise shows the chosen-anchor state without fabricating any URL.
  const coverChosen = coverPreview !== '' || (detail?.coverAssetId ?? draft.coverAssetId) != null
  const coverSrc = coverPreview

  async function persistDraft(): Promise<boolean> {
    const problems = draftInputProblems(draft, categories)
    if (problems.length) { setNotice(`请检查：${problems.join('；')}`); return false }
    const receipt = editing || createdId.current
      ? await repository.update(`merchant-service:${createdId.current}:update`, createdId.current, version.current, draft)
      : await repository.create('merchant-service:create', draft)
    createdId.current = receipt.serviceId
    version.current = receipt.version
    return true
  }
  async function saveDraft() {
    if (readonly || busy) return
    setNotice(''); setBusy(true)
    try {
      const saved = await persistDraft()
      if (saved) {
        setDetail(await repository.detail(createdId.current))
        setNotice('草稿已保存。')
      }
    } catch (error) { setNotice(serviceManageMessage(error)) } finally { setBusy(false) }
  }
  async function submitForReview() {
    if (readonly || busy) return
    setNotice('')
    const missing = missingSubmitFields(draft, categories)
    if (missing.length) { setNotice(`提交审核前需补齐：${missing.join('、')}。`); return }
    const problems = draftInputProblems(draft, categories)
    if (problems.length) { setNotice(`请检查：${problems.join('；')}`); return }
    setBusy(true)
    try {
      await persistDraft()
      await repository.submitOnline(`merchant-service:${createdId.current}:online`, createdId.current, version.current)
      setDetail(await repository.detail(createdId.current))
      setNotice('已提交审核，等待平台审核结果。')
    } catch (error) { setNotice(serviceManageMessage(error)) } finally { setBusy(false) }
  }
  async function takeOffline() {
    if (!detail || detail.status !== 'ACTIVE' || busy) return
    const confirmed = await Taro.showModal({ title: '确认下架', content: '下架后前台不再展示该服务；对历史订单无影响，仍按原快照履约。', confirmText: '确认下架', cancelText: '取消' })
    if (!confirmed.confirm) return
    setNotice(''); setBusy(true)
    try {
      await repository.takeOffline(`merchant-service:${detail.serviceId}:offline`, detail.serviceId, detail.version)
      setDetail(await repository.detail(detail.serviceId))
      setDraft(draftFromDetail(await repository.detail(detail.serviceId)))
      setNotice('服务已下架，历史订单不受影响。')
    } catch (error) { setNotice(serviceManageMessage(error)) } finally { setBusy(false) }
  }

  return <View className='msvc-page medit-page' style={style}>
    <View className='msvc-status-area' />
    <View className='msvc-header'>
      <Button id='medit-back' ariaLabel='返回' className='msvc-back' onClick={() => void back()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='msvc-title'>{editing ? '编辑服务' : '添加服务'}</Text>
    </View>
    {phase !== 'ready' && <View className='msvc-state' role='status'>
      <Text>{phase === 'loading' || phase === 'idle' ? '正在加载…' : phase === 'missing' ? '服务不存在或已删除。' : phase === 'entry' ? '请从商家工作台进入服务管理。' : notice || '加载失败，请重试。'}</Text>
      {phase === 'load-error' && <Button id='medit-retry' className='msvc-state-action' onClick={() => void load()}>重新加载</Button>}
      {phase === 'entry' && !preview && <Button className='msvc-state-action' onClick={() => Taro.redirectTo({ url: '/merchant/pages/workspace/index' })}>去商家工作台</Button>}
      {(phase === 'missing' || phase === 'entry') && <Button className='msvc-state-action' onClick={() => Taro.redirectTo({ url: '/merchant/pages/services/index' })}>返回服务管理</Button>}
    </View>}
    {phase === 'ready' && <View className='medit-body'>
      {detail && detail.status === 'REJECTED' && detail.latestRejection !== null && <View className='medit-banner medit-reject' role='alert'>
        <Text className='medit-banner-title'>审核驳回（{manageStatusText.REJECTED}）</Text>
        <Text className='medit-banner-text'>驳回原因：{detail.latestRejection.opinion}</Text>
        <Text className='medit-banner-text'>请按驳回原因修改后重新提交审核。</Text>
      </View>}
      {readonly && <View className='medit-banner' role='status'>
        <Text className='medit-banner-title'>{detail!.status === 'ACTIVE' ? '上架中的服务为只读展示' : '审核中的服务为只读展示'}</Text>
        <Text className='medit-banner-text'>{detail!.status === 'ACTIVE' ? '如需修改价格或内容，请先下架；下架对历史订单无影响。' : '请等待平台审核结果；驳回后可查看原因并修改重提。'}</Text>
        {detail!.status === 'ACTIVE' && <Button id='medit-offline' className='medit-banner-action' disabled={busy} onClick={() => void takeOffline()}>下架服务</Button>}
      </View>}
      <View className='medit-section'>
        <Text className='medit-label'>头图</Text>
        <Button id='medit-cover' className='medit-cover' onClick={pickCover}>
          {coverSrc
            ? <Image className='medit-cover-image' src={coverSrc} mode='aspectFill' />
            : coverChosen
              ? <Text className='medit-cover-placeholder'>已绑定封面素材（展示链接由 C 端签名投影提供）</Text>
              : <Text className='medit-cover-placeholder'>上传服务封面（JPG / PNG）</Text>}
          <View className='medit-cover-badge'>
            <Image className='medit-cover-camera' src={iconCamera} mode='scaleToFill' />
            <Text>更换头图</Text>
          </View>
        </Button>
      </View>
      <Field label='服务名称' required={!readonly}>
        <Input id='medit-name' className='medit-input' disabled={readonly} value={draft.serviceName} placeholder='例如：猫咪洗澡+基础护理'
          onInput={event => patch({ serviceName: event.detail.value })} />
      </Field>
      <View className='medit-row'>
        <Field label='价格（元）' required={!readonly}>
          <Input id='medit-price' className='medit-input' disabled={readonly} type='digit' value={draft.price ?? ''} placeholder='例如：128'
            onInput={event => patch({ price: event.detail.value === '' ? null : event.detail.value })} />
        </Field>
        <Field label='划线价（可选）'>
          <Input id='medit-list-price' className='medit-input' disabled={readonly} type='digit' value={draft.listPrice ?? ''} placeholder='原价'
            onInput={event => patch({ listPrice: event.detail.value === '' ? null : event.detail.value })} />
        </Field>
      </View>
      <View className='medit-section'>
        <Text className='medit-label'>服务分类{!readonly && <Text className='medit-required'>*</Text>}</Text>
        <View className='medit-pills'>
          {categories.map(category => <Button key={category.categoryId} className='medit-pill' data-selected={draft.categoryId === category.categoryId} disabled={readonly}
            onClick={() => patch({ categoryId: draft.categoryId === category.categoryId ? null : category.categoryId })}><Text>{category.categoryName}</Text></Button>)}
        </View>
      </View>
      <View className='medit-section'>
        <Text className='medit-label'>适用宠物类型{!readonly && <Text className='medit-required'>*</Text>}</Text>
        <View className='medit-pills'>
          {petTypes.map(type => <Button key={type} className='medit-pill' data-selected={draft.applicablePetTypes.includes(type)} disabled={readonly}
            onClick={() => togglePetType(type)}><Text>{petTypeText[type]}</Text></Button>)}
        </View>
      </View>
      <View className='medit-section'>
        <Text className='medit-label'>履约方式{!readonly && <Text className='medit-required'>*</Text>}</Text>
        <View className='medit-pills'>
          {fulfillments.map(type => <Button key={type} className='medit-pill' data-selected={draft.fulfillmentType === type} disabled={readonly}
            onClick={() => patch({ fulfillmentType: draft.fulfillmentType === type ? null : type })}><Text>{fulfillmentText[type]}</Text></Button>)}
        </View>
        <Text className='medit-hint'>两种方式均为单次预约服务；上门接送型将分别占用接送与上门预约时段。</Text>
      </View>
      <Field label='服务时长(分钟)' required={!readonly}>
        <Input id='medit-duration' className='medit-input' disabled={readonly} type='number' value={draft.durationMinutes === null ? '' : String(draft.durationMinutes)} placeholder='60'
          onInput={event => { const value = event.detail.value === '' ? null : Math.trunc(Number(event.detail.value)); patch({ durationMinutes: value !== null && Number.isFinite(value) ? value : null }) }} />
      </Field>
      <View className='medit-section'>
        <Text className='medit-label'>是否需要核销</Text>
        <View className='medit-pills'>
          <Button className='medit-pill' data-selected={draft.verificationRequired} disabled={readonly} onClick={() => patch({ verificationRequired: true })}><Text>是</Text></Button>
          <Button className='medit-pill' data-selected={!draft.verificationRequired} disabled={readonly} onClick={() => patch({ verificationRequired: false })}><Text>否</Text></Button>
        </View>
      </View>
      <View className='medit-section'>
        <Text className='medit-label'>服务说明<Text className='medit-counter'>{[...(draft.description ?? '')].length}/1000</Text></Text>
        <Textarea id='medit-description' className='medit-textarea' disabled={readonly} value={draft.description ?? ''} maxlength={1000}
          placeholder='介绍服务内容、服务流程、适用情况、注意事项等' onInput={event => patch({ description: event.detail.value === '' ? null : event.detail.value })} />
      </View>
      <Field label='服务人员要求'>
        <Input id='medit-staff' className='medit-input' disabled={readonly} value={draft.staffRequirement ?? ''} placeholder='对服务人员资质或等级的要求（0-200字）' maxlength={200}
          onInput={event => patch({ staffRequirement: event.detail.value === '' ? null : event.detail.value })} />
      </Field>
      <View className='medit-section'>
        <Text className='medit-label'>售后说明<Text className='medit-counter'>{[...(draft.aftersaleNote ?? '')].length}/500</Text></Text>
        <Textarea id='medit-aftersale' className='medit-textarea' disabled={readonly} value={draft.aftersaleNote ?? ''} maxlength={500}
          placeholder='服务前后的退款与售后说明（0-500字）' onInput={event => patch({ aftersaleNote: event.detail.value === '' ? null : event.detail.value })} />
      </View>
      <View className='medit-section'>
        <Text className='medit-label'>备注<Text className='medit-counter'>{[...(draft.remark ?? '')].length}/500</Text></Text>
        <Textarea id='medit-remark' className='medit-textarea' disabled={readonly} value={draft.remark ?? ''} maxlength={500}
          placeholder='店内备注，不对外展示（0-500字）' onInput={event => patch({ remark: event.detail.value === '' ? null : event.detail.value })} />
      </View>
      {notice && <Text id='medit-notice' className='msvc-notice'>{notice}</Text>}
      {!readonly && <View className='medit-actions'>
        <Button id='medit-save-draft' className='medit-action medit-action-secondary' disabled={busy} onClick={() => void saveDraft()}>保存草稿</Button>
        <Button id='medit-submit' className='medit-action' disabled={busy} onClick={() => void submitForReview()}>提交审核</Button>
      </View>}
      <View className='msvc-tail'><Text>页面数据：{preview ? '契约 Mock（preview=1，不联调）' : '真实接口（后端交付前失败关闭）'}</Text></View>
    </View>}
  </View>
}

function Field({ label, required, children }: { label: string; required?: boolean; children: React.ReactNode }) {
  return <View className='medit-section'>
    <Text className='medit-label'>{label}{required && <Text className='medit-required'>*</Text>}</Text>
    {children}
  </View>
}

function isStatus(error: unknown, statusCode: number): boolean {
  return typeof error === 'object' && error !== null && 'statusCode' in error && (error as { statusCode?: number }).statusCode === statusCode
}

function realRepository(): ServiceManageDeps {
  return new RealServiceManageRepository(consumerApi,
    () => consumerApi.scope.current?.merchantId || '',
    () => consumerApi.scope.current?.storeId || '')
}
