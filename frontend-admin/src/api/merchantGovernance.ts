import type { Transport } from '../request';
import {
  GovernanceFailure, Page, ROOT, boundedPage, createGovernanceEnvelope, enumOf, exact, isoFilter,
  name, optional, pageOf, queryString, text, validId, validUtc,
} from './governanceHttp';

// Contract 64 merchant ledger face (HTTP10 §5.12, PRD 6.2.2): read-only ledger list/detail
// over the merchant kernel. Server facts only — status vocabularies, masked provider number,
// signing state; no metric is derived client side.
export const MERCHANT_STATUSES = ['APPLYING', 'ACTIVE', 'OFFLINE', 'FROZEN', 'CANCELED'] as const;
export const STORE_STATUSES = ['ACTIVE', 'OFFLINE', 'FROZEN'] as const;
export type MerchantStatus = typeof MERCHANT_STATUSES[number];
export type StoreStatus = typeof STORE_STATUSES[number];

export type MerchantLedgerSummary = {
  merchantId: string; merchantName: string; status: MerchantStatus;
  merchantTypeCode: string | null; cityCode: string | null;
  storeCount: number; activeStoreCount: number;
  applicationStatus: 'DRAFT' | 'REVIEWING' | 'APPROVED' | 'REJECTED' | null;
  signingStatus: 'SIGNED' | 'NOT_SIGNED' | null;
  providerMerchantNoMasked: string | null; createdAt: string;
};
export type MerchantStoreRow = { storeId: string; storeName: string; address: string; status: StoreStatus };
export type MerchantApplicationFact = {
  applicationId: string; applicationNo: string | null;
  status: 'DRAFT' | 'REVIEWING' | 'APPROVED' | 'REJECTED';
  submittedAt: string | null; reviewedAt: string | null;
};
export type MerchantSigningFact = {
  signingStatus: 'SIGNED' | 'NOT_SIGNED'; agreementVersion: string; acceptedAt: string | null;
};
export type MerchantLedgerDetail = MerchantLedgerSummary & {
  stores: MerchantStoreRow[]; application: MerchantApplicationFact | null;
  signing: MerchantSigningFact | null;
};
export type MerchantLedgerFilters = {
  status?: MerchantStatus; storeStatus?: StoreStatus; cityCode?: string;
  merchantTypeCode?: string; keyword?: string; createdFrom?: string; createdTo?: string;
};

const summary = enumOf<MerchantStatus>(MERCHANT_STATUSES);
const storeStatus = enumOf<StoreStatus>(STORE_STATUSES);
const applicationStatus = enumOf(['DRAFT', 'REVIEWING', 'APPROVED', 'REJECTED']);

