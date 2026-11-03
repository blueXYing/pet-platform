import { useEffect, useRef, useState } from 'react';
import { Button, Checkbox, ConfigProvider, Input } from 'antd';
import type { TableColumnsType } from 'antd';
import type { AdminTemplateSummary, CouponAdminClient } from '../api/couponAdmin';
import { RequestFailure } from '../request';
import type { ServiceCategoryAdminClient } from '../api/serviceCategoryAdmin';
import {
  describeMarketingFailure, HOME_BLOCK_TARGET_TYPES, isMarketingStale, PLACEMENT_POSITIONS,
  targetNeedsValue,
  type Campaign, type CampaignDetail, type CampaignInput, type CouponCampaign, type CouponClaim,
  type Enrollment, type HomeBlock, type MarketingAdminClient, type Placement, type StoreLink, type StoreOption,
} from '../api/marketingAdmin';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';
const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿', PENDING_REVIEW: '待审核', SCHEDULED: '待生效', ONLINE: '进行中',
  PAUSED: '已暂停', ENDED: '已结束', CANCELED: '已取消', REJECTED: '已驳回',
  SUBMITTED: '已提交', UNDER_REVIEW: '审核中', APPROVED: '已通过', WITHDRAWN: '已撤回',
  ENABLED: '已启用', DISABLED: '已停用', ACTIVE: '进行中',
};
const iso = (local: string) => local ? new Date(local).toISOString() : '';
const localInput = (value: string | null) => {
  if (!value) return '';
  const d = new Date(value);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
};
const csv = (value: string) => value.split(',').map(item => item.trim()).filter(Boolean);

// F-A-02: campaign service categories are picked from the service-category dictionary face
// (contract 71 v0.3 §4.1) instead of being typed by hand. `foreign` marks an existing
// campaign code that the dictionary no longer lists (deleted or disabled since) — it stays
// rendered and checked so an untouched save preserves stored data instead of silently
// narrowing the campaign to "all categories".
type CategoryOption = { code: string; name: string; foreign?: boolean };
type CategoryLoadState = 'loading' | 'ready' | 'failed';

function CategoryField({ state, options, defaultCodes, failureText, onRetry }: {
  state: CategoryLoadState;
  options: CategoryOption[];
  defaultCodes: string[];
  failureText: string;
  onRetry: () => void;
}) {
  const selected = new Set(defaultCodes);
  return <fieldset>
    <legend>服务分类（可多选，来自服务分类字典）</legend>
    {state === 'loading' && <InlineStatus>分类加载中…</InlineStatus>}
    {state === 'failed' && <ErrorAlert>{failureText}<Button onClick={onRetry}>重试</Button></ErrorAlert>}
    {state === 'ready' && options.length === 0 && <p className="hint">字典中暂无启用中的分类。</p>}
    {options.map(option => <div key={option.code} style={{ margin: '4px 0' }}>
      <Checkbox name="serviceCategoryCode" value={option.code} defaultChecked={selected.has(option.code)}>
        {option.foreign ? `${option.code}（字典外或已停用）` : `${option.name}（${option.code}）`}
      </Checkbox>
    </div>)}
    <p className="hint">不勾选任何分类 = 活动面向全部分类（提交空列表）；勾选的编码按服务分类字典校验，不存在或未启用的分类会被后端拒绝。</p>
  </fieldset>;
}

function AssetPicker({ name, label, defaultValue, client, canManage, onFailure }: {
  name: string; label: string; defaultValue?: string | null;
  client: MarketingAdminClient; canManage: boolean; onFailure: (caught: unknown) => void;
}) {
  const [url, setUrl] = useState(defaultValue ?? '');
  const [busy, setBusy] = useState(false);
  async function upload(file: File | undefined) {
    if (!file || busy) return;
    setBusy(true);
    try { setUrl((await client.uploadAsset(file)).url); }
    catch (caught) { onFailure(caught); }
    finally { setBusy(false); }
  }
  return <div className="asset-picker">
    <label>{label} URL<Input name={name} value={url} onChange={event => setUrl(event.target.value)} maxLength={512} /></label>
    {canManage && <label>上传插画（PNG/JPEG，≤2MiB）<input type="file" accept="image/png,image/jpeg" disabled={busy} onChange={event => { void upload(event.currentTarget.files?.[0]); event.currentTarget.value = ''; }} /></label>}
    {busy && <InlineStatus>上传中…</InlineStatus>}
    {url && <img src={url} alt="素材预览" style={{ maxWidth: 180, maxHeight: 100, display: 'block', marginTop: 8 }} />}
  </div>;
}

