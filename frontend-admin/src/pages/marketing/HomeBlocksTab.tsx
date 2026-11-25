import { useEffect, useState, type FormEvent } from 'react';
import { Button, ConfigProvider, Empty, Input, Popconfirm, Select, Space } from 'antd';
import type { TableColumnsType } from 'antd';
import { HOME_BLOCK_TARGET_TYPES, targetNeedsValue, type HomeBlock, type MarketingAdminClient } from '../../api/marketingAdmin';
import { DataTable } from '../../ui/DataTable';
import { StatusTag } from '../../ui/bits';
import {
  AssetPicker, campaignPickerOptions, CoverThumb, Field, FormDrawer, HOME_POSITION_TEXT, HOME_TARGET_TEXT, IdPicker, iso,
  LoadFailed, localInput, PAGE_SIZE, STATUS_TEXT, TimeCell, useCampaignDirectory, useCloseOnLeave, usePagedList,
  visibilityOf, VisibilityTag, windowIsValid, type FiltersOf, type TabProps,
} from './shared';

type HomeBlockFilters = FiltersOf<MarketingAdminClient['listHomeBlocks']>;
const LINK_STYLE = { padding: 0, height: 'auto' } as const;
const POSITION_OPTIONS = [
  { value: 'HOME_SERVICE', label: '热门服务' },
  { value: 'HOME_COMMUNITY', label: '宠友圈热门' },
  { value: 'HOME_EXOTIC_PLANT', label: '植物&异宠' },
];
const TEMPLATE_OF_POSITION: Record<string, string> = {
  HOME_SERVICE: 'HOT_SERVICE_FEATURE', HOME_COMMUNITY: 'COMMUNITY_HOT', HOME_EXOTIC_PLANT: 'EXOTIC_PLANT',
};
const TEMPLATE_OPTIONS = [
  { value: 'HOT_SERVICE_FEATURE', label: '热门服务大卡' },
  { value: 'COMMUNITY_HOT', label: '宠友圈热门容器' },
  { value: 'EXOTIC_PLANT', label: '植物&异宠小卡' },
];
const STATUS_OPTIONS = [
  { value: '', label: '全部状态' }, { value: 'ENABLED', label: '已启用' }, { value: 'DISABLED', label: '已停用' },
];

/**
 * 目标值要求，逐条对应后端 validateHomeBlockInput：
 * NONE / HOT_SERVICE 不保存目标值；服务目录、宠友圈留空时后端用默认值；其余类型目标值必填。
 * 旧界面一律不标必填，运营只能提交后收到一句笼统的「填写内容不符合要求」。
 */
const TARGET_RULES: Record<string, { required: boolean; placeholder: string; hint: string }> = {
  SERVICE_DIRECTORY: { required: false, placeholder: '留空 = 全部服务', hint: '留空时后端按默认值 ALL（全部服务）提交。' },
  SERVICE_CATEGORY: { required: true, placeholder: '服务分类关键词，如 宠物美容', hint: '必填。填服务分类的名称关键词。' },
  STORE: { required: true, placeholder: '门店 ID', hint: '必填。填门店 ID，可在活动详情的参与门店列表里查看。' },
  SERVICE: { required: true, placeholder: '服务 ID', hint: '必填。填服务 ID。' },
  CAMPAIGN: { required: true, placeholder: '输入活动名称或 ID', hint: '必填。选一个活动，点击后进入该活动的会场页。' },
  COMMUNITY: { required: false, placeholder: '留空 = 默认内容', hint: '留空时后端按默认值 HOT 提交。' },
  MINIAPP_PATH: { required: true, placeholder: '/consumer/pages/…', hint: '必填。必须是用户端小程序页面路径，以 /consumer/pages/ 开头。' },
};
const BLOCK_CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{1,63}$/;

