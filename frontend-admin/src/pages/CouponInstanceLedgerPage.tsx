import { useEffect, useRef, useState } from 'react';
import { Button, Checkbox, ConfigProvider, Input, Select } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  isCouponConflict, INSTANCE_STATUSES, type AdminInstanceDetail, type AdminInstanceSummary,
  type CouponAdminClient, type InstanceStatus, type LedgerRow, type VoidReceipt,
} from '../api/couponAdmin';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, FactList, InlineStatus, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

const STATUS_TEXT: Record<string, string> = {
  AVAILABLE: '可领取', FROZEN: '已冻结', USED: '已使用', EXPIRED: '已过期', RISK_FROZEN: '风控冻结',
  VOID: '已作废',
};

/**
 * Contract-65 券实例台账页 (PRD 6.4.3, A-005): instance ledger with filters, the append-only
 * coupon_ledger state timeline and the §4.4 void channel (user ruling 2026-10-16 doc 36
 * minimal authorization, CCR-CPN-VOID-001 minimum): AVAILABLE rows carry a 作废 entry that is
 * double-confirmed before the wire; every non-AVAILABLE state answers 409 server-side and
 * renders no entry. Batch void / return / risk-freeze stay unauthorized (CCR-ADM-A005-OPS-001)
 * and the page states that honestly.
 */
