export default defineAppConfig({
  pages: ['consumer/pages/shell/index', 'consumer/pages/diagnostics/index'],
  window: { navigationBarTitleText: '工程验证', backgroundColor: '#f5f5f5' },
  // M-001 supplies real merchant routes; C-End alone registers ordinary subPackages.
  subPackages: [],
})