function PlacementForm({ placement, client, canManage, busy, onSubmit, onFailure, onCancel }: {
  placement?: Placement; client: MarketingAdminClient; canManage: boolean; busy: boolean;
  onSubmit: (input: Record<string, unknown>) => void;
  onFailure: (caught: unknown) => void;
  onCancel?: () => void;
}) {
  return <form onSubmit={event => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    onSubmit({
      positionCode: String(form.get('positionCode') ?? 'HOME_ACTIVITY'),
      campaignId: String(form.get('campaignId') ?? '').trim(),
      cityCode: String(form.get('cityCode') ?? '').trim() || undefined,
      titleOverride: String(form.get('titleOverride') ?? '').trim() || undefined,
      subtitleOverride: String(form.get('subtitleOverride') ?? '').trim() || undefined,
      imageUrlOverride: String(form.get('imageUrlOverride') ?? '').trim() || undefined,
      templateCode: String(form.get('templateCode') ?? '').trim() || undefined,
      sortWeight: Number(form.get('sortWeight') ?? 0),
      startAt: iso(String(form.get('startAt') ?? '')),
      endAt: iso(String(form.get('endAt') ?? '')),
    });
  }}>
    <p className="hint">热门服务顶部 Banner 全平台最多同时启用 1 个（启用时校验，超限返回 409）；创建后为草稿，需在活动生效后启用。</p>
    <label>位置<select name="positionCode" defaultValue={placement?.positionCode ?? 'HOME_ACTIVITY'}>
      {PLACEMENT_POSITIONS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
    </select></label>
    <label>活动 ID<input name="campaignId" defaultValue={placement?.campaignId ?? ''} required /></label>
    <label>城市编码（空=全部）<input name="cityCode" defaultValue={placement?.cityCode ?? ''} /></label>
    <label>标题覆盖<input name="titleOverride" defaultValue={placement?.titleOverride ?? ''} maxLength={100} /></label>
    <label>副标题覆盖<input name="subtitleOverride" defaultValue={placement?.subtitleOverride ?? ''} maxLength={200} /></label>
    <AssetPicker name="imageUrlOverride" label="投放插画" defaultValue={placement?.imageUrlOverride}
      client={client} canManage={canManage} onFailure={onFailure} />
    <label>卡片模板<select name="templateCode" defaultValue={placement?.templateCode ?? ''}>
      <option value="">通用模板</option><option value="new_user">新客活动卡</option><option value="beauty">美容活动卡</option>
    </select></label>
    <label>排序<input name="sortWeight" type="number" min={0} max={9999} defaultValue={placement?.sortWeight ?? 0} /></label>
    <label>开始时间<input name="startAt" type="datetime-local" defaultValue={localInput(placement?.startAt ?? null)} required /></label>
    <label>结束时间<input name="endAt" type="datetime-local" defaultValue={localInput(placement?.endAt ?? null)} required /></label>
    <button type="submit" disabled={busy}>{placement ? '保存修改' : '创建投放位'}</button>
    {placement && onCancel && <button type="button" disabled={busy} onClick={onCancel}>取消</button>}
  </form>;
}

function HomeBlockForm({ block, client, canManage, busy, onSubmit, onFailure }: {
  block?: HomeBlock; client: MarketingAdminClient; canManage: boolean; busy: boolean;
  onSubmit: (input: Record<string, unknown>) => void; onFailure: (caught: unknown) => void;
}) {
  // F-A-03: targetType=HOT_SERVICE routes the C-side on the type alone (contract 71 v0.3 §10);
  // the server nulls targetValue for it exactly like NONE, so the input stays blank/disabled.
  const [targetType, setTargetType] = useState(block?.targetType ?? 'SERVICE_DIRECTORY');
  const needsValue = targetNeedsValue(targetType);
  return <form onSubmit={event => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    onSubmit({
      blockCode: String(form.get('blockCode') ?? '').trim(),
      positionCode: String(form.get('positionCode') ?? 'HOME_SERVICE'),
      templateCode: String(form.get('templateCode') ?? 'HOT_SERVICE_FEATURE'),
      title: String(form.get('title') ?? '').trim(),
      subtitle: String(form.get('subtitle') ?? '').trim() || undefined,
      tagName: String(form.get('tagName') ?? '').trim() || undefined,
      imageUrl: String(form.get('imageUrl') ?? '').trim() || undefined,
      targetType: String(form.get('targetType') ?? 'SERVICE_DIRECTORY'),
      targetValue: String(form.get('targetValue') ?? '').trim() || undefined,
      cityCode: String(form.get('cityCode') ?? '').trim() || undefined,
      sponsorMerchantId: String(form.get('sponsorMerchantId') ?? '').trim() || undefined,
      sponsorStoreId: String(form.get('sponsorStoreId') ?? '').trim() || undefined,
      sortWeight: form.get('sortWeight') ? Number(form.get('sortWeight')) : 0,
      startAt: iso(String(form.get('startAt') ?? '')),
      endAt: iso(String(form.get('endAt') ?? '')),
    });
  }}>
    <label className="filter-item">内容位编码（2-64，字母数字/下划线/短横线）<Input name="blockCode" defaultValue={block?.blockCode ?? ''} minLength={2} maxLength={64} required /></label>
    <label className="filter-item">位置<select name="positionCode" defaultValue={block?.positionCode ?? 'HOME_SERVICE'}>
      <option value="HOME_SERVICE">热门服务</option>
      <option value="HOME_COMMUNITY">宠友圈热门</option>
      <option value="HOME_EXOTIC_PLANT">植物&异宠</option>
    </select></label>
    <label className="filter-item">模板<select name="templateCode" defaultValue={block?.templateCode ?? 'HOT_SERVICE_FEATURE'}>
      <option value="HOT_SERVICE_FEATURE">热门服务大卡</option>
      <option value="COMMUNITY_HOT">宠友圈热门容器</option>
      <option value="EXOTIC_PLANT">植物&异宠小卡</option>
    </select></label>
    <label className="filter-item">标签名<Input name="tagName" defaultValue={block?.tagName ?? ''} maxLength={32} placeholder="热门服务 / 宠友圈热门 / 植物&异宠" /></label>
    <label className="filter-item">标题<Input name="title" defaultValue={block?.title ?? ''} maxLength={100} required /></label>
    <label className="filter-item">副标题<Input name="subtitle" defaultValue={block?.subtitle ?? ''} maxLength={200} /></label>
    <AssetPicker name="imageUrl" label="内容位图片" defaultValue={block?.imageUrl} client={client} canManage={canManage} onFailure={onFailure} />
    <label className="filter-item">落地类型<select name="targetType" defaultValue={block?.targetType ?? 'SERVICE_DIRECTORY'} onChange={event => setTargetType(event.target.value)}>
      {HOME_BLOCK_TARGET_TYPES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
    </select></label>
    <label className="filter-item">{needsValue ? '目标值' : '目标值（此类型留空即可，后端不保存目标值）'}
      <Input name="targetValue" defaultValue={block?.targetValue ?? ''} maxLength={255} disabled={!needsValue}
        placeholder={needsValue ? '如 宠物美容 / 门店ID / /consumer/pages/...' : '留空'} />
    </label>
    <label className="filter-item">城市编码（空=全部）<Input name="cityCode" defaultValue={block?.cityCode ?? ''} maxLength={32} /></label>
    <label className="filter-item">赞助商家 ID（预留竞价，可空）<Input name="sponsorMerchantId" defaultValue={block?.sponsorMerchantId ?? ''} /></label>
    <label className="filter-item">赞助门店 ID（预留竞价，可空）<Input name="sponsorStoreId" defaultValue={block?.sponsorStoreId ?? ''} /></label>
    <label className="filter-item">排序权重<Input name="sortWeight" type="number" min={0} max={9999} defaultValue={block?.sortWeight ?? 0} /></label>
    <label className="filter-item">开始时间<input name="startAt" type="datetime-local" defaultValue={block ? localInput(block.startAt) : ''} required /></label>
    <label className="filter-item">结束时间<input name="endAt" type="datetime-local" defaultValue={block ? localInput(block.endAt) : ''} required /></label>
    <Button type="primary" htmlType="submit" disabled={busy}>{block ? '保存修改' : '创建草稿'}</Button>
  </form>;
}

