import { useEffect, useRef, useState } from 'react';
import type { AdminTemplateSummary, CouponAdminClient } from '../api/couponAdmin';
import {
  type Campaign, type CampaignDetail, type CampaignInput, type CouponCampaign, type CouponClaim,
  type Enrollment, type HomeBlock, type MarketingAdminClient, type Placement, type StoreLink, type StoreOption,
} from '../api/marketingAdmin';

const POSITIONS = [
  ['HOME_ACTIVITY', '首页活动位（活动钜惠）'],
  ['HOME_BANNER', '首页 Banner'],
  ['STORE_LIST_TOP', '商家列表置顶（商家钜惠）'],
] as const;
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
    <label>{label} URL<input name={name} value={url} onChange={event => setUrl(event.target.value)} maxLength={512} /></label>
    {canManage && <label>上传插画（PNG/JPEG，≤2MiB）<input type="file" accept="image/png,image/jpeg" disabled={busy} onChange={event => { void upload(event.currentTarget.files?.[0]); event.currentTarget.value = ''; }} /></label>}
    {busy && <span role="status">上传中…</span>}
    {url && <img src={url} alt="素材预览" style={{ maxWidth: 180, maxHeight: 100, display: 'block', marginTop: 8 }} />}
  </div>;
}

function HomeBlockForm({ block, client, canManage, busy, onSubmit, onFailure }: {
  block?: HomeBlock; client: MarketingAdminClient; canManage: boolean; busy: boolean;
  onSubmit: (input: Record<string, unknown>) => void; onFailure: (caught: unknown) => void;
}) {
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
    <label>内容位编码（2-64，字母数字/下划线/短横线）<input name="blockCode" defaultValue={block?.blockCode ?? ''} minLength={2} maxLength={64} required /></label>
    <label>位置<select name="positionCode" defaultValue={block?.positionCode ?? 'HOME_SERVICE'}>
      <option value="HOME_SERVICE">热门服务</option>
      <option value="HOME_COMMUNITY">宠友圈热门</option>
      <option value="HOME_EXOTIC_PLANT">植物&amp;异宠</option>
    </select></label>
    <label>模板<select name="templateCode" defaultValue={block?.templateCode ?? 'HOT_SERVICE_FEATURE'}>
      <option value="HOT_SERVICE_FEATURE">热门服务大卡</option>
      <option value="COMMUNITY_HOT">宠友圈热门容器</option>
      <option value="EXOTIC_PLANT">植物&amp;异宠小卡</option>
    </select></label>
    <label>标签名<input name="tagName" defaultValue={block?.tagName ?? ''} maxLength={32} placeholder="热门服务 / 宠友圈热门 / 植物&异宠" /></label>
    <label>标题<input name="title" defaultValue={block?.title ?? ''} maxLength={100} required /></label>
    <label>副标题<input name="subtitle" defaultValue={block?.subtitle ?? ''} maxLength={200} /></label>
    <AssetPicker name="imageUrl" label="内容位图片" defaultValue={block?.imageUrl} client={client} canManage={canManage} onFailure={onFailure} />
    <label>落地类型<select name="targetType" defaultValue={block?.targetType ?? 'SERVICE_DIRECTORY'}>
      <option value="SERVICE_DIRECTORY">服务目录</option>
      <option value="SERVICE_CATEGORY">服务分类关键词</option>
      <option value="STORE">门店</option>
      <option value="SERVICE">单次服务</option>
      <option value="CAMPAIGN">活动</option>
      <option value="COMMUNITY">宠友圈</option>
      <option value="MINIAPP_PATH">小程序路径</option>
      <option value="NONE">无跳转</option>
    </select></label>
    <label>目标值<input name="targetValue" defaultValue={block?.targetValue ?? ''} maxLength={255} placeholder="如 宠物美容 / 门店ID / /consumer/pages/..." /></label>
    <label>城市编码（空=全部）<input name="cityCode" defaultValue={block?.cityCode ?? ''} maxLength={32} /></label>
    <label>赞助商家 ID（预留竞价，可空）<input name="sponsorMerchantId" defaultValue={block?.sponsorMerchantId ?? ''} /></label>
    <label>赞助门店 ID（预留竞价，可空）<input name="sponsorStoreId" defaultValue={block?.sponsorStoreId ?? ''} /></label>
    <label>排序权重<input name="sortWeight" type="number" min={0} max={9999} defaultValue={block?.sortWeight ?? 0} /></label>
    <label>开始时间<input name="startAt" type="datetime-local" defaultValue={block ? localInput(block.startAt) : ''} required /></label>
    <label>结束时间<input name="endAt" type="datetime-local" defaultValue={block ? localInput(block.endAt) : ''} required /></label>
    <button type="submit" disabled={busy}>{block ? '保存修改' : '创建草稿'}</button>
  </form>;
}

