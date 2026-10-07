// 核销码页（47号 §4 v0.2 C 端凭证路由，后端 PR#114 交付，IMPLEMENTED_DEFAULT_OFF：
// pet.auth.c.enabled + pet.verification.credential.http.enabled 双层开关默认关闭）。本文件是
// 核销码 C 端投影契约口径的唯一替换点，字段事实全部来自契约与 #114 实现：
// - GET /api/v1/c/orders/{orderId}/verification-code 完整视图恰七键：
//   orderId/credentialVersion/status(NONE|ACTIVE|EXPIRED|INVALIDATED|LOCKED)/code?/expiresAt?/
//   refreshAfter?/lockedUntil?；仅 ACTIVE 回显码；refreshAfter=expiresAt（47号）；时间一律
//   appendInstant(3) —— UTC 毫秒 ISO 串。GET 不带任何 query 参数（带参 400）。
// - POST 同路径签发/刷新：严格 JSON {expectedCredentialVersion, refreshKind}，refreshKind 由
//   客户端明示（INITIAL/AUTO/MANUAL），服务端按内核规则核验：INITIAL 仅无当前码、AUTO 仅当前
//   码已过期、MANUAL 滚动 60 秒最多 5 次成功，第 6 次 429 COMMON_RATE_LIMITED；版本不匹配
//   409 COMMON_CONFLICT。成功回执恰七键 orderId/credentialId/credentialVersion/code/issuedAt/
//   expiresAt/refreshAfter，首次与幂等重放均 200。X-Request-Id 终端 UUID，五元组幂等。
// - 码为 160bit 随机源的 32 位 base32 变体字母数字串（字母表 0-9A-HJKMNP-TVWXYZ，无 I/L/O/U）。
// - GET/POST 均 no-store；页面不缓存码、不在任何日志输出码。
// 订单读取现状：10号 §3.7 GET /api/v1/c/orders、/c/orders/{orderId} 仅有契约形状，后端无
// Controller、前端无订单列表/详情页（C-004 后续切片）。本页按 aftersale 先例以显式输入订单号
// 进入，不虚构任何订单字段（服务、门店、金额等一概不展示）。

import { ApiError } from '../../shared/request'
import type { WorkspaceScope } from '../../shared/workspace'
import { object } from '../../shared/consumer-api'

export type CredentialStatus = 'NONE' | 'ACTIVE' | 'EXPIRED' | 'INVALIDATED' | 'LOCKED'
export const credentialStatuses: readonly CredentialStatus[] = ['NONE', 'ACTIVE', 'EXPIRED', 'INVALIDATED', 'LOCKED']
export const isCredentialStatus = (value?: string): value is CredentialStatus =>
  (credentialStatuses as readonly string[]).includes(value || '')

export type RefreshKind = 'INITIAL' | 'AUTO' | 'MANUAL'
export const refreshKinds: readonly RefreshKind[] = ['INITIAL', 'AUTO', 'MANUAL']

export type CredentialView = Readonly<{
  orderId: string
  credentialVersion: string
  status: CredentialStatus
  code: string | null
  expiresAt: string | null
  refreshAfter: string | null
  lockedUntil: string | null
}>

export type IssueReceipt = Readonly<{
  orderId: string
  credentialId: string
  credentialVersion: string
  code: string
  issuedAt: string
  expiresAt: string
  refreshAfter: string
}>

/** POST 幂等槽里的未确认命令参数（ConsumerApi.write 日志复原用）。 */
export type PendingIssue = Readonly<{ expectedCredentialVersion: string; refreshKind: RefreshKind }>

const idPattern = /^[1-9][0-9]{0,18}(?![\s\S])/
export const isOrderId = (value: string) => idPattern.test(value) && BigInt(value) <= 9223372036854775807n
const version = (value: unknown): string => typeof value === 'string' && /^(0|[1-9][0-9]{0,18})(?![\s\S])/.test(value) ? value : fail()
// 后端 CredentialProtection 字母表：0123456789ABCDEFGHJKMNPQRSTVWXYZ（无 I/L/O/U），32 位。
const credentialCode = (value: unknown): string => typeof value === 'string' && /^[0-9A-HJKMNP-TVWXYZ]{32}(?![\s\S])/.test(value) ? value : fail()
// appendInstant(3)：UTC 毫秒精度 ISO 串，且必须是可往返的规范形式（aftersale instant 同口径）。
const instant = (v: unknown): string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(v) && Number.isFinite(Date.parse(v)) && new Date(v).toISOString() === v ? v : fail()

