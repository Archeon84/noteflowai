package com.noteflowai.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noteflowai.app.ui.NoteFlowApp
import com.noteflowai.app.ui.theme.NoteFlowTheme
import com.noteflowai.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val widgetAction = mutableStateOf<String?>(null)
    private val deepLinkScreen = mutableStateOf<String?>(null)
    private val widgetOpenNoteFileName = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        setContent {
            val viewModel: MainViewModel = viewModel()
            val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
            val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
            val appFont by viewModel.appFont.collectAsStateWithLifecycle()

            NoteFlowTheme(
                darkTheme = isDarkMode,
                themeName = appTheme,
                fontName = appFont
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NoteFlowApp(
                        viewModel = viewModel,
                        widgetAction = widgetAction,
                        deepLinkScreen = deepLinkScreen,
                        widgetOpenNoteFileName = widgetOpenNoteFileName
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        widgetAction.value = intent.getStringExtra(EXTRA_WIDGET_ACTION)

        // Support explicit EXTRA_NAVIGATE_TO from notifications, or URI deep link
        val navigateTo = intent.getStringExtra(EXTRA_NAVIGATE_TO)
        val deepLink = parseDeepLink(intent)
        deepLinkScreen.value = navigateTo ?: deepLink

        if (intent.action == com.noteflowai.app.widget.NoteFlowWidget.ACTION_WIDGET_OPEN_NOTE) {
            widgetOpenNoteFileName.value = intent.getStringExtra(EXTRA_NOTE_FILE_NAME)
        }
    }

    private fun parseDeepLink(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "noteflowai" || data.host != "screen") return null
        val seg = data.lastPathSegment?.lowercase() ?: return null
        return when (seg) {
            "home" -> "HOME"
            "record" -> "RECORD"
            "notes" -> "NOTES"
            "scan" -> "SCAN"
            "youtube" -> "YOUTUBE"
            "chat" -> "CHAT"
            "settings" -> "SETTINGS"
            "digest" -> "DIGEST"
            "memory" -> "MEMORY"
            "graph" -> "GRAPH"
            "conflict", "conflicts" -> "CONFLICT_DETECTION"
            "review" -> "WEEKLY_REVIEW"
            else -> null
        }
    }

    companion object {
        const val EXTRA_WIDGET_ACTION = "com.noteflowai.app.WIDGET_ACTION"
        const val EXTRA_NOTE_FILE_NAME = "com.noteflowai.app.NOTE_FILE_NAME"
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        const val ACTION_WIDGET_RECORD = "record"
        const val ACTION_WIDGET_NOTE = "note"
        const val ACTION_WIDGET_CHAT = "chat"
        const val ACTION_WIDGET_SEARCH = "search"
    }
}
