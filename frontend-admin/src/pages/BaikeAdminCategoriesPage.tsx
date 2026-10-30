import { useEffect, useRef, useState } from 'react';
import { Button, Input } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  CATEGORY_NAMES, isConflict, type AdminCategoryRow, type EncyclopediaAdminClient,
} from '../api/encyclopediaAdmin';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus, Section, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

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

  const columns: TableColumnsType<AdminCategoryRow> = [
    { title: '枚举', dataIndex: 'categoryCode' },
    { title: '名称', render: (_, row) => CATEGORY_NAMES[row.categoryCode] },
    { title: '状态', render: (_, row) => <StatusTag label={STATUS_TEXT[row.status]} status={row.status} /> },
    { title: '排序', dataIndex: 'sortOrder' },
    { title: '品种数', dataIndex: 'breedCount' },
    { title: '已发布品种', dataIndex: 'publishedBreedCount' },
    { title: '版本', dataIndex: 'version' },
    { title: '操作', render: (_, row) => <>
      <Button danger disabled={busy} onClick={guard(() => void act(
        () => client.setCategoryEnabled(row.categoryCode, row.version, row.status !== 'ENABLED', crypto.randomUUID()),
        row.status === 'ENABLED' ? '已停用' : '已启用'))}>
        {row.status === 'ENABLED' ? '停用' : '启用'}
      </Button>
      <Button disabled={busy} onClick={guard(() => setEditing(row))}>维护</Button>
    </> },
  ];

  return <PageContainer
    title="宠物百科 · 分类管理"
    hint="五分类枚举（狗狗/猫咪/爬宠/鸟类/昆虫）与 C 端严格一致，不可新增；停用后 C 端分类 Tab 与搜索立即不可见，重新启用立即恢复。">
    {notice && <InlineStatus>{notice}</InlineStatus>}
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {rows === undefined
      ? <InlineStatus>加载中</InlineStatus>
      : <DataTable columns={columns} rows={rows} rowKey={row => row.categoryCode} />}
    {editing && <section aria-label="分类维护">
      <Section title={`维护：${CATEGORY_NAMES[editing.categoryCode]}（当前版本 ${editing.version}）`}>
        <form onSubmit={event => {
          event.preventDefault();
          const form = new FormData(event.currentTarget);
          const categoryName = String(form.get('categoryName') ?? '').trim();
          const sortOrder = Number(form.get('sortOrder') ?? '0');
          void act(() => client.updateCategory(editing.categoryCode, categoryName, sortOrder, editing.version, crypto.randomUUID()), '已保存');
        }}>
          <label className="filter-item">分类名称（2..10 字）<Input name="categoryName" defaultValue={editing.categoryName} minLength={2} maxLength={10} required /></label>
          <label className="filter-item">排序值（0..999，越小越靠前）<Input name="sortOrder" type="number" min={0} max={999} defaultValue={editing.sortOrder} /></label>
          <div className="decision-actions">
            <Button type="primary" htmlType="submit" disabled={busy}>保存</Button>
            <Button onClick={() => setEditing(undefined)}>关闭</Button>
          </div>
        </form>
      </Section>
    </section>}
  </PageContainer>;
}
