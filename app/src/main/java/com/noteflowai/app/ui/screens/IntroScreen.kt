package com.noteflowai.app.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noteflowai.app.R
import com.noteflowai.app.ui.theme.*
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------

object IntroPrefs {
    private const val PREF_NAME = "intro"
    private const val KEY_SHOWN = "has_shown"

    fun hasShown(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOWN, false)

    fun markShown(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOWN, true).apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOWN, false).apply()
    }
}

// ---------------------------------------------------------------------------
// Main composable
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IntroScreen(onComplete: () -> Unit) {
    val totalPages = 4
    val pagerState = rememberPagerState(pageCount = { totalPages })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val navBarsHorizontal = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal).asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current

    BackHandler {
        if (pagerState.currentPage > 0) {
            scope.launch {
                pagerState.animateScrollToPage(pagerState.currentPage - 1)
            }
        } else {
            (context as? android.app.Activity)?.finish()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Pager Content
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> IntroPageWelcome()
                1 -> IntroPageFeatures()
                2 -> IntroPageTutorial()
                3 -> IntroPagePermissions()
            }
        }

        // Top Navigation Header (Progress counter + Quick Skip)
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(
                    start = AppSpacing.xl + navBarsHorizontal.calculateStartPadding(layoutDirection),
                    end = AppSpacing.xl + navBarsHorizontal.calculateEndPadding(layoutDirection),
                    top = AppSpacing.sm,
                    bottom = AppSpacing.sm
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(AppRadius.small),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            ) {
                Text(
                    text = "${pagerState.currentPage + 1} / $totalPages",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs)
                )
            }

            if (pagerState.currentPage < totalPages - 1) {
                TextButton(
                    onClick = {
                        Haptics.tap(context)
                        onComplete()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.intro_skip),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }
        }

        // Bottom Controls Bar (Pill Indicator + Back/Next buttons)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(
                        start = AppSpacing.xl + navBarsHorizontal.calculateStartPadding(layoutDirection),
                        end = AppSpacing.xl + navBarsHorizontal.calculateEndPadding(layoutDirection),
                        top = AppSpacing.md,
                        bottom = AppSpacing.md
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Animated Pill Page Indicator
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = AppSpacing.md)
                ) {
                    repeat(totalPages) { index ->
                        DotIndicator(isSelected = pagerState.currentPage == index)
                    }
                }

                // Action Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pagerState.currentPage > 0) {
                        TextButton(
                            onClick = {
                                Haptics.tap(context)
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(AppSpacing.xs))
                            Text(
                                text = stringResource(R.string.intro_back),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    } else {
                        // On first page, placeholder keeps Next button aligned to the right
                        Spacer(Modifier.width(60.dp))
                    }

                    Button(
                        onClick = {
                            if (pagerState.currentPage < totalPages - 1) {
                                Haptics.tap(context)
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            } else {
                                Haptics.confirm(context)
                                onComplete()
                            }
                        },
                        shape = RoundedCornerShape(AppRadius.medium),
                        contentPadding = PaddingValues(horizontal = AppSpacing.xl, vertical = AppSpacing.md)
                    ) {
                        if (pagerState.currentPage == totalPages - 1) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(AppSpacing.sm))
                            Text(
                                text = stringResource(R.string.intro_get_started),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.intro_next),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(AppSpacing.xs))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pages
// ---------------------------------------------------------------------------

/**
 * Page 0: Welcome Screen with animated halo emblem and core value propositions.
 */
@Composable
private fun IntroPageWelcome() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    CenteredPage {
        AnimatedIntroContent(visible) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                val reducedMotion = isReducedMotionEnabled()
                val isDark = !isLightBg(MaterialTheme.colorScheme.background)
                val gradient = if (isDark) AiGradientDark else AiGradientLight
                val accent = if (isDark) AiAccentDark else AiAccentLight

                val infiniteTransition = rememberInfiniteTransition(label = "welcomePulse")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = if (reducedMotion) 1f else 1.06f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseScale"
                )

                // Glowing Halo Emblem
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(128.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        accent.copy(alpha = 0.35f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(92.dp),
                        shadowElevation = 6.dp
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = accent
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.xl))

                Surface(
                    shape = RoundedCornerShape(AppRadius.small),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                ) {
                    Text(
                        text = "NEXT-GEN PERSONAL MEMORY OS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs)
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        brush = Brush.horizontalGradient(gradient)
                    ),
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(AppSpacing.xs))

                Text(
                    text = stringResource(R.string.intro_welcome_headline),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                Text(
                    text = stringResource(R.string.intro_welcome_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(horizontal = AppSpacing.sm)
                )

                Spacer(modifier = Modifier.height(AppSpacing.xl))

                // Value proposition chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm, Alignment.CenterHorizontally)
                ) {
                    PillBadge(icon = Icons.Default.Lock, text = stringResource(R.string.intro_badge_private))
                    PillBadge(icon = Icons.Default.CloudOff, text = stringResource(R.string.intro_badge_offline))
                }
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm, Alignment.CenterHorizontally)
                ) {
                    PillBadge(icon = Icons.Default.Speed, text = stringResource(R.string.intro_badge_fast))
                    PillBadge(icon = Icons.Default.CheckCircle, text = stringResource(R.string.intro_badge_no_sub))
                }

                Spacer(modifier = Modifier.height(AppSpacing.xl))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.intro_welcome_swipe_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

