import { createWebClient, RequestFailure, type Transport } from '../request';
import type { SettlementPage } from './settlementAdmin';

// Contract 81 §4 商家收单开通（拉卡拉进件）运营网页接口（CCR-PAY-ONBOARD-001 / 44 号裁决补充）。
// - 所有 ID 为十进制字符串，日期 yyyy-MM-dd，时间 UTC ISO-8601，金额/费率按字符串原样展示；
// - 写接口经 createWebClient 携带 X-Request-Id（完整 UUID）幂等；
// - 权限码：settlement.onboarding.write（代填/发起/同步/重提/补录/废弃/上传）、
//   settlement.onboarding.read-sensitive（按原因查看明文）、settlement.report.read（列表/详情/审计，脱敏）。
const ROOT = '/api/v1/admin/settlement/onboardings';

// ---- 状态与枚举标签（文案仅用于展示，不改契约取值） ----

export const ONBOARDING_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  EC_SIGNING: '电子合同待签署',
  CONTRACT_APPLYING: '进件申请中',
  CONTRACT_UNKNOWN: '进件结果未知',
  CONTRACT_REJECTED: '进件已驳回',
  LEDGER_APPLYING: '开通分账审核中',
  LEDGER_REJECTED: '开通分账已驳回',
  BIND_APPLYING: '绑定审核中',
  BIND_REJECTED: '绑定已驳回',
  ACTIVE: '已开通',
};

export const ONBOARDING_STEP_LABELS: Record<string, string> = {
  EC: '电子合同',
  CONTRACT: '进件',
  LEDGER: '开通分账',
  BIND: '绑定',
};

export const ONBOARDING_STEP_STATUS_LABELS: Record<string, string> = {
  NONE: '未开始',
  WAITING_SIGN: '待商家法人签署',
  APPLYING: '处理中',
  UNKNOWN: '结果未知',
  REJECTED: '已驳回',
  DONE: '已完成',
};

export const INELIGIBLE_REASON_LABELS: Record<string, string> = {
  MERCHANT_NOT_FOUND: '商家不存在',
  MERCHANT_NOT_ACTIVE: '商家未处于营业中',
  APPLICATION_NOT_APPROVED: '入驻申请未审核通过',
  AGREEMENT_NOT_SIGNED: '平台协议未签署',
};

export const MISSING_CONFIG_LABELS: Record<string, string> = {
  posType: 'POS 终端类型 (posType)',
  feeRates: '渠道费率 (feeRates)',
  splitLowestRatio: '最低分账比例 (splitLowestRatio)',
  platformReceiverNo: '平台分账接收方编号 (platformReceiverNo)',
  notifyBaseUrl: '渠道回调通知基址 (notifyBaseUrl)',
};

export const ONBOARDING_ACTION_LABELS: Record<string, string> = {
  SAVE_PROFILE: '保存资料',
  SUBMIT: '发起开通',
  SYNC: '同步',
  RETRY: '重新提交',
  ADOPT_CONTRACT: '补录进件 ID',
  DISCARD_CONTRACT: '确认拉卡拉未受理',
};

// 九种附件类型（契约 §4.3/§4.4）：前六个必传；后三个仅「对私且结算人非法人」时必传，其余时候须为空。
export const ONBOARDING_ATTACHMENT_TYPES = [
  'FR_ID_CARD_FRONT',
  'FR_ID_CARD_BEHIND',
  'BANK_CARD',
  'BUSINESS_LICENCE',
  'MERCHANT_PHOTO',
  'SHOPINNER',
  'ID_CARD_FRONT',
  'ID_CARD_BEHIND',
  'NO_LEGAL_PERSON_SETT_AUTH_LETTER',
] as const;
export type OnboardingAttachmentType = typeof ONBOARDING_ATTACHMENT_TYPES[number];

