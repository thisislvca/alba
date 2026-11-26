# Backup

How Alba backs up the phone's photos to iCloud, and what "Backed up" promises. For contributors; the code is in `engine/.../companion/` and `protocol-icloud-web/.../photos/PhotosUpload.kt`.

## Pipeline

1. **Discover.** `TransferIntake.discoverAndStageAutomatic` reads MediaStore from a checkpoint: what was added since enrollment first, then (when chosen) the existing library, newest first. Formats iCloud.com does not take are recorded as skipped, with the reason.
2. **Queue.** Each item becomes a `READY` transfer with its exact size and SHA-256. A MediaStore original is read where it is (`InPlaceOriginals`): one read hashes it, checks its format and reads its capture time, with no private copy. Anything that cannot be read in place is staged as a private copy (`UploadStagingStore`), as below.
3. **Look first.** An automatic item is looked up in the synced library (`ExistingCopyCheck`); an exact copy already there is linked without uploading.
4. **Upload.** `UploadInvoker.runRound` starts up to three uploads per account at once (`MAX_UPLOADS_IN_FLIGHT`), each holding a write slot and an upload permit, while discovery queues up to three items ahead. Arming an attempt takes the slot in the same transaction that moves the transfer to `SENDING`, so an attempt is sent at most once. The protocol does `createUploadUrl`, one PUT of the whole file, then `putAsset`, saving each phase in its own encrypted journal row per attempt.
5. **Verify.** `Reconciler` reads iCloud's change feed and matches Apple's receipt (below). One match is `VERIFIED`, with an asset link, the remote pair and a proof of method `RECEIPT`.
6. **Recover.** After a crash, every `SENDING` attempt is resumed from its own journal phase if it can be, or becomes uncertain and is verified without being sent again. An attempt the journal proves was never registered starts afresh after a backoff.

The constraints around this are unchanged: the network and charging settings, the foreground-drain budget, `ProtocolStatusGate` pauses, iCloud storage full, and session expiry.

### Reading in place, and when Alba stages a copy

A source read in place is read through the descriptor MediaStore hands over (MediaStore opens camera files on the lower filesystem, which has no path the app could open again): twice from the start, once to queue it and once as it is sent, and by position for the format check and capture time. A change shows in the descriptor's size and modification time and in MediaStore's revision and `DATE_MODIFIED`; a retry opens the item again by its URI. `SentBytesSource` hashes the bytes as the transport reads them and, before handing over the last bytes, checks the hash against the queued one and that the file's size and modification time did not change. If anything changed, the PUT never completes; the journal proves nothing was registered, so the attempt is let go and the item is read and queued afresh. An item edited between queueing and its upload is caught when it is opened again, and is redone the same way.

Alba stages a private copy instead when the source cannot be re-opened reliably:

- photos the user picks with the system photo picker or a document picker, whose access grant may not outlive the process;
- MediaStore descriptors that are not regular files (a pipe or socket, such as a transcoded or provider-served stream), which cannot be read twice;
- originals whose open descriptor cannot be read by position for the format check and capture time;
- sources that do not implement `ExactOriginalSource.openInPlace`.

## Two levels of verification

### Receipt: what "Backed up" means

While uploading, Alba computes SHA-256 and the byte count over exactly the bytes it sends: the stream is hashed as it is read, so the hashed bytes are the sent bytes, and TLS protects them in transit. Apple's content store answers the PUT with a receipt (`singleFile`), and Alba keeps its `size` and `fileChecksum` with the acceptance. In the change feed, the new CPLMaster must have:

- `resOriginalRes.size` equal to the sent byte count, and
- `resOriginalRes.fileChecksum` equal to the receipt's `fileChecksum`, and
- `resOriginalFingerprint` equal to the receipt's `fileChecksum` too (Apple sets them equal).

Exactly one such record is a backup; none or several by the deadline go to the uncertainty handling below.

Why this is enough: Alba knows what it sent (hashed as sent, refused if it differs from what was queued), TLS guarantees Apple received those bytes, and the receipt is Apple's own size and checksum of what it received. The CPLMaster carrying that same size, checksum and fingerprint is Apple's record that the original it stores is the one it received. Downloading the original again would compare Apple's copy with itself.

The one residual risk is a bug on Apple's side after the receipt: the stored original differs from what was received while the record still carries the receipt's values. That is what the byte check covers, at the one moment it matters.

### Byte check: before a phone copy is deleted

