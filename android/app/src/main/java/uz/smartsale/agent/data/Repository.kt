package uz.smartsale.agent.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uz.smartsale.agent.data.db.AppDatabase
import uz.smartsale.agent.data.db.CategoryEntity
import uz.smartsale.agent.data.db.CustomerEntity
import uz.smartsale.agent.data.db.OrderEntity
import uz.smartsale.agent.data.db.OrderLineEntity
import uz.smartsale.agent.data.db.PaymentEntity
import uz.smartsale.agent.data.db.PriceEntity
import uz.smartsale.agent.data.db.PriceTypeEntity
import uz.smartsale.agent.data.db.ProductEntity
import uz.smartsale.agent.data.db.PromotionEntity
import uz.smartsale.agent.data.db.PromotionProductEntity
import uz.smartsale.agent.data.db.PromotionThresholdEntity
import uz.smartsale.agent.data.db.RouteStopEntity
import uz.smartsale.agent.data.db.StockEntity
import uz.smartsale.agent.data.db.VisitEntity
import uz.smartsale.agent.data.db.WarehouseEntity
import uz.smartsale.agent.data.net.ApiFactory
import uz.smartsale.agent.data.net.LoginRequest
import uz.smartsale.agent.data.net.OrderDto
import uz.smartsale.agent.data.net.OrderLineDto
import uz.smartsale.agent.data.net.PaymentDto
import uz.smartsale.agent.data.net.PushRequest
import uz.smartsale.agent.data.net.SmartSaleApi
import uz.smartsale.agent.data.net.VisitDto
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Итог обмена, показываемый агенту на экране синхронизации. */
data class SyncResult(
    val ok: Boolean,
    val message: String,
    val sent: Int = 0,
    val rejected: Int = 0,
    val received: Int = 0,
)

class Repository(private val context: Context) {

    private val db = AppDatabase.get(context)
    val settings = Settings(context)

    /**
     * Клиент пересоздаётся под текущий адрес сервера.
     *
     * Адрес меняется редко, но меняется — при переезде на домен или другой
     * порт. Держать его в неизменяемом синглтоне значит требовать
     * переустановки приложения ради строки настройки.
     */
    @Volatile private var кэшТокена: String? = null

    private suspend fun api(): SmartSaleApi {
        val server = settings.serverNow()
        require(server.isNotBlank()) { "не задан адрес сервера" }
        // Токен читается здесь, в suspend-контексте, и кладётся в поле.
        // Перехватчик OkHttp работает в обычном потоке и приостановиться не
        // может — читать настройки прямо в нём пришлось бы через
        // runBlocking, а это блокировка сетевого потока.
        кэшТокена = settings.tokenNow()
        return ApiFactory.create(server) { кэшТокена }
    }

