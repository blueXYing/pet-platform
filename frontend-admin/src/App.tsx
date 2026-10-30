import { useCallback, useRef, useState, type ReactNode } from 'react';
import { BrowserRouter, Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { createAuthClient, createSessionClient, type AdminPermissions, type AdminSession } from './api/adminAuth';
import { createMerchantApplicationClient } from './api/merchantApplications';
import LoginPage from './pages/LoginPage';
import ReviewListPage from './pages/ReviewListPage';
import ReviewDetailPage from './pages/ReviewDetailPage';
import { createAfterSaleClient } from './api/aftersales';
import AfterSaleListPage from './pages/AfterSaleListPage';
import AfterSaleDetailPage from './pages/AfterSaleDetailPage';
import { createReviewAppealClient } from './api/reviewAppeals';
import ReviewAppealListPage from './pages/ReviewAppealListPage';
import ReviewAppealDetailPage from './pages/ReviewAppealDetailPage';
import { createPointsTaskConfigClient } from './api/pointsTaskConfig';
import PointsTaskConfigPage from './pages/PointsTaskConfigPage';
import { createServiceReviewClient } from './api/serviceReview';
import ServiceReviewListPage from './pages/ServiceReviewListPage';
import ServiceReviewDetailPage from './pages/ServiceReviewDetailPage';
import { createTradeQueryClient } from './api/tradeQuery';
import { createMerchantGovernanceClient } from './api/merchantGovernance';
import { createScheduleViewClient } from './api/scheduleView';
import { createStaffGovernanceClient } from './api/staffGovernance';
import { createEncyclopediaAdminClient } from './api/encyclopediaAdmin';
import { createCouponAdminClient } from './api/couponAdmin';
import { createLedgerAdminClient } from './api/ledgerAdmin';
import { createMarketingAdminClient } from './api/marketingAdmin';
import { createCommunityAdminClient } from './api/communityAdmin';
import { createServiceCategoryAdminClient } from './api/serviceCategoryAdmin';
import ServiceCategoryAdminPage from './pages/ServiceCategoryAdminPage';
import MarketingCenterPage from './pages/MarketingCenterPage';
import CommunityModerationPage from './pages/CommunityModerationPage';
import MerchantLedgerListPage from './pages/MerchantLedgerListPage';
import MerchantLedgerDetailPage from './pages/MerchantLedgerDetailPage';
import ScheduleViewPage from './pages/ScheduleViewPage';
import StaffGovernancePage from './pages/StaffGovernancePage';
import BaikeAdminCategoriesPage from './pages/BaikeAdminCategoriesPage';
import BaikeAdminBreedsPage from './pages/BaikeAdminBreedsPage';
import CouponTemplatePage from './pages/CouponTemplatePage';
import CouponInstanceLedgerPage from './pages/CouponInstanceLedgerPage';
import PointsLedgerPage from './pages/PointsLedgerPage';
import SettingsRuntimePage from './pages/SettingsRuntimePage';
import TradeOrderListPage from './pages/TradeOrderListPage';
import TradeOrderDetailPage from './pages/TradeOrderDetailPage';
import TradeRefundListPage from './pages/TradeRefundListPage';
import TradeRefundDetailPage from './pages/TradeRefundDetailPage';
import TradeVerificationListPage from './pages/TradeVerificationListPage';
import TradeVerificationDetailPage from './pages/TradeVerificationDetailPage';

const READ_ACTION = 'merchant.application.read';
const auth = createAuthClient();
const sessions = createSessionClient();
const applications = createMerchantApplicationClient();
const aftersales = createAfterSaleClient();
const reviewAppeals = createReviewAppealClient();
const pointsTaskConfig = createPointsTaskConfigClient();
const serviceReview = createServiceReviewClient();
const tradeQuery = createTradeQueryClient();
const merchantGovernance = createMerchantGovernanceClient();
const scheduleView = createScheduleViewClient();
const staffGovernance = createStaffGovernanceClient();
const encyclopedia = createEncyclopediaAdminClient();
const couponAdmin = createCouponAdminClient();
const ledgerAdmin = createLedgerAdminClient();
const marketingAdmin = createMarketingAdminClient();
const communityAdmin = createCommunityAdminClient();
const serviceCategories = createServiceCategoryAdminClient();

export function App() {
  const [token, setToken] = useState<string>();
  const [session, setSession] = useState<AdminSession>();
  const [permissions, setPermissions] = useState<AdminPermissions>();
  const [bootstrap, setBootstrap] = useState<'idle' | 'loading' | 'failed'>('idle');
  const identityEpoch = useRef(0);
  const location = useLocation();

  async function signIn(nextToken: string) {
    const started = ++identityEpoch.current;
    setBootstrap('loading');
    setSession(undefined);
    setPermissions(undefined);
    setToken(nextToken);
    applications.setToken(nextToken);
    aftersales.resetContext(nextToken);
    reviewAppeals.resetContext(nextToken);
    pointsTaskConfig.resetContext(nextToken);
    serviceReview.resetContext(nextToken);
    tradeQuery.resetContext(nextToken);
    merchantGovernance.resetContext(nextToken);
    scheduleView.resetContext(nextToken);
    staffGovernance.resetContext(nextToken);
    encyclopedia.resetContext(nextToken);
    couponAdmin.resetContext(nextToken);
    ledgerAdmin.resetContext(nextToken);
    marketingAdmin.resetContext(nextToken);
    communityAdmin.resetContext(nextToken);
    serviceCategories.resetContext(nextToken);
    try {
      const nextSession = await sessions.session(nextToken);
      const nextPermissions = await sessions.permissions(nextToken);
      if (started !== identityEpoch.current) return;
      if (nextSession.audience !== 'ADMIN_WEB' || nextSession.operatorId !== nextPermissions.operatorId || nextSession.authzVersion !== nextPermissions.authzVersion) throw new Error('INCONSISTENT_AUTHORITY');
      aftersales.bindIdentity(nextSession.operatorId);
      reviewAppeals.bindIdentity(nextSession.operatorId);
      setSession(nextSession);
      setPermissions(nextPermissions);
      setBootstrap('idle');
    } catch {
      if (started !== identityEpoch.current) return;
      // Session/permission queries fail closed: no business page renders on doubt.
      setBootstrap('failed');
    }
  }

  const signOut = useCallback(() => {
    identityEpoch.current++;
    if (token) void sessions.logout(token).catch(() => undefined);
    setToken(undefined);
    setSession(undefined);
    setPermissions(undefined);
    setBootstrap('idle');
    applications.resetContext();
    aftersales.resetContext();
    reviewAppeals.resetContext();
    pointsTaskConfig.resetContext();
    serviceReview.resetContext();
    tradeQuery.resetContext();
    merchantGovernance.resetContext();
    scheduleView.resetContext();
    staffGovernance.resetContext();
    encyclopedia.resetContext();
    couponAdmin.resetContext();
    ledgerAdmin.resetContext();
    marketingAdmin.resetContext();
    communityAdmin.resetContext();
    serviceCategories.resetContext();
  }, [token]);

  if (location.pathname !== '/login' && !token) return <Navigate to="/login" replace />;
  const canRead = permissions !== undefined && permissions.actionCodes.includes(READ_ACTION);
  const canReadAftersale = permissions?.actionCodes.includes('aftersale.read') === true;
  const canReadAppeals = permissions?.actionCodes.includes('review.appeal.read') === true;
  const canReadPointsConfig = permissions?.actionCodes.includes('points.task.config.read') === true;
  const canReadServices = permissions?.actionCodes.includes('service.review.read') === true;
  const canReadOrders = permissions?.actionCodes.includes('order.read') === true;
  const canReadRefunds = permissions?.actionCodes.includes('refund.read') === true;
  const canReadVerifications = permissions?.actionCodes.includes('order.verification-log.read') === true;
  const canReadLedger = permissions?.actionCodes.includes('merchant.read') === true;
  const canReadSchedule = permissions?.actionCodes.includes('schedule.read') === true;
  const canReadStaff = permissions?.actionCodes.includes('merchant.staff.read') === true;
  const canReadEncyclopedia = permissions?.actionCodes.includes('encyclopedia.read') === true;
  const canReadBreeds = permissions?.actionCodes.includes('breed.read') === true;
  const canReadCoupons = permissions?.actionCodes.includes('coupon.template.read') === true;
  const canReadInstances = permissions?.actionCodes.includes('coupon.instance.read') === true;
  const canReadPointsLedger = permissions?.actionCodes.includes('points.ledger.read') === true;
  const canReadSettings = permissions?.actionCodes.includes('system.parameter.read') === true;
  const canReadMarketing = permissions?.actionCodes.includes('marketing.campaign.read') === true;
  const canManageMarketing = permissions?.actionCodes.includes('marketing.campaign.manage') === true;
  const canPublishMarketing = permissions?.actionCodes.includes('marketing.campaign.publish') === true;
  const canReviewMarketing = permissions?.actionCodes.includes('marketing.enrollment.review') === true;
  const canManageCouponCampaigns = permissions?.actionCodes.includes('coupon.campaign.manage') === true;
  const canPublishCouponCampaigns = permissions?.actionCodes.includes('coupon.campaign.publish') === true;
  const canReadCommunity = permissions?.actionCodes.includes('community.post.read') === true;
  const canModerateCommunity = permissions?.actionCodes.includes('community.post.moderate') === true;
  const canHandleCommunityReports = permissions?.actionCodes.includes('community.report.handle') === true;
  const canReadServiceCategories = permissions?.actionCodes.includes('service.category.read') === true;
  const canManageServiceCategories = permissions?.actionCodes.includes('service.category.manage') === true;
  const guard = (element: ReactNode, action = READ_ACTION) => {
    if (!token) return <Navigate to="/login" replace />;
    if (bootstrap === 'loading') return <p role="status">会话加载中</p>;
    if (bootstrap === 'failed') return <div role="alert"><h2>会话或权限查询失败，访问已关闭</h2><button onClick={signOut}>返回登录</button></div>;
    if (!permissions?.actionCodes.includes(action)) return <div role="alert"><h2>{action === READ_ACTION ? '403 · 无申请审核权限' : action === 'aftersale.read' ? '403 · 无售后查看权限' : action === 'review.appeal.read' ? '403 · 无评价申诉权限' : action === 'service.review.read' ? '403 · 无服务审核权限' : action === 'order.read' ? '403 · 无订单查询权限' : action === 'refund.read' ? '403 · 无退款单查询权限' : action === 'order.verification-log.read' ? '403 · 无核销记录权限' : action === 'merchant.read' ? '403 · 无商家台账权限' : action === 'schedule.read' ? '403 · 无排期查看权限' : action === 'merchant.staff.read' ? '403 · 无员工治理权限' : action === 'encyclopedia.read' ? '403 · 无百科台账权限' : action === 'breed.read' ? '403 · 无品种详情权限' : action === 'coupon.template.read' ? '403 · 无券模板权限' : action === 'coupon.instance.read' ? '403 · 无券实例台账权限' : action === 'points.ledger.read' ? '403 · 无积分台账权限' : action === 'system.parameter.read' ? '403 · 无系统参数权限' : action === 'marketing.campaign.read' ? '403 · 无营销活动权限' : action === 'service.category.read' ? '403 · 无服务分类权限' : action === 'community.post.read' ? '403 · 无社区审核权限' : '403 · 无积分任务配置权限'}</h2><p>当前账号未授予 {action}。</p></div>;
    return element;
  };

  return <>
    {location.pathname !== '/login' && <header>
      <strong>宠物平台 · 运营工作台</strong>
      <nav className="workbench-nav" aria-label="工作台导航">{canRead && <Link to="/merchant-applications">商家申请审核</Link>}{canReadAftersale && <Link to="/aftersales">售后工单</Link>}{canReadAppeals && <Link to="/review-appeals">评价申诉</Link>}{canReadPointsConfig && <Link to="/points-task-config">积分任务配置</Link>}{canReadServices && <Link to="/services">服务审核</Link>}{canReadOrders && <Link to="/trade/orders">订单总览</Link>}{canReadRefunds && <Link to="/trade/refunds">退款单</Link>}{canReadVerifications && <Link to="/trade/verifications">核销记录</Link>}{canReadLedger && <Link to="/governance/merchants">商家台账</Link>}{canReadSchedule && <Link to="/governance/schedule">排期查看</Link>}{canReadStaff && <Link to="/governance/staff">员工治理</Link>}{canReadServiceCategories && <Link to="/ops/service-categories">服务分类</Link>}{canReadEncyclopedia && <Link to="/ops/baike/categories">百科分类</Link>}{canReadBreeds && <Link to="/ops/baike/breeds">品种库</Link>}{canReadCoupons && <Link to="/ops/coupons/templates">券模板</Link>}{canReadInstances && <Link to="/ops/coupons/instances">券实例台账</Link>}{canReadPointsLedger && <Link to="/ops/ledger/points">积分台账</Link>}{canReadSettings && <Link to="/ops/settings/runtime">系统设置</Link>}{canReadMarketing && <Link to="/ops/marketing">营销活动</Link>}{canReadCommunity && <Link to="/ops/community">社区审核</Link>}</nav>
      {session && <span>运营 {session.operatorId} · 会话至 {session.expiresAt}</span>}
      {token && <button onClick={signOut}>退出</button>}
    </header>}
    <Routes>
      <Route path="/login" element={token ? <Navigate to="/" replace /> : <LoginPage onAuthenticated={signIn} auth={auth} />} />
      <Route path="/" element={permissions === undefined ? guard(<></>) : <Navigate to={canRead ? '/merchant-applications' : canReadAftersale ? '/aftersales' : canReadPointsConfig ? '/points-task-config' : '/merchant-applications'} replace />} />
      <Route path="/merchant-applications" element={guard(<ReviewListPage client={applications} canDecide={permissions?.actionCodes.includes('merchant.application.decide') === true && permissions.actionCodes.includes('merchant.identity.reveal')} onAuthLost={signOut} />)} />
      <Route path="/merchant-applications/:applicationId" element={guard(<ReviewDetailPage client={applications} canOperate={permissions?.actionCodes.includes('merchant.application.decide') === true && permissions.actionCodes.includes('merchant.identity.reveal')} onAuthLost={signOut} />)} />
      <Route path="/aftersales" element={guard(<AfterSaleListPage client={aftersales} onAuthLost={signOut} />, 'aftersale.read')} />
      <Route path="/aftersales/:afterSaleId" element={guard(<AfterSaleDetailPage key={location.pathname + location.search} client={aftersales} canHandle={permissions?.actionCodes.includes('aftersale.handle') === true} canDecide={permissions?.actionCodes.includes('aftersale.decide') === true} onAuthLost={signOut} />, 'aftersale.read')} />
      <Route path="/review-appeals" element={guard(<ReviewAppealListPage client={reviewAppeals} canDecide={permissions?.actionCodes.includes('review.appeal.decide') === true} onAuthLost={signOut} />, 'review.appeal.read')} />
      <Route path="/review-appeals/:appealId" element={guard(<ReviewAppealDetailPage key={location.pathname} client={reviewAppeals} canDecide={permissions?.actionCodes.includes('review.appeal.decide') === true} onAuthLost={signOut} />, 'review.appeal.read')} />
      <Route path="/points-task-config" element={guard(<PointsTaskConfigPage client={pointsTaskConfig} canWrite={permissions?.actionCodes.includes('points.task.config.write') === true} onAuthLost={signOut} />, 'points.task.config.read')} />
      <Route path="/services" element={guard(<ServiceReviewListPage client={serviceReview} onAuthLost={signOut} />, 'service.review.read')} />
      <Route path="/services/:serviceId" element={guard(<ServiceReviewDetailPage key={location.pathname} client={serviceReview} canDecide={permissions?.actionCodes.includes('service.review.decide') === true} canForce={permissions?.actionCodes.includes('service.force.offline') === true} onAuthLost={signOut} />, 'service.review.read')} />
      <Route path="/trade/orders" element={guard(<TradeOrderListPage client={tradeQuery} onAuthLost={signOut} />, 'order.read')} />
      <Route path="/trade/orders/:orderId" element={guard(<TradeOrderDetailPage key={location.pathname} client={tradeQuery} onAuthLost={signOut} />, 'order.read')} />
      <Route path="/trade/refunds" element={guard(<TradeRefundListPage client={tradeQuery} onAuthLost={signOut} />, 'refund.read')} />
      <Route path="/trade/refunds/:refundOrderId" element={guard(<TradeRefundDetailPage key={location.pathname} client={tradeQuery} onAuthLost={signOut} />, 'refund.read')} />
      <Route path="/trade/verifications" element={guard(<TradeVerificationListPage client={tradeQuery} onAuthLost={signOut} />, 'order.verification-log.read')} />
      <Route path="/trade/verifications/:verificationId" element={guard(<TradeVerificationDetailPage key={location.pathname} client={tradeQuery} onAuthLost={signOut} />, 'order.verification-log.read')} />
      <Route path="/governance/merchants" element={guard(<MerchantLedgerListPage client={merchantGovernance} onAuthLost={signOut} />, 'merchant.read')} />
      <Route path="/governance/merchants/:merchantId" element={guard(<MerchantLedgerDetailPage key={location.pathname} client={merchantGovernance} trade={tradeQuery} canDisable={permissions?.actionCodes.includes('merchant.disable') === true} onAuthLost={signOut} />, 'merchant.read')} />
      <Route path="/governance/schedule" element={guard(<ScheduleViewPage client={scheduleView} onAuthLost={signOut} />, 'schedule.read')} />
      <Route path="/governance/staff" element={guard(<StaffGovernancePage client={staffGovernance} onAuthLost={signOut} />, 'merchant.staff.read')} />
      <Route path="/ops/service-categories" element={guard(<ServiceCategoryAdminPage client={serviceCategories} canWrite={canManageServiceCategories} onAuthLost={signOut} />, 'service.category.read')} />
      <Route path="/ops/baike/categories" element={guard(<BaikeAdminCategoriesPage client={encyclopedia} canWrite={permissions?.actionCodes.includes('encyclopedia.manage') === true} onAuthLost={signOut} />, 'encyclopedia.read')} />
      <Route path="/ops/baike/breeds" element={guard(<BaikeAdminBreedsPage client={encyclopedia} canWrite={permissions?.actionCodes.includes('breed.manage') === true} canPublish={permissions?.actionCodes.includes('encyclopedia.publish') === true} onAuthLost={signOut} />, 'breed.read')} />
      <Route path="/ops/coupons/templates" element={guard(<CouponTemplatePage client={couponAdmin} canWrite={permissions?.actionCodes.includes('coupon.template.manage') === true} canGrant={permissions?.actionCodes.includes('coupon.instance.grant') === true} onAuthLost={signOut} />, 'coupon.template.read')} />
      <Route path="/ops/coupons/instances" element={guard(<CouponInstanceLedgerPage client={couponAdmin} canVoid={permissions?.actionCodes.includes('coupon.instance.void') === true} onAuthLost={signOut} />, 'coupon.instance.read')} />
      <Route path="/ops/ledger/points" element={guard(<PointsLedgerPage client={ledgerAdmin} onAuthLost={signOut} />, 'points.ledger.read')} />
      <Route path="/ops/settings/runtime" element={guard(<SettingsRuntimePage client={ledgerAdmin} onAuthLost={signOut} />, 'system.parameter.read')} />
      <Route path="/ops/community" element={guard(<CommunityModerationPage client={communityAdmin} canModerate={canModerateCommunity} canHandleReports={canHandleCommunityReports} onAuthLost={signOut} />, 'community.post.read')} />
      <Route path="/ops/marketing" element={guard(<MarketingCenterPage client={marketingAdmin} couponClient={couponAdmin} categoryClient={serviceCategories} canManage={canManageMarketing} canPublish={canPublishMarketing} canReview={canReviewMarketing} canCouponManage={canManageCouponCampaigns} canCouponPublish={canPublishCouponCampaigns} onAuthLost={signOut} />, 'marketing.campaign.read')} />
      <Route path="*" element={<main style={{ padding: 32 }}><h1>404 · 页面不存在</h1><p><Link to="/">返回工作台</Link></p></main>} />
    </Routes>
  </>;
}

export default function Root() {
  return <BrowserRouter><App /></BrowserRouter>;
}
