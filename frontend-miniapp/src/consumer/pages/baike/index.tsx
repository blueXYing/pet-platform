import { Button, Image, Input, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealBaikeRepository, previewRepository, isBaikeScenario, type BaikeScenario } from '../../baike/repository'
import {
  highlightParts, normalizeKeyword,
  type BreedCard, type CategoryCode, type EncyclopediaCategory,
} from '../../baike/model'
import backIcon from '../../assets/profile/back.png'
import './index.css'

// 品种百科列表页（设计稿 245:5375，登记表 §4.16）。契约 62 读侧：分类字典 + 分页列表
// （分类切换/关键词搜索/标签筛选），匿名可浏览（PRD「所有用户；未登录用户可浏览」）。
// 偏差登记：①原稿导航标题「宠物信息」为设计残留 → 按语义「品种百科」（§4.12 偏差①同口径）；
// ②原稿分类 tab 4 枚（汪星人/喵星人/异宠/植物）与 PRD 枚举 5 枚冲突，按 PRD 原文渲染 5 枚，
// 视觉沿原稿 tab 样式；③原稿榜单胶囊文案为占位（三条重复），落地为品种标签筛选胶囊；
// ④tab/胶囊图标为切图位，V1 以文字胶囊落地，正式切图待设计补稿；⑤原稿底部 tab 为整页
// 设计残留，子包页不带 tabBar（既有裁决）。
// 官方色值：#5baae8 主蓝 / #c0ecff 浅蓝 / #f0fbff 页底 / #daf4ff 胶囊底。
// WXSS 铁律：button 全重置、无嵌套、状态仅 className、禁 data-*（类名变体替代）。
type Phase = 'loading' | 'ready' | 'load-error'
const PAGE_SIZE = 20
// 标签筛选胶囊（契约 62：取种子标签字典；对应原稿三枚榜单占位胶囊）。
const TAG_FILTERS = ['新手友好', '陪伴需求', '家庭适养'] as const

