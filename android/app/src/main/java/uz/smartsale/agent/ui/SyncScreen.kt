package uz.smartsale.agent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SyncScreen(vm: AgentViewModel) {
    val занят by vm.busy.collectAsState()
    val последний by vm.lastSync.collectAsState()
    val заказы by vm.recentOrders.collectAsState()
    val вОчереди by vm.pendingCount.collectAsState()
    val имя by vm.agentName.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(имя, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            if (последний.isBlank()) "Обмена ещё не было"
            else "Последний обмен: ${последний.take(19).replace('T', ' ')}",
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            if (вОчереди == 0) "Все заказы отправлены"
            else "В очереди заказов: $вОчереди",
            color = if (вОчереди == 0) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )

        Button(onClick = { vm.sync() }, enabled = !занят,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text(if (занят) "Обмен идёт…" else "Обменяться с сервером")
        }

        Text("Мои заказы", fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(заказы, key = { it.clientUid }) { заказ ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            if (заказ.serverNumber.isNotBlank()) заказ.serverNumber
                            else "не отправлен",
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(деньги(заказ.amount))
                    }
                    Text(заказ.date, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline)
                    if (заказ.error.isNotBlank()) {
                        // Отказ сервера показывается дословно: агент должен
                        // понять причину и решить, что делать в точке.
                        Text(заказ.error, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                Divider()
            }
        }

        OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth()) {
            Text("Выйти из учётной записи")
        }
    }
}
