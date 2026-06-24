package com.whatsapptoexcel.app.ui.screens

import android.app.DatePickerDialog
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import com.whatsapptoexcel.app.parser.CaseRow
import com.whatsapptoexcel.app.parser.ChatParser
import com.whatsapptoexcel.app.parser.ProcessStats
import com.whatsapptoexcel.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    currentGps: String,
    gpsAccuracy: Float,
    isGpsLoading: Boolean,
    onRefreshGps: () -> Unit,
    isProcessingChat: Boolean,
    onImportChatFile: () -> Unit,
    importedChatText: String,
    importedFileName: String,
    onChatTextChanged: (String) -> Unit,
    onImportExcelForPdf: () -> Unit,
    pdfBusy: Boolean,
    pdfProgress: String,
    pdfError: String,
    onProcessData: (senderName: String, execName: String, chatText: String, fromDate: Date, toDate: Date) -> Unit,
    rows: List<CaseRow>,
    stats: ProcessStats?,
    onRowChanged: (id: String, field: String, value: String) -> Unit,
    onRowDeleted: (id: String) -> Unit,
    onExportToExcel: () -> Unit,
    onCopyTable: () -> Unit,
    tableCopied: Boolean,
    errorMsg: String,
    setErrorMsg: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Settings State
    var senderName by remember { mutableStateOf("Chauhan") }
    var execName by remember { mutableStateOf("") }
    var settingsOpen by remember { mutableStateOf(false) }

    // Date Range State
    val calendar = Calendar.getInstance()
    var fromDate by remember { mutableStateOf<Date?>(null) }
    var toDate by remember { mutableStateOf<Date?>(null) }

    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)
    val fromDateStr = fromDate?.let { dateFormat.format(it) } ?: ""
    val toDateStr = toDate?.let { dateFormat.format(it) } ?: ""

    // Scroll state for the screen
    val scrollState = rememberScrollState()

    fun showDatePicker(isFrom: Boolean) {
        val currentSelected = if (isFrom) fromDate else toDate
        val cal = Calendar.getInstance()
        if (currentSelected != null) {
            cal.time = currentSelected
        }
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val selectedCal = Calendar.getInstance()
                selectedCal.set(year, month, dayOfMonth, 0, 0, 0)
                selectedCal.set(Calendar.MILLISECOND, 0)
                if (isFrom) {
                    fromDate = selectedCal.time
                } else {
                    toDate = selectedCal.time
                }
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 1. Live Location GPS Bar (Always visible at top)
        GpsLiveBar(
            currentGps = currentGps,
            gpsAccuracy = gpsAccuracy,
            isGpsLoading = isGpsLoading,
            onRefreshGps = onRefreshGps,
            onCopyGps = {
                if (currentGps.isNotEmpty() && currentGps != "Dhoond rahe hain...") {
                    clipboardManager.setText(AnnotatedString(currentGps))
                    Toast.makeText(context, "Location copy ho gayi!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Location abhi available nahi hai", Toast.LENGTH_SHORT).show()
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Brand Title / Header
            HeaderBlock()

            // 2. Collapsible Settings Card
            SettingsCard(
                senderName = senderName,
                onSenderNameChanged = { senderName = it },
                execName = execName,
                onExecNameChanged = { execName = it },
                isOpen = settingsOpen,
                onToggleOpen = { settingsOpen = !settingsOpen }
            )

            // 3. Step 1: Input Chat File Card
            InputChatCard(
                importedFileName = importedFileName,
                onImportFile = onImportChatFile,
                chatText = importedChatText,
                onChatTextChanged = onChatTextChanged
            )

            // 4. Step 2: Date Range Card
            DateRangeCard(
                fromDateStr = fromDateStr,
                toDateStr = toDateStr,
                onSelectFromDate = { showDatePicker(true) },
                onSelectToDate = { showDatePicker(false) }
            )

            // Error Display
            if (errorMsg.isNotEmpty()) {
                ErrorDisplay(
                    errorMsg = errorMsg,
                    onDismiss = { setErrorMsg("") }
                )
            }

            // Process Button
            Button(
                onClick = {
                    if (isProcessingChat) return@Button
                    setErrorMsg("")
                    if (importedChatText.trim().isEmpty()) {
                        setErrorMsg("Pehle WhatsApp chat ki .txt file upload karo ya text paste karo.")
                        return@Button
                    }
                    if (senderName.trim().isEmpty()) {
                        setErrorMsg("Apna WhatsApp naam daalo (jaise export me dikhta hai).")
                        return@Button
                    }
                    val fDate = fromDate
                    val tDate = toDate
                    if (fDate == null || tDate == null) {
                        setErrorMsg("Date range chuno (From aur To).")
                        return@Button
                    }
                    if (fDate.time > tDate.time) {
                        setErrorMsg("\"From\" date \"To\" date se badi hai. Sahi karo.")
                        return@Button
                    }
                    onProcessData(senderName, execName, importedChatText, fDate, tDate)
                },
                enabled = !isProcessingChat,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                if (isProcessingChat) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Processing ho raha hai...",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                } else {
                    Text(
                        text = "Data process karo",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // 5. Excel to PDF Card
            ExcelToPdfCard(
                onSelectExcel = onImportExcelForPdf,
                busy = pdfBusy,
                progress = pdfProgress,
                error = pdfError
            )

            // 6. Results Section
            if (stats != null) {
                ResultsSection(
                    rows = rows,
                    stats = stats,
                    onRowChanged = onRowChanged,
                    onRowDeleted = onRowDeleted,
                    onExportToExcel = onExportToExcel,
                    onCopyTable = onCopyTable,
                    tableCopied = tableCopied
                )
            } else {
                EmptyResultsCard()
            }
        }
    }
}

@Composable
fun GpsLiveBar(
    currentGps: String,
    gpsAccuracy: Float,
    isGpsLoading: Boolean,
    onRefreshGps: () -> Unit,
    onCopyGps: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(containerColor = DarkBlue)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "GPS",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = if (currentGps.isEmpty()) "LIVE LOCATION GPS (OFF HAI)" else "LIVE LOCATION GPS (ACTIVE)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.7f),
                        letterSpacing = 1.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (currentGps.isEmpty()) "Location Off (GPS On karein)" else currentGps,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (gpsAccuracy > 0 && currentGps.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "(Â±${gpsAccuracy.toInt()}m)",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            Row {
                IconButton(
                    onClick = onRefreshGps,
                    enabled = !isGpsLoading,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isGpsLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh GPS",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onCopyGps,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Copy GPS",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HeaderBlock() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PrimaryBlue),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Email, // Representing message/chat icon
                contentDescription = "App Icon",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = "FIELD VISIT TOOLKIT",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryBlue,
                letterSpacing = 2.sp
            )
            Text(
                text = "WhatsApp to Excel",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )
        }
    }
}

@Composable
fun SettingsCard(
    senderName: String,
    onSenderNameChanged: (String) -> Unit,
    execName: String,
    onExecNameChanged: (String) -> Unit,
    isOpen: Boolean,
    onToggleOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleOpen() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BackgroundLight),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Naam settings",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Text(
                            text = "($senderName)",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                }
                Icon(
                    imageVector = if (isOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand",
                    tint = TextMuted
                )
            }

            AnimatedVisibility(visible = isOpen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Divider(color = BorderColor)
                    Column {
                        Text(
                            text = "WhatsApp naam (sender filter)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextDark,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        OutlinedTextField(
                            value = senderName,
                            onValueChange = onSenderNameChanged,
                            placeholder = { Text("Jaise: Chauhan") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryBlue,
                                unfocusedBorderColor = BorderColor
                            )
                        )
                        Text(
                            text = "App sirf is naam ke bheje messages padhega, baaki ignore karega.",
                            fontSize = 11.sp,
                            color = TextMuted,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Field Executive Name (Excel header)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextDark,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        OutlinedTextField(
                            value = execName,
                            onValueChange = onExecNameChanged,
                            placeholder = { Text("Jaise Excel sheet me likhna ho") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryBlue,
                                unfocusedBorderColor = BorderColor
                            )
                        )
                        Text(
                            text = "Ye naam har din ki sheet ke upar \"FIELD EXECUTIVE NAME\" me chhapega.",
                            fontSize = 11.sp,
                            color = TextMuted,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InputChatCard(
    importedFileName: String,
    onImportFile: () -> Unit,
    chatText: String,
    onChatTextChanged: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(PrimaryBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "1",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Chat file daalo",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(BackgroundLight)
                    .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                    .clickable { onImportFile() }
                    .padding(vertical = 18.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add, // Standard representation of upload/import
                        contentDescription = "Upload",
                        tint = PrimaryBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (importedFileName.isNotEmpty()) importedFileName else "WhatsApp export (.txt) upload karo",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column {
                Text(
                    text = "Chat text",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextDark,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                val displayText = if (importedFileName.isNotEmpty() && chatText.length > 3000) {
                    chatText.substring(0, 3000) + "\n\n... [Truncated for performance. Full chat will be processed!]"
                } else {
                    chatText
                }
                OutlinedTextField(
                    value = displayText,
                    onValueChange = { 
                        if (importedFileName.isEmpty()) {
                            onChatTextChanged(it)
                        }
                    },
                    readOnly = importedFileName.isNotEmpty(),
                    placeholder = {
                        Text(
                            "01/01/2024, 10:35 - Chauhan: B.V (Aadhar fin.)\n1)Applicat name :- Parasantbhai Haribhai savani\n...",
                            fontSize = 12.sp
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryBlue,
                        unfocusedBorderColor = BorderColor
                    )
                )
            }
        }
    }
}

@Composable
fun DateRangeCard(
    fromDateStr: String,
    toDateStr: String,
    onSelectFromDate: () -> Unit,
    onSelectToDate: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(PrimaryBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "2",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Date range chuno",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "From",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMuted,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                            .clickable { onSelectFromDate() }
                            .padding(horizontal = 12.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = if (fromDateStr.isNotEmpty()) fromDateStr else "Select date",
                            fontSize = 14.sp,
                            color = if (fromDateStr.isNotEmpty()) TextDark else TextMuted
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "To",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMuted,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                            .clickable { onSelectToDate() }
                            .padding(horizontal = 12.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = if (toDateStr.isNotEmpty()) toDateStr else "Select date",
                            fontSize = 14.sp,
                            color = if (toDateStr.isNotEmpty()) TextDark else TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExcelToPdfCard(
    onSelectExcel: () -> Unit,
    busy: Boolean,
    progress: String,
    error: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Send, // Representing sheet/pdf conversion
                        contentDescription = "Excel to PDF",
                        tint = PrimaryBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Excel se PDF banao",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )
            }

            Text(
                text = "Excel upload karo â€” har sheet ek landscape page par (A1:I24), saare rows aur columns ek page me fit, aur page number top-right corner me. Single PDF download aur share hogi.",
                fontSize = 12.sp,
                color = TextMuted,
                lineHeight = 18.sp
            )

            Button(
                onClick = onSelectExcel,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = PrimaryBlue),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        color = PrimaryBlue,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Ban rahi hai...", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Convert",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Excel upload karte PDF banao", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            if (progress.isNotEmpty() && error.isEmpty()) {
                Text(
                    text = progress,
                    fontSize = 12.sp,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (error.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DestructiveBg)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Error",
                        tint = DestructiveRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = error,
                        fontSize = 11.sp,
                        color = DestructiveRed,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyResultsCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(BackgroundLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "No data",
                    tint = TextMuted,
                    modifier = Modifier.size(28.dp)
                )
            }
            Text(
                text = "Result yahan dikhega",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )
            Text(
                text = "Left side chat daalo, date range chuno aur \"Data process karo\" dabao â€” extract hua data yahan editable table me aayega.",
                fontSize = 12.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.widthIn(max = 280.dp)
            )
        }
    }
}

@Composable
fun ErrorDisplay(
    errorMsg: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DestructiveBg)
            .border(1.dp, DestructiveRed.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Error",
                tint = DestructiveRed,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = errorMsg,
                fontSize = 13.sp,
                color = DestructiveRed
            )
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = DestructiveRed,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun ResultsSection(
    rows: List<CaseRow>,
    stats: ProcessStats,
    onRowChanged: (id: String, field: String, value: String) -> Unit,
    onRowDeleted: (id: String) -> Unit,
    onExportToExcel: () -> Unit,
    onCopyTable: () -> Unit,
    tableCopied: Boolean
) {
    var visibleRowsCount by remember { mutableStateOf(50) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text(
                    text = "Result",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )
                Text(
                    text = "${stats.totalMessages} messages padhe â€¢ ${stats.fromSender} tumhare â€¢ ${stats.inRange} date range me â€¢ ${stats.validCases} valid case",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }

            if (rows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Is date range me koi valid case nahi mila. Date ya naam check karo.",
                        fontSize = 13.sp,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Table header actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${rows.size} cases mile. Niche edit kar sakte ho.",
                        fontSize = 12.sp,
                        color = TextMuted,
                        modifier = Modifier.weight(1f)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onCopyTable,
                            colors = ButtonDefaults.buttonColors(containerColor = BackgroundLight, contentColor = TextDark),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(
                                imageVector = if (tableCopied) Icons.Default.Check else Icons.Default.Share, // Copy mapping
                                contentDescription = "Copy",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = if (tableCopied) "Copied" else "Copy", fontSize = 12.sp)
                        }

                        Button(
                            onClick = onExportToExcel,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue, contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.List, // Excel layout icon representation
                                contentDescription = "Excel",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Excel", fontSize = 12.sp)
                        }
                    }
                }

                // Scrollable spreadsheet grid
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                ) {
                    val horizontalScrollState = rememberScrollState()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(horizontalScrollState)
                    ) {
                        // Table Headers
                        Row(
                            modifier = Modifier
                                .background(BackgroundLight)
                                .padding(vertical = 8.dp)
                        ) {
                            TableCell(text = "SR", width = 50.dp, isHeader = true)
                            TableCell(text = "DATE", width = 110.dp, isHeader = true)
                            TableCell(text = "BANK NAME", width = 150.dp, isHeader = true)
                            TableCell(text = "APPLICANT NAME", width = 180.dp, isHeader = true)
                            TableCell(text = "REASON", width = 120.dp, isHeader = true)
                            TableCell(text = "STATUS", width = 100.dp, isHeader = true)
                            TableCell(text = "LATLONG FROM", width = 140.dp, isHeader = true)
                            TableCell(text = "LATLONG TO", width = 140.dp, isHeader = true)
                            TableCell(text = "AREA", width = 130.dp, isHeader = true)
                            TableCell(text = "KM", width = 80.dp, isHeader = true)
                            Spacer(modifier = Modifier.width(60.dp)) // Action col placeholder
                        }

                        Divider(color = BorderColor)

                        // Table Body Rows (only take visibleRowsCount for UI rendering performance)
                        rows.take(visibleRowsCount).forEachIndexed { index, row ->
                            Row(
                                modifier = Modifier
                                    .background(if (index % 2 == 0) Color.White else BackgroundLight)
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TableCell(text = row.srNo.toString(), width = 50.dp, isCenter = true)
                                TableCell(text = row.date, width = 110.dp)

                                EditableTableCell(value = row.bankName, onValueChange = { onRowChanged(row.id, "bankName", it) }, width = 150.dp)
                                EditableTableCell(value = row.applicantName, onValueChange = { onRowChanged(row.id, "applicantName", it) }, width = 180.dp)
                                EditableTableCell(value = row.reasonForCnv, onValueChange = { onRowChanged(row.id, "reasonForCnv", it) }, width = 120.dp)
                                EditableTableCell(value = row.status, onValueChange = { onRowChanged(row.id, "status", it) }, width = 100.dp)
                                EditableTableCell(value = row.latlongFrom, onValueChange = { onRowChanged(row.id, "latlongFrom", it) }, width = 140.dp)
                                EditableTableCell(value = row.latlongTo, onValueChange = { onRowChanged(row.id, "latlongTo", it) }, width = 140.dp)
                                EditableTableCell(value = row.area, onValueChange = { onRowChanged(row.id, "area", it) }, width = 130.dp)
                                EditableTableCell(value = row.km, onValueChange = { onRowChanged(row.id, "km", it) }, width = 80.dp)

                                // Delete row action
                                Box(
                                    modifier = Modifier
                                        .width(60.dp)
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    IconButton(
                                        onClick = { onRowDeleted(row.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Row",
                                            tint = DestructiveRed,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            Divider(color = BorderColor.copy(alpha = 0.5f))
                        }
                    }
                }

                if (rows.size > visibleRowsCount) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Dhyan dein: Performance achhi rakhne ke liye abhi pehle $visibleRowsCount cases dikhaye gaye hain. Excel export me saare ${rows.size} cases aayenge.",
                        fontSize = 11.sp,
                        color = TextMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { visibleRowsCount += 100 },
                        colors = ButtonDefaults.buttonColors(containerColor = BackgroundLight, contentColor = PrimaryBlue),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(text = "Aur 100 rows dekhein (${rows.size - visibleRowsCount} bache hain)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun TableCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    isHeader: Boolean = false,
    isCenter: Boolean = false
) {
    Text(
        text = text,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 8.dp),
        fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
        fontSize = if (isHeader) 11.sp else 13.sp,
        color = if (isHeader) TextMuted else TextDark,
        textAlign = if (isHeader || isCenter) TextAlign.Center else TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditableTableCell(
    value: String,
    onValueChange: (String) -> Unit,
    width: androidx.compose.ui.unit.Dp
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 4.dp)
            .background(Color.Transparent)
            .border(0.5.dp, BorderColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 13.sp,
            color = TextDark,
            fontFamily = FontFamily.SansSerif
        )
    )
}
