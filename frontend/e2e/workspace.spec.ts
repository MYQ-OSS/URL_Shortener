import { expect, test } from '@playwright/test'

test('桌面工作台：筛选、停用恢复、创建与二维码分享', async ({ page, context }, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('./')
  await expect(page.getByRole('heading', { name: '我的链接' })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('desktop.png'), fullPage: true })
  await page.screenshot({ path: testInfo.outputPath('desktop-viewport.png') })
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.getByRole('button', { name: '复制 十月产品更新 的短链接', exact: true }).click()
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toContain('/k7M2pQ')
  await page.getByRole('textbox', { name: '搜索链接' }).fill('团队设计')
  await expect(page.locator('tbody tr')).toHaveCount(1)
  await page.getByRole('textbox', { name: '搜索链接' }).clear()
  await page.getByRole('button', { name: '停用 十月产品更新', exact: true }).click()
  await page.getByRole('button', { name: '确认停用', exact: true }).click()
  await expect(page.getByRole('button', { name: '恢复 十月产品更新', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '恢复 十月产品更新', exact: true }).click()
  await page.getByRole('textbox', { name: '目标网址' }).fill('https://example.com/front-end-test')
  await page.getByPlaceholder('例如：十月活动报名').fill('前端验收')
  await page.getByRole('button', { name: '生成短链接', exact: true }).click()
  await page.getByRole('button', { name: '前端验收', exact: true }).click()
  await page.getByRole('button', { name: '分享二维码', exact: true }).click()
  await expect(page.getByRole('img', { name: /短链接.*的二维码/ })).toHaveAttribute('src', /^data:image\/png/)
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).not.toBeVisible()
  expect(errors).toEqual([])
})

test('手机布局：无横向溢出，抽屉导航和创建对话框可用', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto('./')
  await expect(page.getByRole('heading', { name: '我的链接' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375)
  await page.screenshot({ path: testInfo.outputPath('mobile.png'), fullPage: true })
  await page.getByRole('button', { name: '新建短链', exact: true }).click()
  await page.getByRole('textbox', { name: '目标网址' }).fill('https://example.com/mobile')
  await page.getByPlaceholder('例如：十月活动报名').fill('移动端验收')
  await page.getByRole('button', { name: '生成短链接', exact: true }).click()
  await expect(page.getByText('链接已就绪', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '关闭对话框' }).click()
  await page.getByRole('button', { name: '展开导航' }).click()
  await page.getByRole('link', { name: /运行观察/ }).click()
  await expect(page.getByRole('heading', { name: '运行观察' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375)
})

test('真实连接契约：Bearer、管理员头和主版本运行状态', async ({ page }, testInfo) => {
  const record = [{ id: '2065100100012345678', shortUri: 'Ab12Cd', domain: 'localhost:8080', originUrl: 'https://example.com', gid: 'default', describe: '验收入口', enableStatus: 0, validType: 0, validEnd: null, clickNum: 0, createTime: '2026-10-01T10:00:00' }]
  await page.route('**/api/link/list?**', route => {
    expect(route.request().headers().authorization).toBe('Bearer test-api-token')
    return route.fulfill({ json: record })
  })
  await page.route('**/admin/stats', route => {
    expect(route.request().headers()['x-admin-token']).toBe('test-admin-token')
    return route.fulfill({ json: {
    localCache: { size: 10, hitCount: 90, missCount: 10, hitRate: '0.9', evictionCount: 0 },
    stats: { pending: 0, bufferCapacity: 4096, dropped: 0, publishFailures: 0, flushedRows: 10, lastFlushAt: Date.now(), deliveryMode: "best-effort" },
  } })
  })
  await page.goto('./#/settings')
  await page.getByLabel('API Token', { exact: true }).fill('test-api-token')
  await page.getByRole('checkbox', { name: /同时连接运行观察/ }).check()
  await page.getByLabel('管理员 Token', { exact: true }).fill('test-admin-token')
  await page.getByRole('button', { name: '连接并载入', exact: true }).click()
  await expect(page.getByRole('button', { name: '验收入口', exact: true })).toBeVisible()
  await expect(page.locator('.demo-strip')).toHaveCount(0)
  await page.getByRole('link', { name: /运行观察/ }).click()
  await expect(page.getByText('投递失败次数', { exact: true })).toBeVisible()
  await expect(page.getByText(/不用于计费或审计/)).toBeVisible()
  await expect(page.getByRole('button', { name: /重建布隆/ })).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('operations.png'), fullPage: true })
  expect(await page.evaluate(() => Object.keys(localStorage))).toEqual([])
  await page.reload()
  await expect(page.locator('.demo-strip')).toBeVisible()
})

test('四个断点均不产生横向滚动', async ({ page }) => {
  for (const width of [375, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('./#/settings')
    await expect(page.getByRole('heading', { name: '连接设置', exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
  }
})

test('主版本运行维护在演示空间完整闭环', async ({ page }) => {
  await page.goto('./#/operations')
  await page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await expect(page.getByText(/不用于计费或审计/)).toBeVisible()
  await page.getByRole('button', { name: /清理本机缓存/ }).click()
  await page.getByRole('button', { name: '确认执行', exact: true }).click()
  await expect(page.locator('.runtime-facts > div').filter({ hasText: '本地缓存条目' }).locator('dd')).toHaveText(/^0\s*条$/)
})

test('无效 Token 保留连接错误，不假装连接成功', async ({ page }) => {
  await page.route('**/api/link/list?**', route => route.fulfill({ status: 401, contentType: 'application/json', body: '{"error":"API Token 未通过验证"}' }))
  await page.goto('./#/settings')
  await page.getByLabel('API Token', { exact: true }).fill('wrong-token')
  await page.getByRole('button', { name: '连接并载入', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('API Token 未通过验证')
  await expect(page.locator('.demo-strip')).toBeVisible()
  await expect(page.getByRole('heading', { name: '连接设置', exact: true })).toBeVisible()
})
