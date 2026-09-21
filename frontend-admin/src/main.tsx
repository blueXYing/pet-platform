import { StrictMode, lazy, Suspense } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';

// The default production build renders the real operations app and contains no
// demo identity selector or fixture chunk; DEV/fixture keeps the engineering demo.
const Root = import.meta.env.DEV || import.meta.env.MODE === 'fixture'
  ? lazy(() => import('./FixtureApp'))
  : lazy(() => import('./App'));
createRoot(document.getElementById('root')!).render(<StrictMode><Suspense fallback={<p>加载中</p>}><Root /></Suspense></StrictMode>);
