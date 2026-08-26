package com.autoshop.mechai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AutoShopScreen() } }
    }
}

@Composable
fun AutoShopScreen() {
    val scope = rememberCoroutineScope()
    var vin by remember { mutableStateOf("") }
    var dtcs by remember { mutableStateOf("") }
    var symptoms by remember { mutableStateOf("") }
    var vehicle by remember { mutableStateOf<Vehicle?>(null) }
    var result by remember { mutableStateOf<DiagnosticResponse?>(null) }
    var status by remember { mutableStateOf("Ready") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("AutoShop Mech AI") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Vehicle", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(vin, { vin = it.uppercase() }, label = { Text("VIN") }, modifier = Modifier.fillMaxWidth())
            Button(
                enabled = vin.length in 11..17,
                onClick = {
                    scope.launch {
                        status = "Decoding VIN…"
                        runCatching { ApiClient.api.decodeVin(VinDecodeRequest(vin)) }
                            .onSuccess { vehicle = it; status = "Vehicle loaded" }
                            .onFailure { status = "VIN error: ${it.message}" }
                    }
                }
            ) { Text("Decode VIN") }

            vehicle?.let { v ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(listOfNotNull(v.year?.toString(), v.make, v.model, v.trim).joinToString(" "))
                        v.engine?.let { Text(it) }
                    }
                }
            }

            HorizontalDivider()
            Text("Diagnostic session", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(dtcs, { dtcs = it.uppercase() }, label = { Text("DTCs, comma separated") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(symptoms, { symptoms = it }, label = { Text("Symptoms / technician notes") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Button(
                enabled = vehicle != null,
                onClick = {
                    val selected = vehicle ?: return@Button
                    scope.launch {
                        status = "Analyzing…"
                        val request = DiagnosticRequest(
                            vehicle = selected,
                            evidence = DiagnosticEvidence(
                                dtcs = dtcs.split(",").map { it.trim() }.filter { it.isNotBlank() },
                                symptoms = listOf(symptoms).filter { it.isNotBlank() },
                                technician_notes = symptoms,
                            )
                        )
                        runCatching { ApiClient.api.diagnose(request) }
                            .onSuccess { result = it; status = "Analysis complete" }
                            .onFailure { status = "Diagnostic error: ${it.message}" }
                    }
                }
            ) { Text("Analyze") }

            Text(status, style = MaterialTheme.typography.bodySmall)

            result?.let { diagnosis ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(diagnosis.summary, style = MaterialTheme.typography.titleMedium)
                        Text("Likely causes")
                        diagnosis.likely_causes.forEach { Text("• $it") }
                        Text("Test plan")
                        diagnosis.next_steps.sortedBy { it.order }.forEach {
                            Text("${it.order}. ${it.action}")
                            Text(it.rationale, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Confidence: ${diagnosis.confidence}")
                    }
                }
            }
        }
    }
}
