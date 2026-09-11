// Client isolation coordinates only. Never evidence of backend authorization.
export type Workspace = Readonly<{
  userId: string; workspace: 'consumer' | 'merchant';
  merchantId: string | null; storeId: string | null;
}>
export class StaleContextError extends Error {
  constructor() { super('STALE_CONTEXT'); }
}
export class WorkspaceScope {
  private value: Workspace | null = null
  private epoch = 0
  private cache = new Map<string, unknown>()
  private listeners = new Set<() => void>()
  get current() { return this.value }
  get revision() { return this.epoch }
  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
  // Also call on logout/revocation; repeated same coordinates still invalidate in-flight work.
  replace(next: Workspace | null) {
    this.value = next ? Object.freeze({ ...next }) : null
    this.epoch++
    this.cache.clear()
    this.listeners.forEach(listener => listener())
  }
  capture() {
    if (!this.value) throw new Error('NO_CONTEXT')
    const revision = this.epoch
    const key = JSON.stringify([this.value.userId, this.value.workspace, this.value.merchantId, this.value.storeId])
    return { context: this.value, key, assertCurrent: () => {
      if (revision !== this.epoch) throw new StaleContextError()
    } }
  }
  read<T>(key: string): T | undefined {
    if (!this.value) return undefined
    return this.cache.get(`${this.capture().key}:${key}`) as T | undefined
  }
  async run<T>(key: string | undefined, operation: (context: Workspace) => Promise<T>): Promise<T> {
    const ticket = this.capture()
    let result: T
    try { result = await operation(ticket.context) }
    catch (error) { ticket.assertCurrent(); throw error }
    ticket.assertCurrent()
    if (key) this.cache.set(`${ticket.key}:${key}`, result)
    return result
  }
}
