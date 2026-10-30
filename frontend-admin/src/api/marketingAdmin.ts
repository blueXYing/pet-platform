import { createWebClient, RequestFailure, type Transport } from '../request';

const ROOT = '/api/v1/admin/marketing';
const COUPON_ROOT = '/api/v1/admin/coupon-campaigns';

export type Page<T> = { page: number; pageSize: number; total: number; items: T[] };
export type Receipt = { id: string; status: string; version: string; pageVersion?: number; occurredAt: string };
export type Campaign = {
  campaignId: string; campaignCode: string; name: string; ownerType: string; campaignType: string;
  participationMode: string; status: string; title: string; subtitle: string | null;
  tagCode: string | null; tagName: string | null; coverUrl: string | null;
  cityCodes: string[]; serviceCategoryCodes: string[];
  startAt: string; endAt: string; enrollStartAt: string | null; enrollEndAt: string | null;
  enrollmentQuota: number | null; couponCampaignId: string | null; pageVersion: number;
  storeCount: number; sortWeight: number; version: string; createdAt: string; updatedAt: string;
};
export type StoreLink = {
  storeId: string; merchantId: string; source: string; status: string;
  offerTag: string | null; offerTitle: string | null; offerCopy: string | null; sortWeight: number;
};
export type CampaignDetail = {
  campaign: Campaign; stores: StoreLink[]; pageSchema: string | null;
  publishedPageSchema: string | null; pageVersions: { version: number; publishedAt: string }[];
};
export type Placement = {
  placementId: string; positionCode: string; campaignId: string; campaignTitle: string;
  cityCode: string | null; titleOverride: string | null; subtitleOverride: string | null;
  imageUrlOverride: string | null; templateCode: string | null; sortWeight: number;
  startAt: string; endAt: string; status: string; version: string;
};
export type HomeBlock = {
  blockId: string; blockCode: string; positionCode: string; templateCode: string;
  title: string; subtitle: string | null; tagName: string | null; imageUrl: string | null;
  targetType: string; targetValue: string | null; cityCode: string | null;
  sponsorMerchantId: string | null; sponsorStoreId: string | null; sortWeight: number;
  startAt: string; endAt: string; status: string; version: string;
};
export type Enrollment = {
  enrollmentId: string; campaignId: string; campaignTitle: string; merchantId: string;
  storeId: string; storeName: string | null; status: string; offerTag: string | null;
  offerTitle: string | null; offerCopy: string | null; materials: string | null;
  rejectReason: string | null; submittedAt: string; decidedAt: string | null;
  decidedBy: string | null; version: string;
};
export type StoreOption = {
  storeId: string; merchantId: string; storeName: string; merchantName: string;
  cityCode: string; address: string;
  promotion: { level: number | null; title: string | null; text: string | null } | null;
};
export type CouponCampaign = {
  campaignId: string; name: string; templateId: string; templateName: string;
  amountOff: string | null; thresholdAmount: string | null; typeLabel: string | null;
  audienceType: string; perUserLimit: number; totalLimit: number; budgetLimit: string;
  issuedCount: number; issuedAmount: string; startAt: string; endAt: string;
  status: string; version: string;
};
export type CouponClaim = { claimId: string; userId: string; couponInstanceId: string; claimedAt: string };
export type MarketingAsset = {
  assetId: string; url: string; contentType: string; bytes: number;
  width: number; height: number; sha256: string; createdAt: string;
};

export type CampaignInput = {
  name: string; campaignType: string; participationMode: string; title: string;
  subtitle?: string; tagCode?: string; tagName?: string; coverUrl?: string;
  cityCodes: string[]; serviceCategoryCodes: string[];
  startAt: string; endAt: string; enrollStartAt?: string; enrollEndAt?: string;
  enrollmentQuota?: number; couponCampaignId?: string; sortWeight?: number;
};

const query = (params: Record<string, string | number | undefined>) => {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) if (value !== undefined && value !== '') search.set(key, String(value));
  return search.toString();
};

