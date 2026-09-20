import { consumerApi, consumerStorage } from '../../shared/consumer-runtime'
import { PrivateMaterialUpload } from './upload'
import { privateUploadFiles } from './upload-platform'
import { MerchantApplicationRepository } from '../../shared/merchant-repositories'
import { unavailableDependencies } from './model'
import { ApplicationRecovery } from './recovery'
import Taro from '@tarojs/taro'
import { nativeLocationAdapter } from './location'

declare const MERCHANT_APPLICATION_ENABLED: boolean
declare const PRIVATE_MATERIAL_UPLOAD_ENABLED: boolean
let materialUploads: PrivateMaterialUpload | null = null
export function applicationRuntime(preview: boolean) {
  const dependencies = unavailableDependencies()
  if (!preview) dependencies.location = nativeLocationAdapter(() => Taro.chooseLocation({}))
  if (preview || !MERCHANT_APPLICATION_ENABLED) return { dependencies, recovery: null, uploads: null }
  if (PRIVATE_MATERIAL_UPLOAD_ENABLED && !materialUploads) materialUploads = new PrivateMaterialUpload(consumerApi, consumerStorage, privateUploadFiles())
  const uploads = materialUploads
  if (uploads) dependencies.upload = kind => uploads.upload(kind)
  const repository = new MerchantApplicationRepository(consumerApi)
  return {
    dependencies: { ...dependencies, application: repository, cities: async () => (await repository.cities()).map(city => ({ code: city.cityCode, name: city.cityName })) },
    recovery: new ApplicationRecovery(repository, consumerApi.scope),
    uploads,
  }
}
