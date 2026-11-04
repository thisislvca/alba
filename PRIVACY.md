# Privacy

Alba connects your phone directly to Apple. Alba has no server that receives your photos or Apple Account credentials. Passwords and verification codes are used for sign-in and are not saved. Sessions are encrypted on the phone.

Photo metadata, previews, saved originals, settings and transfer state are stored locally. Alba requests Android media access for browsing, uploads and optional backup. Backup is off until you turn it on; it then uploads photos and videos from your camera and the folders you choose, and, if you choose, the ones already on your phone, straight to your iCloud account. Alba asks for photo location access so uploads keep where each photo was taken. Android backup and device-to-device transfer are disabled for app data.

## Intended audience

Alba is intended for teens aged 13 and older and adults. It is not directed to children under 13.

## Diagnostics

Builds without a configured Sentry destination send no diagnostics. If you build Alba yourself, you can configure one with `ALBA_SENTRY_DSN` or the local Sentry properties file. Configured builds enable crash and performance reporting by default; you can turn it off in About.

Reports may include app and Android versions, device characteristics, an installation identifier, timings, result categories, transfer sizes and how long sign-in sessions last. Official Alba builds use Sentry's US region; other distributors should disclose their own destination.

Photos, filenames, Apple Account identifiers, passwords, verification codes, session tokens, cookies and request contents are excluded. Screenshots, session replay and view-hierarchy collection are disabled.

Requests to Apple are subject to Apple's privacy practices. Alba uses unofficial Apple web APIs and is not affiliated with Apple.

## Protocol status

Before writing to iCloud, Alba checks a small status file on GitHub (raw.githubusercontent.com) that lets its maintainers pause uploads or edits when Apple changes iCloud. The request carries no account, device or photo information, and no cookies; GitHub sees the request's IP address, as with any web request.
