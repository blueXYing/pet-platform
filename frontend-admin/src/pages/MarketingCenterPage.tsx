import { useEffect, useRef, useState, type ReactNode } from 'react';
import { describeMarketingFailure, isMarketingStale, type MarketingAdminClient } from '../api/marketingAdmin';
import { RequestFailure } from '../request';
import type { CouponAdminClient } from '../api/couponAdmin';
import type { ServiceCategoryAdminClient } from '../api/serviceCategoryAdmin';
import { ErrorAlert, InlineStatus } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';
import { CampaignsTab } from './marketing/CampaignsTab';
import { CouponsTab } from './marketing/CouponsTab';
import { EnrollmentsTab } from './marketing/EnrollmentsTab';
import { HomeBlocksTab } from './marketing/HomeBlocksTab';
import { PlacementsTab } from './marketing/PlacementsTab';
import { isUnauthenticated, type AuthOrError, type Run } from './marketing/shared';

type TabKey = 'campaigns' | 'placements' | 'home' | 'enrollments' | 'coupons';
const TABS: { key: TabKey; label: string }[] = [
  { key: 'campaigns', label: '活动' },
  { key: 'placements', label: '投放位' },
  { key: 'home', label: '首页内容位' },
  { key: 'enrollments', label: '商家报名' },
  { key: 'coupons', label: '券活动' },
];
/** 成功提示的停留时长：它是即时反馈，读完自动收起；错误提示会一直留到下一次操作。 */
const NOTICE_TTL_MS = 8000;

/**
 * 营销活动中心：五个分区共用一套消息 / 忙状态，数据各自管理。
 * 分区懒加载——切到哪个分区才拉哪个分区的数据。五个列表接口各受不同权限控制
 * （marketing.campaign.read / placement.read / enrollment.read …），一进页面就全拉会让只有部分权限的
 * 运营一上来就看到一排「读取失败」；现在报错只落在对应的分区里。
 */
export default function MarketingCenterPage({ client, couponClient, categoryClient, canManage, canPublish, canReview, canCouponManage, canCouponPublish, onAuthLost }: {
  client: MarketingAdminClient;
  couponClient: CouponAdminClient;
  categoryClient: ServiceCategoryAdminClient;
  canManage: boolean;
  canPublish: boolean;
  canReview: boolean;
  canCouponManage: boolean;
  canCouponPublish: boolean;
  onAuthLost: () => void;
}) {
  const [tab, setTab] = useState<TabKey>('campaigns');
  // 分区首次打开后才挂载，之后保持挂载：切回来时筛选条件还在，数据会按最近一次条件刷新。
  const [visited, setVisited] = useState<Record<TabKey, boolean>>({
    campaigns: true, placements: false, home: false, enrollments: false, coupons: false,
  });
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const noticeTimer = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(noticeTimer.current), []);

  function switchTab(key: TabKey) {
    setTab(key);
    setVisited(current => (current[key] ? current : { ...current, [key]: true }));
  }

  const authOrError: AuthOrError = (caught, fallback) => {
    if (isUnauthenticated(caught)) { onAuthLost(); return; }
    // 列表读取遇到 404，不是「某条数据被删了」，而是接口本身不存在（功能开关未开 / 未部署）。
    // 这时不能套用单条数据的「已为你刷新列表」，要如实说接口不可用。
    const listEndpointMissing = caught instanceof RequestFailure && caught.code === 'COMMON_NOT_FOUND' && fallback.startsWith('读取');
    setError(listEndpointMissing
      ? `${fallback}：接口不存在或功能未开通，请联系管理员确认配置`
      : describeMarketingFailure(caught, fallback));
  };
  /** 所有写操作的唯一入口：成功给提示并刷新，失败给可读原因；版本冲突 / 数据不存在时额外刷新一次，兑现文案里的「已为你刷新」。 */
  const run: Run = async (action, ok, refresh) => {
    window.clearTimeout(noticeTimer.current);
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await action();
      setNotice(ok);
      noticeTimer.current = window.setTimeout(() => setNotice(''), NOTICE_TTL_MS);
      await refresh?.();
    } catch (caught) {
      authOrError(caught, '操作失败');
      if (isMarketingStale(caught)) await refresh?.();
    } finally {
      setBusy(false);
    }
  };

  const shared = { client, busy, run, authOrError, onAuthLost };
  const panes: Record<TabKey, ReactNode> = {
    campaigns: <CampaignsTab {...shared} active={tab === 'campaigns'} categoryClient={categoryClient}
      canManage={canManage} canPublish={canPublish} />,
    placements: visited.placements && <PlacementsTab {...shared} active={tab === 'placements'} canManage={canManage} />,
    home: visited.home && <HomeBlocksTab {...shared} active={tab === 'home'} canManage={canManage} />,
    enrollments: visited.enrollments && <EnrollmentsTab {...shared} active={tab === 'enrollments'} canReview={canReview} />,
    coupons: visited.coupons && <CouponsTab {...shared} couponClient={couponClient} active={tab === 'coupons'}
      canManage={canCouponManage} canPublish={canCouponPublish} />,
  };

  return <PageContainer
    title="营销活动中心"
    hint="营销活动的统一工作台：活动（含会场页）、投放位、首页内容位、商家报名审核、券活动。素材直接上传（PNG/JPEG，≤2MiB）；用户端只加载后台素材地址，新增活动或换图不需要小程序发版，新增卡片模板才需要发版。">
    <nav className="mkt-tabs" aria-label="营销中心分区">
      <div className="mkt-tab-strip" role="group" aria-label="分区切换">
        {TABS.map(item => <button key={item.key} type="button" className="mkt-tab"
          aria-pressed={tab === item.key} onClick={() => switchTab(item.key)}>{item.label}</button>)}
      </div>
      {(notice || error) && <div className="mkt-tab-feedback">
        {notice && <InlineStatus>{notice}</InlineStatus>}
        {error && <ErrorAlert>{error}</ErrorAlert>}
      </div>}
    </nav>
    {TABS.map(item => <div key={item.key} hidden={tab !== item.key}>{panes[item.key]}</div>)}
  </PageContainer>;
}
