package me.lvca.alba.app.ui.designsystem

// Material Icons path data is licensed under Apache-2.0; see THIRD_PARTY_NOTICES.md.

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.ui.designsystem.theme.AlbaIconSize
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme

/**
 * Alba's one icon family is Material's Rounded set. These are the Rounded glyphs material-icons-core leaves out, from
 * Material Icons' path data. Core glyphs come from `Icons.Rounded` and `Icons.AutoMirrored.Rounded`, and the collection
 * glyphs in `res/drawable` are Rounded too. Don't mix in Outlined, Filled or another family.
 */
internal object AlbaIcons {
    val Pause: ImageVector by lazy {
        pathIcon(
            "Pause",
            "M8 19c1.1 0 2 -0.9 2 -2L10 7c0 -1.1 -0.9 -2 -2 -2s-2 0.9 -2 2v10c0 1.1 0.9 2 2 2ZM14 7v10c0 1.1 0.9 2 2 2s2 " +
            "-0.9 2 -2L18 7c0 -1.1 -0.9 -2 -2 -2s-2 0.9 -2 2Z",
        )
    }

    val VolumeUp: ImageVector by lazy {
        pathIcon(
            "VolumeUp",
            "M3 10v4c0 0.55 0.45 1 1 1h3l3.29 3.29c0.63 0.63 1.71 0.18 1.71 -0.71L12 6.41c0 -0.89 -1.08 -1.34 -1.71 " +
            "-0.71L7 9L4 9c-0.55 0 -1 0.45 -1 1ZM16.5 12c0 -1.77 -1.02 -3.29 -2.5 -4.03v8.05c1.48 -0.73 2.5 -2.25 2.5 " +
            "-4.02ZM14 4.45v0.2c0 0.38 0.25 0.71 0.6 0.85C17.18 6.53 19 9.06 19 12s-1.82 5.47 -4.4 6.5c-0.36 0.14 -0.6 " +
            "0.47 -0.6 0.85v0.2c0 0.63 0.63 1.07 1.21 0.85C18.6 19.11 21 15.84 21 12s-2.4 -7.11 -5.79 -8.4c-0.58 -0.23 " +
            "-1.21 0.22 -1.21 0.85Z",
        )
    }

    val VolumeOff: ImageVector by lazy {
        pathIcon(
            "VolumeOff",
            "M3.63 3.63c-0.39 0.39 -0.39 1.02 0 1.41L7.29 8.7L7 9L4 9c-0.55 0 -1 0.45 -1 1v4c0 0.55 0.45 1 1 1h3l3.29 " +
            "3.29c0.63 0.63 1.71 0.18 1.71 -0.71v-4.17l4.18 4.18c-0.49 0.37 -1.02 0.68 -1.6 0.91c-0.36 0.15 -0.58 0.53 " +
            "-0.58 0.92c0 0.72 0.73 1.18 1.39 0.91c0.8 -0.33 1.55 -0.77 2.22 -1.31l1.34 1.34c0.39 0.39 1.02 0.39 1.41 " +
            "0c0.39 -0.39 0.39 -1.02 0 -1.41L5.05 3.63c-0.39 -0.39 -1.02 -0.39 -1.42 0ZM19 12c0 0.82 -0.15 1.61 -0.41 " +
            "2.34l1.53 1.53c0.56 -1.17 0.88 -2.48 0.88 -3.87c0 -3.83 -2.4 -7.11 -5.78 -8.4c-0.59 -0.23 -1.22 0.23 -1.22 " +
            "0.86v0.19c0 0.38 0.25 0.71 0.61 0.85C17.18 6.54 19 9.06 19 12ZM10.29 5.71l-0.17 0.17L12 7.76L12 6.41c0 -0.89 " +
            "-1.08 -1.33 -1.71 -0.7ZM16.5 12c0 -1.77 -1.02 -3.29 -2.5 -4.03v1.79l2.48 2.48c0.01 -0.08 0.02 -0.16 0.02 " +
            "-0.24Z",
        )
    }