/**
 * Page 1: Top Best Features showcasing NoteFlow AI superpowers.
 */
@Composable
private fun IntroPageFeatures() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    CenteredPage {
        AnimatedIntroContent(visible) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.intro_features_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = stringResource(R.string.intro_features_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(AppSpacing.xl))

                FeatureHighlightCard(
                    icon = Icons.Default.Mic,
                    title = stringResource(R.string.intro_feature_omni_title),
                    badge = stringResource(R.string.intro_feature_omni_badge),
                    description = stringResource(R.string.intro_feature_omni_desc),
                    accentColor = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(AppSpacing.md))

                FeatureHighlightCard(
                    icon = Icons.Default.Hub,
                    title = stringResource(R.string.intro_feature_graph_title),
                    badge = stringResource(R.string.intro_feature_graph_badge),
                    description = stringResource(R.string.intro_feature_graph_desc),
                    accentColor = EntityTintTemporal
                )

                Spacer(modifier = Modifier.height(AppSpacing.md))

                FeatureHighlightCard(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = stringResource(R.string.intro_feature_chat_rag_title),
                    badge = stringResource(R.string.intro_feature_chat_rag_badge),
                    description = stringResource(R.string.intro_feature_chat_rag_desc),
                    accentColor = EntityTintTheme
                )

                Spacer(modifier = Modifier.height(AppSpacing.md))

                FeatureHighlightCard(
                    icon = Icons.Default.AutoAwesome,
                    title = stringResource(R.string.intro_feature_recall_title),
                    badge = stringResource(R.string.intro_feature_recall_badge),
                    description = stringResource(R.string.intro_feature_recall_desc),
                    accentColor = EntityTintAction
                )
            }
        }
    }
}

/**
 * Page 2: Mini Tutorial on how to use the app by downloading LLM, Embedder, Reranker, and Whisper.
 */
@Composable
private fun IntroPageTutorial() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    CenteredPage {
        AnimatedIntroContent(visible) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(AppRadius.small),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = AppSpacing.xs)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "MINI TUTORIAL",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.intro_tutorial_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = stringResource(R.string.intro_tutorial_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(AppSpacing.xl))

                // Model 1: LLM
                ModelTutorialCard(
                    stepNumber = "1",
                    icon = Icons.Default.Memory,
                    title = stringResource(R.string.intro_model_llm_title),
                    badge = stringResource(R.string.intro_model_llm_badge),
                    description = stringResource(R.string.intro_model_llm_desc),
                    actionGuide = stringResource(R.string.intro_model_llm_action),
                    tagColor = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(AppSpacing.md))

                // Model 2: Embedder
                ModelTutorialCard(
                    stepNumber = "2",
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.intro_model_embed_title),
                    badge = stringResource(R.string.intro_model_embed_badge),
                    description = stringResource(R.string.intro_model_embed_desc),
                    actionGuide = stringResource(R.string.intro_model_embed_action),
                    tagColor = EntityTintTheme
                )

                Spacer(modifier = Modifier.height(AppSpacing.md))

                // Model 3: Reranker
                ModelTutorialCard(
                    stepNumber = "3",
                    icon = Icons.Default.FilterList,
                    title = stringResource(R.string.intro_model_reranker_title),
                    badge = stringResource(R.string.intro_model_reranker_badge),
                    description = stringResource(R.string.intro_model_reranker_desc),
                    actionGuide = stringResource(R.string.intro_model_reranker_action),
                    tagColor = EntityTintAction
                )

                Spacer(modifier = Modifier.height(AppSpacing.md))

                // Model 4: Whisper
                ModelTutorialCard(
                    stepNumber = "4",
                    icon = Icons.Default.GraphicEq,
                    title = stringResource(R.string.intro_model_whisper_title),
                    badge = stringResource(R.string.intro_model_whisper_badge),
                    description = stringResource(R.string.intro_model_whisper_desc),
                    actionGuide = stringResource(R.string.intro_model_whisper_action),
                    tagColor = EntityTintPerson
                )

                Spacer(modifier = Modifier.height(AppSpacing.md))

                // Cloud alternative tip card
                Card(
                    shape = RoundedCornerShape(AppRadius.medium),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(AppSpacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(AppSpacing.sm))
                        Text(
                            text = stringResource(R.string.intro_tutorial_cloud_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Page 3: Permissions transparency and privacy guarantee.
 */
@Composable
private fun IntroPagePermissions() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    CenteredPage {
        AnimatedIntroContent(visible) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.lg))

                Text(
                    text = stringResource(R.string.intro_privacy_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = stringResource(R.string.intro_privacy_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(AppSpacing.xl))

                PermissionDetailCard(
                    icon = Icons.Default.Mic,
                    title = stringResource(R.string.intro_permissions_mic_title),
                    description = stringResource(R.string.intro_permissions_mic_desc)
                )

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                PermissionDetailCard(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.intro_permissions_notif_title),
                    description = stringResource(R.string.intro_permissions_notif_desc)
                )

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                PermissionDetailCard(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.intro_privacy_storage_title),
                    description = stringResource(R.string.intro_privacy_storage_desc)
                )

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                PermissionDetailCard(
                    icon = Icons.Default.CloudOff,
                    title = stringResource(R.string.intro_privacy_offline_title),
                    description = stringResource(R.string.intro_privacy_offline_desc)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers & Components
// ---------------------------------------------------------------------------

@Composable
private fun CenteredPage(content: @Composable () -> Unit) {
    val navBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navBarsHorizontal = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal).asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val navMode = rememberNavigationMode()
    // Dynamic bottom clearance: base controls height (~116dp) + navigation bar inset + safety margin
    val dynamicBottomPadding = 120.dp + navBarsBottom + if (navMode.isButtonNav) 16.dp else 4.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 24.dp + navBarsHorizontal.calculateStartPadding(layoutDirection),
                end = 24.dp + navBarsHorizontal.calculateEndPadding(layoutDirection)
            )
            .padding(top = 56.dp, bottom = dynamicBottomPadding),
        contentAlignment = Alignment.TopCenter
    ) {
        content()
    }
}

