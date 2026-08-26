package com.autoshop.mechai

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class XtoolShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                XtoolShareScreen(
                    sharedUri = extractSharedUri(intent),
                    onDone = { finish() },
                )
            }
        }
    }

    private fun extractSharedUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XtoolShareScreen(sharedUri: Uri?, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var parsed by remember { mutableStateOf<XtoolImportResult?>(null) }
    var repairOrders by remember { mutableStateOf<List<RepairOrder>>(emptyList()) }
    var selectedRoId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Reading shared XTOOL file…") }
    var busy by remember { mutableStateOf(true) }
    var diagnosis by remember { mutableStateOf<DiagnosticResponse?>(null) }

    LaunchedEffect(sharedUri) {
        if (sharedUri == null) {
            busy = false
            status = "No shared file was provided"
            return@LaunchedEffect
        }

        runCatching {
            val resolver = context.contentResolver
            val sourceName = resolver.query(
                sharedUri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: "XTOOL D7 CSV"

            val text = resolver.openInputStream(sharedUri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("Unable to read the shared file")

            parsed = XtoolCsvParser.parse(text, sourceName)
            repairOrders = ApiClient.api.repairOrders()
                .filter { it.status != "closed" }
                .sortedByDescending { it.ro_number ?: it.id }
            selectedRoId = repairOrders.firstOrNull()?.id
            status = if (repairOrders.isEmpty()) {
                "XTOOL file parsed, but there are no open repair orders"
            } else {
                "Choose the repair order to attach this scan"
            }
        }.onFailure {
            status = "Import error: ${it.message ?: it::class.simpleName}"
        }
        busy = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import XTOOL D7 Scan") },
                navigationIcon = {
                    TextButton(onClick = onDone) { Text("Close") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodyMedium)

            parsed?.let { import ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(import.sourceName, fontWeight = FontWeight.Bold)
                        Text("${import.rowCount} rows • ${import.liveValueCount} live values • ${import.scan.dtcs.size} DTCs")
                        import.scan.vin?.let { Text("VIN: $it") }
                        if (import.scan.dtcs.isNotEmpty()) {
                            Text("DTCs: ${import.scan.dtcs.joinToString(", ") { it.code }}")
                        }
                    }
                }
            }

            if (repairOrders.isNotEmpty()) {
                Text("Open repair orders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                repairOrders.forEach { ro ->
                    val selected = selectedRoId == ro.id
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedRoId = ro.id },
                        tonalElevation = if (selected) 6.dp else 1.dp,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RadioButton(selected = selected, onClick = { selectedRoId = ro.id })
                            Column(Modifier.weight(1f)) {
                                Text(ro.ro_number ?: "Repair order", fontWeight = FontWeight.SemiBold)
                                Text(
                                    listOfNotNull(ro.vehicle.year?.toString(), ro.vehicle.make, ro.vehicle.model)
                                        .joinToString(" "),
                                )
                                Text(ro.complaint, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && parsed != null && selectedRoId != null,
                    onClick = {
                        val import = parsed ?: return@Button
                        val ro = repairOrders.firstOrNull { it.id == selectedRoId } ?: return@Button
                        scope.launch {
                            busy = true
                            status = "Attaching XTOOL scan to ${ro.ro_number ?: "repair order"}…"
                            runCatching {
                                val scan = import.scan.copy(vin = import.scan.vin ?: ro.vehicle.vin)
                                ApiClient.api.attachObdScan(ro.id, scan)
                                diagnosis = ApiClient.api.diagnoseRepairOrder(ro.id)
                            }.onSuccess {
                                status = "Scan attached and AI diagnosis completed"
                            }.onFailure {
                                status = "Attach error: ${it.message ?: it::class.simpleName}"
                            }
                            busy = false
                        }
                    },
                ) {
                    Text("Attach & Diagnose")
                }
            }

            diagnosis?.let { result ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("AI assessment", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(result.summary)
                        if (result.likely_causes.isNotEmpty()) {
                            Text("Likely causes", fontWeight = FontWeight.SemiBold)
                            result.likely_causes.forEach { Text("• $it") }
                        }
                        if (result.next_steps.isNotEmpty()) {
                            Text("Next tests", fontWeight = FontWeight.SemiBold)
                            result.next_steps.sortedBy { it.order }.forEach { step ->
                                Text("${step.order}. ${step.action}")
                            }
                        }
                        Text("Confidence: ${result.confidence}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text("Done")
                }
            }
        }
    }
}