    val CloudDone: ImageVector by lazy {
        pathIcon(
            "CloudDone",
            "M19.35 10.04C18.67 6.59 15.64 4 12 4C9.11 4 6.6 5.64 5.35 8.04C2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 " +
            "6h13c2.76 0 5 -2.24 5 -5c0 -2.64 -2.05 -4.78 -4.65 -4.96ZM10.71 16.29c-0.39 0.39 -1.02 0.39 -1.41 0L7.2 " +
            "14.2c-0.39 -0.39 -0.39 -1.02 0 -1.41c0.39 -0.39 1.02 -0.39 1.41 0L10 14.18l4.48 -4.48c0.39 -0.39 1.02 -0.39 " +
            "1.41 0c0.39 0.39 0.39 1.02 0 1.41l-5.18 5.18Z",
        )
    }

    val CloudQueue: ImageVector by lazy {
        pathIcon(
            "CloudQueue",
            "M19.35 10.04C18.67 6.59 15.64 4 12 4C9.11 4 6.6 5.64 5.35 8.04C2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 " +
            "6h13c2.76 0 5 -2.24 5 -5c0 -2.64 -2.05 -4.78 -4.65 -4.96ZM19 18H6c-2.21 0 -4 -1.79 -4 -4s1.79 -4 4 " +
            "-4h0.71C7.37 7.69 9.48 6 12 6c3.04 0 5.5 2.46 5.5 5.5v0.5H19c1.66 0 3 1.34 3 3s-1.34 3 -3 3Z",
        )
    }

    val CloudUpload: ImageVector by lazy {
        pathIcon(
            "CloudUpload",
            "M19.35 10.04C18.67 6.59 15.64 4 12 4C9.11 4 6.6 5.64 5.35 8.04C2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 " +
            "6h13c2.76 0 5 -2.24 5 -5c0 -2.64 -2.05 -4.78 -4.65 -4.96ZM14 13v4h-4v-4H7l4.65 -4.65c0.2 -0.2 0.51 -0.2 0.71 " +
            "0L17 13h-3Z",
        )
    }

    val DeleteOutline: ImageVector by lazy {
        pathIcon(
            "DeleteOutline",
            "M6 19c0 1.1 0.9 2 2 2h8c1.1 0 2 -0.9 2 -2L18 9c0 -1.1 -0.9 -2 -2 -2L8 7c-1.1 0 -2 0.9 -2 2v10ZM9 9h6c0.55 0 1 " +
            "0.45 1 1v8c0 0.55 -0.45 1 -1 1L9 19c-0.55 0 -1 -0.45 -1 -1v-8c0 -0.55 0.45 -1 1 -1ZM15.5 4l-0.71 -0.71c-0.18 " +
            "-0.18 -0.44 -0.29 -0.7 -0.29L9.91 3c-0.26 0 -0.52 0.11 -0.7 0.29L8.5 4L6 4c-0.55 0 -1 0.45 -1 1s0.45 1 1 " +
            "1h12c0.55 0 1 -0.45 1 -1s-0.45 -1 -1 -1h-2.5Z",
        )
    }

    val DeleteSweep: ImageVector by lazy {
        pathIcon(
            "DeleteSweep",
            "M16 16h2c0.55 0 1 0.45 1 1s-0.45 1 -1 1h-2c-0.55 0 -1 -0.45 -1 -1s0.45 -1 1 -1ZM16 8h5c0.55 0 1 0.45 1 " +
            "1s-0.45 1 -1 1h-5c-0.55 0 -1 -0.45 -1 -1s0.45 -1 1 -1ZM16 12h4c0.55 0 1 0.45 1 1s-0.45 1 -1 1h-4c-0.55 0 -1 " +
            "-0.45 -1 -1s0.45 -1 1 -1ZM3 18c0 1.1 0.9 2 2 2h6c1.1 0 2 -0.9 2 -2L13 8L3 8v10ZM13 5h-2l-0.71 -0.71c-0.18 " +
            "-0.18 -0.44 -0.29 -0.7 -0.29L6.41 4c-0.26 0 -0.52 0.11 -0.7 0.29L5 5L3 5c-0.55 0 -1 0.45 -1 1s0.45 1 1 " +
            "1h10c0.55 0 1 -0.45 1 -1s-0.45 -1 -1 -1Z",
        )
    }

