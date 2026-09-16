// Native interaction tests for the pet pages via the wechatide-skill CLI (DevTools 2.02 channel).
// Mirrors the C-002-profile platform test discipline: real page events, preview-only data,
// no claim of backend writes. Requires the project window open (initializer/IDE).
const { execSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')

const WECHATIDE = process.env.WECHATIDE || 'D:/soft/微信web开发者工具/wechatide.cmd'
const CLIENT = process.env.WECHATIDE_CLIENT || 'Codex'
const PROJECT = path.resolve(__dirname, '../../..')
const mockedApis = new Set()
const out = path.resolve(__dirname, '../../../../planning/issues/wave-2/C-002-pet-page/evidence/navigation')

const report = { status: 'RUNNING', source: 'real WeChat DevTools simulator via wechatide CLI', dataMode: 'explicit in-memory visual preview; no backend session or save', eventMethod: 'native Taro page event handlers via automator element actions', tests: [], exceptions: [] }
const timeout = setTimeout(() => { report.status = 'TIMEOUT'; save(); process.exit(1) }, 600000)
function save() { fs.writeFileSync(path.join(out, 'navigation-platform.json'), JSON.stringify(report, null, 2)) }
function check(name) { report.tests.push(name); console.log('CHECK', name); save() }
function ide(args, options = {}) {
  const command = [WECHATIDE, '-c', CLIENT, ...args].map(a => `"${String(a).replaceAll('"', '')}"`).join(' ')
  const raw = execSync(command, { encoding: 'utf8', timeout: options.timeout || 60000, windowsHide: true })
  const s = raw.indexOf('{')
  if (s < 0) throw new Error('non-JSON: ' + raw.slice(0, 300))
  const result = JSON.parse(raw.slice(s))
  if (result.ok === false || result.result?.success === false) throw new Error(JSON.stringify(result))
  if (args[0] === 'automation_wx_api') {
    const method = args[args.indexOf('--method') + 1]
    const action = args[args.indexOf('--action') + 1]
    if (action === 'mock') mockedApis.add(method)
    if (action === 'restore') mockedApis.delete(method)
  }
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
function assertNotIncludes(text, fragment, label) {
  if (text.includes(fragment)) throw new Error(`${label}: unexpectedly contains ${fragment}`)
}
function screenshot(name) {
  ide(['simulator_screenshot', '--project', PROJECT, '--path', path.join(out, name), '--optimize', 'false'])
}
function rects(selectors) {
  return JSON.parse(evalJs(`function(){return new Promise(resolve=>{const q=wx.createSelectorQuery();${selectors.map(s => `q.select('${s}').boundingClientRect();`).join('')}q.exec(resolve)})}`))
}
function wxApi(action, method, value) {
  const file = path.join(out, `wx-${method}-${action}.json`)
  fs.writeFileSync(file, JSON.stringify(value))
  return ide(['automation_wx_api', '--project', PROJECT, '--action', action, '--method', method, action === 'call' ? '--args-file' : '--result-file', file])
}
async function checkNavigation(prefix, section, label) {
  const boxes = rects(['.consumer-page-layout', '.consumer-bottom-nav', `#${prefix}-tab-home`, `#${prefix}-tab-services`, `#${prefix}-tab-mine`])
  if (boxes.some(box => !box)) throw new Error(`${label}: shared navigation missing`)
  if (!(boxes[2].left < boxes[3].left && boxes[3].left < boxes[4].left)) throw new Error(`${label}: tabs overlap`)
  const rows = JSON.parse(evalJs("function(){return new Promise(resolve=>wx.createSelectorQuery().selectAll('.consumer-bottom-nav').boundingClientRect(resolve).exec())}"))
  if (rows.length !== 1) throw new Error(`${label}: duplicate bottom navigation`)
  const info = JSON.parse(evalJs('function(){return wx.getWindowInfo()}'))
  if (Math.abs(boxes[1].bottom - info.windowHeight) > 2) throw new Error(`${label}: bar not at viewport bottom`)
  const expectedHeight = 62 * info.windowWidth / 402 + (info.screenHeight - info.safeArea.bottom)
  if (Math.abs(boxes[1].height - expectedHeight) > 2) throw new Error(`${label}: safe area not reserved`)
  report.window = info
  report.geometry = report.geometry || {}
  report.geometry[label] = boxes
  const currentLabel = evalJs(`function(){let result;function walk(n){if(!n)return;if(n.uid==='${prefix}-tab-${section}')result=n.ariaLabel;(n.cn||[]).forEach(walk)}walk(getCurrentPages().at(-1).data.root);return result}`)
  assertIncludes(currentLabel, '当前栏目', `${label}: current section`)
  screenshot(`${label}.png`)
  check(`${label}: one shared bar, five distinct items, viewport bottom and safe area`)
}
;(async () => {
  try {
    fs.mkdirSync(out, { recursive: true })
    await launch('consumer/pages/pet-archive/index', 'preview=1', '宠物档案')
    await checkNavigation('pet', 'home', 'list-navigation')
    element('tap', '#pet-tab-services')
    await sleep(300)
    assertIncludes(content(), '“服务”页面尚未接入本次预览', 'unimplemented navigation')
    assertIncludes(evalJs('function(){return getCurrentPages().at(-1).route}'), 'pet-archive/index', 'no fake navigation')
    check('unimplemented top-level route gives a notice and stays on the current page')
    element('tap', '#pet-card-30001')
    await sleep(800)
    await checkNavigation('pet', 'home', 'detail-navigation')
    element('tap', '#pet-detail-edit')
    await sleep(800)
    await checkNavigation('pet', 'home', 'form-navigation')
    element('input', '#pet-form-name', ['--value', '未保存的宠物名字'])
    wxApi('mock', 'showModal', { confirm: false, cancel: true })
    element('tap', '#pet-tab-home')
    await sleep(300)
    assertNotIncludes(content(), '“首页”页面尚未接入本次预览', 'cancelled leave')
    assertIncludes(JSON.stringify(element('value', '#pet-form-name')), '未保存的宠物名字', 'draft retained after cancelled navigation')
    wxApi('mock', 'showModal', { confirm: true, cancel: false })
    element('tap', '#pet-tab-home')
    await sleep(300)
    assertIncludes(content(), '“首页”页面尚未接入本次预览', 'confirmed leave uses central notice')
    ide(['automation_wx_api', '--project', PROJECT, '--action', 'restore', '--method', 'showModal'])
    check('pet form keeps its unsaved-change confirmation when using shared navigation (mock modal responses)')
    ide(['automation_evaluate', '--project', PROJECT, '--fn-source', 'function(){return new Promise(resolve=>wx.pageScrollTo({scrollTop:9999,duration:0,success:()=>setTimeout(()=>resolve(true),300)}))}'])
    const bottom = rects(['#pet-form-save', '.consumer-bottom-nav'])
    if (bottom[0].bottom > bottom[1].top) throw new Error('footer covers form save button at page bottom')
    screenshot('form-save-above-navigation.png')
    check('page layout reserves space so the pet form save button is not covered')

    await launch('consumer/pages/profile-edit/index', 'preview=1', '编辑资料')
    await checkNavigation('profile', 'mine', 'profile-navigation')
    element('tap', '#profile-tab-services')
    await sleep(300)
    assertIncludes(content(), '“服务”页面尚未接入本次预览', 'profile uses central navigation notice')
    element('input', '#profile-nickname', ['--value', '尚未保存的昵称'])
    wxApi('mock', 'showModal', { confirm: false, cancel: true })
    element('tap', '#profile-tab-home')
    await sleep(300)
    assertNotIncludes(content(), '“首页”页面尚未接入本次预览', 'profile cancelled leave')
    assertIncludes(JSON.stringify(element('value', '#profile-nickname')), '尚未保存的昵称', 'profile draft retained')
    ide(['automation_wx_api', '--project', PROJECT, '--action', 'restore', '--method', 'showModal'])
    check('profile keeps its draft and leave guard after adopting the same navigation')

    report.notVerified = ['Physical keyboard show/hide: DevTools rejects mock wx.onKeyboardHeightChange; requires device validation']
    report.status = 'PASS_SHARED_NAVIGATION'
  } catch (error) { report.status = 'FAIL'; report.error = String(error); console.error(error); process.exitCode = 1 }
  finally {
    for (const method of mockedApis) {
      try { ide(['automation_wx_api', '--project', PROJECT, '--action', 'restore', '--method', method]) }
      catch (error) { report.exceptions.push(`restore ${method}: ${error}`) }
    }
    clearTimeout(timeout); save()
  }
})()
