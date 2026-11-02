import { useEffect, useRef, useState } from 'react';
import { Button, Input, Select } from 'antd';
import type { TableColumnsType } from 'antd';
import { RequestFailure } from '../request';
import {
  CATEGORY_STATUSES, describeCategoryFailure, isCategoryConflict,
  type CategoryPatch, type CategoryStatus, type ServiceCategoryAdminClient,
  type ServiceCategoryReceipt, type ServiceCategoryRow,
} from '../api/serviceCategoryAdmin';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus, Section, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

const STATUS_TEXT: Record<CategoryStatus, string> = { ENABLED: '启用', DISABLED: '停用' };
const CODE_LEXICON = /^[A-Z][A-Z0-9_]{1,31}$/;
const PAGE_SIZE = 50; // Backend caps pageSize at 50 (contract 74 §4).
type FieldMode = 'keep' | 'clear' | 'set';

function IconCell({ code, url }: { code: string; url: string | null }) {
  const [broken, setBroken] = useState(false);
  useEffect(() => { setBroken(false); }, [url]);
  if (!url || broken) return <span className="hint">无图标</span>;
  return <img src={url} alt={`${code} 图标`} width={24} height={24} style={{ verticalAlign: 'middle' }} onError={() => setBroken(true)} />;
}

function describeReadFailure(error: unknown): string {
  if (error instanceof RequestFailure) {
    if (error.code === 'FORBIDDEN' || error.code === 'COMMON_FORBIDDEN') return '读取失败：当前账号未授予 service.category.read 权限';
    if (error.code === 'COMMON_DEPENDENCY_UNAVAILABLE') return '读取失败：分类服务暂不可用，请稍后重试';
    return `读取失败（${error.code}）`;
  }
  return '读取失败';
}

/**
 * Contract-74 §4 服务分类字典页 (CCR-SVC-CAT-001 B-SVC-02): full-status dictionary with
 * create/update/enable/disable. iconUrl follows scheme A (paste an existing uploaded HTTPS
 * URL — no upload pipeline). Update form shows the wire's patch semantics explicitly: a
 * field left on 保持现值 is absent from the PUT body (server keeps stored value); 清空 sends
 * an explicit null; 设为新值 sends the string. Every write carries a fresh UUID X-Request-Id.
 */
