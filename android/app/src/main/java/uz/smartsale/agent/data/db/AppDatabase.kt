package uz.smartsale.agent.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProductEntity::class, CategoryEntity::class, PriceTypeEntity::class,
        PriceEntity::class, StockEntity::class, WarehouseEntity::class,
        CustomerEntity::class, RouteStopEntity::class,
        OrderEntity::class, OrderLineEntity::class,
        PaymentEntity::class, VisitEntity::class,
        PromotionEntity::class, PromotionProductEntity::class,
        PromotionThresholdEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun catalog(): CatalogDao
    abstract fun customers(): CustomerDao
    abstract fun documents(): DocumentDao
    abstract fun promotions(): PromotionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * v1 → v2: добавлены таблицы акций. Только CREATE TABLE новых таблиц —
         * существующие не трогаются, неотправленные заказы сохраняются.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `promotions` (" +
                        "`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`mechanic` TEXT NOT NULL, `dateFrom` TEXT NOT NULL, " +
                        "`dateTo` TEXT NOT NULL, `segmentUuid` TEXT NOT NULL, " +
                        "`priority` INTEGER NOT NULL, `percent` TEXT NOT NULL, " +
                        "`buyQty` TEXT NOT NULL, `bonusProductUuid` TEXT NOT NULL, " +
                        "`bonusQty` TEXT NOT NULL, `active` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`uuid`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `promotion_products` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`promotionUuid` TEXT NOT NULL, `productUuid` TEXT NOT NULL, " +
                        "`isGroup` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_promotion_products_promotionUuid` " +
                        "ON `promotion_products` (`promotionUuid`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `promotion_thresholds` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`promotionUuid` TEXT NOT NULL, `minQty` TEXT NOT NULL, " +
                        "`minSum` TEXT NOT NULL, `percent` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_promotion_thresholds_promotionUuid` " +
                        "ON `promotion_thresholds` (`promotionUuid`)"
                )
            }
        }

        // v2 → v3: у клиента признак наличия действующего договора. Существующим
        // строкам — 1 (по умолчанию разрешено), обмен перепишет из УТ.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `customers` ADD COLUMN `hasContract` " +
                        "INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "smartsale.db"
            )
                // Миграции обязательны с первого же обновления. Разрушающая
                // пересборка здесь не годится: в базе лежат неотправленные
                // заказы, и потерять их — потерять день работы агента.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
