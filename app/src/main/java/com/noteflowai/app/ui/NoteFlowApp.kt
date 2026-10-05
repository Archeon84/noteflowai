package com.noteflowai.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.VerticalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import com.noteflowai.app.MainActivity
import com.noteflowai.app.R
import com.noteflowai.app.ui.screens.*
import com.noteflowai.app.ui.screens.chat.ChatScreen
import com.noteflowai.app.ui.screens.KnowledgeGraphScreen
import com.noteflowai.app.ui.components.OmniCaptureSheet
import androidx.compose.ui.platform.LocalLayoutDirection
import com.noteflowai.app.ui.theme.LocalNavigationMode
import com.noteflowai.app.ui.theme.rememberNavigationMode
import com.noteflowai.app.ui.theme.computeIsReducedMotionEnabled
import com.noteflowai.app.ui.theme.isReducedMotionEnabled
import com.noteflowai.app.viewmodel.MainViewModel
import com.noteflowai.app.viewmodel.YouTubeViewModel
import com.noteflowai.app.viewmodel.DocumentViewModel
import com.noteflowai.app.viewmodel.BackupDriveViewModel
import com.noteflowai.app.viewmodel.SlideViewModel

enum class Screen {
    HOME, RECORD, NOTES, NOTE_DETAIL, SCAN, YOUTUBE, DOCUMENT, CHAT, SETTINGS, GRAPH, MEMORY, DIGEST, MEMORY_INBOX, DECISION_TIMELINE, COMMITMENT_DASHBOARD, CHANGE_ANALYSIS, CONFLICT_DETECTION, WEEKLY_REVIEW, EVALUATION, TIMELINE, PRIVACY_DASHBOARD
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3WindowSizeClassApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun NoteFlowApp(
    viewModel: MainViewModel = viewModel(),
    widgetAction: MutableState<String?>? = null,
    deepLinkScreen: MutableState<String?>? = null,
    widgetOpenNoteFileName: MutableState<String?>? = null
) {
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var previousScreen by remember { mutableStateOf(Screen.HOME) }
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val windowSizeClass = calculateWindowSizeClass(activity)
    val isCompact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
    var showIntro by remember { mutableStateOf(!IntroPrefs.hasShown(context)) }
    var showOnboarding by remember {
        mutableStateOf(!showIntro && !OnboardingPrefs.hasShown(context))
    }
    // Permission request flow: intro -> permissions -> onboarding
    var introComplete by remember { mutableStateOf(!showIntro) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        showOnboarding = true
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // After mic permission, request notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showOnboarding = true
        }
    }
    // When intro completes, start permission request chain
    LaunchedEffect(introComplete) {
        if (!introComplete && !showIntro) {
            introComplete = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val youtubeViewModel: YouTubeViewModel = viewModel()
    val documentViewModel: DocumentViewModel = viewModel()
    val backupDriveViewModel: BackupDriveViewModel = viewModel()
    val slideViewModel: SlideViewModel = viewModel()
    // Wire delegation: MainViewModel.scheduleAutoSync → BackupDriveViewModel
    LaunchedEffect(backupDriveViewModel) { viewModel.backupDriveViewModel = backupDriveViewModel }

    // Scroll position restoration per screen
    val scrollStates = remember {
        mutableMapOf<Screen, androidx.compose.foundation.lazy.LazyListState>()
    }
    @androidx.compose.runtime.Composable
    fun scrollStateFor(screen: Screen): androidx.compose.foundation.lazy.LazyListState {
        return scrollStates.getOrPut(screen) {
            androidx.compose.foundation.lazy.rememberLazyListState()
        }
    }

    // Handle widget quick actions
    LaunchedEffect(widgetAction?.value) {
        val action = widgetAction?.value
        if (action != null) {
            when (action) {
                MainActivity.ACTION_WIDGET_RECORD -> {
                    currentScreen = Screen.RECORD
                    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.startRecording()
                    }
                }
                MainActivity.ACTION_WIDGET_NOTE -> {
                    currentScreen = Screen.NOTES
                    viewModel.showNewNoteDialog()
                }
            }
            widgetAction.value = null
        }
    }

    // Handle widget note-click: navigate to Notes screen and select the tapped note
    LaunchedEffect(widgetOpenNoteFileName?.value) {
        val fileName = widgetOpenNoteFileName?.value
        if (fileName != null) {
            currentScreen = Screen.NOTES
            viewModel.selectNoteByFileName(fileName)
            widgetOpenNoteFileName.value = null
        }
    }

    // Handle deep links
    LaunchedEffect(deepLinkScreen?.value) {
        val dl = deepLinkScreen?.value
        if (dl != null) {
            runCatching { currentScreen = Screen.valueOf(dl) }
            deepLinkScreen.value = null
        }
    }

    // Track originating tab for back navigation from modal screens
    val tabScreens = setOf(Screen.NOTES, Screen.CHAT, Screen.MEMORY, Screen.SETTINGS, Screen.HOME)
    LaunchedEffect(currentScreen) {
        if (currentScreen in tabScreens) {
            previousScreen = currentScreen
        }
    }

    // Nav-layer flag guards: hub cards hide disabled features, but deep
    // links and widgets can request any destination — bounce those to Memory.
    val decisionTimelineOn by viewModel.decisionTimelineEnabled.collectAsStateWithLifecycle()
    val commitmentDashboardOn by viewModel.commitmentDashboardEnabled.collectAsStateWithLifecycle()
    val changeAnalysisOn by viewModel.changeAnalysisEnabled.collectAsStateWithLifecycle()
    val conflictDetectionOn by viewModel.conflictDetectionEnabled.collectAsStateWithLifecycle()
    val weeklyReviewOn by viewModel.weeklyReviewEnabled.collectAsStateWithLifecycle()
    val evaluationOn by viewModel.evaluationEnabled.collectAsStateWithLifecycle()
    val digestOn by viewModel.dailyDigestEnabled.collectAsStateWithLifecycle()
    LaunchedEffect(
        currentScreen, decisionTimelineOn, commitmentDashboardOn,
        changeAnalysisOn, conflictDetectionOn, weeklyReviewOn, evaluationOn, digestOn
    ) {
        val allowed = when (currentScreen) {
            Screen.DECISION_TIMELINE -> decisionTimelineOn
            Screen.COMMITMENT_DASHBOARD -> commitmentDashboardOn
            Screen.CHANGE_ANALYSIS -> changeAnalysisOn
            Screen.CONFLICT_DETECTION -> conflictDetectionOn
            Screen.WEEKLY_REVIEW -> weeklyReviewOn
            Screen.EVALUATION -> evaluationOn
            Screen.DIGEST -> digestOn
            else -> true
        }
        if (!allowed) currentScreen = Screen.MEMORY
    }

    // Handle system back button: Go to Home unless already on Home
    BackHandler(enabled = currentScreen != Screen.HOME) {
        currentScreen = when (currentScreen) {
            Screen.NOTE_DETAIL -> {
                viewModel.clearSelection()
                Screen.NOTES
            }
            Screen.GRAPH, Screen.MEMORY_INBOX, Screen.DECISION_TIMELINE, Screen.COMMITMENT_DASHBOARD, Screen.CHANGE_ANALYSIS, Screen.CONFLICT_DETECTION, Screen.WEEKLY_REVIEW, Screen.EVALUATION, Screen.TIMELINE, Screen.PRIVACY_DASHBOARD -> Screen.MEMORY
            Screen.YOUTUBE, Screen.DOCUMENT, Screen.SCAN, Screen.RECORD, Screen.DIGEST -> previousScreen
            Screen.CHAT, Screen.MEMORY, Screen.SETTINGS, Screen.NOTES -> Screen.HOME
            else -> Screen.HOME
        }
    }

    val showNavBar = currentScreen in tabScreens
    val reducedMotion = remember { computeIsReducedMotionEnabled(context) }
    val navigationMode = rememberNavigationMode()

    // Tablet master-detail: when a note is selected on wider screens, show list + detail side by side
    val tabletSelectedNote by viewModel.selectedNote.collectAsStateWithLifecycle()
    val showTabletDetail = !isCompact && (currentScreen == Screen.NOTES || currentScreen == Screen.NOTE_DETAIL) && tabletSelectedNote != null

    var showOmniCaptureSheet by remember { mutableStateOf(false) }

    // Tablet: back in detail pane clears selection
    BackHandler(enabled = showTabletDetail && currentScreen == Screen.NOTES) {
        viewModel.clearSelection()
    }

    val navBarsPadding = WindowInsets.navigationBars.asPaddingValues()
    val navBarsBottom = navBarsPadding.calculateBottomPadding()
    val navBarsHorizontal = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal).asPaddingValues()

