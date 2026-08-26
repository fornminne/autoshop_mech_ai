package com.autoshop.mechai

/**
 * Best-effort parser for user-exported XTOOL CSV recordings.
 *
 * XTOOL exports vary by software/vehicle. This parser intentionally accepts both
 * long-form rows (Parameter, Value, Unit) and wide time-series rows
 * (Time, RPM, Coolant, ...). It never attempts to access XTOOL private APIs.
 */
data class XtoolImportResult(
    val scan: ObdScan,
    val sourceName: String,
    val rowCount: Int,
    val liveValueCount: Int,
)

object XtoolCsvParser {
    private val dtcRegex = Regex("\\b[PCBU][0-3][0-9A-F]{3}\\b", RegexOption.IGNORE_CASE)
    private val vinRegex = Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b", RegexOption.IGNORE_CASE)

    fun parse(text: String, sourceName: String = "XTOOL CSV"): XtoolImportResult {
        val rows = text
            .lineSequence()
            .map { parseCsvLine(it).map(String::trim) }
            .filter { row -> row.any { it.isNotBlank() } }
            .toList()

        require(rows.isNotEmpty()) { "CSV is empty" }

        val allText = rows.flatten().joinToString(" ")
        val dtcs = dtcRegex.findAll(allText)
            .map { it.value.uppercase() }
            .distinct()
            .map(::ObdCode)
            .toList()
        val vin = vinRegex.find(allText)?.value?.uppercase()

        val live = linkedMapOf<String, Any>()
        val readiness = linkedMapOf<String, Any>()
        val freeze = linkedMapOf<String, Any>()

        parseLongForm(rows, live, readiness, freeze)
        if (live.isEmpty()) parseWideForm(rows, live)

        live["_source"] = sourceName
        live["_rows"] = rows.size

        return XtoolImportResult(
            scan = ObdScan(
                vin = vin,
                dongle_model = "XTOOL D7 CSV import",
                dtcs = dtcs,
                readiness = readiness,
                freeze_frame = freeze,
                live_data = live,
            ),
            sourceName = sourceName,
            rowCount = rows.size,
            liveValueCount = live.keys.count { !it.startsWith("_") },
        )
    }

    private fun parseLongForm(
        rows: List<List<String>>,
        live: MutableMap<String, Any>,
        readiness: MutableMap<String, Any>,
        freeze: MutableMap<String, Any>,
    ) {
        val headerIndex = rows.indexOfFirst { row ->
            val normalized = row.map(::norm)
            normalized.any { it in setOf("parameter", "name", "datastream", "pid", "item") } &&
                normalized.any { it in setOf("value", "currentvalue", "reading", "data") }
        }
        if (headerIndex < 0) return

        val header = rows[headerIndex].map(::norm)
        val nameIndex = header.indexOfFirst { it in setOf("parameter", "name", "datastream", "pid", "item") }
        val valueIndex = header.indexOfFirst { it in setOf("value", "currentvalue", "reading", "data") }
        val unitIndex = header.indexOfFirst { it in setOf("unit", "units") }
        if (nameIndex < 0 || valueIndex < 0) return

        for (row in rows.drop(headerIndex + 1)) {
            val name = row.getOrNull(nameIndex)?.trim().orEmpty()
            val value = row.getOrNull(valueIndex)?.trim().orEmpty()
            if (name.isBlank() || value.isBlank()) continue
            val unit = row.getOrNull(unitIndex)?.trim().orEmpty()
            val encoded = if (unit.isBlank()) scalar(value) else "$value $unit"
            when {
                name.contains("readiness", true) || name.contains("monitor", true) -> readiness[name] = encoded
                name.contains("freeze", true) -> freeze[name] = encoded
                live.size < 120 -> live[name] = encoded
            }
        }
    }

    private fun parseWideForm(rows: List<List<String>>, live: MutableMap<String, Any>) {
        val candidate = rows.withIndex()
            .filter { it.value.size >= 3 }
            .maxByOrNull { indexed -> indexed.value.count { it.isNotBlank() } }
            ?: return
        val headerIndex = candidate.index
        val headers = candidate.value
        val valueRow = rows.drop(headerIndex + 1)
            .filter { it.size >= 2 }
            .lastOrNull { row -> row.any { it.isNotBlank() } }
            ?: return

        headers.forEachIndexed { index, rawHeader ->
            val header = rawHeader.trim()
            val value = valueRow.getOrNull(index)?.trim().orEmpty()
            if (header.isBlank() || value.isBlank()) return@forEachIndexed
            if (norm(header) in setOf("time", "timestamp", "date", "index", "no")) return@forEachIndexed
            if (live.size < 120) live[header] = scalar(value)
        }
    }

    private fun scalar(value: String): Any {
        val cleaned = value.trim()
        return cleaned.toDoubleOrNull() ?: cleaned
    }

    private fun norm(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            when {
                ch == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
            index++
        }
        result += current.toString()
        return result
    }
}
