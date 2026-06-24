import ExcelJS from 'exceljs'
import { jsPDF } from 'jspdf'

// We always render exactly this range from every sheet: A1:I24.
const FIRST_COL = 1
const LAST_COL = 9
const FIRST_ROW = 1
const LAST_ROW = 24

// Fallback column widths (Excel character units) for A..I, used when a sheet
// does not carry an explicit width. Matches the export template.
const DEFAULT_COL_WIDTHS = [6.71, 12.43, 33.86, 5.86, 8.43, 18.43, 19.0, 17.86, 7.57]
const DEFAULT_ROW_HEIGHT = 18 // points

// Excel character-width -> pixels (approx Excel formula).
function colWidthToPx(width: number): number {
  return Math.round(width * 7 + 5)
}
// Excel row height (points) -> pixels.
function rowHeightToPx(pts: number): number {
  return Math.round((pts * 96) / 72)
}

function colLetterToNum(letters: string): number {
  let n = 0
  for (const ch of letters.toUpperCase()) {
    n = n * 26 + (ch.charCodeAt(0) - 64)
  }
  return n
}

type MergeRange = { r1: number; c1: number; r2: number; c2: number }

function parseMerges(ws: ExcelJS.Worksheet): MergeRange[] {
  // exceljs exposes merges on the worksheet model after load.
  const raw =
    (ws as unknown as { model?: { merges?: string[] } }).model?.merges ??
    (ws as unknown as { _merges?: Record<string, { tl: string; br: string }> })._merges
  const out: MergeRange[] = []
  if (Array.isArray(raw)) {
    for (const range of raw) {
      const m = String(range).match(/^([A-Z]+)(\d+):([A-Z]+)(\d+)$/i)
      if (!m) continue
      out.push({
        c1: colLetterToNum(m[1]),
        r1: Number(m[2]),
        c2: colLetterToNum(m[3]),
        r2: Number(m[4]),
      })
    }
  } else if (raw && typeof raw === 'object') {
    for (const key of Object.keys(raw)) {
      const m = key.match(/^([A-Z]+)(\d+):([A-Z]+)(\d+)$/i)
      if (!m) continue
      out.push({
        c1: colLetterToNum(m[1]),
        r1: Number(m[2]),
        c2: colLetterToNum(m[3]),
        r2: Number(m[4]),
      })
    }
  }
  return out
}

// Resolve a cell's display text, handling formula results, hyperlinks and rich text.
function cellText(cell: ExcelJS.Cell): string {
  const v = cell.value as any
  if (v == null) return ''
  if (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') {
    return String(v)
  }
  if (v instanceof Date) return v.toLocaleDateString()
  if (typeof v === 'object') {
    const o = v as Record<string, unknown>
    if ('result' in o && o.result != null) return String(o.result)
    if ('text' in o && o.text != null) return String(o.text)
    if ('richText' in o && Array.isArray(o.richText)) {
      return (o.richText as Array<{ text?: string }>).map((p) => p.text ?? '').join('')
    }
    if ('hyperlink' in o && o.hyperlink != null) return String(o.hyperlink)
  }
  return ''
}

function argbToCss(argb?: string): string | undefined {
  if (!argb) return undefined
  // exceljs uses AARRGGBB; strip alpha for CSS.
  const hex = argb.length === 8 ? argb.slice(2) : argb
  return `#${hex}`
}

function borderPx(style?: string): number {
  if (!style) return 0
  // Map Excel border styles to pixel widths so thin/medium/thick stay distinct,
  // exactly like the source workbook.
  switch (style) {
    case 'thick':
      return 3
    case 'medium':
    case 'mediumDashed':
    case 'mediumDashDot':
    case 'mediumDashDotDot':
    case 'double':
      return 2
    default:
      // hair, thin, dotted, dashed, etc.
      return 1
  }
}

function wrapLines(
  ctx: CanvasRenderingContext2D,
  text: string,
  maxWidth: number,
  wrap: boolean,
): string[] {
  if (!text) return ['']
  // Honor explicit newlines first.
  const paragraphs = text.split('\n')
  if (!wrap) return paragraphs
  const lines: string[] = []
  for (const para of paragraphs) {
    const words = para.split(/\s+/)
    let line = ''
    for (const word of words) {
      const test = line ? `${line} ${word}` : word
      if (ctx.measureText(test).width > maxWidth && line) {
        lines.push(line)
        line = word
      } else {
        line = test
      }
    }
    lines.push(line)
  }
  return lines
}

