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
