package uz.smartsale.agent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProducts(items: List<ProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(items: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPriceTypes(items: List<PriceTypeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPrices(items: List<PriceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStocks(items: List<StockEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWarehouses(items: List<WarehouseEntity>)

    /**
     * Товары к продаже: активные, с ценой по виду цены клиента.
     *
     * Товар без цены в выдачу не попадает намеренно. Показать его — значит
     * дать агенту набрать позицию, которую сервер потом отклонит; узнать об
     * этом он должен не после ухода из точки.
     */
    @Query(
        """
        SELECT p.uuid, p.code, p.name, p.unit, p.packageQty, p.packageName,
               p.categoryUuid AS categoryUuid,
               pr.price AS price,
               COALESCE((SELECT SUM(CAST(s.free AS REAL)) FROM stocks s
                         WHERE s.productUuid = p.uuid), 0) AS free
        FROM products p
        JOIN prices pr ON pr.productUuid = p.uuid AND pr.priceTypeUuid = :priceTypeUuid
        WHERE p.active = 1
          AND (:query = '' OR p.name LIKE '%' || :query || '%'
                           OR p.code LIKE '%' || :query || '%'
                           OR p.barcode = :query)
        ORDER BY p.name
        """
    )
    fun catalog(priceTypeUuid: String, query: String): Flow<List<CatalogRow>>

    @Query("SELECT * FROM products WHERE uuid = :uuid")
    suspend fun product(uuid: String): ProductEntity?

    @Query("SELECT * FROM warehouses WHERE active = 1 ORDER BY name")
    suspend fun warehouses(): List<WarehouseEntity>

    @Query("SELECT uuid FROM price_types WHERE isDefault = 1 LIMIT 1")
    suspend fun defaultPriceType(): String?
}

/** Строка каталога для экрана подбора. */
data class CatalogRow(
    val uuid: String,
    val code: String,
    val name: String,
    val unit: String,
    val packageQty: String,
    val packageName: String,
    val categoryUuid: String?,
    val price: String,
    val free: Double,
)

@Dao
interface CustomerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<CustomerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoute(items: List<RouteStopEntity>)

    @Query("DELETE FROM route_stops")
    suspend fun clearRoute()

    @Query("SELECT * FROM customers WHERE active = 1 ORDER BY name")
    fun all(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): CustomerEntity?

    /** Маршрут на день недели: 1 — понедельник, 7 — воскресенье. */
    @Query(
        """
        SELECT c.* FROM customers c
        JOIN route_stops r ON r.customerUuid = c.uuid AND r.weekday = :weekday
        WHERE c.active = 1
        ORDER BY r.sortOrder, c.name
        """
    )
    fun route(weekday: Int): Flow<List<CustomerEntity>>

    /** Дни, на которые вообще заведён маршрут. Нужно, чтобы отличить
     *  «сегодня выходной» от «маршруты не настроены». */
    @Query("SELECT DISTINCT weekday FROM route_stops ORDER BY weekday")
    fun routeDays(): Flow<List<Int>>
}

@Dao
interface DocumentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: OrderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLines(lines: List<OrderLineEntity>)

    @Transaction
    suspend fun saveOrder(order: OrderEntity, lines: List<OrderLineEntity>) {
        insertOrder(order)
        insertLines(lines)
    }

    @Query("SELECT * FROM orders WHERE synced = 0 ORDER BY createdAt")
    suspend fun pendingOrders(): List<OrderEntity>

    @Query("SELECT * FROM order_lines WHERE orderUid = :orderUid")
    suspend fun linesOf(orderUid: String): List<OrderLineEntity>

    @Query("SELECT * FROM orders ORDER BY createdAt DESC LIMIT 100")
    fun recentOrders(): Flow<List<OrderEntity>>

    @Query("SELECT COUNT(*) FROM orders WHERE synced = 0")
    fun pendingOrderCount(): Flow<Int>

    @Query(
        """
        UPDATE orders SET synced = 1, serverNumber = :number, serverStatus = :status,
                          error = '' WHERE clientUid = :uid
        """
    )
    suspend fun markOrderSent(uid: String, number: String, status: String)

    @Query("UPDATE orders SET error = :error WHERE clientUid = :uid")
    suspend fun markOrderRejected(uid: String, error: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(payment: PaymentEntity)

    @Query("SELECT * FROM payments WHERE synced = 0 ORDER BY createdAt")
    suspend fun pendingPayments(): List<PaymentEntity>

    @Query("UPDATE payments SET synced = 1, serverNumber = :number, error = '' WHERE clientUid = :uid")
    suspend fun markPaymentSent(uid: String, number: String)

    @Query("UPDATE payments SET error = :error WHERE clientUid = :uid")
    suspend fun markPaymentRejected(uid: String, error: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVisit(visit: VisitEntity)

    @Query("SELECT * FROM visits WHERE synced = 0")
    suspend fun pendingVisits(): List<VisitEntity>

    @Query("UPDATE visits SET synced = 1, error = '' WHERE clientUid = :uid")
    suspend fun markVisitSent(uid: String)

    @Query("UPDATE visits SET error = :error WHERE clientUid = :uid")
    suspend fun markVisitRejected(uid: String, error: String)

    @Query("SELECT customerUuid FROM visits WHERE date = :date")
    fun visitedOn(date: String): Flow<List<String>>
}

@Dao
interface PromotionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPromotions(items: List<PromotionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(items: List<PromotionProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThresholds(items: List<PromotionThresholdEntity>)

    @Query("DELETE FROM promotion_products WHERE promotionUuid IN (:uuids)")
    suspend fun deleteProductsOf(uuids: List<String>)

    @Query("DELETE FROM promotion_thresholds WHERE promotionUuid IN (:uuids)")
    suspend fun deleteThresholdsOf(uuids: List<String>)

    /**
     * Приём пачки акций: сама акция и её состав. Состав переписывается
     * целиком — акцию проще заменить, чем сверять построчно, а объём мал.
     * Инкрементальная синхронизация присылает только изменившиеся акции,
     * поэтому чистим состав лишь у пришедших.
     */
    @Transaction
    suspend fun upsert(
        promotions: List<PromotionEntity>,
        products: List<PromotionProductEntity>,
        thresholds: List<PromotionThresholdEntity>,
    ) {
        if (promotions.isEmpty()) return
        val uuids = promotions.map { it.uuid }
        upsertPromotions(promotions)
        deleteProductsOf(uuids)
        deleteThresholdsOf(uuids)
        if (products.isNotEmpty()) insertProducts(products)
        if (thresholds.isNotEmpty()) insertThresholds(thresholds)
    }

    /** Действующие акции для движка. Неактивные (погашенные) не берём. */
    @Query("SELECT * FROM promotions WHERE active = 1")
    suspend fun activePromotions(): List<PromotionEntity>

    @Query("SELECT * FROM promotion_products")
    suspend fun allProducts(): List<PromotionProductEntity>

    @Query("SELECT * FROM promotion_thresholds")
    suspend fun allThresholds(): List<PromotionThresholdEntity>
}
