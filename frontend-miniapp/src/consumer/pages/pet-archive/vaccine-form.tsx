import { View, Text } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useEffect } from 'react'

// 兼容旧深链：疫苗记录添加已改为档案详情上的底部半框；独立路由重定向到详情并带 sheet 参数。
export default function PetVaccineRecordFormRedirect() {
  const route = useRouter()
  useEffect(() => {
    const petId = route.params.petId || ''
    const query = [`petId=${encodeURIComponent(petId)}`, 'recordSheet=vaccine']
    if (route.params.preview === '1') query.push('preview=1')
    if (route.params.scenario) query.push(`scenario=${encodeURIComponent(route.params.scenario)}`)
    void Taro.redirectTo({ url: `/consumer/pages/pet-archive/detail?${query.join('&')}` })
  }, [route.params.petId, route.params.preview, route.params.scenario])
  return <View style={{ minHeight: '100vh', background: '#f0fbff', paddingTop: '40vh', textAlign: 'center' }}>
    <Text>正在打开记录表单…</Text>
  </View>
}
