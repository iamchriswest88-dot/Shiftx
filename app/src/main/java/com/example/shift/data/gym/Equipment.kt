package com.example.shift.data.gym

/**
 * What can actually be lifted. Every load the planner is allowed to name comes
 * from here, and anything else is rejected before it reaches the screen.
 *
 * Loads are per implement: one kettlebell, one dumbbell (a pair means the same
 * number in each hand), one plate, or the cable's single stack.
 */
enum class Equipment(val label: String) {
    KETTLEBELL("Kettlebell"),
    DUMBBELL("Dumbbell"),
    PLATE("Plate"),
    CABLE("Cable"),
    BODYWEIGHT("Bodyweight");
}

object Gear {
    /** Kettlebells on hand, heaviest 12kg. Edit here when a new bell arrives. */
    val KETTLEBELLS_KG: List<Double> = listOf(4.0, 6.0, 8.0, 10.0, 12.0)

    /** Adjustable pair, 2.5kg steps to 10kg. */
    val DUMBBELLS_KG: List<Double> = listOf(2.5, 5.0, 7.5, 10.0)

    /** Two 10kg plates: one on the hips, or both. */
    val PLATES_KG: List<Double> = listOf(10.0, 20.0)

    /** Rayofi portable cable machine: 3–30kg, 1kg steps, constant tension both ways. */
    val CABLE_KG: List<Double> = (3..30).map { it.toDouble() }

    fun loadsFor(equipment: Equipment): List<Double> = when (equipment) {
        Equipment.KETTLEBELL -> KETTLEBELLS_KG
        Equipment.DUMBBELL -> DUMBBELLS_KG
        Equipment.PLATE -> PLATES_KG
        Equipment.CABLE -> CABLE_KG
        Equipment.BODYWEIGHT -> emptyList()
    }

    /** True when [kg] is a load that exists for [equipment]. Bodyweight accepts null or zero only. */
    fun isAvailable(equipment: Equipment, kg: Double?): Boolean {
        if (equipment == Equipment.BODYWEIGHT) return kg == null || kg == 0.0
        if (kg == null) return false
        return loadsFor(equipment).any { kotlin.math.abs(it - kg) < 0.01 }
    }

    /** The next heavier load that exists, or null when [kg] is already the heaviest. */
    fun nextLoad(equipment: Equipment, kg: Double?): Double? {
        if (equipment == Equipment.BODYWEIGHT || kg == null) return null
        return loadsFor(equipment).firstOrNull { it > kg + 0.01 }
    }

    /** The heaviest load that exists for [equipment], for clamping. */
    fun maxLoad(equipment: Equipment): Double? = loadsFor(equipment).maxOrNull()

    /** Increment used when stepping past the loads on the list. */
    fun stepKg(equipment: Equipment): Double = when (equipment) {
        Equipment.KETTLEBELL -> 2.0
        Equipment.DUMBBELL -> 2.5
        Equipment.PLATE -> 10.0
        Equipment.CABLE -> 1.0
        Equipment.BODYWEIGHT -> 1.0
    }

    /**
     * The next load up or down from [kg] for the runner's stepper. Snaps to
     * owned loads while inside the list, then keeps going by [stepKg] above it:
     * the list caps what gets recommended, not what can be logged. Stepping
     * below the lightest load lands on null, bodyweight.
     */
    fun steppedLoad(equipment: Equipment, kg: Double?, up: Boolean): Double? {
        val loads = loadsFor(equipment)
        val step = stepKg(equipment)
        if (kg == null) return if (up) (loads.firstOrNull() ?: step) else null
        return if (up) {
            loads.firstOrNull { it > kg + 0.01 } ?: (kg + step)
        } else {
            loads.lastOrNull { it < kg - 0.01 }?.let { return it }
            // Above the list, keep stepping down; at or below its lightest load, bodyweight.
            val lightest = loads.firstOrNull()
            if (lightest == null || kg > lightest + 0.01) (kg - step).takeIf { it > 0.01 } else null
        }
    }

    /** One-line inventory for the planner prompt. */
    fun describe(): String = buildString {
        appendLine("- Kettlebells: ${KETTLEBELLS_KG.joinToString(", ") { fmt(it) }} kg (heaviest 12kg)")
        appendLine("- Dumbbells (pair): ${DUMBBELLS_KG.joinToString(", ") { fmt(it) }} kg each")
        appendLine("- Plates: 2 x 10kg (so 10kg or 20kg)")
        append("- Rayofi portable cable machine: single cable, door anchor, rope handle, ankle strap, 3–30kg in 1kg steps, constant tension both directions. Single cable means one arm at a time; left and right are logged separately.")
    }

    fun fmt(kg: Double): String = if (kg % 1.0 == 0.0) kg.toInt().toString() else kg.toString()
}
