import { createWebClient, RequestFailure, type Transport } from '../request';

// Contract 80 §4 支付中心结算、分账接收方、提现台账、平台补贴应付报表与日对账差异运营接口。
// 遵循 40 号裁决补充与 22 号运营权限补充：
// - 所有 ID 保持十进制字符串，金额保持精确字符串展示；
// - 所有写接口均经 createWebClient 携带 X-Request-Id 幂等键；
// - 提现台账与平台补贴应付报表严格只读（不提供代商家提现或线下标记已打款接口）。
const ROOT = '/api/v1/admin/settlement';

export type SettlementPage<T> = {
  items: T[];
  total: number;
  page: number;
  size: number;
};

// ---- 4.1 商家结算配置 ----

export type SettlementConfigView = {
  merchantId: string;
  settleDays: number;
  feeRate: string;
  enabled: boolean;
  configured: boolean;
  version: number;
  updatedAt: string | null;
};

export type UpsertSettlementConfigRequest = {
  settleDays: number;
  feeRate: string;
  enabled: boolean;
  reason?: string;
};

// ---- 4.2 分账接收方 ----

export const RECEIVER_STATUSES = [
  'DRAFT',
  'RECEIVER_APPLYING',
  'RECEIVER_CREATED',
  'BIND_APPLYING',
  'ACTIVE',
  'REJECTED',
  'CHANGE_APPLYING',
] as const;
export type ReceiverStatus = typeof RECEIVER_STATUSES[number];

export const RECEIVER_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  RECEIVER_APPLYING: '接收方申请中',
  RECEIVER_CREATED: '待绑定分账关系',
  BIND_APPLYING: '绑定审核中',
  ACTIVE: '已生效',
  REJECTED: '已驳回',
  CHANGE_APPLYING: '变更审核中',
};

export const RECEIVER_TYPE_LABELS: Record<string, string> = {
  PERSONAL: '个人',
  ENTERPRISE: '企业/个体户',
};

export const ATTACHMENT_TYPE_OPTIONS: { value: string; label: string }[] = [
  { value: 'ID_CARD_FRONT', label: '身份证人像面 (ID_CARD_FRONT)' },
  { value: 'ID_CARD_BEHIND', label: '身份证国徽面 (ID_CARD_BEHIND)' },
  { value: 'BANK_CARD', label: '银行卡照片 (BANK_CARD)' },
  { value: 'BUSINESS_LICENCE', label: '营业执照 (BUSINESS_LICENCE)' },
  { value: 'OPENING_PERMIT', label: '开户许可证 (OPENING_PERMIT)' },
  { value: 'COOPERATION_AGREEMENT', label: '分账合作协议 (COOPERATION_AGREEMENT)' },
];

export type ReceiverAttachmentInput = {
  attType: string;
  assetId: string;
};

export type ReceiverAttachmentView = {
  attType: string;
  assetId: string;
  attFileId?: string | null;
};

export type ReceiverAccountSummary = {
  receiverAccountId?: string;
  merchantId: string;
  receiverNo: string | null;
  receiverName: string;
  receiverType: string;
  acctName: string;
  acctNoMasked: string | null;
  acctNoLast4?: string | null;
  acctOpenBankName: string | null;
  status: string;
  applyId: string | null;
  rejectReason: string | null;
  updatedAt: string | null;
  createdAt?: string | null;
};

export type ReceiverAccountView = {
  receiverAccountId?: string;
  merchantId: string;
  receiverNo: string | null;
  receiverName: string;
  receiverType: string;
  acctNoMasked: string | null;
  acctNoLast4?: string | null;
  acctName: string;
  acctTypeCode: string | null;
  acctCertificateType: string | null;
  acctCertificateNoMasked: string | null;
  acctOpenBankCode: string | null;
  acctOpenBankName: string | null;
  acctClearBankCode: string | null;
  contactMobileMasked: string | null;
  licenseNoMasked: string | null;
  licenseName: string | null;
  legalPersonName: string | null;
  legalPersonCertificateType: string | null;
  legalPersonCertificateNoMasked?: string | null;
  settleType?: string | null;
  status: string;
  applyId: string | null;
  bindApplyId?: string | null;
  rejectReason: string | null;
  attachments?: ReceiverAttachmentView[];
  version?: number;
  createdAt?: string | null;
  updatedAt: string | null;
};

