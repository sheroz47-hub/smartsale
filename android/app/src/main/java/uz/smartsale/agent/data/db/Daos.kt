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

    /** Цена товара по виду цены клиента. Нужна бонусному товару акции:
     *  он уходит строкой заказа с ценой и 100% скидкой. */
    @Query("SELECT price FROM prices WHERE productUuid = :productUuid AND priceTypeUuid = :priceTypeUuid")
    suspend fun priceOf(productUuid: String, priceTypeUuid: String): String?

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

    @Query("SELECT * FROM payments ORDER BY createdAt DESC LIMIT 100")
    fun recentPayments(): Flow<List<PaymentEntity>>

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
interface TaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): TaskEntity?

    /** Обновить только поля, ведомые УТ, не трогая локальное выполнение. */
    @Query(
        """
        UPDATE tasks SET customerUuid = :customerUuid, date = :date,
                         text = :text, active = :active
        WHERE uuid = :uuid
        """
    )
    suspend fun refreshFromServer(
        uuid: String, customerUuid: String?, date: String, text: String,
        active: Boolean,
    )

    /**
     * Приём заданий с сервера. Локально выполненное, но ещё не отправленное
     * задание (done && !synced) не перезаписываем целиком — иначе отчёт агента
     * пропал бы до отправки в УТ; обновляем у него лишь поля из УТ.
     */
    @Transaction
    suspend fun applyFromServer(items: List<TaskEntity>) {
        for (t in items) {
            val местное = byUuid(t.uuid)
            if (местное != null && местное.done && !местное.synced) {
                refreshFromServer(t.uuid, t.customerUuid, t.date, t.text, t.active)
            } else {
                insert(t)
            }
        }
    }

    /** Задания к исполнению у клиента: активные и невыполненные. */
    @Query(
        """
        SELECT * FROM tasks
        WHERE customerUuid = :customerUuid AND active = 1 AND done = 0
        ORDER BY date
        """
    )
    fun forCustomer(customerUuid: String): Flow<List<TaskEntity>>

    /** Отметить выполнение локально: уйдёт в очередь отправки. */
    @Query(
        """
        UPDATE tasks SET done = 1, doneAt = :doneAt, comment = :comment,
                         synced = 0, error = '' WHERE uuid = :uuid
        """
    )
    suspend fun markDoneLocal(uuid: String, doneAt: String, comment: String)

    @Query("SELECT * FROM tasks WHERE done = 1 AND synced = 0")
    suspend fun pendingCompletions(): List<TaskEntity>

    @Query("UPDATE tasks SET synced = 1, error = '' WHERE uuid = :uuid")
    suspend fun markSent(uuid: String)

    /**
     * Отказ сервера в отметке — терминальный: ставим synced = 1, чтобы задание
     * ушло из очереди отправки (иначе pendingCompletions гоняло бы его вечно).
     *
     * У задания, в отличие от заказа, отказы сервера постоянные по существу:
     * «задание не найдено» и «назначено другому агенту» повтором не лечатся, а
     * своего задания сервер этому агенту в /pull больше не отдаёт — active =
     * false до него не доедет. Транзиентные сбои сюда не попадают: обрыв связи
     * рвётся до формирования per-task результата и ловится выше. error
     * сохраняем для разбора; done остаётся 1 — карточка клиента задание больше
     * не показывает.
     */
    @Query("UPDATE tasks SET synced = 1, error = :error WHERE uuid = :uuid")
    suspend fun markRejected(uuid: String, error: String)
}

@Dao
interface MediaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: TaskPhotoEntity)

    @Query("SELECT * FROM task_photos WHERE synced = 0 ORDER BY createdAt")
    suspend fun pendingPhotos(): List<TaskPhotoEntity>

    /** Сколько фото уже прикреплено к заданию — для показа агенту в диалоге. */
    @Query("SELECT COUNT(*) FROM task_photos WHERE taskUuid = :taskUuid")
    fun photoCount(taskUuid: String): Flow<Int>

    @Query("UPDATE task_photos SET synced = 1, error = '' WHERE uuid = :uuid")
    suspend fun markPhotoSent(uuid: String)

    /** Терминальный отказ сервера (4xx): снимаем с очереди, причину сохраняем.
     *  Сетевой сбой сюда НЕ попадает — фото остаётся synced=0 и повторится. */
    @Query("UPDATE task_photos SET synced = 1, error = :error WHERE uuid = :uuid")
    suspend fun markPhotoFailed(uuid: String, error: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocation(location: LocationEntity)

    @Query("SELECT * FROM pending_locations WHERE synced = 0")
    suspend fun pendingLocations(): List<LocationEntity>

    @Query("UPDATE pending_locations SET synced = 1, error = '' WHERE customerUuid = :uuid")
    suspend fun markLocationSent(uuid: String)

    @Query("UPDATE pending_locations SET synced = 1, error = :error WHERE customerUuid = :uuid")
    suspend fun markLocationFailed(uuid: String, error: String)
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
