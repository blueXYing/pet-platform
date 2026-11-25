// 小程序商家端钱包、提现与分账接收方只读客户端（Contract 80 §5 / 40 号裁决 §4、§5.5、§7、§8、§9）：
// - GET  /api/v1/merchant/settlement/receiver（仅 OWNER，严格只读，商家不可自助编辑）
// - GET  /api/v1/merchant/wallet（仅 OWNER，余额以拉卡拉 1269 curBalance 为准，不展示平台补贴待结算）
// - GET  /api/v1/merchant/wallet/withdrawals?status=&page=1&size=20（仅 OWNER，分页提现记录）
// - POST /api/v1/merchant/wallet/withdrawals（仅 OWNER，X-Request-Id 幂等槽，无短信验证码）

import { ApiError, type RequestSpec } from './request'
import { object, type Command, type ConsumerApi } from './consumer-api'

export type MerchantReceiverStatusView = Readonly<{
  status: string
  statusLabel: string
  receiverNo: string | null
  acctNameMasked: string | null
  acctNoLast4: string | null
  bankName: string | null
  active: boolean
  rejectReason: string | null
  updatedAt: string | null
}>

export type MerchantWalletView = Readonly<{
  receiverStatus: string
  receiverActive: boolean
  availableBalance: string
  pendingSettlementAmount: string
  pendingSettlementCount: number
  currency: string
  balanceAsOf: string | null
  channelBalanceAvailable: boolean
}>

export type WithdrawalOrderView = Readonly<{
  withdrawalId: string
  merOrderNo: string
  merchantId: string
  receiverNo: string
  amount: string
  feeAmount: string | null
  netAmount: string | null
  status: string
  drawJnl: string | null
  failReason: string | null
  channelState: string | null
  requestedByUserId: string | null
  requestedAt: string | null
  finishedAt: string | null
  createdAt: string | null
}>

export type WithdrawalPage = Readonly<{
  items: readonly WithdrawalOrderView[]
  total: number
  page: number
  size: number
}>

export const WITHDRAWAL_STATUS_LABELS: Readonly<Record<string, string>> = {
  PROCESSING: '处理中',
  SUCCEEDED: '已到账',
  FAILED: '提现失败',
}

export const RECEIVER_STATUS_LABELS: Readonly<Record<string, string>> = {
  UNCONFIGURED: '未配置',
  DRAFT: '资料草稿中',
  RECEIVER_APPLYING: '接收方申请中',
  RECEIVER_CREATED: '待绑定分账关系',
  BIND_APPLYING: '绑定审核中',
  ACTIVE: '已生效',
  REJECTED: '审核驳回',
  CHANGE_APPLYING: '信息变更审核中',
}

function fail(): never {
  throw new Error('INVALID_RESPONSE')
}

function decimalText(value: unknown): string {
  if (typeof value === 'number') {
    if (!Number.isFinite(value) || value < 0) fail()
    return value.toFixed(2)
  }
  if (typeof value !== 'string') fail()
  const trimmed = value.trim()
  if (!/^(?:0|[1-9][0-9]{0,15})(?:\.[0-9]{1,2})?$/.test(trimmed)) fail()
  const [intPart, fracPart = ''] = trimmed.split('.')
  return `${intPart}.${fracPart.padEnd(2, '0')}`
}

function nullableDecimalText(value: unknown): string | null {
  if (value === null || value === undefined || value === '') return null
  return decimalText(value)
}

function nullableString(value: unknown): string | null {
  if (value === null || value === undefined) return null
  if (typeof value !== 'string') fail()
  return value
}

function requiredString(value: unknown): string {
  if (typeof value !== 'string' || !value.trim()) fail()
  return value
}

function requiredId(value: unknown): string {
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,18}$/.test(value)) fail()
  return value
}

function nonNegativeInt(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) fail()
  return value
}

