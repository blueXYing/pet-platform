// Raw output byte inventory is a conservative engineering budget, not WeChat upload/package verification.
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const root = path.join(__dirname, 'dist')
const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
assert.deepEqual(app.pages, ['consumer/pages/shell/index', 'consumer/pages/diagnostics/index'])
const packages = app.subPackages || app.subpackages || []
assert.ok(packages.every(p => !p.independent), 'Only ordinary subpackages allowed')
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
  platformLimitVerified: false, platformUploadVerified: false,
  deviceWindowsVerified: false, imageClarityVerified: false }
console.log(JSON.stringify(report, null, 2))
assert.ok(mainBytes < report.internalBudgetBytes, 'Internal main budget exceeded')
for (const bytes of Object.values(sizes)) assert.ok(bytes < report.internalBudgetBytes)
