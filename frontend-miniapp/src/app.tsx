import type { PropsWithChildren } from 'react'
import { WorkspaceProvider } from './shared/workspace-react'
import './app.css'

export default function App({ children }: PropsWithChildren) {
  return <WorkspaceProvider>{children}</WorkspaceProvider>
}
