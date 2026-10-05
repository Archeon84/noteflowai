# NoteFlowAI — Release Checklist

Pre-release gates, mirroring the Agentic Implementation Guide §14 completion checklist. A release
is not done merely because the code compiles; verify each gate.

## Build

- [ ] `./gradlew :app:assembleDebug` succeeds.
- [ ] `./gradlew :app:assembleRelease` succeeds against the release keystore (see `BUILD_TESTING.md`).
- [ ] `./gradlew :app:lintDebug` has no new (non-baseline) high-severity findings.
- [ ] Native libs (whisper.cpp / llama.cpp) build for both `arm64-v8a` and `x86_64`.

## Tests

- [ ] `./gradlew :app:testDebugUnitTest` is fully green.
- [ ] Room migration tests (`MemoryDatabaseMigrationTest`) pass.
- [ ] Regression tests exist for any bug/feature shipped since the last release.
- [ ] Failure paths are tested, not just happy paths.

## Data safety

- [ ] Raw source content is preserved; derived AI data is rebuildable.
- [ ] Database schema changes ship with a migration (never a silent recreate).
- [ ] No known data-loss path: capture persists raw before processing; force-close/restart keeps notes.
- [ ] Deletion behavior tested: deleting derived data does not delete source notes, and vice versa.

## Async / processing

- [ ] Processing workers/jobs are idempotent, retryable, and survive process death.
- [ ] Status is persisted and visible; failures are recoverable with a retry action.

## AI & citations

- [ ] Model output is validated; malformed output does not crash the app.
- [ ] Every user-data claim has a validated citation; unsupported claims are rejected.
- [ ] "Insufficient evidence" refusals are supported.

## Security & privacy

- [ ] No secrets (API keys, tokens, database keys) appear in logs.
- [ ] Database keys are protected by Android Keystore (see `MemoryDatabase.memoryDbPassphrase`).
- [ ] Backups exclude private files and any keys.
- [ ] Temporary files and caches are cleaned up.
- [ ] Cloud boundaries are explicit; local-only mode makes no cloud requests.

## UX & accessibility

- [ ] Loading, empty, and failure states exist across core screens.
- [ ] Retry exists where appropriate.
- [ ] TalkBack traversal and touch targets verified; large text does not clip.

## Manual smoke

- [ ] Fresh install: onboarding, capture, note list, search, chat, settings.
- [ ] Record offline, force-close, reopen — note survives and processing resumes.
- [ ] TTS play/stop/restart with no silent stops.
- [ ] Export/backup does not leak private configuration.
