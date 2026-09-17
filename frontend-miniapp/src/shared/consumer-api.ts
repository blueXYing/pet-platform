import { ApiError, type Transport, type RequestSpec } from './request'
import { WorkspaceScope, StaleContextError } from './workspace'

export type LocalStore = { get(key: string): unknown; set(key: string, value: unknown): void; remove(key: string): void }
export type Session = { sessionId: string; userId: string; audience: 'MINIAPP'; expiresAt: string; phoneMasked?: string }
type Grant = Session & { accessToken: string; tokenType: 'Bearer' }
type Attempt = { attemptId: string; attemptToken: string; nextStep: string }
export type Command = RequestSpec & { requestId: string }
const SESSION_KEY = 'pet.c.session.v1'
const WRITE_KEY = 'pet.c.pending.v1'
const LOGOUT_KEY = 'pet.c.logout.v1'
export const object = (value: unknown): Record<string, any> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('INVALID_RESPONSE')
  return value as Record<string, any>
}
export const id = (value: unknown): string => {
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,18}(?![\s\S])/.test(value) || BigInt(value) > 9223372036854775807n) throw new Error('INVALID_RESPONSE')
  return value
}
function session(value: unknown): Session {
  const v = object(value)
  if (v.audience !== 'MINIAPP' || typeof v.expiresAt !== 'string' || !Number.isFinite(Date.parse(v.expiresAt))) throw new Error('INVALID_RESPONSE')
  return { sessionId: id(v.sessionId), userId: id(v.userId), audience: 'MINIAPP', expiresAt: v.expiresAt, phoneMasked: v.phoneMasked }
}
function grant(value: unknown): Grant {
  const v = object(value)
  if (typeof v.accessToken !== 'string' || !v.accessToken || v.tokenType !== 'Bearer') throw new Error('INVALID_RESPONSE')
  return { ...session(v), accessToken: v.accessToken, tokenType: 'Bearer' }
}
export function definiteRejection(error: unknown) {
  return error instanceof ApiError && [400, 401, 403, 404, 422].includes(error.statusCode)
}

