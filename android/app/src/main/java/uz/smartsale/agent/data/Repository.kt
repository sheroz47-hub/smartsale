package uz.smartsale.agent.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import uz.smartsale.agent.data.db.AppDatabase
import uz.smartsale.agent.data.db.AuditAnswerEntity
import uz.smartsale.agent.data.db.AuditEntity
import uz.smartsale.agent.data.db.AuditQuestionEntity
import uz.smartsale.agent.data.db.CategoryEntity
import uz.smartsale.agent.data.db.CustomerEntity
import uz.smartsale.agent.data.db.LocationEntity
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
import uz.smartsale.agent.data.db.TaskEntity
import uz.smartsale.agent.data.db.TaskPhotoEntity
import uz.smartsale.agent.data.db.VisitEntity
import uz.smartsale.agent.data.db.WarehouseEntity
import uz.smartsale.agent.data.net.ApiFactory
import uz.smartsale.agent.data.net.AuditAnswerDto
import uz.smartsale.agent.data.net.AuditDto
import uz.smartsale.agent.data.net.LocationDto
import uz.smartsale.agent.data.net.LoginRequest
import uz.smartsale.agent.data.net.OrderDto
import uz.smartsale.agent.data.net.OrderLineDto
import uz.smartsale.agent.data.net.PaymentDto
import uz.smartsale.agent.data.net.PushRequest
import uz.smartsale.agent.data.net.SmartSaleApi
import uz.smartsale.agent.data.net.TaskDoneDto
import uz.smartsale.agent.data.net.VisitDto
import java.io.ByteArrayOutputStream
import java.io.File
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
        val media = db.media()
        val заказы = documents.pendingOrders()
        val оплаты = documents.pendingPayments()
        val визиты = documents.pendingVisits()
        val задания = db.tasks().pendingCompletions()
        val локации = media.pendingLocations()
        val фото = media.pendingPhotos()
        val аудиты = db.audits().pendingAudits()

        if (заказы.isEmpty() && оплаты.isEmpty() && визиты.isEmpty()
            && задания.isEmpty() && локации.isEmpty() && фото.isEmpty()
            && аудиты.isEmpty()) {
            return SyncResult(ok = true, message = "нечего отправлять")
        }

        var отправлено = 0
        var отклонено = 0

        // JSON-документы (заказы/оплаты/визиты/задания/координаты) — одним
        // запросом. Фото уходят отдельно бинарём, поэтому JSON-часть шлём,
        // только если в ней что-то есть.
        val естьJson = заказы.isNotEmpty() || оплаты.isNotEmpty()
            || визиты.isNotEmpty() || задания.isNotEmpty() || локации.isNotEmpty()
            || аудиты.isNotEmpty()
        if (естьJson) {
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
                tasks = задания.map {
                    TaskDoneDto(it.uuid, it.doneAt, it.comment)
                },
                locations = локации.map {
                    LocationDto(it.customerUuid, it.lat, it.lon)
                },
                audits = аудиты.map { аудит ->
                    AuditDto(
                        clientUid = аудит.clientUid,
                        customerUuid = аудит.customerUuid,
                        date = аудит.date,
                        answers = db.audits().answersOf(аудит.clientUid).map {
                            AuditAnswerDto(it.questionUuid, it.value)
                        },
                    )
                },
            )

            val ответ = api().push(запрос)

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
            ответ.tasks.forEach { итог ->
                if (итог.accepted) {
                    db.tasks().markSent(итог.uuid); отправлено++
                } else {
                    db.tasks().markRejected(итог.uuid, итог.error); отклонено++
                }
            }
            ответ.locations.forEach { итог ->
                if (итог.accepted) {
                    media.markLocationSent(итог.customerUuid); отправлено++
                } else {
                    // Отказ по существу (клиент не найден/чужой/битые координаты)
                    // постоянный — снимаем с очереди.
                    media.markLocationFailed(итог.customerUuid, итог.error); отклонено++
                }
            }
            ответ.audits.forEach { итог ->
                if (итог.accepted) {
                    db.audits().markAuditSent(итог.clientUid); отправлено++
                } else {
                    db.audits().markAuditRejected(итог.clientUid, итог.error); отклонено++
                }
            }
        }

        val (отпрФото, отклФото) = uploadPendingPhotos(фото)
        отправлено += отпрФото
        отклонено += отклФото

        return SyncResult(ok = отклонено == 0, message = "", sent = отправлено,
            rejected = отклонено)
    }

    /**
     * Догрузка фотоотчётов — по одному бинарному запросу на снимок. Каждое фото
     * за себя: сбой одного (сеть, отказ) не роняет обмен и остальные снимки.
     * Успех и постоянный отказ (4xx) снимают снимок с очереди и удаляют файл;
     * временный сбой (5xx/сеть) оставляют на следующий обмен.
     */
    private suspend fun uploadPendingPhotos(
        фото: List<TaskPhotoEntity>,
    ): Pair<Int, Int> {
        if (фото.isEmpty()) return 0 to 0
        val media = db.media()
        val client = api()
        var отправлено = 0
        var отклонено = 0
        for (снимок in фото) {
            val файл = File(снимок.path)
            if (!файл.exists()) {
                // Файл пропал (очистка кэша/ручное удаление) — слать нечего.
                // Не считаем «отклонённым»: сервер тут ни при чём, незачем
                // пугать агента отчётом «сервер отклонил».
                media.markPhotoFailed(снимок.uuid, "файл снимка не найден")
                continue
            }
            try {
                val тело = файл.readBytes().toRequestBody("image/jpeg".toMediaType())
                val ответ = client.uploadTaskPhoto(снимок.taskUuid, снимок.uuid, тело)
                // Тело ответа не разбираем, но обязаны закрыть — иначе течёт
                // соединение (ResponseBody держит поток).
                ответ.body()?.close()
                ответ.errorBody()?.close()
                when {
                    ответ.isSuccessful -> {
                        media.markPhotoSent(снимок.uuid); файл.delete(); отправлено++
                    }
                    ответ.code() in 400..499 -> {
                        // Постоянный отказ (задание не найдено/чужое/битое имя):
                        // повтор не поможет, снимаем с очереди.
                        media.markPhotoFailed(снимок.uuid, "сервер отклонил (${ответ.code()})")
                        файл.delete(); отклонено++
                    }
                    // 5xx — временный, оставляем на повтор.
                    else -> Unit
                }
            } catch (_: Exception) {
                // Сеть/таймаут: оставляем снимок в очереди, повторим на
                // следующем обмене.
            }
        }
        return отправлено to отклонено
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
                        it.paymentType, it.creditLimit, it.deferralDays,
                        it.limitEnabled, it.forbidOverdue, it.blocked,
                        it.blockedReason, it.debt, it.overdue, it.overdueDays,
                        it.debtStatus, it.active, it.hasContract)
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

            if (ответ.tasks.isNotEmpty()) {
                // synced = done: если сервер уже считает задание выполненным,
                // оно улажено, повторно слать нечего. applyFromServer при этом
                // не затрёт локально выполненное, но ещё не отправленное.
                db.tasks().applyFromServer(ответ.tasks.map {
                    TaskEntity(
                        uuid = it.uuid, customerUuid = it.customerUuid,
                        date = it.date, text = it.text, active = it.active,
                        done = it.done, synced = it.done)
                })
            }

            if (ответ.auditQuestions.isNotEmpty()) {
                db.audits().upsertQuestions(ответ.auditQuestions.map {
                    AuditQuestionEntity(
                        uuid = it.uuid, text = it.text, answerType = it.answerType,
                        sortOrder = it.order, required = it.required,
                        active = it.active)
                })
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
        deliveryDate: String,
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
                deliveryDate = deliveryDate,
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

    // --- задания --------------------------------------------------------------

    /** Отметить задание выполненным (с комментарием). Уходит в УТ обменом. */
    suspend fun completeTask(uuid: String, comment: String) =
        withContext(Dispatchers.IO) {
            db.tasks().markDoneLocal(uuid, timestamp(), comment)
        }

    /** Прикрепить фото к заданию: сжать кадр и поставить в очередь отправки.
     *  [sourceFile] — временный полный кадр из камеры; декодируется, ужимается
     *  и удаляется здесь, в фоне (не на главном потоке). */
    suspend fun addTaskPhoto(taskUuid: String, sourceFile: File) =
        withContext(Dispatchers.IO) {
            val байты = сжатьJpeg(sourceFile)
            sourceFile.delete()
            if (байты == null) return@withContext
            val uid = UUID.randomUUID().toString()
            val каталог = File(context.filesDir, "photos").apply { mkdirs() }
            val файл = File(каталог, "$uid.jpg")
            файл.writeBytes(байты)
            db.media().insertPhoto(
                TaskPhotoEntity(
                    uuid = uid, taskUuid = taskUuid, path = файл.absolutePath,
                    createdAt = System.currentTimeMillis()))
        }

    /** Число прикреплённых к заданию фото — для показа агенту. */
    fun taskPhotoCount(taskUuid: String) = db.media().photoCount(taskUuid)

    /** Уточнить координаты клиента «по кнопке»: очередь на отправку в УТ. */
    suspend fun refineLocation(customerUuid: String, lat: Double, lon: Double) =
        withContext(Dispatchers.IO) {
            db.media().upsertLocation(
                LocationEntity(
                    customerUuid = customerUuid,
                    lat = lat.toString(), lon = lon.toString(),
                    createdAt = System.currentTimeMillis()))
        }

    // --- аудит точки ----------------------------------------------------------

    /** Активные вопросы аудита — форма осмотра точки. */
    fun auditQuestions() = db.audits().activeQuestions()

    /** Сохранить пройденный аудит в очередь отправки. answers: uuid вопроса →
     *  ответ (число/да-нет тоже строкой). */
    suspend fun submitAudit(customerUuid: String, answers: Map<String, String>) =
        withContext(Dispatchers.IO) {
            val uid = UUID.randomUUID().toString()
            db.audits().saveAudit(
                AuditEntity(
                    clientUid = uid, customerUuid = customerUuid, date = today(),
                    createdAt = System.currentTimeMillis()),
                answers.map { (вопрос, ответ) ->
                    AuditAnswerEntity(
                        auditUid = uid, questionUuid = вопрос, value = ответ)
                })
        }

    // --- акции ----------------------------------------------------------------

    /** Действующие акции в форме для движка: строки → BigDecimal (безопасно,
     *  пустая/битая строка → 0), состав разбит на товары и группы. */
    suspend fun promotionsForEngine(): List<PromotionEngine.Promotion> =
        withContext(Dispatchers.IO) {
            val dao = db.promotions()
            val товары = dao.allProducts().groupBy { it.promotionUuid }
            val пороги = dao.allThresholds().groupBy { it.promotionUuid }
            dao.activePromotions().map { акция ->
                val состав = товары[акция.uuid].orEmpty()
                PromotionEngine.Promotion(
                    uuid = акция.uuid, mechanic = акция.mechanic,
                    dateFrom = акция.dateFrom, dateTo = акция.dateTo,
                    segmentUuid = акция.segmentUuid, priority = акция.priority,
                    percent = дробь(акция.percent), buyQty = дробь(акция.buyQty),
                    bonusProductUuid = акция.bonusProductUuid,
                    bonusQty = дробь(акция.bonusQty),
                    productUuids = состав.filterNot { it.isGroup }
                        .map { it.productUuid }.toSet(),
                    groupUuids = состав.filter { it.isGroup }
                        .map { it.productUuid }.toSet(),
                    thresholds = пороги[акция.uuid].orEmpty().map {
                        PromotionEngine.Threshold(
                            дробь(it.minQty), дробь(it.minSum), дробь(it.percent))
                    },
                )
            }
        }

    /** Наименование товара (для показа бонусной строки агенту). */
    suspend fun productName(uuid: String): String = withContext(Dispatchers.IO) {
        db.catalog().product(uuid)?.name ?: uuid
    }

    /** Цена бонусного товара по виду цены клиента; нет цены → null. */
    suspend fun priceOf(uuid: String, priceTypeUuid: String): String? =
        withContext(Dispatchers.IO) { db.catalog().priceOf(uuid, priceTypeUuid) }

    fun database() = db

    companion object {
        private val ДЕНЬ = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val МОМЕНТ = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        fun today(): String = ДЕНЬ.format(Date())
        fun timestamp(): String = МОМЕНТ.format(Date())
    }
}

