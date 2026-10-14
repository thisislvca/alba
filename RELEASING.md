# Releasing Alba

Alba uses Google Play App Signing for `me.lvca.alba`. Google holds the app signing key. GitHub Actions holds a separate, replaceable upload key in the `release-signing` environment. Gradle always builds unsigned release files and never reads signing credentials.

## Prepare alpha builds and promote to stable

1. Merge into `main` and wait for Android CI on that exact commit to pass.
2. Run [Prepare alpha release](https://github.com/thisislvca/alba/actions/workflows/prepare-app-release.yml) on `main` with channel `alpha` and a version such as `0.1.1` and release notes of at most 500 characters.
3. Approve the `release-signing` deployment after reviewing the source commit. A fresh runner signs the unsigned AAB, deletes the temporary key immediately, and records GitHub build provenance. A separate runner uploads it to Play internal testing. After that upload succeeds, the GitHub release is published as a prerelease, labelled `Alba 0.1.1 Alpha (build N)`. Failed or skipped uploads leave the candidate draft.
4. Test the Play internal build on your phone.
5. Run [Promote tested build to stable](https://github.com/thisislvca/alba/actions/workflows/publish-app-release.yml) with the candidate tag, then approve the `play-production` deployment. This promotes the existing internal build without rebuilding it and marks the same GitHub release stable, removing the Alpha label.

The Android version name stays `0.1.1` across alpha builds; the workflow run counter supplies a higher version code each time. No version-bump commit or separate alpha counter is needed. Tags retain `app/v0.1.1-build.N` and point to the exact tested main commit. Channel metadata is carried in the attested release manifest; older candidates without it remain promotable.

Play review, production eligibility and managed publishing still apply. The first bundle must be uploaded through Play Console: prepare it with **Upload to Play** disabled, select Google's generated app signing key, and upload the AAB. Keep the installer check enabled.

**Dry run** builds and checks unsigned artifacts without signing keys, Google authentication, tags or uploads. A dry run cannot be promoted.

## Credentials and permissions

| Location | Setting | Purpose |
| --- | --- | --- |
| `release-signing` environment secret | `ALBA_UPLOAD_KEYSTORE_BASE64` | Password-encrypted PKCS12 upload keystore |
| `release-signing` environment secret | `ALBA_KEYSTORE_PASSWORD` | Keystore and key password |
| `release-signing` environment variable | `ALBA_KEY_ALIAS` | Upload-key alias |
| `release-signing` environment variable | `ALBA_UPLOAD_CERT_SHA256` | Approved public certificate fingerprint |
| Each Play environment variable | `GOOGLE_WORKLOAD_IDENTITY_PROVIDER` | Restricted Google workload identity provider |
| Each Play environment variable | `GOOGLE_PLAY_SERVICE_ACCOUNT` | Its own Google service identity |
| Repository variable, optional | `APP_VERSION_CODE_OFFSET` | Default 1000; add workflow run number for versionCode |
| Repository variable, optional | `ALBA_SENTRY_DSN` | Public diagnostics destination; empty disables diagnostics |

All three environments allow only the `main` branch. `release-signing` and `play-production` require the owner's approval and disable administrator bypass. Self-review is allowed so the sole maintainer can approve their own manually dispatched release. Account takeover remains a risk; protect Google and GitHub accounts with strong MFA.

Google project `lvca-alba-releases` uses Workload Identity Federation. No service-account private JSON key is stored in GitHub. Trust is restricted to the numeric repository and owner IDs, `main`, manual workflow dispatch, and the intended workflow/environment. The internal service account can view and release Alba to testing tracks; the production service account can view and promote Alba to production. Neither gets account-wide administration, financial, user-management or store-listing permissions.

Do not add release credentials as repository secrets. Build and PR jobs must never receive them. The signing job has no Gradle/dependency build or cache restore. Google authentication happens after dependencies and artifact verification. Artifact uploads use an explicit file list and omit hidden files.

## Keys and recovery

The old Mela family-beta certificate is permanently rejected by the signing script because its keystore was committed to private Git history. Removing a file from the latest commit does not make that key safe. Preserve any historical copy only for legacy installs; never reuse it for Alba.

Keep the new encrypted upload keystore outside Git and its password in a password manager or macOS Keychain. Keep an independently recoverable encrypted backup. Public certificates and SHA-256 fingerprints are safe to share; private keys and passwords are not. Do not paste credentials into chat, command arguments or logs.

If the upload key leaks, stop release workflows, request an upload-key reset in Play Console, then replace the environment secrets and approved fingerprint. Google's app signing key remains separate. If Google API access is compromised, disable the affected federation provider or service account, revoke its Play permissions and inspect recent releases. Short-lived tokens can remain usable until they expire.

## Artifacts and verification

Candidate tags use `app/v0.2.0-build.1002`. New workflow runs consume build numbers; reruns reuse them. After a tag/upload exists, start a new run. Raise the offset before resetting a run counter.

GitHub candidate assets contain the upload-signed AAB, R8 mapping, checksums and a source/build manifest. They do not include an installable upload-key-signed APK: it would have a different certificate from the Play app. Install through Play. A future direct APK distribution must use a compatible signing identity and account for the enabled installer check.

The bundle and manifest have GitHub provenance tied to the repository, workflow and source commit. Production verifies both attestations, the tag's commit, file hashes and bundle certificate. No additional SSH signing key or custom manifest signature is needed. Provenance establishes origin; it does not prove the source is free from bugs or malicious changes.

Unsigned workflow artifacts expire after seven days; signed ones after 30 days. Draft release assets persist. Temporary signing files are removed in the signing script's `finally` block and an unconditional workflow cleanup step.

If Play upload fails after the draft is created, upload its AAB manually or prepare another candidate. If promotion succeeds but GitHub publication fails, check Play's state before retrying.
