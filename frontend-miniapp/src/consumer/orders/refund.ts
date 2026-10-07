// C-005 退款申请前端切片（10号 §3.9 + 49号内核，后端同批 PR 交付，IMPLEMENTED_DEFAULT_OFF：
// pet.auth.c.enabled + pet.refund.application.http.enabled 双层默认关闭）。本文件是退款申请
// C 端投影契约口径的唯一替换点，字段事实全部来自契约与本批实现：
// - POST /api/v1/c/orders/{orderId}/refund-applications，严格 JSON {reasonCode, reasonText?}：
//   reasonCode 必填（服务端配置字典校验，1..64 码点；字典未封板，页面不内置任何选项，由
//   用户如实输入），reasonText 选填（0..500 码点）。X-Request-Id 终端 UUID 由 ConsumerApi
//   分配，五元组幂等：首报文 201、受保护重放 200 同回执。
// - 回执恰六键 applicationId/applicationStatus(PENDING_MERCHANT|APPROVED|AUTO_APPROVED|
//   REJECTED)/route(AUTO_FULL_BEFORE_SERVICE|MERCHANT_CONFIRM_AFTER_SERVICE|AFTERSALE_DECISION)/
//   merchantDeadline?/refundOrderId?/displayStatus（十枚举）。本路由首回执只出现两窗口：
//   服务前 AUTO_APPROVED（系统自动全额，displayStatus=REFUNDING），服务后 PENDING_MERCHANT
//   （商家 24 小时处理，displayStatus=REFUND_PENDING_CONFIRM）；refund_order 由耐久任务异步
//   创建，首回执 refundOrderId=null（49号三阶段，不是缺失）。
// - 错误面：窗口外 REFUND_NOT_ELIGIBLE、在途 REFUND_APPLICATION_ALREADY_PROCESSED、已有
//   退款单 REFUND_ORDER_ALREADY_EXISTS、REJECTED 后可再申请（新 X-Request-Id 新行）等按
//   49号；推导不放页面（ARCH-005）：文案/徽标映射全在本模块，页面只消费现成展示值。

import { ApiError } from '../../shared/request'
import type { WorkspaceScope } from '../../shared/workspace'
import { object } from '../../shared/consumer-api'
import { displayOrderStatuses, displayStatusLabels, PreviewOrderReadRepository, PREVIEW_VERIFY_ORDER, type DisplayOrderStatus, type OrderDetailView } from './model'

export type RefundApplicationStatus = 'PENDING_MERCHANT' | 'APPROVED' | 'AUTO_APPROVED' | 'REJECTED'
export type RefundRoute = 'AUTO_FULL_BEFORE_SERVICE' | 'MERCHANT_CONFIRM_AFTER_SERVICE' | 'AFTERSALE_DECISION'

export type RefundApplyReceipt = Readonly<{
  applicationId: string
  applicationStatus: RefundApplicationStatus
  route: RefundRoute
  merchantDeadline: string | null
  refundOrderId: string | null
  displayStatus: DisplayOrderStatus
}>

export type RefundApplyInput = Readonly<{ reasonCode: string; reasonText?: string | null }>
export type PendingApply = Readonly<{ reasonCode: string; reasonText: string | null }>

const idPattern = /^[1-9][0-9]{0,18}(?![\s\S])/
export const isOrderId = (value: string) => idPattern.test(value) && BigInt(value) <= 9223372036854775807n

function fail(): never { throw new Error('INVALID_RESPONSE') }
const id = (value: unknown): string => typeof value === 'string' && idPattern.test(value) && BigInt(value) <= 9223372036854775807n ? value : fail()
const one = <T extends string>(value: unknown, values: readonly T[]): T => typeof value === 'string' && (values as readonly string[]).includes(value) ? value as T : fail()
// appendInstant(3)：UTC 毫秒精度 ISO 串且可往返（aftersale/order-verify instant 同口径）。
const instant = (value: unknown): string => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value ? value : fail()
const nullable = <T>(value: unknown, decode: (value: unknown) => T): T | null => value === null ? null : decode(value)

