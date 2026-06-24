// WhatsApp chat parser for field-visit case extraction.
// Workflow: parse export .txt -> filter by sender + date range -> extract case fields.

export type ParsedMessage = {
  date: Date
  rawDate: string
  time: string
  sender: string
  body: string
}

export type CaseRow = {
  id: string
  srNo: number
  date: string // DD/MM/YYYY
  bankName: string
  applicantName: string
  reasonForCnv: string
  status: string
  latlongFrom: string
  latlongTo: string
  area: string
  km: string
  rawBody: string
}

// Matches the leading "DD/MM/YY(YY), HH:MM(:SS)( am/pm) - Sender: " portion of a line.
// Supports both 12h and 24h, optional seconds, and the unicode narrow space some exports use.
const LINE_HEADER_RE =
  /^(\d{1,2})\/(\d{1,2})\/(\d{2,4}),?\s+(\d{1,2}:\d{2}(?::\d{2})?)\s*([apAP][. ]?[mM][.]?)?\s*[-–—]\s*([^:]+?):\s?([\s\S]*)$/

export function parseDDMMYYYY(d: string): Date | null {
  const m = d.trim().match(/^(\d{1,2})\/(\d{1,2})\/(\d{2,4})$/)
  if (!m) return null
  let [, dd, mm, yyyy] = m
  let year = Number.parseInt(yyyy, 10)
  if (year < 100) year += 2000
  const day = Number.parseInt(dd, 10)
  const month = Number.parseInt(mm, 10)
  if (month < 1 || month > 12 || day < 1 || day > 31) return null
  const date = new Date(year, month - 1, day)
  return Number.isNaN(date.getTime()) ? null : date
}

function buildDate(dd: string, mm: string, yy: string): Date {
  let year = Number.parseInt(yy, 10)
  if (year < 100) year += 2000
  return new Date(year, Number.parseInt(mm, 10) - 1, Number.parseInt(dd, 10))
}

function formatDDMMYYYY(d: Date): string {
  const dd = String(d.getDate()).padStart(2, '0')
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const yyyy = d.getFullYear()
  return `${dd}/${mm}/${yyyy}`
}

export function parseMessages(raw: string): ParsedMessage[] {
  // Normalize line endings; some exports use \r\n. Strip the LRM/invisible chars.
  const lines = raw.replace(/\r\n/g, '\n').replace(/[\u200e\u200f]/g, '').split('\n')
  const messages: ParsedMessage[] = []

  for (const line of lines) {
    const header = line.match(LINE_HEADER_RE)
    if (header) {
      const [, dd, mm, yy, time, , sender, body] = header
      messages.push({
        date: buildDate(dd, mm, yy),
        rawDate: `${dd}/${mm}/${yy}`,
        time,
        sender: sender.trim(),
        body,
      })
    } else if (messages.length > 0) {
      // Continuation of the previous multi-line message.
      messages[messages.length - 1].body += '\n' + line
    }
  }

  return messages
}

function extractReasonAndBank(body: string): { reason: string; bank: string } | null {
  // Find the first line that contains "( ... )".
  const bracketMatch = body.match(/^([^\n(]*?)\(([^)]+)\)/m)
  if (!bracketMatch) return null
  const reason = bracketMatch[1].replace(/[:\-–—\s]+$/, '').trim()
  const bank = bracketMatch[2].trim()
  if (!bank) return null
  return { reason, bank }
}

const APPLICANT_RE = /applic\w*(?:\s*name)?\s*[:\-–—=]+\s*(.+)/i

function extractApplicant(body: string): string | null {
  for (const line of body.split('\n')) {
    // Strip a leading list marker like "1)" / "1." / "-" before matching.
    const cleaned = line.replace(/^\s*\d+\s*[).]?\s*/, '').trim()
    const m = cleaned.match(APPLICANT_RE)
    if (m) {
      const val = m[1].trim()
      if (val) return titleCase(val)
    }
  }
  return null
}

function titleCase(s: string): string {
  return s
    .toLowerCase()
    .split(/\s+/)
    .map((w) => (w ? w[0].toUpperCase() + w.slice(1) : w))
    .join(' ')
    .trim()
}

function extractLocation(body: string): { area: string; latlongTo: string } {
  for (const line of body.split('\n')) {
    const trimmed = line.trim()
    if (!trimmed.startsWith('#')) continue
    // Drop the leading "#" and strip WhatsApp annotations like "<This message was edited>".
    const content = trimmed
      .slice(1)
      .replace(/<[^>]*>/g, '')
      .trim()
    if (!content) continue
    // Pull the "lat,long" from anywhere in the line; whatever remains is the area name.
    const latlongMatch = content.match(/(-?\d+(?:\.\d+)?\s*,\s*-?\d+(?:\.\d+)?)/)
    let area = content
    let latlongTo = ''
    if (latlongMatch) {
      latlongTo = latlongMatch[1].replace(/\s+/g, '')
      area = (
        content.slice(0, latlongMatch.index) +
        content.slice(latlongMatch.index! + latlongMatch[0].length)
      ).trim()
    }
    return { area: area.trim(), latlongTo }
  }
  return { area: '', latlongTo: '' }
}

export function extractCase(body: string): {
  reason: string;
  bank: string;
  applicant: string;
} | null {
  const rb = extractReasonAndBank(body)
  if (!rb) return null
  const applicant = extractApplicant(body)
  if (!applicant) return null
  return { reason: rb.reason, bank: rb.bank, applicant }
}

export type ProcessOptions = {
  raw: string
  senderName: string
  from: Date
  to: Date
}

export type ProcessResult = {
  rows: CaseRow[]
  stats: {
    totalMessages: number
    fromSender: number
    inRange: number
    validCases: number
  }
}

function sameSender(a: string, b: string): boolean {
  return a.trim().toLowerCase().includes(b.trim().toLowerCase())
}

function inRange(d: Date, from: Date, to: Date): boolean {
  const day = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
  const f = new Date(from.getFullYear(), from.getMonth(), from.getDate()).getTime()
  const t = new Date(to.getFullYear(), to.getMonth(), to.getDate()).getTime()
  return day >= f && day <= t
}

export function processChat({ raw, senderName, from, to }: ProcessOptions): ProcessResult {
  const messages = parseMessages(raw)
  let fromSender = 0
  let inRangeCount = 0
  const rows: CaseRow[] = []
  let srNo = 0

  // Filter messages by sender and date range, then sort by date ascending.
  const filtered = messages
    .filter((m) => {
      if (!sameSender(m.sender, senderName)) return false
      fromSender++
      if (!inRange(m.date, from, to)) return false
      inRangeCount++
      return true
    })
    .sort((a, b) => a.date.getTime() - b.date.getTime())

  for (const m of filtered) {
    const c = extractCase(m.body)
    if (!c) continue
    const loc = extractLocation(m.body)
    srNo++
    rows.push({
      id: `${m.date.getTime()}-${srNo}`,
      srNo,
      date: formatDDMMYYYY(m.date),
      bankName: c.bank,
      applicantName: c.applicant,
      reasonForCnv: c.reason,
      status: '',
      latlongFrom: '',
      latlongTo: loc.latlongTo,
      area: loc.area,
      km: '',
      rawBody: m.body.trim(),
    })
  }

  return {
    rows,
    stats: {
      totalMessages: messages.length,
      fromSender,
      inRange: inRangeCount,
      validCases: rows.length,
    },
  }
}
