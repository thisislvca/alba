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

A source read in place is read twice: once to queue it, once as it is sent. `SentBytesSource` hashes the bytes as the transport reads them and, before handing over the last bytes, checks the hash against the queued one and that the file's size and modification time did not change. If anything changed, the PUT never completes; the journal proves nothing was registered, so the attempt is let go and the item is read and queued afresh. An item edited between queueing and its upload is caught when it is opened again, and is redone the same way.

Alba stages a private copy instead when the source cannot be re-opened reliably:

- photos the user picks with the system photo picker or a document picker, whose access grant may not outlive the process;
- MediaStore descriptors that are not regular files (a pipe or socket, such as a transcoded or provider-served stream), which cannot be read twice;
- originals whose random-access view (`/proc/self/fd`) cannot be read for the format check and capture time;
- sources that do not implement `ExactOriginalSource.openInPlace`.

## Two levels of verification

### Receipt: what "Backed up" means

While uploading, Alba computes SHA-256 and the byte count over exactly the bytes it sends: the stream is hashed as it is read, so the hashed bytes are the sent bytes, and TLS protects them in transit. Apple's content store answers the PUT with a receipt (`singleFile`), and Alba keeps its `size` and `fileChecksum` with the acceptance. In the change feed, the new CPLMaster must have:

- `resOriginalRes.size` equal to the sent byte count, and
- `resOriginalRes.fileChecksum` equal to the receipt's `fileChecksum`, and
- `resOriginalFingerprint` equal to the receipt's `fileChecksum` too (Apple sets them equal).

Exactly one such record is a backup; none or several by the deadline go to the uncertainty handling (no second upload, the bytes are suppressed).

Why this is enough: Alba knows what it sent (hashed as sent, refused if it differs from what was queued), TLS guarantees Apple received those bytes, and the receipt is Apple's own size and checksum of what it received. The CPLMaster carrying that same size, checksum and fingerprint is Apple's record that the original it stores is the one it received. Downloading the original again would compare Apple's copy with itself.

The one residual risk is a bug on Apple's side after the receipt: the stored original differs from what was received while the record still carries the receipt's values. That is what the byte check covers, at the one moment it matters.

### Byte check: before a phone copy is deleted

`ByteCheck` downloads the iCloud original afresh and compares every byte, by count and SHA-256, with the phone's file. Free up space (debug-only, `FREE_UP_SPACE_ENABLED`) runs it on each item right before Android's trash request. Only items that pass are offered, and their proof becomes method `BYTES`. An item whose iCloud original differs stays on the phone and its proof is invalidated with `BYTE_CHECK_FAILED`, so it is never offered again. A failed read keeps the item without flagging it.

The byte check also verifies an attempt that has no receipt (one from before receipts were kept), and backs the existing-copy link, which compares before linking.

## No resumable upload

An upload that stops mid-PUT starts its bytes again; there is no ranged or resumed upload, because the endpoint takes a single PUT:

- iCloud.com's own uploader calls `createUploadUrl` for one URL per file, PUTs the whole file to it once, and registers the `singleFile` receipt with `putAsset`;
- rclone's iCloud backend uploads each file in one request and has no resume;
- CloudKit's documentation describes asset upload as one request to the URL it hands out, with no chunked or ranged variant.

What Alba does resume is the attempt: the journal records `RESERVED`, `RECEIPT_SAVED` and `ACCEPTED`, so a crash after the bytes were received registers the saved receipt instead of sending the bytes again.

## Motion photos

iCloud.com cannot pair a still with a video into a Live Photo. The policy:

- **Photo only** (today): a Motion Photo uploads as its still, byte for byte, with the video embedded in the file kept, never cut off.
- **Photo and video separately**: the other allowed option, as two ordinary items.
- **Live Photo**: hidden until it is proven on real accounts.
