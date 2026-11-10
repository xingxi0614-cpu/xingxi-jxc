/** 数据包大小显示:1 MB 以下按 KB,其余按 MB 保留一位。 */
export function fmtSize(bytes?: number): string {
  if (!bytes) return '—'
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
