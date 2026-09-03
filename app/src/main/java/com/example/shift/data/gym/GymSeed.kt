package com.example.shift.data.gym

/**
 * The exercise library, built around what is actually in the house: kettlebells
 * to 12kg, a pair of dumbbells to 10kg, two 10kg plates and a single-cable
 * portable machine. Split squats, lunges, single-leg RDLs and side planks are
 * deliberately absent.
 */
object GymSeed {
    val EXERCISES: List<GymExercise> = listOf(
        ex("Goblet squat",               MovementPattern.SQUAT, false, Equipment.KETTLEBELL),
        ex("Kettlebell swing",           MovementPattern.HINGE, false, Equipment.KETTLEBELL),
        ex("Romanian deadlift",          MovementPattern.HINGE, false, Equipment.DUMBBELL),
        ex("Hip thrust",                 MovementPattern.HINGE, false, Equipment.PLATE),
        ex("Glute bridge",               MovementPattern.HINGE, false, Equipment.BODYWEIGHT),
        ex("Cable row",                  MovementPattern.PULL,  true,  Equipment.CABLE),
        ex("Cable pull-down",            MovementPattern.PULL,  true,  Equipment.CABLE),
        ex("Cable reverse fly",          MovementPattern.PULL,  true,  Equipment.CABLE),
        ex("Face pull",                  MovementPattern.PULL,  false, Equipment.CABLE),
        ex("Bent-over dumbbell row",     MovementPattern.PULL,  false, Equipment.DUMBBELL),
        ex("Single-arm overhead press",  MovementPattern.PUSH,  true,  Equipment.DUMBBELL),
        ex("Cable chest press",          MovementPattern.PUSH,  true,  Equipment.CABLE),
        ex("Cable triceps pushdown",     MovementPattern.PUSH,  false, Equipment.CABLE),
        ex("Push-up",                    MovementPattern.PUSH,  false, Equipment.BODYWEIGHT),
        ex("Suitcase carry",             MovementPattern.CARRY, true,  Equipment.KETTLEBELL),
        ex("Farmer carry",               MovementPattern.CARRY, false, Equipment.DUMBBELL),
        ex("Pallof press",               MovementPattern.CORE,  true,  Equipment.CABLE),
        ex("Plank",                      MovementPattern.CORE,  false, Equipment.BODYWEIGHT),
        ex("Dead bug",                   MovementPattern.CORE,  false, Equipment.BODYWEIGHT),
    )

    /** Movements never to be planned, matched case-insensitively against exercise names. */
    val EXCLUDED_NAME_FRAGMENTS = listOf("split squat", "lunge", "single-leg rdl", "single leg rdl", "single-leg romanian", "single leg romanian", "side plank")

    fun isExcluded(name: String): Boolean {
        val lower = name.lowercase()
        return EXCLUDED_NAME_FRAGMENTS.any { lower.contains(it) }
    }

    private fun ex(name: String, pattern: String, unilateral: Boolean, equipment: Equipment) =
        GymExercise(name = name, movementPattern = pattern, unilateral = unilateral, equipment = equipment.name, active = true)
}
