package com.autoshop.d7inspector

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { D7InspectorScreen(this) }
    }
}

@Composable
private fun D7InspectorScreen(activity: ComponentActivity) {
    var status by remember { mutableStateOf("Ready. No root or Developer Options required.") }
    var running by remember { mutableStateOf(false) }
    val readStorageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = if (granted) "Storage read permission granted." else "Storage permission denied. Device/package inspection will still work."
    }

    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("D7 Inspector") }) }) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("XTOOL D7 local inventory", style = MaterialTheme.typography.headlineSmall)
                Text("Collects Android/build properties, installed package metadata, XTOOL-related components, accessible storage paths, network interfaces, USB devices, and debugging-state indicators. It does not root, flash, patch, or modify XTOOL software.")

                Card(Modifier.fillMaxWidth()) {
                    Text(status, Modifier.padding(14.dp))
                }

                if (Build.VERSION.SDK_INT <= 32) {
                    Button(
                        enabled = !running,
                        onClick = { readStorageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Grant storage read access") }
                }

                Button(
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        running = true
                        status = "Collecting..."
                        Thread {
                            try {
                                val reports = Inspector.collect(activity)
                                val written = reports.map { (name, text) -> ReportWriter.write(activity, name, text) }
                                activity.runOnUiThread {
                                    status = "Done. Wrote ${written.size} reports to Downloads/D7-inspection/."
                                    running = false
                                }
                            } catch (t: Throwable) {
                                activity.runOnUiThread {
                                    status = "Collection failed: ${t::class.java.simpleName}: ${t.message ?: "unknown error"}"
                                    running = false
                                }
                            }
                        }.start()
                    }
                ) { Text(if (running) "Collecting..." else "Run inspection") }

                Text("Expected output: device.txt, debug-state.txt, packages.txt, xtool-packages.txt, storage.txt, files.txt, network.txt, usb.txt")
            }
        }
    }
}

private object Inspector {
    fun collect(context: Context): Map<String, String> = linkedMapOf(
        "device.txt" to device(context),
        "debug-state.txt" to debugState(context),
        "packages.txt" to packages(context, xtoolOnly = false),
        "xtool-packages.txt" to packages(context, xtoolOnly = true),
        "storage.txt" to storage(context),
        "files.txt" to files(),
        "network.txt" to network(),
        "usb.txt" to usb(context)
    )

    private fun header(title: String) = buildString {
        appendLine("D7 Inspector")
        appendLine(title)
        appendLine("timestamp=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())}")
        appendLine("------------------------------------------------------------")
    }