// Render exactly A1:I24 of a worksheet directly onto a canvas.
// This avoids html2canvas (which cannot parse the app's lab()/oklch() theme colors) and
// gives a deterministic, pixel-accurate result.
function sheetToCanvas(ws: ExcelJS.Worksheet): HTMLCanvasElement {
  const merges = parseMerges(ws)
  const mergeHead = new Map<string, MergeRange>()
  const skip = new Set<string>()
  for (const mr of merges) {
    mergeHead.set(`${mr.r1},${mr.c1}`, mr)
    for (let r = mr.r1; r <= mr.r2; r++) {
      for (let c = mr.c1; c <= mr.c2; c++) {
        if (r === mr.r1 && c === mr.c1) continue
        skip.add(`${r},${c}`)
      }
    }
  }

  // Column left edges (index 1..LAST_COL+1) and row top edges (1..LAST_ROW+1).
  const colX: number[] = [0, 0]
  for (let c = FIRST_COL; c <= LAST_COL; c++) {
    const w = ws.getColumn(c).width ?? DEFAULT_COL_WIDTHS[c - 1]
    colX[c + 1] = colX[c] + colWidthToPx(w)
  }
  const rowY: number[] = [0, 0]
  for (let r = FIRST_ROW; r <= LAST_ROW; r++) {
    const rh = ws.getRow(r).height ?? DEFAULT_ROW_HEIGHT
    rowY[r + 1] = rowY[r] + rowHeightToPx(rh)
  }
  const totalW = colX[LAST_COL + 1]
  const totalH = rowY[LAST_ROW + 1]

  const scale = 2
  const canvas = document.createElement('canvas')
  canvas.width = Math.ceil(totalW * scale)
  canvas.height = Math.ceil(totalH * scale)
  const ctx = canvas.getContext('2d')!
  ctx.scale(scale, scale)
  ctx.textBaseline = 'middle'

  // White background.
  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, totalW, totalH)

  const pad = 3

  for (let r = FIRST_ROW; r <= LAST_ROW; r++) {
    for (let c = FIRST_COL; c <= LAST_COL; c++) {
      const key = `${r},${c}`
      if (skip.has(key)) continue
      const cell = ws.getCell(r, c)
      const head = mergeHead.get(key)
      const c2 = head ? head.c2 : c
      const r2 = head ? head.r2 : r
      const left = colX[c]
      const top = rowY[r]
      const right = colX[c2 + 1]
      const bottom = rowY[r2 + 1]
      const boxW = right - left
      const boxH = bottom - top

      // Fill
      const fill = cell.fill as { type?: string; fgColor?: { argb?: string } } | undefined
      if (fill?.type === 'pattern' && fill.fgColor?.argb) {
        const bg = argbToCss(fill.fgColor.argb)
        if (bg) {
          ctx.fillStyle = bg
          ctx.fillRect(left, top, boxW, boxH)
        }
      }

      // Text
      const text = cellText(cell)
      if (text) {
        const f = cell.font ?? {}
        const size = f.size ? Number(f.size) : 11
        const weight = f.bold ? 'bold ' : ''
        const italic = f.italic ? 'italic ' : ''
        ctx.font = `${italic}${weight}${size}px Calibri, Arial, sans-serif`
        ctx.fillStyle = argbToCss((f.color as { argb?: string } | undefined)?.argb) ?? '#000000'

        const a = cell.alignment ?? {}
        const halign = (a.horizontal as string) || 'left'
        const valign = (a.vertical as string) || 'middle'
        const wrap = !!a.wrapText
        const lines = wrapLines(ctx, text, boxW - pad * 2, wrap)
        const lineH = size * 1.2

        ctx.save()
        ctx.beginPath()
        ctx.rect(left, top, boxW, boxH)
        ctx.clip()

        // Extra horizontal inset (~one space) so text never touches the cell
        // border, matching Excel's default cell padding.
        const textInset = pad + 4
        let tx = left + textInset
        if (halign === 'center') {
          ctx.textAlign = 'center'
          tx = left + boxW / 2
        } else if (halign === 'right') {
          ctx.textAlign = 'right'
          tx = right - textInset
        } else {
          ctx.textAlign = 'left'
        }

        const blockH = lines.length * lineH
        let startY: number
        if (valign === 'top') startY = top + pad + lineH / 2
        else if (valign === 'bottom') startY = bottom - pad - blockH + lineH / 2
        else startY = top + boxH / 2 - blockH / 2 + lineH / 2

        lines.forEach((ln, idx) => {
          ctx.fillText(ln, tx, startY + idx * lineH)
        })
        ctx.restore()
      }

      // Borders (drawn per side using the cell's own border styles).
      // Snap each line to a crisp pixel boundary so the grid renders sharp,
      // exactly like Excel's cell borders.
      const b = cell.border ?? {}
      ctx.strokeStyle = '#000000'
      const drawLine = (x1: number, y1: number, x2: number, y2: number, wpx: number) => {
        if (wpx <= 0) return
        ctx.lineWidth = wpx
        // For a 1px line, offset by half a pixel to avoid anti-alias blur.
        const off = wpx % 2 === 1 ? 0.5 : 0
        ctx.beginPath()
        ctx.moveTo(Math.round(x1) + off, Math.round(y1) + off)
        ctx.lineTo(Math.round(x2) + off, Math.round(y2) + off)
        ctx.stroke()
      }
      drawLine(left, top, right, top, borderPx(b.top?.style))
      drawLine(left, bottom, right, bottom, borderPx(b.bottom?.style))
      drawLine(left, top, left, bottom, borderPx(b.left?.style))
      drawLine(right, top, right, bottom, borderPx(b.right?.style))
    }
  }

  // Thick outer border around the entire A1:I24 range (drawn last so it sits
  // on top of any cell borders).
  const outerW = 3
  ctx.strokeStyle = '#000000'
  ctx.lineWidth = outerW
  const oOff = outerW / 2
  ctx.strokeRect(oOff, oOff, totalW - outerW, totalH - outerW)

  return canvas
}