function fail(): never { throw new Error('INVALID_RESPONSE') }
/** 严格 exact-key：只允许契约键集合，未知键失败关闭（沿 coupon-points/aftersale 惯例）。 */
function exact(value: unknown, keys: readonly string[]): Record<string, any> {
  const v = object(value)
  const expected = [...keys].sort().join(',')
  if (Object.keys(v).sort().join(',') !== expected) fail()
  return v
}
const nullable = <T>(v: unknown, decode: (v: unknown) => T): T | null => v === null ? null : decode(v)

/** GET 完整视图解码：仅 ACTIVE 回显码；refreshAfter=expiresAt（47号）；LOCKED 必带 lockedUntil。 */
export function decodeCredentialView(value: unknown): CredentialView {
  const v = exact(value, ['code', 'credentialVersion', 'expiresAt', 'lockedUntil', 'orderId', 'refreshAfter', 'status'])
  const status = v.status
  if (!isCredentialStatus(typeof status === 'string' ? status : undefined)) fail()
  const code = v.code === null ? null : credentialCode(v.code)
  // 仅 ACTIVE 回显码；其余状态码字段必须为 null（服务端 plain 仅 ACTIVE 输出）。
  if ((status === 'ACTIVE') !== (code !== null)) fail()
  const expiresAt = nullable(v.expiresAt, instant), refreshAfter = nullable(v.refreshAfter, instant)
  // ACTIVE 时码与时间必在；EXPIRED 旧截止仍在（码已不可用）；refreshAfter 恒等于 expiresAt。
  if (status === 'ACTIVE' && (expiresAt === null || refreshAfter === null)) fail()
  if ((expiresAt === null) !== (refreshAfter === null)) fail()
  if (expiresAt !== null && expiresAt !== refreshAfter) fail()
  const lockedUntil = nullable(v.lockedUntil, instant)
  if (status === 'LOCKED' && lockedUntil === null) fail()
  const orderId = isOrderId(typeof v.orderId === 'string' ? v.orderId : '') ? v.orderId as string : fail()
  return { orderId, credentialVersion: version(v.credentialVersion), status, code, expiresAt, refreshAfter, lockedUntil }
}

/** POST 成功回执解码（首次与幂等重放同形，均 200）。 */
export function decodeIssueReceipt(value: unknown): IssueReceipt {
  const v = exact(value, ['code', 'credentialId', 'credentialVersion', 'expiresAt', 'issuedAt', 'orderId', 'refreshAfter'])
  const orderId = isOrderId(typeof v.orderId === 'string' ? v.orderId : '') ? v.orderId as string : fail()
  const expiresAt = instant(v.expiresAt), refreshAfter = instant(v.refreshAfter)
  if (expiresAt !== refreshAfter) fail()
  return { orderId, credentialId: isOrderId(typeof v.credentialId === 'string' ? v.credentialId : '') ? v.credentialId as string : fail(),
    credentialVersion: version(v.credentialVersion), code: credentialCode(v.code),
    issuedAt: instant(v.issuedAt), expiresAt, refreshAfter }
}

export const statusLabels: Record<CredentialStatus, string> = {
  NONE: '尚未生成',
  ACTIVE: '有效',
  EXPIRED: '已过期',
  INVALIDATED: '已失效',
  LOCKED: '已锁定',
}
export const statusHints: Record<CredentialStatus, string> = {
  NONE: '订单确认后可获取核销码，服务当天到店出示即可。',
  ACTIVE: '请在有效期内到店出示。每次签发有效期5分钟，过期或需要新码时可刷新，刷新成功旧码立即作废。',
  EXPIRED: '核销码已过期，不能继续使用；刷新后获得新码，旧码不会复活。',
  INVALIDATED: '订单改期后原核销码即失效且永不再可用；改期成功的新预约需重新获取核销码。',
  LOCKED: '因多次无效尝试触发安全锁定，锁定期内不显示核销码、不可签发或刷新；到期自动解除。',
}

/** 按 47号内核规则映射当前状态可发起的写操作；LOCKED 无操作。 */
export function actionFor(status: CredentialStatus): { kind: RefreshKind; label: string } | null {
  if (status === 'NONE') return { kind: 'INITIAL', label: '获取核销码' }
  if (status === 'INVALIDATED') return { kind: 'INITIAL', label: '获取新核销码' }
  if (status === 'ACTIVE') return { kind: 'MANUAL', label: '刷新核销码' }
  if (status === 'EXPIRED') return { kind: 'AUTO', label: '刷新已过期核销码' }
  return null
}

