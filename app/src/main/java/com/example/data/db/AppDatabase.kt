package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.ChatDao
import com.example.data.dao.MessageDao
import com.example.data.dao.WakeWordDao
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.SyncState
import com.example.data.model.TriggerType
import com.example.data.model.VoiceCommandEntity
import com.example.data.model.WakeWord
import com.example.data.model.WakeWordSample
import com.example.game.AdventureGameDao
import com.example.game.AdventureGameEntity
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Main RoomDatabase access point for the application's local SQLite persistence and
 * hybrid offline-first Room + Cloud Firestore synchronization architecture.
 *
 * Manages entities:
 * - [ChatSession] (`Chat` — `chat_sessions` table)
 * - [ChatMessage] (`Message` — `chat_messages` table)
 * - [WakeWord] (`wake_words` table)
 * - [WakeWordSample] (`wake_word_samples` table)
 * - [VoiceCommandEntity] (`voice_command_history` table)
 * - [AdventureGameEntity] (`adventure_game_states` table)
 */
@Database(
    entities = [
        ChatSession::class,
        ChatMessage::class,
        WakeWordSample::class,
        WakeWord::class,
        VoiceCommandEntity::class,
        AdventureGameEntity::class
    ],
    version = 19,
    exportSchema = false
)
@TypeConverters(AppDatabase.Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun wakeWordDao(): WakeWordDao
    abstract fun adventureGameDao(): AdventureGameDao

    class Converters {
        @TypeConverter
        fun fromAppTheme(value: AppTheme?): String = value?.name ?: AppTheme.SYSTEM.name

        @TypeConverter
        fun toAppTheme(value: String?): AppTheme = try {
            if (value != null) AppTheme.valueOf(value) else AppTheme.SYSTEM
        } catch (_: Exception) {
            AppTheme.SYSTEM
        }

        @TypeConverter
        fun fromTriggerType(value: TriggerType?): String = value?.name ?: TriggerType.CHAT.name

        @TypeConverter
        fun toTriggerType(value: String?): TriggerType = try {
            when (value?.trim()?.uppercase()) {
                TriggerType.HANDS_FREE.name, "HANDS-FREE", "HANDSFREE" -> TriggerType.HANDS_FREE
                else -> TriggerType.CHAT
            }
        } catch (_: Exception) {
            TriggerType.CHAT
        }

        @TypeConverter
        fun fromSyncState(value: SyncState?): String = value?.name ?: SyncState.PENDING.name

        @TypeConverter
        fun toSyncState(value: String?): SyncState = SyncState.fromStatus(value)
    }

    companion object {
        const val DATABASE_NAME = "gemini_wake_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN isPersistent INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN totalTrackedSeconds INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN attachmentUri TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN attachmentName TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN attachmentMimeType TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN attachmentSizeBytes INTEGER")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN themeMode TEXT NOT NULL DEFAULT 'SYSTEM'")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS wake_word_samples (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, chatId INTEGER NOT NULL, samplePath TEXT NOT NULL, recordedAt INTEGER NOT NULL)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS wake_word_samples")
                db.execSQL("CREATE TABLE IF NOT EXISTS wake_word_samples (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, chatId INTEGER NOT NULL, samplePath TEXT NOT NULL, recordedAt INTEGER NOT NULL)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wake_word_samples ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wake_word_samples ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wake_word_samples ADD COLUMN mimeType TEXT NOT NULL DEFAULT 'audio/mp4'")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `configured_wake_words`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `wake_words` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `phrase` TEXT NOT NULL,
                        `triggerType` TEXT NOT NULL,
                        `metadata` TEXT,
                        `commandRole` TEXT NOT NULL,
                        `chatId` INTEGER,
                        `chatTitle` TEXT NOT NULL,
                        `isEnabled` INTEGER NOT NULL,
                        `sensitivity` REAL NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wake_words_chatId` ON `wake_words` (`chatId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wake_words_triggerType` ON `wake_words` (`triggerType`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wake_words_commandRole` ON `wake_words` (`commandRole`)")
                db.execSQL(
                    """
                    INSERT INTO `wake_words` (`phrase`, `triggerType`, `metadata`, `commandRole`, `chatId`, `chatTitle`, `isEnabled`, `sensitivity`, `createdAt`, `updatedAt`)
                    SELECT `wakeWord`, 'CHAT', `title`, 'CHAT_WAKE', `id`, `title`, 1, 0.78, `createdAt`, `lastUpdatedAt`
                    FROM `chat_sessions`
                    """.trimIndent()
                )
                val now = System.currentTimeMillis()
                db.execSQL("INSERT INTO `wake_words` (`phrase`, `triggerType`, `metadata`, `commandRole`, `chatId`, `chatTitle`, `isEnabled`, `sensitivity`, `createdAt`, `updatedAt`) VALUES ('Dictate', 'HANDS_FREE', 'DICTATE', 'DICTATE', NULL, 'DICTATE', 1, 0.78, $now, $now)")
                db.execSQL("INSERT INTO `wake_words` (`phrase`, `triggerType`, `metadata`, `commandRole`, `chatId`, `chatTitle`, `isEnabled`, `sensitivity`, `createdAt`, `updatedAt`) VALUES ('Send', 'HANDS_FREE', 'SEND', 'SEND', NULL, 'SEND', 1, 0.78, $now, $now)")
                db.execSQL("INSERT INTO `wake_words` (`phrase`, `triggerType`, `metadata`, `commandRole`, `chatId`, `chatTitle`, `isEnabled`, `sensitivity`, `createdAt`, `updatedAt`) VALUES ('Heartbeat', 'HANDS_FREE', 'HEARTBEAT', 'HEARTBEAT', NULL, 'HEARTBEAT', 1, 0.78, $now, $now)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `voice_command_history` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `commandType` TEXT NOT NULL,
                        `triggerPhrase` TEXT NOT NULL,
                        `recognizedTranscript` TEXT NOT NULL,
                        `chatTitle` TEXT,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_voice_command_history_timestamp` ON `voice_command_history` (`timestamp`)")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `wake_word_samples` ADD COLUMN `wakeWordId` INTEGER")
                db.execSQL("ALTER TABLE `wake_word_samples` ADD COLUMN `sampleUuid` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `wake_word_samples` ADD COLUMN `wakePhrase` TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wake_word_samples_chatId` ON `wake_word_samples` (`chatId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wake_word_samples_wakeWordId` ON `wake_word_samples` (`wakeWordId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wake_word_samples_sampleUuid` ON `wake_word_samples` (`sampleUuid`)")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. chat_sessions hybrid sync columns + indices
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `remoteId` TEXT")
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `userId` TEXT")
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `lastSyncedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_sessions_remoteId` ON `chat_sessions` (`remoteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_sessions_syncStatus` ON `chat_sessions` (`syncStatus`)")

                // 2. chat_messages hybrid sync columns + indices
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `remoteId` TEXT")
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `userId` TEXT")
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `lastSyncedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_remoteId` ON `chat_messages` (`remoteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_syncStatus` ON `chat_messages` (`syncStatus`)")

                // 3. wake_words hybrid sync columns + indices
                db.execSQL("ALTER TABLE `wake_words` ADD COLUMN `remoteId` TEXT")
                db.execSQL("ALTER TABLE `wake_words` ADD COLUMN `userId` TEXT")
                db.execSQL("ALTER TABLE `wake_words` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE `wake_words` ADD COLUMN `lastSyncedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wake_words_remoteId` ON `wake_words` (`remoteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wake_words_syncStatus` ON `wake_words` (`syncStatus`)")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `isDeleted` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_sessions_userId` ON `chat_sessions` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_sessions_isDeleted` ON `chat_sessions` (`isDeleted`)")

                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `isDeleted` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_userId` ON `chat_messages` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_isDeleted` ON `chat_messages` (`isDeleted`)")

                db.execSQL("ALTER TABLE `wake_words` ADD COLUMN `isDeleted` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wake_words_userId` ON `wake_words` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wake_words_isDeleted` ON `wake_words` (`isDeleted`)")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `wake_words` ADD COLUMN `targetChatId` INTEGER")
                db.execSQL("ALTER TABLE `wake_words` ADD COLUMN `sensitivityInt` INTEGER NOT NULL DEFAULT 78")
                db.execSQL("UPDATE `wake_words` SET `targetChatId` = `chatId` WHERE `targetChatId` IS NULL")
                db.execSQL("UPDATE `wake_words` SET `sensitivityInt` = CAST(ROUND(`sensitivity` * 100) AS INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wake_words_targetChatId` ON `wake_words` (`targetChatId`)")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `greetingMessage` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `totalVoiceCommands` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    DELETE FROM `chat_messages`
                    WHERE `id` NOT IN (
                        SELECT MIN(`id`)
                        FROM `chat_messages`
                        GROUP BY `chatId`, `timestamp`, `sender`
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_chat_messages_chatId_timestamp_sender` ON `chat_messages` (`chatId`, `timestamp`, `sender`)"
                )
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `colorPaletteId` TEXT NOT NULL DEFAULT 'OCEAN_CYAN'")
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `backgroundStyle` TEXT NOT NULL DEFAULT 'DEFAULT'")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `adventure_game_states` (
                        `chatId` INTEGER NOT NULL PRIMARY KEY,
                        `isAdventureModeEnabled` INTEGER NOT NULL DEFAULT 0,
                        `questsJson` TEXT NOT NULL DEFAULT '[]',
                        `inventoryJson` TEXT NOT NULL DEFAULT '[]',
                        `settingsJson` TEXT NOT NULL DEFAULT '{}',
                        `characterName` TEXT NOT NULL DEFAULT 'Aether Vanguard',
                        `characterClass` TEXT NOT NULL DEFAULT 'Chrono-Wayfarer',
                        `characterPortraitUri` TEXT,
                        `attachedLoreFilesJson` TEXT NOT NULL DEFAULT '[]',
                        `totalXp` INTEGER NOT NULL DEFAULT 150,
                        `lastSavedAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19
        )

        private val queryExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            java.util.concurrent.Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "Room-Query-Worker").apply { isDaemon = true }
            }
        }

        private val transactionExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "Room-Tx-Worker").apply { isDaemon = true }
            }
        }

        /**
         * Returns the thread-safe singleton [AppDatabase] instance for the application.
         */
        fun getDatabase(
            context: Context,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .setQueryExecutor(queryExecutor)
                    .setTransactionExecutor(transactionExecutor)
                    .addMigrations(*ALL_MIGRATIONS)
                    .fallbackToDestructiveMigration(true)
                    .addCallback(DatabaseCallback(scope))
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /**
         * Convenience alias for [getDatabase] to retrieve the singleton [AppDatabase] instance.
         */
        fun getInstance(
            context: Context,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ): AppDatabase = getDatabase(context, scope)

        /**
         * Creates an isolated in-memory [AppDatabase] instance for unit and Robolectric tests.
         */
        fun buildInMemory(
            context: Context,
            allowMainThreadQueries: Boolean = true
        ): AppDatabase {
            val builder = Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AppDatabase::class.java
            )
            if (allowMainThreadQueries) {
                builder.allowMainThreadQueries()
            }
            return builder.build()
        }

        @Volatile
        private var populateJob: kotlinx.coroutines.Job? = null

        /**
         * Closes and resets the singleton [INSTANCE] reference for isolated test teardown.
         */
        fun clearInstanceForTesting() {
            synchronized(this) {
                populateJob?.cancel()
                populateJob = null
                runCatching { INSTANCE?.close() }
                INSTANCE = null
            }
        }

        private class DatabaseCallback(
            private val scope: CoroutineScope
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    populateJob = scope.launch(Dispatchers.IO) {
                        runCatching {
                            if (database.isOpen) {
                                populateInitialData(database.chatDao(), database.wakeWordDao())
                            }
                        }
                    }
                }
            }

            private suspend fun populateInitialData(dao: ChatDao, wakeWordDao: WakeWordDao) {
                val now = System.currentTimeMillis()

                val defaultChatId = dao.insertChat(
                    ChatSession(
                        title = "General Gemini",
                        wakeWord = "Hey Gemini",
                        isDefaultWakeChat = true,
                        systemInstruction = "You are Gemini, an intelligent, concise, and helpful AI assistant.",
                        greetingMessage = "Welcome back! Say \"Hey Gemini\" anytime to start talking, or say \"Gemini, sleep\" to pause listening.",
                        iconName = "Sparkles",
                        themeColorHex = "#38BDF8",
                        createdAt = now,
                        lastUpdatedAt = now
                    )
                )

                dao.insertMessage(
                    ChatMessage(
                        chatId = defaultChatId,
                        sender = "gemini",
                        content = "Hello! I am Gemini. You can speak to me anytime by saying \"Hey Gemini\", or tap the microphone to talk.",
                        timestamp = now
                    )
                )

                val coderChatId = dao.insertChat(
                    ChatSession(
                        title = "Code Mode",
                        wakeWord = "Code Mode",
                        isDefaultWakeChat = false,
                        systemInstruction = "You are an expert senior software engineer. Provide concise, clean, and bug-free code examples with explanations.",
                        greetingMessage = "Code Mode ready! Share your architecture, stack trace, or coding challenge.",
                        iconName = "Code",
                        themeColorHex = "#10B981",
                        createdAt = now + 1,
                        lastUpdatedAt = now + 1
                    )
                )

                dao.insertMessage(
                    ChatMessage(
                        chatId = coderChatId,
                        sender = "gemini",
                        content = "Ready for coding challenges! Say \"Code Mode\" anytime from the home screen to jump straight here.",
                        timestamp = now + 1
                    )
                )

                val brainstormChatId = dao.insertChat(
                    ChatSession(
                        title = "Creative Sparks",
                        wakeWord = "Creative Idea",
                        isDefaultWakeChat = false,
                        systemInstruction = "You are an inventive brainstorming partner. Help generate fresh, imaginative, and out-of-the-box concepts.",
                        greetingMessage = "Let's brainstorm! What new concept or project are we exploring today?",
                        iconName = "Lightbulb",
                        themeColorHex = "#A855F7",
                        createdAt = now + 2,
                        lastUpdatedAt = now + 2
                    )
                )

                dao.insertMessage(
                    ChatMessage(
                        chatId = brainstormChatId,
                        sender = "gemini",
                        content = "Got a new idea? Say \"Creative Idea\" and let's bring it to life!",
                        timestamp = now + 2
                    )
                )

                wakeWordDao.insertWakeWords(
                    listOf(
                        WakeWord(
                            phrase = "Hey Gemini",
                            triggerType = TriggerType.CHAT,
                            metadata = "General Gemini",
                            commandRole = WakeWord.ROLE_CHAT_WAKE,
                            chatId = defaultChatId,
                            chatTitle = "General Gemini",
                            isEnabled = true,
                            sensitivity = 0.78f,
                            createdAt = now,
                            updatedAt = now
                        ),
                        WakeWord(
                            phrase = "Code Mode",
                            triggerType = TriggerType.CHAT,
                            metadata = "Code Mode",
                            commandRole = WakeWord.ROLE_CHAT_WAKE,
                            chatId = coderChatId,
                            chatTitle = "Code Mode",
                            isEnabled = true,
                            sensitivity = 0.78f,
                            createdAt = now + 1,
                            updatedAt = now + 1
                        ),
                        WakeWord(
                            phrase = "Creative Idea",
                            triggerType = TriggerType.CHAT,
                            metadata = "Creative Sparks",
                            commandRole = WakeWord.ROLE_CHAT_WAKE,
                            chatId = brainstormChatId,
                            chatTitle = "Creative Sparks",
                            isEnabled = true,
                            sensitivity = 0.78f,
                            createdAt = now + 2,
                            updatedAt = now + 2
                        ),
                        WakeWord(
                            phrase = "Dictate",
                            triggerType = TriggerType.HANDS_FREE,
                            metadata = WakeWord.ROLE_DICTATE,
                            commandRole = WakeWord.ROLE_DICTATE,
                            chatId = null,
                            chatTitle = WakeWord.ROLE_DICTATE,
                            isEnabled = true,
                            sensitivity = 0.78f,
                            createdAt = now + 3,
                            updatedAt = now + 3
                        ),
                        WakeWord(
                            phrase = "Send",
                            triggerType = TriggerType.HANDS_FREE,
                            metadata = WakeWord.ROLE_SEND,
                            commandRole = WakeWord.ROLE_SEND,
                            chatId = null,
                            chatTitle = WakeWord.ROLE_SEND,
                            isEnabled = true,
                            sensitivity = 0.78f,
                            createdAt = now + 4,
                            updatedAt = now + 4
                        ),
                        WakeWord(
                            phrase = "Heartbeat",
                            triggerType = TriggerType.HANDS_FREE,
                            metadata = WakeWord.ROLE_HEARTBEAT,
                            commandRole = WakeWord.ROLE_HEARTBEAT,
                            chatId = null,
                            chatTitle = WakeWord.ROLE_HEARTBEAT,
                            isEnabled = true,
                            sensitivity = 0.78f,
                            createdAt = now + 5,
                            updatedAt = now + 5
                        )
                    )
                )
            }
        }
    }
}