export function decodeMerchantReceiverStatus(value: unknown): MerchantReceiverStatusView {
  const v = object(value)
  if (typeof v.active !== 'boolean') fail()
  const status = requiredString(v.status)
  const statusLabel = typeof v.statusLabel === 'string' && v.statusLabel.trim()
    ? v.statusLabel
    : (RECEIVER_STATUS_LABELS[status] ?? status)
  return {
    status,
    statusLabel,
    receiverNo: nullableString(v.receiverNo),
    acctNameMasked: nullableString(v.acctNameMasked),
    acctNoLast4: nullableString(v.acctNoLast4),
    bankName: nullableString(v.bankName),
    active: v.active,
    rejectReason: nullableString(v.rejectReason),
    updatedAt: nullableString(v.updatedAt),
  }
}

export function decodeMerchantWallet(value: unknown): MerchantWalletView {
  const v = object(value)
  if (typeof v.receiverActive !== 'boolean' || typeof v.channelBalanceAvailable !== 'boolean') fail()
  return {
    receiverStatus: requiredString(v.receiverStatus),
    receiverActive: v.receiverActive,
    availableBalance: decimalText(v.availableBalance),
    pendingSettlementAmount: decimalText(v.pendingSettlementAmount),
    pendingSettlementCount: nonNegativeInt(v.pendingSettlementCount),
    currency: typeof v.currency === 'string' && v.currency.trim() ? v.currency : 'CNY',
    balanceAsOf: nullableString(v.balanceAsOf),
    channelBalanceAvailable: v.channelBalanceAvailable,
  }
}

export function decodeWithdrawalOrder(value: unknown): WithdrawalOrderView {
  const v = object(value)
  return {
    withdrawalId: requiredId(v.withdrawalId),
    merOrderNo: requiredString(v.merOrderNo),
    merchantId: requiredId(v.merchantId),
    receiverNo: requiredString(v.receiverNo),
    amount: decimalText(v.amount),
    feeAmount: nullableDecimalText(v.feeAmount),
    netAmount: nullableDecimalText(v.netAmount),
    status: requiredString(v.status),
    drawJnl: nullableString(v.drawJnl),
    failReason: nullableString(v.failReason),
    channelState: nullableString(v.channelState),
    requestedByUserId: nullableString(v.requestedByUserId),
    requestedAt: nullableString(v.requestedAt),
    finishedAt: nullableString(v.finishedAt),
    createdAt: nullableString(v.createdAt),
  }
}

export function decodeWithdrawalPage(value: unknown): WithdrawalPage {
  const v = object(value)
  if (!Array.isArray(v.items)) fail()
  const page = nonNegativeInt(v.page)
  const size = typeof v.size === 'number' ? nonNegativeInt(v.size) : nonNegativeInt(v.pageSize)
  const total = nonNegativeInt(v.total)
  if (page < 1 || size < 1) fail()
  return {
    items: Object.freeze(v.items.map(decodeWithdrawalOrder)),
    total,
    page,
    size,
  }
}

/**
 * 纯十进制字符串归一化：校验正数金额（最多两位小数），消除浮点误差。
 * 非法、零或负数返回 null。
 */
export function normalizeWithdrawalAmount(raw: string): string | null {
  const trimmed = raw.trim()
  if (!/^(?:0|[1-9][0-9]{0,12})(?:\.[0-9]{1,2})?$/.test(trimmed)) return null
  const [intPart, fracPart = ''] = trimmed.split('.')
  const cents = BigInt(intPart) * 100n + BigInt(fracPart.padEnd(2, '0'))
  if (cents <= 0n) return null
  return `${intPart}.${fracPart.padEnd(2, '0')}`
}

export function withdrawalSlot(userId: string, merchantId: string): string {
  return `merchant-wallet-withdraw:${userId}:${merchantId}`
}