/** 这些 409 对该 payload 是终局拒绝（版本/资格/退款单事实不会回退），可退幂等槽后重新读取。 */
export function isDefiniteVerifyConflict(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 409
    && ['COMMON_CONFLICT', 'VERIFICATION_BLOCKED_BY_REFUND', 'VERIFICATION_ALREADY_DONE', 'VERIFICATION_NOT_ALLOWED'].includes(error.code)
}

/** 页面错误文案（47号 §4 + 12号错误码；403 同时覆盖未知订单的防探测语义）。 */
export function verifyMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401) return '登录已失效，请重新登录后查看'
    if (error.code === 'VERIFICATION_BLOCKED_BY_REFUND') return '该订单已创建退款单，核销码不可用'
    if (error.code === 'VERIFICATION_ALREADY_DONE') return '该订单已核销完成，无需再出示核销码'
    if (error.code === 'VERIFICATION_NOT_ALLOWED') return '当前订单状态不支持核销码（需已支付待服务且未核销）'
    if (error.code === 'VERIFICATION_RISK_LOCKED') return '多次无效尝试已触发安全锁定，暂时无法获取核销码，请稍后重试原操作'
    if (error.code === 'COMMON_RATE_LIMITED') return '刷新太频繁：每60秒最多刷新5次，请稍后重试原操作'
    if (error.code === 'COMMON_CONFLICT') return '核销码状态已变化（可能已刷新或改期），请重新读取后操作'
    if (error.code === 'IDEMPOTENCY_KEY_CONFLICT') return '上次操作参数已变化，请重新读取后再试'
    if (error.statusCode === 403) return '仅订单本人可查看核销码，或订单不存在'
    if (error.statusCode === 404) return '核销码服务未开放或订单不存在，请稍后再试'
    if (error.statusCode === 400) return '请求无效，请重新读取核销码后操作'
    if (error.statusCode === 429) return '操作太频繁，请稍后重试原操作'
    if (error.statusCode === 503) return '服务暂不可用，请稍后重试'
  }
  if (error instanceof Error && error.message === 'PENDING_WRITE_CHANGED') return '上次取码/刷新结果尚未确认，请先重试原操作'
  if (error instanceof Error && error.message === 'INVALID_RESPONSE') return '服务返回异常，请稍后重试'
  return '结果尚未确认，请重试原操作'
}

/** 码展示分组：32 位按 8 位一组，便于口头报码与人工核对。 */
export function formatCode(code: string | null): string {
  if (code === null || !/^[0-9A-HJKMNP-TVWXYZ]{32}(?![\s\S])/.test(code)) return '—'
  return `${code.slice(0, 8)} ${code.slice(8, 16)} ${code.slice(16, 24)} ${code.slice(24, 32)}`
}

const pad = (value: number): string => String(value).padStart(2, '0')

/** 有效期剩余（ACTIVE 秒级倒计时源数据）；过期/无效返回空串。 */
export function remainingLabel(now: number, expiresAt: string | null): string {
  if (expiresAt === null) return ''
  const left = Date.parse(expiresAt) - now
  if (!Number.isFinite(left) || left <= 0) return ''
  const totalSeconds = Math.floor(left / 1000)
  const minutes = Math.floor(totalSeconds / 60), seconds = totalSeconds % 60
  return minutes > 0 ? `${minutes}分${pad(seconds)}秒` : `${seconds}秒`
}

/** UTC ISO 串 → 北京时间展示（设备时区不作假设，aftersale formatTime 同口径）。 */
export function formatInstant(value: string | null): string {
  if (value === null) return '—'
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return '—'
  return new Date(date.getTime() + 8 * 3600000).toISOString().slice(0, 19).replace('T', ' ') + '（北京时间）'
}

// ---- preview=1 设计验收通道（本地夹具，不发任何网络请求） ----

export type OrderVerifyScenario = 'active' | 'none' | 'expired' | 'invalidated' | 'locked'
export const isOrderVerifyScenario = (value?: string): value is OrderVerifyScenario =>
  ['active', 'none', 'expired', 'invalidated', 'locked'].includes(value || '')

const PREVIEW_ORDER = '900101001990001'
const previewCode = (seed: number) => {
  const alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
  let state = (seed * 2654435761) >>> 0
  let text = ''
  for (let index = 0; index < 32; index++) { state = (state * 1664525 + 1013904223) >>> 0; text += alphabet[state % 32] }
  return text
}

