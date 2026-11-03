import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Button, DatePicker, Input, Select } from 'antd';
import dayjs from 'dayjs';
import type { TableColumnsType } from 'antd';
import { REFUND_SOURCE_TYPES, REFUND_STATUSES, TradeQueryFailure, refundSourceLabel, refundStatusLabel, type Page, type RefundFilters, type RefundSummary, type TradeQueryClient } from '../api/tradeQuery';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

type Filters = { status: string; sourceType: string; refundType: string; orderId: string; createdFrom: string; createdTo: string };
const EMPTY: Filters = { status: '', sourceType: '', refundType: '', orderId: '', createdFrom: '', createdTo: '' };
const ID = /^[1-9][0-9]{0,18}$/;
const ISO = (value: string) => (value ? new Date(value).toISOString() : undefined);
const LOCAL = 'YYYY-MM-DDTHH:mm';

/** 运营 PRD 6.3.4 退款单管理（读侧）：统一逆向资金查询；防重复退款事实以服务端为准。 */
export default function TradeRefundListPage({ client, onAuthLost }: { client: TradeQueryClient; onAuthLost: () => void }) {
  const [params] = useSearchParams();
  const initial: Filters = { ...EMPTY, orderId: params.get('orderId') ?? '' };
  const [filters, setFilters] = useState<Filters>(initial);
  const [applied, setApplied] = useState<Filters>();
  const [result, setResult] = useState<Page<RefundSummary>>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const sequence = useRef(0);
  useEffect(() => { void query(initial, 1); return () => { sequence.current++; }; }, []);
  async function query(next: Filters, page: number) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await client.listRefunds(page, {
        status: (next.status || undefined) as RefundFilters['status'],
        sourceType: (next.sourceType || undefined) as RefundFilters['sourceType'],
        refundType: (next.refundType || undefined) as RefundFilters['refundType'],
        orderId: next.orderId || undefined,
        createdFrom: ISO(next.createdFrom),
        createdTo: ISO(next.createdTo),
      });
      if (call !== sequence.current) return;
      setResult(data);
      setApplied(next);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof TradeQueryFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (caught instanceof TradeQueryFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
      if (caught instanceof TradeQueryFailure && caught.code === 'STALE_CONTEXT') return;
      setError(caught instanceof TradeQueryFailure ? `退款单查询失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '退款单查询失败，请重试。');
    } finally { if (call === sequence.current) setLoading(false); }
  }
  function patch(key: keyof Filters, value: string) {
    setFilters(current => ({ ...current, [key]: value }));
  }
  const valid = (filters.orderId === '' || ID.test(filters.orderId)) && (!filters.createdFrom || !filters.createdTo || filters.createdFrom <= filters.createdTo);
  const columns: TableColumnsType<RefundSummary> = [
    { title: '退款单号', dataIndex: 'refundNo' },
    { title: '关联订单', dataIndex: 'orderId' },
    { title: '类型', render: (_, item) => item.refundType === 'FULL' ? '全额' : '部分' },
    { title: '来源', render: (_, item) => refundSourceLabel(item.sourceType) },
    { title: '状态', render: (_, item) => <StatusTag label={refundStatusLabel(item.status)} status={item.status} /> },
    { title: '金额', dataIndex: 'refundAmount' },
    { title: '发起方', dataIndex: 'initiatorType' },
    { title: '创建（UTC）', dataIndex: 'createdAt' },
    { title: '完成（UTC）', render: (_, item) => item.succeededAt ?? '—' },
    { title: '操作', render: (_, item) => <Link to={`/trade/refunds/${item.refundOrderId}`}>详情</Link> },
  ];
  return <PageContainer
    title="退款单管理"
    hint="平台退款单统一查询。退款以支付服务商回执为最终结果；一笔订单最多一张业务退款单。渠道查单/重试不在 V1 交付范围。">
    <form className="filter-bar" onSubmit={event => { event.preventDefault(); if (valid) void query(filters, 1); }}>
      <label className="filter-item">退款状态
        <Select style={{ width: 160 }} value={filters.status} onChange={value => patch('status', value)}
          options={[{ value: '', label: '全部' }, ...REFUND_STATUSES.map(value => ({ value, label: refundStatusLabel(value) }))]} />
      </label>
      <label className="filter-item">退款来源
        <Select style={{ width: 160 }} value={filters.sourceType} onChange={value => patch('sourceType', value)}
          options={[{ value: '', label: '全部' }, ...REFUND_SOURCE_TYPES.map(value => ({ value, label: refundSourceLabel(value) }))]} />
      </label>
      <label className="filter-item">退款类型
        <Select style={{ width: 160 }} value={filters.refundType} onChange={value => patch('refundType', value)}
          options={[{ value: '', label: '全部' }, { value: 'FULL', label: '全额退款' }, { value: 'PARTIAL', label: '部分退款' }]} />
      </label>
      <label className="filter-item">订单编号<Input style={{ width: 200 }} inputMode="numeric" value={filters.orderId} onChange={event => patch('orderId', event.target.value)} /></label>
      <label className="filter-item">创建起<DatePicker showTime format="YYYY-MM-DD HH:mm" value={filters.createdFrom ? dayjs(filters.createdFrom) : null} onChange={value => patch('createdFrom', value ? value.format(LOCAL) : '')} /></label>
      <label className="filter-item">创建止<DatePicker showTime format="YYYY-MM-DD HH:mm" value={filters.createdTo ? dayjs(filters.createdTo) : null} onChange={value => patch('createdTo', value ? value.format(LOCAL) : '')} /></label>
      <Button type="primary" htmlType="submit" disabled={loading || !valid}>查询退款单</Button>
    </form>
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {loading && <InlineStatus>正在查询退款单</InlineStatus>}
    {result && <DataTable
      columns={columns}
      rows={result.items}
      rowKey={item => item.refundOrderId}
      page={result.page}
      pageSize={result.pageSize}
      total={result.total}
      onPageChange={page => applied && void query(applied, page)}
    />}
  </PageContainer>;
}
