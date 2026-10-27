import { Button, Image, Input, Picker, ScrollView, Text, Textarea, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useEffect, useRef, useState } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { realPetRepository } from '../../api/page-repository'
import {
  dewormingTypeLabels, emptyDewormDraft, emptyVaccineDraft, healthRecordErrorMessage,
  validateDewormDraft, validateVaccineDraft,
  type DewormDraft, type HealthRecordType, type VaccineDraft,
} from '../../pet/health-record'
import sheetClose from './assets/sheet-close.png'
import iconDate from './assets/icon-date.png'
import './record-form.css'

// 契约 68（2026-10-19 裁决回补，设计稿 96:2580/96:3006）：添加表单是档案详情页上的底部
// 半框弹层（96:2841/96:2842 面板 + 96:2848 标题 + 96:3001/3003 行动按钮），不是三级页面。
// 字段按 PRD 5.1.3：疫苗=名称/接种日期/针次?/下次接种日期?/备注?；驱虫=类型/日期/产品?/下次
// 日期?/备注?。设计稿「接种状态」chips 是 nextRecordDate 派生的读侧展示态（contract 68 §字段），
// 不作为可提交表单字段。POST 幂等槽 pet-health-record:{vaccine|deworm}:{petId}。
type Phase = 'ready' | 'saving' | 'save-error'

export type HealthRecordFormKind = Extract<HealthRecordType, 'VACCINATION' | 'DEWORMING'>

type Props = {
  kind: HealthRecordFormKind
  petId: string
  preview: boolean
  onClose: () => void
  onSaved: () => void
}

