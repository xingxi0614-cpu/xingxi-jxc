import 'vue'

// main.ts 注册的全局属性：数量统一两位小数格式化
declare module 'vue' {
  interface ComponentCustomProperties {
    $fmtQty: (n: number | string | null | undefined) => string
    $qtyFmt: (row: any, col: any, val: any) => string
    $fmtMin: (d: string | Date | null | undefined) => string
    $fmtMinute: (row: any, col: any, val: any) => string
  }
}
