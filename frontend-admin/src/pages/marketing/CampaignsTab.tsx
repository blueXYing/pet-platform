import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Alert, Button, ConfigProvider, Drawer, Empty, Input, Popconfirm, Select, Space, Spin, Tabs, Tag } from 'antd';
import { CheckCircleFilled, ExclamationCircleOutlined } from '@ant-design/icons';
import type { TableColumnsType } from 'antd';
import type { ServiceCategoryAdminClient } from '../../api/serviceCategoryAdmin';
import {
  CAMPAIGN_PLAY_TYPES, CAMPAIGN_THEMES, campaignThemeLabel,
  type Campaign, type CampaignDetail, type CampaignInput, type CouponCampaign,
  type MarketingAdminClient, type StoreLink, type StoreOption,
} from '../../api/marketingAdmin';
import { DataTable } from '../../ui/DataTable';
import { ErrorAlert, FactList, StatusTag } from '../../ui/bits';
import {
  AssetPicker, background, CAMPAIGN_TYPE_TEXT, CategoryField, couponPickerOptions, CoverThumb, DrawerHead, Field,
  fmtDT, FormDrawer, displayUrlOf, IdPicker, ImageZoom, iso, LoadFailed, localInput, OWNER_TYPE_TEXT, PAGE_SIZE,
  PARTICIPATION_MODE_TEXT, prettyJson, RejectModal, STATUS_TEXT, STORE_SOURCE_TEXT, TimeCell, useCategoryOptions,
  useCloseOnLeave, usePagedList, visibilityOf, VisibilityTag, windowIsValid, withForeignCategories,
  type CategoryLoadState, type CategoryOption, type FiltersOf, type TabProps,
} from './shared';

type CampaignFilters = FiltersOf<MarketingAdminClient['listCampaigns']>;
type CampaignAction = 'publish' | 'pause' | 'end' | 'cancel';
type CampaignLike = Pick<Campaign, 'ownerType' | 'status'>;

const csv = (value: string) => value.split(',').map(item => item.trim()).filter(Boolean);
const ACTION_OK: Record<CampaignAction, string> = {
  publish: '活动已发布', pause: '活动已暂停', end: '活动已结束', cancel: '活动已取消',
};
const STATUS_TABS = [
  { key: '', label: '全部' }, { key: 'DRAFT', label: '草稿' }, { key: 'PENDING_REVIEW', label: '待审核' },
  { key: 'SCHEDULED', label: '待生效' }, { key: 'ONLINE', label: '进行中' }, { key: 'PAUSED', label: '已暂停' },
  { key: 'ENDED', label: '已结束' }, { key: 'CANCELED', label: '已取消' }, { key: 'REJECTED', label: '已驳回' },
];
const OWNER_OPTIONS = [
  { value: '', label: '全部归属' }, { value: 'PLATFORM', label: '平台活动' }, { value: 'MERCHANT', label: '商家活动' },
];
const LINK_STYLE = { padding: 0, height: 'auto' } as const;

/**
 * 生命周期动作的可用条件，与后端 MarketingAdminService 的状态机逐条对齐：
 * 发布 DRAFT/PENDING_REVIEW/SCHEDULED/PAUSED（商家活动仅 PENDING_REVIEW，等于「审核通过」）；
 * 暂停 ONLINE；结束 ONLINE/PAUSED/SCHEDULED；取消 DRAFT/PENDING_REVIEW/SCHEDULED/PAUSED。
 * 列表行与详情抽屉共用这一份，避免两处条件各写一遍后走样。
 */
function allowedCampaignActions(campaign: CampaignLike): CampaignAction[] {
  const { status, ownerType } = campaign;
  const cancellable = ['DRAFT', 'PENDING_REVIEW', 'SCHEDULED', 'PAUSED'].includes(status);
  const actions: CampaignAction[] = [];
  if (ownerType === 'MERCHANT' ? status === 'PENDING_REVIEW' : cancellable) actions.push('publish');
  if (status === 'ONLINE') actions.push('pause');
  if (['ONLINE', 'PAUSED', 'SCHEDULED'].includes(status)) actions.push('end');
  if (cancellable) actions.push('cancel');
  return actions;
}
const rejectable = (campaign: CampaignLike) => campaign.ownerType === 'MERCHANT' && campaign.status === 'PENDING_REVIEW';

/** 当前状态意味着什么、运营接下来该做什么——原页面只有一枚状态标签，这一层说明是缺失的。 */
function statusGuidance(campaign: CampaignLike): string {
  switch (campaign.status) {
    case 'DRAFT': return '草稿尚未发布，用户看不到。配置好参与门店、通过下方自检后即可发布。';
    case 'PENDING_REVIEW': return campaign.ownerType === 'MERCHANT'
      ? '商家提交的活动正在等你审核：核对活动信息后发布（即审核通过）或驳回。'
      : '活动待确认发布：核对无误后点击发布。';
    case 'SCHEDULED': return '已发布但未到开始时间，到达开始时间后自动向用户展示。';
    case 'ONLINE': return '活动已上线，实际是否在展示以「展示中 / 已到期」为准。可暂停或结束。';
    case 'PAUSED': return '活动已暂停，用户看不到。可再次发布恢复，或结束 / 取消。';
    case 'ENDED': return '活动已结束，不可恢复。';
    case 'CANCELED': return '活动已取消，不可恢复。';
    case 'REJECTED': return '活动已被驳回。';
    default: return '';
  }
}

