package com.example.shift.data.db

import com.example.shift.data.model.Exercise

object SeedData {

    val GYM_EXERCISES: List<Exercise> = listOf(
        e("sg01", "Push-ups",            "Chest",     "gym", "Bodyweight", false),
        e("sg02", "Squats",              "Quads",     "gym", "Bodyweight", false),
        e("sg03", "Lunges",              "Quads",     "gym", "Bodyweight", true),
        e("sg04", "Plank",               "Core",      "gym", "Bodyweight", false),
        e("sg05", "Glute Bridge",        "Glutes",    "gym", "Bodyweight", false),
        e("sg06", "Bird Dog",            "Core",      "gym", "Bodyweight", true),
        e("sg07", "Calf Raise",          "Calves",    "gym", "Bodyweight", false),
        e("sg08", "Dead Bug",            "Core",      "gym", "Bodyweight", false),
        e("sg09", "DB Bicep Curl",       "Biceps",    "gym", "Dumbbells",  false),
        e("sg10", "DB Hammer Curl",      "Biceps",    "gym", "Dumbbells",  false),
        e("sg11", "DB Shoulder Press",   "Shoulders", "gym", "Dumbbells",  false),
        e("sg12", "DB Lateral Raise",    "Shoulders", "gym", "Dumbbells",  false),
        e("sg13", "DB Front Raise",      "Shoulders", "gym", "Dumbbells",  false),
        e("sg14", "DB Bent-over Row",    "Back",      "gym", "Dumbbells",  false),
        e("sg15", "DB Single-arm Row",   "Back",      "gym", "Dumbbells",  true),
        e("sg16", "DB Floor Chest Press","Chest",     "gym", "Dumbbells",  false),
        e("sg17", "DB Chest Fly",        "Chest",     "gym", "Dumbbells",  false),
        e("sg18", "DB Pullover",         "Back",      "gym", "Dumbbells",  false),
        e("sg19", "DB Goblet Squat",     "Quads",     "gym", "Dumbbells",  false),
        e("sg20", "DB Romanian Deadlift","Hamstrings","gym", "Dumbbells",  false),
        e("sg21", "DB Lunge",            "Quads",     "gym", "Dumbbells",  true),
        e("sg22", "DB Step-up",          "Glutes",    "gym", "Dumbbells",  true),
        e("sg23", "DB Overhead Triceps", "Triceps",   "gym", "Dumbbells",  false),
        e("sg24", "DB Triceps Kickback", "Triceps",   "gym", "Dumbbells",  false),
        e("sg25", "DB Shrug",            "Traps",     "gym", "Dumbbells",  false),
        e("sg26", "DB Renegade Row",     "Back",      "gym", "Dumbbells",  false),
        e("sg27", "DB Thruster",         "Full body", "gym", "Dumbbells",  false),
        e("sg28", "DB Russian Twist",    "Core",      "gym", "Dumbbells",  false),
    )

    val FLOW_EXERCISES: List<Exercise> = listOf(
        e("sf01", "Downward Dog",       "Spine",      "flow", "Stretch", false),
        e("sf02", "Child's Pose",       "Hips",       "flow", "Stretch", false),
        e("sf03", "Cobra",              "Spine",      "flow", "Stretch", false),
        e("sf04", "Cat\u2013Cow",       "Spine",      "flow", "Stretch", false),
        e("sf05", "Forward Fold",       "Hamstrings", "flow", "Stretch", false),
        e("sf06", "Low Lunge",          "Hips",       "flow", "Stretch", true),
        e("sf07", "Warrior I",          "Legs",       "flow", "Stretch", true),
        e("sf08", "Warrior II",         "Legs",       "flow", "Stretch", true),
        e("sf09", "Triangle",           "Hips",       "flow", "Stretch", true),
        e("sf10", "Pigeon",             "Hips",       "flow", "Stretch", true),
        e("sf11", "Seated Twist",       "Spine",      "flow", "Stretch", true),
        e("sf12", "Bridge Pose",        "Spine",      "flow", "Stretch", false),
        e("sf13", "Tree Pose",          "Balance",    "flow", "Stretch", true),
        e("sf14", "Boat Pose",          "Core",       "flow", "Stretch", false),
        e("sf15", "Happy Baby",         "Hips",       "flow", "Stretch", false),
        e("sf16", "Savasana",           "Full body",  "flow", "Stretch", false),
        e("sf17", "Hamstring Stretch",  "Hamstrings", "flow", "Stretch", true),
        e("sf18", "Quad Stretch",       "Quads",      "flow", "Stretch", true),
        e("sf19", "Hip Flexor Stretch", "Hips",       "flow", "Stretch", true),
        e("sf20", "Chest Opener",       "Chest",      "flow", "Stretch", false),
        e("sf21", "Shoulder Stretch",   "Shoulders",  "flow", "Stretch", true),
        e("sf22", "Neck Stretch",       "Neck",       "flow", "Stretch", true),
        e("sf23", "Calf Stretch",       "Calves",     "flow", "Stretch", true),
        e("sf24", "Butterfly",          "Hips",       "flow", "Stretch", false),
        e("sf25", "Figure-4",           "Hips",       "flow", "Stretch", true),
        e("sf26", "Side Bend",          "Spine",      "flow", "Stretch", true),
        e("sf27", "Supine Twist",       "Spine",      "flow", "Stretch", true),
    )

    val ALL_AREAS = listOf(
        "Chest", "Back", "Shoulders", "Biceps", "Triceps", "Traps",
        "Core", "Legs", "Glutes", "Hamstrings", "Quads", "Calves",
        "Hips", "Spine", "Neck", "Balance", "Full body", "Other"
    )

    private fun e(id: String, name: String, area: String, category: String, equipment: String, isUnilateral: Boolean) =
        Exercise(
            id = java.util.UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8)).toString(), 
            name = name, 
            area = area, 
            category = category, 
            equipment = equipment,
            isUnilateral = isUnilateral,
            isCustom = false
        )
}
