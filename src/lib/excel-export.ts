import ExcelJS from 'exceljs'
import type { CaseRow } from './chat-parser'

// Column headers exactly matching the user's printed Excel template.
const HEADERS = [
  'SR NO.',
  'BANK NAME',
  'APPLICAT NAME',
  'STATUS',
  'REASON FOR CNV',
  'LATLONG FROM.',
  'LATLONG TO.',
  'AREA',
  'KM',
]

// Exact column widths (Excel character units) provided by the user, A..I.
const COL_WIDTHS = [6.71, 12.43, 33.86, 5.86, 8.43, 18.43, 19.0, 17.86, 7.57]

const DATA_ROWS = 15 // rows 3..17 in the template (row 18 is the spacer/gap)
const KM_RATE = 2
const VISIT_RATE = 25

// Fixed "Home" starting point. F3 always holds this; the route chain starts
// and ends here (Home -> Visit1 -> Visit2 -> ... -> Home).
const HOME_LATLONG = '22.1589,71.6827'

export type ExportMeta = {
  executiveName?: string
}

// Group rows by their DD/MM/YYYY date string, preserving date order.
function groupByDate(rows: CaseRow[]): Map<string, CaseRow[]> {
  const map = new Map<string, CaseRow[]>()
  for (const r of rows) {
    if (!map.has(r.date)) map.set(r.date, [])
    map.get(r.date)!.push(r)
  }
  return map
}

// "DD/MM/YYYY" -> "DD-MM-YYYY" (sheet names can't contain "/").
function dateToSheetName(date: string): string {
  return (date || 'Sheet1').replace(/[\/\\?*[\]:]/g, '-').slice(0, 31)
}

const MONTH_NAMES = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
]

// Derive a "Month-Year" name (e.g. "May-2026") from the first dated row.
// Dates arrive as "DD/MM/YYYY"; falls back to null if none can be parsed.
function monthYearFromRows(rows: CaseRow[]): string | null {
  for (const r of rows) {
    const m = String(r.date || '').match(/^(\d{1,2})\/(\d{1,2})\/(\d{2,4})$/)
    if (m) {
      const monthIdx = Number.parseInt(m[2], 10) - 1
      const year = m[3].length === 2 ? `20${m[3]}` : m[3]
      if (monthIdx >= 0 && monthIdx < 12) {
        return `${MONTH_NAMES[monthIdx]}-${year}`
      }
    }
  }
  return null
}

function numericKm(km: string): number {
  const n = Number.parseFloat(String(km).replace(/[^\d.]/g, ''))
  return Number.isFinite(n) ? n : 0
}

const THIN = { style: 'thin' as const, color: { argb: 'FF000000' } }
const THICK = { style: 'medium' as const, color: { argb: 'FF000000' } }
const ALL_BORDERS = { top: THIN, left: THIN, bottom: THIN, right: THIN }

// Apply a thin black border to every cell within a rectangular range.
function borderRange(ws: ExcelJS.Worksheet, r1: number, c1: number, r2: number, c2: number) {
  for (let r = r1; r <= r2; r++) {
    for (let c = c1; c <= c2; c++) {
      ws.getCell(r, c).border = ALL_BORDERS
    }
  }
}

// Draw a thick (medium) black border around the outside edge of a rectangular
// range, while preserving any thin inner borders already applied to the cells.
function thickOutline(ws: ExcelJS.Worksheet, r1: number, c1: number, r2: number, c2: number) {
  for (let r = r1; r <= r2; r++) {
    for (let c = c1; c <= c2; c++) {
      const cell = ws.getCell(r, c)
      const b = { ...(cell.border ?? {}) } as any
      if (r === r1) b.top = THICK
      if (r === r2) b.bottom = THICK
      if (c === c1) b.left = THICK
      if (c === c2) b.right = THICK
      cell.border = b
    }
  }
}