function buildCampaignInput(form: FormData): CampaignInput {
  return {
    name: String(form.get('name') ?? '').trim(),
    campaignType: String(form.get('campaignType') ?? 'DISPLAY'),
    participationMode: String(form.get('participationMode') ?? 'PLATFORM_SELECTED'),
    title: String(form.get('title') ?? '').trim(),
    subtitle: String(form.get('subtitle') ?? '').trim() || undefined,
    tagCode: String(form.get('tagCode') ?? '').trim() || undefined,
    tagName: String(form.get('tagName') ?? '').trim() || undefined,
    coverUrl: String(form.get('coverUrl') ?? '').trim() || undefined,
    cityCodes: csv(String(form.get('cityCodes') ?? '')),
    serviceCategoryCodes: form.getAll('serviceCategoryCode').map(String),
    startAt: iso(String(form.get('startAt') ?? '')),
    endAt: iso(String(form.get('endAt') ?? '')),
    enrollStartAt: iso(String(form.get('enrollStartAt') ?? '')) || undefined,
    enrollEndAt: iso(String(form.get('enrollEndAt') ?? '')) || undefined,
    enrollmentQuota: form.get('enrollmentQuota') ? Number(form.get('enrollmentQuota')) : undefined,
    couponCampaignId: String(form.get('couponCampaignId') ?? '').trim() || undefined,
    // 契约 76 v0.1 §5：节日营销扩展字段均可空——留空即不提交（undefined 不进请求体），
    // 空值保持为空而不是编造默认值；themeLabel / coverUrl 是服务端派生的只读值，永不上送。
    description: String(form.get('description') ?? '').trim() || undefined,
    themeCode: String(form.get('themeCode') ?? '').trim() || undefined,
    highlightText: String(form.get('highlightText') ?? '').trim() || undefined,
    playType: String(form.get('playType') ?? '').trim() || undefined,
    coverAssetId: String(form.get('coverAssetId') ?? '').trim() || undefined,
    sortWeight: form.get('sortWeight') ? Number(form.get('sortWeight')) : 0,
  };
}

/**
 * 契约 76 的节日营销字段，只在有值时出现在详情里——平台活动（这些字段全为空）的详情保持原样，
 * 不堆一排「—」。标签优先用服务端派生的 themeLabel，缺失才回退到前端映射。
 */
function campaignExtensionFacts(campaign: Campaign): { label: string; value: ReactNode }[] {
  const theme = campaignThemeLabel(campaign.themeLabel ?? campaign.themeCode);
  const rows: { label: string; value: ReactNode }[] = [];
  if (theme) rows.push({ label: '节日主题', value: theme });
  if (campaign.playType) {
    rows.push({ label: '玩法', value: CAMPAIGN_PLAY_TYPES.find(([value]) => value === campaign.playType)?.[1] ?? campaign.playType });
  }
  if (campaign.highlightText) rows.push({ label: '主打项目', value: campaign.highlightText });
  if (campaign.coverAssetId) rows.push({ label: '封面素材', value: `ID ${campaign.coverAssetId}` });
  // 说明可能多行，单独占一整行：FactList 是两列等宽，这里用列跨度撑满。
  if (campaign.description) rows.push({ label: '说明', value: <span style={{ whiteSpace: 'pre-wrap' }}>{campaign.description}</span> });
  return rows;
}

/** 发布前自检：逐条对应后端 publishCampaign 的放行条件，把 409 的原因提前摆到运营眼前。只是提示，最终以后端校验为准。 */
function PublishReadiness({ detail }: { detail: CampaignDetail }) {
  const { campaign } = detail;
  const needsStore = campaign.participationMode !== 'MERCHANT_ENROLL';
  const upcoming = Date.parse(campaign.startAt) > Date.now();
  const rows = [
    {
      ok: !needsStore || campaign.storeCount > 0,
      text: needsStore
        ? `参与门店 ${campaign.storeCount} 家（本活动发布至少需要 1 家）`
        : '招商活动允许 0 家门店发布，商家可在报名期内报名加入',
    },
    {
      ok: true,
      text: detail.pageSchema ? '活动页 Schema 已配置，发布时会生成新的页面版本' : '活动页 Schema 未配置，发布时使用系统默认页面',
    },
    {
      ok: true,
      text: campaign.couponCampaignId ? '已关联券活动，发布时会校验该券活动是否可用' : '未关联券活动',
    },
    // 契约 76 §5：玩法只能是 THEME；绑了封面素材时素材必须仍然可用。后者前端无从判断（素材表不在运营端读取范围），
    // 只如实提示「发布时会校验」，不替后端下结论。
    ...(campaign.playType ? [{
      ok: campaign.playType === 'THEME',
      text: campaign.playType === 'THEME' ? '玩法为「节日主题」，符合发布要求' : `玩法「${campaign.playType}」不在允许范围内（仅支持节日主题），不能发布`,
    }] : []),
    ...(campaign.coverAssetId ? [{
      ok: true,
      text: `已绑定封面素材（ID ${campaign.coverAssetId}），发布时会校验该素材是否仍然可用`,
    }] : []),
    {
      ok: true,
      text: upcoming ? `发布后为「待生效」，${fmtDT(campaign.startAt)} 起向用户展示` : '发布后立即向用户展示',
    },
  ];
  return <ul className="mkt-checklist">
    {rows.map(row => <li key={row.text} className={row.ok ? 'is-ok' : 'is-warn'}>
      {row.ok ? <CheckCircleFilled /> : <ExclamationCircleOutlined />}
      <span>{row.text}</span>
    </li>)}
  </ul>;
}

