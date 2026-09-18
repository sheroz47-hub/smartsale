package uz.smartsale.agent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import uz.smartsale.agent.service.TrackService
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
import uz.smartsale.agent.ui.NewClientScreen
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

private enum class Экран { Маршрут, Клиент, Заказ, Обмен, Документы, Задания, НоваяЗаявка }

@Composable
private fun Root(vm: AgentViewModel = viewModel()) {
    val токен by vm.token.collectAsState()
    val сообщение by vm.message.collectAsState()
    val снэкбар = remember { SnackbarHostState() }
    var экран by remember { mutableStateOf(Экран.Маршрут) }
    val контекст = LocalContext.current

    // Фоновую локацию (для трека при заблокированном экране) на Android 10+
    // просят ОТДЕЛЬНО и только после обычной — система иначе игнорирует запрос.
    val фоноваяЛокация = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { }

    val разрешенияТрека = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { итог ->
        val естьЛокация = итог[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            итог[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (естьЛокация) {
            TrackService.start(контекст)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(контекст,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED) {
                фоноваяЛокация.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    // Трек включается по входу агента, гаснет по выходу. Разрешения спрашиваем
    // здесь: без локации сервис просто не стартует, работу это не блокирует.
    LaunchedEffect(токен) {
        if (токен.isBlank()) {
            TrackService.stop(контекст)
        } else {
            val нужные = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val нехватает = нужные.any {
                ContextCompat.checkSelfPermission(контекст, it) !=
                    PackageManager.PERMISSION_GRANTED
            }
            if (нехватает) {
                разрешенияТрека.launch(нужные.toTypedArray())
            } else {
                TrackService.start(контекст)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(контекст,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
                        PackageManager.PERMISSION_GRANTED) {
                    фоноваяЛокация.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
        }
    }

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
                RouteScreen(vm,
                    onNewClient = { экран = Экран.НоваяЗаявка }) { uuid ->
                    vm.openCustomer(uuid)
                    экран = Экран.Клиент
                }
            }
            Экран.НоваяЗаявка -> androidx.compose.foundation.layout.Box(модификатор) {
                NewClientScreen(vm,
                    onDone = { экран = Экран.Маршрут },
                    onBack = { экран = Экран.Маршрут })
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
