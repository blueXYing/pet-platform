import { Button, Canvas, Image, RichText, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealBaikeRepository, previewRepository, isBaikeScenario } from '../../baike/repository'
import {
  isBreedNotFound, radarAxes, sanitizeRichText,
  type BreedDetail, type BreedRadar,
} from '../../baike/model'
import backIcon from '../../assets/profile/back.png'
import './detail.css'

// 品种百科详情页（设计稿 253:953，登记表 §4.16）。契约 62 读侧：hero（名称+体型签+别名+
// 标签+简介+封面）、事实卡三枚、适养能力图谱五维雷达、品种介绍富文本、健康与护理 + 科普
// 免责声明（PRD 硬规则）、相关推荐（PRD 叙述功能，设计缺稿沿白卡家族补最小实现）。
// 偏差登记：①原稿标题「宠物信息」为设计残留 → 按语义「品种百科」；②健康提示必须展示科普
// 免责声明（PRD「医疗和健康内容仅用于科普，不替代兽医诊断」）为固定渲染；③适养建议富文本
// 无原稿槽位，落图谱区下方；④封面为占位 URL，onError 回退分类渐底 + 品种首字。
type Phase = 'loading' | 'ready' | 'load-error' | 'missing'

export default function BaikeDetailPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isBaikeScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const breedId = preview ? '711001' : (route.params.breedId || '')
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [phase, setPhase] = useState<Phase>('loading')
  const [detail, setDetail] = useState<BreedDetail | null>(null)
  const [failedCovers, setFailedCovers] = useState<ReadonlySet<string>>(new Set())
  const mounted = useRef(true)
  const sequence = useRef(0)
  const repository = useRef(preview ? previewRepository(scope, scenario) : new RealBaikeRepository(consumerApi))
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = {
    '--bkd-top': `${platformInfo.statusBarHeight || 0}px`,
    '--bkd-unit': `${unit}px`,
  } as CSSProperties

  const load = useCallback(async () => {
    const current = ++sequence.current
    setPhase('loading')
    try {
      const value = await scope.run(undefined, () => repository.current.detail(breedId))
      if (!mounted.current || current !== sequence.current) return
      setDetail(value); setPhase('ready')
    } catch (error) {
      if (!mounted.current || current !== sequence.current) return
      setPhase(isBreedNotFound(error) ? 'missing' : 'load-error')
    }
  }, [breedId, scope])
  useEffect(() => { void load() }, [load])
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; sequence.current++ }
  }, [])
  useEffect(() => {
    if (phase === 'ready' && detail?.radar) drawRadar('bk-radar', detail.radar, unit)
  }, [phase, detail, unit])

  const goBack = () => { Taro.navigateBack().catch(() => Taro.switchTab({ url: '/consumer/pages/mine/index' })) }
  const backToList = () => {
    Taro.navigateBack().catch(() => Taro.redirectTo({
      url: `/consumer/pages/baike/index${preview ? '?preview=1&scenario=' + scenario : ''}`,
    }).catch(() => {}))
  }
  const openRelated = (id: string) => {
    if (preview) return
    Taro.redirectTo({ url: `/consumer/pages/baike/detail?breedId=${id}` })
      .catch(() => Taro.showToast({ title: '页面打开失败，请重试', icon: 'none' }))
  }
  const coverFailed = (id: string) => setFailedCovers(previous => new Set(previous).add(id))

  return (
    <View className='cpn-page bkd-page' style={style}>
      <View className='bkd-status-area' />
      <View className='bkd-header'>
        <Button className='bkd-back' hoverClass='none' ariaLabel='返回' onClick={goBack}>
          <Image className='bkd-back-icon' src={backIcon} mode='aspectFit' />
        </Button>
        <Text className='bkd-title'>品种百科</Text>
      </View>
      <ScrollView className='bkd-body' scrollY enhanced showScrollbar={false}>
        {phase === 'loading' && (
          <View className='bkd-state'>
            <View className='bkd-skeleton-hero' />
            <View className='bkd-skeleton-block' />
            <View className='bkd-skeleton-block' />
          </View>
        )}
        {phase === 'load-error' && (
          <View className='bkd-state'>
            <Text className='bkd-state-text'>百科内容加载失败，请稍后重试</Text>
            <Button className='bkd-state-btn' hoverClass='none' onClick={() => { void load() }}>重新加载</Button>
          </View>
        )}
        {phase === 'missing' && (
          <View className='bkd-state'>
            <Text className='bkd-state-text'>内容不存在或已下线</Text>
            <Button className='bkd-state-btn' hoverClass='none' onClick={backToList}>返回百科首页</Button>
          </View>
        )}
        {phase === 'ready' && detail && (
          <View className='bkd-ready'>
            {/* hero（原稿奶油底 + 涂鸦装饰带：品种名 + 体型签 + 别名 + 标签 + 简介 + 右侧封面卡） */}
            <View className='bkd-hero'>
              <View className='bkd-hero-main'>
                <View className='bkd-hero-head'>
                  <Text className='bkd-hero-name'>{detail.name}</Text>
                  {detail.bodyType && <View className='bkd-body-chip'><Text className='bkd-body-chip-text'>{detail.bodyType}</Text></View>}
                </View>
                {!!detail.aliases.length && <Text className='bkd-hero-alias'>别名：{detail.aliases.join('、')}</Text>}
                {!!detail.tags.length && (
                  <View className='bkd-hero-tags'>
                    {detail.tags.slice(0, 4).map(item => <View key={item} className='bkd-chip'><Text className='bkd-chip-text'>{item}</Text></View>)}
                  </View>
                )}
                <Text className='bkd-hero-summary'>{detail.summary}</Text>
              </View>
              <View className={`bkd-hero-cover bkd-cover-${detail.categoryCode.toLowerCase()}`}>
                {detail.coverUrl && !failedCovers.has(detail.breedId)
                  ? <Image className='bkd-hero-cover-img' src={detail.coverUrl} mode='aspectFill' onError={() => coverFailed(detail.breedId)} />
                  : <Text className='bkd-hero-cover-letter'>{detail.name.slice(0, 1)}</Text>}
              </View>
            </View>
            {/* 事实卡三枚（原稿 134x95 卡：原产地/平均寿命/参考体重） */}
            {!!detail.facts.length && (
              <View className='bkd-facts'>
                {detail.facts.map(fact => (
                  <View key={fact.label} className='bkd-fact'>
                    <Text className='bkd-fact-label'>{fact.label}</Text>
                    <Text className='bkd-fact-value'>{fact.value}</Text>
                  </View>
                ))}
              </View>
            )}
            {/* 适养能力图谱（原稿五维雷达：陪伴需求/掉毛程度/聪明度/易养度/运动需求）+ 适养建议 */}
            {detail.radar && (
              <View className='bkd-section'>
                <Text className='bkd-section-title'>适养能力图谱</Text>
                <View className='bkd-radar-wrap'>
                  <CanvasRadar id='bk-radar' />
                  {radarAxes.map((axis, index) => (
                    <Text key={axis.label} className={`bkd-radar-label bkd-radar-label-${index}`}>{axis.label}</Text>
                  ))}
                </View>
                {detail.careAdviceHtml && (
                  <View className='bkd-care'>
                    <Text className='bkd-care-title'>适养建议</Text>
                    <View className='bkd-rich'>
                      <RichText nodes={sanitizeRichText(detail.careAdviceHtml)} />
                    </View>
                  </View>
                )}
              </View>
            )}
            {/* 品种介绍（性格特点 chips + 品种描述富文本：起源历史/外貌特征/气质与行为） */}
            <View className='bkd-section'>
              {!!detail.tags.length && (
                <View className='bkd-personality'>
                  <Text className='bkd-section-sub'>性格特点</Text>
                  <View className='bkd-personality-chips'>
                    {detail.tags.map(item => <View key={item} className='bkd-chip'><Text className='bkd-chip-text'>{item}</Text></View>)}
                  </View>
                </View>
              )}
              {detail.descriptionHtml && (
                <View className='bkd-rich'>
                  <RichText nodes={sanitizeRichText(detail.descriptionHtml)} />
                </View>
              )}
            </View>
            {/* 健康与护理 + 科普免责声明（PRD 硬规则：必须展示） */}
            {(detail.healthTipsHtml || detail.careAdviceHtml) && (
              <View className='bkd-section'>
                <Text className='bkd-section-title'>健康与护理</Text>
                {detail.healthTipsHtml && (
                  <View className='bkd-rich'>
                    <RichText nodes={sanitizeRichText(detail.healthTipsHtml)} />
                  </View>
                )}
                <View className='bkd-disclaimer'>
                  <Text className='bkd-disclaimer-text'>以上内容仅用于科普，不替代兽医诊断；如爱宠出现不适，请及时就医。</Text>
                </View>
              </View>
            )}
            {/* 相关推荐（PRD 核心功能：同分类已发布品种前 3，设计缺稿沿白卡家族） */}
            {!!detail.related.length && (
              <View className='bkd-section'>
                <Text className='bkd-section-title'>相关推荐</Text>
                <ScrollView className='bkd-related' scrollX enhanced showScrollbar={false}>
                  {detail.related.map(breed => (
                    <Button key={breed.breedId} className='bkd-related-card' hoverClass='none' onClick={() => openRelated(breed.breedId)}>
                      <View className={`bkd-related-cover bkd-cover-${breed.categoryCode.toLowerCase()}`}>
                        {breed.coverUrl && !failedCovers.has(breed.breedId)
                          ? <Image className='bkd-related-img' src={breed.coverUrl} mode='aspectFill' onError={() => coverFailed(breed.breedId)} />
                          : <Text className='bkd-related-letter'>{breed.name.slice(0, 1)}</Text>}
                      </View>
                      <Text className='bkd-related-name'>{breed.name}</Text>
                      <Text className='bkd-related-summary'>{breed.summary}</Text>
                    </Button>
                  ))}
                </ScrollView>
              </View>
            )}
            <View className='bkd-tail'><Text>内容由平台运营发布</Text></View>
          </View>
        )}
      </ScrollView>
    </View>
  )
}

