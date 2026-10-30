import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Button, Input, Select } from 'antd';
import type { TableColumnsType } from 'antd';
import { AfterSaleFailure, CASE_STATUSES, caseStatusLabel, validId, type AfterSaleClient, type CasePage, type CaseStatus, type CaseSummary } from '../api/aftersales';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

type Filters = { merchantId: string; storeId: string; status: string; orderId: string };
export default function AfterSaleListPage({ client, onAuthLost }: { client: AfterSaleClient; onAuthLost: () => void }) {
  const [params, setParams] = useSearchParams();
  const initial: Filters = { merchantId: params.get('merchantId') ?? '', storeId: params.get('storeId') ?? '', status: '', orderId: '' };
  const [filters, setFilters] = useState(initial);
  const [applied, setApplied] = useState<Filters>();
  const [result, setResult] = useState<CasePage>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const sequence = useRef(0);
  useEffect(() => {
    if (validId(initial.merchantId) && validId(initial.storeId)) void query(initial, 1);
    return () => { sequence.current++; };
    // Only the explicit submit selects a new store; partial input never reads.
  }, []);
  async function query(next: Filters, page: number) {
    const call = ++sequence.current;
    setResult(undefined);
    setError('');
    if (!validId(next.merchantId) || !validId(next.storeId) || next.orderId !== '' && !validId(next.orderId)) { setError('请填写有效的商家编号、门店编号及可选订单编号。'); setLoading(false); return; }
    if (applied && (next.merchantId !== applied.merchantId || next.storeId !== applied.storeId)) client.changeScope();
    setApplied({ ...next });
    setParams({ merchantId: next.merchantId, storeId: next.storeId }, { replace: true });
    setLoading(true);
    try {
      const data = await client.list({ merchantId: next.merchantId, storeId: next.storeId, page, pageSize: 20, ...(next.status ? { status: next.status as CaseStatus } : {}), ...(next.orderId ? { orderId: next.orderId } : {}) });
      if (call !== sequence.current) return;
      setResult(data);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof AfterSaleFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (caught instanceof AfterSaleFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
      if (caught instanceof AfterSaleFailure && caught.code === 'STALE_CONTEXT') return;
      setError(caught instanceof AfterSaleFailure ? `售后查询失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '售后查询失败，请重试。');
    } finally { if (call === sequence.current) setLoading(false); }
  }
  function patch(key: keyof Filters, value: string) {
    setFilters(current => ({ ...current, [key]: value }));
    if (key === 'merchantId' || key === 'storeId') {
      // Hide the previous store immediately, including during partially typed changes.
      sequence.current++; setResult(undefined); setLoading(false); setError('');
      client.changeScope();
    }
  }
  const columns: TableColumnsType<CaseSummary> = [
    { title: '工单编号', dataIndex: 'afterSaleId' },
    { title: '订单编号', dataIndex: 'orderId' },
    { title: '状态', render: (_, item) => <StatusTag label={caseStatusLabel(item.status)} status={item.status} /> },
    { title: '问题 / 诉求代码', render: (_, item) => `${item.typeCode} / ${item.demandCode}` },
    { title: '诉求金额', render: (_, item) => item.requestedAmount ?? '未指定' },
    { title: '创建时间（UTC）', dataIndex: 'createdAt' },
    { title: '申请截止（UTC）', dataIndex: 'deadline' },
    { title: '操作', render: (_, item) => <Link to={`/aftersales/${item.afterSaleId}?merchantId=${item.merchantId}&storeId=${item.storeId}`}>查看工单</Link> },
  ];
  return <PageContainer
    title="售后工单"
    hint="指定商家与门店后查询当前获权范围。输入编号仅缩小查询，服务端会复核门店归属及资源权限。">
    <form className="filter-bar" onSubmit={event => { event.preventDefault(); void query(filters, 1); }}>
      <label className="filter-item">商家编号<Input style={{ width: 160 }} inputMode="numeric" value={filters.merchantId} onChange={event => patch('merchantId', event.target.value)} /></label>
      <label className="filter-item">门店编号<Input style={{ width: 160 }} inputMode="numeric" value={filters.storeId} onChange={event => patch('storeId', event.target.value)} /></label>
      <label className="filter-item">工单状态
        <Select style={{ width: 160 }} value={filters.status} onChange={value => patch('status', value)}
          options={[{ value: '', label: '全部' }, ...CASE_STATUSES.map(value => ({ value, label: caseStatusLabel(value) }))]} />
      </label>
      <label className="filter-item">订单编号<Input style={{ width: 200 }} inputMode="numeric" value={filters.orderId} onChange={event => patch('orderId', event.target.value)} /></label>
      <Button type="primary" htmlType="submit" disabled={loading}>查询售后</Button>
    </form>
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {loading && <InlineStatus>正在查询售后工单</InlineStatus>}
    {!applied && <p>请先指定商家和门店。</p>}
    {result && <>
      <p>商家 {applied?.merchantId} · 门店 {applied?.storeId}</p>
      {result.items.length === 0 ? <p>该门店没有符合条件的售后工单。</p> : <DataTable
        columns={columns}
        rows={result.items}
        rowKey={item => item.afterSaleId}
        page={result.page}
        pageSize={result.pageSize}
        total={result.total}
        onPageChange={page => applied && void query(applied, page)}
      />}
    </>}
  </PageContainer>;
}
