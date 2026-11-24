import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { CloseOutlined, ZoomInOutlined } from '@ant-design/icons';
import { Alert, AutoComplete, Button, Drawer, Empty, Input, Modal, Tag, Upload } from 'antd';
import { RequestFailure } from '../../request';
import {
  HOME_BLOCK_TARGET_TYPES, PLACEMENT_POSITIONS,
  type Campaign, type CouponCampaign, type MarketingAdminClient, type Page,
} from '../../api/marketingAdmin';
import type { ServiceCategoryAdminClient } from '../../api/serviceCategoryAdmin';

/** 分区列表统一页大小（后端默认值；上限 100）。 */
export const PAGE_SIZE = 20;

// ---------------------------------------------------------------------------
// 展示层文案：只翻译展示，不改任何线上数据。
// ---------------------------------------------------------------------------
export const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿', PENDING_REVIEW: '待审核', SCHEDULED: '待生效', ONLINE: '进行中',
  PAUSED: '已暂停', ENDED: '已结束', CANCELED: '已取消', REJECTED: '已驳回',
  SUBMITTED: '已提交', UNDER_REVIEW: '审核中', APPROVED: '已通过', WITHDRAWN: '已撤回',
  ENABLED: '已启用', DISABLED: '已停用', ACTIVE: '进行中',
};
export const CAMPAIGN_TYPE_TEXT: Record<string, string> = {
  DISPLAY: '展示活动', COUPON: '券活动', MERCHANT_ENROLL: '商家招商',
};
export const PARTICIPATION_MODE_TEXT: Record<string, string> = {
  PLATFORM_SELECTED: '平台选择门店', MERCHANT_ENROLL: '商家报名',
};
export const OWNER_TYPE_TEXT: Record<string, string> = { PLATFORM: '平台', MERCHANT: '商家' };
export const STORE_SOURCE_TEXT: Record<string, string> = {
  PLATFORM_SELECTED: '平台指定', MERCHANT_ENROLL: '商家报名',
};
export const AUDIENCE_TYPE_TEXT: Record<string, string> = { ALL: '全部用户', NEW_USER: '新用户' };
export const POSITION_TEXT: Record<string, string> = Object.fromEntries(PLACEMENT_POSITIONS);
export const HOME_TARGET_TEXT: Record<string, string> = Object.fromEntries(HOME_BLOCK_TARGET_TYPES);
export const HOME_POSITION_TEXT: Record<string, string> = {
  HOME_SERVICE: '热门服务', HOME_COMMUNITY: '宠友圈热门', HOME_EXOTIC_PLANT: '植物&异宠',
};
export const PLACEMENT_TEMPLATE_TEXT: Record<string, string> = {
  full_card: '整卡图', new_user: '新客活动卡', beauty: '美容活动卡',
};

export const iso = (local: string) => local ? new Date(local).toISOString() : '';
export const localInput = (value: string | null | undefined) => {
  if (!value) return '';
  const d = new Date(value);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
};
/** ISO → 「YYYY-MM-DD HH:mm」展示。 */
export const fmtDT = (value: string | null | undefined) => value ? value.slice(0, 16).replace('T', ' ') : '--';
/** JSON 串美化（只读展示用）；不是合法 JSON 时原样返回。 */
export function prettyJson(raw: string): string {
  try { return JSON.stringify(JSON.parse(raw), null, 2); } catch { return raw; }
}

/** 时间窗口的本地校验：两项都填且结束晚于开始。后端同样会拒，这里提前用中文说清楚。 */
export function windowIsValid(startAt: string, endAt: string): boolean {
  return !!startAt && !!endAt && new Date(endAt).getTime() > new Date(startAt).getTime();
}

