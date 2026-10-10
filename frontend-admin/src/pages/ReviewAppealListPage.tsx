import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { appealStatusLabel, APPEAL_STATUSES, ReviewAppealFailure, validId, type AppealPage, type AppealStatus, type ReviewAppealClient } from '../api/reviewAppeals';

const FAILURE_TEXT: Record<string, string> = {
  UNAUTHENTICATED: '登录已失效',
  FORBIDDEN: '无权访问评价申诉',
  INVALID_ARGUMENT: '筛选条件不合法',
  INVALID_RESPONSE: '服务返回异常',
  DEPENDENCY_UNAVAILABLE: '申诉数据暂时不可用',
};

/** Contract56 admin list: global appeal queue with the status filter; single ops reader. */
export default function ReviewAppealListPage({ client, canDecide, onAuthLost }: { client: ReviewAppealClient; canDecide: boolean; onAuthLost: () => void }) {
  const [status, setStatus] = useState<AppealStatus | ''>('');
  const [result, setResult] = useState<AppealPage>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const sequence = useRef(0);
  useEffect(() => { void query(1); return () => { sequence.current++; }; }, []);
  async function query(page: number, nextStatus: AppealStatus | '' = status) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await client.list({ page, pageSize: 20, ...(nextStatus ? { status: nextStatus } : {}) });
      if (call !== sequence.current) return;
      setResult(data);
      setLoading(false);
    } catch (caught) {
      if (call !== sequence.current) return;
      setLoading(false);
      if (caught instanceof ReviewAppealFailure && ['UNAUTHENTICATED', 'STALE_CONTEXT'].includes(caught.code)) { onAuthLost(); return; }
      setError(caught instanceof ReviewAppealFailure ? FAILURE_TEXT[caught.code] ?? `查询失败（${caught.code}）` : '查询失败');
    }
  }
  function patch(next: AppealStatus | '') { setStatus(next); void query(1, next); }
  const totalPages = result ? Math.max(1, Math.ceil(result.total / result.pageSize)) : 1;
  const page = result?.page ?? 1;
  const goto = (nextPage: number) => void query(nextPage);
  return <main>
    <h1>评价申诉</h1>
    <form className="filters" onSubmit={event => { event.preventDefault(); void goto(1); }}>
      <label>状态
        <select value={status} onChange={event => patch(event.target.value as AppealStatus | '')}>
          <option value="">全部</option>
          {APPEAL_STATUSES.map(item => <option key={item} value={item}>{appealStatusLabel(item)}</option>)}
        </select>
      </label>
      <button type="submit">查询</button>
    </form>
    <p className="hint">评价治理：每条评价最多申诉一次；裁决 APPROVED=评价隐藏、REJECTED=维持展示，均不可改判。{canDecide ? '单人直接裁决，无双人审批。' : '当前账号仅可查看。'}</p>
    {loading && <p role="status">加载中</p>}
    {error && <p role="alert">{error}</p>}
    {result && !loading && (result.items.length === 0
      ? <p>没有符合条件的申诉。</p>
      : <table>
        <thead><tr><th>申诉编号</th><th>评价编号</th><th>订单编号</th><th>商家/门店</th><th>状态</th><th>提交时间</th><th>裁决时间</th><th></th></tr></thead>
        <tbody>
          {result.items.map(item => <tr key={item.appealId}>
            <td>{validId(item.appealId) ? item.appealId : item.appealId}</td>
            <td>{item.reviewId}</td>
            <td>{item.orderId}</td>
            <td>{item.merchantId} / {item.storeId}</td>
            <td>{appealStatusLabel(item.status)}</td>
            <td>{item.createdAt.slice(0, 16).replace('T', ' ')}</td>
            <td>{item.decidedAt ? item.decidedAt.slice(0, 16).replace('T', ' ') : '—'}</td>
            <td><Link to={`/review-appeals/${item.appealId}`}>打开</Link></td>
          </tr>)}
        </tbody>
      </table>)}
    {result && !loading && <nav className="pager" aria-label="分页">
      <button disabled={page <= 1} onClick={() => goto(page - 1)}>上一页</button>
      <span>第 {page} / {totalPages} 页 · 共 {result.total} 条</span>
      <button disabled={page >= totalPages} onClick={() => goto(page + 1)}>下一页</button>
    </nav>}
  </main>;
}
