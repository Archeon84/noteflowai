# Google Play Console Declarations Guide — NoteFlow AI

This document provides the exact, copy-paste-ready answers for **Google Play Console -> App Content** declarations so your submission passes Google Play automated and manual review on the first attempt.

---

## Part 1: Privacy Policy URL (App Content -> Privacy Policy)

- **Privacy Policy URL**:
  `https://raw.githubusercontent.com/Archeon84/noteflowai/main/docs/PRIVACY_POLICY.md`
  *(or host `docs/PRIVACY_POLICY.md` on GitHub Pages / your domain and paste the public HTTPS URL).*
- **In-App Access**:
  Available offline and online inside the app under **Settings -> Privacy & Data Vault -> Privacy Policy**.

---

## Part 2: Data Safety Questionnaire (App Content -> Data Safety)

### Why We Declare Optional Cloud Transmission
Although NoteFlow AI is **100% offline by default** and operates **no developer-owned servers**, Google Play's Data Safety policy defines **"Data Collection"** as *any user data transmitted off the user's device* (even when sent directly to a user-configured cloud API or the user's own Google Drive `appDataFolder`, unless end-to-end encrypted with a user-held key).

To prevent Google Play automated network scanners (which detect `api.deepgram.com`, `googleapis.com/drive`, `api.openai.com`, `generativelanguage.googleapis.com`, etc. in the bytecode) from flagging an "Undisclosed Data Collection" violation, select the following exact options:

### Step 1: Overview Questions
| Question | Selection | Notes |
|---|---|---|
| **Does your app collect or share any of the required user data types?** | **Yes** | Required because optional Google Drive Sync, optional Deepgram STT/TTS, and optional Cloud AI send data off-device when enabled by the user. |
| **Is all of the user data collected by your app encrypted in transit?** | **Yes** | All network calls use HTTPS / WSS (TLS 1.2/1.3) and `network_security_config.xml` blocks cleartext traffic to public hosts. |
| **Do you provide a way for users to request that their data is deleted?** | **No — my app does not allow users to create an account, or all data is stored locally / in the user's own cloud storage** *(or select **"Yes"** and provide `https://github.com/Archeon84/noteflowai/blob/main/docs/PRIVACY_POLICY.md#5-data-retention-deletion--user-rights`)* | In the account/deletion section, explain that users delete data directly in-app (`Settings -> Privacy & Data Vault -> Purge Derived Data`, note/recording delete, or Google Drive sign-out/disconnect). |

### Step 2: Data Types to Check
Check **ONLY** the following 4 data types (leave Location, Contacts, Financial Info, Health, Device IDs, App Activity/Analytics, and Crash Logs **UNCHECKED**):

1. **Personal info -> Name & Email address**
   - *Why*: Read via `GoogleSignInAccount` only if the user connects **Google Drive Sync** in Settings.
2. **Messages -> Other in-app messages**
   - *Why*: Chat prompts and note context sent only if the user configures an optional **Cloud AI Provider** (OpenAI, Gemini, Anthropic, DeepSeek, etc.) instead of the default On-Device LiteRT-LM engine.
3. **Photos and videos -> Photos**
   - *Why*: Only if the user explicitly attaches an image to a **Cloud AI** chat prompt (OCR scanning itself is 100% on-device via ML Kit).
4. **Audio files -> Voice or sound recordings**
   - *Why*: Only if the user explicitly enables **Online Mode (Deepgram)** for transcription or backs up recordings to their personal **Google Drive**.
5. **Files and docs -> Files and docs**
   - *Why*: Note files and document summaries backed up to the user's personal **Google Drive `appDataFolder`** or sent as context to an optional **Cloud AI Provider**.

### Step 3: Per-Data-Type Details (Apply to All 5 Checked Data Types Above)
For each of the 5 checked data types above, select these exact answers:

| Question | Answer to Select |
|---|---|
| **Is this data collected, shared, or both?** | **Collected** *(Do **NOT** check "Shared" — data is only processed on user instruction for app functionality / user's own Drive storage, never sold or shared with third-party marketers/brokers).* |
| **Is this data processed ephemerally?** | **No** *(Users can store backups in Google Drive; third-party BYOK API retention is governed by the user's own API account).* |
| **Is this data required for your app, or can users choose whether it's collected?** | **Users can choose whether this data is collected** *(100% optional — the app works completely offline and includes a Local-Only Mode firewall).* |
| **Why is this user data collected?** | Check **App functionality** ONLY. *(Leave Analytics, Advertising, Fraud prevention, Personalization, Account management unchecked).* |

---

## Part 3: Foreground Service Permission Declaration (`FOREGROUND_SERVICE_MICROPHONE`)

Because `targetSdk = 35` (Android 15) and `AndroidManifest.xml` declares:
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_MICROPHONE`
- `<service android:name=".service.RecordingService" android:foregroundServiceType="microphone" ... />`

Google Play Console (**App Content -> Foreground service permissions**) requires:
1. A written justification for `microphone` foreground service usage.
2. A link to a short video (under 30 seconds, hosted on YouTube Unlisted or Google Drive with "Anyone with the link can view") demonstrating the user-initiated workflow and persistent notification.

### 1. Checkboxes to Select under `FOREGROUND_SERVICE_MICROPHONE`
- Check: **Voice recorder / audio note-taking / dictation** (or **Audio recording initiated by the user**).

### 2. Copy-Paste Written Justification
> NoteFlow AI is an audio note-taking and voice transcription app. When a user taps the Record button on the Voice Recorder screen (or configures a daily Scheduled Recording alarm), the app starts `RecordingService` with `foregroundServiceType="microphone"` and immediately displays a persistent notification ("NoteFlow AI — Recording — Tap to return to the app") with a "Stop" action button. This allows users to record lectures, meetings, and voice notes uninterrupted when they switch to another app or lock their device screen. If the user does not keep the recording running in the background, multi-minute lecture and meeting recordings would be terminated by the OS as soon as the screen turns off, resulting in lost user recordings.

### 3. Step-by-Step Video Demonstration Script (~20 Seconds)
You can record this video automatically using [`scripts/record_fgs_playstore_demo.ps1`](file:///h:/Work/NoteFlowAI/scripts/record_fgs_playstore_demo.ps1) with a connected Android device or emulator:

1. **0:00 – 0:04**: Launch **NoteFlow AI** and navigate to the **Record** screen (tap the **Capture** button or **Record** action).
2. **0:04 – 0:08**: Tap the **Microphone (Record)** button so the timer and live waveform start visibly recording.
3. **0:08 – 0:14**: Press the Android **Home** button to background the app, then swipe down the **Android Notification Shade** to clearly show the ongoing **`NoteFlow AI — Recording`** notification with its **`Stop`** action button.
4. **0:14 – 0:20**: Tap **`Stop`** directly on the notification (or tap the notification to return to the app and tap Stop), showing that recording stops immediately and the notification is dismissed.

---

## Part 4: Exact Alarm Permission Note (`SCHEDULE_EXACT_ALARM`)

If prompted in Play Console for `SCHEDULE_EXACT_ALARM`:
- **Use case**: User-configured **Scheduled Recordings** (automatic daily voice recording at a user-selected `HH:mm`), **Daily Digest** morning briefing, and **Recall Reminders**.
- **Fallback**: `ScheduledRecordingManager`, `DailyDigestScheduler`, and `RecallReminderManager` all check `AlarmManager.canScheduleExactAlarms()` on Android 12+ and gracefully fall back to `setAndAllowWhileIdle()` if exact alarm permission is not granted.
