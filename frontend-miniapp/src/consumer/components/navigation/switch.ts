import Taro from '@tarojs/taro'
import { consumerTabRoutes, type ConsumerNavigationKey } from './model'

/** Switch to a top-level tab from any subpage's custom bottom navigation (Taro 依赖单独成文件，
 *  model.ts 保持纯数据供 node 测试驱动)。 */
export async function switchConsumerTab(key: ConsumerNavigationKey): Promise<void> {
  await Taro.switchTab({ url: consumerTabRoutes[key] })
}
