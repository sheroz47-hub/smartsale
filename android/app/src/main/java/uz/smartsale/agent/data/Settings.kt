package uz.smartsale.agent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore("smartsale")

/**
 * Настройки и состояние входа.
 *
 * Токен лежит в обычном DataStore. Это осознанное упрощение: аппарат агента
 * не хранит ничего, что не пришло бы с сервера, а сам токен отзывается из
 * бэкофиса одним нажатием. Шифрованное хранилище стоит завести, когда на
 * телефоне появятся персональные данные клиентов сверх названия и адреса.
 */
class Settings(private val context: Context) {

    private object Keys {
        val server = stringPreferencesKey("server")
        val token = stringPreferencesKey("token")
        val deviceId = stringPreferencesKey("device_id")
        val agentName = stringPreferencesKey("agent_name")
        val lastSync = stringPreferencesKey("last_sync")
    }

    val server: Flow<String> = context.dataStore.data.map { it[Keys.server] ?: "" }
    val agentName: Flow<String> = context.dataStore.data.map { it[Keys.agentName] ?: "" }
    val lastSync: Flow<String> = context.dataStore.data.map { it[Keys.lastSync] ?: "" }
    val token: Flow<String> = context.dataStore.data.map { it[Keys.token] ?: "" }

    suspend fun serverNow(): String = server.first()
    suspend fun tokenNow(): String = token.first()
    suspend fun lastSyncNow(): String = lastSync.first()

    /**
     * Идентификатор устройства.
     *
     * Генерируется сам и живёт до переустановки приложения. Аппаратные
     * идентификаторы вроде ANDROID_ID сознательно не используются: на части
     * прошивок они совпадают у разных аппаратов, и два телефона склеились бы
     * в одно устройство на сервере.
     */
    suspend fun deviceId(): String {
        val существующий = context.dataStore.data.map { it[Keys.deviceId] }.first()
        if (!существующий.isNullOrBlank()) return существующий
        val новый = UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.deviceId] = новый }
        return новый
    }

    suspend fun setServer(value: String) =
        context.dataStore.edit { it[Keys.server] = value.trim().trimEnd('/') }

    suspend fun signIn(token: String, agentName: String) = context.dataStore.edit {
        it[Keys.token] = token
        it[Keys.agentName] = agentName
    }

    suspend fun signOut() = context.dataStore.edit {
        it.remove(Keys.token)
        it.remove(Keys.agentName)
        // Отсечка синхронизации сбрасывается вместе с токеном: следующий
        // вход должен забрать справочники заново, иначе новый агент на том
        // же аппарате увидит чужих клиентов.
        it.remove(Keys.lastSync)
    }

    suspend fun setLastSync(value: String) =
        context.dataStore.edit { it[Keys.lastSync] = value }
}
