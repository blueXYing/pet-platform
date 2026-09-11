import { StrictMode, lazy, Suspense } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';

// The default production build contains no demo identity selector or fixture chunk.
const Demo = import.meta.env.DEV || import.meta.env.MODE === 'fixture' ? lazy(() => import('./FixtureApp')) : null;
createRoot(document.getElementById('root')!).render(<StrictMode>{Demo ? <Suspense fallback={<p>加载中</p>}><Demo /></Suspense> : <main><h1>运营服务尚未接入</h1><p>登录与权限接口确认后开放。</p></main>}</StrictMode>);
