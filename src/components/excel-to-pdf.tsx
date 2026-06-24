import { useRef, useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from './ui/card'
import { Button } from './ui/button'
import { excelToPdf } from '../lib/excel-to-pdf'
import { FileSpreadsheet, FileDown, Loader2, AlertCircle } from 'lucide-react'

export function ExcelToPdf() {
  const inputRef = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState('')
  const [error, setError] = useState('')

  const handleFile = async (file: File) => {
    setError('')
    setBusy(true)
    setProgress('PDF ban rahi hai...')
    try {
      await excelToPdf(file, (done, total) => {
        setProgress(`Page ${done} / ${total} ban raha hai...`)
      })
      setProgress('PDF download ho gayi.')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'PDF banane me dikkat aayi.')
      setProgress('')
    } finally {
      setBusy(false)
      if (inputRef.current) inputRef.current.value = ''
    }
  }

  return (
    <Card className="border-border/70 bg-card shadow-sm transition-shadow hover:shadow-md">
      <CardHeader>
        <CardTitle className="flex items-center gap-2.5 text-base">
          <span className="flex size-8 items-center justify-center rounded-lg bg-accent text-accent-foreground">
            <FileSpreadsheet className="size-4" />
          </span>
          Excel se PDF banao
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-xs leading-relaxed text-muted-foreground">
          Excel upload karo — har sheet ek landscape page par (A1:I24), saare rows aur columns ek
          page me fit, aur page number top-right corner me. Single PDF download hogi.
        </p>
        <input
          ref={inputRef}
          type="file"
          accept=".xlsx,.xls,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          className="hidden"
          onChange={(e) => {
            const f = e.target.files?.[0]
            if (f) handleFile(f)
          }}
        />
        <Button
          type="button"
          onClick={() => inputRef.current?.click()}
          disabled={busy}
          className="w-full shadow-sm transition-transform hover:-translate-y-0.5 sm:w-auto"
        >
          {busy ? (
            <Loader2 className="size-4 animate-spin" />
          ) : (
            <FileDown className="size-4" />
          )}
          {busy ? 'Ban rahi hai...' : 'Excel upload karte PDF banao'}
        </Button>
        {progress && !error && <p className="text-xs text-muted-foreground mt-2">{progress}</p>}
        {error && (
          <p className="flex items-center gap-1.5 text-xs text-destructive mt-2">
            <AlertCircle className="size-3.5" />
            {error}
          </p>
        )}
      </CardContent>
    </Card>
  )
}
