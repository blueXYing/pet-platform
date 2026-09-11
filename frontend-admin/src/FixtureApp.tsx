import { useEffect, useMemo, useState } from 'react';
import { BrowserRouter, Link, Route, Routes } from 'react-router-dom';
import { can, createFixtureTransport, fixturePath, profiles, type FixtureSession } from './fixture';
import { createWebClient, RequestFailure } from './request';

function Sample({ session }: { session: FixtureSession }) {
  const client = useMemo(() => createWebClient(createFixtureTransport(session)), [session]);
  const [result, setResult] = useState('');
  useEffect(() => () => client.resetContext(), [client]);
  if (!session.authenticated) return <h2>未登录</h2>;
  if (session.failed) return <h2>权限查询失败，访问已关闭</h2>;
  if (!can(session, 'sample.read')) return <h2>403 · 无权限</h2>;
  async function run(method: 'GET' | 'POST') {
    try {
      const data = await client.request<{ id: string; amount: string; displayStatus: string }>(fixturePath, { method });
      setResult(`${data.id} / ${data.amount} / ${data.displayStatus}`);
    } catch (error) {
      if (error instanceof RequestFailure && error.code === 'STALE_CONTEXT') return;
      setResult(error instanceof Error ? error.message : '请求失败');
    }
  }
  return <><h2>权限示例</h2><p>这里仅验证通用授权和请求适配，没有业务写入。</p><button onClick={() => void run('GET')}>读取示例</button>{can(session, 'sample.execute') && <button onClick={() => void run('POST')}>执行示例动作</button>}<output aria-live="polite">{result}</output></>;
}
export default function FixtureApp() {
  const [profile, setProfile] = useState('anonymous');
  const session = profiles[profile];
  return <BrowserRouter><header><strong>宠物平台 · 运营工程壳</strong><span>内部演示</span></header><div className="layout"><aside><label htmlFor="profile">测试身份</label><select id="profile" value={profile} onChange={event => setProfile(event.target.value)}>{Object.entries(profiles).map(([key, item]) => <option key={key} value={key}>{item.label}</option>)}</select><nav><Link to="/">工程概览</Link>{can(session, 'sample.read') && <Link to="/example">权限示例</Link>}</nav></aside><main><Routes><Route path="/" element={<><h1>运营网页基础工程</h1><p>选择测试身份，检查菜单、路由和按钮权限。</p><p>生产登录与权限接入等待 CCR-PERM-001。当前数据均为内部示例。</p></>} /><Route path="/example" element={<Sample key={profile} session={session} />} /><Route path="*" element={<h1>404 · 页面不存在</h1>} /></Routes></main></div><footer>演示授权不代表服务端鉴权；业务页面和真实权限尚未接入。</footer></BrowserRouter>;
}