/** 严格 exact-key：只允许回执六键，未知键失败关闭（沿 coupon-points/aftersale 惯例）。 */
function exact(value: unknown, keys: readonly string[]): Record<string, any> {
  const v = object(value)
  if (Object.keys(v).sort().join(',') !== [...keys].sort().join(',')) fail()
  return v
}

const receiptKeys = ['applicationId', 'applicationStatus', 'displayStatus', 'merchantDeadline', 'refundOrderId', 'route']
const statuses: readonly RefundApplicationStatus[] = ['PENDING_MERCHANT', 'APPROVED', 'AUTO_APPROVED', 'REJECTED']
const routes: readonly RefundRoute[] = ['AUTO_FULL_BEFORE_SERVICE', 'MERCHANT_CONFIRM_AFTER_SERVICE', 'AFTERSALE_DECISION']

export function decodeRefundApplyReceipt(value: unknown): RefundApplyReceipt {
  const v = exact(value, receiptKeys)
  return {
    applicationId: id(v.applicationId),
    applicationStatus: one(v.applicationStatus, statuses),
    route: one(v.route, routes),
    merchantDeadline: nullable(v.merchantDeadline, instant),
    refundOrderId: nullable(v.refundOrderId, id),
    displayStatus: one(v.displayStatus, displayOrderStatuses),
  }
}

// ---- 表单草稿（结构校验归本模块；字典与业务准入归服务端内核） ----

export type RefundDraft = Readonly<{ reasonCode: string; reasonText: string }>
export type RefundDraftErrors = Readonly<Partial<Record<'reasonCode' | 'reasonText', string>>>

export const emptyRefundDraft = (): RefundDraft => ({ reasonCode: '', reasonText: '' })

const codePoints = (value: string): number => Array.from(value).length
const hasSurrogate = (value: string): boolean => Array.from(value).some(point => { const scalar = point.codePointAt(0)!; return scalar >= 0xd800 && scalar <= 0xdfff })

/** 契约边界校验：reasonCode 必填 1..64 码点非空白；reasonText 0..500 码点（选填）。 */
export function validateRefundDraft(draft: RefundDraft): RefundDraftErrors {
  const errors: { reasonCode?: string; reasonText?: string } = {}
  const code = draft.reasonCode.trim()
  if (code.length === 0) errors.reasonCode = '请填写退款原因代码（服务端配置的原因代码，如渠道公示的代码）'
  else if (codePoints(code) > 64) errors.reasonCode = '退款原因代码不能超过 64 个字符'
  if (hasSurrogate(draft.reasonCode)) errors.reasonCode = '退款原因代码包含无效字符'
  if (codePoints(draft.reasonText) > 500) errors.reasonText = '补充说明不能超过 500 个字符'
  if (hasSurrogate(draft.reasonText)) errors.reasonText = '补充说明包含无效字符'
  return errors
}

/** 提交载荷：reasonCode 去首尾空白；reasonText 空串按契约省略（等价 null）。 */
export function refundApplyInput(draft: RefundDraft): RefundApplyInput {
  const reasonCode = draft.reasonCode.trim()
  const reasonText = draft.reasonText.trim()
  return reasonText.length === 0 ? { reasonCode, reasonText: null } : { reasonCode, reasonText }
}

// ---- 展示映射（ARCH-005：事实→展示推导归本模块，页面只消费现成展示值） ----

export const routeLabels: Record<RefundRoute, string> = {
  AUTO_FULL_BEFORE_SERVICE: '服务前自动全额退款',
  MERCHANT_CONFIRM_AFTER_SERVICE: '服务后商家确认退款',
  AFTERSALE_DECISION: '售后裁决退款',
}
export const refundStatusLabels: Record<RefundApplicationStatus, string> = {
  PENDING_MERCHANT: '待商家处理',
  APPROVED: '商家已同意',
  AUTO_APPROVED: '系统自动同意',
  REJECTED: '商家已拒绝',
}