// ---------------------------------------------------------------------------
// 实际展示情况：C 端读取口径是「已发布 + 开始时间 <= now < 结束时间」（MarketingMapper.xml），
// 后台没有调度器把「待生效」翻成「进行中」、到期也不会改状态——只看状态标签会和用户实际看到的对不上，
// 所以这里按时间推导出真实的展示情况。
// ---------------------------------------------------------------------------
export type Visibility = 'upcoming' | 'live' | 'expired' | 'unknown';
const LIVE_STATUSES: Record<string, string[]> = {
  campaign: ['SCHEDULED', 'ONLINE'], placement: ['ENABLED'], homeBlock: ['ENABLED'],
};
export function visibilityOf(kind: keyof typeof LIVE_STATUSES, status: string, startAt: string, endAt: string, now = Date.now()): Visibility {
  if (!LIVE_STATUSES[kind].includes(status)) return 'unknown';
  const start = Date.parse(startAt);
  const end = Date.parse(endAt);
  if (Number.isNaN(start) || Number.isNaN(end)) return 'unknown';
  if (now < start) return 'upcoming';
  if (now >= end) return 'expired';
  return 'live';
}
const VISIBILITY_TEXT: Record<Visibility, string> = { upcoming: '未开始', live: '展示中', expired: '已到期', unknown: '' };
const VISIBILITY_COLOR: Record<Visibility, string> = { upcoming: 'blue', live: 'green', expired: 'orange', unknown: 'default' };
export function VisibilityTag({ visibility }: { visibility: Visibility }) {
  if (visibility === 'unknown') return null;
  return <Tag color={VISIBILITY_COLOR[visibility]}>{VISIBILITY_TEXT[visibility]}</Tag>;
}

/**
 * 能否直接当图片地址用：只有完整的 http(s) / data: 地址才算。
 * 营销图片读写一体存 OSS Key（2026-11-24 裁决）：xxxUrl 是存储值（可能是 Key，如 "marketing/fullcard/a.png"），
 * 不能塞给 <img>；预览一律用服务端拼好的展示地址（displayUrlOf）。
 */
export function isDisplayableImage(value: string | null | undefined): value is string {
  return !!value && /^(https?:\/\/|data:image\/)/i.test(value);
}

/**
 * 取可用于预览的地址：优先服务端拼好的展示地址；旧后端不返回它时，回退到存储值本身
 * （那时存储值就是完整地址）。存储值为空、或既无展示地址又不是完整地址 → null（不可预览）。
 */
export function displayUrlOf(
  stored: string | null | undefined,
  display: string | null | undefined,
): string | null {
  if (!stored) return null;
  if (isDisplayableImage(display)) return display;
  return isDisplayableImage(stored) ? stored : null;
}

/** 列表缩略图：有可预览地址才渲染图片；否则显示占位并说明，绝不裂图。 */
export function CoverThumb({ url, displayUrl, emptyText }: {
  url: string | null | undefined; displayUrl?: string | null; emptyText: string;
}) {
  const preview = displayUrlOf(url, displayUrl);
  if (preview) return <img className="mkt-entity-cover" src={preview} alt="" />;
  return <span className="mkt-entity-cover mkt-entity-cover-empty"
    title={url ? `已设置图片（${url}），但服务端没有给出可访问的预览地址` : undefined}>
    {url ? '已设图片' : emptyText}
  </span>;
}

/**
 * 图片放大镜：小图右下角带放大角标，点击弹出大图预览（ESC / 关闭 / 点遮罩均可关闭）。
 * 只应传入 isDisplayableImage 为真的地址；素材标识（未解析的 Key）没有可放的图，不渲染本组件。
 */
export function ImageZoom({ src, alt, width = 96, height = 60 }: { src: string; alt: string; width?: number; height?: number }) {
  const [open, setOpen] = useState(false);
  return <>
    <button type="button" className="mkt-zoom-trigger" title="点击放大查看" onClick={() => setOpen(true)}>
      <img src={src} alt={alt} style={{ width, height }} />
      <span className="mkt-zoom-badge" aria-hidden="true"><ZoomInOutlined /></span>
    </button>
    <Modal open={open} footer={null} width={1080} centered title={alt}
      onCancel={() => setOpen(false)} destroyOnHidden>
      <div className="mkt-zoom-stage"><img src={src} alt={alt} /></div>
      <p className="hint" style={{ margin: '8px 0 0', wordBreak: 'break-all' }}>图片地址：{src}</p>
    </Modal>
  </>;
}

