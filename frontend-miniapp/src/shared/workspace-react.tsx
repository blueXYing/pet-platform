import { createContext, useContext, useState, useSyncExternalStore, type PropsWithChildren } from 'react'
import { WorkspaceScope } from './workspace'
import { consumerFixture } from './fixture'
const Context = createContext<WorkspaceScope | null>(null)
export function WorkspaceProvider({ children }: PropsWithChildren) {
  const [scope] = useState(() => { const scope = new WorkspaceScope(); scope.replace(consumerFixture); return scope })
  return <Context.Provider value={scope}>{children}</Context.Provider>
}
export function useWorkspace() {
  const scope = useContext(Context)
  if (!scope) throw new Error('WORKSPACE_PROVIDER_REQUIRED')
  const revision = useSyncExternalStore(scope.subscribe, () => scope.revision)
  return { scope, revision, context: scope.current }
}
