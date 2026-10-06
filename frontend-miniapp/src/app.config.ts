export default defineAppConfig({
  pages: ['consumer/pages/shell/index', 'consumer/pages/diagnostics/index', 'consumer/pages/profile-edit/index', 'consumer/pages/messages/index'],
  window: { navigationBarTitleText: '工程验证', backgroundColor: '#f5f5f5' },
  permission: { 'scope.userLocation': { desc: '用于选择入驻店铺的位置与地址' } },
  requiredPrivateInfos: ['chooseLocation'],
  // M-001 internal shell shares this AppID; C-End owns ordinary subpackage registration.
  // The pet archive pages ship as an ordinary subpackage so the 2x design strips stay within
  // the platform per-package limit; routes are unchanged.
  subPackages: [
    { root: 'merchant', pages: ['pages/workspace/index', 'pages/services/index', 'pages/services/edit', 'pages/messages/index',
      'pages/schedule/index', 'pages/schedule/windows', 'pages/schedule/staff', 'pages/schedule/capabilities',
      // Contract 54 staff binding (D1-a): member management workbench child page, one compact
      // line so parallel M-side page PRs rebase cleanly.
      'pages/members/index', 'pages/aftersale/index', 'pages/aftersale/detail',
      // Staff workbench slice (52/54/48 K1, 2026-10-06 adjudication): staff landing with the
      // invitation confirm entry, invitation id lookup and the fail-closed verify entry page.
      'pages/staff-workbench/index', 'pages/staff-invitation/index', 'pages/staff-verify/index'] },
    { root: 'consumer/pages/pet-archive', pages: ['index', 'detail', 'form'] },
    { root: 'consumer/pages/merchant-application', pages: ['index', 'signing'] },
    { root: 'consumer/pages/store-services', pages: ['index', 'service-detail', 'stores'] },
    { root: 'consumer/pages/coupon-points', pages: ['coupons', 'coupon-detail', 'points'] },
    { root: 'consumer/pages/aftersale', pages: ['index', 'detail', 'apply'] },
  ],
})
