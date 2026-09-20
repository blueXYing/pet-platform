import { useState, type ReactNode } from 'react';
import { BrowserRouter, Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { createAuthClient, createSessionClient, type AdminPermissions, type AdminSession } from './api/adminAuth';
import { createMerchantApplicationClient } from './api/merchantApplications';
import LoginPage from './pages/LoginPage';
import ReviewListPage from './pages/ReviewListPage';
import ReviewDetailPage from './pages/ReviewDetailPage';

const READ_ACTION = 'merchant.application.read';
const auth = createAuthClient();
const sessions = createSessionClient();
const applications = createMerchantApplicationClient();

export function App() {
  const [token, setToken] = useState<string>();
  const [session, setSession] = useState<AdminSession>();
  const [permissions, setPermissions] = useState<AdminPermissions>();
  const [bootstrap, setBootstrap] = useState<'idle' | 'loading' | 'failed'>('idle');
  const location = useLocation();

  async function signIn(nextToken: string) {
    setBootstrap('loading');
    setToken(nextToken);
    applications.setToken(nextToken);
    try {
      setSession(await sessions.session(nextToken));
      setPermissions(await sessions.permissions(nextToken));
      setBootstrap('idle');
    } catch {
      // Session/permission queries fail closed: no business page renders on doubt.
      setBootstrap('failed');
    }
  }

  function signOut() {
    if (token) void sessions.logout(token).catch(() => undefined);
    setToken(undefined);
    setSession(undefined);
    setPermissions(undefined);
    setBootstrap('idle');
    applications.resetContext();
  }

  if (location.pathname !== '/login' && !token) return <Navigate to="/login" replace />;
  const canRead = permissions !== undefined && permissions.actionCodes.includes(READ_ACTION);
  const guard = (element: ReactNode) => {
    if (!token) return <Navigate to="/login" replace />;
    if (bootstrap === 'loading') return <p role="status">会话加载中</p>;
    if (bootstrap === 'failed') return <div role="alert"><h2>会话或权限查询失败，访问已关闭</h2><button onClick={signOut}>返回登录</button></div>;
    if (!canRead) return <div role="alert"><h2>403 · 无申请审核权限</h2><p>当前账号未授予 merchant.application.read。</p></div>;
    return element;
  };

  return <>
    {location.pathname !== '/login' && <header>
      <strong>宠物平台 · 运营工作台</strong>
      {session && <span>运营 {session.operatorId} · 会话至 {session.expiresAt}</span>}
      {token && <button onClick={signOut}>退出</button>}
    </header>}
    <Routes>
      <Route path="/login" element={token ? <Navigate to="/" replace /> : <LoginPage onAuthenticated={signIn} auth={auth} />} />
      <Route path="/" element={<Navigate to="/merchant-applications" replace />} />
      <Route path="/merchant-applications" element={guard(<ReviewListPage client={applications} canDecide={permissions?.actionCodes.includes('merchant.application.decide') === true && permissions.actionCodes.includes('merchant.identity.reveal')} onAuthLost={signOut} />)} />
      <Route path="/merchant-applications/:applicationId" element={guard(<ReviewDetailPage client={applications} canOperate={permissions?.actionCodes.includes('merchant.application.decide') === true && permissions.actionCodes.includes('merchant.identity.reveal')} onAuthLost={signOut} />)} />
      <Route path="*" element={<main style={{ padding: 32 }}><h1>404 · 页面不存在</h1><p><Link to="/">返回工作台</Link></p></main>} />
    </Routes>
  </>;
}

export default function Root() {
  return <BrowserRouter><App /></BrowserRouter>;
}
