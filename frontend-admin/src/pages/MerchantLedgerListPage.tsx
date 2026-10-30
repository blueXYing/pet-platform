import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, DatePicker, Input, Select } from 'antd';
import dayjs from 'dayjs';
import type { TableColumnsType } from 'antd';
import {
  MERCHANT_STATUSES, STORE_STATUSES, merchantStatusLabel, signingLabel,
  type MerchantGovernanceClient, type MerchantLedgerFilters, type MerchantLedgerSummary,
} from '../api/merchantGovernance';
import { GovernanceFailure, governanceFailureText, type Page } from '../api/governanceHttp';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

type Filters = {
  status: string; storeStatus: string; cityCode: string; merchantTypeCode: string;
  keyword: string; createdFrom: string; createdTo: string;
};
const EMPTY: Filters = { status: '', storeStatus: '', cityCode: '', merchantTypeCode: '', keyword: '', createdFrom: '', createdTo: '' };
const UTC_LOCAL = (value: string) => (value ? new Date(value).toISOString() : undefined);
const LOCAL = 'YYYY-MM-DDTHH:mm';

/** 运营 PRD 6.2.2 商家台账：全量商家统一档案视图（只读）；四层状态与签约事实均为服务端投影。 */
export default function MerchantLedgerListPage({ client, onAuthLost }: { client: MerchantGovernanceClient; onAuthLost: () => void }) {
  const [filters, setFilters] = useState<Filters>(EMPTY);
  const [result, setResult] = useState<Page<MerchantLedgerSummary>>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const sequence = useRef(0);
  useEffect(() => { void query(EMPTY, 1); return () => { sequence.current++; }; }, []);
  function toApi(next: Filters): MerchantLedgerFilters {
    return {
      status: (next.status || undefined) as MerchantLedgerFilters['status'],
      storeStatus: (next.storeStatus || undefined) as MerchantLedgerFilters['storeStatus'],
      cityCode: next.cityCode || undefined,
      merchantTypeCode: next.merchantTypeCode || undefined,
      keyword: next.keyword || undefined,
      createdFrom: UTC_LOCAL(next.createdFrom),
      createdTo: UTC_LOCAL(next.createdTo),
    };
  }
  async function query(next: Filters, page: number) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await client.listMerchants(page, toApi(next));
      if (call !== sequence.current) return;
      setResult(data);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof GovernanceFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (caught instanceof GovernanceFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
      if (caught instanceof GovernanceFailure && caught.code === 'STALE_CONTEXT') return;
      setError(governanceFailureText(caught, '商家台账查询失败'));
    } finally { if (call === sequence.current) setLoading(false); }
  }
  function patch(key: keyof Filters, value: string) {
    setFilters(current => ({ ...current, [key]: value }));
  }
  const keywordOk = filters.keyword === '' || (filters.keyword.length >= 2 && filters.keyword.length <= 64 && !/[%_\\]/.test(filters.keyword));
  const rangeOk = (!filters.createdFrom && !filters.createdTo)
    || (filters.createdFrom && filters.createdTo && filters.createdFrom <= filters.createdTo);
  const valid = keywordOk && rangeOk;
  const columns: TableColumnsType<MerchantLedgerSummary> = [
    { title: '商家 ID', dataIndex: 'merchantId' },
    { title: '名称', dataIndex: 'merchantName' },
    { title: '状态', render: (_, item) => <StatusTag label={merchantStatusLabel(item.status)} status={item.status} /> },
    { title: '类型', render: (_, item) => item.merchantTypeCode ?? '—' },
    { title: '城市', render: (_, item) => item.cityCode ?? '—' },
    { title: '门店（经营/总数）', render: (_, item) => `${item.activeStoreCount} / ${item.storeCount}` },
    { title: '申请状态', render: (_, item) => applicationText(item) },
    { title: '签约', render: (_, item) => signingLabel(item.signingStatus) },
    { title: '子商户号', render: (_, item) => item.providerMerchantNoMasked ?? '—' },
    { title: '入驻（UTC）', dataIndex: 'createdAt' },
    { title: '操作', render: (_, item) => <Link to={`/governance/merchants/${item.merchantId}`}>详情</Link> },
  ];
  return <PageContainer
    title="商家台账"
    hint="运营 PRD 6.2.2：商家档案巡检与咨询应答入口（只读）。手机号与子商户号仅脱敏呈现；冻结/下线处置不在本页（HTTP10 §5.6 另切片）。">
    <form className="filter-bar" onSubmit={event => { event.preventDefault(); if (valid) void query(filters, 1); }}>
      <label className="filter-item">商家状态
        <Select style={{ width: 160 }} value={filters.status} onChange={value => patch('status', value)}
          options={[{ value: '', label: '全部' }, ...MERCHANT_STATUSES.map(value => ({ value, label: merchantStatusLabel(value) }))]} />
      </label>
      <label className="filter-item">门店状态
        <Select style={{ width: 160 }} value={filters.storeStatus} onChange={value => patch('storeStatus', value)}
          options={[{ value: '', label: '全部' }, ...STORE_STATUSES.map(value => ({ value, label: merchantStatusLabel(value) }))]} />
      </label>
      <label className="filter-item">城市<Input style={{ width: 160 }} value={filters.cityCode} onChange={event => patch('cityCode', event.target.value)} /></label>
      <label className="filter-item">商家类型<Input style={{ width: 160 }} value={filters.merchantTypeCode} onChange={event => patch('merchantTypeCode', event.target.value)} /></label>
      <label className="filter-item">关键词（商家/门店名）<Input style={{ width: 200 }} value={filters.keyword} onChange={event => patch('keyword', event.target.value)} /></label>
      <label className="filter-item">入驻起<DatePicker showTime format="YYYY-MM-DD HH:mm" value={filters.createdFrom ? dayjs(filters.createdFrom) : null} onChange={value => patch('createdFrom', value ? value.format(LOCAL) : '')} /></label>
      <label className="filter-item">入驻止<DatePicker showTime format="YYYY-MM-DD HH:mm" value={filters.createdTo ? dayjs(filters.createdTo) : null} onChange={value => patch('createdTo', value ? value.format(LOCAL) : '')} /></label>
      <Button type="primary" htmlType="submit" disabled={loading || !valid}>查询商家</Button>
    </form>
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {loading && <InlineStatus>正在查询商家台账</InlineStatus>}
    {result && <DataTable
      columns={columns}
      rows={result.items}
      rowKey={item => item.merchantId}
      page={result.page}
      pageSize={result.pageSize}
      total={result.total}
      onPageChange={page => void query(filters, page)}
    />}
  </PageContainer>;
}

function applicationText(item: MerchantLedgerSummary) {
  if (!item.applicationStatus) return '—';
  return ({
    DRAFT: '草稿', REVIEWING: '审核中', APPROVED: '已通过', REJECTED: '已驳回',
  } as Record<string, string>)[item.applicationStatus] ?? item.applicationStatus;
}