function CampaignActions({ campaign, busy, compact, publishLabel, onAction, onReject }: {
  campaign: Campaign; busy: boolean; compact?: boolean; publishLabel?: string;
  onAction: (campaign: Campaign, action: CampaignAction) => void;
  onReject: (campaign: Campaign) => void;
}) {
  const actions = allowedCampaignActions(campaign);
  const secondary = compact ? { type: 'link' as const, size: 'small' as const, style: LINK_STYLE } : {};
  return <Space size={compact ? 12 : 8} wrap>
    {actions.includes('publish') && <Button type="primary" size={compact ? 'small' : 'middle'} disabled={busy}
      onClick={() => onAction(campaign, 'publish')}>{publishLabel ?? '发布'}</Button>}
    {rejectable(campaign) && <Button {...secondary} danger disabled={busy} onClick={() => onReject(campaign)}>驳回</Button>}
    {actions.includes('pause') && <Popconfirm title="暂停后活动不再对用户展示，之后可再次发布恢复。确认暂停？"
      okText="确认暂停" cancelText="再想想" onConfirm={() => onAction(campaign, 'pause')}>
      <Button {...secondary} disabled={busy}>暂停</Button>
    </Popconfirm>}
    {actions.includes('end') && <Popconfirm title="结束后活动不再展示且不可恢复。确认结束？"
      okText="确认结束" cancelText="再想想" okButtonProps={{ danger: true }} onConfirm={() => onAction(campaign, 'end')}>
      <Button {...secondary} danger disabled={busy}>结束</Button>
    </Popconfirm>}
    {actions.includes('cancel') && <Popconfirm title="取消后该活动作废且不可恢复。确认取消？"
      okText="确认取消" cancelText="再想想" okButtonProps={{ danger: true }} onConfirm={() => onAction(campaign, 'cancel')}>
      <Button {...secondary} danger disabled={busy}>取消</Button>
    </Popconfirm>}
  </Space>;
}

/** 活动新建 / 编辑共用字段：基本信息 → 投放范围 → 活动时间与报名 → 关联与排序。 */
function CampaignFields({ idPrefix, detail, client, canManage, onAssetFailure, category, couponCampaigns }: {
  idPrefix: string; detail?: Campaign; client: MarketingAdminClient; canManage: boolean;
  onAssetFailure: (caught: unknown) => void;
  category: { state: CategoryLoadState; options: CategoryOption[]; failure: string; retry: () => void };
  couponCampaigns: CouponCampaign[] | undefined;
}) {
  const [participationMode, setParticipationMode] = useState(detail?.participationMode ?? 'PLATFORM_SELECTED');
  const [couponCampaignId, setCouponCampaignId] = useState(detail?.couponCampaignId ?? '');
  const enrollVisible = participationMode === 'MERCHANT_ENROLL';
  const id = (suffix: string) => `${idPrefix}-${suffix}`;
  return <>
    <input type="hidden" name="couponCampaignId" value={couponCampaignId} />
    <fieldset>
      <legend>基本信息</legend>
      <div className="mkt-form-grid">
        <Field id={id('name')} label="活动名称（2-100）"><Input id={id('name')} name="name" defaultValue={detail?.name} minLength={2} maxLength={100} required /></Field>
        <Field id={id('title')} label="标题（1-100）"><Input id={id('title')} name="title" defaultValue={detail?.title} maxLength={100} required /></Field>
        <Field id={id('subtitle')} label="副标题" span><Input id={id('subtitle')} name="subtitle" defaultValue={detail?.subtitle ?? ''} maxLength={200} /></Field>
        <Field id={id('type')} label="活动类型">
          <select id={id('type')} name="campaignType" defaultValue={detail?.campaignType ?? 'DISPLAY'}>
            <option value="DISPLAY">展示活动</option>
            <option value="COUPON">券活动</option>
            <option value="MERCHANT_ENROLL">商家招商</option>
          </select>
        </Field>
        <Field id={id('mode')} label="参与方式">
          <select id={id('mode')} name="participationMode" value={participationMode} onChange={event => setParticipationMode(event.target.value)}>
            <option value="PLATFORM_SELECTED">平台选择门店</option>
            <option value="MERCHANT_ENROLL">商家报名</option>
          </select>
        </Field>
        <Field id={id('tagCode')} label="标签编码"><Input id={id('tagCode')} name="tagCode" defaultValue={detail?.tagCode ?? ''} placeholder="new-user / beauty / boarding" maxLength={32} /></Field>
        <Field id={id('tagName')} label="标签名称"><Input id={id('tagName')} name="tagName" defaultValue={detail?.tagName ?? ''} placeholder="新客专享 / 美容优惠" maxLength={32} /></Field>
        {/* 回显的是存储值（coverStoredKey，通常是 OSS Key），保存时原样提交；预览用拼好的 coverUrl。 */}
        <AssetPicker name="coverUrl" label="活动封面" uploadText="上传封面"
          defaultValue={detail?.coverStoredKey ?? detail?.coverUrl}
          defaultDisplayUrl={detail?.coverUrl}
          client={client} canManage={canManage} onFailure={onAssetFailure} />
      </div>
      <p className="hint">参与方式决定门店怎么来：「平台选择门店」由运营在详情里指定；「商家报名」由商家自行报名、运营在「商家报名」分区审核。</p>
    </fieldset>
    <fieldset>
      <legend>投放范围</legend>
      <div className="mkt-form-grid">
        <Field id={id('cities')} label="城市编码（逗号分隔，空=全部）" span>
          <Input id={id('cities')} name="cityCodes" defaultValue={detail?.cityCodes.join(',')} placeholder="shanghai,chengdu" />
        </Field>
        <CategoryField state={category.state} options={withForeignCategories(category.options, detail?.serviceCategoryCodes ?? [])}
          defaultCodes={detail?.serviceCategoryCodes ?? []} failureText={category.failure} onRetry={() => void category.retry()} />
      </div>
    </fieldset>
    <fieldset>
      <legend>活动时间{enrollVisible ? '与商家报名' : ''}</legend>
      <div className="mkt-form-grid">
        <Field id={id('start')} label="开始时间"><input id={id('start')} name="startAt" type="datetime-local" defaultValue={detail ? localInput(detail.startAt) : ''} required /></Field>
        <Field id={id('end')} label="结束时间"><input id={id('end')} name="endAt" type="datetime-local" defaultValue={detail ? localInput(detail.endAt) : ''} required /></Field>
        {enrollVisible && <>
          <Field id={id('enrollStart')} label="报名开始"><input id={id('enrollStart')} name="enrollStartAt" type="datetime-local" defaultValue={detail ? localInput(detail.enrollStartAt) : ''} /></Field>
          <Field id={id('enrollEnd')} label="报名结束"><input id={id('enrollEnd')} name="enrollEndAt" type="datetime-local" defaultValue={detail ? localInput(detail.enrollEndAt) : ''} /></Field>
          <Field id={id('quota')} label="报名名额" hint="留空 = 不限名额"><input id={id('quota')} name="enrollmentQuota" type="number" min={1} max={10000} defaultValue={detail?.enrollmentQuota ?? ''} /></Field>
        </>}
      </div>
      <p className="hint">{enrollVisible
        ? '商家只能在报名窗口内报名，窗口未填写时商家端不会开放报名；建议报名窗口落在活动时间内。'
        : '参与方式为「商家报名」时，这里会出现报名窗口与名额。'}</p>
    </fieldset>
    <fieldset>
      <legend>关联与排序</legend>
      <div className="mkt-form-grid">
        <Field id={id('coupon')} label="关联券活动 ID" span hint="类型为「券活动」时填写；可输入券活动名称联想，也可直接粘贴 ID">
          <IdPicker id={id('coupon')} value={couponCampaignId} onChange={setCouponCampaignId}
            options={couponPickerOptions(couponCampaigns)} placeholder="输入券活动名称或 ID" />
        </Field>
        <Field id={id('sort')} label="排序权重" hint="数值越小越靠前"><input id={id('sort')} name="sortWeight" type="number" min={0} max={9999} defaultValue={detail?.sortWeight ?? 0} /></Field>
      </div>
    </fieldset>
    <fieldset>
      <legend>节日营销（契约 76，均可空）</legend>
      <p className="hint">商家提交的「节日主题」玩法活动，发布前会校验封面素材有效。平台活动可以不填。</p>
      <div className="mkt-form-grid">
        <Field id={id('description')} label="说明（≤300）" span>
          <Input.TextArea id={id('description')} name="description" defaultValue={detail?.description ?? ''} maxLength={300} rows={3} placeholder="活动规则与参与方式" />
        </Field>
        <Field id={id('theme')} label="节日主题">
          <select id={id('theme')} name="themeCode" defaultValue={detail?.themeCode ?? ''}>
            <option value="">未设置</option>
            {CAMPAIGN_THEMES.map(([value, label]) => <option key={value} value={value}>{label}（{value}）</option>)}
          </select>
        </Field>
        <Field id={id('playType')} label="玩法" hint="留空时后端按「节日主题」处理">
          <select id={id('playType')} name="playType" defaultValue={detail?.playType ?? ''}>
            <option value="">未设置（后端按 THEME 处理）</option>
            {CAMPAIGN_PLAY_TYPES.map(([value, label]) => <option key={value} value={value}>{label}（{value}）</option>)}
          </select>
        </Field>
        <Field id={id('highlight')} label="主打项目（≤100）">
          <Input id={id('highlight')} name="highlightText" defaultValue={detail?.highlightText ?? ''} maxLength={100} placeholder="如 宠物月饼礼盒" />
        </Field>
        <Field id={id('coverAsset')} label="封面素材 ID（商家上传的封面资产，可空）">
          <Input id={id('coverAsset')} name="coverAssetId" defaultValue={detail?.coverAssetId ?? ''} maxLength={32} />
        </Field>
      </div>
    </fieldset>
  </>;
}

