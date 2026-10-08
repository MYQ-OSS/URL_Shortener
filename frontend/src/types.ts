export interface LinkRecord {
  id: string
  shortUri: string
  domain: string
  originUrl: string
  gid: string
  enableStatus: number
  validType: number
  validEnd: string | null
  clickNum: number
  describe: string | null
  createTime: string | null
}

export interface CreateLinkInput {
  originUrl: string
  gid: string
  forceNew: boolean
  validType: 0 | 1
  validEnd: string | null
  describe: string | null
}

export interface Connection {
  baseUrl: string
  gid: string
  apiToken: string
  adminToken: string
}

export interface RuntimeStats {
  localCache: { size: number; hitCount: number; missCount: number; hitRate: string; evictionCount: number }
  stats: { pending: number; bufferCapacity: number; dropped: number; publishFailures: number; flushedRows: number; lastFlushAt: number; deliveryMode: 'best-effort' }
}

export type LinkStatus = 'active' | 'disabled' | 'expired' | 'blocked'
export type MaintenanceAction = 'cache' | 'expired'

/**
 * 维护操作结果。
 *
 * expired 清理的 status 与后端 CleanupStatus 一致：completed 跑完、truncated 时间预算用尽仍有剩余、
 * skipped_lock_held 定时任务或其他实例正在执行、interrupted 被中断。
 * cleanedRows=0 时必须结合 executed 判断，不能读成“没有过期数据”。
 */
export interface MaintenanceResult {
  status?: 'completed' | 'truncated' | 'skipped_lock_held' | 'interrupted' | string
  executed?: boolean
  cleanedRows?: number
  moreRemaining?: boolean
  cleared?: number
}
