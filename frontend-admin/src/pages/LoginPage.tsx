import { useState } from 'react';
import { Button, Card, Input } from 'antd';
import { RequestFailure } from '../request';
import type { createAuthClient, Attempt, CaptchaChallenge } from '../api/adminAuth';

type AuthClient = ReturnType<typeof createAuthClient>;

function failureText(error: unknown) {
  if (!(error instanceof RequestFailure)) return '网络或服务不可用，请稍后重试';
  if (error.code === 'COMMON_RATE_LIMITED') return '尝试次数过多，请稍后再试';
  if (error.code === 'UNAUTHENTICATED') return '账号或密码不正确，或凭证已失效';
  return `登录未完成（${error.code}）`;
}

export default function LoginPage({ auth, onAuthenticated }: { auth: AuthClient; onAuthenticated: (token: string) => void }) {
  const [account, setAccount] = useState('');
  const [password, setPassword] = useState('');
  const [attempt, setAttempt] = useState<Attempt>();
  const [captcha, setCaptcha] = useState<CaptchaChallenge>();
  const [answer, setAnswer] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function run(step: () => Promise<void>) {
    setBusy(true);
    setError('');
    try { await step(); }
    catch (caught) { setError(failureText(caught)); }
    finally { setBusy(false); }
  }

  // One attempt per form submission; the attempt token (X-Auth-Attempt) and the
  // binding cookie travel with every /auth/* call of this flow.
  const beginLogin = () => run(async () => {
    const next = await auth.createAttempt();
    setAttempt(next);
    const { requiredVerification } = await auth.requirements(next);
    if (requiredVerification === 'CAPTCHA') setCaptcha(await auth.createCaptcha(next));
    else {
      const result = await auth.login(next, account, password, '');
      onAuthenticated(result.accessToken);
    }
  });

  const finishCaptcha = () => run(async () => {
    if (!attempt || !captcha) return;
    const proof = await auth.verifyCaptcha(attempt, captcha.captchaId, answer);
    const result = await auth.login(attempt, account, password, proof.captchaProof);
    onAuthenticated(result.accessToken);
  });

  return <main className="login-page">
    <Card className="login-card">
      <h1>运营登录</h1>
      <p className="hint">仅平台运营账号。无双人审批、无额外 MFA；失败次数过多将触发验证码。</p>
      <form onSubmit={event => { event.preventDefault(); if (!busy) void (captcha ? finishCaptcha() : beginLogin()); }}>
        <label htmlFor="account" className="field-label">账号</label>
        <Input id="account" value={account} onChange={event => setAccount(event.target.value)} autoComplete="username" required />
        <label htmlFor="password" className="field-label">密码</label>
        <Input id="password" type="password" value={password} onChange={event => setPassword(event.target.value)} autoComplete="current-password" required />
        {captcha && <>
          <label htmlFor="captcha" className="field-label">验证码</label>
          <img src={captcha.imageDataUrl} alt="验证码图片" width={160} height={48} />
          <Input id="captcha" value={answer} onChange={event => setAnswer(event.target.value)} required />
          <Button type="primary" htmlType="submit" block disabled={busy || !answer}>{busy ? '校验中…' : '继续登录'}</Button>
        </>}
        {!captcha && <Button type="primary" htmlType="submit" block disabled={busy || !account || !password}>{busy ? '处理中…' : '登录'}</Button>}
        {error && <p role="alert">{error}</p>}
      </form>
      {captcha && <p className="hint">请输入图中字符后点击“继续登录”完成验证码校验。</p>}
    </Card>
  </main>;
}
