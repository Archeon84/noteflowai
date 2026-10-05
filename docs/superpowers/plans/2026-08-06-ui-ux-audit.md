# UI/UX Audit & Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all UI/UX discrepancies, accessibility gaps, design system violations, and broken interactions found in the NoteFlowAI audit.

**Architecture:** Fixes are grouped by layer: design-system adoption first (tokens, elevation, typography), then accessibility (touch targets, contentDescription, string resources), then consistency (button styles, empty states, feedback channels), then bug fixes (broken long-press, back navigation, locale-hardcoded "All"), then cleanup (dead code, unused imports). Each task is self-contained and testable.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, Android strings.xml, AppSpacing/AppRadius design tokens

## Global Constraints

- minSdk 26, targetSdk 35, compileSdk 35
- No new Gradle dependencies
- Follow existing pattern: `AppSpacing` tokens (`xs=4, sm=8, md=12, lg=16, xl=24, xxl=32, xxxl=48`), `AppRadius` tokens (`small=4, medium=8, large=12, extraLarge=16, full=24`)
- All user-facing text must live in `strings.xml` — no hardcoded strings in composables
- Touch targets minimum 48x48dp per Material3 guidelines
- `Color(0x...)` hex values only in `ui/theme/Color.kt`, never in screen/component files
- Existing `EmptyState` component with Canvas illustrations is the canonical empty state
- Snackbar for transient feedback (not Toast), per Material3 guidelines
- `./gradlew assembleDebug` must pass after each task

---

## Task 1: Extract Hardcoded Strings to strings.xml

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/noteflowai/app/ui/components/SearchExplanationDialog.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/SettingsSections.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/components/IdeaEvolutionPanel.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/chat/ChatScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/RecordScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/NoteDetailContent.kt`

**Interfaces:**
- Consumes: None (standalone task)
- Produces: New string resources in `strings.xml`, all composables updated to use `stringResource()`

- [ ] **Step 1: Add new string resources to strings.xml**

Add these strings before the closing `</resources>` tag in `app/src/main/res/values/strings.xml`:

```xml
    <!-- Search Explanation Dialog -->
    <string name="search_explanation_title">Why this note?</string>
    <string name="search_explanation_relevance">Relevance: %d%%</string>
    <string name="search_explanation_retrieved_because">Retrieved because:</string>
    <string name="search_explanation_shared_concepts">Shared concepts:</string>

    <!-- Advanced RAG Settings -->
    <string name="settings_advanced_rag_title">Advanced RAG</string>
    <string name="settings_advanced_rag_description">Enhanced retrieval features for smarter note search and context.</string>
    <string name="settings_concept_graph">Concept Graph</string>
    <string name="settings_multi_hop_retrieval">Multi-Hop Retrieval</string>
    <string name="settings_search_explanations">Search Explanations</string>
    <string name="settings_smart_snippets">Smart Snippets</string>

    <!-- Idea Evolution -->
    <string name="idea_evolution_title">How your thinking evolved</string>
    <string name="idea_evolution_notes_span">%1$d notes spanning %2$d days</string>
    <string name="idea_evolution_today">today</string>
    <string name="idea_evolution_days">%d days ago</string>
    <string name="idea_evolution_weeks">%d weeks ago</string>
    <string name="idea_evolution_months">%d months ago</string>
    <string name="idea_evolution_years">%d years ago</string>

    <!-- Chat Source Chips -->
    <string name="chat_sources_label">Sources:</string>
    <string name="chat_from_notes">From: %d note(s)</string>
    <string name="chat_source_info">Why this note?</string>

    <!-- Note Connections -->
    <string name="note_connection_strong">Strong</string>
    <string name="note_connection_medium">Medium</string>
    <string name="note_connection_weak">Weak</string>

    <!-- Common -->
    <string name="common_ok">OK</string>
    <string name="common_back">Back</string>
    <string name="common_close">Close</string>
    <string name="common_dismiss">Dismiss</string>

    <!-- Settings misc -->
    <string name="settings_mic_permission_required">Microphone permission required for voice commands</string>

    <!-- Record search -->
    <string name="record_no_matches">No matches found</string>
    <string name="record_no_matches_message">Try a different search term.</string>
