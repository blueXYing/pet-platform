import { createWebClient, type Transport } from '../request';

export type AdminSession = { sessionId: string; operatorId: string; audience: string; expiresAt: string; idleExpiresAt: string; authzVersion: string };
export type AdminPermissions = { operatorId: string; authzVersion: string; checkedAt: string; roles: string[]; dataScope: string; actionCodes: string[] };
export type LoginResult = { sessionId: string; operatorId: string; audience: string; tokenType: string; accessToken: string; expiresAt: string };
export type Attempt = { attemptId: string; attemptToken: string; expiresAt: string; nextStep: string };
export type AttemptRequirements = { requiredVerification: 'CAPTCHA' | 'NONE' };
export type CaptchaChallenge = { captchaId: string; imageDataUrl: string; expiresAt: string };
export type CaptchaProof = { captchaProof: string; expiresAt: string };

// Real AdminAuthController protocol: every POST carries a JSON object body (even empty),
// and attempt-bound calls must present the attempt token via X-Auth-Attempt alongside
// the __Host cookie. /auth/* predates the unified envelope; the shared client tolerates
// both shapes (CONTRACT.md §4.1). Only /auth/* requests may carry credentials.
export function createAuthClient(transport: Transport = fetch) {
  const base = createWebClient(transport);
  const bound = (attempt: Attempt) => ({ withCredentials: true as const, headers: { 'X-Auth-Attempt': attempt.attemptToken } });
  return {
    createAttempt: () => base.request<Attempt>('/api/v1/admin/auth/attempts', { method: 'POST', body: {}, withCredentials: true }),
    requirements: (attempt: Attempt) => base.request<AttemptRequirements>(`/api/v1/admin/auth/attempts/${attempt.attemptId}/requirements`, bound(attempt)),
    createCaptcha: (attempt: Attempt) => base.request<CaptchaChallenge>('/api/v1/admin/auth/captcha/challenges', { method: 'POST', body: { attemptId: attempt.attemptId }, ...bound(attempt) }),
    verifyCaptcha: (attempt: Attempt, captchaId: string, answer: string) => base.request<CaptchaProof>('/api/v1/admin/auth/captcha/verify', { method: 'POST', body: { attemptId: attempt.attemptId, captchaId, answer }, ...bound(attempt) }),
    login: (attempt: Attempt, account: string, password: string, captchaProof: string) => base.request<LoginResult>('/api/v1/admin/auth/login', { method: 'POST', body: { attemptId: attempt.attemptId, account, password, captchaProof }, ...bound(attempt) }),
  };
}

export function createSessionClient(transport: Transport = fetch) {
  const base = createWebClient(transport);
  return {
    session: (token: string) => { base.resetContext(token); return base.request<AdminSession>('/api/v1/admin/auth/session'); },
    permissions: (token: string) => { base.resetContext(token); return base.request<AdminPermissions>('/api/v1/admin/auth/permissions'); },
    logout: async (token: string) => { base.resetContext(token); await base.request('/api/v1/admin/auth/logout', { method: 'POST', body: {} }); base.resetContext(); },
  };
}
