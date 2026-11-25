import { useEffect, useState, type FormEvent } from 'react';
import { Button, ConfigProvider, Empty, Input, Popconfirm, Select, Space, Tag } from 'antd';
import type { TableColumnsType } from 'antd';
import { PLACEMENT_POSITIONS, type Campaign, type MarketingAdminClient, type Placement } from '../../api/marketingAdmin';
import { DataTable } from '../../ui/DataTable';
import { StatusTag } from '../../ui/bits';
import {
  AssetPicker, campaignPickerOptions, Field, fmtDT, FormDrawer, IdPicker, iso, LoadFailed, localInput,
  PAGE_SIZE, PLACEMENT_TEMPLATE_TEXT, POSITION_TEXT, STATUS_TEXT, TimeCell, useCampaignDirectory,
  useCloseOnLeave, usePagedList, visibilityOf, VisibilityTag, windowIsValid, type FiltersOf, type TabProps,
} from './shared';

type PlacementFilters = FiltersOf<MarketingAdminClient['listPlacements']>;
const LINK_STYLE = { padding: 0, height: 'auto' } as const;
const STATUS_OPTIONS = [
  { value: '', label: '全部状态' }, { value: 'ENABLED', label: '已启用' }, { value: 'DISABLED', label: '已停用' },
];
const POSITION_OPTIONS = [{ value: '', label: '全部位置' }, ...PLACEMENT_POSITIONS.map(([value, label]) => ({ value, label }))];

