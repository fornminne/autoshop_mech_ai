package com.autoshop.mechai

import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.NumberFormat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AutoShopScreen() } }
    }
}

private fun money(value: Double): String = NumberFormat.getCurrencyInstance().format(value)
private fun parseDtcs(raw: String): List<ObdCode> = raw
    .split(",", " ", "\n")
    .map { it.trim().uppercase() }
    .filter { it.isNotBlank() }
    .distinct()
    .map { ObdCode(it) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoShopScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var customer by remember { mutableStateOf<Customer?>(null) }

    var vin by remember { mutableStateOf("") }
    var vehicle by remember { mutableStateOf<Vehicle?>(null) }
    var complaint by remember { mutableStateOf("") }
    var odometer by remember { mutableStateOf("") }
    var repairOrder by remember { mutableStateOf<RepairOrder?>(null) }

    var dtcs by remember { mutableStateOf("") }
    var technicianNotes by remember { mutableStateOf("") }
    var diagnosis by remember { mutableStateOf<DiagnosticResponse?>(null) }
    var xtoolImport by remember { mutableStateOf<XtoolImportResult?>(null) }

    var lineType by remember { mutableIntStateOf(0) }
    var lineDescription by remember { mutableStateOf("") }
    var lineQty by remember { mutableStateOf("1") }
    var linePrice by remember { mutableStateOf("") }
    var lineCost by remember { mutableStateOf("") }

    var partsTax by remember { mutableStateOf("0") }
    var laborTax by remember { mutableStateOf("0") }
    var invoice by remember { mutableStateOf<Invoice?>(null) }

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }

    fun launchTask(label: String, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            status = label
            try { block() } catch (t: Throwable) {
                status = "Error: ${t.message ?: t::class.simpleName}"
            } finally { busy = false }
        }
    }

    val xtoolCsvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ro = repairOrder ?: return@rememberLauncherForActivityResult
        launchTask("Importing XTOOL D7 recording…") {
            val resolver = context.contentResolver
            val sourceName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: "XTOOL CSV"
            val text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Unable to read selected CSV")
            val parsed = XtoolCsvParser.parse(text, sourceName)
            val scan = parsed.scan.copy(vin = parsed.scan.vin ?: ro.vehicle.vin)
            repairOrder = ApiClient.api.attachObdScan(ro.id, scan)
            xtoolImport = parsed.copy(scan = scan)
            dtcs = scan.dtcs.joinToString(", ") { it.code }
            status = "XTOOL imported: ${parsed.liveValueCount} live values, ${scan.dtcs.size} DTCs"
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("AutoShop AI") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Technician workflow", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Customer → vehicle → repair order → OBD evidence → AI diagnosis → estimate → invoice")
            StatusCard(status, busy)

            SectionCard("1. Customer") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(firstName, { firstName = it }, label = { Text("First name") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(lastName, { lastName = it }, label = { Text("Last name") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && firstName.isNotBlank() && lastName.isNotBlank(), onClick = { launchTask("Creating customer…") { customer = ApiClient.api.createCustomer(CustomerCreate(firstName.trim(), lastName.trim(), email.trim().ifBlank { null })); status = "Customer created" } }) { Text(if (customer == null) "Create customer" else "Replace customer") }
                customer?.let { Text("Active: ${it.first_name} ${it.last_name}", fontWeight = FontWeight.SemiBold) }
            }

            SectionCard("2. Vehicle & complaint") {
                OutlinedTextField(vin, { vin = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(17) }, label = { Text("VIN") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && vin.length in 11..17, onClick = { launchTask("Decoding VIN…") { vehicle = ApiClient.api.decodeVin(VinDecodeRequest(vin)); status = "Vehicle loaded" } }) { Text("Decode VIN") }
                vehicle?.let { v -> ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(listOfNotNull(v.year?.toString(), v.make, v.model, v.trim).joinToString(" "), fontWeight = FontWeight.Bold); v.engine?.let { Text(it) }; v.vin?.let { Text(it, style = MaterialTheme.typography.bodySmall) } } } }
                OutlinedTextField(complaint, { complaint = it }, label = { Text("Customer complaint") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(odometer, { odometer = it.filter(Char::isDigit) }, label = { Text("Odometer in") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && customer != null && vehicle != null && complaint.isNotBlank(), onClick = { val c=customer?:return@Button; val v=vehicle?:return@Button; launchTask("Opening repair order…") { repairOrder=ApiClient.api.createRepairOrder(RepairOrderCreate(c.id,v,complaint.trim(),odometer.toIntOrNull())); xtoolImport=null; diagnosis=null; status="Repair order ${repairOrder?.ro_number ?: "created"}" } }) { Text("Open repair order") }
                repairOrder?.let { ro -> Text("${ro.ro_number ?: "RO"} • ${ro.status.replace('_',' ')} • ${money(ro.subtotal)}", fontWeight=FontWeight.SemiBold) }
            }

            SectionCard("3. OBD evidence & AI diagnosis") {
                Button(enabled=!busy && repairOrder!=null,onClick={xtoolCsvPicker.launch(arrayOf("text/csv","text/comma-separated-values","text/plain","application/octet-stream"))}) { Text("Import XTOOL D7 CSV") }
                xtoolImport?.let { imported -> Text("${imported.sourceName}: ${imported.liveValueCount} live values • ${imported.scan.dtcs.size} DTCs") }
                OutlinedTextField(dtcs,{dtcs=it.uppercase()},label={Text("DTCs")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(technicianNotes,{technicianNotes=it},label={Text("Technician observations / test results")},modifier=Modifier.fillMaxWidth(),minLines=3)
                Button(enabled=!busy && repairOrder!=null,onClick={val ro=repairOrder?:return@Button;launchTask("AI diagnostic analysis…"){repairOrder=ApiClient.api.attachObdScan(ro.id,(repairOrder?.obd_scan?:ObdScan(vin=ro.vehicle.vin,dongle_model="manual / ELM327")).copy(dtcs=parseDtcs(dtcs))); if(technicianNotes.isNotBlank()) repairOrder=ApiClient.api.patchRepairOrder(ro.id,RepairOrderPatch(technician_notes=technicianNotes.trim())); diagnosis=ApiClient.api.diagnoseRepairOrder(ro.id);repairOrder=ApiClient.api.repairOrder(ro.id);status="Diagnosis complete"}}){Text("Attach & Diagnose")}
                diagnosis?.let { DiagnosisCard(it) }
            }

            SectionCard("4. Estimate lines") {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){SmallAction("Labor",lineType!=0){lineType=0};SmallAction("Part",lineType!=1){lineType=1};SmallAction("Fee",lineType!=2){lineType=2}}
                OutlinedTextField(lineDescription,{lineDescription=it},label={Text("Description")},modifier=Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(lineQty,{lineQty=it},label={Text(if(lineType==0)"Hours" else "Qty")},modifier=Modifier.weight(1f));OutlinedTextField(linePrice,{linePrice=it},label={Text("Unit price")},modifier=Modifier.weight(1f));OutlinedTextField(lineCost,{lineCost=it},label={Text("Unit cost")},modifier=Modifier.weight(1f))}
                Button(enabled=!busy&&repairOrder!=null&&lineDescription.isNotBlank()&&lineQty.toDoubleOrNull()!=null&&linePrice.toDoubleOrNull()!=null,onClick={val ro=repairOrder?:return@Button;launchTask("Adding estimate line…"){repairOrder=ApiClient.api.addRepairOrderLine(ro.id,RepairOrderLine(line_type=lineType,description=lineDescription.trim(),quantity=lineQty.toDoubleOrNull()?:1.0,unit_price=linePrice.toDoubleOrNull()?:0.0,unit_cost=lineCost.toDoubleOrNull()));lineDescription="";lineQty="1";linePrice="";lineCost="";status="Estimate updated"}}){Text("Add to repair order")}
                repairOrder?.let { ro -> ro.lines.forEach { line -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(line.description,modifier=Modifier.weight(1f));Text(money(line.lineTotal),fontWeight=FontWeight.SemiBold)} }; HorizontalDivider(); Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Estimate subtotal",fontWeight=FontWeight.Bold);Text(money(ro.subtotal),fontWeight=FontWeight.Bold)} }
            }

            SectionCard("5. Invoice") {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(partsTax,{partsTax=it},label={Text("Parts tax %")},modifier=Modifier.weight(1f));OutlinedTextField(laborTax,{laborTax=it},label={Text("Labor tax %")},modifier=Modifier.weight(1f))}
                Button(enabled=!busy&&repairOrder?.lines?.isNotEmpty()==true,onClick={val ro=repairOrder?:return@Button;launchTask("Creating invoice…"){invoice=ApiClient.api.createInvoice(InvoiceCreate(ro.id,(partsTax.toDoubleOrNull()?:0.0)/100.0,(laborTax.toDoubleOrNull()?:0.0)/100.0));status="Invoice ${invoice?.invoice_number ?: "created"}"}}){Text("Create invoice")}
                invoice?.let { inv -> Text("${inv.invoice_number ?: "Invoice"} • ${inv.status} • ${money(inv.total)}",fontWeight=FontWeight.Bold) }
            }
        }
    }
}

@Composable private fun StatusCard(status:String,busy:Boolean){ElevatedCard(Modifier.fillMaxWidth()){Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){if(busy) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp);Text(status)}}}
@Composable private fun SectionCard(title:String,content:@Composable ColumnScope.()->Unit){ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);content()}}}
@Composable private fun SmallAction(label:String,enabled:Boolean,onClick:()->Unit){OutlinedButton(onClick=onClick,enabled=enabled){Text(label)}}
@Composable private fun DiagnosisCard(result:DiagnosticResponse){ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text("AI diagnosis",fontWeight=FontWeight.Bold);Text(result.summary);result.suggested_tests.take(5).forEach{Text("• $it")};result.safety_notes.take(3).forEach{Text("Safety: $it")}}}}