export const REQUIRED_ATTACHMENT_TYPES: readonly OnboardingAttachmentType[] = [
  'FR_ID_CARD_FRONT', 'FR_ID_CARD_BEHIND', 'BANK_CARD', 'BUSINESS_LICENCE', 'MERCHANT_PHOTO', 'SHOPINNER',
];
export const SETTLER_ATTACHMENT_TYPES: readonly OnboardingAttachmentType[] = [
  'ID_CARD_FRONT', 'ID_CARD_BEHIND', 'NO_LEGAL_PERSON_SETT_AUTH_LETTER',
];

export const ONBOARDING_ATTACHMENT_LABELS: Record<OnboardingAttachmentType, string> = {
  FR_ID_CARD_FRONT: '法人身份证人像面',
  FR_ID_CARD_BEHIND: '法人身份证国徽面',
  BANK_CARD: '银行卡照片',
  BUSINESS_LICENCE: '营业执照',
  MERCHANT_PHOTO: '门头照（商户照片）',
  SHOPINNER: '店内环境照',
  ID_CARD_FRONT: '结算人身份证人像面',
  ID_CARD_BEHIND: '结算人身份证国徽面',
  NO_LEGAL_PERSON_SETT_AUTH_LETTER: '非法人结算授权书',
};

// allowedActions 子集（契约 §4.3，由服务端按状态计算，前端不得自行推导）。
export type OnboardingAllowedAction =
  | 'SAVE_PROFILE' | 'SUBMIT' | 'SYNC' | 'RETRY' | 'ADOPT_CONTRACT' | 'DISCARD_CONTRACT';

// ---- 响应与请求类型（字段名与契约 §4.1～§4.12 一字不差） ----

export type OnboardingFeeRateView = {
  feeRateTypeCode: string;
  feeRateTypeName: string;
  feeRatePct: string;
};

export type OnboardingPlatformView = {
  posType: string | null;
  mccCode: string | null;
  merBusiContent: string | null;
  settlePeriod: string | null;
  feeRates: OnboardingFeeRateView[];
  splitLowestRatio: string | null;
  platformReceiverConfigured?: boolean;
};

export type OnboardingDefaultsView = {
  merchantId: string;
  eligible: boolean;
  ineligibleReason: string | null;
  merchantName: string | null;
  merchantTypeCode: string | null;
  cityCode: string | null;
  districtCode: string | null;
  configReady: boolean;
  missingConfig: string[];
  platform: OnboardingPlatformView;
};

export type OnboardingSummaryView = {
  onboardingId: string;
  merchantId: string;
  status: string;
  merCupNo: string | null;
  termNo: string | null;
  rejectReason: string | null;
  submittedAt: string | null;
  completedAt: string | null;
  updatedAt: string | null;
};

export type OnboardingMerchantView = {
  merchantTypeCode: string | null;
  merRegName: string;
  merBizName: string | null;
  merRegDistCode: string | null;
  merRegAddr: string | null;
  merBlisName: string | null;
  merBlisNoMasked: string | null;
  merBlisStDt: string | null;
  merBlisExpDt: string | null;
  larName: string | null;
  larIdType: string | null;
  larIdcardMasked: string | null;
  larIdcardStDt: string | null;
  larIdcardExpDt: string | null;
  larMobileMasked: string | null;
  contactName: string | null;
  contactMobileMasked: string | null;
};

export type OnboardingSettlementView = {
  acctTypeCode: string | null;
  acctName: string | null;
  acctNoMasked: string | null;
  acctNoLast4: string | null;
  acctIdType: string | null;
  acctIdcardMasked: string | null;
  acctIdExpDt: string | null;
  openningBankCode: string | null;
  openningBankName: string | null;
  clearingBankCode: string | null;
};

export type OnboardingStepView = {
  step: string;
  status: string;
  submittedAt: string | null;
  finishedAt: string | null;
  summary: string | null;
  reason: string | null;
};

export type OnboardingDetailView = {
  onboardingId: string;
  merchantId: string;
  status: string;
  version: number;
  merchant: OnboardingMerchantView;
  settlement: OnboardingSettlementView;
  platform: OnboardingPlatformView;
  attachments: Partial<Record<OnboardingAttachmentType, string | null>>;
  cooperationAssetId: string | null;
  steps: OnboardingStepView[];
  ecSignUrl: string | null;
  ecNo: string | null;
  contractId: string | null;
  merCupNo: string | null;
  termNo: string | null;
  rejectReason: string | null;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
  allowedActions: OnboardingAllowedAction[];
  submittedAt: string | null;
  completedAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
};