@Composable
private fun AnimatedIntroContent(visible: Boolean, content: @Composable () -> Unit) {
    val reducedMotion = isReducedMotionEnabled()
    val slideDuration = if (reducedMotion) 1 else 600
    val fadeDuration = if (reducedMotion) 1 else 500

    val offsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 40f,
        animationSpec = tween(durationMillis = slideDuration, easing = FastOutSlowInEasing),
        label = "slideUp"
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = fadeDuration, delayMillis = if (reducedMotion) 0 else 100),
        label = "fadeIn"
    )
    Box(
        modifier = Modifier
            .offset(y = offsetY.dp)
            .alpha(alpha)
    ) {
        content()
    }
}

@Composable
private fun DotIndicator(isSelected: Boolean) {
    val width by animateDpAsState(
        targetValue = if (isSelected) 24.dp else 8.dp,
        label = "dotWidth"
    )
    val color by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
        label = "dotColor"
    )
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .height(8.dp)
            .width(width)
            .clip(AppRadius.full)
            .background(color = color)
    )
}

@Composable
private fun PillBadge(icon: ImageVector, text: String) {
    Surface(
        shape = AppRadius.full,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun FeatureHighlightCard(
    icon: ImageVector,
    title: String,
    badge: String,
    description: String,
    accentColor: Color
) {
    Card(
        shape = RoundedCornerShape(AppRadius.large),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.lg),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            Surface(
                shape = RoundedCornerShape(AppRadius.medium),
                color = accentColor.copy(alpha = 0.15f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = RoundedCornerShape(AppRadius.small),
                        color = accentColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(AppSpacing.xs))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun ModelTutorialCard(
    stepNumber: String,
    icon: ImageVector,
    title: String,
    badge: String,
    description: String,
    actionGuide: String,
    tagColor: Color
) {
    Card(
        shape = RoundedCornerShape(AppRadius.large),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.md)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Surface(
                    shape = CircleShape,
                    color = tagColor,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = stepNumber,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(AppRadius.small),
                    color = tagColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = tagColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    shape = RoundedCornerShape(AppRadius.small),
                    color = tagColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = tagColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(AppSpacing.xs))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                lineHeight = 18.sp,
                modifier = Modifier.padding(start = 36.dp)
            )

            Spacer(Modifier.height(AppSpacing.sm))

            Surface(
                shape = RoundedCornerShape(AppRadius.medium),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                border = BorderStroke(1.dp, tagColor.copy(alpha = 0.25f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = tagColor
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = actionGuide,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionDetailCard(
    icon: ImageVector,
    title: String,
    description: String
) {
    Card(
        shape = RoundedCornerShape(AppRadius.large),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}
