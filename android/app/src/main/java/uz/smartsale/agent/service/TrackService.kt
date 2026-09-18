package uz.smartsale.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uz.smartsale.agent.MainActivity
import uz.smartsale.agent.SmartSaleApp
import uz.smartsale.agent.data.net.TrackPointDto
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Фоновый сервис слежения за положением агента.
 *
 * В рабочие часы снимает координаты с интервалом ~3 мин и шлёт их СРАЗУ на
 * сервер (телефон трек не хранит — не доехало, потеряли). Foreground-сервис с
 * постоянным уведомлением: агент видит, что запись идёт (иначе слежка была бы
 * скрытой). Геолокация — штатным LocationManager, как и уточнение точки на
 * визите, без Google Play Services.
 */
class TrackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var manager: LocationManager? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = обработать(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("до API 29 обязателен")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate() {
        super.onCreate()
        запуститьПередний()
        начатьСлежение()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { manager?.removeUpdates(listener) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun запуститьПередний() {
        val уведомление = уведомление()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, уведомление, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, уведомление)
        }
    }

    private fun начатьСлежение() {
        val естьТочная = ContextCompat.checkSelfPermission(this,
            android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val естьГрубая = ContextCompat.checkSelfPermission(this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!естьТочная && !естьГрубая) {
            stopSelf(); return
        }

        val mgr = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run {
            stopSelf(); return
        }
        manager = mgr
        val провайдеры = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { mgr.isProviderEnabled(it) }.getOrDefault(false) }
        if (провайдеры.isEmpty()) return
        try {
            провайдеры.forEach { p ->
                mgr.requestLocationUpdates(p, ИНТЕРВАЛ_МС, 0f, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun обработать(location: Location) {
        if (!вРабочиеЧасы()) return
        val точка = TrackPointDto(
            recordedAt = ФОРМАТ.format(Date()),
            lat = location.latitude.toString(),
            lon = location.longitude.toString(),
            accuracy = if (location.hasAccuracy()) location.accuracy.toInt().toString() else "",
        )
        val репозиторий = (application as SmartSaleApp).repository
        scope.launch { репозиторий.sendTrack(listOf(точка)) }
    }

    private fun вРабочиеЧасы(): Boolean {
        val час = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return час in ЧАС_НАЧАЛА until ЧАС_КОНЦА
    }

    private fun уведомление(): Notification {
        val менеджер = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val канал = NotificationChannel(КАНАЛ, "Отслеживание маршрута",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Фиксация положения агента в рабочие часы"
            }
            менеджер.createNotificationChannel(канал)
        }
        val открыть = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, КАНАЛ)
            .setContentTitle("SmartSale")
            .setContentText("Отслеживание маршрута включено")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(открыть)
            .build()
    }

    companion object {
        private const val КАНАЛ = "track"
        private const val NOTIF_ID = 42
        private const val ИНТЕРВАЛ_МС = 180_000L      // 3 минуты
        private const val ЧАС_НАЧАЛА = 8
        private const val ЧАС_КОНЦА = 19
        private val ФОРМАТ = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TrackService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackService::class.java))
        }
    }
}
