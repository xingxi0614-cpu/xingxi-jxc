import type { RouteLocationNormalizedLoaded } from 'vue-router'

export function applyRouteQuery(query: Record<string, any>, route: RouteLocationNormalizedLoaded, keys: string[]) {
  let changed = false
  for (const key of keys) {
    const value = route.query[key]
    if (Array.isArray(value)) {
      query[key] = value[0] ?? ''
      changed = true
    } else if (value !== undefined && value !== null) {
      query[key] = value
      changed = true
    }
  }
  if (changed) query.current = 1
  return changed || route.query.auto === '1'
}
