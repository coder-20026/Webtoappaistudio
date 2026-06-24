package com.whatsapptoexcel.app.exporter

import com.whatsapptoexcel.app.parser.CaseRow
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFFont
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.util.Locale

object ExcelExporter {

    private val HEADERS = listOf(
        "SR NO.",
        "BANK NAME",
        "APPLICAT NAME",
        "STATUS",
        "REASON FOR CNV",
        "LATLONG FROM.",
        "LATLONG TO.",
        "AREA",
        "KM"
    )

    // Exact column widths (Excel character units) A..I.
    private val COL_WIDTHS = listOf(6.71, 12.43, 33.86, 5.86, 8.43, 18.43, 19.0, 17.86, 7.57)

    private const val DATA_ROWS = 15
    private const val HOME_LATLONG = "22.1589,71.6827"

    private val MONTH_NAMES = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )

    private fun groupByDate(rows: List<CaseRow>): Map<String, List<CaseRow>> {
        val map = linkedMapOf<String, MutableList<CaseRow>>()
        for (r in rows) {
            val list = map.getOrPut(r.date) { mutableListOf() }
            list.add(r)
        }
        return map
    }

    private fun dateToSheetName(date: String): String {
        return (date ?: "Sheet1").replace(Regex("[/\\\\?*\\[\\]:]"), "-")
            .let { if (it.length > 31) it.substring(0, 31) else it }
    }

    fun monthYearFromRows(rows: List<CaseRow>): String? {
        for (r in rows) {
            val m = Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{2,4})$").find(r.date ?: "")
            if (m != null) {
                val monthIdx = (m.groupValues[2].toIntOrNull() ?: 1) - 1
                val yy = m.groupValues[3]
                val year = if (yy.length == 2) "20$yy" else yy
                if (monthIdx in 0..11) {
                    return "${MONTH_NAMES[monthIdx]}-$year"
                }
            }
        }
        return null
    }

    private fun numericKm(km: String): Double {
        val cleaned = km.replace(Regex("[^\\d.]"), "")
        return cleaned.toDoubleOrNull() ?: 0.0
    }

    // Border and Outline styling helper
    private fun borderRange(sheet: Sheet, r1: Int, c1: Int, r2: Int, c2: Int, style: CellStyle) {
        for (r in r1..r2) {
            val row = sheet.getRow(r) ?: sheet.createRow(r)
            for (c in c1..c2) {
                val cell = row.getCell(c) ?: row.createCell(c)
                cell.cellStyle = style
            }
        }
    }

    private fun thickOutline(wb: XSSFWorkbook, sheet: Sheet, r1: Int, c1: Int, r2: Int, c2: Int) {
        for (r in r1..r2) {
            val row = sheet.getRow(r) ?: sheet.createRow(r)
            for (c in c1..c2) {
                val cell = row.getCell(c) ?: row.createCell(c)
                val currentStyle = cell.cellStyle ?: wb.createCellStyle()
                
                // Create a clone style so we don't mess up shared styles
                val newStyle = wb.createCellStyle()
                newStyle.cloneStyleFrom(currentStyle)

                if (r == r1) newStyle.borderTop = BorderStyle.MEDIUM
                if (r == r2) newStyle.borderBottom = BorderStyle.MEDIUM
                if (c == c1) newStyle.borderLeft = BorderStyle.MEDIUM
                if (c == c2) newStyle.borderRight = BorderStyle.MEDIUM

                cell.cellStyle = newStyle
            }
        }
    }

    private fun buildDaySheet(
        wb: XSSFWorkbook,
        sheet: Sheet,
        date: String,
        dayRows: List<CaseRow>,
        executiveName: String
    ) {
        // Create common styles
        val bold16Font = wb.createFont().apply {
            bold = true
            fontHeightInPoints = 16
        }
        val bold11Font = wb.createFont().apply {
            bold = true
            fontHeightInPoints = 11
        }
        val normal14Font = wb.createFont().apply {
            fontHeightInPoints = 14
        }
        val normal18Font = wb.createFont().apply {
            fontHeightInPoints = 18
        }
        val bold18Font = wb.createFont().apply {
            bold = true
            fontHeightInPoints = 18
        }
        val hyperlinkFont = wb.createFont().apply {
            fontHeightInPoints = 14
            color = IndexedColors.BLUE.index
            underline = Font.U_SINGLE
        }

        val leftAlign16Style = wb.createCellStyle().apply {
            setFont(bold16Font)
            verticalAlignment = VerticalAlignment.CENTER
            alignment = HorizontalAlignment.LEFT
        }

        val headerStyle = wb.createCellStyle().apply {
            setFont(bold11Font)
            verticalAlignment = VerticalAlignment.CENTER
            alignment = HorizontalAlignment.CENTER
            wrapText = true
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        val dataStyle = wb.createCellStyle().apply {
            setFont(normal14Font)
            verticalAlignment = VerticalAlignment.CENTER
            alignment = HorizontalAlignment.CENTER
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        val hyperlinkStyle = wb.createCellStyle().apply {
            setFont(hyperlinkFont)
            verticalAlignment = VerticalAlignment.CENTER
            alignment = HorizontalAlignment.LEFT
        }

        val summaryLabelStyle = wb.createCellStyle().apply {
            setFont(bold11Font)
            verticalAlignment = VerticalAlignment.CENTER
            alignment = HorizontalAlignment.CENTER
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        val summaryValueStyle = wb.createCellStyle().apply {
            setFont(normal18Font)
            verticalAlignment = VerticalAlignment.CENTER
            alignment = HorizontalAlignment.CENTER
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        val summaryTotalValStyle = wb.createCellStyle().apply {
            setFont(bold18Font)
            verticalAlignment = VerticalAlignment.CENTER
            alignment = HorizontalAlignment.CENTER
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        // Row 1: Field Executive (A1:F1) and Date (G1:I1)
        val row1 = sheet.createRow(0)
        row1.heightInPoints = 26.25f

        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 5))
        sheet.addMergedRegion(CellRangeAddress(0, 0, 6, 8))

        val cellA1 = row1.createCell(0)
        cellA1.setCellValue("FIELD EXECUTIVE NAME :- $executiveName")
        cellA1.cellStyle = leftAlign16Style

        val cellG1 = row1.createCell(6)
        cellG1.setCellValue("DATE :- $date")
        cellG1.cellStyle = leftAlign16Style

        // Fill other merged cells in row 1 with borders
        for (i in 1..5) row1.createCell(i).cellStyle = leftAlign16Style
        for (i in 7..8) row1.createCell(i).cellStyle = leftAlign16Style

        // Row 2: Headers
        val row2 = sheet.createRow(1)
        row2.heightInPoints = 24f
        for (i in HEADERS.indices) {
            val cell = row2.createCell(i)
            cell.setCellValue(HEADERS[i])
            cell.cellStyle = headerStyle
        }

        // Data Rows 3..17 (index 2..16)
        val visitCount = dayRows.size
        val maxLen = HEADERS.map { it.length }.toMutableList()

        for (i in 0 until DATA_ROWS) {
            val rIdx = 2 + i
            val row = sheet.createRow(rIdx)
            row.heightInPoints = if (rIdx <= 11) 20.25f else 15f

            val r = dayRows.getOrNull(i)
            if (r != null) {
                row.createCell(0).apply { setCellValue((i + 1).toDouble()); cellStyle = dataStyle }
                row.createCell(1).apply { setCellValue(r.bankName); cellStyle = dataStyle }
                row.createCell(2).apply { setCellValue(r.applicantName); cellStyle = dataStyle }
                row.createCell(3).apply { setCellValue(r.status); cellStyle = dataStyle }
                row.createCell(4).apply { setCellValue(r.reasonForCnv); cellStyle = dataStyle }

                val latlongFrom = if (i == 0) HOME_LATLONG else dayRows[i - 1].latlongTo
                row.createCell(5).apply { setCellValue(latlongFrom); cellStyle = dataStyle }
                row.createCell(6).apply { setCellValue(r.latlongTo); cellStyle = dataStyle }
                row.createCell(7).apply { setCellValue(r.area); cellStyle = dataStyle }
                row.createCell(8).apply { setCellValue(r.km); cellStyle = dataStyle }
            } else if (i == visitCount && visitCount > 0) {
                // Return to home row
                val last = dayRows[visitCount - 1]
                row.createCell(0).apply { setCellValue((i + 1).toDouble()); cellStyle = dataStyle }
                row.createCell(1).apply { setCellValue(""); cellStyle = dataStyle }
                row.createCell(2).apply { setCellValue(""); cellStyle = dataStyle }
                row.createCell(3).apply { setCellValue(""); cellStyle = dataStyle }
                row.createCell(4).apply { setCellValue(""); cellStyle = dataStyle }
                row.createCell(5).apply { setCellValue(last.latlongTo); cellStyle = dataStyle }
                row.createCell(6).apply { setCellValue(HOME_LATLONG); cellStyle = dataStyle }
                val areaHome = if (last.area.isNotEmpty()) "${last.area} - Home" else "Home"
                row.createCell(7).apply { setCellValue(areaHome); cellStyle = dataStyle }
                row.createCell(8).apply { setCellValue(""); cellStyle = dataStyle }
            } else {
                row.createCell(0).apply { setCellValue((i + 1).toDouble()); cellStyle = dataStyle }
                for (c in 1..8) {
                    row.createCell(c).apply { setCellValue(""); cellStyle = dataStyle }
                }
            }

            // Compute max lengths for auto-fit
            for (c in 0..8) {
                val cell = row.getCell(c)
                val text = when (cell.cellType) {
                    CellType.NUMERIC -> {
                        val num = cell.numericCellValue
                        if (num == num.toInt().toDouble()) num.toInt().toString() else num.toString()
                    }
                    else -> cell.stringCellValue ?: ""
                }
                if (text.length > maxLen[c]) maxLen[c] = text.length
            }
        }

        // Set column widths based on max content
        for (i in COL_WIDTHS.indices) {
            val base = COL_WIDTHS[i]
            val fitted = maxLen[i] + 2
            val width = Math.max(base, fitted.toDouble())
            sheet.setColumnWidth(i, (width * 256).toInt())
        }

        // Row 18: Spacer row (height 12.0)
        val row18 = sheet.createRow(17)
        row18.heightInPoints = 12f

        // Ensure F3 (row 3, col 5) has HOME_LATLONG even if empty
        val row3 = sheet.getRow(2) ?: sheet.createRow(2)
        val cellF3 = row3.getCell(5) ?: row3.createCell(5)
        if (cellF3.stringCellValue.isEmpty()) {
            cellF3.setCellValue(HOME_LATLONG)
            cellF3.cellStyle = dataStyle
        }

        // Column J: Google Maps route links, J3:J16
        sheet.setColumnWidth(9, (13 * 256))
        for (rIdx in 2..15) {
            val row = sheet.getRow(rIdx) ?: sheet.createRow(rIdx)
            val cellJ = row.createCell(9)
            val rowNum = rIdx + 1
            cellJ.cellFormula = "HYPERLINK(\"https://www.google.com/maps/dir/?api=1&origin=\"&F$rowNum&\"&destination=\"&G$rowNum&\"&travelmode=driving\",\"Open Route\")"
            cellJ.cellStyle = hyperlinkStyle
        }

        // Summary block F19:H24
        // Row 19 (header)
        val row19 = sheet.createRow(18).apply { heightInPoints = 16.5f }
        row19.createCell(5).apply { setCellValue(""); cellStyle = summaryLabelStyle }
        row19.createCell(6).apply { setCellValue("NO. OF COUNT"); cellStyle = summaryLabelStyle }
        row19.createCell(7).apply { setCellValue("AMOUNT"); cellStyle = summaryLabelStyle }

        // Row 20 (Total KM)
        val row20 = sheet.createRow(19).apply { heightInPoints = 24.75f }
        row20.createCell(5).apply { setCellValue("Total KM"); cellStyle = summaryLabelStyle }
        row20.createCell(6).apply { cellFormula = "SUM(I3:I17)&\"×2.5\""; cellStyle = summaryValueStyle }
        row20.createCell(7).apply { cellFormula = "ROUNDUP(SUM(I3:I17)*2.5,0)"; cellStyle = summaryValueStyle }

        // Row 21 (Lunch)
        val row21 = sheet.createRow(20).apply { heightInPoints = 24.75f }
        row21.createCell(5).apply { setCellValue("LUNCH"); cellStyle = summaryLabelStyle }
        row21.createCell(6).apply { setCellValue(""); cellStyle = summaryValueStyle }
        row21.createCell(7).apply { cellFormula = "IF(SUM(I3:I17)>=110,75,0)"; cellStyle = summaryValueStyle }

        // Row 22 (Visit)
        val row22 = sheet.createRow(21).apply { heightInPoints = 24.75f }
        row22.createCell(5).apply { setCellValue("VISIT"); cellStyle = summaryLabelStyle }
        row22.createCell(6).apply { cellFormula = "COUNTA(B3:B17)&\"×25\""; cellStyle = summaryValueStyle }
        row22.createCell(7).apply { cellFormula = "COUNTA(B3:B17)*25"; cellStyle = summaryValueStyle }

        // Row 23 (Other)
        val row23 = sheet.createRow(22).apply { heightInPoints = 25.5f }
        row23.createCell(5).apply { setCellValue("OTHER"); cellStyle = summaryLabelStyle }
        row23.createCell(6).apply { setCellValue(""); cellStyle = summaryValueStyle }
        row23.createCell(7).apply { setCellValue(""); cellStyle = summaryValueStyle }

        // Row 24 (Total Amount)
        val row24 = sheet.createRow(23).apply { heightInPoints = 15f }
        sheet.addMergedRegion(CellRangeAddress(23, 23, 5, 6))
        val cellTotalLbl = row24.createCell(5)
        cellTotalLbl.setCellValue("TOTAL AMOUNT")
        cellTotalLbl.cellStyle = summaryLabelStyle
        // empty cell for merged region
        row24.createCell(6).cellStyle = summaryLabelStyle

        row24.createCell(7).apply { cellFormula = "SUM(H20:H23)"; cellStyle = summaryTotalValStyle }

        // Draw outer borders
        thickOutline(wb, sheet, 0, 0, 16, 8) // Main table block
        thickOutline(wb, sheet, 18, 5, 23, 7) // Summary block
        thickOutline(wb, sheet, 0, 0, 23, 8) // Full grid outline
    }

    private fun sheetRef(name: String): String {
        return "'${name.replace("'", "''")}'"
    }

    private fun applyMonthlyTotals(wb: XSSFWorkbook, sheet: Sheet, allSheetNames: List<String>) {
        val grandTotalExpr = allSheetNames.joinToString("+") { "${sheetRef(it)}!H24" }

        val bold18Font = wb.createFont().apply {
            bold = true
            fontHeightInPoints = 18
        }
        val normal14Font = wb.createFont().apply {
            fontHeightInPoints = 14
        }
        val normal18Font = wb.createFont().apply {
            fontHeightInPoints = 18
        }

        // Style for C21
        val c21Style = wb.createCellStyle().apply {
            setFont(bold18Font)
            verticalAlignment = VerticalAlignment.CENTER
            alignment = HorizontalAlignment.CENTER
        }

        // Style for G23
        val g23Style = wb.createCellStyle().apply {
            setFont(normal14Font)
            verticalAlignment = VerticalAlignment.CENTER
            alignment = HorizontalAlignment.CENTER
        }

        // Style for H23
        val h23Style = wb.createCellStyle().apply {
            setFont(normal18Font)
            verticalAlignment = VerticalAlignment.CENTER
            alignment = HorizontalAlignment.CENTER
        }

        // Set values
        val row21 = sheet.getRow(20) ?: sheet.createRow(20)
        val cellC21 = row21.createCell(2)
        cellC21.cellFormula = "\"Total Amount:- \"&($grandTotalExpr)&\"/-\""
        cellC21.cellStyle = c21Style

        val row23 = sheet.getRow(22) ?: sheet.createRow(22)
        val cellG23 = row23.createCell(6)
        cellG23.setCellValue("Monthly recharge")
        cellG23.cellStyle = g23Style

        val cellH23 = row23.createCell(7)
        cellH23.setCellValue(299.0)
        cellH23.cellStyle = h23Style
    }

    fun exportToExcel(rows: List<CaseRow>, executiveName: String): ByteArray {
        val wb = XSSFWorkbook()
        val groups = groupByDate(rows)
        val sheetNames = mutableListOf<String>()

        if (groups.isEmpty()) {
            val name = "Sheet1"
            val sheet = wb.createSheet(name)
            buildDaySheet(wb, sheet, "", emptyList(), executiveName)
            sheetNames.add(name)
        } else {
            for ((date, dayRows) in groups) {
                val name = dateToSheetName(date)
                val sheet = wb.createSheet(name)
                buildDaySheet(wb, sheet, date, dayRows, executiveName)
                sheetNames.add(name)
            }
        }

        // Monthly grand total + recharge on the last sheet only
        val lastName = sheetNames.last()
        val lastSheet = wb.getSheet(lastName)
        applyMonthlyTotals(wb, lastSheet, sheetNames)

        val out = ByteArrayOutputStream()
        wb.write(out)
        wb.close()
        return out.toByteArray()
    }

    fun rowsToClipboard(rows: List<CaseRow>): String {
        val header = HEADERS.joinToString("\t")
        val lines = rows.map { r ->
            listOf(
                r.srNo,
                r.bankName,
                r.applicantName,
                r.status,
                r.reasonForCnv,
                r.latlongFrom,
                r.latlongTo,
                r.area,
                r.km
            ).joinToString("\t")
        }
        return (listOf(header) + lines).joinToString("\n")
    }
}
