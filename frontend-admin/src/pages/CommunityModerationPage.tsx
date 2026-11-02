import { useEffect, useState } from 'react';
import { Button, Input, Select } from 'antd';
import type { TableColumnsType } from 'antd';
import type { CommunityAdminClient, CommunityAdminPost, CommunityAdminReport } from '../api/communityAdmin';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus, PreserveText, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

const STATUS: Record<string, string> = {
  PENDING_REVIEW: '待审核', PUBLISHED: '已发布', OFFLINE: '已下线', DELETED: '已删除',
  PENDING: '待处理', RESOLVED: '已处理', DISMISSED: '已驳回',
};
const POST_STATUS_OPTIONS = [
  { value: 'PENDING_REVIEW', label: '待审核' }, { value: 'PUBLISHED', label: '已发布' },
  { value: 'OFFLINE', label: '已下线' }, { value: 'DELETED', label: '已删除' },
];
const REPORT_STATUS_OPTIONS = [
  { value: 'PENDING', label: '待处理' }, { value: 'RESOLVED', label: '已处理' }, { value: 'DISMISSED', label: '已驳回' },
];

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

  const postColumns: TableColumnsType<CommunityAdminPost> = [
    { title: '动态', render: (_, post) => <>
      <strong>{post.title || '无标题'}</strong>
      <PreserveText><small>{post.content.slice(0, 120)}</small></PreserveText>
      {post.images.length > 0 && <small>图片 {post.images.length} 张</small>}
      {post.topic && <small> · #{post.topic.name}</small>}
    </> },
    { title: '作者', render: (_, post) => <>{post.author.nickname}<br /><small>{post.author.userId}</small></> },
    { title: '状态', render: (_, post) => <StatusTag label={STATUS[post.status] ?? post.status} status={post.status} /> },
    { title: '互动', render: (_, post) => `赞 ${post.likeCount} · 评 ${post.commentCount} · 藏 ${post.favoriteCount}` },
    { title: '时间', render: (_, post) => post.createdAt.slice(0, 10) },
    { title: '操作', render: (_, post) => <>
      {canModerate && post.status === 'PENDING_REVIEW' && <Button htmlType="button" disabled={busy} onClick={() => moderate(post, 'APPROVE')}>通过</Button>}
      {canModerate && post.status === 'PUBLISHED' && <Button danger htmlType="button" disabled={busy} onClick={() => moderate(post, 'OFFLINE')}>下线</Button>}
      {canModerate && post.status !== 'DELETED' && <Button danger htmlType="button" disabled={busy} onClick={() => moderate(post, 'DELETE')}>删除</Button>}
      <small>v{post.version}</small>
    </> },
  ];
  const reportColumns: TableColumnsType<CommunityAdminReport> = [
    { title: '对象', render: (_, report) => <>{report.targetType} {report.targetId}<br /><small>{report.targetPreview ?? ''}</small></> },
    { title: '举报人', render: (_, report) => <>{report.reporterNickname}<br /><small>{report.reporterUserId}</small></> },
    { title: '原因', render: (_, report) => <>{report.reasonCode}<br /><small>{report.reasonText ?? ''}</small></> },
    { title: '状态', render: (_, report) => <>
      <StatusTag label={STATUS[report.status] ?? report.status} status={report.status} />
      <br /><small>{report.handlingNote ?? ''}</small>
    </> },
    { title: '时间', render: (_, report) => report.createdAt.slice(0, 10) },
    { title: '操作', render: (_, report) => canHandleReports && report.status === 'PENDING' && <>
      <Button htmlType="button" disabled={busy} onClick={() => resolve(report, 'RESOLVE')}>处理</Button>
      <Button danger htmlType="button" disabled={busy} onClick={() => resolve(report, 'DISMISS')}>驳回</Button>
    </> },
  ];

  return <PageContainer
    title="社区审核"
    hint="CCR-COM-001：审核动态、下线/删除违规内容、处理举报；所有动作记录理由与审计。社区内容由用户发布，运营不代发。">
    <nav aria-label="社区审核分区">
      <Button htmlType="button" onClick={() => setTab('posts')} disabled={tab === 'posts'}>动态审核</Button>
      <Button htmlType="button" onClick={() => setTab('reports')} disabled={tab === 'reports'}>举报处理</Button>
      <form className="filter-bar" onSubmit={event => {
        event.preventDefault();
        void (tab === 'posts' ? loadPosts() : loadReports());
      }}>
        <label className="filter-item">状态
          <Select style={{ width: 160 }} aria-label="状态" value={status} onChange={value => setStatus(value)}
            options={[{ value: '', label: '全部状态' }, ...(tab === 'posts' ? POST_STATUS_OPTIONS : REPORT_STATUS_OPTIONS)]} />
        </label>
        {tab === 'posts' && <label className="filter-item">关键词
          <Input style={{ width: 200 }} placeholder="搜索标题/正文" value={keyword} maxLength={100} onChange={event => setKeyword(event.target.value)} />
        </label>}
        <Button type="primary" htmlType="submit">查询</Button>
      </form>
    </nav>
    {notice && <InlineStatus>{notice}</InlineStatus>}
    {error && <ErrorAlert>{error}</ErrorAlert>}

    {tab === 'posts' && (posts === undefined
      ? <InlineStatus>加载中</InlineStatus>
      : <>
        <DataTable columns={postColumns} rows={posts} rowKey={post => post.postId} />
        {posts.length === 0 && <p className="hint">暂无动态</p>}
      </>)}

    {tab === 'reports' && (reports === undefined
      ? <InlineStatus>加载中</InlineStatus>
      : <>
        <DataTable columns={reportColumns} rows={reports} rowKey={report => report.reportId} />
        {reports.length === 0 && <p className="hint">暂无举报</p>}
      </>)}
  </PageContainer>;
}
