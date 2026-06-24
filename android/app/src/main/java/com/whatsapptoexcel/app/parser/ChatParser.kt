package com.whatsapptoexcel.app.parser

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ParsedMessage(
    val date: Date,
    val rawDate: String,
    val time: String,
    val sender: String,
    val body: String
)

data class CaseRow(
    val id: String = UUID.randomUUID().toString(),
    var srNo: Int,
    var date: String, // DD/MM/YYYY
    var bankName: String,
    var applicantName: String,
    var reasonForCnv: String,
    var status: String,
    var latlongFrom: String,
    var latlongTo: String,
    var area: String,
    var km: String,
    val rawBody: String
)

data class ProcessStats(
    val totalMessages: Int,
    val fromSender: Int,
    val inRange: Int,
    val validCases: Int
)

data class ProcessResult(
    val rows: List<CaseRow>,
    val stats: ProcessStats
)

object ChatParser {

    // Matches the leading "DD/MM/YY(YY), HH:MM(:SS)( am/pm) - Sender: " portion of a line.
    // Supports both 12h and 24h, optional seconds, and the unicode narrow space.
    private val LINE_HEADER_RE = Regex(
        "^(\\d{1,2})/(\\d{1,2})/(\\d{2,4}),?\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?)\\s*([apAP][. ]?[mM][.]?)?\\s*[-–—]\\s*([^:]+?):\\s?([\\s\\S]*)$"
    )

    private val APPLICANT_RE = Regex("applic\\w*(?:\\s*name)?\\s*[:\\-–—=]+\\s*(.+)", RegexOption.IGNORE_CASE)

    fun parseDDMMYYYY(d: String): Date? {
        val m = Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{2,4})$").find(d.trim()) ?: return null
        val dd = m.groupValues[1].toIntOrNull() ?: return null
        val mm = m.groupValues[2].toIntOrNull() ?: return null
        var yyyy = m.groupValues[3].toIntOrNull() ?: return null
        if (yyyy < 100) yyyy += 2000

