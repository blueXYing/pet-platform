// 店铺信息页控制器（27号 §6.4 M 面）：读资料 → 表单草稿 → 全字段替换保存（幂等槽 + 409 退役）。

import type { BusinessHour, StoreProfileInput, StoreProfileView } from '../../shared/merchant-storefront-api'
import { ownerAccess, profileMessage, profileSaveNotice } from './model'
import type { MerchantStorefrontDeps } from './repository'

export type ShopInfoState = Readonly<{
  phase: 'idle' | 'loading' | 'entry' | 'error' | 'ready'
  notice: string
  frozen: boolean
  busy: boolean
  view: StoreProfileView | null
  draft: {
    intro: string
    announcement: string
    promoTitle: string
    promoText: string
    promoLevel: number
  }
  saving: boolean
  savedVersion: string | null
}>

const initialDraft = () => ({ intro: '', announcement: '', promoTitle: '', promoText: '', promoLevel: 0 })

const initialState = (): ShopInfoState => ({
  phase: 'idle', notice: '', frozen: false, busy: false, view: null,
  draft: initialDraft(), saving: false, savedVersion: null,
})

export class ShopInfoController {
  private state = initialState()
  private listeners = new Set<() => void>()
  private active = true
  private epoch = 0
  private reads = 0
  private unsubscribe: () => void
  constructor(private deps: MerchantStorefrontDeps,
    private coordinates: { merchantId: string; storeId: string }) {
    this.unsubscribe = deps.scope.subscribe(() => { this.epoch++; this.publish(initialState()) })
  }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish(state: ShopInfoState) { if (!this.active) return; this.state = Object.freeze(state); this.listeners.forEach(listener => listener()) }
  private live(epoch: number) { return this.active && epoch === this.epoch }

  hide() { this.active = false }
  dispose() { this.active = false; this.unsubscribe() }

  private adoptDraft(view: StoreProfileView) {
    return {
      intro: view.profile.intro ?? '',
      announcement: view.profile.announcement ?? '',
      promoTitle: view.profile.promoTitle ?? '',
      promoText: view.profile.promoText ?? '',
      promoLevel: view.profile.promoLevel ?? 0,
    }
  }

  async load() {
    if (!this.active || this.state.busy) return
    const epoch = this.epoch, run = ++this.reads
    this.publish({ ...this.state, phase: 'loading', notice: '' })
    try {
      const admission = await this.deps.admission(this.coordinates.merchantId, this.coordinates.storeId)
      if (!this.live(epoch) || run !== this.reads) return
      const access = ownerAccess(admission, this.coordinates.merchantId, this.coordinates.storeId)
      if (!access.readable) {
        this.publish({ ...this.state, phase: 'entry', frozen: access.frozen })
        return
      }
      const view = await this.deps.storeProfile()
      if (!this.live(epoch) || run !== this.reads) return
      this.publish({ ...this.state, phase: 'ready', view, draft: this.adoptDraft(view), frozen: access.frozen })
    } catch (error) {
      if (!this.live(epoch) || run !== this.reads) return
      this.publish({ ...this.state, phase: 'error', notice: profileMessage[(error as Error)?.message] ?? '店铺资料读取失败，请稍后重试' })
    }
  }

  editDraft(patch: Partial<ShopInfoState['draft']>) {
    this.publish({ ...this.state, draft: { ...this.state.draft, ...patch }, savedVersion: null })
  }

  /** 表单边界（PRD 5.7 字段表）：0-500/0-200/0-100 码点、促销等级 1-9 折。 */
  save(): Promise<void> {
    const view = this.state.view
    if (!view || this.state.saving) return Promise.resolve()
    const draft = this.state.draft
    const intro = draft.intro.trim()
    const announcement = draft.announcement.trim()
    const promoTitle = draft.promoTitle.trim()
    const promoText = draft.promoText.trim()
    if ([intro, announcement, promoTitle, promoText].some(value => Array.from(value).length > 500)
      || Array.from(announcement).length > 200 || Array.from(promoTitle).length > 100
      || Array.from(promoText).length > 100 || Array.from(intro).length > 500) {
      this.publish({ ...this.state, notice: '字段超出长度限制' })
      return Promise.resolve()
    }
    const input: StoreProfileInput = {
      intro, announcement,
      photoAssetIds: view.profile.photoAssetIds,
      businessHours: view.profile.businessHours as readonly BusinessHour[],
      promoLevel: draft.promoLevel,
      promoTitle, promoText,
      expectedVersion: view.profile.version,
    }
    this.publish({ ...this.state, saving: true, notice: '' })
    const epoch = this.epoch
    return this.deps.saveStoreProfile(input).then(saved => {
      if (!this.live(epoch)) return
      this.publish({ ...this.state, saving: false, view: saved, draft: this.adoptDraft(saved), savedVersion: saved.profile.version })
    }).catch(error => {
      if (!this.live(epoch)) return
      // 409（版本冲突/幂等键冲突）对该 payload 终局：退役槽位让用户刷新后重提。
      if ((error as { code?: string })?.code === 'COMMON_CONFLICT'
        || (error as { code?: string })?.code === 'IDEMPOTENCY_KEY_CONFLICT') {
        try { this.deps.retireProfileConflict(error) } catch { /* 槽位校验失败保持锁定 */ }
      }
      this.publish({ ...this.state, saving: false, notice: profileSaveNotice(error) })
    })
  }
}
