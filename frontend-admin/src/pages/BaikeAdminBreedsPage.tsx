import { useEffect, useRef, useState } from 'react';
import {
  CATEGORY_NAMES, isConflict,
  type AdminBreedCard, type AdminBreedDetail,
  type EncyclopediaAdminClient, type BreedStatus,
} from '../api/encyclopediaAdmin';

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

  return <main className="page">
    <h2>宠物百科 · 品种库</h2>
    <p className="hint">全状态品种台账；上下线后 C 端列表/详情立即生效（62 号读侧过滤）。分类枚举五值封闭，新增/删除/导出不在 V1（CCR-ADM-A005-OPS-001）。</p>
    <form className="filters" onSubmit={event => { event.preventDefault(); void load(status, keyword); }}>
      <label>状态
        <select value={status} onChange={event => { setStatus(event.target.value as BreedStatus | ''); void load(event.target.value as BreedStatus | '', keyword); }}>
          <option value="">全部</option>
          <option value="PUBLISHED">已发布</option>
          <option value="OFFLINE">已下线</option>
        </select>
      </label>
      <label>关键词
        <input value={keyword} onChange={event => setKeyword(event.target.value)} placeholder="品种名/别名" maxLength={40} />
      </label>
      <button type="submit">查询</button>
      {notice && <output role="status">{notice}</output>}
      {error && <span role="alert">{error}</span>}
    </form>
    {rows === undefined ? <p role="status">加载中</p> : <table>
      <thead><tr>
        <th>品种 ID</th><th>分类</th><th>名称</th><th>标签</th><th>状态</th><th>排序</th><th>版本</th><th>操作</th>
      </tr></thead>
      <tbody>
        {rows.map(row => <tr key={row.breedId}>
          <td>{row.breedId}</td>
          <td>{CATEGORY_NAMES[row.categoryCode]}</td>
          <td>{row.name}</td>
          <td>{row.tags.join('、')}</td>
          <td>{STATUS_TEXT[row.status]}</td>
          <td>{row.sortOrder}</td>
          <td>{row.version}</td>
          <td>
            <button disabled={busy} onClick={guard(canPublish, () => void act(
              () => client.setBreedPublished(row.breedId, row.version, row.status !== 'PUBLISHED', crypto.randomUUID()),
              row.status === 'PUBLISHED' ? '已下线' : '已上线'))}>
              {row.status === 'PUBLISHED' ? '下线' : '上线'}
            </button>
            <button disabled={busy} onClick={guard(canWrite, () => void openDetail(row.breedId))}>维护</button>
          </td>
        </tr>)}
        {rows.length === 0 && <tr><td colSpan={8}>无匹配品种</td></tr>}
      </tbody>
    </table>}
    <p className="hint">共 {total} 行</p>
    {detail && <section className="detail-card" aria-label="品种维护">
      <h3>维护：{detail.name}（当前版本 {detail.version}）</h3>
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
        <label>简介（≤120 字）<textarea name="summary" defaultValue={detail.summary} maxLength={120} required /></label>
        <label>排序值（0..9999）<input name="sortOrder" type="number" min={0} max={9999} defaultValue={detail.sortOrder} /></label>
        <label>别名（、分隔，≤5 个）<input name="aliases" defaultValue={detail.aliases.join('、')} /></label>
        <label>标签（、分隔，≤10 个）<input name="tags" defaultValue={detail.tags.join('、')} /></label>
        <label>封面 URL（≤512）<input name="coverUrl" defaultValue={detail.coverUrl ?? ''} /></label>
        <p className="hint">富文本列（描述/适养建议/健康提示）本面只读：V1 无富文本编辑通道（CCR-ADM-A005-OPS-001）。</p>
        <button type="submit" disabled={busy}>保存</button>
        <button type="button" onClick={() => setDetail(undefined)}>关闭</button>
      </form>
    </section>}
  </main>;
}