/** 成功回执口径（两窗口如实展示，不发明规则）：服务前自动全额无需商家处理；服务后商家 24 小时内处理。 */
export function receiptHeadline(receipt: RefundApplyReceipt): string {
  if (receipt.route === 'AUTO_FULL_BEFORE_SERVICE') return '申请已提交，系统已自动同意全额退款'
  if (receipt.route === 'MERCHANT_CONFIRM_AFTER_SERVICE') return '申请已提交，等待商家在 24 小时内处理'
  return '申请已提交'
}
export function receiptBody(receipt: RefundApplyReceipt, formatInstant: (value: string | null) => string): string {
  if (receipt.route === 'AUTO_FULL_BEFORE_SERVICE')
    return '服务开始前申请退款，按平台规则自动全额原路退回，无需商家处理；退款单生成与到账以支付渠道为准。'
  if (receipt.route === 'MERCHANT_CONFIRM_AFTER_SERVICE')
    return `服务开始后申请退款，商家将在 24 小时内处理；处理截止时间：${formatInstant(receipt.merchantDeadline)}`
  return '本次退款申请已按售后裁决流程提交。'
}
export function receiptStatusBadge(receipt: RefundApplyReceipt): Readonly<{ label: string; className: string }> {
  return {
    label: displayStatusLabels[receipt.displayStatus],
    className: `is-${receipt.displayStatus.toLowerCase().replace(/_/g, '-')}`,
  }
}

/** 详情页退款入口条件：仅凭服务端 OrderActions.canApplyRefund（10号 §3.7 事实字段，不推导）。 */
export function canApplyRefundEntry(detail: OrderDetailView): boolean {
  return detail.actions?.canApplyRefund === true
}

/** 这些 409 对该 payload 是终局拒绝（资格/在途/退款单事实不会回退），可退幂等槽后重新读取。 */
export function isDefiniteRefundConflict(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 409
    && ['COMMON_CONFLICT', 'REFUND_NOT_ELIGIBLE', 'REFUND_APPLICATION_ALREADY_PROCESSED',
      'REFUND_ORDER_ALREADY_EXISTS', 'REFUND_ALREADY_EXISTS', 'REFUND_MERCHANT_DEADLINE_PASSED'].includes(error.code)
}

/** 页面错误文案（49号语义 + 12号 §6 映射；403 同时覆盖未知订单的防探测语义）。 */
export function refundApplyMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401) return '登录已失效，请重新登录后再试'
    if (error.code === 'REFUND_NOT_ELIGIBLE') return '当前订单状态不支持申请退款（需已支付且未取消，处于两个退款窗口之一）'
    if (error.code === 'REFUND_APPLICATION_ALREADY_PROCESSED') return '该订单已有在途退款申请，请等待处理结果；被拒绝后可再次申请'
    if (error.code === 'REFUND_ORDER_ALREADY_EXISTS' || error.code === 'REFUND_ALREADY_EXISTS') return '该订单已创建退款单，不能再发起退款申请'
    if (error.code === 'REFUND_BEFORE_SERVICE_NOT_IMPLEMENTED') return '服务前自动退款通道暂未开放，请稍后重试原操作'
    if (error.code === 'REFUND_MERCHANT_DEADLINE_PASSED') return '商家处理窗口已过，系统已接管处理'
    if (error.code === 'IDEMPOTENCY_KEY_CONFLICT') return '上次提交的参数已变化，请核对后重新提交'
    if (error.code === 'COMMON_CONFLICT') return '订单状态已变化，请刷新后重新提交'
    if (error.statusCode === 403) return '仅订单本人可申请退款，或订单不存在'
    if (error.statusCode === 404) return '退款申请服务未开放或订单不存在，请稍后再试'
    if (error.statusCode === 400) return '提交内容无效，请检查退款原因代码与补充说明后重试'
    if (error.statusCode === 503) return '服务暂不可用，结果尚未确认，请稍后重试原操作'
  }
  if (error instanceof Error && error.message === 'PENDING_WRITE_CHANGED') return '上次申请结果尚未确认，请先重试原操作'
  if (error instanceof Error && error.message === 'INVALID_RESPONSE') return '服务返回异常，请稍后重试'
  return '申请结果尚未确认，请重试原操作；不会重复创建'
}

