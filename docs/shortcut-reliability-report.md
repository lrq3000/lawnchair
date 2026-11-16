# Lawnchair shortcut reliability implementation report

## Delivery and boundaries

Base: `505dbc40e6154c05158b5d0271c45f6a885a411b` (`v15.0.0-beta3.0`). Branch: `fix/preserve-unavailable-shortcuts`. This is a complete independent source implementation for Lawnchair, not an AOSP-dependent wrapper. Android compilation, APK delivery, integration-test execution, and independent coordinator review remain pending; this report does not claim release readiness or a verified end-to-end fix.

All changes belong to the assigned `lawnchair/.worktrees/shortcut-reliability` worktree. No push, merge, branch/worktree cleanup, upstream submission, device interaction, emulator, global tool installation, or other repository mutation was performed. The Gradle wrapper populated its standard Gradle 9.3.0 cache during the initial prerequisite check.

## Behavioral implementation

- Ordinary application favorites retain their row, intent, placement, screen, container, and rank while package/component availability is unresolved. Protected folder children remain model members, preventing empty-folder/page pruning from inferring that their positions are vacant. Existing corrupt-intent, removed-profile, invalid-placement, widget, deep-shortcut, archive, and restore-placeholder paths remain specialized.
- The centralized policy uses positive exact-component resolution, known installation storage history, per-profile recovery, and confirmed-uninstall row snapshots. Boot completion, time, unrelated applications, and any mounted volume are not deletion/recovery evidence. Known internal applications outside recovery retain normal loader/update policy; unknown migration history is preserved provisionally.
- A checksummed, schema-versioned, AtomicFile journal lives in no-backup storage per workspace database identity. It records stable profile serial/package history, storage UUID/public mapping when provable, component/item recovery, event generation, and pending uninstall fingerprints. Corrupt/unreadable/unsupported metadata becomes unknown history, not a favorites purge.
- A tiny auxiliary identity table is created non-destructively without changing the favorites schema version. New/cleared layouts, Android restores, and local Lawnchair backup restoration renew identity. Public-volume mapping uses only matching UUID evidence from API-29-compatible public APIs; unmapped adopted storage stays unknown. No hard-coded paths or newer volume callback APIs are used.
- Consequential package tasks are captured before enqueue, journaled before the unloaded-model discard gate, and drained at loader checkpoints. An uninstall during loading invalidates the loader and schedules a fresh load; conditional deletes are replayed before workspace binding. Pending deletes match database, row, stable profile serial, original intent, component, and row modification fingerprint, not a broad later package query. A later installation/new row cannot inherit an old deletion.
- `OP_UPDATE` and `OP_ADD` recover an existing exact target in place. Recovery clears only NOT_AVAILABLE, preserving quiet/locked/suspended and other flags; a different launcher activity does not rewrite a protected intent. UI removal predicates use captured values and perform no package/database lookup on the UI thread.
- Foreground, package availability, boot, and media events request coalesced model-thread revalidation. While foreground, unresolved items retry with 1–30-second backoff without expiry-based deletion. Pause/destroy stops repeated work and unregisters observations at app-state teardown.
- Normal last-valid labels/icons are snapshotted through Lawnchair's existing custom-label/custom-icon pipeline. Folder children get a durable high-resolution snapshot without forcing every low-resolution in-memory child to remain high resolution. Unavailable loading uses saved blobs and labels; migration can recover an existing IconCache image before later eviction. Failed queries/default images/empty labels do not replace valid snapshots or customization metadata. Unavailable taps explain that the shortcut is retained and request revalidation.
- Existing retained applications are excluded from automatic install-queue/deck additions, including queues persisted before model loading. No recovery path inserts a new favorites row.

## Verification actually performed

- `python tools/test_workspace_availability.py`: **17 passing tests**, using JDK 21 `javac -Xlint:all` and the actual production policy/codec with an injected atomic-persistence boundary. Cases cover restart, internal policy, recovery, exact activities, multiple profiles, database identity, row reuse/reinstall, pending uninstall replay, corrupt/future/unreadable state, interrupted atomic writes, unresolved volume mapping, durable pre-load availability events, and recovery retirement after user deletion.
- Observed host RED→GREEN: first missing-migration assertion failed `Expected 1, got 0`; unknown availability persistence failed its durable-write assertion; empty-row uninstall failed its recovery-retirement assertion. Each then passed after the corresponding production change. The initial API skeleton RED is not represented as an execution of the old Android loader.
- JDK compiler parser: 18 changed/new Java files parsed with zero syntax errors in the intermediate pass. Syntax parsing is not Android type checking.
- `javap -classpath prebuilts/libs/framework-10.jar android.os.storage.StorageManager android.os.storage.StorageVolume android.content.pm.ApplicationInfo android.util.AtomicFile` confirms Android-10 signatures used by this change. This does not establish the final APK's minimum SDK or runtime behavior.
- `git diff --check`: passed before milestone commit.

