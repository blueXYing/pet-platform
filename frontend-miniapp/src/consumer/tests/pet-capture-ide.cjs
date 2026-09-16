// Capture pet pages via the wechatide-skill CLI (the supported automation channel since the
// DevTools 2.02 update replaced the old service-port CLI path). Mirrors pet-capture.cjs states.
const { execSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')

const WECHATIDE = process.env.WECHATIDE || 'D:/soft/微信web开发者工具/wechatide.cmd'
const CLIENT = process.env.WECHATIDE_CLIENT || 'Codex'
const PROJECT = path.resolve(__dirname, '../../..')
const out = process.env.PET_EVIDENCE_DIR || path.resolve(__dirname, '../../../../planning/issues/wave-2/C-002-pet-page/evidence')
const width = process.env.PET_WINDOW_WIDTH || '390'

function ide(args, options = {}) {
  // wechatide.cmd runs through cmd.exe; quote every argument so URL "&" survives the shell.
  const command = [WECHATIDE, '-c', CLIENT, ...args].map(arg => `"${String(arg).replaceAll('"', '')}"`).join(' ')
  const result = execSync(command, { encoding: 'utf8', timeout: options.timeout || 60000, windowsHide: true })
  const start = result.indexOf('{')
  if (start < 0) throw new Error('non-JSON output: ' + result.slice(0, 400))
  return JSON.parse(result.slice(start))
}

function ideJson(args) {
  const payload = ide(args)
  if (payload.ok === false) throw new Error('tool failed: ' + JSON.stringify(payload).slice(0, 400))
  return payload
}

// automation_evaluate wraps the function return as result.result; promise returns add one more
// { result: <value> } layer. Unwrap both.
function unwrap(payload) {
  let value = payload && typeof payload === 'object' && 'result' in payload ? payload.result : payload
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value)
      if (parsed && typeof parsed === 'object' && 'result' in parsed && Object.keys(parsed).length === 1) value = parsed.result
      else value = parsed
    } catch (error) { /* plain string */ }
  } else if (value && typeof value === 'object' && 'result' in value && Object.keys(value).length === 1) {
    value = value.result
  }
  return typeof value === 'string' ? value : JSON.stringify(value)
}
function content() {
  try {
    const payload = ideJson(['automation_evaluate', '--project', PROJECT,
      '--fn-source', "function(){function read(n){return n?(n.v||'')+(n.cn||[]).map(read).join(''):''}return read(getCurrentPages().at(-1)&&getCurrentPages().at(-1).data.root)}"])
    return unwrap(payload.result)
  } catch (error) {
    return '' // the automator bridge restarts while the simulator recompiles; keep polling
  }
}

