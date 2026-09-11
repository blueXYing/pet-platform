import { Button, Text, View } from '@tarojs/components'
import { useState } from 'react'
import { platform } from '../../../shared/platform'
export default function Shell() {
  const [count, setCount] = useState(0)
  return <View className='shell'>
    <View className='panel'><Text className='line'>C-001 工程示例</Text>
      <Text>内部 fixture · 非产品页面 · 非真实登录</Text></View>
    <Text className='line'>局部计数：{count}</Text>
    <Button onClick={() => setCount(value => value + 1)}>增加计数</Button>
    <Button onClick={() => platform.navigate('/consumer/pages/diagnostics/index')}>打开隔离验证页</Button>
  </View>
}