export type ReceiverSensitiveView = {
  merchantId?: string;
  acctNo: string | null;
  acctCertificateNo: string | null;
  contactMobile: string | null;
  licenseNo: string | null;
  legalPersonCertificateNo?: string | null;
};

export type ReceiverUpsertRequest = {
  submitToChannel: boolean;
  receiverName: string;
  receiverType: string;
  acctNo: string;
  acctName: string;
  acctTypeCode: string;
  acctCertificateType: string;
  acctCertificateNo: string;
  acctOpenBankCode: string;
  acctOpenBankName: string;
  acctClearBankCode: string;
  contactMobile: string;
  licenseNo: string | null;
  licenseName: string | null;
  legalPersonName: string | null;
  legalPersonCertificateType: string | null;
  legalPersonCertificateNo: string | null;
  attachments: ReceiverAttachmentInput[];
  reason?: string;
};

export type ReceiverBindRequest = {
  cooperationAssetId?: string;
  remark?: string;
};

// ---- 4.3 结算单与指令台账 ----

export const SETTLEMENT_ORDER_STATUSES = [
  'PENDING_TRIGGER',
  'WAITING',
  'ON_HOLD',
  'SPLITTING',
  'SPLIT_SUCCEEDED',
  'SPLIT_FAILED',
  'REVERSING',
  'PARTIALLY_REVERSED',
  'REVERSED',
  'REVERSE_FAILED',
  'CANCELED',
] as const;
export type SettlementOrderStatus = typeof SETTLEMENT_ORDER_STATUSES[number];

export const SETTLEMENT_ORDER_STATUS_LABELS: Record<string, string> = {
  PENDING_TRIGGER: '待核销触发',
  WAITING: '等待到期分账',
  ON_HOLD: '暂停待核查',
  SPLITTING: '分账处理中',
  SPLIT_SUCCEEDED: '分账成功',
  SPLIT_FAILED: '分账失败',
  REVERSING: '分账回退中',
  PARTIALLY_REVERSED: '部分回退',
  REVERSED: '已全额回退',
  REVERSE_FAILED: '回退失败',
  CANCELED: '已作废',
};

export const INSTRUCTION_TYPE_LABELS: Record<string, string> = {
  SPLIT: '订单分账 (1293)',
  SEPARATE: '订单分账 (1293)',
  FALLBACK: '分账回退 (2395)',
};

export const INSTRUCTION_STATUS_LABELS: Record<string, string> = {
  SENT: '已提交',
  PROCESSING: '处理中',
  SUCCEEDED: '成功',
  FAILED: '失败',
};

export type SettlementOrderView = {
  settlementOrderId: string;
  paymentId: string;
  orderId: string | null;
  bizType: string;
  bizNo: string;
  merchantId: string;
  storeId: string | null;
  originalPrice: string;
  flashDiscountAmount: string;
  merchantDiscountAmount: string;
  platformSubsidyAmount: string;
  paidAmount: string;
  refundedAmount: string;
  splitBase: string;
  feeRateSnapshot: string;
  merchantShare: string;
  splitAmount: string;
  platformRetainedAmount: string;
  platformSubsidyDue: string;
  actualMerchantAmount: string | null;
  channelSplitFee: string | null;
  verifiedAt: string | null;
  settleDaysSnapshot: number | null;
  eligibleAt: string | null;
  reversedAmount: string;
  activeInstructionId: string | null;
  status: string;
  holdReason: string | null;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
};

