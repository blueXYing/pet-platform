import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Button, DatePicker, Input, Select } from 'antd';
import dayjs from 'dayjs';
import type { TableColumnsType } from 'antd';
import { TradeQueryFailure, VERIFY_METHODS, type Page, type TradeQueryClient, type VerificationFilters, type VerificationSummary } from '../api/tradeQuery';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

type Filters = { orderId: string; storeId: string; method: string; verifiedFrom: string; verifiedTo: string };
const EMPTY: Filters = { orderId: '', storeId: '', method: '', verifiedFrom: '', verifiedTo: '' };
const ID = /^[1-9][0-9]{0,18}$/;
const ISO = (value: string) => (value ? new Date(value).toISOString() : undefined);
const LOCAL = 'YYYY-MM-DDTHH:mm';

/** 运营 PRD 6.3.5 平台核销记录：核销/风控锁定事实查询，争议溯源入口；运营不得手置已完成。 */
export default function TradeVerificationListPage({ client, onAuthLost }: { client: TradeQueryClient; onAuthLost: () => void }) {
  const [params] = useSearchParams();
  const initial: Filters = { ...EMPTY, orderId: params.get('orderId') ?? '' };
  const [filters, setFilters] = useState<Filters>(initial);
  const [applied, setApplied] = useState<Filters>();
  const [result, setResult] = useState<Page<VerificationSummary>>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const sequence = useRef(0);
  useEffect(() => { void query(initial, 1); return () => { sequence.current++; }; }, []);
  async function query(next: Filters, page: number) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await client.listVerifications(page, {
        orderId: next.orderId || undefined,
        storeId: next.storeId || undefined,
        method: (next.method || undefined) as VerificationFilters['method'],
        verifiedFrom: ISO(next.verifiedFrom),
        verifiedTo: ISO(next.verifiedTo),
      });
      if (call !== sequence.current) return;
      setResult(data);
      setApplied(next);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof TradeQueryFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (caught instanceof TradeQueryFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
      if (caught instanceof TradeQueryFailure && caught.code === 'STALE_CONTEXT') return;
      setError(caught instanceof TradeQueryFailure ? `核销记录查询失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '核销记录查询失败，请重试。');
    } finally { if (call === sequence.current) setLoading(false); }
  }
  function patch(key: keyof Filters, value: string) {
    setFilters(current => ({ ...current, [key]: value }));
  }
  const valid = (filters.orderId === '' || ID.test(filters.orderId)) && (filters.storeId === '' || ID.test(filters.storeId))
    && (!filters.verifiedFrom || !filters.verifiedTo || filters.verifiedFrom <= filters.verifiedTo);
  const columns: TableColumnsType<VerificationSummary> = [
    { title: '核销流水号', dataIndex: 'verificationNo' },
    { title: '订单编号', dataIndex: 'orderId' },
    { title: '门店编号', dataIndex: 'storeId' },
    { title: '核销方式', render: (_, item) => item.verifyMethod === 'SCAN' ? '扫码' : '手动输入' },
    { title: '核销时间（UTC）', dataIndex: 'verifiedAt' },
    { title: '操作', render: (_, item) => <Link to={`/trade/verifications/${item.verificationId}`}>详情</Link> },
  ];
  return <PageContainer
    title="平台核销记录"
    hint="核销成功是订单进入已完成的唯一入口；核销与退款单互斥事实以服务端为准。凭证密文不出域。">
    <form className="filter-bar" onSubmit={event => { event.preventDefault(); if (valid) void query(filters, 1); }}>
      <label className="filter-item">订单编号<Input style={{ width: 200 }} inputMode="numeric" value={filters.orderId} onChange={event => patch('orderId', event.target.value)} /></label>
      <label className="filter-item">门店编号<Input style={{ width: 160 }} inputMode="numeric" value={filters.storeId} onChange={event => patch('storeId', event.target.value)} /></label>
      <label className="filter-item">核销方式
        <Select style={{ width: 160 }} value={filters.method} onChange={value => patch('method', value)}
          options={[{ value: '', label: '全部' }, ...VERIFY_METHODS.map(value => ({ value, label: value === 'SCAN' ? '扫码' : '手动输入' }))]} />
      </label>
      <label className="filter-item">核销起（UTC）<DatePicker showTime format="YYYY-MM-DD HH:mm" value={filters.verifiedFrom ? dayjs(filters.verifiedFrom) : null} onChange={value => patch('verifiedFrom', value ? value.format(LOCAL) : '')} /></label>
      <label className="filter-item">核销止（UTC）<DatePicker showTime format="YYYY-MM-DD HH:mm" value={filters.verifiedTo ? dayjs(filters.verifiedTo) : null} onChange={value => patch('verifiedTo', value ? value.format(LOCAL) : '')} /></label>
      <Button type="primary" htmlType="submit" disabled={loading || !valid}>查询核销记录</Button>
    </form>
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {loading && <InlineStatus>正在查询核销记录</InlineStatus>}
    {result && <DataTable
      columns={columns}
      rows={result.items}
      rowKey={item => item.verificationId}
      page={result.page}
      pageSize={result.pageSize}
      total={result.total}
      onPageChange={page => applied && void query(applied, page)}
    />}
  </PageContainer>;
}
