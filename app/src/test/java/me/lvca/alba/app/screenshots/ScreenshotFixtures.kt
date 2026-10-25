package me.lvca.alba.app.screenshots

import me.lvca.alba.app.account.AccountInfo
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.gallery.GalleryState
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.engine.model.CloudAccountProfile
import me.lvca.alba.engine.model.CloudPlanSource
import me.lvca.alba.engine.model.CloudStoragePlan
import me.lvca.alba.engine.model.CloudStorageUsage
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaKind
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedAlbumInfo
import me.lvca.alba.engine.model.SharedAlbumRole
import me.lvca.alba.engine.model.StorageCategory
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import java.time.Instant

/**
 * Fixed state for screenshot tests: no clock, network, files or repository, so a golden changes only when the UI does.
 * Photos have no preview files and show their accent colours, which keeps the images small and stable.
 */
internal object ScreenshotFixtures {
    /** 2024-06-14 09:00 UTC; screenshot tests run in UTC so day and month headers never depend on the machine. */
    private const val NEWEST = 1_718_355_600_000L
    private const val HOUR = 3_600_000L

    private val accents = listOf(
        0xFF5B8DB8 to 0xFF2E4A62, 0xFFE0A458 to 0xFF8C5A2B, 0xFF7FB069 to 0xFF3D5A2E, 0xFFC96B6B to 0xFF6B2E2E,
        0xFF9C89C7 to 0xFF4B3F6B, 0xFF6BB5B0 to 0xFF2F5A57, 0xFFD9C26A to 0xFF7A6A2B, 0xFF8A9BA8 to 0xFF3E4A53,
    )

    /** [count] photos, three a day going back from mid-June 2024; every seventh is a video and every fifth a favourite. */
    fun library(count: Int = 30): List<GalleryMedia> = List(count) { index ->
        val (start, end) = accents[index % accents.size]
        val video = index % 7 == 3
        GalleryMedia(
            id = "photo-$index",
            fileName = if (video) "VID_$index.MOV" else "IMG_$index.HEIC",
            capturedAtEpochMillis = NEWEST - (index / 3) * 24 * HOUR - (index % 3) * HOUR,
            width = if (index % 4 == 1) 3024 else 4032,
            height = if (index % 4 == 1) 4032 else 3024,
            origin = MediaOrigin.ICLOUD,
            availability = MediaAvailability.PREVIEW_CACHED,
            previewReference = null,
            originalReference = null,
            accentStartArgb = start,
            accentEndArgb = end,
            kind = if (video) MediaKind.VIDEO else MediaKind.PHOTO,
            mimeType = if (video) "video/quicktime" else "image/heic",
            durationMillis = if (video) 14_000L else null,
            isFavorite = index % 5 == 0,
        )
    }

    val collections = listOf(
        GalleryCollection("album-trips", "Trips", position = 1, coverMediaId = "photo-2"),
        GalleryCollection("album-family", "Family", position = 2, coverMediaId = "photo-5"),
        GalleryCollection("album-garden", "Garden", position = 3, coverMediaId = "photo-9"),
        GalleryCollection("shared:fixture:private:owner:moments", "Family moments", position = 4,
            shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER, participantCount = 3), coverMediaId = "photo-1"),
    )

    fun gallery(count: Int = 30): GalleryState {
        val items = library(count)
        // Phone access granted, so the gallery shows photos rather than the prompt to include phone photos.
        return GalleryState(items = items, collections = collections, cloudCount = items.size, deviceMediaAccess = true)
    }

    val signedIn = SessionState(
        accountState = ICloudAccountState.SignedIn("alex@example.com"),
        isRefreshing = false,
        network = NetworkStatus(true, false),
        lastRefreshedAt = Instant.ofEpochMilli(NEWEST),
        accountInfo = AccountInfo(
            profile = CloudAccountProfile("Alex Example", null, CloudStoragePlan(setOf(CloudPlanSource.ICLOUD_PLUS), 200L shl 30)),
            storage = CloudStorageUsage(72_000_000_000, 200_000_000_000, listOf(
                StorageCategory("Photos", 64_000_000_000), StorageCategory("Documents", 4_000_000_000),
                StorageCategory("Backup", 2_000_000_000), StorageCategory("Other", 2_000_000_000),
            ), NEWEST),
            photosAvailable = true,
        ),
    )

    val signedOut = SessionState(accountState = ICloudAccountState.SignedOut, isRefreshing = false)

    val offline = signedIn.copy(accountState = ICloudAccountState.SignedIn("alex@example.com", SessionStatus.OFFLINE))
}
