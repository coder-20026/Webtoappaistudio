package com.whatsapptoexcel.app.exporter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

object PdfConverter {

    private const val FIRST_COL = 0
    private const val LAST_COL = 8
    private const val FIRST_ROW = 0
    private const val LAST_ROW = 23

    private val DEFAULT_COL_WIDTHS = listOf(6.71, 12.43, 33.86, 5.86, 8.43, 18.43, 19.0, 17.86, 7.57)
    private const val DEFAULT_ROW_HEIGHT = 18.0 // points

    // Excel width units to approximate pixels
    private fun colWidthToPx(width: Double): Float {
        return (width * 7 + 5).toFloat()
    }

    // Excel row height (points) to approximate pixels
    private fun rowHeightToPx(pts: Double): Float {
        return (pts * 96 / 72).toFloat()
    }

    private fun getCellText(cell: Cell): String {
        return when (cell.cellType) {
            CellType.BLANK -> ""
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
                    sdf.format(cell.dateCellValue)
                } else {
                    val v = cell.numericCellValue
                    if (v == v.toInt().toDouble()) v.toInt().toString() else v.toString()
                }
            }
            CellType.STRING -> cell.stringCellValue ?: ""
            CellType.FORMULA -> {
                try {
                    val cv = cell.sheet.workbook.creationHelper.createFormulaEvaluator().evaluate(cell)
                    when (cv.cellType) {
                        CellType.BOOLEAN -> cv.booleanValue.toString()
                        CellType.NUMERIC -> {
                            val v = cv.numberValue
                            if (v == v.toInt().toDouble()) v.toInt().toString() else v.toString()
                        }
                        CellType.STRING -> cv.stringValue ?: ""
                        else -> ""
                    }
                } catch (e: Exception) {
                    // Fallback to cached formula value
                    try {
                        cell.stringCellValue ?: ""
                    } catch (e2: Exception) {
                        try {
                            cell.numericCellValue.toString()
                        } catch (e3: Exception) {
                            ""
                        }
                    }
                }
            }
            else -> ""
        }
    }

    private fun wrapText(paint: Paint, text: String, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val paragraphs = text.split("\n")
        val lines = mutableListOf<String>()

        for (para in paragraphs) {
            val words = para.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                lines.add(para)
                continue
            }
            var currentLine = ""
            for (word in words) {
                val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                val width = paint.measureText(testLine)
                if (width > maxWidth && currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                    currentLine = word
                } else {
                    currentLine = testLine
                }
            }
            if (currentLine.isNotEmpty()) {
                lines.add(currentLine)
            }
        }
        return lines
    }

    private fun parseArgbColor(color: org.apache.poi.ss.usermodel.Color?): Int {
        if (color == null) return Color.WHITE
        if (color is org.apache.poi.xssf.usermodel.XSSFColor) {
            val hex = color.argbHex
            if (hex != null && hex.length >= 6) {
                val fullHex = if (hex.length == 8) hex else "FF$hex"
                return try {
                    Color.parseColor("#$fullHex")
                } catch (e: Exception) {
                    Color.WHITE
                }
            }
        }
        return Color.WHITE
    }

    fun convertExcelToPdf(
        excelBytes: ByteArray,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): ByteArray {
        val wb = XSSFWorkbook(ByteArrayInputStream(excelBytes))
        val sheets = mutableListOf<Sheet>()
        for (i in 0 until wb.numberOfSheets) {
            val s = wb.getSheetAt(i)
            if (s != null && s.physicalNumberOfRows >= 0) {
                sheets.add(s)
            }
        }

        if (sheets.isEmpty()) {
            wb.close()
            throw IllegalArgumentException("Excel file me koi valid sheets nahi mili!")
        }

        val pdfDocument = PdfDocument()

        // Page size in points (A4 Landscape: 842 x 595)
        val pageWidthPoints = 842
        val pageHeightPoints = 595
        val margin = 40f // padding on left and right in points
        val topOffset = 25f // space for top page numbers

        for (sheetIdx in sheets.indices) {
            val ws = sheets[sheetIdx]

            // Collect merges
            val mergedRegions = mutableListOf<CellRangeAddress>()
            for (i in 0 until ws.numMergedRegions) {
                mergedRegions.add(ws.getMergedRegion(i))
            }

            val skipCells = mutableSetOf<String>()
            val mergeHeadMap = mutableMapOf<String, CellRangeAddress>()
            for (mr in mergedRegions) {
                mergeHeadMap["${mr.firstRow},${mr.firstColumn}"] = mr
                for (r in mr.firstRow..mr.lastRow) {
                    for (c in mr.firstColumn..mr.lastColumn) {
                        if (r == mr.firstRow && c == mr.firstColumn) continue
                        skipCells.add("$r,$c")
                    }
                }
            }

            // Compute cumulative column x-coordinates and row y-coordinates in pixels
            val colX = FloatArray(LAST_COL + 3)
            colX[0] = 0f
            colX[1] = 0f
            for (c in FIRST_COL..LAST_COL) {
                val explicitWidth = ws.getColumnWidth(c) / 256.0
                val w = if (explicitWidth > 0) explicitWidth else DEFAULT_COL_WIDTHS[c]
                colX[c + 2] = colX[c + 1] + colWidthToPx(w)
            }

            val rowY = FloatArray(LAST_ROW + 3)
            rowY[0] = 0f
            rowY[1] = 0f
            for (r in FIRST_ROW..LAST_ROW) {
                val row = ws.getRow(r)
                val explicitHeight = row?.heightInPoints?.toDouble() ?: DEFAULT_ROW_HEIGHT
                val rh = if (explicitHeight > 0) explicitHeight else DEFAULT_ROW_HEIGHT
                rowY[r + 2] = rowY[r + 1] + rowHeightToPx(rh)
            }

            val totalTableWidth = colX[LAST_COL + 2]
            val totalTableHeight = rowY[LAST_ROW + 2]

            // Create PDF page
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidthPoints, pageHeightPoints, sheetIdx + 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            // Clear with white background
            val bgPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, pageWidthPoints.toFloat(), pageHeightPoints.toFloat(), bgPaint)

            // Scale table to fit in the printable area (842 - 2 * margin) x (595 - 2 * margin - topOffset)
            val availW = pageWidthPoints - margin * 2
            val availH = pageHeightPoints - margin * 2 - topOffset
            val scaleX = availW / totalTableWidth
            val scaleY = availH / totalTableHeight
            val scale = Math.min(scaleX, scaleY)

            // Save state, translate and scale
            canvas.save()
            val startX = margin + (availW - totalTableWidth * scale) / 2f
            val startY = margin + topOffset
            canvas.translate(startX, startY)
            canvas.scale(scale, scale)

            // Setup Paints
            val textPaint = Paint().apply {
                isAntiAlias = true
                color = Color.BLACK
            }
            val borderPaint = Paint().apply {
                isAntiAlias = true
                color = Color.BLACK
                style = Paint.Style.STROKE
            }
            val fillPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
            }

            val pad = 3f

            for (r in FIRST_ROW..LAST_ROW) {
                for (c in FIRST_COL..LAST_COL) {
                    val key = "$r,$c"
                    if (skipCells.contains(key)) continue

                    val rowObj = ws.getRow(r)
                    val cellObj = rowObj?.getCell(c)

                    val head = mergeHeadMap[key]
                    val c2 = head?.lastColumn ?: c
                    val r2 = head?.lastRow ?: r

                    val left = colX[c + 1]
                    val top = rowY[r + 1]
                    val right = colX[c2 + 2]
                    val bottom = rowY[r2 + 2]
                    val cellW = right - left
                    val cellH = bottom - top

                    // Draw Cell Fill Background
                    if (cellObj != null) {
                        val fill = cellObj.cellStyle?.fillForegroundColorColor
                        if (fill != null) {
                            val parsedColor = parseArgbColor(fill)
                            if (parsedColor != Color.WHITE) {
                                fillPaint.color = parsedColor
                                canvas.drawRect(left, top, right, bottom, fillPaint)
                            }
                        }
                    }

                    // Draw Cell Text
                    if (cellObj != null) {
                        val text = getCellText(cellObj)
                        if (text.isNotEmpty()) {
                            val cellStyle = cellObj.cellStyle
                            val font = if (cellStyle != null) {
                                wb.getFontAt(cellStyle.fontIndex.toInt())
                            } else {
                                wb.createFont()
                            }

                            val fontName = font.fontName ?: "Calibri"
                            val fontSize = font.fontHeightInPoints.toFloat()
                            val isBold = font.bold
                            val isItalic = font.italic

                            val typefaceStyle = when {
                                isBold && isItalic -> Typeface.BOLD_ITALIC
                                isBold -> Typeface.BOLD
                                isItalic -> Typeface.ITALIC
                                else -> Typeface.NORMAL
                            }

                            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, typefaceStyle)
                            textPaint.textSize = fontSize

                            // Text color
                            val fontColor = font.color
                            textPaint.color = if (fontColor == IndexedColors.BLUE.index) {
                                Color.BLUE
                            } else {
                                Color.BLACK
                            }

                            // Alignment
                            val hAlign = cellStyle?.alignment ?: HorizontalAlignment.LEFT
                            val wrapText = cellStyle?.wrapText ?: false

                            val paddedWidth = cellW - pad * 2 - 8f // offset inset for safety
                            val lines = wrapText(textPaint, text, paddedWidth)
                            val lineHeight = fontSize * 1.2f

                            // Align text inside bounds
                            val textHeight = lines.size * lineHeight

                            // Clip bounds to avoid drawing over other cells
                            canvas.save()
                            canvas.clipRect(left, top, right, bottom)

                            lines.forEachIndexed { idx, line ->
                                val measuredLineWidth = textPaint.measureText(line)
                                val xPos = when (hAlign) {
                                    HorizontalAlignment.CENTER -> left + cellW / 2f - measuredLineWidth / 2f
                                    HorizontalAlignment.RIGHT -> right - pad - 4f - measuredLineWidth
                                    else -> left + pad + 4f
                                }

                                // Vertically center the lines block inside the cell bounds
                                val yStart = top + (cellH - textHeight) / 2f + lineHeight / 2f + (fontSize / 2.5f)
                                val yPos = yStart + idx * lineHeight

                                canvas.drawText(line, xPos, yPos, textPaint)
                            }

                            canvas.restore()
                        }
                    }

                    // Draw Cell Borders
                    val cellStyle = cellObj?.cellStyle
                    val bTop = cellStyle?.borderTop ?: BorderStyle.NONE
                    val bBottom = cellStyle?.borderBottom ?: BorderStyle.NONE
                    val bLeft = cellStyle?.borderLeft ?: BorderStyle.NONE
                    val bRight = cellStyle?.borderRight ?: BorderStyle.NONE

                    fun borderThickness(bStyle: BorderStyle): Float {
                        return when (bStyle) {
                            BorderStyle.NONE -> 0f
                            BorderStyle.THICK -> 2.5f
                            BorderStyle.MEDIUM, BorderStyle.DOUBLE -> 1.5f
                            else -> 0.75f // thin, hair, dashed, etc.
                        }
                    }

                    fun drawBorderLine(x1: Float, y1: Float, x2: Float, y2: Float, bStyle: BorderStyle) {
                        val thickness = borderThickness(bStyle)
                        if (thickness > 0) {
                            borderPaint.strokeWidth = thickness
                            canvas.drawLine(x1, y1, x2, y2, borderPaint)
                        }
                    }

                    // Draw standard cell borders
                    drawBorderLine(left, top, right, top, bTop)
                    drawBorderLine(left, bottom, right, bottom, bBottom)
                    drawBorderLine(left, top, left, bottom, bLeft)
                    drawBorderLine(right, top, right, bottom, bRight)
                }
            }

            // Draw final outer thick border around the entire active range A1:I24 (colX[1]..colX[LAST_COL+2], rowY[1]..rowY[LAST_ROW+2])
            borderPaint.strokeWidth = 2.5f
            canvas.drawRect(
                colX[1],
                rowY[1],
                colX[LAST_COL + 2],
                rowY[LAST_ROW + 2],
                borderPaint
            )

            // Restore canvas translations
            canvas.restore()

            // Draw Page Number in top-right corner of the actual PDF Document page (not inside scaled sheet)
            val pageNumPaint = Paint().apply {
                isAntiAlias = true
                color = Color.BLACK
                textSize = 12f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            val pageNumStr = (sheetIdx + 1).toString()
            val textWidth = pageNumPaint.measureText(pageNumStr)
            canvas.drawText(pageNumStr, pageWidthPoints - margin - textWidth, margin, pageNumPaint)

            pdfDocument.finishPage(page)

            onProgress?.invoke(sheetIdx + 1, sheets.size)
        }

        val out = ByteArrayOutputStream()
        pdfDocument.writeTo(out)
        pdfDocument.close()
        wb.close()
        return out.toByteArray()
    }
}
