package uz.smartsale.agent.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Фоновая синхронизация.
 *
 * Заказ, снятый в точке без связи, не должен ждать, пока агент вспомнит про
 * кнопку «обменяться». WorkManager запустит обмен сам, как только появится
 * сеть, и переживёт перезагрузку телефона.
 */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = Repository(applicationContext)
        if (repository.settings.tokenNow().isBlank()) {
            // Агент не вошёл — работать нечем и повторять незачем.
            return Result.success()
        }

        val итог = repository.sync()
        // Отклонённые сервером документы — не повод повторять: причина в
        // существе документа, а не в связи, и повтор ничего не изменит.
        // Перезапуск нужен только когда обмен не состоялся вовсе.
        return if (итог.ok || итог.rejected > 0) Result.success() else Result.retry()
    }

    companion object {
        private const val ИМЯ = "smartsale-sync"

        fun schedule(context: Context) {
            val запрос = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                ИМЯ,
                // KEEP, а не UPDATE: иначе каждый запуск приложения сбрасывал
                // бы отсчёт периода, и на активно используемом телефоне
                // фоновый обмен не случался бы никогда.
                ExistingPeriodicWorkPolicy.KEEP,
                запрос,
            )
        }
    }
}
