package uz.smartsale.agent.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Локальная база агента.
 *
 * Справочники — зеркало сервера, ключ везде серверный uuid: локальные
 * автономера при повторной загрузке разъезжались бы со ссылками.
 *
 * Деньги и количества хранятся строками и считаются через BigDecimal.
 * Double здесь недопустим: цена 15000.1 превращается в 15000.099999999999,
 * и сумма накладной из тридцати строк расходится с серверной на копейки —
 * агент видит одно, клиент в накладной другое.
 */

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val uuid: String,
    val code: String,
    val name: String,
    val unit: String,
    val packageQty: String,
    val packageName: String,
    val barcode: String,
    val vatRate: String,
    val categoryUuid: String?,
    val active: Boolean,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    val parentUuid: String?,
    val sortOrder: Int,
    val active: Boolean,
)

@Entity(tableName = "price_types")
data class PriceTypeEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    val isDefault: Boolean,
    val active: Boolean,
)

@Entity(tableName = "prices", primaryKeys = ["productUuid", "priceTypeUuid"])
data class PriceEntity(
    val productUuid: String,
    val priceTypeUuid: String,
    val price: String,
)

@Entity(tableName = "stocks", primaryKeys = ["productUuid", "warehouseUuid"])
data class StockEntity(
    val productUuid: String,
    val warehouseUuid: String,
    val free: String,
)

@Entity(tableName = "warehouses")
data class WarehouseEntity(
    @PrimaryKey val uuid: String,
    val code: String,
    val name: String,
    val active: Boolean,
)

@Entity(tableName = "customers")
data class CustomerEntity(
    @PrimaryKey val uuid: String,
    val code: String,
    val name: String,
    val legalName: String,
    val inn: String,
    val phone: String,
    val address: String,
    val lat: Double?,
    val lon: Double?,
    val priceTypeUuid: String?,
    val paymentType: String,
    val creditLimit: String,
    val deferralDays: Int,
    val blocked: Boolean,
    val blockedReason: String,
    /** Долг и просрочка приходят с сервера посчитанными: считать их на
     *  телефоне не по чему — отгрузок и оплат других агентов он не видит. */
    val debt: String,
    val overdue: String,
    val active: Boolean,
)

@Entity(tableName = "route_stops", primaryKeys = ["weekday", "customerUuid"])
data class RouteStopEntity(
    val weekday: Int,
    val customerUuid: String,
    val sortOrder: Int,
)

/**
 * Документ, созданный на телефоне.
 *
 * [clientUid] генерируется здесь и никогда не меняется — это ключ, по
 * которому сервер отличает повтор отправки от нового документа. [synced]
 * поднимается только после подтверждения сервера: пока его нет, документ
 * остаётся в очереди, сколько бы раз приложение ни перезапустили.
 *
 * [error] хранит отказ сервера. Отклонённый документ не удаляется молча:
 * агент должен увидеть, почему заказ не принят, и решить, что делать.
 */
@Entity(tableName = "orders", indices = [Index("customerUuid"), Index("synced")])
data class OrderEntity(
    @PrimaryKey val clientUid: String,
    val customerUuid: String,
    val warehouseUuid: String?,
    val date: String,
    val deliveryDate: String?,
    val paymentType: String,
    val comment: String,
    val amount: String,
    val createdAt: Long,
    val synced: Boolean = false,
    val serverNumber: String = "",
    val serverStatus: String = "",
    val error: String = "",
)

@Entity(tableName = "order_lines", indices = [Index("orderUid")])
data class OrderLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderUid: String,
    val productUuid: String,
    val qty: String,
    val price: String,
    val discountPercent: String,
)

@Entity(tableName = "payments", indices = [Index("synced")])
data class PaymentEntity(
    @PrimaryKey val clientUid: String,
    val customerUuid: String,
    val date: String,
    val amount: String,
    val kind: String,
    val comment: String,
    val createdAt: Long,
    val synced: Boolean = false,
    val serverNumber: String = "",
    val error: String = "",
)

/**
 * Акция — условие скидки, настроенное в УТ и принятое с сервера.
 *
 * Считает акции движок приложения при наборе заказа (PromotionEngine); в УТ
 * результат уходит ручной скидкой в строке заказа. Механика [mechanic]:
 * percent — процент на товары; volume — ступенчатая скидка от объёма; bonus —
 * купи N — получи M бесплатно. Товары акции и пороги — в отдельных таблицах.
 *
 * Погашенная в УТ акция приходит с [active] = false: движок её пропускает, но
 * запись остаётся, пока сервер не перестанет её слать (иначе снятая акция
 * продолжала бы действовать на телефоне).
 */
@Entity(tableName = "promotions")
data class PromotionEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    val mechanic: String,
    val dateFrom: String,
    val dateTo: String,
    val segmentUuid: String,
    val priority: Int,
    val percent: String,
    val buyQty: String,
    val bonusProductUuid: String,
    val bonusQty: String,
    val active: Boolean,
)

@Entity(tableName = "promotion_products", indices = [Index("promotionUuid")])
data class PromotionProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val promotionUuid: String,
    /** uuid товара ИЛИ группы номенклатуры (см. isGroup). */
    val productUuid: String,
    val isGroup: Boolean,
)

@Entity(tableName = "promotion_thresholds", indices = [Index("promotionUuid")])
data class PromotionThresholdEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val promotionUuid: String,
    val minQty: String,
    val minSum: String,
    val percent: String,
)

@Entity(tableName = "visits", indices = [Index("synced")])
data class VisitEntity(
    @PrimaryKey val clientUid: String,
    val customerUuid: String,
    val date: String,
    val startedAt: String?,
    val finishedAt: String?,
    val lat: Double?,
    val lon: Double?,
    val result: String,
    val comment: String,
    val synced: Boolean = false,
    val error: String = "",
)