/** 首页内容位新建 / 编辑共用表单。 */
function HomeBlockForm({ idPrefix, block, client, campaigns, canManage, busy, onInvalid, onSubmit, onFailure, onCancel }: {
  idPrefix: string; block?: HomeBlock; client: MarketingAdminClient; campaigns: ReturnType<typeof useCampaignDirectory>;
  canManage: boolean; busy: boolean; onInvalid: (message: string) => void;
  onSubmit: (input: Record<string, unknown>) => void;
  onFailure: (caught: unknown) => void; onCancel?: () => void;
}) {
  const [positionCode, setPositionCode] = useState(block?.positionCode ?? 'HOME_SERVICE');
  const [templateCode, setTemplateCode] = useState(block?.templateCode ?? 'HOT_SERVICE_FEATURE');
  // 已有内容位的模板视为运营定过的：切换位置时不覆盖。
  const [templateTouched, setTemplateTouched] = useState(!!block);
  // F-A-03: targetType=HOT_SERVICE routes the C-side on the type alone (contract 71 v0.3 §10);
  // the server nulls targetValue for it exactly like NONE, so the input stays blank/disabled.
  const [targetType, setTargetType] = useState(block?.targetType ?? 'SERVICE_DIRECTORY');
  const [campaignTarget, setCampaignTarget] = useState(block?.targetType === 'CAMPAIGN' ? block.targetValue ?? '' : '');
  const needsValue = targetNeedsValue(targetType);
  const rule = TARGET_RULES[targetType];
  const id = (suffix: string) => `${idPrefix}-${suffix}`;

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const text = (key: string) => String(form.get(key) ?? '').trim();
    if (!BLOCK_CODE_PATTERN.test(text('blockCode'))) {
      onInvalid('内容位编码须为 2-64 位，以字母或数字开头，只能包含字母、数字、下划线和短横线'); return;
    }
    if (!windowIsValid(text('startAt'), text('endAt'))) { onInvalid('结束时间必须晚于开始时间'); return; }
    // 落地类型为「活动」时目标值是联想选择控件（没有原生 required），必填在这里补上。
    if (targetType === 'CAMPAIGN' && !text('targetValue')) { onInvalid('落地类型为「活动」时必须选择目标活动'); return; }
    if (targetType === 'MINIAPP_PATH' && !text('targetValue').startsWith('/consumer/pages/')) {
      onInvalid('小程序路径必须以 /consumer/pages/ 开头'); return;
    }
    if (Boolean(text('sponsorMerchantId')) !== Boolean(text('sponsorStoreId'))) {
      onInvalid('赞助商家 ID 与赞助门店 ID 必须同时填写，或同时留空'); return;
    }
    onSubmit({
      blockCode: text('blockCode'),
      positionCode,
      templateCode,
      title: text('title'),
      subtitle: text('subtitle') || undefined,
      tagName: text('tagName') || undefined,
      imageUrl: text('imageUrl') || undefined,
      targetType,
      targetValue: (needsValue ? text('targetValue') : '') || undefined,
      cityCode: text('cityCode') || undefined,
      sponsorMerchantId: text('sponsorMerchantId') || undefined,
      sponsorStoreId: text('sponsorStoreId') || undefined,
      sortWeight: form.get('sortWeight') ? Number(form.get('sortWeight')) : 0,
      startAt: iso(text('startAt')),
      endAt: iso(text('endAt')),
    });
  };
  return <form className="mkt-form-body" onSubmit={submit}>
    <div className="mkt-form-fields">
    <fieldset>
      <legend>内容位与模板</legend>
      <div className="mkt-form-grid">
        <Field id={id('code')} label="内容位编码（2-64，字母数字/下划线/短横线）">
          <Input id={id('code')} name="blockCode" defaultValue={block?.blockCode ?? ''} minLength={2} maxLength={64} required />
        </Field>
        <Field id={id('position')} label="位置">
          <select id={id('position')} name="positionCode" value={positionCode} onChange={event => {
            const next = event.target.value;
            setPositionCode(next);
            if (!templateTouched) setTemplateCode(TEMPLATE_OF_POSITION[next] ?? templateCode);
          }}>
            {POSITION_OPTIONS.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        </Field>
        <Field id={id('template')} label="模板" hint="位置与模板一一对应，切换位置时自动带出">
          <select id={id('template')} name="templateCode" value={templateCode}
            onChange={event => { setTemplateCode(event.target.value); setTemplateTouched(true); }}>
            {TEMPLATE_OPTIONS.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        </Field>
      </div>
    </fieldset>
    <fieldset>
      <legend>展示内容</legend>
      <div className="mkt-form-grid">
        <Field id={id('title')} label="标题"><Input id={id('title')} name="title" defaultValue={block?.title ?? ''} maxLength={100} required /></Field>
        <Field id={id('tagName')} label="标签名"><Input id={id('tagName')} name="tagName" defaultValue={block?.tagName ?? ''} maxLength={32} placeholder="热门服务 / 宠友圈热门 / 植物&异宠" /></Field>
        <Field id={id('subtitle')} label="副标题" span><Input id={id('subtitle')} name="subtitle" defaultValue={block?.subtitle ?? ''} maxLength={200} /></Field>
        <AssetPicker name="imageUrl" label="内容位图片" uploadText="上传图片" defaultValue={block?.imageUrl}
          defaultDisplayUrl={block?.imageDisplayUrl}
          client={client} canManage={canManage} onFailure={onFailure} />
      </div>
    </fieldset>
    <fieldset>
      <legend>落地跳转</legend>
      <div className="mkt-form-grid">
        <Field id={id('targetType')} label="落地类型" span>
          <select id={id('targetType')} name="targetType" value={targetType} onChange={event => setTargetType(event.target.value)}>
            {HOME_BLOCK_TARGET_TYPES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </Field>
        <Field id={id('targetValue')} span
          label={needsValue ? '目标值' : '目标值（此类型留空即可，后端不保存目标值）'}
          hint={needsValue ? rule?.hint : undefined}>
          {targetType === 'CAMPAIGN' && needsValue
            ? <>
              <input type="hidden" name="targetValue" value={campaignTarget.trim()} />
              <IdPicker id={id('targetValue')} value={campaignTarget} onChange={setCampaignTarget}
                options={campaignPickerOptions(campaigns)} placeholder={rule?.placeholder} />
            </>
            : <Input id={id('targetValue')} name="targetValue" defaultValue={block?.targetValue ?? ''} maxLength={255}
              disabled={!needsValue} required={needsValue && !!rule?.required}
              placeholder={needsValue ? rule?.placeholder : '留空'} />}
        </Field>
      </div>
    </fieldset>
    <fieldset>
      <legend>定向、赞助与排序</legend>
      <div className="mkt-form-grid">
        <Field id={id('city')} label="城市编码（空=全部）" hint="须为已开放城市的小写编码，如 shanghai">
          <Input id={id('city')} name="cityCode" defaultValue={block?.cityCode ?? ''} maxLength={32} />
        </Field>
        <Field id={id('sort')} label="排序权重" hint="数值越小越靠前">
          <input id={id('sort')} name="sortWeight" type="number" min={0} max={9999} defaultValue={block?.sortWeight ?? 0} />
        </Field>
        <Field id={id('sponsorMerchant')} label="赞助商家 ID（预留竞价，可空）" hint="与赞助门店 ID 必须同时填写或同时留空">
          <Input id={id('sponsorMerchant')} name="sponsorMerchantId" defaultValue={block?.sponsorMerchantId ?? ''} />
        </Field>
        <Field id={id('sponsorStore')} label="赞助门店 ID（预留竞价，可空）">
          <Input id={id('sponsorStore')} name="sponsorStoreId" defaultValue={block?.sponsorStoreId ?? ''} />
        </Field>
      </div>
    </fieldset>
    <fieldset>
      <legend>展示时间窗</legend>
      <div className="mkt-form-grid">
        <Field id={id('start')} label="开始时间"><input id={id('start')} name="startAt" type="datetime-local" defaultValue={localInput(block?.startAt)} required /></Field>
        <Field id={id('end')} label="结束时间"><input id={id('end')} name="endAt" type="datetime-local" defaultValue={localInput(block?.endAt)} required /></Field>
      </div>
    </fieldset>
    </div>
    <div className="mkt-form-actions">
      <Button type="primary" htmlType="submit" disabled={busy}>{block ? '保存修改' : '创建草稿'}</Button>
      {block && onCancel && <Button disabled={busy} onClick={onCancel}>取消</Button>}
    </div>
  </form>;
}

export function HomeBlocksTab({ client, active, canManage, busy, run, authOrError, onAuthLost }: TabProps & { canManage: boolean }) {
  const list = usePagedList<HomeBlock, HomeBlockFilters>(
    (filters, page) => client.listHomeBlocks(filters, page, PAGE_SIZE), authOrError, '读取首页内容位失败');
  const [filters, setFilters] = useState<HomeBlockFilters>({});
  const campaigns = useCampaignDirectory(client, active, onAuthLost);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<HomeBlock>();

  useEffect(() => { if (active) void list.ensure({}); }, [active, list.ensure]);
  useCloseOnLeave(active, () => { setCreating(false); setEditing(undefined); });

  function apply(patch: Partial<HomeBlockFilters>) {
    const next: HomeBlockFilters = { ...filters, ...patch };
    setFilters(next);
    void list.load(next, 1);
  }
  const onInvalid = (message: string) => authOrError(new Error(message), '请检查表单');
  const act = (block: HomeBlock, action: 'enable' | 'disable') => void run(
    () => client.homeBlockAction(block.blockId, action, block.version),
    action === 'enable' ? '首页内容位已启用' : '首页内容位已停用', list.reload);

  // 列宽预算合计 ≤ 1000px（见活动分区），操作列固定在右侧。
  const columns: TableColumnsType<HomeBlock> = [
    { title: '内容位', key: 'block', width: 230, render: (_, block) => <div className="mkt-entity">
      <CoverThumb url={block.imageUrl} displayUrl={block.imageDisplayUrl} emptyText="无图" />
      <div className="mkt-entity-text">
        <div className="mkt-entity-name" title={block.title}>{block.title}</div>
        <div className="mkt-entity-sub" title={block.subtitle ?? undefined}>{block.subtitle}</div>
        <div className="mkt-entity-id">{block.blockCode} · ID {block.blockId}</div>
      </div>
    </div> },
    { title: '位置', width: 130, render: (_, block) => <div className="mkt-entity-text">
      <div>{HOME_POSITION_TEXT[block.positionCode] ?? block.positionCode}</div>
      <div className="mkt-entity-sub">{block.cityCode ?? '全部城市'}</div>
    </div> },
    { title: '落地', width: 150, render: (_, block) => <div className="mkt-entity-text">
      <div>{HOME_TARGET_TEXT[block.targetType] ?? block.targetType}</div>
      <div className="mkt-entity-sub" title={block.targetValue ?? undefined}>{block.targetValue}</div>
    </div> },
    { title: '展示窗口', width: 150, render: (_, block) => <TimeCell startAt={block.startAt} endAt={block.endAt} /> },
    { title: '状态', width: 116, render: (_, block) => <div className="mkt-stack">
      <StatusTag label={STATUS_TEXT[block.status] ?? block.status} status={block.status} />
      <VisibilityTag visibility={visibilityOf('homeBlock', block.status, block.startAt, block.endAt)} />
    </div> },
    { title: '排序', width: 60, dataIndex: 'sortWeight' },
    { title: '操作', key: 'actions', width: 150, fixed: 'right', render: (_, block) => canManage && <Space size={12} wrap>
      <Button type="link" size="small" style={LINK_STYLE} disabled={busy}
        onClick={() => { setCreating(false); setEditing(block); }}>编辑</Button>
      {block.status !== 'ENABLED' && <Button type="link" size="small" style={LINK_STYLE} disabled={busy}
        onClick={() => act(block, 'enable')}>启用</Button>}
      {block.status === 'ENABLED' && <Popconfirm title="停用后用户将不再在首页看到该内容位。确认停用？"
        okText="确认停用" cancelText="再想想" okButtonProps={{ danger: true }} onConfirm={() => act(block, 'disable')}>
        <Button type="link" size="small" danger style={LINK_STYLE} disabled={busy}>停用</Button>
      </Popconfirm>}
    </Space> },
  ];

  return <>
    <p className="hint">小程序首页的「热门服务 / 宠友圈热门 / 植物&异宠」三个板块由这里配置。改文案、换图、排序、上下线都在这里生效，不需要小程序发版。
      只有「已启用」且在展示时间窗内的内容位才会出现在首页。</p>
    <div className="filter-bar" aria-label="首页内容位筛选">
      <Field label="位置"><Select aria-label="位置" style={{ width: 140 }} value={filters.positionCode ?? ''}
        options={[{ value: '', label: '全部位置' }, ...POSITION_OPTIONS]}
        onChange={value => apply({ positionCode: value || undefined })} /></Field>
      <Field label="状态"><Select aria-label="状态" style={{ width: 130 }} value={filters.status ?? ''} options={STATUS_OPTIONS}
        onChange={value => apply({ status: value || undefined })} /></Field>
      <Button disabled={busy || list.loading} onClick={() => void list.reload()}>刷新</Button>
      {canManage && <Button type="primary" onClick={() => { setEditing(undefined); setCreating(true); }}>新建首页内容位</Button>}
    </div>

    {list.failed && !list.rows ? <LoadFailed onRetry={() => void list.reload()} />
      : <ConfigProvider renderEmpty={() => <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={filters.positionCode || filters.status ? '没有符合当前筛选条件的内容位' : '暂无首页内容位'} />}>
        <DataTable tableLayout="fixed" columns={columns} rows={list.rows} rowKey={block => block.blockId}
          loading={list.loading} page={list.page} pageSize={PAGE_SIZE} total={list.total}
          onPageChange={page => void list.load(filters, page)} />
      </ConfigProvider>}

    <FormDrawer open={creating} onClose={() => setCreating(false)} title="新建首页内容位" label="新建首页内容位"
      description="创建后为停用状态，确认展示效果后在列表点「启用」上线。">
      <HomeBlockForm idPrefix="home-new" client={client} campaigns={campaigns} canManage={canManage} busy={busy}
        onInvalid={onInvalid} onFailure={caught => authOrError(caught, '素材上传失败')}
        onSubmit={input => void run(() => client.createHomeBlock(input), '首页内容位草稿已创建',
          async () => { setCreating(false); await list.load({ ...filters, status: undefined }, 1); setFilters({ ...filters, status: undefined }); })} />
    </FormDrawer>

    <FormDrawer open={!!editing} onClose={() => setEditing(undefined)} title={`编辑首页内容位：${editing?.title ?? ''}`}
      label="编辑首页内容位" description="已启用的内容位也可以直接修改，无需先停用；保存后用户下次打开首页即可看到。">
      {editing && <HomeBlockForm idPrefix="home-edit" block={editing} client={client} campaigns={campaigns} canManage={canManage}
        busy={busy} onInvalid={onInvalid} onFailure={caught => authOrError(caught, '素材上传失败')}
        onSubmit={input => void run(() => client.updateHomeBlock(editing.blockId, input, editing.version), '首页内容位已保存',
          async () => { setEditing(undefined); await list.reload(); })}
        onCancel={() => setEditing(undefined)} />}
    </FormDrawer>
  </>;
}