`ByteCheck` downloads the iCloud original afresh and compares every byte, by count and SHA-256, with the phone's file. Free up space (debug-only, `FREE_UP_SPACE_ENABLED`) runs it on each item right before Android's trash request. Only items that pass are offered, and their proof becomes method `BYTES`. An item whose iCloud original differs stays on the phone and its proof is invalidated with `BYTE_CHECK_FAILED`, so it is never offered again. A failed read keeps the item without flagging it.

The byte check also verifies an attempt that has no receipt (one from before receipts were kept), and backs the existing-copy link, which compares before linking.

## Uncertain uploads heal themselves

An upload Reconciler could not settle by its deadline ends uncertain: `NOT_OBSERVED` (no matching record) or `AMBIGUOUS` (several). Its bytes are suppressed, so it is never sent twice while uncertain, and it gives its write slot back, so other uploads go on. `UncertainHealer` then looks for it again; its schedule is `HealSchedule`:

- **When.** Only after a library refresh newer than the last look (the catalog checkpoint's refresh time): after each refresh for the first 24 hours, then at most daily for seven more days. A `NOT_OBSERVED` upload is often one the change feed reported late.
- **How.** The refreshed catalog's items of the same size are read afresh and matched against Apple's receipt, as Reconciler does. One match verifies the upload (proof `RECEIPT`) and lifts the suppression. Several make a `NOT_OBSERVED` upload `AMBIGUOUS`.
- **After the window**, a `NOT_OBSERVED` upload is decided once more. With no receipt match and no candidate at all in the catalog (no item of its size with its file name or capture time, `ExistingCopyMatcher`), nothing in iCloud can be it, so it is sent again in a fresh attempt that names the uncertain one. A single candidate that is an exact copy, compared byte for byte, verifies it instead. Any other candidate makes it `AMBIGUOUS`: a second upload could duplicate it.
- **Ambiguous uploads are never sent again.** They are resolved with the byte check, which downloads each candidate, only when the user opens the item or before Free up space lists what it can remove. Exact copies are the same original, so the upload is verified against the one Apple's acceptance named, or the first. Until then the item waits, marked as being checked, without holding up the queue.

An upload iCloud could not process (`UploadProcessingStatus.Failed`) is recorded as rejected and is never sent again; a later look can still verify it if a copy appears.

## Retries

- **A failed byte transfer starts again from the beginning.** An attempt whose PUT failed never registered a photo (its journal stops before `REGISTERING`), so Reconciler lets it go once a complete read of the changes since it started shows no copy. The transfer is queued again for a fresh attempt, which asks `createUploadUrl` for a fresh URL. It waits 1, 5, 15 and 60 minutes, then once a day (`Reconciler.retryBackoffMillis`), for as long as it takes; "Retry now" skips the wait.
- **An expired URL is never used.** `createUploadUrl`'s URL lasts about 15 minutes. A reservation saved in the journal records when it was made, and an attempt resumed from `RESERVED` after that (or one saved before the time was kept) reserves again before sending any byte.
- **Large items need a long run.** An automatic item over 100 MB (`LARGE_ITEM_BYTES`) starts, or resumes, only in a foreground backup run (`BackupDrain`, `WakeReason.LONG_RUNNING`) on an unmetered network. The 6-minute background budget would always cut it off. A run with a large item queued asks to be a foreground run; Android may refuse that while Alba is in the background, and the item then waits for a run that is allowed. The user's own uploads are not limited.
- **Only clear permanent errors stop retries**, each recorded with its reason (`TransferEntity.failureReason` and the message): a format iCloud.com does not take (skipped at discovery), iCloud refusing the file at registration (a 400, 415 or 422 for that file) or failing to process it, and a phone file that is gone. Everything else is retried.

## No resumable upload

An upload that stops mid-PUT starts its bytes again; there is no ranged or resumed upload, because the endpoint takes a single PUT:

- iCloud.com's own uploader calls `createUploadUrl` for one URL per file, PUTs the whole file to it once, and registers the `singleFile` receipt with `putAsset`;
- rclone's iCloud backend uploads each file in one request and has no resume;
- CloudKit's documentation describes asset upload as one request to the URL it hands out, with no chunked or ranged variant.

What Alba does resume is the attempt: the journal records `RESERVED`, `RECEIPT_SAVED` and `ACCEPTED`, so a crash after the bytes were received registers the saved receipt instead of sending the bytes again.

## Motion photos

An Android motion photo is a JPEG or HEIC with a short MP4 appended, described by its XMP. The "Motion photos" setting on the Backup page decides what backup does with one; it applies to items automatic backup queues after the change.

- **Photo only**: the file uploads as its still, byte for byte, with the embedded video kept, never cut off. Nothing else happens.
- **Photo and video separately**: the still as above, plus the embedded MP4 extracted and staged as its own video item, which backs up through the normal path.
- **As Live Photo** (default): the still as above, then the embedded MP4 is attached to it so iCloud shows a Live Photo. Verified end to end on a device: a Pixel motion photo backed up in the background was attached and plays on iCloud.com with the LIVE badge. The default applies where no choice is saved; a choice the user made is kept.

Under either of the last two, each queued still gets a row in `motion_photo_parts`. Once the still is verified, `MotionPhotoParts` reads the phone file once more (its SHA-256 must still be the one backed up) and `MotionPhotoReader` finds the video; a file with none ends `NOT_MOTION`.

### Formats

| Format | Phones | Supported |
| --- | --- | --- |
| Google Motion Photo v1 (`Camera:MotionPhoto`, `Container:Directory`) | Pixel, newer Samsung, OnePlus, Oppo, Realme | Yes |
| Google MicroVideo (`GCamera:MicroVideoOffset`) | Xiaomi, Redmi, POCO, older Pixel ("MVIMG") | Yes |
| Samsung trailer (`MotionPhoto_Data` block, SEF footer, no XMP) | Older Galaxy | Yes |
| Huawei and Honor "Moving Picture" | Huawei, Honor | Not yet, [#78](https://github.com/thisislvca/alba/issues/78) |
| Vivo "Live Photo" | Vivo | Not yet, [#79](https://github.com/thisislvca/alba/issues/79) |

Unsupported formats back up as stills. The parser is pure and tested on the JVM with synthetic files of each supported format (`MotionPhotosTest`). Offsets in the XMP count back from the end of the file; the MP4 must start with an `ftyp` box where they point (declared padding is tried too), and its length is the run of top-level boxes from there, so a trailer after it is left out. The still's time in the video is `MotionPhotoPresentationTimestampUs` (or `MicroVideoPresentationTimestampUs`), else the middle of the video.

### The Live Photo recipe

Proven on the owner's account: after it, iCloud.com and Photos on a Mac both showed the LIVE badge and played the motion. `LivePhotoAttach` (protocol) does exactly this:

1. Upload the still with the normal upload (`createUploadUrl`, the PUT, `putAsset`), keeping the `cplMaster` and `cplAsset` record names. Alba attaches only once the still is verified by its receipt.
2. Look up both records for their `recordChangeTag`s.
3. `POST {ckdatabasews}/database/1/com.apple.photos.cloud/production/private/assets/upload` with `{zoneID: {zoneName: "PrimarySync"}, tokens: [{recordType: "CPLMaster", fieldName: F, recordName: <master>}]}` for F in `resOriginalVidComplRes`, `resVidMedRes`, `resVidSmallRes`. POST the video bytes raw to each returned `tokens[i].url`; each answers `singleFile {fileChecksum, size, wrappingKey, referenceChecksum, receipt}`.
4. One atomic `records/modify` with two `update` operations at those change tags:
   - CPLMaster, for each prefix P in `resOriginalVidCompl`, `resVidMed`, `resVidSmall`: `PRes` = the receipt as `ASSETID`, `PFileType` = `com.apple.quicktime-movie`, `PFingerprint` = that receipt's `fileChecksum`, `PFileSize`, `PWidth`, `PHeight`.
   - CPLAsset: `vidComplDurValue` (duration in ns) with `vidComplDurScale` 1000000000, `vidComplDispValue` (the still's time in ns) with `vidComplDispScale` 1000000000, `vidComplVisibilityState` 0, `assetSubtypeV2` 2, and `mediaGroupId` (below).

Pitfalls found while proving it:

- Without the `resVidMed` and `resVidSmall` derivatives, iCloud.com shows a warning badge and does not play.
- Real Live Photos use smaller derivatives (about 654×872 and 1 MB, 436×581 and 320 KB, for a 1308×1744 original). Sending the original video for all three worked, and that is what Alba does: no transcoding.
- Changing the video of an existing item was not picked up by clients that had cached the old one. Alba only ever adds a video to a still its own upload created (Apple accepted it as new, `duplicateHint` false, and the verified pair is the one Apple named); a still iCloud already had is left alone and flagged.
- Re-uploading an existing still is refused as a duplicate (409), and a master's record name is its file checksum.
- The test files carried Apple's ContentIdentifier in both halves (the JPEG's Apple MakerNote tag 17 and the MOV's `com.apple.quicktime.content.identifier`), and the server derived the asset's `mediaGroupId` from the still's. Whether pairing needs it is not known.

### Decisions, and what is unverified

- **ContentIdentifier.** Alba does not rewrite the still: writing an Apple MakerNote into a JPEG on Android is not practical, and the still must stay the phone's exact bytes so its receipt and the byte check before Free up space hold. Instead a fresh upper-case UUID per item is written as the asset's `mediaGroupId` in the modify. If CloudKit refuses the record over a field (`BAD_REQUEST`, `INVALID_ARGUMENTS`, or HTTP 400), the modify is sent once more without it, and the part records `mediaGroupIdWritten` false. Nothing is written into the MP4 either: MediaMuxer cannot write QuickTime metadata keys. Verified on the owner's account: CloudKit accepted an upper-case UUID as `mediaGroupId`, and the item played as a Live Photo on iCloud.com and in Photos on a Mac.
- **Container and file type.** The video is the motion photo's own MP4, unchanged. MediaMuxer writes MP4, not QuickTime, so remuxing to .mov is not possible with it; the file type stays the proven `com.apple.quicktime-movie` (an MP4 is the same ISO base media format). Verified: an MP4 under that type plays as a Live Photo on iCloud.com and in Photos on a Mac, and a Pixel motion photo backed up from a device plays on iCloud.com.
- **Derivatives.** The original video for all three resources, as proven; no MediaCodec transcoding.
- **Dimensions** are the video track's as displayed (a quarter-turn matrix swaps them); the duration is the movie header's.

### Journal, verification and failure

- The attach is journalled in the encrypted upload journal under `live:<transfer>:<round>`: `LIVE_UPLOADING`, `LIVE_RECEIPTS_SAVED` (the three receipts and when), `LIVE_MODIFYING`, `LIVE_MODIFIED`. Calling it again resumes: fresh receipts (under 15 minutes old) are reused rather than uploaded again; a master that already holds this video (an earlier modify went through before its response was saved) is recognised and not modified again; the modify only sets fields, so sending it again at fresh change tags is safe. A journal row the current sign-in cannot read counts as none, and the records read afresh decide. A master that already has a different video is refused.
- **Backed up** for a Live Photo needs the still's receipt match (as for any photo) and, after the modify, a fresh read of the CPLMaster showing `resOriginalVidComplRes` with the attach receipt's `fileChecksum` and the video's size. Until then the item shows as uploading ("Adding the motion…") and is not counted as backed up.
- Failures that may pass (network, throttling, a read that does not show the video yet) are tried again with the uploads' backoff, up to five times. One iCloud refuses (a CloudKit record error other than a conflict, a 400 or 404, a still that is not new or already has another video), or the fifth failure, leaves the part `FAILED`: the still stays backed up as a plain photo, the item says "Live Photo part not attached", and Retry (or Retry all) attaches afresh under a new journal round.
- **Free up space** removes a motion photo backed up under either option only once its still and its video both pass the byte check: the iCloud Live Photo video (`resOriginalVidComplRes`) or the separate video item is downloaded and compared with the video bytes read from the phone file. An item whose video is pending, failed or differs stays on the phone.
- Motion photos already backed up as stills are not upgraded.

### Diagnostics

Backup reports to the app's opt-in diagnostics (Sentry, only while the user allows it and a DSN is set) through `BackupDiagnostics`; the engine never calls Sentry. Spans: each backup round, staging or reading in place, the upload, `putAsset`, the receipt match, each heal check, the motion photo read, the Live attach and its `assets/upload`, three POSTs, `records/modify` and re-read, and the byte check. Breadcrumbs: retries, backoff and healing decisions. Events: unexpected exceptions, receipt mismatches, uploads ending `AMBIGUOUS` or `NOT_OBSERVED`, Live attach failures (with CloudKit's `serverErrorCode` or the HTTP status), a byte check failing before deletion (fatal), and motion photo parse failures (format id only). Attributes are limited to phase, outcome, error class or code, media kind, motion format id, size bucket, counts and durations, and `BackupTelemetry.sanitize` drops anything that is not a short identifier, so no file name, path, record name, checksum, receipt, URL, account id, place or EXIF leaves the phone.
