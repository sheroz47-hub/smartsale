package uz.smartsale.agent.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProductEntity::class, CategoryEntity::class, PriceTypeEntity::class,
        PriceEntity::class, StockEntity::class, WarehouseEntity::class,
        CustomerEntity::class, RouteStopEntity::class,
        OrderEntity::class, OrderLineEntity::class,
        PaymentEntity::class, VisitEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun catalog(): CatalogDao
    abstract fun customers(): CustomerDao
    abstract fun documents(): DocumentDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "smartsale.db"
            )
                // Миграции обязательны с первого же обновления. Разрушающая
                // пересборка здесь не годится: в базе лежат неотправленные
                // заказы, и потерять их — потерять день работы агента.
                .build()
                .also { instance = it }
        }
    }
}
