import Taro from '@tarojs/taro'
import { AfterSaleClient } from './aftersale-api'
import { consumerApi, requestUuid } from './consumer-runtime'

/** Images are app-private temporary files, cleared on hide, logout and context switch. */
export function afterSaleClient(party: 'c' | 'merchant'): AfterSaleClient {
  const paths = new Set<string>(); let epoch = 0
  const fs = Taro.getFileSystemManager()
  const clear = () => { epoch++; for (const path of paths) { try { fs.unlinkSync(path) } catch { /* retry at next clear */ } } paths.clear() }
  const unsubscribe = consumerApi.scope.subscribe(clear)
  return new AfterSaleClient(consumerApi, party, {
    async read(path) {
      const ticket = consumerApi.scope.capture(); const revision = epoch
      const name = await requestUuid(); ticket.assertCurrent()
      const data = await consumerApi.readAfterSaleEvidence(path); ticket.assertCurrent()
      if (revision !== epoch) throw new Error('STALE_CONTEXT')
      const local = `${Taro.env.USER_DATA_PATH}/pet-aftersale-${name}.img`
      fs.writeFileSync(local, data)
      paths.add(local)
      return local
    },
    clear,
    dispose: unsubscribe,
  })
}