/** MINIAPP session transport. Merchant access is limited to the two agreement routes. */
export class ConsumerApi {
  readonly scope = new WorkspaceScope()
  private credential: Grant | null = null
  private attempt: Attempt | null = null
  private authCommand: Command | null = null
  private authFlight: Promise<void> | null = null
  private logoutCommand: { command: Command; token: string } | null = null
  private writes = new Map<string, Promise<unknown>>()
  private writeSpecs = new Map<string, string>()
  private logoutFlight: Promise<void> | null = null
  private pending: Record<string, { userId: string; command: Command }> = {}
  currentSession: Session | null = null
  authStep: 'idle' | 'phone' | 'retry' | 'authenticated' = 'idle'
  constructor(private transport: Transport, private store: LocalStore, readonly uuid: () => Promise<string>) {
    try { const saved = store.get(SESSION_KEY); if (saved) this.credential = grant(saved) } catch { store.remove(SESSION_KEY) }
    // Persistent pending commands never authorize a user. They are selected only after GET session.
    try { const saved = store.get(WRITE_KEY); if (saved) this.pending = object(saved) } catch { store.remove(WRITE_KEY) }
    const logout = store.get(LOGOUT_KEY)
    if (logout) { this.logoutCommand = object(logout) as { command: Command; token: string }; this.credential = null; store.remove(SESSION_KEY) }
  }
  private async send(spec: RequestSpec, headers: Record<string, string> = {}) {
    const agreementPath = (spec.path === '/api/v1/merchant/agreement' && spec.method === 'GET') ||
      (spec.path === '/api/v1/merchant/agreement/consent' && spec.method === 'POST')
    if (!/^\/api\/v1\/c\/[a-z0-9/-]+$/.test(spec.path) && !agreementPath) throw new Error('INVALID_PATH')
    if (spec.method !== 'GET' && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(spec.requestId || '')) throw new Error('REQUEST_ID_REQUIRED')
    const response = await this.transport({ ...spec, headers: { 'Content-Type': 'application/json', ...(spec.requestId ? { 'X-Request-Id': spec.requestId } : {}), ...headers } })
    const body = object(response.data)
    if (response.statusCode < 200 || response.statusCode >= 300 || body.code !== 'SUCCESS') throw new ApiError(typeof body.code === 'string' ? body.code : 'INVALID_RESPONSE', response.statusCode)
    const applicationPath = /^\/api\/v1\/c\/merchant-applications(?:\/|$)/.test(spec.path)
    if ((agreementPath || applicationPath) && body.success !== true) throw new Error('INVALID_RESPONSE')
    return body.data
  }
  private clear() {
    this.credential = null; this.currentSession = null; this.attempt = null; this.authCommand = null; this.authStep = 'idle'
    this.pending = {}; this.writes.clear(); this.writeSpecs.clear()
    this.scope.replace(null)
    this.store.remove(SESSION_KEY); this.store.remove(WRITE_KEY)
  }
  cancelLogin() { this.clear() }
  private assertRevision(revision: number) { if (revision !== this.scope.revision) throw new StaleContextError() }
  private async accept(value: unknown, revision: number) {
    this.assertRevision(revision)
    if (object(value).nextStep === 'VERIFY_PHONE') { this.authStep = 'phone'; this.authCommand = null; return }
    const accepted = grant(value)
    this.credential = accepted; this.store.set(SESSION_KEY, accepted)
    await this.restore()
    this.attempt = null; this.authCommand = null; this.authStep = 'authenticated'
  }
  /** Always validate stored credentials on the server before publishing the principal. */
  async restore() {
    const revision = this.scope.revision
    const credential = this.credential
    if (!credential) throw new ApiError('COMMON_UNAUTHORIZED', 401)
    try {
      const value = session(await this.send({ path: '/api/v1/c/auth/session', method: 'GET' }, { Authorization: `Bearer ${credential.accessToken}` }))
      this.assertRevision(revision)
      if (value.userId !== credential.userId || value.sessionId !== credential.sessionId) throw new Error('INVALID_RESPONSE')
      this.currentSession = value
      if (!this.scope.current) this.scope.replace({ userId: value.userId, workspace: 'consumer', merchantId: null, storeId: null })
      this.authStep = 'authenticated'
      return value
    } catch (error) {
      this.assertRevision(revision)
      if (error instanceof ApiError && error.statusCode === 401) this.clear()
      throw error
    }
  }
  private singleAuth(action: () => Promise<void>) {
    if (this.authFlight) return this.authFlight
    const promise = action().finally(() => { if (this.authFlight === promise) this.authFlight = null })
    this.authFlight = promise
    return promise
  }
  startLogin(login: () => Promise<{ code: string }>) {
    return this.singleAuth(async () => {
      if (this.logoutCommand || this.logoutFlight) throw new Error('LOGOUT_PENDING')
      this.clear()
      const revision = this.scope.revision
      try {
        const proof = await login(); this.assertRevision(revision)
        if (!proof.code) throw new Error('LOGIN_CANCELLED')
        const requestId = await this.uuid(); this.assertRevision(revision)
        const value = object(await this.send({ method: 'POST', path: '/api/v1/c/auth/attempts', requestId, data: { purpose: 'WECHAT_LOGIN' } }))
        this.assertRevision(revision)
        if (typeof value.attemptToken !== 'string' || !value.attemptToken || value.nextStep !== 'PROVE_IDENTITY') throw new Error('INVALID_RESPONSE')
        this.attempt = { attemptId: id(value.attemptId), attemptToken: value.attemptToken, nextStep: value.nextStep }
        const commandId = await this.uuid(); this.assertRevision(revision)
        this.authCommand = { method: 'POST', path: '/api/v1/c/auth/wechat-login', requestId: commandId, data: { attemptId: this.attempt.attemptId, wechatCode: proof.code } }
        await this.submitAuth(revision)
      } catch (error) { this.assertRevision(revision); if (this.authCommand) this.authStep = 'retry'; throw error }
    })
  }
  bindPhone(result: { code?: string; errMsg?: string }) {
    return this.singleAuth(async () => {
      if (this.authStep !== 'phone' || !this.attempt) throw new Error('LOGIN_REQUIRED')
      if (!result.code || result.errMsg !== 'getPhoneNumber:ok') throw new Error('PHONE_AUTH_DENIED')
      const revision = this.scope.revision
      const requestId = await this.uuid(); this.assertRevision(revision)
      this.authCommand = { method: 'POST', path: '/api/v1/c/account/phone-binding', requestId, data: { attemptId: this.attempt.attemptId, phoneCode: result.code } }
      await this.submitAuth(revision)
    })
  }
  retryLogin() { return this.singleAuth(() => this.submitAuth(this.scope.revision)) }
  private async submitAuth(revision: number) {
    if (!this.attempt || !this.authCommand) throw new Error('LOGIN_REQUIRED')
    try {
      const value = await this.send(this.authCommand, { 'X-Auth-Attempt': this.attempt.attemptToken })
      await this.accept(value, revision)
    } catch (error) {
      this.assertRevision(revision)
      this.authStep = 'retry'
      if (definiteRejection(error)) { this.authCommand = null; this.attempt = null; this.authStep = 'idle' }
      throw error
    }
  }
  async request<T>(spec: RequestSpec, decode: (data: unknown) => T): Promise<T> {
    const ticket = this.scope.capture()
    if (!this.credential || !this.currentSession || this.currentSession.userId !== ticket.context.userId) throw new ApiError('COMMON_UNAUTHORIZED', 401)
    const merchantRequest = spec.path.startsWith('/api/v1/merchant/')
    if (merchantRequest) {
      if (ticket.context.workspace !== 'merchant' || !ticket.context.merchantId || spec.data?.merchantId !== ticket.context.merchantId) throw new Error('WORKSPACE_PATH_MISMATCH')
    } else if (ticket.context.workspace !== 'consumer') throw new Error('WORKSPACE_PATH_MISMATCH')
    try {
      const value = await this.send(spec, { Authorization: `Bearer ${this.credential.accessToken}` })
      ticket.assertCurrent()
      return decode(value)
    } catch (error) {
      ticket.assertCurrent()
      if (error instanceof ApiError && error.statusCode === 401) this.clear()
      throw error
    }
  }
  pendingCommand(slot: string): Command | undefined {
    const saved = this.pending[slot]
    return saved?.userId === this.scope.current?.userId ? saved.command : undefined
  }
  pendingDeletes() { return Object.keys(this.pending).filter(key => key.startsWith('delete:') && this.pendingCommand(key)).map(key => key.slice(7)) }
  /** Same operation survives page remount and app restart. Unknown results lock payload/key. */
  write<T>(slot: string, spec: Omit<RequestSpec, 'requestId'>, decode: (data: unknown) => T): Promise<T> {
    // Snapshot before UUID allocation: caller edits must not change an in-flight intent.
    spec = JSON.parse(JSON.stringify(spec)) as Omit<RequestSpec, 'requestId'>
    const existing = this.writes.get(slot)
    const fingerprint = JSON.stringify(spec)
    if (existing) return this.writeSpecs.get(slot) === fingerprint ? existing as Promise<T> : Promise.reject(new Error('PENDING_WRITE_CHANGED'))
    const ticket = this.scope.capture()
    const operation = (async () => {
      let command = this.pendingCommand(slot)
      if (command && (command.path !== spec.path || command.method !== spec.method || JSON.stringify(command.data) !== JSON.stringify(spec.data))) throw new Error('PENDING_WRITE_CHANGED')
      if (!command) {
        const requestId = await this.uuid(); ticket.assertCurrent()
        command = { ...spec, requestId }
        this.pending[slot] = { userId: ticket.context.userId, command }
      }
      this.store.set(WRITE_KEY, this.pending) // must durably journal before every explicit send
      try {
        const value = await this.request(command, decode)
        ticket.assertCurrent(); delete this.pending[slot]; this.store.set(WRITE_KEY, this.pending)
        return value
      } catch (error) {
        ticket.assertCurrent()
        if (definiteRejection(error)) { delete this.pending[slot]; this.store.set(WRITE_KEY, this.pending) }
        throw error
      }
    })().finally(() => { if (this.writes.get(slot) === operation) { this.writes.delete(slot); this.writeSpecs.delete(slot) } })
    this.writes.set(slot, operation)
    this.writeSpecs.set(slot, fingerprint)
    return operation
  }
  logout() {
    if (this.logoutFlight) return this.logoutFlight
    const promise = this.performLogout().finally(() => { if (this.logoutFlight === promise) this.logoutFlight = null })
    this.logoutFlight = promise
    return promise
  }
  private async performLogout() {
    if (!this.logoutCommand) {
      const token = this.credential?.accessToken
      this.clear() // invalidate immediately, even when the revocation response is lost
      if (!token) return
      this.logoutCommand = { token, command: { method: 'POST', path: '/api/v1/c/auth/logout', data: {}, requestId: await this.uuid() } }
      this.store.set(LOGOUT_KEY, this.logoutCommand)
    }
    const pending = this.logoutCommand
    const value = object(await this.send(pending.command, { Authorization: `Bearer ${pending.token}` }))
    if (value.loggedOut !== true) throw new Error('INVALID_RESPONSE')
    if (this.logoutCommand === pending) { this.logoutCommand = null; this.store.remove(LOGOUT_KEY) }
  }
}

