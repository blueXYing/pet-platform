// Raw output byte inventory checked against documented caps, not WeChat upload/package verification.
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const root = path.join(__dirname, 'dist')
const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
assert.deepEqual(app.pages, ['consumer/pages/shell/index', 'consumer/pages/diagnostics/index'])
const packages = app.subPackages || app.subpackages || []
assert.deepEqual(packages.map(p => ({ root: p.root, pages: p.pages })), [
  { root: 'merchant', pages: ['pages/workspace/index'] },
], 'M-001 merchant route must be registered in the single app')
assert.ok(packages.every(p => !p.independent), 'Only ordinary subpackages allowed')
for (const extension of ['js', 'json', 'wxml']) {
  assert.ok(fs.existsSync(path.join(root, 'merchant/pages/workspace/index.' + extension)),
    'Merchant page build artifact missing: ' + extension)
}
function walk(dir) { return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory()
  ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]) }
const files = walk(root).map(file => ({ file: path.relative(root, file).replaceAll('\\', '/'), bytes: fs.statSync(file).size }))
const sizes = Object.fromEntries(packages.map(p => [p.root, 0]))
let mainBytes = 0
for (const file of files) {
  const pack = packages.find(p => file.file.startsWith(p.root + '/'))
  if (pack) sizes[pack.root] += file.bytes
  else mainBytes += file.bytes
}
const totalBytes = files.reduce((n, f) => n + f.bytes, 0)
const report = { test: 'MINI-005 static inventory only', mainBytes, subpackages: sizes, totalBytes,
  ordinarySubpackageCount: packages.length, fileCount: files.length,
  internalBudgetBytes: 2 * 1024 * 1024,
  platformLimitVerified: true, platformLimitSource: 'https://developers.weixin.qq.com/miniprogram/dev/framework/subpackages.html',
  platformLimitCheckedOn: '2026-09-11', totalBudgetBytes: 20 * 1024 * 1024,
  totalBudgetReason: 'conservative service-provider cap; standard total cap is 30M', platformUploadVerified: false,
  deviceWindowsVerified: false, imageClarityVerified: false }
console.log(JSON.stringify(report, null, 2))
assert.ok(mainBytes < report.internalBudgetBytes, 'Internal main budget exceeded')
for (const bytes of Object.values(sizes)) assert.ok(bytes < report.internalBudgetBytes)
assert.ok(totalBytes < report.totalBudgetBytes, 'Total package budget exceeded')
