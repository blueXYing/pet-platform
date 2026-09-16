import { ConsumerApi, object, id } from '../../shared/consumer-api'
import type { ProfileDraft } from '../profile/model'
import { draftToContract, type PetView, type PetDraft, type PetType } from '../pet/model'

function text(value: unknown, nullable = false): string | null {
  if (nullable && value === null) return null
  if (typeof value !== 'string') throw new Error('INVALID_RESPONSE')
  return value
}
export function decodePet(value: unknown): PetView {
  const v = object(value)
  id(v.petId); text(v.name)
  for (const key of ['breedName', 'birthDate', 'weightKg', 'healthNote', 'avatarUrl']) text(v[key], true)
  if (!['DOG', 'CAT', 'OTHER'].includes(v.petType) || !['MALE', 'FEMALE', 'UNKNOWN'].includes(v.sex) || !['ACTIVE', 'DISABLED'].includes(v.status) || typeof v.isDefault !== 'boolean') throw new Error('INVALID_RESPONSE')
  if (v.weightKg !== null && !/^\d{1,3}\.\d{2}$/.test(v.weightKg)) throw new Error('INVALID_RESPONSE')
  return v as PetView
}
export function decodeProfile(value: unknown): ProfileDraft {
  const v = object(value); id(v.userId)
  return { nickname: text(v.nickname, true) || '', avatarUrl: text(v.avatarUrl, true) || '', phoneMasked: text(v.phoneMasked, true) || '', gender: null, signature: '' }
}
export class RealProfileRepository {
  constructor(private api: ConsumerApi) {}
  load() { return this.api.request({ method: 'GET', path: '/api/v1/c/profile' }, decodeProfile) }
  save(draft: ProfileDraft, _previewId: string) {
    if (draft.gender !== null || draft.signature !== '') throw new Error('PROFILE_FIELDS_NOT_APPROVED')
    return this.api.write('profile', { method: 'PUT', path: '/api/v1/c/profile', data: { nickname: draft.nickname } }, decodeProfile)
  }
}
export class RealPetRepository {
  private loaded = new Map<string, PetView>()
  private revision = -1
  constructor(private api: ConsumerApi, private chooseType: () => Promise<PetType>) {}
  private checkScope() {
    if (this.revision !== this.api.scope.revision) { this.loaded.clear(); this.revision = this.api.scope.revision }
  }
  load() {
    this.checkScope()
    return this.api.request({ method: 'GET', path: '/api/v1/c/pets' }, value => {
      if (!Array.isArray(value)) throw new Error('INVALID_RESPONSE')
      const pets = value.map(decodePet); pets.forEach(pet => this.loaded.set(pet.petId, pet)); return pets
    })
  }
  async get(petId: string) {
    this.checkScope()
    id(petId)
    const pet = await this.api.request({ method: 'GET', path: `/api/v1/c/pets/${petId}` }, decodePet)
    this.loaded.set(petId, pet); return pet
  }
  async save(petId: string | null, draft: PetDraft, _previewId: string) {
    this.checkScope()
    const ticket = this.api.scope.capture()
    const slot = petId ? `pet:${petId}` : 'pet:create'
    const pending = this.api.pendingCommand(slot)
    const mapped = draftToContract(draft)
    let data: Record<string, unknown>
    if (petId) {
      id(petId)
      const current = this.loaded.get(petId)
      if (!current) throw new Error('PET_NOT_LOADED')
      // PUT replaces nullable fields. Preserve existing non-form values rather than clearing them.
      data = { name: mapped.name, breedName: mapped.breedName, birthDate: mapped.birthDate, sex: mapped.sex, weightKg: mapped.weightKg, healthNote: mapped.healthNote,
        sterilizationStatus: pending ? pending.data?.sterilizationStatus : current.sterilizationStatus,
        vaccineStatus: pending ? pending.data?.vaccineStatus : current.vaccineStatus,
        avatarUrl: pending ? pending.data?.avatarUrl : current.avatarUrl, isDefault: pending ? pending.data?.isDefault : current.isDefault }
    } else {
      const petType = pending?.data?.petType || await this.chooseType(); ticket.assertCurrent()
      if (!['DOG', 'CAT', 'OTHER'].includes(String(petType))) throw new Error('PET_TYPE_REQUIRED')
      data = { name: mapped.name, petType, breedName: mapped.breedName, birthDate: mapped.birthDate, sex: mapped.sex, weightKg: mapped.weightKg, healthNote: mapped.healthNote }
    }
    // C HTTP parser rejects explicit null. Omitted optional fields become null in the PUT command.
    data = Object.fromEntries(Object.entries(data).filter(([, value]) => value !== null && value !== undefined))
    const saved = await this.api.write(slot, { method: petId ? 'PUT' : 'POST', path: petId ? `/api/v1/c/pets/${petId}` : '/api/v1/c/pets', data }, decodePet)
    ticket.assertCurrent(); this.loaded.set(saved.petId, saved); return saved
  }
  remove(petId: string, _previewId: string) {
    id(petId)
    return this.api.write(`delete:${petId}`, { method: 'DELETE', path: `/api/v1/c/pets/${petId}` }, value => {
      const receipt = object(value)
      if (receipt.petId !== petId || receipt.status !== 'DISABLED') throw new Error('INVALID_RESPONSE')
      return { petId, status: 'DISABLED' as const }
    })
  }
}
