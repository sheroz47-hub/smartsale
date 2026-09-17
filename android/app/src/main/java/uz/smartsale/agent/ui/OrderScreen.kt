package uz.smartsale.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import uz.smartsale.agent.data.db.CatalogRow
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Подбор товара и корзина на одном экране.
 *
 * Разделять их не нужно: агент стоит у прилавка и набирает позиции подряд,
 * а сумма заказа должна быть видна всё время — по ней клиент решает, брать
 * ли ещё.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderScreen(vm: AgentViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    var поиск by remember { mutableStateOf("") }
    var примечание by remember { mutableStateOf("") }
    var показатьПроверку by remember { mutableStateOf(false) }
    // Дата предполагаемой отгрузки: по умолчанию завтра, агент может сдвинуть
    // на более поздний день (раньше завтра нельзя), пустой быть не может.
    var миллисОтгрузки by remember { mutableStateOf(началоДняUTC(1)) }
    var показатьКалендарь by remember { mutableStateOf(false) }
    val датаОтгрузки = миллисВдату(миллисОтгрузки)
    val клиент by vm.currentCustomer.collectAsState()
    val корзина by vm.cart.collectAsState()
    val цены by vm.pricedCart.collectAsState()

    val поток: Flow<List<CatalogRow>> = remember(поиск, клиент?.uuid) { vm.catalog(поиск) }
    val товары by поток.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp)) {
            Text(клиент?.name.orEmpty(), fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = поиск, onValueChange = { поиск = it },
                label = { Text("Поиск товара") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }

        if (товары.isEmpty()) {
            Text(
                if (клиент?.priceTypeUuid.isNullOrBlank())
                    "У клиента не задан вид цены — заказ оформить нельзя. " +
                        "Сообщите в офис."
                else "Ничего не найдено.",
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            items(товары, key = { it.uuid }) { товар ->
                ProductRow(товар, vm.qtyOf(товар.uuid)) { количество ->
                    vm.putInCart(товар, количество)
                }
                HorizontalDivider()
            }
        }

        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Позиций: ${корзина.size}", modifier = Modifier.weight(1f))
                    // Сумма без скидок: скидки и бонусы считаются по кнопке ниже
                    // и показываются в окне проверки перед отправкой.
                    Text(деньги(vm.cartTotal.toPlainString()),
                        fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                OutlinedTextField(
                    value = примечание, onValueChange = { примечание = it },
                    label = { Text("Комментарий к заказу") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Отгрузка: $датаОтгрузки", modifier = Modifier.weight(1f))
                    TextButton(onClick = { показатьКалендарь = true }) {
                        Text("Изменить дату")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                        Text("Отмена")
                    }
                    Button(
                        onClick = {
                            // Явный расчёт по окончании набора, затем окно
                            // проверки со скидками, бонусами и итогом.
                            vm.рассчитатьСкидки()
                            показатьПроверку = true
                        },
                        enabled = корзина.isNotEmpty(),
                        modifier = Modifier.weight(2f),
                    ) { Text("Рассчитать скидки и бонусы") }
                }
            }
        }

        if (показатьПроверку) {
            ПроверкаЗаказа(
                цены = цены,
                датаОтгрузки = датаОтгрузки,
                onConfirm = {
                    показатьПроверку = false
                    vm.saveOrder(
                        paymentType = клиент?.paymentType ?: "cash",
                        comment = примечание,
                        deliveryDate = датаОтгрузки,
                        onDone = onDone,
                    )
                },
                onDismiss = { показатьПроверку = false },
            )
        }

        if (показатьКалендарь) {
            val состояние = rememberDatePickerState(
                initialSelectedDateMillis = миллисОтгрузки,
                selectableDates = object : SelectableDates {
                    // Раньше завтра отгрузку не ставим.
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        utcTimeMillis >= началоДняUTC(1)
                })
            DatePickerDialog(
                onDismissRequest = { показатьКалендарь = false },
                confirmButton = {
                    TextButton(onClick = {
                        состояние.selectedDateMillis?.let { миллисОтгрузки = it }
                        показатьКалендарь = false
                    }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { показатьКалендарь = false }) { Text("Отмена") }
                },
            ) { DatePicker(state = состояние) }
        }
    }
}

/** Полночь UTC даты, отстоящей на [днейОтСегодня] от сегодня (локально). Формат
 *  DatePicker — UTC-миллисекунды, поэтому и границу считаем в UTC. */
private fun началоДняUTC(днейОтСегодня: Int): Long {
    val местное = Calendar.getInstance()
    местное.add(Calendar.DAY_OF_MONTH, днейОтСегодня)
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    utc.clear()
    utc.set(местное.get(Calendar.YEAR), местное.get(Calendar.MONTH),
        местное.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    return utc.timeInMillis
}

private fun миллисВдату(миллисUTC: Long): String {
    val формат = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    формат.timeZone = TimeZone.getTimeZone("UTC")
    return формат.format(Date(миллисUTC))
}

/** Просмотр полного заказа перед отправкой: позиции со скидкой, бонусы, итог. */
@Composable
private fun ПроверкаЗаказа(
    цены: PricedCart,
    датаОтгрузки: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Проверьте заказ") },
        text = {
            LazyColumn {
                items(цены.lines, key = { it.product.uuid }) { строка ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(строка.product.name, fontSize = 14.sp)
                            val подпись = "${строка.qty.toPlainString()} × " +
                                деньги(строка.product.price) +
                                if (строка.discountPercent > BigDecimal.ZERO)
                                    "  −${строка.discountPercent.toPlainString()}%" else ""
                            Text(подпись, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline)
                        }
                        Text(деньги(строка.amount.toPlainString()),
                            fontWeight = FontWeight.Medium)
                    }
                }
                items(цены.bonuses, key = { it.productUuid }) { бонус ->
                    Text("🎁 ${бонус.name} × ${бонус.qty.toPlainString()} (бонус)",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 3.dp))
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                Text("Отгрузка: $датаОтгрузки", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline)
                if (цены.discount > BigDecimal.ZERO) {
                    Text("Скидка: −${деньги(цены.discount.toPlainString())}",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
                Text("Итого: ${деньги(цены.total.toPlainString())}",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Изменить")
                    }
                    Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                        Text("Отправить")
                    }
                }
            }
        },
    )
}

@Composable
private fun ProductRow(товар: CatalogRow, вКорзине: BigDecimal,
                       onQty: (BigDecimal) -> Unit) {
    var текст by remember(товар.uuid, вКорзине) {
        mutableStateOf(if (вКорзине > BigDecimal.ZERO) вКорзине.toPlainString() else "")
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(товар.name)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(деньги(товар.price), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("свободно ${товар.free.toInt()} ${товар.unit}", fontSize = 13.sp,
                    color = if (товар.free <= 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline)
            }
        }
        OutlinedTextField(
            value = текст,
            onValueChange = { значение ->
                текст = значение
                onQty(значение.replace(",", ".").toBigDecimalOrNull() ?: BigDecimal.ZERO)
            },
            label = { Text(товар.unit) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.width(100.dp),
        )
    }
}
