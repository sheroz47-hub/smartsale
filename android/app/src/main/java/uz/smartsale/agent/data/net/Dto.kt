package uz.smartsale.agent.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Формат обмена с сервером.
 *
 * Все денежные величины — строки. Так они доезжают ровно такими, какими
 * ушли: JSON не различает 1.10 и 1.1, а разбор в Double добавляет свою
 * погрешность.
 */

@Serializable
data class LoginRequest(
    val login: String,
    val password: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("app_version") val appVersion: String,
    val protocol: String = "1.0",
)

@Serializable
data class LoginResponse(
    val token: String,
    val protocol: String,
    @SerialName("server_time") val serverTime: String,
    val currency: String,
    val user: UserDto,
)

@Serializable
data class UserDto(
    val uuid: String,
    @SerialName("full_name") val fullName: String,
    val login: String,
)

@Serializable
data class PullResponse(
    @SerialName("server_time") val serverTime: String,
    val protocol: String = "1.0",
    val more: Boolean = false,
    val warehouses: List<WarehouseDto> = emptyList(),
    @SerialName("price_types") val priceTypes: List<PriceTypeDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val products: List<ProductDto> = emptyList(),
    val prices: List<PriceDto> = emptyList(),
    val stocks: List<StockDto> = emptyList(),
    val customers: List<CustomerDto> = emptyList(),
    val routes: List<RouteDto> = emptyList(),
    val tasks: List<TaskDto> = emptyList(),
    @SerialName("audit_questions") val auditQuestions: List<AuditQuestionDto> = emptyList(),
    val promotions: List<PromotionDto> = emptyList(),
)

@Serializable
data class AuditQuestionDto(
    val uuid: String,
    val text: String = "",
    @SerialName("answer_type") val answerType: String = "string",
    val order: Int = 100,
    val required: Boolean = false,
    val active: Boolean = true,
)

@Serializable
data class TaskDto(
    val uuid: String,
    @SerialName("customer_uuid") val customerUuid: String? = null,
    val date: String = "",
    val text: String = "",
    val done: Boolean = false,
    val active: Boolean = true,
)

@Serializable
data class PromotionDto(
    val uuid: String,
    val name: String,
    val mechanic: String,
    @SerialName("date_from") val dateFrom: String = "",
    @SerialName("date_to") val dateTo: String = "",
    @SerialName("segment_uuid") val segmentUuid: String = "",
    val priority: Int = 0,
    val percent: String = "0",
    @SerialName("buy_qty") val buyQty: String = "0",
    @SerialName("bonus_product_uuid") val bonusProductUuid: String = "",
    @SerialName("bonus_qty") val bonusQty: String = "0",
    val active: Boolean = true,
    val products: List<PromotionProductDto> = emptyList(),
    val thresholds: List<PromotionThresholdDto> = emptyList(),
)

@Serializable
data class PromotionProductDto(
    val uuid: String,
    @SerialName("is_group") val isGroup: Boolean = false,
)

@Serializable
data class PromotionThresholdDto(
    @SerialName("min_qty") val minQty: String = "0",
    @SerialName("min_sum") val minSum: String = "0",
    val percent: String = "0",
)

@Serializable
data class WarehouseDto(
    val uuid: String, val code: String, val name: String, val active: Boolean,
)

@Serializable
data class PriceTypeDto(
    val uuid: String, val code: String, val name: String,
    @SerialName("is_default") val isDefault: Boolean, val active: Boolean,
)

@Serializable
data class CategoryDto(
    val uuid: String, val name: String,
    @SerialName("parent_uuid") val parentUuid: String? = null,
    @SerialName("sort_order") val sortOrder: Int, val active: Boolean,
)

@Serializable
data class ProductDto(
    val uuid: String, val code: String, val name: String, val unit: String,
    @SerialName("package_qty") val packageQty: String,
    @SerialName("package_name") val packageName: String,
    val barcode: String,
    @SerialName("vat_rate") val vatRate: String,
    @SerialName("category_uuid") val categoryUuid: String? = null,
    @SerialName("has_image") val hasImage: Boolean = false,
    val active: Boolean,
)

@Serializable
data class PriceDto(
    @SerialName("product_uuid") val productUuid: String,
    @SerialName("price_type_uuid") val priceTypeUuid: String,
    val price: String,
)

@Serializable
data class StockDto(
    @SerialName("product_uuid") val productUuid: String,
    @SerialName("warehouse_uuid") val warehouseUuid: String,
    val free: String,
)