export default function CouponInstanceLedgerPage({ client, canVoid, onAuthLost }: {
  client: CouponAdminClient;
  canVoid: boolean;
  onAuthLost: () => void;
}) {
  const [rows, setRows] = useState<AdminInstanceSummary[]>();
  const [total, setTotal] = useState(0);
  const [status, setStatus] = useState<InstanceStatus | ''>('');
  const [templateId, setTemplateId] = useState('');
  const [detail, setDetail] = useState<AdminInstanceDetail>();
  const [voiding, setVoiding] = useState<AdminInstanceSummary>();
  const [voidConfirm, setVoidConfirm] = useState(false);
  const [voidReason, setVoidReason] = useState('');
  const [voidReceipt, setVoidReceipt] = useState<VoidReceipt>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const sequence = useRef(0);

  async function load(nextStatus: InstanceStatus | '', nextTemplateId: string) {
    const call = ++sequence.current;
    try {
      const page = await client.listInstances({
        status: nextStatus || undefined,
        templateId: /^\d+$/.test(nextTemplateId) ? nextTemplateId : undefined,
      });
      if (call !== sequence.current) return;
      setRows(page.items);
      setTotal(page.total);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof Error && caught.message === 'UNAUTHENTICATED') { onAuthLost(); return; }
      setError(caught instanceof Error ? `读取失败（${caught.message}）` : '读取失败');
    }
  }

  useEffect(() => { void load('', ''); return () => { sequence.current++; }; }, [client]); // eslint-disable-line react-hooks/exhaustive-deps

  async function openDetail(instanceId: string) {
    try {
      setDetail(await client.getInstance(instanceId));
    } catch (caught) {
      setError(caught instanceof Error ? `读取失败（${caught.message}）` : '读取失败');
    }
  }

  const voidGuard = (row: AdminInstanceSummary) => () => {
    if (!canVoid) { setError('当前账号未授予 coupon.instance.void'); return; }
    setVoiding(row); setVoidConfirm(false); setVoidReason(''); setVoidReceipt(undefined);
  };

  /** §4.4 void: reason + explicit double confirm → one terminal request id per void. */
  async function submitVoid() {
    if (!voiding) return;
    const trimmed = voidReason.trim();
    const codePoints = [...trimmed].length;
    if (codePoints < 10 || codePoints > 200) { setError('作废原因必填（10–200 字）'); return; }
    if (!voidConfirm) { setError('请先勾选二次确认'); return; }
    setBusy(true);
    try {
      const receipt = await client.voidInstance(voiding.instanceId, trimmed, crypto.randomUUID());
      setVoidReceipt(receipt);
      setVoiding(undefined);
      setVoidConfirm(false);
      setVoidReason('');
      setNotice(`已作废：券实例 ${receipt.instanceId}（已作废，不可恢复，无补偿）`);
      setError('');
      await load(status, templateId);
    } catch (caught) {
      if (caught instanceof Error && caught.message === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (isCouponConflict(caught)) setError('作废冲突：券不在可作废状态（仅未使用且未被订单冻结的券可作废），或请求编号冲突');
      else setError(caught instanceof Error ? `作废失败（${caught.message}）` : '作废失败');
    } finally {
      setBusy(false);
    }
  }

  const columns: TableColumnsType<AdminInstanceSummary> = [
    { title: '实例 ID', dataIndex: 'instanceId' },
    { title: '券名称', dataIndex: 'templateName' },
    { title: '状态', render: (_, row) => <StatusTag label={STATUS_TEXT[row.status]} status={row.status} /> },
    { title: '面额', dataIndex: 'amountOff' },
    { title: '门槛', render: (_, row) => row.thresholdAmount ?? '无门槛' },
    { title: '用户', dataIndex: 'userId' },
    { title: '到期', render: (_, row) => row.expireAt.slice(0, 10) },
    { title: '订单', render: (_, row) => row.orderId ?? '—' },
    { title: '操作', render: (_, row) => <>
      {row.status === 'AVAILABLE' && <Button danger disabled={busy} onClick={voidGuard(row)}>作废</Button>}
      <Button onClick={() => void openDetail(row.instanceId)}>时间轴</Button>
    </> },
  ];
  const ledgerColumns: TableColumnsType<LedgerRow> = [
    { title: '动作', dataIndex: 'action' },
    { title: '前状态', render: (_, entry) => entry.fromStatus ?? '—' },
    { title: '后状态', dataIndex: 'toStatus' },
    { title: '订单', render: (_, entry) => entry.orderId ?? '—' },
    { title: '时间', dataIndex: 'createdAt' },
  ];

  return <PageContainer
    title="优惠券 · 券实例台账"
    hint={'券资产全生命周期记录与状态时间轴。作废渠道为最小实现：仅"可领取"状态的实例可作废（已使用/被订单冻结/已过期的券一律拒绝，被订单锁着的券不许作废），作废即时生效、不可恢复、无补偿（CCR-CPN-VOID-001）；批量作废/返还/风控冻结在 V1 无授权（CCR-ADM-A005-OPS-001）。'}>
    <form className="filter-bar" onSubmit={event => { event.preventDefault(); void load(status, templateId); }}>
      <label className="filter-item">状态
        <Select style={{ width: 160 }} value={status} onChange={value => { setStatus(value as InstanceStatus | ''); void load(value as InstanceStatus | '', templateId); }}
          options={[{ value: '', label: '全部' }, ...INSTANCE_STATUSES.map(value => ({ value, label: STATUS_TEXT[value] }))]} />
      </label>
      <label className="filter-item">模板 ID
        <Input style={{ width: 220 }} value={templateId} onChange={event => setTemplateId(event.target.value)} placeholder="Snowflake ID" inputMode="numeric" />
      </label>
      <Button type="primary" htmlType="submit">查询</Button>
      {error && <ErrorAlert>{error}</ErrorAlert>}
      {notice && <InlineStatus>{notice}</InlineStatus>}
    </form>
    {rows === undefined ? <InlineStatus>加载中</InlineStatus> : <ConfigProvider renderEmpty={() => '无实例'}>
      <DataTable columns={columns} rows={rows} rowKey={row => row.instanceId} />
    </ConfigProvider>}
    <p className="hint">共 {total} 行</p>
    {voiding && <section className="detail-card" aria-label="作废确认">
      <h3>作废：{voiding.instanceId}（{voiding.templateName}，面额 {voiding.amountOff}，用户 {voiding.userId}）</h3>
      <p className="hint">作废后该券对用户立即不可见且不可恢复，V1 无补偿路径（CCR-CPN-VOID-001）。</p>
      <form onSubmit={event => { event.preventDefault(); void submitVoid(); }}>
        <label className="filter-item">作废原因（必填，10–200 字）
          <Input.TextArea rows={3} value={voidReason} onChange={event => setVoidReason(event.target.value)} disabled={busy} />
        </label>
        <div>
          <Checkbox checked={voidConfirm}
            onChange={event => setVoidConfirm(event.target.checked)} disabled={busy}>
            我确认作废该券实例（同一人二次确认，非第二人审批）
          </Checkbox>
        </div>
        <Button danger type="primary" htmlType="submit" disabled={busy || !voidConfirm}>确认作废</Button>
        <Button disabled={busy} onClick={() => { setVoiding(undefined); setVoidConfirm(false); }}>取消</Button>
      </form>
    </section>}
    {voidReceipt && <section className="detail-card" aria-label="作废回执">
      <h3>作废回执</h3>
      <FactList items={[
        { label: '实例 ID', value: voidReceipt.instanceId },
        { label: '用户', value: voidReceipt.userId },
        { label: '状态', value: STATUS_TEXT[voidReceipt.status] },
        { label: '时间（UTC）', value: voidReceipt.occurredAt },
      ]} />
      <Button onClick={() => setVoidReceipt(undefined)}>关闭</Button>
    </section>}
    {detail && <section className="detail-card" aria-label="实例时间轴">
      <h3>状态时间轴：{detail.instanceId}（{STATUS_TEXT[detail.status]}）</h3>
      <DataTable columns={ledgerColumns} rows={detail.ledger} rowKey={entry => entry.ledgerId} />
      <Button onClick={() => setDetail(undefined)}>关闭</Button>
    </section>}
  </PageContainer>;
}
