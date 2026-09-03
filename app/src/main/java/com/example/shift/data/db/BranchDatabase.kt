package com.example.shift.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.shift.data.dao.*
import com.example.shift.data.model.*
import com.example.shift.data.gym.GymDao
import com.example.shift.data.gym.GymExercise
import com.example.shift.data.gym.GymSeed
import com.example.shift.data.gym.GymSession
import com.example.shift.data.gym.GymSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import androidx.room.migration.Migration

@Database(
    entities = [
        Exercise::class, Workout::class, Step::class, DoneLog::class, ExerciseLog::class,
        GymSession::class, GymSet::class, GymExercise::class
    ],
    version = 9,
    exportSchema = false
)
abstract class BranchDatabase : RoomDatabase() {

    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun stepDao(): StepDao
    abstract fun doneDao(): DoneDao
    abstract fun exerciseLogDao(): ExerciseLogDao
    abstract fun gymDao(): GymDao

    companion object {
        @Volatile
        private var INSTANCE: BranchDatabase? = null

        fun getDatabase(context: Context): BranchDatabase =
            INSTANCE ?: synchronized(this) {
                val MIGRATION_1_2 = object : Migration(1, 2) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE exercises ADD COLUMN equipment TEXT NOT NULL DEFAULT 'None'")
                        db.execSQL("ALTER TABLE exercises ADD COLUMN isUnilateral INTEGER NOT NULL DEFAULT 0")
                    }
                }
                
                val MIGRATION_2_3 = object : Migration(2, 3) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        val seeds = SeedData.GYM_EXERCISES + SeedData.FLOW_EXERCISES
                        seeds.forEach { ex ->
                            db.execSQL(
                                "UPDATE exercises SET equipment = '${ex.equipment}', isUnilateral = ${if (ex.isUnilateral) 1 else 0} WHERE id = '${ex.id}' AND equipment = 'None'"
                            )
                        }
                    }
                }

                val MIGRATION_3_4 = object : Migration(3, 4) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `deleted_sync_queue` (`id` TEXT NOT NULL, `tableName` TEXT NOT NULL, PRIMARY KEY(`id`))")
                    }
                }
                
                val MIGRATION_4_5 = object : Migration(4, 5) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE workouts ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                    }
                }
                
                val MIGRATION_5_6 = object : Migration(5, 6) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE workouts ADD COLUMN rounds INTEGER NOT NULL DEFAULT 1")
                    }
                }

                val MIGRATION_6_7 = object : Migration(6, 7) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `exercise_logs` (`id` TEXT NOT NULL, `exerciseId` TEXT NOT NULL, `dateMillis` INTEGER NOT NULL, `weightUsed` REAL NOT NULL, `feeling` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                    }
                }
                
                val MIGRATION_7_8 = object : Migration(7, 8) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE done_log ADD COLUMN hrTss INTEGER DEFAULT NULL")
                    }
                }

                // Strength module: sessions, the sets inside them, and the
                // exercise library. Column types and constraints must match the
                // entities exactly or Room refuses to open the database.
                val MIGRATION_8_9 = object : Migration(8, 9) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `gym_session` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `date` TEXT NOT NULL, `started_at_millis` INTEGER NOT NULL, `duration_minutes` INTEGER NOT NULL, `notes` TEXT, `perceived_effort` INTEGER, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `gym_set` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `exercise_name` TEXT NOT NULL, `weight_kg` REAL, `reps` INTEGER, `target_reps` INTEGER, `hold_seconds` INTEGER, `side` TEXT, `set_index` INTEGER NOT NULL, `completed` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `gym_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_gym_set_session_id` ON `gym_set` (`session_id`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `gym_exercise` (`name` TEXT NOT NULL, `movement_pattern` TEXT NOT NULL, `unilateral` INTEGER NOT NULL, `equipment` TEXT NOT NULL, `active` INTEGER NOT NULL, PRIMARY KEY(`name`))")
                    }
                }
                
                Room.databaseBuilder(
                    context.applicationContext,
                    BranchDatabase::class.java,
                    "branch_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .addCallback(object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                INSTANCE?.exerciseDao()?.upsertAll(
                                    SeedData.GYM_EXERCISES + SeedData.FLOW_EXERCISES
                                )
                                // Insert-if-absent, not upsert: an exercise the user
                                // has switched off must stay off.
                                INSTANCE?.gymDao()?.insertExercisesIfAbsent(GymSeed.EXERCISES)
                            }
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
