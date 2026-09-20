import { defineConfig } from '@tarojs/cli'

export default defineConfig<'webpack5'>({
  projectName: 'pet-platform-miniapp', date: '2026-09-11',
  designWidth: 750, deviceRatio: { 750: 1 },
  sourceRoot: 'src', outputRoot: 'dist', framework: 'react',
  compiler: { type: 'webpack5', errorLevel: 1, prebundle: { enable: false } },
  plugins: ['@tarojs/plugin-platform-weapp'],
  defineConstants: {
    C_API_ORIGIN: JSON.stringify(process.env.PET_C_API_ORIGIN || ''),
    ALLOW_LOCAL_HTTP: JSON.stringify(process.env.NODE_ENV === 'development' && process.env.PET_ALLOW_LOCAL_HTTP === 'true'),
    MERCHANT_APPLICATION_ENABLED: JSON.stringify(process.env.PET_MERCHANT_APPLICATION_ENABLED === 'true' && !!process.env.PET_C_API_ORIGIN),
    PRIVATE_MATERIAL_UPLOAD_ENABLED: JSON.stringify(process.env.PET_PRIVATE_MATERIAL_UPLOAD_ENABLED === 'true' && !!process.env.PET_C_API_ORIGIN),
  },
  mini: {},
})
