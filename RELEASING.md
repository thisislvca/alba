# Releasing Alba

Merge finished work into `main`. Website changes deploy automatically after their checks pass. Follow the steps below to release the app.

## Quick start

Complete the one-time setup before your first release.

1. Merge your app changes into `main` and wait for Android CI to pass.
2. Run [Prepare app release](https://github.com/thisislvca/alba/actions/workflows/prepare-app-release.yml) on `main` with a version such as `0.2.0` and short notes. Leave **Upload to Play** enabled.
3. Install the candidate from Play internal testing and test it on your phone.
4. Run [Publish tested app](https://github.com/thisislvca/alba/actions/workflows/publish-app-release.yml) on `main` with the candidate's full tag. It promotes that tested build to production and publishes its GitHub release.

Complete the initial public-release go/no-go before publishing. Play eligibility, review and managed-publishing settings still apply.

**Dry run** checks and packages unsigned files without credentials, tags, releases or Play uploads. You cannot promote a dry run.

## One-time setup

1. Create a fresh Play upload key and a separate, unencrypted SSH signing key. The old family-beta key existed in private Git history and must not be reused. Keep credentials and backups outside Git.
2. Create the Play Console app and register your upload key with Play App Signing. Enable the Android Publisher API and give a service account permission to upload internal builds and promote them to production.
3. Add the settings below in GitHub Settings > Secrets and variables > Actions.
4. Prepare your first signed candidate with **Upload to Play** disabled. Upload its AAB manually to Play internal testing. Later candidates can use automated uploads.

| Setting | Type | Value |
| --- | --- | --- |
| `ALBA_UPLOAD_KEYSTORE_BASE64` | Secret | Base64 of the new upload keystore |
| `ALBA_KEYSTORE_PASSWORD` | Secret | Keystore password |
| `ALBA_KEY_ALIAS` | Secret | Upload-key alias |
| `ALBA_KEY_PASSWORD` | Secret | Upload-key password |
| `ALBA_TAG_SIGNING_KEY` | Secret | SSH private key for tags and manifests |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Secret | Play service account JSON |
| `ALBA_UPLOAD_CERT_SHA256` | Variable | Approved upload certificate SHA-256 fingerprint |
| `ALBA_TAG_SIGNING_PUBLIC_KEY` | Variable | Matching public SSH key |
| `APP_VERSION_CODE_OFFSET` | Variable | Optional build counter offset, default 1000 |
| `ALBA_SENTRY_DSN` | Variable | Optional diagnostics destination, empty disables diagnostics |

## Reference

### Versions and notes

Choose `versionName`, such as `0.2.0`. The workflow sets `versionCode` to `APP_VERSION_CODE_OFFSET + workflow run number`. New runs consume numbers, including failures and dry runs. Reruns keep their number. Start a new run after a tag or upload. Before resetting the counter, raise the offset above all uploaded build numbers.

Tags such as `app/v0.2.0-build.1002` distinguish candidates of the same version. Automated Play notes are English-only, with a 500-character limit. Publishing reuses the candidate's notes unless you override them. GitHub-only candidates without notes get draft app changes since the previous candidate tag.

### Checks and files

Preparation requires passing Android CI for the exact commit. Publishing checks the signed tag, signed manifest, file hashes and GitHub build provenance. The candidate must be on the internal track. Promotion does not rebuild it.

Signed candidates include APK, AAB, R8 mapping, hashes and a signed source/build manifest. Workflow artifacts expire after 30 days. Draft release assets remain available and exclude credentials.

The `github-pages` environment permits only `main`. Production uses `play-production`. You can require reviewers there. Add your public SSH key to GitHub for the Verified badge. Workflow checks use `ALBA_TAG_SIGNING_PUBLIC_KEY` directly.

### Failed uploads or publishing

If Play upload fails after the draft is created, upload its AAB manually or prepare another candidate. If promotion succeeds but GitHub publication fails, check Play's state before rerunning or publishing the draft manually.