        if (mm < 1 || mm > 12 || dd < 1 || dd > 31) return null
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, yyyy)
        cal.set(Calendar.MONTH, mm - 1)
        cal.set(Calendar.DAY_OF_MONTH, dd)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.time
    }

    private fun buildDate(dd: String, mm: String, yy: String): Date {
        var year = yy.toIntOrNull() ?: 2024
        if (year < 100) year += 2000
        val month = (mm.toIntOrNull() ?: 1) - 1
        val day = dd.toIntOrNull() ?: 1

        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month)
        cal.set(Calendar.DAY_OF_MONTH, day)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.time
    }

    fun formatDDMMYYYY(d: Date): String {
        val cal = Calendar.getInstance()
        cal.time = d
        val dd = String.format(Locale.US, "%02d", cal.get(Calendar.DAY_OF_MONTH))
        val mm = String.format(Locale.US, "%02d", cal.get(Calendar.MONTH) + 1)
        val yyyy = cal.get(Calendar.YEAR)
        return "$dd/$mm/$yyyy"
    }

    fun parseMessages(raw: String): List<ParsedMessage> {
        val normalized = raw.replace("\r\n", "\n").replace("[\u200e\u200f]".toRegex(), "")
        val lines = normalized.split("\n")
        val messages = mutableListOf<ParsedMessage>()

        for (line in lines) {
            val match = LINE_HEADER_RE.find(line)
            if (match != null) {
                val dd = match.groupValues[1]
                val mm = match.groupValues[2]
                val yy = match.groupValues[3]
                val time = match.groupValues[4]
                // group 5 is am/pm, group 6 is sender, group 7 is body
                val sender = match.groupValues[6].trim()
                val body = match.groupValues[7]

                messages.add(
                    ParsedMessage(
                        date = buildDate(dd, mm, yy),
                        rawDate = "$dd/$mm/$yy",
                        time = time,
                        sender = sender,
                        body = body
                    )
                )
            } else if (messages.isNotEmpty()) {
                val last = messages.last()
                messages[messages.size - 1] = last.copy(body = last.body + "\n" + line)
            }
        }
        return messages
    }

    private fun extractReasonAndBank(body: String): Pair<String, String>? {
        val bracketMatch = Regex("^([^\\n(]*?)\\(([^)]+)\\)", RegexOption.MULTILINE).find(body) ?: return null
        val reason = bracketMatch.groupValues[1].replace("[:\\-–—\\s]+$".toRegex(), "").trim()
        val bank = bracketMatch.groupValues[2].trim()
        if (bank.isEmpty()) return null
        return Pair(reason, bank)
    }

    private fun extractApplicant(body: String): String? {
        for (line in body.split("\n")) {
            // Strip a leading list marker like "1)" / "1." / "-" before matching.
            val cleaned = line.replace("^\\s*\\d+\\s*[).]?\\s*".toRegex(), "").trim()
            val m = APPLICANT_RE.find(cleaned)
            if (m != null) {
                val valStr = m.groupValues[1].trim()
                if (valStr.isNotEmpty()) {
                    return titleCase(valStr)
                }
            }
        }
        return null
    }

    private fun titleCase(s: String): String {
        return s.lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            .trim()
    }

    private fun extractLocation(body: String): Pair<String, String> {
        for (line in body.split("\n")) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("#")) continue
            // Drop leading "#" and strip WhatsApp annotations like "<This message was edited>".
            val content = trimmed.substring(1)
                .replace("<[^>]*>".toRegex(), "")
                .trim()
            if (content.isEmpty()) continue

            // Pull the "lat,long" from anywhere in the line
            val latlongMatch = Regex("(-?\\d+(?:\\.\\d+)?\\s*,\\s*-?\\d+(?:\\.\\d+)?)").find(content)
            var area = content
            var latlongTo = ""
            if (latlongMatch != null) {
                latlongTo = latlongMatch.groupValues[1].replace("\\s+".toRegex(), "")
                val startIndex = latlongMatch.range.first
                val endIndex = latlongMatch.range.last + 1
                area = (content.substring(0, startIndex) + content.substring(endIndex)).trim()
            }
            return Pair(area.trim(), latlongTo)
        }
        return Pair("", "")
    }

    fun extractCase(body: String): Triple<String, String, String>? {
        val rb = extractReasonAndBank(body) ?: return null
        val applicant = extractApplicant(body) ?: return null
        return Triple(rb.first, rb.second, applicant)
    }

    fun processChat(raw: String, senderName: String, fromDate: Date, toDate: Date): ProcessResult {
        val messages = parseMessages(raw)
        var fromSender = 0
        var inRangeCount = 0
        val rows = mutableListOf<CaseRow>()
        var srNo = 0

        // Filter messages by sender and date range, then sort by date ascending.
        val filtered = messages.filter { m ->
            val isSender = m.sender.trim().lowercase().contains(senderName.trim().lowercase())
            if (!isSender) return@filter false
            fromSender++

            val isInDateRange = isInRange(m.date, fromDate, toDate)
            if (!isInDateRange) return@filter false
            inRangeCount++

            true
        }.sortedBy { it.date }

        for (m in filtered) {
            val c = extractCase(m.body) ?: continue
            val loc = extractLocation(m.body)
            srNo++
            rows.add(
                CaseRow(
                    id = "${m.date.time}-$srNo",
                    srNo = srNo,
                    date = formatDDMMYYYY(m.date),
                    bankName = c.second,
                    applicantName = c.third,
                    reasonForCnv = c.first,
                    status = "",
                    latlongFrom = "",
                    latlongTo = loc.second,
                    area = loc.first,
                    km = "",
                    rawBody = m.body.trim()
                )
            )
        }

        return ProcessResult(
            rows = rows,
            stats = ProcessStats(
                totalMessages = messages.size,
                fromSender = fromSender,
                inRange = inRangeCount,
                validCases = rows.size
            )
        )
    }

    private fun isInRange(date: Date, from: Date, to: Date): Boolean {
        val dCal = Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val fCal = Calendar.getInstance().apply {
            time = from
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val tCal = Calendar.getInstance().apply {
            time = to
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val dTime = dCal.timeInMillis
        val fTime = fCal.timeInMillis
        val tTime = tCal.timeInMillis

        return dTime in fTime..tTime
    }
}