// Build one worksheet for a single day, matching the template exactly.
function buildDaySheet(ws: ExcelJS.Worksheet, date: string, dayRows: CaseRow[], meta: ExportMeta) {
  // ----- Row 1: Field Executive Name (A1:F1) + Date (G1:I1) -----
  ws.mergeCells('A1:F1')
  ws.mergeCells('G1:I1')
  const a1 = ws.getCell('A1')
  a1.value = `FIELD EXECUTIVE NAME :- ${meta.executiveName ?? ''}`.trim()
  a1.font = { bold: true, size: 16 }
  a1.alignment = { vertical: 'middle', horizontal: 'left' }
  const g1 = ws.getCell('G1')
  g1.value = `DATE :- ${date}`
  g1.font = { bold: true, size: 16 }
  g1.alignment = { vertical: 'middle', horizontal: 'left' }
  ws.getRow(1).height = 26.25

  // ----- Row 2: column headers -----
  const headerRow = ws.getRow(2)
  HEADERS.forEach((h, i) => {
    const cell = headerRow.getCell(i + 1)
    cell.value = h
    cell.font = { bold: true, size: 11 }
    cell.alignment = { vertical: 'middle', horizontal: 'center', wrapText: true }
  })
  headerRow.height = 24.0

  // ----- Rows 3..18: data rows -----
  let totalKm = 0
  const visitCount = dayRows.length
  // Track the longest text per column (A..I) so we can auto-fit widths below.
  const maxLen = HEADERS.map((h) => h.length)
  for (let i = 0; i < DATA_ROWS; i++) {
    const rowNum = 3 + i
    const row = ws.getRow(rowNum)
    const r = dayRows[i]
    if (r) {
      totalKm += numericKm(r.km)
      row.getCell(1).value = i + 1
      row.getCell(2).value = r.bankName
      row.getCell(3).value = r.applicantName
      row.getCell(4).value = r.status
      row.getCell(5).value = r.reasonForCnv
      // Route chain: LATLONG FROM (col F) = previous visit's destination,
      // or Home for the very first visit. LATLONG TO (col G) = this visit.
      row.getCell(6).value = i === 0 ? HOME_LATLONG : dayRows[i - 1].latlongTo
      row.getCell(7).value = r.latlongTo
      row.getCell(8).value = r.area
      row.getCell(9).value = r.km
    } else if (i === visitCount && visitCount > 0) {
      // Extra return row after the last visit: last visit -> Home.
      const last = dayRows[visitCount - 1]
      row.getCell(1).value = i + 1
      row.getCell(6).value = last.latlongTo
      row.getCell(7).value = HOME_LATLONG
      row.getCell(8).value = last.area ? `${last.area} - Home` : 'Home'
    } else {
      row.getCell(1).value = i + 1
    }
    // Center-align every data cell (middle vertical, center horizontal).
    for (let c = 1; c <= 9; c++) {
      const cell = row.getCell(c)
      cell.alignment = { vertical: 'middle', horizontal: 'center' }
      cell.font = { size: 14 }
      const text = cell.value == null ? '' : String(cell.value)
      if (text.length > maxLen[c - 1]) maxLen[c - 1] = text.length
    }
    // Heights: rows 3-12 = 20.25, rows 13-17 = 15.0
    if (rowNum <= 12) row.height = 20.25
    else row.height = 15.0
  }

  // Auto-fit: keep the template width as the minimum, but widen a column when
  // its longest cell text would otherwise be clipped.
  COL_WIDTHS.forEach((base, i) => {
    const fitted = maxLen[i] + 2 // small padding for readability
    ws.getColumn(i + 1).width = Math.max(base, fitted)
  })

  // Thin gridlines for the whole main grid A1:I17 (header rows + 15 data rows).
  borderRange(ws, 1, 1, 17, 9)

  // ----- Row 18: spacer/gap row between main table and summary (height 12.0) -----
  ws.getRow(18).height = 12.0

  // F3 always holds the fixed Home latlong in every sheet (even with no data).
  ws.getCell('F3').value = HOME_LATLONG

  // ----- Column J: Google Maps route link, J3:J16 (always present) -----
  // Formula is unconditional: every row gets it, even if the row is blank.
  for (let rowNum = 3; rowNum <= 16; rowNum++) {
    const cell = ws.getCell(`J${rowNum}`)
    cell.value = {
      formula: `HYPERLINK("https://www.google.com/maps/dir/?api=1&origin="&F${rowNum}&"&destination="&G${rowNum}&"&travelmode=driving","Open Route")`,
    }
    cell.font = { size: 14, color: { argb: 'FF0563C1' }, underline: true }
    cell.alignment = { vertical: 'middle', horizontal: 'left' }
  }
  ws.getColumn(10).width = 13

  // ----- Summary table F19:H24 -----
  // Row 19: headers
  ws.getCell('G19').value = 'NO. OF COUNT'
  ws.getCell('H19').value = 'AMOUNT'
  // Row 20: Total Km
  ws.getCell('F20').value = 'Total KM'
  ws.getCell('G20').value = { formula: 'SUM(I3:I17)&"×2.5"' }
  ws.getCell('H20').value = { formula: 'ROUNDUP(SUM(I3:I17)*2.5,0)' }
  // Row 21: Lunch
  ws.getCell('F21').value = 'LUNCH'
  ws.getCell('H21').value = { formula: 'IF(SUM(I3:I17)>=110,75,0)' }
  // Row 22: Visit
  ws.getCell('F22').value = 'VISIT'
  ws.getCell('G22').value = { formula: 'COUNTA(B3:B17)&"×25"' }
  ws.getCell('H22').value = { formula: 'COUNTA(B3:B17)*25' }
  // Row 23: Other
  ws.getCell('F23').value = 'OTHER'
  // Row 24: Total Amount (F24:G24 merged)
  ws.mergeCells('F24:G24')
  ws.getCell('F24').value = 'TOTAL AMOUNT'
  ws.getCell('H24').value = { formula: 'SUM(H20:H23)' }

  // Row 19 headers: size 11, bold, centered.
  ;['G19', 'H19'].forEach((addr) => {
    const c = ws.getCell(addr)
    c.font = { bold: true, size: 11 }
    c.alignment = { vertical: 'middle', horizontal: 'center' }
  })
  // F20-F23 labels: size 11, bold, centered.
  ;['F20', 'F21', 'F22', 'F23'].forEach((addr) => {
    const c = ws.getCell(addr)
    c.font = { bold: true, size: 11 }
    c.alignment = { vertical: 'middle', horizontal: 'center' }
  })
  // G & H value cells for rows 20-23: size 18, centered.
  ;['G20', 'H20', 'G21', 'H21', 'G22', 'H22', 'G23', 'H23'].forEach((addr) => {
    const c = ws.getCell(addr)
    c.font = { size: 18 }
    c.alignment = { vertical: 'middle', horizontal: 'center' }
  })
  // Row 24: F24 (merged F24:G24) size 11 bold; H24 size 16 bold.
  const f24 = ws.getCell('F24')
  f24.font = { bold: true, size: 11 }
  f24.alignment = { vertical: 'middle', horizontal: 'center' }
  const h24 = ws.getCell('H24')
  h24.font = { bold: true, size: 16 }
  h24.alignment = { vertical: 'middle', horizontal: 'center' }

  // Summary row heights: 19=16.5, 20-22=24.75, 23=25.5, 24=15.0
  ws.getRow(19).height = 16.5
  ws.getRow(20).height = 24.75
  ws.getRow(21).height = 24.75
  ws.getRow(22).height = 24.75
  ws.getRow(23).height = 25.5
  ws.getRow(24).height = 15.0

  // Thin gridlines for the summary table F19:H24.
  borderRange(ws, 19, 6, 24, 8)

  // ----- Thick outer borders -----
  // Main table block (A1:I17) gets a thick outline.
  thickOutline(ws, 1, 1, 17, 9)
  // Summary table block (F19:H24) gets a thick outline.
  thickOutline(ws, 19, 6, 24, 8)
  // Single thick border around the entire block A1:I24.
  thickOutline(ws, 1, 1, 24, 9)
}

