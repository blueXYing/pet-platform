import { Button, Input, ScrollView, Switch, Text, Textarea, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import {
  addressWriteMessage, emptyDraft, sameDraft, validateDraft,
  type AddressDraft, type AddressPhase, type AddressView,
} from '../../address/model'
import type { RealAddressRepository } from '../../address/repository'
import type { PreviewAddressRepository } from '../../address/model'
import './address-form.css'

// 新增/编辑共用表单（设计稿 129:13731 / 129:13533 同构底部弹层：遮罩 + 白面板 + 标题× +
// 收货人/手机号/所在地区 INPUT h42 + 详细地址 TEXTAREA h82 + 设为默认开关行 + 保存钮）。
// 偏差登记：编辑帧标题残留「新增地址」→按语义用「编辑地址」；微信 chooseAddress/
// chooseLocation 原稿形态为纯 INPUT，未接入并在 §4.14 披露。手机号/姓名等校验与后端
// 契约 59 §28 同口径；写路径走 ConsumerApi 幂等槽（address:create / address:{id}），
// 未确认提交在重进时按原载荷还原重试（23号）。
type Repository = PreviewAddressRepository | RealAddressRepository

export function AddressForm({ mode, addressId, preview, scenario, repository }: {
  mode: 'new' | 'edit'
  addressId: string | undefined
  preview: boolean
  scenario: string
  repository: Repository
}) {
  const { scope, revision, context } = useWorkspace(preview ? 'preview' : 'real')
  const [phase, setPhase] = useState<AddressPhase>('loading')
  const [draft, setDraft] = useState<AddressDraft>({ ...emptyDraft })
  const [baseline, setBaseline] = useState<AddressDraft>({ ...emptyDraft })
  const [errors, setErrors] = useState<ReturnType<typeof validateDraft>>({})
  const [notice, setNotice] = useState('')
  const mounted = useRef(true)
  const sequence = useRef(0)
  const saving = useRef(false)
  const request = useRef<{ id: string; draft: AddressDraft } | null>(null)
  const previousRevision = useRef(revision)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--addr-top': `${platformInfo.statusBarHeight || 0}px`, '--addr-unit': `${unit}px` } as CSSProperties

  const load = useCallback(async () => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    setPhase('loading'); setNotice(''); setErrors({})
    if (!scope.current || scope.current.workspace !== 'consumer') { setPhase('expired'); return }
    try {
      let initial: AddressDraft = { ...emptyDraft }
      if (mode === 'edit' && addressId) {
        const address: AddressView = await scope.run(undefined, () => repository.get(addressId))
        if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
        initial = {
          contactName: address.contactName, contactPhone: address.contactPhone,
          region: address.region, detail: address.detail, isDefault: address.isDefault,
        }
      }
      // 幂等恢复：上次保存未确认时按原 PUT/POST 载荷还原表单（同载荷重试同一 X-Request-Id）。
      const slot = mode === 'edit' && addressId ? `address:${addressId}` : 'address:create'
      const pending = !preview ? consumerApi.pendingCommand(slot) : null
      if (pending && pending.data) {
        const data = pending.data as Record<string, unknown>
        initial = {
          contactName: String(data.contactName || ''), contactPhone: String(data.contactPhone || ''),
          region: String(data.region || ''), detail: String(data.detail || ''),
          isDefault: data.isDefault === true,
        }
      }
      setDraft(initial); setBaseline(initial); request.current = null
      setPhase(pending ? 'save-error' : 'ready')
      if (pending) setNotice('上次保存结果尚未确认，请核对后重试原提交')
    } catch {
      if (mounted.current && current === sequence.current && currentRevision === scope.revision) setPhase('load-error')
    }
  }, [preview, mode, addressId, repository, scope])
  useEffect(() => {
    mounted.current = true
    void load()
    return () => { mounted.current = false; sequence.current++ }
  }, [load])
  useEffect(() => {
    if (previousRevision.current !== revision || !context || context.workspace !== 'consumer') {
      previousRevision.current = revision
      sequence.current++; request.current = null; saving.current = false
      setDraft({ ...emptyDraft }); setBaseline({ ...emptyDraft }); setNotice(''); setErrors({})
      setPhase('expired')
    }
  }, [revision, context])

  const fieldsLocked = saving.current || phase === 'saving'
  function edit<K extends keyof AddressDraft>(key: K, value: AddressDraft[K]) {
    if (fieldsLocked) return
    setDraft(old => ({ ...old, [key]: value })); setNotice(''); setErrors({})
  }
  async function save() {
    if (saving.current || !['ready', 'save-error'].includes(phase)) return
    const invalid = validateDraft(draft); setErrors(invalid)
    if (Object.keys(invalid).length) { setNotice(Object.values(invalid)[0]); return }
    saving.current = true; setPhase('saving'); setNotice('')
    const currentRevision = scope.revision
    if (!request.current || !sameDraft(request.current.draft, draft)) {
      // preview 事务身份；真实写路径由 ConsumerApi.write 分配 23号 X-Request-Id。
      request.current = { id: `preview-${Date.now()}-${++sequence.current}`, draft: { ...draft } }
    }
    const pending = request.current
    try {
      const saved = await scope.run(undefined, () => repository.save(mode === 'edit' && addressId ? addressId : null, pending.draft, pending.id))
      if (!mounted.current || currentRevision !== scope.revision) return
      const confirmed: AddressDraft = preview ? pending.draft : {
        contactName: saved.contactName, contactPhone: saved.contactPhone,
        region: saved.region, detail: saved.detail, isDefault: saved.isDefault,
      }
      setDraft(confirmed); setBaseline(confirmed); setPhase('ready'); setNotice(preview ? '预览数据已更新' : '已保存')
      request.current = null
      setTimeout(() => { if (mounted.current) void Taro.navigateBack().catch(() => { /* 已在首页栈底则停留 */ }) }, 400)
    } catch (error) {
      if (mounted.current && currentRevision === scope.revision) { setPhase('save-error'); setNotice(preview ? '保存失败，请重试；已填写内容保留' : addressWriteMessage(error)) }
    } finally { saving.current = false }
  }
  async function leave() {
    if (saving.current) return
    if (!sameDraft(draft, baseline)) {
      const result = await Taro.showModal({ title: '放弃修改？', content: '尚未保存的修改将不会保留。', confirmText: '放弃修改', cancelText: '继续编辑' })
      if (!result.confirm) return
    }
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: '/consumer/pages/mine/index' })
  }

  const editable = ['ready', 'saving', 'save-error'].includes(phase)
  return <View className={`addr-form-page${editable ? '' : ' addr-form-page-static'}`} style={style} onClick={() => void leave()}>
    <View className='addr-form-sheet' onClick={event => event.stopPropagation()}>
      <View className='addr-form-head'>
        <Text className='addr-form-title'>{mode === 'edit' ? '编辑地址' : '新增地址'}</Text>
        <Button id={`addr-form-close-${mode}`} className='addr-form-close' ariaLabel='关闭' hoverClass='none' onClick={() => void leave()}>×</Button>
      </View>
      <ScrollView className='addr-form-body' scrollY enhanced showScrollbar={false}>
        {phase === 'loading' && <View className='addr-form-state' role='status'><Text>正在加载地址…</Text></View>}
        {phase === 'expired' && <View className='addr-form-state' role='status'><Text id='addr-form-expired'>登录已失效，请重新登录后再试。</Text></View>}
        {phase === 'load-error' && <View className='addr-form-state' role='status'>
          <Text id='addr-form-error'>地址读取失败，请稍后重试。</Text>
          <View className='addr-form-state-action'><Button id='addr-form-retry' className='addr-form-state-btn' hoverClass='none' onClick={() => void load()}>重新加载</Button></View>
        </View>}
        {editable && <View className='addr-form-fields'>
          <View className='addr-form-field'>
            <Text className='addr-form-label'>收货人</Text>
            <Input id='addr-form-name' className={`addr-form-input${errors.contactName ? ' is-error' : ''}`} value={draft.contactName}
              maxlength={-1} disabled={fieldsLocked} ariaLabel='收货人' adjustPosition
              onInput={event => edit('contactName', event.detail.value)} />
          </View>
          <View className='addr-form-field'>
            <Text className='addr-form-label'>手机号</Text>
            <Input id='addr-form-phone' className={`addr-form-input${errors.contactPhone ? ' is-error' : ''}`} value={draft.contactPhone}
              type='number' maxlength={11} disabled={fieldsLocked} ariaLabel='手机号' adjustPosition
              onInput={event => edit('contactPhone', event.detail.value)} />
          </View>
          <View className='addr-form-field'>
            <Text className='addr-form-label'>所在地区</Text>
            <Input id='addr-form-region' className={`addr-form-input${errors.region ? ' is-error' : ''}`} value={draft.region}
              maxlength={-1} disabled={fieldsLocked} ariaLabel='所在地区' adjustPosition placeholder='省市区，如：上海市 浦东新区'
              onInput={event => edit('region', event.detail.value)} />
          </View>
          <View className='addr-form-field'>
            <Text className='addr-form-label'>详细地址</Text>
            <Textarea id='addr-form-detail' className={`addr-form-textarea${errors.detail ? ' is-error' : ''}`} value={draft.detail}
              maxlength={-1} disabled={fieldsLocked} ariaLabel='详细地址' adjustPosition showConfirmBar={false}
              placeholder='街道、门牌号、楼层等' onInput={event => edit('detail', event.detail.value)} />
          </View>
          <View className='addr-form-default'>
            <Text className='addr-form-default-label'>设为默认地址</Text>
            <Switch id='addr-form-default' checked={draft.isDefault} disabled={fieldsLocked} color='#5baae8'
              ariaLabel='设为默认地址' onChange={() => edit('isDefault', !draft.isDefault)} />
          </View>
          <Button id='addr-form-save' className='addr-form-save' hoverClass='none' disabled={phase === 'saving'}
            onClick={() => void save()}>{phase === 'saving' ? '保存中…' : '保存地址'}</Button>
          {notice && <Text id='addr-form-notice' className={`addr-form-notice${phase === 'save-error' ? ' is-error' : ''}`}>{notice}</Text>}
        </View>}
      </ScrollView>
    </View>
  </View>
}