    val DownloadForOffline: ImageVector by lazy {
        pathIcon(
            "DownloadForOffline",
            "M12 2C6.49 2 2 6.49 2 12s4.49 10 10 10s10 -4.49 10 -10S17.51 2 12 2ZM11 10V7c0 -0.55 0.45 -1 1 -1h0c0.55 0 1 " +
            "0.45 1 1v3h1.79c0.45 0 0.67 0.54 0.35 0.85l-2.79 2.79c-0.2 0.2 -0.51 0.2 -0.71 0l-2.79 -2.79C8.54 10.54 8.76 " +
            "10 9.21 10H11ZM16 17H8c-0.55 0 -1 -0.45 -1 -1v0c0 -0.55 0.45 -1 1 -1h8c0.55 0 1 0.45 1 1v0C17 16.55 16.55 17 " +
            "16 17Z",
        )
    }

    val CloudOff: ImageVector by lazy {
        pathIcon(
            "CloudOff",
            "M24 15c0 -2.64 -2.05 -4.78 -4.65 -4.96C18.67 6.59 15.64 4 12 4c-1.33 0 -2.57 0.36 -3.65 0.97l1.49 1.49C10.51 " +
            "6.17 11.23 6 12 6c3.04 0 5.5 2.46 5.5 5.5v0.5H19c1.66 0 3 1.34 3 3c0 0.99 -0.48 1.85 -1.21 2.4l1.41 1.41c1.09 " +
            "-0.92 1.8 -2.27 1.8 -3.81ZM3.71 4.56c-0.39 0.39 -0.39 1.02 0 1.41l2.06 2.06h-0.42c-3.28 0.35 -5.76 3.34 -5.29 " +
            "6.79C0.46 17.84 3.19 20 6.22 20h11.51l1.29 1.29c0.39 0.39 1.02 0.39 1.41 0c0.39 -0.39 0.39 -1.02 0 -1.41L5.12 " +
            "4.56c-0.39 -0.39 -1.02 -0.39 -1.41 0ZM6 18c-2.21 0 -4 -1.79 -4 -4s1.79 -4 4 -4h1.73l8 8H6Z",
        )
    }

    val ErrorOutline: ImageVector by lazy {
        pathIcon(
            "ErrorOutline",
            "M12 7c0.55 0 1 0.45 1 1v4c0 0.55 -0.45 1 -1 1s-1 -0.45 -1 -1L11 8c0 -0.55 0.45 -1 1 -1ZM11.99 2C6.47 2 2 6.48 " +
            "2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2ZM12 20c-4.42 0 -8 -3.58 -8 -8s3.58 -8 8 -8s8 " +
            "3.58 8 8s-3.58 8 -8 8ZM13 17h-2v-2h2v2Z",
        )
    }

    val PhoneAndroid: ImageVector by lazy {
        pathIcon(
            "PhoneAndroid",
            "M16 1L8 1C6.34 1 5 2.34 5 4v16c0 1.66 1.34 3 3 3h8c1.66 0 3 -1.34 3 -3L19 4c0 -1.66 -1.34 -3 -3 -3ZM13.5 " +
            "21h-3c-0.28 0 -0.5 -0.22 -0.5 -0.5s0.22 -0.5 0.5 -0.5h3c0.28 0 0.5 0.22 0.5 0.5s-0.22 0.5 -0.5 0.5ZM17 18L7 " +
            "18L7 4h10v14Z",
        )
    }

    val SwapVert: ImageVector by lazy {
        pathIcon(
            "SwapVert",
            "M16 17.01V11c0 -0.55 -0.45 -1 -1 -1s-1 0.45 -1 1v6.01h-1.79c-0.45 0 -0.67 0.54 -0.35 0.85l2.79 2.78c0.2 0.19 " +
            "0.51 0.19 0.71 0l2.79 -2.78c0.32 -0.31 0.09 -0.85 -0.35 -0.85H16ZM8.65 3.35L5.86 6.14c-0.32 0.31 -0.1 0.85 " +
            "0.35 0.85H8V13c0 0.55 0.45 1 1 1s1 -0.45 1 -1V6.99h1.79c0.45 0 0.67 -0.54 0.35 -0.85L9.35 3.35c-0.19 -0.19 " +
            "-0.51 -0.19 -0.7 0Z",
        )
    }

