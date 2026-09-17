package uz.smartsale.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import uz.smartsale.agent.ui.AgentViewModel
import uz.smartsale.agent.ui.CustomerScreen
import uz.smartsale.agent.ui.LoginScreen
import uz.smartsale.agent.ui.OrderScreen
import uz.smartsale.agent.ui.RouteScreen
import uz.smartsale.agent.ui.SentDocsScreen
import uz.smartsale.agent.ui.SyncScreen
import uz.smartsale.agent.ui.ЗаданияScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Root() } }
    }
}

private enum class Экран { Маршрут, Клиент, Заказ, Обмен, Документы, Задания }

@Composable
private fun Root(vm: AgentViewModel = viewModel()) {
    val токен by vm.token.collectAsState()
    val сообщение by vm.message.collectAsState()
    val снэкбар = remember { SnackbarHostState() }
    var экран by remember { mutableStateOf(Экран.Маршрут) }

    LaunchedEffect(сообщение) {
        if (сообщение.isNotBlank()) {
            снэкбар.showSnackbar(сообщение)
            vm.clearMessage()
        }
    }

    if (токен.isBlank()) {
        LoginScreen(vm)
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(снэкбар) },
        bottomBar = {
            // Нижняя панель прячется на экранах заказа и карточки клиента:
            // уход с них посреди набора корзины теряет несохранённый заказ.
            if (экран == Экран.Маршрут || экран == Экран.Обмен
                || экран == Экран.Документы || экран == Экран.Задания) {
                NavigationBar {
                    NavigationBarItem(
                        selected = экран == Экран.Маршрут,
                        onClick = { экран = Экран.Маршрут },
                        icon = {}, label = { Text("Клиенты") },
                    )
                    NavigationBarItem(
                        selected = экран == Экран.Задания,
                        onClick = { экран = Экран.Задания },
                        icon = {}, label = { Text("Задания") },
                    )
                    NavigationBarItem(
                        selected = экран == Экран.Документы,
                        onClick = { экран = Экран.Документы },
                        icon = {}, label = { Text("Отправленные") },
                    )
                    NavigationBarItem(
                        selected = экран == Экран.Обмен,
                        onClick = { экран = Экран.Обмен },
                        icon = {}, label = { Text("Обмен") },
                    )
                }
            }
        },
    ) { отступы ->
        val модификатор = Modifier.padding(отступы)
        when (экран) {
            Экран.Маршрут -> androidx.compose.foundation.layout.Box(модификатор) {
                RouteScreen(vm) { uuid ->
                    vm.openCustomer(uuid)
                    экран = Экран.Клиент
                }
            }
            Экран.Клиент -> androidx.compose.foundation.layout.Box(модификатор) {
                CustomerScreen(vm,
                    onOrder = { экран = Экран.Заказ },
                    onBack = { экран = Экран.Маршрут })
            }
            Экран.Заказ -> androidx.compose.foundation.layout.Box(модификатор) {
                OrderScreen(vm,
                    onDone = { экран = Экран.Маршрут },
                    onBack = { экран = Экран.Клиент })
            }
            Экран.Обмен -> androidx.compose.foundation.layout.Box(модификатор) {
                SyncScreen(vm)
            }
            Экран.Документы -> androidx.compose.foundation.layout.Box(модификатор) {
                SentDocsScreen(vm)
            }
            Экран.Задания -> androidx.compose.foundation.layout.Box(модификатор) {
                ЗаданияScreen(vm)
            }
        }
    }
}