async function launch(page, query, marker) {
  // simulator_open_page triggers the project-window compile AND opens the page with its query,
  // so every state runs against a freshly compiled bundle (the IDE does not watch dist rewrites).
  const openArgs = ['simulator_open_page', '--project', PROJECT, '--page', page]
  if (query) openArgs.push('--query', query)
  ideJson(openArgs)
  // The triggered compile restarts the runtime bridge; give it a moment before polling.
  await new Promise(resolve => setTimeout(resolve, 5000))
  const deadline = Date.now() + 20000
  while (!content().includes(marker)) {
    if (Date.now() > deadline) throw new Error('Page did not become ready: ' + marker)
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  // Native rendering and font/image decoding follow the React data update.
  await new Promise(resolve => setTimeout(resolve, 1500))
  await scrollTo(0)
}

async function scrollTo(top) {
  ideJson(['automation_evaluate', '--project', PROJECT, '--fn-source', `function(){return new Promise(resolve=>wx.pageScrollTo({scrollTop:${top},duration:0,success:()=>setTimeout(()=>resolve(true),300)}))}`])
}

let canvasSelector = '.pet-page'
function canvasRect() {
  const payload = ideJson(['automation_evaluate', '--project', PROJECT,
    '--fn-source', `function(){return new Promise(resolve=>{wx.createSelectorQuery().select('${canvasSelector}').boundingClientRect(r=>resolve(r?{x:r.left,y:r.top,width:r.width,height:r.height}:null)).exec()})}`])
  return JSON.parse(unwrap(payload.result))
}

function windowInfo() {
  const payload = ideJson(['automation_evaluate', '--project', PROJECT, '--fn-source', 'function(){return JSON.stringify(wx.getWindowInfo())}'])
  return JSON.parse(unwrap(payload.result))
}

function screenshot(name) {
  const file = path.join(out, name)
  const payload = ideJson(['simulator_screenshot', '--project', PROJECT, '--path', file, '--optimize', 'false'])
  const result = payload.result || payload
  const recorded = result.imageWidth ? { w: result.imageWidth, h: result.imageHeight } : {}
  return recorded
}

async function canvasRectWithRetry() {
  for (let attempt = 0; attempt < 6; attempt++) {
    const rect = canvasRect()
    if (rect && rect.width) return rect
    await new Promise(resolve => setTimeout(resolve, 600))
  }
  throw new Error('canvas rect unavailable: ' + JSON.stringify(canvasRect()))
}
async function captureCanvas(name, canvasHeight, tileName) {
  const info = windowInfo()
  // Layout viewport = windowHeight (753); pageScrollTo clamps to canvasHeight - windowHeight.
  // Screen rows below the layout viewport are device area, not canvas, and are never cropped.
  const layout = info.windowHeight
  const chromeTop = 100 // notch + status text + menu capsule overlay the first ~90 screen rows
  const maxScroll = Math.max(0, canvasHeight - layout)
  const scrolls = [0]
  let desired = layout - chromeTop
  while (desired < maxScroll && desired !== scrolls[scrolls.length - 1]) {
    scrolls.push(desired)
    desired += layout - chromeTop
  }
  if (maxScroll > 0 && scrolls[scrolls.length - 1] !== maxScroll) scrolls.push(maxScroll)
  const tiles = []
  for (const index of scrolls.keys()) {
    const scroll = scrolls[index]
    const last = index === scrolls.length - 1
    await scrollTo(scroll)
    const rect = canvasRect()
    const actualScroll = Math.max(0, -rect.y)
    if (Math.abs(actualScroll - scroll) > 2) throw new Error(`requested scroll ${scroll}, observed ${actualScroll}`)
    const file = `${tileName}-tile${index}.png`
    const size = screenshot(file)
    const cropTopCss = scroll === 0 ? 0 : chromeTop
    tiles.push({ file, scrollTop: actualScroll, cropTopCss, cropBottomCss: layout, canvasTopInScreenshot: Math.max(0, Math.round(rect.y)), screenshotWidth: size.w || 0, screenshotHeight: size.h || 0 })
  }
  fs.writeFileSync(path.join(out, `${tileName}-tiles.json`), JSON.stringify({ canvasHeight, layout, windowWidth: info.windowWidth, chromeTop, tiles }, null, 2))
}

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const states = [
    { name: 'list', page: 'consumer/pages/pet-archive/index', query: 'preview=1&referenceCanvas=1', marker: '宠物档案', canvas: 312 },
    { name: 'detail', page: 'consumer/pages/pet-archive/detail', query: 'preview=1&petId=30001&referenceCanvas=1', marker: '基本信息', canvas: 1252 },
    { name: 'form-brother', page: 'consumer/pages/pet-archive/form', query: 'preview=1&scenario=form-brother&referenceCanvas=1', marker: '添加宠物信息', canvas: 1066 },
    { name: 'form-sister', page: 'consumer/pages/pet-archive/form', query: 'preview=1&scenario=form-sister&referenceCanvas=1', marker: '添加宠物信息', canvas: 1067 },
  ]
  for (const state of states) {
    if (process.env.PET_CAPTURE_MODE === 'device') continue
    // The list is an embedded home section; compare the original 312px content, not its page navigation.
    canvasSelector = state.name === 'list' ? '.pet-list-design' : '.pet-page'
    await launch(state.page, state.query, state.marker)
    if (windowInfo().windowWidth !== Number(width)) throw new Error(`actual window width does not match requested ${width}`)
    const rect = await canvasRectWithRetry()
    if (Math.round(rect.width) !== 402) throw new Error(`${state.name} reference canvas width ${rect.width} != 402 (rect=${JSON.stringify(rect)})`)
    fs.writeFileSync(path.join(out, `reference-canvas-bounds-${state.name}-${width}.json`), JSON.stringify({ state: state.name, canvas: rect, design: state.canvas }, null, 2))
    if (Math.abs(rect.height - state.canvas) > 1) throw new Error(`${state.name} height ${rect.height} != ${state.canvas}`)
    console.log('capture reference canvas', state.name)
    await captureCanvas(state.name, state.canvas, `reference-${state.name}-raw-${width}`)
  }
  const plain = [
    { name: `normal-list-${width}`, page: 'consumer/pages/pet-archive/index', query: 'preview=1', marker: '宠物档案' },
    { name: `list-empty-${width}`, page: 'consumer/pages/pet-archive/index', query: 'preview=1&scenario=list-empty', marker: '还没有宠物档案' },
    { name: `list-load-error-${width}`, page: 'consumer/pages/pet-archive/index', query: 'preview=1&scenario=load-error', marker: '加载失败' },
    { name: `normal-detail-${width}`, page: 'consumer/pages/pet-archive/detail', query: 'preview=1&petId=30001', marker: '基本信息' },
    { name: `normal-form-brother-${width}`, page: 'consumer/pages/pet-archive/form', query: 'preview=1&scenario=form-brother', marker: '添加宠物信息' },
    { name: `normal-form-sister-${width}`, page: 'consumer/pages/pet-archive/form', query: 'preview=1&scenario=form-sister', marker: '添加宠物信息' },
    { name: `form-create-${width}`, page: 'consumer/pages/pet-archive/form', query: 'preview=1', marker: '添加宠物信息' },
  ]
  for (const state of plain) {
    if (process.env.PET_CAPTURE_MODE === 'reference') continue
    await launch(state.page, state.query, state.marker)
    console.log('capture', state.name)
    screenshot(state.name + '.png')
  }
  fs.writeFileSync(path.join(out, `capture-final-${width}.json`), JSON.stringify({ status: 'CAPTURED', source: 'actual WeChat DevTools simulator via wechatide-skill CLI', channel: 'wechatide (DevTools 2.02); legacy miniprogram-automator ws path retired by the update', deviceWindowWidth: width, states: states.map(s => s.name).concat(plain.map(p => p.name)), visualAcceptance: 'REQUIRES_COMPARISON_AND_REVIEW' }, null, 2))
  console.log(`Captured reference canvases and device states at window width ${width}.`)
})().catch(error => { console.error(error); process.exit(1) })
