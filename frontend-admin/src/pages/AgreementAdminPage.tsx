import { useEffect, useRef, useState } from 'react';
import { Button, Input, Tag } from 'antd';
import { AgreementMarkdownPreview } from './agreement-markdown';
import type { TableColumnsType } from 'antd';
import { createWebClient, RequestFailure, type Transport } from '../request';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

/** 2026-11-20 用户裁决：运营端协议管理——纯文本协议编辑/发布/版本历史。
 *  版本链松耦合：发布即生效（小程序实时读到新版），存量签约不受影响，
 *  在途签署以盖章时刻的当前版本为准，不会因版本漂移被拒。 */
type AgreementVersion = {
  versionId: string;
  agreementVersion: string;
  content: string;
  contentSha256: string;
  publishedAt: string | null;
  publishedByOperatorId: string | null;
  current: boolean;
};

export function createAgreementAdminClient(transport: Transport = fetch) {
  const base = createWebClient(transport);
  return {
    list: () => base.request<{ items: AgreementVersion[]; total: number }>('/api/v1/admin/merchant-agreements'),
    publish: (content: string, requestId: string) =>
      base.request<{ versionId: string; agreementVersion: string; contentSha256: string }>(
        '/api/v1/admin/merchant-agreements',
        { method: 'POST', body: { content }, requestId }),
  };
}

export type AgreementAdminClient = ReturnType<typeof createAgreementAdminClient>;

export default function AgreementAdminPage({ client, canWrite, onAuthLost }: {
  client: AgreementAdminClient;
  canWrite: boolean;
  onAuthLost: () => void;
}) {
  const [versions, setVersions] = useState<AgreementVersion[]>();
  const [draft, setDraft] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [previewing, setPreviewing] = useState(false);
  const sequence = useRef(0);

  function load() {
    const call = ++sequence.current;
    void (async () => {
      try {
        const data = await client.list();
        if (call !== sequence.current) return;
        setVersions(data.items);
      } catch (caught) {
        if (call !== sequence.current) return;
        if (caught instanceof RequestFailure && (caught.code === 'UNAUTHENTICATED' || caught.code === 'STALE_CONTEXT')) { onAuthLost(); return; }
        setError(caught instanceof RequestFailure ? `读取失败（${caught.serverMessage ?? caught.code}）` : '读取失败');
      }
    })();
  }

  useEffect(() => { load(); return () => { sequence.current++; }; }, []);

  async function publish() {
    setError('');
    setNotice('');
    if (draft.trim().length < 10) {
      setError('协议内容至少 10 个字');
      return;
    }
    setBusy(true);
    try {
      const result = await client.publish(draft.trim(), crypto.randomUUID());
      setNotice(`已发布并生效：版本 ${result.agreementVersion}（小程序端实时读到新版）`);
      setDraft('');
      load();
    } catch (caught) {
      if (caught instanceof RequestFailure && (caught.code === 'UNAUTHENTICATED' || caught.code === 'STALE_CONTEXT')) { onAuthLost(); return; }
      setError(caught instanceof RequestFailure
        ? `发布失败（${caught.serverMessage ?? caught.code}）`
        : '发布失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  const current = versions?.find(v => v.current);
  const columns: TableColumnsType<AgreementVersion> = [
    { title: '版本号', dataIndex: 'agreementVersion', key: 'agreementVersion', render: (v, row) => (
      <span>{v} {row.current ? <Tag color="green" style={{ marginInlineStart: 6 }}>当前生效</Tag> : null}</span>
    )},
    { title: '内容摘要', dataIndex: 'content', key: 'content', ellipsis: true, render: (v: string) => (
      <span title={v}>{v.length > 60 ? v.slice(0, 60) + '…' : v}</span>
    )},
    { title: '发布时间', dataIndex: 'publishedAt', key: 'publishedAt', render: (v: string | null) => v ? v.replace('T', ' ').slice(0, 19) : '—' },
    { title: '内容哈希(前12位)', dataIndex: 'contentSha256', key: 'contentSha256', render: (v: string) => v.slice(0, 12) + '…' },
    { title: '操作', key: 'actions', render: (_, row) => (
      <Button size="small" onClick={() => setDraft(row.content)} disabled={!canWrite}>复制到编辑框</Button>
    )},
  ];

  return (
    <PageContainer
      title="协议管理"
      hint={`商家入驻服务协议（纯文本）。当前生效版本：${current ? current.agreementVersion : '尚未发布'}。发布即生效；已签约商家不受影响，签署始终以盖章时刻的当前版本为准。`}
    >
      {notice && <InlineStatus>{notice}</InlineStatus>}
      {error && <ErrorAlert>{error}</ErrorAlert>}
      <section style={{ marginBottom: 24 }}>
        <h3 style={{ marginBottom: 8 }}>发布新版本</h3>
        <Input.TextArea
          rows={10}
          maxLength={100000}
          value={draft}
          onChange={event => setDraft(event.target.value)}
          placeholder={canWrite ? '输入协议全文（纯文本，支持换行分段；至少 10 个字）' : '无发布权限（merchant.application.decide）'}
          disabled={!canWrite}
          showCount
        />
        <div style={{ marginTop: 12, display: 'flex', gap: 12, alignItems: 'center' }}>
          <Button type="primary" onClick={() => void publish()} disabled={!canWrite || busy || draft.trim().length < 10}>
            {busy ? '发布中…' : '发布并生效'}
          </Button>
          <Button onClick={() => setPreviewing(v => !v)} disabled={draft.trim().length < 10}>
            {previewing ? '关闭预览' : '预览小程序效果'}
          </Button>
          <span className="hint" style={{ margin: 0 }}>支持 Markdown 轻标记：# 标题、**加粗**、1. 条款列表；发布后小程序立即读到新版。</span>
        </div>
        {previewing && <AgreementMarkdownPreview source={draft} />}
      </section>
      <h3 style={{ marginBottom: 8 }}>版本历史</h3>
      <DataTable
        rowKey={row => row.versionId}
        columns={columns}
        rows={versions}
        loading={versions === undefined}
      />
    </PageContainer>
  );
}
