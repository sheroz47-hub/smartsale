package uz.smartsale.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uz.smartsale.agent.data.db.CustomerEntity
import java.math.BigDecimal

/** Суммы с разделителями разрядов: в сумах они длинные. */
fun деньги(значение: String?): String {
    val число = значение?.toBigDecimalOrNull() ?: return "—"
    return число.setScale(0, java.math.RoundingMode.HALF_UP)
        .toPlainString()
        .reversed().chunked(3).joinToString(" ").reversed()
}

@Composable
fun LoginScreen(vm: AgentViewModel) {
    val сохранённыйАдрес by vm.server.collectAsState()
    var адрес by remember(сохранённыйАдрес) { mutableStateOf(сохранённыйАдрес) }
    var логин by remember { mutableStateOf("") }
    var пароль by remember { mutableStateOf("") }
    val занят by vm.busy.collectAsState()
    val сообщение by vm.message.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("SmartSale", fontSize = 28.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text("приложение торгового агента", color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            textAlign = TextAlign.Center)

        OutlinedTextField(
            value = адрес, onValueChange = { адрес = it },
            label = { Text("Адрес сервера") },
            placeholder = { Text("http://31.207.45.93") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = логин, onValueChange = { логин = it },
            label = { Text("Логин") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = пароль, onValueChange = { пароль = it },
            label = { Text("Пароль") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Button(
            onClick = { vm.login(адрес, логин, пароль) },
            enabled = !занят && адрес.isNotBlank() && логин.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            if (занят) CircularProgressIndicator(Modifier.width(20.dp))
            else Text("Войти")
        }

        if (сообщение.isNotBlank()) {
            Text(сообщение, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun RouteScreen(vm: AgentViewModel, onCustomer: (String) -> Unit) {
    val маршрут by vm.todayRoute.collectAsState()
    val все by vm.customers.collectAsState()
    var показатьВсех by remember { mutableStateOf(false) }
    val список = if (показатьВсех) все else маршрут

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { показатьВсех = false }, enabled = показатьВсех) {
                Text("Маршрут (${маршрут.size})")
            }
            OutlinedButton(onClick = { показатьВсех = true }, enabled = !показатьВсех) {
                Text("Все клиенты (${все.size})")
            }
        }

        if (список.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (показатьВсех) "Клиентов нет. Обменяйтесь с сервером."
                    else "На сегодня маршрут не назначен.",
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            LazyColumn {
                items(список, key = { it.uuid }) { клиент ->
                    CustomerRow(клиент) { onCustomer(клиент.uuid) }
                    Divider()
                }
            }
        }
    }
}

@Composable
private fun CustomerRow(клиент: CustomerEntity, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(клиент.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text("Открыть") }
        }
        if (клиент.address.isNotBlank()) {
            Text(клиент.address, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val долг = клиент.debt.toBigDecimalOrNull() ?: BigDecimal.ZERO
            if (долг > BigDecimal.ZERO) {
                Text("долг ${деньги(клиент.debt)}", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error)
            }
            val просрочено = клиент.overdue.toBigDecimalOrNull() ?: BigDecimal.ZERO
            if (просрочено > BigDecimal.ZERO) {
                Text("просрочено ${деньги(клиент.overdue)}", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
            if (клиент.blocked) {
                Text("СТОП", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun CustomerScreen(vm: AgentViewModel, onOrder: () -> Unit, onBack: () -> Unit) {
    val клиент by vm.currentCustomer.collectAsState()
    val текущий = клиент ?: return
    var сумма by remember { mutableStateOf("") }
    var примечание by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(текущий.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (текущий.address.isNotBlank()) {
            Text(текущий.address, color = MaterialTheme.colorScheme.outline)
        }

        Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Строка("Долг", деньги(текущий.debt))
                Строка("Просрочено", деньги(текущий.overdue))
                Строка("Лимит", деньги(текущий.creditLimit))
                Строка("Отсрочка", "${текущий.deferralDays} дн.")
                if (текущий.blocked) {
                    Text("Отгрузка запрещена: ${текущий.blockedReason}",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        Button(onClick = onOrder, enabled = !текущий.blocked,
            modifier = Modifier.fillMaxWidth()) {
            Text("Оформить заказ")
        }

        Text("Принять оплату", fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
        OutlinedTextField(
            value = сумма, onValueChange = { сумма = it },
            label = { Text("Сумма наличными") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = примечание, onValueChange = { примечание = it },
            label = { Text("Примечание") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedButton(
            onClick = {
                vm.savePayment(сумма, "cash", примечание)
                сумма = ""; примечание = ""
            },
            enabled = сумма.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text("Записать оплату") }

        Row(Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.saveVisit("no_order", "", null, null) },
                modifier = Modifier.weight(1f)) { Text("Отказ") }
            OutlinedButton(onClick = { vm.saveVisit("closed", "", null, null) },
                modifier = Modifier.weight(1f)) { Text("Закрыта") }
        }

        TextButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) {
            Text("← к списку")
        }
    }
}

@Composable
private fun Строка(подпись: String, значение: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(подпись, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
        Text(значение, fontWeight = FontWeight.Medium)
    }
}
