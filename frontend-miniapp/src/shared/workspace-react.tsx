import { createContext, useContext, useEffect, useState, useSyncExternalStore, type PropsWithChildren } from 'react'
import { WorkspaceScope } from './workspace'
import { consumerFixture } from './fixture'
import { consumerApi } from './consumer-runtime'
const Context = createContext<WorkspaceScope | null>(null)
export function WorkspaceProvider({ children }: PropsWithChildren) {
  const [scope] = useState(() => { const scope = new WorkspaceScope(); scope.replace(consumerFixture); return scope })
  useEffect(() => { void consumerApi.restore().catch(() => {}) }, [])
  return <Context.Provider value={scope}>{children}</Context.Provider>
}
export function useWorkspace(mode: 'preview' | 'real' = 'preview') {
  const previewScope = useContext(Context)
  const scope = mode === 'real' ? consumerApi.scope : previewScope
  if (!scope) throw new Error('WORKSPACE_PROVIDER_REQUIRED')
  const revision = useSyncExternalStore(scope.subscribe, () => scope.revision)
  return { scope, revision, context: scope.current }
}