/** 雷达画布：weapp canvas 2d，五维正五边形网格 + 数据多边形。 */
function CanvasRadar(props: { id: string }) {
  return <Canvas id={props.id} type='2d' className='bkd-radar-canvas' />
}

function drawRadar(id: string, radar: BreedRadar, unit: number) {
  const query = Taro.createSelectorQuery()
  query.select(`#${id}`).fields({ node: true, size: true }).exec((result: Array<{ node?: { width: number; height: number; getContext: (kind: '2d') => CanvasRenderingContext2D } } | null>) => {
    const entry = result && result[0]
    const canvas = entry?.node
    if (!canvas) return
    const width = Math.round(unit * 260)
    const height = Math.round(unit * 200)
    const dpr = (Taro.getWindowInfo().pixelRatio) || 2
    canvas.width = width * dpr
    canvas.height = height * dpr
    const ctx = canvas.getContext('2d')
    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.scale(dpr, dpr)
    const cx = width / 2
    const cy = height / 2 + 6
    const radius = Math.min(width, height) / 2 - 14
    const values = [radar.companion, radar.shedding, radar.intelligence, radar.easyCare, radar.exercise]
    const point = (index: number, ratio: number) => {
      const angle = -Math.PI / 2 + index * (2 * Math.PI / 5)
      return [cx + radius * ratio * Math.cos(angle), cy + radius * ratio * Math.sin(angle)] as const
    }
    // 网格四环
    ctx.strokeStyle = '#c0ecff'
    ctx.lineWidth = 1
    for (const ring of [0.25, 0.5, 0.75, 1]) {
      ctx.beginPath()
      for (let index = 0; index < 5; index++) {
        const [x, y] = point(index, ring)
        if (index === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y)
      }
      ctx.closePath()
      ctx.stroke()
    }
    // 轴线
    ctx.beginPath()
    for (let index = 0; index < 5; index++) {
      const [x, y] = point(index, 1)
      ctx.moveTo(cx, cy); ctx.lineTo(x, y)
    }
    ctx.stroke()
    // 数据面
    ctx.beginPath()
    values.forEach((value, index) => {
      const [x, y] = point(index, value / 5)
      if (index === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y)
    })
    ctx.closePath()
    ctx.fillStyle = 'rgba(91, 170, 232, 0.30)'
    ctx.fill()
    ctx.strokeStyle = '#5baae8'
    ctx.lineWidth = 2
    ctx.stroke()
    // 数据点
    ctx.fillStyle = '#2f83c7'
    values.forEach((value, index) => {
      const [x, y] = point(index, value / 5)
      ctx.beginPath(); ctx.arc(x, y, 3, 0, Math.PI * 2); ctx.fill()
    })
  })
}
