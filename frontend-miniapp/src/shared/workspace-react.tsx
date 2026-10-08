import { createContext, useContext, useEffect, useState, useSyncExternalStore, type PropsWithChildren } from 'react'
import { WorkspaceScope } from './workspace'
import { consumerFixture } from './fixture'
import { consumerApi, consumerSession } from './consumer-runtime'
const Context = createContext<WorkspaceScope | null>(null)
export function WorkspaceProvider({ children }: PropsWithChildren) {
  const [scope] = useState(() => { const scope = new WorkspaceScope(); scope.replace(consumerFixture); return scope })
  // App-launch session bootstrap (用户 2026-10-08 裁决): validate the persisted grant first,
  // silent-login (wx.login → wechat-login) only when no valid grant remains; failures stay
  // silent — pages keep their original 401/error paths.
  useEffect(() => { void consumerSession.launch().catch(() => {}) }, [])
  return <Context.Provider value={scope}>{children}</Context.Provider>
}
export function useWorkspace(mode: 'preview' | 'real' = 'preview') {
  const previewScope = useContext(Context)
  const scope = mode === 'real' ? consumerApi.scope : previewScope
  if (!scope) throw new Error('WORKSPACE_PROVIDER_REQUIRED')
  const revision = useSyncExternalStore(scope.subscribe, () => scope.revision)
  return { scope, revision, context: scope.current }
}