export default function MarketingCenterPage({ client, couponClient, categoryClient, canManage, canPublish, canReview, canCouponManage, canCouponPublish, onAuthLost }: {
  client: MarketingAdminClient;
  couponClient: CouponAdminClient;
  categoryClient: ServiceCategoryAdminClient;
  canManage: boolean;
  canPublish: boolean;
  canReview: boolean;
  canCouponManage: boolean;
  canCouponPublish: boolean;
  onAuthLost: () => void;
}) {
  const [tab, setTab] = useState<'campaigns' | 'placements' | 'home' | 'enrollments' | 'coupons'>('campaigns');
  const [campaigns, setCampaigns] = useState<Campaign[]>();
  const [placements, setPlacements] = useState<Placement[]>();
  const [editingPlacement, setEditingPlacement] = useState<Placement>();
  const [enrollments, setEnrollments] = useState<Enrollment[]>();
  const [coupons, setCoupons] = useState<CouponCampaign[]>();
  const [homeBlocks, setHomeBlocks] = useState<HomeBlock[]>();
  const [templates, setTemplates] = useState<AdminTemplateSummary[]>([]);
  const [detail, setDetail] = useState<CampaignDetail>();
  const [claims, setClaims] = useState<CouponClaim[]>();
  const [stores, setStores] = useState<StoreOption[]>([]);
  const [storeKeyword, setStoreKeyword] = useState('');
  const [offerTag, setOfferTag] = useState('');
  const [offerTitle, setOfferTitle] = useState('');
  const [offerCopy, setOfferCopy] = useState('');
  const [schema, setSchema] = useState('');
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const sequence = useRef(0);
  // F-A-02: enabled dictionary categories feeding the campaign forms' multi-select.
  const [categoryOptions, setCategoryOptions] = useState<CategoryOption[]>([]);
  const [categoryState, setCategoryState] = useState<CategoryLoadState>('loading');
  const [categoryFailure, setCategoryFailure] = useState('分类列表读取失败，请重试');

  async function loadCategoryOptions() {
    setCategoryState('loading');
    try {
      // The dictionary face caps pageSize at 50 (contract 74 §4); page through ENABLED rows.
      const options: CategoryOption[] = [];
      for (let page = 1; page <= 20; page++) {
        const result = await categoryClient.list({ status: 'ENABLED' }, page, 50);
        for (const row of result.items) {
          if (!options.some(option => option.code === row.categoryCode)) {
            options.push({ code: row.categoryCode, name: row.categoryName });
          }
        }
        if (options.length >= result.total || result.items.length === 0) break;
      }
      setCategoryOptions(options);
      setCategoryState('ready');
    } catch (caught) {
      if (caught instanceof RequestFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
      setCategoryFailure(caught instanceof RequestFailure && (caught.code === 'FORBIDDEN' || caught.code === 'COMMON_FORBIDDEN')
        ? '当前账号未授予 service.category.read，无法读取分类列表；不勾选任何分类 = 面向全部分类'
        : '分类列表读取失败，请重试；不勾选任何分类 = 面向全部分类');
      setCategoryState('failed');
    }
  }

  // Existing campaign codes the dictionary no longer lists stay rendered (checked) so an
  // untouched edit does not silently rewrite the stored category scope.
  function campaignCategoryOptions(existing: string[]): CategoryOption[] {
    const merged = categoryOptions.map(option => ({ ...option }));
    for (const code of existing) {
      if (!merged.some(option => option.code === code)) merged.push({ code, name: '', foreign: true });
    }
    return merged;
  }

  async function loadCampaigns() {
    const call = ++sequence.current;
    try {
      const page = await client.listCampaigns({}, 1, 50);
      if (call === sequence.current) setCampaigns(page.items);
    } catch (caught) { authOrError(caught, '读取活动失败'); }
  }
  async function loadPlacements() {
    try { setPlacements((await client.listPlacements()).items); } catch (caught) { authOrError(caught, '读取投放位失败'); }
  }
  async function loadEnrollments() {
    try { setEnrollments((await client.listEnrollments()).items); } catch (caught) { authOrError(caught, '读取报名失败'); }
  }
  async function loadCoupons() {
    try { setCoupons((await client.listCouponCampaigns()).items); } catch (caught) { authOrError(caught, '读取券活动失败'); }
  }
  async function loadHomeBlocks() {
    try { setHomeBlocks((await client.listHomeBlocks()).items); } catch (caught) { authOrError(caught, '读取首页内容位失败'); }
  }
  function authOrError(caught: unknown, fallback: string) {
    if (caught instanceof Error && caught.message === 'UNAUTHENTICATED') { onAuthLost(); return; }
    setError(describeMarketingFailure(caught, fallback));
  }
  async function refreshAll() {
    await Promise.allSettled([loadCampaigns(), loadPlacements(), loadHomeBlocks(), loadEnrollments(), loadCoupons()]);
    if (detail) {
      try { setDetail(await client.getCampaign(detail.campaign.campaignId)); } catch { /* keep the previous detail on a transient read failure */ }
    }
  }
  async function run(action: () => Promise<unknown>, ok: string) {
    setBusy(true); setError(''); setNotice('');
    try { await action(); setNotice(ok); await refreshAll(); }
    catch (caught) {
      authOrError(caught, '操作失败');
      // Keep the readable text's promise: conflict/not-found texts say the data was refreshed.
      if (isMarketingStale(caught)) void refreshAll();
    }
    finally { setBusy(false); }
  }

  useEffect(() => { void Promise.all([loadCampaigns(), loadPlacements(), loadHomeBlocks(), loadEnrollments(), loadCoupons()]); return () => { sequence.current++; }; }, [client]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { void loadCategoryOptions(); }, [categoryClient]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { couponClient.listTemplates({ status: 'ACTIVE' }).then(page => setTemplates(page.items)).catch(() => setTemplates([])); }, [couponClient]);

  async function openDetail(campaign: Campaign) {
    setError('');
    try {
      const next = await client.getCampaign(campaign.campaignId);
      setDetail(next);
      setSchema(next.pageSchema ?? '');
      setStores([]);
    } catch (caught) { authOrError(caught, '读取活动详情失败'); }
  }

  async function searchStores() {
    setError('');
    try { setStores((await client.listStores({ keyword: storeKeyword || undefined })).items); }
    catch (caught) { authOrError(caught, '门店查询失败'); }
  }

  function saveStores() {
    if (!detail) return;
    const selected: StoreLink[] = stores.map(store => ({
      storeId: store.storeId, merchantId: store.merchantId, source: 'PLATFORM_SELECTED',
      status: 'ACTIVE', offerTag: offerTag || null, offerTitle: offerTitle || null,
      offerCopy: offerCopy || null, sortWeight: 0,
    }));
    void run(() => client.replaceStores(detail.campaign.campaignId, selected, detail.campaign.version), '参与门店已保存');
  }

  const storeColumns: TableColumnsType<StoreLink> = [
    { title: '门店 ID', dataIndex: 'storeId' },
    { title: '来源', dataIndex: 'source' },
    { title: '优惠角标', render: (_, store) => store.offerTag ?? '--' },
    { title: '优惠标题', render: (_, store) => store.offerTitle ?? '--' },
    { title: '优惠说明', render: (_, store) => store.offerCopy ?? '--' },
  ];
  const storeSearchColumns: TableColumnsType<StoreOption> = [
    { title: '门店', dataIndex: 'storeName' },
    { title: '城市', dataIndex: 'cityCode' },
    { title: '地址', dataIndex: 'address' },
  ];
  const placementColumns: TableColumnsType<Placement> = [
    { title: 'ID', dataIndex: 'placementId' },
    { title: '位置', dataIndex: 'positionCode' },
    { title: '活动', render: (_, placement) => `${placement.campaignTitle}（${placement.campaignId}）` },
    { title: '模板', render: (_, placement) => placement.templateCode ?? '通用' },
    { title: '城市', render: (_, placement) => placement.cityCode ?? '全部' },
    { title: '时间', render: (_, placement) => `${placement.startAt.slice(0, 16)} ~ ${placement.endAt.slice(0, 16)}` },
    { title: '状态', render: (_, placement) => <StatusTag label={STATUS_TEXT[placement.status] ?? placement.status} status={placement.status} /> },
    { title: '操作', render: (_, placement) => canManage && <>
      {placement.status !== 'ENABLED' && <Button disabled={busy} onClick={() => setEditingPlacement(placement)}>编辑</Button>}
      {placement.status !== 'ENABLED' && <Button disabled={busy} onClick={() => void run(() => client.placementAction(placement.placementId, 'enable', placement.version), '投放位已启用')}>启用</Button>}
      {placement.status === 'ENABLED' && <Button danger disabled={busy} onClick={() => void run(() => client.placementAction(placement.placementId, 'disable', placement.version), '投放位已停用')}>停用</Button>}
    </> },
  ];
  const homeBlockColumns: TableColumnsType<HomeBlock> = [
    { title: 'ID', dataIndex: 'blockId' },
    { title: '位置/模板', render: (_, block) => <>{block.positionCode}<br />{block.templateCode}</> },
    { title: '标题', render: (_, block) => <>{block.title}<br /><small>{block.tagName ?? ''}</small></> },
    { title: '目标', render: (_, block) => <>{block.targetType}<br /><small>{block.targetValue ?? ''}</small></> },
    { title: '窗口', render: (_, block) => `${block.startAt.slice(0, 10)} ~ ${block.endAt.slice(0, 10)}` },
    { title: '状态', render: (_, block) => <StatusTag label={STATUS_TEXT[block.status] ?? block.status} status={block.status} /> },
    { title: '版本', dataIndex: 'version' },
    { title: '操作', render: (_, block) => <>
      {canManage && <details>
        <summary>编辑</summary>
        <HomeBlockForm key={block.version} block={block} client={client} canManage={canManage} busy={busy}
          onSubmit={input => void run(() => client.updateHomeBlock(block.blockId, input, block.version), '首页内容位已保存')}
          onFailure={caught => authOrError(caught, '素材上传失败')} />
      </details>}
      {canManage && block.status !== 'ENABLED' && <Button disabled={busy}
        onClick={() => void run(() => client.homeBlockAction(block.blockId, 'enable', block.version), '首页内容位已启用')}>启用</Button>}
      {canManage && block.status === 'ENABLED' && <Button danger disabled={busy}
        onClick={() => void run(() => client.homeBlockAction(block.blockId, 'disable', block.version), '首页内容位已停用')}>停用</Button>}
    </> },
  ];
  const enrollmentColumns: TableColumnsType<Enrollment> = [
    { title: 'ID', dataIndex: 'enrollmentId' },
    { title: '活动', dataIndex: 'campaignTitle' },
    { title: '门店', render: (_, enrollment) => enrollment.storeName ?? enrollment.storeId },
    { title: '优惠', render: (_, enrollment) => <>{enrollment.offerTitle ?? '--'}<br />{enrollment.offerCopy ?? ''}</> },
    { title: '状态', render: (_, enrollment) => <StatusTag
      label={`${STATUS_TEXT[enrollment.status] ?? enrollment.status}${enrollment.rejectReason ? `（${enrollment.rejectReason}）` : ''}`}
      status={enrollment.status} /> },
    { title: '提交时间', render: (_, enrollment) => enrollment.submittedAt.slice(0, 16) },
    { title: '操作', render: (_, enrollment) => canReview && ['SUBMITTED', 'UNDER_REVIEW'].includes(enrollment.status) && <>
      <Button disabled={busy} onClick={() => void run(() => client.reviewEnrollment(enrollment.enrollmentId, 'APPROVE', undefined, enrollment.version), '报名已通过')}>通过</Button>
      <Button danger disabled={busy} onClick={() => {
        const reason = window.prompt('驳回原因（1-500 字）')?.trim();
        if (reason) void run(() => client.reviewEnrollment(enrollment.enrollmentId, 'REJECT', reason, enrollment.version), '报名已驳回');
      }}>驳回</Button>
    </> },
  ];
  const couponColumns: TableColumnsType<CouponCampaign> = [
    { title: 'ID', dataIndex: 'campaignId' },
    { title: '名称', dataIndex: 'name' },
    { title: '模板', render: (_, campaign) => `${campaign.templateName}（${campaign.templateId}）` },
    { title: '面额/门槛', render: (_, campaign) => `${campaign.amountOff ?? '--'} / ${campaign.thresholdAmount ?? '无门槛'}` },
    { title: '对象', render: (_, campaign) => campaign.audienceType === 'NEW_USER' ? '新用户' : '全部用户' },
    { title: '已发/上限', render: (_, campaign) => `${campaign.issuedCount} / ${campaign.totalLimit}（¥${campaign.issuedAmount}）` },
    { title: '预算', render: (_, campaign) => `¥${campaign.budgetLimit}` },
    { title: '状态', render: (_, campaign) => <StatusTag label={STATUS_TEXT[campaign.status] ?? campaign.status} status={campaign.status} /> },
    { title: '操作', render: (_, campaign) => <>
      {canCouponPublish && <>
        {campaign.status === 'DRAFT' && <Button disabled={busy} onClick={() => void run(() => client.couponCampaignAction(campaign.campaignId, 'publish', campaign.version), '券活动已发布')}>发布</Button>}
        {campaign.status === 'ACTIVE' && <Button disabled={busy} onClick={() => void run(() => client.couponCampaignAction(campaign.campaignId, 'pause', campaign.version), '券活动已暂停')}>暂停</Button>}
        {['ACTIVE', 'PAUSED', 'SCHEDULED'].includes(campaign.status) && <Button danger disabled={busy} onClick={() => void run(() => client.couponCampaignAction(campaign.campaignId, 'end', campaign.version), '券活动已结束')}>结束</Button>}
      </>}
      <Button onClick={() => void client.listCouponClaims(campaign.campaignId).then(page => setClaims(page.items)).catch(caught => authOrError(caught, '读取领取记录失败'))}>领取记录</Button>
    </> },
  ];
  const claimColumns: TableColumnsType<CouponClaim> = [
    { title: '领取 ID', dataIndex: 'claimId' },
    { title: '用户', dataIndex: 'userId' },
    { title: '券实例', dataIndex: 'couponInstanceId' },
    { title: '时间', render: (_, claim) => claim.claimedAt.slice(0, 19) },
  ];

  return <PageContainer
    title="营销活动中心"
    hint="活动位/活动会场/商家报名/商家发起活动/券活动。活动封面/投放插画可直接上传（PNG/JPEG，≤2MiB）；C 端首页只加载后台素材 URL，新增活动/换图不需要小程序发版，新增卡片模板才需要发版。">
    <nav className="filter-bar" aria-label="营销中心分区">
      <Button onClick={() => setTab('campaigns')} disabled={tab === 'campaigns'}>活动</Button>
      <Button onClick={() => setTab('placements')} disabled={tab === 'placements'}>投放位</Button>
      <Button onClick={() => setTab('home')} disabled={tab === 'home'}>首页内容位</Button>
      <Button onClick={() => setTab('enrollments')} disabled={tab === 'enrollments'}>商家报名</Button>
      <Button onClick={() => setTab('coupons')} disabled={tab === 'coupons'}>券活动</Button>
      {notice && <InlineStatus>{notice}</InlineStatus>}
      {error && <ErrorAlert>{error}</ErrorAlert>}
    </nav>

    {tab === 'campaigns' && <>
      {canManage && <section className="detail-card">
        <h3>新建活动</h3>
        <form onSubmit={event => {
          event.preventDefault();
          const form = new FormData(event.currentTarget);
          const input: CampaignInput = {
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
            sortWeight: form.get('sortWeight') ? Number(form.get('sortWeight')) : 0,
          };
          void run(() => client.createCampaign(input), '活动草稿已创建');
        }}>
          <label className="filter-item">活动名称（2-100）<Input name="name" minLength={2} maxLength={100} required /></label>
          <label className="filter-item">标题（1-100）<Input name="title" maxLength={100} required /></label>
          <label className="filter-item">副标题<Input name="subtitle" maxLength={200} /></label>
          <label className="filter-item">活动类型<select name="campaignType"><option value="DISPLAY">展示活动</option><option value="COUPON">券活动</option><option value="MERCHANT_ENROLL">商家招商</option></select></label>
          <label className="filter-item">参与方式<select name="participationMode"><option value="PLATFORM_SELECTED">平台选择门店</option><option value="MERCHANT_ENROLL">商家报名</option></select></label>
          <label className="filter-item">标签编码<Input name="tagCode" placeholder="new-user / beauty / boarding" maxLength={32} /></label>
          <label className="filter-item">标签名称<Input name="tagName" placeholder="新客专享 / 美容优惠" maxLength={32} /></label>
          <AssetPicker name="coverUrl" label="活动封面" client={client} canManage={canManage} onFailure={caught => authOrError(caught, '素材上传失败')} />
          <label className="filter-item">城市编码（逗号分隔，空=全部）<Input name="cityCodes" placeholder="shanghai,chengdu" /></label>
          <CategoryField state={categoryState} options={categoryOptions} defaultCodes={[]}
            failureText={categoryFailure} onRetry={() => void loadCategoryOptions()} />
          <label className="filter-item">开始时间<input name="startAt" type="datetime-local" required /></label>
          <label className="filter-item">结束时间<input name="endAt" type="datetime-local" required /></label>
          <label className="filter-item">报名开始<input name="enrollStartAt" type="datetime-local" /></label>
          <label className="filter-item">报名结束<input name="enrollEndAt" type="datetime-local" /></label>
          <label className="filter-item">报名名额<Input name="enrollmentQuota" type="number" min={1} max={10000} /></label>
          <label className="filter-item">关联券活动 ID<Input name="couponCampaignId" /></label>
          <label className="filter-item">排序权重<Input name="sortWeight" type="number" min={0} max={9999} defaultValue={0} /></label>
          <Button type="primary" htmlType="submit" disabled={busy}>创建草稿</Button>
        </form>
      </section>}
      {campaigns === undefined ? <InlineStatus>加载中</InlineStatus> : <table>
        <thead><tr><th>ID</th><th>名称</th><th>类型</th><th>状态</th><th>时间</th><th>门店</th><th>版本</th><th>操作</th></tr></thead>
        <tbody>{campaigns.map(campaign => <tr key={campaign.campaignId}>
          <td>{campaign.campaignId}</td><td>{campaign.name}</td>
          <td>{campaign.campaignType} / {campaign.participationMode}</td>
          <td><StatusTag label={STATUS_TEXT[campaign.status] ?? campaign.status} status={campaign.status} /></td>
          <td>{campaign.startAt.slice(0, 10)} ~ {campaign.endAt.slice(0, 10)}</td>
          <td>{campaign.storeCount}</td><td>{campaign.version}</td>
          <td>
            <Button onClick={() => void openDetail(campaign)}>详情</Button>
            {canPublish && (campaign.ownerType === 'MERCHANT'
              ? campaign.status === 'PENDING_REVIEW'
              : ['DRAFT', 'PENDING_REVIEW', 'SCHEDULED', 'PAUSED'].includes(campaign.status)) &&
              <Button type="primary" disabled={busy} onClick={() => void run(() => client.campaignAction(campaign.campaignId, 'publish', campaign.version), '活动已发布')}>发布</Button>}
            {canPublish && campaign.ownerType === 'MERCHANT' && campaign.status === 'PENDING_REVIEW' &&
              <Button danger disabled={busy} onClick={() => {
                const reason = window.prompt('驳回原因');
                if (reason) void run(() => client.rejectCampaign(campaign.campaignId, reason, campaign.version), '活动已驳回');
              }}>驳回</Button>}
            {canPublish && campaign.status === 'ONLINE' &&
              <Button disabled={busy} onClick={() => void run(() => client.campaignAction(campaign.campaignId, 'pause', campaign.version), '活动已暂停')}>暂停</Button>}
            {canPublish && ['ONLINE', 'PAUSED', 'SCHEDULED'].includes(campaign.status) &&
              <Button danger disabled={busy} onClick={() => void run(() => client.campaignAction(campaign.campaignId, 'end', campaign.version), '活动已结束')}>结束</Button>}
            {canPublish && ['DRAFT', 'PENDING_REVIEW', 'SCHEDULED', 'PAUSED'].includes(campaign.status) &&
              <Button danger disabled={busy} onClick={() => void run(() => client.campaignAction(campaign.campaignId, 'cancel', campaign.version), '活动已取消')}>取消</Button>}
          </td>
        </tr>)}{campaigns.length === 0 && <tr><td colSpan={8}>无活动</td></tr>}</tbody>
      </table>}
      {detail && <section className="detail-card" aria-label="活动详情">
        <h3>{detail.campaign.title}（{STATUS_TEXT[detail.campaign.status] ?? detail.campaign.status} · v{detail.campaign.version}）</h3>
        <p>ID {detail.campaign.campaignId} · 页面版本 {detail.campaign.pageVersion} · 门店 {detail.campaign.storeCount}</p>
        {canManage && <details key={detail.campaign.version}>
          <summary>编辑活动基础信息</summary>
          <form onSubmit={event => {
            event.preventDefault();
            const form = new FormData(event.currentTarget);
            const input: CampaignInput = {
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
              sortWeight: form.get('sortWeight') ? Number(form.get('sortWeight')) : 0,
            };
            void run(() => client.updateCampaign(detail.campaign.campaignId, input, detail.campaign.version), '活动基础信息已保存');
          }}>
            <label className="filter-item">名称<Input name="name" defaultValue={detail.campaign.name} minLength={2} maxLength={100} required /></label>
            <label className="filter-item">标题<Input name="title" defaultValue={detail.campaign.title} maxLength={100} required /></label>
            <label className="filter-item">副标题<Input name="subtitle" defaultValue={detail.campaign.subtitle ?? ''} maxLength={200} /></label>
            <label className="filter-item">类型<select name="campaignType" defaultValue={detail.campaign.campaignType}><option value="DISPLAY">展示活动</option><option value="COUPON">券活动</option><option value="MERCHANT_ENROLL">商家招商</option></select></label>
            <label className="filter-item">参与方式<select name="participationMode" defaultValue={detail.campaign.participationMode}><option value="PLATFORM_SELECTED">平台选择门店</option><option value="MERCHANT_ENROLL">商家报名</option></select></label>
            <label className="filter-item">标签编码<Input name="tagCode" defaultValue={detail.campaign.tagCode ?? ''} maxLength={32} /></label>
            <label className="filter-item">标签名称<Input name="tagName" defaultValue={detail.campaign.tagName ?? ''} maxLength={32} /></label>
            <AssetPicker name="coverUrl" label="活动封面" defaultValue={detail.campaign.coverUrl} client={client} canManage={canManage} onFailure={caught => authOrError(caught, '素材上传失败')} />
            <label className="filter-item">城市编码（逗号分隔，空=全部）<Input name="cityCodes" defaultValue={detail.campaign.cityCodes.join(',')} /></label>
            <CategoryField state={categoryState} options={campaignCategoryOptions(detail.campaign.serviceCategoryCodes)}
              defaultCodes={detail.campaign.serviceCategoryCodes} failureText={categoryFailure}
              onRetry={() => void loadCategoryOptions()} />
            <label className="filter-item">开始时间<input name="startAt" type="datetime-local" defaultValue={localInput(detail.campaign.startAt)} required /></label>
            <label className="filter-item">结束时间<input name="endAt" type="datetime-local" defaultValue={localInput(detail.campaign.endAt)} required /></label>
            <label className="filter-item">报名开始<input name="enrollStartAt" type="datetime-local" defaultValue={localInput(detail.campaign.enrollStartAt)} /></label>
            <label className="filter-item">报名结束<input name="enrollEndAt" type="datetime-local" defaultValue={localInput(detail.campaign.enrollEndAt)} /></label>
            <label className="filter-item">报名名额<Input name="enrollmentQuota" type="number" min={1} max={10000} defaultValue={detail.campaign.enrollmentQuota ?? ''} /></label>
            <label className="filter-item">关联券活动 ID<Input name="couponCampaignId" defaultValue={detail.campaign.couponCampaignId ?? ''} /></label>
            <label className="filter-item">排序权重<Input name="sortWeight" type="number" min={0} max={9999} defaultValue={detail.campaign.sortWeight} /></label>
            <Button type="primary" htmlType="submit" disabled={busy}>保存基础信息</Button>
          </form>
        </details>}
        <h4>参与门店</h4>
        <ConfigProvider renderEmpty={() => '暂无参与门店'}>
          <DataTable columns={storeColumns} rows={detail.stores} rowKey={store => store.storeId} />
        </ConfigProvider>
        {canManage && <>
          <label>门店搜索<Input value={storeKeyword} onChange={event => setStoreKeyword(event.target.value)} placeholder="门店名称关键词" /></label>
          <Button onClick={() => void searchStores()}>搜索门店</Button>
          {stores.length > 0 && <DataTable columns={storeSearchColumns} rows={stores} rowKey={store => store.storeId} />}
          <p className="hint">当前“保存门店”会把搜索结果中的门店全部写入本活动，并应用下方统一的优惠文案。</p>
          <label>优惠角标<Input value={offerTag} onChange={event => setOfferTag(event.target.value)} maxLength={32} placeholder="5折 / 立减" /></label>
          <label>优惠标题<Input value={offerTitle} onChange={event => setOfferTitle(event.target.value)} maxLength={100} /></label>
          <label>优惠说明<Input value={offerCopy} onChange={event => setOfferCopy(event.target.value)} maxLength={100} /></label>
          <Button disabled={busy} onClick={() => void run(() => client.replaceStores(detail.campaign.campaignId,
            detail.stores.map(store => ({ ...store, offerTag: offerTag || null, offerTitle: offerTitle || null, offerCopy: offerCopy || null })), detail.campaign.version), '参与门店已保存')}>保存现有门店文案</Button>
          <Button disabled={busy || stores.length === 0} onClick={() => saveStores()}>用搜索结果替换门店</Button>
        </>}
        <h4>活动页 Schema</h4>
        <Input.TextArea value={schema} onChange={event => setSchema(event.target.value)} rows={12} spellCheck={false} />
        {canManage && <Button disabled={busy} onClick={() => void run(() => client.savePage(detail.campaign.campaignId, schema, detail.campaign.version), 'Schema 草稿已保存')}>保存 Schema 草稿</Button>}
        {canPublish && (detail.campaign.ownerType === 'MERCHANT'
          ? detail.campaign.status === 'PENDING_REVIEW'
          : ['DRAFT', 'PENDING_REVIEW', 'SCHEDULED', 'PAUSED'].includes(detail.campaign.status)) &&
          <Button type="primary" disabled={busy} onClick={() => void run(() => client.campaignAction(detail.campaign.campaignId, 'publish', detail.campaign.version), '活动已发布')}>发布（发布当前 Schema 与门店）</Button>}
        {canPublish && detail.campaign.ownerType === 'MERCHANT' && detail.campaign.status === 'PENDING_REVIEW' &&
          <Button danger disabled={busy} onClick={() => {
            const reason = window.prompt('驳回原因');
            if (reason) void run(() => client.rejectCampaign(detail.campaign.campaignId, reason, detail.campaign.version), '活动已驳回');
          }}>驳回商家活动</Button>}
        {detail.pageVersions.length > 0 && <div>
          <h4>页面发布版本</h4>
          <ul>{detail.pageVersions.map(version => <li key={version.version}>
            v{version.version} · {version.publishedAt.slice(0, 19).replace('T', ' ')}
            {canPublish && version.version !== detail.campaign.pageVersion && <Button disabled={busy}
              onClick={() => void run(() => client.rollbackPage(detail.campaign.campaignId, version.version, detail.campaign.version), `已回滚到 v${version.version}`)}>回滚到此版本</Button>}
          </li>)}</ul>
        </div>}
      </section>}
    </>}

    {tab === 'placements' && <>
      {canManage && <section className="detail-card">
        <h3>新建投放位</h3>
        <PlacementForm client={client} canManage={canManage} busy={busy}
          onSubmit={input => void run(() => client.createPlacement(input), '投放位已创建')}
          onFailure={caught => authOrError(caught, '素材上传失败')} />
      </section>}
      {canManage && editingPlacement && <section className="detail-card">
        <h3>编辑投放位：{editingPlacement.placementId}</h3>
        <PlacementForm placement={editingPlacement} client={client} canManage={canManage} busy={busy}
          onSubmit={input => void run(async () => {
            await client.updatePlacement(editingPlacement.placementId, input, editingPlacement.version);
            setEditingPlacement(undefined);
          }, '投放位已保存')}
          onFailure={caught => authOrError(caught, '素材上传失败')}
          onCancel={() => setEditingPlacement(undefined)} />
      </section>}
      {placements === undefined ? <InlineStatus>加载中</InlineStatus> : <ConfigProvider renderEmpty={() => '无投放位'}>
        <DataTable columns={placementColumns} rows={placements} rowKey={placement => placement.placementId} />
      </ConfigProvider>}
    </>}

    {tab === 'home' && <>
      <p className="hint">首页「热门服务 / 宠友圈热门 / 植物&异宠」由运营手动配置；图片走后台上传，改文案/换图/排序/上下线不需要小程序发版。落地类型「热门服务」无需目标值，C 端按类型直接路由（目标值留空）。预留赞助商家/门店字段，V1 不做竞价结算。</p>
      {canManage && <section className="detail-card">
        <h3>新建首页内容位</h3>
        <HomeBlockForm client={client} canManage={canManage} busy={busy}
          onSubmit={input => void run(() => client.createHomeBlock(input), '首页内容位草稿已创建')}
          onFailure={caught => authOrError(caught, '素材上传失败')} />
      </section>}
      {homeBlocks === undefined ? <InlineStatus>加载中</InlineStatus> : <ConfigProvider renderEmpty={() => '暂无内容位'}>
        <DataTable columns={homeBlockColumns} rows={homeBlocks} rowKey={block => block.blockId} />
      </ConfigProvider>}
    </>}

    {tab === 'enrollments' && <>
      {enrollments === undefined ? <InlineStatus>加载中</InlineStatus> : <ConfigProvider renderEmpty={() => '无报名'}>
        <DataTable columns={enrollmentColumns} rows={enrollments} rowKey={enrollment => enrollment.enrollmentId} />
      </ConfigProvider>}
    </>}

    {tab === 'coupons' && <>
      {canCouponManage && <section className="detail-card">
        <h3>新建券活动</h3>
        <form onSubmit={event => {
          event.preventDefault();
          const form = new FormData(event.currentTarget);
          void run(() => client.createCouponCampaign({
            name: String(form.get('name') ?? '').trim(),
            templateId: String(form.get('templateId') ?? '').trim(),
            audienceType: String(form.get('audienceType') ?? 'ALL'),
            perUserLimit: 1,
            totalLimit: Number(form.get('totalLimit') ?? 0),
            budgetLimit: Number(form.get('budgetLimit') ?? 0),
            startAt: iso(String(form.get('startAt') ?? '')),
            endAt: iso(String(form.get('endAt') ?? '')),
          }), '券活动已创建');
        }}>
          <label className="filter-item">活动名称<Input name="name" minLength={2} maxLength={50} required /></label>
          <label className="filter-item">券模板<select name="templateId" required>{templates.map(template => <option key={template.templateId} value={template.templateId}>{template.name}（{template.templateId} · ¥{template.amountOff}{template.thresholdAmount ? ` 满${template.thresholdAmount}` : ' 无门槛'}）</option>)}</select></label>
          {templates.length === 0 && <p className="hint">暂无启用中的券模板，请先在「券模板」页启用模板。</p>}
          <label className="filter-item">发放对象<select name="audienceType"><option value="ALL">全部用户</option><option value="NEW_USER">新用户</option></select></label>
          <label className="filter-item">总发放上限<Input name="totalLimit" type="number" min={1} max={1000000} required /></label>
          <label className="filter-item">预算上限（元）<Input name="budgetLimit" type="number" min={0.01} step={0.01} required /></label>
          <label className="filter-item">开始时间<input name="startAt" type="datetime-local" required /></label>
          <label className="filter-item">结束时间<input name="endAt" type="datetime-local" required /></label>
          <Button type="primary" htmlType="submit" disabled={busy}>创建券活动</Button>
        </form>
      </section>}
      {coupons === undefined ? <InlineStatus>加载中</InlineStatus> : <ConfigProvider renderEmpty={() => '无券活动'}>
        <DataTable columns={couponColumns} rows={coupons} rowKey={campaign => campaign.campaignId} />
      </ConfigProvider>}
      {claims && <section className="detail-card"><h3>领取记录</h3>
        <ConfigProvider renderEmpty={() => ''}>
          <DataTable columns={claimColumns} rows={claims} rowKey={claim => claim.claimId} />
        </ConfigProvider>
      </section>}
    </>}
  </PageContainer>;
}
