import { Button, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'
import { consumerFixture, loadEngineeringFixture, type EngineeringFixture } from '../../../shared/fixture'
import { useWorkspace } from '../../../shared/workspace-react'
export default function Diagnostics() {
  const { scope, revision, context } = useWorkspace()
  const [result, setResult] = useState<{ revision: number; data?: EngineeringFixture; error?: string }>()
  useEffect(() => { setResult(undefined) }, [revision])
  async function load() {
    const started = scope.revision
    try {
      const data = await scope.run('engineering', loadEngineeringFixture)
      if (started === scope.revision) setResult({ revision: started, data })
    } catch (error) {
      if (started === scope.revision) setResult({ revision: started, error: String(error) })
    }
  }
  const visible = result?.revision === revision ? result : undefined
  return <View className='shell'>
    <Text className='line'>内部上下文：{context?.workspace ?? '已清除'}</Text>
    <Text className='line'>无真实会话、权限或业务操作</Text>
    <Button disabled={!context} onClick={load}>读取内部样本</Button>
    <Button onClick={() => scope.replace(null)}>清除上下文和缓存</Button>
    <Button onClick={() => scope.replace(consumerFixture)}>重新注入样本上下文</Button>
    {visible?.data && <Text className='line'>{JSON.stringify(visible.data)}</Text>}
    {visible?.error && <Text className='line'>{visible.error}</Text>}
  </View>
}
