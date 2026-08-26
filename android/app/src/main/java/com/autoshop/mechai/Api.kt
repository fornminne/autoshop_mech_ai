package com.autoshop.mechai

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

data class Vehicle(
    val id: String? = null,
    val vin: String? = null,
    val year: Int? = null,
    val make: String? = null,
    val model: String? = null,
    val engine: String? = null,
    val trim: String? = null,
    val plate: String? = null,
    val mileage: Int? = null,
    val notes: String = "",
)

data class VinDecodeRequest(val vin: String)

data class CustomerCreate(
    val first_name: String,
    val last_name: String,
    val email: String? = null,
    val phone: String? = null,
    val notes: String = "",
)

data class Customer(
    val id: String,
    val first_name: String,
    val last_name: String,
    val email: String? = null,
    val phone: String? = null,
    val notes: String = "",
    val vehicles: List<Vehicle> = emptyList(),
    val archived: Boolean = false,
)

data class ObdCode(val code: String, val description: String? = null)

data class ObdScan(
    val scan_id: String? = null,
    val vin: String? = null,
    val dongle_model: String? = null,
    val dtcs: List<ObdCode> = emptyList(),
    val readiness: Map<String, Any> = emptyMap(),
    val freeze_frame: Map<String, Any> = emptyMap(),
    val live_data: Map<String, Any> = emptyMap(),
    val mode06_failures: List<Map<String, Any>> = emptyList(),
    val created_at: String? = null,
)

data class DiagnosticEvidence(
    val dtcs: List<String> = emptyList(),
    val symptoms: List<String> = emptyList(),
    val freeze_frame: Map<String, Any> = emptyMap(),
    val live_data: Map<String, Any> = emptyMap(),
    val mode06_failures: List<Map<String, Any>> = emptyList(),
    val technician_notes: String = "",
)

data class DiagnosticRequest(val vehicle: Vehicle, val evidence: DiagnosticEvidence)
data class DiagnosticStep(val order: Int, val action: String, val rationale: String, val expected: String? = null)
data class DiagnosticResponse(
    val summary: String,
    val likely_causes: List<String>,
    val next_steps: List<DiagnosticStep>,
    val source_refs: List<String> = emptyList(),
    val confidence: String = "preliminary",
)

data class RepairOrderCreate(
    val customer_id: String,
    val vehicle: Vehicle,
    val complaint: String,
    val odometer_in: Int? = null,
)

data class RepairOrderPatch(
    val status: String? = null,
    val diagnosis_summary: String? = null,
    val technician_notes: String? = null,
    val assigned_technician: String? = null,
    val approval_status: String? = null,
)

data class RepairOrderLine(
    val id: String? = null,
    val line_type: Int,
    val description: String,
    val quantity: Double = 1.0,
    val unit_price: Double = 0.0,
    val unit_cost: Double? = null,
) {
    val lineTotal: Double get() = quantity * unit_price
}

data class RepairOrder(
    val id: String,
    val ro_number: String? = null,
    val customer_id: String,
    val vehicle: Vehicle,
    val complaint: String,
    val diagnosis_summary: String = "",
    val technician_notes: String = "",
    val odometer_in: Int? = null,
    val status: String = "intake",
    val assigned_technician: String? = null,
    val obd_scan: ObdScan? = null,
    val lines: List<RepairOrderLine> = emptyList(),
    val approval_status: String = "not_sent",
) {
    val subtotal: Double get() = lines.sumOf { it.lineTotal }
}

data class InvoiceCreate(
    val repair_order_id: String,
    val due_at: String? = null,
    val notes: String = "",
    val parts_tax_rate: Double = 0.0,
    val labor_tax_rate: Double = 0.0,
)

data class Invoice(
    val id: String,
    val invoice_number: String? = null,
    val repair_order_id: String,
    val customer_id: String,
    val customer_display_name: String = "",
    val lines: List<RepairOrderLine> = emptyList(),
    val status: Int = 0,
    val notes: String = "",
    val issued_at: String? = null,
    val due_at: String? = null,
    val paid_at: String? = null,
    val parts_tax_rate: Double = 0.0,
    val labor_tax_rate: Double = 0.0,
) {
    val partsSubtotal: Double get() = lines.filter { it.line_type == 1 }.sumOf { it.lineTotal }
    val laborSubtotal: Double get() = lines.filter { it.line_type == 0 }.sumOf { it.lineTotal }
    val feeSubtotal: Double get() = lines.filter { it.line_type == 2 }.sumOf { it.lineTotal }
    val total: Double get() = partsSubtotal + laborSubtotal + feeSubtotal +
        partsSubtotal * parts_tax_rate + laborSubtotal * labor_tax_rate
}

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

    @POST("v1/customers")
    suspend fun createCustomer(@Body request: CustomerCreate): Customer

    @GET("v1/customers")
    suspend fun customers(): List<Customer>

    @POST("v1/customers/{customerId}/vehicles")
    suspend fun addCustomerVehicle(@Path("customerId") customerId: String, @Body vehicle: Vehicle): Customer

    @POST("v1/repair-orders")
    suspend fun createRepairOrder(@Body request: RepairOrderCreate): RepairOrder

    @GET("v1/repair-orders")
    suspend fun repairOrders(): List<RepairOrder>

    @GET("v1/repair-orders/{roId}")
    suspend fun repairOrder(@Path("roId") roId: String): RepairOrder

    @PATCH("v1/repair-orders/{roId}")
    suspend fun patchRepairOrder(@Path("roId") roId: String, @Body request: RepairOrderPatch): RepairOrder

    @POST("v1/repair-orders/{roId}/lines")
    suspend fun addRepairOrderLine(@Path("roId") roId: String, @Body line: RepairOrderLine): RepairOrder

    @POST("v1/repair-orders/{roId}/obd-scan")
    suspend fun attachObdScan(@Path("roId") roId: String, @Body scan: ObdScan): RepairOrder

    @POST("v1/repair-orders/{roId}/diagnose")
    suspend fun diagnoseRepairOrder(@Path("roId") roId: String): DiagnosticResponse

    @POST("v1/invoices")
    suspend fun createInvoice(@Body request: InvoiceCreate): Invoice

    @GET("v1/invoices")
    suspend fun invoices(): List<Invoice>

    @POST("v1/invoices/{invoiceId}/status/{status}")
    suspend fun transitionInvoice(@Path("invoiceId") invoiceId: String, @Path("status") status: Int): Invoice
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