    private fun device(context: Context) = buildString {
        append(header("Device / Android build"))
        appendLine("manufacturer=${Build.MANUFACTURER}")
        appendLine("brand=${Build.BRAND}")
        appendLine("model=${Build.MODEL}")
        appendLine("device=${Build.DEVICE}")
        appendLine("product=${Build.PRODUCT}")
        appendLine("board=${Build.BOARD}")
        appendLine("hardware=${Build.HARDWARE}")
        appendLine("bootloader=${Build.BOOTLOADER}")
        appendLine("display=${Build.DISPLAY}")
        appendLine("id=${Build.ID}")
        appendLine("fingerprint=${Build.FINGERPRINT}")
        appendLine("type=${Build.TYPE}")
        appendLine("tags=${Build.TAGS}")
        appendLine("sdk=${Build.VERSION.SDK_INT}")
        appendLine("release=${Build.VERSION.RELEASE}")
        appendLine("securityPatch=${Build.VERSION.SECURITY_PATCH}")
        appendLine("supportedAbis=${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("androidId=${runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull() ?: "unavailable"}")
        appendLine()
        appendLine("getprop:")
        append(shell("/system/bin/getprop"))
    }

    private fun debugState(context: Context) = buildString {
        append(header("Debug / developer state"))
        appendLine("adb_enabled=${globalInt(context, Settings.Global.ADB_ENABLED)}")
        appendLine("development_settings_enabled=${globalInt(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)}")
        val keys = listOf(
            "ro.debuggable", "ro.secure", "ro.build.type", "ro.build.tags",
            "persist.sys.usb.config", "sys.usb.config", "ro.boot.verifiedbootstate",
            "ro.boot.flash.locked", "ro.boot.vbmeta.device_state", "ro.product.board",
            "ro.hardware", "ro.build.fingerprint"
        )
        keys.forEach { key -> appendLine("$key=${prop(key)}") }
    }

    @Suppress("DEPRECATION")
    private fun packages(context: Context, xtoolOnly: Boolean): String {
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS
        val all: List<PackageInfo> = pm.getInstalledPackages(flags)
        val selected = if (!xtoolOnly) all else all.filter { p ->
            val label = runCatching { pm.getApplicationLabel(p.applicationInfo ?: return@filter false).toString() }.getOrDefault("")
            val haystack = "${p.packageName} $label".lowercase(Locale.US)
            listOf("xtool", "diag", "obd", "vehicle", "autoscan", "scanner", "ecu").any(haystack::contains)
        }

        return buildString {
            append(header(if (xtoolOnly) "XTOOL / diagnostic package candidates" else "Installed packages"))
            appendLine("visiblePackageCount=${all.size}")
            appendLine("selectedCount=${selected.size}")
            selected.sortedBy { it.packageName }.forEach { p ->
                val ai = p.applicationInfo
                val label = runCatching { if (ai != null) pm.getApplicationLabel(ai).toString() else "" }.getOrDefault("")
                appendLine()
                appendLine("package=${p.packageName}")
                appendLine("label=$label")
                appendLine("versionName=${p.versionName ?: ""}")
                appendLine("versionCode=${if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()}")
                appendLine("sourceDir=${ai?.sourceDir ?: ""}")
                appendLine("nativeLibraryDir=${ai?.nativeLibraryDir ?: ""}")
                appendLine("dataDir=${ai?.dataDir ?: ""}")
                appendLine("systemApp=${ai?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: false}")
                appendComponents("activity", p.activities?.toList()) { it.name to it.exported }
                appendComponents("service", p.services?.toList()) { it.name to it.exported }
                appendComponents("receiver", p.receivers?.toList()) { it.name to it.exported }
                appendComponents("provider", p.providers?.toList()) { it.name to it.exported }
                p.requestedPermissions?.forEach { appendLine("permission=$it") }
            }
        }
    }

    private fun <T> StringBuilder.appendComponents(kind: String, items: List<T>?, map: (T) -> Pair<String, Boolean>) {
        items.orEmpty().forEach { item ->
            val (name, exported) = map(item)
            appendLine("$kind=$name exported=$exported")
        }
    }

    private fun storage(context: Context) = buildString {
        append(header("Storage"))
        appendLine("externalStorageState=${Environment.getExternalStorageState()}")
        @Suppress("DEPRECATION")
        appendLine("externalStorageRoot=${Environment.getExternalStorageDirectory().absolutePath}")
        appendLine("downloads=${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath}")
        context.getExternalFilesDirs(null).forEachIndexed { index, file -> appendLine("externalFiles[$index]=${file?.absolutePath ?: "null"}") }
        context.getExternalMediaDirs().forEachIndexed { index, file -> appendLine("externalMedia[$index]=${file.absolutePath}") }
    }

    @Suppress("DEPRECATION")
    private fun files(): String {
        val root = Environment.getExternalStorageDirectory()
        val interestingExt = setOf("csv", "txt", "log", "json", "xml", "db", "sqlite", "sqlite3", "pdf", "html", "zip")
        val interestingNames = listOf("xtool", "diag", "report", "record", "obd", "scan", "vehicle", "ecu", "d7")
        val hits = mutableListOf<String>()
        var visited = 0
        fun walk(dir: File, depth: Int) {
            if (depth > 6 || visited > 25000 || hits.size >= 3000) return
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            children.forEach { f ->
                if (visited++ > 25000 || hits.size >= 3000) return
                if (f.isDirectory) {
                    val n = f.name.lowercase(Locale.US)
                    if (!n.startsWith(".") && n !in setOf("cache", "obb")) walk(f, depth + 1)
                } else {
                    val n = f.name.lowercase(Locale.US)
                    val ext = f.extension.lowercase(Locale.US)
                    if (ext in interestingExt || interestingNames.any(n::contains)) {
                        hits += "${f.absolutePath}\tsize=${runCatching { f.length() }.getOrDefault(-1)}"
                    }
                }
            }
        }
        walk(root, 0)
        return buildString {
            append(header("Accessible diagnostic/report-like files"))
            appendLine("root=${root.absolutePath}")
            appendLine("visited=$visited")
            appendLine("matches=${hits.size}")
            hits.sorted().forEach(::appendLine)
        }
    }

    private fun network() = buildString {
        append(header("Network interfaces"))
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
        interfaces.sortedBy { it.name }.forEach { nif ->
            appendLine()
            appendLine("interface=${nif.name}")
            appendLine("displayName=${nif.displayName}")
            appendLine("up=${runCatching { nif.isUp }.getOrDefault(false)} loopback=${runCatching { nif.isLoopback }.getOrDefault(false)}")
            appendLine("mtu=${runCatching { nif.mtu }.getOrDefault(-1)}")
            nif.inetAddresses.toList().forEach { appendLine("address=${it.hostAddress}") }
        }
    }

    private fun usb(context: Context) = buildString {
        append(header("USB devices"))
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        appendLine("deviceCount=${manager.deviceList.size}")
        manager.deviceList.values.sortedBy { it.deviceName }.forEach { d ->
            appendLine()
            appendLine("name=${d.deviceName}")
            appendLine("vendorId=0x${d.vendorId.toString(16)} productId=0x${d.productId.toString(16)}")
            appendLine("class=${d.deviceClass} subclass=${d.deviceSubclass} protocol=${d.deviceProtocol}")
            appendLine("manufacturer=${runCatching { d.manufacturerName }.getOrNull() ?: ""}")
            appendLine("product=${runCatching { d.productName }.getOrNull() ?: ""}")
            appendLine("version=${runCatching { d.version }.getOrNull() ?: ""}")
            appendLine("interfaces=${d.interfaceCount}")
        }
    }

    private fun globalInt(context: Context, name: String): String = runCatching {
        Settings.Global.getInt(context.contentResolver, name).toString()
    }.getOrElse { "unavailable:${it::class.java.simpleName}" }

    private fun prop(key: String): String {
        val out = shell("/system/bin/getprop", key).trim()
        return if (out.isBlank()) "<blank-or-unavailable>" else out
    }

    private fun shell(vararg args: String): String = runCatching {
        ProcessBuilder(*args).redirectErrorStream(true).start().let { p ->
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            text
        }
    }.getOrElse { "<unavailable:${it::class.java.simpleName}:${it.message}>\n" }
}

private object ReportWriter {
    fun write(context: Context, name: String, text: String): String {
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/D7-inspection")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create $name")
            resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                ?: error("Unable to write $name")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.resolve("D7-inspection")
                ?: error("External files directory unavailable")
            dir.mkdirs()
            dir.resolve(name).writeText(text)
            dir.resolve(name).absolutePath
        }
    }
}
