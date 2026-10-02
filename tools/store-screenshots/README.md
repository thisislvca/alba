# Alba store screenshots

Real Android UI captures, composed into five opaque 1080 × 1920 PNGs. Headlines
and order are editable in `assets/store/screenshots.json`. Each card's `capture`
identifies the raw frame; its `filename` numbers the export in listing order.
The layouts take their
cue from the Chrome/Wikipedia listings: large headings, soft backgrounds and
rounded app views. Captures use the production UI with synthetic demo data.
The backup capture renders the real account settings and storage allowance with a synthetic signed-in
account and inert callbacks; no credentials, Apple requests or backup jobs run.
The outer cards are 9:16; the real app captures are 1080 × 2400 (9:20).
Headlines use the bundled Google Sans Medium font, rendered as glyph outlines so
the result does not depend on locally installed fonts. Alt text for Play Console
is stored alongside each card and exported in `en-US/manifest.json`.
The same configuration controls heading size, spacing, pill colors and padding,
font and phone placement. The phone extends just below the
card so the title has more breathing room; the source capture stays proportional.
Every heading and pill uses one 80px Google Sans Medium size. Common line boxes
give all cards the same title baselines, centered above the phone. Plain text is
black. The library-access card keeps the blue iCloud pill and green Android pill.
The other cards use distinct shades from Alba’s dawn palette: rose, mauve, violet,
and indigo. Gradients stay subtle; text and filled
icons use dark ink on light fills and white on darker fills. The icons are 64px,
with 32px of left padding.
Each card's `callouts` identifies the word, line, icon and
color tone. Words stay in the editable headline; the renderer adds their pills.
Copy covers iCloud access on Android, photo viewing, Shared Albums with other
people, a planned backup experience and video playback. The store captures
include the real Shared Albums list. The album-interior capture is used by the
README and website, but is not a store card.
The bundled demo has eight shared albums with multiple photos and a video.
Its licensed photos mix travel, pets, cafés, friends and everyday moments.
Library and sharing captures show different parts of that demo.
The viewer opens photo 8. The shared-album capture shows ten photos in two rows
inside Good company. It reuses existing licensed assets and does not increase
the bundled media size. Demo photos are grouped into fuller months to avoid sparse rows. No recipient is
selected and no message is sent during capture. Apple's Shared Photo Library is
a different feature and is not claimed by this listing.

The listing starts with the library-access cover, followed by the Shared Albums
list, then backup, the photo viewer and video playback.
Card 3 is a backup draft. Its actual screen is
the Backup page: the existing cloud storage card, a usage bar, category totals,
available space and current automatic JPEG backup settings. The shared storage
card keeps its existing account/demo filtering and unavailable-state handling.
The backup page labels it "Cloud storage"; profile labels are unchanged.
The headline's asterisk explains
that full backup and automatic space saving are coming soon. The current app's
Offline collection and backup behavior remain unchanged. The planned card is
listed separately in the manifest's `plannedFiles`; `readyFiles` excludes it.
The upload ZIP contains only `readyFiles`. All five design drafts are available
in `alba-screenshot-drafts-en-US.zip` under `.artifacts/store-captures`.
Keep future-feature artwork out of the launch submission until the submitted
build supports its claims. The coming-soon label is a design disclosure, not
an assurance of Play review acceptance.

Install this tool's dependency with `bun install` here, then run:

```sh
bun run render ../../.artifacts/store-captures/raw
```

To refresh the captures, build and install the debug app and instrumentation APK.
On the owned disposable emulator, clear `com.mannaworks.mela.dev` app data before
capturing so prior UI tests do not leave offline items or selections behind. Run
`dev.mela.app.StoreScreenshotTest` with `-e captureStoreScreenshots true` alone on a disposable English demo emulator
with a 1080 × 2400 display. Capture paths are the app's external-files directory,
`store-screenshots`. Copy the twelve PNGs into `.artifacts/store-captures/raw`:
eleven screens used by the README, including the five store frames, plus an
extra `00-library-top` frame showing the gallery before scrolling.
Use host GPU/VM acceleration on macOS, verify Apple GPU/Metal after boot, and
stop the owned emulator when finished. The capture test is for artwork generation;
it is not a product test to include in every CI run.
It waits for the photo viewer's loading indicators to disappear before saving
that frame.

Launch the app before configuring demo mode, so its network listeners are active.
Before capturing, enable Android System UI demo mode and set the clock to 09:41,
hide notifications, show full Wi-Fi/mobile indicators and a full, unplugged
battery. Keep the app's own status/navigation bar colors and real UI intact.
Send separate network broadcasts for Wi-Fi (`wifi=show`, `level=4`, `fully=true`)
and mobile (`mobile=show`, `level=4`, `datatype=lte`, `slot=0`, `fully=true`).

## Font provenance

`fonts/GoogleSans-Medium.ttf` is the unmodified Android static Medium font from
[Google Sans v14.000](https://github.com/googlefonts/googlesans/releases/tag/v14.000),
distributed under the SIL Open Font License 1.1 in `fonts/OFL.txt`.
It is a store-artwork dependency and is not bundled in the Android app.
SHA-256: `654567c75cc0eac0567ed0e5e6f8c1ea88f2ffd397a10889e88857bd9c2e0e23`.

## Callout artwork

The cloud and feature symbols are original SVG artwork in `titles.mjs`.
The cover's Android callout uses an original green phone icon, allowing its footer
to contain only two trademark notices: Android/Google LLC and iCloud/Apple Inc.
It does not reproduce the Android robot. Google's robot artwork requires an
additional Creative Commons credit within the creative, so restoring that icon
also requires restoring its attribution.

An optional Android head asset remains available from the flat SVG distributed by Google at
<https://developer.android.com/static/images/brand/android-head_flat.svg>.
The source file is unchanged and is not currently used in the screenshots.
The robot is reproduced from work created and shared by Google and used under
the Creative Commons 3.0 Attribution License:
<https://creativecommons.org/licenses/by/3.0/>.

This Android-branding composition is a design draft. Google's published
[brand guidelines](https://developer.android.com/distribute/marketing-tools/brand-guidelines)
ask for Android brand-team review and approval before publishing marketing
that references Android trademarks. Keep that review separate from Play's
technical screenshot validation.

Pexels demo media are separate from app code and remain under the Pexels License.
Source URLs, dimensions, byte sizes and the video edit are in
`assets/store/demo-media.json`; see `THIRD_PARTY_NOTICES.md` for credit.

## Refreshing demo photography

`import-demo-media.mjs` downloads the selected source URLs recorded in
`assets/store/demo-media.json`, preserves framing and applies EXIF orientation,
then exports WebP at a maximum 960px edge with metadata stripped. Each photo has
a 160KB limit. The manifest records credits, dimensions, bytes and SHA-256.
For example, from the repository root:

```sh
node tools/store-screenshots/import-demo-media.mjs 3 4 5 6 7 8 9 10 11 12 13 14
```

When changing a source, update the fixture dimensions and source revision before
building and recapturing the real Android UI.

## README banner

From this folder, run `bun install --frozen-lockfile` and `bun run banner`.
The generator uses the bundled demo captures in `assets/readme/screens/`, the Alba
mark and Google Sans to rebuild `assets/readme/banner.webp` at 3200 × 1280.
To refresh all eleven README screens from new raw captures and rebuild the banner,
run `bun run banner ../../.artifacts/store-captures/raw`.
Refresh the website's four screen exports with
`bun run assets -- /absolute/path/to/raw` from `website/`.
Edit `render-readme.mjs` to change the layout.
