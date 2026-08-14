package uz.smartsale.agent.data.net

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface SmartSaleApi {

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @GET("api/v1/sync/pull")
    suspend fun pull(@Query("since") since: String): PullResponse

    @POST("api/v1/sync/push")
    suspend fun push(@Body request: PushRequest): PushResponse

    @GET("api/v1/ping")
    suspend fun ping(): Map<String, String>
}

/**
 * Сборка клиента.
 *
 * Токен подставляется перехватчиком, а не параметром каждого метода: иначе
 * однажды появится метод, который забудет его передать, и разбираться будут
 * по 401 в поле.
 */
object ApiFactory {

    private val json = Json {
        ignoreUnknownKeys = true   // сервер вправе добавлять поля, не ломая старые сборки
        encodeDefaults = true
    }

    fun create(baseUrl: String, tokenProvider: () -> String?): SmartSaleApi {
        val auth = Interceptor { chain ->
            val token = tokenProvider()
            val request = if (token.isNullOrBlank()) chain.request()
            else chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
            chain.proceed(request)
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(auth)
            // Мобильный интернет на окраине отвечает долго. Обрывать раньше
            // времени хуже, чем подождать: повтор стоит дороже ожидания.
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        return Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SmartSaleApi::class.java)
    }
}
