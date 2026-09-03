package com.example.shift.data.gym

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * One completed strength session. Sits alongside the older `workouts` /
 * `exercise_logs` tables rather than replacing them: those back the timer-only
 * circuit runner, this backs the set-by-set strength runner.
 */
@Entity(tableName = "gym_session")
data class GymSession(
    @PrimaryKey val id: String,
    val name: String,
    /** Local calendar date, "YYYY-MM-DD". */
    val date: String,
    @ColumnInfo(name = "started_at_millis") val startedAtMillis: Long,
    @ColumnInfo(name = "duration_minutes") val durationMinutes: Int,
    val notes: String? = null,
    /** 1 (easy) to 5 (all out). Optional. */
    @ColumnInfo(name = "perceived_effort") val perceivedEffort: Int? = null
)

/**
 * One set as it actually happened. The plan is only a suggestion; what gets
 * written here is what was lifted.
 *
 * [side] is "left" / "right" for single-arm or single-leg work and null when
 * both sides move together. The cable machine is single-cable, so every cable
 * exercise bar the two-handed face pull logs left and right separately.
 */
@Entity(
    tableName = "gym_set",
    foreignKeys = [ForeignKey(
        entity = GymSession::class,
        parentColumns = ["id"],
        childColumns = ["session_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("session_id")]
)
data class GymSet(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "exercise_name") val exerciseName: String,
    /** Null for bodyweight. Per implement: one kettlebell, one dumbbell, one cable handle. */
    @ColumnInfo(name = "weight_kg") val weightKg: Double? = null,
    /** Null for timed holds. */
    val reps: Int? = null,
    /** What the plan asked for, so progression can tell a clean set from a short one. */
    @ColumnInfo(name = "target_reps") val targetReps: Int? = null,
    /** Null for rep-based sets. */
    @ColumnInfo(name = "hold_seconds") val holdSeconds: Int? = null,
    val side: String? = null,
    /** 1-based set number within the exercise. */
    @ColumnInfo(name = "set_index") val setIndex: Int,
    val completed: Boolean
) {
    companion object {
        const val SIDE_LEFT = "left"
        const val SIDE_RIGHT = "right"
    }
}

/** The exercise library the planner may pick from. */
@Entity(tableName = "gym_exercise")
data class GymExercise(
    @PrimaryKey val name: String,
    /** One of [MovementPattern]. */
    @ColumnInfo(name = "movement_pattern") val movementPattern: String,
    val unilateral: Boolean,
    /** One of [Equipment] by name. */
    val equipment: String,
    val active: Boolean = true
)

/** Kept outside the entity so Room sees only real columns. */
val GymExercise.equipmentType: Equipment
    get() = Equipment.entries.firstOrNull { it.name == equipment } ?: Equipment.BODYWEIGHT

object MovementPattern {
    const val SQUAT = "squat"
    const val HINGE = "hinge"
    const val PUSH = "push"
    const val PULL = "pull"
    const val CARRY = "carry"
    const val CORE = "core"
    val ALL = listOf(SQUAT, HINGE, PUSH, PULL, CARRY, CORE)
}

data class GymSessionWithSets(
    @Embedded val session: GymSession,
    @Relation(parentColumn = "id", entityColumn = "session_id", entity = GymSet::class)
    val sets: List<GymSet>
)