export type SettlementInstructionView = {
  instructionId: string;
  settlementOrderId: string;
  paymentId: string;
  refundOrderId: string | null;
  originInstructionId: string | null;
  merchantId: string;
  instructionType: string;
  attemptNo: number;
  outSeparateNo: string;
  separateNo: string | null;
  merchantNo: string;
  receiverNo: string | null;
  logNo: string | null;
  logDate: string | null;
  totalAmount: string;
  merchantAmount: string;
  platformAmount: string;
  actualSeparateAmount: string | null;
  actualMerchantAmount: string | null;
  totalFeeAmount: string | null;
  merchantFeeAmount: string | null;
  status: string;
  channelStatus: string | null;
  channelFinalStatus: string | null;
  accResultDesc: string | null;
  fallbackReason: string | null;
  finishDate: string | null;
  sentAt: string | null;
  terminalAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
};

// ---- 4.4 商家提现台账（只读） ----

export const WITHDRAWAL_STATUSES = ['PROCESSING', 'SUCCEEDED', 'FAILED'] as const;
export type WithdrawalStatus = typeof WITHDRAWAL_STATUSES[number];

export const WITHDRAWAL_STATUS_LABELS: Record<string, string> = {
  PROCESSING: '处理中',
  SUCCEEDED: '已成功',
  FAILED: '提现失败',
};

export type WithdrawalOrderView = {
  withdrawalId: string;
  merOrderNo: string;
  merchantId: string;
  receiverNo: string;
  amount: string;
  feeAmount: string | null;
  netAmount: string | null;
  status: string;
  drawJnl: string | null;
  failReason: string | null;
  channelState: string | null;
  requestedByUserId: string | null;
  requestedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
};

// ---- 4.5 平台补贴应付报表（只读） ----

export type SubsidyReportSummary = {
  totalOrderCount: number;
  totalMerchantShare: string;
  totalSplitAmount: string;
  totalSubsidyDue: string;
};

export type SubsidyReportItem = {
  merchantId: string;
  period: string;
  orderCount: number;
  totalPaidAmount: string;
  totalMerchantShare: string;
  totalSplitAmount: string;
  totalReversedAmount: string;
  totalSubsidyDue: string;
};

export type SubsidyReportResponse = {
  summary: SubsidyReportSummary;
  items: SubsidyReportItem[];
  total: number;
  page: number;
  size: number;
};

// ---- 4.6 日对账差异 ----

export const RECONCILE_DIFF_STATUSES = ['OPEN', 'RESOLVED'] as const;
export type ReconcileDiffStatus = typeof RECONCILE_DIFF_STATUSES[number];

export const RECONCILE_DIFF_STATUS_LABELS: Record<string, string> = {
  OPEN: '待处理',
  RESOLVED: '已处理',
};

export type ReconcileDiffView = {
  diffId: string;
  reconcileDate: string;
  bizType: string;
  diffType: string;
  settlementOrderId: string | null;
  instructionId: string | null;
  outSeparateNo: string | null;
  separateNo: string | null;
  merchantId: string | null;
  localAmount: string | null;
  channelAmount: string | null;
  localStatus: string | null;
  channelStatus: string | null;
  detail: string | null;
  status: string;
  resolvedByOperatorId: string | null;
  resolveNote: string | null;
  resolvedAt: string | null;
  createdAt: string;
};

// ---- 辅助函数 ----

const query = (params: Record<string, string | number | undefined>) => {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') search.set(key, String(value));
  }
  const encoded = search.toString();
  return encoded ? `?${encoded}` : '';
};

export function isValidDecimalId(value: string): boolean {
  return /^[1-9][0-9]{0,18}$/.test(value.trim());
}

export function formatAmount(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'number') return Number.isFinite(value) ? `¥${value.toFixed(2)}` : '—';
  return `¥${value}`;
}

export function formatPercentRate(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  return `${value}%`;
}

