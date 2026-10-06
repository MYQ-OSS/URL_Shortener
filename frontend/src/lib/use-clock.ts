import { useIntervalFn, useNow } from '@vueuse/core'

/** 只需秒级更新有效期，避免默认逐帧调度触发整张表重算。 */
export function useClock() {
  return useNow({ scheduler: callback => useIntervalFn(callback, 1000) })
}
