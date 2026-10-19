import { PolicyContentPage } from './policy-content'
import { privacyPolicyDoc } from '../../settings/model'

// 隐私政策静态内容页（契约 66 / PRD 5.1.29「隐私政策：查看平台隐私政策」）。
export default function PrivacyPolicyPage() {
  return <PolicyContentPage doc={privacyPolicyDoc} />
}
