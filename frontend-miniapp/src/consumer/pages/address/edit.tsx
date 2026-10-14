import { useRouter } from '@tarojs/taro'
import { useState } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealAddressRepository } from '../../address/repository'
import { fixtureAddresses, isAddressScenario, previewRepository } from '../../address/model'
import { AddressForm } from './address-form'

// 编辑地址页（设计稿 129:13533 与新增同构；编辑帧标题残留「新增地址」→按语义用
// 「编辑地址」，登记表 §4.14 记偏差）。preview 无 addressId 时回退设计稿第一条夹具。
export default function AddressEditPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = isAddressScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [repository] = useState(() => preview ? previewRepository(scope, scenario) : new RealAddressRepository(consumerApi))
  const addressId = route.params.addressId || (preview ? fixtureAddresses[0].addressId : '')
  return <AddressForm mode='edit' addressId={addressId || undefined} preview={preview} scenario={scenario} repository={repository} />
}
