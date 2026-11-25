import { createWebClient, RequestFailure, type Transport } from '../request';

const ROOT = '/api/v1/admin/marketing';
const COUPON_ROOT = '/api/v1/admin/coupon-campaigns';

export type Page<T> = { page: number; pageSize: number; total: number; items: T[] };
export type Receipt = { id: string; status: string; version: string; pageVersion?: number; occurredAt: string };
export type Campaign = {
  campaignId: string; campaignCode: string; name: string; ownerType: string; campaignType: string;
  participationMode: string; status: string; title: string; subtitle: string | null;
  // Contract 76 v0.1 §5: campaign cards/details carry the merchant-marketing extension
  // fields. All are nullable — platform campaigns created before P1 have none of them,
  // and themeLabel/coverUrl are server-derived read-only values.
  description: string | null; themeCode: string | null; themeLabel: string | null;
  highlightText: string | null; playType: string | null; coverAssetId: string | null;
  tagCode: string | null; tagName: string | null;
  /**
   * 营销图片读写一体存 OSS Key（2026-11-24 用户裁决）。
   * - 封面：coverUrl 是服务端拼好的可访问地址（既有语义，用于预览）；coverStoredKey 是库里存的原始值
   *   （编辑表单回显它、保存时原样提交，后端再把本 bucket 的完整地址还原成 Key）。
   * - 投放插画 / 内容位图：xxxUrl 是存储值，xxxDisplayUrl 是可访问地址（预览用）。
   */
  coverUrl: string | null; coverStoredKey?: string | null;
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
  imageUrlOverride: string | null; imageUrlOverrideDisplayUrl?: string | null;
  templateCode: string | null; sortWeight: number;
  startAt: string; endAt: string; status: string; version: string;
};
export type HomeBlock = {
  blockId: string; blockCode: string; positionCode: string; templateCode: string;
  title: string; subtitle: string | null; tagName: string | null; imageUrl: string | null;
  imageDisplayUrl?: string | null;
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
  /** 图片在对象存储里的 Key（表单回填它）；对象存储未启用时为 null，此时回填 url。previewUrl 是可访问的预览地址。 */
  objectKey?: string | null; previewUrl?: string | null;
};

