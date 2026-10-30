import { useEffect, useRef, useState } from 'react';
import { RequestFailure } from '../request';
import {
  CATEGORY_STATUSES, describeCategoryFailure, isCategoryConflict,
  type CategoryPatch, type CategoryStatus, type ServiceCategoryAdminClient,
  type ServiceCategoryReceipt, type ServiceCategoryRow,
} from '../api/serviceCategoryAdmin';

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

  return <main className="page">
    <h2>服务分类字典</h2>
    <p className="hint">服务分类是 C 端服务 Tab 与活动分类的统一字典：编码创建后不可修改，名称全局唯一；每次保存版本号 +1。图标采用方案 A：粘贴既有后台上传的 https:// 图片地址，本页不做上传。</p>
    {notice && <output role="status">{notice}</output>}
    {error && <p role="alert">{error}</p>}
    <form className="filters" onSubmit={event => {
      event.preventDefault();
      const nextKeyword = keywordInput.trim();
      setKeyword(nextKeyword);
      setError('');
      void load(1, statusFilter, nextKeyword);
    }}>
      <label>状态
        <select value={statusFilter} onChange={event => {
          const next = event.target.value as CategoryStatus | '';
          setStatusFilter(next);
          setError('');
          void load(1, next, keyword);
        }}>
          <option value="">全部</option>
          {CATEGORY_STATUSES.map(value => <option key={value} value={value}>{STATUS_TEXT[value]}</option>)}
        </select>
      </label>
      <label>关键词（编码或名称）<input value={keywordInput} onChange={event => setKeywordInput(event.target.value)} maxLength={50} /></label>
      <button type="submit">查询</button>
      <button type="button" onClick={() => { setKeywordInput(''); setKeyword(''); setStatusFilter(''); setError(''); void load(1, '', ''); }}>重置</button>
      <button type="button" disabled={busy} onClick={guard(() => setCreating(true))}>新增分类</button>
    </form>

    {rows === undefined
      ? <>
        <p role="status">加载中</p>
        <button onClick={() => { setError(''); void load(); }}>重新加载</button>
      </>
      : <table>
        <thead><tr>
          <th>编码</th><th>名称</th><th>图标</th><th>描述</th><th>排序</th><th>状态</th><th>版本</th><th>操作</th>
        </tr></thead>
        <tbody>
          {rows.map(row => <tr key={row.categoryCode}>
            <td>{row.categoryCode}</td>
            <td>{row.categoryName}</td>
            <td><IconCell code={row.categoryCode} url={row.iconUrl} /></td>
            <td>{row.description ?? ''}</td>
            <td>{row.sortNo}</td>
            <td>{STATUS_TEXT[row.status]}</td>
            <td>{row.version}</td>
            <td>
              <button disabled={busy} onClick={guard(() => void act(
                () => client.setEnabled(row.categoryCode, row.version, row.status !== 'ENABLED', crypto.randomUUID()),
                receipt => `${STATUS_TEXT[receipt.status]}（版本 ${receipt.version}）`))}>
                {row.status === 'ENABLED' ? '停用' : '启用'}
              </button>
              <button disabled={busy} onClick={guard(() => setEditing(row))}>编辑</button>
            </td>
          </tr>)}
          {rows.length === 0 && <tr><td colSpan={8}>暂无分类</td></tr>}
        </tbody>
      </table>}
    {rows !== undefined && <p className="hint">
      第 {page} 页 · 共 {total} 条 · 每页 {PAGE_SIZE} 条
      <button disabled={page <= 1} onClick={() => { setError(''); void load(page - 1); }}>上一页</button>
      <button disabled={page * PAGE_SIZE >= total} onClick={() => { setError(''); void load(page + 1); }}>下一页</button>
    </p>}

    {creating && <section className="detail-card" aria-label="新增分类">
      <h3>新增分类</h3>
      <form onSubmit={event => { event.preventDefault(); submitCreate(event.currentTarget); }}>
        <label>分类编码（大写字母开头，仅大写字母/数字/下划线，2-32 位，创建后不可修改）<input name="categoryCode" required pattern="[A-Z][A-Z0-9_]{1,31}" placeholder="如 GROOMING" /></label>
        <label>分类名称（1-20 字，全局唯一）<input name="categoryName" required maxLength={20} /></label>
        <label>父分类编码（可选；须为已存在的分类编码，留空为无父分类）<input name="parentCode" pattern="[A-Z][A-Z0-9_]{1,31}" /></label>
        <label>图标 URL（可选；方案 A：粘贴既有后台上传的 https:// 地址，≤512 字）<input name="iconUrl" type="url" placeholder="https://…" /></label>
        <label>描述（可选，≤200 字）<input name="description" maxLength={200} /></label>
        <label>排序（0-9999，越小越靠前，留空默认 0）<input name="sortNo" type="number" min={0} max={9999} /></label>
        <button type="submit" disabled={busy}>创建</button>
        <button type="button" onClick={() => setCreating(false)}>关闭</button>
      </form>
    </section>}

    {editing && <EditCategoryForm key={editing.categoryCode} row={editing} busy={busy} onSubmit={submitEdit} onClose={() => setEditing(undefined)} />}
  </main>;
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
    if (name === 'parentCode') return <input name="parentCode" pattern="[A-Z][A-Z0-9_]{1,31}" placeholder="如 GROOMING（须为已存在的分类编码）" />;
    if (name === 'iconUrl') return <input name="iconUrl" type="url" placeholder="https://…（方案 A：粘贴既有后台上传的 URL）" />;
    return <input name="description" maxLength={200} />;
  };

  const modeSelect = (name: string, label: string, current: string | null, mode: FieldMode, onMode: (m: FieldMode) => void, clearText: string) => <>
    <label>{label}（当前：{current === null ? '空' : current}）
      <select value={mode} onChange={event => onMode(event.target.value as FieldMode)}>
        <option value="keep">保持现值（本次不修改）</option>
        <option value="clear">{clearText}</option>
        <option value="set">设为新值</option>
      </select>
    </label>
    {mode === 'set' && fieldInput(name)}
  </>;

  return <section className="detail-card" aria-label="编辑分类">
    <h3>编辑：{row.categoryName}（{row.categoryCode} · 当前版本 {row.version}）</h3>
    <p className="hint">下方三个可选字段按「保持现值 / 清空 / 设为新值」提交：保持现值的字段不会随本次保存发送；「清空」会显式把该字段置为空。保存后版本号 +1。</p>
    {formError && <p role="alert">{formError}</p>}
    <form onSubmit={event => { event.preventDefault(); submit(event.currentTarget); }}>
      <label>分类名称（1-20 字，全局唯一）<input name="categoryName" defaultValue={row.categoryName} required maxLength={20} /></label>
      <label>排序（0-9999，越小越靠前）<input name="sortNo" type="number" min={0} max={9999} defaultValue={row.sortNo} required /></label>
      {modeSelect('parentCode', '父分类编码', row.parentCode, parentMode, setParentMode, '清空（设为无父分类）')}
      {modeSelect('iconUrl', '图标 URL', row.iconUrl, iconMode, setIconMode, '清空（移除图标）')}
      {modeSelect('description', '描述', row.description, descMode, setDescMode, '清空（移除描述）')}
      <button type="submit" disabled={busy}>保存</button>
      <button type="button" onClick={onClose}>关闭</button>
    </form>
  </section>;
}
