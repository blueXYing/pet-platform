import { Button, Input, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useDidHide } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { afterSaleClient } from '../../../shared/aftersale-runtime'
import type { AfterSaleStatus } from '../../../shared/aftersale-api'
import { consumerController } from '../../aftersale/runtime'
import { afterSaleMessage, isId, statusLabel } from '../../aftersale/model'
import { AfterSaleFrame, Card, Notice, formatTime } from './common'

const filters: { code: AfterSaleStatus | undefined; label: string }[] = [{ code: undefined, label: '全部' }, { code: 'PENDING', label: '待受理' }, { code: 'PROCESSING', label: '处理中' }, { code: 'WAITING_SUPPLEMENT', label: '待补证' }, { code: 'RESOLVED', label: '已裁决' }, { code: 'WITHDRAWN', label: '已撤回' }, { code: 'INVALIDATED', label: '已失效' }, { code: 'CLOSED', label: '已关闭' }]
export default function AfterSaleListPage() {
  const { revision } = useWorkspace('real')
  return <ListScreen key={revision} />
}
function ListScreen() {
  const [client] = useState(() => afterSaleClient('c'))
  const [controller] = useState(() => consumerController(client))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [status, setStatus] = useState<AfterSaleStatus | undefined>()
  const [orderId, setOrderId] = useState('')
  const [notice, setNotice] = useState('')
  async function load(page = 1, filter = status) {
    setNotice('')
    try { await client.api.restore(); await controller.loadList(page, filter) }
    catch (error) { setNotice(afterSaleMessage(error)) }
  }
  useDidShow(() => { void load() })
  useDidHide(() => client.clearImages())
  useEffect(() => () => { controller.dispose(); client.dispose() }, [controller, client])
  function apply() {
    if (!isId(orderId)) { setNotice('请输入真实订单号'); return }
    void Taro.navigateTo({ url: `/consumer/pages/aftersale/apply?orderId=${orderId}` })
  }
  return <AfterSaleFrame title='我的售后'>
    <Notice text={notice || state.notice} />
    <Card title='查看订单售后资格'><Text className='afs-hint'>输入本人的订单号，资格以订单实时校验结果为准。</Text><Input id='afs-order-id' className='afs-input afs-field' value={orderId} type='text' maxlength={19} placeholder='订单号' onInput={event => setOrderId(event.detail.value)} /><View className='afs-actions afs-divider'><Button className='afs-secondary' id='afs-check-eligibility' onClick={apply}>查看申请资格</Button></View></Card>
    <View className='afs-filter'>{filters.map(item => <Button key={item.code || 'all'} className={status === item.code ? 'selected' : ''} disabled={state.phase === 'loading'} onClick={() => { setStatus(item.code); void load(1, item.code) }}>{item.label}</Button>)}</View>
    {state.phase === 'loading' && <Notice text='正在读取售后工单…' />}
    {state.phase === 'ready' && state.page?.items.length === 0 && <Card><Text className='afs-hint'>暂无符合条件的售后申请</Text></Card>}
    {state.page?.items.map(item => <Card key={item.afterSaleId}><View className='afs-row'><Text className='afs-heading'>售后工单 {item.afterSaleId}</Text><Text className='afs-status'>{statusLabel(item.status)}</Text></View><Text className='afs-hint'>订单号 {item.orderId}</Text><Text className='afs-hint'>申请时间：{formatTime(item.createdAt)}</Text><View className='afs-actions afs-divider'><Button id={`afs-detail-${item.afterSaleId}`} className='afs-secondary' onClick={() => void Taro.navigateTo({ url: `/consumer/pages/aftersale/detail?afterSaleId=${item.afterSaleId}` })}>查看进度</Button></View></Card>)}
    <View className='afs-actions'><Button className='afs-secondary' disabled={state.phase === 'loading'} onClick={() => void load(state.page?.page || 1)}>刷新</Button>{state.page && <><Button className='afs-secondary' disabled={state.phase === 'loading' || state.page.page <= 1} onClick={() => void load(state.page!.page - 1)}>上一页</Button><Button className='afs-secondary' disabled={state.phase === 'loading' || state.page.page * state.page.pageSize >= state.page.total} onClick={() => void load(state.page!.page + 1)}>下一页</Button></>}</View>
  </AfterSaleFrame>
}
