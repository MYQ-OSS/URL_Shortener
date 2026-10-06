import { cpSync, existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'

const project = path.resolve(import.meta.dirname, '../..')
const source = path.join(project, 'frontend', 'dist')
const staticRoot = path.join(project, 'src', 'main', 'resources', 'static')
const target = path.join(staticRoot, 'app')
if (!existsSync(path.join(source, 'index.html'))) throw new Error('先执行前端构建。')
if (path.dirname(target) !== staticRoot || path.basename(target) !== 'app') throw new Error('静态目录校验失败。')
mkdirSync(target, { recursive: true })
// 仅复制构建产物，不触碰原有 static/assets 与 favicon。
cpSync(source, target, { recursive: true })
console.log(`前端已同步到 ${target}`)