export function integrationMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401) return '登录或授权已失效，请重新登录'
    if (error.code === 'USER_FROZEN' || error.statusCode === 403) return '当前账号无权执行此操作'
    if (error.statusCode === 404) return '记录不存在或已删除，请重新加载'
    if (error.statusCode === 400) return '提交内容无效，请检查后重试'
    if (error.statusCode === 409) return '资料或状态已变化，请重新读取核对；不要更换请求编号盲目重试'
    if (error.statusCode === 503) return '服务暂不可用，结果尚未确认，请保留原操作后重试'
  }
  if (error instanceof Error && error.message === 'PHONE_AUTH_DENIED') return '未获得手机号授权，尚未登录'
  if (error instanceof Error && error.message === 'PENDING_WRITE_CHANGED') return '上次保存结果尚未确认，请先重试原操作'
  if (error instanceof Error && error.message === 'API_NOT_CONFIGURED') return '服务尚未配置，请稍后再试'
  if (error instanceof Error && error.message === 'LOGOUT_PENDING') return '退出结果尚未确认，请先重试退出登录'
  if (error instanceof Error && error.message === 'LOGIN_CANCELLED') return '已取消登录'
  if (error && typeof error === 'object' && 'errMsg' in error && /cancel/.test(String(error.errMsg))) return '已取消操作，未提交保存'
  return '操作未确认成功，请重试原操作；不会重复创建'
}
