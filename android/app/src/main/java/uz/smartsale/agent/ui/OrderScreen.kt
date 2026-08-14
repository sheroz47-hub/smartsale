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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import uz.smartsale.agent.data.db.CatalogRow
import java.math.BigDecimal

/**
 * Подбор товара и корзина на одном экране.
 *
 * Разделять их не нужно: агент стоит у прилавка и набирает позиции подряд,
 * а сумма заказа должна быть видна всё время — по ней клиент решает, брать
 * ли ещё.
 */
@Composable
fun OrderScreen(vm: AgentViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    var поиск by remember { mutableStateOf("") }
    var примечание by remember { mutableStateOf("") }
    val клиент by vm.currentCustomer.collectAsState()
    val корзина by vm.cart.collectAsState()

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
                    Text(деньги(vm.cartTotal.toPlainString()),
                        fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                OutlinedTextField(
                    value = примечание, onValueChange = { примечание = it },
                    label = { Text("Комментарий к заказу") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                        Text("Отмена")
                    }
                    Button(
                        onClick = {
                            vm.saveOrder(
                                paymentType = клиент?.paymentType ?: "cash",
                                comment = примечание,
                                onDone = onDone,
                            )
                        },
                        enabled = корзина.isNotEmpty(),
                        modifier = Modifier.weight(2f),
                    ) { Text("Записать заказ") }
                }
            }
        }
    }
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
