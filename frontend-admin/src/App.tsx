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

const READ_ACTION = 'merchant.application.read';
const auth = createAuthClient();
const sessions = createSessionClient();
const applications = createMerchantApplicationClient();
const aftersales = createAfterSaleClient();
const reviewAppeals = createReviewAppealClient();

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
  }, [token]);

  if (location.pathname !== '/login' && !token) return <Navigate to="/login" replace />;
  const canRead = permissions !== undefined && permissions.actionCodes.includes(READ_ACTION);
  const canReadAftersale = permissions?.actionCodes.includes('aftersale.read') === true;
  const canReadAppeals = permissions?.actionCodes.includes('review.appeal.read') === true;
  const guard = (element: ReactNode, action = READ_ACTION) => {
    if (!token) return <Navigate to="/login" replace />;
    if (bootstrap === 'loading') return <p role="status">会话加载中</p>;
    if (bootstrap === 'failed') return <div role="alert"><h2>会话或权限查询失败，访问已关闭</h2><button onClick={signOut}>返回登录</button></div>;
    if (!permissions?.actionCodes.includes(action)) return <div role="alert"><h2>{action === READ_ACTION ? '403 · 无申请审核权限' : action === 'aftersale.read' ? '403 · 无售后查看权限' : '403 · 无评价申诉权限'}</h2><p>当前账号未授予 {action}。</p></div>;
    return element;
  };

  return <>
    {location.pathname !== '/login' && <header>
      <strong>宠物平台 · 运营工作台</strong>
      <nav className="workbench-nav" aria-label="工作台导航">{canRead && <Link to="/merchant-applications">商家申请审核</Link>}{canReadAftersale && <Link to="/aftersales">售后工单</Link>}{canReadAppeals && <Link to="/review-appeals">评价申诉</Link>}</nav>
      {session && <span>运营 {session.operatorId} · 会话至 {session.expiresAt}</span>}
      {token && <button onClick={signOut}>退出</button>}
    </header>}
    <Routes>
      <Route path="/login" element={token ? <Navigate to="/" replace /> : <LoginPage onAuthenticated={signIn} auth={auth} />} />
      <Route path="/" element={permissions === undefined ? guard(<></>) : <Navigate to={canRead || !canReadAftersale ? '/merchant-applications' : '/aftersales'} replace />} />
      <Route path="/merchant-applications" element={guard(<ReviewListPage client={applications} canDecide={permissions?.actionCodes.includes('merchant.application.decide') === true && permissions.actionCodes.includes('merchant.identity.reveal')} onAuthLost={signOut} />)} />
      <Route path="/merchant-applications/:applicationId" element={guard(<ReviewDetailPage client={applications} canOperate={permissions?.actionCodes.includes('merchant.application.decide') === true && permissions.actionCodes.includes('merchant.identity.reveal')} onAuthLost={signOut} />)} />
      <Route path="/aftersales" element={guard(<AfterSaleListPage client={aftersales} onAuthLost={signOut} />, 'aftersale.read')} />
      <Route path="/aftersales/:afterSaleId" element={guard(<AfterSaleDetailPage key={location.pathname + location.search} client={aftersales} canHandle={permissions?.actionCodes.includes('aftersale.handle') === true} canDecide={permissions?.actionCodes.includes('aftersale.decide') === true} onAuthLost={signOut} />, 'aftersale.read')} />
      <Route path="/review-appeals" element={guard(<ReviewAppealListPage client={reviewAppeals} canDecide={permissions?.actionCodes.includes('review.appeal.decide') === true} onAuthLost={signOut} />, 'review.appeal.read')} />
      <Route path="/review-appeals/:appealId" element={guard(<ReviewAppealDetailPage key={location.pathname} client={reviewAppeals} canDecide={permissions?.actionCodes.includes('review.appeal.decide') === true} onAuthLost={signOut} />, 'review.appeal.read')} />
      <Route path="*" element={<main style={{ padding: 32 }}><h1>404 · 页面不存在</h1><p><Link to="/">返回工作台</Link></p></main>} />
    </Routes>
  </>;
}

export default function Root() {
  return <BrowserRouter><App /></BrowserRouter>;
}