export type OnboardingSaveRequest = {
  expectedVersion: number;
  merchant: {
    merRegName: string;
    merBizName: string | null;
    merRegDistCode: string;
    merRegAddr: string;
    mccCode: string | null;
    merBlisName: string;
    merBlisNo: string;
    merBlisStDt: string;
    merBlisExpDt: string;
    larName: string;
    larIdcard: string;
    larIdcardStDt: string;
    larIdcardExpDt: string;
    larMobile: string;
    contactName: string;
    contactMobile: string;
  };
  settlement: {
    acctTypeCode: string;
    acctName: string;
    acctNo: string;
    acctIdType: string | null;
    acctIdcard: string | null;
    acctIdExpDt: string | null;
    openningBankCode: string;
    openningBankName: string;
    clearingBankCode: string;
  };
  attachments: Record<OnboardingAttachmentType, string | null>;
  cooperationAssetId: string | null;
  reason: string;
};

export type OnboardingRetryRequest = {
  expectedVersion: number;
  cooperationAssetId?: string;
  reason?: string;
};

export type OnboardingRevealView = {
  merchantId: string;
  merBlisNo: string | null;
  larName: string | null;
  larIdcard: string | null;
  larMobile: string | null;
  contactName: string | null;
  contactMobile: string | null;
  acctName: string | null;
  acctNo: string | null;
  acctIdType: string | null;
  acctIdcard: string | null;
};

export type OnboardingAuditView = {
  auditId: string;
  actionType: string;
  operatorType: string;
  operatorId: string | null;
  reason: string | null;
  detail: Record<string, unknown> | null;
  createdAt: string;
};

