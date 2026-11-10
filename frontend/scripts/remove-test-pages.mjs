import { rm } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const distDir = path.resolve(__dirname, '..', 'dist')
const testPages = ['button-audit.html']

await Promise.all(testPages.map(name =>
  rm(path.join(distDir, name), { force: true }).catch(() => {})
))
