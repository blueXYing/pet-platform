// View state only, mirroring the approved PetView contract (CCR-W2-API-001):
// IDs are String, weightKg is a two-decimal decimal string, age is derived from birthDate.
export type PetType = 'DOG' | 'CAT' | 'OTHER'
export type PetSex = 'MALE' | 'FEMALE' | 'UNKNOWN'
export type SterilizationStatus = 'INTACT' | 'NEUTERED' | 'UNKNOWN'
export type VaccineStatus = 'NONE' | 'PARTIAL' | 'COMPLETE' | 'UNKNOWN'
export type PetStatus = 'ACTIVE' | 'DISABLED'
export type PetView = Readonly<{
  petId: string; name: string; petType: PetType; breedName: string | null
  birthDate: string | null; sex: PetSex; weightKg: string | null
  sterilizationStatus: SterilizationStatus | null; vaccineStatus: VaccineStatus | null
  healthNote: string | null; avatarUrl: string | null; isDefault: boolean; status: PetStatus
}>
export type PetDraft = Readonly<{
  name: string; breedName: string; birthDate: string; sex: PetSex
  weightInput: string; healthNote: string
}>
export type PetPhase = 'loading' | 'ready' | 'saving' | 'load-error' | 'save-error' | 'expired' | 'unavailable'
export const codePointLength = (value: string) => Array.from(value).length
export const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/
export function isCalendarDate(value: string): boolean {
  if (!ISO_DATE.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value
}

// The form input keeps the designed "28.5kg" text shape; the contract value is a two-decimal string.
export function weightInputToContract(input: string): string | null {
  const value = input.trim().toLowerCase().replace(/kg$/, '').trim()
  if (!/^\d{1,3}(\.\d{1,2})?$/.test(value)) return null
  const weight = Number(value)
  if (!Number.isFinite(weight) || weight < 0.01 || weight > 999.99) return null
  return weight.toFixed(2)
}
export function weightContractToInput(weightKg: string | null): string {
  if (!weightKg) return ''
  const trimmed = weightKg.replace(/0+$/, '').replace(/\.$/, '')
  return `${trimmed}kg`
}
export function formatWeightDisplay(weightKg: string | null): string {
  if (!weightKg) return ''
  return weightContractToInput(weightKg)
}
// Contract decision 3: age is derived on the client from birthDate, never stored.
export function deriveAgeLabel(birthDate: string | null, todayISO: string): string {
  if (!birthDate || !isCalendarDate(birthDate) || !isCalendarDate(todayISO)) return ''
  if (birthDate > todayISO) return ''
  const birth = birthDate.split('-').map(Number) as [number, number, number]
  const today = todayISO.split('-').map(Number) as [number, number, number]
  let years = today[0] - birth[0]
  const beforeBirthday = today[1] < birth[1] || (today[1] === birth[1] && today[2] < birth[2])
  if (beforeBirthday) years -= 1
  return years >= 1 ? `${years}岁` : '未满1岁'
}
export const sexLabel = (sex: PetSex) => (sex === 'MALE' ? '弟弟' : sex === 'FEMALE' ? '妹妹' : '')
export function breedAgeLine(pet: PetView, todayISO: string): string {
  return [pet.breedName || '', deriveAgeLabel(pet.birthDate, todayISO)].filter(Boolean).join(' · ')
}
export type PetDraftErrors = Partial<Record<'name' | 'breedName' | 'birthDate' | 'weightInput' | 'healthNote' | 'sex', string>>
export function validateDraft(draft: PetDraft, todayISO: string): PetDraftErrors {
  const errors: PetDraftErrors = {}
  if (!draft.name.trim()) errors.name = '请填写宠物名字'
  else if (draft.name !== draft.name.trim()) errors.name = '名字首尾不能包含空格'
  else if (codePointLength(draft.name) > 64) errors.name = '名字最多64字'
  if (codePointLength(draft.breedName) > 64) errors.breedName = '品种最多64字'
  if (draft.birthDate) {
    if (!isCalendarDate(draft.birthDate)) errors.birthDate = '请选择有效的出生日期'
    else if (draft.birthDate > todayISO) errors.birthDate = '出生日期不能晚于今天'
  }
  if (draft.weightInput.trim() && !weightInputToContract(draft.weightInput)) errors.weightInput = '体重格式应如28.5kg'
  if (codePointLength(draft.healthNote) > 1000) errors.healthNote = '健康备注最多1000字'
  return errors
}
export function sameDraft(a: PetDraft, b: PetDraft) {
  return a.name === b.name && a.breedName === b.breedName && a.birthDate === b.birthDate && a.sex === b.sex && a.weightInput === b.weightInput && a.healthNote === b.healthNote
}
export function draftToContract(draft: PetDraft): PetView {
  return {
    petId: '', name: draft.name, petType: 'OTHER', breedName: draft.breedName.trim() || null,
    birthDate: draft.birthDate || null, sex: draft.sex, weightKg: draft.weightInput.trim() ? weightInputToContract(draft.weightInput) : null,
    sterilizationStatus: null, vaccineStatus: null, healthNote: draft.healthNote.trim() || null,
    avatarUrl: null, isDefault: false, status: 'ACTIVE',
  }
}

export type PreviewScenario =
  | 'normal' | 'load-error' | 'save-error' | 'expired'
  | 'list-empty' | 'form-brother' | 'form-sister'
export const isPreviewScenario = (value?: string): value is PreviewScenario =>
  ['normal', 'load-error', 'save-error', 'expired', 'list-empty', 'form-brother', 'form-sister'].includes(value || '')

// Fixture pets follow the approved PetView contract exactly; photos come from local design cutouts by petId.
export const fixturePets: PetView[] = [
  {
    petId: '30001', name: '豆豆', petType: 'DOG', breedName: '金毛寻回犬', birthDate: '2024-06-18',
    sex: 'MALE', weightKg: '28.50', sterilizationStatus: 'NEUTERED', vaccineStatus: 'COMPLETE',
    healthNote: '性格温顺粘人，喜欢球类玩具。对鸡肉不过敏，注意控制零食量。', avatarUrl: null, isDefault: true, status: 'ACTIVE',
  },
  {
    petId: '30002', name: '咪咪', petType: 'CAT', breedName: '英国短毛猫', birthDate: '2025-03-10',
    sex: 'FEMALE', weightKg: '4.20', sterilizationStatus: 'NEUTERED', vaccineStatus: 'COMPLETE',
    healthNote: '', avatarUrl: null, isDefault: false, status: 'ACTIVE',
  },
]

// Design-sample copy that has NO approved contract field yet (vaccine/deworming record names and
// dates, chip number). Rendered for visual fidelity only; never submitted as pet data. Registered
// in HANDOFF.md as a contract gap pending a supplement.
export const designSamples = {
  listTags: {
    '30001': { pill: 'vaccine', name: '犬窝咳疫苗', date: '疫苗 · 2026-10-08' },
    '30002': { pill: 'deworm', name: '拜耳内虫逃', date: '驱虫 · 2026-08-01' },
  } as Record<string, { pill: 'vaccine' | 'deworm'; name: string; date: string }>,
  chipNumber: '900001234567890',
  vaccineRecords: [
    { name: '狂犬疫苗', date: '接种 2026-04-10 · 下次 2027-04-10', status: '已接种' },
    { name: '犬六联疫苗', date: '接种 2026-02-15 · 下次 2026-08-15', status: '已接种' },
    { name: '犬窝咳疫苗', date: '接种 2025-10-08 · 下次 2026-10-08', status: '即将到期' },
  ],
  dewormRecords: [
    { name: '体内驱虫', brand: '拜宠清', date: '驱虫 2026-06-01 · 下次 2026-09-01' },
    { name: '体外驱虫', brand: '福来恩', date: '驱虫 2026-06-01 · 下次 2026-09-01' },
  ],
}
export const formFixture: PetDraft = {
  name: '豆豆', breedName: '金毛寻回犬', birthDate: '', sex: 'UNKNOWN',
  weightInput: '28.5kg', healthNote: '性格温顺粘人，喜欢球类玩具。对鸡肉不过敏，注意控制零食量。',
}
export const emptyDraft: PetDraft = { name: '', breedName: '', birthDate: '', sex: 'UNKNOWN', weightInput: '', healthNote: '' }

// These visual-only records belong to the specific source fixture, never every pet.
// This is NOT a new HTTP DTO: record/chip contracts still require CCR approval.
export function previewSupplement(petId?: string) {
  return {
    tag: petId ? designSamples.listTags[petId] : undefined,
    chipNumber: petId === '30001' ? designSamples.chipNumber : null,
    vaccineRecords: petId === '30001' ? designSamples.vaccineRecords : [],
    dewormRecords: petId === '30001' ? designSamples.dewormRecords : [],
  }
}

// Share preview mutations across pages, but never across workspace revisions.
const previewStores = new WeakMap<object, { revision: number; repositories: Map<PreviewScenario, PreviewPetRepository> }>()
export function previewRepository(scope: { revision: number }, scenario: PreviewScenario): PreviewPetRepository {
  let entry = previewStores.get(scope)
  if (!entry || entry.revision !== scope.revision) {
    entry = { revision: scope.revision, repositories: new Map() }
    previewStores.set(scope, entry)
  }
  let repository = entry.repositories.get(scenario)
  if (!repository) {
    repository = new PreviewPetRepository(scenario === 'list-empty' ? [] : fixturePets, scenario)
    entry.repositories.set(scenario, repository)
  }
  return repository
}

// Explicit preview repository: no network, no session, no durable user data.
export class PreviewPetRepository {
  private pets: PetView[]
  private failLoad: boolean
  private failSave: boolean
  private receipts = new Map<string, { request: object; value: object }>()
  private sequence = 0
  constructor(initial: PetView[] = fixturePets, scenario: PreviewScenario = 'normal', private pause: () => Promise<void> = async () => {}) {
    this.pets = initial.map(pet => ({ ...pet }))
    this.failLoad = scenario === 'load-error'
    this.failSave = scenario === 'save-error'
  }
  async load(): Promise<PetView[]> {
    await this.pause()
    if (this.failLoad) { this.failLoad = false; throw new Error('PREVIEW_LOAD_FAILED') }
    return this.pets.filter(pet => pet.status === 'ACTIVE').map(pet => ({ ...pet }))
  }
  async get(petId: string): Promise<PetView> {
    const found = (await this.load()).find(pet => pet.petId === petId)
    if (!found) throw new Error('PET_NOT_FOUND')
    return found
  }
  async save(petId: string | null, draft: PetDraft, requestId: string): Promise<PetView> {
    await this.pause()
    if (Object.keys(validateDraft(draft, '9999-12-31')).length) throw new Error('INVALID_DRAFT')
    const request = { petId, draft: { ...draft } }
    const receipt = this.receipts.get(requestId)
    if (receipt) {
      if (JSON.stringify(receipt.request) !== JSON.stringify(request)) throw new Error('REQUEST_CONFLICT')
      return receipt.value as PetView
    }
    if (this.failSave) { this.failSave = false; throw new Error('PREVIEW_SAVE_FAILED') }
    const contract = draftToContract(draft)
    let value: PetView
    if (petId) {
      const index = this.pets.findIndex(pet => pet.petId === petId && pet.status === 'ACTIVE')
      if (index < 0) throw new Error('PET_NOT_FOUND')
      // The designed form edits only draft fields; contract-managed fields keep their stored values.
      value = {
        ...this.pets[index], ...contract, petId, petType: this.pets[index].petType,
        sterilizationStatus: this.pets[index].sterilizationStatus, vaccineStatus: this.pets[index].vaccineStatus,
        avatarUrl: this.pets[index].avatarUrl, isDefault: this.pets[index].isDefault, status: 'ACTIVE',
      }
      this.pets[index] = value
    } else {
      // The designed form has no petType chooser; creations stay OTHER until the contract gap is closed (HANDOFF).
      value = { ...contract, petId: `preview-${++this.sequence}` }
      this.pets.push(value)
    }
    this.receipts.set(requestId, { request, value: { ...value } })
    return { ...value }
  }
  async remove(petId: string, requestId: string): Promise<{ petId: string; status: PetStatus }> {
    await this.pause()
    const request = { petId }
    const receipt = this.receipts.get(requestId)
    if (receipt) {
      if (JSON.stringify(receipt.request) !== JSON.stringify(request)) throw new Error('REQUEST_CONFLICT')
      return receipt.value as { petId: string; status: PetStatus }
    }
    const index = this.pets.findIndex(pet => pet.petId === petId && pet.status === 'ACTIVE')
    if (index < 0) throw new Error('PET_NOT_FOUND')
    this.pets[index] = { ...this.pets[index], status: 'DISABLED' }
    const value = { petId, status: 'DISABLED' as PetStatus }
    this.receipts.set(requestId, { request, value })
    return value
  }
}
