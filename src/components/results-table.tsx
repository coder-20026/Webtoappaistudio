import { Input } from './ui/input'
import { Button } from './ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from './ui/table'
import { Download, Copy, Check, Trash2 } from 'lucide-react'
import type { CaseRow } from '../lib/chat-parser'

type EditableField =
  | 'bankName'
  | 'applicantName'
  | 'reasonForCnv'
  | 'status'
  | 'latlongFrom'
  | 'latlongTo'
  | 'area'
  | 'km'

const COLUMNS: { key: EditableField | 'srNo' | 'date'; label: string; w: string }[] = [
  { key: 'srNo', label: 'SR', w: 'w-12 text-center' },
  { key: 'date', label: 'DATE', w: 'w-28' },
  { key: 'bankName', label: 'BANK NAME', w: 'w-40' },
  { key: 'applicantName', label: 'APPLICANT NAME', w: 'w-56' },
  { key: 'reasonForCnv', label: 'REASON', w: 'w-28' },
  { key: 'status', label: 'STATUS', w: 'w-28' },
  { key: 'latlongFrom', label: 'LATLONG FROM', w: 'w-36' },
  { key: 'latlongTo', label: 'LATLONG TO', w: 'w-36' },
  { key: 'area', label: 'AREA', w: 'w-28' },
  { key: 'km', label: 'KM', w: 'w-20' },
]

export function ResultsTable({
  rows,
  onChange,
  onDelete,
  onExport,
  onCopy,
  copied,
}: {
  rows: CaseRow[]
  onChange: (id: string, field: EditableField, value: string) => void
  onDelete: (id: string) => void
  onExport: () => void
  onCopy: () => void
  copied: boolean
}) {
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          <span className="font-semibold text-foreground">{rows.length}</span> case
          {rows.length === 1 ? '' : 's'} mile. Niche edit kar sakte ho.
        </p>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={onCopy}>
            {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
            {copied ? 'Copied' : 'Copy'}
          </Button>
          <Button size="sm" onClick={onExport}>
            <Download className="size-4" />
            Excel
          </Button>
        </div>
      </div>

      <div className="overflow-x-auto rounded-xl border border-border/70 shadow-sm">
        <Table>
          <TableHeader>
            <TableRow className="border-b border-border bg-muted/70 hover:bg-muted/70">
              {COLUMNS.map((c) => (
                <TableHead
                  key={c.key}
                  className={`${c.w} text-[11px] font-semibold uppercase tracking-wide text-muted-foreground`}
                >
                  {c.label}
                </TableHead>
              ))}
              <TableHead className="w-12" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((row) => (
              <TableRow key={row.id} className="even:bg-muted/25 hover:bg-accent/30">
                <TableCell className="text-center text-sm text-muted-foreground">
                  {row.srNo}
                </TableCell>
                <TableCell className="whitespace-nowrap text-sm">{row.date}</TableCell>
                {(
                  [
                    'bankName',
                    'applicantName',
                    'reasonForCnv',
                    'status',
                    'latlongFrom',
                    'latlongTo',
                    'area',
                    'km',
                  ] as EditableField[]
                ).map((field) => (
                  <TableCell key={field} className="p-1">
                    <Input
                      value={row[field]}
                      onChange={(e) => onChange(row.id, field, e.target.value)}
                      className="h-8 border-transparent bg-transparent text-sm shadow-none focus-visible:border-input focus-visible:bg-background"
                    />
                  </TableCell>
                ))}
                <TableCell className="p-1 text-center">
                  <Button
                    variant="ghost"
                    size="icon"
                    className="size-8 text-muted-foreground hover:text-destructive"
                    onClick={() => onDelete(row.id)}
                    aria-label="Delete row"
                  >
                    <Trash2 className="size-4" />
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}
