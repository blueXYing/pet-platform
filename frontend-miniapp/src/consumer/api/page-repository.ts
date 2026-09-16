import Taro from '@tarojs/taro'
import { consumerApi } from '../../shared/consumer-runtime'
import { RealPetRepository } from './repositories'
import type { PetType } from '../pet/model'
export const realPetRepository = () => new RealPetRepository(consumerApi, async () => {
  const selected = await Taro.showActionSheet({ itemList: ['狗（创建后不可更改）', '猫（创建后不可更改）', '其他（创建后不可更改）'] })
  return (['DOG', 'CAT', 'OTHER'] as PetType[])[selected.tapIndex]
})