    val screenContent: @Composable (PaddingValues) -> Unit = { paddingValues ->
        val isImeVisible = WindowInsets.isImeVisible
        val layoutDirection = LocalLayoutDirection.current
        val scaffoldBottom = paddingValues.calculateBottomPadding()
        // When showNavBar is true, Scaffold's NavigationBar handles bottom spacing.
        // When showNavBar is false, ensure screens clear 3-button or gesture navigation.
        val effectiveBottom = when {
            isImeVisible -> 0.dp
            showNavBar -> scaffoldBottom
            else -> maxOf(scaffoldBottom, navBarsBottom)
        }
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(
                top = paddingValues.calculateTopPadding(),
                bottom = effectiveBottom,
                start = navBarsHorizontal.calculateStartPadding(layoutDirection),
                end = navBarsHorizontal.calculateEndPadding(layoutDirection)
            )
        ) {
            SharedTransitionLayout {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    val instantSpec = tween<Float>(durationMillis = 1, easing = LinearEasing)

                    // Detail screens (note, youtube, document): container transform — expand in
                    if (targetState == Screen.NOTE_DETAIL || targetState == Screen.YOUTUBE || targetState == Screen.DOCUMENT) {
                        if (reducedMotion) {
                            fadeIn(instantSpec) togetherWith fadeOut(instantSpec)
                        } else {
                            fadeIn(spring(stiffness = Spring.StiffnessLow)) + expandVertically(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            ) togetherWith fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium))
                        }
                    }
                    // Modal screens (chat): fade-through
                    else if (targetState == Screen.CHAT) {
                        if (reducedMotion) {
                            fadeIn(instantSpec) togetherWith fadeOut(instantSpec)
                        } else {
                            fadeIn(spring(stiffness = Spring.StiffnessLow)) togetherWith
                                fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium))
                        }
                    }
                    // Tab switches: shared axis X
                    else {
                        if (reducedMotion) {
                            fadeIn(instantSpec) togetherWith fadeOut(instantSpec)
                        } else {
                            slideInHorizontally(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessLow
                                ),
                                initialOffsetX = { if (targetState.ordinal > initialState.ordinal) it / 4 else -it / 4 }
                            ) + fadeIn(spring(stiffness = Spring.StiffnessLow)) togetherWith
                                slideOutHorizontally(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessLow
                                    ),
                                    targetOffsetX = { if (targetState.ordinal > initialState.ordinal) -it / 4 else it / 4 }
                                ) + fadeOut(spring(stiffness = Spring.StiffnessMedium))
                        }
                    }
                },
                label = "screenTransition",
                modifier = Modifier.fillMaxSize()
            ) { screen ->
                // Extract animatedVisibilityScope from AnimatedContent's receiver
                val animVisScope = this@AnimatedContent
                when (screen) {
                    Screen.HOME -> HomeScreen(
                        viewModel = viewModel,
                        onNavigate = { currentScreen = it },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = animVisScope
                    )
                    Screen.RECORD -> RecordScreen(viewModel = viewModel, lazyListState = scrollStateFor(Screen.RECORD))
                    Screen.NOTES -> NotesScreen(
                        viewModel = viewModel,
                        slideViewModel = slideViewModel,
                        lazyListState = scrollStateFor(Screen.NOTES),
                        onNavigate = { currentScreen = it },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = animVisScope
                    )
                    Screen.NOTE_DETAIL -> NoteDetailContent(
                        viewModel = viewModel,
                        slideViewModel = slideViewModel,
                        onBack = { currentScreen = Screen.NOTES },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = animVisScope
                    )
                    Screen.SCAN -> ScanScreen(viewModel = viewModel)
                    Screen.YOUTUBE -> YouTubeScreen(viewModel = youtubeViewModel, mainViewModel = viewModel, slideViewModel = slideViewModel, onBack = { currentScreen = previousScreen })
                    Screen.DOCUMENT -> DocumentScreen(viewModel = documentViewModel, onBack = { currentScreen = previousScreen })
                    Screen.CHAT -> ChatScreen(
                        viewModel = viewModel,
                        onBack = { currentScreen = previousScreen },
                        onOpenNote = { fileName ->
                            viewModel.selectNoteByFileName(fileName)
                            currentScreen = Screen.NOTE_DETAIL
                        },
                        onOpenSettings = { currentScreen = Screen.SETTINGS }
                    )
                    Screen.SETTINGS -> SettingsScreen(viewModel = viewModel, backupDriveViewModel = backupDriveViewModel)
                    Screen.GRAPH -> KnowledgeGraphScreen(
                        viewModel = viewModel,
                        onBack = { currentScreen = Screen.MEMORY },
                        onNoteClick = { fileName ->
                            viewModel.selectNoteByFileName(fileName)
                            currentScreen = Screen.NOTE_DETAIL
                        }
                    )
                    Screen.DIGEST -> DailyDigestScreen(
                        viewModel = viewModel,
                        onBack = { currentScreen = previousScreen },
                        onOpenNote = { fileName ->
                            viewModel.selectNoteByFileName(fileName)
                            currentScreen = Screen.NOTE_DETAIL
                        }
                    )
                    Screen.MEMORY -> MemoryHubScreen(
                        viewModel = viewModel,
                        onNavigate = { currentScreen = it },
                        onBack = { currentScreen = previousScreen }
                    )
                    Screen.MEMORY_INBOX -> {
                        val context = LocalContext.current
                        val reviewRepository = remember(context) { com.noteflowai.app.data.memory.repository.MemoryReviewRepository(context) }
                        val decisionRepository = remember(context) { com.noteflowai.app.data.memory.repository.DecisionRepository(context) }
                        val commitmentRepository = remember(context) { com.noteflowai.app.data.memory.repository.CommitmentRepository(context) }
                        val memoryRepository = remember(context) { com.noteflowai.app.data.memory.repository.MemoryRepository(context) }
                        val entityRepository = remember(context) { com.noteflowai.app.data.memory.repository.EntityRepository(context) }
                        MemoryInboxScreen(
                            reviewRepository = reviewRepository,
                            decisionRepository = decisionRepository,
                            commitmentRepository = commitmentRepository,
                            memoryRepository = memoryRepository,
                            entityRepository = entityRepository,
                            onBack = { currentScreen = Screen.MEMORY },
                            onOpenNote = { fileName ->
                                viewModel.selectNoteByFileName(fileName)
                                currentScreen = Screen.NOTE_DETAIL
                            }
                        )
                    }
                    Screen.TIMELINE -> {
                        TimelineScreen(
                            viewModel = viewModel,
                            onBack = { currentScreen = Screen.MEMORY },
                            onOpenNote = { fileName ->
                                viewModel.selectNoteByFileName(fileName)
                                currentScreen = Screen.NOTE_DETAIL
                            }
                        )
                    }
                    Screen.DECISION_TIMELINE -> {
                        val context = LocalContext.current
                        val decisionRepository = remember(context) { com.noteflowai.app.data.memory.repository.DecisionRepository(context) }
                        DecisionTimelineScreen(
                            decisionRepository = decisionRepository,
                            onBack = { currentScreen = Screen.MEMORY },
                            onOpenNote = { fileName ->
                                viewModel.selectNoteByFileName(fileName)
                                currentScreen = Screen.NOTE_DETAIL
                            }
                        )
                    }
                    Screen.COMMITMENT_DASHBOARD -> {
                        val context = LocalContext.current
                        val commitmentRepository = remember(context) { com.noteflowai.app.data.memory.repository.CommitmentRepository(context) }
                        CommitmentDashboardScreen(
                            commitmentRepository = commitmentRepository,
                            onBack = { currentScreen = Screen.MEMORY },
                            onOpenNote = { fileName ->
                                viewModel.selectNoteByFileName(fileName)
                                currentScreen = Screen.NOTE_DETAIL
                            }
                        )
                    }
                    Screen.CHANGE_ANALYSIS -> {
                        val context = LocalContext.current
                        com.noteflowai.app.ui.screens.ChangeAnalysisScreen(
                            context = context,
                            onBack = { currentScreen = Screen.MEMORY }
                        )
                    }
                    Screen.CONFLICT_DETECTION -> {
                        val context = LocalContext.current
                        val conflictRepository = remember(context) { com.noteflowai.app.data.memory.repository.ConflictRepository(context) }
                        com.noteflowai.app.ui.screens.ConflictDetectionScreen(
                            conflictRepository = conflictRepository,
                            onBack = { currentScreen = Screen.MEMORY },
                            onOpenNote = { fileName ->
                                viewModel.selectNoteByFileName(fileName)
                                currentScreen = Screen.NOTE_DETAIL
                            }
                        )
                    }
                    Screen.WEEKLY_REVIEW -> {
                        val context = LocalContext.current
                        com.noteflowai.app.ui.screens.WeeklyReviewScreen(
                            context = context,
                            onBack = { currentScreen = Screen.MEMORY }
                        )
                    }
                    Screen.EVALUATION -> {
                        com.noteflowai.app.ui.screens.EvaluationScreen(
                            viewModel = viewModel,
                            onBack = { currentScreen = Screen.MEMORY }
                        )
                    }
                    Screen.PRIVACY_DASHBOARD -> {
                        com.noteflowai.app.ui.screens.privacy.PrivacyDashboardScreen(
                            viewModel = viewModel,
                            backupDriveViewModel = backupDriveViewModel,
                            onBack = { currentScreen = Screen.MEMORY }
                        )
                    }
                }
            }
            } // SharedTransitionLayout
        }
    }

    CompositionLocalProvider(LocalNavigationMode provides navigationMode) {
        if (isCompact) {
            Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showNavBar) {
                    NavigationBar {
                        // 1. HOME
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_desc_home)) },
                            label = { Text(stringResource(R.string.nav_label_home)) },
                            selected = currentScreen == Screen.HOME,
                            onClick = { currentScreen = Screen.HOME }
                        )
                        // 2. LIBRARY
                        NavigationBarItem(
                            icon = { Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = stringResource(R.string.nav_desc_library)) },
                            label = { Text(stringResource(R.string.nav_label_library)) },
                            selected = currentScreen == Screen.NOTES || currentScreen == Screen.NOTE_DETAIL,
                            onClick = { currentScreen = Screen.NOTES }
                        )
                        // 3. OMNI-CAPTURE (Center Speed-Dial Action)
                        NavigationBarItem(
                            icon = {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(36.dp),
                                    shadowElevation = 2.dp
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = stringResource(R.string.nav_desc_capture),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            },
                            label = { Text(stringResource(R.string.nav_label_capture), fontWeight = FontWeight.Bold) },
                            selected = false,
                            onClick = { showOmniCaptureSheet = true }
                        )
                        // 4. RECALL
                        NavigationBarItem(
                            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = stringResource(R.string.nav_desc_recall)) },
                            label = { Text(stringResource(R.string.nav_label_recall)) },
                            selected = currentScreen == Screen.CHAT,
                            onClick = { currentScreen = Screen.CHAT }
                        )
                        // 5. INSIGHTS
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Psychology, contentDescription = stringResource(R.string.nav_desc_insights)) },
                            label = { Text(stringResource(R.string.nav_label_insights)) },
                            selected = currentScreen == Screen.MEMORY,
                            onClick = { currentScreen = Screen.MEMORY }
                        )
                    }
                }
            }
        ) { paddingValues -> screenContent(paddingValues) }
    } else {
        Row(modifier = Modifier.fillMaxSize()) {
            if (showNavBar) {
                NavigationRail {
                    // 1. HOME
                    NavigationRailItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_desc_home)) },
                        label = { Text(stringResource(R.string.nav_label_home)) },
                        selected = currentScreen == Screen.HOME,
                        onClick = { currentScreen = Screen.HOME }
                    )
                    // 2. LIBRARY
                    NavigationRailItem(
                        icon = { Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = stringResource(R.string.nav_desc_library)) },
                        label = { Text(stringResource(R.string.nav_label_library)) },
                        selected = currentScreen == Screen.NOTES || currentScreen == Screen.NOTE_DETAIL,
                        onClick = { currentScreen = Screen.NOTES }
                    )
                    // 3. OMNI-CAPTURE (Center Speed-Dial Action)
                    NavigationRailItem(
                        icon = {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(36.dp),
                                shadowElevation = 2.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = stringResource(R.string.nav_desc_capture),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        },
                        label = { Text(stringResource(R.string.nav_label_capture), fontWeight = FontWeight.Bold) },
                        selected = false,
                        onClick = { showOmniCaptureSheet = true }
                    )
                    // 4. RECALL
                    NavigationRailItem(
                        icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = stringResource(R.string.nav_desc_recall)) },
                        label = { Text(stringResource(R.string.nav_label_recall)) },
                        selected = currentScreen == Screen.CHAT,
                        onClick = { currentScreen = Screen.CHAT }
                    )
                    // 5. INSIGHTS
                    NavigationRailItem(
                        icon = { Icon(Icons.Default.Psychology, contentDescription = stringResource(R.string.nav_desc_insights)) },
                        label = { Text(stringResource(R.string.nav_label_insights)) },
                        selected = currentScreen == Screen.MEMORY,
                        onClick = { currentScreen = Screen.MEMORY }
                    )
                }
            }
            // Master-detail pane: Notes list (40%) + NoteDetail (60%) side by side
            if (showTabletDetail) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
                        SharedTransitionLayout {
                            AnimatedContent(
                                targetState = Screen.NOTES,
                                transitionSpec = {
                                    fadeIn(tween(1)) togetherWith fadeOut(tween(1))
                                },
                                label = "tabletNotesList",
                                modifier = Modifier.fillMaxSize()
                            ) {
                                NotesScreen(
                                    viewModel = viewModel,
                                    slideViewModel = slideViewModel,
                                    lazyListState = scrollStateFor(Screen.NOTES),
                                    onNavigate = { currentScreen = it },
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = this@AnimatedContent
                                )
                            }
                        }
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
                        SharedTransitionLayout {
                            AnimatedContent(
                                targetState = Screen.NOTE_DETAIL,
                                transitionSpec = {
                                    fadeIn(tween(1)) togetherWith fadeOut(tween(1))
                                },
                                label = "tabletNoteDetail",
                                modifier = Modifier.fillMaxSize()
                            ) {
                                NoteDetailContent(
                                    viewModel = viewModel,
                                    slideViewModel = slideViewModel,
                                    onBack = { viewModel.clearSelection() },
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = this@AnimatedContent
                                )
                            }
                        }
                    }
                }
            } else {
                screenContent(WindowInsets.navigationBars.asPaddingValues())
            }
        }
    }

    if (showIntro) {
        IntroScreen(onComplete = {
            IntroPrefs.markShown(context)
            OnboardingPrefs.markShown(context)
            showIntro = false
        })
    } else if (showOnboarding) {
        OnboardingScreen(onDismiss = {
            OnboardingPrefs.markShown(context)
            showOnboarding = false
        })
    }

    if (showOmniCaptureSheet) {
        OmniCaptureSheet(
            onDismiss = { showOmniCaptureSheet = false },
            onRecordVoice = {
                currentScreen = Screen.RECORD
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    viewModel.startRecording()
                }
            },
            onNewNote = {
                currentScreen = Screen.NOTES
                viewModel.showNewNoteDialog()
            },
            onScan = {
                currentScreen = Screen.SCAN
            },
            onImportDocument = {
                currentScreen = Screen.DOCUMENT
            },
            onYouTube = {
                currentScreen = Screen.YOUTUBE
            }
        )
    }
    } // CompositionLocalProvider
}