export function formatBeijingDateTime(value: string | null): string {
  if (!value) return '—'
  const millis = Date.parse(value)
  if (!Number.isFinite(millis)) return value
  const bj = new Date(millis + 8 * 3600000)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${bj.getUTCFullYear()}-${pad(bj.getUTCMonth() + 1)}-${pad(bj.getUTCDate())} ${pad(bj.getUTCHours())}:${pad(bj.getUTCMinutes())}`
}

export function merchantWalletErrorMessage(error: unknown, fallback = '操作失败，请稍后重试'): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401 || error.code === 'COMMON_UNAUTHORIZED') return '登录已失效，请重新登录后再试'
    if (error.statusCode === 403 || error.code === 'COMMON_FORBIDDEN') return '仅认证商家主账号（OWNER）可使用钱包与提现功能'
    if (error.statusCode === 404 || error.code === 'COMMON_NOT_FOUND') return '钱包服务暂未开放或记录不存在，请稍后再试'
    if (error.code === 'IDEMPOTENCY_KEY_CONFLICT') return '相同请求编号已用于不同金额，请刷新页面后重试'
    if (error.statusCode === 400 || error.code === 'COMMON_INVALID_ARGUMENT') {
      return error.serverMessage ? `输入无效：${error.serverMessage}` : '提现金额或参数无效，请核对后重试'
    }
    if (error.statusCode === 409) {
      return error.serverMessage ? `当前无法提交提现：${error.serverMessage}` : '当前余额或账户状态不支持本次提现，请刷新后重试'
    }
    if (error.statusCode === 503 || error.code === 'COMMON_DEPENDENCY_UNAVAILABLE') {
      return '渠道或钱包服务暂不可用；若已提交提现，再次点击将沿用原请求编号，不会重复扣款'
    }
    if (error.serverMessage) return `${fallback}：${error.serverMessage}`
    return `${fallback}（${error.code}）`
  }
  if (error instanceof Error) {
    if (error.message === 'INVALID_AMOUNT') return '请输入大于 0.00 的合法提现金额（最多两位小数）'
    if (error.message === 'PENDING_WRITE_CHANGED') return '上次提现请求结果尚未确认，请先按原金额重试，切勿更换金额重复提交'
    if (error.message === 'MERCHANT_ENTRY_REQUIRED' || error.message === 'WORKSPACE_PATH_MISMATCH') return '请从商家工作台进入钱包页面'
    if (error.message === 'INVALID_RESPONSE') return '服务返回格式异常，请稍后重试'
  }
  return fallback
}

export class MerchantWalletClient {
  constructor(private api: ConsumerApi) {}

  private context() {
    const c = this.api.scope.capture().context
    if (c.workspace !== 'merchant' || !c.merchantId) throw new Error('MERCHANT_ENTRY_REQUIRED')
    return { userId: c.userId, merchantId: c.merchantId }
  }

  async getReceiver(): Promise<MerchantReceiverStatusView> {
    this.context()
    return this.api.request(
      { path: '/api/v1/merchant/settlement/receiver', method: 'GET' },
      decodeMerchantReceiverStatus,
    )
  }

  async getWallet(): Promise<MerchantWalletView> {
    this.context()
    return this.api.request(
      { path: '/api/v1/merchant/wallet', method: 'GET' },
      decodeMerchantWallet,
    )
  }

  async listWithdrawals(filter: { status?: string; page?: number; size?: number } = {}): Promise<WithdrawalPage> {
    this.context()
    const page = filter.page ?? 1
    const size = filter.size ?? 20
    if (!Number.isInteger(page) || page < 1 || page > 10000 || !Number.isInteger(size) || size < 1 || size > 100) {
      throw new Error('INVALID_QUERY')
    }
    const data: Record<string, unknown> = { page, size }
    if (filter.status) data.status = filter.status
    return this.api.request(
      { path: '/api/v1/merchant/wallet/withdrawals', method: 'GET', data },
      decodeWithdrawalPage,
    )
  }

  pendingWithdrawal(): { amount: string; requestId: string } | null {
    const current = this.api.scope.current
    if (!current || current.workspace !== 'merchant' || !current.merchantId) return null
    const saved = this.api.pendingCommand(withdrawalSlot(current.userId, current.merchantId))
    if (!saved || typeof saved.data?.amount !== 'string') return null
    return { amount: saved.data.amount, requestId: saved.requestId }
  }

  /**
   * 仅 OWNER 发起手动提现（无短信验证码，X-Request-Id 持久化幂等槽）。
   * - 网络抖动/503/超时：保留槽位，重试严格沿用同一 X-Request-Id 与同一金额；
   * - 明确业务 409 拒绝（非 IDEMPOTENCY_KEY_CONFLICT）：退役槽位以便商家调整金额后重新提交。
   */
  async withdraw(rawAmount: string): Promise<WithdrawalOrderView> {
    const { userId, merchantId } = this.context()
    const amount = normalizeWithdrawalAmount(rawAmount)
    if (amount === null) throw new Error('INVALID_AMOUNT')
    const slot = withdrawalSlot(userId, merchantId)
    const spec: Omit<RequestSpec, 'requestId'> = {
      path: '/api/v1/merchant/wallet/withdrawals',
      method: 'POST',
      data: { amount },
    }
    return this.api.write(
      slot,
      spec,
      decodeWithdrawalOrder,
      undefined,
      (error: unknown, command: Command) => {
        if (error instanceof ApiError && error.statusCode === 409 && error.code !== 'IDEMPOTENCY_KEY_CONFLICT') {
          try { this.api.retireRejectedCommand(slot, command) } catch { /* 保留原日志 */ }
        }
      },
    )
  }
}

// ---- preview=1 本地样例数据（只读展示，不发任何真实请求） ----

export function previewMerchantReceiver(): MerchantReceiverStatusView {
  return {
    status: 'ACTIVE',
    statusLabel: '已生效',
    receiverNo: 'SR202611250001',
    acctNameMasked: '张*',
    acctNoLast4: '8821',
    bankName: '中国工商银行上海市徐汇支行',
    active: true,
    rejectReason: null,
    updatedAt: '2026-11-25T06:30:00.000Z',
  }
}

export function previewMerchantWallet(): MerchantWalletView {
  return {
    receiverStatus: 'ACTIVE',
    receiverActive: true,
    availableBalance: '680.50',
    pendingSettlementAmount: '240.00',
    pendingSettlementCount: 3,
    currency: 'CNY',
    balanceAsOf: '2026-11-25T08:00:00.000Z',
    channelBalanceAvailable: true,
  }
}

export function previewWithdrawals(): WithdrawalPage {
  return {
    items: [
      {
        withdrawalId: '900810000000000001',
        merOrderNo: 'WD202611240001',
        merchantId: '9007199254740995',
        receiverNo: 'SR202611250001',
        amount: '300.00',
        feeAmount: '0.00',
        netAmount: '300.00',
        status: 'SUCCEEDED',
        drawJnl: 'LKL2026112409001122',
        failReason: null,
        channelState: 'SUCCESS',
        requestedByUserId: 'preview',
        requestedAt: '2026-11-24T04:15:00.000Z',
        finishedAt: '2026-11-24T04:16:00.000Z',
        createdAt: '2026-11-24T04:15:00.000Z',
      },
      {
        withdrawalId: '900810000000000002',
        merOrderNo: 'WD202611250002',
        merchantId: '9007199254740995',
        receiverNo: 'SR202611250001',
        amount: '120.00',
        feeAmount: '0.00',
        netAmount: '120.00',
        status: 'PROCESSING',
        drawJnl: 'LKL2026112507301188',
        failReason: null,
        channelState: 'PROCESSING',
        requestedByUserId: 'preview',
        requestedAt: '2026-11-25T07:30:00.000Z',
        finishedAt: null,
        createdAt: '2026-11-25T07:30:00.000Z',
      },
    ],
    total: 2,
    page: 1,
    size: 20,
  }
}