// Escape a sheet name for use inside a formula reference: wrap in single
// quotes and double any embedded single quotes (e.g. 'My ''Sheet''!H24).
function sheetRef(name: string): string {
  return `'${name.replace(/'/g, "''")}'`
}

// Add the monthly grand total + recharge block to the LAST sheet only.
// Grand total is a live cross-sheet formula summing every sheet's H24, so it
// always stays correct without re-computing daily totals in JS.
function applyMonthlyTotals(ws: ExcelJS.Worksheet, allSheetNames: string[]) {
  const grandTotalExpr = allSheetNames.map((n) => `${sheetRef(n)}!H24`).join('+')

  // C21: "Total Amount:- <GrandTotal>/-"
  const c21 = ws.getCell('C21')
  c21.value = { formula: `"Total Amount:- "&(${grandTotalExpr})&"/-"` }
  c21.font = { bold: true, size: 18 }
  c21.alignment = { vertical: 'middle', horizontal: 'center' }

  // G23: "Monthly recharge"
  const g23 = ws.getCell('G23')
  g23.value = 'Monthly recharge'
  g23.font = { size: 14 }
  g23.alignment = { vertical: 'middle', horizontal: 'center' }

  // H23: 299
  const h23 = ws.getCell('H23')
  h23.value = 299
  h23.font = { size: 18 }
  h23.alignment = { vertical: 'middle', horizontal: 'center' }
}

