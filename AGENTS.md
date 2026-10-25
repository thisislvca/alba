# Working on Alba

Notes for coding agents and contributors. Product direction is in [VISION.md](VISION.md); how to test is in the README's "Testing" section. This file covers the working rules those two leave out.

## Layout

- `app`: the Compose UI (`ui/`), feature controllers and state holders, and the demo mode.
- `engine`: the library, sync, uploads and backup, on Room and WorkManager.
- `protocol-icloud-web`: the iCloud web protocol (auth, CloudKit, Shared Albums).
- `benchmark`: macrobenchmarks and the Baseline Profile.
- `build-logic`: the Gradle conventions every module shares, including test JVM settings and managed devices.

## Build

- Use JDK 21. Android Studio's bundled one works: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Before a commit: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest lintDebug detekt ktlintCheck`. `./gradlew ktlintFormat` fixes most ktlint findings.
- New detekt findings fail the build. Fix them; don't regenerate the baselines.

## Tests

- Put a test in the lowest layer that can show the behaviour: plain JVM, Robolectric UI from fixed state (`setAlbaContent`), screenshot goldens, and only then a device test. A device test that doesn't need a device is in the wrong layer.
- Locally, run the JVM layers before pushing. Run a device test class only when a change touches device-only behaviour, and only the classes it affects. Never run the whole device suite locally: CI runs it.
- CI runs `@Smoke` device tests on every pull request, and every device test plus API 30 and a compact phone on `main` and nightly. A test that passes only on the retry is listed as flaky in the job summary. Fix it at the root, don't add waits.
- Tests must not depend on screen size or wall-clock timing. Put the UI in a known state (scroll by key, fixed state, the test clock) rather than nudging or sleeping. Local devices and CI both use a Pixel 9 profile.
- Never weaken an assertion to make a test pass. When behaviour changes on purpose, change the test to check the new behaviour.

## Screenshot goldens

Goldens are recorded and compared on Linux in CI, with a zero-pixel threshold. A local run renders them but doesn't compare.

When a change alters how a screen looks on purpose:

1. Push. CI's JVM job fails, and its `screenshots` artifact shows the new images and the differences.
2. Check the differences are the ones you meant.
3. In GitHub, open Actions > CI > Run workflow, pick the branch and tick "record-screenshots". That run commits the new goldens to the branch.
4. Push again or re-run the checks: commits made by Actions don't start CI.

Never commit goldens recorded on a Mac.

## Emulators

- On macOS, always boot with host GPU and acceleration (`-gpu host -accel on`) on a native arm64 image. Check `adb shell dumpsys SurfaceFlinger` reports the Apple GPU / Metal, never a software renderer.
- Run one emulator at a time, and don't run other Gradle builds while device tests run.
- Shut down emulators you started.

## UI

- Google Photos is the reference for gestures, motion and small details. Keep Alba's layout and Apple's names for iCloud data. Polish; don't redesign.
- Every user-visible string goes in `res/values/strings.xml` and `res/values-it/strings.xml`. `TranslationResourcesTest` checks they match.
- Keep `testTag`s stable. Tests and screenshots rely on them.
- Respect reduced motion wherever you add animation.

## Git

- Conventional Commits (`feat(viewer): …`, `fix(gallery): …`, `test: …`).
- Pull requests are squash-merged into `main` once CI is green.