// ---- preview=1 设计验收通道（本地夹具，不发任何网络请求） ----

export type RefundApplyScenario = 'pre' | 'post' | 'conflict'
export const isRefundApplyScenario = (value?: string): value is RefundApplyScenario =>
  ['pre', 'post', 'conflict'].includes(value || '')

/** preview 夹具复用订单模型样例单（PENDING_SERVICE+canApplyRefund=true 的核销码样例单）。 */
export const PREVIEW_REFUND_ORDER = PREVIEW_VERIFY_ORDER

/** 内核两窗口规则的 preview 模拟：按场景返回固定回执；同单第二笔申请拒绝（在途语义）。 */
export class PreviewRefundApplyRepository {
  private applied = new Set<string>()
  private sequence = 0
  constructor(private scenario: RefundApplyScenario = 'pre') {}

  async detail(orderId: string): Promise<OrderDetailView> {
    return new PreviewOrderReadRepository('normal').detail(isOrderId(orderId) ? orderId : PREVIEW_REFUND_ORDER)
  }

  async apply(orderId: string, input: RefundApplyInput): Promise<RefundApplyReceipt> {
    const target = isOrderId(orderId) ? orderId : PREVIEW_REFUND_ORDER
    if (this.scenario === 'conflict') throw new ApiError('REFUND_APPLICATION_ALREADY_PROCESSED', 409)
    if (this.applied.has(target)) throw new ApiError('REFUND_APPLICATION_ALREADY_PROCESSED', 409)
    if (!input.reasonCode.trim()) throw new ApiError('COMMON_INVALID_ARGUMENT', 400)
    this.applied.add(target)
    this.sequence += 1
    const at = (offsetMs: number) => new Date(Date.now() + offsetMs).toISOString()
    if (this.scenario === 'pre') {
      return { applicationId: String(9600000000000000 + this.sequence), applicationStatus: 'AUTO_APPROVED',
        route: 'AUTO_FULL_BEFORE_SERVICE', merchantDeadline: at(24 * 3600000), refundOrderId: null, displayStatus: 'REFUNDING' }
    }
    return { applicationId: String(9600000000000000 + this.sequence), applicationStatus: 'PENDING_MERCHANT',
      route: 'MERCHANT_CONFIRM_AFTER_SERVICE', merchantDeadline: at(24 * 3600000), refundOrderId: null, displayStatus: 'REFUND_PENDING_CONFIRM' }
  }

  pendingApply(): PendingApply | null { return null }
  retireConflict(): void { /* preview 无幂等槽 */ }
}

// ---- 页面控制器（详情门控 + 一个主体写操作；未知结果写保留原 payload，仅显式重试重发） ----

export type RefundApplyDeps = {
  detail(orderId: string): Promise<OrderDetailView>
  apply(orderId: string, input: RefundApplyInput): Promise<RefundApplyReceipt>
  pendingApply(orderId: string): PendingApply | null
  retireConflict(orderId: string, error: unknown): void
}

export type RefundApplyState = Readonly<{
  phase: 'idle' | 'loading' | 'ready' | 'ineligible' | 'unauthorized' | 'load-error'
  orderId: string | null
  detail: OrderDetailView | null
  busy: boolean
  receipt: RefundApplyReceipt | null
  pending: PendingApply | null
  notice: string
}>

const initialState = (): RefundApplyState => ({ phase: 'idle', orderId: null, detail: null, busy: false, receipt: null, pending: null, notice: '' })

export class RefundApplyController {
  private state = initialState()
  private listeners = new Set<() => void>()
  private active = true
  private epoch = 0
  private reads = 0
  private unsubscribe: () => void
  constructor(private deps: RefundApplyDeps, private scope: WorkspaceScope) {
    this.unsubscribe = scope.subscribe(() => { this.epoch++; this.reads++; this.publish(initialState()) })
  }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish(state: RefundApplyState) { if (!this.active) return; this.state = Object.freeze(state); this.listeners.forEach(listener => listener()) }
  private live(epoch: number) { return this.active && epoch === this.epoch }

