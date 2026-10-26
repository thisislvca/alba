package me.lvca.alba.app.ui.designsystem

// Material Icons path data is licensed under Apache-2.0; see THIRD_PARTY_NOTICES.md.

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme

internal object AlbaIcons {
    val Pause: ImageVector by lazy {
        icon("Pause") {
            moveTo(6f, 4f); horizontalLineTo(10f); verticalLineTo(20f); horizontalLineTo(6f); close()
            moveTo(14f, 4f); horizontalLineTo(18f); verticalLineTo(20f); horizontalLineTo(14f); close()
        }
    }

    val VolumeUp: ImageVector by lazy {
        icon("VolumeUp") {
            moveTo(3f, 9f); horizontalLineTo(7f); lineTo(12f, 5f); verticalLineTo(19f); lineTo(7f, 15f); horizontalLineTo(3f); close()
            moveTo(14f, 8f); lineTo(15.4f, 6.6f); curveTo(18.4f, 9.3f, 18.4f, 14.7f, 15.4f, 17.4f); lineTo(14f, 16f)
            curveTo(16.2f, 13.9f, 16.2f, 10.1f, 14f, 8f); close()
            moveTo(17.3f, 4.7f); lineTo(18.7f, 3.3f); curveTo(23.1f, 7.7f, 23.1f, 16.3f, 18.7f, 20.7f)
            lineTo(17.3f, 19.3f); curveTo(20.9f, 15.7f, 20.9f, 8.3f, 17.3f, 4.7f); close()
        }
    }

    val VolumeOff: ImageVector by lazy {
        icon("VolumeOff") {
            moveTo(3f, 9f); horizontalLineTo(7f); lineTo(12f, 5f); verticalLineTo(19f); lineTo(7f, 15f); horizontalLineTo(3f); close()
            moveTo(15.4f, 9f); lineTo(17f, 10.6f); lineTo(18.6f, 9f); lineTo(20f, 10.4f)
            lineTo(18.4f, 12f); lineTo(20f, 13.6f); lineTo(18.6f, 15f)
            lineTo(17f, 13.4f); lineTo(15.4f, 15f); lineTo(14f, 13.6f)
            lineTo(15.6f, 12f); lineTo(14f, 10.4f); close()
        }
    }

    val CloudDone: ImageVector by lazy {
        icon("CloudDone") {
            moveTo(19.35f, 10.04f)
            curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
            curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
            curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
            curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
            horizontalLineToRelative(13.0f)
            curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
            curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
            close()
            moveTo(19.0f, 18.0f)
            lineTo(6.0f, 18.0f)
            curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
            curveToRelative(0.0f, -2.05f, 1.53f, -3.76f, 3.56f, -3.97f)
            lineToRelative(1.07f, -0.11f)
            lineToRelative(0.5f, -0.95f)
            curveTo(8.08f, 7.14f, 9.94f, 6.0f, 12.0f, 6.0f)
            curveToRelative(2.62f, 0.0f, 4.88f, 1.86f, 5.39f, 4.43f)
            lineToRelative(0.3f, 1.5f)
            lineToRelative(1.53f, 0.11f)
            curveToRelative(1.56f, 0.1f, 2.78f, 1.41f, 2.78f, 2.96f)
            curveToRelative(0.0f, 1.65f, -1.35f, 3.0f, -3.0f, 3.0f)
            close()
            moveTo(10.0f, 14.18f)
            lineToRelative(-2.09f, -2.09f)
            lineTo(6.5f, 13.5f)
            lineTo(10.0f, 17.0f)
            lineToRelative(6.01f, -6.01f)
            lineToRelative(-1.41f, -1.41f)
            close()
        }
    }

    val CloudQueue: ImageVector by lazy {
        icon("CloudQueue") {
            moveTo(19.35f, 10.04f)
            curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
            curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
            curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
            curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
            horizontalLineToRelative(13.0f)
            curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
            curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
            close()
            moveTo(19.0f, 18.0f)
            horizontalLineTo(6.0f)
            curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
            reflectiveCurveToRelative(1.79f, -4.0f, 4.0f, -4.0f)
            horizontalLineToRelative(0.71f)
            curveTo(7.37f, 7.69f, 9.48f, 6.0f, 12.0f, 6.0f)
            curveToRelative(3.04f, 0.0f, 5.5f, 2.46f, 5.5f, 5.5f)
            verticalLineToRelative(0.5f)
            horizontalLineTo(19.0f)
            curveToRelative(1.66f, 0.0f, 3.0f, 1.34f, 3.0f, 3.0f)
            reflectiveCurveToRelative(-1.34f, 3.0f, -3.0f, 3.0f)
            close()
        }
    }

