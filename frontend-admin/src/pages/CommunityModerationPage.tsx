import { useEffect, useState } from 'react';
import type { CommunityAdminClient, CommunityAdminPost, CommunityAdminReport } from '../api/communityAdmin';

const STATUS: Record<string, string> = {
  PENDING_REVIEW: '待审核', PUBLISHED: '已发布', OFFLINE: '已下线', DELETED: '已删除',
  PENDING: '待处理', RESOLVED: '已处理', DISMISSED: '已驳回',
};

export default function CommunityModerationPage({ client, canModerate, canHandleReports, onAuthLost }: {
  client: CommunityAdminClient;
  canModerate: boolean;
  canHandleReports: boolean;
  onAuthLost: () => void;
}) {
  const [tab, setTab] = useState<'posts' | 'reports'>('posts');
  const [posts, setPosts] = useState<CommunityAdminPost[]>();
  const [reports, setReports] = useState<CommunityAdminReport[]>();
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');

  function fail(caught: unknown, fallback: string) {
    if (caught instanceof Error && caught.message === 'UNAUTHENTICATED') { onAuthLost(); return; }
    setError(caught instanceof Error ? `${fallback}（${caught.message}）` : fallback);
  }
  async function loadPosts() {
    try { setPosts((await client.listPosts({ status: status || undefined, keyword: keyword || undefined })).items); }
    catch (caught) { fail(caught, '读取动态失败'); }
  }
  async function loadReports() {
    const next = ['PENDING', 'RESOLVED', 'DISMISSED'].includes(status) ? status : '';
    try { setReports((await client.listReports({ status: next || undefined })).items); }
    catch (caught) { fail(caught, '读取举报失败'); }
  }
  async function run(action: () => Promise<unknown>, ok: string) {
    setBusy(true); setError(''); setNotice('');
    try { await action(); setNotice(ok); await Promise.all([loadPosts(), loadReports()]); }
    catch (caught) { fail(caught, '操作失败'); }
    finally { setBusy(false); }
  }

  useEffect(() => { void Promise.all([loadPosts(), loadReports()]); }, [client]); // eslint-disable-line react-hooks/exhaustive-deps

  function moderate(post: CommunityAdminPost, decision: 'APPROVE' | 'OFFLINE' | 'DELETE') {
    const reason = decision === 'APPROVE' ? undefined : window.prompt(decision === 'OFFLINE' ? '下线原因（必填）' : '删除原因（必填）') ?? undefined;
    if (decision !== 'APPROVE' && !reason) return;
    void run(() => client.moderatePost(post.postId, decision, reason, post.version),
      decision === 'APPROVE' ? '已通过审核' : decision === 'OFFLINE' ? '已下线' : '已删除');
  }
  function resolve(report: CommunityAdminReport, decision: 'RESOLVE' | 'DISMISS') {
    const note = window.prompt(decision === 'RESOLVE' ? '处理说明（必填）' : '驳回说明（必填）') ?? '';
    if (!note.trim()) return;
    void run(() => client.resolveReport(report.reportId, decision, note.trim()),
      decision === 'RESOLVE' ? '举报已处理' : '举报已驳回');
  }

  return <main className="page">
    <h2>社区审核</h2>
    <p className="hint">CCR-COM-001：审核动态、下线/删除违规内容、处理举报；所有动作记录理由与审计。社区内容由用户发布，运营不代发。</p>
    <nav className="filters" aria-label="社区审核分区">
      <button type="button" onClick={() => setTab('posts')} disabled={tab === 'posts'}>动态审核</button>
      <button type="button" onClick={() => setTab('reports')} disabled={tab === 'reports'}>举报处理</button>
      <select value={status} onChange={event => setStatus(event.target.value)}>
        <option value="">全部状态</option>
        {tab === 'posts' ? <>
          <option value="PENDING_REVIEW">待审核</option><option value="PUBLISHED">已发布</option>
          <option value="OFFLINE">已下线</option><option value="DELETED">已删除</option>
        </> : <><option value="PENDING">待处理</option><option value="RESOLVED">已处理</option><option value="DISMISSED">已驳回</option></>}
      </select>
      {tab === 'posts' && <input placeholder="搜索标题/正文" value={keyword} maxLength={100} onChange={event => setKeyword(event.target.value)} />}
      <button type="button" onClick={() => void (tab === 'posts' ? loadPosts() : loadReports())}>查询</button>
      {notice && <output role="status">{notice}</output>}
      {error && <span role="alert">{error}</span>}
    </nav>

    {tab === 'posts' && (posts === undefined ? <p role="status">加载中</p> : <table>
      <thead><tr><th>动态</th><th>作者</th><th>状态</th><th>互动</th><th>时间</th><th>操作</th></tr></thead>
      <tbody>{posts.map(post => <tr key={post.postId}>
        <td><strong>{post.title || '无标题'}</strong><br /><small>{post.content.slice(0, 120)}</small><br />
          {post.images.length > 0 && <small>图片 {post.images.length} 张</small>}
          {post.topic && <small> · #{post.topic.name}</small>}
        </td>
        <td>{post.author.nickname}<br /><small>{post.author.userId}</small></td>
        <td>{STATUS[post.status] ?? post.status}</td>
        <td>赞 {post.likeCount} · 评 {post.commentCount} · 藏 {post.favoriteCount}</td>
        <td>{post.createdAt.slice(0, 10)}</td>
        <td>
          {canModerate && post.status === 'PENDING_REVIEW' && <button type="button" disabled={busy} onClick={() => moderate(post, 'APPROVE')}>通过</button>}
          {canModerate && post.status === 'PUBLISHED' && <button type="button" disabled={busy} onClick={() => moderate(post, 'OFFLINE')}>下线</button>}
          {canModerate && post.status !== 'DELETED' && <button type="button" disabled={busy} onClick={() => moderate(post, 'DELETE')}>删除</button>}
          <small>v{post.version}</small>
        </td>
      </tr>)}{posts.length === 0 && <tr><td colSpan={6}>暂无动态</td></tr>}</tbody>
    </table>)}

    {tab === 'reports' && (reports === undefined ? <p role="status">加载中</p> : <table>
      <thead><tr><th>对象</th><th>举报人</th><th>原因</th><th>状态</th><th>时间</th><th>操作</th></tr></thead>
      <tbody>{reports.map(report => <tr key={report.reportId}>
        <td>{report.targetType} {report.targetId}<br /><small>{report.targetPreview ?? ''}</small></td>
        <td>{report.reporterNickname}<br /><small>{report.reporterUserId}</small></td>
        <td>{report.reasonCode}<br /><small>{report.reasonText ?? ''}</small></td>
        <td>{STATUS[report.status] ?? report.status}<br /><small>{report.handlingNote ?? ''}</small></td>
        <td>{report.createdAt.slice(0, 10)}</td>
        <td>{canHandleReports && report.status === 'PENDING' && <>
          <button type="button" disabled={busy} onClick={() => resolve(report, 'RESOLVE')}>处理</button>
          <button type="button" disabled={busy} onClick={() => resolve(report, 'DISMISS')}>驳回</button>
        </>}</td>
      </tr>)}{reports.length === 0 && <tr><td colSpan={6}>暂无举报</td></tr>}</tbody>
    </table>)}
  </main>;
}
