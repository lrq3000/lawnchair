# Lawnchair shortcut reliability implementation ledger

Plan: `C:/git/android-launchers-shortcuts-fix/docs/superpowers/plans/2026-11-16-shortcut-reliability.md`. Binding specification: `C:/git/android-launchers-shortcuts-fix/docs/brainstorm/android-launchers-shortcuts-fix_HANDOFF_STANDALONE_EN.md`. Base: `505dbc40e6154c05158b5d0271c45f6a885a411b` (`v15.0.0-beta3.0`). Scope is this worktree and ordinary application favorites only; coordinator owns publication, integration, APK builds, and independent final review.

## Concrete sequence

1. Regress the actual loader's boot-complete missing-target behavior in the canonical Android test source set; inspect build prerequisites and record execution limits.
2. Implement and directly test a production Java availability journal with injected atomic persistence: package/profile storage history, database/item/component protection, exact resolution, confirmed-removal tombstones, corruption and interruption safety.
3. Integrate the journal through loader, post-boot receiver, package update/removal callbacks, pre-discard and pre-bind reconciliation, foreground revalidation, cache persistence, and unavailable interaction.
4. Run bounded host tests and static/compiler checks, commit coherent milestones, and provide exact Android build/test commands and remaining verification requirements to coordinator.

## Interfaces and rulings

- Pre-flight: loader, callbacks, package tasks, and foreground entry share one model-owned availability service. Favorites remain authoritative for placement. A separate no-backup atomic journal avoids changing the favorites schema/version; a tiny identity table in the existing database distinguishes recreated databases without altering favorites.
- Ruling: execute this subproject inline and retain this committed ledger rather than running shared-plan orchestration scripts — the shared plan covers three repositories and coordinator-only operations, whereas this worktree owns only Lawnchair.
- Ruling: keep the assigned release baseline rather than current development — Android 10 support and independently reviewable scope are explicit requirements.
- Ruling: preserve absent unknown-history ordinary targets provisionally; known internal targets retain normal policy unless already recovering. Exact-component absence during recovery never rewrites the original intent.

## Investigation and validation record

- Source confirms `Utilities.isBootCompleted()` gates missing-package deletion in `WorkspaceItemProcessor`; `SdCardAvailableReceiver` converts inferred absence into `OP_REMOVE`; `OP_UPDATE` clears NOT_AVAILABLE before checking the exact target; ordinary application icon blobs are not loaded by `LoaderCursor.createIconRequestInfo`.
- Existing tests are in `tests/src` and Soong `Launcher3Tests`; Gradle main source sets do not wire these Android tests. No local AOSP platform test tree or device is provided. Actual old loader RED execution is blocked, not claimed.
- `git submodule status`: SystemUI submodule `6a11ef767998885838a599331b5485f768b3d725` uninitialized. No submodule mutation performed.
- `JAVA_HOME=C:/Program Files/Java/jdk-21 ANDROID_HOME=C:/Users/33632/AppData/Local/Android/Sdk ./gradlew.bat help --offline --console=plain -q`: failed resolving uncached `com.gradle.develocity:4.3.1`. Wrapper downloaded Gradle 9.3.0 despite offline mode applying only after wrapper startup; no global installation or configuration edit was made.
- Coordinator build command: `./gradlew.bat assembleLawnWithQuickstepGithubDebug --console=plain -q`; configured minSdk is 26 (including withQuickstep), debug package is `app.lawnchair.debug`. Actual APK manifest remains to verify. Compile SDK is 36.1 and build-tools 36.1.0; installed SDK availability needs coordinator reconciliation.
- Native version mechanism: `build.gradle` versionCode has final two-digit revision field (`15_00_02_03_00` baseline); releaseName controls Beta display. Coordinator should bump revision for publication without advancing upstream sources.

## Milestone 1: durable policy