export default function MarketingCenterPage({ client, couponClient, canManage, canPublish, canReview, canCouponManage, canCouponPublish, onAuthLost }: {
  client: MarketingAdminClient;
  couponClient: CouponAdminClient;
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
    setError(caught instanceof Error ? `${fallback}（${caught.message}）` : fallback);
  }
  async function run(action: () => Promise<unknown>, ok: string) {
    setBusy(true); setError(''); setNotice('');
    try { await action(); setNotice(ok); await Promise.allSettled([loadCampaigns(), loadPlacements(), loadHomeBlocks(), loadEnrollments(), loadCoupons()]); if (detail) { try { setDetail(await client.getCampaign(detail.campaign.campaignId)); } catch { /* keep the previous detail on a transient read failure */ } } }
    catch (caught) { authOrError(caught, '操作失败'); }
    finally { setBusy(false); }
  }

  useEffect(() => { void Promise.all([loadCampaigns(), loadPlacements(), loadHomeBlocks(), loadEnrollments(), loadCoupons()]); return () => { sequence.current++; }; }, [client]); // eslint-disable-line react-hooks/exhaustive-deps
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

  return <main className="page">
    <h2>营销活动中心</h2>
    <p className="hint">活动位/活动会场/商家报名/商家发起活动/券活动。活动封面/投放插画可直接上传（PNG/JPEG，≤2MiB）；C 端首页只加载后台素材 URL，新增活动/换图不需要小程序发版，新增卡片模板才需要发版。</p>
    <nav className="filters" aria-label="营销中心分区">
      <button type="button" onClick={() => setTab('campaigns')} disabled={tab === 'campaigns'}>活动</button>
      <button type="button" onClick={() => setTab('placements')} disabled={tab === 'placements'}>投放位</button>
      <button type="button" onClick={() => setTab('home')} disabled={tab === 'home'}>首页内容位</button>
      <button type="button" onClick={() => setTab('enrollments')} disabled={tab === 'enrollments'}>商家报名</button>
      <button type="button" onClick={() => setTab('coupons')} disabled={tab === 'coupons'}>券活动</button>
      {notice && <output role="status">{notice}</output>}
      {error && <span role="alert">{error}</span>}
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
            serviceCategoryCodes: csv(String(form.get('serviceCategoryCodes') ?? '')),
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
          <label>活动名称（2-100）<input name="name" minLength={2} maxLength={100} required /></label>
          <label>标题（1-100）<input name="title" maxLength={100} required /></label>
          <label>副标题<input name="subtitle" maxLength={200} /></label>
          <label>活动类型<select name="campaignType"><option value="DISPLAY">展示活动</option><option value="COUPON">券活动</option><option value="MERCHANT_ENROLL">商家招商</option></select></label>
          <label>参与方式<select name="participationMode"><option value="PLATFORM_SELECTED">平台选择门店</option><option value="MERCHANT_ENROLL">商家报名</option></select></label>
          <label>标签编码<input name="tagCode" placeholder="new-user / beauty / boarding" maxLength={32} /></label>
          <label>标签名称<input name="tagName" placeholder="新客专享 / 美容优惠" maxLength={32} /></label>
          <AssetPicker name="coverUrl" label="活动封面" client={client} canManage={canManage} onFailure={caught => authOrError(caught, '素材上传失败')} />
          <label>城市编码（逗号分隔，空=全部）<input name="cityCodes" placeholder="shanghai,chengdu" /></label>
          <label>服务分类（逗号分隔，空=全部）<input name="serviceCategoryCodes" placeholder="BEAUTY,BOARDING" /></label>
          <label>开始时间<input name="startAt" type="datetime-local" required /></label>
          <label>结束时间<input name="endAt" type="datetime-local" required /></label>
          <label>报名开始<input name="enrollStartAt" type="datetime-local" /></label>
          <label>报名结束<input name="enrollEndAt" type="datetime-local" /></label>
          <label>报名名额<input name="enrollmentQuota" type="number" min={1} max={10000} /></label>
          <label>关联券活动 ID<input name="couponCampaignId" /></label>
          <label>排序权重<input name="sortWeight" type="number" min={0} max={9999} defaultValue={0} /></label>
          <button type="submit" disabled={busy}>创建草稿</button>
        </form>
      </section>}
      {campaigns === undefined ? <p role="status">加载中</p> : <table>
        <thead><tr><th>ID</th><th>名称</th><th>类型</th><th>状态</th><th>时间</th><th>门店</th><th>版本</th><th>操作</th></tr></thead>
        <tbody>{campaigns.map(campaign => <tr key={campaign.campaignId}>
          <td>{campaign.campaignId}</td><td>{campaign.name}</td>
          <td>{campaign.campaignType} / {campaign.participationMode}</td>
          <td>{STATUS_TEXT[campaign.status] ?? campaign.status}</td>
          <td>{campaign.startAt.slice(0, 10)} ~ {campaign.endAt.slice(0, 10)}</td>
          <td>{campaign.storeCount}</td><td>{campaign.version}</td>
          <td>
            <button type="button" onClick={() => void openDetail(campaign)}>详情</button>
            {canPublish && (campaign.ownerType === 'MERCHANT'
              ? campaign.status === 'PENDING_REVIEW'
              : ['DRAFT', 'PENDING_REVIEW', 'SCHEDULED', 'PAUSED'].includes(campaign.status)) &&
              <button type="button" disabled={busy} onClick={() => void run(() => client.campaignAction(campaign.campaignId, 'publish', campaign.version), '活动已发布')}>发布</button>}
            {canPublish && campaign.ownerType === 'MERCHANT' && campaign.status === 'PENDING_REVIEW' &&
              <button type="button" disabled={busy} onClick={() => {
                const reason = window.prompt('驳回原因');
                if (reason) void run(() => client.rejectCampaign(campaign.campaignId, reason, campaign.version), '活动已驳回');
              }}>驳回</button>}
            {canPublish && campaign.status === 'ONLINE' &&
              <button type="button" disabled={busy} onClick={() => void run(() => client.campaignAction(campaign.campaignId, 'pause', campaign.version), '活动已暂停')}>暂停</button>}
            {canPublish && ['ONLINE', 'PAUSED', 'SCHEDULED'].includes(campaign.status) &&
              <button type="button" disabled={busy} onClick={() => void run(() => client.campaignAction(campaign.campaignId, 'end', campaign.version), '活动已结束')}>结束</button>}
            {canPublish && ['DRAFT', 'PENDING_REVIEW', 'SCHEDULED', 'PAUSED'].includes(campaign.status) &&
              <button type="button" disabled={busy} onClick={() => void run(() => client.campaignAction(campaign.campaignId, 'cancel', campaign.version), '活动已取消')}>取消</button>}
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
              serviceCategoryCodes: csv(String(form.get('serviceCategoryCodes') ?? '')),
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
            <label>名称<input name="name" defaultValue={detail.campaign.name} minLength={2} maxLength={100} required /></label>
            <label>标题<input name="title" defaultValue={detail.campaign.title} maxLength={100} required /></label>
            <label>副标题<input name="subtitle" defaultValue={detail.campaign.subtitle ?? ''} maxLength={200} /></label>
            <label>类型<select name="campaignType" defaultValue={detail.campaign.campaignType}><option value="DISPLAY">展示活动</option><option value="COUPON">券活动</option><option value="MERCHANT_ENROLL">商家招商</option></select></label>
            <label>参与方式<select name="participationMode" defaultValue={detail.campaign.participationMode}><option value="PLATFORM_SELECTED">平台选择门店</option><option value="MERCHANT_ENROLL">商家报名</option></select></label>
            <label>标签编码<input name="tagCode" defaultValue={detail.campaign.tagCode ?? ''} maxLength={32} /></label>
            <label>标签名称<input name="tagName" defaultValue={detail.campaign.tagName ?? ''} maxLength={32} /></label>
            <AssetPicker name="coverUrl" label="活动封面" defaultValue={detail.campaign.coverUrl} client={client} canManage={canManage} onFailure={caught => authOrError(caught, '素材上传失败')} />
            <label>城市编码（逗号分隔，空=全部）<input name="cityCodes" defaultValue={detail.campaign.cityCodes.join(',')} /></label>
            <label>服务分类（逗号分隔，空=全部）<input name="serviceCategoryCodes" defaultValue={detail.campaign.serviceCategoryCodes.join(',')} /></label>
            <label>开始时间<input name="startAt" type="datetime-local" defaultValue={localInput(detail.campaign.startAt)} required /></label>
            <label>结束时间<input name="endAt" type="datetime-local" defaultValue={localInput(detail.campaign.endAt)} required /></label>
            <label>报名开始<input name="enrollStartAt" type="datetime-local" defaultValue={localInput(detail.campaign.enrollStartAt)} /></label>
            <label>报名结束<input name="enrollEndAt" type="datetime-local" defaultValue={localInput(detail.campaign.enrollEndAt)} /></label>
            <label>报名名额<input name="enrollmentQuota" type="number" min={1} max={10000} defaultValue={detail.campaign.enrollmentQuota ?? ''} /></label>
            <label>关联券活动 ID<input name="couponCampaignId" defaultValue={detail.campaign.couponCampaignId ?? ''} /></label>
            <label>排序权重<input name="sortWeight" type="number" min={0} max={9999} defaultValue={detail.campaign.sortWeight} /></label>
            <button type="submit" disabled={busy}>保存基础信息</button>
          </form>
        </details>}
        <h4>参与门店</h4>
        <table><thead><tr><th>门店 ID</th><th>来源</th><th>优惠角标</th><th>优惠标题</th><th>优惠说明</th></tr></thead>
          <tbody>{detail.stores.map(store => <tr key={store.storeId}>
            <td>{store.storeId}</td><td>{store.source}</td><td>{store.offerTag ?? '--'}</td>
            <td>{store.offerTitle ?? '--'}</td><td>{store.offerCopy ?? '--'}</td>
          </tr>)}{detail.stores.length === 0 && <tr><td colSpan={5}>暂无参与门店</td></tr>}</tbody></table>
        {canManage && <>
          <label>门店搜索<input value={storeKeyword} onChange={event => setStoreKeyword(event.target.value)} placeholder="门店名称关键词" /></label>
          <button type="button" onClick={() => void searchStores()}>搜索门店</button>
          {stores.length > 0 && <table><thead><tr><th>门店</th><th>城市</th><th>地址</th></tr></thead>
            <tbody>{stores.map(store => <tr key={store.storeId}><td>{store.storeName}</td><td>{store.cityCode}</td><td>{store.address}</td></tr>)}</tbody></table>}
          <p className="hint">当前“保存门店”会把搜索结果中的门店全部写入本活动，并应用下方统一的优惠文案。</p>
          <label>优惠角标<input value={offerTag} onChange={event => setOfferTag(event.target.value)} maxLength={32} placeholder="5折 / 立减" /></label>
          <label>优惠标题<input value={offerTitle} onChange={event => setOfferTitle(event.target.value)} maxLength={100} /></label>
          <label>优惠说明<input value={offerCopy} onChange={event => setOfferCopy(event.target.value)} maxLength={100} /></label>
          <button type="button" disabled={busy} onClick={() => void run(() => client.replaceStores(detail.campaign.campaignId,
            detail.stores.map(store => ({ ...store, offerTag: offerTag || null, offerTitle: offerTitle || null, offerCopy: offerCopy || null })), detail.campaign.version), '参与门店已保存')}>保存现有门店文案</button>
          <button type="button" disabled={busy || stores.length === 0} onClick={() => saveStores()}>用搜索结果替换门店</button>
        </>}
        <h4>活动页 Schema</h4>
        <textarea value={schema} onChange={event => setSchema(event.target.value)} rows={12} spellCheck={false} />
        {canManage && <button type="button" disabled={busy} onClick={() => void run(() => client.savePage(detail.campaign.campaignId, schema, detail.campaign.version), 'Schema 草稿已保存')}>保存 Schema 草稿</button>}
        {canPublish && (detail.campaign.ownerType === 'MERCHANT'
          ? detail.campaign.status === 'PENDING_REVIEW'
          : ['DRAFT', 'PENDING_REVIEW', 'SCHEDULED', 'PAUSED'].includes(detail.campaign.status)) &&
          <button type="button" disabled={busy} onClick={() => void run(() => client.campaignAction(detail.campaign.campaignId, 'publish', detail.campaign.version), '活动已发布')}>发布（发布当前 Schema 与门店）</button>}
        {canPublish && detail.campaign.ownerType === 'MERCHANT' && detail.campaign.status === 'PENDING_REVIEW' &&
          <button type="button" disabled={busy} onClick={() => {
            const reason = window.prompt('驳回原因');
            if (reason) void run(() => client.rejectCampaign(detail.campaign.campaignId, reason, detail.campaign.version), '活动已驳回');
          }}>驳回商家活动</button>}
        {detail.pageVersions.length > 0 && <div>
          <h4>页面发布版本</h4>
          <ul>{detail.pageVersions.map(version => <li key={version.version}>
            v{version.version} · {version.publishedAt.slice(0, 19).replace('T', ' ')}
            {canPublish && version.version !== detail.campaign.pageVersion && <button type="button" disabled={busy}
              onClick={() => void run(() => client.rollbackPage(detail.campaign.campaignId, version.version, detail.campaign.version), `已回滚到 v${version.version}`)}>回滚到此版本</button>}
          </li>)}</ul>
        </div>}
      </section>}
    </>}

    {tab === 'placements' && <>
      {canManage && <section className="detail-card">
        <h3>新建投放位</h3>
        <form onSubmit={event => {
          event.preventDefault();
          const form = new FormData(event.currentTarget);
          void run(() => client.createPlacement({
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
          }), '投放位已创建');
        }}>
          <label>位置<select name="positionCode">{POSITIONS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
          <label>活动 ID<input name="campaignId" required /></label>
          <label>城市编码（空=全部）<input name="cityCode" /></label>
          <label>标题覆盖<input name="titleOverride" maxLength={100} /></label>
          <label>副标题覆盖<input name="subtitleOverride" maxLength={200} /></label>
          <AssetPicker name="imageUrlOverride" label="投放插画" client={client} canManage={canManage} onFailure={caught => authOrError(caught, '素材上传失败')} />
          <label>卡片模板<select name="templateCode" defaultValue=""><option value="">通用模板</option><option value="new_user">新客活动卡</option><option value="beauty">美容活动卡</option></select></label>
          <label>排序<input name="sortWeight" type="number" min={0} max={9999} defaultValue={0} /></label>
          <label>开始时间<input name="startAt" type="datetime-local" required /></label>
          <label>结束时间<input name="endAt" type="datetime-local" required /></label>
          <button type="submit" disabled={busy}>创建投放位</button>
        </form>
      </section>}
      {placements === undefined ? <p role="status">加载中</p> : <table>
        <thead><tr><th>ID</th><th>位置</th><th>活动</th><th>模板</th><th>城市</th><th>时间</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>{placements.map(placement => <tr key={placement.placementId}>
          <td>{placement.placementId}</td><td>{placement.positionCode}</td>
          <td>{placement.campaignTitle}（{placement.campaignId}）</td><td>{placement.templateCode ?? '通用'}</td><td>{placement.cityCode ?? '全部'}</td>
          <td>{placement.startAt.slice(0, 16)} ~ {placement.endAt.slice(0, 16)}</td>
          <td>{STATUS_TEXT[placement.status] ?? placement.status}</td>
          <td>{canManage && <>
            {placement.status !== 'ENABLED' && <button type="button" disabled={busy} onClick={() => void run(() => client.placementAction(placement.placementId, 'enable', placement.version), '投放位已启用')}>启用</button>}
            {placement.status === 'ENABLED' && <button type="button" disabled={busy} onClick={() => void run(() => client.placementAction(placement.placementId, 'disable', placement.version), '投放位已停用')}>停用</button>}
          </>}</td>
        </tr>)}{placements.length === 0 && <tr><td colSpan={8}>无投放位</td></tr>}</tbody>
      </table>}
    </>}

    {tab === 'home' && <>
      <p className="hint">首页「热门服务 / 宠友圈热门 / 植物&amp;异宠」由运营手动配置；图片走后台上传，改文案/换图/排序/上下线不需要小程序发版。预留赞助商家/门店字段，V1 不做竞价结算。</p>
      {canManage && <section className="detail-card">
        <h3>新建首页内容位</h3>
        <HomeBlockForm client={client} canManage={canManage} busy={busy}
          onSubmit={input => void run(() => client.createHomeBlock(input), '首页内容位草稿已创建')}
          onFailure={caught => authOrError(caught, '素材上传失败')} />
      </section>}
      {homeBlocks === undefined ? <p role="status">加载中</p> : <table>
        <thead><tr><th>ID</th><th>位置/模板</th><th>标题</th><th>目标</th><th>窗口</th><th>状态</th><th>版本</th><th>操作</th></tr></thead>
        <tbody>{homeBlocks.map(block => <tr key={block.blockId}>
          <td>{block.blockId}</td>
          <td>{block.positionCode}<br />{block.templateCode}</td>
          <td>{block.title}<br /><small>{block.tagName ?? ''}</small></td>
          <td>{block.targetType}<br /><small>{block.targetValue ?? ''}</small></td>
          <td>{block.startAt.slice(0, 10)} ~ {block.endAt.slice(0, 10)}</td>
          <td>{STATUS_TEXT[block.status] ?? block.status}</td>
          <td>{block.version}</td>
          <td>
            {canManage && <details>
              <summary>编辑</summary>
              <HomeBlockForm block={block} client={client} canManage={canManage} busy={busy}
                onSubmit={input => void run(() => client.updateHomeBlock(block.blockId, input, block.version), '首页内容位已保存')}
                onFailure={caught => authOrError(caught, '素材上传失败')} />
            </details>}
            {canManage && block.status !== 'ENABLED' && <button type="button" disabled={busy}
              onClick={() => void run(() => client.homeBlockAction(block.blockId, 'enable', block.version), '首页内容位已启用')}>启用</button>}
            {canManage && block.status === 'ENABLED' && <button type="button" disabled={busy}
              onClick={() => void run(() => client.homeBlockAction(block.blockId, 'disable', block.version), '首页内容位已停用')}>停用</button>}
          </td>
        </tr>)}{homeBlocks.length === 0 && <tr><td colSpan={8}>暂无内容位</td></tr>}</tbody>
      </table>}
    </>}

    {tab === 'enrollments' && <>
      {enrollments === undefined ? <p role="status">加载中</p> : <table>
        <thead><tr><th>ID</th><th>活动</th><th>门店</th><th>优惠</th><th>状态</th><th>提交时间</th><th>操作</th></tr></thead>
        <tbody>{enrollments.map(enrollment => <tr key={enrollment.enrollmentId}>
          <td>{enrollment.enrollmentId}</td><td>{enrollment.campaignTitle}</td>
          <td>{enrollment.storeName ?? enrollment.storeId}</td>
          <td>{enrollment.offerTitle ?? '--'}<br />{enrollment.offerCopy ?? ''}</td>
          <td>{STATUS_TEXT[enrollment.status] ?? enrollment.status}{enrollment.rejectReason ? `（${enrollment.rejectReason}）` : ''}</td>
          <td>{enrollment.submittedAt.slice(0, 16)}</td>
          <td>{canReview && ['SUBMITTED', 'UNDER_REVIEW'].includes(enrollment.status) && <>
            <button type="button" disabled={busy} onClick={() => void run(() => client.reviewEnrollment(enrollment.enrollmentId, 'APPROVE', undefined, enrollment.version), '报名已通过')}>通过</button>
            <button type="button" disabled={busy} onClick={() => {
              const reason = window.prompt('驳回原因（1-500 字）')?.trim();
              if (reason) void run(() => client.reviewEnrollment(enrollment.enrollmentId, 'REJECT', reason, enrollment.version), '报名已驳回');
            }}>驳回</button>
          </>}</td>
        </tr>)}{enrollments.length === 0 && <tr><td colSpan={7}>无报名</td></tr>}</tbody>
      </table>}
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
          <label>活动名称<input name="name" minLength={2} maxLength={50} required /></label>
          <label>券模板<select name="templateId" required>{templates.map(template => <option key={template.templateId} value={template.templateId}>{template.name}（{template.templateId} · ¥{template.amountOff}{template.thresholdAmount ? ` 满${template.thresholdAmount}` : ' 无门槛'}）</option>)}</select></label>
          {templates.length === 0 && <p className="hint">暂无启用中的券模板，请先在「券模板」页启用模板。</p>}
          <label>发放对象<select name="audienceType"><option value="ALL">全部用户</option><option value="NEW_USER">新用户</option></select></label>
          <label>总发放上限<input name="totalLimit" type="number" min={1} max={1000000} required /></label>
          <label>预算上限（元）<input name="budgetLimit" type="number" min={0.01} step={0.01} required /></label>
          <label>开始时间<input name="startAt" type="datetime-local" required /></label>
          <label>结束时间<input name="endAt" type="datetime-local" required /></label>
          <button type="submit" disabled={busy}>创建券活动</button>
        </form>
      </section>}
      {coupons === undefined ? <p role="status">加载中</p> : <table>
        <thead><tr><th>ID</th><th>名称</th><th>模板</th><th>面额/门槛</th><th>对象</th><th>已发/上限</th><th>预算</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>{coupons.map(campaign => <tr key={campaign.campaignId}>
          <td>{campaign.campaignId}</td><td>{campaign.name}</td>
          <td>{campaign.templateName}（{campaign.templateId}）</td>
          <td>{campaign.amountOff ?? '--'} / {campaign.thresholdAmount ?? '无门槛'}</td>
          <td>{campaign.audienceType === 'NEW_USER' ? '新用户' : '全部用户'}</td>
          <td>{campaign.issuedCount} / {campaign.totalLimit}（¥{campaign.issuedAmount}）</td>
          <td>¥{campaign.budgetLimit}</td>
          <td>{STATUS_TEXT[campaign.status] ?? campaign.status}</td>
          <td>{canCouponPublish && <>
            {campaign.status === 'DRAFT' && <button type="button" disabled={busy} onClick={() => void run(() => client.couponCampaignAction(campaign.campaignId, 'publish', campaign.version), '券活动已发布')}>发布</button>}
            {campaign.status === 'ACTIVE' && <button type="button" disabled={busy} onClick={() => void run(() => client.couponCampaignAction(campaign.campaignId, 'pause', campaign.version), '券活动已暂停')}>暂停</button>}
            {['ACTIVE', 'PAUSED', 'SCHEDULED'].includes(campaign.status) && <button type="button" disabled={busy} onClick={() => void run(() => client.couponCampaignAction(campaign.campaignId, 'end', campaign.version), '券活动已结束')}>结束</button>}
          </>}
          <button type="button" onClick={() => void client.listCouponClaims(campaign.campaignId).then(page => setClaims(page.items)).catch(caught => authOrError(caught, '读取领取记录失败'))}>领取记录</button>
          </td>
        </tr>)}{coupons.length === 0 && <tr><td colSpan={9}>无券活动</td></tr>}</tbody>
      </table>}
      {claims && <section className="detail-card"><h3>领取记录</h3>
        <table><thead><tr><th>领取 ID</th><th>用户</th><th>券实例</th><th>时间</th></tr></thead>
          <tbody>{claims.map(claim => <tr key={claim.claimId}><td>{claim.claimId}</td><td>{claim.userId}</td><td>{claim.couponInstanceId}</td><td>{claim.claimedAt.slice(0, 19)}</td></tr>)}</tbody></table>
      </section>}
    </>}
  </main>;
}
