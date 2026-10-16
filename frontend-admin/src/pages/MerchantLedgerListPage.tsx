import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  MERCHANT_STATUSES, STORE_STATUSES, merchantStatusLabel, signingLabel,
  type MerchantGovernanceClient, type MerchantLedgerFilters, type MerchantLedgerSummary,
} from '../api/merchantGovernance';
import { GovernanceFailure, governanceFailureText, type Page } from '../api/governanceHttp';

type Filters = {
  status: string; storeStatus: string; cityCode: string; merchantTypeCode: string;
  keyword: string; createdFrom: string; createdTo: string;
};
const EMPTY: Filters = { status: '', storeStatus: '', cityCode: '', merchantTypeCode: '', keyword: '', createdFrom: '', createdTo: '' };
const UTC_LOCAL = (value: string) => (value ? new Date(value).toISOString() : undefined);

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
  const pages = result ? Math.min(10000, Math.max(1, Math.ceil(result.total / result.pageSize))) : 1;
  return <main className="aftersale-page">
    <h1>商家台账</h1>
    <p className="hint">运营 PRD 6.2.2：商家档案巡检与咨询应答入口（只读）。手机号与子商户号仅脱敏呈现；冻结/下线处置不在本页（HTTP10 §5.6 另切片）。</p>
    <form className="filters" onSubmit={event => { event.preventDefault(); if (valid) void query(filters, 1); }}>
      <label>商家状态
        <select value={filters.status} onChange={event => patch('status', event.target.value)}>
          <option value="">全部</option>
          {MERCHANT_STATUSES.map(value => <option key={value} value={value}>{merchantStatusLabel(value)}</option>)}
        </select>
      </label>
      <label>门店状态
        <select value={filters.storeStatus} onChange={event => patch('storeStatus', event.target.value)}>
          <option value="">全部</option>
          {STORE_STATUSES.map(value => <option key={value} value={value}>{merchantStatusLabel(value)}</option>)}
        </select>
      </label>
      <label>城市<input value={filters.cityCode} onChange={event => patch('cityCode', event.target.value)} /></label>
      <label>商家类型<input value={filters.merchantTypeCode} onChange={event => patch('merchantTypeCode', event.target.value)} /></label>
      <label>关键词（商家/门店名）<input value={filters.keyword} onChange={event => patch('keyword', event.target.value)} /></label>
      <label>入驻起<input type="datetime-local" value={filters.createdFrom} onChange={event => patch('createdFrom', event.target.value)} /></label>
      <label>入驻止<input type="datetime-local" value={filters.createdTo} onChange={event => patch('createdTo', event.target.value)} /></label>
      <button disabled={loading || !valid}>查询商家</button>
    </form>
    {error && <p role="alert">{error}</p>}
    {loading && <p role="status">正在查询商家台账</p>}
    {result && <>
      <div className="table-scroll"><table>
        <thead><tr><th>商家 ID</th><th>名称</th><th>状态</th><th>类型</th><th>城市</th><th>门店（经营/总数）</th><th>申请状态</th><th>签约</th><th>子商户号</th><th>入驻（UTC）</th><th>操作</th></tr></thead>
        <tbody>{result.items.map(item => <tr key={item.merchantId}>
          <td>{item.merchantId}</td>
          <td>{item.merchantName}</td>
          <td>{merchantStatusLabel(item.status)}</td>
          <td>{item.merchantTypeCode ?? '—'}</td>
          <td>{item.cityCode ?? '—'}</td>
          <td>{item.activeStoreCount} / {item.storeCount}</td>
          <td>{applicationText(item)}</td>
          <td>{signingLabel(item.signingStatus)}</td>
          <td>{item.providerMerchantNoMasked ?? '—'}</td>
          <td>{item.createdAt}</td>
          <td><Link to={`/governance/merchants/${item.merchantId}`}>详情</Link></td>
        </tr>)}</tbody>
      </table></div>
      <nav className="pager" aria-label="商家台账分页">
        <button disabled={loading || result.page <= 1} onClick={() => void query(filters, result.page - 1)}>上一页</button>
        <span>第 {result.page} / {pages} 页 · 共 {result.total} 条</span>
        <button disabled={loading || result.page >= pages} onClick={() => void query(filters, result.page + 1)}>下一页</button>
      </nav>
    </>}
  </main>;
}

function applicationText(item: MerchantLedgerSummary) {
  if (!item.applicationStatus) return '—';
  return ({
    DRAFT: '草稿', REVIEWING: '审核中', APPROVED: '已通过', REJECTED: '已驳回',
  } as Record<string, string>)[item.applicationStatus] ?? item.applicationStatus;
}