```

- [ ] **Step 2: Update SearchExplanationDialog.kt**

Replace all hardcoded strings:

```kotlin
// Line ~29: title
Text(stringResource(R.string.search_explanation_title), fontWeight = FontWeight.Bold)

// Line ~34: dynamic note title stays as-is (it's interpolated)
Text("\"${explanation.noteTitle}\"", ...)

// Line ~42: relevance score
Text(stringResource(R.string.search_explanation_relevance, (explanation.overallScore * 100).toInt()), ...)

// Line ~49: section label
Text(stringResource(R.string.search_explanation_retrieved_because), ...)

// Line ~73: section label
Text(stringResource(R.string.search_explanation_shared_concepts), ...)

// Line ~88: OK button
Button(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) }
```

- [ ] **Step 3: Update SettingsSections.kt AdvancedRagSection**

Replace all hardcoded strings in the `AdvancedRagSection` composable:

```kotlin
title = stringResource(R.string.settings_advanced_rag_title),
// description text
Text(stringResource(R.string.settings_advanced_rag_description), ...)
// Switch labels
Text(stringResource(R.string.settings_concept_graph), ...)
Text(stringResource(R.string.settings_multi_hop_retrieval), ...)
Text(stringResource(R.string.settings_search_explanations), ...)
Text(stringResource(R.string.settings_smart_snippets), ...)
```

- [ ] **Step 4: Update IdeaEvolutionPanel.kt**

Replace hardcoded strings:

```kotlin
// Title
Text(stringResource(R.string.idea_evolution_title), ...)
// Notes count
Text(stringResource(R.string.idea_evolution_notes_span, evolution.noteCount, evolution.daySpan), ...)
// Relative time strings in the timeline
when {
    days == 0 -> Text(stringResource(R.string.idea_evolution_today), ...)
    days < 30 -> Text(stringResource(R.string.idea_evolution_days, days), ...)
    days < 365 -> Text(stringResource(R.string.idea_evolution_weeks, days / 7), ...)
    // etc.
}
```

- [ ] **Step 5: Update ChatScreen.kt**

Replace hardcoded strings:

```kotlin
// Line ~1136: "Sources:" label
Text(stringResource(R.string.chat_sources_label), ...)
// Line ~1182: contentDescription
contentDescription = stringResource(R.string.chat_source_info),
```

- [ ] **Step 6: Update SettingsScreen.kt, RecordScreen.kt, NoteDetailContent.kt**

```kotlin
// SettingsScreen.kt line 77
Toast.makeText(context, context.getString(R.string.settings_mic_permission_required), ...)

// RecordScreen.kt line 926 — use existing unused string
Intent.createChooser(intent, context.getString(R.string.record_share_audio))

// NoteDetailContent.kt lines 373-375
contentDescription = stringResource(R.string.note_connection_strong)
contentDescription = stringResource(R.string.note_connection_medium)
contentDescription = stringResource(R.string.note_connection_weak)
```

- [ ] **Step 7: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/java/com/noteflowai/app/ui/
git commit -m "fix: extract hardcoded UI strings to strings.xml"
```

---

## Task 2: Adopt AppSpacing Tokens Across All Screens

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/HomeScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/NotesScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/NoteDetailContent.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/RecordScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/ScanScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/YouTubeScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/DocumentScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/chat/ChatScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/components/SearchExplanationDialog.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/components/IdeaEvolutionPanel.kt`

**Interfaces:**
- Consumes: `AppSpacing.xs/sm/md/lg/xl/xxl/xxxl` tokens from `ui/theme/Spacing.kt`
- Produces: All spacing values in composables use tokens instead of raw `.dp`

**Token mapping rule:** Replace the nearest token. Exact matches: `4.dp→xs, 8.dp→sm, 12.dp→md, 16.dp→lg, 24.dp→xl, 32.dp→xxl, 48.dp→xxxl`. Non-token values like `10.dp`, `14.dp`, `2.dp`, `6.dp` should be snapped to the nearest token. Special cases: `0.dp` stays as-is (no spacing), and values like `40.dp` or `60.dp` should be expressed as `AppSpacing.xxl + AppSpacing.sm` (40) or `AppSpacing.xxl + AppSpacing.xl` (56, nearest) only if it improves readability — otherwise keep as raw `.dp` with a comment `// intentional`.

