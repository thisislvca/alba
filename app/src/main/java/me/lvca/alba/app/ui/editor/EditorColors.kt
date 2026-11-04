package me.lvca.alba.app.ui.editor

import androidx.compose.ui.graphics.Color

/**
 * The editor is always dark, like Google Photos': a black canvas with white ink, whatever the app theme. These are
 * the colours it draws itself; Material components inside it read the dark [me.lvca.alba.app.ui.designsystem.theme.AlbaTheme].
 */
internal object EditorColors {
    val canvas = Color.Black
    val onCanvas = Color.White

    /** Dims the part of the photo outside the crop. */
    val cropShade = Color.Black.copy(alpha = .58f)

    /** The crop frame and its corner handles. */
    val cropFrame = Color.White

    /** Behind a label drawn on the photo, such as "Original" while comparing. */
    val labelScrim = Color.Black.copy(alpha = .6f)
    val onLabel = Color.White

    /** The zero tick on a centred slider. */
    val sliderZero = Color.White
}
