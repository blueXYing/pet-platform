import { useEffect, useRef, useState } from 'react';
import { Button, Checkbox, ConfigProvider, DatePicker, Input, Select } from 'antd';
import dayjs from 'dayjs';
import type { TableColumnsType } from 'antd';
import {
  isCouponConflict, INSTANCE_STATUSES, TEMPLATE_STATUSES,
  type AdminTemplateDetail, type AdminTemplateSummary, type CouponAdminClient,
  type GrantReceipt, type TemplateStatus,
} from '../api/couponAdmin';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿', ACTIVE: '启用', OFFLINE: '停用',
  AVAILABLE: '可领取', FROZEN: '已冻结', USED: '已使用', EXPIRED: '已过期', RISK_FROZEN: '风控冻结',
};

/**
 * Contract-65 券模板管理页 (PRD 6.4.1, A-005): template ledger, create-draft form, edit
 * guard (发放过的模板不可编辑，需停用后新建), enable/offline transitions, the freeze-rule
 * trial calculation and the §4.3 grant channel (user ruling 2026-10-16, CCR-CPN-GRANT-001
 * minimum: one user + one ACTIVE template = one AVAILABLE instance per terminal requestId,
 * double-confirmed before the wire). Instance-status writes (作废/返还) still do not exist.
 */