export function CampaignsTab({ client, categoryClient, active, canManage, canPublish, busy, run, authOrError, onAuthLost }: TabProps & {
  categoryClient: ServiceCategoryAdminClient;
  canManage: boolean;
  canPublish: boolean;
}) {
  const list = usePagedList<Campaign, CampaignFilters>(
    (filters, page) => client.listCampaigns(filters, page, PAGE_SIZE), authOrError, '读取活动失败');
  const category = useCategoryOptions(categoryClient, onAuthLost, active);
  const [filters, setFilters] = useState<CampaignFilters>({});
  const [keywordText, setKeywordText] = useState('');
  const [creating, setCreating] = useState(false);
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detail, setDetail] = useState<CampaignDetail>();
  const [schema, setSchema] = useState('');
  const [schemaError, setSchemaError] = useState('');
  const [storeDrafts, setStoreDrafts] = useState<StoreLink[]>([]);
  const [storesDirty, setStoresDirty] = useState(false);
  const [schemaDirty, setSchemaDirty] = useState(false);
  const [storeKeyword, setStoreKeyword] = useState('');
  const [storeSearching, setStoreSearching] = useState(false);
  const [storeSearched, setStoreSearched] = useState(false);
  const [storeOptions, setStoreOptions] = useState<StoreOption[]>([]);
  const [rejecting, setRejecting] = useState<Campaign>();
  const [couponCampaigns, setCouponCampaigns] = useState<CouponCampaign[]>();
  const couponLoaded = useRef(false);
  // 门店链接上只有 ID（契约 71 未带名称）：用门店选择接口尽力回填名称，回填不到的按 ID 显示。
  const storeNames = useRef(new Map<string, StoreOption>());
  const [, setNamesVersion] = useState(0);

  useEffect(() => { if (active) void list.ensure({}); }, [active, list.ensure]);
  // 券活动目录只在要填「关联券活动」时（抽屉打开）才拉，失败不打扰运营。
  useEffect(() => {
    if (!active || !(creating || detailOpen) || couponLoaded.current) return;
    couponLoaded.current = true;
    background(client.listCouponCampaigns(undefined, 1, 100).then(page => setCouponCampaigns(page.items)), onAuthLost);
  }, [active, creating, detailOpen, client, onAuthLost]);
  useCloseOnLeave(active, () => { setCreating(false); setDetailOpen(false); setRejecting(undefined); });

  function apply(patch: Partial<CampaignFilters>, page = 1) {
    const next: CampaignFilters = { ...filters, ...patch };
    setFilters(next);
    void list.load(next, page);
  }
  const hasFilter = Boolean(filters.status || filters.ownerType || filters.keyword);
  const checkForm = (caught: Error) => authOrError(caught, '请检查表单');

  function adoptDetail(next: CampaignDetail, keepEdits = false) {
    setDetail(next);
    setStoreOptions([]);
    setStoreSearched(false);
    if (!keepEdits) {
      setStoreDrafts(next.stores.map(store => ({ ...store })));
      setStoresDirty(false);
      setSchema(next.pageSchema ?? '');
      setSchemaDirty(false);
    }
    setSchemaError('');
  }
  async function reloadDetail(campaignId: string, keepEdits = false) {
    try { adoptDetail(await client.getCampaign(campaignId), keepEdits); }
    catch { /* 瞬时读失败保留旧详情；下一次动作会以版本冲突提示并重新拉取 */ }
  }
  /** 抽屉里的动作：成功后同时刷新详情和它背后的列表，关抽屉时列表不会是旧数据。 */
  async function refreshAfter(campaignId: string, keepEdits = true) {
    await Promise.all([reloadDetail(campaignId, keepEdits), list.reload()]);
  }
  async function openDetail(campaign: Campaign) {
    setCreating(false);
    setRejecting(undefined);
    setDetail(undefined);
    setDetailOpen(true);
    setDetailLoading(true);
    let loaded = false;
    try {
      adoptDetail(await client.getCampaign(campaign.campaignId));
      loaded = true;
    } catch (caught) {
      authOrError(caught, '读取活动详情失败');
      setDetailOpen(false);
    } finally { setDetailLoading(false); }
    if (!loaded) return;
    // 回填门店名称用。这个接口每页上限是 50（MerchantStoreDisplayService），超过会直接 400。
    background(client.listStores({}, 1, 50).then(page => {
      for (const option of page.items) storeNames.current.set(option.storeId, option);
      setNamesVersion(version => version + 1);
    }), onAuthLost);
  }
  function act(campaign: Campaign, action: CampaignAction) {
    void run(() => client.campaignAction(campaign.campaignId, action, campaign.version), ACTION_OK[action],
      () => refreshAfter(campaign.campaignId));
  }

  async function searchStores() {
    setStoreSearching(true);
    try {
      const page = await client.listStores({ keyword: storeKeyword.trim() || undefined }, 1, 50);
      setStoreOptions(page.items);
      setStoreSearched(true);
      for (const option of page.items) storeNames.current.set(option.storeId, option);
    } catch (caught) { authOrError(caught, '门店查询失败'); }
    finally { setStoreSearching(false); }
  }
  function patchStore(index: number, patch: Partial<StoreLink>) {
    setStoreDrafts(stores => stores.map((store, position) => position === index ? { ...store, ...patch } : store));
    setStoresDirty(true);
  }
  function addStore(option: StoreOption) {
    if (storeDrafts.some(store => store.storeId === option.storeId)) return;
    setStoreDrafts(stores => [...stores, {
      storeId: option.storeId, merchantId: option.merchantId, source: 'PLATFORM_SELECTED', status: 'ACTIVE',
      offerTag: null, offerTitle: null, offerCopy: null, sortWeight: 0,
    }]);
    setStoresDirty(true);
  }
  function saveStores() {
    if (!detail) return;
    void run(() => client.replaceStores(detail.campaign.campaignId, storeDrafts, detail.campaign.version), '参与门店已保存',
      () => refreshAfter(detail.campaign.campaignId, false));
  }
  function saveSchema() {
    if (!detail) return;
    if (!schema.trim()) { setSchemaError('Schema 不能为空。不打算配置活动页时无需保存，发布时会使用系统默认页面。'); return; }
    try { JSON.parse(schema); } catch (caught) {
      setSchemaError(`不是合法的 JSON（${caught instanceof Error ? caught.message : '格式错误'}），请修正后再保存。`);
      return;
    }
    setSchemaError('');
    void run(() => client.savePage(detail.campaign.campaignId, schema, detail.campaign.version), 'Schema 草稿已保存',
      () => refreshAfter(detail.campaign.campaignId));
  }

  const storeName = (storeId: string) => storeNames.current.get(storeId);
  // 有编辑权才渲染可输入单元格；只读账号看到的是纯文本，不会出现“能改但没处保存”的假象。
  const offerCell = (key: 'offerTag' | 'offerTitle' | 'offerCopy', max: number, width: number, placeholder?: string) => ({
    title: { offerTag: '优惠角标', offerTitle: '优惠标题', offerCopy: '优惠说明' }[key],
    width,
    render: (_: unknown, store: StoreLink, index: number) => canManage
      ? <Input size="small" maxLength={max} value={store[key] ?? ''} placeholder={placeholder}
        onChange={event => patchStore(index, { [key]: event.target.value.trim() || null } as Partial<StoreLink>)} />
      : (store[key] ?? '--'),
  });
  // 列宽合计控制在抽屉内容宽度以内（抽屉 920 − 内边距 48）：任何一列都不能被裁掉。
  const storeDraftColumns: TableColumnsType<StoreLink> = [
    { title: '门店', key: 'store', width: 200, render: (_, store) => {
      const known = storeName(store.storeId);
      return <div className="mkt-entity-text">
        <div className="mkt-entity-name" title={known?.storeName}>{known?.storeName ?? `门店 ${store.storeId}`}</div>
        <div className="mkt-entity-sub">{known ? `ID ${store.storeId} · ` : ''}商家 {known?.merchantName ?? store.merchantId}</div>
        <div><Tag>{STORE_SOURCE_TEXT[store.source] ?? store.source}</Tag></div>
      </div>;
    } },
    offerCell('offerTag', 32, 110, '如 5折 / 立减'),
    offerCell('offerTitle', 100, 170),
    offerCell('offerCopy', 100, 190),
    { title: '排序', width: 76, render: (_, store, index) => canManage
      ? <Input size="small" type="number" min={0} max={9999} value={store.sortWeight}
        onChange={event => patchStore(index, { sortWeight: Number(event.target.value || 0) })} />
      : store.sortWeight },
    { title: '操作', width: 64, render: (_, store) => canManage && <Popconfirm
      title={store.source === 'MERCHANT_ENROLL' ? '该门店由商家报名加入，从活动移除后需商家重新报名。确认移除？' : '确认从本活动移除该门店？保存后生效。'}
      okText="移除" okButtonProps={{ danger: true }} cancelText="再想想"
      onConfirm={() => { setStoreDrafts(stores => stores.filter(row => row.storeId !== store.storeId)); setStoresDirty(true); }}>
      <Button type="link" size="small" danger style={LINK_STYLE}>移除</Button>
    </Popconfirm> },
  ];
  const storeOptionColumns: TableColumnsType<StoreOption> = [
    { title: '门店', dataIndex: 'storeName', width: 170, ellipsis: true },
    { title: '商家', dataIndex: 'merchantName', width: 140, ellipsis: true },
    { title: '城市', dataIndex: 'cityCode', width: 90 },
    { title: '地址', dataIndex: 'address', width: 238, ellipsis: true },
    { title: '操作', width: 84, render: (_, option) => {
      const added = storeDrafts.some(store => store.storeId === option.storeId);
      return <Button type="link" size="small" style={LINK_STYLE} disabled={added}
        onClick={() => addStore(option)}>{added ? '已加入' : '添加'}</Button>;
    } },
  ];
  // 列宽预算：合计 ≤ 1000px（1280 宽的屏幕、侧边栏展开时内容区正好 1000px），操作列固定在右侧，
  // 更窄的屏幕出现横向滚动时操作入口也不会被卷走。
  const campaignColumns: TableColumnsType<Campaign> = [
    { title: '活动', key: 'campaign', width: 240, render: (_, campaign) => <div className="mkt-entity">
      <CoverThumb url={campaign.coverStoredKey ?? campaign.coverUrl} displayUrl={campaign.coverUrl} emptyText="无封面" />
      <div className="mkt-entity-text">
        <div className="mkt-entity-name" title={campaign.name}>{campaign.name}</div>
        <div className="mkt-entity-sub" title={campaign.title}>{campaign.title}{campaign.tagName ? ` · ${campaign.tagName}` : ''}</div>
        <div className="mkt-entity-id">ID {campaign.campaignId}</div>
      </div>
    </div> },
    { title: '类型', width: 120, render: (_, campaign) => <div className="mkt-entity-text">
      <div>{CAMPAIGN_TYPE_TEXT[campaign.campaignType] ?? campaign.campaignType}</div>
      <div className="mkt-entity-sub">{PARTICIPATION_MODE_TEXT[campaign.participationMode] ?? campaign.participationMode}</div>
      {/* 契约 76：节日主题。标签优先用服务端派生的 themeLabel，缺失才回退到前端映射。 */}
      {campaignThemeLabel(campaign.themeCode ?? campaign.themeLabel) && <div className="mkt-entity-sub" title="节日主题">🏮 {campaignThemeLabel(campaign.themeLabel ?? campaign.themeCode)}</div>}
    </div> },
    { title: '归属', width: 76, render: (_, campaign) => <Tag color={campaign.ownerType === 'MERCHANT' ? 'purple' : 'default'}>{OWNER_TYPE_TEXT[campaign.ownerType] ?? campaign.ownerType}</Tag> },
    { title: '状态', width: 116, render: (_, campaign) => <div className="mkt-stack">
      <StatusTag label={STATUS_TEXT[campaign.status] ?? campaign.status} status={campaign.status} />
      <VisibilityTag visibility={visibilityOf('campaign', campaign.status, campaign.startAt, campaign.endAt)} />
    </div> },
    { title: '活动时间', width: 150, render: (_, campaign) => <TimeCell startAt={campaign.startAt} endAt={campaign.endAt} /> },
    { title: '门店', width: 60, align: 'right', render: (_, campaign) => campaign.storeCount },
    { title: '操作', key: 'actions', width: 230, fixed: 'right', render: (_, campaign) => <Space size={12} wrap>
      <Button type="link" size="small" style={LINK_STYLE} onClick={() => void openDetail(campaign)}>详情</Button>
      {canPublish && <CampaignActions campaign={campaign} busy={busy} compact onAction={act} onReject={setRejecting} />}
    </Space> },
  ];

  const newCampaignFilters = (): CampaignFilters => (
    filters.status && filters.status !== 'DRAFT' ? { ...filters, status: undefined } : filters
  );
  const create = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    if (!windowIsValid(String(form.get('startAt') ?? ''), String(form.get('endAt') ?? ''))) {
      checkForm(new Error('结束时间必须晚于开始时间')); return;
    }
    void run(() => client.createCampaign(buildCampaignInput(form)),
      '活动草稿已创建：在列表点「详情」配置参与门店，自检通过后即可发布',
      async () => {
        setCreating(false);
        const next = newCampaignFilters();
        setFilters(next);
        await list.load(next, 1);
      });
  };
  const saveBasic = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!detail) return;
    const form = new FormData(event.currentTarget);
    if (!windowIsValid(String(form.get('startAt') ?? ''), String(form.get('endAt') ?? ''))) {
      checkForm(new Error('结束时间必须晚于开始时间')); return;
    }
    const campaignId = detail.campaign.campaignId;
    void run(() => client.updateCampaign(campaignId, buildCampaignInput(form), detail.campaign.version), '活动基础信息已保存',
      () => refreshAfter(campaignId));
  };

  return <>
    <Tabs size="small" activeKey={filters.status ?? ''} onChange={value => apply({ status: value || undefined })}
      items={STATUS_TABS.map(tab => ({ key: tab.key, label: tab.label }))}
      tabBarExtraContent={{ right: <Space wrap>
        <Select style={{ width: 120 }} value={filters.ownerType ?? ''} options={OWNER_OPTIONS} aria-label="归属"
          onChange={value => apply({ ownerType: value || undefined })} />
        <Input.Search style={{ width: 240 }} placeholder="活动名称 / 标题关键词" value={keywordText} maxLength={50}
          allowClear onChange={event => setKeywordText(event.target.value)}
          onSearch={value => apply({ keyword: value.trim() || undefined })} />
        <Button disabled={busy || list.loading} onClick={() => void list.reload()}>刷新</Button>
        {canManage && <Button type="primary" onClick={() => { setDetailOpen(false); setCreating(true); }}>新建活动</Button>}
      </Space> }} />

    {list.failed && !list.rows ? <LoadFailed onRetry={() => void list.reload()} />
      : <ConfigProvider renderEmpty={() => <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={hasFilter ? '没有符合当前筛选条件的活动' : '暂无活动'}>
        {hasFilter
          ? <Button size="small" onClick={() => { setKeywordText(''); apply({ status: undefined, ownerType: undefined, keyword: undefined }); }}>清除筛选</Button>
          : canManage ? <Button size="small" type="primary" onClick={() => setCreating(true)}>创建第一个活动</Button> : null}
      </Empty>}>
        <DataTable tableLayout="fixed" columns={campaignColumns} rows={list.rows} rowKey={campaign => campaign.campaignId}
          loading={list.loading} page={list.page} pageSize={PAGE_SIZE} total={list.total}
          onPageChange={page => void list.load(filters, page)} />
      </ConfigProvider>}

    <FormDrawer open={creating} onClose={() => setCreating(false)} title="新建活动" label="新建活动"
      description="创建后是草稿，用户看不到。创建完成后在列表点「详情」配置参与门店，自检通过再发布。">
      <form className="mkt-form-body" onSubmit={create}>
        <div className="mkt-form-fields">
          <CampaignFields idPrefix="campaign-new" client={client} canManage={canManage}
            onAssetFailure={caught => authOrError(caught, '素材上传失败')} category={category}
            couponCampaigns={couponCampaigns} />
        </div>
        <div className="mkt-form-actions">
          <Button type="primary" htmlType="submit" disabled={busy}>创建草稿</Button>
          <Button disabled={busy} onClick={() => setCreating(false)}>取消</Button>
        </div>
      </form>
    </FormDrawer>

    <Drawer rootClassName="mkt-drawer" open={detailOpen} onClose={() => setDetailOpen(false)} width={920}
      destroyOnHidden closable={false}>
      <DrawerHead title={detail?.campaign.name ?? '活动详情'}
        tag={detail && <StatusTag label={STATUS_TEXT[detail.campaign.status] ?? detail.campaign.status} status={detail.campaign.status} />}
        description={detail && statusGuidance(detail.campaign)} onClose={() => setDetailOpen(false)} />
      <Spin spinning={detailLoading}>
        {detail && <section className="mkt-detail" aria-label="活动详情">
          <FactList column={2} items={[
            { label: '标题', value: <>{detail.campaign.title}{detail.campaign.subtitle ? ` — ${detail.campaign.subtitle}` : ''}</> },
            { label: '活动 ID', value: detail.campaign.campaignId },
            { label: '状态', value: <Space size={4} wrap>
              <StatusTag label={STATUS_TEXT[detail.campaign.status] ?? detail.campaign.status} status={detail.campaign.status} />
              <VisibilityTag visibility={visibilityOf('campaign', detail.campaign.status, detail.campaign.startAt, detail.campaign.endAt)} />
            </Space> },
            { label: '类型', value: `${CAMPAIGN_TYPE_TEXT[detail.campaign.campaignType] ?? detail.campaign.campaignType} · ${PARTICIPATION_MODE_TEXT[detail.campaign.participationMode] ?? detail.campaign.participationMode}` },
            { label: '归属', value: `${OWNER_TYPE_TEXT[detail.campaign.ownerType] ?? detail.campaign.ownerType}活动` },
            { label: '活动时间', value: `${fmtDT(detail.campaign.startAt)} 至 ${fmtDT(detail.campaign.endAt)}` },
            { label: '报名窗口', value: detail.campaign.participationMode === 'MERCHANT_ENROLL'
              ? detail.campaign.enrollStartAt ? `${fmtDT(detail.campaign.enrollStartAt)} 至 ${fmtDT(detail.campaign.enrollEndAt)}` : '未设置（商家端不会开放报名）'
              : '不适用' },
            { label: '投放城市', value: detail.campaign.cityCodes.length ? detail.campaign.cityCodes.join('、') : '全部城市' },
            { label: '服务分类', value: detail.campaign.serviceCategoryCodes.length ? detail.campaign.serviceCategoryCodes.join('、') : '全部分类' },
            { label: '标签', value: detail.campaign.tagName ? `${detail.campaign.tagName}（${detail.campaign.tagCode ?? '--'}）` : '--' },
            { label: '关联券活动', value: detail.campaign.couponCampaignId ?? '未关联' },
            { label: '报名名额', value: detail.campaign.participationMode === 'MERCHANT_ENROLL'
              ? detail.campaign.enrollmentQuota ?? '不限' : '不适用' },
            { label: '排序权重', value: detail.campaign.sortWeight },
            ...campaignExtensionFacts(detail.campaign),
            { label: '封面', value: (() => {
              // 存储值 = coverStoredKey（新后端）；老后端只有 coverUrl，那时它本身就是完整地址。
              const stored = detail.campaign.coverStoredKey ?? detail.campaign.coverUrl;
              // 预览地址必须是合法的 http(s)/data: 地址：对象存储没配置时服务端会把 Key 原样放进 coverUrl，
              // 直接塞给 <img> 会裂图——这种情况下显示占位说明。
              const preview = displayUrlOf(stored, detail.campaign.coverUrl);
              if (!stored) return '未设置';
              return preview
                ? <ImageZoom src={preview} alt="活动封面" />
                : `已设置（存储标识「${stored}」），服务端暂未给出可预览的地址`;
            })() },
            { label: '数据版本', value: `v${detail.campaign.version}（并发保护用，保存时自动携带）` },
            { label: '更新时间', value: fmtDT(detail.campaign.updatedAt) },
          ]} />

          {canPublish && <div className="mkt-panel">
            <h4>生命周期操作</h4>
            {(storesDirty || schemaDirty) && <Alert type="warning" showIcon style={{ marginBottom: 12 }}
              message="有未保存的门店 / 活动页修改：下面的操作只作用于已保存的内容，请先保存。" />}
            {allowedCampaignActions(detail.campaign).includes('publish') && <PublishReadiness detail={detail} />}
            <CampaignActions campaign={detail.campaign} busy={busy} onAction={act} onReject={setRejecting}
              publishLabel="发布（按已保存的门店与活动页上线）" />
            {!allowedCampaignActions(detail.campaign).length && !rejectable(detail.campaign) &&
              <p className="hint" style={{ margin: 0 }}>当前状态没有可执行的发布类操作。</p>}
          </div>}

          {canManage && <details>
            <summary>编辑活动基础信息</summary>
            <form onSubmit={saveBasic}>
              <CampaignFields idPrefix="campaign-edit" detail={detail.campaign} client={client} canManage={canManage}
                onAssetFailure={caught => authOrError(caught, '素材上传失败')} category={category}
                couponCampaigns={couponCampaigns} />
              <Space>
                <Button type="primary" htmlType="submit" disabled={busy}>保存基础信息</Button>
              </Space>
            </form>
          </details>}

          <div className="mkt-panel">
            <h4>参与门店（{storeDrafts.length}）{storesDirty && <Tag color="orange">有未保存修改</Tag>}</h4>
            <ConfigProvider renderEmpty={() => <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={detail.campaign.participationMode === 'MERCHANT_ENROLL'
              ? '暂无参与门店：商家报名通过后会自动加入' : '暂无参与门店：发布前至少需要添加 1 家'} />}>
              <DataTable tableLayout="fixed" columns={storeDraftColumns} rows={storeDrafts} rowKey={store => store.storeId} />
            </ConfigProvider>
            {canManage && <>
              <div className="mkt-inline">
                <Input style={{ width: 260 }} placeholder="门店名称关键词" value={storeKeyword} maxLength={50}
                  onChange={event => setStoreKeyword(event.target.value)} onPressEnter={() => void searchStores()} />
                <Button disabled={busy || storeSearching} onClick={() => void searchStores()}>搜索门店</Button>
                <Button type="primary" disabled={busy || !storesDirty} onClick={saveStores}>保存门店</Button>
              </div>
              <p className="hint">搜索结果点「添加」加入本活动；上方列表可逐行修改优惠文案与排序、可移除；「保存门店」按上方列表整体提交。
                {detail.campaign.participationMode === 'MERCHANT_ENROLL' && ' 商家报名通过的门店会自动加入，请勿在这里移除。'}</p>
              {storeSearching && <p className="inline-status">门店搜索中…</p>}
              {storeOptions.length > 0 && <>
                <div className="mkt-subhead">搜索结果（{storeOptions.length} 家）</div>
                <DataTable tableLayout="fixed" columns={storeOptionColumns} rows={storeOptions} rowKey={option => option.storeId} />
              </>}
              {storeSearched && !storeSearching && storeOptions.length === 0 && <p className="hint">没有找到匹配的门店，换个关键词试试。</p>}
            </>}
          </div>

          <div className="mkt-panel">
            <h4>活动页 Schema{schemaDirty && <Tag color="orange">有未保存修改</Tag>}</h4>
            <p className="hint">活动会场页面的 JSON 配置：保存为草稿，发布时生效。不配置时发布会使用系统默认页面。</p>
            <Input.TextArea value={schema} onChange={event => { setSchema(event.target.value); setSchemaDirty(true); }}
              rows={10} className="mkt-code" readOnly={!canManage} />
            {schemaError && <ErrorAlert>{schemaError}</ErrorAlert>}
            {canManage && <Button disabled={busy || !schemaDirty} onClick={saveSchema}>保存 Schema 草稿</Button>}
          </div>
          {detail.publishedPageSchema && <div className="mkt-panel">
            <details>
              <summary>查看当前线上页面的 Schema（v{detail.campaign.pageVersion}）</summary>
              <pre className="mkt-pre">{prettyJson(detail.publishedPageSchema)}</pre>
            </details>
          </div>}

          {detail.pageVersions.length > 0 && <div className="mkt-panel">
            <h4>页面发布版本</h4>
            <ul className="mkt-versions">
              {detail.pageVersions.map(version => <li key={version.version}>
                <span>v{version.version}</span>
                <span>{fmtDT(version.publishedAt)}</span>
                {version.version === detail.campaign.pageVersion
                  ? <Tag color="green">当前线上</Tag>
                  : canPublish && <Popconfirm title={`回滚后用户看到的是 v${version.version} 的活动页。确认回滚？`}
                    okText="确认回滚" onConfirm={() => void run(
                      () => client.rollbackPage(detail.campaign.campaignId, version.version, detail.campaign.version),
                      `已回滚到 v${version.version}`, () => refreshAfter(detail.campaign.campaignId))}>
                    <Button type="link" size="small" style={LINK_STYLE} disabled={busy}>回滚到此版本</Button>
                  </Popconfirm>}
              </li>)}
            </ul>
          </div>}
        </section>}
      </Spin>
    </Drawer>

    <RejectModal open={!!rejecting} busy={busy} title={rejecting ? `驳回活动：${rejecting.name}` : ''}
      description="驳回原因会同步给商家。"
      onCancel={() => setRejecting(undefined)}
      onSubmit={reason => {
        const target = rejecting;
        if (!target) return;
        void run(() => client.rejectCampaign(target.campaignId, reason, target.version), '活动已驳回',
          async () => { setRejecting(undefined); await refreshAfter(target.campaignId); });
      }} />
  </>;
}