// Multi-sheet workbook: one sheet per day, matching the template.
export async function exportToExcel(rows: CaseRow[], fileName: string, meta: ExportMeta = {}) {
  const wb = new ExcelJS.Workbook()
  const groups = groupByDate(rows)

  const sheetNames: string[] = []
  if (groups.size === 0) {
    const name = 'Sheet1'
    buildDaySheet(wb.addWorksheet(name), '', [], meta)
    sheetNames.push(name)
  } else {
    for (const [date, dayRows] of groups) {
      const name = dateToSheetName(date)
      buildDaySheet(wb.addWorksheet(name), date, dayRows, meta)
      sheetNames.push(name)
    }
  }

  // Monthly grand total + recharge: only on the last sheet of the workbook.
  const lastName = sheetNames[sheetNames.length - 1]
  applyMonthlyTotals(wb.getWorksheet(lastName)!, sheetNames)

  const buffer = await wb.xlsx.writeBuffer()
  // Copy the bytes into a fresh, standalone Uint8Array. ExcelJS may hand back a
  // view onto an internal/shared buffer; copying guarantees the Blob owns a
  // stable snapshot so the saved .xlsx stays valid and re-openable any number
  // of times (avoids the "invalid path"/opens-once issue on mobile/WebView).
  const bytes = new Uint8Array(buffer as ArrayBuffer)
  const stable = new Uint8Array(bytes.length)
  stable.set(bytes)
  const blob = new Blob([stable], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  })

  // Name the file "Month-Year" (e.g. May-2026) based on the data's month;
  // fall back to the provided name if no date could be parsed.
  const monthYear = monthYearFromRows(rows)
  const downloadName = monthYear ? `${monthYear}.xlsx` : fileName

  // Prefer the native save dialog when available (best on mobile) so the OS
  // writes a real, persistent file instead of a transient blob reference.
  const navWithSave = navigator as Navigator & {
    msSaveOrOpenBlob?: (b: Blob, name: string) => void
  }
  if (typeof navWithSave.msSaveOrOpenBlob === 'function') {
    navWithSave.msSaveOrOpenBlob(blob, downloadName)
    return
  }

  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = downloadName
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  // Revoke well after the download manager has had time to finish writing the
  // file to disk. Revoking too early can corrupt the saved copy on some
  // mobile browsers, causing it to open only once before failing.
  setTimeout(() => URL.revokeObjectURL(url), 60000)
}

// Tab-separated rows so the user can paste straight into Excel (single block).
export function rowsToClipboard(rows: CaseRow[]): string {
  const header = HEADERS.join('\t')
  const lines = rows.map((r) =>
    [r.srNo, r.bankName, r.applicantName, r.status, r.reasonForCnv, r.latlongFrom, r.latlongTo, r.area, r.km].join('\t')
  )
  return [header, ...lines].join('\n')
}

// Copy text to clipboard with a fallback for sandboxed iframes where the
// async Clipboard API is blocked by permissions policy.
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // fall through to legacy approach
  }

  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.left = '-9999px'
    ta.setAttribute('readonly', '')
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}
