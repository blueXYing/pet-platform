import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { switchConsumerTab } from '../../components/navigation/switch'
import { ConsumerPageLayout } from '../../components/page-layout'
import { isCouponPointsScenario, PreviewCouponPointsRepository } from '../../coupon-points/model'
import { RealCouponPointsRepository } from '../../coupon-points/repository'
import { mallActionLabel, mallGoods, mallPageCopy } from './model'
import product1 from './assets/128-2764-mall-product-1@2x.png'
import product2 from './assets/128-2785-mall-product-2@2x.png'
import product3 from './assets/128-2804-mall-product-3@2x.png'
import product4 from './assets/128-2823-mall-product-4@2x.png'
import product5 from './assets/128-2842-mall-product-5@2x.png'
import product6 from './assets/128-2861-mall-product-6@2x.png'
import './points-mall.css'

// 积分商城展示态（32号裁决补充第二节第 1 条）：V1 积分只赚取/扣回、不消费——商品可
// 陈列，兑换动作不可用（不产生任何积分扣减/兑换请求/新后端写接口），页面文案明示
// 暂未开放。设计源 128:2614（我的-积分商城，2 列六件样例商品 + 当前积分卡）。
// 商品无后端：designSamples 先例（门店页/服务详情页），样例数据+一比一切图，不虚构
// 真实感数据；积分余额读现有 points 读侧（RealCouponPointsRepository.balance，券/
// 积分页既有），preview=1 走本地夹具（余额 1280 与原稿一致）。原稿「兑换/已兑完」
// 按钮按裁决渲染为置灰禁用态（沿用原稿售罄卡的灰字幽灵样式，登记偏差见 20号 §4.15）。
// 「赚积分」跳既有积分明细页（读侧）；无签到/拉新/兑换任何写路径。
const productImages: Record<string, string> = { '1': product1, '2': product2, '3': product3, '4': product4, '5': product5, '6': product6 }

export default function PointsMallPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isCouponPointsScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope, revision } = useWorkspace(preview ? 'preview' : 'real')
  const repository = useRef(preview ? undefined : new RealCouponPointsRepository(consumerApi))
  // 展示态页面：余额读取软降级（null → 显示 —），商品陈列不因读侧失败整页关闭（先例=「我的」统计条）。
  const [balance, setBalance] = useState<string | null>(null)
  const mounted = useRef(true)
  const sequence = useRef(0)
  const previousRevision = useRef(revision)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--pml-status-top': `${platformInfo.statusBarHeight || 0}px`, '--pml-unit': `${unit}px` } as CSSProperties

  const load = useCallback(async () => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    if (preview) {
      try {
        const data = await scope.run(undefined, () => new PreviewCouponPointsRepository(scenario).load())
        if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
        setBalance(data.balance.balance)
      } catch {
        if (mounted.current && current === sequence.current && currentRevision === scope.revision) setBalance(null)
      }
      return
    }
    // 未登录不设登录墙（展示页可匿名浏览），余额显示 — 并提示可登录查看。
    if (!scope.current) { if (current === sequence.current) setBalance(null); return }
    try {
      const read = await scope.run(undefined, () => repository.current!.balance())
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setBalance(read.balance)
    } catch {
      if (mounted.current && current === sequence.current && currentRevision === scope.revision) setBalance(null)
    }
  }, [preview, scenario, scope])
  useEffect(() => { mounted.current = true; void load(); return () => { mounted.current = false } }, [load])
  useEffect(() => {
    if (revision === previousRevision.current) return
    previousRevision.current = revision
    void load()
  }, [revision, load])

  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: '/consumer/pages/mine/index' })
  }
  function earnPoints() {
    // 赚积分 → 既有积分明细页（读侧）；签到/拉新等赚取入口不在本页（32号第二批各切片自管）。
    Taro.navigateTo({ url: '/consumer/pages/coupon-points/points' }).catch(() => {
      void Taro.switchTab({ url: '/consumer/pages/mine/index' })
    })
  }

  return <ConsumerPageLayout page='mallPage' unit={unit} className='pml-page'
    navigation={{ idPrefix: 'pml', onSelect: key => void switchConsumerTab(key) }}
    style={style}>
    <View className='pml-status-area' />
    <View className='pml-nav'>
      <Button id='pml-back' ariaLabel='返回' className='pml-nav-back' hoverClass='none' onClick={() => void goBack()}>
        <View className='pml-nav-back-icon' />
      </Button>
      <Text className='pml-nav-title'>{mallPageCopy.title}</Text>
    </View>
    <View className='pml-body'>
      <View className='pml-balance' id='pml-balance'>
        <Text className='pml-balance-label'>{mallPageCopy.balanceLabel}</Text>
        <Text id='pml-balance-value' className='pml-balance-value'>{balance ?? mallPageCopy.balanceUnavailable}</Text>
        <Button id='pml-earn' className='pml-earn' hoverClass='none' ariaLabel='赚积分，前往积分明细' onClick={earnPoints}>
          <Text>{mallPageCopy.earn}</Text>
        </Button>
        {balance === null && !preview && <Text className='pml-balance-hint'>{mallPageCopy.loginHint}</Text>}
      </View>
      {/* 32号裁决第二节第 1 条：页面文案明示兑换暂未开放（原稿无此条，新增说明条，登记偏差）。 */}
      <View className='pml-notice' role='status'><Text id='pml-notice'>{mallPageCopy.notice}</Text></View>
      <View className='pml-grid'>
        {mallGoods.map(good => <View key={good.goodId} id={`pml-good-${good.goodId}`}
          className='pml-good' ariaLabel={`${good.name}，需${good.points}积分，${good.stock}，${mallActionLabel(good)}（${mallPageCopy.actionDisabledHint}）`}>
          <View className='pml-good-photo'>
            <Image className='pml-good-img' src={productImages[good.image]!} mode='scaleToFill' />
            {good.hot && <View className='pml-good-hot' ariaLabel='热销'><Text>热销</Text></View>}
          </View>
          <View className='pml-good-info'>
            <Text className='pml-good-name'>{good.name}</Text>
            <View className='pml-good-bottom'>
              <View className='pml-good-points'>
                <View className='pml-good-points-line'>
                  <View className='pml-good-points-ico' />
                  <Text className='pml-good-points-num'>{good.points}</Text>
                </View>
                <Text className='pml-good-stock'>{good.stock}</Text>
              </View>
              {/* 展示态：兑换动作渲染但置灰禁用（32号裁决：不产生任何积分扣减/兑换请求）。 */}
              <Button id={`pml-good-action-${good.goodId}`}
                className={`pml-good-action${good.action === 'sold-out' ? ' is-sold-out' : ''} is-disabled`}
                disabled ariaLabel={`${mallActionLabel(good)}（${mallPageCopy.actionDisabledHint}）`}>
                <Text>{mallActionLabel(good)}</Text>
              </Button>
            </View>
          </View>
        </View>)}
      </View>
      <View className='pml-preview-note'><Text>{preview ? mallPageCopy.previewNote : mallPageCopy.realNote}</Text></View>
    </View>
  </ConsumerPageLayout>
}