/** 时间窗口单元格：两行小字，窄列也放得下（各分区列表共用）。 */
export function TimeCell({ startAt, endAt }: { startAt: string; endAt: string }) {
  return <div className="mkt-entity-text">
    <div className="mkt-entity-sub">{fmtDT(startAt)} 至</div>
    <div className="mkt-entity-sub">{fmtDT(endAt)}</div>
  </div>;
}

// ---------------------------------------------------------------------------
// 分区公共约定
// ---------------------------------------------------------------------------
export type Run = (action: () => Promise<unknown>, ok: string, refresh?: () => unknown) => Promise<void>;
export type AuthOrError = (caught: unknown, fallback: string) => void;
export type TabProps = {
  client: MarketingAdminClient;
  active: boolean;
  busy: boolean;
  run: Run;
  authOrError: AuthOrError;
  onAuthLost: () => void;
};

export const isUnauthenticated = (caught: unknown) =>
  caught instanceof RequestFailure && caught.code === 'UNAUTHENTICATED';

/** 列表接口的筛选参数类型（去掉可选参数自带的 undefined，供 usePagedList 的 Filters 约束使用）。 */
export type FiltersOf<T extends (...args: never[]) => unknown> = NonNullable<Parameters<T>[0]>;

/** 后台补充查询（联想数据源等）：失败不打扰运营，但登录失效仍要交还给入口处理。 */
export function background(promise: Promise<unknown>, onAuthLost: () => void) {
  promise.catch(caught => { if (isUnauthenticated(caught)) onAuthLost(); });
}

/**
 * 活动目录（不分状态，最多 100 条）：给「选活动」类控件联想、以及「关联活动是否在展示」的提示用。
 * 与活动分区自己的筛选互不影响；加载失败时静默，相关控件退化成手填 ID。
 */
export function useCampaignDirectory(client: MarketingAdminClient, active: boolean, onAuthLost: () => void) {
  const [campaigns, setCampaigns] = useState<Campaign[]>();
  const loaded = useRef(false);
  useEffect(() => {
    if (!active || loaded.current) return;
    loaded.current = true;
    background(client.listCampaigns({}, 1, 100).then(page => setCampaigns(page.items)), onAuthLost);
  }, [active, client, onAuthLost]);
  return campaigns;
}

/**
 * 分区列表的数据层：服务端筛选 + 分页 + 竞态防护。
 * - load(filters, page)：按条件取一页；当前页被筛空时自动落到最后一个有数据的页。
 * - ensure(filters)：分区首次激活时加载，之后再激活沿用最近一次条件刷新（保留运营的筛选）。
 * - reload()：动作成功或数据过期（409/404）后刷新当前页。
 * 读取失败置 failed——「加载失败」不能被渲染成「暂无数据」。
 */
export function usePagedList<Row, Filters extends object>(
  fetchPage: (filters: Filters, page: number) => Promise<Page<Row>>,
  authOrError: AuthOrError,
  failureText: string,
) {
  const [rows, setRows] = useState<Row[]>();
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const sequence = useRef(0);
  const latest = useRef<{ filters: Filters; page: number } | undefined>(undefined);
  // 回调存 ref：load/reload/ensure 身份恒定，分区 effect 不会因为内联箭头函数而反复触发。
  const callbacks = useRef({ fetchPage, authOrError, failureText });
  callbacks.current = { fetchPage, authOrError, failureText };

  const load = useCallback(async (filters: Filters, target = 1) => {
    const call = ++sequence.current;
    latest.current = { filters, page: target };
    setLoading(true);
    try {
      let landing = target;
      let result = await callbacks.current.fetchPage(filters, target);
      if (result.items.length === 0 && target > 1) {
        landing = result.total > 0 ? Math.max(1, Math.ceil(result.total / Math.max(1, result.pageSize))) : 1;
        latest.current = { filters, page: landing };
        if (landing !== target) result = await callbacks.current.fetchPage(filters, landing);
      }
      if (call !== sequence.current) return;
      setPage(landing);
      setRows(result.items);
      setTotal(result.total);
      setFailed(false);
    } catch (caught) {
      if (call !== sequence.current) return;
      setFailed(true);
      callbacks.current.authOrError(caught, callbacks.current.failureText);
    } finally {
      if (call === sequence.current) setLoading(false);
    }
  }, []);

  const reload = useCallback(() => (
    latest.current ? load(latest.current.filters, latest.current.page) : Promise.resolve()
  ), [load]);
  const ensure = useCallback((filters: Filters) => (
    latest.current ? reload() : load(filters, 1)
  ), [reload, load]);

  return { rows, page, total, loading, failed, load, reload, ensure };
}

