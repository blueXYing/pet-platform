import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Button, ConfigProvider, Drawer, Empty, Input, Popconfirm, Progress, Select, Space } from 'antd';
import type { TableColumnsType } from 'antd';
import type { AdminTemplateSummary, CouponAdminClient } from '../../api/couponAdmin';
import type { CouponCampaign, CouponClaim } from '../../api/marketingAdmin';
import { DataTable } from '../../ui/DataTable';
import { FactList, StatusTag } from '../../ui/bits';
import {
  AUDIENCE_TYPE_TEXT, DrawerHead, Field, fmtDT, FormDrawer, isUnauthenticated, LoadFailed, localInput, PAGE_SIZE,
  STATUS_TEXT, TimeCell, useCloseOnLeave, usePagedList, windowIsValid, type TabProps,
} from './shared';

type CouponFilters = { status?: string };
const LINK_STYLE = { padding: 0, height: 'auto' } as const;
const STATUS_OPTIONS = [
  { value: '', label: '全部状态' }, { value: 'DRAFT', label: '草稿' }, { value: 'SCHEDULED', label: '待生效' },
  { value: 'ACTIVE', label: '进行中' }, { value: 'PAUSED', label: '已暂停' }, { value: 'ENDED', label: '已结束' },
];
const templateCondition = (template: { amountOff: string | null; thresholdAmount: string | null }) =>
  `¥${template.amountOff ?? '--'}${template.thresholdAmount ? ` 满${template.thresholdAmount}可用` : ' 无门槛'}`;

type CouponFormState = { templateId: string; totalLimit: string; budgetLimit: string; startAt: string; endAt: string };
const EMPTY_FORM: CouponFormState = { templateId: '', totalLimit: '', budgetLimit: '', startAt: '', endAt: '' };

/**
 * 券活动新建 / 编辑（仅草稿可编辑）共用表单。约束逐条对应后端 AdminCouponCampaignService：
 * 名称 2-50 字且唯一；模板须启用；窗口须落在模板有效期内；预算至少够发一张；V1 每人限领 1 张。
 * 这里提前用中文说清楚，避免提交后只得到一句「填写内容不符合要求」。
 */
