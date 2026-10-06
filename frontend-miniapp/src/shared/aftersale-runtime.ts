import Taro from '@tarojs/taro'
import { AfterSaleClient } from './aftersale-api'
import { consumerApi, requestUuid } from './consumer-runtime'
import { PrivateEvidenceFiles } from './private-evidence-files'

let startupPreviews: PrivateEvidenceFiles | undefined

/** Images are app-private temporary files, cleared on hide, logout and context switch. */
export function afterSaleClient(party: 'c' | 'merchant'): AfterSaleClient {
  let epoch = 0
  const fs = Taro.getFileSystemManager()
  const root = Taro.env.USER_DATA_PATH
  if (!root) throw new Error('PRIVATE_FILES_NOT_AVAILABLE')
  // A process may have stopped before hide/logout. Recover only our strict preview
  // names once on startup; keep failed deletions reachable throughout this process.
  if (!startupPreviews) { startupPreviews = new PrivateEvidenceFiles(fs, root, true); startupPreviews.clear() }
  const files = new PrivateEvidenceFiles(fs, root)
  const clear = () => { epoch++; startupPreviews?.clear(); files.clear() }
  const unsubscribe = consumerApi.scope.subscribe(clear)
  return new AfterSaleClient(consumerApi, party, {
    async read(path) {
      const ticket = consumerApi.scope.capture(); const revision = epoch
      const name = await requestUuid(); ticket.assertCurrent()
      const data = await consumerApi.readAfterSaleEvidence(path); ticket.assertCurrent()
      if (revision !== epoch) throw new Error('STALE_CONTEXT')
      return files.save(name, data)
    },
    clear,
    dispose: unsubscribe,
  })
}
