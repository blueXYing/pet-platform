import type { Location } from './model'

export type NativeLocation = { address: string; name: string; longitude: number; latitude: number }
function coordinate(value: number, bound: number) {
  if (!Number.isFinite(value) || Math.abs(value) > bound) throw new Error('LOCATION_INVALID')
  return value.toFixed(7).replace(/\.?0+$/, '') || '0'
}
/** WeChat native picker supplies GCJ-02 coordinates, not the platform's open-city key. */
export function nativeLocationAdapter(chooseLocation: () => Promise<NativeLocation>) {
  return async (): Promise<Location | null> => {
    try {
      const result = await chooseLocation()
      const address = [result.address?.trim(), result.name?.trim()].filter(Boolean).filter((value, index, all) => all.indexOf(value) === index).join(' ')
      if (!address || [...address].length > 255) throw new Error('LOCATION_INVALID')
      return { address, longitude: coordinate(result.longitude, 180), latitude: coordinate(result.latitude, 90) }
    } catch (error) {
      const message = error && typeof error === 'object' && 'errMsg' in error ? String(error.errMsg) : ''
      if (/^chooseLocation:fail cancel$/i.test(message)) return null
      if (/auth deny|auth denied|authorize|permission|privacy/i.test(message)) throw new Error('LOCATION_PERMISSION_DENIED')
      if (error instanceof Error && error.message === 'LOCATION_INVALID') throw error
      throw new Error('LOCATION_UNAVAILABLE')
    }
  }
}
