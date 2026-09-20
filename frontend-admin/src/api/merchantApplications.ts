import { RequestFailure, createWebClient, type Transport } from '../request';

// Field sets mirror the approved application/private-asset projections verbatim (CONTRACT.md §3).
export type SubjectVerification = 'PENDING' | 'VERIFIED';
export type ApplicationStatus = 'REVIEWING' | 'APPROVED' | 'REJECTED';
export type ReviewTaskStatus = 'AVAILABLE' | 'CLAIMED';
export type DecisionType = 'APPROVE' | 'REJECT' | 'REQUEST_CORRECTION';

export type ApplicationSummary = {
  applicationId: string; applicationNo: string; reservedMerchantId: string; status: ApplicationStatus;
  version: string; merchantName: string; merchantTypeCode: string; cityCode: string;
  submittedRevisionId: string; submittedAt: string; subjectVerificationStatus: SubjectVerification;
};

export type DecisionView = { reviewDecisionId: string; submittedRevisionId: string; decisionType: DecisionType | string; opinion: string; decidedAt: string } | null;

export type ReviewTaskView = {
  applicationId: string; taskId: string; submittedRevisionId: string;
  status: ReviewTaskStatus; version: string; claimedByOperatorId: string | null;
};

export type ApplicationSnapshot = {
  merchantName: string; merchantTypeCode: string; cityCode: string; address: string;
  longitude: string | null; latitude: string | null; introduction: string;
  storePhotoAssetIds: string[]; businessLicenseAssetId: string | null;
  idCardFrontAssetId: string | null; idCardBackAssetId: string | null; industryLicenseAssetId: string | null;
  contactNameMasked: string; contactPhoneMasked: string; emailMasked: string;
};

export type ReviewDetail = {
  applicationId: string; applicationNo: string; reservedMerchantId: string; status: ApplicationStatus;
  version: string; merchantName: string; merchantTypeCode: string; cityCode: string;
  submittedRevisionId: string; submittedAt: string; subjectVerificationStatus: SubjectVerification;
  submittedRevision: { revisionId: string; revisionNo: number; snapshot: ApplicationSnapshot; createdAt: string };
  task: ReviewTaskView; latestDecision: DecisionView;
};

export type ApplicationReceipt = {
  applicationId: string; applicationNo: string; reservedMerchantId: string;
  status: ApplicationStatus; version: string; currentRevisionId: string;
};

export type ReadGrant = { readUrl: string; expiresAt: string };

export type ListFilters = {
  page?: number; pageSize?: number; status?: ApplicationStatus; merchantTypeCode?: string;
  cityCode?: string; submittedFrom?: string; submittedTo?: string; keyword?: string;
};

export const MERCHANT_TYPES = [
  { code: 'PET_LIFE_STORE', label: '宠物生活馆' },
  { code: 'PET_HOSPITAL', label: '宠物医院' },
  { code: 'PET_GROOMING', label: '宠物美容' },
  { code: 'PET_BOARDING', label: '宠物寄养' },
  { code: 'PET_TRAINING', label: '宠物训练' },
  { code: 'OTHER', label: '其他' },
] as const;

export function merchantTypeLabel(code: string) { return MERCHANT_TYPES.find(t => t.code === code)?.label ?? code; }
export function statusLabel(status: string) {
  return status === 'REVIEWING' ? '审核中' : status === 'APPROVED' ? '已通过' : status === 'REJECTED' ? '未通过' : status;
}
export function verificationLabel(value: string) { return value === 'VERIFIED' ? '已核验' : '待核验'; }
export function decisionLabel(type: string) {
  return type === 'APPROVE' ? '通过' : type === 'REJECT' ? '拒绝' : type === 'REQUEST_CORRECTION' ? '要求补正' : type;
}