- [ ] **Step 1: Update HomeScreen.kt**

Add `import com.noteflowai.app.ui.theme.AppSpacing` (already present, remove dead import if needed, then re-add if not there).

Replace all hardcoded spacing. Key mappings:
- `16.dp` → `AppSpacing.lg`
- `8.dp` → `AppSpacing.sm`
- `24.dp` → `AppSpacing.xl`
- `4.dp` → `AppSpacing.xs`
- `14.dp` → `AppSpacing.lg` (nearest, minor visual change)
- `40.dp` → `AppSpacing.xxl + AppSpacing.sm`

Also fix the double-padding hero section: remove `padding(horizontal = 16.dp)` from the hero gradient Box (line ~133) since the outer Column already has `horizontal = 16.dp`.

- [ ] **Step 2: Update NotesScreen.kt**

Replace spacing values. Key mappings:
- `10.dp` → `AppSpacing.md` (12dp, minor visual change)
- `6.dp` → `AppSpacing.sm` (8dp)
- `2.dp` → `AppSpacing.xs` (4dp)
- `1.dp` → stays `1.dp` (divider, not spacing token)
- `60.dp` → stays as-is (FAB margin, intentional)

- [ ] **Step 3: Update remaining screens (batch)**

Apply the same token mapping to: NoteDetailContent.kt, RecordScreen.kt, ScanScreen.kt, YouTubeScreen.kt, DocumentScreen.kt, ChatScreen.kt, SettingsScreen.kt, SearchExplanationDialog.kt, IdeaEvolutionPanel.kt.

For each file:
1. Add `import com.noteflowai.app.ui.theme.AppSpacing` if not present
2. Replace `4.dp` → `AppSpacing.xs`, `8.dp` → `AppSpacing.sm`, `12.dp` → `AppSpacing.md`, `16.dp` → `AppSpacing.lg`, `24.dp` → `AppSpacing.xl`, `32.dp` → `AppSpacing.xxl`, `48.dp` → `AppSpacing.xxxl`
3. Non-token values: snap to nearest or keep with comment

- [ ] **Step 4: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/
git commit -m "refactor: adopt AppSpacing tokens across all screens"
```

---

## Task 3: Consistent Card Elevation

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/HomeScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/YouTubeScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/ScanScreen.kt`

**Interfaces:**
- Consumes: Material3 `CardDefaults.cardElevation()` API
- Produces: Consistent elevation across all list/content cards

**Rule:** All content cards use `defaultElevation = 1.dp`. All list-item cards (NoteCard, RecordingItem, etc.) keep their existing 2.dp. Cards that are backgrounds (SettingsGroupCard, section containers) keep 0.dp.

- [ ] **Step 1: Fix HomeScreen.kt card elevations**

Add `elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)` to all Home cards that currently have none:
- Quick actions card
- Stats card
- Recent notes section card
- Recent recordings section card
- News/What's new card

Leave `NoteFlowCard` wrapper cards as-is (they have their own elevation logic).

- [ ] **Step 2: Fix YouTubeScreen.kt card elevations**

Normalize all three card types to use consistent elevation:
- Video card: add `elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)`
- Transcript card: already has 1.dp, keep
- Summary card: add `elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)`