    val CloudSync: ImageVector by lazy {
        pathIcon(
            "CloudSync",
            "M24 17.48c0 1.38 -1.12 2.5 -2.5 2.5L15 20c-1.66 0 -3 -1.34 -3 -3c0 -1.6 1.26 -2.9 2.84 -2.98C15.4 12.83 16.6 " +
            "12 18 12c1.76 0 3.2 1.3 3.45 2.99c0.02 0 0.03 -0.01 0.05 -0.01C22.88 14.98 24 16.1 24 17.48ZM10 15c0 -0.55 " +
            "-0.45 -1 -1 -1s-1 0.45 -1 1v1.44c-1.22 -1.1 -2 -2.67 -2 -4.44c0 -2.38 1.39 -4.43 3.4 -5.4C9.77 6.42 10 6.04 " +
            "10 5.63c0 -0.71 -0.73 -1.18 -1.37 -0.88C5.89 6.03 4 8.79 4 12c0 2.4 1.06 4.54 2.73 6H5c-0.55 0 -1 0.45 -1 " +
            "1s0.45 1 1 1h4c0.55 0 1 -0.45 1 -1V15ZM19 6c0.55 0 1 -0.45 1 -1s-0.45 -1 -1 -1h-4c-0.55 0 -1 0.45 -1 1v4c0 " +
            "0.55 0.45 1 1 1s1 -0.45 1 -1V7.56c0.98 0.89 1.68 2.08 1.92 3.44l2.02 0c-0.25 -1.99 -1.23 -3.74 -2.66 -5H19Z",
        )
    }

    val SaveAlt: ImageVector by lazy {
        pathIcon(
            "SaveAlt",
            "M19 13v5c0 0.55 -0.45 1 -1 1L6 19c-0.55 0 -1 -0.45 -1 -1v-5c0 -0.55 -0.45 -1 -1 -1s-1 0.45 -1 1v6c0 1.1 0.9 2 " +
            "2 2h14c1.1 0 2 -0.9 2 -2v-6c0 -0.55 -0.45 -1 -1 -1s-1 0.45 -1 1ZM13 12.67l1.88 -1.88c0.39 -0.39 1.02 -0.39 " +
            "1.41 0c0.39 0.39 0.39 1.02 0 1.41l-3.59 3.59c-0.39 0.39 -1.02 0.39 -1.41 0L7.7 12.2c-0.39 -0.39 -0.39 -1.02 0 " +
            "-1.41c0.39 -0.39 1.02 -0.39 1.41 0L11 12.67L11 4c0 -0.55 0.45 -1 1 -1s1 0.45 1 1v8.67Z",
        )
    }

    val PhotoLibrary: ImageVector by lazy {
        pathIcon(
            "PhotoLibrary",
            "M22 16L22 4c0 -1.1 -0.9 -2 -2 -2L8 2c-1.1 0 -2 0.9 -2 2v12c0 1.1 0.9 2 2 2h12c1.1 0 2 -0.9 2 -2ZM11.4 " +
            "12.53l1.63 2.18l2.58 -3.22c0.2 -0.25 0.58 -0.25 0.78 0l2.96 3.7c0.26 0.33 0.03 0.81 -0.39 0.81L9 16c-0.41 0 " +
            "-0.65 -0.47 -0.4 -0.8l2 -2.67c0.2 -0.26 0.6 -0.26 0.8 0ZM2 7v13c0 1.1 0.9 2 2 2h13c0.55 0 1 -0.45 1 -1s-0.45 " +
            "-1 -1 -1L5 20c-0.55 0 -1 -0.45 -1 -1L4 7c0 -0.55 -0.45 -1 -1 -1s-1 0.45 -1 1Z",
        )
    }

