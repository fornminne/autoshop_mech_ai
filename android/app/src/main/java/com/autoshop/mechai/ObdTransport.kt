package com.autoshop.mechai

import kotlinx.coroutines.flow.Flow

/**
 * Read-only OBD transport boundary. Implementations may use Bluetooth RFCOMM,
 * BLE GATT, or Wi-Fi TCP depending on the ELM327 adapter.
 */
interface ObdTransport {
    suspend fun connect(): Result<Unit>
    suspend fun disconnect()
    suspend fun command(command: String): Result<String>
    val state: Flow<ObdState>
}

enum class ObdState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class ObdSnapshot(
    val dtcs: List<String> = emptyList(),
    val freezeFrame: Map<String, String> = emptyMap(),
    val liveData: Map<String, String> = emptyMap(),
)

class Elm327Session(private val transport: ObdTransport) {
    suspend fun initialize(): Result<Unit> = runCatching {
        transport.command("ATZ").getOrThrow()
        transport.command("ATE0").getOrThrow()
        transport.command("ATL0").getOrThrow()
        transport.command("ATS0").getOrThrow()
        transport.command("ATH0").getOrThrow()
        transport.command("ATSP0").getOrThrow()
    }

    suspend fun readStoredDtcs(): Result<String> = transport.command("03")
    suspend fun readPendingDtcs(): Result<String> = transport.command("07")
    suspend fun readRpm(): Result<String> = transport.command("010C")
    suspend fun readCoolantTemp(): Result<String> = transport.command("0105")
    suspend fun readThrottle(): Result<String> = transport.command("0111")
    suspend fun readVehicleSpeed(): Result<String> = transport.command("010D")

    // Deliberately no Mode 04 clear-code or bidirectional/control commands in MVP.
}