export function createMarketingAdminClient(transport?: Transport) {
  const client = createWebClient(transport);
  const request = <T>(path: string, options: { method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'; body?: unknown; requestId?: string } = {}) => client.request<T>(path, options);
  return {
    resetContext(token?: string) { client.resetContext(token); },

    listCampaigns(filters: { status?: string; ownerType?: string; keyword?: string } = {}, page = 1, pageSize = 20) {
      return request<Page<Campaign>>(`${ROOT}/campaigns?${query({ ...filters, page, pageSize })}`);
    },
    getCampaign(campaignId: string) { return request<CampaignDetail>(`${ROOT}/campaigns/${campaignId}`); },
    createCampaign(input: CampaignInput, requestId = crypto.randomUUID()) {
      return request<Receipt>(`${ROOT}/campaigns`, { method: 'POST', body: input, requestId });
    },
    updateCampaign(campaignId: string, input: CampaignInput, expectedVersion: string, requestId = crypto.randomUUID()) {
      return request<Receipt>(`${ROOT}/campaigns/${campaignId}`, { method: 'PUT', body: { ...input, expectedVersion }, requestId });
    },
    replaceStores(campaignId: string, stores: StoreLink[], expectedVersion: string, requestId = crypto.randomUUID()) {
      return request<Receipt>(`${ROOT}/campaigns/${campaignId}/stores`, { method: 'POST', body: { stores, expectedVersion }, requestId });
    },
    savePage(campaignId: string, schema: string, expectedVersion: string, requestId = crypto.randomUUID()) {
      return request<Receipt>(`${ROOT}/campaigns/${campaignId}/page`, {
        method: 'POST', body: { schema: JSON.parse(schema), expectedVersion }, requestId,
      });
    },
    campaignAction(campaignId: string, action: 'publish' | 'pause' | 'end' | 'cancel', expectedVersion: string) {
      return request<Receipt>(`${ROOT}/campaigns/${campaignId}/${action}`, { method: 'POST', body: { expectedVersion } });
    },
    rejectCampaign(campaignId: string, reason: string, expectedVersion: string, requestId = crypto.randomUUID()) {
      return request<Receipt>(`${ROOT}/campaigns/${campaignId}/reject`, { method: 'POST', body: { reason, expectedVersion }, requestId });
    },
    rollbackPage(campaignId: string, pageVersion: number, expectedVersion: string, requestId = crypto.randomUUID()) {
      return request<Receipt>(`${ROOT}/campaigns/${campaignId}/page/rollback`, { method: 'POST', body: { pageVersion, expectedVersion }, requestId });
    },
    preview(campaignId: string) { return request<{ campaignId: string; draftVersion: number; publishedVersion: number; draftSchemaJson: string; stores: StoreLink[] }>(`${ROOT}/campaigns/${campaignId}/preview`); },
    uploadAsset(file: File, requestId = crypto.randomUUID()) {
      const form = new FormData();
      form.set('file', file);
      return request<MarketingAsset>(`${ROOT}/assets`, { method: 'POST', body: form, requestId });
    },

    listPlacements(filters: { positionCode?: string; status?: string } = {}, page = 1, pageSize = 50) {
      return request<Page<Placement>>(`${ROOT}/placements?${query({ ...filters, page, pageSize })}`);
    },
    createPlacement(input: Record<string, unknown>, requestId = crypto.randomUUID()) {
      return request<Receipt>(`${ROOT}/placements`, { method: 'POST', body: input, requestId });
    },
    placementAction(placementId: string, action: 'enable' | 'disable', expectedVersion: string) {
      return request<Receipt>(`${ROOT}/placements/${placementId}/${action}`, { method: 'POST', body: { expectedVersion } });
    },

    listHomeBlocks(filters: { positionCode?: string; status?: string } = {}, page = 1, pageSize = 50) {
      return request<Page<HomeBlock>>(`${ROOT}/home-blocks?${query({ ...filters, page, pageSize })}`);
    },
    createHomeBlock(input: Record<string, unknown>, requestId = crypto.randomUUID()) {
      return request<Receipt>(`${ROOT}/home-blocks`, { method: 'POST', body: input, requestId });
    },
    updateHomeBlock(blockId: string, input: Record<string, unknown>, expectedVersion: string, requestId = crypto.randomUUID()) {
      return request<Receipt>(`${ROOT}/home-blocks/${blockId}`, { method: 'PUT', body: { ...input, expectedVersion }, requestId });
    },
    homeBlockAction(blockId: string, action: 'enable' | 'disable', expectedVersion: string) {
      return request<Receipt>(`${ROOT}/home-blocks/${blockId}/${action}`, { method: 'POST', body: { expectedVersion } });
    },

    listEnrollments(filters: { campaignId?: string; status?: string } = {}, page = 1, pageSize = 50) {
      return request<Page<Enrollment>>(`${ROOT}/enrollments?${query({ ...filters, page, pageSize })}`);
    },
    reviewEnrollment(enrollmentId: string, decision: 'APPROVE' | 'REJECT', reason: string | undefined, expectedVersion: string) {
      return request<Receipt>(`${ROOT}/enrollments/${enrollmentId}/review`, {
        method: 'POST', body: { decision, reason, expectedVersion },
      });
    },
    listStores(filters: { keyword?: string; cityCode?: string } = {}, page = 1, pageSize = 50) {
      return request<Page<StoreOption>>(`${ROOT}/stores?${query({ ...filters, page, pageSize })}`);
    },

    listCouponCampaigns(status?: string, page = 1, pageSize = 50) {
      return request<Page<CouponCampaign>>(`${COUPON_ROOT}?${query({ status, page, pageSize })}`);
    },
    createCouponCampaign(input: Record<string, unknown>, requestId = crypto.randomUUID()) {
      return request<{ campaignId: string; status: string; version: string; occurredAt: string }>(COUPON_ROOT, { method: 'POST', body: input, requestId });
    },
    couponCampaignAction(campaignId: string, action: 'publish' | 'pause' | 'end', expectedVersion: string) {
      return request<{ campaignId: string; status: string; version: string; occurredAt: string }>(`${COUPON_ROOT}/${campaignId}/${action}`, { method: 'POST', body: { expectedVersion } });
    },
    listCouponClaims(campaignId: string, page = 1, pageSize = 50) {
      return request<Page<CouponClaim>>(`${COUPON_ROOT}/${campaignId}/claims?${query({ page, pageSize })}`);
    },
  };
}

export type MarketingAdminClient = ReturnType<typeof createMarketingAdminClient>;

// Wire whitelists mirror MarketingAdminService (contract 71 v0.3): POSITIONS and
// HOME_BLOCK_TARGETS. Labels are operator-facing; the codes are what goes on the wire.
export const PLACEMENT_POSITIONS = [
  ['HOME_ACTIVITY', '首页活动位（活动钜惠）'],
  ['HOME_BANNER', '首页 Banner'],
  ['STORE_LIST_TOP', '商家列表置顶（商家钜惠）'],
  ['HOT_SERVICE_TOP', '热门服务顶部 Banner（全平台最多启用 1 个）'],
] as const;

export const HOME_BLOCK_TARGET_TYPES = [
  ['SERVICE_DIRECTORY', '服务目录'],
  ['SERVICE_CATEGORY', '服务分类关键词'],
  ['STORE', '门店'],
  ['SERVICE', '单次服务'],
  ['CAMPAIGN', '活动'],
  ['COMMUNITY', '宠友圈'],
  ['MINIAPP_PATH', '小程序路径'],
  ['NONE', '无跳转'],
  ['HOT_SERVICE', '热门服务'],
] as const;

// Contract 71 v0.3 §10: the C-side routes on targetType alone for NONE/HOT_SERVICE — the
// server nulls targetValue regardless of what is sent, so the UI keeps those inputs blank.
export function targetNeedsValue(targetType: string): boolean {
  return targetType !== 'NONE' && targetType !== 'HOT_SERVICE';
}

// Readable operator-facing text per stable error code (docs/04-api/12号 registry marketing
// batch + common codes) — the page never dumps raw code jargon.
const FAILURE_TEXT: Record<string, string> = {
  SERVICE_CATEGORY_INVALID: '保存失败：所选服务分类中有分类不存在或未启用，请在「服务分类」字典确认后重新勾选再保存',
  PLACEMENT_LIMIT_EXCEEDED: '操作失败：已达位置启用上限——热门服务顶部 Banner 全平台最多同时启用 1 个，首页 Banner 同一城市最多同时启用 3 个。请先停用已启用的同位置 Banner 再试',
  CAMPAIGN_LANDING_INVALID: '保存失败：投放窗口必须完全落在关联活动的时间窗口内',
  CAMPAIGN_STATE_NOT_ALLOWED: '操作失败：当前状态不允许该操作，已为你刷新最新数据，请重试',
  CAMPAIGN_NOT_FOUND: '该活动不存在或已被删除，已为你刷新列表',
  COMMON_CONFLICT: '操作冲突：数据已被其他人修改，已为你刷新最新数据，请重试',
  COMMON_NOT_FOUND: '数据不存在或已被删除，已为你刷新列表',
  COMMON_INVALID_ARGUMENT: '保存失败：填写内容不符合要求，请检查名称/标题长度、时间窗口、城市与分类选择等字段后重试',
  IDEMPOTENCY_KEY_CONFLICT: '相同请求正在处理中，请稍后重试',
  COMMON_RATE_LIMITED: '操作过于频繁，请稍等片刻后重试',
  COMMON_DEPENDENCY_UNAVAILABLE: '依赖服务暂不可用（功能未开放或后端繁忙），请稍后重试',
  COMMON_INTERNAL_ERROR: '服务器开小差了，请稍后重试',
  FORBIDDEN: '权限不足：当前账号未授予该操作所需权限',
  INVALID_RESPONSE: '服务器响应格式异常，请稍后重试',
  STALE_CONTEXT: '页面状态已过期，请刷新后重试',
  INVALID_ADMIN_PATH: '请求地址非法，已阻止发送',
};

export function describeMarketingFailure(error: unknown, fallback = '操作失败'): string {
  if (error instanceof RequestFailure) {
    if (error.code === 'UNAUTHENTICATED') return '登录已失效，请重新登录';
    return FAILURE_TEXT[error.code] ?? `${fallback}（${error.code}）`;
  }
  return error instanceof Error && error.message ? `${fallback}（${error.message}）` : fallback;
}

/** Codes whose readable text promises a data refresh — run() reloads lists after them. */
export function isMarketingStale(error: unknown): boolean {
  return error instanceof RequestFailure
    && (error.code === 'COMMON_CONFLICT' || error.code === 'CAMPAIGN_STATE_NOT_ALLOWED'
      || error.code === 'CAMPAIGN_NOT_FOUND' || error.code === 'COMMON_NOT_FOUND');
}
