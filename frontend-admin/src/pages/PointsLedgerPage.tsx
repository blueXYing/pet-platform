import { useEffect, useRef, useState } from 'react';
import { Button, ConfigProvider, Input, Select } from 'antd';
import type { TableColumnsType } from 'antd';
import { BIZ_TYPES, BIZ_TYPE_LABELS, type BizType, type LedgerAdminClient, type PointsLedgerRow } from '../api/ledgerAdmin';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

/**
 * Contract-65 积分台账页 (PRD 6.5 积分流水, HTTP10 §5.8 drafted route, A-005): read-only
 * operator ledger with user/type filters. V1 points only accrue and get clawed back
 * (SSOT §10) — the page offers no adjustment/freeze/clawback action because the wire has none.
 */
export default function PointsLedgerPage({ client, onAuthLost }: {
  client: LedgerAdminClient;
  onAuthLost: () => void;
}) {
  const [rows, setRows] = useState<PointsLedgerRow[]>();
  const [total, setTotal] = useState(0);
  const [userId, setUserId] = useState('');
  const [bizType, setBizType] = useState<BizType | ''>('');
  const [error, setError] = useState('');
  const sequence = useRef(0);

  async function load(nextUserId: string, nextBizType: BizType | '') {
    const call = ++sequence.current;
    try {
      const page = await client.listPointsLedger({
        userId: /^\d+$/.test(nextUserId) ? nextUserId : undefined,
        bizType: nextBizType || undefined,
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

  const columns: TableColumnsType<PointsLedgerRow> = [
    { title: '流水 ID', dataIndex: 'ledgerId' },
    { title: '用户', dataIndex: 'userId' },
    { title: '来源', render: (_, row) => BIZ_TYPE_LABELS[row.bizType] },
    { title: '变动', dataIndex: 'delta' },
    { title: '余额', dataIndex: 'balanceAfter' },
    { title: '订单', render: (_, row) => row.orderId ?? '—' },
    { title: '时间', dataIndex: 'createdAt' },
  ];

  return <PageContainer
    title="台账 · 积分流水"
    hint="只读台账：用户、来源、数量、余额、时间（PRD 6.5）。积分只赚取/扣回，不消费（SSOT §10）；运营手工调整在 V1 无授权路由。">
    <form className="filter-bar" onSubmit={event => { event.preventDefault(); void load(userId, bizType); }}>
      <label className="filter-item">用户 ID
        <Input style={{ width: 220 }} value={userId} onChange={event => setUserId(event.target.value)} placeholder="Snowflake ID" inputMode="numeric" />
      </label>
      <label className="filter-item">来源
        <Select style={{ width: 160 }} value={bizType} onChange={value => { setBizType(value as BizType | ''); void load(userId, value as BizType | ''); }}
          options={[{ value: '', label: '全部' }, ...BIZ_TYPES.map(value => ({ value, label: BIZ_TYPE_LABELS[value] }))]} />
      </label>
      <Button type="primary" htmlType="submit">查询</Button>
      {error && <ErrorAlert>{error}</ErrorAlert>}
    </form>
    {rows === undefined ? <InlineStatus>加载中</InlineStatus> : <ConfigProvider renderEmpty={() => '无流水'}>
      <DataTable columns={columns} rows={rows} rowKey={row => row.ledgerId} />
    </ConfigProvider>}
    <p className="hint">共 {total} 行</p>
  </PageContainer>;
}
