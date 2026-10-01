package dev.mela.app.ui

// Material Icons path data is licensed under Apache-2.0; see THIRD_PARTY_NOTICES.md.

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object MelaIcons {
    val StarOutline: ImageVector by lazy {
        icon("StarOutline") {
            moveTo(22f, 9.24f)
            lineTo(14.81f, 8.62f)
            lineTo(12f, 2f)
            lineTo(9.19f, 8.63f)
            lineTo(2f, 9.24f)
            lineTo(7.46f, 13.97f)
            lineTo(5.82f, 21f)
            lineTo(12f, 17.27f)
            lineTo(18.18f, 21f)
            lineTo(16.55f, 13.97f)
            close()
            moveTo(12f, 15.4f)
            lineTo(8.24f, 17.67f)
            lineTo(9.24f, 13.39f)
            lineTo(5.92f, 10.51f)
            lineTo(10.3f, 10.13f)
            lineTo(12f, 6.1f)
            lineTo(13.71f, 10.14f)
            lineTo(18.09f, 10.52f)
            lineTo(14.77f, 13.4f)
            lineTo(15.77f, 17.68f)
            close()
        }
    }

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
}

private fun icon(name: String, pathBuilder: PathBuilder.() -> Unit): ImageVector = ImageVector.Builder(
    name = "Mela.$name",
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