export default function CouponTemplatePage({ client, canWrite, canGrant, onAuthLost }: {
  client: CouponAdminClient;
  canWrite: boolean;
  canGrant: boolean;
  onAuthLost: () => void;
}) {
  const [rows, setRows] = useState<AdminTemplateSummary[]>();
  const [status, setStatus] = useState<TemplateStatus | ''>('');
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminTemplateDetail>();
  const [granting, setGranting] = useState<AdminTemplateSummary>();
  const [grantConfirm, setGrantConfirm] = useState(false);
  const [grantReceipt, setGrantReceipt] = useState<GrantReceipt>();
  const [trial, setTrial] = useState('');
  const [trialResult, setTrialResult] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const sequence = useRef(0);

  async function load(nextStatus: TemplateStatus | '') {
    const call = ++sequence.current;
    try {
      const page = await client.listTemplates(nextStatus ? { status: nextStatus } : {});
      if (call !== sequence.current) return;
      setRows(page.items);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof Error && caught.message === 'UNAUTHENTICATED') { onAuthLost(); return; }
      setError(caught instanceof Error ? `读取失败（${caught.message}）` : '读取失败');
    }
  }

  useEffect(() => { void load(''); return () => { sequence.current++; }; }, [client]); // eslint-disable-line react-hooks/exhaustive-deps

  async function act(action: () => Promise<unknown>, okText: string) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await action();
      setNotice(okText);
      setEditing(undefined);
      await load(status);
    } catch (caught) {
      if (isCouponConflict(caught)) setError('状态或版本冲突：模板已被其他人修改，请刷新后重试');
      else setError(caught instanceof Error ? `操作失败（${caught.message}）` : '操作失败');
    } finally {
      setBusy(false);
    }
  }

  const guard = (action: () => void) => () => {
    if (!canWrite) { setError('当前账号未授予 coupon.template.manage'); return; }
    action();
  };

  const grantGuard = (action: () => void) => () => {
    if (!canGrant) { setError('当前账号未授予 coupon.instance.grant'); return; }
    action();
  };

  async function runTrial(summary: AdminTemplateSummary) {
    setEditing(undefined);
    if (!trial || Number(trial) <= 0) { setError('请输入有效的试算订单金额'); return; }
    try {
      const result = await client.trial(summary.templateId, Number(trial).toFixed(2));
      setTrialResult(result.eligible
        ? `可抵扣 ${result.discountAmount} 元，应付 ${result.payAmount} 元`
        : `不可用：${result.ineligibleReason}`);
    } catch (caught) {
      setError(caught instanceof Error ? `试算失败（${caught.message}）` : '试算失败');
    }
  }

  /** §4.3 grant: form → explicit double confirm → one terminal request id per grant. */
  async function submitGrant(form: HTMLFormElement) {
    const data = new FormData(form);
    const templateId = String(data.get('templateId') ?? '');
    const userId = String(data.get('userId') ?? '').trim();
    setError('');
    if (!/^[1-9][0-9]{0,18}$/.test(templateId) || !/^[1-9][0-9]{0,18}$/.test(userId)) {
      setError('模板 ID 与用户 ID 必须为纯数字 Snowflake 字符串');
      return;
    }
    if (!grantConfirm) { setError('请先勾选二次确认'); return; }
    try {
      const receipt = await client.grantInstance(templateId, userId, crypto.randomUUID());
      setGrantReceipt(receipt);
      setGranting(undefined);
      setGrantConfirm(false);
      setNotice(`已发放：券实例 ${receipt.instanceId}（${STATUS_TEXT[receipt.status]}）`);
      await load(status);
    } catch (caught) {
      if (caught instanceof Error && caught.message === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (isCouponConflict(caught)) setError('发放冲突：模板不在启用状态或不在有效期内（或请求编号冲突）');
      else setError(caught instanceof Error ? `发放失败（${caught.message}）` : '发放失败');
    }
  }

  const columns: TableColumnsType<AdminTemplateSummary> = [
    { title: '模板 ID', dataIndex: 'templateId' },
    { title: '名称', dataIndex: 'name' },
    { title: '状态', render: (_, row) => <StatusTag label={STATUS_TEXT[row.status]} status={row.status} /> },
    { title: '面额', dataIndex: 'amountOff' },
    { title: '门槛', render: (_, row) => row.thresholdAmount ?? '无门槛' },
    { title: '有效期', render: (_, row) => `${row.validStartAt.slice(0, 10)} ~ ${row.validEndAt.slice(0, 10)}` },
    { title: '已发放', dataIndex: 'issuedCount' },
    { title: '版本', dataIndex: 'version' },
    { title: '操作', render: (_, row) => <>
      {row.status === 'DRAFT' && <Button disabled={busy} onClick={guard(() => void act(
        () => client.setTemplateEnabled(row.templateId, row.version, true, crypto.randomUUID()), '已启用'))}>启用</Button>}
      {row.status === 'ACTIVE' && <Button disabled={busy} onClick={guard(() => void act(
        () => client.setTemplateEnabled(row.templateId, row.version, false, crypto.randomUUID()), '已停用'))}>停用</Button>}
      {row.status !== 'OFFLINE' && row.issuedCount === '0' && <Button disabled={busy} onClick={guard(() => void client.getTemplate(row.templateId).then(setEditing).catch(cause => setError(cause instanceof Error ? `读取失败（${cause.message}）` : '读取失败')))}>编辑</Button>}
      {canGrant && row.status === 'ACTIVE' && <Button disabled={busy} onClick={grantGuard(() => { setEditing(undefined); setGranting(row); setGrantConfirm(false); setGrantReceipt(undefined); })}>发放</Button>}
    </> },
  ];

  return <PageContainer
    title="优惠券 · 券模板"
    hint={'模板一经发放（引用数 > 0）关键字段不可修改，需停用后新建；停用不影响已发放券实例。发放渠道为最小实现：每次向一名用户发放一张（单用户持有上限/批量发放/频控未开放，CCR-CPN-GRANT-001）；“领取后 N 天”模式 V1 不做。'}>
    <form className="filter-bar" onSubmit={event => event.preventDefault()}>
      <label className="filter-item">状态
        <Select style={{ width: 160 }} value={status} onChange={value => { setStatus(value as TemplateStatus | ''); void load(value as TemplateStatus | ''); }}
          options={[{ value: '', label: '全部' }, ...TEMPLATE_STATUSES.map(value => ({ value, label: STATUS_TEXT[value] }))]} />
      </label>
      <Button type="primary" disabled={busy} onClick={guard(() => setCreating(true))}>新增模板</Button>
      {notice && <InlineStatus>{notice}</InlineStatus>}
      {error && <ErrorAlert>{error}</ErrorAlert>}
    </form>
    {rows === undefined ? <InlineStatus>加载中</InlineStatus> : <ConfigProvider renderEmpty={() => '无模板'}>
      <DataTable columns={columns} rows={rows} rowKey={row => row.templateId} />
    </ConfigProvider>}

    {(creating || editing) && <section className="detail-card" aria-label="模板表单">
      <h3>{creating ? '新增模板（草稿）' : `编辑：${editing?.name}（版本 ${editing?.version}）`}</h3>
      <form onSubmit={event => {
        event.preventDefault();
        const form = new FormData(event.currentTarget);
        const thresholdRaw = String(form.get('thresholdAmount') ?? '').trim();
        const draft = {
          name: String(form.get('name') ?? '').trim(),
          amountOff: Number(form.get('amountOff') ?? '0').toFixed(2),
          thresholdAmount: thresholdRaw ? Number(thresholdRaw).toFixed(2) : null,
          validStartAt: `${String(form.get('validStartDate') ?? '')}T00:00:00.000Z`,
          validEndAt: `${String(form.get('validEndDate') ?? '')}T23:59:59.000Z`.replace('T23:59:59.000Z', 'T15:59:59.000Z'),
          scopeSummary: (String(form.get('scopeSummary') ?? '').trim() || null),
          typeLabel: null,
        };
        if (creating) void act(() => client.createTemplate(draft, crypto.randomUUID()), '已创建（草稿）');
        else if (editing) void act(() => client.updateTemplate(editing.templateId, draft, editing.version, crypto.randomUUID()), '已保存');
      }}>
        <label className="filter-item">券名称（2..50 字）<Input name="name" defaultValue={editing?.name} minLength={2} maxLength={50} required /></label>
        <label className="filter-item">面额（元，&gt;0）<Input name="amountOff" type="number" step="0.01" min="0.01" defaultValue={editing?.amountOff} required /></label>
        <label className="filter-item">使用门槛（元，留空为无门槛券）<Input name="thresholdAmount" type="number" step="0.01" min="0" defaultValue={editing?.thresholdAmount ?? ''} /></label>
        <label className="filter-item">生效日期<DatePicker name="validStartDate" format="YYYY-MM-DD" required defaultValue={editing ? dayjs(editing.validStartAt.slice(0, 10)) : undefined} /></label>
        <label className="filter-item">失效日期<DatePicker name="validEndDate" format="YYYY-MM-DD" required defaultValue={editing ? dayjs(editing.validEndAt.slice(0, 10)) : undefined} /></label>
        <label className="filter-item">适用范围说明（展示用，≤64 字）<Input name="scopeSummary" defaultValue={editing?.scopeSummary ?? ''} maxLength={64} /></label>
        <Button type="primary" htmlType="submit" disabled={busy}>保存</Button>
        <Button onClick={() => { setCreating(false); setEditing(undefined); }}>关闭</Button>
      </form>
      {editing && <form className="filter-bar" onSubmit={event => { event.preventDefault(); void runTrial(editing); }}>
        <label className="filter-item">试算订单金额（元）<Input value={trial} onChange={event => setTrial(event.target.value)} inputMode="decimal" /></label>
        <Button htmlType="submit" disabled={busy}>试算</Button>
        {trialResult && <InlineStatus>{trialResult}</InlineStatus>}
      </form>}
    </section>}

    {granting && <section className="detail-card" aria-label="发放确认">
      <h3>发放：{granting.name}（面额 {granting.amountOff}）</h3>
      <form onSubmit={event => { event.preventDefault(); void submitGrant(event.currentTarget); }}>
        <label className="filter-item">模板 ID<Input name="templateId" value={granting.templateId} readOnly /></label>
        <label className="filter-item">用户 ID（纯数字 Snowflake）<Input name="userId" required pattern="[1-9][0-9]{0,18}" /></label>
        <div>
          <Checkbox checked={grantConfirm}
            onChange={event => setGrantConfirm(event.target.checked)}>
            确认向该用户发放 1 张本模板券实例（发放后不可作废，V1 无实例写动作）
          </Checkbox>
        </div>
        <Button type="primary" htmlType="submit" disabled={busy || !grantConfirm}>确认发放</Button>
        <Button onClick={() => { setGranting(undefined); setGrantConfirm(false); }}>关闭</Button>
      </form>
    </section>}

    {grantReceipt && <section className="detail-card" aria-label="发放回执">
      <h3>发放回执</h3>
      <p>券实例 <strong>{grantReceipt.instanceId}</strong> · {STATUS_TEXT[grantReceipt.status]} · 有效期至 {grantReceipt.expireAt.slice(0, 10)}</p>
      <p className="hint">用户在 C 端「我的优惠券」立即可见；该实例由运营发放（coupon_ledger ISSUE 台账留痕）。</p>
      <Button onClick={() => setGrantReceipt(undefined)}>关闭</Button>
    </section>}
  </PageContainer>;
}

export const COUPON_INSTANCE_STATUS_LABELS = Object.fromEntries(
  INSTANCE_STATUSES.map(value => [value, STATUS_TEXT[value]]));
