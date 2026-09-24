import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import { PrivateAssetUpload, type UploadFiles } from '../../shared/private-asset-upload'
import type { MaterialKind } from './model'
export type { UploadFiles } from '../../shared/private-asset-upload'
export class PrivateMaterialUpload extends PrivateAssetUpload<MaterialKind> {
  constructor(api: ConsumerApi, store: LocalStore, files: UploadFiles) {
    super(api, store, files, {
      purpose: 'MERCHANT_APPLICATION_MATERIAL', workspace: 'consumer', journal: 'pet.private-upload.v1',
      kinds: ['storePhotoAssetIds', 'businessLicenseAssetId', 'idCardFrontAssetId', 'idCardBackAssetId', 'industryLicenseAssetId'],
    })
  }
}
