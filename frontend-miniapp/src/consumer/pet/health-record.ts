// 宠物健康记录（疫苗/驱虫）C 端模型——契约 68（2026-10-19 裁决回补，推翻登记表 §4.11 原范围排除）。
// 解码沿仓库严格 exact-key 惯例：PetHealthRecordData 原始事实投影；设计稿「接种状态」chips 为
// 读侧日期派生的展示态（contract 68 §字段：不入库不进契约），展示派生归本文件 statusLabel。
import { ApiError } from '../../shared/request'

export type HealthRecordType = 'VACCINATION' | 'DEWORMING'
export type DewormingType = 'EXTERNAL' | 'INTERNAL' | 'INTERNAL_EXTERNAL'

export type HealthRecordView = Readonly<{
  recordId: string
  petId: string
  recordType: HealthRecordType
  recordDate: string
  vaccineName: string | null
  doseNumber: number | null
  dewormingType: DewormingType | null
  productName: string | null
  nextRecordDate: string | null
  note: string | null
  status: string
}>

const invalid = (): never => { throw new Error('INVALID_RESPONSE') }
const objectLike = (value: unknown): Record<string, any> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid()
  return value as Record<string, any>
}
const isId = (value: any): string => {
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,18}(?![\s\S])/.test(value) || BigInt(value) > 9223372036854775807n) invalid()
  return value
}
const isoDate = (value: any): string => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value) || !Number.isFinite(Date.parse(value))) invalid()
  return value
}
const textOrNull = (value: any, max: number): string | null => {
  if (value === null) return null
  if (typeof value !== 'string' || value.length < 1 || [...value].length > max) invalid()
  return value
}
const oneOf = <T extends string>(value: any, values: readonly T[]): T => {
  if (typeof value !== 'string' || !(values as readonly string[]).includes(value)) invalid()
  return value as T
}

const recordKeys = ['recordId', 'petId', 'recordType', 'recordDate', 'vaccineName', 'doseNumber',
  'dewormingType', 'productName', 'nextRecordDate', 'note', 'status']
export function decodeHealthRecord(value: unknown): HealthRecordView {
  const v = objectLike(value)
  for (const key of Object.keys(v)) if (!recordKeys.includes(key)) invalid()
  const dose = v.doseNumber
  return {
    recordId: isId(v.recordId),
    petId: isId(v.petId),
    recordType: oneOf(v.recordType, ['VACCINATION', 'DEWORMING'] as const),
    recordDate: isoDate(v.recordDate),
    vaccineName: textOrNull(v.vaccineName, 50),
    doseNumber: dose === null || dose === undefined ? null
      : Number.isInteger(dose) && dose >= 1 && dose <= 20 ? dose : invalid(),
    dewormingType: v.dewormingType === null || v.dewormingType === undefined ? null
      : oneOf(v.dewormingType, ['EXTERNAL', 'INTERNAL', 'INTERNAL_EXTERNAL'] as const),
    productName: textOrNull(v.productName, 50),
    nextRecordDate: v.nextRecordDate === null || v.nextRecordDate === undefined ? null : isoDate(v.nextRecordDate),
    note: textOrNull(v.note, 200),
    status: oneOf(v.status, ['ACTIVE'] as const),
  }
}

export function decodeHealthRecordList(value: unknown): HealthRecordView[] {
  if (!Array.isArray(value)) invalid()
  return (value as unknown[]).map(decodeHealthRecord)
}

// POST 回执（契约 68 §路由：201 首次 / 200 同 requestId 重放）只含 {petId,recordId,recordType,status}，
// 不是完整记录投影；页面保存后由 onSaved 重新 GET 列表，因此这里不能复用 decodeHealthRecord。
export type HealthRecordReceipt = Readonly<{
  recordId: string
  petId: string
  recordType: HealthRecordType
  status: 'ACTIVE'
}>
const receiptKeys = ['recordId', 'petId', 'recordType', 'status']
export function decodeHealthRecordReceipt(value: unknown): HealthRecordReceipt {
  const v = objectLike(value)
  for (const key of Object.keys(v)) if (!receiptKeys.includes(key)) invalid()
  if (Object.keys(v).length !== receiptKeys.length) invalid()
  return {
    recordId: isId(v.recordId),
    petId: isId(v.petId),
    recordType: oneOf(v.recordType, ['VACCINATION', 'DEWORMING'] as const),
    status: oneOf(v.status, ['ACTIVE'] as const),
  }
}

// ---- 展示派生（contract 68：设计稿 96:2580 状态 chips = 日期派生展示态，非契约字段） ----

