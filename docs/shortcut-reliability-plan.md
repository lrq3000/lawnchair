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