    val Print: ImageVector by lazy {
        pathIcon(
            "Print",
            "M19 8L5 8c-1.66 0 -3 1.34 -3 3v4c0 1.1 0.9 2 2 2h2v2c0 1.1 0.9 2 2 2h8c1.1 0 2 -0.9 2 -2v-2h2c1.1 0 2 -0.9 2 " +
            "-2v-4c0 -1.66 -1.34 -3 -3 -3ZM15 19L9 19c-0.55 0 -1 -0.45 -1 -1v-4h8v4c0 0.55 -0.45 1 -1 1ZM19 12c-0.55 0 -1 " +
            "-0.45 -1 -1s0.45 -1 1 -1s1 0.45 1 1s-0.45 1 -1 1ZM17 3L7 3c-0.55 0 -1 0.45 -1 1v2c0 0.55 0.45 1 1 1h10c0.55 0 " +
            "1 -0.45 1 -1L18 4c0 -0.55 -0.45 -1 -1 -1Z",
        )
    }

    val Visibility: ImageVector by lazy {
        pathIcon(
            "Visibility",
            "M12 4C7 4 2.73 7.11 1 11.5C2.73 15.89 7 19 12 19s9.27 -3.11 11 -7.5C21.27 7.11 17 4 12 4ZM12 16.5c-2.76 0 -5 " +
            "-2.24 -5 -5s2.24 -5 5 -5s5 2.24 5 5s-2.24 5 -5 5ZM12 8.5c-1.66 0 -3 1.34 -3 3s1.34 3 3 3s3 -1.34 3 -3s-1.34 " +
            "-3 -3 -3Z",
        )
    }

    val VisibilityOff: ImageVector by lazy {
        pathIcon(
            "VisibilityOff",
            "M12 6.5c2.76 0 5 2.24 5 5c0 0.51 -0.1 1 -0.24 1.46l3.06 3.06c1.39 -1.23 2.49 -2.77 3.18 -4.53C21.27 7.11 17 4 " +
            "12 4c-1.27 0 -2.49 0.2 -3.64 0.57l2.17 2.17c0.47 -0.14 0.96 -0.24 1.47 -0.24ZM2.71 3.16c-0.39 0.39 -0.39 1.02 " +
            "0 1.41l1.97 1.97C3.06 7.83 1.77 9.53 1 11.5C2.73 15.89 7 19 12 19c1.52 0 2.97 -0.3 4.31 -0.82l2.72 2.72c0.39 " +
            "0.39 1.02 0.39 1.41 0c0.39 -0.39 0.39 -1.02 0 -1.41L4.13 3.16c-0.39 -0.39 -1.03 -0.39 -1.42 0ZM12 16.5c-2.76 " +
            "0 -5 -2.24 -5 -5c0 -0.77 0.18 -1.5 0.49 -2.14l1.57 1.57c-0.03 0.18 -0.06 0.37 -0.06 0.57c0 1.66 1.34 3 3 " +
            "3c0.2 0 0.38 -0.03 0.57 -0.07L14.14 16c-0.65 0.32 -1.37 0.5 -2.14 0.5ZM14.97 11.17c-0.15 -1.4 -1.25 -2.49 " +
            "-2.64 -2.64l2.64 2.64Z",
        )
    }

    val Key: ImageVector by lazy {
        pathIcon(
            "Key",
            "M12.65 10C11.7 7.31 8.9 5.5 5.77 6.12c-2.29 0.46 -4.15 2.29 -4.63 4.58C0.32 14.57 3.26 18 7 18c2.61 0 4.83 " +
            "-1.67 5.65 -4H17v2c0 1.1 0.9 2 2 2s2 -0.9 2 -2v-2c1.1 0 2 -0.9 2 -2s-0.9 -2 -2 -2h-8.35ZM7 14c-1.1 0 -2 -0.9 " +
            "-2 -2s0.9 -2 2 -2s2 0.9 2 2s-0.9 2 -2 2Z",
        )
    }
}

/** An icon from Material Icons' SVG path data. */
private fun pathIcon(name: String, pathData: String): ImageVector = ImageVector.Builder(
    name = "Alba.$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()

/** Favourites are a heart everywhere: filled in the favourite red when set, outlined in the content colour when not. */
@Composable
internal fun FavoriteIcon(favorite: Boolean, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = if (favorite) AlbaTheme.colors.favorite else LocalContentColor.current) {
    Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, contentDescription, modifier, tint)
}

/** An iCloud collection or media-type glyph, in the primary colour (at least 3:1 on every surface). */
@Composable
internal fun ICloudIcon(@DrawableRes resource: Int) {
    Icon(painterResource(resource), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(AlbaIconSize.standard))
}