export type PdfProgress = (done: number, total: number) => void

// Convert an uploaded .xlsx file to a single landscape PDF: one page per sheet,
// each sheet scaled so all rows and columns fit on one page, with the page
// number printed in the top-right corner.
export async function excelToPdf(file: File, onProgress?: PdfProgress): Promise<void> {
  const buf = await file.arrayBuffer()
  const wb = new ExcelJS.Workbook()
  await wb.xlsx.load(buf)

  const sheets = wb.worksheets.filter((ws) => ws && ws.rowCount >= 0)
  if (sheets.length === 0) {
    throw new Error('Excel me koi sheet nahi mila.')
  }

  const pdf = new jsPDF({ orientation: 'landscape', unit: 'mm', format: 'a4' })
  const pageW = pdf.internal.pageSize.getWidth()
  const pageH = pdf.internal.pageSize.getHeight()
  const margin = 18 // larger margin -> more space on left/right and top
  // Let the table fill the available printable area (keeps natural proportions).
  const tableScale = 1.0
  // Push the table down from the top margin so it isn't glued to the edge.
  const topOffset = 8 // mm below the top margin

  for (let i = 0; i < sheets.length; i++) {
    const ws = sheets[i]
    const canvas = sheetToCanvas(ws)
    const imgData = canvas.toDataURL('image/png')

    if (i > 0) pdf.addPage('a4', 'landscape')

    // Fit the sheet inside the printable area WITHOUT distorting it: scale by
    // whichever dimension is the limiting one so the table keeps its natural
    // proportions (no vertical stretching). The wide table is normally limited
    // by width, leaving equal left/right margins; anchor it at the top margin
    // so the layout matches the original sheet.
    const availW = pageW - margin * 2
    const availH = pageH - margin * 2 - topOffset
    const ratio = Math.min(availW / canvas.width, availH / canvas.height) * tableScale
    const drawW = canvas.width * ratio
    const drawH = canvas.height * ratio
    const x = (pageW - drawW) / 2
    const y = margin + topOffset
    pdf.addImage(imgData, 'PNG', x, y, drawW, drawH)

    // Page number in the top-right corner, just outside the table.
    pdf.setFontSize(12)
    pdf.setTextColor(0, 0, 0)
    pdf.text(String(i + 1), pageW - margin, margin - 3, { align: 'right', baseline: 'bottom' })

    onProgress?.(i + 1, sheets.length)
  }

  const base = file.name.replace(/\.(xlsx|xls)$/i, '') || 'sheets'
  pdf.save(`${base}.pdf`)
}