Also normalize card colors: use `surfaceVariant` for all content cards (remove the arbitrary `surface` on transcript and `secondaryContainer` on summary — or keep secondaryContainer for summary since it's a distinct AI result, but at least make video and transcript match).

- [ ] **Step 3: Fix SettingsScreen.kt and ScanScreen.kt**

SettingsScreen: SettingsGroupCard already uses 0.dp — keep (it's a section background).
ScanScreen: Add 1.dp elevation to the scan result card.

- [ ] **Step 4: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/
git commit -m "fix: normalize card elevation across screens"
```

---

## Task 4: Fix Touch Targets Below 48dp

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/NotesScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/RecordScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/chat/ChatScreen.kt`

**Interfaces:**
- Consumes: Material3 `Modifier.minimumInteractiveComponentSize()` or `Modifier.size(48.dp)`
- Produces: All interactive elements meet 48x48dp minimum

- [ ] **Step 1: Fix NotesScreen.kt search-history close button**

The search-history chip close button is `IconButton(modifier = Modifier.size(16.dp))` — far too small.

```kotlin
IconButton(
    onClick = { /* remove search */ },
    modifier = Modifier.size(48.dp) // was 16.dp
) {
    Icon(
        Icons.Default.Close,
        contentDescription = stringResource(R.string.common_dismiss),
        modifier = Modifier.size(16.dp) // icon stays small, touch target is 48dp
    )
}
```

- [ ] **Step 2: Fix RecordScreen.kt language picker and pick buttons**

The language `OutlinedButton` and "Pick" button use `contentPadding = PaddingValues(vertical = 4.dp)` making them ~36dp tall. Increase vertical padding:

```kotlin
OutlinedButton(
    onClick = { ... },
    contentPadding = PaddingValues(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
) {
    // ... text content
}
```

Same for the "Pick" button — increase `vertical = 4.dp` to `vertical = AppSpacing.sm` (8dp), giving ~44dp height. If still under 48dp, add `Modifier.minimumInteractiveComponentSize()` to the Button.

- [ ] **Step 3: Fix ChatScreen.kt attachment items and quick-setup buttons**

`AttachmentItem` is a bare `clickable` Column. Wrap in a `Modifier.minimumInteractiveComponentSize()` or add padding to ensure 48dp height:

```kotlin
Column(
    modifier = Modifier
        .clickable { onClick() }
        .minimumInteractiveComponentSize()
        .padding(AppSpacing.sm),
    ...
)
```

Quick-setup `OutlinedButton`s in AiConfigDialog: change `height(40.dp)` to `height(48.dp)`.

- [ ] **Step 4: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/
git commit -m "fix: ensure all interactive elements meet 48dp touch target"
```

---

## Task 5: Replace Inline Hex Colors with Theme Tokens

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/chat/ChatScreen.kt`

**Interfaces:**
- Consumes: `Color.SuccessColor`, `Color.WarningColor` from `ui/theme/Color.kt`
- Produces: No `Color(0x...)` in screen files

- [ ] **Step 1: Replace relevance score colors in ChatScreen.kt**

Find the source chip relevance coloring (~line 1150):

```kotlin
// Before:
source.relevanceScore >= 0.7f -> Color(0xFF4CAF50)
source.relevanceScore >= 0.4f -> Color(0xFFFFC107)

// After:
source.relevanceScore >= 0.7f -> Color.SuccessColor
source.relevanceScore >= 0.4f -> Color.WarningColor
```

Add import: `import com.noteflowai.app.ui.theme.SuccessColor` and `import com.noteflowai.app.ui.theme.WarningColor`

- [ ] **Step 2: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/chat/ChatScreen.kt
git commit -m "fix: replace inline hex colors with theme tokens in ChatScreen"
```

---

## Task 6: Fix Broken Long-Press Multi-Select in NotesScreen

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/NotesScreen.kt`

**Interfaces:**
- Consumes: Existing `selectionMode: Boolean`, `selectedNotes: Set<String>`, `onToggleSelection: (String) -> Unit`
- Produces: Long-press toggles selection when in selection mode

- [ ] **Step 1: Fix NoteCard onLongClick handler**

The `NoteCard` composable has a `combinedClickable` that ignores its `onLongClick` parameter. Fix:

```kotlin
// In NoteCard, the combinedClickable currently:
// .combinedClickable(
//     onClick = { onClick() },
//     onLongClick = { /* ignores this, opens context menu */ },
// )

// Change to:
.combinedClickable(
    onClick = { onClick() },
    onLongClick = {
        if (selectionMode) {
            onToggleSelection(note.fileName)
        } else {
            onShowContextMenu(note)
        }
    },
)
```

Add `selectionMode: Boolean = false` and `onToggleSelection: (String) -> Unit = {}` parameters to `NoteCard` if not already present, and pass them from the caller.

Also update the caller (in the `LazyColumn`):

```kotlin
NoteCard(
    note = note,
    selectionMode = selectionMode,
    selectedNotes = selectedNotes,
    onToggleSelection = onToggleSelection,
    onClick = { /* existing click handler */ },
    // ... other params
)
```

- [ ] **Step 2: Remove dead onLongClick parameter**

Remove the unused `onLongClick: () -> Unit = {}` parameter from `NoteCard`'s signature since the behavior is now handled by `selectionMode` + `onToggleSelection`.

- [ ] **Step 3: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/NotesScreen.kt
git commit -m "fix: restore long-press multi-select in NotesScreen"
```

---

## Task 7: Fix Locale-Hardcoded "All" Category

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/NotesScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/NoteDetailContent.kt`

**Interfaces:**
- Consumes: `stringResource(R.string.notes_category_all)` (already exists in strings.xml)
- Produces: Category comparison uses localized string

- [ ] **Step 1: Fix hardcoded "All" in NotesScreen.kt**

Find the line comparing `note.category != "All"` (line ~666). Change to use the localized constant:

```kotlin
// Before:
if (note.category != "All") {

// After:
val allCategory = stringResource(R.string.notes_category_all)
// ... then:
if (note.category != allCategory) {
```

Note: `stringResource` can't be called inside a lambda directly — it needs to be called at composable scope and captured. The comparison should happen where `allCategory` is in scope.

- [ ] **Step 2: Fix hardcoded "All" in NoteDetailContent.kt**

Find `categories.filter { it != "All" }` (line ~266). Change to:

```kotlin
val allCategory = stringResource(R.string.notes_category_all)
val filteredCategories = categories.filter { it != allCategory }
```

- [ ] **Step 3: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/NotesScreen.kt app/src/main/java/com/noteflowai/app/ui/screens/NoteDetailContent.kt
git commit -m "fix: use localized string for 'All' category comparison"
```

---

## Task 8: Consistent Button Styles Across Screens

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/RecordScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/NoteDetailContent.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/chat/ChatScreen.kt`

**Interfaces:**
- Consumes: Material3 `Button`, `OutlinedButton`, `FilledTonalButton` APIs
- Produces: Same-tier actions use same button style

**Rules:**
- Primary action: `Button` (filled)
- Secondary action: `OutlinedButton`
- Tertiary/dismissible action: `FilledTonalButton` or `TextButton`
- "Save" actions: `Button` (filled) consistently
- "Cancel" actions: `OutlinedButton` consistently

- [ ] **Step 1: Fix language picker button style inconsistency**

RecordScreen uses `OutlinedButton` for language picker; ScanScreen uses `Button`. Standardize: language picker is a secondary configuration action → use `OutlinedButton` everywhere.

Update ScanScreen.kt: change the language picker `Button` to `OutlinedButton`.

- [ ] **Step 2: Fix SettingsScreen fake-outlined buttons**

"Restore from Backup" and "Export Notes Only" use `Button` + `ButtonDefaults.outlinedButtonColors()` to fake outlined style. Replace with actual `OutlinedButton`:

```kotlin
// Before:
Button(
    onClick = { ... },
    colors = ButtonDefaults.outlinedButtonColors(),
    // ...
)

// After:
OutlinedButton(
    onClick = { ... },
    // ...
)
```

- [ ] **Step 3: Fix NoteDetailContent AI tools consistency**

"Summarize/Proofread/Rewrite" use custom `ActionChip`; "Translate" uses `Button`. Since Translate is a primary action on that row, make it consistent: either all `Button` or all `ActionChip`. Recommendation: make Translate an `OutlinedButton` (secondary action, same as the AI tools tier):

```kotlin
OutlinedButton(
    onClick = { showLanguagePicker = true },
    modifier = Modifier.fillMaxWidth()
) {
    Icon(Icons.Default.Translate, ...)
    Spacer(...)
    Text(stringResource(R.string.note_detail_translate))
}
```

- [ ] **Step 4: Fix ChatScreen config dialog button row**

The AiConfigDialog confirmButton has three buttons (Save Preset / Clear / Save) in a Row. Keep the structure but make styles consistent:
- "Save" (primary): `Button`
- "Save Preset" (secondary): `OutlinedButton`
- "Clear" (destructive): `TextButton` with error color

- [ ] **Step 5: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/
git commit -m "fix: standardize button styles across screens"
```

---

## Task 9: Adopt Shared EmptyState Component

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/chat/ChatScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/YouTubeScreen.kt`

**Interfaces:**
- Consumes: `EmptyState` composable from `ui/components/EmptyState.kt`, `EmptyStateIllustrations.kt`
- Produces: All screens use the shared empty state component

- [ ] **Step 1: Replace ChatScreen hand-rolled empty state**

The ChatScreen empty state (lines ~232-253) is a custom Column with Icon + Text. Replace with `EmptyState`. The string `R.string.chat_empty_hint` already exists ("Start a conversation. Ask a question, attach an image, or paste notes to chat.").

The `EmptyState` signature requires `icon: ImageVector`, `title: String`, `message: String`, with optional `illustration` and `action`:

```kotlin
// Before (hand-rolled):
Column(
    modifier = Modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
) {
    Icon(...)
    Spacer(...)
    Text(...)
}

// After (shared component):
EmptyState(
    icon = Icons.Default.Chat,
    title = stringResource(R.string.ai_assistant),
    message = stringResource(R.string.chat_empty_hint)
)
```

Add `import androidx.compose.material.icons.filled.Chat` if not present.

- [ ] **Step 2: Replace YouTubeScreen hand-rolled empty state**

Replace the custom Card empty state (lines ~377-410) with `EmptyState`. Strings `R.string.youtube_empty_hint` and `R.string.youtube_empty_paste` already exist:

```kotlin
EmptyState(
    icon = Icons.Default.PlayCircle,
    title = stringResource(R.string.youtube_title),
    message = stringResource(R.string.youtube_empty_hint),
    illustration = { YouTubeIllustration() },
    action = {
        OutlinedButton(onClick = { /* paste link */ }) {
            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.youtube_empty_paste))
        }
    }
)
```

- [ ] **Step 3: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/chat/ChatScreen.kt app/src/main/java/com/noteflowai/app/ui/screens/YouTubeScreen.kt
git commit -m "fix: adopt shared EmptyState component in Chat and YouTube screens"
```

---

## Task 10: Fix Back Navigation Origin

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/NoteFlowApp.kt`

**Interfaces:**
- Consumes: `currentScreen` state, `Screen` enum
- Produces: Back from YouTube/Chat/Document returns to originating tab, not always Home

- [ ] **Step 1: Track originating screen**

Add a variable to remember where the user was before opening a modal screen:

```kotlin
var previousScreen by remember { mutableStateOf(Screen.HOME) }

// When navigating to a modal screen, record where we came from:
LaunchedEffect(currentScreen) {
    if (currentScreen !in listOf(Screen.NOTE_DETAIL, Screen.YOUTUBE, Screen.CHAT, Screen.DOCUMENT)) {
        previousScreen = currentScreen
    }
}
```

- [ ] **Step 2: Fix back handlers for modal screens**

Update the `onBack` callbacks for YouTube, Chat, and Document to return to `previousScreen` instead of hardcoded `HOME`:

```kotlin
// YouTube screen
Screen.YOUTUBE -> YouTubeScreen(
    onBack = { currentScreen = previousScreen },
    ...
)

// Chat screen  
Screen.CHAT -> ChatScreen(
    onBack = { currentScreen = previousScreen },
    ...
)

// Document screen
Screen.DOCUMENT -> DocumentScreen(
    onBack = { currentScreen = previousScreen },
    ...
)
```

- [ ] **Step 3: Fix global BackHandler**

Update the global BackHandler to also route back to `previousScreen`:

```kotlin
BackHandler(enabled = currentScreen != Screen.HOME) {
    currentScreen = when (currentScreen) {
        Screen.NOTE_DETAIL -> Screen.NOTES
        else -> previousScreen
    }
}
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/NoteFlowApp.kt
git commit -m "fix: back navigation from modal screens returns to originating tab"
```

---

## Task 11: Fix Settings Theme Row Overflow on Small Screens

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/SettingsScreen.kt`

**Interfaces:**
- Consumes: None
- Produces: Theme color row wraps on small screens instead of clipping

- [ ] **Step 1: Make theme row horizontally scrollable or use FlowRow**

The six `ThemeColorCircle`s with `spacedBy(12.dp)` = ~348dp minimum, which overflows on 360dp-wide phones minus padding.

Option A (preferred — uses FlowRow for natural wrapping):

```kotlin
// Before:
Row(
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    themes.forEach { theme -> ThemeColorCircle(...) }
}

// After:
FlowRow(
    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
    verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
) {
    themes.forEach { theme -> ThemeColorCircle(...) }
}
```

Option B (horizontal scroll):

```kotlin
LazyRow(
    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
    contentPadding = PaddingValues(horizontal = 0.dp)
) {
    items(themes) { theme -> ThemeColorCircle(...) }
}
```

Use FlowRow (Option A) — it's more natural for a grid of color circles and doesn't hide overflow.

- [ ] **Step 2: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/SettingsScreen.kt
git commit -m "fix: wrap theme color row with FlowRow to prevent overflow on small screens"
```

---

## Task 12: Consistent Search Bar Styling (Record vs Notes)

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/RecordScreen.kt`

**Interfaces:**
- Consumes: NotesScreen search bar styling pattern
- Produces: RecordScreen search bar matches NotesScreen

- [ ] **Step 1: Align RecordScreen search bar with NotesScreen**

NotesScreen search uses `bodyMedium` text style; RecordScreen uses `bodySmall`. Also, RecordScreen shows "No recordings yet" even when search filtering yields zero results (misleading).

Fix text style:
```kotlin
// RecordScreen search OutlinedTextField:
textStyle = MaterialTheme.typography.bodyMedium // was bodySmall
placeholder = { Text(stringResource(R.string.record_search_placeholder), style = MaterialTheme.typography.bodyMedium) }
```

Add a search-specific empty state (strings already added in Task 1):
```kotlin
// When search query is active but no results match:
if (recordings.isNotEmpty() && filteredRecordings.isEmpty()) {
    EmptyState(
        icon = Icons.Default.SearchOff,
        title = stringResource(R.string.record_no_matches),
        message = stringResource(R.string.record_no_matches_message)
    )
}
```

- [ ] **Step 2: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/RecordScreen.kt
git commit -m "fix: align RecordScreen search bar style and empty state with NotesScreen"
```

---

## Task 13: Fix Settings Section Typography Inconsistency

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/SettingsScreen.kt`

**Interfaces:**
- Consumes: None
- Produces: All section headers use consistent typography

- [ ] **Step 1: Standardize section header typography**

Currently `SettingsGroupCard` headers use `titleSmall` but standalone section labels ("Data", "Google Drive Sync") use `titleMedium`. Standardize all to `titleMedium` + Bold for consistency since these are section-level headings:

```kotlin
// In SettingsGroupCard:
Text(
    text = title,
    style = MaterialTheme.typography.titleMedium, // was titleSmall
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.primary
)
```

- [ ] **Step 2: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/SettingsScreen.kt
git commit -m "fix: standardize section header typography in Settings"
```

---

## Task 14: Remove Dead Code and Unused Imports

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/NoteDetailContent.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/HomeScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/NotesScreen.kt`

**Interfaces:**
- Consumes: None
- Produces: Clean code with no dead imports or redundant wrappers

- [ ] **Step 1: Fix NoteDetailContent.kt**

Remove duplicate `LocalContext` import (lines 18 and 22 — one is redundant).

- [ ] **Step 2: Fix HomeScreen.kt**

Remove unused `AppSpacing` import if it's dead (it was noted as imported but unused — after Task 2 it will be used, so verify after Task 2 runs; if still unused, remove).

- [ ] **Step 3: Fix NotesScreen.kt**

Remove redundant `Row` wrapper with `Arrangement.SpaceBetween` that contains a single child (note-count row, lines ~305-311):

```kotlin
// Before:
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween
) {
    Text(stringResource(R.string.notes_count, notes.size), ...)
}

// After:
Text(
    stringResource(R.string.notes_count, notes.size),
    modifier = Modifier.fillMaxWidth(),
    ...
)
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/NoteDetailContent.kt app/src/main/java/com/noteflowai/app/ui/screens/HomeScreen.kt app/src/main/java/com/noteflowai/app/ui/screens/NotesScreen.kt
git commit -m "chore: remove dead code, duplicate imports, and redundant wrappers"
```

---

## Task 15: Fix Cross-Screen String Misuse

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/YouTubeScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: None
- Produces: Each screen uses its own context-appropriate string resources

- [ ] **Step 1: Add generic back/close strings**

Add to strings.xml if not already present:
```xml
<string name="common_close">Close</string>
```

- [ ] **Step 2: Fix YouTubeScreen.kt back button**

YouTubeScreen uses `R.string.chat_desc_back` for its back icon. Replace with `R.string.common_back` (which should exist) or add:
```xml
<string name="common_back">Back</string>
```

Then update YouTubeScreen:
```kotlin
contentDescription = stringResource(R.string.common_back)
```

- [ ] **Step 3: Fix SettingsScreen backup card close button**

The backup-message card close button uses `R.string.chat_desc_back`. Replace with `R.string.common_close`:
```kotlin
contentDescription = stringResource(R.string.common_close)
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/YouTubeScreen.kt app/src/main/java/com/noteflowai/app/ui/screens/SettingsScreen.kt app/src/main/res/values/strings.xml
git commit -m "fix: use context-appropriate strings instead of cross-screen string reuse"
```

---

## Task 16: Final Build Verification and Cleanup

**Files:**
- Modify: None (verification only)

**Interfaces:**
- Consumes: All prior tasks
- Produces: Clean build, no warnings

- [ ] **Step 1: Full clean build**

```bash
./gradlew clean assembleDebug
```
Expected: BUILD SUCCESSFUL with no errors.

- [ ] **Step 2: Run lint check**

```bash
./gradlew lintDebug 2>&1 | tail -20
```
Check for new warnings. Fix any critical issues.

- [ ] **Step 3: Commit any final fixes**

```bash
git add -A
git commit -m "chore: final UI/UX audit cleanup"
```

---

## Summary of All Tasks

| # | Task | Impact | Effort |
|---|------|--------|--------|
| 1 | Extract hardcoded strings to strings.xml | Localization + accessibility | Medium |
| 2 | Adopt AppSpacing tokens | Design system consistency | High (many files) |
| 3 | Consistent card elevation | Visual consistency | Low |
| 4 | Fix touch targets below 48dp | Accessibility (CRITICAL) | Medium |
| 5 | Replace inline hex colors with theme tokens | Theming correctness | Low |
| 6 | Fix broken long-press multi-select | Bug fix (broken feature) | Low |
| 7 | Fix locale-hardcoded "All" category | Bug fix (localization) | Low |
| 8 | Consistent button styles | Visual consistency | Medium |
| 9 | Adopt shared EmptyState component | Component reuse + consistency | Medium |
| 10 | Fix back navigation origin | UX bug fix | Medium |
| 11 | Fix theme row overflow on small screens | Bug fix (layout) | Low |
| 12 | Consistent search bar styling | Visual consistency | Low |
| 13 | Fix Settings section typography | Visual consistency | Low |
| 14 | Remove dead code and unused imports | Code quality | Low |
| 15 | Fix cross-screen string misuse | Correctness | Low |
| 16 | Final build verification | Quality gate | Low |

**Execution order recommendation:** Tasks 1, 5, 14, 15 first (quick wins). Then Task 2 (highest-impact design system fix). Then Tasks 3, 4, 6, 7 (critical fixes). Then Tasks 8, 9, 10, 11, 12, 13 (consistency). Finally Task 16 (verification).

**Total estimated effort:** ~3-4 hours with subagent-driven development.