/** Key-agnostic core shared by the summary (exact) and the detail (allOf) shapes. */
function ledgerCore(v: Record<string, unknown>): boolean {
  return validId(v.merchantId) && name(v.merchantName) && summary(v.status)
    && optional(v.merchantTypeCode, x => typeof x === 'string' && x.length >= 1 && x.length <= 32)
    && optional(v.cityCode, x => typeof x === 'string' && x.length >= 1 && x.length <= 64)
    && typeof v.storeCount === 'number' && v.storeCount >= 0
    && typeof v.activeStoreCount === 'number' && v.activeStoreCount >= 0
    && optional(v.applicationStatus, applicationStatus)
    && optional(v.signingStatus, enumOf(['SIGNED', 'NOT_SIGNED']))
    && optional(v.providerMerchantNoMasked, x => typeof x === 'string' && x.length >= 1 && x.length <= 8)
    && validUtc(v.createdAt);
}
function ledgerRow(v: unknown): v is MerchantLedgerSummary {
  return exact(v, ['merchantId', 'merchantName', 'status', 'merchantTypeCode', 'cityCode', 'storeCount', 'activeStoreCount', 'applicationStatus', 'signingStatus', 'providerMerchantNoMasked', 'createdAt'])
    && ledgerCore(v);
}
function applicationFact(v: unknown): v is MerchantApplicationFact {
  return exact(v, ['applicationId', 'applicationNo', 'status', 'submittedAt', 'reviewedAt'])
    && validId(v.applicationId)
    && optional(v.applicationNo, x => typeof x === 'string' && x.length >= 1 && x.length <= 22)
    && applicationStatus(v.status) && optional(v.submittedAt, validUtc) && optional(v.reviewedAt, validUtc);
}
function signingFact(v: unknown): v is MerchantSigningFact {
  return exact(v, ['signingStatus', 'agreementVersion', 'acceptedAt'])
    && enumOf(['SIGNED', 'NOT_SIGNED'])(v.signingStatus)
    && typeof v.agreementVersion === 'string' && v.agreementVersion.length >= 1 && v.agreementVersion.length <= 64
    && optional(v.acceptedAt, validUtc);
}
function storeRow(v: unknown): v is MerchantStoreRow {
  return exact(v, ['storeId', 'storeName', 'address', 'status'])
    && validId(v.storeId) && name(v.storeName) && text(v.address) && v.address.length >= 1
    && v.address.length <= 255 && storeStatus(v.status);
}
function ledgerDetail(v: unknown): v is MerchantLedgerDetail {
  if (!exact(v, ['merchantId', 'merchantName', 'status', 'merchantTypeCode', 'cityCode', 'storeCount', 'activeStoreCount', 'applicationStatus', 'signingStatus', 'providerMerchantNoMasked', 'createdAt', 'stores', 'application', 'signing'])
    || !ledgerCore(v)) return false;
  const raw = v as Record<string, unknown>;
  if (!Array.isArray(raw.stores) || !(raw.stores as unknown[]).every(storeRow)) return false;
  if (raw.application !== null && !applicationFact(raw.application)) return false;
  if (raw.signing !== null && !signingFact(raw.signing)) return false;
  return true;
}

export function merchantStatusLabel(value: string) {
  return ({
    APPLYING: '入驻中', ACTIVE: '经营中', OFFLINE: '已下线', FROZEN: '已冻结', CANCELED: '已注销',
  } as Record<string, string>)[value] ?? value;
}
export function signingLabel(value: string | null) {
  return value === 'SIGNED' ? '已签约' : value === 'NOT_SIGNED' ? '未签约' : '无协议版本';
}

export function createMerchantGovernanceClient(transport: Transport = fetch) {
  const http = createGovernanceEnvelope(transport);
  return {
    resetContext: http.resetContext,
    async listMerchants(page: number, filters: MerchantLedgerFilters = {}, pageSize = 20): Promise<Page<MerchantLedgerSummary>> {
      boundedPage(page, pageSize);
      const data = await http.envelope(`${ROOT}/merchants${queryString({
        page, pageSize,
        status: filters.status, storeStatus: filters.storeStatus,
        cityCode: filters.cityCode, merchantTypeCode: filters.merchantTypeCode,
        keyword: filters.keyword,
        createdFrom: isoFilter(filters.createdFrom), createdTo: isoFilter(filters.createdTo),
      })}`);
      const check = pageOf<MerchantLedgerSummary>(ledgerRow, page, pageSize);
      if (!check(data)) throw new GovernanceFailure('INVALID_RESPONSE', 200);
      return data;
    },
    async getMerchant(merchantId: string): Promise<MerchantLedgerDetail> {
      if (!validId(merchantId)) throw new GovernanceFailure('INVALID_ARGUMENT', 0);
      const data = await http.envelope(`${ROOT}/merchants/${merchantId}`);
      if (!ledgerDetail(data)) throw new GovernanceFailure('INVALID_RESPONSE', 200);
      return data;
    },
  };
}
export type MerchantGovernanceClient = ReturnType<typeof createMerchantGovernanceClient>;