    val CloudUpload: ImageVector by lazy {
        icon("CloudUpload") {
            moveTo(19.35f, 10.04f)
            curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
            curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
            curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
            curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
            horizontalLineToRelative(13.0f)
            curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
            curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
            close()
            moveTo(19.0f, 18.0f)
            horizontalLineTo(6.0f)
            curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
            curveToRelative(0.0f, -2.05f, 1.53f, -3.76f, 3.56f, -3.97f)
            lineToRelative(1.07f, -0.11f)
            lineToRelative(0.5f, -0.95f)
            curveTo(8.08f, 7.14f, 9.94f, 6.0f, 12.0f, 6.0f)
            curveToRelative(2.62f, 0.0f, 4.88f, 1.86f, 5.39f, 4.43f)
            lineToRelative(0.3f, 1.5f)
            lineToRelative(1.53f, 0.11f)
            curveToRelative(1.56f, 0.1f, 2.78f, 1.41f, 2.78f, 2.96f)
            curveToRelative(0.0f, 1.65f, -1.35f, 3.0f, -3.0f, 3.0f)
            close()
            moveTo(8.0f, 13.0f)
            horizontalLineToRelative(2.55f)
            verticalLineToRelative(3.0f)
            horizontalLineToRelative(2.9f)
            verticalLineToRelative(-3.0f)
            horizontalLineTo(16.0f)
            lineToRelative(-4.0f, -4.0f)
            close()
        }
    }