/** 投放位新建 / 编辑共用表单。活动用联想选择（提交的仍是活动 ID），并即时对照活动窗口。 */
function PlacementForm({ idPrefix, placement, client, campaigns, canManage, busy, onInvalid, onSubmit, onFailure, onCancel }: {
  idPrefix: string; placement?: Placement; client: MarketingAdminClient; campaigns: Campaign[] | undefined;
  canManage: boolean; busy: boolean; onInvalid: (message: string) => void;
  onSubmit: (input: Record<string, unknown>) => void;
  onFailure: (caught: unknown) => void; onCancel?: () => void;
}) {
  const [campaignId, setCampaignId] = useState(placement?.campaignId ?? '');
  const [startAt, setStartAt] = useState(localInput(placement?.startAt));
  const [endAt, setEndAt] = useState(localInput(placement?.endAt));
  const matched = campaigns?.find(item => item.campaignId === campaignId.trim());
  const id = (suffix: string) => `${idPrefix}-${suffix}`;
  // 后端要求投放窗口完全落在活动窗口内（否则 409 CAMPAIGN_LANDING_INVALID）。活动在目录里才做本地预检，
  // 目录里找不到的就交给后端裁决——本地预检只是把失败原因提前说清楚。
  const outside = !!matched && windowIsValid(startAt, endAt)
    && (Date.parse(iso(startAt)) < Date.parse(matched.startAt) || Date.parse(iso(endAt)) > Date.parse(matched.endAt));
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!campaignId.trim()) { onInvalid('请选择要投放的活动'); return; }
    if (!windowIsValid(startAt, endAt)) { onInvalid('结束时间必须晚于开始时间'); return; }
    if (outside && matched) {
      onInvalid(`投放窗口必须完全落在关联活动「${matched.name}」的时间窗口内（${fmtDT(matched.startAt)} 至 ${fmtDT(matched.endAt)}）`);
      return;
    }
    const form = new FormData(event.currentTarget);
    onSubmit({
      positionCode: String(form.get('positionCode') ?? 'HOME_ACTIVITY'),
      campaignId: campaignId.trim(),
      cityCode: String(form.get('cityCode') ?? '').trim() || undefined,
      titleOverride: String(form.get('titleOverride') ?? '').trim() || undefined,
      subtitleOverride: String(form.get('subtitleOverride') ?? '').trim() || undefined,
      imageUrlOverride: String(form.get('imageUrlOverride') ?? '').trim() || undefined,
      templateCode: String(form.get('templateCode') ?? '').trim() || undefined,
      sortWeight: Number(form.get('sortWeight') ?? 0),
      startAt: iso(startAt),
      endAt: iso(endAt),
    });
  };
  return <form className="mkt-form-body" onSubmit={submit}>
    <input type="hidden" name="campaignId" value={campaignId.trim()} />
    <div className="mkt-form-fields">
      <fieldset>
        <legend>投放位置与活动</legend>
        <p className="hint">热门服务顶部 Banner 全平台最多同时启用 1 个（启用时校验，超限返回 409）；创建后默认「已停用」，在列表点「启用」后才会对用户展示。</p>
        <div className="mkt-form-grid">
          <Field id={id('position')} label="位置">
            <select id={id('position')} name="positionCode" defaultValue={placement?.positionCode ?? 'HOME_ACTIVITY'}>
              {PLACEMENT_POSITIONS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          </Field>
          <Field id={id('city')} label="城市编码（空=全部）"><Input id={id('city')} name="cityCode" defaultValue={placement?.cityCode ?? ''} maxLength={32} /></Field>
          <Field id={id('campaign')} label="活动 ID" span
            hint={outside ? undefined : '必填。输入活动名称、标题或 ID 联想选择；也可直接粘贴完整 ID。投放窗口必须完全落在活动窗口内。'}>
            <IdPicker id={id('campaign')} value={campaignId} onChange={setCampaignId}
              options={campaignPickerOptions(campaigns)} placeholder="输入活动名称、标题或 ID" />
          </Field>
          {outside && matched && <p className="mkt-warn mkt-span-2">
            当前投放窗口超出了关联活动「{matched.name}」的活动窗口（{fmtDT(matched.startAt)} 至 {fmtDT(matched.endAt)}），保存会被拒绝。
          </p>}
        </div>
      </fieldset>
      <fieldset>
        <legend>投放内容（不填则沿用活动素材）</legend>
        <div className="mkt-form-grid">
          <Field id={id('title')} label="标题覆盖"><Input id={id('title')} name="titleOverride" defaultValue={placement?.titleOverride ?? ''} maxLength={100} /></Field>
          <Field id={id('subtitle')} label="副标题覆盖"><Input id={id('subtitle')} name="subtitleOverride" defaultValue={placement?.subtitleOverride ?? ''} maxLength={200} /></Field>
          <Field id={id('template')} label="卡片模板">
            <select id={id('template')} name="templateCode" defaultValue={placement?.templateCode ?? ''}>
              <option value="">通用模板</option>
              {/* full_card：整卡图模式——插画本身就是完整的卡片画面（运营演示数据用的就是它）。只加在投放位。 */}
              <option value="full_card">整卡图</option>
              <option value="new_user">新客活动卡</option>
              <option value="beauty">美容活动卡</option>
              {placement?.templateCode && !['full_card', 'new_user', 'beauty'].includes(placement.templateCode)
                && <option value={placement.templateCode}>{PLACEMENT_TEMPLATE_TEXT[placement.templateCode] ?? placement.templateCode}（{placement.templateCode}）</option>}
            </select>
          </Field>
          <AssetPicker name="imageUrlOverride" label="投放插画" uploadText="上传插画" defaultValue={placement?.imageUrlOverride}
            defaultDisplayUrl={placement?.imageUrlOverrideDisplayUrl}
            client={client} canManage={canManage} onFailure={onFailure} />
        </div>
      </fieldset>
      <fieldset>
        <legend>投放窗口与排序</legend>
        <div className="mkt-form-grid">
          <Field id={id('start')} label="开始时间"><input id={id('start')} name="startAt" type="datetime-local" value={startAt} required onChange={event => setStartAt(event.target.value)} /></Field>
          <Field id={id('end')} label="结束时间"><input id={id('end')} name="endAt" type="datetime-local" value={endAt} required onChange={event => setEndAt(event.target.value)} /></Field>
          <Field id={id('sort')} label="排序" hint="数值越小越靠前"><input id={id('sort')} name="sortWeight" type="number" min={0} max={9999} defaultValue={placement?.sortWeight ?? 0} /></Field>
        </div>
        {matched && <p className="hint">关联活动「{matched.name}」：{STATUS_TEXT[matched.status] ?? matched.status} · 活动窗口 {fmtDT(matched.startAt)} 至 {fmtDT(matched.endAt)}。
          {visibilityOf('campaign', matched.status, matched.startAt, matched.endAt) !== 'live' && '该活动当前没有在展示，投放位启用后也不会对用户可见。'}</p>}
      </fieldset>
    </div>
    <div className="mkt-form-actions">
      <Button type="primary" htmlType="submit" disabled={busy}>{placement ? '保存修改' : '创建投放位'}</Button>
      {placement && onCancel && <Button disabled={busy} onClick={onCancel}>取消</Button>}
    </div>
  </form>;
}