export default function BaikeListPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario: BaikeScenario = preview && isBaikeScenario(route.params.scenario) ? route.params.scenario : 'normal'
  // 入口可携带 keyword（宠物档案品种行互跳：keyword=品种名，PRD 5.1.4 核心功能 4）。
  const initialKeyword = (() => {
    try { return route.params.keyword ? normalizeKeyword(decodeURIComponent(route.params.keyword)) : '' } catch { return '' }
  })()
  const { scope, revision } = useWorkspace(preview ? 'preview' : 'real')
  const [phase, setPhase] = useState<Phase>('loading')
  const [categories, setCategories] = useState<readonly EncyclopediaCategory[]>([])
  const [category, setCategory] = useState<CategoryCode>('DOG')
  const [keywordInput, setKeywordInput] = useState(initialKeyword)
  const [keyword, setKeyword] = useState(initialKeyword)
  const [tag, setTag] = useState<string | null>(null)
  const [items, setItems] = useState<readonly BreedCard[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [refreshing, setRefreshing] = useState(false)
  const [notice, setNotice] = useState('')
  const mounted = useRef(true)
  const sequence = useRef(0)
  const repository = useRef(preview ? previewRepository(scope, scenario) : new RealBaikeRepository(consumerApi))
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = {
    '--bk-top': `${platformInfo.statusBarHeight || 0}px`,
    '--bk-unit': `${unit}px`,
  } as CSSProperties

  const load = useCallback(async (next: { category: CategoryCode; keyword: string; tag: string | null; page: number }, append: boolean) => {
    const current = ++sequence.current
    if (!append) setPhase('loading')
    try {
      const value = await scope.run(undefined, () => repository.current.page({
        categoryCode: next.category, keyword: next.keyword, tag: next.tag, page: next.page, pageSize: PAGE_SIZE,
      }))
      if (!mounted.current || current !== sequence.current) return
      setItems(previous => append ? [...previous, ...value.items] : value.items)
      setTotal(value.total)
      setPage(next.page)
      setPhase('ready')
    } catch {
      if (!mounted.current || current !== sequence.current) return
      if (append) setNotice('加载失败，请稍后重试')
      else setPhase('load-error')
    }
  }, [scope])

  const bootstrap = useCallback(async () => {
    const current = ++sequence.current
    setPhase('loading')
    try {
      const dictionary = await scope.run(undefined, () => repository.current.categories())
      if (!mounted.current || current !== sequence.current) return
      setCategories(dictionary)
      // PRD 默认值 狗狗；字典首项即 DOG，以服务端字典序为准。
      const first = dictionary[0]?.categoryCode ?? 'DOG'
      setCategory(previous => (previous === 'DOG' || dictionary.some(item => item.categoryCode === previous)) ? previous : first)
    } catch {
      if (!mounted.current || current !== sequence.current) return
      setPhase('load-error')
    }
  }, [scope])
  useDidShow(() => {
    if (!categories.length) void bootstrap()
  })
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; sequence.current++ }
  }, [])
  useEffect(() => {
    // 分类/关键词/标签任一变化 → 回第一页整表刷新（PRD：切换分类后显示加载状态）。
    void load({ category, keyword, tag, page: 1 }, false)
  }, [category, keyword, tag])

  const submitKeyword = () => {
    try { setKeyword(normalizeKeyword(keywordInput)) } catch { setNotice('关键词最多40字'); }
  }
  const pickCategory = (code: CategoryCode) => { if (code !== category) setCategory(code) }
  const pickTag = (next: string | null) => setTag(previous => previous === next ? null : next)
  const loadMore = () => {
    if (phase !== 'ready' || items.length >= total) return
    void load({ category, keyword, tag, page: page + 1 }, true)
  }
  const refresh = async () => {
    setRefreshing(true)
    try { await load({ category, keyword, tag, page: 1 }, false) } finally { setRefreshing(false) }
  }
  const openDetail = (breedId: string) => {
    Taro.navigateTo({ url: `/consumer/pages/baike/detail?breedId=${breedId}${preview ? '&preview=1&scenario=' + scenario : ''}` })
      .catch(() => Taro.showToast({ title: '页面打开失败，请重试', icon: 'none' }))
  }
  const goBack = () => { Taro.navigateBack().catch(() => Taro.switchTab({ url: '/consumer/pages/mine/index' })) }
  const reset = () => { setKeywordInput(''); setKeyword(''); setTag(null) }

  return (
    <View className='cpn-page bk-page' style={style}>
      <View className='bk-status-area' />
      <View className='bk-header'>
        <Button className='bk-back' hoverClass='none' ariaLabel='返回' onClick={goBack}>
          <Image className='bk-back-icon' src={backIcon} mode='aspectFit' />
        </Button>
        <Text className='bk-title'>品种百科</Text>
      </View>
      <ScrollView
        className='bk-body' scrollY enhanced showScrollbar={false}
        refresherEnabled refresherTriggered={refreshing} onRefresherRefresh={() => { void refresh() }}
        onScrollToLower={loadMore}
      >
        <View className='bk-search'>
          <View className='bk-search-icon' />
          <Input
            className='bk-search-input' type='text' maxlength={40} value={keywordInput}
            placeholder='搜索品种或关键词' placeholderClass='bk-search-placeholder'
            onInput={event => setKeywordInput(event.detail.value)}
            onConfirm={submitKeyword}
          />
          <Button className='bk-search-btn' hoverClass='none' onClick={submitKeyword}>搜索</Button>
        </View>
        <View className='bk-tags'>
          <Button className={`bk-tag${tag === null ? ' bk-tag-on' : ''}`} hoverClass='none' onClick={() => pickTag(null)}>全部</Button>
          {TAG_FILTERS.map(item => (
            <Button key={item} className={`bk-tag${tag === item ? ' bk-tag-on' : ''}`} hoverClass='none' onClick={() => pickTag(item)}>{item}</Button>
          ))}
        </View>
        <View className='bk-cats'>
          {(categories.length ? categories : [{ categoryCode: 'DOG', categoryName: '狗狗', sortOrder: 1 }] as EncyclopediaCategory[]).map(item => (
            <Button key={item.categoryCode} className={`bk-cat${item.categoryCode === category ? ' bk-cat-on' : ''}`}
              hoverClass='none' onClick={() => pickCategory(item.categoryCode)}>
              <Text className='bk-cat-name'>{item.categoryName}</Text>
            </Button>
          ))}
        </View>
        {phase === 'loading' && (
          <View className='bk-state'>
            {[0, 1, 2].map(seed => <View key={seed} className='bk-skeleton-card'>
              <View className='bk-skeleton-img' />
              <View className='bk-skeleton-lines'>
                <View className='bk-skeleton-line' />
                <View className='bk-skeleton-line bk-skeleton-short' />
              </View>
            </View>)}
          </View>
        )}
        {phase === 'load-error' && (
          <View className='bk-state'>
            <Text className='bk-state-text'>百科内容加载失败，请稍后重试</Text>
            <Button className='bk-state-btn' hoverClass='none' onClick={() => { void bootstrap() }}>重新加载</Button>
          </View>
        )}
        {phase === 'ready' && !items.length && (
          <View className='bk-state'>
            <Text className='bk-state-text'>{keyword || tag ? '没有找到匹配的品种，换个条件试试' : '该分类内容筹备中，敬请期待'}</Text>
            {(keyword || tag) && <Button className='bk-state-btn' hoverClass='none' onClick={reset}>清空条件</Button>}
          </View>
        )}
        {phase === 'ready' && !!items.length && (
          <View className='bk-list'>
            {items.map(breed => <BreedRow key={breed.breedId} breed={breed} keyword={keyword} onOpen={openDetail} />)}
            {items.length < total && <View className='bk-more'><Text className='bk-more-text'>上拉加载更多</Text></View>}
          </View>
        )}
        {notice ? <Text className='bk-notice'>{notice}</Text> : null}
        <View className='bk-tail'><Text>内容由平台运营发布，健康内容仅用于科普</Text></View>
      </ScrollView>
    </View>
  )
}

function BreedRow(props: { breed: BreedCard; keyword: string; onOpen: (breedId: string) => void }) {
  const { breed, keyword, onOpen } = props
  return (
    <Button className='bk-card' hoverClass='none' onClick={() => onOpen(breed.breedId)}>
      <View className={`bk-cover bk-cover-${breed.categoryCode.toLowerCase()}`}>
        {breed.coverUrl
          ? <Image className='bk-cover-img' src={breed.coverUrl} mode='aspectFill'
            onError={() => { /* 素材缺稿：onError 由外层渐底 + 品种首字占位回退承载 */ }} />
          : null}
        <Text className='bk-cover-letter'>{breed.name.slice(0, 1)}</Text>
      </View>
      <View className='bk-card-main'>
        <View className='bk-card-head'>
          <Text className='bk-card-name'>
            {highlightParts(breed.name, keyword).map((part, index) =>
              part.hit ? <Text key={index} className='bk-hit'>{part.text}</Text> : <Text key={index}>{part.text}</Text>)}
          </Text>
          {!!breed.aliases.length && <Text className='bk-card-alias'>{breed.aliases.join('、')}</Text>}
        </View>
        <Text className='bk-card-summary'>{breed.summary}</Text>
        <View className='bk-card-tags'>
          {breed.tags.slice(0, 4).map(item => <View key={item} className='bk-chip'><Text className='bk-chip-text'>{item}</Text></View>)}
        </View>
      </View>
    </Button>
  )
}