/** 分区切走时收起自己的浮层：抽屉/弹窗挂在 body 下，不会随分区一起隐藏。 */
export function useCloseOnLeave(active: boolean, close: () => void) {
  const closeRef = useRef(close);
  closeRef.current = close;
  useEffect(() => { if (!active) closeRef.current(); }, [active]);
}

/** 列表读取失败的占位：具体原因已经在页头的错误提示里，这里只给一个就地重试入口。 */
export function LoadFailed({ onRetry }: { onRetry: () => void }) {
  return <div className="mkt-load-failed">
    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="列表加载失败">
      <Button size="small" onClick={onRetry}>重试</Button>
    </Empty>
  </div>;
}

// ---------------------------------------------------------------------------
// 表单积木
// ---------------------------------------------------------------------------

/** 抽屉头部：标题 / 状态标签 / 说明在左，关闭在右。抽屉自身关掉 antd 头部，由这里统一各抽屉的头部形态。 */
export function DrawerHead({ title, tag, description, onClose }: {
  title: ReactNode; tag?: ReactNode; description?: ReactNode; onClose: () => void;
}) {
  return <div className="mkt-drawer-head">
    <div className="mkt-drawer-head-text">
      <h3>{title}{tag}</h3>
      {description && <p className="hint">{description}</p>}
    </div>
    <Button type="text" className="mkt-drawer-close" aria-label="关闭" title="关闭" icon={<CloseOutlined />} onClick={onClose} />
  </div>;
}

/**
 * 表单抽屉：头部固定、字段区滚动、操作栏固定在底部——长表单不用滚到底才找得到提交按钮。
 * children 是 <form className="mkt-form-body">，内部依次为 .mkt-form-fields（滚动）与 .mkt-form-actions（固定）。
 * 标题是 section 里真实的 h3（验收脚本按 section 内的 heading 定位新建表单）。
 */
export function FormDrawer({ open, onClose, title, description, label, width = 720, children }: {
  open: boolean; onClose: () => void; title: string; description?: ReactNode; label: string; width?: number;
  children: ReactNode;
}) {
  return <Drawer rootClassName="mkt-drawer mkt-drawer-form" open={open} onClose={onClose} width={width}
    destroyOnHidden closable={false}>
    <section className="mkt-form" aria-label={label}>
      <DrawerHead title={title} description={description} onClose={onClose} />
      {children}
    </section>
  </Drawer>;
}

/** 表单字段单元：label 用 htmlFor 显式关联控件（验收脚本按 label 文案定位控件）。 */
export function Field({ id, label, span, hint, children }: {
  id?: string; label: string; span?: boolean; hint?: ReactNode; children: ReactNode;
}) {
  return <div className={`filter-item${span ? ' mkt-span-2' : ''}`}>
    <label className="mkt-label" htmlFor={id}>{label}</label>
    {children}
    {hint && <span className="hint">{hint}</span>}
  </div>;
}

