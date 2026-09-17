package uz.smartsale.agent.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uz.smartsale.agent.data.db.CustomerEntity
import uz.smartsale.agent.data.db.OrderEntity
import uz.smartsale.agent.data.db.PaymentEntity
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

private val ДНИ = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
private val ДНИ_ПОЛНЫЕ = listOf(
    "понедельник", "вторник", "среду", "четверг", "пятницу", "субботу", "воскресенье")

@Composable
fun RouteScreen(vm: AgentViewModel, onCustomer: (String) -> Unit) {
    val маршрут by vm.routeCustomers.collectAsState()
    val все by vm.customers.collectAsState()
    val день by vm.weekday.collectAsState()
    val дниСМаршрутом by vm.routeDays.collectAsState()
    var показатьВсех by remember { mutableStateOf(false) }
    val список = if (показатьВсех) все else маршрут

    Column(Modifier.fillMaxSize()) {
        // Выбранная вкладка выделяется заливкой, а не гасится. Раньше она
        // была disabled: серая нажатая кнопка читается как «недоступно», и
        // маршрут выглядел сломанным, хотя список под ней был правильный.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Вкладка("Маршрут (${маршрут.size})", выбрана = !показатьВсех,
                onClick = { показатьВсех = false }, modifier = Modifier.weight(1f))
            Вкладка("Все клиенты (${все.size})", выбрана = показатьВсех,
                onClick = { показатьВсех = true }, modifier = Modifier.weight(1f))
        }

        if (!показатьВсех) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ДНИ.forEachIndexed { индекс, имя ->
                    val номер = индекс + 1
                    ДеньНедели(
                        имя = имя,
                        выбран = день == номер,
                        естьМаршрут = номер in дниСМаршрутом,
                        onClick = { vm.setWeekday(номер) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (список.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        показатьВсех ->
                            "Клиентов нет. Откройте «Обмен» и обменяйтесь с сервером."
                        дниСМаршрутом.isEmpty() ->
                            "Маршруты не назначены. Обменяйтесь с сервером, " +
                                "а если пусто и после обмена — скажите в офисе."
                        else ->
                            "На ${ДНИ_ПОЛНЫЕ[день - 1]} маршрута нет.\n" +
                                "Есть на: " + дниСМаршрутом.joinToString(", ") { ДНИ[it - 1] }
                    },
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn {
                items(список, key = { it.uuid }) { клиент ->
                    CustomerRow(клиент) { onCustomer(клиент.uuid) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Вкладка(текст: String, выбрана: Boolean, onClick: () -> Unit,
                    modifier: Modifier = Modifier) {
    if (выбрана) {
        Button(onClick = onClick, modifier = modifier) { Text(текст) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(текст) }
    }
}

@Composable
private fun ДеньНедели(имя: String, выбран: Boolean, естьМаршрут: Boolean,
                       onClick: () -> Unit, modifier: Modifier = Modifier) {
    // День без маршрута показан бледным, но нажимается: агент должен видеть,
    // что там пусто, а не гадать, почему кнопка не работает.
    val цветФона = when {
        выбран -> MaterialTheme.colorScheme.primary
        естьМаршрут -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val цветТекста = when {
        выбран -> MaterialTheme.colorScheme.onPrimary
        естьМаршрут -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        onClick = onClick,
        color = цветФона,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.height(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(имя, color = цветТекста, fontSize = 13.sp,
                fontWeight = if (выбран) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CustomerRow(клиент: CustomerEntity, onClick: () -> Unit) {
    // Открытие по двойному клику: одиночный тап в длинном списке легко
    // случается при прокрутке. Кнопка «Открыть» оставлена для явного действия.
    Column(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = {}, onDoubleClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
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
                if (!текущий.hasContract) {
                    Text("Нет договора — заказ и оплату оформить нельзя. " +
                        "Сообщите в офис.",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        Button(onClick = onOrder, enabled = !текущий.blocked && текущий.hasContract,
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

/** Отправленные документы: заказы и оплаты с их состоянием и номером в учёте. */
@Composable
fun SentDocsScreen(vm: AgentViewModel) {
    val заказы by vm.recentOrders.collectAsState()
    val оплаты by vm.recentPayments.collectAsState()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Text("Заказы", fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        }
        if (заказы.isEmpty()) {
            item { Text("нет", color = MaterialTheme.colorScheme.outline) }
        }
        items(заказы, key = { it.clientUid }) { з ->
            ДокументСтрока(
                номер = if (з.serverNumber.isNotBlank()) "№ ${з.serverNumber}" else "не отправлен",
                дата = з.date,
                сумма = з.amount,
                состояние = состояниеЗаказа(з),
                ошибка = з.error,
            )
            HorizontalDivider()
        }
        item {
            Text("Оплаты", fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        }
        if (оплаты.isEmpty()) {
            item { Text("нет", color = MaterialTheme.colorScheme.outline) }
        }
        items(оплаты, key = { it.clientUid }) { о ->
            ДокументСтрока(
                номер = if (о.serverNumber.isNotBlank()) "№ ${о.serverNumber}" else "не отправлена",
                дата = о.date,
                сумма = о.amount,
                состояние = if (о.synced) "отправлена" else if (о.error.isNotBlank()) "ошибка" else "в очереди",
                ошибка = о.error,
            )
            HorizontalDivider()
        }
    }
}

private fun состояниеЗаказа(з: OrderEntity): String = when {
    !з.synced && з.error.isNotBlank() -> "ошибка"
    !з.synced -> "в очереди"
    з.serverStatus.isNotBlank() -> з.serverStatus
    else -> "отправлен"
}

@Composable
private fun ДокументСтрока(номер: String, дата: String, сумма: String,
                           состояние: String, ошибка: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(номер, fontWeight = FontWeight.Medium)
                Text("$дата · $состояние", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline)
            }
            Text(деньги(сумма), fontWeight = FontWeight.Medium)
        }
        if (ошибка.isNotBlank()) {
            Text(ошибка, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}