@Serializable
data class CustomerDto(
    val uuid: String, val code: String, val name: String,
    @SerialName("legal_name") val legalName: String,
    val inn: String, val phone: String, val address: String,
    val lat: Double? = null, val lon: Double? = null,
    @SerialName("price_type_uuid") val priceTypeUuid: String? = null,
    @SerialName("payment_type") val paymentType: String,
    @SerialName("credit_limit") val creditLimit: String,
    @SerialName("deferral_days") val deferralDays: Int,
    @SerialName("limit_enabled") val limitEnabled: Boolean = false,
    @SerialName("forbid_overdue") val forbidOverdue: Boolean = false,
    val blocked: Boolean,
    @SerialName("blocked_reason") val blockedReason: String,
    @SerialName("has_contract") val hasContract: Boolean = true,
    val debt: String, val overdue: String,
    @SerialName("overdue_days") val overdueDays: Int = 0,
    @SerialName("debt_status") val debtStatus: String = "working",
    val active: Boolean,
)

@Serializable
data class RouteDto(
    val uuid: String, val name: String, val weekday: Int, val active: Boolean,
    val stops: List<RouteStopDto> = emptyList(),
)

@Serializable
data class RouteStopDto(
    @SerialName("customer_uuid") val customerUuid: String,
    @SerialName("sort_order") val sortOrder: Int,
)

// --- отправка ---------------------------------------------------------------

@Serializable
data class PushRequest(
    val orders: List<OrderDto> = emptyList(),
    val payments: List<PaymentDto> = emptyList(),
    val visits: List<VisitDto> = emptyList(),
    val tasks: List<TaskDoneDto> = emptyList(),
    val locations: List<LocationDto> = emptyList(),
    val audits: List<AuditDto> = emptyList(),
)

@Serializable
data class TaskDoneDto(
    val uuid: String,
    @SerialName("done_at") val doneAt: String? = null,
    val comment: String = "",
)

@Serializable
data class LocationDto(
    @SerialName("customer_uuid") val customerUuid: String,
    val lat: String,
    val lon: String,
)

@Serializable
data class AuditAnswerDto(
    @SerialName("question_uuid") val questionUuid: String,
    val value: String = "",
)

@Serializable
data class AuditDto(
    @SerialName("client_uid") val clientUid: String,
    @SerialName("customer_uuid") val customerUuid: String,
    val date: String,
    val answers: List<AuditAnswerDto>,
)

@Serializable
data class OrderDto(
    @SerialName("client_uid") val clientUid: String,
    @SerialName("customer_uuid") val customerUuid: String,
    @SerialName("warehouse_uuid") val warehouseUuid: String? = null,
    val date: String,
    @SerialName("delivery_date") val deliveryDate: String? = null,
    @SerialName("payment_type") val paymentType: String,
    val comment: String = "",
    val lines: List<OrderLineDto>,
)

@Serializable
data class OrderLineDto(
    @SerialName("product_uuid") val productUuid: String,
    val qty: String,
    val price: String,
    @SerialName("discount_percent") val discountPercent: String = "0",
)

@Serializable
data class PaymentDto(
    @SerialName("client_uid") val clientUid: String,
    @SerialName("customer_uuid") val customerUuid: String,
    val date: String,
    val amount: String,
    val kind: String,
    val comment: String = "",
)

@Serializable
data class VisitDto(
    @SerialName("client_uid") val clientUid: String,
    @SerialName("customer_uuid") val customerUuid: String,
    val date: String,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val result: String = "",
    val comment: String = "",
)

@Serializable
data class PushResponse(
    val orders: List<PushResult> = emptyList(),
    val payments: List<PushResult> = emptyList(),
    val visits: List<PushResult> = emptyList(),
    val tasks: List<TaskPushResult> = emptyList(),
    val locations: List<GeoPushResult> = emptyList(),
    // Аудиты: результат keyed по client_uid — та же форма, что у заказов.
    val audits: List<PushResult> = emptyList(),
    @SerialName("server_time") val serverTime: String = "",
)

@Serializable
data class GeoPushResult(
    @SerialName("customer_uuid") val customerUuid: String,
    val status: String,
    val error: String = "",
) {
    val accepted: Boolean get() = status == "accepted"
}

@Serializable
data class TaskPushResult(
    val uuid: String,
    val status: String,
    val error: String = "",
) {
    val accepted: Boolean get() = status == "accepted"
}

@Serializable
data class PushResult(
    @SerialName("client_uid") val clientUid: String,
    val status: String,
    val number: String = "",
    val state: String = "",
    val error: String = "",
) {
    val accepted: Boolean get() = status == "accepted"
}
