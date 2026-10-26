import { ConsumerPageLayout } from '../../components/page-layout'
import { switchConsumerTab } from '../../components/navigation/switch'
import { Button, Image, Input, Picker, Text, Textarea, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { realPetRepository } from '../../api/page-repository'
import {
  dewormingTypeLabels, emptyDewormDraft, emptyVaccineDraft, healthRecordErrorMessage,
  validateDewormDraft, validateVaccineDraft,
  type DewormDraft, type HealthRecordType, type VaccineDraft,
} from '../../pet/health-record'
import navBack from './assets/nav-back.png'
import './fonts.css'
import './record-form.css'

// 契约 68（2026-10-19 裁决回补，设计稿 96:2580 疫苗 / 96:3006 驱虫）：添加表单独立成页
// （address/new+edit 弹层成页先例；原稿为档案详情页上的面板形态，登记偏差）。
// 字段按 PRD 5.1.3 字段表：疫苗=名称/接种日期/针次?/下次接种日期?/备注?；驱虫=类型/驱虫日期/
// 产品?/下次驱虫日期?/备注?。设计稿「接种状态」chips 不入表单（读侧日期派生展示态，contract 68
// §字段登记）；驱虫类型按 PRD 三枚举全集（设计稿两枚举，登记偏差）。POST 幂等槽
// pet-health-record:{vaccine|deworm}:{petId}（23号 X-Request-Id，未确认提交重进还原重试）。
type Phase = 'ready' | 'saving' | 'save-error'

export type HealthRecordFormKind = Extract<HealthRecordType, 'VACCINATION' | 'DEWORMING'>

export function HealthRecordFormPage({ kind }: { kind: HealthRecordFormKind }) {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const petId = route.params.petId || ''
  const isVaccine = kind === 'VACCINATION'
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [phase, setPhase] = useState<Phase>('ready')
  const [vaccineDraft, setVaccineDraft] = useState<VaccineDraft>({ ...emptyVaccineDraft })
  const [dewormDraft, setDewormDraft] = useState<DewormDraft>({ ...emptyDewormDraft })
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [notice, setNotice] = useState('')
  const saving = useRef(false)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--pet-unit': `${unit}px` } as CSSProperties
  const today = todayISO()

  useEffect(() => {
    // 未确认提交重进还原（23号惯例，同 pet/address 表单）：幂等槽里有 pending 命令则回填。
    if (preview || !petId) return
    const pending = consumerApi.pendingCommand(`pet-health-record:${isVaccine ? 'vaccine' : 'deworm'}:${petId}`)
    if (!pending?.data) return
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
    setNotice('上次提交结果尚未确认，请重试原保存')
  }, [preview, petId, isVaccine])

  const leave = useCallback(async () => {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: `/consumer/pages/pet-archive/detail?petId=${encodeURIComponent(petId)}` })
  }, [petId])
  function editVaccine<K extends keyof VaccineDraft>(key: K, value: VaccineDraft[K]) {
    if (saving.current) return
    setVaccineDraft(old => ({ ...old, [key]: value })); setNotice(''); setErrors({})
  }
  function editDeworm<K extends keyof DewormDraft>(key: K, value: DewormDraft[K]) {
    if (saving.current) return
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
      await leave()
    } catch (error) {
      if (currentRevision !== scope.revision) return
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
  return <ConsumerPageLayout page='petDetail' unit={unit} navigation={preview ? { idPrefix: 'pet', disabled: phase === 'saving', onSelect: key => void switchConsumerTab(key) } : undefined} className='pet-page pet-record-form-page' style={style}>
    <View className='pet-status-area' />
    <View className='pet-record-header'>
      <Button id='pet-record-back' className='pet-record-back' ariaLabel='返回' onClick={() => void leave()}>
        <Image src={navBack} mode='scaleToFill' />
      </Button>
      <Text className='pet-record-title'>{title}</Text>
    </View>
    <View className='pet-record-body'>
      <View className='pet-record-panel'>
        {isVaccine && <>
          <Text className='pet-record-label'>疫苗名称</Text>
          <Input id='pet-record-vaccine-name' className='pet-record-input' type='text' value={vaccineDraft.vaccineName}
            placeholder='如：狂犬疫苗、犬六联疫苗' maxlength={50}
            onInput={event => editVaccine('vaccineName', event.detail.value)} />
          {errors.vaccineName && <Text className='pet-record-error'>{errors.vaccineName}</Text>}
          <Text className='pet-record-label'>接种日期</Text>
          <Picker id='pet-record-vaccine-date' mode='date' end={today} value={vaccineDraft.recordDate || today}
            onChange={event => editVaccine('recordDate', event.detail.value)}>
            <View className='pet-record-input pet-record-picker'>
              <Text className={vaccineDraft.recordDate ? '' : 'pet-record-placeholder'}>{vaccineDraft.recordDate || '请选择接种日期'}</Text>
            </View>
          </Picker>
          {errors.recordDate && <Text className='pet-record-error'>{errors.recordDate}</Text>}
          <Text className='pet-record-label'>接种针次（选填）</Text>
          <Input id='pet-record-vaccine-dose' className='pet-record-input' type='number' value={vaccineDraft.doseNumber}
            placeholder='第几针（1~20）' maxlength={2}
            onInput={event => editVaccine('doseNumber', event.detail.value)} />
          {errors.doseNumber && <Text className='pet-record-error'>{errors.doseNumber}</Text>}
          <Text className='pet-record-label'>下次接种日期（选填）</Text>
          <Picker id='pet-record-vaccine-next' mode='date' value={vaccineDraft.nextRecordDate || today}
            onChange={event => editVaccine('nextRecordDate', event.detail.value)}>
            <View className='pet-record-input pet-record-picker'>
              <Text className={vaccineDraft.nextRecordDate ? '' : 'pet-record-placeholder'}>{vaccineDraft.nextRecordDate || '请选择日期'}</Text>
            </View>
          </Picker>
          {errors.nextRecordDate && <Text className='pet-record-error'>{errors.nextRecordDate}</Text>}
        </>}
        {!isVaccine && <>
          <Text className='pet-record-label'>驱虫类型</Text>
          <View className='pet-record-chips'>
            {(Object.keys(dewormingTypeLabels) as (keyof typeof dewormingTypeLabels)[]).map(type =>
              <Button key={type} id={`pet-record-type-${type}`} hoverClass='none'
                className={`pet-record-chip${dewormDraft.dewormingType === type ? ' is-current' : ''}`}
                onClick={() => editDeworm('dewormingType', type)}>
                <Text>{dewormingTypeLabels[type]}</Text>
              </Button>)}
          </View>
          <Text className='pet-record-label'>驱虫日期</Text>
          <Picker id='pet-record-deworm-date' mode='date' end={today} value={dewormDraft.recordDate || today}
            onChange={event => editDeworm('recordDate', event.detail.value)}>
            <View className='pet-record-input pet-record-picker'>
              <Text className={dewormDraft.recordDate ? '' : 'pet-record-placeholder'}>{dewormDraft.recordDate || '请选择驱虫日期'}</Text>
            </View>
          </Picker>
          {errors.recordDate && <Text className='pet-record-error'>{errors.recordDate}</Text>}
          <Text className='pet-record-label'>驱虫产品（选填）</Text>
          <Input id='pet-record-deworm-product' className='pet-record-input' type='text' value={dewormDraft.productName}
            placeholder='如：拜宠清、福来恩' maxlength={50}
            onInput={event => editDeworm('productName', event.detail.value)} />
          {errors.productName && <Text className='pet-record-error'>{errors.productName}</Text>}
          <Text className='pet-record-label'>下次驱虫日期（选填）</Text>
          <Picker id='pet-record-deworm-next' mode='date' value={dewormDraft.nextRecordDate || today}
            onChange={event => editDeworm('nextRecordDate', event.detail.value)}>
            <View className='pet-record-input pet-record-picker'>
              <Text className={dewormDraft.nextRecordDate ? '' : 'pet-record-placeholder'}>{dewormDraft.nextRecordDate || '请选择日期'}</Text>
            </View>
          </Picker>
          {errors.nextRecordDate && <Text className='pet-record-error'>{errors.nextRecordDate}</Text>}
        </>}
        <Text className='pet-record-label'>备注（选填）</Text>
        <Textarea id='pet-record-note' className='pet-record-textarea' value={isVaccine ? vaccineDraft.note : dewormDraft.note}
          placeholder='随记录展示（200 字以内）' maxlength={200}
          onInput={event => (isVaccine ? editVaccine('note', event.detail.value) : editDeworm('note', event.detail.value))} />
        {errors.note && <Text className='pet-record-error'>{errors.note}</Text>}
        {notice && <Text id='pet-record-notice' className='pet-record-error'>{notice}</Text>}
        <View className='pet-record-actions'>
          <Button id='pet-record-cancel' className='pet-record-button pet-record-cancel' onClick={() => void leave()}>取消</Button>
          <Button id='pet-record-save' className='pet-record-button pet-record-save' disabled={phase === 'saving'} onClick={() => void save()}>
            {phase === 'saving' ? '保存中…' : '保存'}
          </Button>
        </View>
      </View>
      <View className='pet-record-tail'><Text>{preview ? '只读预览：本地样例数据，仅用于设计验收，不发起真实请求。' : '页面数据：真实接口（契约 68，添加幂等）。'}</Text></View>
    </View>
  </ConsumerPageLayout>
}

function todayISO() {
  const now = new Date()
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  const day = `${now.getDate()}`.padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}
