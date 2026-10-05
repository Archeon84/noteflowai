package com.noteflowai.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.noteflowai.app.R
import com.noteflowai.app.ui.theme.AppSpacing
import com.noteflowai.app.viewmodel.BackupDriveViewModel
import com.noteflowai.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    backupDriveViewModel: BackupDriveViewModel
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.lg)
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(AppSpacing.sm))

            // 1. AI & Intelligence Domain
            AiIntelligenceSection(viewModel = viewModel)

            Spacer(modifier = Modifier.height(AppSpacing.md))

            // 2. Voice & Speech Domain
            VoiceSpeechSection(viewModel = viewModel)

            Spacer(modifier = Modifier.height(AppSpacing.md))

            // 3. Privacy, Security & Data Vault Domain
            PrivacyVaultSection(
                viewModel = viewModel,
                backupDriveViewModel = backupDriveViewModel
            )

            Spacer(modifier = Modifier.height(AppSpacing.md))

            // 4. Appearance & Habits Domain
            AppearanceHabitsSection(viewModel = viewModel)

            Spacer(modifier = Modifier.height(AppSpacing.md))

            // 5. About & Diagnostics Domain
            AboutDiagnosticsSection(
                viewModel = viewModel,
                onResetOnboarding = {
                    IntroPrefs.reset(context)
                    OnboardingPrefs.reset(context)
                    (context as? ComponentActivity)?.recreate()
                }
            )

            Spacer(modifier = Modifier.height(AppSpacing.xl))
        }
    }
}