export type OnboardingAssetUploadView = {
  assetId: string;
  mediaType: string;
  bytes: number;
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

// ---- 客户端 ----

export function createOnboardingAdminClient(transport?: Transport) {
  const client = createWebClient(transport);

  return {
    resetContext(token?: string) {
      client.resetContext(token);
    },

    // 4.1 平台级默认值与准入
    getDefaults(merchantId: string) {
      return client.request<OnboardingDefaultsView>(`${ROOT}/${encodeURIComponent(merchantId.trim())}/defaults`);
    },

    // 4.2 开通列表
    listOnboardings(filters: { merchantId?: string; status?: string; page?: number; size?: number } = {}) {
      return client.request<SettlementPage<OnboardingSummaryView>>(
        `${ROOT}${query({
          merchantId: filters.merchantId?.trim(),
          status: filters.status,
          page: filters.page ?? 1,
          size: filters.size ?? 20,
        })}`,
      );
    },

    // 4.3 开通详情（无记录 404，由调用方按「新开通」处理）
    getOnboarding(merchantId: string) {
      return client.request<OnboardingDetailView>(`${ROOT}/${encodeURIComponent(merchantId.trim())}`);
    },

    // 4.4 保存资料
    saveOnboarding(merchantId: string, body: OnboardingSaveRequest, requestId?: string) {
      return client.request<OnboardingDetailView>(`${ROOT}/${encodeURIComponent(merchantId.trim())}`, {
        method: 'PUT',
        body,
        requestId,
      });
    },

    // 4.5 发起开通
    submitOnboarding(merchantId: string, expectedVersion: number, requestId?: string) {
      return client.request<OnboardingDetailView>(`${ROOT}/${encodeURIComponent(merchantId.trim())}/submit`, {
        method: 'POST',
        body: { expectedVersion },
        requestId,
      });
    },

    // 4.6 同步（立即执行一次当前步渠道查询）
    syncOnboarding(merchantId: string, requestId?: string) {
      return client.request<OnboardingDetailView>(`${ROOT}/${encodeURIComponent(merchantId.trim())}/sync`, {
        method: 'POST',
        body: {},
        requestId,
      });
    },

    // 4.7 重新提交（LEDGER_REJECTED / BIND_REJECTED；BIND_REJECTED 可同时更换合作协议）
    retryOnboarding(merchantId: string, body: OnboardingRetryRequest, requestId?: string) {
      return client.request<OnboardingDetailView>(`${ROOT}/${encodeURIComponent(merchantId.trim())}/retry`, {
        method: 'POST',
        body,
        requestId,
      });
    },

    // 4.8 补录进件 ID（CONTRACT_UNKNOWN）
    adoptContract(merchantId: string, body: { expectedVersion: number; contractId: string; reason: string }, requestId?: string) {
      return client.request<OnboardingDetailView>(`${ROOT}/${encodeURIComponent(merchantId.trim())}/contract/adopt`, {
        method: 'POST',
        body,
        requestId,
      });
    },

    // 4.9 确认拉卡拉未受理（CONTRACT_UNKNOWN，回到 DRAFT）
    discardContract(merchantId: string, body: { expectedVersion: number; reason: string }, requestId?: string) {
      return client.request<OnboardingDetailView>(`${ROOT}/${encodeURIComponent(merchantId.trim())}/contract/discard`, {
        method: 'POST',
        body,
        requestId,
      });
    },

    // 4.10 按原因查看明文（settlement.onboarding.read-sensitive，写不可变审计）
    revealOnboarding(merchantId: string, reason: string, requestId?: string) {
      return client.request<OnboardingRevealView>(`${ROOT}/${encodeURIComponent(merchantId.trim())}/reveal`, {
        method: 'POST',
        body: { reason: reason.trim() },
        requestId,
      });
    },

    // 4.11 审计记录
    listAudits(merchantId: string) {
      return client.request<OnboardingAuditView[]>(`${ROOT}/${encodeURIComponent(merchantId.trim())}/audits`);
    },

    // 4.12 运营上传附件（multipart/form-data，仅一个 file 部件；jpg/png，≤5MB）
    uploadAsset(merchantId: string, file: File, requestId?: string) {
      const form = new FormData();
      form.append('file', file);
      return client.request<OnboardingAssetUploadView>(`${ROOT}/${encodeURIComponent(merchantId.trim())}/assets`, {
        method: 'POST',
        body: form,
        requestId,
      });
    },
  };
}

export type OnboardingAdminClient = ReturnType<typeof createOnboardingAdminClient>;

const FAILURE_TEXT: Record<string, string> = {
  UNAUTHENTICATED: '登录已失效，请重新登录',
  FORBIDDEN: '权限不足：当前账号未授予该操作所需的商家收单开通权限',
  COMMON_FORBIDDEN: '权限不足：当前账号未授予该操作所需的商家收单开通权限',
  COMMON_NOT_FOUND: '记录不存在（该商家尚无开通记录）',
  COMMON_INVALID_ARGUMENT: '资料校验未通过，请核对填写内容',
  IDEMPOTENCY_KEY_CONFLICT: '幂等键冲突（同请求编号用于不同参数），请刷新后重新操作',
  COMMON_CONFLICT: '当前状态或版本不允许该操作，请刷新最新数据后重试',
  COMMON_RATE_LIMITED: '操作过于频繁，请稍后重试',
  COMMON_DEPENDENCY_UNAVAILABLE: '拉卡拉渠道或平台配置依赖暂不可用，请稍后重试',
  COMMON_INTERNAL_ERROR: '服务器处理异常，请稍后重试',
  INVALID_RESPONSE: '服务器响应格式异常，请稍后重试',
  STALE_CONTEXT: '页面会话状态已变化，请刷新后重试',
};

export function describeOnboardingFailure(error: unknown, fallback = '操作失败'): string {
  if (error instanceof RequestFailure) {
    if (error.serverMessage) {
      const prefix = FAILURE_TEXT[error.code];
      return prefix ? `${prefix}（${error.serverMessage}）` : `${fallback}：${error.serverMessage}`;
    }
    return FAILURE_TEXT[error.code] ?? `${fallback}（${error.code}）`;
  }
  return error instanceof Error && error.message ? `${fallback}（${error.message}）` : fallback;
}
