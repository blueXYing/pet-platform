import { useEffect, useRef, useState } from 'react';
import { Button, Input, Select } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  CATEGORY_NAMES, isConflict,
  type AdminBreedCard, type AdminBreedDetail,
  type EncyclopediaAdminClient, type BreedStatus,
} from '../api/encyclopediaAdmin';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus, Section, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

const STATUS_TEXT: Record<string, string> = {
  ENABLED: '启用', DISABLED: '停用', PUBLISHED: '已发布', OFFLINE: '已下线',
};

/**
 * Contract-65 百科品种维护页 (PRD 6.10.2, A-005): all-status breed ledger, white-listed
 * field maintenance and the publish/offline transition (the 62 leftover 上下线运营面).
 * Category creation/deletion and breed creation do not exist on the wire (CCR-ADM-A005-OPS-001).
 */
export default function BaikeAdminBreedsPage({ client, canWrite, canPublish, onAuthLost }: {
  client: EncyclopediaAdminClient;
  canWrite: boolean;
  canPublish: boolean;
  onAuthLost: () => void;
}) {
  const [rows, setRows] = useState<AdminBreedCard[]>();
  const [total, setTotal] = useState(0);
  const [status, setStatus] = useState<BreedStatus | ''>('');
  const [keyword, setKeyword] = useState('');
  const [detail, setDetail] = useState<AdminBreedDetail>();
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const sequence = useRef(0);

  async function load(nextStatus: BreedStatus | '', nextKeyword: string) {
    const call = ++sequence.current;
    setError('');
    try {
      const page = await client.listBreeds({
        status: nextStatus || undefined,
        keyword: nextKeyword || undefined,
      });
      if (call !== sequence.current) return;
      setRows(page.items);
      setTotal(page.total);
    } catch (caught) {
      if (call !== sequence.current) return;
      handleError(caught);
    }
  }

  useEffect(() => { void load('', ''); return () => { sequence.current++; }; }, [client]); // eslint-disable-line react-hooks/exhaustive-deps

  function handleError(caught: unknown) {
    if (caught instanceof Error && caught.message === 'UNAUTHENTICATED') { onAuthLost(); return; }
    setError(caught instanceof Error ? `操作失败（${caught.message}）` : '操作失败');
  }

  async function act(action: () => Promise<{ version: string; status: string }>, okText: string) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await action();
      setNotice(okText);
      await load(status, keyword);
    } catch (caught) {
      if (isConflict(caught)) setError('状态或版本冲突：行已被其他人修改，请刷新后重试');
      else handleError(caught);
    } finally {
      setBusy(false);
    }
  }

  async function openDetail(breedId: string) {
    try {
      setDetail(await client.getBreed(breedId));
    } catch (caught) {
      handleError(caught);
    }
  }

  const guard = (allowed: boolean, action: () => void) => () => {
    if (!allowed) { setError('当前账号未授予该动作'); return; }
    action();
  };

  const columns: TableColumnsType<AdminBreedCard> = [
    { title: '品种 ID', dataIndex: 'breedId' },
    { title: '分类', render: (_, row) => CATEGORY_NAMES[row.categoryCode] },
    { title: '名称', dataIndex: 'name' },
    { title: '标签', render: (_, row) => row.tags.join('、') },
    { title: '状态', render: (_, row) => <StatusTag label={STATUS_TEXT[row.status]} status={row.status} /> },
    { title: '排序', dataIndex: 'sortOrder' },
    { title: '版本', dataIndex: 'version' },
    { title: '操作', render: (_, row) => <>
      <Button danger disabled={busy} onClick={guard(canPublish, () => void act(
        () => client.setBreedPublished(row.breedId, row.version, row.status !== 'PUBLISHED', crypto.randomUUID()),
        row.status === 'PUBLISHED' ? '已下线' : '已上线'))}>
        {row.status === 'PUBLISHED' ? '下线' : '上线'}
      </Button>
      {' '}
      <Button disabled={busy} onClick={guard(canWrite, () => void openDetail(row.breedId))}>维护</Button>
    </> },
  ];

  return <PageContainer
    title="宠物百科 · 品种库"
    hint="全状态品种台账；上下线后 C 端列表/详情立即生效（62 号读侧过滤）。分类枚举五值封闭，新增/删除/导出不在 V1（CCR-ADM-A005-OPS-001）。">
    <form className="filter-bar" onSubmit={event => { event.preventDefault(); void load(status, keyword); }}>
      <label className="filter-item">状态
        <Select style={{ width: 160 }} value={status} onChange={value => {
          const next = value as BreedStatus | '';
          setStatus(next);
          void load(next, keyword);
        }} options={[
          { value: '', label: '全部' },
          { value: 'PUBLISHED', label: '已发布' },
          { value: 'OFFLINE', label: '已下线' },
        ]} />
      </label>
      <label className="filter-item">关键词
        <Input style={{ width: 200 }} value={keyword} onChange={event => setKeyword(event.target.value)} placeholder="品种名/别名" maxLength={40} />
      </label>
      <Button type="primary" htmlType="submit">查询</Button>
    </form>
    {notice && <InlineStatus>{notice}</InlineStatus>}
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {rows === undefined
      ? <InlineStatus>加载中</InlineStatus>
      : rows.length === 0
        ? <p>无匹配品种</p>
        : <DataTable columns={columns} rows={rows} rowKey={row => row.breedId} />}
    <p className="hint">共 {total} 行</p>
    {detail && <section aria-label="品种维护">
      <Section title={`维护：${detail.name}（当前版本 ${detail.version}）`}>
        <form onSubmit={event => {
          event.preventDefault();
          const form = new FormData(event.currentTarget);
          const summary = String(form.get('summary') ?? '').trim();
          const sortOrder = Number(form.get('sortOrder') ?? '0');
          const aliases = String(form.get('aliases') ?? '').split(/[、,]/).map(x => x.trim()).filter(Boolean);
          const tags = String(form.get('tags') ?? '').split(/[、,]/).map(x => x.trim()).filter(Boolean);
          const coverUrl = String(form.get('coverUrl') ?? '').trim();
          void act(() => client.updateBreed(detail.breedId, {
            summary, sortOrder, aliases, tags, coverUrl: coverUrl || null, bodyType: detail.bodyType,
          }, detail.version, crypto.randomUUID()).then(() => { setDetail(undefined); return { version: '', status: '' }; }), '已保存');
        }}>
          <label className="filter-item">简介（≤120 字）<Input.TextArea name="summary" defaultValue={detail.summary} maxLength={120} required rows={3} /></label>
          <label className="filter-item">排序值（0..9999）<Input name="sortOrder" type="number" min={0} max={9999} defaultValue={detail.sortOrder} /></label>
          <label className="filter-item">别名（、分隔，≤5 个）<Input name="aliases" defaultValue={detail.aliases.join('、')} /></label>
          <label className="filter-item">标签（、分隔，≤10 个）<Input name="tags" defaultValue={detail.tags.join('、')} /></label>
          <label className="filter-item">封面 URL（≤512）<Input name="coverUrl" defaultValue={detail.coverUrl ?? ''} /></label>
          <p className="hint">富文本列（描述/适养建议/健康提示）本面只读：V1 无富文本编辑通道（CCR-ADM-A005-OPS-001）。</p>
          <div className="decision-actions">
            <Button type="primary" htmlType="submit" disabled={busy}>保存</Button>
            <Button onClick={() => setDetail(undefined)}>关闭</Button>
          </div>
        </form>
      </Section>
    </section>}
  </PageContainer>;
}
