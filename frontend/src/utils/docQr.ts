import QRCode from 'qrcode'
import type { PrintQr } from '@/utils/printExport'

export type DocQrType =
  | 'PO'
  | 'RECV'
  | 'INV'
  | 'OREQ'
  | 'OREQ_REV'
  | 'OUT'
  | 'RETURN'
  | 'LOAN'
  | 'TRANSFER'

export const DOC_TYPE_LABELS: Record<DocQrType, string> = {
  PO: '采购订单',
  RECV: '采购入库',
  INV: '采购发票',
  OREQ: '出库申请',
  OREQ_REV: '出库申请修订',
  OUT: '材料出库',
  RETURN: '退料单',
  LOAN: '借料单',
  TRANSFER: '库存转库'
}

const DEFAULT_PUBLIC_BASE_URL = typeof window !== 'undefined' ? window.location.origin : ''

function publicBaseUrl() {
  const configured = (import.meta.env.VITE_PUBLIC_BASE_URL || '').trim()
  return (configured || DEFAULT_PUBLIC_BASE_URL).replace(/\/+$/, '')
}

export function normalizeDocQrType(type: string): DocQrType | null {
  const value = String(type || '').trim().toUpperCase()
  return Object.prototype.hasOwnProperty.call(DOC_TYPE_LABELS, value) ? value as DocQrType : null
}

export function buildMobileDocPath(type: DocQrType, id: string | number) {
  return `/mobile/doc/${encodeURIComponent(type)}/${encodeURIComponent(String(id))}`
}

export function buildMobileDocUrl(type: DocQrType, id: string | number) {
  return `${publicBaseUrl()}/#${buildMobileDocPath(type, id)}`
}

export async function buildQrDataUrl(content: string) {
  return QRCode.toDataURL(content, {
    errorCorrectionLevel: 'M',
    margin: 1,
    width: 192,
    color: {
      dark: '#111827',
      light: '#ffffff'
    }
  })
}

export async function buildPrintQr(type: DocQrType, id: string | number, docNo?: string): Promise<PrintQr> {
  const url = buildMobileDocUrl(type, id)
  return {
    label: DOC_TYPE_LABELS[type],
    docNo: docNo || String(id),
    url,
    dataUrl: await buildQrDataUrl(url)
  }
}