/** 内核规则的 preview 模拟：状态机 + MANUAL 滚动 60 秒计数，供设计验收与单测共用。 */
export class PreviewOrderVerifyRepository {
  private current: CredentialView
  private manualLog: number[] = []
  private sequence = 0
  constructor(scenario: OrderVerifyScenario = 'active', private now: () => number = Date.now) {
    const base = Date.now()
    const at = (offsetMs: number) => new Date(base + offsetMs).toISOString()
    if (scenario === 'locked') this.current = { orderId: PREVIEW_ORDER, credentialVersion: '3', status: 'LOCKED', code: null, expiresAt: at(300000), refreshAfter: at(300000), lockedUntil: at(600000) }
    else if (scenario === 'invalidated') this.current = { orderId: PREVIEW_ORDER, credentialVersion: '2', status: 'INVALIDATED', code: null, expiresAt: null, refreshAfter: null, lockedUntil: null }
    else if (scenario === 'expired') this.current = { orderId: PREVIEW_ORDER, credentialVersion: '1', status: 'EXPIRED', code: null, expiresAt: at(-60000), refreshAfter: at(-60000), lockedUntil: null }
    else if (scenario === 'none') this.current = { orderId: PREVIEW_ORDER, credentialVersion: '0', status: 'NONE', code: null, expiresAt: null, refreshAfter: null, lockedUntil: null }
    else this.current = { orderId: PREVIEW_ORDER, credentialVersion: '1', status: 'ACTIVE', code: previewCode(7), expiresAt: at(300000), refreshAfter: at(300000), lockedUntil: null }
  }
  async view(orderId: string): Promise<CredentialView> {
    if (!isOrderId(orderId)) throw new ApiError('COMMON_NOT_FOUND', 404)
    // 随真实时钟推进重算 ACTIVE/EXPIRED，锁定期满解除锁定。
    const now = this.now()
    let status = this.current.status
    if (status === 'ACTIVE' && now >= Date.parse(this.current.expiresAt!)) status = 'EXPIRED'
    if (status === 'LOCKED' && this.current.lockedUntil !== null && now >= Date.parse(this.current.lockedUntil)) {
      const expired = this.current.expiresAt !== null && now >= Date.parse(this.current.expiresAt)
      status = this.current.code === null ? 'NONE' : expired ? 'EXPIRED' : 'ACTIVE'
    }
    const code = status === 'ACTIVE' ? this.current.code : null
    return { ...this.current, status, code }
  }
  async issue(orderId: string, expectedCredentialVersion: string, refreshKind: RefreshKind): Promise<IssueReceipt> {
    const current = await this.view(orderId)
    const now = this.now()
    if (current.status === 'LOCKED') throw new ApiError('VERIFICATION_RISK_LOCKED', 409)
    if (current.credentialVersion !== expectedCredentialVersion) throw new ApiError('COMMON_CONFLICT', 409)
    if (refreshKind === 'INITIAL' && current.status !== 'NONE' && current.status !== 'INVALIDATED') throw new ApiError('COMMON_CONFLICT', 409)
    if (refreshKind === 'AUTO' && current.status !== 'EXPIRED') throw new ApiError('COMMON_CONFLICT', 409)
    if (refreshKind === 'MANUAL') {
      this.manualLog = this.manualLog.filter(time => now - time < 60000)
      if (this.manualLog.length >= 5) throw new ApiError('COMMON_RATE_LIMITED', 429)
      this.manualLog.push(now)
    }
    const issuedAt = now, expiresAt = now + 300000
    const receipt: IssueReceipt = { orderId: current.orderId, credentialId: String(9100000000000000 + ++this.sequence),
      credentialVersion: String(Number(current.credentialVersion) + 1), code: previewCode(11 + this.sequence),
      issuedAt: new Date(issuedAt).toISOString(), expiresAt: new Date(expiresAt).toISOString(), refreshAfter: new Date(expiresAt).toISOString() }
    this.current = { orderId: current.orderId, credentialVersion: receipt.credentialVersion, status: 'ACTIVE', code: receipt.code, expiresAt: receipt.expiresAt, refreshAfter: receipt.refreshAfter, lockedUntil: null }
    return receipt
  }
  pendingIssue(): PendingIssue | null { return null }
  retireConflict(): void { /* preview 无幂等槽 */ }
}

// ---- 页面控制器（一个挂载页 + 一个主体；未知结果写保留原 payload，仅显式重试重发） ----

export type OrderVerifyDeps = {
  view(orderId: string): Promise<CredentialView>
  issue(orderId: string, expectedCredentialVersion: string, refreshKind: RefreshKind): Promise<IssueReceipt>
  pendingIssue(orderId: string): PendingIssue | null
  retireConflict(orderId: string, error: unknown): void
}

