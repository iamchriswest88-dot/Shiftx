package com.example.shift.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.shift.data.dao.*
import com.example.shift.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import androidx.room.migration.Migration

@Database(
    entities = [Exercise::class, Workout::class, Step::class, DoneLog::class, ExerciseLog::class],
    version = 8,
    exportSchema = false
)
abstract class BranchDatabase : RoomDatabase() {

    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun stepDao(): StepDao
    abstract fun doneDao(): DoneDao
    abstract fun exerciseLogDao(): ExerciseLogDao

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
                
                Room.databaseBuilder(
                    context.applicationContext,
                    BranchDatabase::class.java,
                    "branch_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .addCallback(object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                INSTANCE?.exerciseDao()?.upsertAll(
                                    SeedData.GYM_EXERCISES + SeedData.FLOW_EXERCISES
                                )
                            }
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
