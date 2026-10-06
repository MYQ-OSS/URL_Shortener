import { describe, expect, it } from 'vitest'
import { backendTime, linkStatus, safeHttpUrl, shortUrl } from '../src/lib/format'
import { demoLinks } from '../src/lib/demo'

describe('短链显示规则', () => {
  it('北京时间不受客户端时区影响，到截止时间即显示过期', () => {
    const row = { ...demoLinks()[0], validType: 1, validEnd: '2026-10-01T10:00:00' }
    expect(backendTime(row.validEnd)).toBe(Date.parse('2026-10-01T02:00:00Z'))
    expect(linkStatus(row, backendTime(row.validEnd) - 1)).toBe('active')
    expect(linkStatus(row, backendTime(row.validEnd))).toBe('expired')
  })
  it('封禁及过期条目不能伪装成可切换的正常链接', () => {
    expect(linkStatus({ ...demoLinks()[0], enableStatus: 2 })).toBe('blocked')
    expect(linkStatus({ ...demoLinks()[0], validType: 1, validEnd: null })).toBe('expired')
  })
  it('拒绝危险 URL 协议并保留已有 HTTPS 域名', () => {
    expect(safeHttpUrl('javascript:alert(1)')).toBeNull()
    expect(shortUrl({ domain: 'https://s.example', shortUri: 'Ab12Cd' })).toBe('https://s.example/Ab12Cd')
  })
})