export type OrderVerifyState = Readonly<{
  phase: 'idle' | 'loading' | 'ready' | 'load-error' | 'unauthorized'
  orderId: string | null
  view: CredentialView | null
  busy: boolean
  pending: PendingIssue | null
  notice: string
}>

const initialState = (): OrderVerifyState => ({ phase: 'idle', orderId: null, view: null, busy: false, pending: null, notice: '' })

export class OrderVerifyController {
  private state = initialState()
  private listeners = new Set<() => void>()
  private active = true
  private epoch = 0
  private reads = 0
  private unsubscribe: () => void
  constructor(private deps: OrderVerifyDeps, private scope: WorkspaceScope) {
    this.unsubscribe = scope.subscribe(() => { this.epoch++; this.reads++; this.publish(initialState()) })
  }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish(state: OrderVerifyState) { if (!this.active) return; this.state = Object.freeze(state); this.listeners.forEach(listener => listener()) }
  private live(epoch: number) { return this.active && epoch === this.epoch }
  /** 读取完整视图；401 进未登录态，其余失败关闭为 load-error。 */
  async load(orderId: string) {
    if (!this.active || this.state.busy || !isOrderId(orderId)) return
    const epoch = this.epoch, run = ++this.reads
    this.publish({ ...this.state, phase: 'loading', notice: '' })
    try {
      const view = await this.deps.view(orderId)
      if (!this.live(epoch) || run !== this.reads) return
      this.publish({ ...this.state, phase: 'ready', orderId, view, pending: this.deps.pendingIssue(orderId), notice: '' })
    } catch (error) {
      if (!this.live(epoch) || run !== this.reads) return
      const unauthorized = error instanceof ApiError && error.statusCode === 401
      this.publish({ ...this.state, phase: unauthorized ? 'unauthorized' : 'load-error', orderId, view: null, pending: null, notice: verifyMessage(error) })
    }
  }
  /** 按当前视图状态发起取码/刷新；有未确认命令时只能重试原操作。 */
  async issue() {
    const view = this.state.view
    if (!view || this.state.phase !== 'ready' || this.state.busy) return
    if (this.state.pending) return this.retry()
    const action = actionFor(view.status)
    if (!action) return
    await this.run(this.state.orderId!, view.credentialVersion, action.kind)
  }
  /** 重试未确认的原操作（同 X-Request-Id、同 payload）。 */
  async retry() {
    const pending = this.state.pending
    if (!pending || !this.state.orderId || this.state.busy) return
    await this.run(this.state.orderId, pending.expectedCredentialVersion, pending.refreshKind)
  }
  private async run(orderId: string, expectedCredentialVersion: string, refreshKind: RefreshKind) {
    const epoch = this.epoch
    this.reads++
    this.publish({ ...this.state, busy: true, notice: '' })
    try {
      await this.deps.issue(orderId, expectedCredentialVersion, refreshKind)
      if (!this.live(epoch)) return
      this.publish({ ...this.state, busy: false, pending: this.deps.pendingIssue(orderId), notice: '核销码已更新' })
      await this.load(orderId) // 成功后重读完整视图展示新码（GET no-store）
    } catch (error) {
      if (!this.live(epoch)) return
      // 终局 409 可解锁重试（退幂等槽后重新读取）；429/锁占用/未知结果保留原命令继续重试原操作。
      let rejected = false
      if (isDefiniteVerifyConflict(error)) {
        try { this.deps.retireConflict(orderId, error); rejected = true } catch { rejected = false }
      }
      const unauthorized = error instanceof ApiError && error.statusCode === 401
      this.publish({ ...this.state, busy: false,
        pending: rejected || unauthorized ? null : this.deps.pendingIssue(orderId),
        ...(unauthorized ? { phase: 'unauthorized' as const, view: null, orderId: null } : {}),
        notice: verifyMessage(error) })
      if (rejected && this.live(epoch)) await this.load(orderId)
    }
  }
  /** 页面挂载时恢复未确认命令（仅提示 + 重试入口，不自动发送）。 */
  restore(orderId: string) {
    if (!this.active || this.state.busy) return
    const pending = this.deps.pendingIssue(orderId)
    if (pending) this.publish({ ...this.state, pending, notice: '已恢复上次未确认的取码/刷新，请重试原操作' })
  }
  dispose() { this.active = false; this.epoch++; this.unsubscribe(); this.listeners.clear() }
}
