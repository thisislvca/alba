# Releasing Alba

Use short-lived branches and merge finished work into `main`. There is no permanent release branch. Website changes on `main` deploy automatically after the website checks pass. App releases are deliberate, versioned builds.

## Prepare a candidate

In GitHub Actions, select **Prepare app release**, choose `main`, and enter a version such as `0.2.0` plus short release notes. The workflow checks the exact commit, builds the APK and AAB, verifies their signatures, creates a signed tag and draft GitHub release, and optionally uploads the AAB to Play internal testing.

- Leave **Upload to Play** enabled once Play is configured. Disable it for the first signed build that must be uploaded manually in Play Console.
- **Dry run** checks and packages unsigned artifacts only. It needs no signing/Play credentials, creates no tag or release, and cannot be promoted. It does not substitute for the device-test CI gate.
- A real candidate requires successful Android CI for the exact source commit. Failed or pending checks prevent release preparation.
- `versionName` is your chosen version. `versionCode` is `APP_VERSION_CODE_OFFSET + workflow run number` (offset defaults to 1000). Every new run consumes a number, including dry runs and failures. Rerunning an existing run retains its number; use a new run after an upload or tag was created. Never reuse/reset this counter without raising the offset above every uploaded build.
- Tags include both values, for example `app/v0.2.0-build.1001`, so you can test another build of the same user-facing version.
- Draft notes include app changes since the previous candidate tag. Edit them before publishing. Play notes are limited to 500 characters; only English notes are automated initially.

Artifacts include APK, AAB, R8 mapping, file hashes, a signed source/build manifest, and GitHub build-provenance attestations. Dry-run artifacts are explicitly unsigned. Workflow artifacts expire after 30 days; real candidate assets remain attached to the draft GitHub release.

## Publish the tested candidate

Test the internal build, review the changelog, and finish the initial public-release go/no-go in Play Console. Then run **Publish tested app** from `main` with the candidate's complete tag and final notes (or reuse its prepared notes).

The workflow verifies the signed tag, signed manifest, file hashes, and GitHub provenance. It requires that exact build to be on the internal track, promotes it to production without rebuilding or uploading another binary, and publishes its GitHub release. Running this workflow is the explicit production approval. It does not bypass Play eligibility, review, or managed-publishing settings.

If an upload fails after the GitHub draft was created, its assets and tag remain available. Resolve Play setup and upload that AAB manually, or prepare a new candidate. If Play promotion succeeds but GitHub publication fails, confirm the Play state before rerunning or publishing the GitHub draft manually.

## One-time credentials

Configure these repository secrets in GitHub Settings → Secrets and variables → Actions:

| Secret | Purpose |
| --- | --- |
| `ALBA_UPLOAD_KEYSTORE_BASE64` | Base64 of the **new** Play upload keystore |
| `ALBA_KEYSTORE_PASSWORD` | Keystore password |
| `ALBA_KEY_ALIAS` | Upload-key alias |
| `ALBA_KEY_PASSWORD` | Upload-key password |
| `ALBA_TAG_SIGNING_KEY` | Dedicated, unencrypted SSH private key for signing release tags and manifests |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Play API service account JSON; needed only for Play uploads/promotion |

Configure these repository variables:

| Variable | Purpose |
| --- | --- |
| `ALBA_UPLOAD_CERT_SHA256` | Approved new upload certificate SHA-256 fingerprint; every build must match it |
| `ALBA_TAG_SIGNING_PUBLIC_KEY` | Public SSH key matching the release signing key |
| `APP_VERSION_CODE_OFFSET` | Optional counter offset; raise only when needed to stay above existing Play build numbers |
| `ALBA_SENTRY_DSN` | Optional diagnostics destination; empty means no Sentry uploads |

Do not reuse the old family-beta signing key: it existed in the private archive's history. Create a fresh upload key and register it with Play App Signing. Keep backups outside Git. The Play account/app must exist, the Android Publisher API must be enabled, and the service account must have the needed permissions for Alba. Fastlane requires the first app build to be uploaded manually before API uploads work.

Add the public tag-signing key as a signing key on the GitHub account if you want GitHub's Verified badge. The workflow also verifies signatures directly against `ALBA_TAG_SIGNING_PUBLIC_KEY`; no private key or credentials are included in artifacts.

The `github-pages` environment permits only `main`. Production runs use the separate `play-production` environment; you can add required reviewers there later if more people join the project.
