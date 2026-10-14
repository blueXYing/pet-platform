import { useRouter } from '@tarojs/taro'
import { useState } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealAddressRepository } from '../../address/repository'
import { isAddressScenario, previewRepository } from '../../address/model'
import { AddressForm } from './address-form'

// 新增地址页（设计稿 129:13731 底部弹层形态；列表页 navigateTo 进入）。
// preview=1 本地夹具通道（设计验收，不发起真实请求）。
export default function AddressNewPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = isAddressScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [repository] = useState(() => preview ? previewRepository(scope, scenario) : new RealAddressRepository(consumerApi))
  return <AddressForm mode='new' addressId={undefined} preview={preview} scenario={scenario} repository={repository} />
}
