import { PolicyContentPage } from './policy-content'
import { userAgreementDoc } from '../../settings/model'

// 用户协议静态内容页（契约 66 / PRD 5.1.29「用户协议：查看平台用户协议」）。
export default function UserAgreementPage() {
  return <PolicyContentPage doc={userAgreementDoc} />
}
