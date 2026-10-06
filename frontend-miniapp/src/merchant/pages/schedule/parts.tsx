import { Button, Picker, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useState, type CSSProperties, type ReactNode } from 'react'
import { chipClass, splitBeijingParts } from '../../schedule/model'

/**
 * Shared chrome for the four schedule-maintenance pages. There is no Figma original for the
 * contract-conformant pages (registry §4: frame 12:6214 depicts the forbidden weekly
 * hourly-template model), so the pages follow the M-002 page language — services/page.css
 * measures and tokens — via schedule/page.css. Status variants are class names, never
 * data-* attributes (Taro 4.1.5 drops dynamic data-* from the native wxml).
 */
export function useScheduleStyle(): CSSProperties {
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  return { '--sch-status-top': `${platformInfo.statusBarHeight || 0}px`, '--sch-unit': `${unit}px` } as CSSProperties
}
export type PagePhase = 'idle' | 'loading' | 'ready' | 'load-error' | 'entry' | 'closed'
export function ScheduleShell(props: {
  title: string
  style: CSSProperties
  phase: PagePhase
  notice: string
  closedReason: string
  onRetry: () => void
  onBack: () => void
  preview: boolean
  children: ReactNode
}) {
  return <View className='sch-page' style={props.style}>
    <View className='sch-status-area' />
    <View className='sch-header'>
      <Button ariaLabel='返回' className='sch-back' onClick={props.onBack}>返回</Button>
      <Text className='sch-title'>{props.title}</Text>
    </View>
    {props.phase !== 'ready' && <View className='sch-state' role='status'>
      {props.phase === 'closed'
        ? <View className='sch-closed' role='alert'>
            <Text className='sch-closed-title'>功能未开放</Text>
            <Text className='sch-closed-text'>{props.closedReason || '排期维护功能未开放，请稍后再试。'}</Text>
          </View>
        : <Text>{props.phase === 'loading' || props.phase === 'idle' ? '正在加载排期数据…'
            : props.phase === 'entry' ? '请从商家工作台进入排期维护。'
            : props.notice || '加载失败，请重试。'}</Text>}
      {props.phase === 'load-error' && <Button className='sch-state-action' onClick={props.onRetry}>重新加载</Button>}
      {props.phase === 'entry' && !props.preview && <Button className='sch-state-action' onClick={() => Taro.redirectTo({ url: '/merchant/pages/workspace/index' })}>去商家工作台</Button>}
    </View>}
    {props.phase === 'ready' && props.children}
  </View>
}
export function Field(props: { label: string; required?: boolean; hint?: string; children: ReactNode }) {
  return <View className='sch-section'>
    <Text className='sch-label'>{props.label}{props.required && <Text className='sch-required'>*</Text>}</Text>
    {props.children}
    {props.hint && <Text className='sch-hint'>{props.hint}</Text>}
  </View>
}
/** Minute-granularity Beijing wall-clock interval (date + time pickers, half-open semantics).
 *  `dateOnly` hides the time pickers for calendar-day ranges (batch close). */
export function IntervalFields(props: {
  startDate: string; startTime: string; endDate: string; endTime: string
  disabled?: boolean
  dateOnly?: boolean
  labels?: { start?: string; end?: string }
  onChange: (next: { startDate?: string; startTime?: string; endDate?: string; endTime?: string }) => void
}) {
  return <View className='sch-row'>
    <Field label={props.labels?.start ?? '开始'} required>
      <Picker mode='date' value={props.startDate} disabled={props.disabled}
        onChange={event => props.onChange({ startDate: event.detail.value })}>
        <View className='sch-picker'><Text>{props.startDate}</Text></View>
      </Picker>
      {!props.dateOnly && <Picker mode='time' value={props.startTime} disabled={props.disabled}
        onChange={event => props.onChange({ startTime: event.detail.value })}>
        <View className='sch-picker'><Text>{props.startTime}</Text></View>
      </Picker>}
    </Field>
    <Field label={props.labels?.end ?? '结束'} required>
      <Picker mode='date' value={props.endDate} disabled={props.disabled}
        onChange={event => props.onChange({ endDate: event.detail.value })}>
        <View className='sch-picker'><Text>{props.endDate}</Text></View>
      </Picker>
      {!props.dateOnly && <Picker mode='time' value={props.endTime} disabled={props.disabled}
        onChange={event => props.onChange({ endTime: event.detail.value })}>
        <View className='sch-picker'><Text>{props.endTime}</Text></View>
      </Picker>}
    </Field>
  </View>
}
export function intervalText(startAt: string, endAt: string): string {
  const start = splitBeijingParts(startAt), end = splitBeijingParts(endAt)
  return start.date === end.date ? `${start.date} ${start.time}-${end.time}` : `${start.date} ${start.time} ~ ${end.date} ${end.time}`
}
export function Chip(props: { selected: boolean; disabled?: boolean; onClick: () => void; children: ReactNode }) {
  return <Button className={chipClass(props.selected)} disabled={props.disabled} onClick={props.onClick}>
    <Text>{props.children}</Text>
  </Button>
}