export type CampaignInput = {
  name: string; campaignType: string; participationMode: string; title: string;
  subtitle?: string; tagCode?: string; tagName?: string; coverUrl?: string;
  // Contract 76 v0.1 §5: operators may set the merchant-marketing extension fields on
  // admin create/edit. Sent only when filled — omitting keeps the stored value decision
  // with the server (themeLabel/coverUrl on the wire are read-only, never submitted).
  description?: string; themeCode?: string; highlightText?: string;
  playType?: string; coverAssetId?: string;
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
    updatePlacement(placementId: string, input: Record<string, unknown>, expectedVersion: string, requestId = crypto.randomUUID()) {
      return request<Receipt>(`${ROOT}/placements/${placementId}`, { method: 'PUT', body: { ...input, expectedVersion }, requestId });
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
    // PUT /coupon-campaigns/{id}（contract 72）：仅草稿可编辑，请求体与创建一致并带 expectedVersion。
    updateCouponCampaign(campaignId: string, input: Record<string, unknown>, expectedVersion: string, requestId = crypto.randomUUID()) {
      return request<{ campaignId: string; status: string; version: string; occurredAt: string }>(`${COUPON_ROOT}/${campaignId}`, { method: 'PUT', body: { ...input, expectedVersion }, requestId });
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

// Contract 76 v0.1 §3.1: campaign theme whitelist. Codes go on the wire; labels are the
// frontend display-name mapping (PLAN: 展示名由前端映射), mirroring the server-derived
// themeLabel for known codes.
export const CAMPAIGN_THEMES = [
  ['SPRING_FESTIVAL', '春节'],
  ['MID_AUTUMN', '中秋'],
  ['NATIONAL_DAY', '国庆'],
  ['CHRISTMAS', '圣诞'],
  ['NEW_YEAR', '元旦'],
  ['DOUBLE_11', '双11'],
  ['OTHER', '其他'],
] as const;

// Contract 76 v0.1 §3.1: playType is optional (server default THEME); P1 only allows THEME.
export const CAMPAIGN_PLAY_TYPES = [
  ['THEME', '节日主题'],
] as const;

export function campaignThemeLabel(code: string | null | undefined): string {
  if (!code) return '';
  return CAMPAIGN_THEMES.find(([value]) => value === code)?.[1] ?? code;
}

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
  CAMPAIGN_LANDING_INVALID: '操作未通过校验：投放窗口必须落在关联活动的时间窗口内；活动发布需要至少一家参与门店且关联券活动可用',
  CAMPAIGN_THEME_INVALID: '保存失败：节日主题缺失或不在白名单，请从「节日主题」下拉中重新选择',
  CAMPAIGN_PLAY_TYPE_INVALID: '保存失败：玩法仅支持「节日主题（THEME）」，请重新选择',
  MARKETING_ASSET_INVALID: '保存失败：封面素材不存在、不属于当前商家或未启用，请重新上传或清空封面资产 ID',
  MARKETING_ASSET_TOO_LARGE: '上传失败：文件超过 2 MiB 上限，请压缩后重试',
  CAMPAIGN_STATE_NOT_ALLOWED: '操作失败：当前状态不允许该操作，已为你刷新最新数据，请重试',
  CAMPAIGN_NOT_FOUND: '该活动不存在或已被删除，已为你刷新列表',
  CAMPAIGN_PAGE_INVALID: '保存失败：活动页 Schema 不符合页面模板要求，请检查 JSON 结构',
  ENROLLMENT_STATE_NOT_ALLOWED: '操作失败：该报名已被处理或已撤回，已为你刷新最新数据',
  ENROLLMENT_QUOTA_EXCEEDED: '操作失败：该活动报名名额已满。如需继续通过报名，请先在活动详情里调高「报名名额」',
  MARKETING_STORE_NOT_ELIGIBLE: '操作失败：报名门店当前不可用或与商家归属不一致，无法通过该报名',
  COUPON_CAMPAIGN_NOT_FOUND: '券活动不存在或已被删除，已为你刷新列表',
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

// 这几个错误码在后端对应多种互不相干的原因（同一个 COMMON_CONFLICT 既可能是版本冲突，
// 也可能是「券活动名称已存在」），而服务端 message 本身就是写给运营看的中文说明。
// 所以有 message 时优先透出服务端原话，没有才退回固定文案。
const SERVER_MESSAGE_FIRST = new Set(['CAMPAIGN_LANDING_INVALID', 'CAMPAIGN_STATE_NOT_ALLOWED', 'COMMON_CONFLICT']);

export function describeMarketingFailure(error: unknown, fallback = '操作失败'): string {
  if (error instanceof RequestFailure) {
    if (error.code === 'UNAUTHENTICATED') return '登录已失效，请重新登录';
    // 服务端把错误码原样回显成 message 时不算具体原因，不透出（否则又变成裸错误码）。
    const detail = error.serverMessage && error.serverMessage !== error.code ? error.serverMessage.trim() : '';
    if (SERVER_MESSAGE_FIRST.has(error.code)) {
      if (!detail) return FAILURE_TEXT[error.code];
      return /版本/.test(detail)
        ? `${fallback}：${detail}，已为你刷新最新数据，请确认后重试`
        : `${fallback}：${detail}`;
    }
    if (!(error.code in FAILURE_TEXT)) {
      // 文案表没登记的错误码：有服务端原话就用原话，绝不把错误码丢给运营。
      return detail ? `${fallback}：${detail}` : `${fallback}（${error.code}）`;
    }
    // 参数校验（2026-11-20 裁决：校验失败必须透出具体原因）：保留中文指引，同时带上服务端指出的具体字段。
    if (error.code === 'COMMON_INVALID_ARGUMENT' && detail && detail !== '请求参数不合法') {
      return `${FAILURE_TEXT.COMMON_INVALID_ARGUMENT}（服务端：${detail}）`;
    }
    return FAILURE_TEXT[error.code];
  }
  return error instanceof Error && error.message ? `${fallback}（${error.message}）` : fallback;
}

/** Codes whose readable text promises a data refresh — run() reloads lists after them. */
export function isMarketingStale(error: unknown): boolean {
  return error instanceof RequestFailure
    && (error.code === 'COMMON_CONFLICT' || error.code === 'CAMPAIGN_STATE_NOT_ALLOWED'
      || error.code === 'ENROLLMENT_STATE_NOT_ALLOWED'
      || error.code === 'CAMPAIGN_NOT_FOUND' || error.code === 'COMMON_NOT_FOUND');
}