function CouponForm({ idPrefix, campaign, templates, templatesState, onRetryTemplates, busy, onInvalid, onSubmit, onCancel }: {
  idPrefix: string; campaign?: CouponCampaign; templates: AdminTemplateSummary[];
  templatesState: 'loading' | 'ready' | 'failed'; onRetryTemplates: () => void;
  busy: boolean; onInvalid: (message: string) => void;
  onSubmit: (input: Record<string, unknown>) => void; onCancel?: () => void;
}) {
  const [form, setForm] = useState<CouponFormState>(campaign ? {
    templateId: campaign.templateId,
    totalLimit: String(campaign.totalLimit),
    budgetLimit: String(campaign.budgetLimit),
    startAt: localInput(campaign.startAt),
    endAt: localInput(campaign.endAt),
  } : EMPTY_FORM);
  const id = (suffix: string) => `${idPrefix}-${suffix}`;
  const patch = (next: Partial<CouponFormState>) => setForm(current => ({ ...current, ...next }));
  const template = templates.find(item => item.templateId === form.templateId);
  const templateMissing = !!campaign && templatesState === 'ready' && !template;
  const amount = Number(template?.amountOff);
  const budget = Number(form.budgetLimit);
  const limit = Number(form.totalLimit);
  const budgetShort = Number.isFinite(amount) && amount > 0 && Number.isFinite(budget) && budget > 0 && budget < amount;
  const outsideValidity = !!template && windowIsValid(form.startAt, form.endAt)
    && (Date.parse(new Date(form.startAt).toISOString()) < Date.parse(template.validStartAt)
      || Date.parse(new Date(form.endAt).toISOString()) > Date.parse(template.validEndAt));
  const byBudget = Number.isFinite(amount) && amount > 0 && Number.isFinite(budget) && budget > 0
    ? Math.floor(budget / amount + 1e-9) : NaN;
  const estimated = Number.isFinite(byBudget) && Number.isFinite(limit) && limit > 0 ? Math.min(byBudget, limit) : NaN;

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const name = String(data.get('name') ?? '').trim();
    if (name.length < 2 || name.length > 50) { onInvalid('活动名称须为 2-50 个字'); return; }
    if (!form.templateId) { onInvalid('请选择券模板'); return; }
    if (!Number.isInteger(limit) || limit < 1 || limit > 1_000_000) { onInvalid('总发放上限须为 1 到 1,000,000 之间的整数'); return; }
    // 金额最多两位小数：留 1e-6 容差，避免 0.29 × 100 = 28.999… 这类浮点误差误伤合法输入。
    if (!Number.isFinite(budget) || budget <= 0 || Math.abs(Math.round(budget * 100) - budget * 100) > 1e-6) {
      onInvalid('预算上限须为大于 0 的金额，最多两位小数'); return;
    }
    if (!windowIsValid(form.startAt, form.endAt)) { onInvalid('结束时间必须晚于开始时间'); return; }
    if (template && outsideValidity) {
      onInvalid(`券活动时间必须落在券模板的有效期内（${fmtDT(template.validStartAt)} 至 ${fmtDT(template.validEndAt)}）`); return;
    }
    if (template && budgetShort) { onInvalid(`预算上限不足以发放一张券（该模板面额 ¥${template.amountOff}）`); return; }
    onSubmit({
      name, templateId: form.templateId,
      audienceType: String(data.get('audienceType') ?? 'ALL'),
      perUserLimit: 1,
      totalLimit: limit,
      budgetLimit: budget,
      startAt: new Date(form.startAt).toISOString(),
      endAt: new Date(form.endAt).toISOString(),
    });
  };
  return <form className="mkt-form-body" onSubmit={submit}>
    <div className="mkt-form-fields">
    <fieldset>
      <legend>基本信息</legend>
      <div className="mkt-form-grid">
        <Field id={id('name')} label="活动名称（2-50 字，不可与已有券活动重名）" span>
          <Input id={id('name')} name="name" defaultValue={campaign?.name} minLength={2} maxLength={50} required />
        </Field>
        <Field id={id('template')} label="券模板" span hint="只能选择启用中的券模板；模板决定面额与使用门槛">
          <select id={id('template')} name="templateId" required value={form.templateId} onChange={event => patch({ templateId: event.target.value })}>
            <option value="" disabled>请选择券模板</option>
            {templates.map(item => <option key={item.templateId} value={item.templateId}>{item.name}（{templateCondition(item)}）</option>)}
            {templateMissing && <option value={campaign?.templateId}>{campaign?.templateName}（原模板，已不在启用列表）</option>}
          </select>
        </Field>
        {templatesState === 'loading' && <p className="hint mkt-span-2">券模板加载中…</p>}
        {templatesState === 'failed' && <p className="hint mkt-span-2">券模板读取失败，暂时无法新建券活动。<Button type="link" size="small" style={LINK_STYLE} onClick={onRetryTemplates}>重试</Button></p>}
        {templatesState === 'ready' && templates.length === 0 && <p className="hint mkt-span-2">暂无启用中的券模板，请先到「券模板」页启用模板。</p>}
        {templateMissing && <p className="mkt-warn mkt-span-2">该券活动绑定的模板已停用或不在有效期，后端不允许再保存，请知悉。</p>}
      </div>
    </fieldset>
    <fieldset>
      <legend>发放规则</legend>
      <div className="mkt-form-grid">
        <Field id={id('audience')} label="发放对象">
          <select id={id('audience')} name="audienceType" defaultValue={campaign?.audienceType ?? 'ALL'}>
            <option value="ALL">全部用户</option>
            <option value="NEW_USER">新用户</option>
          </select>
        </Field>
        <Field id={id('total')} label="总发放上限（张）"><Input id={id('total')} name="totalLimit" type="number" min={1} max={1000000} required
          value={form.totalLimit} onChange={event => patch({ totalLimit: event.target.value })} /></Field>
        <Field id={id('budget')} label="预算上限（元）"><Input id={id('budget')} name="budgetLimit" type="number" min={0.01} step={0.01} required
          value={form.budgetLimit} onChange={event => patch({ budgetLimit: event.target.value })} /></Field>
        <p className="hint">每个用户限领 1 张（V1 固定）。</p>
      </div>
      {template && <div className={`mkt-callout ${budgetShort ? 'is-warn' : ''}`}>
        <div>所选模板：{template.name} · {templateCondition(template)} · 有效期 {fmtDT(template.validStartAt)} 至 {fmtDT(template.validEndAt)}</div>
        {budgetShort
          ? <div>预算不足以发放一张券（面额 ¥{template.amountOff}），后端会拒绝创建。</div>
          : Number.isFinite(estimated) && <div>按面额估算：预算最多支撑 {byBudget} 张，与总发放上限取小后预计最多发放 {estimated} 张，最多支出 ¥{(estimated * amount).toFixed(2)}（实际以领取为准）。</div>}
        {outsideValidity && <div>活动时间超出了券模板的有效期，后端会拒绝保存。</div>}
      </div>}
    </fieldset>
    <fieldset>
      <legend>发放时间</legend>
      <div className="mkt-form-grid">
        <Field id={id('start')} label="开始时间"><input id={id('start')} name="startAt" type="datetime-local" required
          value={form.startAt} onChange={event => patch({ startAt: event.target.value })} /></Field>
        <Field id={id('end')} label="结束时间"><input id={id('end')} name="endAt" type="datetime-local" required
          value={form.endAt} onChange={event => patch({ endAt: event.target.value })} /></Field>
      </div>
      <p className="hint">开始时间在未来时，发布后先进入「待生效」，到达开始时间后开始发放。</p>
    </fieldset>
    </div>
    <div className="mkt-form-actions">
      <Button type="primary" htmlType="submit" disabled={busy}>{campaign ? '保存修改' : '创建券活动'}</Button>
      {campaign && onCancel && <Button disabled={busy} onClick={onCancel}>取消</Button>}
    </div>
  </form>;
}

