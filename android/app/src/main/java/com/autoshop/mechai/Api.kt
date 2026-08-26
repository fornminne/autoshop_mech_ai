package com.autoshop.mechai

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

data class Vehicle(
    val vin: String? = null,
    val year: Int? = null,
    val make: String? = null,
    val model: String? = null,
    val engine: String? = null,
    val trim: String? = null,
)

data class VinDecodeRequest(val vin: String)
data class DiagnosticEvidence(
    val dtcs: List<String> = emptyList(),
    val symptoms: List<String> = emptyList(),
    val freeze_frame: Map<String, Any> = emptyMap(),
    val live_data: Map<String, Any> = emptyMap(),
    val technician_notes: String = "",
)
data class DiagnosticRequest(val vehicle: Vehicle, val evidence: DiagnosticEvidence)
data class DiagnosticStep(val order: Int, val action: String, val rationale: String, val expected: String? = null)
data class DiagnosticResponse(
    val summary: String,
    val likely_causes: List<String>,
    val next_steps: List<DiagnosticStep>,
    val source_refs: List<String> = emptyList(),
    val confidence: String,
)
data class RecallResponse(val count: Int, val results: List<Map<String, String?>>)

interface AutoShopApi {
    @POST("v1/vehicles/decode")
    suspend fun decodeVin(@Body request: VinDecodeRequest): Vehicle

    @GET("v1/vehicles/recalls")
    suspend fun recalls(
        @Query("make") make: String,
        @Query("model") model: String,
        @Query("year") year: Int,
    ): RecallResponse

    @POST("v1/diagnostics/analyze")
    suspend fun diagnose(@Body request: DiagnosticRequest): DiagnosticResponse
}

object ApiClient {
    val api: AutoShopApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AutoShopApi::class.java)
    }
}
