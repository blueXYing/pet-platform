// View state only. User accepted CCR correction; authoritative contract sync is pending.
export type Gender = 'MALE' | 'FEMALE'
export type ProfileDraft = Readonly<{ nickname: string; gender: Gender | null; signature: string; avatarUrl: string; phoneMasked: string }>
export type ProfilePhase = 'loading' | 'ready' | 'saving' | 'load-error' | 'save-error' | 'expired' | 'unavailable'
export const codePointLength = (value: string) => Array.from(value).length
export function validateDraft(value: ProfileDraft): Partial<Record<'nickname' | 'signature', string>> {
  const result: Partial<Record<'nickname' | 'signature', string>> = {}
  if (!value.nickname.trim()) result.nickname = '请填写昵称'
  else if (value.nickname !== value.nickname.trim()) result.nickname = '昵称首尾不能包含空格'
  else if (codePointLength(value.nickname) > 20) result.nickname = '昵称最多20字'
  if (codePointLength(value.signature) > 60) result.signature = '个性签名最多60字'
  return result
}
export function sameDraft(a: ProfileDraft, b: ProfileDraft) {
  return a.nickname === b.nickname && a.gender === b.gender && a.signature === b.signature && a.avatarUrl === b.avatarUrl
}
export type PreviewScenario = 'normal' | 'load-error' | 'save-error' | 'expired' | 'empty'
export const isPreviewScenario = (value?: string): value is PreviewScenario => ['normal', 'load-error', 'save-error', 'expired', 'empty'].includes(value || '')

// Explicit preview repository: no network, no userId supplied to backend, no durable user data.
export class PreviewProfileRepository {
  private value: ProfileDraft
  private failLoad: boolean
  private failSave: boolean
  private receipts = new Map<string, { request: ProfileDraft; value: ProfileDraft }>()
  constructor(initial: ProfileDraft, scenario: PreviewScenario = 'normal', private pause: () => Promise<void> = async () => {}) {
    this.value = { ...initial }; this.failLoad = scenario === 'load-error'; this.failSave = scenario === 'save-error'
  }
  async load() {
    await this.pause()
    if (this.failLoad) { this.failLoad = false; throw new Error('PREVIEW_LOAD_FAILED') }
    return { ...this.value }
  }
  async save(draft: ProfileDraft, requestId: string) {
    await this.pause()
    if (Object.keys(validateDraft(draft)).length) throw new Error('INVALID_DRAFT')
    const receipt = this.receipts.get(requestId)
    if (receipt) {
      if (!sameDraft(receipt.request, draft)) throw new Error('REQUEST_CONFLICT')
      return { ...receipt.value }
    }
    if (this.failSave) { this.failSave = false; throw new Error('PREVIEW_SAVE_FAILED') }
    this.value = { ...draft }
    this.receipts.set(requestId, { request: { ...draft }, value: { ...draft } })
    return { ...this.value }
  }
}
