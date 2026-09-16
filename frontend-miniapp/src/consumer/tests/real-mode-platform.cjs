// Actual Taro components/events in WeChat DevTools; wx.login/request are explicit TEST DOUBLES.
// Build with PET_C_API_ORIGIN=https://c-integration.invalid. Never contacts a real account/API.
const { execSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')
const project = path.resolve(__dirname, '../../..')
const out = process.env.C_EVIDENCE_DIR
if (!out) throw new Error('C_EVIDENCE_DIR required')
fs.mkdirSync(out, { recursive: true })
const idePath = process.env.WECHATIDE || 'D:/soft/微信web开发者工具/wechatide.cmd'
const report = { mode: 'WeChat DevTools real page path; wx.login/request Provider TEST DOUBLES; no real WeChat or backend claim', eventMethod: 'automator trigger tap/input callbacks; not physical pointer or keyboard validation', checks: [], status: 'RUNNING' }
function ide(args) {
  const command = [idePath, '-c', 'Codex', ...args].map(x => `"${String(x).replace(/\r?\n/g, ' ').replaceAll('"', '\\"')}"`).join(' ')
  const raw = execSync(command, { encoding: 'utf8', windowsHide: true, timeout: 60000 })
  const data = JSON.parse(raw.slice(raw.indexOf('{')))
  if (!data.ok || data.result?.success === false) throw new Error(data.message || 'IDE operation failed')
  let result = data.result
  while (result && typeof result === 'object' && 'result' in result) result = result.result
  return result
}
function evaluate(source) { return ide(['automation_evaluate', '--project', project, '--fn-source', source]) }
function element(action, selector, ...args) { if (action === 'tap' || action === 'longpress') { args = ['--type', action, ...args]; action = 'trigger' } ide(['automation_runtime_info', '--project', project, '--action', 'currentPage']); return ide(['automation_element_action', '--project', project, '--action', action, '--selector', selector, '--wait-for-selector', selector, '--wait', '0.5', ...args]) }
function text() { return evaluate("function(){function read(n){return n?(n.v||'')+(n.cn||[]).map(read).join(''):''}return read(getCurrentPages().at(-1).data.root)}") }
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
async function expect(fragment) {
  for (let i = 0; i < 12; i++) { if (String(text()).includes(fragment)) return; await sleep(300) }
  throw new Error('Missing visible text: ' + fragment)
}
function check(name) { report.checks.push(name); console.log('CHECK', name) }
function mock(method, result) {
  const file = path.join(out, `mock-${method}.json`); fs.writeFileSync(file, JSON.stringify(result))
  ide(['automation_wx_api', '--project', project, '--action', 'mock', '--method', method, '--result-file', file])
}
function shot(name) { ide(['simulator_screenshot', '--project', project, '--path', path.join(out, name), '--optimize', 'false']) }
async function run() {
  ide(['simulator_open_page', '--project', project, '--page', 'consumer/pages/shell/index'])
  await sleep(5000) // simulator_open_page reports dispatch, not compilation completion
  await expect('真实接口接入验证')
  report.runtime = evaluate('function(){var i=wx.getSystemInfoSync();return {SDKVersion:i.SDKVersion,platform:i.platform,model:i.model,windowWidth:i.windowWidth,windowHeight:i.windowHeight,pixelRatio:i.pixelRatio,system:i.system}}')
  // Mock holds no real secret. Its state is isolated in the automation runtime and removed below.
  mock('login', { code: 'test-only-wx-proof' })
  mock('showActionSheet', { tapIndex: 1 })
  mock('showModal', { confirm: true, cancel: false })
  ide(['automation_wx_api', '--project', project, '--action', 'mock', '--method', 'request', '--function-declaration', `function(options){
    if(options.url.indexOf('https://c-integration.invalid/')!==0)throw new Error('TEST_ORIGIN_REQUIRED');
    var s=wx.__cIntegrationFixture||(wx.__cIntegrationFixture={nickname:'接口测试用户',pets:[],receipts:{},active:false,calls:[]});
    var p=options.url.split('https://c-integration.invalid')[1],d=options.data||{},m=options.method||'GET',k=options.header['X-Request-Id'];
    s.calls.push({path:p,method:m,key:k,petName:p.indexOf('/pets')>0?d.name:undefined});
    var session={userId:'101',sessionId:'201',audience:'MINIAPP',expiresAt:'2099-01-01T00:00:00.000Z',phoneMasked:'138****1234'};
    var data;
    if(p.endsWith('/attempts'))data={attemptId:'301',attemptToken:'unusable-test-attempt',nextStep:'PROVE_IDENTITY'};
    else if(p.endsWith('/wechat-login'))data={nextStep:'VERIFY_PHONE'};
    else if(p.endsWith('/phone-binding')){s.active=true;data=Object.assign({},session,{tokenType:'Bearer',accessToken:'unusable-test-token'})}
    else if(p.endsWith('/logout')){s.active=false;data={loggedOut:true}}
    else if(!s.active)return {statusCode:401,data:{code:'COMMON_UNAUTHORIZED',data:null}};
    else if(p.endsWith('/session'))data=session;
    else if(s.receipts[k])data=s.receipts[k];
    else if(p.endsWith('/profile')){if(m==='PUT')s.nickname=d.nickname;data={userId:'101',nickname:s.nickname,avatarUrl:null,phoneMasked:session.phoneMasked,passwordEnabled:false}}
    else if(p==='/api/v1/c/pets'&&m==='GET')data=s.pets;
    else if(p==='/api/v1/c/pets'&&m==='POST'){
      data=Object.assign({petId:'501',petType:'CAT',breedName:null,birthDate:null,sex:'UNKNOWN',weightKg:null,sterilizationStatus:null,vaccineStatus:null,healthNote:null,avatarUrl:null,isDefault:false,status:'ACTIVE'},d);s.pets.push(data);
    }else{var pet=s.pets.find(function(v){return p.endsWith('/'+v.petId)});if(!pet)return {statusCode:404,data:{code:'COMMON_NOT_FOUND',data:null}};if(m==='DELETE'){s.pets=[];data={petId:pet.petId,status:'DISABLED'}}else if(m==='PUT'){Object.assign(pet,d);data=pet}else data=pet}
    if(k)s.receipts[k]=data;
    s.calls[s.calls.length-1].responsePetName=data && data.name;
    return JSON.parse(JSON.stringify({statusCode:200,data:{code:'SUCCESS',data:data}}));
  }`])
  element('tap', '#c-login'); await expect('授权手机号并完成登录')
  const denied = path.join(out, 'phone-denied.json'); fs.writeFileSync(denied, JSON.stringify({errMsg:'getPhoneNumber:fail user deny'}))
  element('trigger', '#c-phone', '--type', 'getphonenumber', '--detail-file', denied)
}
// Remaining steps are explicit sequential page events; no React state mutation.
run().then(async () => {
  await expect('尚未登录'); check('phone denial leaves unauthenticated state')
  const detail = path.join(out, 'phone-proof.json'); fs.writeFileSync(detail, JSON.stringify({ code: 'test-only-phone-proof', errMsg: 'getPhoneNumber:ok' }))
  element('trigger', '#c-phone', '--type', 'getphonenumber', '--detail-file', detail)
  await expect('已登录'); check('login + phone callback publishes server principal')
  element('tap', '#c-profile'); await expect('本次可保存昵称')
  element('input', '#profile-nickname', '--value', '接口保存昵称')
  element('tap', '#profile-save'); await expect('昵称已保存'); shot('real-profile.png')
  report.profileGeometry = evaluate("function(){return new Promise(function(resolve){var q=wx.createSelectorQuery();['.profile-design','.profile-avatar-card','.profile-nickname-card','.profile-gender-card','.profile-phone-card','.profile-signature-card','#profile-save'].forEach(function(s){q.select(s).boundingClientRect()});q.exec(resolve)})}")
  element('tap', '#profile-back'); element('tap', '#c-profile'); await expect('本次可保存昵称')
  const value = element('value', '#profile-nickname')
  if (!JSON.stringify(value).includes('接口保存昵称')) throw new Error('Profile did not reload saved value')
  check('profile saves and reloads through real repository with unsupported fields disabled')
  element('tap', '#profile-back'); element('tap', '#c-pets'); await expect('还没有宠物档案')
  element('tap', '#pet-list-count'); element('input', '#pet-form-name', '--value', '接口新增宠物')
  element('tap', '#pet-form-save'); await expect('已保存')
  element('tap', '#pet-form-back'); await expect('接口新增宠物'); shot('real-list.png')
  element('tap', '#pet-card-501'); await expect('记录功能尚未接通')
  if (String(text()).includes('900001234567890') || String(text()).includes('狂犬疫苗')) throw new Error('Design data leaked into real page')
  shot('real-detail.png'); element('tap', '#pet-detail-edit')
  element('input', '#pet-form-name', '--value', '接口修改宠物'); element('tap', '#pet-form-save'); await expect('已保存')
  shot('real-form.png'); element('tap', '#pet-form-back'); await expect('接口修改宠物')
  element('tap', '#pet-detail-back'); await expect('接口修改宠物')
  element('longpress', '#pet-card-501'); await expect('还没有宠物档案')
  check('pet create/detail/update/delete and page reload; no chip/medical fixture leakage')
  ide(['automation_navigate', '--project', project, '--action', 'reLaunch', '--url', '/consumer/pages/shell/index'])
  element('tap', '#c-logout'); await expect('已退出登录'); check('logout clears real context')
  report.status = 'PASS_PROVIDER_DOUBLES'
}).catch(error => { report.status = 'FAIL'; report.error = String(error); console.error(error); process.exitCode = 1 }).finally(() => {
  try { report.page = text(); report.pets = evaluate('function(){return wx.__cIntegrationFixture && wx.__cIntegrationFixture.pets}') } catch {}
  try { report.wire = evaluate('function(){return wx.__cIntegrationFixture && wx.__cIntegrationFixture.calls}') } catch {}
  for (const method of ['login', 'request', 'showActionSheet', 'showModal']) { try { ide(['automation_wx_api', '--project', project, '--action', 'restore', '--method', method]) } catch {} }
  try { evaluate("function(){delete wx.__cIntegrationFixture;wx.removeStorageSync('https://c-integration.invalid:pet.c.session.v1');wx.removeStorageSync('https://c-integration.invalid:pet.c.pending.v1');wx.removeStorageSync('https://c-integration.invalid:pet.c.logout.v1');return true}") } catch {}
  fs.writeFileSync(path.join(out, 'report.json'), JSON.stringify(report, null, 2))
})
