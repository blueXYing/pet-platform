import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import { PrivateAssetUpload, type UploadFiles } from '../../shared/private-asset-upload'

/** Owner + merchant + store + editor target isolate pending uploads across services/accounts. */
export class ServiceCoverUpload extends PrivateAssetUpload<'cover'> {
  constructor(api: ConsumerApi, store: LocalStore, files: UploadFiles, serviceId: string) {
    if (serviceId !== 'new' && !/^[1-9][0-9]{0,18}$/.test(serviceId)) throw new Error('INVALID_SERVICE_ID')
    super(api, store, files, {
      purpose: 'SERVICE_COVER', workspace: 'merchant', kinds: ['cover'],
      journal: `pet.service-cover-upload.v1:${serviceId}`,
    })
  }
}
