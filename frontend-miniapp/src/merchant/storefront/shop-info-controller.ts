// 店铺信息页控制器（27号 §6.4 M 面 + 2026-10-19 扩展）：读资料 → 表单草稿 → 全字段替换保存
// （幂等槽 + 409 退役）。扩展面：营业状态开关（OPEN/RESTING）、店铺照片页内上传
// （STORE_PHOTO 私有资产管线）、店铺头像、店铺事实名称/地址/电话（不触发复审，落审计）。

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
    storeName: string
    address: string
    phone: string
    intro: string
    announcement: string
    promoTitle: string
    promoText: string
    promoLevel: number
    photoAssetIds: readonly string[]
    avatarAssetId: string
    businessStatus: string
  }
  saving: boolean
  uploadingPhoto: boolean
  savedVersion: string | null
}>

const initialDraft = () => ({
  storeName: '', address: '', phone: '', intro: '', announcement: '',
  promoTitle: '', promoText: '', promoLevel: 0,
  photoAssetIds: [] as readonly string[], avatarAssetId: '', businessStatus: '',
})

const initialState = (): ShopInfoState => ({
  phase: 'idle', notice: '', frozen: false, busy: false, view: null,
  draft: initialDraft(), saving: false, uploadingPhoto: false, savedVersion: null,
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
      storeName: view.readonlyFacts.storeName,
      address: view.readonlyFacts.address,
      phone: '',
      intro: view.profile.intro ?? '',
      announcement: view.profile.announcement ?? '',
      promoTitle: view.profile.promoTitle ?? '',
      promoText: view.profile.promoText ?? '',
      promoLevel: view.profile.promoLevel ?? 0,
      photoAssetIds: view.profile.photoAssetIds,
      avatarAssetId: view.profile.avatarAssetId ?? '',
      businessStatus: view.profile.businessStatus,
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

  /** 营业状态开关（2026-10-19 扩展）：OPEN/RESTING；空串=回到 OPEN 投影。 */
  toggleBusinessStatus(resting: boolean) {
    this.editDraft({ businessStatus: resting ? 'RESTING' : 'OPEN' })
  }

  /** 店铺照片页内上传（STORE_PHOTO 管线）：选取图片 → 上传 → 进草稿，保存时随全字段替换提交。 */
  async addPhoto(filePath: string): Promise<void> {
    const view = this.state.view
    if (!view || this.state.uploadingPhoto) return
    if (this.state.draft.photoAssetIds.length >= 6) {
      this.publish({ ...this.state, notice: '店铺照片最多 6 张' })
      return
    }
    this.publish({ ...this.state, uploadingPhoto: true, notice: '' })
    const epoch = this.epoch
    try {
      const receipt = await this.deps.uploadStorePhoto(filePath)
      if (!this.live(epoch)) return
      const current = this.state.draft.photoAssetIds
      if (current.includes(receipt.assetId) || current.length >= 6) {
        this.publish({ ...this.state, uploadingPhoto: false })
        return
      }
      this.publish({
        ...this.state, uploadingPhoto: false,
        draft: { ...this.state.draft, photoAssetIds: [...current, receipt.assetId] },
        savedVersion: null,
      })
    } catch (error) {
      if (!this.live(epoch)) return
      this.publish({ ...this.state, uploadingPhoto: false, notice: photoNotice(error) })
    }
  }

  removePhoto(assetId: string) {
    this.editDraft({ photoAssetIds: this.state.draft.photoAssetIds.filter(id => id !== assetId) })
  }

  /** 店铺头像（2026-10-19 扩展）：同照片管线；空串=清空。 */
  async setAvatar(filePath: string): Promise<void> {
    if (!this.state.view || this.state.uploadingPhoto) return
    this.publish({ ...this.state, uploadingPhoto: true, notice: '' })
    const epoch = this.epoch
    try {
      const receipt = await this.deps.uploadStorePhoto(filePath)
      if (!this.live(epoch)) return
      this.publish({
        ...this.state, uploadingPhoto: false,
        draft: { ...this.state.draft, avatarAssetId: receipt.assetId },
        savedVersion: null,
      })
    } catch (error) {
      if (!this.live(epoch)) return
      this.publish({ ...this.state, uploadingPhoto: false, notice: photoNotice(error) })
    }
  }

  clearAvatar() { this.editDraft({ avatarAssetId: '' }) }

  /** 表单边界（PRD 5.7 字段表 + 2026-10-19 扩展）：名称 1-128、地址 1-255、电话 5-32 位数字、
   *  0-500/0-200/0-100 码点、促销等级 1-9 折。 */
  save(): Promise<void> {
    const view = this.state.view
    if (!view || this.state.saving) return Promise.resolve()
    const draft = this.state.draft
    const storeName = draft.storeName.trim()
    const address = draft.address.trim()
    const phone = draft.phone.trim()
    const intro = draft.intro.trim()
    const announcement = draft.announcement.trim()
    const promoTitle = draft.promoTitle.trim()
    const promoText = draft.promoText.trim()
    if (!storeName || Array.from(storeName).length > 128 || !address
      || Array.from(address).length > 255
      || (phone && !/^[0-9]{5,32}$/.test(phone))
      || Array.from(announcement).length > 200 || Array.from(promoTitle).length > 100
      || Array.from(promoText).length > 100 || Array.from(intro).length > 500) {
      this.publish({ ...this.state, notice: '字段超出长度限制或格式不正确' })
      return Promise.resolve()
    }
    const input: StoreProfileInput = {
      storeName, address, phone,
      intro, announcement,
      photoAssetIds: draft.photoAssetIds,
      businessHours: view.profile.businessHours as readonly BusinessHour[],
      promoLevel: draft.promoLevel,
      promoTitle, promoText,
      avatarAssetId: draft.avatarAssetId,
      businessStatus: draft.businessStatus === 'RESTING' ? 'RESTING' : draft.businessStatus === 'OPEN' ? 'OPEN' : '',
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

function photoNotice(error: unknown): string {
  const code = (error as { code?: string })?.code
  if (code === 'COMMON_FORBIDDEN') return '仅商家主账号可上传店铺照片素材'
  if (code === 'UNSUPPORTED_PRIVATE_ASSET_MEDIA_TYPE') return '仅支持 JPEG/PNG 图片'
  if (code === 'COMMON_UNAUTHORIZED') return '登录已失效，请重新进入'
  return '照片上传失败，请稍后重试'
}
