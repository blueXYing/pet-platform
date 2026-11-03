import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, Checkbox, DatePicker, Input, Select } from 'antd';
import dayjs from 'dayjs';
import type { TableColumnsType } from 'antd';
import { DISPLAY_STATUSES, PAYMENT_STATUSES, TradeQueryFailure, displayStatusLabel, field, type DisplayStatus, type OrderSummary, type Page, type PaymentStatus, type TradeQueryClient } from '../api/tradeQuery';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

type Filters = {
  displayStatus: string; paymentStatus: string; merchantId: string; storeId: string;
  orderNo: string; keyword: string; createdFrom: string; createdTo: string; rescheduledOnly: boolean;
};
const EMPTY: Filters = { displayStatus: '', paymentStatus: '', merchantId: '', storeId: '', orderNo: '', keyword: '', createdFrom: '', createdTo: '', rescheduledOnly: false };
const ID = /^[1-9][0-9]{0,18}$/;
const ISO = (value: string) => (value ? new Date(value).toISOString() : undefined);
const LOCAL = 'YYYY-MM-DDTHH:mm';

/** 运营 PRD 6.3.1 订单总览：平台统一查询视图；金额/状态只读，服务端投影展示态。 */
export default function TradeOrderListPage({ client, onAuthLost }: { client: TradeQueryClient; onAuthLost: () => void }) {
  const [filters, setFilters] = useState<Filters>(EMPTY);
  const [result, setResult] = useState<Page<OrderSummary>>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const sequence = useRef(0);
  useEffect(() => { void query(EMPTY, 1); return () => { sequence.current++; }; }, []);
  function toApi(next: Filters) {
    const { displayStatus: ds, paymentStatus: ps } = next;
    return {
      displayStatus: (ds || undefined) as DisplayStatus | undefined,
      paymentStatus: (ps || undefined) as PaymentStatus | undefined,
      merchantId: next.merchantId || undefined,
      storeId: next.storeId || undefined,
      orderNo: next.orderNo || undefined,
      keyword: next.keyword || undefined,
      createdFrom: ISO(next.createdFrom),
      createdTo: ISO(next.createdTo),
      rescheduledOnly: next.rescheduledOnly || undefined,
    };
  }
  async function query(next: Filters, page: number) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await client.listOrders(page, toApi(next));
      if (call !== sequence.current) return;
      setResult(data);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof TradeQueryFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (caught instanceof TradeQueryFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
      if (caught instanceof TradeQueryFailure && caught.code === 'STALE_CONTEXT') return;
      setError(caught instanceof TradeQueryFailure ? `订单查询失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '订单查询失败，请重试。');
    } finally { if (call === sequence.current) setLoading(false); }
  }
  function patch(key: keyof Filters, value: string | boolean) {
    setFilters(current => ({ ...current, [key]: value }));
  }
  const idOk = (value: string) => value === '' || ID.test(value);
  const keywordOk = filters.keyword === '' || (filters.keyword.length >= 2 && filters.keyword.length <= 64 && !/[%_\\]/.test(filters.keyword));
  const valid = idOk(filters.merchantId) && idOk(filters.storeId) && idOk(filters.orderNo) && keywordOk
    && (!filters.createdFrom || !filters.createdTo || filters.createdFrom <= filters.createdTo);
  const { displayStatus: selectedDisplay, paymentStatus: selectedPayment } = filters;
  const columns: TableColumnsType<OrderSummary> = [
    { title: '订单号', dataIndex: 'orderNo' },
    { title: '商家', render: (_, item) => item.merchantName ?? item.merchantId },
    { title: '服务', render: (_, item) => item.serviceName ?? item.serviceId },
    { title: '展示状态', render: (_, item) => <StatusTag label={displayStatusLabel(field(item, 'displayStatus'))} status={field(item, 'displayStatus')} /> },
    { title: '支付', render: (_, item) => field(item, 'paymentStatus') === 'PAID' ? '已支付' : '未支付' },
    { title: '实付', dataIndex: 'payAmount' },
    { title: '已退', dataIndex: 'refundedAmount' },
    { title: '预约开始（UTC）', dataIndex: 'appointmentStart' },
    { title: '创建（UTC）', dataIndex: 'createdAt' },
    { title: '改期', dataIndex: 'rescheduleCount' },
    { title: '操作', render: (_, item) => <Link to={`/trade/orders/${item.orderId}`}>详情</Link> },
  ];
  return <PageContainer
    title="订单总览"
    hint="平台订单统一查询。展示状态由服务端统一计算；金额只读；不回传用户手机号等敏感明文。">
    <form className="filter-bar" onSubmit={event => { event.preventDefault(); if (valid) void query(filters, 1); }}>
      <label className="filter-item">展示状态
        <Select style={{ width: 160 }} value={selectedDisplay} onChange={value => patch('displayStatus', value)}
          options={[{ value: '', label: '全部' }, ...DISPLAY_STATUSES.map(value => ({ value, label: displayStatusLabel(value) }))]} />
      </label>
      <label className="filter-item">支付状态
        <Select style={{ width: 160 }} value={selectedPayment} onChange={value => patch('paymentStatus', value)}
          options={[{ value: '', label: '全部' }, ...PAYMENT_STATUSES.map(value => ({ value, label: value === 'INIT' ? '未支付' : '已支付' }))]} />
      </label>
      <label className="filter-item">商家编号<Input style={{ width: 160 }} inputMode="numeric" value={filters.merchantId} onChange={event => patch('merchantId', event.target.value)} /></label>
      <label className="filter-item">门店编号<Input style={{ width: 160 }} inputMode="numeric" value={filters.storeId} onChange={event => patch('storeId', event.target.value)} /></label>
      <label className="filter-item">订单号<Input style={{ width: 200 }} inputMode="numeric" value={filters.orderNo} onChange={event => patch('orderNo', event.target.value)} /></label>
      <label className="filter-item">关键词（商家名）<Input style={{ width: 200 }} value={filters.keyword} onChange={event => patch('keyword', event.target.value)} /></label>
      <label className="filter-item">创建起<DatePicker showTime format="YYYY-MM-DD HH:mm" value={filters.createdFrom ? dayjs(filters.createdFrom) : null} onChange={value => patch('createdFrom', value ? value.format(LOCAL) : '')} /></label>
      <label className="filter-item">创建止<DatePicker showTime format="YYYY-MM-DD HH:mm" value={filters.createdTo ? dayjs(filters.createdTo) : null} onChange={value => patch('createdTo', value ? value.format(LOCAL) : '')} /></label>
      <div className="filter-item"><Checkbox checked={filters.rescheduledOnly} onChange={event => patch('rescheduledOnly', event.target.checked)}>仅看已改期</Checkbox></div>
      <Button type="primary" htmlType="submit" disabled={loading || !valid}>查询订单</Button>
    </form>
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {loading && <InlineStatus>正在查询订单</InlineStatus>}
    {result && <DataTable
      columns={columns}
      rows={result.items}
      rowKey={item => item.orderId}
      page={result.page}
      pageSize={result.pageSize}
      total={result.total}
      onPageChange={page => void query(filters, page)}
    />}
  </PageContainer>;
}
