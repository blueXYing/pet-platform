// Native interaction tests for the pet pages via the wechatide-skill CLI (DevTools 2.02 channel).
// Mirrors the C-002-profile platform test discipline: real page events, preview-only data,
// no claim of backend writes. Requires the project window open (initializer/IDE).
const { execSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')

const WECHATIDE = process.env.WECHATIDE || 'D:/soft/微信web开发者工具/wechatide.cmd'
const CLIENT = process.env.WECHATIDE_CLIENT || 'zcode-c002-pet'
const PROJECT = path.resolve(__dirname, '../../..')
const out = path.resolve(__dirname, '../../../../planning/issues/wave-2/C-002-pet-page/evidence')

const report = { status: 'RUNNING', source: 'real WeChat DevTools simulator via wechatide CLI', dataMode: 'explicit in-memory visual preview; no backend session or save', eventMethod: 'native Taro page event handlers via automator element actions', tests: [], exceptions: [] }
const timeout = setTimeout(() => { report.status = 'TIMEOUT'; save(); process.exit(1) }, 300000)
function save() { fs.writeFileSync(path.join(out, 'platform.json'), JSON.stringify(report, null, 2)) }
function check(name) { report.tests.push(name); console.log('CHECK', name); save() }
function ide(args, options = {}) {
  const command = [WECHATIDE, '-c', CLIENT, ...args].map(a => `"${String(a).replaceAll('"', '')}"`).join(' ')
  const raw = execSync(command, { encoding: 'utf8', timeout: options.timeout || 60000 })
  const s = raw.indexOf('{')
  if (s < 0) throw new Error('non-JSON: ' + raw.slice(0, 300))
  return JSON.parse(raw.slice(s))
}
function unwrap(payload) {
  let v = payload && typeof payload === 'object' && 'result' in payload ? payload.result : payload
  if (typeof v === 'string') {
    try { const p = JSON.parse(v); if (p && typeof p === 'object' && 'result' in p && Object.keys(p).length === 1) v = p.result } catch (e) { /* keep */ }
  } else if (v && typeof v === 'object' && 'result' in v && Object.keys(v).length === 1) v = v.result
  return typeof v === 'string' ? v : JSON.stringify(v)
}
function evalJs(source) { return unwrap(ide(['automation_evaluate', '--project', PROJECT, '--fn-source', source]).result) }
function element(action, selector, extra = []) {
  return ide(['automation_element_action', '--project', PROJECT, '--action', action, '--selector', selector, ...extra])
}
function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)) }
function content() {
  try {
    return unwrap(ide(['automation_evaluate', '--project', PROJECT, '--fn-source', "function(){function read(n){return n?(n.v||'')+(n.cn||[]).map(read).join(''):''}return read(getCurrentPages().at(-1)&&getCurrentPages().at(-1).data.root)}"]).result)
  } catch (error) { return '' }
}
async function launch(page, query, marker) {
  const openArgs = ['simulator_open_page', '--project', PROJECT, '--page', page]
  if (query) openArgs.push('--query', query)
  ide(openArgs)
  await sleep(5000)
  const deadline = Date.now() + 20000
  while (!content().includes(marker)) {
    if (Date.now() > deadline) throw new Error('Page did not become ready: ' + marker)
    await sleep(300)
  }
  await sleep(1200)
}
function assertIncludes(text, fragment, label) {
  if (!text.includes(fragment)) throw new Error(`${label}: missing ${fragment}; got ${text.slice(0, 300)}`)
}
;(async () => {
  try {
  fs.mkdirSync(out, { recursive: true })
  // 1. form: validation errors block save and keep the form visible
  await launch('consumer/pages/pet-archive/form', 'preview=1&scenario=form-brother', '编辑宠物信息')
  element('input', '#pet-form-weight', ['--value', 'heavy'])
  await sleep(300)
  element('tap', '#pet-form-save')
  await sleep(800)
  assertIncludes(content(), '体重格式应如28.5kg', 'bad weight validation')
  check('form validation blocks save and surfaces field messages')

  // 2. form: valid edit saves through the preview repository and reports preview-only success
  element('input', '#pet-form-name', ['--value', '豆豆'])
  await sleep(300)
  element('input', '#pet-form-weight', ['--value', '28.5kg'])
  await sleep(300)
  element('tap', '#pet-form-sex-FEMALE')
  await sleep(400)
  element('tap', '#pet-form-save')
  await sleep(1200)
  assertIncludes(content(), '预览数据已更新', 'preview save success notice')
  check('form save succeeds in preview repository with explicit preview notice')

  // 3. form: failed save preserves the draft; retry succeeds
  await launch('consumer/pages/pet-archive/form', 'preview=1&scenario=save-error', '编辑宠物信息')
  element('input', '#pet-form-name', ['--value', '豆豆'])
  await sleep(300)
  element('input', '#pet-form-note', ['--value', '失败后保留的备注'])
  await sleep(300)
  element('tap', '#pet-form-save')
  await sleep(1000)
  assertIncludes(content(), '保存失败，请重试；已填写内容保留', 'save failure notice')
  element('tap', '#pet-form-save')
  await sleep(1200)
  assertIncludes(content(), '预览数据已更新', 'retry success')
  check('failed save preserves draft; explicit retry succeeds')

  // 4. list: load failure and retry
  await launch('consumer/pages/pet-archive/index', 'preview=1&scenario=load-error', '加载失败')
  ide(['simulator_screenshot', '--project', PROJECT, '--path', path.join(out, 'platform-list-load-failure.png'), '--optimize', 'false'])
  element('tap', '#pet-retry-load')
  await sleep(1500)
  assertIncludes(content(), '豆豆', 'list loads after retry')
  check('list load failure and retry use real page state transitions')

  // 5. list: card navigates to detail; detail renders contract fields
  await launch('consumer/pages/pet-archive/index', 'preview=1', '宠物档案')
  element('tap', '#pet-card-30001')
  await sleep(1800)
  const detailText = content()
  assertIncludes(detailText, '基本信息', 'detail renders')
  assertIncludes(detailText, '金毛寻回犬 · 2岁', 'detail breed/age line')
  assertIncludes(detailText, '28.5kg', 'detail weight display')
  check('list card opens detail with contract-backed fields')

  // 6. detail: edit icon navigates to the form with the pet loaded
  element('tap', '#pet-detail-edit')
  await sleep(1800)
  assertIncludes(content(), '编辑宠物信息', 'edit opens form')
  check('detail edit action opens the form for the selected pet')

  // 7. non-preview entry refuses fixture data
  await launch('consumer/pages/pet-archive/index', '', '宠物服务暂不可用')
  assertIncludes(content(), '宠物服务暂不可用', 'non-preview guard')
  check('non-preview entry does not leak fixture or pretend real data')

  report.status = 'PASS_PREVIEW_INTERACTIONS'
  save()
  console.log(report.status)
  } catch (error) {
  report.status = 'FAIL'; report.error = String(error); save(); console.error(error); process.exitCode = 1
} finally { clearTimeout(timeout); save() }
})()