## Android tests supplied, but NOT executed

Ten focused real Android tests are wired through `build.gradle` to `tests/shortcutReliability/src/app/lawnchair/model/WorkspaceAvailabilityIntegrationTest.java`. They use real favorites/cursor/model/journal and icon decoding, with isolated in-memory databases and per-test journal/preferences. A test-only launcher activity supplies a resolvable exact component. The suite covers workspace/dock/folder retention, cached custom appearance, receiver inference, unloaded uninstall and profile scope, service restart, flag-preserving exact recovery, unavailable-write protection, non-destructive identity migration/reset, actual update/foreground callbacks without duplicates, and rapid uninstall/reinstall.

The original real-cursor boot-complete regression is also in `tests/src/com/android/launcher3/model/LoaderCursorTest.java`; existing Soong test fixtures were adapted to the model-owned service. The baseline Gradle project did not wire its existing `tests/src`/Soong suite. No old-loader executable RED, instrumentation GREEN, full project-suite GREEN, or Android type-check result is claimed.

## Coordinator commands and prerequisites

Environment: `JAVA_HOME=C:/Program Files/Java/jdk-21`, `ANDROID_HOME=C:/Users/33632/AppData/Local/Android/Sdk`.

1. Populate the worktree's `platform_frameworks_libs_systemui` submodule at the pinned `6a11ef767998885838a599331b5485f768b3d725` revision. It was uninitialized at inspection. Existing framework/SystemUI prebuilt JARs are present under `prebuilts/libs`.
2. Resolve Gradle dependencies online and provide the configured compile SDK **36.1** and build tools **36.1.0**. The initial `./gradlew.bat help --offline --console=plain -q` failed resolving uncached `com.gradle.develocity:4.3.1` before Android configuration. The wrapper is Gradle 9.3.0, AGP 9.0.1, Kotlin 2.3.0; this worker did not substitute versions or upgrade unrelated sources.
3. Build APK: `./gradlew.bat assembleLawnWithQuickstepGithubDebug --console=plain -q`.
4. Compile focused test APK: `./gradlew.bat assembleLawnWithQuickstepGithubDebugAndroidTest --console=plain -q`.
5. On a separately authorized target only: `./gradlew.bat connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.model.WorkspaceAvailabilityIntegrationTest --console=plain -q`.

Configured minSdk is **26**, including `withQuickstep`; debug application ID is `app.lawnchair.debug`. Effective APK manifest minSdk remains unverified until an APK exists. No APK was built in this worker because the coordinator owns expensive build scheduling. Lawnchair's native publication revision is the final two-digit field of `build.gradle` versionCode; the coordinator owns its patch bump and publication.

## Remaining acceptance and validation limits

- Mandatory release gates: Android compile/type checking, both APKs, actual manifest inspection, ten instrumentation tests, the applicable broader project suite, and independent coordinator review.
- Physical Lenovo P11/Android 10 validation has not occurred. Adopted/public UUID exposure, delayed PackageManager registration, real custom-icon rendering, and storage removal while the app is already loaded still need device evidence.
- The supplied tests do not execute the precise concurrent late-uninstall/loader-binding race or a full backup-restoration/import flow. The code includes pre-bind drainage, loader invalidation, guarded tombstones, and identity renewal for these paths, but their race and restore behavior remain unverified.
- An uninstall missed while the entire launcher process is stopped cannot be distinguished reliably from inaccessible external storage using a later negative lookup. The deliberately conservative result is a retained unavailable shortcut, not inferred deletion. No metadata path recreates a deleted favorites row.
- Atomic persistence retains the previous complete journal on a failed write; the event remains in memory and errors are logged. No implementation can guarantee new durable data when the filesystem refuses all writes. Such failures require real storage-fault validation.
- The requested independent APK delivery is not yet satisfied at this worker handoff. Source integration is substantive, but host tests alone do not prove end-to-end Android behavior.

## Local artifacts

Generated host classes remain under `%LOCALAPPDATA%/Temp/opencode/lawnchair-availability-tests`; Gradle's standard caches may also contain the wrapper distribution and failed-resolution metadata. No temporary files were deleted. All non-regenerable tests, the runner, this report, and the ledger are committed with the implementation.