export default function ServiceCategoryAdminPage({ client, canWrite, onAuthLost }: {
  client: ServiceCategoryAdminClient;
  canWrite: boolean;
  onAuthLost: () => void;
}) {
  const [rows, setRows] = useState<ServiceCategoryRow[]>();
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState<CategoryStatus | ''>('');
  const [keyword, setKeyword] = useState('');
  const [keywordInput, setKeywordInput] = useState('');
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<ServiceCategoryRow>();
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const sequence = useRef(0);

  async function load(nextPage = page, nextStatus = statusFilter, nextKeyword = keyword) {
    const call = ++sequence.current;
    try {
      const result = await client.list({
        ...(nextStatus ? { status: nextStatus } : {}),
        ...(nextKeyword ? { keyword: nextKeyword } : {}),
      }, nextPage, PAGE_SIZE);
      if (call !== sequence.current) return;
      setRows(result.items);
      setTotal(result.total);
      setPage(nextPage);
      // Deliberately does NOT clear `error`: the write-conflict path refreshes the list
      // while keeping its readable message on screen; user-initiated reloads clear it.
    } catch (caught) {
      if (call !== sequence.current) return;
      setError(describeReadFailure(caught));
    }
  }

  useEffect(() => {
    void load(1, '', '');
    return () => { sequence.current++; };
  }, [client]); // eslint-disable-line react-hooks/exhaustive-deps

  async function act(action: () => Promise<ServiceCategoryReceipt>, okText: (receipt: ServiceCategoryReceipt) => string) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const receipt = await action();
      setNotice(okText(receipt));
      setCreating(false);
      setEditing(undefined);
      await load();
    } catch (caught) {
      if (caught instanceof RequestFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
      setError(describeCategoryFailure(caught));
      // The conflict/404 text promises a refreshed list — make that true.
      if (isCategoryConflict(caught)) await load();
    } finally {
      setBusy(false);
    }
  }

  const guard = (action: () => void) => () => {
    if (!canWrite) { setError('当前账号未授予 service.category.manage，无法执行写操作'); return; }
    action();
  };

  function submitCreate(form: HTMLFormElement) {
    const data = new FormData(form);
    const categoryCode = String(data.get('categoryCode') ?? '').trim();
    const categoryName = String(data.get('categoryName') ?? '').trim();
    const parentCode = String(data.get('parentCode') ?? '').trim();
    const iconUrl = String(data.get('iconUrl') ?? '').trim();
    const description = String(data.get('description') ?? '').trim();
    const sortNoRaw = String(data.get('sortNo') ?? '').trim();
    if (!CODE_LEXICON.test(categoryCode)) { setError('分类编码需为大写字母开头，仅含大写字母/数字/下划线（2-32 位），如 GROOMING'); return; }
    if (!categoryName || categoryName.length > 20) { setError('请填写分类名称（1-20 字）'); return; }
    if (parentCode && !CODE_LEXICON.test(parentCode)) { setError('父分类编码格式不正确：需为已存在的分类编码（大写字母开头，2-32 位）'); return; }
    if (iconUrl && (!iconUrl.startsWith('https://') || iconUrl.length > 512)) { setError('图标地址须为 https:// 开头且不超过 512 字（方案 A：粘贴既有后台上传的 URL）'); return; }
    if (description.length > 200) { setError('描述不超过 200 字'); return; }
    const sortNo = sortNoRaw === '' ? undefined : Number(sortNoRaw);
    if (sortNo !== undefined && (!Number.isInteger(sortNo) || sortNo < 0 || sortNo > 9999)) { setError('排序需为 0-9999 的整数（越小越靠前，留空默认 0）'); return; }
    void act(
      () => client.create({
        categoryCode,
        categoryName,
        ...(parentCode ? { parentCode } : {}),
        ...(iconUrl ? { iconUrl } : {}),
        ...(description ? { description } : {}),
        ...(sortNo !== undefined ? { sortNo } : {}),
      }, crypto.randomUUID()),
      receipt => `已创建：${receipt.categoryCode}（版本 ${receipt.version}）`);
  }

  function submitEdit(row: ServiceCategoryRow, patch: CategoryPatch) {
    void act(
      () => client.update(row.categoryCode, patch, row.version, crypto.randomUUID()),
      receipt => `已保存：${receipt.categoryCode}（版本 ${receipt.version}）`);
  }

  const columns: TableColumnsType<ServiceCategoryRow> = [
    { title: '编码', dataIndex: 'categoryCode' },
    { title: '名称', dataIndex: 'categoryName' },
    { title: '图标', render: (_, row) => <IconCell code={row.categoryCode} url={row.iconUrl} /> },
    { title: '描述', render: (_, row) => row.description ?? '' },
    { title: '排序', dataIndex: 'sortNo' },
    { title: '状态', render: (_, row) => <StatusTag label={STATUS_TEXT[row.status]} status={row.status} /> },
    { title: '版本', dataIndex: 'version' },
    { title: '操作', render: (_, row) => <>
      <Button danger disabled={busy} onClick={guard(() => void act(
        () => client.setEnabled(row.categoryCode, row.version, row.status !== 'ENABLED', crypto.randomUUID()),
        receipt => `${STATUS_TEXT[receipt.status]}（版本 ${receipt.version}）`))}>
        {row.status === 'ENABLED' ? '停用' : '启用'}
      </Button>
      <Button disabled={busy} onClick={guard(() => setEditing(row))}>编辑</Button>
    </> },
  ];

  return <PageContainer
    title="服务分类字典"
    hint="服务分类是 C 端服务 Tab 与活动分类的统一字典：编码创建后不可修改，名称全局唯一；每次保存版本号 +1。图标采用方案 A：粘贴既有后台上传的 https:// 图片地址，本页不做上传。">
    {notice && <InlineStatus>{notice}</InlineStatus>}
    {error && <ErrorAlert>{error}</ErrorAlert>}
    <form className="filter-bar" onSubmit={event => {
      event.preventDefault();
      const nextKeyword = keywordInput.trim();
      setKeyword(nextKeyword);
      setError('');
      void load(1, statusFilter, nextKeyword);
    }}>
      <label className="filter-item">状态
        <Select style={{ width: 160 }} aria-label="状态" value={statusFilter} onChange={value => {
          const next = value as CategoryStatus | '';
          setStatusFilter(next);
          setError('');
          void load(1, next, keyword);
        }} options={[{ value: '', label: '全部' }, ...CATEGORY_STATUSES.map(status => ({ value: status, label: STATUS_TEXT[status] }))]} />
      </label>
      <label className="filter-item">关键词（编码或名称）<Input style={{ width: 200 }} value={keywordInput} onChange={event => setKeywordInput(event.target.value)} maxLength={50} /></label>
      <Button type="primary" htmlType="submit">查询</Button>
      <Button htmlType="button" onClick={() => { setKeywordInput(''); setKeyword(''); setStatusFilter(''); setError(''); void load(1, '', ''); }}>重置</Button>
      <Button htmlType="button" disabled={busy} onClick={guard(() => setCreating(true))}>新增分类</Button>
    </form>

    {rows === undefined
      ? <><InlineStatus>加载中</InlineStatus><Button onClick={() => { setError(''); void load(); }}>重新加载</Button></>
      : <DataTable
        columns={columns}
        rows={rows}
        rowKey={row => row.categoryCode}
        page={page}
        pageSize={PAGE_SIZE}
        total={total}
        onPageChange={next => { setError(''); void load(next); }}
      />}
    {rows !== undefined && rows.length === 0 && <p className="hint">暂无分类</p>}

    {creating && <section aria-label="新增分类">
      <Section title="新增分类">
        <form onSubmit={event => { event.preventDefault(); submitCreate(event.currentTarget); }}>
          <label className="filter-item">分类编码（大写字母开头，仅大写字母/数字/下划线，2-32 位，创建后不可修改）<Input name="categoryCode" required pattern="[A-Z][A-Z0-9_]{1,31}" placeholder="如 GROOMING" /></label>
          <label className="filter-item">分类名称（1-20 字，全局唯一）<Input name="categoryName" required maxLength={20} /></label>
          <label className="filter-item">父分类编码（可选；须为已存在的分类编码，留空为无父分类）<Input name="parentCode" pattern="[A-Z][A-Z0-9_]{1,31}" /></label>
          <label className="filter-item">图标 URL（可选；方案 A：粘贴既有后台上传的 https:// 地址，≤512 字）<Input name="iconUrl" type="url" placeholder="https://…" /></label>
          <label className="filter-item">描述（可选，≤200 字）<Input name="description" maxLength={200} /></label>
          <label className="filter-item">排序（0-9999，越小越靠前，留空默认 0）<Input name="sortNo" type="number" min={0} max={9999} /></label>
          <div className="decision-actions">
            <Button type="primary" htmlType="submit" disabled={busy}>创建</Button>
            <Button htmlType="button" onClick={() => setCreating(false)}>关闭</Button>
          </div>
        </form>
      </Section>
    </section>}

    {editing && <EditCategoryForm key={editing.categoryCode} row={editing} busy={busy} onSubmit={submitEdit} onClose={() => setEditing(undefined)} />}
  </PageContainer>;
}

