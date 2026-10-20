![Alba — your iCloud photos on Android](assets/readme/banner.webp)

Your iCloud photos, on Android.

Alba is free and open source, with optional donations. No subscriptions or paid features.

**Still experimental.** It uses unofficial Apple APIs, so things can break when Apple changes them. Not affiliated with Apple.

Android 11+. English and Italian. Android application ID: `me.lvca.alba`.

## Features

- [x] Sign in to iCloud with two-factor authentication
- [x] Browse your iCloud photos and the photos on your phone
- [x] Watch videos and Live Photos
- [x] Search by filename, filter by date, and sort your library
- [x] Use albums, favorites and Shared Albums
- [x] Crop and rotate photos, then save an edited copy
- [x] Save originals offline or to your phone, and share them with other apps
- [x] Upload JPEG, PNG, HEIC, static WebP and H.264/HEVC videos in MP4 or MOV files
- [x] Automatically back up new JPEG photos from your camera over an unmetered connection

## Next

- [ ] Automatic backup for videos and more photo formats
- [ ] Choose which folders to back up
- [ ] Include older photos in backups, with a size estimate before starting
- [ ] Play Store release

iCloud Shared Photo Library isn't supported. It's different from Shared Albums, which are supported.

## How it works

Alba talks directly to Apple using the web APIs behind iCloud. Your photos don't pass through an Alba server.

It keeps a local catalog and downloaded previews on your phone, so you can still browse those when you're offline. Full-size originals are downloaded when you need them. You can also choose to keep them offline.

Your password and verification codes aren't saved. Your sign-in session is stored encrypted on your phone. See [Privacy](PRIVACY.md) for the details, including optional crash reporting.

## Try the demo

Choose **Try the demo** on the welcome screen; no Apple Account is needed. Browse the bundled library, albums, videos and editor. Library refresh reads the sample catalog locally.

Open the profile icon, then **Automatic JPEG backup → Try sample backup** to walk through consent, queue progress and completion. The backup walkthrough is simulated: it never reads phone photos, requests photo permissions or uploads to Apple. Use the interruption and Wi-Fi controls to pause and resume it, then preview freeing space with imaginary copies (no real files are deleted). Stop and restart it to repeat; its state resets when leaving demo mode or restarting the app. Real iCloud authentication, transfers and remote verification still require an Apple Account.

The **+** button can add a bundled sample or a selected photo/video to the demo library. Android’s **Share to Alba** and **Open with Alba** screens can also import into demo after confirmation. Selected bytes are retained in private temporary storage, never uploaded, and cleared on the next app process start. Shared albums support real sample-state changes: accepting/declining incoming invitations, approving/denying requests, selecting a cover, contributing selected media, and saving a shared copy to the sample personal library. Demo sharing screens label all people, invitations and nonfunctional public URLs as samples; no invitations are sent or links published.

## Build

Use JDK 21 and Android SDK 37 with build tools 37.0.0. Set `JAVA_HOME` and `ANDROID_SDK_ROOT`, then run:

```sh
./gradlew :app:assembleDebug testDebugUnitTest lintDebug
```

APKs are in `app/build/outputs/apk/`. `:app:assembleRelease` always produces an unsigned release. Official bundles are signed in an isolated GitHub Actions job; see [RELEASING.md](RELEASING.md).

Shared Android settings (SDK levels, Java 17, managed test devices) live in the convention plugins under `build-logic/`.

### Static analysis

CI runs ktlint and detekt on every module:

```sh
./gradlew ktlintCheck detekt
```

ktlint rules are configured in `.editorconfig` and detekt overrides in `config/detekt/detekt.yml`. Findings that predate the checks are recorded per module in `detekt-baseline.xml` and `ktlint-baseline.xml`, so only new findings fail the build. Fix old findings rather than adding new ones; after a deliberate cleanup, regenerate the baselines with:

```sh
./gradlew detektBaseline ktlintGenerateBaseline
```

Reports are written to `<module>/build/reports/detekt/` and `<module>/build/reports/ktlint/`.

JavaScript tools use the Node LTS version pinned in `.nvmrc`. Run `nvm install` and `nvm use` from the repository root before using website, screenshot or release scripts. Script checks: `node --test 'scripts/**/*.test.mjs'`. Bun is used to install the website and screenshot packages from their lockfiles.

## Releases

The website deploys from `main`. App versions are prepared in GitHub Actions, tested internally, then promoted without rebuilding. See [release instructions](RELEASING.md).

## Tests

CI runs device tests on Android 11 and 16. Locally, create a native Android emulator in Android Studio, then run `ALBA_AVD_NAME=your_avd scripts/run_android_tests.sh`. On a Mac, the script requires and checks hardware graphics.

For performance work, set `ANDROID_SERIAL` to your test device. Run `scripts/benchmark_android.sh profile` to update the Baseline Profile, then `scripts/benchmark_android.sh measure` on a physical phone. These use a separate app with demo photos. The older emulator measurement script is only a smoke check.

## Contribute

Found a bug or have an idea? [Open an issue](https://github.com/thisislvca/alba/issues). Pull requests are welcome too, under the same AGPL-3.0-only license.

Please leave passwords, account data and personal photos out of reports. For security problems, use [private reporting](SECURITY.md).

## License

[AGPL-3.0-only](LICENSE). See [third-party credits and licenses](THIRD_PARTY_NOTICES.md).
