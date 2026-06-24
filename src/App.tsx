import { useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from './components/ui/card'
import { Input } from './components/ui/input'
import { Label } from './components/ui/label'
import { Button } from './components/ui/button'
import { ResultsTable } from './components/results-table'
import { GpsCoordinate } from './components/gps-coordinate'
import { ExcelToPdf } from './components/excel-to-pdf'
import { processChat, type CaseRow, type ProcessResult } from './lib/chat-parser'
import { exportToExcel, rowsToClipboard, copyText } from './lib/excel-export'
import { Upload, FileText, MessageSquareText, AlertCircle, Settings2, ChevronDown } from 'lucide-react'

// WhatsApp chat -> Excel data extractor
const DEFAULT_NAME = 'Chauhan'

function fromInputDate(s: string): Date | null {
  if (!s) return null
  const [y, m, d] = s.split('-').map(Number)
  if (!y || !m || !d) return null
  return new Date(y, m - 1, d)
}

export default function App() {
  const [name, setName] = useState(DEFAULT_NAME)
  const [execName, setExecName] = useState('')
  const [chatText, setChatText] = useState('')
  const [fileName, setFileName] = useState('')
  const [fromStr, setFromStr] = useState('')
  const [toStr, setToStr] = useState('')
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [rows, setRows] = useState<CaseRow[]>([])
  const [stats, setStats] = useState<ProcessResult['stats'] | null>(null)
  const [error, setError] = useState('')
  const [copied, setCopied] = useState(false)
  const [processed, setProcessed] = useState(false)

  const handleFile = async (file: File) => {
    const text = await file.text()
    setChatText(text)
    setFileName(file.name)
    setError('')
  }

  const handleProcess = () => {
    setError('')
    setCopied(false)

    if (!chatText.trim()) {
      setError('Pehle WhatsApp chat ki .txt file upload karo ya text paste karo.')
      return
    }
    if (!name.trim()) {
      setError('Apna WhatsApp naam daalo (jaise export me dikhta hai).')
      return
    }

    // Resolve date range from the pickers.
    const from = fromInputDate(fromStr)
    const to = fromInputDate(toStr)

    if (!from || !to) {
      setError('Date range chuno (From aur To).')
      return
    }
    if (from.getTime() > to.getTime()) {
      setError('"From" date "To" date se badi hai. Sahi karo.')
      return
    }

    const result = processChat({ raw: chatText, senderName: name, from, to })
    setRows(result.rows)
    setStats(result.stats)
    setProcessed(true)
  }

  const updateRow = (id: string, field: any, value: string) => {
    setRows((prev) =>
      prev.map((r) => (r.id === id ? { ...r, [field]: value } : r))
    )
  }

  const deleteRow = (id: string) => {
    setRows((prev) =>
      prev.filter((r) => r.id !== id).map((r, i) => ({ ...r, srNo: i + 1 }))
    )
  }

  const handleExport = async () => {
    const stamp = `${fromStr}_${toStr}`
    try {
      await exportToExcel(rows, `data_${stamp.replace(/[^\dA-Za-z]/g, '-')}.xlsx`, {
        executiveName: execName.trim(),
      })
    } catch {
      setError('Excel banane me dikkat aayi. Dobara try karo.')
    }
  }

  const handleCopy = async () => {
    const ok = await copyText(rowsToClipboard(rows))
    if (ok) {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } else {
      setError('Copy nahi ho paya. Excel download use karo ya rows manually select karo.')
    }
  }

  return (
    <main className="min-h-screen bg-background pb-16">
      {/* Always-visible location bar */}
      <GpsCoordinate />

      <div className="mx-auto max-w-6xl px-4">
        {/* Brand header */}
        <header className="flex flex-col gap-3 py-8 sm:flex-row sm:items-center sm:justify-between md:py-10">
          <div className="flex items-center gap-3">
            <div className="flex size-12 items-center justify-center rounded-2xl bg-primary text-primary-foreground shadow-sm">
              <MessageSquareText className="size-6" />
            </div>
            <div>
              <span className="block text-[11px] font-semibold uppercase tracking-[0.18em] text-primary">
                Field Visit Toolkit
              </span>
              <h1 className="text-balance text-2xl font-bold tracking-tight md:text-3xl">
                WhatsApp to Excel
              </h1>
            </div>
          </div>
          <p className="max-w-xs text-pretty text-sm leading-relaxed text-muted-foreground text-right sm:text-left">
            Chat se bank, applicant aur reason nikaal kar saaf-suthri Excel aur PDF banao.
          </p>
        </header>

        {/* Dashboard: workflow left, output right */}
        <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.25fr)] lg:items-start">
          {/* LEFT: input workflow */}
          <div className="grid gap-6 lg:sticky lg:top-24">
            {/* Step 1: Settings (collapsible dropdown) */}
            <Card className="overflow-hidden border-border/70 shadow-sm transition-shadow hover:shadow-md">
              <button
                type="button"
                onClick={() => setSettingsOpen((o) => !o)}
                aria-expanded={settingsOpen}
                className="flex w-full items-center justify-between gap-2 px-6 py-4 text-left transition-colors hover:bg-muted/40 cursor-pointer"
              >
                <span className="flex items-center gap-2.5 text-base font-semibold">
                  <span className="flex size-8 items-center justify-center rounded-lg bg-muted text-muted-foreground">
                    <Settings2 className="size-4" />
                  </span>
                  Naam settings
                  <span className="ml-1 truncate text-xs font-normal text-muted-foreground">
                    ({name || 'koi naam nahi'})
                  </span>
                </span>
                <ChevronDown
                  className={`size-4 shrink-0 text-muted-foreground transition-transform ${
                    settingsOpen ? 'rotate-180' : ''
                  }`}
                />
              </button>
              {settingsOpen && (
                <CardContent className="border-t pt-4">
                  <div className="grid gap-4 sm:grid-cols-2">
                    <div className="space-y-2">
                      <Label htmlFor="name">WhatsApp naam (sender filter)</Label>
                      <Input
                        id="name"
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        placeholder="Chauhan"
                      />
                      <p className="text-[11px] text-muted-foreground leading-snug">
                        App sirf is naam ke bheje messages padhega, baaki ignore karega.
                      </p>
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="exec">Field Executive Name (Excel header)</Label>
                      <Input
                        id="exec"
                        value={execName}
                        onChange={(e) => setExecName(e.target.value)}
                        placeholder="Jaise Excel sheet me likhna ho"
                      />
                      <p className="text-[11px] text-muted-foreground leading-snug">
                        Ye naam har din ki sheet ke upar "FIELD EXECUTIVE NAME" me chhapega.
                      </p>
                    </div>
                  </div>
                </CardContent>
              )}
            </Card>

            {/* Step 2: Chat input */}
            <Card className="border-border/70 shadow-sm transition-shadow hover:shadow-md">
              <CardHeader>
                <CardTitle className="flex items-center gap-2.5 text-base">
                  <span className="flex size-7 items-center justify-center rounded-full bg-primary text-xs font-bold text-primary-foreground shadow-sm">
                    1
                  </span>
                  Chat file daalo
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                <label className="flex cursor-pointer items-center justify-center gap-2 rounded-xl border border-dashed border-border bg-muted/30 px-4 py-5 text-center transition-colors hover:border-primary/40 hover:bg-accent/40">
                  <Upload className="size-4 shrink-0 text-primary" />
                  <span className="truncate text-sm font-medium">
                    {fileName ? fileName : 'WhatsApp export (.txt) upload karo'}
                  </span>
                  <input
                    type="file"
                    accept=".txt,text/plain"
                    className="hidden"
                    onChange={(e) => {
                      const f = e.target.files?.[0]
                      if (f) handleFile(f)
                    }}
                  />
                </label>

                <div className="space-y-2">
                  <Label htmlFor="chat" className="flex items-center gap-1.5">
                    <FileText className="size-3.5" />
                    Chat text
                  </Label>
                  <textarea
                    id="chat"
                    value={chatText}
                    onChange={(e) => {
                      setChatText(e.target.value)
                      setFileName('')
                    }}
                    placeholder={
                      '01/01/2024, 10:35 - Chauhan: B.V (Aadhar fin.)\n1)Applicat name :- Parasantbhai Haribhai savani\n...'
                    }
                    rows={4}
                    className="w-full rounded-lg border bg-background px-3 py-2 font-mono text-xs leading-relaxed outline-none focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/30"
                  />
                </div>
              </CardContent>
            </Card>

            {/* Step 3: Date range */}
            <Card className="border-border/70 shadow-sm transition-shadow hover:shadow-md">
              <CardHeader>
                <CardTitle className="flex items-center gap-2.5 text-base">
                  <span className="flex size-7 items-center justify-center rounded-full bg-primary text-xs font-bold text-primary-foreground shadow-sm">
                    2
                  </span>
                  Date range chuno
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-2">
                    <Label htmlFor="from">From</Label>
                    <Input
                      id="from"
                      type="date"
                      value={fromStr}
                      onChange={(e) => setFromStr(e.target.value)}
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="to">To</Label>
                    <Input id="to" type="date" value={toStr} onChange={(e) => setToStr(e.target.value)} />
                  </div>
                </div>
              </CardContent>
            </Card>

            {error && (
              <div className="flex items-start gap-2 rounded-xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
                <AlertCircle className="mt-0.5 size-4 shrink-0" />
                <span>{error}</span>
              </div>
            )}

            <Button
              size="lg"
              onClick={handleProcess}
              className="w-full shadow-sm transition-transform hover:-translate-y-0.5 cursor-pointer"
            >
              Data process karo
            </Button>
          </div>

          {/* RIGHT: tools + output */}
          <div className="grid gap-6">
            <ExcelToPdf />

            {/* Results */}
            {processed ? (
              <Card className="border-border/70 shadow-sm">
                <CardHeader>
                  <CardTitle className="text-base">Result</CardTitle>
                  {stats && (
                    <p className="text-xs text-muted-foreground">
                      {stats.totalMessages} messages padhe • {stats.fromSender} tumhare •{' '}
                      {stats.inRange} date range me • {stats.validCases} valid case
                    </p>
                  )}
                </CardHeader>
                <CardContent className="pt-0">
                  {rows.length > 0 ? (
                    <ResultsTable
                      rows={rows}
                      onChange={updateRow}
                      onDelete={deleteRow}
                      onExport={handleExport}
                      onCopy={handleCopy}
                      copied={copied}
                    />
                  ) : (
                    <div className="rounded-lg border border-dashed px-4 py-10 text-center text-sm text-muted-foreground">
                      Is date range me koi valid case nahi mila. Date ya naam check karo.
                    </div>
                  )}
                </CardContent>
              </Card>
            ) : (
              <Card className="flex min-h-64 flex-col items-center justify-center gap-3 border-dashed border-border/70 px-6 py-12 text-center">
                <span className="flex size-14 items-center justify-center rounded-2xl bg-accent text-primary">
                  <FileText className="size-7" />
                </span>
                <p className="text-sm font-medium">Result yahan dikhega</p>
                <p className="max-w-xs text-pretty text-xs leading-relaxed text-muted-foreground">
                  Left side chat daalo, date range chuno aur "Data process karo" dabao — extract hua
                  data yahan editable table me aayega.
                </p>
              </Card>
            )}
          </div>
        </div>
      </div>
    </main>
  )
}