    suspend fun login(server: String, login: String, password: String,
                      appVersion: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            settings.setServer(server)
            val client = ApiFactory.create(settings.serverNow()) { null }
            val ответ = client.login(
                LoginRequest(
                    login = login.trim(),
                    password = password,
                    deviceId = settings.deviceId(),
                    deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                    appVersion = appVersion,
                )
            )
            settings.signIn(ответ.token, ответ.user.fullName)
            ответ.user.fullName
        }
    }

    suspend fun logout() = settings.signOut()

    // --- обмен ---------------------------------------------------------------

    /**
     * Полный обмен: сначала отправка, потом забор.
     *
     * Порядок важен. Отправив заказы первыми, мы получим справочники уже с
     * учётом изменившегося долга клиента — агент увидит актуальную картину
     * сразу. В обратном порядке долг отставал бы на один обмен.
     */
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        runCatching {
            val отправлено = push()
            val принято = pull()
            SyncResult(
                ok = отправлено.rejected == 0,
                message = if (отправлено.rejected == 0) "Обмен завершён"
                else "Сервер отклонил документов: ${отправлено.rejected}",
                sent = отправлено.sent,
                rejected = отправлено.rejected,
                received = принято,
            )
        }.getOrElse { ошибка ->
            SyncResult(ok = false, message = ошибка.понятноеСообщение())
        }
    }

    private suspend fun push(): SyncResult {
        val documents = db.documents()
        val заказы = documents.pendingOrders()
        val оплаты = documents.pendingPayments()
        val визиты = documents.pendingVisits()

        if (заказы.isEmpty() && оплаты.isEmpty() && визиты.isEmpty()) {
            return SyncResult(ok = true, message = "нечего отправлять")
        }

        val запрос = PushRequest(
            orders = заказы.map { заказ ->
                OrderDto(
                    clientUid = заказ.clientUid,
                    customerUuid = заказ.customerUuid,
                    warehouseUuid = заказ.warehouseUuid,
                    date = заказ.date,
                    deliveryDate = заказ.deliveryDate,
                    paymentType = заказ.paymentType,
                    comment = заказ.comment,
                    lines = documents.linesOf(заказ.clientUid).map {
                        OrderLineDto(it.productUuid, it.qty, it.price, it.discountPercent)
                    },
                )
            },
            payments = оплаты.map {
                PaymentDto(it.clientUid, it.customerUuid, it.date, it.amount, it.kind, it.comment)
            },
            visits = визиты.map {
                VisitDto(it.clientUid, it.customerUuid, it.date, it.startedAt,
                    it.finishedAt, it.lat, it.lon, it.result, it.comment)
            },
        )

        val ответ = api().push(запрос)

        var отправлено = 0
        var отклонено = 0

        ответ.orders.forEach { итог ->
            if (итог.accepted) {
                documents.markOrderSent(итог.clientUid, итог.number, итог.state)
                отправлено++
            } else {
                // Отклонённый заказ не удаляется и повторно не отправляется:
                // причина не в связи, а в существе — клиент в стопе, лимит,
                // снятый товар. Решать это агенту, а не приложению.
                documents.markOrderRejected(итог.clientUid, итог.error)
                отклонено++
            }
        }
        ответ.payments.forEach { итог ->
            if (итог.accepted) {
                documents.markPaymentSent(итог.clientUid, итог.number); отправлено++
            } else {
                documents.markPaymentRejected(итог.clientUid, итог.error); отклонено++
            }
        }
        ответ.visits.forEach { итог ->
            if (итог.accepted) {
                documents.markVisitSent(итог.clientUid); отправлено++
            } else {
                documents.markVisitRejected(итог.clientUid, итог.error); отклонено++
            }
        }

        return SyncResult(ok = отклонено == 0, message = "", sent = отправлено,
            rejected = отклонено)
    }

    private suspend fun pull(): Int {
        var принято = 0
        var отсечка = settings.lastSyncNow()

        // Сервер отдаёт справочники страницами. Пока он говорит «есть ещё»,
        // забираем дальше — иначе первый обмен на большом каталоге привёз бы
        // только его начало, и агент ушёл бы в поле с половиной товаров.
        var страниц = 0
        do {
            val ответ = api().pull(отсечка)

            db.catalog().apply {
                if (ответ.warehouses.isNotEmpty()) upsertWarehouses(ответ.warehouses.map {
                    WarehouseEntity(it.uuid, it.code, it.name, it.active)
                })
                if (ответ.priceTypes.isNotEmpty()) upsertPriceTypes(ответ.priceTypes.map {
                    PriceTypeEntity(it.uuid, it.name, it.isDefault, it.active)
                })
                if (ответ.categories.isNotEmpty()) upsertCategories(ответ.categories.map {
                    CategoryEntity(it.uuid, it.name, it.parentUuid, it.sortOrder, it.active)
                })
                if (ответ.products.isNotEmpty()) upsertProducts(ответ.products.map {
                    ProductEntity(it.uuid, it.code, it.name, it.unit, it.packageQty,
                        it.packageName, it.barcode, it.vatRate, it.categoryUuid, it.active)
                })
                if (ответ.prices.isNotEmpty()) upsertPrices(ответ.prices.map {
                    PriceEntity(it.productUuid, it.priceTypeUuid, it.price)
                })
                if (ответ.stocks.isNotEmpty()) upsertStocks(ответ.stocks.map {
                    StockEntity(it.productUuid, it.warehouseUuid, it.free)
                })
            }

            if (ответ.customers.isNotEmpty()) {
                db.customers().upsert(ответ.customers.map {
                    CustomerEntity(it.uuid, it.code, it.name, it.legalName, it.inn,
                        it.phone, it.address, it.lat, it.lon, it.priceTypeUuid,
                        it.paymentType, it.creditLimit, it.deferralDays, it.blocked,
                        it.blockedReason, it.debt, it.overdue, it.active)
                })
            }

            if (ответ.routes.isNotEmpty()) {
                // Маршрут переписывается целиком: снятая точка иначе осталась
                // бы в списке навсегда — на неё просто не придёт обновление.
                db.customers().clearRoute()
                db.customers().upsertRoute(
                    ответ.routes.filter { it.active }.flatMap { маршрут ->
                        маршрут.stops.map {
                            RouteStopEntity(маршрут.weekday, it.customerUuid, it.sortOrder)
                        }
                    }
                )
            }

            if (ответ.promotions.isNotEmpty()) {
                db.promotions().upsert(
                    promotions = ответ.promotions.map {
                        PromotionEntity(it.uuid, it.name, it.mechanic, it.dateFrom,
                            it.dateTo, it.segmentUuid, it.priority, it.percent,
                            it.buyQty, it.bonusProductUuid, it.bonusQty, it.active)
                    },
                    products = ответ.promotions.flatMap { акция ->
                        акция.products.map {
                            PromotionProductEntity(
                                promotionUuid = акция.uuid,
                                productUuid = it.uuid, isGroup = it.isGroup)
                        }
                    },
                    thresholds = ответ.promotions.flatMap { акция ->
                        акция.thresholds.map {
                            PromotionThresholdEntity(
                                promotionUuid = акция.uuid,
                                minQty = it.minQty, minSum = it.minSum,
                                percent = it.percent)
                        }
                    },
                )
            }

            принято += ответ.products.size + ответ.customers.size + ответ.prices.size +
                    ответ.stocks.size
            отсечка = ответ.serverTime
            settings.setLastSync(отсечка)
            страниц++
        } while (ответ.more && страниц < 50)

        return принято
    }

    // --- создание документов --------------------------------------------------

    /**
     * Записать заказ в очередь отправки.
     *
     * Заказ считается принятым, как только лёг в базу телефона: связи в
     * точке может не быть вовсе, и держать агента до успешной отправки
     * означает не дать ему работать.
     */
    suspend fun saveOrder(
        customerUuid: String,
        warehouseUuid: String?,
        paymentType: String,
        comment: String,
        lines: List<OrderLineEntity>,
        amount: BigDecimal,
    ): String = withContext(Dispatchers.IO) {
        val uid = UUID.randomUUID().toString()
        db.documents().saveOrder(
            OrderEntity(
                clientUid = uid,
                customerUuid = customerUuid,
                warehouseUuid = warehouseUuid,
                date = today(),
                deliveryDate = null,
                paymentType = paymentType,
                comment = comment,
                amount = amount.toPlainString(),
                createdAt = System.currentTimeMillis(),
            ),
            lines.map { it.copy(orderUid = uid) },
        )
        uid
    }

    suspend fun savePayment(customerUuid: String, amount: BigDecimal, kind: String,
                            comment: String) = withContext(Dispatchers.IO) {
        db.documents().insertPayment(
            PaymentEntity(
                clientUid = UUID.randomUUID().toString(),
                customerUuid = customerUuid,
                date = today(),
                amount = amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                kind = kind,
                comment = comment,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun saveVisit(customerUuid: String, result: String, comment: String,
                          lat: Double?, lon: Double?) = withContext(Dispatchers.IO) {
        val сейчас = timestamp()
        db.documents().insertVisit(
            VisitEntity(
                clientUid = UUID.randomUUID().toString(),
                customerUuid = customerUuid,
                date = today(),
                startedAt = сейчас,
                finishedAt = сейчас,
                lat = lat, lon = lon,
                result = result, comment = comment,
            )
        )
    }

    fun database() = db

    companion object {
        private val ДЕНЬ = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val МОМЕНТ = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        fun today(): String = ДЕНЬ.format(Date())
        fun timestamp(): String = МОМЕНТ.format(Date())
    }
}

/**
 * Текст ошибки для агента.
 *
 * Агент в поле не должен читать про SocketTimeoutException: ему нужно
 * понять, ждать связи или звонить в офис.
 */
fun Throwable.понятноеСообщение(): String = when (this) {
    is java.net.UnknownHostException -> "Нет связи с сервером — проверьте интернет"
    is java.net.SocketTimeoutException -> "Сервер не ответил вовремя, попробуйте ещё раз"
    // Сначала пробуем сказать словами сервера. Свой текст по коду ответа —
    // только когда сервер промолчал: один и тот же 401 приходит и на
    // неверный пароль при входе, и на отозванный токен при обмене, а
    // догадка приложения в первом случае прямо врала агенту.
    is retrofit2.HttpException -> детальОтвета() ?: when (code()) {
        401 -> "Неверный логин или пароль либо истёк срок входа"
        403 -> "Устройство отключено, обратитесь к администратору"
        426 -> "Приложение устарело, обновите его"
        else -> "Ошибка сервера (${code()})"
    }
    else -> message ?: "Неизвестная ошибка"
}

/** Поле detail из ответа сервера: FastAPI кладёт причину отказа туда. */
private fun retrofit2.HttpException.детальОтвета(): String? = try {
    val тело = response()?.errorBody()?.string().orEmpty()
    Regex("\"detail\"\\s*:\\s*\"([^\"]*)\"")
        .find(тело)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    // Тело читается один раз и может быть уже израсходовано или не быть
    // JSON вовсе. Это не повод ронять показ ошибки.
    null
}