// F-A-02: campaign service categories are picked from the service-category dictionary face
// (contract 71 v0.3 §4.1) instead of being typed by hand. `foreign` marks an existing
// campaign code that the dictionary no longer lists (deleted or disabled since) — it stays
// rendered and checked so an untouched save preserves stored data instead of silently
// narrowing the campaign to "all categories".
export type CategoryOption = { code: string; name: string; foreign?: boolean };
export type CategoryLoadState = 'loading' | 'ready' | 'failed';

/** 分类字典：活动分区首次激活时加载一次，失败可重试（不把失败当成「没有分类」）。 */
export function useCategoryOptions(categoryClient: ServiceCategoryAdminClient, onAuthLost: () => void, active: boolean) {
  const [state, setState] = useState<CategoryLoadState>('loading');
  const [options, setOptions] = useState<CategoryOption[]>([]);
  const [failure, setFailure] = useState('分类列表读取失败，请重试');
  const started = useRef(false);
  const load = useCallback(async () => {
    setState('loading');
    try {
      // The dictionary face caps pageSize at 50 (contract 74 §4); page through ENABLED rows.
      const next: CategoryOption[] = [];
      for (let page = 1; page <= 20; page++) {
        const result = await categoryClient.list({ status: 'ENABLED' }, page, 50);
        for (const row of result.items) {
          if (!next.some(option => option.code === row.categoryCode)) {
            next.push({ code: row.categoryCode, name: row.categoryName });
          }
        }
        if (next.length >= result.total || result.items.length === 0) break;
      }
      setOptions(next);
      setState('ready');
    } catch (caught) {
      if (isUnauthenticated(caught)) { onAuthLost(); return; }
      setFailure(caught instanceof RequestFailure && (caught.code === 'FORBIDDEN' || caught.code === 'COMMON_FORBIDDEN')
        ? '当前账号未授予 service.category.read，无法读取分类列表；不勾选任何分类 = 面向全部分类'
        : '分类列表读取失败，请重试；不勾选任何分类 = 面向全部分类');
      setState('failed');
    }
  }, [categoryClient, onAuthLost]);
  useEffect(() => {
    if (active && !started.current) { started.current = true; void load(); }
  }, [active, load]);
  return { state, options, failure, retry: load };
}

/** 字典外既有编码补进选项（标 foreign），编辑时未改动不会把已存的分类范围悄悄改成「全部分类」。 */
export function withForeignCategories(options: CategoryOption[], existing: readonly string[]): CategoryOption[] {
  const merged = options.map(option => ({ ...option }));
  for (const code of existing) {
    if (!merged.some(option => option.code === code)) merged.push({ code, name: '', foreign: true });
  }
  return merged;
}

export function CategoryField({ state, options, defaultCodes, failureText, onRetry }: {
  state: CategoryLoadState;
  options: CategoryOption[];
  defaultCodes: string[];
  failureText: string;
  onRetry: () => void;
}) {
  const selected = new Set(defaultCodes);
  return <div className="filter-item mkt-span-2" role="group" aria-label="服务分类">
    <span className="mkt-label">服务分类（可多选，来自服务分类字典）</span>
    {state === 'loading' && <p className="inline-status">分类加载中…</p>}
    {state === 'failed' && <Alert role="alert" type="error" showIcon
      message={<>{failureText}<Button size="small" onClick={onRetry}>重试</Button></>} />}
    {state === 'ready' && options.length === 0 && <p className="hint">字典中暂无启用中的分类。</p>}
    {state === 'ready' && options.length > 0 && <div className="mkt-checks">
      {options.map(option => <label key={option.code} className="mkt-check">
        <input type="checkbox" name="serviceCategoryCode" value={option.code} defaultChecked={selected.has(option.code)} />
        <span>{option.foreign ? `${option.code}（字典外或已停用）` : `${option.name}（${option.code}）`}</span>
      </label>)}
    </div>}
    <p className="hint">不勾选任何分类 = 活动面向全部分类（提交空列表）；勾选的编码按服务分类字典校验，不存在或未启用的分类会被后端拒绝。</p>
  </div>;
}