/** 疫苗行状态：无下次日期=已接种；下次日期已过=待接续；未来 30 天内=即将到期；其余=已接种。 */
export function vaccineStatusLabel(record: HealthRecordView, today: string): string {
  if (record.nextRecordDate === null) return '已接种'
  if (record.nextRecordDate < today) return '待接续'
  const due = Date.parse(record.nextRecordDate)
  const now = Date.parse(today)
  if (Number.isFinite(due) && Number.isFinite(now) && due - now <= 30 * 24 * 60 * 60 * 1000) return '即将到期'
  return '已接种'
}

export const dewormingTypeLabels: Record<DewormingType, string> = {
  EXTERNAL: '体外驱虫', INTERNAL: '体内驱虫', INTERNAL_EXTERNAL: '内外同驱',
}

/** 疫苗行日期行文案（设计稿 78:3076 记录行词汇：接种 yyyy-MM-dd · 下次 yyyy-MM-dd）。 */
export function vaccineDateLine(record: HealthRecordView): string {
  return record.nextRecordDate
    ? `接种 ${record.recordDate} · 下次 ${record.nextRecordDate}`
    : `接种 ${record.recordDate}`
}

/** 驱虫行：标题=类型文案，brand=产品名（无产品时并入日期行）。 */
export function dewormingDateLine(record: HealthRecordView): string {
  return record.nextRecordDate
    ? `驱虫 ${record.recordDate} · 下次 ${record.nextRecordDate}`
    : `驱虫 ${record.recordDate}`
}

// ---- 表单（PRD 5.1.3 字段表校验；contract 68 §字段校验同源） ----

export type VaccineDraft = { vaccineName: string; recordDate: string; doseNumber: string; nextRecordDate: string; note: string }
export type DewormDraft = { dewormingType: DewormingType; recordDate: string; productName: string; nextRecordDate: string; note: string }

export const emptyVaccineDraft: VaccineDraft = { vaccineName: '', recordDate: '', doseNumber: '', nextRecordDate: '', note: '' }
export const emptyDewormDraft: DewormDraft = { dewormingType: 'EXTERNAL', recordDate: '', productName: '', nextRecordDate: '', note: '' }

export function validateVaccineDraft(draft: VaccineDraft, today: string): Record<string, string> {
  const errors: Record<string, string> = {}
  const name = draft.vaccineName.trim()
  if (name.length < 1 || [...name].length > 50) errors.vaccineName = '请填写疫苗名称（50 字以内）'
  if (!/^\d{4}-\d{2}-\d{2}$/.test(draft.recordDate)) errors.recordDate = '请选择接种日期'
  else if (draft.recordDate > today) errors.recordDate = '接种日期不得晚于今天'
  if (draft.doseNumber.trim() !== '') {
    const dose = Number(draft.doseNumber)
    if (!Number.isInteger(dose) || dose < 1 || dose > 20) errors.doseNumber = '针次为 1~20 的整数'
  }
  if (draft.nextRecordDate !== '' && draft.recordDate !== '' && draft.nextRecordDate < draft.recordDate) {
    errors.nextRecordDate = '下次接种日期不得早于接种日期'
  }
  if ([...draft.note].length > 200) errors.note = '备注不超过 200 字'
  return errors
}

export function validateDewormDraft(draft: DewormDraft, today: string): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!/^\d{4}-\d{2}-\d{2}$/.test(draft.recordDate)) errors.recordDate = '请选择驱虫日期'
  else if (draft.recordDate > today) errors.recordDate = '驱虫日期不得晚于今天'
  const product = draft.productName.trim()
  if (product.length > 50) errors.productName = '产品名称不超过 50 字'
  if (draft.nextRecordDate !== '' && draft.recordDate !== '' && draft.nextRecordDate < draft.recordDate) {
    errors.nextRecordDate = '下次驱虫日期不得早于驱虫日期'
  }
  if ([...draft.note].length > 200) errors.note = '备注不超过 200 字'
  return errors
}

/** 写失败的用户文案（错误码 → 页面语；与 68号错误映射同源）。 */
export function healthRecordErrorMessage(error: unknown): string {
  const code = (error as ApiError)?.code
  if (code === 'PET_HEALTH_RECORD_LIMIT_EXCEEDED') return '该记录类型已达上限（200 条），暂无法继续添加'
  if (code === 'IDEMPOTENCY_KEY_CONFLICT') return '提交参数与上次不一致，请重试'
  if (code === 'PET_NOT_FOUND') return '宠物不存在或已删除'
  if (code === 'USER_FROZEN') return '账号不可用，无法保存'
  return '保存失败，请稍后重试'
}
