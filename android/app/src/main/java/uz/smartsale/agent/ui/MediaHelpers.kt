package uz.smartsale.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Съёмка фото системной камерой. Возвращает лямбду, запускающую камеру; по
 * снимку отдаёт временный ФАЙЛ полного кадра в [onCaptured] — сжатие и удаление
 * файла делает слой данных в фоне (декодировать и жать многомегапиксельный кадр
 * на главном потоке — заметный фриз на слабых аппаратах).
 *
 * Через системную камеру (TakePicture), а не свою: разрешение CAMERA не нужно,
 * агенту привычнее штатное приложение, а нам меньше кода и прав.
 *
 * Путь кадра — в rememberSaveable: если систему убьёт процесс, пока открыта
 * камера (на дешёвых устройствах при нехватке памяти — реально), после возврата
 * путь восстановится и снятый кадр не потеряется.
 */
@Composable
fun rememberPhotoCapture(onCaptured: (File) -> Unit): () -> Unit {
    val context = LocalContext.current
    var путьКадра by rememberSaveable { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { успех ->
        val путь = путьКадра
        if (успех && путь != null) {
            val файл = File(путь)
            if (файл.exists()) onCaptured(файл)
        }
        путьКадра = null
    }

    return {
        val каталог = File(context.cacheDir, "capture").apply { mkdirs() }
        val файл = File(каталог, "cap_${System.currentTimeMillis()}.jpg")
        путьКадра = файл.absolutePath
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", файл)
        launcher.launch(uri)
    }
}

/**
 * Запрос текущих координат «по кнопке»: спрашивает разрешение при необходимости,
 * берёт свежий фикс, при таймауте — последний известный. Ошибку/успех отдаёт
 * колбэками (агенту показывается снекбаром).
 */
@Composable
fun rememberLocationRequester(
    onLocation: (Double, Double) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { результат ->
        if (результат.values.any { it }) достатьЛокацию(context, onLocation, onError)
        else onError("Без разрешения на геолокацию координаты не уточнить")
    }

    return {
        if (естьРазрешениеНаГео(context)) достатьЛокацию(context, onLocation, onError)
        else permLauncher.launch(
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}

private fun естьРазрешениеНаГео(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.ACCESS_FINE_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")  // проверено естьРазрешениеНаГео до вызова
private fun достатьЛокацию(
    context: Context,
    onLocation: (Double, Double) -> Unit,
    onError: (String) -> Unit,
) {
    val менеджер = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (менеджер == null) {
        onError("Геолокация недоступна на устройстве")
        return
    }
    val провайдеры = listOf(
        LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER
    ).filter { менеджер.isProviderEnabled(it) }
    if (провайдеры.isEmpty()) {
        onError("Геолокация выключена — включите её в настройках")
        return
    }

    var доставлено = false
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (доставлено) return
            доставлено = true
            менеджер.removeUpdates(this)
            onLocation(location.latitude, location.longitude)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    for (провайдер in провайдеры) {
        менеджер.requestLocationUpdates(провайдер, 0L, 0f, listener, Looper.getMainLooper())
    }

    // Свежий фикс не всегда приходит быстро (в помещении GPS молчит). Ждём
    // до 8 секунд, потом отдаём последний известный или честно сообщаем отказ.
    Handler(Looper.getMainLooper()).postDelayed({
        if (доставлено) return@postDelayed
        доставлено = true
        менеджер.removeUpdates(listener)
        val последний = провайдеры
            .mapNotNull { менеджер.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
        if (последний != null) onLocation(последний.latitude, последний.longitude)
        else onError("Координаты не получены — попробуйте на открытом месте")
    }, 8000)
}
