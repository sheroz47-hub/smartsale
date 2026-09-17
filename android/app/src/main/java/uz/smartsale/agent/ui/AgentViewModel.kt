package uz.smartsale.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uz.smartsale.agent.data.PromotionEngine
import uz.smartsale.agent.data.Repository
import uz.smartsale.agent.data.SyncResult
import uz.smartsale.agent.data.db.CatalogRow
import uz.smartsale.agent.data.db.CustomerEntity
import uz.smartsale.agent.data.db.OrderEntity
import uz.smartsale.agent.data.db.OrderLineEntity
import uz.smartsale.agent.data.db.PaymentEntity
import uz.smartsale.agent.data.понятноеСообщение
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar

/** Позиция в корзине. Количество — строка: агент вводит его руками. */
data class CartLine(
    val product: CatalogRow,
    val qty: BigDecimal,
) {
    val amount: BigDecimal
        get() = qty.multiply(BigDecimal(product.price)).setScale(2, RoundingMode.HALF_UP)
}

/** Строка корзины после расчёта акций: со скидкой и суммой со скидкой. */
data class PricedLine(
    val product: CatalogRow,
    val qty: BigDecimal,
    val discountPercent: BigDecimal,
    val amount: BigDecimal,
)

/** Бонусный товар от акции «купи N — получи M»: уходит в заказ бесплатной
 *  строкой (100% скидка), агенту показывается отдельно. */
data class BonusItem(
    val productUuid: String,
    val name: String,
    val qty: BigDecimal,
    val price: String,
)

/** Документ, открытый на просмотр (заказ/оплата), с готовыми к показу строками. */
data class OpenedDoc(
    val title: String,
    val date: String,
    val state: String,
    val lines: List<DocLine>,
    val total: String,
    val note: String,
)

/** Строка открытого заказа. Числа — строками, форматирует их экран. */
data class DocLine(
    val name: String,
    val qty: String,
    val price: String,
    val discountPercent: String,
    val amount: String,
)

/** Корзина после расчёта акций движком: строки со скидками, бонусы, итог. */
data class PricedCart(
    val lines: List<PricedLine> = emptyList(),
    val bonuses: List<BonusItem> = emptyList(),
    val total: BigDecimal = BigDecimal.ZERO,
    val discount: BigDecimal = BigDecimal.ZERO,
)

class AgentViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = Repository(app)
    private val db = repository.database()

    val agentName = repository.settings.agentName
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val token = repository.settings.token
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val lastSync = repository.settings.lastSync
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val server = repository.settings.server
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val pendingCount: StateFlow<Int> = db.documents().pendingOrderCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val customers = db.customers().all()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Выбранный день маршрута, по умолчанию сегодняшний.
     *
     *  День выбирается, а не жёстко берётся из часов: агент планирует
     *  завтрашний объезд с вечера, а пропущенную точку заезжает посмотреть
     *  на следующий день. Экран, показывающий только «сегодня», для этого
     *  бесполезен.
     */
    private val _weekday = MutableStateFlow(isoWeekday())
    val weekday = _weekday.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val routeCustomers = _weekday
        .flatMapLatest { день -> db.customers().route(день) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun setWeekday(день: Int) { _weekday.value = день }

    /** Есть ли маршрут хоть на какой-нибудь день: пустой маршрут на сегодня
     *  и вовсе не заведённый маршрут — разные беды, и говорить о них надо
     *  разными словами. */
    val routeDays = db.customers().routeDays()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentOrders = db.documents().recentOrders()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentPayments = db.documents().recentPayments()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Активные вопросы аудита точки — форма осмотра (общая всем агентам). */
    val auditQuestions = repository.auditQuestions()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Открытый на просмотр документ (заказ/оплата). Экран «Отправленные»
    // открывает его двойным кликом; строки заказа подтягиваются с именами
    // товаров из каталога.
    private val _openDoc = MutableStateFlow<OpenedDoc?>(null)
    val openDoc = _openDoc.asStateFlow()

    fun openOrder(order: OrderEntity) = viewModelScope.launch {
        val строкиБД = db.documents().linesOf(order.clientUid)
        val строки = строкиБД.map { л ->
            val имя = db.catalog().product(л.productUuid)?.name ?: л.productUuid
            val кол = дробьVM(л.qty)
            val цена = дробьVM(л.price)
            val процент = дробьVM(л.discountPercent)
            val сумма = кол.multiply(цена)
                .multiply(BigDecimal(100).subtract(процент))
                .divide(BigDecimal(100))
                .setScale(2, RoundingMode.HALF_UP)
            DocLine(
                name = имя,
                qty = л.qty,
                price = л.price,
                discountPercent = л.discountPercent,
                amount = сумма.toPlainString())
        }
        _openDoc.value = OpenedDoc(
            title = if (order.serverNumber.isNotBlank())
                "Заказ № ${order.serverNumber}" else "Заказ (не отправлен)",
            date = order.date,
            state = состояниеЗаказаVM(order),
            lines = строки,
            total = order.amount,
            note = order.comment,
        )
    }

    fun openPayment(payment: PaymentEntity) {
        _openDoc.value = OpenedDoc(
            title = if (payment.serverNumber.isNotBlank())
                "Оплата № ${payment.serverNumber}" else "Оплата (не отправлена)",
            date = payment.date,
            state = if (payment.synced) "отправлена"
                else if (payment.error.isNotBlank()) "ошибка" else "в очереди",
            lines = emptyList(),
            total = payment.amount,
            note = payment.comment,
        )
    }

    fun closeDoc() { _openDoc.value = null }

    private fun состояниеЗаказаVM(з: OrderEntity): String = when {
        !з.synced && з.error.isNotBlank() -> "ошибка: ${з.error}"
        !з.synced -> "в очереди"
        з.serverStatus.isNotBlank() -> з.serverStatus
        else -> "отправлен"
    }

    private fun дробьVM(значение: String): BigDecimal =
        значение.trim().replace(",", ".").toBigDecimalOrNull() ?: BigDecimal.ZERO

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _message = MutableStateFlow("")
    val message = _message.asStateFlow()

    private val _syncResult = MutableStateFlow<SyncResult?>(null)
    val syncResult = _syncResult.asStateFlow()

    // --- корзина -------------------------------------------------------------

    private val _cart = MutableStateFlow<List<CartLine>>(emptyList())
    val cart = _cart.asStateFlow()

    private val _currentCustomer = MutableStateFlow<CustomerEntity?>(null)
    val currentCustomer = _currentCustomer.asStateFlow()

    /** Задания текущего клиента (открытые). Показываются в его карточке. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentTasks = _currentCustomer
        .flatMapLatest { клиент ->
            if (клиент == null) flowOf(emptyList())
            else db.tasks().forCustomer(клиент.uuid)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Сумма корзины без скидок — для проверок; агенту показываем итог со
     *  скидками из [pricedCart]. */
    val cartTotal: BigDecimal
        get() = _cart.value.fold(BigDecimal.ZERO) { сумма, строка -> сумма + строка.amount }

    // Акции текущего клиента, загружаются при открытии карточки. Расчёт при
    // наборе идёт по этому кэшу — без обращения к базе на каждый ввод.
    private val _promotions = MutableStateFlow<List<PromotionEngine.Promotion>>(emptyList())

    /** Корзина после расчёта акций движком: показывается агенту и уходит в
     *  заказ. Пересчитывается при каждом изменении корзины. */
    private val _pricedCart = MutableStateFlow(PricedCart())
    val pricedCart = _pricedCart.asStateFlow()

    // Имя и цена бонусного товара по виду цены клиента, разово при загрузке
    // акций. Иначе резолвили бы их из базы на каждый ввод количества.
    private var _bonusInfo: Map<String, Pair<String, String>> = emptyMap()

    init {
        // Скидки НЕ считаем на каждый ввод: агент набирает заказ, а расчёт
        // скидок и бонусов запускает кнопкой по окончании (рассчитатьСкидки).
        // Любое изменение корзины сбрасывает прошлый расчёт — чтобы на экране
        // не осталась устаревшая скидка от прежнего состава.
        viewModelScope.launch { _cart.collect { _pricedCart.value = PricedCart() } }
    }

    /** Явный расчёт скидок и бонусов — по кнопке, когда заказ набран. */
    fun рассчитатьСкидки() = пересчитатьЦены(_cart.value)

    fun openCustomer(uuid: String) = viewModelScope.launch {
        _currentCustomer.value = db.customers().byUuid(uuid)
        _cart.value = emptyList()
        val акции = repository.promotionsForEngine()
        _promotions.value = акции
        _bonusInfo = загрузитьБонусы(акции)
        // Явный пересчёт: акции грузятся после очистки корзины, а изменения
        // корзины (пустой) уже отработали по пустым акциям.
        пересчитатьЦены(_cart.value)
    }

    /** Имя и цена бонусных товаров акций — разово, чтобы не дёргать базу на
     *  каждый пересчёт корзины. */
    private suspend fun загрузитьБонусы(
        акции: List<PromotionEngine.Promotion>,
    ): Map<String, Pair<String, String>> {
        val видЦены = _currentCustomer.value?.priceTypeUuid.orEmpty()
        val товары = акции
            .filter { it.mechanic == "bonus" && it.bonusProductUuid.isNotEmpty() }
            .map { it.bonusProductUuid }
            .distinct()
        val карта = mutableMapOf<String, Pair<String, String>>()
        for (uuid in товары) {
            val имя = repository.productName(uuid)
            val цена = if (видЦены.isNotEmpty())
                repository.priceOf(uuid, видЦены) ?: "0" else "0"
            карта[uuid] = имя to цена
        }
        return карта
    }

    /** Прогнать корзину через движок акций и сложить оценённую корзину. */
    private fun пересчитатьЦены(корзина: List<CartLine>) {
        if (корзина.isEmpty()) {
            _pricedCart.value = PricedCart()
            return
        }
        val строкиДвижка = корзина.map {
            PromotionEngine.Line(
                productUuid = it.product.uuid,
                categoryUuid = it.product.categoryUuid,
                price = цена(it.product.price),
                qty = it.qty,
            )
        }
        val итог = PromotionEngine.apply(строкиДвижка, _promotions.value, Repository.today())

        var скидкаВсего = BigDecimal.ZERO
        val строки = корзина.map { позиция ->
            val процент = итог.lineDiscounts[позиция.product.uuid]?.discountPercent
                ?: BigDecimal.ZERO
            // Брутто округляем той же мерой, что и сумму со скидкой, иначе
            // разница даёт «шумовую» копейку скидки там, где акции нет.
            val брутто = позиция.qty.multiply(цена(позиция.product.price))
                .setScale(2, RoundingMode.HALF_UP)
            val сумма = брутто.multiply(BigDecimal(100).subtract(процент))
                .divide(BigDecimal(100))
                .setScale(2, RoundingMode.HALF_UP)
            скидкаВсего += брутто - сумма
            PricedLine(позиция.product, позиция.qty, процент, сумма)
        }

        val бонусы = итог.bonuses.map { бонус ->
            val инфо = _bonusInfo[бонус.productUuid]
            BonusItem(
                productUuid = бонус.productUuid,
                name = инфо?.first ?: бонус.productUuid,
                qty = бонус.qty,
                price = инфо?.second ?: "0",
            )
        }

        val всего = строки.fold(BigDecimal.ZERO) { s, л -> s + л.amount }
        _pricedCart.value = PricedCart(строки, бонусы, всего, скидкаВсего)
    }

    /** Цена из строки каталога в BigDecimal; пустая/битая → 0, чтобы набор не
     *  падал на кривой цене (у акций разбор такой же безопасный). */
    private fun цена(значение: String): BigDecimal =
        значение.trim().replace(",", ".").toBigDecimalOrNull() ?: BigDecimal.ZERO

    fun putInCart(product: CatalogRow, qty: BigDecimal) {
        val текущие = _cart.value.toMutableList()
        val индекс = текущие.indexOfFirst { it.product.uuid == product.uuid }
        when {
            // Нулевое количество означает «убрать позицию»: отдельная кнопка
            // удаления на телефоне попадает мимо чаще, чем ввод нуля.
            qty <= BigDecimal.ZERO && индекс >= 0 -> текущие.removeAt(индекс)
            qty <= BigDecimal.ZERO -> Unit
            индекс >= 0 -> текущие[индекс] = текущие[индекс].copy(qty = qty)
            else -> текущие.add(CartLine(product, qty))
        }
        _cart.value = текущие
    }

    fun qtyOf(uuid: String): BigDecimal =
        _cart.value.firstOrNull { it.product.uuid == uuid }?.qty ?: BigDecimal.ZERO

    fun catalog(query: String) = db.catalog().catalog(
        priceTypeUuid = _currentCustomer.value?.priceTypeUuid.orEmpty(),
        query = query.trim(),
    )

    // --- действия ------------------------------------------------------------

    fun login(server: String, login: String, password: String) = viewModelScope.launch {
        _busy.value = true
        val итог = repository.login(server, login, password, ВЕРСИЯ)
        _busy.value = false
        итог.onSuccess {
            _message.value = "Здравствуйте, $it"
            sync()
        }.onFailure {
            _message.value = уточнить(it)
        }
    }

    fun logout() = viewModelScope.launch {
        repository.logout()
        _cart.value = emptyList()
        _currentCustomer.value = null
    }

    fun sync() = viewModelScope.launch {
        _busy.value = true
        val итог = repository.sync()
        _busy.value = false
        _syncResult.value = итог
        _message.value = итог.message
    }

    fun saveOrder(paymentType: String, comment: String, onDone: () -> Unit) =
        viewModelScope.launch {
            val клиент = _currentCustomer.value ?: return@launch
            if (_cart.value.isEmpty()) {
                _message.value = "Заказ пуст"
                return@launch
            }
            if (клиент.blocked) {
                // Сервер это тоже проверит, но узнать об отказе агент должен
                // здесь, стоя в точке, а не завтра при обмене.
                _message.value = "Клиент в стопе: ${клиент.blockedReason}"
                return@launch
            }
            if (!клиент.hasContract) {
                // Без договора УТ заказ не примет (расчёты по договорам) —
                // отсекаем в точке, а не отказом на следующий день.
                _message.value = "У клиента нет договора — заказ оформить нельзя. " +
                    "Сообщите в офис."
                return@launch
            }

            // Пересчитываем перед сохранением: последний ввод количества мог
            // не успеть отразиться в _pricedCart (пересчёт идёт асинхронно на
            // изменение корзины).
            пересчитатьЦены(_cart.value)
            val цены = _pricedCart.value

            val строки = цены.lines.map {
                OrderLineEntity(
                    orderUid = "",
                    productUuid = it.product.uuid,
                    qty = it.qty.toPlainString(),
                    price = it.product.price,
                    discountPercent = it.discountPercent.toPlainString(),
                )
            } + цены.bonuses.map {
                // Бонус — бесплатная строка: цена товара со 100% скидкой. В УТ
                // ляжет строкой с полной ручной скидкой.
                OrderLineEntity(
                    orderUid = "",
                    productUuid = it.productUuid,
                    qty = it.qty.toPlainString(),
                    price = it.price,
                    discountPercent = "100",
                )
            }
            val склад = db.catalog().warehouses().firstOrNull()?.uuid

            repository.saveOrder(клиент.uuid, склад, paymentType, comment, строки, цены.total)
            _cart.value = emptyList()
            _message.value = "Заказ записан и уйдёт при первой связи"
            onDone()
            sync()
        }

    fun savePayment(amount: String, kind: String, comment: String) = viewModelScope.launch {
        val клиент = _currentCustomer.value ?: return@launch
        if (!клиент.hasContract) {
            _message.value = "У клиента нет договора — оплату оформить нельзя."
            return@launch
        }
        val сумма = amount.replace(" ", "").replace(",", ".").toBigDecimalOrNull()
        if (сумма == null || сумма <= BigDecimal.ZERO) {
            _message.value = "Неверная сумма"
            return@launch
        }
        repository.savePayment(клиент.uuid, сумма, kind, comment)
        _message.value = "Оплата записана"
        sync()
    }

    fun saveVisit(result: String, comment: String, lat: Double?, lon: Double?) =
        viewModelScope.launch {
            val клиент = _currentCustomer.value ?: return@launch
            repository.saveVisit(клиент.uuid, result, comment, lat, lon)
            _message.value = "Визит отмечен"
        }

    fun completeTask(uuid: String, comment: String) = viewModelScope.launch {
        repository.completeTask(uuid, comment)
        _message.value = "Задание отмечено выполненным"
        sync()
    }

    /** Прикрепить снимок к заданию: файл полного кадра из камеры, сжатие и
     *  удаление временного файла делает Repository в фоне. */
    fun addTaskPhoto(taskUuid: String, file: File) = viewModelScope.launch {
        repository.addTaskPhoto(taskUuid, file)
        _message.value = "Фото добавлено"
    }

    /** Поток числа прикреплённых к заданию фото — для диалога отметки. */
    fun taskPhotoCount(taskUuid: String) = repository.taskPhotoCount(taskUuid)

    /** Уточнить координаты текущего клиента «по кнопке». */
    fun refineCustomerLocation(lat: Double, lon: Double) = viewModelScope.launch {
        val клиент = _currentCustomer.value ?: return@launch
        repository.refineLocation(клиент.uuid, lat, lon)
        _message.value = "Координаты уточнены, уйдут при обмене"
        sync()
    }

    /** Сохранить пройденный аудит текущего клиента. answers: uuid вопроса → ответ. */
    fun submitAudit(answers: Map<String, String>) = viewModelScope.launch {
        val клиент = _currentCustomer.value ?: return@launch
        repository.submitAudit(клиент.uuid, answers)
        _message.value = "Аудит сохранён, уйдёт при обмене"
        sync()
    }

    fun clearMessage() { _message.value = "" }

    /** Показать агенту сообщение (напр. ошибку получения координат из UI). */
    fun setMessage(text: String) { _message.value = text }

    private fun уточнить(ошибка: Throwable): String =
        if (ошибка.message.orEmpty().contains("адрес сервера")) "Укажите адрес сервера"
        else ошибка.понятноеСообщение()

    companion object {
        const val ВЕРСИЯ = "1.0.0"

        fun isoWeekday(): Int {
            val календарь = Calendar.getInstance()
            // Calendar: воскресенье = 1. На сервере ISO: понедельник = 1.
            return when (val день = календарь.get(Calendar.DAY_OF_WEEK)) {
                Calendar.SUNDAY -> 7
                else -> день - 1
            }
        }
    }
}