/**
 * Edit form with the contract's tri-state patch semantics made explicit per nullable field
 * (parentCode/iconUrl/description): 保持现值 omits the key (server keeps stored value),
 * 清空 sends an explicit null, 设为新值 sends the entered string. categoryName/sortNo always
 * submit (server keeps the stored value only when they are absent — the form always sends
 * them, unchanged values are no-ops server-side). expectedVersion carries the listed row's
 * version for the optimistic CAS.
 */
function EditCategoryForm({ row, busy, onSubmit, onClose }: {
  row: ServiceCategoryRow;
  busy: boolean;
  onSubmit: (row: ServiceCategoryRow, patch: CategoryPatch) => void;
  onClose: () => void;
}) {
  const [parentMode, setParentMode] = useState<FieldMode>('keep');
  const [iconMode, setIconMode] = useState<FieldMode>('keep');
  const [descMode, setDescMode] = useState<FieldMode>('keep');
  const [formError, setFormError] = useState('');

  function submit(form: HTMLFormElement) {
    setFormError('');
    const data = new FormData(form);
    const categoryName = String(data.get('categoryName') ?? '').trim();
    const sortNo = Number(data.get('sortNo') ?? '');
    if (!categoryName || categoryName.length > 20) { setFormError('请填写分类名称（1-20 字）'); return; }
    if (!Number.isInteger(sortNo) || sortNo < 0 || sortNo > 9999) { setFormError('排序需为 0-9999 的整数'); return; }
    const patch: CategoryPatch = { categoryName, sortNo };
    const parentValue = String(data.get('parentCode') ?? '').trim();
    if (parentMode === 'clear') patch.parentCode = null;
    else if (parentMode === 'set') {
      if (!CODE_LEXICON.test(parentValue)) { setFormError('父分类编码格式不正确：需为已存在的分类编码（大写字母开头，2-32 位）'); return; }
      patch.parentCode = parentValue;
    }
    const iconValue = String(data.get('iconUrl') ?? '').trim();
    if (iconMode === 'clear') patch.iconUrl = null;
    else if (iconMode === 'set') {
      if (!iconValue.startsWith('https://') || iconValue.length > 512) { setFormError('图标地址须为 https:// 开头且不超过 512 字（方案 A：粘贴既有后台上传的 URL）'); return; }
      patch.iconUrl = iconValue;
    }
    const descValue = String(data.get('description') ?? '').trim();
    if (descMode === 'clear') patch.description = null;
    else if (descMode === 'set') {
      if (descValue.length > 200) { setFormError('描述不超过 200 字'); return; }
      patch.description = descValue;
    }
    onSubmit(row, patch);
  }

  const fieldInput = (name: string) => {
    if (name === 'parentCode') return <Input name="parentCode" pattern="[A-Z][A-Z0-9_]{1,31}" placeholder="如 GROOMING（须为已存在的分类编码）" />;
    if (name === 'iconUrl') return <Input name="iconUrl" type="url" placeholder="https://…（方案 A：粘贴既有后台上传的 URL）" />;
    return <Input name="description" maxLength={200} />;
  };

  const modeSelect = (name: string, label: string, current: string | null, mode: FieldMode, onMode: (m: FieldMode) => void, clearText: string) => <>
    <label className="filter-item">{label}（当前：{current === null ? '空' : current}）
      <Select<FieldMode> style={{ width: 240 }} aria-label={label} value={mode} onChange={onMode}
        options={[{ value: 'keep', label: '保持现值（本次不修改）' }, { value: 'clear', label: clearText }, { value: 'set', label: '设为新值' }]} />
    </label>
    {mode === 'set' && fieldInput(name)}
  </>;

  return <section aria-label="编辑分类">
    <Section title={`编辑：${row.categoryName}（${row.categoryCode} · 当前版本 ${row.version}）`}>
      <p className="hint">下方三个可选字段按「保持现值 / 清空 / 设为新值」提交：保持现值的字段不会随本次保存发送；「清空」会显式把该字段置为空。保存后版本号 +1。</p>
      {formError && <ErrorAlert>{formError}</ErrorAlert>}
      <form onSubmit={event => { event.preventDefault(); submit(event.currentTarget); }}>
        <label className="filter-item">分类名称（1-20 字，全局唯一）<Input name="categoryName" defaultValue={row.categoryName} required maxLength={20} /></label>
        <label className="filter-item">排序（0-9999，越小越靠前）<Input name="sortNo" type="number" min={0} max={9999} defaultValue={row.sortNo} required /></label>
        {modeSelect('parentCode', '父分类编码', row.parentCode, parentMode, setParentMode, '清空（设为无父分类）')}
        {modeSelect('iconUrl', '图标 URL', row.iconUrl, iconMode, setIconMode, '清空（移除图标）')}
        {modeSelect('description', '描述', row.description, descMode, setDescMode, '清空（移除描述）')}
        <div className="decision-actions">
          <Button type="primary" htmlType="submit" disabled={busy}>保存</Button>
          <Button htmlType="button" onClick={onClose}>关闭</Button>
        </div>
      </form>
    </Section>
  </section>;
}
