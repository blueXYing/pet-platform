import { defineConfig } from '@tarojs/cli'
import { merchantApplicationEnabled, privateMaterialUploadEnabled } from './feature-flags'

export default defineConfig<'webpack5'>({
  projectName: 'pet-platform-miniapp', date: '2026-09-11',
  designWidth: 750, deviceRatio: { 750: 1 },
  sourceRoot: 'src', outputRoot: 'dist', framework: 'react',
  compiler: { type: 'webpack5', errorLevel: 1, prebundle: { enable: false } },
  plugins: ['@tarojs/plugin-platform-weapp'],
  defineConstants: {
    C_API_ORIGIN: JSON.stringify(process.env.PET_C_API_ORIGIN || ''),
    ALLOW_LOCAL_HTTP: JSON.stringify(process.env.NODE_ENV === 'development' && process.env.PET_ALLOW_LOCAL_HTTP === 'true'),
    MERCHANT_APPLICATION_ENABLED: JSON.stringify(merchantApplicationEnabled(process.env)),
    PRIVATE_MATERIAL_UPLOAD_ENABLED: JSON.stringify(privateMaterialUploadEnabled(process.env)),
  },
  mini: {},
})
