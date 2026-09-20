import { consumerApi } from '../../shared/consumer-runtime'
import { MerchantApplicationRepository } from '../../shared/merchant-repositories'
import { unavailableDependencies } from './model'
import { ApplicationRecovery } from './recovery'
import Taro from '@tarojs/taro'
import { nativeLocationAdapter } from './location'

declare const MERCHANT_APPLICATION_ENABLED: boolean
export function applicationRuntime(preview: boolean) {
  const dependencies = unavailableDependencies()
  if (!preview) dependencies.location = nativeLocationAdapter(() => Taro.chooseLocation({}))
  if (preview || !MERCHANT_APPLICATION_ENABLED) return { dependencies, recovery: null }
  const repository = new MerchantApplicationRepository(consumerApi)
  return {
    dependencies: { ...dependencies, application: repository, cities: async () => (await repository.cities()).map(city => ({ code: city.cityCode, name: city.cityName })) },
    recovery: new ApplicationRecovery(repository, consumerApi.scope),
  }
}
