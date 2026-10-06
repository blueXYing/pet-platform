import type { NotificationPreference } from '../../shared/notification-repositories'
import { ApiError } from '../../shared/request'

// 通知偏好（NTF 偏好切片）。SSOT §16.4 只允许用户关闭两类提醒：普通互动提醒与微信外部
// 推送；订单/退款/核销/售后/审核站内消息必须保留（服务端强制，页面只呈现该事实）。
// 偏好项严格等于 notification_preference schema 的两个开关，SSOT 未定义的偏好项一律不做。
export type PreferenceKey = 'interactionEnabled' | 'externalPushEnabled'
export type PreferenceInput = Readonly<{ interactionEnabled: boolean; externalPushEnabled: boolean }>
export type PreferenceDeps = {
  load(): Promise<NotificationPreference>
  save(input: PreferenceInput): Promise<NotificationPreference>
}
export type PreferencesState = Readonly<{
  status: 'loading' | 'ready' | 'error' | 'unauthorized'
  // 最近一次服务端确认的偏好；null = 尚未读到。
  saved: NotificationPreference | null
  // 本地草稿开关（未保存）；ready 后始终存在。
  draft: PreferenceInput | null
  saving: boolean
  notice: string
}>

export const preferenceKeys: readonly PreferenceKey[] = ['interactionEnabled', 'externalPushEnabled']

export const preferenceMeta: Record<PreferenceKey, { title: string; description: string }> = {
  interactionEnabled: {
    title: '互动消息提醒',
    description: '普通互动类消息（如评价、回复）的站内提醒；关闭后不再新产生此类站内消息。',
  },
  externalPushEnabled: {
    title: '微信外部推送',
    description: '微信渠道外部提醒的偏好开关；该渠道发送能力 V1 尚未开通，当前仅保存偏好，不触发任何外部发送。',
  },
}

// 订单/退款/核销/售后/审核的站内必达事实（SSOT §16.4）：不可关闭，仅作说明。
export const mandatoryNotice =
  '订单、退款、核销、售后、审核类站内消息为必须通知，不可关闭；偏好仅影响普通互动提醒与微信外部推送。'

function unauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 401
}

/** One controller per mounted page; toggles only edit the local draft until save. */
export class NotificationPreferencesController {
  private state: PreferencesState = { status: 'loading', saved: null, draft: null, saving: false, notice: '' }
  private listeners = new Set<() => void>()
  private active = true
  private run = 0
  constructor(private deps: PreferenceDeps) {}
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
  private publish(next: PreferencesState) {
    this.state = Object.freeze(next)
    this.listeners.forEach(listener => listener())
  }
  private fail(error: unknown) {
    if (!this.active) return
    this.publish({
      status: unauthorized(error) ? 'unauthorized' : 'error',
      saved: null, draft: null, saving: false,
      notice: unauthorized(error) ? '登录已失效，请重新登录。' : '通知偏好读取失败，请稍后重试。',
    })
  }
  async load() {
    if (!this.active) return
    const run = ++this.run
    this.publish({ status: 'loading', saved: null, draft: null, saving: false, notice: '' })
    try {
      const saved = await this.deps.load()
      if (!this.active || run !== this.run) return
      this.publish({
        status: 'ready', saved, draft: { interactionEnabled: saved.interactionEnabled, externalPushEnabled: saved.externalPushEnabled },
        saving: false, notice: '',
      })
    } catch (error) {
      if (run === this.run) this.fail(error)
    }
  }
  /** Toggles edit the draft only; nothing leaves the page until save. */
  toggle(key: PreferenceKey) {
    if (!this.active || this.state.status !== 'ready' || this.state.saving || this.state.draft === null) return
    this.publish({ ...this.state, draft: { ...this.state.draft, [key]: !this.state.draft[key] }, notice: '' })
  }
  dirty(): boolean {
    const { saved, draft } = this.state
    return saved !== null && draft !== null
      && (saved.interactionEnabled !== draft.interactionEnabled
        || saved.externalPushEnabled !== draft.externalPushEnabled)
  }
  /** Full-state save of both switches; ConsumerApi.write keeps the requestId across retries. */
  async save() {
    if (!this.active || this.state.status !== 'ready' || this.state.saving || this.state.draft === null) return
    const run = ++this.run
    const input = { ...this.state.draft }
    this.publish({ ...this.state, saving: true, notice: '' })
    try {
      const saved = await this.deps.save(input)
      if (!this.active || run !== this.run) return
      this.publish({
        status: 'ready', saved,
        draft: { interactionEnabled: saved.interactionEnabled, externalPushEnabled: saved.externalPushEnabled },
        saving: false, notice: '已保存。',
      })
    } catch (error) {
      if (!this.active || run !== this.run) return
      if (unauthorized(error)) {
        this.publish({ status: 'unauthorized', saved: null, draft: null, saving: false, notice: '登录已失效，请重新登录。' })
        return
      }
      // The draft survives a failed save: the client journal keeps the requestId, so an
      // explicit retry replays the same idempotent command instead of issuing a new one.
      this.publish({ ...this.state, saving: false, notice: '保存失败，请重试。' })
    }
  }
  /** Discard unsaved draft switches back to the last confirmed state. */
  reset() {
    if (!this.active || this.state.status !== 'ready' || this.state.saved === null || this.state.saving) return
    this.publish({
      ...this.state,
      draft: { interactionEnabled: this.state.saved.interactionEnabled, externalPushEnabled: this.state.saved.externalPushEnabled },
      notice: '',
    })
  }
  dispose() {
    this.active = false
    this.run++
    this.listeners.clear()
  }
}

export type PreferenceScenario = 'normal' | 'off'
export const isPreferenceScenario = (value?: string): value is PreferenceScenario =>
  ['normal', 'off'].includes(value || '')

/** preview=1 专用：数据全部来自本地常量，不做任何网络请求（设计验收通道）。 */
export class PreviewPreferenceDeps implements PreferenceDeps {
  private current: NotificationPreference
  constructor(scenario: PreferenceScenario = 'normal') {
    this.current = scenario === 'off'
      ? { interactionEnabled: false, externalPushEnabled: false, version: '3', updatedAt: '2026-10-06T02:00:00.000Z' }
      : { interactionEnabled: true, externalPushEnabled: true, version: '0', updatedAt: null }
  }
  async load() { return this.current }
  async save(input: PreferenceInput) {
    this.current = { ...input, version: String(Number(this.current.version) + 1), updatedAt: '2026-10-06T02:00:00.000Z' }
    return this.current
  }
}