- `python tools/test_workspace_availability.py` RED: production API skeleton returned REMOVE for unknown migration history; executable assertion failed `Expected 1, got 0` in `missingMigrationTargetSurvivesRestart`.
- Same command GREEN: 15 production policy/codec tests passed, JDK 21 `javac -Xlint:all`, no Android mocks. Covers process restart, known internal policy, recovery, exact components, profiles, database identity, row reuse, confirmed uninstall, interrupted acknowledgement, reinstall, corrupt/future/unreadable metadata, failed atomic writes, and unmapped adopted storage.
- The test persistence boundary models all-or-nothing writes; actual Android AtomicFile implementation and favorites integration require separate Android validation. Host tests do not establish end-to-end behavior.
- Temporary generated classes: `%LOCALAPPDATA%/Temp/opencode/lawnchair-availability-tests`; retained for manual cleanup.

## Milestone 2: independent Android integration

- Added model-owned `WorkspaceAvailability`: API-29-compatible exact-component queries, conservative storage association, per-database no-backup AtomicFile journal, conditional uninstall replay, and last-valid full-resolution appearance snapshots through the existing Lawnchair icon/custom-label pipeline.
- Integrated ordinary application preservation in `WorkspaceItemProcessor`, icon-blob recovery in `LoaderCursor`, cached folder-child snapshots and unchanged recovery ranks in `LoaderTask`, non-expiring post-boot revalidation, and protection from failed cache writes. Corrupt intents, deleted profiles, invalid placement, deep shortcuts, restore placeholders, and archived-app paths remain specialized.
- Integrated `OP_UPDATE`/`OP_ADD` exact-target recovery without fallback-intent rewriting, true-uninstall snapshot deletion, callback journaling before the unloaded-task gate, a loader pre-bind checkpoint, and loader invalidation for an uninstall received during loading. Predicates passed to UI callbacks contain only captured IDs/flags, never database/package queries.
- Added coalesced foreground/storage/boot revalidation and model-thread backoff (1–30 seconds while foreground only), pause/destroy cleanup, explanatory unavailable tap text, and retained-row guards in install queue/deck auto-add paths.
- Added a non-destructive auxiliary database identity table without bumping favorites user_version. New/cleared layouts and both Android/local Lawnchair restore paths rotate identity; journal files are scoped by the identity token so unrelated/restored layouts do not inherit storage history or pending deletes.
- Added 10 Gradle-wired Android instrumentation regressions in `tests/shortcutReliability/src` with isolated in-memory favorites and per-test journal/preferences. They cover actual cursor/model loading, workspace/dock/folder identity, cached custom appearance, receiver behavior, unloaded confirmed uninstall/profile scope, service restart, exact-target flag isolation, update/foreground nonduplication, rapid reinstall, and identity migration/reset. These tests have NOT been executed or compiled in this environment; they are not counted as passing tests.
- Existing Soong model-test fixtures were adjusted for the new model-owned dependency; the original boot-complete real-cursor regression remains in `tests/src`.
- Additional host RED→GREEN: `availabilityEventIsDurableWithoutKnownPackage` failed because an unknown availability observation was not saved; fixed with explicit unknown-vs-known classification. `uninstallRetiresRecoveryForPreviouslyDeletedRows` failed because recovery survived an empty-row uninstall; fixed by retiring package/profile recovery metadata. Current host command passes 17 tests.
- JDK compiler parser checked 18 changed/new Java files with zero syntax errors; this is syntax-only, not Android type checking. `javap` against the repository's Android-10 framework prebuilt confirms `ApplicationInfo.storageUuid`, `StorageManager.UUID_DEFAULT/getStorageVolumes`, `StorageVolume.getUuid/getState`, and AtomicFile methods used here; no newer storage callback APIs were introduced.
- Ruling: add a focused Gradle `androidTest` source set rather than claim the repository's unwired Soong tests run under Gradle — this makes the new integration regressions runnable on the actual Lawnchair variant, at the cost of AndroidX test-runner dependencies and a test APK.
- Ruling: do not run an expensive Android build or use an emulator in this worker — coordinator explicitly owns RAM scheduling and device restrictions. Build, APK manifest, Android test results, race scheduling, and the affected Lenovo's storage behavior remain validation gates.