export function HealthRecordFormSheet({ kind, petId, preview, onClose, onSaved }: Props) {
  const isVaccine = kind === 'VACCINATION'
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [phase, setPhase] = useState<Phase>('ready')
  const [vaccineDraft, setVaccineDraft] = useState<VaccineDraft>({ ...emptyVaccineDraft })
  const [dewormDraft, setDewormDraft] = useState<DewormDraft>({ ...emptyDewormDraft })
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [notice, setNotice] = useState('')
  const [pendingLocked, setPendingLocked] = useState(false)
  const saving = useRef(false)
  const slot = `pet-health-record:${isVaccine ? 'vaccine' : 'deworm'}:${petId}`
  const [windowInfo] = useState(() => Taro.getWindowInfo())
  const safeBottom = Math.max(0, windowInfo.screenHeight - (windowInfo.safeArea?.bottom ?? windowInfo.screenHeight))
  const today = todayISO()

  useEffect(() => {
    // 未确认提交重进还原（23号惯例）：幂等槽里有 pending 命令则回填。
    if (preview || !petId) return
    const pending = consumerApi.pendingCommand(slot)
    if (!pending?.data) { setPendingLocked(false); return }
    const body = pending.data as Record<string, unknown>
    if (isVaccine) {
      setVaccineDraft({
        vaccineName: String(body.vaccineName || ''),
        recordDate: String(body.recordDate || ''),
        doseNumber: body.doseNumber == null ? '' : String(body.doseNumber),
        nextRecordDate: String(body.nextRecordDate || ''),
        note: String(body.note || ''),
      })
    } else {
      setDewormDraft({
        dewormingType: (body.dewormingType as DewormDraft['dewormingType']) || 'EXTERNAL',
        recordDate: String(body.recordDate || ''),
        productName: String(body.productName || ''),
        nextRecordDate: String(body.nextRecordDate || ''),
        note: String(body.note || ''),
      })
    }
    setPhase('save-error')
    setPendingLocked(true)
    setNotice('')
  }, [preview, petId, isVaccine, slot])

  function close() {
    if (saving.current) return
    onClose()
  }
  function editVaccine<K extends keyof VaccineDraft>(key: K, value: VaccineDraft[K]) {
    if (saving.current) return
    if (pendingLocked) { setNotice('上次提交结果尚未确认，请直接点击保存重试'); return }
    setVaccineDraft(old => ({ ...old, [key]: value })); setNotice(''); setErrors({})
  }
  function editDeworm<K extends keyof DewormDraft>(key: K, value: DewormDraft[K]) {
    if (saving.current) return
    if (pendingLocked) { setNotice('上次提交结果尚未确认，请直接点击保存重试'); return }
    setDewormDraft(old => ({ ...old, [key]: value })); setNotice(''); setErrors({})
  }
  async function save() {
    if (saving.current || !['ready', 'save-error'].includes(phase)) return
    const body = isVaccine ? vaccineBody() : dewormBody()
    const invalid = isVaccine ? validateVaccineDraft(vaccineDraft, today) : validateDewormDraft(dewormDraft, today)
    setErrors(invalid)
    if (Object.keys(invalid).length) { setNotice(Object.values(invalid)[0]); return }
    if (!petId) { setNotice('页面链接缺少宠物信息，请从档案详情进入'); return }
    if (preview) { void Taro.showModal({ title: '预览模式', content: 'preview=1 只做设计验收，不发起真实请求。', showCancel: false }); return }
    saving.current = true; setPhase('saving'); setNotice('')
    const currentRevision = scope.revision
    try {
      const repository = realPetRepository()
      if (isVaccine) await scope.run(undefined, () => repository.createVaccinationRecord(petId, body))
      else await scope.run(undefined, () => repository.createDewormingRecord(petId, body))
      if (currentRevision !== scope.revision) return
      void Taro.showToast({ title: '已保存', icon: 'success' })
      onSaved()
    } catch (error) {
      if (currentRevision !== scope.revision) return
      // 明确拒绝会清掉幂等槽；此时允许用户修改内容后重新发起。未知结果仍锁定原内容重试。
      if (!consumerApi.pendingCommand(slot)) setPendingLocked(false)
      saving.current = false; setPhase('save-error'); setNotice(healthRecordErrorMessage(error))
    }
  }
  function vaccineBody(): Record<string, unknown> {
    const body: Record<string, unknown> = {
      recordDate: vaccineDraft.recordDate,
      vaccineName: vaccineDraft.vaccineName.trim(),
    }
    if (vaccineDraft.doseNumber.trim() !== '') body.doseNumber = Number(vaccineDraft.doseNumber)
    if (vaccineDraft.nextRecordDate !== '') body.nextRecordDate = vaccineDraft.nextRecordDate
    if (vaccineDraft.note.trim() !== '') body.note = vaccineDraft.note.trim()
    return body
  }
  function dewormBody(): Record<string, unknown> {
    const body: Record<string, unknown> = {
      recordDate: dewormDraft.recordDate,
      dewormingType: dewormDraft.dewormingType,
    }
    if (dewormDraft.productName.trim() !== '') body.productName = dewormDraft.productName.trim()
    if (dewormDraft.nextRecordDate !== '') body.nextRecordDate = dewormDraft.nextRecordDate
    if (dewormDraft.note.trim() !== '') body.note = dewormDraft.note.trim()
    return body
  }

  const title = isVaccine ? '添加疫苗记录' : '添加驱虫记录'
  return <View className='pet-record-sheet-mask' catchMove onClick={close}>
    <View className='pet-record-sheet' style={{ height: `calc(var(--pet-unit) * 494 + ${safeBottom}px)` }} catchMove onClick={event => event.stopPropagation()}>
      <View className='pet-record-sheet-head'>
        <Text className='pet-record-sheet-title'>{title}</Text>
        <Button id='pet-record-close' className='pet-record-sheet-close' ariaLabel='关闭' disabled={phase === 'saving'} onClick={close}>
          <Image className='pet-record-sheet-closeicon' src={sheetClose} mode='aspectFit' />
        </Button>
      </View>
      <ScrollView className='pet-record-sheet-body' scrollY enhanced showScrollbar={false}>
        <View className='pet-record-sheet-fields'>
          {pendingLocked && <Text className='pet-record-sheet-error pet-record-sheet-pending'>
            上次提交结果尚未确认，请直接点击「保存」重试；重试不会重复添加。
          </Text>}
          {isVaccine && <>
            <View className='pet-record-sheet-field'>
              <Text className='pet-record-sheet-label'>疫苗名称</Text>
              <Input id='pet-record-vaccine-name' className='pet-record-sheet-input' type='text' value={vaccineDraft.vaccineName}
                placeholder='如：狂犬疫苗、犬六联疫苗' maxlength={50} placeholderClass='pet-record-sheet-placeholder'
                onInput={event => editVaccine('vaccineName', event.detail.value)} />
              {errors.vaccineName && <Text className='pet-record-sheet-error'>{errors.vaccineName}</Text>}
            </View>
            <View className='pet-record-sheet-field'>
              <Text className='pet-record-sheet-label'>接种日期</Text>
              <Picker id='pet-record-vaccine-date' mode='date' end={today} value={vaccineDraft.recordDate || today}
                onChange={event => editVaccine('recordDate', event.detail.value)}>
                <View className='pet-record-sheet-input pet-record-sheet-picker'>
                  <Text className={vaccineDraft.recordDate ? '' : 'pet-record-sheet-placeholder'}>{vaccineDraft.recordDate || '-/-/-'}</Text>
                  <Image className='pet-record-sheet-dateicon' src={iconDate} mode='scaleToFill' />
                </View>
              </Picker>
              {errors.recordDate && <Text className='pet-record-sheet-error'>{errors.recordDate}</Text>}
            </View>
            <View className='pet-record-sheet-field'>
              <Text className='pet-record-sheet-label'>接种针次（选填）</Text>
              <Input id='pet-record-vaccine-dose' className='pet-record-sheet-input' type='number' value={vaccineDraft.doseNumber}
                placeholder='第几针（1~20）' maxlength={2} placeholderClass='pet-record-sheet-placeholder'
                onInput={event => editVaccine('doseNumber', event.detail.value)} />
              {errors.doseNumber && <Text className='pet-record-sheet-error'>{errors.doseNumber}</Text>}
            </View>
            <View className='pet-record-sheet-field'>
              <Text className='pet-record-sheet-label'>下次接种日期（选填）</Text>
              <Picker id='pet-record-vaccine-next' mode='date' value={vaccineDraft.nextRecordDate || today}
                onChange={event => editVaccine('nextRecordDate', event.detail.value)}>
                <View className='pet-record-sheet-input pet-record-sheet-picker'>
                  <Text className={vaccineDraft.nextRecordDate ? '' : 'pet-record-sheet-placeholder'}>{vaccineDraft.nextRecordDate || '-/-/-'}</Text>
                  <Image className='pet-record-sheet-dateicon' src={iconDate} mode='scaleToFill' />
                </View>
              </Picker>
              {errors.nextRecordDate && <Text className='pet-record-sheet-error'>{errors.nextRecordDate}</Text>}
            </View>
          </>}
          {!isVaccine && <>
            <View className='pet-record-sheet-field'>
              <Text className='pet-record-sheet-label'>驱虫类型</Text>
              <View className='pet-record-sheet-chips'>
                {(Object.keys(dewormingTypeLabels) as (keyof typeof dewormingTypeLabels)[]).map(type =>
                  <Button key={type} id={`pet-record-type-${type}`} hoverClass='none'
                    className={`pet-record-sheet-chip${dewormDraft.dewormingType === type ? ' is-current' : ''}`}
                    onClick={() => editDeworm('dewormingType', type)}>
                    <Text>{dewormingTypeLabels[type]}</Text>
                  </Button>)}
              </View>
            </View>
            <View className='pet-record-sheet-field'>
              <Text className='pet-record-sheet-label'>驱虫日期</Text>
              <Picker id='pet-record-deworm-date' mode='date' end={today} value={dewormDraft.recordDate || today}
                onChange={event => editDeworm('recordDate', event.detail.value)}>
                <View className='pet-record-sheet-input pet-record-sheet-picker'>
                  <Text className={dewormDraft.recordDate ? '' : 'pet-record-sheet-placeholder'}>{dewormDraft.recordDate || '-/-/-'}</Text>
                  <Image className='pet-record-sheet-dateicon' src={iconDate} mode='scaleToFill' />
                </View>
              </Picker>
              {errors.recordDate && <Text className='pet-record-sheet-error'>{errors.recordDate}</Text>}
            </View>
            <View className='pet-record-sheet-field'>
              <Text className='pet-record-sheet-label'>驱虫产品（选填）</Text>
              <Input id='pet-record-deworm-product' className='pet-record-sheet-input' type='text' value={dewormDraft.productName}
                placeholder='如：拜宠清、福来恩' maxlength={50} placeholderClass='pet-record-sheet-placeholder'
                onInput={event => editDeworm('productName', event.detail.value)} />
              {errors.productName && <Text className='pet-record-sheet-error'>{errors.productName}</Text>}
            </View>
            <View className='pet-record-sheet-field'>
              <Text className='pet-record-sheet-label'>下次驱虫日期（选填）</Text>
              <Picker id='pet-record-deworm-next' mode='date' value={dewormDraft.nextRecordDate || today}
                onChange={event => editDeworm('nextRecordDate', event.detail.value)}>
                <View className='pet-record-sheet-input pet-record-sheet-picker'>
                  <Text className={dewormDraft.nextRecordDate ? '' : 'pet-record-sheet-placeholder'}>{dewormDraft.nextRecordDate || '-/-/-'}</Text>
                  <Image className='pet-record-sheet-dateicon' src={iconDate} mode='scaleToFill' />
                </View>
              </Picker>
              {errors.nextRecordDate && <Text className='pet-record-sheet-error'>{errors.nextRecordDate}</Text>}
            </View>
          </>}
          <View className='pet-record-sheet-field'>
            <Text className='pet-record-sheet-label'>备注（选填）</Text>
            <Textarea id='pet-record-note' className='pet-record-sheet-textarea' value={isVaccine ? vaccineDraft.note : dewormDraft.note}
              placeholder='随记录展示（200 字以内）' maxlength={200} placeholderClass='pet-record-sheet-placeholder'
              onInput={event => (isVaccine ? editVaccine('note', event.detail.value) : editDeworm('note', event.detail.value))} />
            {errors.note && <Text className='pet-record-sheet-error'>{errors.note}</Text>}
          </View>
          {notice && <Text id='pet-record-notice' className='pet-record-sheet-error pet-record-sheet-notice'>{notice}</Text>}
        </View>
      </ScrollView>
      <View className='pet-record-sheet-actions'>
        <Button id='pet-record-cancel' className='pet-record-sheet-button pet-record-sheet-cancel' disabled={phase === 'saving'} onClick={close}>取消</Button>
        <Button id='pet-record-save' className='pet-record-sheet-button pet-record-sheet-save' disabled={phase === 'saving'} onClick={() => void save()}>
          {phase === 'saving' ? '保存中…' : '保存'}
        </Button>
      </View>
    </View>
  </View>
}

function todayISO() {
  const now = new Date()
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  const day = `${now.getDate()}`.padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}
