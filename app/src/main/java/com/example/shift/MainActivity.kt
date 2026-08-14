package com.example.shift

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.Composable
import com.example.shift.theme.ShiftBg
import com.example.shift.theme.ShiftOrange
import com.example.shift.theme.ShiftTextSecondary
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.example.shift.data.CourseManager
import com.example.shift.data.SettingsManager
import com.example.shift.theme.ShiftTheme
import com.example.shift.ui.*
import com.example.shift.ui.gym.GymViewModel
import com.example.shift.ui.gym.GymHistoryScreen
import com.example.shift.ui.gym.GymHistoryViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        
        val settingsManager = SettingsManager(applicationContext)
        val courseManager = CourseManager(applicationContext)
        val matchCacheManager = com.example.shift.data.MatchCacheManager(applicationContext)
        val cloudSyncManager = com.example.shift.data.CloudSyncManager(applicationContext)

        setContent {
            ShiftTheme {
                val navController = rememberNavController()
                val mainViewModel: MainViewModel = viewModel(factory = MainViewModelFactory(application, settingsManager, matchCacheManager, courseManager, cloudSyncManager))
                val courseViewModel: CourseViewModel = viewModel(factory = CourseViewModelFactory(settingsManager, courseManager, matchCacheManager))
                val coursesListViewModel: CoursesListViewModel = viewModel(factory = CoursesListViewModelFactory(courseManager, matchCacheManager))
                val routeCreatorViewModel: RouteCreatorViewModel = viewModel(factory = RouteCreatorViewModelFactory(settingsManager))
                
                val appDb = (application as com.example.shift.ShiftApplication).database
                val workoutRepo = com.example.shift.data.repository.WorkoutRepository(appDb.workoutDao(), appDb.stepDao())
                val exerciseRepo = com.example.shift.data.repository.ExerciseRepository(appDb.exerciseDao(), appDb.exerciseLogDao())
                val doneRepo = com.example.shift.data.repository.DoneRepository(appDb.doneDao())
                val coachHistoryManager = com.example.shift.data.CoachHistoryManager(applicationContext)
                val coachMemoryManager = com.example.shift.data.CoachMemoryManager(applicationContext)
                val aiCoachViewModel: com.example.shift.ui.AiCoachViewModel = viewModel(factory = com.example.shift.ui.AiCoachViewModelFactory(mainViewModel, workoutRepo, exerciseRepo, coachHistoryManager, coachMemoryManager, matchCacheManager, courseManager, doneRepo))

                LaunchedEffect(Unit) {
                    aiCoachViewModel.navigationEvent.collect { workoutId ->
                        navController.navigate("builder?workoutId=$workoutId")
                    }
                }

                val isKaroo = android.os.Build.MANUFACTURER.contains("Hammerhead", ignoreCase = true) || android.os.Build.MODEL.contains("Karoo", ignoreCase = true)

                val tabs = if (isKaroo) listOf("Segments", "Settings") else listOf("Activity", "Gym", "Hub", "Coach", "Segments", "Settings")
                val tabIconsSelected = if (isKaroo) listOf(
                    Icons.Default.Map,
                    Icons.Default.Settings
                ) else listOf(
                    Icons.Default.ShowChart,
                    Icons.Default.FitnessCenter,
                    Icons.Default.Hub,
                    Icons.Default.AutoAwesome,
                    Icons.Default.Map,
                    Icons.Default.Settings
                )
                val tabIconsUnselected = if (isKaroo) listOf(
                    Icons.Outlined.Map,
                    Icons.Outlined.Settings
                ) else listOf(
                    Icons.Outlined.ShowChart,
                    Icons.Outlined.FitnessCenter,
                    Icons.Outlined.Hub,
                    Icons.Outlined.AutoAwesome,
                    Icons.Outlined.Map,
                    Icons.Outlined.Settings
                )

                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        val pagerState = rememberPagerState(
                            initialPage = if (isKaroo) 0 else 2,
                            pageCount = { tabs.size }
                        )
                        val coroutineScope = rememberCoroutineScope()

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(ShiftBg)
                                .statusBarsPadding()
                        ) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize(),
                                userScrollEnabled = true
                            ) { page ->
                                if (isKaroo) {
                                    when (page) {
                                        0 -> CoursesScreen(
                                            viewModel = coursesListViewModel,
                                            onCreateCourse = { navController.navigate("create_course/new") },
                                            onOpenRoutePlanner = { navController.navigate("route_planner") },
                                            onCourseClick = { courseId -> navController.navigate("course_detail/$courseId") }
                                        )
                                        1 -> SettingsScreen(viewModel = mainViewModel)
                                    }
                                } else {
                                    when (page) {
                                        0 -> RidesScreen(
                                            viewModel = mainViewModel,
                                            onActivityClick = { activity ->
                                                val isWorkout = activity.type == "WeightTraining" || activity.type == "Yoga" || activity.type == "Workout" || activity.id.startsWith("gym_")
                                                if (isWorkout) {
                                                    val dateStr = activity.start_date_local.take(10)
                                                    val eventId = if (activity.id.startsWith("gym_")) "null" else activity.id
                                                    navController.navigate("gym_history/$dateStr?eventId=$eventId")
                                                } else if (activity.id.startsWith("hc_")) {
                                                    navController.navigate("run_stats/${activity.id}")
                                                } else {
                                                    navController.navigate("map/${activity.id}")
                                                }
                                            }
                                        )
                                        1 -> com.example.shift.ui.gym.GymScreen(
                                            onNewWorkout = { navController.navigate("builder") },
                                            onEditWorkout = { workoutId -> navController.navigate("builder?workoutId=$workoutId") },
                                            onRunWorkout = { id -> navController.navigate("runner/$id") }
                                        )
                                        2 -> StatsScreen(
                                            viewModel = mainViewModel,
                                            onNavigateToCoach = { prompt ->
                                                aiCoachViewModel.sendMessage(prompt)
                                                coroutineScope.launch {
                                                    pagerState.animateScrollToPage(3)
                                                }
                                            },
                                            onCreateTemplate = { title, durationMin, exercises, isFlow ->
                                                aiCoachViewModel.createTemplateFromRoutine(title, durationMin, exercises, isFlow)
                                            },
                                            onNavigateToGym = {
                                                coroutineScope.launch {
                                                    pagerState.animateScrollToPage(1)
                                                }
                                            }
                                        )
                                        3 -> com.example.shift.ui.AiCoachScreen(
                                            viewModel = aiCoachViewModel, 
                                            mainViewModel = mainViewModel,
                                            onNavigateToBuilder = { workoutId -> navController.navigate("builder?workoutId=$workoutId") }
                                        )
                                        4 -> CoursesScreen(
                                            viewModel = coursesListViewModel,
                                            onCreateCourse = { navController.navigate("create_course/new") },
                                            onOpenRoutePlanner = { navController.navigate("route_planner") },
                                            onCourseClick = { courseId -> navController.navigate("course_detail/$courseId") }
                                        )
                                        5 -> SettingsScreen(viewModel = mainViewModel)
                                    }
                                }
                            }

                            // Floating Blur Pill Navigation Overlay
                            FloatingBottomNav(
                                tabs = tabs,
                                tabIcons = tabIconsSelected,
                                selectedIndex = pagerState.currentPage,
                                onTabSelected = { index ->
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 20.dp)
                            )
                        }
                    }
                    composable(
                        route = "builder?workoutId={workoutId}",
                        arguments = listOf(
                            navArgument("workoutId") { 
                                type = NavType.StringType
                                nullable = true
                            }
                        )
                    ) { backStackEntry ->
                        val workoutId = backStackEntry.arguments?.getString("workoutId")
                        com.example.shift.ui.builder.BuilderScreen(
                            category = "gym",
                            workoutId = workoutId,
                            onBack = { navController.popBackStack() },
                            onRun = { id -> navController.navigate("runner/$id") {
                                popUpTo("home")
                            } }
                        )
                    }
                    composable("runner/{workoutId}") { backStackEntry ->
                        val workoutId = backStackEntry.arguments?.getString("workoutId") ?: return@composable
                        com.example.shift.ui.runner.RunnerScreen(
                            workoutId = workoutId,
                            onBack = { navController.popBackStack("home", inclusive = false) }
                        )
                    }
                    composable("pick_ride") {
                        PickRideScreen(
                            viewModel = mainViewModel,
                            onBack = { navController.popBackStack() },
                            onRidePicked = { activityId -> navController.navigate("create_course/$activityId") }
                        )
                    }
                    composable("course_detail/{courseId}") { backStackEntry ->
                        val courseId = backStackEntry.arguments?.getString("courseId") ?: return@composable
                        val factory = CourseDetailViewModelFactory(
                            application = application,
                            courseId = courseId,
                            settingsManager = settingsManager,
                            courseManager = courseManager,
                            mainViewModel = mainViewModel
                        )
                        val detailViewModel: CourseDetailViewModel = viewModel(factory = factory)
                        CourseDetailScreen(
                            viewModel = detailViewModel,
                            onBack = { navController.popBackStack() },
                            onEditCourse = { cId, aId -> navController.navigate("edit_course/$cId/$aId") },
                            onMatchClick = { activityId ->
                                if (activityId.startsWith("hc_")) {
                                    navController.navigate("run_stats/$activityId")
                                } else {
                                    navController.navigate("map/$activityId")
                                }
                            }
                        )
                    }
                    composable(
                        "gym_history/{dateStr}?eventId={eventId}",
                        arguments = listOf(
                            navArgument("dateStr") { type = NavType.StringType },
                            navArgument("eventId") { type = NavType.StringType; nullable = true; defaultValue = null }
                        )
                    ) { backStackEntry ->
                        val dateStr = backStackEntry.arguments?.getString("dateStr") ?: ""
                        val eventId = backStackEntry.arguments?.getString("eventId")
                        val factory = GymHistoryViewModel.factory(dateStr, eventId)
                        val vm: GymHistoryViewModel = viewModel(factory = factory)
                        GymHistoryScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("run_stats/{activityId}") { backStackEntry ->
                        val activityId = backStackEntry.arguments?.getString("activityId") ?: return@composable
                        com.example.shift.ui.RunStatsScreen(
                            activityId = activityId,
                            viewModel = mainViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("map/{activityId}") { backStackEntry ->
                        val activityId = backStackEntry.arguments?.getString("activityId") ?: return@composable
                        val healthActivity = if (activityId.startsWith("hc_")) {
                            mainViewModel.activities.value.find { it.id == activityId }
                        } else null

                        androidx.compose.runtime.LaunchedEffect(activityId) {
                            if (healthActivity != null) {
                                courseViewModel.loadActivityDirectly(healthActivity)
                            } else {
                                courseViewModel.loadActivity(activityId)
                            }
                        }

                        MapScreen(
                            activityId = activityId,
                            courseId = null,
                            viewModel = courseViewModel,
                            mainViewModel = mainViewModel,
                            onBack = { navController.popBackStack() },
                            isCreationMode = false,
                            onDelete = {
                                mainViewModel.deleteActivity(activityId)
                                navController.popBackStack()
                            },
                            onCourseClick = { cId -> navController.navigate("course_detail/$cId") }
                        )

                    }
                    composable("route_planner") {
                        RouteCreatorScreen(
                            viewModel = routeCreatorViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("create_course/{activityId}") { backStackEntry ->
                        val activityId = backStackEntry.arguments?.getString("activityId") ?: return@composable
                        MapScreen(
                            activityId = activityId,
                            courseId = null,
                            viewModel = courseViewModel,
                            mainViewModel = mainViewModel,
                            onBack = { navController.popBackStack() },
                            isCreationMode = true
                        )
                    }
                    composable("edit_course/{courseId}/{activityId}") { backStackEntry ->
                        val courseId = backStackEntry.arguments?.getString("courseId") ?: return@composable
                        val activityId = backStackEntry.arguments?.getString("activityId") ?: return@composable
                        MapScreen(
                            activityId = activityId,
                            courseId = courseId,
                            viewModel = courseViewModel,
                            mainViewModel = mainViewModel,
                            onBack = { navController.popBackStack() },
                            isCreationMode = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FloatingBottomNav(
    tabs: List<String>,
    tabIcons: List<ImageVector>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(999.dp),
                spotColor = Color.Black.copy(alpha = 0.16f)
            )
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.88f))
            .border(1.dp, Color(0xCCFFFFFF), RoundedCornerShape(999.dp))
            .padding(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedIndex == index
                val icon = tabIcons[index]

                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) ShiftOrange else Color.Transparent)
                        .clickable { onTabSelected(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = if (isSelected) Color.White else ShiftTextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