export function createMerchantApplicationClient(transport: Transport = fetch) {
  const base = createWebClient(transport);
  const root = '/api/v1/admin/merchant-applications';
  let token: string | undefined;
  return {
    resetContext(nextToken?: string) { base.resetContext(nextToken); token = nextToken; },
    setToken(nextToken: string) { base.resetContext(nextToken); token = nextToken; },
    // Single-use watermarked read: raw bytes, not the JSON envelope; the digest binds
    // manual-verification evidence to exactly what this operator inspected (CONTRACT.md §4.3).
    async consumeReadGrant(readUrl: string): Promise<{ blob: Blob; sha256: string }> {
      if (!readUrl.startsWith('/api/v1/admin/private-asset-read-grants/') || !token) throw new Error('GRANT_UNAVAILABLE');
      const response = await fetch(new URL(readUrl, window.location.origin), { headers: { Authorization: `Bearer ${token}` }, credentials: 'omit', redirect: 'error' });
      if (!response.ok) throw new RequestFailure(response.status === 404 ? 'GRANT_NOT_FOUND' : response.status === 409 ? 'GRANT_ALREADY_USED' : 'PRIVATE_ASSET_UNAVAILABLE', response.status);
      const buffer = await response.arrayBuffer();
      const digest = await crypto.subtle.digest('SHA-256', buffer);
      const sha256 = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
      return { blob: new Blob([buffer], { type: response.headers.get('Content-Type') ?? 'application/octet-stream' }), sha256 };
    },
    async list(filters: ListFilters) {
      const query = new URLSearchParams();
      for (const [key, value] of Object.entries(filters)) if (value !== undefined && value !== '') query.set(key, String(value));
      const suffix = query.size > 0 ? `?${query}` : '';
      return base.request<{ items: ApplicationSummary[]; page: number; pageSize: number; total: number }>(`${root}${suffix}`);
    },
    get: (applicationId: string) => base.request<ReviewDetail>(`${root}/${applicationId}`),
    claim: (applicationId: string, expectedTaskVersion: string) =>
      base.request<ReviewTaskView>(`${root}/${applicationId}/claim`, { method: 'POST', body: { expectedTaskVersion } }),
    release: (applicationId: string, expectedTaskVersion: string) =>
      base.request<ReviewTaskView>(`${root}/${applicationId}/release`, { method: 'POST', body: { expectedTaskVersion } }),
    recordManualVerification: (
      applicationId: string,
      input: { submissionRevisionId: string; expectedVersion: string; expectedTaskVersion: string; evidenceItems: ManualEvidence[]; reason: string; confirmed: boolean },
    ) => base.request<ApplicationReceipt>(`${root}/${applicationId}/manual-verification`, { method: 'POST', body: input }),
    decide: (
      applicationId: string,
      input: { decisionType: DecisionType; submissionRevisionId: string; expectedVersion: string; expectedTaskVersion: string; opinion: string; internalNote?: string; confirmed: boolean },
    ) => base.request<ApplicationReceipt>(`${root}/${applicationId}/decision`, { method: 'POST', body: input }),
    issueReadGrant: (applicationId: string, assetId: string, input: { submissionRevisionId: string; purposeCode: string; reason: string; confirmed: boolean }) =>
      base.request<ReadGrant>(`${root}/${applicationId}/private-assets/${assetId}/read-grants`, { method: 'POST', body: input }),
  };
}

export type ManualEvidence = {
  materialId: string; materialSha256: string;
  credentialType: 'CREDIT_CODE' | 'IDENTITY_NUMBER' | 'INDUSTRY_LICENSE';
  subjectName: string; identifier: string; validFrom: string | null;
  validityKind: 'LONG_TERM' | 'DATED'; validTo: string | null;
};

// Presentation-only dictionary; server state is never recomputed from it.
export const MATERIAL_LABELS = [
  { kind: 'storePhoto', label: '门店照片' },
  { kind: 'businessLicense', label: '营业执照' },
  { kind: 'idCardFront', label: '身份证（人像面）' },
  { kind: 'idCardBack', label: '身份证（国徽面）' },
  { kind: 'industryLicense', label: '行业许可证' },
] as const;
export type MaterialKind = typeof MATERIAL_LABELS[number]['kind'];