export function PlacementsTab({ client, active, canManage, busy, run, authOrError, onAuthLost }: TabProps & { canManage: boolean }) {
  const list = usePagedList<Placement, PlacementFilters>(
    (filters, page) => client.listPlacements(filters, page, PAGE_SIZE), authOrError, '读取投放位失败');
  const [filters, setFilters] = useState<PlacementFilters>({});
  const campaigns = useCampaignDirectory(client, active, onAuthLost);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Placement>();

  useEffect(() => { if (active) void list.ensure({}); }, [active, list.ensure]);
  useCloseOnLeave(active, () => { setCreating(false); setEditing(undefined); });

  function apply(patch: Partial<PlacementFilters>) {
    const next: PlacementFilters = { ...filters, ...patch };
    setFilters(next);
    void list.load(next, 1);
  }
  const onInvalid = (message: string) => authOrError(new Error(message), '请检查表单');
  const act = (placement: Placement, action: 'enable' | 'disable') => void run(
    () => client.placementAction(placement.placementId, action, placement.version),
    action === 'enable' ? '投放位已启用' : '投放位已停用', list.reload);

  const campaignOf = (placement: Placement) => campaigns?.find(item => item.campaignId === placement.campaignId);
  // 列宽预算合计 ≤ 1000px（见活动分区），操作列固定在右侧。
  const columns: TableColumnsType<Placement> = [
    { title: '投放位', key: 'placement', width: 210, render: (_, placement) => <div className="mkt-entity-text">
      <div className="mkt-entity-name" title={POSITION_TEXT[placement.positionCode] ?? placement.positionCode}>
        {POSITION_TEXT[placement.positionCode] ?? placement.positionCode}
      </div>
      <div className="mkt-entity-id">ID {placement.placementId}</div>
      <div><Tag>{placement.cityCode ?? '全部城市'}</Tag></div>
    </div> },
    { title: '活动', key: 'campaign', width: 190, render: (_, placement) => {
      const campaign = campaignOf(placement);
      const linkedLive = !campaign || ['SCHEDULED', 'ONLINE'].includes(campaign.status);
      return <div className="mkt-entity-text">
        <div className="mkt-entity-name" title={placement.campaignTitle}>{placement.campaignTitle}</div>
        <div className="mkt-entity-id">ID {placement.campaignId}</div>
        {placement.status === 'ENABLED' && !linkedLive && <div><Tag color="orange">关联活动未上线</Tag></div>}
      </div>;
    } },
    { title: '投放内容', width: 150, render: (_, placement) => <div className="mkt-entity-text">
      <div title={placement.titleOverride ?? undefined}>{placement.titleOverride ?? '沿用活动标题'}</div>
      <div className="mkt-entity-sub">{!placement.templateCode ? '通用模板' : (PLACEMENT_TEMPLATE_TEXT[placement.templateCode] ?? `模板 ${placement.templateCode}`)}</div>
    </div> },
    { title: '投放窗口', width: 150, render: (_, placement) => <TimeCell startAt={placement.startAt} endAt={placement.endAt} /> },
    { title: '状态', width: 116, render: (_, placement) => <div className="mkt-stack">
      <StatusTag label={STATUS_TEXT[placement.status] ?? placement.status} status={placement.status} />
      <VisibilityTag visibility={visibilityOf('placement', placement.status, placement.startAt, placement.endAt)} />
    </div> },
    { title: '操作', key: 'actions', width: 160, fixed: 'right', render: (_, placement) => canManage && <Space size={12} wrap>
      {placement.status !== 'ENABLED' && <>
        <Button type="link" size="small" style={LINK_STYLE} disabled={busy}
          onClick={() => { setCreating(false); setEditing(placement); }}>编辑</Button>
        <Button type="link" size="small" style={LINK_STYLE} disabled={busy} onClick={() => act(placement, 'enable')}>启用</Button>
      </>}
      {placement.status === 'ENABLED' && <Popconfirm title="停用后该投放位不再对用户展示。确认停用？"
        okText="确认停用" cancelText="再想想" okButtonProps={{ danger: true }} onConfirm={() => act(placement, 'disable')}>
        <Button type="link" size="small" danger style={LINK_STYLE} disabled={busy}>停用</Button>
      </Popconfirm>}
    </Space> },
  ];

  const save = (placement: Placement, input: Record<string, unknown>) => void run(
    () => client.updatePlacement(placement.placementId, input, placement.version), '投放位已保存',
    async () => { setEditing(undefined); await list.reload(); });

  return <>
    <div className="filter-bar" aria-label="投放位筛选">
      <Field label="位置"><Select aria-label="位置" style={{ width: 220 }} value={filters.positionCode ?? ''} options={POSITION_OPTIONS}
        onChange={value => apply({ positionCode: value || undefined })} /></Field>
      <Field label="状态"><Select aria-label="状态" style={{ width: 130 }} value={filters.status ?? ''} options={STATUS_OPTIONS}
        onChange={value => apply({ status: value || undefined })} /></Field>
      <Button disabled={busy || list.loading} onClick={() => void list.reload()}>刷新</Button>
      {canManage && <Button type="primary" onClick={() => { setEditing(undefined); setCreating(true); }}>新建投放位</Button>}
    </div>

    {list.failed && !list.rows ? <LoadFailed onRetry={() => void list.reload()} />
      : <ConfigProvider renderEmpty={() => <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={filters.positionCode || filters.status ? '没有符合当前筛选条件的投放位' : '暂无投放位'} />}>
        <DataTable tableLayout="fixed" columns={columns} rows={list.rows} rowKey={placement => placement.placementId}
          loading={list.loading} page={list.page} pageSize={PAGE_SIZE} total={list.total}
          onPageChange={page => void list.load(filters, page)} />
      </ConfigProvider>}

    <FormDrawer open={creating} onClose={() => setCreating(false)} title="新建投放位" label="新建投放位"
      description="投放位把活动投到小程序的指定位置（首页活动位、首页 Banner、商家列表、热门服务顶部）。">
      <PlacementForm idPrefix="placement-new" client={client} campaigns={campaigns} canManage={canManage} busy={busy}
        onInvalid={onInvalid} onFailure={caught => authOrError(caught, '素材上传失败')}
        onSubmit={input => void run(() => client.createPlacement(input), '投放位已创建（默认停用，可在列表点「启用」上线）',
          async () => { setCreating(false); await list.load({ ...filters, status: undefined }, 1); setFilters({ ...filters, status: undefined }); })} />
    </FormDrawer>

    <FormDrawer open={!!editing} onClose={() => setEditing(undefined)} title={`编辑投放位：${editing?.placementId ?? ''}`}
      label="编辑投放位" description="已启用的投放位需先停用才能编辑；保存后仍为停用状态，确认无误后请重新启用。">
      {editing && <PlacementForm idPrefix="placement-edit" placement={editing} client={client} campaigns={campaigns}
        canManage={canManage} busy={busy} onInvalid={onInvalid}
        onFailure={caught => authOrError(caught, '素材上传失败')}
        onSubmit={input => save(editing, input)} onCancel={() => setEditing(undefined)} />}
    </FormDrawer>
  </>;
}