const MAX_ASSET_BYTES = 2 * 1024 * 1024;

/**
 * 素材：上传（后端校验格式/大小）或直接填写，带预览。
 * 读写一体存 Key（2026-11-24 裁决）：
 * - 输入框里是「存储值」——上传成功回填对象存储的 Key（对象存储未启用时回填素材地址）；
 *   也可以手填 Key 或素材地址（后端会把本 bucket 的完整地址还原成 Key）。提交的就是输入框里的值。
 * - 预览不直接用存储值：Key 不是可访问地址。上传后用回执里的 previewUrl；
 *   回显已有数据时用 defaultDisplayUrl（服务端按存储值拼好的展示地址）；手填的完整地址可直接预览。
 */
export function AssetPicker({ name, label, uploadText, defaultValue, defaultDisplayUrl, client, canManage, onFailure }: {
  name: string; label: string; uploadText: string; defaultValue?: string | null;
  /** 与 defaultValue 配套的展示地址（服务端返回）；defaultValue 本身是完整地址时可省略。 */
  defaultDisplayUrl?: string | null;
  client: MarketingAdminClient; canManage: boolean; onFailure: (caught: unknown) => void;
}) {
  const [value, setValue] = useState(defaultValue ?? '');
  // 回显后用户没改动时，沿用服务端给的展示地址；一旦改动（含清空）就以当前值为准。
  const [pristine, setPristine] = useState(true);
  const [uploadedPreview, setUploadedPreview] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  async function upload(file: File) {
    if (busy) return;
    if (file.size > MAX_ASSET_BYTES) { onFailure(new Error('图片超过 2MiB')); return; }
    if (!/^image\/(png|jpeg)$/.test(file.type)) { onFailure(new Error('仅支持 PNG/JPEG')); return; }
    setBusy(true);
    try {
      const asset = await client.uploadAsset(file);
      // 表单回填 Key（读写一体）；对象存储未启用时后端不返回 objectKey，回填素材地址。
      setValue(asset.objectKey ?? asset.url);
      setUploadedPreview(asset.previewUrl ?? asset.url);
      setPristine(false);
    }
    catch (caught) { onFailure(caught); }
    finally { setBusy(false); }
  }
  const onChange = (next: string) => { setValue(next); setPristine(false); setUploadedPreview(null); };
  const preview = uploadedPreview
    ?? displayUrlOf(value, pristine ? defaultDisplayUrl : null);
  return <div className="filter-item mkt-span-2 mkt-asset">
    <span className="mkt-label">{label}</span>
    <div className="mkt-asset-row">
      <Input name={name} value={value} onChange={event => onChange(event.target.value)} maxLength={512}
        placeholder="上传后自动填入；也可填素材 Key 或 https:// 地址" aria-label={`${label}`} allowClear />
      {canManage && <Upload accept="image/png,image/jpeg" showUploadList={false}
        beforeUpload={file => { void upload(file); return Upload.LIST_IGNORE; }}>
        <Button loading={busy} disabled={busy}>{uploadText}</Button>
      </Upload>}
    </div>
    <span className="hint">PNG/JPEG，不超过 2MiB。上传成功后自动填入图片的存储标识；也可以手填标识或 https:// 地址（保存时统一存成标识）。</span>
    {preview && <img src={preview} alt="素材预览" className="mkt-asset-preview" />}
    {value && !preview && !busy && <span className="mkt-warn">
      当前值（{value}）没有可用的预览地址，这里暂时无法预览；它已保存在活动上，不改动就不会影响展示。
    </span>}
  </div>;
}

/** ID 类字段的联想输入：输入框里是 ID（线上的键），下拉里展示人读得懂的名称与状态；也允许直接粘贴完整 ID。必填由表单在提交时校验。 */
export function IdPicker({ id, value, onChange, options, placeholder }: {
  id: string; value: string; onChange: (value: string) => void;
  options: PickerOption[]; placeholder?: string;
}) {
  return <AutoComplete id={id} value={value} onChange={onChange} options={options}
    style={{ width: '100%' }} placeholder={placeholder} allowClear
    filterOption={(input, option) => (option?.search ?? '').includes(input.trim().toLowerCase())} />;
}