    val DeleteOutline: ImageVector by lazy {
        icon("DeleteOutline") {
            moveTo(6.0f, 19.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(8.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(18.0f, 7.0f)
            lineTo(6.0f, 7.0f)
            verticalLineToRelative(12.0f)
            close()
            moveTo(8.0f, 9.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(10.0f)
            lineTo(8.0f, 19.0f)
            lineTo(8.0f, 9.0f)
            close()
            moveTo(15.5f, 4.0f)
            lineToRelative(-1.0f, -1.0f)
            horizontalLineToRelative(-5.0f)
            lineToRelative(-1.0f, 1.0f)
            lineTo(5.0f, 4.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(14.0f)
            lineTo(19.0f, 4.0f)
            horizontalLineToRelative(-3.5f)
            close()
        }
    }

    val DeleteSweep: ImageVector by lazy {
        icon("DeleteSweep") {
            moveTo(15.0f, 16.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(-4.0f)
            close()
            moveTo(15.0f, 8.0f)
            horizontalLineToRelative(7.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(-7.0f)
            close()
            moveTo(15.0f, 12.0f)
            horizontalLineToRelative(6.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(-6.0f)
            close()
            moveTo(3.0f, 18.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(6.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(13.0f, 8.0f)
            lineTo(3.0f, 8.0f)
            verticalLineToRelative(10.0f)
            close()
            moveTo(5.0f, 10.0f)
            horizontalLineToRelative(6.0f)
            verticalLineToRelative(8.0f)
            lineTo(5.0f, 18.0f)
            verticalLineToRelative(-8.0f)
            close()
            moveTo(10.0f, 4.0f)
            lineTo(6.0f, 4.0f)
            lineTo(5.0f, 5.0f)
            lineTo(2.0f, 5.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(12.0f)
            lineTo(14.0f, 5.0f)
            horizontalLineToRelative(-3.0f)
            close()
        }
    }

    val DownloadForOffline: ImageVector by lazy {
        icon("DownloadForOffline") {
            moveTo(12.0f, 2.0f)
            curveTo(6.49f, 2.0f, 2.0f, 6.49f, 2.0f, 12.0f)
            reflectiveCurveToRelative(4.49f, 10.0f, 10.0f, 10.0f)
            reflectiveCurveToRelative(10.0f, -4.49f, 10.0f, -10.0f)
            reflectiveCurveTo(17.51f, 2.0f, 12.0f, 2.0f)
            close()
            moveTo(12.0f, 20.0f)
            curveToRelative(-4.41f, 0.0f, -8.0f, -3.59f, -8.0f, -8.0f)
            reflectiveCurveToRelative(3.59f, -8.0f, 8.0f, -8.0f)
            reflectiveCurveToRelative(8.0f, 3.59f, 8.0f, 8.0f)
            reflectiveCurveTo(16.41f, 20.0f, 12.0f, 20.0f)
            close()
            moveTo(14.59f, 8.59f)
            lineTo(16.0f, 10.0f)
            lineToRelative(-4.0f, 4.0f)
            lineToRelative(-4.0f, -4.0f)
            lineToRelative(1.41f, -1.41f)
            lineTo(11.0f, 10.17f)
            verticalLineTo(6.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(4.17f)
            lineTo(14.59f, 8.59f)
            close()
            moveTo(17.0f, 17.0f)
            horizontalLineTo(7.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(10.0f)
            verticalLineTo(17.0f)
            close()
        }
    }

    val ErrorOutline: ImageVector by lazy {
        icon("ErrorOutline") {
            moveTo(11.0f, 15.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-2.0f)
            close()
            moveTo(11.0f, 7.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(-2.0f)
            lineTo(11.0f, 7.0f)
            close()
            moveTo(11.99f, 2.0f)
            curveTo(6.47f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
            reflectiveCurveToRelative(4.47f, 10.0f, 9.99f, 10.0f)
            curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
            reflectiveCurveTo(17.52f, 2.0f, 11.99f, 2.0f)
            close()
            moveTo(12.0f, 20.0f)
            curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
            reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
            reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
            reflectiveCurveToRelative(-3.58f, 8.0f, -8.0f, 8.0f)
            close()
        }
    }

    val PhoneAndroid: ImageVector by lazy {
        icon("PhoneAndroid") {
            moveTo(16.0f, 1.0f)
            lineTo(8.0f, 1.0f)
            curveTo(6.34f, 1.0f, 5.0f, 2.34f, 5.0f, 4.0f)
            verticalLineToRelative(16.0f)
            curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
            horizontalLineToRelative(8.0f)
            curveToRelative(1.66f, 0.0f, 3.0f, -1.34f, 3.0f, -3.0f)
            lineTo(19.0f, 4.0f)
            curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
            close()
            moveTo(17.0f, 18.0f)
            lineTo(7.0f, 18.0f)
            lineTo(7.0f, 4.0f)
            horizontalLineToRelative(10.0f)
            verticalLineToRelative(14.0f)
            close()
            moveTo(14.0f, 21.0f)
            horizontalLineToRelative(-4.0f)
            verticalLineToRelative(-1.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(1.0f)
            close()
        }
    }

    /** Arrows up and down: uploads, downloads and other transfers. */
    val SwapVert: ImageVector by lazy {
        icon("SwapVert") {
            moveTo(16f, 17.01f); verticalLineTo(10f); horizontalLineToRelative(-2f); verticalLineToRelative(7.01f); horizontalLineToRelative(-3f)
            lineTo(15f, 21f); lineToRelative(4f, -3.99f); horizontalLineToRelative(-3f); close()
            moveTo(9f, 3f); lineTo(5f, 6.99f); horizontalLineToRelative(3f); verticalLineTo(14f); horizontalLineToRelative(2f)
            verticalLineTo(6.99f); horizontalLineToRelative(3f); lineTo(9f, 3f); close()
        }
    }

    /** A tray with an arrow into it: saving a copy onto this phone. */
    val SaveAlt: ImageVector by lazy {
        icon("SaveAlt") {
            moveTo(19f, 12f); verticalLineToRelative(7f); horizontalLineTo(5f); verticalLineToRelative(-7f); horizontalLineTo(3f)
            verticalLineToRelative(7f); curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineToRelative(-7f); horizontalLineToRelative(-2f); close()
            moveTo(13f, 12.67f); lineToRelative(2.59f, -2.58f); lineTo(17f, 11.5f); lineToRelative(-5f, 5f); lineToRelative(-5f, -5f)
            lineToRelative(1.41f, -1.41f); lineTo(11f, 12.67f); verticalLineTo(3f); horizontalLineToRelative(2f); close()
        }
    }

    val PhotoLibrary: ImageVector by lazy {
        icon("PhotoLibrary") {
            moveTo(20.0f, 4.0f)
            verticalLineToRelative(12.0f)
            lineTo(8.0f, 16.0f)
            lineTo(8.0f, 4.0f)
            horizontalLineToRelative(12.0f)
            moveToRelative(0.0f, -2.0f)
            lineTo(8.0f, 2.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(12.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(12.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(22.0f, 4.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(11.5f, 11.67f)
            lineToRelative(1.69f, 2.26f)
            lineToRelative(2.48f, -3.1f)
            lineTo(19.0f, 15.0f)
            lineTo(9.0f, 15.0f)
            close()
            moveTo(2.0f, 6.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            verticalLineToRelative(-2.0f)
            lineTo(4.0f, 20.0f)
            lineTo(4.0f, 6.0f)
            lineTo(2.0f, 6.0f)
            close()
        }
    }

    val Visibility: ImageVector by lazy {
        pathIcon("Visibility", "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5z" +
            "M12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z")
    }

    val VisibilityOff: ImageVector by lazy {
        pathIcon("VisibilityOff", "M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75" +
            "-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46" +
            "C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27z" +
            "M7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53" +
            "-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z")
    }
}

/** An icon from Material Icons' SVG path data. */
private fun pathIcon(name: String, pathData: String): ImageVector = ImageVector.Builder(
    name = "Alba.$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()

private fun icon(name: String, pathBuilder: PathBuilder.() -> Unit): ImageVector = ImageVector.Builder(
    name = "Alba.$name",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = SolidColor(Color.Black),
        stroke = null,
        strokeLineWidth = 0f,
        strokeLineCap = StrokeCap.Butt,
        strokeLineJoin = StrokeJoin.Bevel,
        strokeLineMiter = 4f,
        pathFillType = PathFillType.NonZero,
        pathBuilder = pathBuilder,
    )
}.build()

/** Favourites are a heart everywhere: filled in the favourite red when set, outlined in the content colour when not. */
@Composable
internal fun FavoriteIcon(favorite: Boolean, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = if (favorite) AlbaTheme.colors.favorite else LocalContentColor.current) {
    Icon(if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, contentDescription, modifier, tint)
}

@Composable
internal fun ICloudIcon(@DrawableRes resource: Int) {
    Icon(painterResource(resource), contentDescription = null, tint = AlbaTheme.colors.accent, modifier = Modifier.size(24.dp))
}
