import { useEffect, useRef, useState } from 'react';
import {
  CATEGORY_NAMES, isConflict, type AdminCategoryRow, type EncyclopediaAdminClient,
} from '../api/encyclopediaAdmin';

const STATUS_TEXT: Record<string, string> = { ENABLED: '启用', DISABLED: '停用' };

/**
 * Contract-65 百科分类台账页 (PRD 6.10.1, A-005): all-status category dictionary with breed
 * counters, display-name/sort maintenance and the enable/disable transition (the 62 leftover
 * 分类停用运营面). The five-code enum is closed — no create/delete route exists.
 */
export default function BaikeAdminCategoriesPage({ client, canWrite }: {
  client: EncyclopediaAdminClient;
  canWrite: boolean;
  onAuthLost: () => void;
}) {
  const [rows, setRows] = useState<AdminCategoryRow[]>();
  const [editing, setEditing] = useState<AdminCategoryRow>();
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const sequence = useRef(0);

  useEffect(() => {
    const call = ++sequence.current;
    void (async () => {
      try {
        const page = await client.listCategories();
        if (call !== sequence.current) return;
        setRows(page.items);
      } catch (caught) {
        if (call !== sequence.current) return;
        setError(caught instanceof Error ? `读取失败（${caught.message}）` : '读取失败');
      }
    })();
    return () => { sequence.current++; };
  }, [client]);

  async function act(action: () => Promise<unknown>, okText: string) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await action();
      setNotice(okText);
      setRows(await client.listCategories().then(page => page.items));
      setEditing(undefined);
    } catch (caught) {
      if (isConflict(caught)) setError('状态或版本冲突：行已被其他人修改，请刷新后重试');
      else setError(caught instanceof Error ? `操作失败（${caught.message}）` : '操作失败');
    } finally {
      setBusy(false);
    }
  }

  const guard = (action: () => void) => () => {
    if (!canWrite) { setError('当前账号未授予 encyclopedia.manage'); return; }
    action();
  };

  return <main className="page">
    <h2>宠物百科 · 分类管理</h2>
    <p className="hint">五分类枚举（狗狗/猫咪/爬宠/鸟类/昆虫）与 C 端严格一致，不可新增；停用后 C 端分类 Tab 与搜索立即不可见，重新启用立即恢复。</p>
    {notice && <output role="status">{notice}</output>}
    {error && <p role="alert">{error}</p>}
    {rows === undefined ? <p role="status">加载中</p> : <table>
      <thead><tr>
        <th>枚举</th><th>名称</th><th>状态</th><th>排序</th><th>品种数</th><th>已发布品种</th><th>版本</th><th>操作</th>
      </tr></thead>
      <tbody>
        {rows.map(row => <tr key={row.categoryCode}>
          <td>{row.categoryCode}</td>
          <td>{CATEGORY_NAMES[row.categoryCode]}</td>
          <td>{STATUS_TEXT[row.status]}</td>
          <td>{row.sortOrder}</td>
          <td>{row.breedCount}</td>
          <td>{row.publishedBreedCount}</td>
          <td>{row.version}</td>
          <td>
            <button disabled={busy} onClick={guard(() => void act(
              () => client.setCategoryEnabled(row.categoryCode, row.version, row.status !== 'ENABLED', crypto.randomUUID()),
              row.status === 'ENABLED' ? '已停用' : '已启用'))}>
              {row.status === 'ENABLED' ? '停用' : '启用'}
            </button>
            <button disabled={busy} onClick={guard(() => setEditing(row))}>维护</button>
          </td>
        </tr>)}
      </tbody>
    </table>}
    {editing && <section className="detail-card" aria-label="分类维护">
      <h3>维护：{CATEGORY_NAMES[editing.categoryCode]}（当前版本 {editing.version}）</h3>
      <form onSubmit={event => {
        event.preventDefault();
        const form = new FormData(event.currentTarget);
        const categoryName = String(form.get('categoryName') ?? '').trim();
        const sortOrder = Number(form.get('sortOrder') ?? '0');
        void act(() => client.updateCategory(editing.categoryCode, categoryName, sortOrder, editing.version, crypto.randomUUID()), '已保存');
      }}>
        <label>分类名称（2..10 字）<input name="categoryName" defaultValue={editing.categoryName} minLength={2} maxLength={10} required /></label>
        <label>排序值（0..999，越小越靠前）<input name="sortOrder" type="number" min={0} max={999} defaultValue={editing.sortOrder} /></label>
        <button type="submit" disabled={busy}>保存</button>
        <button type="button" onClick={() => setEditing(undefined)}>关闭</button>
      </form>
    </section>}
  </main>;
}
