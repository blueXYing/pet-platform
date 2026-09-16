// Native interaction tests for the pet pages via the wechatide-skill CLI (DevTools 2.02 channel).
// Mirrors the C-002-profile platform test discipline: real page events, preview-only data,
// no claim of backend writes. Requires the project window open (initializer/IDE).
const { execSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')

const WECHATIDE = process.env.WECHATIDE || 'D:/soft/微信web开发者工具/wechatide.cmd'
const CLIENT = process.env.WECHATIDE_CLIENT || 'Codex'
const PROJECT = path.resolve(__dirname, '../../..')
const out = process.env.PET_EVIDENCE_DIR || path.resolve(__dirname, '../../../../planning/issues/wave-2/C-002-pet-page/evidence')

const report = { status: 'RUNNING', source: 'real WeChat DevTools simulator via wechatide CLI', dataMode: 'explicit in-memory visual preview; no backend session or save', eventMethod: 'native Taro page event handlers via automator element actions', tests: [], exceptions: [] }
const timeout = setTimeout(() => { report.status = 'TIMEOUT'; save(); process.exit(1) }, 600000)
function save() { fs.writeFileSync(path.join(out, 'platform.json'), JSON.stringify(report, null, 2)) }
function check(name) { report.tests.push(name); console.log('CHECK', name); save() }
function ide(args, options = {}) {
  const command = [WECHATIDE, '-c', CLIENT, ...args].map(a => `"${String(a).replaceAll('"', '')}"`).join(' ')
  const raw = execSync(command, { encoding: 'utf8', timeout: options.timeout || 60000, windowsHide: true })
  const s = raw.indexOf('{')
  if (s < 0) throw new Error('non-JSON: ' + raw.slice(0, 300))
  const result = JSON.parse(raw.slice(s))
  if (result.ok === false || result.result?.success === false) throw new Error(JSON.stringify(result))
  return result
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
  ide(['automation_runtime_info', '--project', PROJECT, '--action', 'currentPage'])
  return ide(['automation_element_action', '--project', PROJECT, '--action', action, '--selector', selector, '--wait-for-selector', selector, ...extra])
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
function assertNotIncludes(text, fragment, label) {
  if (text.includes(fragment)) throw new Error(`${label}: unexpectedly contains ${fragment}`)
}
function screenshot(name) {
  ide(['simulator_screenshot', '--project', PROJECT, '--path', path.join(out, name), '--optimize', 'false'])
}
function rects(selectors) {
  return JSON.parse(evalJs(`function(){return new Promise(resolve=>{const q=wx.createSelectorQuery();${selectors.map(s => `q.select('${s}').boundingClientRect();`).join('')}q.exec(resolve)})}`))
}
;(async () => {
  try {
  fs.mkdirSync(out, { recursive: true })
  // 1. form: validation errors block save and keep the form visible
  await launch('consumer/pages/pet-archive/form', 'preview=1&scenario=form-brother', '添加宠物信息')
  const formRects = rects(['.pet-form-design', '#pet-form-name', '#pet-tab-home', '#pet-tab-services', '#pet-tab-mine'])
  const scale = formRects[0].width / 402
  if (Math.abs(formRects[1].top - formRects[0].top - 235 * scale) > 2) throw new Error('direct form entry lost base positioning')
  if (!(formRects[2].left < formRects[3].left && formRects[3].left < formRects[4].left)) throw new Error('tab buttons overlap')
  screenshot('takeover-form-direct.png')
  check('direct form entry positions fields and separates all five tabs')
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
  const sexRects = rects(['#pet-form-sex-MALE', '#pet-form-sex-FEMALE'])
  if (sexRects[0].right > sexRects[1].left + 1) throw new Error('sex options overlap after selecting female')
  check('switching sex keeps brother and sister in distinct positions')
  element('tap', '#pet-form-save')
  await sleep(1200)
  assertIncludes(content(), '预览数据已更新', 'preview save success notice')
  check('form save succeeds in preview repository with explicit preview notice')

  // 3. form: failed save preserves the draft; retry succeeds
  await launch('consumer/pages/pet-archive/form', 'preview=1&scenario=save-error', '添加宠物信息')
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
  assertIncludes(JSON.stringify(element('value', '#pet-form-name')), '豆豆', 'legacy edit entry loads selected pet; separate edit design pending')
  check('detail edit action opens the form for the selected pet')

  // Cross-page mutations, including long text, must be visible after navigating back.
  const editedName = '一只名字特别长的金毛豆豆'
  element('input', '#pet-form-name', ['--value', editedName])
  element('input', '#pet-form-weight', ['--value', '30.25kg'])
  element('input', '#pet-form-note', ['--value', '健康备注需要完整展示。'.repeat(35)])
  element('tap', '#pet-form-save')
  await sleep(500)
  element('tap', '#pet-form-back')
  await sleep(1000)
  assertIncludes(content(), editedName, 'detail refresh after edit')
  assertIncludes(content(), '30.25kg', 'detail updated weight')
  const detailRects = rects(['.pet-detail-design', '.pet-detail-identity', '.pet-detail-name', '.pet-detail-sexpill', '.pet-detail-health-panel'])
  if (detailRects[2].right > detailRects[3].left + 1) throw new Error('long name overlaps sex badge')
  if (detailRects[4].bottom > detailRects[0].bottom) throw new Error('long health note clipped by canvas')
  screenshot('takeover-detail-edited.png')
  element('tap', '#pet-detail-back')
  await sleep(1000)
  assertIncludes(content(), editedName, 'list refresh after edit')
  check('saved text refreshes detail and list; long name and health note fit the layout')

  element('tap', '#pet-card-30002')
  await sleep(1000)
  assertIncludes(content(), '咪咪', 'selected cat detail')
  assertNotIncludes(content(), '狂犬疫苗', 'cat must not inherit dog records')
  assertNotIncludes(content(), '900001234567890', 'cat must not inherit dog chip')
  screenshot('takeover-detail-cat.png')
  check('switching pets does not copy dog records or chip into cat details')

  // Newly created IDs must remain stable across repeated saves and be navigable from the list.
  await launch('consumer/pages/pet-archive/index', 'preview=1&scenario=list-empty', '还没有宠物档案')
  element('tap', '#pet-list-count')
  await sleep(800)
  element('input', '#pet-form-name', ['--value', '新宠'])
  element('tap', '#pet-form-save')
  await sleep(500)
  element('input', '#pet-form-name', ['--value', '新宠第二次保存'])
  element('tap', '#pet-form-save')
  await sleep(500)
  element('tap', '#pet-form-back')
  await sleep(800)
  assertIncludes(content(), '1 只萌宠', 'one creation after repeated saves')
  assertIncludes(content(), '新宠第二次保存', 'created pet list label')
  element('tap', '#pet-card-preview-1')
  await sleep(800)
  assertIncludes(content(), '新宠第二次保存', 'created pet can open detail')
  check('create from empty list and repeated save produce one navigable pet')

  element('tap', '#pet-detail-back')
  await sleep(500)
  for (let i = 2; i <= 4; i++) {
    element('tap', '#pet-list-count')
    await sleep(500)
    element('input', '#pet-form-name', ['--value', `第${i}只宠物`])
    element('tap', '#pet-form-save')
    await sleep(400)
    element('tap', '#pet-form-back')
    await sleep(500)
  }
  assertIncludes(content(), '4 只萌宠', 'dynamic count')
  const listRects = rects(['.pet-list-design', '#pet-card-preview-4'])
  if (listRects[1].bottom > listRects[0].bottom) throw new Error('fourth pet clipped by fixed canvas')
  screenshot('takeover-list-four.png')
  check('four pets expand the list canvas and keep the last card visible')

  await launch('consumer/pages/pet-archive/detail', 'preview=1', '基本信息')
  const direct = rects(['.pet-detail-design', '.pet-detail-identity', '.pet-detail-name'])
  if (Math.abs(direct[1].top - direct[0].top - 105 * direct[0].width / 402) > 2) throw new Error('direct detail entry lost base styles')
  screenshot('takeover-detail-direct.png')
  check('direct detail entry retains layout without visiting list first')

  // 7. non-preview entry refuses fixture data
  await launch('consumer/pages/pet-archive/index', '', '登录已失效')
  assertIncludes(content(), '登录已失效', 'non-preview guard')
  check('non-preview entry does not leak fixture or pretend real data')

  report.status = 'PASS_PREVIEW_INTERACTIONS'
  save()
  console.log(report.status)
  } catch (error) {
  report.status = 'FAIL'; report.error = String(error); save(); console.error(error); process.exitCode = 1
} finally { clearTimeout(timeout); save() }
})()
