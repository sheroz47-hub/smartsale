package uz.smartsale.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uz.smartsale.agent.data.Repository
import uz.smartsale.agent.data.SyncResult
import uz.smartsale.agent.data.db.CatalogRow
import uz.smartsale.agent.data.db.CustomerEntity
import uz.smartsale.agent.data.db.OrderLineEntity
import uz.smartsale.agent.data.понятноеСообщение
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

    /** Маршрут на сегодня. Calendar отдаёт воскресенье первым, приводим к ISO. */
    val todayRoute = db.customers().route(isoWeekday())
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentOrders = db.documents().recentOrders()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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

    val cartTotal: BigDecimal
        get() = _cart.value.fold(BigDecimal.ZERO) { сумма, строка -> сумма + строка.amount }

    fun openCustomer(uuid: String) = viewModelScope.launch {
        _currentCustomer.value = db.customers().byUuid(uuid)
        _cart.value = emptyList()
    }

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

            val строки = _cart.value.map {
                OrderLineEntity(
                    orderUid = "",
                    productUuid = it.product.uuid,
                    qty = it.qty.toPlainString(),
                    price = it.product.price,
                    discountPercent = "0",
                )
            }
            val склад = db.catalog().warehouses().firstOrNull()?.uuid

            repository.saveOrder(клиент.uuid, склад, paymentType, comment, строки, cartTotal)
            _cart.value = emptyList()
            _message.value = "Заказ записан и уйдёт при первой связи"
            onDone()
            sync()
        }

    fun savePayment(amount: String, kind: String, comment: String) = viewModelScope.launch {
        val клиент = _currentCustomer.value ?: return@launch
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

    fun clearMessage() { _message.value = "" }

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