type PickerOption = { value: string; label: ReactNode; search: string };
const searchKey = (...parts: string[]) => parts.join(' ').toLowerCase();

export function campaignPickerOptions(campaigns: Campaign[] | undefined): PickerOption[] {
  return (campaigns ?? []).map(campaign => ({
    value: campaign.campaignId,
    label: <span className="mkt-option">
      <span className="mkt-option-main">{campaign.name}</span>
      <span className="mkt-option-sub">{campaign.title} · {STATUS_TEXT[campaign.status] ?? campaign.status} · {fmtDT(campaign.startAt)} 至 {fmtDT(campaign.endAt)}</span>
    </span>,
    search: searchKey(campaign.campaignId, campaign.name, campaign.title),
  }));
}

export function couponPickerOptions(coupons: CouponCampaign[] | undefined): PickerOption[] {
  return (coupons ?? []).map(coupon => ({
    value: coupon.campaignId,
    label: <span className="mkt-option">
      <span className="mkt-option-main">{coupon.name}</span>
      <span className="mkt-option-sub">¥{coupon.amountOff ?? '--'}${coupon.thresholdAmount ? ` 满${coupon.thresholdAmount}可用` : ' 无门槛'} · {STATUS_TEXT[coupon.status] ?? coupon.status}</span>
    </span>,
    search: searchKey(coupon.campaignId, coupon.name),
  }));
}

/** 驳回原因弹窗：替代 window.prompt——1-500 字，写明原因才能确认。 */
export function RejectModal({ open, title, description, busy, onCancel, onSubmit }: {
  open: boolean; title: string; description?: string; busy: boolean;
  onCancel: () => void; onSubmit: (reason: string) => void;
}) {
  const [reason, setReason] = useState('');
  useEffect(() => { if (open) setReason(''); }, [open]);
  const trimmed = reason.trim();
  return <Modal
    open={open} title={title} onCancel={onCancel} destroyOnHidden
    okText="确认驳回" okButtonProps={{ danger: true, disabled: trimmed.length === 0 }}
    cancelText="取消" confirmLoading={busy}
    onOk={() => onSubmit(trimmed)}>
    {description && <p className="hint">{description}</p>}
    <Input.TextArea value={reason} onChange={event => setReason(event.target.value)} rows={4}
      maxLength={500} showCount placeholder="请填写驳回原因，对方会看到这段文字" />
  </Modal>;
}

const PRE_STYLE: React.CSSProperties = {
  whiteSpace: 'pre-wrap', wordBreak: 'break-all', background: '#fafafa', padding: 12,
  borderRadius: 6, maxHeight: 320, overflow: 'auto', fontSize: 12, margin: 0,
};
const URL_LIKE = /^https?:\/\/\S+$/i;

/** 商家报名材料是商家端提交的合法 JSON（MarketingSupport.safeJson）：整组都是链接时按图片展示，其余原样展开。 */
export function renderMaterials(raw: string | null): ReactNode {
  if (!raw) return <p className="hint" style={{ margin: 0 }}>商家未提交材料。</p>;
  let parsed: unknown;
  try { parsed = JSON.parse(raw); } catch { return <pre style={PRE_STYLE}>{raw}</pre>; }
  if (Array.isArray(parsed) && parsed.length > 0
    && parsed.every(item => typeof item === 'string' && URL_LIKE.test(item))) {
    return <div className="mkt-materials">
      {(parsed as string[]).map((url, index) => <a key={`${index}-${url}`} href={url} target="_blank" rel="noreferrer">
        <img src={url} alt={`报名材料 ${index + 1}`} />
      </a>)}
    </div>;
  }
  return <pre style={PRE_STYLE}>{JSON.stringify(parsed, null, 2)}</pre>;
}
