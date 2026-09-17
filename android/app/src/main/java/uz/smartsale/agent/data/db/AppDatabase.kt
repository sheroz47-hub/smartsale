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
        PromotionThresholdEntity::class, TaskEntity::class,
        TaskPhotoEntity::class, LocationEntity::class,
        AuditQuestionEntity::class, AuditEntity::class, AuditAnswerEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun catalog(): CatalogDao
    abstract fun customers(): CustomerDao
    abstract fun documents(): DocumentDao
    abstract fun promotions(): PromotionDao
    abstract fun tasks(): TaskDao
    abstract fun media(): MediaDao
    abstract fun audits(): AuditDao

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

        // v3 → v4: таблица заданий агента (маршруты и задания, фаза B). Только
        // CREATE TABLE — существующие таблицы не трогаются, очередь документов
        // сохраняется.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tasks` (" +
                        "`uuid` TEXT NOT NULL, `customerUuid` TEXT, " +
                        "`date` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                        "`active` INTEGER NOT NULL, `done` INTEGER NOT NULL, " +
                        "`doneAt` TEXT, `comment` TEXT NOT NULL, " +
                        "`synced` INTEGER NOT NULL, `error` TEXT NOT NULL, " +
                        "PRIMARY KEY(`uuid`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tasks_customerUuid` " +
                        "ON `tasks` (`customerUuid`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tasks_synced` " +
                        "ON `tasks` (`synced`)"
                )
            }
        }

        // v4 → v5: очереди фотоотчётов и уточнённых координат (фаза C). Только
        // CREATE TABLE — существующие таблицы не трогаются, очередь документов
        // сохраняется.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_photos` (" +
                        "`uuid` TEXT NOT NULL, `taskUuid` TEXT NOT NULL, " +
                        "`path` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`synced` INTEGER NOT NULL, `error` TEXT NOT NULL, " +
                        "PRIMARY KEY(`uuid`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_photos_taskUuid` " +
                        "ON `task_photos` (`taskUuid`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_photos_synced` " +
                        "ON `task_photos` (`synced`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_locations` (" +
                        "`customerUuid` TEXT NOT NULL, `lat` TEXT NOT NULL, " +
                        "`lon` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`synced` INTEGER NOT NULL, `error` TEXT NOT NULL, " +
                        "PRIMARY KEY(`customerUuid`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_locations_synced` " +
                        "ON `pending_locations` (`synced`)"
                )
            }
        }

        // v5 → v6: аудит точки (вопросы из УТ + пройденные аудиты с ответами).
        // Только CREATE TABLE — существующие таблицы не трогаются.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `audit_questions` (" +
                        "`uuid` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                        "`answerType` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                        "`required` INTEGER NOT NULL, `active` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`uuid`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audit_questions_active` " +
                        "ON `audit_questions` (`active`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `audits` (" +
                        "`clientUid` TEXT NOT NULL, `customerUuid` TEXT NOT NULL, " +
                        "`date` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`synced` INTEGER NOT NULL, `error` TEXT NOT NULL, " +
                        "PRIMARY KEY(`clientUid`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audits_synced` " +
                        "ON `audits` (`synced`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `audit_answers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`auditUid` TEXT NOT NULL, `questionUuid` TEXT NOT NULL, " +
                        "`value` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audit_answers_auditUid` " +
                        "ON `audit_answers` (`auditUid`)"
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    MIGRATION_4_5, MIGRATION_5_6)
                .build()
                .also { instance = it }
        }
    }
}
