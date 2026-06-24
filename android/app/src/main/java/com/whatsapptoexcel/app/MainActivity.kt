package com.whatsapptoexcel.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.whatsapptoexcel.app.exporter.ExcelExporter
import com.whatsapptoexcel.app.exporter.PdfConverter
import com.whatsapptoexcel.app.parser.CaseRow
import com.whatsapptoexcel.app.parser.ChatParser
import com.whatsapptoexcel.app.parser.ProcessStats
import com.whatsapptoexcel.app.ui.screens.MainScreen
import com.whatsapptoexcel.app.ui.theme.WhatsAppToExcelTheme
import java.io.File
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Screen-level states
    private var currentGps by mutableStateOf("")
    private var gpsAccuracy by mutableStateOf(0f)
    private var isGpsLoading by mutableStateOf(false)
    private var isProcessingChat by mutableStateOf(false)

    private var importedFileName by mutableStateOf("")
    private var importedChatText by mutableStateOf("")

    private var pdfBusy by mutableStateOf(false)
    private var pdfProgress by mutableStateOf("")
    private var pdfError by mutableStateOf("")

    private var rowsState = mutableStateListOf<CaseRow>()
    private var processStatsState by mutableStateOf<ProcessStats?>(null)

    private var tableCopied by mutableStateOf(false)
    private var errorMsg by mutableStateOf("")

    // Track the Excel bytes loaded for PDF conversion
    private var excelBytesForPdf: ByteArray? = null

    // Launchers for picking files
    private val pickChatFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            handleChatFilePicked(uri)
        }
    }

    private val pickExcelFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            handleExcelFilePicked(uri)
        }
    }

    private fun isGpsEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Fetch location initially only if GPS is enabled and permission is granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && isGpsEnabled()) {
            refreshGpsLocation()
        } else {
            currentGps = ""
        }

        setContent {
            WhatsAppToExcelTheme {
                MainScreen(
                    currentGps = currentGps,
                    gpsAccuracy = gpsAccuracy,
                    isGpsLoading = isGpsLoading,
                    onRefreshGps = { refreshGpsLocation() },
                    isProcessingChat = isProcessingChat,
                    importedFileName = importedFileName,
                    importedChatText = importedChatText,
                    onChatTextChanged = { importedChatText = it },
                    onImportChatFile = { pickChatFileLauncher.launch("text/plain") },
                    onImportExcelForPdf = { pickExcelFileLauncher.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") },
                    pdfBusy = pdfBusy,
                    pdfProgress = pdfProgress,
                    pdfError = pdfError,
                    onProcessData = { senderName, execName, chatText, fromDate, toDate ->
                        processData(senderName, execName, chatText, fromDate, toDate)
                    },
                    rows = rowsState,
                    stats = processStatsState,
                    onRowChanged = { id, field, value ->
                        val idx = rowsState.indexOfFirst { it.id == id }
                        if (idx >= 0) {
                            val original = rowsState[idx]
                            val updated = when (field) {
                                "bankName" -> original.copy(bankName = value)
                                "applicantName" -> original.copy(applicantName = value)
                                "reasonForCnv" -> original.copy(reasonForCnv = value)
                                "status" -> original.copy(status = value)
                                "latlongFrom" -> original.copy(latlongFrom = value)
                                "latlongTo" -> original.copy(latlongTo = value)
                                "area" -> original.copy(area = value)
                                "km" -> original.copy(km = value)
                                else -> original
                            }
                            rowsState[idx] = updated
                        }
                    },
                    onRowDeleted = { id ->
                        val idx = rowsState.indexOfFirst { it.id == id }
                        if (idx >= 0) {
                            rowsState.removeAt(idx)
                            // Recalculate SR Nos
                            for (i in rowsState.indices) {
                                rowsState[i] = rowsState[i].copy(srNo = i + 1)
                            }
                            // Update statistics count
                            processStatsState = processStatsState?.copy(
                                validCases = rowsState.size
                            )
                        }
                    },
                    onExportToExcel = { exportExcelSheet() },
                    onCopyTable = { copyTableToClipboard() },
                    tableCopied = tableCopied,
                    errorMsg = errorMsg,
                    setErrorMsg = { errorMsg = it }
                )
            }
        }
    }

    private fun refreshGpsLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 100)
            return
        }

        if (!isGpsEnabled()) {
            currentGps = ""
            gpsAccuracy = 0f
            Toast.makeText(this, "Kripya apna GPS/Location on karein", Toast.LENGTH_LONG).show()
            return
        }

        isGpsLoading = true
        currentGps = "Dhoond rahe hain..."
        fusedLocationClient.lastLocation.addOnCompleteListener { task ->
            isGpsLoading = false
            if (task.isSuccessful && task.result != null) {
                val loc = task.result
                currentGps = String.format(Locale.US, "%.4f,%.4f", loc.latitude, loc.longitude)
                gpsAccuracy = loc.accuracy
            } else {
                currentGps = "" // No fallback when GPS fails or is off
                gpsAccuracy = 0f
                Toast.makeText(this, "Location nahi mil payi. Kripya check karein ki GPS on hai.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleChatFilePicked(uri: Uri) {
        isProcessingChat = true
        errorMsg = ""
        Thread {
            try {
                val text = contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().use { it.readText() }
                } ?: ""
                val fileName = getFileName(uri) ?: "WhatsApp_Chat.txt"
                runOnUiThread {
                    importedFileName = fileName
                    importedChatText = text
                    isProcessingChat = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    errorMsg = "File read karne me dikkat aayi: ${e.localizedMessage}"
                    isProcessingChat = false
                }
            }
        }.start()
    }

    private fun handleExcelFilePicked(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes()
            }
            if (bytes != null && bytes.isNotEmpty()) {
                excelBytesForPdf = bytes
                convertExcelToPdfAndShare()
            } else {
                pdfError = "Excel file khaali hai."
            }
        } catch (e: Exception) {
            pdfError = "Excel read karne me dikkat aayi: ${e.localizedMessage}"
        }
    }

    private fun convertExcelToPdfAndShare() {
        val excelBytes = excelBytesForPdf ?: return
        pdfBusy = true
        pdfProgress = "PDF ban rahi hai..."
        pdfError = ""

        Thread {
            try {
                val pdfBytes = PdfConverter.convertExcelToPdf(excelBytes) { done, total ->
                    runOnUiThread {
                        pdfProgress = "Page $done / $total ban raha hai..."
                    }
                }

                runOnUiThread {
                    pdfProgress = "PDF taiyaar ho gayi! Share ho rahi hai..."
                    shareDocument(pdfBytes, "Excel_Report.pdf", "application/pdf")
                    pdfBusy = false
                    pdfProgress = "PDF download aur share ho gayi."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    pdfError = "PDF banane me dikkat aayi: ${e.localizedMessage}"
                    pdfBusy = false
                }
            }
        }.start()
    }

    private fun processData(senderName: String, execName: String, chatText: String, fromDate: Date, toDate: Date) {
        isProcessingChat = true
        errorMsg = ""
        Thread {
            try {
                val result = ChatParser.processChat(chatText, senderName, fromDate, toDate)
                runOnUiThread {
                    rowsState.clear()
                    rowsState.addAll(result.rows)
                    processStatsState = result.stats
                    isProcessingChat = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    errorMsg = "Data process karne me error: ${e.localizedMessage}"
                    isProcessingChat = false
                }
            }
        }.start()
    }

    private fun exportExcelSheet() {
        val rows = rowsState.toList()
        if (rows.isEmpty()) {
            Toast.makeText(this, "Export karne ke liye koi data nahi hai!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val execName = rows.firstOrNull()?.bankName ?: "" // default fallback placeholder
            val bytes = ExcelExporter.exportToExcel(rows, execName)

            val monthYear = ExcelExporter.monthYearFromRows(rows)
            val baseName = monthYear?.let { "$it.xlsx" } ?: "Monthly_Report.xlsx"

            shareDocument(bytes, baseName, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        } catch (e: Exception) {
            Toast.makeText(this, "Excel generate karne me error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareDocument(bytes: ByteArray, fileName: String, mimeType: String) {
        try {
            val file = File(cacheDir, fileName)
            file.writeBytes(bytes)

            val uri = FileProvider.getUriForFile(this, "com.whatsapptoexcel.app.fileprovider", file)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "File Open / Share Karo"))
        } catch (e: Exception) {
            Toast.makeText(this, "File share karne me error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyTableToClipboard() {
        val rows = rowsState.toList()
        if (rows.isEmpty()) return

        try {
            val tabSeparated = ExcelExporter.rowsToClipboard(rows)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = android.content.ClipData.newPlainText("Excel Table Data", tabSeparated)
            clipboard.setPrimaryClip(clip)

            tableCopied = true
            Toast.makeText(this, "Table clipboard par copy ho gaya! Excel me direct paste kar sakte ho.", Toast.LENGTH_LONG).show()

            Thread {
                Thread.sleep(2000)
                runOnUiThread { tableCopied = false }
            }.start()
        } catch (e: Exception) {
            Toast.makeText(this, "Copy karne me error aayi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        name = it.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                name = name?.substring(cut + 1)
            }
        }
        return name
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                refreshGpsLocation()
            } else {
                currentGps = "" // No fallback on permission denied
                gpsAccuracy = 0f
                Toast.makeText(this, "Location permission denied.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