/** Число из строки сервера. Пустая/битая → 0: движку нельзя падать на
 *  кривой настройке акции. Разделитель приводим к точке. */
private fun дробь(значение: String): BigDecimal =
    значение.trim().replace(",", ".").toBigDecimalOrNull() ?: BigDecimal.ZERO

/**
 * Сжать снимок из файла в JPEG: ужать до 1600px по большей стороне, качество
 * 80, с поправкой на поворот из EXIF (иначе портретные кадры лежат боком).
 * Битый/непрочитанный файл → null. Вызывать в фоне: декодирование и энкод
 * многомегапиксельного кадра на главном потоке подвешивают интерфейс.
 */
private fun сжатьJpeg(файл: File, предел: Int = 1600, качество: Int = 80): ByteArray? {
    val границы = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(файл.absolutePath, границы)
    if (границы.outWidth <= 0 || границы.outHeight <= 0) return null

    var шаг = 1
    while (границы.outWidth / шаг > предел * 2 || границы.outHeight / шаг > предел * 2) {
        шаг *= 2
    }
    val точечное = BitmapFactory.decodeFile(
        файл.absolutePath, BitmapFactory.Options().apply { inSampleSize = шаг }
    ) ?: return null

    val масштаб = minOf(
        1f, предел.toFloat() / maxOf(точечное.width, точечное.height))
    val уменьшенное = if (масштаб < 1f) {
        Bitmap.createScaledBitmap(
            точечное, (точечное.width * масштаб).toInt(),
            (точечное.height * масштаб).toInt(), true)
    } else точечное

    val повёрнутое = применитьПоворотEXIF(файл, уменьшенное)

    val поток = ByteArrayOutputStream()
    повёрнутое.compress(Bitmap.CompressFormat.JPEG, качество, поток)
    return поток.toByteArray()
}

private fun применитьПоворотEXIF(файл: File, bitmap: Bitmap): Bitmap {
    val угол = try {
        when (ExifInterface(файл.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (_: Exception) {
        0f
    }
    if (угол == 0f) return bitmap
    val матрица = Matrix().apply { postRotate(угол) }
    return Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height, матрица, true)
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