export function CouponsTab({ client, couponClient, active, canManage, canPublish, busy, run, authOrError, onAuthLost }: TabProps & {
  couponClient: CouponAdminClient;
  canManage: boolean;
  canPublish: boolean;
}) {
  const list = usePagedList<CouponCampaign, CouponFilters>(
    (filters, page) => client.listCouponCampaigns(filters.status, page, PAGE_SIZE), authOrError, '读取券活动失败');
  const claims = usePagedList<CouponClaim, { campaignId?: string }>(
    (filters, page) => client.listCouponClaims(filters.campaignId ?? '', page, PAGE_SIZE), authOrError, '读取领取记录失败');
  const [status, setStatus] = useState('');
  const [templates, setTemplates] = useState<AdminTemplateSummary[]>([]);
  const [templatesState, setTemplatesState] = useState<'loading' | 'ready' | 'failed'>('loading');
  const templatesLoaded = useRef(false);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<CouponCampaign>();
  const [claimsOf, setClaimsOf] = useState<CouponCampaign>();

  useEffect(() => { if (active) void list.ensure({}); }, [active, list.ensure]);
  useEffect(() => {
    if (!active || templatesLoaded.current) return;
    templatesLoaded.current = true;
    void loadTemplates();
  }, [active]); // eslint-disable-line react-hooks/exhaustive-deps
  useCloseOnLeave(active, () => { setCreating(false); setEditing(undefined); setClaimsOf(undefined); });

  async function loadTemplates() {
    setTemplatesState('loading');
    try {
      setTemplates((await couponClient.listTemplates({ status: 'ACTIVE' })).items);
      setTemplatesState('ready');
    } catch (caught) {
      if (isUnauthenticated(caught)) { onAuthLost(); return; }
      setTemplatesState('failed');
      authOrError(caught, '读取券模板失败');
    }
  }
  function apply(nextStatus: string) {
    setStatus(nextStatus);
    void list.load({ status: nextStatus || undefined }, 1);
  }
  const onInvalid = (message: string) => authOrError(new Error(message), '请检查表单');
  const act = (campaign: CouponCampaign, action: 'publish' | 'pause' | 'end') => void run(
    () => client.couponCampaignAction(campaign.campaignId, action, campaign.version),
    { publish: '券活动已发布', pause: '券活动已暂停', end: '券活动已结束' }[action], list.reload);
  function openClaims(campaign: CouponCampaign) {
    setClaimsOf(campaign);
    void claims.load({ campaignId: campaign.campaignId }, 1);
  }
  const save = (campaign: CouponCampaign, input: Record<string, unknown>) => void run(
    () => client.updateCouponCampaign(campaign.campaignId, input, campaign.version), '券活动已保存',
    async () => { setEditing(undefined); await list.reload(); });

  // 列宽预算合计 ≤ 1000px（见活动分区），操作列固定在右侧。
  const columns: TableColumnsType<CouponCampaign> = [
    { title: '券活动', key: 'campaign', width: 210, render: (_, campaign) => <div className="mkt-entity-text">
      <div className="mkt-entity-name" title={campaign.name}>{campaign.name}</div>
      <div className="mkt-entity-sub" title={`${campaign.templateName} · ${templateCondition(campaign)}`}>{campaign.templateName} · {templateCondition(campaign)}</div>
      <div className="mkt-entity-id">ID {campaign.campaignId}</div>
    </div> },
    { title: '发放对象', width: 100, render: (_, campaign) => <div className="mkt-entity-text">
      <div>{AUDIENCE_TYPE_TEXT[campaign.audienceType] ?? campaign.audienceType}</div>
      <div className="mkt-entity-sub">每人限领 {campaign.perUserLimit} 张</div>
    </div> },
    { title: '发放进度', width: 200, render: (_, campaign) => <div className="mkt-entity-text">
      <Progress percent={campaign.totalLimit > 0 ? Math.min(100, Math.round(campaign.issuedCount / campaign.totalLimit * 100)) : 0}
        size="small" status={campaign.issuedCount >= campaign.totalLimit ? 'success' : 'normal'} />
      <div className="mkt-entity-sub">已发 {campaign.issuedCount} / 上限 {campaign.totalLimit} 张</div>
      <div className="mkt-entity-sub" title={`已发金额 ¥${campaign.issuedAmount}，预算 ¥${campaign.budgetLimit}`}>已发 ¥{campaign.issuedAmount} / 预算 ¥{campaign.budgetLimit}</div>
    </div> },
    { title: '发放时间', width: 140, render: (_, campaign) => <TimeCell startAt={campaign.startAt} endAt={campaign.endAt} /> },
    { title: '状态', width: 90, render: (_, campaign) => <StatusTag label={STATUS_TEXT[campaign.status] ?? campaign.status} status={campaign.status} /> },
    { title: '操作', key: 'actions', width: 250, fixed: 'right', render: (_, campaign) => <Space size={12} wrap>
      {canManage && campaign.status === 'DRAFT' && <Button type="link" size="small" style={LINK_STYLE} disabled={busy}
        onClick={() => { setCreating(false); setEditing(campaign); }}>编辑</Button>}
      {canPublish && campaign.status === 'DRAFT' && <Popconfirm title={Date.parse(campaign.startAt) > Date.now()
        ? '发布后进入「待生效」，到达开始时间后开始发放。确认发布？' : '发布后开始发放，用户可以领取。确认发布？'}
        okText="确认发布" cancelText="再想想" onConfirm={() => act(campaign, 'publish')}>
        <Button type="primary" size="small" disabled={busy}>发布</Button>
      </Popconfirm>}
      {canPublish && campaign.status === 'ACTIVE' && <Popconfirm title="暂停后用户不能再领取。V1 没有恢复动作，暂停后只能结束。确认暂停？"
        okText="确认暂停" cancelText="再想想" okButtonProps={{ danger: true }} onConfirm={() => act(campaign, 'pause')}>
        <Button type="link" size="small" style={LINK_STYLE} disabled={busy}>暂停</Button>
      </Popconfirm>}
      {canPublish && ['ACTIVE', 'PAUSED', 'SCHEDULED'].includes(campaign.status) && <Popconfirm
        title="结束后该券活动不可恢复。确认结束？" okText="确认结束" cancelText="再想想" okButtonProps={{ danger: true }}
        onConfirm={() => act(campaign, 'end')}>
        <Button type="link" size="small" danger style={LINK_STYLE} disabled={busy}>结束</Button>
      </Popconfirm>}
      <Button type="link" size="small" style={LINK_STYLE} onClick={() => openClaims(campaign)}>领取记录</Button>
    </Space> },
  ];
  const claimColumns: TableColumnsType<CouponClaim> = [
    { title: '领取 ID', dataIndex: 'claimId', width: 170 },
    { title: '用户 ID', dataIndex: 'userId', width: 170 },
    { title: '券实例 ID', dataIndex: 'couponInstanceId', width: 170 },
    { title: '领取时间', width: 150, render: (_, claim) => fmtDT(claim.claimedAt) },
  ];

  const create = (input: Record<string, unknown>) => void run(() => client.createCouponCampaign(input), '券活动已创建（草稿），确认无误后在列表点「发布」',
    async () => { setCreating(false); await list.load({}, 1); setStatus(''); });

  return <>
    <p className="hint">券活动把一张券模板发给用户：先创建为草稿，核对发放规则后再发布。草稿可以编辑；发布、暂停、结束会直接影响用户能否领取，操作前请确认。</p>
    <div className="filter-bar" aria-label="券活动筛选">
      <Field label="状态"><Select aria-label="状态" style={{ width: 130 }} value={status} options={STATUS_OPTIONS} onChange={apply} /></Field>
      <Button disabled={busy || list.loading} onClick={() => void list.reload()}>刷新</Button>
      {canManage && <Button type="primary" onClick={() => { setEditing(undefined); setCreating(true); }}>新建券活动</Button>}
    </div>

    {list.failed && !list.rows ? <LoadFailed onRetry={() => void list.reload()} />
      : <ConfigProvider renderEmpty={() => <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={status ? '没有符合当前筛选条件的券活动' : '暂无券活动'} />}>
        <DataTable tableLayout="fixed" columns={columns} rows={list.rows} rowKey={campaign => campaign.campaignId}
          loading={list.loading} page={list.page} pageSize={PAGE_SIZE} total={list.total}
          onPageChange={page => void list.load({ status: status || undefined }, page)} />
      </ConfigProvider>}

    <FormDrawer open={creating} onClose={() => setCreating(false)} title="新建券活动" label="新建券活动"
      description="创建后为草稿，不会发放任何券；在列表点「发布」后才开始按规则发放。">
      <CouponForm idPrefix="coupon-new" templates={templates} templatesState={templatesState}
        onRetryTemplates={() => void loadTemplates()} busy={busy} onInvalid={onInvalid} onSubmit={create} />
    </FormDrawer>

    <FormDrawer open={!!editing} onClose={() => setEditing(undefined)} title={`编辑券活动：${editing?.name ?? ''}`}
      label="编辑券活动" description="只有草稿可以编辑；发布后关键字段不可修改。">
      {editing && <CouponForm idPrefix="coupon-edit" campaign={editing} templates={templates} templatesState={templatesState}
        onRetryTemplates={() => void loadTemplates()} busy={busy} onInvalid={onInvalid}
        onSubmit={input => save(editing, input)} onCancel={() => setEditing(undefined)} />}
    </FormDrawer>

    <Drawer rootClassName="mkt-drawer" open={!!claimsOf} onClose={() => setClaimsOf(undefined)} width={760} destroyOnHidden closable={false}>
      <DrawerHead title={claimsOf ? `领取记录：${claimsOf.name}` : '领取记录'} onClose={() => setClaimsOf(undefined)} />
      {claimsOf && <>
        <FactList column={3} items={[
          { label: '已发放', value: `${claimsOf.issuedCount} 张` },
          { label: '发放上限', value: `${claimsOf.totalLimit} 张` },
          { label: '已发金额', value: `¥${claimsOf.issuedAmount}` },
        ]} />
        {claims.failed && !claims.rows ? <LoadFailed onRetry={() => void claims.reload()} />
          : <ConfigProvider renderEmpty={() => <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有用户领取" />}>
            <DataTable tableLayout="fixed" columns={claimColumns} rows={claims.rows} rowKey={claim => claim.claimId} loading={claims.loading}
              page={claims.page} pageSize={PAGE_SIZE} total={claims.total}
              onPageChange={page => void claims.load({ campaignId: claimsOf.campaignId }, page)} />
          </ConfigProvider>}
      </>}
    </Drawer>
  </>;
}