export function formatUtcDateTime(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

// ---- 客户端 ----

export function createSettlementAdminClient(transport?: Transport) {
  const client = createWebClient(transport);

  return {
    resetContext(token?: string) {
      client.resetContext(token);
    },

    // 4.1 商家结算配置
    getConfig(merchantId: string) {
      return client.request<SettlementConfigView>(`${ROOT}/configs/${encodeURIComponent(merchantId.trim())}`);
    },
    upsertConfig(merchantId: string, body: UpsertSettlementConfigRequest, requestId?: string) {
      return client.request<SettlementConfigView>(`${ROOT}/configs/${encodeURIComponent(merchantId.trim())}`, {
        method: 'PUT',
        body,
        requestId,
      });
    },

    // 4.2 分账接收方
    listReceivers(filters: { merchantId?: string; status?: string; page?: number; size?: number } = {}) {
      return client.request<SettlementPage<ReceiverAccountSummary>>(
        `${ROOT}/receivers${query({
          merchantId: filters.merchantId?.trim(),
          status: filters.status,
          page: filters.page ?? 1,
          size: filters.size ?? 20,
        })}`,
      );
    },
    getReceiver(merchantId: string) {
      return client.request<ReceiverAccountView>(`${ROOT}/receivers/${encodeURIComponent(merchantId.trim())}`);
    },
    revealReceiver(merchantId: string, reason: string, requestId?: string) {
      return client.request<ReceiverSensitiveView>(`${ROOT}/receivers/${encodeURIComponent(merchantId.trim())}/reveal`, {
        method: 'POST',
        body: { reason: reason.trim() },
        requestId,
      });
    },
    createReceiver(merchantId: string, body: ReceiverUpsertRequest, requestId?: string) {
      return client.request<ReceiverAccountView>(`${ROOT}/receivers/${encodeURIComponent(merchantId.trim())}`, {
        method: 'POST',
        body,
        requestId,
      });
    },
    updateReceiver(merchantId: string, body: ReceiverUpsertRequest, requestId?: string) {
      return client.request<ReceiverAccountView>(`${ROOT}/receivers/${encodeURIComponent(merchantId.trim())}`, {
        method: 'PUT',
        body,
        requestId,
      });
    },
    bindReceiver(merchantId: string, body: ReceiverBindRequest, requestId?: string) {
      return client.request<ReceiverAccountView>(`${ROOT}/receivers/${encodeURIComponent(merchantId.trim())}/bind`, {
        method: 'POST',
        body,
        requestId,
      });
    },
    syncReceiver(merchantId: string, requestId?: string) {
      return client.request<ReceiverAccountView>(`${ROOT}/receivers/${encodeURIComponent(merchantId.trim())}/sync`, {
        method: 'POST',
        requestId,
      });
    },

    // 4.3 结算单与指令台账
    listOrders(filters: { merchantId?: string; status?: string; page?: number; size?: number } = {}) {
      return client.request<SettlementPage<SettlementOrderView>>(
        `${ROOT}/orders${query({
          merchantId: filters.merchantId?.trim(),
          status: filters.status,
          page: filters.page ?? 1,
          size: filters.size ?? 20,
        })}`,
      );
    },
    getOrder(settlementOrderId: string) {
      return client.request<SettlementOrderView>(`${ROOT}/orders/${encodeURIComponent(settlementOrderId.trim())}`);
    },
    listOrderInstructions(settlementOrderId: string) {
      return client.request<SettlementInstructionView[]>(
        `${ROOT}/orders/${encodeURIComponent(settlementOrderId.trim())}/instructions`,
      );
    },
    resumeOrder(settlementOrderId: string, reason?: string, requestId?: string) {
      return client.request<SettlementOrderView>(`${ROOT}/orders/${encodeURIComponent(settlementOrderId.trim())}/resume`, {
        method: 'POST',
        body: reason?.trim() ? { reason: reason.trim() } : {},
        requestId,
      });
    },
    retryOrder(settlementOrderId: string, reason?: string, requestId?: string) {
      return client.request<SettlementOrderView>(`${ROOT}/orders/${encodeURIComponent(settlementOrderId.trim())}/retry`, {
        method: 'POST',
        body: reason?.trim() ? { reason: reason.trim() } : {},
        requestId,
      });
    },
    retryFallback(settlementOrderId: string, refundOrderId: string, reason?: string, requestId?: string) {
      return client.request<SettlementOrderView>(
        `${ROOT}/orders/${encodeURIComponent(settlementOrderId.trim())}/retry-fallback`,
        {
          method: 'POST',
          body: {
            refundOrderId: refundOrderId.trim(),
            ...(reason?.trim() ? { reason: reason.trim() } : {}),
          },
          requestId,
        },
      );
    },

    // 4.4 提现台账（只读）
    listWithdrawals(filters: { merchantId?: string; status?: string; page?: number; size?: number } = {}) {
      return client.request<SettlementPage<WithdrawalOrderView>>(
        `${ROOT}/withdrawals${query({
          merchantId: filters.merchantId?.trim(),
          status: filters.status,
          page: filters.page ?? 1,
          size: filters.size ?? 20,
        })}`,
      );
    },

    // 4.5 平台补贴应付报表（只读）
    getSubsidyReport(filters: { merchantId?: string; fromDate?: string; toDate?: string; page?: number; size?: number } = {}) {
      return client.request<SubsidyReportResponse>(
        `${ROOT}/subsidy-report${query({
          merchantId: filters.merchantId?.trim(),
          fromDate: filters.fromDate,
          toDate: filters.toDate,
          page: filters.page ?? 1,
          size: filters.size ?? 20,
        })}`,
      );
    },

    // 4.6 日对账差异与人工核销处理
    listReconcileDiffs(filters: { reconcileDate?: string; diffType?: string; status?: string; page?: number; size?: number } = {}) {
      return client.request<SettlementPage<ReconcileDiffView>>(
        `${ROOT}/reconcile-diffs${query({
          reconcileDate: filters.reconcileDate,
          diffType: filters.diffType?.trim(),
          status: filters.status,
          page: filters.page ?? 1,
          size: filters.size ?? 20,
        })}`,
      );
    },
    resolveReconcileDiff(diffId: string, note: string, requestId?: string) {
      return client.request<ReconcileDiffView>(
        `${ROOT}/reconcile-diffs/${encodeURIComponent(diffId.trim())}/resolve`,
        {
          method: 'POST',
          body: { note: note.trim() },
          requestId,
        },
      );
    },
  };
}

export type SettlementAdminClient = ReturnType<typeof createSettlementAdminClient>;

const FAILURE_TEXT: Record<string, string> = {
  UNAUTHENTICATED: '登录已失效，请重新登录',
  FORBIDDEN: '权限不足：当前账号未授予该操作所需的结算权限',
  COMMON_FORBIDDEN: '权限不足：当前账号未授予该操作所需的结算权限',
  COMMON_NOT_FOUND: '记录不存在或结算接口开关尚未开放',
  COMMON_INVALID_ARGUMENT: '参数校验未通过，请核对填写内容',
  IDEMPOTENCY_KEY_CONFLICT: '幂等键冲突（同请求编号用于不同参数），请刷新后重新操作',
  COMMON_CONFLICT: '当前状态或版本已变化，请刷新最新数据后重试',
  COMMON_RATE_LIMITED: '操作过于频繁，请稍后重试',
  COMMON_DEPENDENCY_UNAVAILABLE: '渠道或结算依赖服务暂不可用，请稍后重试',
  COMMON_INTERNAL_ERROR: '服务器处理异常，请稍后重试',
  INVALID_RESPONSE: '服务器响应格式异常，请稍后重试',
  STALE_CONTEXT: '页面会话状态已变化，请刷新后重试',
};

export function describeSettlementFailure(error: unknown, fallback = '操作失败'): string {
  if (error instanceof RequestFailure) {
    if (error.serverMessage) {
      const prefix = FAILURE_TEXT[error.code];
      return prefix ? `${prefix}（${error.serverMessage}）` : `${fallback}：${error.serverMessage}`;
    }
    return FAILURE_TEXT[error.code] ?? `${fallback}（${error.code}）`;
  }
  return error instanceof Error && error.message ? `${fallback}（${error.message}）` : fallback;
}