  /** 读取订单详情：入口门控仅凭服务端 actions.canApplyRefund；401 进未登录态。 */
  async load(orderId: string) {
    if (!this.active || this.state.busy || !isOrderId(orderId)) return
    const epoch = this.epoch, run = ++this.reads
    this.publish({ ...this.state, phase: 'loading', notice: '' })
    try {
      const detail = await this.deps.detail(orderId)
      if (!this.live(epoch) || run !== this.reads) return
      this.publish({ ...this.state, phase: canApplyRefundEntry(detail) ? 'ready' : 'ineligible', orderId, detail, receipt: null, pending: this.deps.pendingApply(orderId), notice: '' })
    } catch (error) {
      if (!this.live(epoch) || run !== this.reads) return
      const unauthorized = error instanceof ApiError && error.statusCode === 401
      this.publish({ ...this.state, phase: unauthorized ? 'unauthorized' : 'load-error', orderId, detail: null, pending: null, notice: refundApplyMessage(error) })
    }
  }

  /** 提交申请（结构校验后的载荷）；字段校验失败只提示不发送；有未确认命令时只能重试原操作。 */
  async submit(orderId: string, draft: RefundDraft) {
    if (!this.active || this.state.phase !== 'ready' || this.state.busy) return
    if (this.state.pending) return this.retry(orderId)
    const errors = validateRefundDraft(draft)
    if (errors.reasonCode !== undefined || errors.reasonText !== undefined) {
      this.publish({ ...this.state, notice: '请完善退款原因后再提交' })
      return
    }
    await this.run(orderId, refundApplyInput(draft))
  }

  /** 重试未确认的原操作（同 X-Request-Id、同 payload）。 */
  async retry(orderId: string) {
    const pending = this.state.pending
    if (!pending || !this.active || this.state.busy) return
    await this.run(orderId, { reasonCode: pending.reasonCode, reasonText: pending.reasonText })
  }

  private async run(orderId: string, input: RefundApplyInput) {
    const epoch = this.epoch
    this.reads++
    this.publish({ ...this.state, busy: true, notice: '' })
    try {
      const receipt = await this.deps.apply(orderId, input)
      if (!this.live(epoch)) return
      // 成功后清除未确认槽并回读订单详情（no-store）；入口门控随即失效，回执仍保留展示。
      this.publish({ ...this.state, busy: false, pending: this.deps.pendingApply(orderId), notice: '' })
      await this.load(orderId)
      if (this.live(epoch)) this.publish({ ...this.getSnapshot(), receipt })
    } catch (error) {
      if (!this.live(epoch)) return
      // 终局 409 可解锁重试（退幂等槽后重新读取）；未知结果保留原命令继续重试原操作。
      let rejected = false
      if (isDefiniteRefundConflict(error)) {
        try { this.deps.retireConflict(orderId, error); rejected = true } catch { rejected = false }
      }
      const unauthorized = error instanceof ApiError && error.statusCode === 401
      this.publish({ ...this.state, busy: false,
        pending: rejected || unauthorized ? null : this.deps.pendingApply(orderId),
        ...(unauthorized ? { phase: 'unauthorized' as const, detail: null, orderId: null } : {}),
        notice: refundApplyMessage(error) })
      // 终局拒绝后的回读保持拒绝文案可见（重读清空的提示在此恢复），门控以最新 actions 为准。
      if (rejected && this.live(epoch)) {
        await this.load(orderId)
        if (this.live(epoch)) this.publish({ ...this.getSnapshot(), notice: refundApplyMessage(error) })
      }
    }
  }

  /** 页面挂载时恢复未确认命令（仅提示 + 重试入口，不自动发送）。 */
  restore(orderId: string) {
    if (!this.active || this.state.busy) return
    const pending = this.deps.pendingApply(orderId)
    if (pending) this.publish({ ...this.state, pending, notice: '已恢复上次未确认的退款申请，请重试原操作' })
  }
  dispose() { this.active = false; this.epoch++; this.unsubscribe(); this.listeners.clear() }
}
