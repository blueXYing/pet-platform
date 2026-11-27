import { useCallback, useRef, useState, type ReactNode, useEffect } from 'react';
import { BrowserRouter, Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { Button, type MenuProps } from 'antd';
import { AdminProviders } from './ui/providers';
import { AdminLayout } from './ui/AdminLayout';
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
import AgreementAdminPage, { createAgreementAdminClient } from './pages/AgreementAdminPage';
import TradeOrderListPage from './pages/TradeOrderListPage';
import TradeOrderDetailPage from './pages/TradeOrderDetailPage';
import TradeRefundListPage from './pages/TradeRefundListPage';
import TradeRefundDetailPage from './pages/TradeRefundDetailPage';
import TradeVerificationListPage from './pages/TradeVerificationListPage';
import TradeVerificationDetailPage from './pages/TradeVerificationDetailPage';
import { createMarketingAdminClient } from './api/marketingAdmin';
import { createServiceFlashPriceAdminClient } from './api/serviceFlashPriceAdmin';
import { createCommunityAdminClient } from './api/communityAdmin';
import { createServiceCategoryAdminClient } from './api/serviceCategoryAdmin';
import ServiceCategoryAdminPage from './pages/ServiceCategoryAdminPage';
import MarketingCenterPage from './pages/MarketingCenterPage';
import ServiceFlashPricePage from './pages/ServiceFlashPricePage';
import CommunityModerationPage from './pages/CommunityModerationPage';
import { createSettlementAdminClient } from './api/settlementAdmin';
import SettlementConfigPage from './pages/SettlementConfigPage';
import SettlementReceiverPage from './pages/SettlementReceiverPage';
import SettlementOrderListPage from './pages/SettlementOrderListPage';
import SettlementWithdrawalListPage from './pages/SettlementWithdrawalListPage';
import SettlementSubsidyReportPage from './pages/SettlementSubsidyReportPage';
import SettlementReconcilePage from './pages/SettlementReconcilePage';
import { createMerchantCouponAdminClient } from './api/merchantCouponAdmin';
import MerchantCouponPage from './pages/MerchantCouponPage';
import { createOnboardingAdminClient } from './api/onboardingAdmin';
import OnboardingPage from './pages/OnboardingPage';

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
  const agreementAdmin = createAgreementAdminClient();
const marketingAdmin = createMarketingAdminClient();
const serviceFlashPriceAdmin = createServiceFlashPriceAdminClient();
const communityAdmin = createCommunityAdminClient();
const serviceCategories = createServiceCategoryAdminClient();
const settlementAdmin = createSettlementAdminClient();
const merchantCouponAdmin = createMerchantCouponAdminClient();
const onboardingAdmin = createOnboardingAdminClient();

/** 403 文案与旧版逐字一致；缺省兜底是积分任务配置。 */
const FORBIDDEN_TITLES: Record<string, string> = {
  [READ_ACTION]: '403 · 无申请审核权限',
  'aftersale.read': '403 · 无售后查看权限',
  'review.appeal.read': '403 · 无评价申诉权限',
  'service.review.read': '403 · 无服务审核权限',
  'order.read': '403 · 无订单查询权限',
  'refund.read': '403 · 无退款单查询权限',
  'order.verification-log.read': '403 · 无核销记录权限',
  'merchant.read': '403 · 无商家台账权限',
  'schedule.read': '403 · 无排期查看权限',
  'merchant.staff.read': '403 · 无员工治理权限',
  'encyclopedia.read': '403 · 无百科台账权限',
  'breed.read': '403 · 无品种详情权限',
  'coupon.template.read': '403 · 无券模板权限',
  'coupon.instance.read': '403 · 无券实例台账权限',
  'points.ledger.read': '403 · 无积分台账权限',
  'system.parameter.read': '403 · 无系统参数权限',
  'service.category.read': '403 · 无服务分类权限',
  'community.post.read': '403 · 无社区审核权限',
  'marketing.campaign.read': '403 · 无营销活动权限',
  'service.flash-price.read': '403 · 无秒杀审核权限',
  'coupon.campaign.read': '403 · 无商家券审核权限',
  'coupon.campaign.publish': '403 · 无商家券审核操作权限',
  'settlement.report.read': '403 · 无结算报表权限',
  'settlement.config.write': '403 · 无结算配置权限',
  'settlement.receiver.write': '403 · 无分账接收方管理权限',
  'settlement.receiver.read-sensitive': '403 · 无敏感收款信息查看权限',
  'settlement.instruction.retry': '403 · 无结算异常处理权限',
  'settlement.onboarding.write': '403 · 无商家收单开通权限',
};

// 2026-11-20 用户裁决：运营端刷新不再丢登录态——token 持久化到 localStorage（登出/失效即清除）。
const TOKEN_STORAGE_KEY = 'pet.admin.accessToken';

export function App() {
  const [token, setToken] = useState<string | undefined>(() => localStorage.getItem(TOKEN_STORAGE_KEY) ?? undefined);
  const [session, setSession] = useState<AdminSession>();
  const [permissions, setPermissions] = useState<AdminPermissions>();
  const [bootstrap, setBootstrap] = useState<'idle' | 'loading' | 'failed'>('idle');
  const identityEpoch = useRef(0);
  const location = useLocation();

  async function signIn(nextToken: string) {
    const started = ++identityEpoch.current;
    localStorage.setItem(TOKEN_STORAGE_KEY, nextToken);
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
    serviceFlashPriceAdmin.resetContext(nextToken);
    communityAdmin.resetContext(nextToken);
    serviceCategories.resetContext(nextToken);
    agreementAdmin.resetContext(nextToken);
    settlementAdmin.resetContext(nextToken);
    merchantCouponAdmin.resetContext(nextToken);
    onboardingAdmin.resetContext(nextToken);
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
      localStorage.removeItem(TOKEN_STORAGE_KEY);
      setBootstrap('failed');
    }
  }

  // 刷新/重开恢复：用持久化 token 重新接线并做服务端会话校验（失效则回登录页）。
  const restoredRef = useRef(false);
  useEffect(() => {
    if (restoredRef.current) return;
    restoredRef.current = true;
    const stored = localStorage.getItem(TOKEN_STORAGE_KEY);
    if (stored) void signIn(stored);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const signOut = useCallback(() => {
    identityEpoch.current++;
    localStorage.removeItem(TOKEN_STORAGE_KEY);
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
    serviceFlashPriceAdmin.resetContext();
    communityAdmin.resetContext();
    serviceCategories.resetContext();
    agreementAdmin.resetContext();
    settlementAdmin.resetContext();
    merchantCouponAdmin.resetContext();
    onboardingAdmin.resetContext();
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
  const canReadFlashPrices = permissions?.actionCodes.includes('service.flash-price.read') === true;
  const canReviewFlashPrices = permissions?.actionCodes.includes('service.flash-price.review') === true;
  const canReadMerchantCoupons = permissions?.actionCodes.includes('coupon.campaign.read') === true;
  const canReadSettlement = permissions?.actionCodes.includes('settlement.report.read') === true;
  const canWriteSettlementConfig = permissions?.actionCodes.includes('settlement.config.write') === true;
  const canWriteSettlementReceiver = permissions?.actionCodes.includes('settlement.receiver.write') === true;
  const canReadSensitiveReceiver = permissions?.actionCodes.includes('settlement.receiver.read-sensitive') === true;
  const canRetrySettlement = permissions?.actionCodes.includes('settlement.instruction.retry') === true;
  const canWriteOnboarding = permissions?.actionCodes.includes('settlement.onboarding.write') === true;
  const canReadSensitiveOnboarding = permissions?.actionCodes.includes('settlement.onboarding.read-sensitive') === true;
  const guard = (element: ReactNode, action: string | readonly string[] = READ_ACTION) => {
    if (!token) return <Navigate to="/login" replace />;
    if (bootstrap === 'loading') return <div className="page"><p role="status">会话加载中</p></div>;
    if (bootstrap === 'failed') return <div className="page" role="alert"><h2>会话或权限查询失败，访问已关闭</h2><Button onClick={signOut}>返回登录</Button></div>;
    const required = typeof action === 'string' ? [action] : action;
    if (!required.some(code => permissions?.actionCodes.includes(code))) {
      const primary = required[0];
      return <div className="page" role="alert"><h2>{FORBIDDEN_TITLES[primary] ?? '403 · 无积分任务配置权限'}</h2><p>当前账号未授予 {required.join(' 或 ')}。</p></div>;
    }
    return element;
  };
  // 菜单项内放真实 <Link>：导航保持 link 角色（验收脚本按 link 名字点击），Menu onClick 兜底。
  const menuItem = (key: string, label: string, show: boolean) => show ? { key, label: <Link to={key}>{label}</Link> } as const : false as const;
  const menuGroups: NonNullable<MenuProps['items']> = [
    { type: 'group', label: '审核中心', children: [
      menuItem('/merchant-applications', '商家申请审核', canRead),
      menuItem('/services', '服务审核', canReadServices),
      menuItem('/aftersales', '售后工单', canReadAftersale),
      menuItem('/review-appeals', '评价申诉', canReadAppeals),
      menuItem('/ops/community', '社区审核', canReadCommunity),
    ].filter(Boolean) as NonNullable<MenuProps['items']> },
    { type: 'group', label: '交易', children: [
      menuItem('/trade/orders', '订单总览', canReadOrders),
      menuItem('/trade/refunds', '退款单', canReadRefunds),
      menuItem('/trade/verifications', '核销记录', canReadVerifications),
    ].filter(Boolean) as NonNullable<MenuProps['items']> },
    { type: 'group', label: '结算资金', children: [
      menuItem('/settlement/configs', '商家结算配置', canReadSettlement || canWriteSettlementConfig),
      menuItem('/settlement/receivers', '分账接收方', canReadSettlement),
      menuItem('/settlement/onboardings', '商家收单开通', canReadSettlement || canWriteOnboarding),
      menuItem('/settlement/orders', '结算单与指令', canReadSettlement),
      menuItem('/settlement/withdrawals', '商家提现台账', canReadSettlement),
      menuItem('/settlement/subsidy-report', '平台补贴应付报表', canReadSettlement),
      menuItem('/settlement/reconcile', '日对账差异', canReadSettlement),
    ].filter(Boolean) as NonNullable<MenuProps['items']> },
    { type: 'group', label: '治理', children: [
      menuItem('/governance/merchants', '商家台账', canReadLedger),
      menuItem('/governance/schedule', '排期查看', canReadSchedule),
      menuItem('/governance/staff', '员工治理', canReadStaff),
    ].filter(Boolean) as NonNullable<MenuProps['items']> },
    { type: 'group', label: '运营', children: [
      menuItem('/ops/marketing', '营销活动', canReadMarketing),
      menuItem('/ops/service-flash-prices', '秒杀审核', canReadFlashPrices),
      menuItem('/ops/merchant-coupons', '商家券审核', canReadMerchantCoupons),
      menuItem('/ops/coupons/templates', '券模板', canReadCoupons),
      menuItem('/ops/coupons/instances', '券实例台账', canReadInstances),
      menuItem('/ops/ledger/points', '积分台账', canReadPointsLedger),
      menuItem('/points-task-config', '积分任务配置', canReadPointsConfig),
      menuItem('/ops/service-categories', '服务分类', canReadServiceCategories),
      menuItem('/ops/baike/categories', '百科分类', canReadEncyclopedia),
      menuItem('/ops/baike/breeds', '品种库', canReadBreeds),
    ].filter(Boolean) as NonNullable<MenuProps['items']> },
    { type: 'group', label: '系统', children: [
      menuItem('/ops/settings/runtime', '系统设置', canReadSettings),
      menuItem('/ops/settings/agreement', '协议管理', canReadSettings),
    ].filter(Boolean) as NonNullable<MenuProps['items']> },
  ];
  const routes = <Routes>
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
    <Route path="/ops/baike/categories" element={guard(<BaikeAdminCategoriesPage client={encyclopedia} canWrite={permissions?.actionCodes.includes('encyclopedia.manage') === true} onAuthLost={signOut} />, 'encyclopedia.read')} />
    <Route path="/ops/baike/breeds" element={guard(<BaikeAdminBreedsPage client={encyclopedia} canWrite={permissions?.actionCodes.includes('breed.manage') === true} canPublish={permissions?.actionCodes.includes('encyclopedia.publish') === true} onAuthLost={signOut} />, 'breed.read')} />
    <Route path="/ops/coupons/templates" element={guard(<CouponTemplatePage client={couponAdmin} canWrite={permissions?.actionCodes.includes('coupon.template.manage') === true} canGrant={permissions?.actionCodes.includes('coupon.instance.grant') === true} onAuthLost={signOut} />, 'coupon.template.read')} />
    <Route path="/ops/coupons/instances" element={guard(<CouponInstanceLedgerPage client={couponAdmin} canVoid={permissions?.actionCodes.includes('coupon.instance.void') === true} onAuthLost={signOut} />, 'coupon.instance.read')} />
    <Route path="/ops/ledger/points" element={guard(<PointsLedgerPage client={ledgerAdmin} onAuthLost={signOut} />, 'points.ledger.read')} />
    <Route path="/ops/settings/runtime" element={guard(<SettingsRuntimePage client={ledgerAdmin} onAuthLost={signOut} />, 'system.parameter.read')} />
    <Route path="/ops/settings/agreement" element={guard(<AgreementAdminPage client={agreementAdmin} canWrite={permissions?.actionCodes.includes('merchant.application.decide') === true} onAuthLost={signOut} />, 'system.parameter.read')} />
    <Route path="/ops/service-categories" element={guard(<ServiceCategoryAdminPage client={serviceCategories} canWrite={canManageServiceCategories} onAuthLost={signOut} />, 'service.category.read')} />
    <Route path="/ops/service-flash-prices" element={guard(<ServiceFlashPricePage client={serviceFlashPriceAdmin} canReview={canReviewFlashPrices} onAuthLost={signOut} />, 'service.flash-price.read')} />
    <Route path="/ops/merchant-coupons" element={guard(<MerchantCouponPage client={merchantCouponAdmin} canReview={canPublishCouponCampaigns} onAuthLost={signOut} />, 'coupon.campaign.read')} />
    <Route path="/ops/community" element={guard(<CommunityModerationPage client={communityAdmin} canModerate={canModerateCommunity} canHandleReports={canHandleCommunityReports} onAuthLost={signOut} />, 'community.post.read')} />
    <Route path="/ops/marketing" element={guard(<MarketingCenterPage client={marketingAdmin} couponClient={couponAdmin} categoryClient={serviceCategories} canManage={canManageMarketing} canPublish={canPublishMarketing} canReview={canReviewMarketing} canCouponManage={canManageCouponCampaigns} canCouponPublish={canPublishCouponCampaigns} onAuthLost={signOut} />, 'marketing.campaign.read')} />
    <Route path="/settlement/configs" element={guard(<SettlementConfigPage client={settlementAdmin} canWrite={canWriteSettlementConfig} onAuthLost={signOut} />, ['settlement.report.read', 'settlement.config.write'])} />
    <Route path="/settlement/receivers" element={guard(<SettlementReceiverPage client={settlementAdmin} canWrite={canWriteSettlementReceiver} canReadSensitive={canReadSensitiveReceiver} onAuthLost={signOut} />, 'settlement.report.read')} />
    <Route path="/settlement/onboardings" element={guard(<OnboardingPage client={onboardingAdmin} canWrite={canWriteOnboarding} canReadSensitive={canReadSensitiveOnboarding} onAuthLost={signOut} />, ['settlement.report.read', 'settlement.onboarding.write'])} />
    <Route path="/settlement/orders" element={guard(<SettlementOrderListPage client={settlementAdmin} canRetry={canRetrySettlement} onAuthLost={signOut} />, 'settlement.report.read')} />
    <Route path="/settlement/withdrawals" element={guard(<SettlementWithdrawalListPage client={settlementAdmin} onAuthLost={signOut} />, 'settlement.report.read')} />
    <Route path="/settlement/subsidy-report" element={guard(<SettlementSubsidyReportPage client={settlementAdmin} onAuthLost={signOut} />, 'settlement.report.read')} />
    <Route path="/settlement/reconcile" element={guard(<SettlementReconcilePage client={settlementAdmin} canResolve={canRetrySettlement} onAuthLost={signOut} />, 'settlement.report.read')} />
    <Route path="*" element={<main style={{ padding: 32 }}><h1>404 · 页面不存在</h1><p><Link to="/">返回工作台</Link></p></main>} />
  </Routes>;
  if (location.pathname === '/login') return routes;
  return <AdminLayout
    menuItems={menuGroups}
    identity={session && <span>运营 {session.operatorId} · 会话至 {session.expiresAt}</span>}
    signOut={signOut}
    pathname={location.pathname}
  >{routes}</AdminLayout>;
}

export default function Root() {
  return <AdminProviders><BrowserRouter><App /></BrowserRouter></AdminProviders>;
}
